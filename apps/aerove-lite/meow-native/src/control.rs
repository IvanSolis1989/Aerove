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
pub enum ControlError { Cancelled, NotFound, InvalidSelection, RevisionExhausted, InvalidEpoch, SelectionMemberNotIntegrated, Superseded }



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
            Self::SelectionMemberNotIntegrated => axum::http::StatusCode::UNPROCESSABLE_ENTITY,
        };
        let body = if self == Self::SelectionMemberNotIntegrated {
            serde_json::json!({"error": "selectionMemberNotIntegrated", "field": "proxy-groups.proxies",
                "reason": "selection preparation currently requires DIRECT or REJECT leaf members"})
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
        for event in control.plane.provider_baseline() {
            seen.insert((event.kind, event.name.clone()), event.revision.parse::<u64>().expect("canonical provider revision"));
            let frame = serde_json::json!({"type":"providersLoaded","providers":[event]}).to_string();
            if socket.send(Message::Text(frame.into())).await.is_err() { return; }
        }
        loop {
            tokio::select! {
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
        let mut gate = control.plane.gate.lock();
        if control.check_live(&gate).is_err() { return; }
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
            if members.is_some() && !matches!(proxy.adapter_type(), AdapterType::Selector | AdapterType::Fallback) {
                return Err(ControlError::SelectionMemberNotIntegrated);
            }
            if members.is_none() && !matches!(proxy.adapter_type(), AdapterType::Direct | AdapterType::Reject |
                AdapterType::RejectDrop | AdapterType::Compatible | AdapterType::Pass | AdapterType::PassRule |
                AdapterType::Http | AdapterType::Socks5) {
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
