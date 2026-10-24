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
    #[serde(rename = "picks-version")]
    pub picks_version: String,
    pub picks: BTreeMap<String, String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ControlError { Cancelled, NotFound, InvalidSelection, RevisionExhausted, InvalidEpoch, SelectionMemberNotIntegrated, DialerProxyNotIntegrated, Superseded }



pub struct RouteControl {
    state: Arc<AppState>,
    pub plane: Arc<ControlPlane>,
    groups: parking_lot::Mutex<Option<Arc<meow_config::owned_load::OwnedLoadScope>>>,
    groups_changed: tokio::sync::Notify,
    groups_events: tokio::sync::broadcast::Sender<u64>,
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
            groups_changed: tokio::sync::Notify::new(), groups_events })
    }

    pub fn cancel(&self) {
        self.plane.cancel();
    }

    fn check_live(&self, gate: &PlaneState) -> Result<(), ControlError> {
        if gate.closed || self.plane.cancel.is_cancelled() { Err(ControlError::Cancelled) } else { Ok(()) }
    }

    fn snapshot_locked(&self, gate: &PlaneState) -> RouteSnapshot {
        let route = self.state.tunnel.route_snapshot();
        RouteSnapshot {
            core_epoch: gate.core_epoch.to_string(), picks_version: gate.revision.to_string(),
            picks: route.proxies.iter().filter_map(|(name, proxy)| {
                proxy.current().map(|selected| (name.to_string(), selected))
            }).collect(),
        }
    }

    pub async fn snapshot(&self) -> Result<RouteSnapshot, ControlError> {
        let gate = self.plane.gate.lock();
        self.check_live(&gate)?;
        Ok(self.snapshot_locked(&gate))
    }

    pub async fn set_mode(&self, mode: TunnelMode) -> Result<RouteSnapshot, ControlError> {
        let mut gate = self.plane.gate.lock();
        self.check_live(&gate)?;
        if self.state.tunnel.mode() != mode {
            let next = gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            self.state.tunnel.set_mode(mode);
            self.state.raw_config.write().mode = Some(mode.to_string());
            gate.revision = next;
        }
        Ok(self.snapshot_locked(&gate))
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

impl ControlError {
    pub(crate) fn response(self) -> axum::response::Response {
        use axum::response::IntoResponse;
        let status = match self {
            Self::Cancelled => axum::http::StatusCode::GONE,
            Self::NotFound => axum::http::StatusCode::NOT_FOUND,
            Self::InvalidSelection | Self::InvalidEpoch => axum::http::StatusCode::BAD_REQUEST,
            Self::RevisionExhausted | Self::Superseded => axum::http::StatusCode::CONFLICT,
            Self::SelectionMemberNotIntegrated | Self::DialerProxyNotIntegrated => axum::http::StatusCode::UNPROCESSABLE_ENTITY,
        };
        let body = if self == Self::SelectionMemberNotIntegrated {
            serde_json::json!({"error": "selectionMemberNotIntegrated", "field": "proxy-groups.proxies",
                "reason": "selection preparation currently requires DIRECT or REJECT leaf members"})
        } else if self == Self::DialerProxyNotIntegrated {
            serde_json::json!({"error": "managedDialerProxyGraphNotIntegrated", "field": "proxies.dialer-proxy",
                "reason": "injected dialer edges require managed graph activation and health/selection ownership"})
        } else { serde_json::json!({"error": format!("{self:?}")}) };
        (status, axum::Json(body)).into_response()
    }
}

pub fn router(control: Arc<RouteControl>) -> axum::Router {
    use axum::{extract::{Path, State}, routing::{get, patch, put}, Json};
    axum::Router::new()
        .route("/aerove/telemetry", get(provider_telemetry))
        .route("/aerove/route", get(|State(control): State<Arc<RouteControl>>| async move {
            control.snapshot().await.map(Json).map_err(ControlError::response)
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

async fn provider_telemetry(
    axum::extract::State(control): axum::extract::State<Arc<RouteControl>>,
    axum::Extension(scope): axum::Extension<meow_api::lifecycle::ApiLifecycle>,
    upgrade: axum::extract::WebSocketUpgrade,
) -> axum::response::Response {
    scope.on_upgrade(upgrade, move |mut socket| async move {
        use axum::extract::ws::Message;
        let mut events = control.plane.subscribe();
        let mut seen = std::collections::BTreeMap::new();
        let mut groups = control.groups_events.subscribe();
        let mut traffic = meow_api::routes::subscribe_traffic_feed(&control.state);
        for event in control.plane.provider_baseline() {
            seen.insert((event.kind, event.name.clone()), event.revision.parse::<u64>().expect("canonical provider revision"));
            let frame = serde_json::json!({"type":"providersLoaded","providers":[event]}).to_string();
            if socket.send(Message::Text(frame.into())).await.is_err() { return; }
        }
        loop {
            tokio::select! { biased;
                _ = control.plane.cancel.cancelled() => return,
                sampled = traffic.recv() => {
                    let sampled = match sampled {
                        Ok(sampled) => sampled,
                        Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                        Err(tokio::sync::broadcast::error::RecvError::Closed) => return,
                    };
                    let frame = match telemetry_traffic_frame(&sampled) {
                        Ok(frame) => frame,
                        Err(error) => { tracing::warn!(error,"invalid Core traffic sample"); return; }
                    };
                    if socket.send(Message::Text(frame.into())).await.is_err() { return; }
                }
                incoming = socket.recv() => if incoming.is_none() || incoming.is_some_and(|message| message.is_err() || matches!(message, Ok(Message::Close(_)))) { return; },
                group = groups.recv() => {
                    let Ok(revision) = group else { return; };
                    let frame = serde_json::json!({"type":"groupsChanged","revision":revision}).to_string();
                    if socket.send(Message::Text(frame.into())).await.is_err() { return; }
                }
                event = events.recv() => {
                    let Ok(event) = event else { return; };
                    let revision = event.revision.parse::<u64>().expect("canonical provider revision");
                    let key = (event.kind, event.name.clone());
                    if seen.get(&key).is_some_and(|previous| *previous >= revision) { continue; }
                    seen.insert(key, revision);
                    let frame = serde_json::json!({"type":"providersLoaded","providers":[event]}).to_string();
                    if socket.send(Message::Text(frame.into())).await.is_err() { return; }
                }
            }
        }
    })
}

struct GroupHealthObserver(std::sync::Weak<RouteControl>);
impl meow_common::adapter::HealthObserver for GroupHealthObserver {
    fn commit(&self, health: &meow_common::ProxyHealth, update: meow_common::adapter::HealthUpdate) {
        let Some(control) = self.0.upgrade() else { return; };
        let scope = control.groups.lock().clone();
        if scope.as_ref().map_or(true, |scope| scope.is_cancelled()) { return; }
        let epoch = meow_common::probe_policy::operation_epoch();
        let mut gate = control.plane.gate.lock();
        if control.check_live(&gate).is_err() || epoch.is_some_and(|epoch| epoch != gate.core_epoch) { return; }
        let Ok(proxies) = control.group_members() else { return; };
        if !proxies.iter().any(|proxy| std::ptr::eq(proxy.health(), health)) { return; }
        let Some(revision) = gate.revision.checked_add(1) else { return; };
        let Some(sequence) = gate.groups_sequence.checked_add(1) else { return; };
        let before = control.snapshot_locked(&gate).picks;
        let fixed_before: Vec<_> = proxies.iter().map(|proxy| proxy.selection().and_then(|selection| selection.fixed())).collect();
        health.apply_committed(update);
        for proxy in &proxies {
            if let Some(selection) = proxy.selection() { selection.reconcile_health(); }
        }
        let fixed_after: Vec<_> = proxies.iter().map(|proxy| proxy.selection().and_then(|selection| selection.fixed())).collect();
        if control.snapshot_locked(&gate).picks != before || fixed_before != fixed_after { gate.revision = revision; }
        gate.groups_sequence = sequence;
        drop(gate);
        control.groups_changed.notify_one();
    }
}

impl RouteControl {
    pub fn subscribe_groups(&self) -> tokio::sync::broadcast::Receiver<u64> { self.groups_events.subscribe() }
    fn group_members(&self) -> Result<Vec<Arc<dyn Proxy>>, ControlError> {
        fn visit(proxy: Arc<dyn Proxy>, depth: usize, visiting: &mut std::collections::BTreeSet<usize>,
            seen: &mut std::collections::BTreeSet<usize>, output: &mut Vec<Arc<dyn Proxy>>)
            -> Result<(), ControlError> {
            let id = Arc::as_ptr(&proxy).cast::<()>() as usize;
            if visiting.contains(&id) || depth > 64 { return Err(ControlError::SelectionMemberNotIntegrated); }
            if !seen.insert(id) { return Ok(()); }
            if proxy.as_any().is_some_and(|value| value.is::<meow_config::ChainedProxyAdapter>()) {
                return Err(ControlError::SelectionMemberNotIntegrated);
            }
            let members = proxy.member_proxies();
            if members.is_some() && !matches!(proxy.adapter_type(), AdapterType::Selector | AdapterType::Fallback | AdapterType::UrlTest) {
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
                for member in members { visit(member, depth + 1, visiting, seen, output)?; }
            }
            visiting.remove(&id);
            output.push(proxy);
            Ok(())
        }
        let route = self.state.tunnel.route_snapshot();
        let mut visiting = std::collections::BTreeSet::new();
        let mut seen = std::collections::BTreeSet::new();
        let mut output = Vec::new();
        for proxy in route.proxies.values() { visit(proxy.clone(), 0, &mut visiting, &mut seen, &mut output)?; }
        Ok(output)
    }

    pub fn activate_groups(self: &Arc<Self>, scope: &Arc<meow_config::owned_load::OwnedLoadScope>)
        -> Result<tokio::task::JoinHandle<()>, ControlError> {
        let gate = self.plane.gate.lock(); self.check_live(&gate)?;
        if scope.is_cancelled() { return Err(ControlError::Cancelled); }
        if !self.state.proxy_providers.is_empty() { return Err(ControlError::SelectionMemberNotIntegrated); }
        {
            let raw = self.state.raw_config.read();
            let mut seen = std::collections::BTreeSet::new();
            for node in raw.proxies.iter().flatten().rev() {
                let Some(name) = node.get("name").and_then(serde_yaml::Value::as_str) else { continue; };
                if !seen.insert(name) { continue; }
                if matches!(name, "DIRECT" | "REJECT" | "REJECT-DROP" | "COMPATIBLE" | "PASS" | "PASS-RULE") { continue; }
                if node.get("dialer-proxy").is_some_and(|value|
                    !value.is_null() && value.as_str().is_none_or(|name| !name.is_empty())) {
                    return Err(ControlError::DialerProxyNotIntegrated);
                }
            }
        }
        let proxies = self.group_members()?;
        if proxies.iter().any(|proxy| proxy.health().observed()) { return Err(ControlError::InvalidSelection); }
        let observer: Arc<dyn meow_common::adapter::HealthObserver> = Arc::new(GroupHealthObserver(Arc::downgrade(self)));
        for proxy in proxies { assert!(proxy.health().observe(observer.clone())); }
        *self.groups.lock() = Some(scope.clone());
        drop(gate);
        let control = self.clone();
        scope.spawn_owned(async move { control.run_group_events().await; }).map_err(|_| ControlError::Cancelled)
    }

    async fn run_group_events(&self) {
        let mut seen = 0;
        loop {
            let changed = self.groups_changed.notified();
            tokio::pin!(changed); changed.as_mut().enable();
            let sequence = self.plane.gate.lock().groups_sequence;
            if sequence > seen { let _ = self.groups_events.send(sequence); seen = sequence; }
            tokio::select! { biased; _ = self.plane.cancel.cancelled() => return, _ = changed => {} }
        }
    }

    fn probe_path(mut member: Arc<dyn Proxy>) -> Result<Vec<Arc<dyn Proxy>>, ControlError> {
        let mut path = Vec::new();
        for _ in 0..=64 {
            path.push(member.clone());
            let Some(members) = member.member_proxies() else { return Ok(path); };
            let name = member.current().ok_or(ControlError::InvalidSelection)?;
            member = members.into_iter().find(|proxy| proxy.name() == name).ok_or(ControlError::InvalidSelection)?;
        }
        Err(ControlError::SelectionMemberNotIntegrated)
    }

    async fn select_owned(&self, group: &str, name: &str, scope: &Arc<meow_config::owned_load::OwnedLoadScope>)
        -> Result<RouteSnapshot, ControlError> {
        let (proxy, member, path, probe, ticket) = {
            let mut gate = self.plane.gate.lock(); self.check_live(&gate)?;
            self.group_members()?;
            let route = self.state.tunnel.route_snapshot();
            let proxy = route.proxies.get(group).ok_or(ControlError::NotFound)?.clone();
            proxy.selection().ok_or(ControlError::InvalidSelection)?;
            let member = proxy.member_proxies().ok_or(ControlError::InvalidSelection)?.into_iter()
                .find(|member| member.name() == name).ok_or(ControlError::InvalidSelection)?;
            let probe = proxy.adapter_type() == AdapterType::Fallback && !member.alive_for_url(proxy.test_url().unwrap_or(""));
            let path = Self::probe_path(member.clone())?;
            gate.selection_serial = gate.selection_serial.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let ticket = gate.selection_serial;
            gate.selection_intents.insert(group.to_owned(), ticket);
            (proxy, member, path, probe, ticket)
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
            let leaf = path.last().unwrap();
            Some(tokio::select! {
                biased;
                _ = self.plane.cancel.cancelled() => return Err(ControlError::Cancelled),
                result = meow_config::probe_leaf_without_record(leaf, proxy.test_url().unwrap_or(""),
                    proxy.expected_status().filter(|status| !status.is_empty()), std::time::Duration::from_secs(5)) => result.unwrap_or(0),
            })
        } else { None };
        let snapshot = {
            let mut gate = self.plane.gate.lock(); self.check_live(&gate)?;
            if scope.is_cancelled() { return Err(ControlError::Cancelled); }
            if gate.selection_intents.get(group) != Some(&ticket) { return Err(ControlError::Superseded); }
            let all = self.group_members()?;
            let route = self.state.tunnel.route_snapshot();
            if !route.proxies.get(group).is_some_and(|live| Arc::ptr_eq(live, &proxy)) {
                return Err(ControlError::Superseded);
            }
            let live = proxy.member_proxies().ok_or(ControlError::InvalidSelection)?.into_iter()
                .find(|live| live.name() == name).ok_or(ControlError::InvalidSelection)?;
            let current_path = Self::probe_path(live.clone())?;
            if !Arc::ptr_eq(&live, &member) || path.len() != current_path.len() ||
                !path.iter().zip(current_path.iter()).all(|(before, after)| Arc::ptr_eq(before, after)) {
                return Err(ControlError::Superseded);
            }
            let next = gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let sequence = gate.groups_sequence.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            selection.commit_memory(name).map_err(|_| ControlError::InvalidSelection)?;
            if let Some(delay) = delay {
                for member in &path { member.health().apply_committed(meow_common::adapter::HealthUpdate::Delay(delay)); }
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
        let admitted = (|| {
            let mut gate = self.plane.gate.lock(); self.check_live(&gate)?;
            if expected_epoch.is_some_and(|epoch| epoch != gate.core_epoch) { return Err(ControlError::Superseded); }
            self.group_members()?;
            let route = self.state.tunnel.route_snapshot();
            let proxy = route.proxies.get(name.as_str()).cloned().ok_or(ControlError::NotFound)?;
            let path = Self::probe_path(proxy.clone())?;
            let keys: Vec<_> = path.iter().map(|member| member.health() as *const _ as usize).collect();
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
            result = meow_config::probe_leaf_without_record(path.last().unwrap(),
                params.url.as_deref().unwrap_or(""), params.expected.as_deref(), timeout) => result,
        };
        let committed = (|| {
            let mut gate = self.plane.gate.lock(); self.check_live(&gate)?;
            if gate.core_epoch != epoch || gate.revision != revision ||
                keys.iter().any(|key| gate.delay_intents.get(key) != Some(&ticket)) {
                return Err(ControlError::Superseded);
            }
            let live = self.state.tunnel.route_snapshot();
            if !live.proxies.get(name.as_str()).is_some_and(|value| Arc::ptr_eq(value, &proxy)) {
                return Err(ControlError::Superseded);
            }
            let current_path = Self::probe_path(proxy.clone())?;
            if path.len() != current_path.len() || !path.iter().zip(&current_path)
                .all(|(before, after)| Arc::ptr_eq(before, after)) { return Err(ControlError::Superseded); }
            let all = self.group_members()?;
            let next = gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let sequence = gate.groups_sequence.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let before = self.snapshot_locked(&gate).picks;
            let fixed_before: Vec<_> = all.iter().map(|member| member.selection().and_then(|selection| selection.fixed())).collect();
            let delay = result.as_ref().copied().unwrap_or(0);
            for member in &path { member.health().apply_committed(meow_common::adapter::HealthUpdate::Delay(delay)); }
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
            Ok(delay) => Json(serde_json::json!({"delay":delay})).into_response(),
            Err(meow_config::DelayProbeError::Timeout) =>
                (StatusCode::GATEWAY_TIMEOUT, Json(serde_json::json!({"message":"Timeout"}))).into_response(),
            Err(meow_config::DelayProbeError::Transport(_)) =>
                (StatusCode::SERVICE_UNAVAILABLE, Json(serde_json::json!({"message":"An error occurred in the delay test"}))).into_response(),
        };
        response.headers_mut().insert("x-aerove-core-epoch", HeaderValue::from_str(&epoch.to_string()).unwrap());
        response.headers_mut().insert("x-aerove-picks-version", HeaderValue::from_str(&revision.to_string()).unwrap());
        response
    }
}

struct GroupDelayAdmission {
    route: Arc<meow_tunnel::tunnel::RouteTable>, group: Arc<dyn Proxy>,
    retained: Vec<Arc<dyn Proxy>>, paths: Vec<Vec<Arc<dyn Proxy>>>,
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
        let target = (|| {
            let gate = self.plane.gate.lock(); self.check_live(&gate)?;
            if expected_epoch.is_some_and(|epoch| epoch != gate.core_epoch) { return Err(ControlError::Superseded); }
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
            let mut gate = self.plane.gate.lock(); self.check_live(&gate)?;
            if scope.is_cancelled() { return Err(ControlError::Cancelled); }
            if expected_epoch.is_some_and(|epoch| epoch != gate.core_epoch) { return Err(ControlError::Superseded); }
            let all = self.group_members()?;
            let live = self.state.tunnel.route_snapshot();
            if !live.proxies.get(name.as_str()).is_some_and(|live| Arc::ptr_eq(live, &group)) {
                return Err(ControlError::Superseded);
            }
            let members = group.member_proxies().ok_or(ControlError::NotFound)?;
            let paths: Vec<_> = members.into_iter().map(|member| Self::probe_path(member)).collect::<Result<_,_>>()?;
            let keys: Vec<_> = paths.iter().flat_map(|path| path.iter().map(|member| member.health() as *const _ as usize)).collect();
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
            // Each child future owns its leaf and parameters. Keep `paths`
            // untouched for the pointer/selection CAS after all probes drop.
            let mut pending = Vec::with_capacity(paths.len());
            for (index, path) in paths.iter().enumerate() {
                let leaf = path.last().unwrap().clone();
                let url = params.url.clone().unwrap_or_default();
                let expected = params.expected.clone();
                pending.push(async move {
                    let result = meow_config::probe_leaf_without_record(&leaf,
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
        let committed = (|| {
            let mut gate = self.plane.gate.lock(); self.check_live(&gate)?;
            if scope.is_cancelled() { return Err(ControlError::Cancelled); }
            if gate.core_epoch != epoch || gate.revision != revision ||
                gate.selection_intents.get(&name) != Some(&ticket) ||
                keys.iter().any(|key| gate.delay_intents.get(key) != Some(&ticket)) { return Err(ControlError::Superseded); }
            let live = self.state.tunnel.route_snapshot();
            if !live.proxies.get(name.as_str()).is_some_and(|live| Arc::ptr_eq(live, &group)) { return Err(ControlError::Superseded); }
            let members = group.member_proxies().ok_or(ControlError::NotFound)?;
            if members.len() != paths.len() { return Err(ControlError::Superseded); }
            for (member, before) in members.into_iter().zip(&paths) {
                let after = Self::probe_path(member)?;
                if before.len() != after.len() || !before.iter().zip(&after).all(|(a,b)| Arc::ptr_eq(a,b)) {
                    return Err(ControlError::Superseded);
                }
            }
            let all = self.group_members()?;
            let next = gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let sequence = gate.groups_sequence.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            let before = self.snapshot_locked(&gate).picks;
            let fixed_before: Vec<_> = all.iter().map(|member| member.selection().and_then(|selection| selection.fixed())).collect();
            for (index, result) in &completed {
                let delay = result.as_ref().copied().unwrap_or(0);
                for member in &paths[*index] { member.health().apply_committed(meow_common::adapter::HealthUpdate::Delay(delay)); }
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
            result.as_ref().ok().map(|delay| (paths[*index][0].name().to_string(), *delay))).collect();
        let mut response = if result.is_empty() {
            (StatusCode::GATEWAY_TIMEOUT, Json(serde_json::json!({"message":"Timeout"}))).into_response()
        } else { Json(result).into_response() };
        response.headers_mut().insert("x-aerove-core-epoch", HeaderValue::from_str(&epoch.to_string()).unwrap());
        response.headers_mut().insert("x-aerove-picks-version", HeaderValue::from_str(&revision.to_string()).unwrap());
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
        response.headers_mut().insert("x-aerove-picks-version",HeaderValue::from_str(&revision.to_string()).unwrap());
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
