use meow_api::routes::AppState;
use meow_common::{AdapterType, Proxy, TunnelMode};
use serde::Serialize;
use std::{collections::BTreeMap, sync::Arc};
use parking_lot::Mutex;
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
pub enum ControlError { Cancelled, NotFound, InvalidSelection, RevisionExhausted, InvalidEpoch, SelectionMemberNotIntegrated }

struct CommitState { revision: u64, closed: bool }

pub struct RouteControl {
    state: Arc<AppState>,
    epoch: u64,
    gate: Mutex<CommitState>,
    cancel: CancellationToken,
}

impl RouteControl {
    pub fn new(state: Arc<AppState>, epoch: u64, cancel: CancellationToken)
        -> Result<Arc<Self>, ControlError> {
        if epoch == 0 { return Err(ControlError::InvalidEpoch); }
        Ok(Arc::new(Self { state, epoch, gate: Mutex::new(CommitState { revision: 0, closed: false }), cancel }))
    }

    pub fn cancel(&self) {
        self.gate.lock().closed = true;
        self.cancel.cancel();
    }

    fn check_live(&self, gate: &CommitState) -> Result<(), ControlError> {
        if gate.closed || self.cancel.is_cancelled() { Err(ControlError::Cancelled) } else { Ok(()) }
    }

    fn snapshot_locked(&self, revision: u64) -> RouteSnapshot {
        let route = self.state.tunnel.route_snapshot();
        RouteSnapshot {
            core_epoch: self.epoch.to_string(), picks_version: revision.to_string(),
            picks: route.proxies.iter().filter_map(|(name, proxy)| {
                proxy.current().map(|selected| (name.to_string(), selected))
            }).collect(),
        }
    }

    pub async fn snapshot(&self) -> Result<RouteSnapshot, ControlError> {
        let gate = self.gate.lock();
        self.check_live(&gate)?;
        Ok(self.snapshot_locked(gate.revision))
    }

    pub async fn set_mode(&self, mode: TunnelMode) -> Result<RouteSnapshot, ControlError> {
        let mut gate = self.gate.lock();
        self.check_live(&gate)?;
        if self.state.tunnel.mode() != mode {
            let next = gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
            self.state.tunnel.set_mode(mode);
            self.state.raw_config.write().mode = Some(mode.to_string());
            gate.revision = next;
        }
        Ok(self.snapshot_locked(gate.revision))
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
        { self.check_live(&self.gate.lock())?; }
        let route = self.state.tunnel.route_snapshot();
        let proxy = route.proxies.get(group).ok_or(ControlError::NotFound)?;
        Self::validate_prepare_members(proxy, name)?;
        let selection = proxy.selection().ok_or(ControlError::InvalidSelection)?;
        let health = tokio::select! {
            biased;
            _ = self.cancel.cancelled() => return Err(ControlError::Cancelled),
            result = selection.prepare(name) => result.map_err(|_| ControlError::InvalidSelection)?,
        };
        let mut gate = self.gate.lock();
        self.check_live(&gate)?;
        let live = self.state.tunnel.route_snapshot();
        if !live.proxies.get(group).is_some_and(|current| Arc::ptr_eq(current, proxy)) {
            return Err(ControlError::InvalidSelection);
        }
        Self::validate_prepare_members(proxy, name)?;
        let next = gate.revision.checked_add(1).ok_or(ControlError::RevisionExhausted)?;
        selection.commit(name, health).map_err(|_| ControlError::InvalidSelection)?;
        gate.revision = next;
        Ok(self.snapshot_locked(gate.revision))
    }
}

#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct ModeRequest { mode: TunnelMode }

#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct SelectRequest { name: String }

impl ControlError {
    fn response(self) -> axum::response::Response {
        use axum::response::IntoResponse;
        let status = match self {
            Self::Cancelled => axum::http::StatusCode::GONE,
            Self::NotFound => axum::http::StatusCode::NOT_FOUND,
            Self::InvalidSelection | Self::InvalidEpoch => axum::http::StatusCode::BAD_REQUEST,
            Self::RevisionExhausted => axum::http::StatusCode::CONFLICT,
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
