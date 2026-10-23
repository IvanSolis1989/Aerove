use crate::{providers::ControlPlane, statistics_policy::StatisticsPolicy};
use meow_tunnel::statistics::Statistics;
use parking_lot::RwLock;
use serde::Serialize;
use std::{io, sync::Arc};
use tokio_util::sync::CancellationToken;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatisticsStatus { pub core_epoch: String, pub only_statistics_proxy: bool }
pub struct StatisticsPolicies {
    current: RwLock<Arc<StatisticsPolicy>>, stats: Arc<Statistics>, generation: u64,
    plane: Arc<ControlPlane>, cancel: CancellationToken,
}
impl StatisticsPolicies {
    pub fn new(stats: Arc<Statistics>, generation: u64, only: bool, plane: Arc<ControlPlane>, cancel: CancellationToken) -> io::Result<Arc<Self>> {
        let policy = Arc::new(StatisticsPolicy::new(stats.clone(),generation,only,cancel.clone())?);
        stats.set_only_proxy_view(only);
        Ok(Arc::new(Self { current: RwLock::new(policy), stats, generation, plane, cancel }))
    }
    pub fn from_host(yaml: &str) -> io::Result<bool> {
        let value: serde_yaml::Value = serde_yaml::from_str(yaml).map_err(io::Error::other)?;
        match value.get("only-statistics-proxy") {
            None => Ok(true),
            Some(serde_yaml::Value::Bool(value)) => Ok(*value),
            _ => Err(io::Error::other("invalidOnlyStatisticsProxy")),
        }
    }
    pub fn status(&self) -> io::Result<StatisticsStatus> {
        let gate = self.plane.gate.lock();
        if gate.closed || self.cancel.is_cancelled() { return Err(io::Error::other("cancelled")); }
        let view = self.current.read().read(gate.core_epoch)?;
        if self.stats.only_proxy_view() != view.only_statistics_proxy { return Err(io::Error::other("statisticsViewChanged")); }
        Ok(StatisticsStatus { core_epoch: view.core_epoch.to_string(), only_statistics_proxy: view.only_statistics_proxy })
    }
    pub(crate) fn value_under_plane_gate(&self, epoch: u64) -> io::Result<bool> { self.current.read().read(epoch).map(|view| view.only_statistics_proxy) }
    pub fn apply(&self, expected: u64, only: bool) -> io::Result<StatisticsStatus> {
        let before = self.status()?;
        let candidate = Arc::new(StatisticsPolicy::new(self.stats.clone(),self.generation,only,self.cancel.clone())?);
        let (stamp, retired) = self.plane.commit_policy(expected,before.only_statistics_proxy != only,|| {
            self.stats.set_only_proxy_view(only);
            let mut current = self.current.write(); std::mem::replace(&mut *current,candidate)
        }).map_err(io::Error::other)?;
        drop(retired);
        Ok(StatisticsStatus { core_epoch: stamp.0.to_string(), only_statistics_proxy: only })
    }
}
