use meow_common::probe_policy::{ProbePolicy, ProbeScope, ProbeSource};
use parking_lot::{Mutex, RwLock};
use tokio::sync::Notify;
use serde::{Deserialize, Serialize};
use std::{future::Future, io, pin::Pin, sync::Arc};
use tokio_util::sync::CancellationToken;
use crate::providers::ControlPlane;

struct Work { active: Mutex<usize>, done: Notify }
struct WorkLease(Arc<Work>);
impl Drop for WorkLease {
    fn drop(&mut self) {
        let complete = { let mut active = self.0.active.lock(); *active -= 1; *active == 0 };
        if complete { self.0.done.notify_waiters(); }
    }
}
pub struct ProbePolicies { current: RwLock<Arc<ProbePolicy>>, plane: Arc<ControlPlane>, cancel: CancellationToken, work: Arc<Work> }
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeStatus { pub core_epoch: String, pub probe_dial: ProbePolicy }
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Apply { schema_version: u32, request_id: String, expected_core_epoch: String, probe_dial: ProbePolicy }
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeReceipt { pub request_id: String, pub status: ProbeStatus }
fn invalid() -> io::Error { io::Error::new(io::ErrorKind::InvalidInput, "invalidProbePolicy") }
struct CapturedProbeSource { policy: Arc<ProbePolicy>, epoch: u64, owner: Arc<ProbePolicies> }
impl ProbeSource for CapturedProbeSource {
    fn snapshot(&self) -> io::Result<Arc<ProbePolicy>> { Ok(self.policy.clone()) }
    fn snapshot_with_epoch(&self) -> io::Result<(Arc<ProbePolicy>, Option<u64>)> { Ok((self.policy.clone(),Some(self.epoch))) }
    fn enter(&self) -> io::Result<Box<dyn Send>> { self.owner.enter() }
    fn cancelled(&self) -> Pin<Box<dyn Future<Output = ()> + Send + '_>> { self.owner.cancelled() }
}
impl ProbePolicies {
    pub fn from_host(yaml: &str) -> io::Result<ProbePolicy> {
        let value: serde_yaml::Value = serde_yaml::from_str(yaml).map_err(|_| invalid())?;
        let fields = value.as_mapping().ok_or_else(invalid)?;
        let read = |key: &str| match fields.get(&serde_yaml::Value::String(key.into())) {
            None => Ok(true), Some(serde_yaml::Value::Bool(value)) => Ok(*value), _ => Err(invalid()),
        };
        Ok(ProbePolicy { unified_delay: read("unified-delay")?, tcp_concurrent: read("tcp-concurrent")? })
    }
    pub fn new(initial: ProbePolicy, plane: Arc<ControlPlane>, cancel: CancellationToken) -> Arc<Self> {
        Arc::new(Self { current: RwLock::new(Arc::new(initial)), plane, cancel, work: Arc::new(Work { active: Mutex::new(0), done: Notify::new() }) })
    }
    pub async fn close_and_join(&self) {
        self.cancel.cancel();
        loop {
            let notified = self.work.done.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            if *self.work.active.lock() == 0 { return; }
            notified.await;
        }
    }
    pub fn scope(self: &Arc<Self>) -> ProbeScope { ProbeScope::new(self.clone()) }
    pub(crate) fn frozen_scope_under_plane_gate(self: &Arc<Self>, epoch: u64) -> ProbeScope {
        ProbeScope::new(Arc::new(CapturedProbeSource { policy: self.current.read().clone(), epoch, owner: self.clone() }))
    }

    pub(crate) fn policy_under_plane_gate(&self) -> ProbePolicy { (**self.current.read()).clone() }
    pub fn status(&self) -> io::Result<ProbeStatus> {
        let gate = self.plane.gate.lock();
        if gate.closed || self.cancel.is_cancelled() { return Err(io::Error::from(io::ErrorKind::Interrupted)); }
        Ok(ProbeStatus { core_epoch: gate.core_epoch.to_string(), probe_dial: (**self.current.read()).clone() })
    }
    pub fn apply(&self, json: &str) -> io::Result<ProbeReceipt> {
        if json.len() > 16 * 1024 { return Err(invalid()); }
        let request: Apply = serde_json::from_str(json).map_err(|_| invalid())?;
        if request.schema_version != 1 { return Err(invalid()); }
        let request_id = uuid::Uuid::parse_str(&request.request_id).map_err(|_| invalid())?;
        if request_id.is_nil() || request_id.hyphenated().to_string() != request.request_id { return Err(invalid()); }
        let expected = request.expected_core_epoch.parse::<u64>().ok()
            .filter(|epoch| *epoch != 0 && epoch.to_string() == request.expected_core_epoch).ok_or_else(invalid)?;
        let before = self.current.read().clone();
        let changed = before.as_ref() != &request.probe_dial;
        let policy = Arc::new(request.probe_dial);
        let (stamp, installed) = self.plane.commit_policy(expected, changed, || {
            let mut current = self.current.write();
            let retired = std::mem::replace(&mut *current, policy);
            (retired, current.clone())
        }).map_err(io::Error::other)?;
        let actual = match installed { Some((retired, actual)) => { drop(retired); actual }, None => before };
        Ok(ProbeReceipt { request_id: request.request_id, status: ProbeStatus { core_epoch: stamp.0.to_string(), probe_dial: (*actual).clone() } })
    }
}
impl ProbeSource for ProbePolicies {
    fn snapshot_with_epoch(&self) -> io::Result<(Arc<ProbePolicy>,Option<u64>)> {
        let gate = self.plane.gate.lock();
        if gate.closed || self.cancel.is_cancelled() { return Err(io::Error::from(io::ErrorKind::Interrupted)); }
        Ok((self.current.read().clone(),Some(gate.core_epoch)))
    }
    fn enter(&self) -> io::Result<Box<dyn Send>> {
        let mut active = self.work.active.lock();
        if self.cancel.is_cancelled() { return Err(io::Error::from(io::ErrorKind::Interrupted)); }
        *active = active.checked_add(1).ok_or_else(|| io::Error::other("probe work limit"))?;
        Ok(Box::new(WorkLease(self.work.clone())))
    }
    fn snapshot(&self) -> io::Result<Arc<ProbePolicy>> {
        if self.cancel.is_cancelled() { return Err(io::Error::from(io::ErrorKind::Interrupted)); }
        let policy = self.current.read().clone();
        if self.cancel.is_cancelled() { return Err(io::Error::from(io::ErrorKind::Interrupted)); }
        Ok(policy)
    }
    fn cancelled(&self) -> Pin<Box<dyn Future<Output = ()> + Send + '_>> { Box::pin(self.cancel.cancelled()) }
}

pub fn scope_router(router: axum::Router, owner: &Arc<ProbePolicies>) -> axum::Router {
    router.layer(axum::middleware::from_fn_with_state(owner.scope(), |axum::extract::State(scope): axum::extract::State<ProbeScope>, request: axum::extract::Request, next: axum::middleware::Next| async move {
        use axum::response::IntoResponse;
        match scope.run_owned(next.run(request)).await {
            Ok(response) => response,
            Err(_) => axum::http::StatusCode::SERVICE_UNAVAILABLE.into_response(),
        }
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn startup_defaults_and_both_boolean_values_are_real_typed_policy() {
        assert_eq!(ProbePolicies::from_host("{}").unwrap(), ProbePolicy::default());
        let disabled = ProbePolicies::from_host("unified-delay: false\ntcp-concurrent: false").unwrap();
        assert!(!disabled.unified_delay && !disabled.tcp_concurrent);
        assert!(ProbePolicies::from_host("unified-delay: 'false'").is_err());
    }
    #[tokio::test]
    async fn closing_session_joins_idle_work_and_rejects_late_spawn() {
        let cancel = CancellationToken::new();
        let plane = ControlPlane::new(31, cancel.clone()).unwrap();
        let owner = ProbePolicies::new(ProbePolicy::default(), plane, cancel);
        let scope = owner.scope();
        let (entered, ready) = tokio::sync::oneshot::channel();
        let task = tokio::spawn(async move { scope.run_owned(async {
            entered.send(()).unwrap(); std::future::pending::<()>().await;
        }).await });
        ready.await.unwrap();
        owner.close_and_join().await;
        assert!(task.await.unwrap().is_err());
        assert!(owner.scope().run_owned(async { panic!("closed session must not poll work") }).await.is_err());
    }
    #[tokio::test]
    async fn hot_policy_uses_existing_epoch_cas_and_closed_session_rejects_late_apply() {
        let cancel = CancellationToken::new();
        let plane = ControlPlane::new(31, cancel.clone()).unwrap();
        let owner = ProbePolicies::new(ProbePolicy::default(), plane.clone(), cancel.clone());
        let scope = owner.scope();
        let old = scope.scope(async { meow_common::probe_policy::capture().unwrap().unwrap() }).await;
        let command = r#"{"schemaVersion":1,"requestId":"a687578a-10f5-4a78-a924-44357aa95ae7","expectedCoreEpoch":"31","probeDial":{"unifiedDelay":false,"tcpConcurrent":false}}"#;
        let receipt = owner.apply(command).unwrap();
        assert_ne!(receipt.status.core_epoch, "31");
        assert!(!receipt.status.probe_dial.unified_delay && !receipt.status.probe_dial.tcp_concurrent);
        assert!(owner.apply(command).is_err());
        assert!(old.policy.unified_delay && old.policy.tcp_concurrent);
        let fresh = scope.scope(async { meow_common::probe_policy::capture().unwrap().unwrap() }).await;
        assert!(!fresh.policy.unified_delay && !fresh.policy.tcp_concurrent);
        cancel.cancel();
        assert!(owner.status().is_err());
        assert!(owner.apply(command).unwrap_err().to_string().contains("cancelled"));
        assert!(scope.scope(async { meow_common::probe_policy::capture() }).await.is_err());
        assert!(old.run(async { Ok(()) }).await.is_err());
    }
    #[test]
    fn completion_wakes_after_releasing_probe_work_gate() {
        use std::task::{Context, Poll, Wake, Waker};
        struct Check(Arc<Work>);
        impl Wake for Check {
            fn wake(self: Arc<Self>) { self.wake_by_ref(); }
            fn wake_by_ref(self: &Arc<Self>) { assert!(self.0.active.try_lock().is_some(), "completion must not wake while holding the work gate"); }
        }
        let work = Arc::new(Work { active: Mutex::new(1), done: Notify::new() });
        let waker = Waker::from(Arc::new(Check(work.clone()))); let mut context = Context::from_waker(&waker);
        let mut notified = Box::pin(work.done.notified());
        assert!(matches!(notified.as_mut().poll(&mut context),Poll::Pending));
        drop(WorkLease(work.clone()));
        assert!(matches!(notified.as_mut().poll(&mut context),Poll::Ready(())));
        assert_eq!(*work.active.lock(),0);
    }

}
