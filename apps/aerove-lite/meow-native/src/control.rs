use meow_api::routes::AppState;
use meow_common::{AdapterType, Proxy, TunnelMode};
use serde::Serialize;
use std::{collections::BTreeMap, sync::Arc};
use crate::providers::{ControlPlane, PlaneState};
use tokio_util::sync::CancellationToken;

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct RouteSnapshot {
    #[serde(rename = "core-epoch")]
    pub core_epoch: String,
    #[serde(rename = "route-version")]
    pub route_version: String,
    pub picks: BTreeMap<String, String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ControlError { Cancelled, NotFound, InvalidSelection, RevisionExhausted, InvalidEpoch, SelectionMemberNotIntegrated, DialerProxyNotIntegrated, Superseded, ProviderBusy, DelayHistoryIncomplete }

const MAX_DELAY_HISTORY_BYTES: usize = 8 * 1024 * 1024;
const MAX_DELAY_HISTORY_ENTRIES: usize = 65536;
const MAX_DELAY_URL_BYTES: usize = 16384;

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct LatestDelay { pub name: String, pub url: String, pub value: i32 }

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct DelayHistorySnapshot {
    #[serde(rename = "core-epoch")] pub core_epoch: String,
    #[serde(rename = "route-version")] pub route_version: String,
    #[serde(rename = "history-revision")] pub history_revision: String,
    pub complete: bool,
    pub delays: Vec<LatestDelay>,
}

enum DelayGraphProof {
    Managed(ManagedGraph),
    Core(Arc<meow_common::adapter::UrlDelayReceipt>),
}
impl DelayGraphProof {
    fn proxies(&self) -> &[Arc<dyn Proxy>] {
        match self { Self::Managed(graph) => &graph.members, Self::Core(receipt) => &receipt.proxies }
    }
    fn is_current(&self) -> bool {
        match self { Self::Managed(graph) => graph.is_current(), Self::Core(receipt) => receipt.is_current() }
    }
    fn matches(&self, current: &ManagedGraph) -> bool {
        self.is_current() && current.is_current() && self.proxies().len() == current.len() &&
            self.proxies().iter().all(|before| current.iter().any(|after| Arc::ptr_eq(before, after)))
    }
}
pub(crate) struct DelayHistoryRecord {
    epoch: u64, target: Arc<dyn Proxy>, url: String, delay: u16, proof: DelayGraphProof, bytes: usize,
}



pub struct RouteControl {
    pub(crate) state: Arc<AppState>,
    pub plane: Arc<ControlPlane>,
    groups: parking_lot::Mutex<Option<Arc<meow_config::owned_load::OwnedLoadScope>>>,
    observed_members: parking_lot::Mutex<BTreeMap<usize, std::sync::Weak<dyn Proxy>>>,
    groups_changed: tokio::sync::Notify,
    groups_events: tokio::sync::broadcast::Sender<(u64, u64)>,
}

impl RouteControl {
    pub fn new(state: Arc<AppState>, epoch: u64, cancel: CancellationToken)
        -> Result<Arc<Self>, ControlError> {
        if epoch == 0 { return Err(ControlError::InvalidEpoch); }
        let plane = ControlPlane::new(epoch, cancel).map_err(|_| ControlError::InvalidEpoch)?;
        Ok(Self::with_plane(state, plane))
    }

    pub fn with_plane(state: Arc<AppState>, plane: Arc<ControlPlane>) -> Arc<Self> {
        let (groups_events, _) = tokio::sync::broadcast::channel(128);
        Arc::new(Self { state, plane, groups: parking_lot::Mutex::new(None),
            observed_members: parking_lot::Mutex::new(BTreeMap::new()),
            groups_changed: tokio::sync::Notify::new(), groups_events })
    }

    pub fn cancel(&self) {
        self.plane.cancel();
    }

    fn check_live(&self, gate: &PlaneState) -> Result<(), ControlError> {
        if gate.closed || self.plane.cancel.is_cancelled() { Err(ControlError::Cancelled) } else { Ok(()) }
    }

    // Capture outside the plane lock: the Native ProbeSource may take that
    // same lock to validate its immutable network/policy pair. Bind failures
    // as well as successful RTTs to this pre-admission operation epoch.
    async fn captured_probe_epoch(&self) -> Result<Option<u64>, ControlError> {
        match meow_common::probe_policy::capture() {
            Ok(Some(snapshot)) => Ok(snapshot.scope(async { meow_common::probe_policy::operation_epoch() }).await),
            Ok(None) => Ok(None),
            Err(_) if self.plane.cancel.is_cancelled() => Err(ControlError::Cancelled),
            Err(_) => Err(ControlError::Superseded),
        }
    }

    fn check_graph_idle(&self, gate: &PlaneState) -> Result<(), ControlError> {
        if !gate.busy.is_empty() || !gate.emitting.is_empty() { Err(ControlError::ProviderBusy) } else { Ok(()) }
    }

    fn snapshot_locked(&self, gate: &PlaneState) -> RouteSnapshot {
        let route = self.state.tunnel.route_snapshot();
        RouteSnapshot {
            core_epoch: gate.core_epoch.to_string(), route_version: gate.revision.to_string(),
            picks: route.proxies.iter().filter_map(|(name, proxy)| {
                proxy.current().map(|selected| (name.to_string(), selected))
            }).collect(),
        }
    }

    pub(crate) fn stamp(&self) -> Result<(u64, u64), ControlError> {
        let gate = self.plane.gate.lock();
        self.check_live(&gate)?;
        self.check_graph_idle(&gate)?;
        Ok((gate.core_epoch, gate.revision))
    }

    pub async fn snapshot(&self) -> Result<RouteSnapshot, ControlError> {
        let gate = self.plane.gate.lock();
        self.check_live(&gate)?;
        Ok(self.snapshot_locked(&gate))
    }

    pub async fn set_mode(&self, mode: TunnelMode) -> Result<RouteSnapshot, ControlError> {
        let (snapshot, changed) = {
            let mut gate = self.plane.gate.lock();
            self.check_live(&gate)?;
            let changed = self.state.tunnel.mode() != mode;
            if changed {
                let next = gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
                let sequence = gate.groups_sequence.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
                self.state.tunnel.set_mode(mode);
                self.state.raw_config.write().mode = Some(mode.to_string());
                gate.revision = next; gate.groups_sequence = sequence;
            }
            (self.snapshot_locked(&gate), changed)
        };
        if changed { self.groups_changed.notify_one(); }
        Ok(snapshot)
    }

    fn validate_prepare_members(proxy: &Arc<dyn Proxy>, name: &str) -> Result<(), ControlError> {
        let members = proxy.member_proxies().ok_or(ControlError::InvalidSelection)?;
        if members.iter().any(|member| {
            !matches!(member.adapter_type(), AdapterType::Direct | AdapterType::Reject)
                || member.members().is_some() || member.selection().is_some()
        }) { return Err(ControlError::SelectionMemberNotIntegrated); }
        if !members.iter().any(|member| member.name() == name) {
            return Err(ControlError::InvalidSelection);
        }
        Ok(())
    }
    pub async fn select(&self, group: &str, name: &str) -> Result<RouteSnapshot, ControlError> {
        let owned_scope = self.groups.lock().clone();
        if let Some(scope) = owned_scope { return self.select_owned(group, name, &scope).await; }
        { self.check_live(&self.plane.gate.lock())?; }
        let route = self.state.tunnel.route_snapshot();
        let proxy = route.proxies.get(group).ok_or(ControlError::NotFound)?;
        Self::validate_prepare_members(proxy, name)?;
        let selection = proxy.selection().ok_or(ControlError::InvalidSelection)?;
        let health = tokio::select! {
            biased;
            _ = self.plane.cancel.cancelled() => return Err(ControlError::Cancelled),
            result = selection.prepare(name) => result.map_err(|_| ControlError::InvalidSelection)?,
        };
        let mut gate = self.plane.gate.lock();
        self.check_live(&gate)?;
        let live = self.state.tunnel.route_snapshot();
        if !live.proxies.get(group).is_some_and(|current| Arc::ptr_eq(current, proxy)) {
            return Err(ControlError::InvalidSelection);
        }
        Self::validate_prepare_members(proxy, name)?;
        let next = gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
        selection.commit(name, health).map_err(|_| ControlError::InvalidSelection)?;
        gate.revision = next;
        Ok(self.snapshot_locked(&gate))
    }
}

#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct ModeRequest { mode: TunnelMode }

#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct SelectRequest { name: String }

#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct DelayHistoryQuery { #[serde(rename = "coreEpoch")] core_epoch: Option<String> }

impl ControlError {
    pub(crate) fn response(self) -> axum::response::Response {
        use axum::response::IntoResponse;
        let status = match self {
            Self::Cancelled => axum::http::StatusCode::GONE,
            Self::NotFound => axum::http::StatusCode::NOT_FOUND,
            Self::InvalidSelection | Self::InvalidEpoch => axum::http::StatusCode::BAD_REQUEST,
            Self::RevisionExhausted | Self::Superseded | Self::ProviderBusy => axum::http::StatusCode::CONFLICT,
            Self::SelectionMemberNotIntegrated | Self::DialerProxyNotIntegrated => axum::http::StatusCode::UNPROCESSABLE_ENTITY,
            Self::DelayHistoryIncomplete => axum::http::StatusCode::CONFLICT,
        };
        let body = if self == Self::DelayHistoryIncomplete {
            serde_json::json!({"error":"delayHistoryIncomplete", "field":"delay-history",
                "reason":"A complete current-owner URL/proxy/graph history is unavailable; no partial snapshot was returned"})
        } else if self == Self::ProviderBusy {
            serde_json::json!({"error":"providerBusy"})
        } else if self == Self::SelectionMemberNotIntegrated {
            serde_json::json!({"error": "selectionMemberNotIntegrated", "field": "proxy-groups.proxies",
                "reason": "managed graph requires integrated activation, member observers and selection transactions"})
        } else if self == Self::DialerProxyNotIntegrated {
            serde_json::json!({"error": "managedDialerProxyGraphNotIntegrated", "field": "proxies.dialer-proxy",
                "reason": "injected dialer edges require managed graph activation and health/selection ownership"})
        } else { serde_json::json!({"error": format!("{self:?}")}) };
        (status, axum::Json(body)).into_response()
    }
}

pub fn router(control: Arc<RouteControl>) -> axum::Router {
    use axum::{extract::{Path, State, Query}, routing::{get, patch, put}, Json};
    axum::Router::new()
        .route("/aerove/telemetry", get(provider_telemetry))
        .route("/aerove/route", get(|State(control): State<Arc<RouteControl>>| async move {
            control.snapshot().await.map(Json).map_err(ControlError::response)
        }))
        .route("/aerove/delay-history", get(|State(control): State<Arc<RouteControl>>, Query(query): Query<DelayHistoryQuery>| async move {
            control.delay_history(query.core_epoch.as_deref()).map(Json).map_err(ControlError::response)
        }))
        .route("/aerove/mode", patch(|State(control): State<Arc<RouteControl>>, Json(body): Json<ModeRequest>| async move {
            control.set_mode(body.mode).await.map(Json).map_err(ControlError::response)
        }))
        .route("/aerove/selection/{group}", put(|State(control): State<Arc<RouteControl>>, Path(group): Path<String>, Json(body): Json<SelectRequest>| async move {
            control.select(&group, &body.name).await.map(Json).map_err(ControlError::response)
        }))
        .with_state(control)
}

#[derive(serde::Deserialize)]
struct OfficialTrafficSample {
    up: i64, down: i64,
    #[serde(rename="upTotal")] upload_total: i64,
    #[serde(rename="downTotal")] download_total: i64,
}
fn telemetry_traffic_frame(source:&str)->Result<String, &'static str>{
    let sample:OfficialTrafficSample=serde_json::from_str(source).map_err(|_|"trafficSampleInvalid")?;
    if [sample.up,sample.down,sample.upload_total,sample.download_total].iter().any(|value|*value<0){
        return Err("trafficSampleNegative");
    }
    Ok(serde_json::json!({"type":"traffic","up":sample.up,"down":sample.down,
        "uploadTotal":sample.upload_total,"downloadTotal":sample.download_total}).to_string())
}

#[derive(Default, serde::Deserialize)]
struct TelemetryQuery {
    #[serde(default)] observations: bool,
}

async fn provider_telemetry(
    axum::extract::State(control): axum::extract::State<Arc<RouteControl>>,
    axum::Extension(scope): axum::Extension<meow_api::lifecycle::ApiLifecycle>,
    axum::extract::Query(query): axum::extract::Query<TelemetryQuery>,
    upgrade: axum::extract::WebSocketUpgrade,
) -> axum::response::Response {
    scope.on_upgrade(upgrade, move |mut socket| async move {
        use axum::extract::ws::Message;
        let mut events = control.plane.subscribe();
        let mut seen = std::collections::BTreeMap::new();
        let mut groups = control.groups_events.subscribe();
        let mut traffic = meow_api::routes::subscribe_traffic_feed(&control.state);
        // This receiver owns the shared sampler used by request-traffic coalescing.
        let mut observations = if query.observations {
            match control.state.tunnel.statistics().observation_feed().activate() {
                Ok(delivery) => Some(delivery),
                Err(error) => { tracing::warn!(%error,"Core observation activation failed"); return; }
            }
        } else { None };
        if let Some(delivery) = observations.as_ref() {
            // Activation captures the watermark atomically before later live frames.
            if socket.send(Message::Text(delivery.activation_frame().into())).await.is_err() { return; }
        }
        for event in control.plane.provider_baseline() {
            seen.insert((event.kind, event.name.clone()), event.revision.parse::<u64>().expect("canonical provider revision"));
            let frame = serde_json::json!({"type":"providersLoaded","providers":[event]}).to_string();
            if socket.send(Message::Text(frame.into())).await.is_err() { return; }
        }
        loop {
            let keep_open = tokio::select! { biased;
                _ = control.plane.cancel.cancelled() => return,
                keep_open = async {
                    tokio::select! {
                        observed = async {
                            match observations.as_mut() {
                                Some(delivery) => delivery.next_frame().await,
                                None => std::future::pending().await,
                            }
                        } => {
                            let frame = match observed {
                                Ok(frame) => frame,
                                Err(error) => { tracing::warn!(%error,"Core observation delivery ended"); return false; }
                            };
                            if socket.send(Message::Text(frame.into())).await.is_err() { return false; }
                        }
                        sampled = traffic.recv() => {
                            let sampled = match sampled {
                                Ok(sampled) => sampled,
                                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => return true,
                                Err(tokio::sync::broadcast::error::RecvError::Closed) => return false,
                            };
                            let frame = match telemetry_traffic_frame(&sampled) {
                                Ok(frame) => frame,
                                Err(error) => { tracing::warn!(error,"invalid Core traffic sample"); return false; }
                            };
                            if socket.send(Message::Text(frame.into())).await.is_err() { return false; }
                        }
                        incoming = socket.recv() => if incoming.is_none() || incoming.is_some_and(|message| message.is_err() || matches!(message, Ok(Message::Close(_)))) { return false; },
                        group = groups.recv() => {
                            let Ok((revision, route_version)) = group else { return false; };
                            let frame = serde_json::json!({"type":"groupsChanged","revision":revision,"routeVersion":route_version}).to_string();
                            if socket.send(Message::Text(frame.into())).await.is_err() { return false; }
                        }
                        event = events.recv() => {
                            let Ok(event) = event else { return false; };
                            let revision = event.revision.parse::<u64>().expect("canonical provider revision");
                            let key = (event.kind, event.name.clone());
                            if seen.get(&key).is_some_and(|previous| *previous >= revision) { return true; }
                            seen.insert(key, revision);
                            let frame = serde_json::json!({"type":"providersLoaded","providers":[event]}).to_string();
                            if socket.send(Message::Text(frame.into())).await.is_err() { return false; }
                        }
                    }
                    true
                } => keep_open,
            };
            if !keep_open { return; }
        }
    })
}

// The reservation prevents provider admission from racing the gate-to-send
// handoff. Broadcast may synchronously call arbitrary receiver wakers, so its
// send and this guard's notification must execute outside the plane gate.
struct GroupEmission<'a>(&'a RouteControl);
impl Drop for GroupEmission<'_> {
    fn drop(&mut self) {
        self.0.plane.gate.lock().groups_emitting = false;
        self.0.groups_changed.notify_one();
    }
}

#[derive(Clone)]
struct ManagedGraph { members:Vec<Arc<dyn Proxy>>, dependencies:Vec<meow_config::ManagedProbeDependencies> }
impl std::ops::Deref for ManagedGraph { type Target=Vec<Arc<dyn Proxy>>;fn deref(&self)->&Self::Target { &self.members } }
impl std::ops::DerefMut for ManagedGraph { fn deref_mut(&mut self)->&mut Self::Target { &mut self.members } }
impl<'a> IntoIterator for &'a ManagedGraph { type Item=&'a Arc<dyn Proxy>;type IntoIter=std::slice::Iter<'a,Arc<dyn Proxy>>;fn into_iter(self)->Self::IntoIter { self.members.iter() } }
impl ManagedGraph { fn is_current(&self)->bool { self.dependencies.iter().all(|proof|proof.is_current()) } }

/// One caller-owned routed diagnostic, retaining the original publication guards.
pub(crate) struct RoutedProbeProof {
    pub stamp: (u64, u64),
    route: Arc<meow_tunnel::tunnel::RouteTable>,
    graph: ManagedGraph,
}

impl RouteControl {
    pub(crate) fn routed_scope(&self) -> Result<Arc<meow_config::owned_load::OwnedLoadScope>, ControlError> {
        { let gate = self.plane.gate.lock(); self.check_live(&gate)?; }
        let scope = self.groups.lock().clone().ok_or(ControlError::SelectionMemberNotIntegrated)?;
        if scope.is_cancelled() { return Err(ControlError::Cancelled); }
        Ok(scope)
    }

    pub(crate) fn capture_routed_probe(&self, expected: u64) -> Result<RoutedProbeProof, ControlError> {
        let (stamp, route) = {
            let gate = self.plane.gate.lock();
            self.check_live(&gate)?;
            self.check_graph_idle(&gate)?;
            if gate.core_epoch != expected || meow_common::probe_policy::operation_epoch().is_some_and(|epoch| epoch != expected) {
                return Err(ControlError::Superseded);
            }
            ((gate.core_epoch, gate.revision), self.state.tunnel.route_snapshot())
        };
        // Build outside the gate so even a partially rejected dependency graph
        // releases its final publication guards without holding the plane lock.
        let proof = RoutedProbeProof { stamp, route, graph: self.group_members()? };
        self.readback_routed_probe(&proof)?;
        Ok(proof)
    }

    pub(crate) fn readback_routed_probe(&self, proof: &RoutedProbeProof) -> Result<(), ControlError> {
        let gate = self.plane.gate.lock();
        self.check_live(&gate)?;
        self.check_graph_idle(&gate)?;
        if (gate.core_epoch, gate.revision) != proof.stamp || !proof.graph.is_current()
            || !Arc::ptr_eq(&proof.route, &self.state.tunnel.route_snapshot())
            || meow_common::probe_policy::operation_epoch().is_some_and(|epoch| epoch != proof.stamp.0) {
            return Err(ControlError::Superseded);
        }
        Ok(())
    }

    pub(crate) fn check_routed_target(&self, proof: &RoutedProbeProof,
        route: &Arc<meow_tunnel::tunnel::RouteTable>) -> Result<(), ControlError> {
        self.readback_routed_probe(proof)?;
        if !Arc::ptr_eq(&proof.route, route) { return Err(ControlError::Superseded); }
        Ok(())
    }

    pub(crate) fn routed_proxy(&self, name: &str) -> Result<Arc<dyn Proxy>, ControlError> {
        // Provider leaves are not necessarily keys of RouteTable.proxies.
        let proof = self.capture_routed_probe(self.stamp()?.0)?;
        let mut matches = proof.graph.iter().filter(|proxy| proxy.name() == name);
        let target = matches.next().cloned().ok_or(ControlError::NotFound)?;
        if matches.any(|other| !Arc::ptr_eq(other, &target)) { return Err(ControlError::InvalidSelection); }
        Ok(target)
    }
}

impl RouteControl {
    fn record_delay_locked(gate: &mut PlaneState, target: &Arc<dyn Proxy>, url: &str, delay: u16,
        proof: DelayGraphProof, retired: &mut Vec<DelayHistoryRecord>) {
        let bytes = url.len().saturating_mul(2).saturating_add(target.name().len()).saturating_add(256)
            .saturating_add(proof.proxies().len().saturating_mul(96));
        let entry = DelayHistoryRecord { epoch: gate.core_epoch, target: target.clone(), url: url.to_owned(), delay, proof, bytes };
        if url.is_empty() || url.len() > MAX_DELAY_URL_BYTES || !entry.proof.is_current() {
            gate.delay_history_error = Some("delayHistoryProofUnavailable"); retired.push(entry); return;
        }
        let key = (Arc::as_ptr(target).cast::<()>() as usize, url.to_owned());
        let before = gate.delay_history.get(&key).map_or(0, |entry| entry.bytes);
        let total = gate.delay_history_bytes.saturating_sub(before).saturating_add(bytes);
        if total > MAX_DELAY_HISTORY_BYTES || (!gate.delay_history.contains_key(&key) && gate.delay_history.len() >= MAX_DELAY_HISTORY_ENTRIES) {
            gate.delay_history_error = Some("delayHistoryBudgetExceeded"); retired.push(entry); return;
        }
        let Some(revision) = gate.delay_history_revision.checked_add(1) else {
            gate.delay_history_error = Some("delayHistoryRevisionExhausted"); retired.push(entry); return;
        };
        if let Some(previous) = gate.delay_history.insert(key, entry) { retired.push(previous); }
        gate.delay_history_bytes = total; gate.delay_history_revision = revision;
    }

    fn commit_delay_locked(gate: &mut PlaneState, target: &Arc<dyn Proxy>, url: &str, delay: u16,
        graph: &ManagedGraph, retired: &mut Vec<DelayHistoryRecord>) {
        target.health().apply_committed(meow_common::adapter::HealthUpdate::Delay(delay));
        Self::record_delay_locked(gate, target, url, delay, DelayGraphProof::Managed(graph.clone()), retired);
    }

    pub fn delay_history(&self, expected: Option<&str>) -> Result<DelayHistorySnapshot, ControlError> {
        let expected = expected.and_then(|value| value.parse::<u64>().ok().filter(|epoch| *epoch > 0 && epoch.to_string() == value))
            .ok_or(ControlError::InvalidEpoch)?;
        let mut retired = Vec::new();
        let result = (|| {
            let mut gate = self.plane.gate.lock(); self.check_live(&gate)?; self.check_graph_idle(&gate)?;
            if expected != gate.core_epoch { return Err(ControlError::Superseded); }
            if self.groups.lock().as_ref().is_none_or(|scope| scope.is_cancelled()) { return Err(ControlError::SelectionMemberNotIntegrated); }
            if gate.delay_history_error.is_some() { return Err(ControlError::DelayHistoryIncomplete); }
            let route = self.state.tunnel.route_snapshot();
            let mut eligible = BTreeMap::new(); let mut names = BTreeMap::new();
            for group in route.proxies.values() {
                if let Some(members) = group.member_proxies() {
                    for member in members {
                        if member.member_proxies().is_some() { continue; }
                        let key = Arc::as_ptr(&member).cast::<()>() as usize;
                        if names.insert(member.name().to_owned(), key).is_some_and(|prior| prior != key) {
                            return Err(ControlError::DelayHistoryIncomplete);
                        }
                        eligible.insert(key, member);
                    }
                }
            }
            let mut current = BTreeMap::new();
            for (key, proxy) in &eligible { current.insert(*key, Self::probe_graph(proxy.clone())?); }
            let invalid: Vec<_> = gate.delay_history.iter().filter_map(|(key, entry)| {
                let valid = entry.epoch == gate.core_epoch && current.get(&key.0).is_some_and(|graph| entry.proof.matches(graph));
                (!valid).then(|| key.clone())
            }).collect();
            if !invalid.is_empty() {
                gate.delay_history_revision = gate.delay_history_revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
                for key in invalid {
                    if let Some(entry) = gate.delay_history.remove(&key) {
                        gate.delay_history_bytes = gate.delay_history_bytes.saturating_sub(entry.bytes); retired.push(entry);
                    }
                }
            }
            let mut delays: Vec<_> = gate.delay_history.values().map(|entry| LatestDelay { name: entry.target.name().to_owned(),
                url: entry.url.clone(), value: if entry.delay == 0 { -2 } else { i32::from(entry.delay) } }).collect();
            delays.sort_by(|a,b| (&a.name,&a.url).cmp(&(&b.name,&b.url)));
            Ok(DelayHistorySnapshot { core_epoch: gate.core_epoch.to_string(), route_version: gate.revision.to_string(),
                history_revision: gate.delay_history_revision.to_string(), complete: true, delays })
        })();
        drop(retired);
        result
    }
}

pub(crate) struct ProviderProjectionGraph(ManagedGraph);
impl ProviderProjectionGraph { pub(crate) fn is_current(&self)->bool { self.0.is_current() } }

pub(crate) struct ProviderGroupCommit {
    retained: ManagedGraph, _candidate: ManagedGraph, picks: BTreeMap<String, String>, fixed: Vec<Option<String>>,
}

struct GroupHealthObserver(std::sync::Weak<RouteControl>);
impl meow_common::adapter::HealthObserver for GroupHealthObserver {
    fn begin_url_probe(&self, proxy: &Arc<dyn Proxy>) -> std::io::Result<Option<meow_common::adapter::UrlProbeStamp>> {
        let control = self.0.upgrade().ok_or_else(|| std::io::Error::other("group owner retired"))?;
        let scope = control.groups.lock().clone();
        if scope.as_ref().is_none_or(|scope| scope.is_cancelled()) { return Err(std::io::Error::other("group owner cancelled")); }
        let epoch = meow_common::probe_policy::operation_epoch();
        let mut gate = control.plane.gate.lock();
        control.check_live(&gate).and_then(|_| control.check_graph_idle(&gate))
            .map_err(|_| std::io::Error::other("URL probe owner unavailable"))?;
        if epoch.is_some_and(|epoch| epoch != gate.core_epoch) { return Err(std::io::Error::other("URL probe epoch changed")); }
        let members = control.group_members().map_err(|_| std::io::Error::other("URL probe graph unavailable"))?;
        if !members.iter().any(|current| Arc::ptr_eq(current, proxy)) { return Err(std::io::Error::other("URL probe target retired")); }
        let ticket = gate.selection_serial.checked_add(1).ok_or_else(|| std::io::Error::other("URL probe intent exhausted"))?;
        gate.selection_serial = ticket;
        gate.delay_intents.insert(proxy.health() as *const _ as usize, ticket);
        Ok(Some(meow_common::adapter::UrlProbeStamp { epoch: gate.core_epoch, ticket }))
    }

    fn commit(&self, health: &meow_common::ProxyHealth, update: meow_common::adapter::HealthUpdate) {
        let Some(control) = self.0.upgrade() else { return; };
        let scope = control.groups.lock().clone();
        if scope.as_ref().map_or(true, |scope| scope.is_cancelled()) { return; }
        let epoch = meow_common::probe_policy::operation_epoch();
        let mut retired_delays = Vec::new();
        let mut gate = control.plane.gate.lock();
        if control.check_live(&gate).is_err() || epoch.is_some_and(|epoch| epoch != gate.core_epoch) { return; }
        let Ok(proxies) = control.group_members() else { return; };
        if !proxies.iter().any(|proxy| std::ptr::eq(proxy.health(), health)) { return; }
        let url_record = match &update {
            meow_common::adapter::HealthUpdate::UrlDelay { proxy, url, delay, receipt } => {
                let Some(target) = proxy.upgrade() else { return; };
                if !std::ptr::eq(target.health(), health) || !proxies.iter().any(|current| Arc::ptr_eq(current, &target)) { return; }
                match receipt {
                    Some(receipt) => {
                        let Some(admission) = receipt.admission else { gate.delay_history_error = Some("unownedUrlProbe"); return; };
                        if admission.epoch != gate.core_epoch || gate.delay_intents.get(&(health as *const _ as usize)) != Some(&admission.ticket) { return; }
                        if !Arc::ptr_eq(&target, &receipt.proxy) { return; }
                        let Ok(current) = RouteControl::probe_graph(target.clone()) else { return; };
                        let proof = DelayGraphProof::Core(receipt.clone());
                        if !proof.matches(&current) { return; }
                        Some((target, url.clone(), *delay, proof))
                    }
                    None => { gate.delay_history_error = Some("urlProbeProofUnavailable"); None }
                }
            }
            meow_common::adapter::HealthUpdate::Delay(_) => { gate.delay_history_error = Some("unattributedDelay"); None }
            meow_common::adapter::HealthUpdate::Alive(_) => None,
        };
        let Some(revision) = gate.revision.checked_add(1) else { return; };
        let Some(sequence) = gate.groups_sequence.checked_add(1) else { return; };
        let before = control.snapshot_locked(&gate).picks;
        let fixed_before: Vec<_> = proxies.iter().map(|proxy| proxy.selection().and_then(|selection| selection.fixed())).collect();
        health.apply_committed(update);
        if let Some((target, url, delay, proof)) = url_record {
            RouteControl::record_delay_locked(&mut gate, &target, &url, delay, proof, &mut retired_delays);
        }
        for proxy in &proxies {
            if let Some(selection) = proxy.selection() { selection.reconcile_health(); }
        }
        let fixed_after: Vec<_> = proxies.iter().map(|proxy| proxy.selection().and_then(|selection| selection.fixed())).collect();
        if control.snapshot_locked(&gate).picks != before || fixed_before != fixed_after { gate.revision = revision; }
        gate.groups_sequence = sequence;
        drop(gate);
        drop(retired_delays);
        control.groups_changed.notify_one();
    }
}

impl RouteControl {
    pub fn subscribe_groups(&self) -> tokio::sync::broadcast::Receiver<(u64, u64)> { self.groups_events.subscribe() }
    fn collect_members(roots: Vec<Arc<dyn Proxy>>) -> Result<ManagedGraph, ControlError> { Self::collect_graph(roots,true,None) }
    fn collect_graph(roots: Vec<Arc<dyn Proxy>>, published:bool, projection:Option<&meow_config::ProxyProviderProjection>) -> Result<ManagedGraph, ControlError> {
        fn visit(proxy: Arc<dyn Proxy>, depth: usize, visiting: &mut std::collections::BTreeSet<usize>,
            seen: &mut std::collections::BTreeSet<usize>, output: &mut ManagedGraph, published:bool, projection:Option<&meow_config::ProxyProviderProjection>)
            -> Result<(), ControlError> {
            let id = Arc::as_ptr(&proxy).cast::<()>() as usize;
            if visiting.contains(&id) || depth > 64 { return Err(ControlError::SelectionMemberNotIntegrated); }
            if !seen.insert(id) { return Ok(()); }
            let members = match projection {
                Some(projection)=>meow_config::managed_probe_members_projected(&proxy,projection).map_err(|_|ControlError::SelectionMemberNotIntegrated)?,
                None=>proxy.member_proxies(),
            };
            if members.is_some() && !matches!(proxy.adapter_type(), AdapterType::Selector | AdapterType::Fallback | AdapterType::UrlTest | AdapterType::Relay | AdapterType::LoadBalance) {
                return Err(ControlError::SelectionMemberNotIntegrated);
            }
            if members.is_none() && !matches!(proxy.adapter_type(), AdapterType::Direct | AdapterType::Reject |
                AdapterType::RejectDrop | AdapterType::Compatible | AdapterType::Pass | AdapterType::PassRule |
                AdapterType::Http | AdapterType::Socks5 | AdapterType::Shadowsocks |
                AdapterType::Trojan | AdapterType::Vmess | AdapterType::Vless | AdapterType::Snell |
                AdapterType::Hysteria2 | AdapterType::Anytls | AdapterType::TrustTunnel) {
                return Err(ControlError::SelectionMemberNotIntegrated);
            }
            visiting.insert(id);
            if let Some(members) = members {
                for member in members { visit(member, depth + 1, visiting, seen, output, published, projection)?; }
            }
            if published {
                let proof=meow_config::managed_probe_dependencies(&proxy).map_err(|_|ControlError::SelectionMemberNotIntegrated)?;
                if !proof.is_current() { return Err(ControlError::Superseded); }
                for dependency in &proof.proxies { visit(dependency.clone(),depth+1,visiting,seen,output,published,projection)?; }
                output.dependencies.push(proof);
            }
            visiting.remove(&id);
            output.push(proxy);
            Ok(())
        }
        let mut visiting = std::collections::BTreeSet::new();
        let mut seen = std::collections::BTreeSet::new();
        let mut output = ManagedGraph { members:Vec::new(),dependencies:Vec::new() };
        for proxy in roots { visit(proxy,0,&mut visiting,&mut seen,&mut output,published,projection)?; }
        Ok(output)
    }

    fn group_members(&self) -> Result<ManagedGraph, ControlError> {
        let route = self.state.tunnel.route_snapshot();
        let mut roots: Vec<_> = route.proxies.values().cloned().collect();
        for provider in self.state.proxy_providers.iter() {
            roots.extend(provider.value().proxies());
        }
        Self::collect_members(roots)
    }

    pub(crate) fn provider_projection_matches(&self, projection:&meow_config::ProxyProviderProjection)->bool {
        self.state.proxy_providers.get(&projection.identity().name).is_some_and(|provider|
            provider.provider_identity()==projection.identity() && projection.matches_provider_slot(&provider.slot))
    }
    fn projected_provider_graph(&self, projection:&meow_config::ProxyProviderProjection) -> Result<ManagedGraph,ControlError> {
        let route=self.state.tunnel.route_snapshot();
        let mut roots:Vec<_>=route.proxies.values().cloned().collect();
        for provider in self.state.proxy_providers.iter() {
            if projection.matches_provider_slot(&provider.slot) { roots.extend(projection.members().iter().cloned()); }
            else { roots.extend(provider.proxies()); }
        }
        Self::collect_graph(roots,true,Some(projection))
    }
    pub(crate) fn validate_projected_provider_graph(&self, projection:&meow_config::ProxyProviderProjection, gate:&PlaneState)
        -> Result<ProviderProjectionGraph,String> {
        self.check_live(gate).map_err(|_|"cancelled".to_owned())?;
        if self.groups.lock().as_ref().is_none_or(|scope|scope.is_cancelled()) { return Err("groupOwnerCancelled".into()); }
        let graph=self.projected_provider_graph(projection).map_err(|_|"managedProxyGraphNotIntegrated: projectedProviderGraphInvalid".to_owned())?;
        if !graph.is_current() || !projection.is_current() { return Err("staleProviderProjection".into()); }
        self.validate_member_observers(&graph)?;
        Ok(ProviderProjectionGraph(graph))
    }
    pub(crate) fn prepare_projected_provider_commit(self:&Arc<Self>, projection:&meow_config::ProxyProviderProjection, gate:&PlaneState)
        -> Result<ProviderGroupCommit,String> {
        let candidate=self.validate_projected_provider_graph(projection,gate)?.0;
        let retained=self.group_members().map_err(|_|"managedProxyGraphNotIntegrated".to_owned())?;
        if !retained.is_current() { return Err("staleManagedProxyGraph".into()); }
        let picks=self.snapshot_locked(gate).picks;
        let fixed=retained.iter().map(|proxy|proxy.selection().and_then(|selection|selection.fixed())).collect();
        self.observe_members(&candidate).map_err(|_|"providerHealthObserverConflict".to_owned())?;
        Ok(ProviderGroupCommit { retained,_candidate:candidate,picks,fixed })
    }

    pub(crate) fn validate_provider_member(member: Arc<dyn Proxy>, published: bool) -> Result<(), ControlError> {
        // Initial provider construction precedes publication. Actual private
        // edges are captured at post-publication activate_groups/hot commit.
        Self::collect_graph(vec![member],published,None).map(|_| ())
    }

    pub(crate) fn validate_member_observers(&self, members: &[Arc<dyn Proxy>]) -> Result<(), String> {
        let registered = self.observed_members.lock();
        for member in members {
            let key = member.health() as *const _ as usize;
            if member.health().observed() && !registered.get(&key).and_then(std::sync::Weak::upgrade)
                .is_some_and(|prior| std::ptr::eq(prior.health(), member.health())) {
                return Err("providerHealthObserverConflict".into());
            }
        }
        Ok(())
    }

    fn observe_members(self: &Arc<Self>, members: &[Arc<dyn Proxy>]) -> Result<(), ControlError> {
        let mut registered = self.observed_members.lock();
        registered.retain(|_, member| member.strong_count() > 0);
        for member in members {
            let key = member.health() as *const _ as usize;
            if member.health().observed() && !registered.get(&key).and_then(std::sync::Weak::upgrade)
                .is_some_and(|prior| std::ptr::eq(prior.health(), member.health())) {
                return Err(ControlError::InvalidSelection);
            }
        }
        let observer: Arc<dyn meow_common::adapter::HealthObserver> = Arc::new(GroupHealthObserver(Arc::downgrade(self)));
        let mut installed=std::collections::BTreeSet::new();
        for member in members {
            let key = member.health() as *const _ as usize;
            if !installed.insert(key) { continue; }
            if !member.health().observed() && !member.health().observe(observer.clone()) {
                return Err(ControlError::InvalidSelection);
            }
            registered.insert(key, Arc::downgrade(member));
        }
        Ok(())
    }

    // Called with the same PlaneState gate as the provider slot installation.
    // Only leaf provider payloads are admitted, so existing group vertices are
    // exactly the groups requiring post-install selection reconciliation.
    pub(crate) fn prepare_provider_commit(self: &Arc<Self>, provider_name: &str, members: &[Arc<dyn Proxy>], gate: &PlaneState)
        -> Result<ProviderGroupCommit, String> {
        self.check_live(gate).map_err(|_| "cancelled".to_owned())?;
        if self.groups.lock().as_ref().is_none_or(|scope| scope.is_cancelled()) { return Err("groupOwnerCancelled".into()); }
        let retained = self.group_members().map_err(|_| "managedProxyGraphNotIntegrated".to_owned())?;
        let picks = self.snapshot_locked(gate).picks;
        let fixed = retained.iter().map(|proxy| proxy.selection().and_then(|selection| selection.fixed())).collect();
        let candidate=Self::collect_members(members.to_vec()).map_err(|_|"managedProxyGraphNotIntegrated".to_owned())?;
        if !retained.is_current() || !candidate.is_current() { return Err("staleManagedProxyGraph".into()); }
        // A front group backed by the very slot being replaced would change
        // its member edges during install. Core02 exposes the current graph,
        // not a projected slot. Refuse that exact unproven hot dependency.
        if let Some(provider)=self.state.proxy_providers.get(provider_name) {
            let retiring=provider.proxies();
            if candidate.iter().any(|vertex|retiring.iter().any(|old|Arc::ptr_eq(vertex,old))) {
                return Err("providerDialerTouchesRetiringSlot: projected dependency graph required".into());
            }
        }
        self.observe_members(&candidate).map_err(|_|"providerHealthObserverConflict".to_owned())?;
        Ok(ProviderGroupCommit { retained,_candidate:candidate,picks,fixed })
    }

    pub(crate) fn finish_provider_commit(&self, prepared: &ProviderGroupCommit, gate: &mut PlaneState,
        revision: u64, sequence: u64) {
        for proxy in &prepared.retained {
            if let Some(selection) = proxy.selection() { selection.reconcile_health(); }
        }
        let fixed: Vec<_> = prepared.retained.iter().map(|proxy| proxy.selection().and_then(|selection| selection.fixed())).collect();
        if self.snapshot_locked(gate).picks != prepared.picks || fixed != prepared.fixed { gate.revision = revision; }
        gate.groups_sequence = sequence;
    }
    pub(crate) fn notify_provider_groups(&self) { self.groups_changed.notify_one(); }

    pub fn activate_groups(self: &Arc<Self>, scope: &Arc<meow_config::owned_load::OwnedLoadScope>)
        -> Result<tokio::task::JoinHandle<()>, ControlError> {
        let mut gate = self.plane.gate.lock(); self.check_live(&gate)?;
        if scope.is_cancelled() { return Err(ControlError::Cancelled); }
        if self.groups.lock().is_some() { return Err(ControlError::InvalidSelection); }
        if !gate.busy.is_empty() || !gate.emitting.is_empty() { return Err(ControlError::Superseded); }
        let proxies = self.group_members()?;
        if proxies.iter().any(|proxy| !proxy.delay_history().is_empty()) {
            gate.delay_history_error = Some("preActivationHistoryUnowned");
        }
        self.observe_members(&proxies)?;
        *self.groups.lock() = Some(scope.clone());
        gate.group_control = Some(Arc::downgrade(self));
        drop(gate);
        let control = self.clone();
        scope.spawn_owned(async move { control.run_group_events().await; }).map_err(|_| ControlError::Cancelled)
    }

    async fn run_group_events(&self) {
        let mut seen = 0;
        loop {
            let changed = self.groups_changed.notified();
            tokio::pin!(changed); changed.as_mut().enable();
            let delivery = {
                let mut gate = self.plane.gate.lock();
                if gate.closed || self.plane.cancel.is_cancelled() { return; }
                if gate.group_events_ready() && gate.groups_sequence > seen {
                    gate.groups_emitting = true;
                    Some((gate.groups_sequence, gate.revision))
                } else { None }
            };
            if let Some((sequence, revision)) = delivery {
                let reservation = GroupEmission(self);
                let _ = self.groups_events.send((sequence, revision));
                seen = sequence;
                drop(reservation);
            }
            tokio::select! { biased; _ = self.plane.cancel.cancelled() => return, _ = changed => {} }
        }
    }

    // Capture all identities without asking current()/alive() to choose a path.
    // Pure measurement dials the requested object using the Core's own graph;
    // the complete retained graph is compared again under the commit gate.
    fn probe_graph(member: Arc<dyn Proxy>) -> Result<ManagedGraph, ControlError> {
        let mut retained = Self::collect_members(vec![member.clone()])?;
        retained.retain(|proxy| !Arc::ptr_eq(proxy, &member));
        retained.insert(0, member);
        Ok(retained)
    }

    async fn select_owned(&self, group: &str, name: &str, scope: &Arc<meow_config::owned_load::OwnedLoadScope>)
        -> Result<RouteSnapshot, ControlError> {
        let captured_epoch = self.captured_probe_epoch().await?;
        let (_retained_route, proxy, member, path, probe, ticket, epoch, revision, keys) = {
            let mut gate = self.plane.gate.lock(); self.check_live(&gate)?; self.check_graph_idle(&gate)?;
            if captured_epoch.is_some_and(|epoch|epoch!=gate.core_epoch) { return Err(ControlError::Superseded); }
            self.group_members()?;
            let route = self.state.tunnel.route_snapshot();
            let proxy = route.proxies.get(group).ok_or(ControlError::NotFound)?.clone();
            proxy.selection().ok_or(ControlError::InvalidSelection)?;
            let member = proxy.member_proxies().ok_or(ControlError::InvalidSelection)?.into_iter()
                .find(|member| member.name() == name).ok_or(ControlError::InvalidSelection)?;
            let probe = proxy.adapter_type() == AdapterType::Fallback && !meow_common::probe_policy::DiagnosticToken::measurement().run(|| member.alive_for_url(proxy.test_url().unwrap_or("")));
            let path = Self::probe_graph(member.clone())?;
            gate.selection_serial = gate.selection_serial.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let ticket = gate.selection_serial;
            gate.selection_intents.insert(group.to_owned(), ticket);
            let keys = vec![member.health() as *const _ as usize];
            for key in &keys { gate.delay_intents.insert(*key,ticket); }
            (route, proxy, member, path, probe, ticket, gate.core_epoch, gate.revision, keys)
        };
        let selection = proxy.selection().ok_or(ControlError::InvalidSelection)?;
        let flush = selection.flush_selection();
        let reserved = if flush.is_some() {
            let (send, receive) = std::sync::mpsc::channel::<Box<dyn FnOnce() + Send>>();
            let task = scope.spawn_blocking_owned(move || { if let Ok(work) = receive.recv() { work(); } })
                .map_err(|_| ControlError::Cancelled)?;
            Some((send, task))
        } else { None };
        let delay = if probe {
            let measured = tokio::select! {
                biased;
                _ = self.plane.cancel.cancelled() => return Err(ControlError::Cancelled),
                result = meow_config::probe_without_effects_detailed(&member, proxy.test_url().unwrap_or(""),
                    proxy.expected_status().filter(|status| !status.is_empty()), std::time::Duration::from_secs(5)) => result,
            };
            if measured.as_ref().is_ok_and(|value|value.core_epoch.is_some_and(|value|value!=epoch)) { return Err(ControlError::Superseded); }
            Some(measured.map(|value|value.delay).unwrap_or(0))
        } else { None };
        let mut retired_delays = Vec::new();
        let snapshot = {
            let mut gate = self.plane.gate.lock(); self.check_live(&gate)?;
            if scope.is_cancelled() { return Err(ControlError::Cancelled); }
            if gate.core_epoch != epoch || gate.revision != revision ||
                gate.selection_intents.get(group) != Some(&ticket) ||
                keys.iter().any(|key|gate.delay_intents.get(key)!=Some(&ticket)) { return Err(ControlError::Superseded); }
            self.check_graph_idle(&gate)?;
            let all = self.group_members()?;
            let route = self.state.tunnel.route_snapshot();
            if !route.proxies.get(group).is_some_and(|live| Arc::ptr_eq(live, &proxy)) {
                return Err(ControlError::Superseded);
            }
            let live = proxy.member_proxies().ok_or(ControlError::InvalidSelection)?.into_iter()
                .find(|live| live.name() == name).ok_or(ControlError::InvalidSelection)?;
            let current_path = Self::probe_graph(live.clone())?;
            if !path.is_current() || !current_path.is_current() || !Arc::ptr_eq(&live, &member) || path.len() != current_path.len() ||
                !path.iter().zip(current_path.iter()).all(|(before, after)| Arc::ptr_eq(before, after)) {
                return Err(ControlError::Superseded);
            }
            let next = gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let sequence = gate.groups_sequence.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            selection.commit_memory(name).map_err(|_| ControlError::InvalidSelection)?;
            if let Some(delay) = delay {
                Self::commit_delay_locked(&mut gate, &member, proxy.test_url().unwrap_or(""), delay, &path, &mut retired_delays);
            }
            for member in &all {
                if let Some(selection) = member.selection() { selection.reconcile_health(); }
            }
            gate.revision = next; gate.groups_sequence = sequence;
            self.snapshot_locked(&gate)
        };
        self.groups_changed.notify_one();
        if let Some((send, task)) = reserved {
            send.send(flush.unwrap()).map_err(|_| ControlError::InvalidSelection)?;
            task.await.map_err(|_| ControlError::InvalidSelection)?;
        }
        Ok(snapshot)
    }
}

#[derive(serde::Deserialize)]
pub struct DelayQuery {
    pub url: Option<String>, pub timeout: Option<String>, pub expected: Option<String>,
    #[serde(rename = "coreEpoch")]
    pub core_epoch: Option<String>,
}

impl RouteControl {
    /// The HTTP caller owns this future. The captured factory lease joins its
    /// real I/O at close without detaching a second request task.
    pub(crate) async fn node_delay(self: &Arc<Self>, name: String, query: DelayQuery)
        -> axum::response::Response {
        use axum::response::IntoResponse;
        let scope = self.groups.lock().clone();
        let Some(scope) = scope else { return ControlError::SelectionMemberNotIntegrated.response(); };
        let control = self.clone();
        let work = Box::pin(async move { control.node_delay_captured(name, query).await });
        match scope.run_captured(work).await {
            Ok(response) => response,
            Err(_) => (axum::http::StatusCode::GONE, "cancelled").into_response(),
        }
    }

    async fn node_delay_captured(&self, name: String, query: DelayQuery) -> axum::response::Response {
        use axum::{Json, http::{HeaderValue, StatusCode}, response::IntoResponse};
        let params = meow_api::routes::DelayParams {
            url: query.url, timeout: query.timeout, expected: query.expected,
        };
        let timeout = match meow_api::routes::parse_delay_params(&params) {
            Ok(timeout) => timeout, Err(response) => return *response,
        };
        let expected_epoch = match query.core_epoch {
            Some(value) => match value.parse::<u64>() {
                Ok(epoch) if epoch != 0 && epoch.to_string() == value => Some(epoch),
                _ => return ControlError::InvalidEpoch.response(),
            },
            None => None,
        };
        let captured_epoch = match self.captured_probe_epoch().await { Ok(epoch)=>epoch, Err(error)=>return error.response() };
        let admitted = (|| {
            let mut gate = self.plane.gate.lock(); self.check_live(&gate)?; self.check_graph_idle(&gate)?;
            if expected_epoch.is_some_and(|epoch| epoch != gate.core_epoch) { return Err(ControlError::Superseded); }
            if captured_epoch.is_some_and(|epoch|epoch!=gate.core_epoch) { return Err(ControlError::Superseded); }
            self.group_members()?;
            let route = self.state.tunnel.route_snapshot();
            let (proxy, _) = meow_api::routes::find_proxy(&self.state, &route.proxies, name.as_str()).ok_or(ControlError::NotFound)?;
            let path = Self::probe_graph(proxy.clone())?;
            let keys = vec![proxy.health() as *const _ as usize];
            gate.selection_serial = gate.selection_serial.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let ticket = gate.selection_serial;
            for key in &keys { gate.delay_intents.insert(*key, ticket); }
            Ok((route, proxy, path, keys, ticket, gate.core_epoch, gate.revision))
        })();
        let (route, proxy, path, keys, ticket, epoch, revision) = match admitted {
            Ok(value) => value, Err(error) => return error.response(),
        };
        let result = tokio::select! { biased;
            _ = self.plane.cancel.cancelled() => return ControlError::Cancelled.response(),
            result = meow_config::probe_without_effects_detailed(&proxy,
                params.url.as_deref().unwrap_or(""), params.expected.as_deref(), timeout) => result,
        };
        if result.as_ref().is_ok_and(|value|value.core_epoch.is_some_and(|value|value!=epoch)) { return ControlError::Superseded.response(); }
        let mut retired_delays = Vec::new();
        let committed = (|| {
            let mut gate = self.plane.gate.lock(); self.check_live(&gate)?;
            if gate.core_epoch != epoch || gate.revision != revision ||
                keys.iter().any(|key| gate.delay_intents.get(key) != Some(&ticket)) {
                return Err(ControlError::Superseded);
            }
            self.check_graph_idle(&gate)?;
            let live = self.state.tunnel.route_snapshot();
            if !meow_api::routes::find_proxy(&self.state, &live.proxies, name.as_str()).is_some_and(|(value, _)| Arc::ptr_eq(&value, &proxy)) {
                return Err(ControlError::Superseded);
            }
            let current_path = Self::probe_graph(proxy.clone())?;
            if !path.is_current() || !current_path.is_current() || path.len() != current_path.len() || !path.iter().zip(&current_path)
                .all(|(before, after)| Arc::ptr_eq(before, after)) { return Err(ControlError::Superseded); }
            let all = self.group_members()?;
            let next = gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let sequence = gate.groups_sequence.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let before = self.snapshot_locked(&gate).picks;
            let fixed_before: Vec<_> = all.iter().map(|member| member.selection().and_then(|selection| selection.fixed())).collect();
            let delay = result.as_ref().map(|value|value.delay).unwrap_or(0);
            Self::commit_delay_locked(&mut gate, &proxy, params.url.as_deref().unwrap_or(""), delay, &path, &mut retired_delays);
            for member in &all { if let Some(selection) = member.selection() { selection.reconcile_health(); } }
            let fixed_after: Vec<_> = all.iter().map(|member| member.selection().and_then(|selection| selection.fixed())).collect();
            if self.snapshot_locked(&gate).picks != before || fixed_before != fixed_after { gate.revision = next; }
            gate.groups_sequence = sequence;
            Ok((gate.core_epoch, gate.revision))
        })();
        // Retain the route generation throughout the probe and commit, as the
        // official handler does for chained registry ownership.
        drop(route);
        let (epoch, revision) = match committed { Ok(stamp) => stamp, Err(error) => return error.response() };
        self.groups_changed.notify_one();
        let mut response = match result {
            Ok(measured) => Json(serde_json::json!({"delay":measured.delay})).into_response(),
            Err(meow_config::DelayProbeError::Timeout) =>
                (StatusCode::GATEWAY_TIMEOUT, Json(serde_json::json!({"message":"Timeout"}))).into_response(),
            Err(meow_config::DelayProbeError::Transport(_)) =>
                (StatusCode::SERVICE_UNAVAILABLE, Json(serde_json::json!({"message":"An error occurred in the delay test"}))).into_response(),
        };
        response.headers_mut().insert("x-aerove-core-epoch", HeaderValue::from_str(&epoch.to_string()).unwrap());
        response.headers_mut().insert("x-aerove-route-version", HeaderValue::from_str(&revision.to_string()).unwrap());
        response
    }
}

struct GroupDelayAdmission {
    route: Arc<meow_tunnel::tunnel::RouteTable>, group: Arc<dyn Proxy>,
    retained: ManagedGraph, paths: Vec<ManagedGraph>,
    keys: Vec<usize>, ticket: u64, epoch: u64, revision: u64,
}

impl RouteControl {
    pub async fn group_delay(self: &Arc<Self>, name: String, query: DelayQuery)
        -> axum::response::Response {
        let Some(scope) = self.groups.lock().clone() else { return ControlError::SelectionMemberNotIntegrated.response(); };
        let control = self.clone(); let captured = scope.clone();
        let work = Box::pin(async move { control.group_delay_captured(name, query, captured).await });
        match scope.run_captured(work).await { Ok(response) => response, Err(_) => ControlError::Cancelled.response() }
    }

    async fn group_delay_captured(&self, name: String, query: DelayQuery,
        scope: Arc<meow_config::owned_load::OwnedLoadScope>) -> axum::response::Response {
        use axum::{Json, http::StatusCode, response::IntoResponse};
        let params = meow_api::routes::DelayParams { url: query.url, timeout: query.timeout, expected: query.expected };
        let timeout = match meow_api::routes::parse_delay_params(&params) { Ok(timeout) => timeout, Err(response) => return *response };
        let expected_epoch = match query.core_epoch {
            Some(value) => match value.parse::<u64>() {
                Ok(epoch) if epoch != 0 && epoch.to_string() == value => Some(epoch),
                _ => return ControlError::InvalidEpoch.response(),
            }, None => None,
        };
        if meow_config::validate_delay_parameters(params.url.as_deref().unwrap_or(""), params.expected.as_deref()).is_err() {
            return (StatusCode::BAD_REQUEST, Json(serde_json::json!({"message":"Body invalid"}))).into_response();
        }
        self.group_delay_batch(name, params, timeout, expected_epoch, scope).await
    }

    async fn group_delay_batch(&self, name: String, params: meow_api::routes::DelayParams,
        timeout: std::time::Duration, expected_epoch: Option<u64>,
        scope: Arc<meow_config::owned_load::OwnedLoadScope>) -> axum::response::Response {
        let captured_epoch = match self.captured_probe_epoch().await { Ok(epoch)=>epoch, Err(error)=>return error.response() };
        let target = (|| {
            let gate = self.plane.gate.lock(); self.check_live(&gate)?; self.check_graph_idle(&gate)?;
            if expected_epoch.is_some_and(|epoch| epoch != gate.core_epoch) { return Err(ControlError::Superseded); }
            if captured_epoch.is_some_and(|epoch|epoch!=gate.core_epoch) { return Err(ControlError::Superseded); }
            self.group_members()?;
            let route = self.state.tunnel.route_snapshot();
            let group = route.proxies.get(name.as_str()).cloned().ok_or(ControlError::NotFound)?;
            group.member_proxies().ok_or(ControlError::NotFound)?;
            Ok((route, group))
        })();
        let (route, group) = match target { Ok(target) => target, Err(error) => return error.response() };
        let selection = group.selection().filter(|selection| selection.can_unfix());
        let flush = selection.and_then(|selection| selection.flush_selection());
        // Reserve the non-abortable disk half before changing memory. The
        // session owns/join tracks this worker even if the caller disappears.
        let reserved = if flush.is_some() {
            let (send, receive) = std::sync::mpsc::channel::<Box<dyn FnOnce() + Send>>();
            match scope.spawn_blocking_owned(move || { if let Ok(work) = receive.recv() { work(); } }) {
                Ok(task) => Some((send, task)), Err(_) => return ControlError::Cancelled.response(),
            }
        } else { None };
        let admitted = (|| {
            let mut gate = self.plane.gate.lock(); self.check_live(&gate)?; self.check_graph_idle(&gate)?;
            if scope.is_cancelled() { return Err(ControlError::Cancelled); }
            if expected_epoch.is_some_and(|epoch| epoch != gate.core_epoch) { return Err(ControlError::Superseded); }
            if captured_epoch.is_some_and(|epoch|epoch!=gate.core_epoch) { return Err(ControlError::Superseded); }
            let all = self.group_members()?;
            let live = self.state.tunnel.route_snapshot();
            if !live.proxies.get(name.as_str()).is_some_and(|live| Arc::ptr_eq(live, &group)) {
                return Err(ControlError::Superseded);
            }
            let members = group.member_proxies().ok_or(ControlError::NotFound)?;
            let paths: Vec<_> = members.into_iter().map(|member| Self::probe_graph(member)).collect::<Result<_,_>>()?;
            let keys: Vec<_> = paths.iter().map(|path| path[0].health() as *const _ as usize).collect();
            let ticket = gate.selection_serial.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let next = gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let sequence = gate.groups_sequence.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let unpin = selection.is_some_and(|selection| selection.fixed().is_some_and(|name| !name.is_empty()));
            if let Some(selection) = selection { selection.unfix_memory().map_err(|_| ControlError::InvalidSelection)?; }
            if unpin { gate.revision = next; gate.groups_sequence = sequence; }
            gate.selection_serial = ticket;
            // An explicit return-to-auto supersedes a pending setter even
            // when the group was already auto and no revision changed.
            gate.selection_intents.insert(name.clone(), ticket);
            for key in &keys { gate.delay_intents.insert(*key, ticket); }
            // `all` was retained through the short commit, without calling a
            // last-Arc destructor or external event sink while holding gate.
            Ok((all, paths, keys, ticket, gate.core_epoch, gate.revision, unpin))
        })();
        let (retained, paths, keys, ticket, epoch, revision, unpin) = match admitted {
            Ok(value) => value, Err(error) => return error.response(),
        };
        if unpin { self.groups_changed.notify_one(); }
        if let Some((send, task)) = reserved {
            if send.send(flush.unwrap()).is_err() { return ControlError::InvalidSelection.response(); }
            if task.await.is_err() { return ControlError::InvalidSelection.response(); }
        }
        let admission = GroupDelayAdmission { route, group, retained, paths, keys, ticket, epoch, revision };
        let work = self.group_delay_finish(name, params, timeout, scope, admission);
        match meow_common::probe_policy::capture() {
            Ok(Some(snapshot)) => match snapshot.run(async { Ok(work.await) }).await {
                Ok(response) => response, Err(_) => ControlError::Cancelled.response(),
            },
            Ok(None) => work.await,
            Err(_) => ControlError::Cancelled.response(),
        }
    }

    async fn group_delay_finish(&self, name: String, params: meow_api::routes::DelayParams,
        timeout: std::time::Duration, scope: Arc<meow_config::owned_load::OwnedLoadScope>,
        admission: GroupDelayAdmission)
        -> axum::response::Response {
        use axum::{Json, http::{HeaderValue, StatusCode}, response::IntoResponse};
        use futures::StreamExt;
        let GroupDelayAdmission { route, group, retained, paths, keys, ticket, epoch, revision } = admission;
        let deadline = tokio::time::Instant::now() + timeout;
        let completed = {
            // Each child future owns its complete target and parameters. Keep `paths`
            // as retained graphs for the identity CAS after all probes drop.
            let mut pending = Vec::with_capacity(paths.len());
            for (index, path) in paths.iter().enumerate() {
                let target = path[0].clone();
                let url = params.url.clone().unwrap_or_default();
                let expected = params.expected.clone();
                pending.push(async move {
                    let result = meow_config::probe_without_effects_detailed(&target,
                        &url, expected.as_deref(), timeout).await;
                    (index, result)
                });
            }
            let probes = futures::stream::iter(pending)
                .buffer_unordered(meow_config::GROUP_DELAY_CONCURRENCY);
            tokio::pin!(probes);
            let mut completed = Vec::new();
            loop {
                tokio::select! { biased;
                    _ = self.plane.cancel.cancelled() => return ControlError::Cancelled.response(),
                    item = tokio::time::timeout_at(deadline, probes.next()) => match item {
                        Ok(Some(result)) => completed.push(result), _ => break,
                    },
                }
            }
            completed
        }; // Drop every pending network future before publishing any result.
        if completed.iter().any(|(_,result)|result.as_ref().is_ok_and(|value|value.core_epoch.is_some_and(|value|value!=epoch))) { return ControlError::Superseded.response(); }
        let mut retired_delays = Vec::new();
        let committed = (|| {
            let mut gate = self.plane.gate.lock(); self.check_live(&gate)?;
            if scope.is_cancelled() { return Err(ControlError::Cancelled); }
            if gate.core_epoch != epoch || gate.revision != revision ||
                gate.selection_intents.get(&name) != Some(&ticket) ||
                keys.iter().any(|key| gate.delay_intents.get(key) != Some(&ticket)) { return Err(ControlError::Superseded); }
            self.check_graph_idle(&gate)?;
            let live = self.state.tunnel.route_snapshot();
            if !live.proxies.get(name.as_str()).is_some_and(|live| Arc::ptr_eq(live, &group)) { return Err(ControlError::Superseded); }
            let members = group.member_proxies().ok_or(ControlError::NotFound)?;
            if members.len() != paths.len() { return Err(ControlError::Superseded); }
            for (member, before) in members.into_iter().zip(&paths) {
                let after = Self::probe_graph(member)?;
                if !before.is_current() || !after.is_current() || before.len() != after.len() || !before.iter().zip(&after).all(|(a,b)| Arc::ptr_eq(a,b)) {
                    return Err(ControlError::Superseded);
                }
            }
            let all = self.group_members()?;
            let next = gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let sequence = gate.groups_sequence.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let before = self.snapshot_locked(&gate).picks;
            let fixed_before: Vec<_> = all.iter().map(|member| member.selection().and_then(|selection| selection.fixed())).collect();
            for (index, result) in &completed {
                let delay = result.as_ref().map(|value|value.delay).unwrap_or(0);
                Self::commit_delay_locked(&mut gate, &paths[*index][0], params.url.as_deref().unwrap_or(""), delay, &paths[*index], &mut retired_delays);
            }
            for member in &all { if let Some(selection) = member.selection() { selection.reconcile_health(); } }
            let fixed_after: Vec<_> = all.iter().map(|member| member.selection().and_then(|selection| selection.fixed())).collect();
            if self.snapshot_locked(&gate).picks != before || fixed_before != fixed_after { gate.revision = next; }
            if !completed.is_empty() { gate.groups_sequence = sequence; }
            Ok((all, gate.core_epoch, gate.revision))
        })();
        let (_all, epoch, revision) = match committed { Ok(value) => value, Err(error) => return error.response() };
        drop(retained); drop(route);
        if !completed.is_empty() { self.groups_changed.notify_one(); }
        let result: BTreeMap<String,u16> = completed.iter().filter_map(|(index,result)|
            result.as_ref().ok().map(|measured| (paths[*index][0].name().to_string(), measured.delay))).collect();
        let mut response = if result.is_empty() {
            (StatusCode::GATEWAY_TIMEOUT, Json(serde_json::json!({"message":"Timeout"}))).into_response()
        } else { Json(result).into_response() };
        response.headers_mut().insert("x-aerove-core-epoch", HeaderValue::from_str(&epoch.to_string()).unwrap());
        response.headers_mut().insert("x-aerove-route-version", HeaderValue::from_str(&revision.to_string()).unwrap());
        response
    }
}

#[derive(Default, serde::Deserialize)]
pub(crate) struct UnpinQuery {
    #[serde(rename="coreEpoch")]
    core_epoch: Option<String>,
}
impl RouteControl {
    pub(crate) async fn unpin(self: &Arc<Self>, name:String, query:UnpinQuery) -> axum::response::Response {
        let expected=match query.core_epoch {
            Some(value)=>match value.parse::<u64>() { Ok(epoch) if epoch!=0 && epoch.to_string()==value=>Some(epoch), _=>return ControlError::InvalidEpoch.response() },
            None=>None,
        };
        let Some(scope)=self.groups.lock().clone() else {return ControlError::SelectionMemberNotIntegrated.response();};
        let control=self.clone();let captured=scope.clone();
        let work=Box::pin(async move{control.unpin_captured(name,expected,captured).await});
        match scope.run_captured(work).await {Ok(response)=>response,Err(_)=>ControlError::Cancelled.response()}
    }
    async fn unpin_captured(&self,name:String,expected:Option<u64>,scope:Arc<meow_config::owned_load::OwnedLoadScope>) -> axum::response::Response {
        use axum::{http::{StatusCode,HeaderValue},response::IntoResponse};
        let target=(||{
            let gate=self.plane.gate.lock();self.check_live(&gate)?;
            if expected.is_some_and(|value|value!=gate.core_epoch){return Err(ControlError::Superseded);}
            self.group_members()?;
            let route=self.state.tunnel.route_snapshot();
            let group=route.proxies.get(name.as_str()).cloned().ok_or(ControlError::NotFound)?;
            if !group.selection().is_some_and(|selection|selection.can_unfix()){return Err(ControlError::InvalidSelection);}
            Ok((route,group))
        })();
        let (route,group)=match target{Ok(value)=>value,Err(error)=>return error.response()};
        let selection=group.selection().expect("validated selection");
        let flush=selection.flush_selection();
        let reserved=if flush.is_some(){
            let(send,receive)=std::sync::mpsc::channel::<Box<dyn FnOnce()+Send>>();
            match scope.spawn_blocking_owned(move||{if let Ok(work)=receive.recv(){work();}}){
                Ok(task)=>Some((send,task)),Err(_)=>return ControlError::Cancelled.response(),
            }
        }else{None};
        let committed=(||{
            let mut gate=self.plane.gate.lock();self.check_live(&gate)?;
            if scope.is_cancelled(){return Err(ControlError::Cancelled);}
            if expected.is_some_and(|value|value!=gate.core_epoch){return Err(ControlError::Superseded);}
            let retained=self.group_members()?;
            let live=self.state.tunnel.route_snapshot();
            if !live.proxies.get(name.as_str()).is_some_and(|value|Arc::ptr_eq(value,&group)){return Err(ControlError::Superseded);}
            let ticket=gate.selection_serial.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let next=gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let sequence=gate.groups_sequence.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let changed=selection.fixed().is_some_and(|value|!value.is_empty());
            selection.unfix_memory().map_err(|_|ControlError::InvalidSelection)?;
            // Even auto->auto is a new explicit intent, fencing older PUT or
            // group batches without fabricating a route revision.
            gate.selection_serial=ticket;gate.selection_intents.insert(name,ticket);
            if changed{gate.revision=next;gate.groups_sequence=sequence;}
            Ok((retained,gate.core_epoch,gate.revision,changed))
        })();
        let (_retained,epoch,revision,changed)=match committed{Ok(value)=>value,Err(error)=>return error.response()};
        if changed{self.groups_changed.notify_one();}
        if let Some((send,task))=reserved{
            if send.send(flush.unwrap()).is_err(){return ControlError::InvalidSelection.response();}
            if task.await.is_err(){return ControlError::InvalidSelection.response();}
        }
        drop(route);
        let mut response=StatusCode::NO_CONTENT.into_response();
        response.headers_mut().insert("x-aerove-core-epoch",HeaderValue::from_str(&epoch.to_string()).unwrap());
        response.headers_mut().insert("x-aerove-route-version",HeaderValue::from_str(&revision.to_string()).unwrap());
        response
    }
}

#[cfg(test)]
mod telemetry_traffic_tests {
    use super::telemetry_traffic_frame;
    #[test]
    fn official_sample_maps_exact_kotlin_traffic_fields() {
        let wire=telemetry_traffic_frame(r#"{"up":3,"down":4,"upTotal":17,"downTotal":29}"#).unwrap();
        let event:serde_json::Value=serde_json::from_str(&wire).unwrap();
        assert_eq!(event,serde_json::json!({"type":"traffic","up":3,"down":4,"uploadTotal":17,"downloadTotal":29}));
    }
    #[test]
    fn invalid_samples_are_rejected_without_synthetic_zero_frame() {
        for source in [r#"{"up":-1,"down":0,"upTotal":0,"downTotal":0}"#,r#"{"up":0,"down":-1,"upTotal":0,"downTotal":0}"#,
            r#"{"up":0,"down":0,"upTotal":-1,"downTotal":0}"#,r#"{"up":0,"down":0,"upTotal":0,"downTotal":-1}"#,
            r#"{"up":9223372036854775808,"down":0,"upTotal":0,"downTotal":0}"#,r#"{"up":"0","down":0,"upTotal":0,"downTotal":0}"#,
            r#"{"up":0,"down":0,"upTotal":0}"#,"null"] { assert!(telemetry_traffic_frame(source).is_err()); }
    }
    #[test]
    fn zero_and_signed_long_maximum_remain_exact_numbers() {
        let wire=telemetry_traffic_frame(r#"{"up":0,"down":0,"upTotal":9223372036854775807,"downTotal":9223372036854775807}"#).unwrap();
        let event:serde_json::Value=serde_json::from_str(&wire).unwrap();
        assert_eq!(event["up"].as_i64(),Some(0));assert_eq!(event["uploadTotal"].as_i64(),Some(i64::MAX));
    }
}


#[cfg(test)]
mod provider_event_readiness_tests {
    use super::*;
    use crate::{api::CoreApi, providers::{self, LoadContext, OwnedLoadScope, OwnedWork}};
    use meow_config::provider_events::{ProviderLease, ProviderObserver};
    use std::{future::Future, sync::atomic::{AtomicU64, Ordering}, task::Poll};
    use tokio::sync::{mpsc, oneshot};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    struct Context(Arc<crate::providers::ControlPlane>);
    impl LoadContext for Context {
        fn provider_observer(&self) -> Option<Arc<dyn ProviderObserver>> { Some(self.0.clone()) }
        fn wrap(&self, work: OwnedWork) -> OwnedWork { work }
        fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) { work(); }
    }
    async fn fixture() -> (Arc<CoreApi>, Arc<OwnedLoadScope>, std::path::PathBuf) {
        let root = std::env::temp_dir().join(format!("provider-event-final-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        std::fs::create_dir(&root).unwrap();
        for name in ["feed", "spare"] { std::fs::write(root.join(format!("{name}.yaml")), "proxies:\n - {name: a, type: direct}\n").unwrap(); }
        let plane = crate::providers::ControlPlane::new(700, tokio_util::sync::CancellationToken::new()).unwrap();
        let scope = OwnedLoadScope::with_context(Arc::new(Context(plane.clone())));
        let config = providers::load_with_cache("strict: true\nproxy-providers:\n feed: {type: file, path: feed.yaml, interval: 0}\n spare: {type: file, path: spare.yaml, interval: 0}\nproxy-groups:\n - {name: PICK, type: select, use: [feed]}\nrules: ['MATCH,PICK']\n", &scope, &plane.cancel, Some(&root)).await.unwrap();
        let tunnel = meow_tunnel::Tunnel::new_with_slot(config.dns.resolver_slot.clone());
        tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
        let api = Arc::new(CoreApi::assemble_with_plane(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(),
            "provider-event-final-secret-000000001", None, axum::Router::new(), plane).unwrap());
        tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
        let initial = api.control.activate_groups(&scope).unwrap(); initial.abort(); let _ = initial.await;
        let mut receipts = api.control.plane.subscribe();
        api.activate_providers().unwrap();
        for _ in 0..2 { receipt(&mut receipts).await; }
        emitting_drained(&api.control).await;
        (api, scope, root)
    }
    async fn receipt(receipts: &mut tokio::sync::broadcast::Receiver<crate::providers::ProviderLoaded>) {
        tokio::time::timeout(std::time::Duration::from_secs(5), receipts.recv()).await.unwrap().unwrap();
    }
    async fn emitting_drained(control: &RouteControl) {
        tokio::time::timeout(std::time::Duration::from_secs(5), async {
            loop {
                let changed = control.groups_changed.notified(); tokio::pin!(changed);
                changed.as_mut().enable();
                if control.plane.gate.lock().emitting.is_empty() { return; }
                changed.await;
            }
        }).await.unwrap();
    }
    fn commit_installed_projection(operation: &dyn ProviderLease, projection: meow_config::ProxyProviderProjection) {
        assert!(projection.is_current());
        assert!(projection.is_installed());
        operation.validate_proxy_projection(&projection, &[]).unwrap();
        operation.commit_proxy_projection(&projection, &[], &mut || {
            assert!(projection.is_installed(), "identity installation changed the actual provider slots");
        }).unwrap();
    }
    struct WakeFlag(std::sync::atomic::AtomicBool);
    impl std::task::Wake for WakeFlag {
        fn wake(self: Arc<Self>) { self.0.store(true, Ordering::Release); }
        fn wake_by_ref(self: &Arc<Self>) { self.0.store(true, Ordering::Release); }
    }
    fn driver(control: Arc<RouteControl>) -> (mpsc::UnboundedSender<oneshot::Sender<bool>>, tokio::task::JoinHandle<()>) {
        let (send, mut receive) = mpsc::unbounded_channel::<oneshot::Sender<bool>>();
        let task = tokio::spawn(async move {
            let events = control.run_group_events(); tokio::pin!(events);
            let flag = Arc::new(WakeFlag(std::sync::atomic::AtomicBool::new(false)));
            let waker = std::task::Waker::from(flag.clone());
            while let Some(done) = receive.recv().await {
                let notified = flag.0.swap(false, Ordering::AcqRel);
                let result = events.as_mut().poll(&mut std::task::Context::from_waker(&waker));
                assert!(matches!(result, Poll::Pending)); done.send(notified).unwrap();
            }
        });
        (send, task)
    }
    async fn tick(send: &mpsc::UnboundedSender<oneshot::Sender<bool>>) -> bool {
        let (done, waited) = oneshot::channel(); send.send(done).unwrap();
        tokio::time::timeout(std::time::Duration::from_secs(5), waited).await.unwrap().unwrap()
    }
    async fn finish(api: Arc<CoreApi>, scope: Arc<OwnedLoadScope>, root: std::path::PathBuf,
        send: mpsc::UnboundedSender<oneshot::Sender<bool>>, task: tokio::task::JoinHandle<()>) {
        drop(send); tokio::time::timeout(std::time::Duration::from_secs(5), task).await.unwrap().unwrap();
        api.cancel(); api.control.cancel(); scope.cancel();
        tokio::time::timeout(std::time::Duration::from_secs(5), scope.close()).await.unwrap().unwrap();
        tokio::time::timeout(std::time::Duration::from_secs(5), api.join()).await.unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test(flavor="multi_thread", worker_threads=2)]
    async fn provider_group_event_waits_for_final_arc_and_last_unmodified_lease_after_old_wake() {
        let (api, scope, root) = fixture().await;
        let feed = api.state.proxy_providers.get("feed").unwrap().value().clone();
        let spare = api.state.proxy_providers.get("spare").unwrap().value().clone();
        let operation = api.control.plane.begin(&feed.provider_identity()).unwrap();
        let retained = operation.clone();
        let unrelated = api.control.plane.begin(&spare.provider_identity()).unwrap();
        let mut receiver = api.control.subscribe_groups();
        let mut receipts = api.control.plane.subscribe();
        commit_installed_projection(operation.as_ref(), feed.project_members(feed.proxies()));
        let sequence = api.control.plane.gate.lock().groups_sequence; assert!(sequence > 0);
        let (send, task) = driver(api.control.clone());
        api.control.notify_provider_groups(); tick(&send).await;
        assert!(matches!(receiver.try_recv(), Err(tokio::sync::broadcast::error::TryRecvError::Empty)));
        assert!(matches!(api.control.plane.proxy_snapshot(&api.state, Some("feed")), Err("providerBusy")));
        drop(operation); api.control.notify_provider_groups(); tick(&send).await;
        assert!(matches!(receiver.try_recv(), Err(tokio::sync::broadcast::error::TryRecvError::Empty)));
        drop(retained); assert!(tick(&send).await, "final changed lease did not wake actual event future");
        assert!(matches!(receiver.try_recv(), Err(tokio::sync::broadcast::error::TryRecvError::Empty)));
        drop(unrelated);
        receipt(&mut receipts).await; emitting_drained(&api.control).await;
        assert!(tick(&send).await, "last unmodified lease did not wake actual event future");
        assert_eq!(receiver.try_recv().unwrap(), (sequence, api.control.plane.stamp().1));
        assert!(api.control.plane.proxy_snapshot(&api.state, None).is_ok());
        tick(&send).await;
        assert!(matches!(receiver.try_recv(), Err(tokio::sync::broadcast::error::TryRecvError::Empty)));
        finish(api, scope, root, send, task).await;
    }
    #[tokio::test(flavor="multi_thread", worker_threads=2)]
    async fn provider_group_event_waits_for_emitting_receipt_and_rewakes_after_completion() {
        let (api, scope, root) = fixture().await;
        let feed = api.state.proxy_providers.get("feed").unwrap().value().clone();
        let identity = feed.provider_identity();
        let operation = api.control.plane.begin(&identity).unwrap();
        let mut receiver = api.control.subscribe_groups();
        let mut receipts = api.control.plane.subscribe();
        commit_installed_projection(operation.as_ref(), feed.project_members(feed.proxies()));
        let sequence = api.control.plane.gate.lock().groups_sequence;
        drop(operation);
        receipt(&mut receipts).await; emitting_drained(&api.control).await;
        let held_emitting = u64::MAX;
        api.control.plane.gate.lock().emitting.insert(held_emitting);
        let (send, task) = driver(api.control.clone());
        api.control.notify_provider_groups(); tick(&send).await;
        assert!(matches!(receiver.try_recv(), Err(tokio::sync::broadcast::error::TryRecvError::Empty)));
        api.control.plane.finish_emitting(held_emitting); assert!(tick(&send).await, "receipt completion did not wake actual event future");
        assert_eq!(receiver.try_recv().unwrap(), (sequence, api.control.plane.stamp().1));
        tick(&send).await;
        assert!(matches!(receiver.try_recv(), Err(tokio::sync::broadcast::error::TryRecvError::Empty)));
        finish(api, scope, root, send, task).await;
    }

    #[tokio::test]
    async fn provider_group_event_waits_for_natural_pending_receipt_before_provider_worker_poll() {
        let (api, scope, root) = fixture().await;
        let feed = api.state.proxy_providers.get("feed").unwrap().value().clone();
        let mut receiver = api.control.subscribe_groups();
        let mut receipts = api.control.plane.subscribe();
        {
            let events = api.control.run_group_events(); tokio::pin!(events);
            let flag = Arc::new(WakeFlag(std::sync::atomic::AtomicBool::new(false)));
            let waker = std::task::Waker::from(flag.clone());
            let baseline = api.control.plane.gate.lock().groups_sequence;
            assert!(matches!(events.as_mut().poll(&mut std::task::Context::from_waker(&waker)), Poll::Pending));
            if baseline > 0 { assert_eq!(receiver.try_recv().unwrap(), (baseline, api.control.plane.stamp().1)); }
            assert!(matches!(receiver.try_recv(), Err(tokio::sync::broadcast::error::TryRecvError::Empty)));
            let operation = api.control.plane.begin(&feed.provider_identity()).unwrap();
            commit_installed_projection(operation.as_ref(), feed.project_members(feed.proxies()));
            let sequence = api.control.plane.gate.lock().groups_sequence; assert!(sequence > baseline);
            drop(operation);
            {
                let gate = api.control.plane.gate.lock();
                assert!(gate.busy.is_empty()); assert!(gate.emitting.is_empty());
                assert!(!gate.group_events_ready(), "natural pending receipt must block groups");
            }
            assert!(matches!(events.as_mut().poll(&mut std::task::Context::from_waker(&waker)), Poll::Pending));
            assert!(matches!(receiver.try_recv(), Err(tokio::sync::broadcast::error::TryRecvError::Empty)), "group overtook pending provider receipt");
            assert!(matches!(receipts.try_recv(), Err(tokio::sync::broadcast::error::TryRecvError::Empty)), "provider worker unexpectedly ran before yield");
            flag.0.store(false, Ordering::Release);
            let loaded = tokio::time::timeout(std::time::Duration::from_secs(5), receipts.recv()).await.unwrap().unwrap();
            assert_eq!(loaded.name, "feed"); assert_eq!(loaded.phase, "update");
            assert!(loaded.revision.parse::<u64>().unwrap() > 1);
            emitting_drained(&api.control).await;
            assert!(api.control.plane.gate.lock().group_events_ready());
            assert!(flag.0.swap(false, Ordering::AcqRel), "receipt completion must wake blocked group future");
            assert!(matches!(events.as_mut().poll(&mut std::task::Context::from_waker(&waker)), Poll::Pending));
            assert_eq!(receiver.try_recv().unwrap(), (sequence, api.control.plane.stamp().1));
            assert!(matches!(events.as_mut().poll(&mut std::task::Context::from_waker(&waker)), Poll::Pending));
            assert!(matches!(receiver.try_recv(), Err(tokio::sync::broadcast::error::TryRecvError::Empty)));
        }
        api.cancel(); api.control.cancel(); scope.cancel();
        tokio::time::timeout(std::time::Duration::from_secs(5), scope.close()).await.unwrap().unwrap();
        tokio::time::timeout(std::time::Duration::from_secs(5), api.join()).await.unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }

    struct ReenterDelivery {
        control: Arc<RouteControl>, identity: meow_config::provider_events::ProviderIdentity,
        woke: std::sync::atomic::AtomicBool,
    }
    impl std::task::Wake for ReenterDelivery {
        fn wake(self: Arc<Self>) { self.wake_by_ref(); }
        fn wake_by_ref(self: &Arc<Self>) {
            assert!(self.control.plane.stamp().0>0);
            assert_eq!(self.control.plane.begin(&self.identity).err().unwrap(),"providerBusy");
            self.woke.store(true, Ordering::Release);
        }
    }
    #[tokio::test]
    async fn group_delivery_waker_reenters_plane_without_lock_and_provider_cannot_overtake_reservation() {
        let(api,scope,root)=fixture().await;let provider=api.state.proxy_providers.get("feed").unwrap().value().clone();
        let wake=Arc::new(ReenterDelivery { control:api.control.clone(),identity:provider.provider_identity().clone(),woke:std::sync::atomic::AtomicBool::new(false) });
        let waker=std::task::Waker::from(wake.clone());let mut receiver=api.control.subscribe_groups();let mut receive=Box::pin(receiver.recv());
        assert!(receive.as_mut().poll(&mut std::task::Context::from_waker(&waker)).is_pending());
        let mut receipts=api.control.plane.subscribe();let operation=api.control.plane.begin(provider.provider_identity()).unwrap();commit_installed_projection(operation.as_ref(),provider.project_members(provider.proxies()));drop(operation);
        receipt(&mut receipts).await;emitting_drained(&api.control).await;
        let mut events=Box::pin(api.control.run_group_events());let flag=Arc::new(WakeFlag(std::sync::atomic::AtomicBool::new(false)));let driver=std::task::Waker::from(flag);
        assert!(events.as_mut().poll(&mut std::task::Context::from_waker(&driver)).is_pending());assert!(wake.woke.load(Ordering::Acquire));
        let expected=api.control.plane.gate.lock().groups_sequence;
        match receive.as_mut().poll(&mut std::task::Context::from_waker(&waker)) { Poll::Ready(Ok(sequence))=>assert_eq!(sequence,(expected,api.control.plane.stamp().1)), _=>panic!("reserved group event was not delivered") };drop(receive);drop(events);
        assert!(!api.control.plane.gate.lock().groups_emitting);let admitted=api.control.plane.begin(provider.provider_identity()).unwrap();drop(admitted);
        api.cancel();scope.close().await.unwrap();api.join().await;std::fs::remove_dir_all(root).unwrap();
    }

    struct StaleProbe(u64);
    impl meow_common::probe_policy::ProbeSource for StaleProbe {
        fn snapshot(&self)->std::io::Result<Arc<meow_common::probe_policy::ProbePolicy>> { Ok(Arc::new(Default::default())) }
        fn snapshot_with_epoch(&self)->std::io::Result<(Arc<meow_common::probe_policy::ProbePolicy>,Option<u64>)> { Ok((self.snapshot()?,Some(self.0))) }
        fn cancelled(&self)->std::pin::Pin<Box<dyn std::future::Future<Output=()>+Send+'_>> { Box::pin(std::future::pending()) }
    }
    #[tokio::test]
    async fn captured_old_policy_epoch_is_rejected_before_new_plane_selection_admission() {
        let(api,scope,root)=fixture().await;let before=api.control.snapshot().await.unwrap();let stamp=api.control.plane.stamp();
        let source=meow_common::probe_policy::ProbeScope::new(Arc::new(StaleProbe(stamp.0-1)));
        let rejected=source.scope(api.control.select("PICK","a")).await;assert_eq!(rejected,Err(ControlError::Superseded));assert_eq!(api.control.snapshot().await.unwrap(),before);
        api.cancel();scope.close().await.unwrap();api.join().await;std::fs::remove_dir_all(root).unwrap();
    }
}

impl RouteControl {
    pub(crate) fn provider_health_stamp(&self, name: &str) -> Result<(u64,u64),ControlError> {
        let gate=self.plane.gate.lock();self.check_live(&gate)?;self.check_graph_idle(&gate)?;
        let provider=self.state.proxy_providers.get(name).ok_or(ControlError::NotFound)?;
        gate.provider_health_stamp(&provider.provider_identity()).ok_or(ControlError::Superseded)
    }

    pub(crate) fn provider_health_recent_use(&self, policy: &crate::providers::provider_health::Policy) -> bool {
        let route=self.state.tunnel.route_snapshot();
        policy.groups.iter().filter_map(|name|route.proxies.get(name.as_str()))
            .filter_map(|proxy|proxy.last_user_use()).any(|last|last.elapsed()<policy.interval)
    }

    /// A provider round shares the HTTP selection/delay intent namespace.
    /// Real network I/O is diagnostic; only this short gate publishes health.
    pub(crate) async fn provider_health_round(&self, policy: &crate::providers::provider_health::Policy,
        scope: &Arc<meow_config::owned_load::OwnedLoadScope>) -> Result<(u64,u64),ControlError> {
        // A single operation snapshot binds every buffered member to the
        // pre-admission policy/epoch, including transport failures.
        let snapshot = meow_common::probe_policy::capture().map_err(|_| {
            if self.plane.cancel.is_cancelled() { ControlError::Cancelled } else { ControlError::Superseded }
        })?;
        match snapshot {
            Some(snapshot) => snapshot.scope(self.provider_health_round_captured(policy, scope)).await,
            None => self.provider_health_round_captured(policy, scope).await,
        }
    }

    async fn provider_health_round_captured(&self, policy: &crate::providers::provider_health::Policy,
        scope: &Arc<meow_config::owned_load::OwnedLoadScope>) -> Result<(u64,u64),ControlError> {
        use futures::StreamExt;
        let captured_epoch=self.captured_probe_epoch().await?;
        let (route,provider,stamp,members,paths,keys,ticket,epoch,revision)={
            let mut gate=self.plane.gate.lock();self.check_live(&gate)?;self.check_graph_idle(&gate)?;
            if scope.is_cancelled(){return Err(ControlError::Cancelled);}
            if captured_epoch.is_some_and(|epoch|epoch!=gate.core_epoch){return Err(ControlError::Superseded);}
            let provider=self.state.proxy_providers.get(&policy.name).ok_or(ControlError::NotFound)?.value().clone();
            let stamp=gate.provider_health_stamp(&provider.provider_identity()).ok_or(ControlError::Superseded)?;
            let route=self.state.tunnel.route_snapshot();
            let members=provider.proxies();
            let paths=members.iter().map(|member|Self::probe_graph(member.clone())).collect::<Result<Vec<_>,_>>()?;
            let keys:Vec<_>=members.iter().map(|member|member.health() as *const _ as usize).collect();
            gate.selection_serial=gate.selection_serial.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let ticket=gate.selection_serial;
            for key in &keys{gate.delay_intents.insert(*key,ticket);}
            (route,provider,stamp,members,paths,keys,ticket,gate.core_epoch,gate.revision)
        };
        let results={
            // Each future owns its target and parameters across await. Keep
            // members as the ordered identity snapshot for the final gate.
            let mut pending=Vec::with_capacity(members.len());
            for member in &members {
                let target=member.clone();let url=policy.url.clone();
                let expected=policy.expected.clone();let timeout=policy.timeout;
                pending.push(async move {
                    meow_config::probe_without_effects_detailed(&target,&url,expected.as_deref(),timeout).await
                });
            }
            let probes=futures::stream::iter(pending).buffered(meow_config::GROUP_DELAY_CONCURRENCY);
            tokio::pin!(probes);
            let mut results=Vec::with_capacity(members.len());
            loop { tokio::select! { biased;
                _=self.plane.cancel.cancelled()=>return Err(ControlError::Cancelled),
                _=scope.cancelled()=>return Err(ControlError::Cancelled),
                item=probes.next()=>match item{Some(result)=>results.push(result),None=>break},
            } }
            results
        };
        if results.iter().any(|result|result.as_ref().is_ok_and(|value|value.core_epoch.is_some_and(|value|value!=epoch))){
            return Err(ControlError::Superseded);
        }
        let mut retired_delays = Vec::new();
        let retired={
            let mut gate=self.plane.gate.lock();self.check_live(&gate)?;self.check_graph_idle(&gate)?;
            if scope.is_cancelled(){return Err(ControlError::Cancelled);}
            if gate.core_epoch!=epoch || gate.revision!=revision || keys.iter().any(|key|gate.delay_intents.get(key)!=Some(&ticket)){
                return Err(ControlError::Superseded);
            }
            let current=self.state.proxy_providers.get(&policy.name).ok_or(ControlError::Superseded)?;
            if !Arc::ptr_eq(current.value(),&provider) || gate.provider_health_stamp(&provider.provider_identity())!=Some(stamp){
                return Err(ControlError::Superseded);
            }
            let live=provider.proxies();
            if live.len()!=members.len() || !live.iter().zip(&members).all(|(a,b)|Arc::ptr_eq(a,b)){
                return Err(ControlError::Superseded);
            }
            for (member,before) in members.iter().zip(&paths){
                let after=Self::probe_graph(member.clone())?;
                if !before.is_current() || !after.is_current() || before.len()!=after.len() ||
                    !before.iter().zip(&after).all(|(a,b)|Arc::ptr_eq(a,b)){return Err(ControlError::Superseded);}
            }
            let all=self.group_members()?;
            let next=gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let sequence=gate.groups_sequence.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let before=self.snapshot_locked(&gate).picks;
            let fixed_before:Vec<_>=all.iter().map(|member|member.selection().and_then(|selection|selection.fixed())).collect();
            for (index,(member,result)) in members.iter().zip(&results).enumerate(){
                Self::commit_delay_locked(&mut gate, member, &policy.url, result.as_ref().map(|value|value.delay).unwrap_or(0), &paths[index], &mut retired_delays);
            }
            for member in &all{if let Some(selection)=member.selection(){selection.reconcile_health();}}
            let fixed_after:Vec<_>=all.iter().map(|member|member.selection().and_then(|selection|selection.fixed())).collect();
            if self.snapshot_locked(&gate).picks!=before || fixed_before!=fixed_after{gate.revision=next;}
            if !members.is_empty(){gate.groups_sequence=sequence;}
            all
        };
        drop(retired);drop(route);
        if !members.is_empty(){self.groups_changed.notify_one();}
        Ok(stamp)
    }
}

#[cfg(test)]
mod delay_history_tests {
    use super::*;
    use crate::{api::CoreApi, providers::OwnedLoadScope};
    use std::sync::atomic::{AtomicBool, Ordering};

    async fn fixture() -> (CoreApi, Arc<OwnedLoadScope>, tokio::task::JoinHandle<()>, Arc<dyn Proxy>) {
        let scope = OwnedLoadScope::new();
        let config = meow_config::load_config_from_str_owned("proxy-groups: [{name: PICK,type: select,proxies: [DIRECT]}]\nrules: ['MATCH,PICK']\n", &scope).await.unwrap();
        let tunnel = meow_tunnel::Tunnel::new_with_slot(config.dns.resolver_slot.clone());
        tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
        let api = CoreApi::assemble_with_epoch(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(),
            "history-unit-private-secret-00000001", None, axum::Router::new(), 901, CancellationToken::new()).unwrap();
        tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
        let events = api.control.activate_groups(&scope).unwrap();
        let target = tunnel.route_snapshot().proxies["DIRECT"].clone();
        (api, scope, events, target)
    }
    async fn close(api: CoreApi, scope: Arc<OwnedLoadScope>, events: tokio::task::JoinHandle<()>) {
        api.cancel(); scope.close().await.unwrap();
        if let Err(error) = events.await { assert!(error.is_cancelled()); }
        api.join().await;
    }

    struct Guard { valid: AtomicBool, plane: std::sync::Weak<ControlPlane>, retired: Arc<AtomicBool> }
    impl meow_common::adapter::ProbeDependencyGuard for Guard {
        fn is_current(&self) -> bool { self.valid.load(Ordering::SeqCst) }
    }
    impl Drop for Guard {
        fn drop(&mut self) {
            if let Some(plane) = self.plane.upgrade() { assert!(plane.gate.try_lock().is_some(), "history proof destructor ran under the plane gate"); }
            self.retired.store(true, Ordering::SeqCst);
        }
    }

    #[tokio::test]
    async fn invalidated_original_dependency_proof_is_pruned_and_dropped_outside_the_gate() {
        let (api, scope, events, target) = fixture().await;
        let retired = Arc::new(AtomicBool::new(false));
        let guard = Arc::new(Guard { valid: AtomicBool::new(true), plane: Arc::downgrade(&api.control.plane), retired: retired.clone() });
        let weak = Arc::downgrade(&guard);
        let receipt = Arc::new(meow_common::adapter::UrlDelayReceipt { proxy: target.clone(), proxies: vec![target.clone()],
            dependencies: vec![meow_common::adapter::ProbeDependencies::guarded(Vec::new(), guard)], admission: None });
        let mut old = Vec::new();
        { let mut gate = api.control.plane.gate.lock(); RouteControl::record_delay_locked(&mut gate, &target,
            "http://unit.invalid/proof", 17, DelayGraphProof::Core(receipt), &mut old); }
        drop(old);
        assert_eq!(api.control.delay_history(Some("901")).unwrap().delays.len(), 1);
        weak.upgrade().unwrap().valid.store(false, Ordering::SeqCst);
        let snapshot = api.control.delay_history(Some("901")).unwrap();
        assert!(snapshot.complete && snapshot.delays.is_empty()); assert_eq!(snapshot.history_revision, "2");
        assert!(weak.upgrade().is_none()); assert!(retired.load(Ordering::SeqCst));
        close(api, scope, events).await;
    }

    #[tokio::test]
    async fn history_budget_or_revision_exhaustion_returns_an_error_instead_of_a_partial_complete_snapshot() {
        for revision_exhausted in [false, true] {
            let (api, scope, events, target) = fixture().await;
            let graph = RouteControl::probe_graph(target.clone()).unwrap(); let mut old = Vec::new();
            {
                let mut gate = api.control.plane.gate.lock();
                if revision_exhausted { gate.delay_history_revision = u64::MAX; } else { gate.delay_history_bytes = MAX_DELAY_HISTORY_BYTES; }
                RouteControl::record_delay_locked(&mut gate, &target, "http://unit.invalid/budget", 19, DelayGraphProof::Managed(graph), &mut old);
                assert!(gate.delay_history.is_empty()); assert!(gate.delay_history_error.is_some());
            }
            drop(old);
            assert_eq!(api.control.delay_history(Some("901")).unwrap_err(), ControlError::DelayHistoryIncomplete);
            close(api, scope, events).await;
        }
    }
}
