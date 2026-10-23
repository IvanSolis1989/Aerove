use crate::{flow_owner::FlowOwnerLookup, process_policy::{FindProcessMode, ProcessPolicy}, providers::ControlPlane};
use parking_lot::RwLock;
use serde::Serialize;
use std::{io, sync::Arc};
use tokio_util::sync::CancellationToken;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProcessStatus { pub core_epoch: String, pub find_process_mode: &'static str }
pub struct ProcessPolicies {
    current: RwLock<Arc<ProcessPolicy>>,
    generation: u64,
    lookup: RwLock<Option<Arc<dyn FlowOwnerLookup>>>,
    plane: Arc<ControlPlane>,
    cancel: CancellationToken,
}
impl ProcessPolicies {
    pub fn from_host(yaml: &str) -> io::Result<FindProcessMode> {
        let value: serde_yaml::Value = serde_yaml::from_str(yaml).map_err(io::Error::other)?;
        match value.get("find-process-mode") {
            None | Some(serde_yaml::Value::Null) => Ok(FindProcessMode::Strict),
            Some(serde_yaml::Value::String(mode)) => FindProcessMode::parse(mode),
            _ => Err(io::Error::other("invalidFindProcessMode")),
        }
    }
    pub fn new(generation: u64, lookup: Option<Arc<dyn FlowOwnerLookup>>, mode: FindProcessMode,
        plane: Arc<ControlPlane>, cancel: CancellationToken) -> io::Result<Arc<Self>> {
        let policy = ProcessPolicy::new(generation,lookup.clone(),cancel.clone(),mode)?;
        Ok(Arc::new(Self { current: RwLock::new(policy), generation, lookup: RwLock::new(lookup), plane, cancel }))
    }
    pub fn current(&self) -> Arc<ProcessPolicy> { self.current.read().clone() }
    pub(crate) fn mode_under_plane_gate(&self) -> &'static str { self.current.read().mode().wire() }
    pub fn status(&self) -> io::Result<ProcessStatus> {
        let gate = self.plane.gate.lock();
        if gate.closed || self.cancel.is_cancelled() { return Err(io::Error::other("cancelled")); }
        Ok(ProcessStatus { core_epoch: gate.core_epoch.to_string(), find_process_mode: self.current.read().mode().wire() })
    }
    pub fn apply(&self, expected: u64, mode: FindProcessMode, invalidate: bool) -> io::Result<ProcessStatus> {
        let before = self.current();
        let changed = invalidate || before.mode() != mode;
        let candidate = ProcessPolicy::new(self.generation,self.lookup.read().clone(),self.cancel.clone(),mode)?;
        let (stamp, retired) = self.plane.commit_policy(expected,changed,|| {
            let mut current = self.current.write();
            current.retire();
            std::mem::replace(&mut *current,candidate)
        }).map_err(io::Error::other)?;
        drop(retired);
        Ok(ProcessStatus { core_epoch: stamp.0.to_string(), find_process_mode: mode.wire() })
    }
    pub(crate) fn prepare_network_invalidation(&self) -> io::Result<Arc<ProcessPolicy>> {
        ProcessPolicy::new(self.generation,self.lookup.read().clone(),self.cancel.clone(),self.current().mode())
    }
    pub(crate) fn install_network_invalidation(&self, candidate: Arc<ProcessPolicy>) -> Arc<ProcessPolicy> {
        let mut current = self.current.write(); current.retire(); std::mem::replace(&mut *current,candidate)
    }
    pub fn close_after_flows_join(&self) -> io::Result<()> {
        let empty = ProcessPolicy::new(self.generation,None,self.cancel.clone(),FindProcessMode::Off)?;
        let previous = { let mut current = self.current.write(); current.retire(); std::mem::replace(&mut *current,empty) };
        let lookup = self.lookup.write().take();
        drop((previous,lookup));
        Ok(())
    }
}
