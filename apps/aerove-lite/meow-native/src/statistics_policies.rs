use crate::{providers::ControlPlane, statistics_policy::StatisticsPolicy};
use meow_tunnel::statistics::Statistics;
use parking_lot::RwLock;
use serde::Serialize;
use std::{
    io,
    sync::{Arc, OnceLock},
    time::Instant,
};
use tokio_util::sync::CancellationToken;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatisticsStatus {
    pub core_epoch: String,
    pub only_statistics_proxy: bool,
}

#[derive(Debug, PartialEq, Eq, Serialize)]
pub struct UsageCounters {
    pub up: String,
    pub down: String,
}
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UsageSnapshot {
    pub schema_version: u32,
    pub process_instance: String,
    pub counter_epoch: String,
    pub kind: &'static str,
    pub started_nanos: String,
    pub completed_nanos: String,
    pub r#final: bool,
    pub all: UsageCounters,
    pub proxy: UsageCounters,
}

#[cfg(test)]
mod usage_tests {
    use super::*;
    use meow_tunnel::statistics::ConnCounters;

    fn owner(stats: Arc<Statistics>, cancel: CancellationToken) -> Arc<StatisticsPolicies> {
        StatisticsPolicies::new(
            stats,
            7,
            true,
            ControlPlane::new(1, cancel.clone()).unwrap(),
            cancel,
        )
        .unwrap()
    }

    #[test]
    fn real_scopes_keep_counter_epoch_across_policy_and_observer_rebind() {
        let stats = Arc::new(Statistics::new());
        let direct = ConnCounters::default();
        direct.set_direct(true);
        let proxy = ConnCounters::default();
        proxy.set_direct(false);
        stats.record_upload(&direct, 10);
        stats.record_download(&direct, 20);
        stats.record_upload(&proxy, 30);
        stats.record_download(&proxy, 40);
        let policy = owner(stats.clone(), CancellationToken::new());
        let first = policy.usage_snapshot().unwrap();
        assert_eq!(
            first.all,
            UsageCounters {
                up: "40".into(),
                down: "60".into()
            }
        );
        assert_eq!(
            first.proxy,
            UsageCounters {
                up: "30".into(),
                down: "40".into()
            }
        );
        assert!(!first.r#final);
        policy.apply(1, false).unwrap();
        let changed = policy.usage_snapshot().unwrap();
        assert_eq!(changed.counter_epoch, first.counter_epoch);
        assert_eq!(changed.all, first.all);
        assert_eq!(changed.proxy, first.proxy);
        let rebound = owner(stats, CancellationToken::new())
            .usage_snapshot()
            .unwrap();
        assert_eq!(rebound.process_instance, first.process_instance);
        assert_eq!(rebound.counter_epoch, first.counter_epoch);
        let replacement = owner(Arc::new(Statistics::new()), CancellationToken::new())
            .usage_snapshot()
            .unwrap();
        assert_eq!(replacement.process_instance, first.process_instance);
        assert_ne!(replacement.counter_epoch, first.counter_epoch);
        assert_eq!(
            replacement.all,
            UsageCounters {
                up: "0".into(),
                down: "0".into()
            }
        );
    }

    #[test]
    fn retired_or_invalid_counter_owner_is_unavailable_not_zero() {
        let cancel = CancellationToken::new();
        let stats = Arc::new(Statistics::new());
        let policy = owner(stats.clone(), cancel.clone());
        stats.add_upload(-1);
        assert!(policy.usage_snapshot().is_err());
        let healthy = owner(Arc::new(Statistics::new()), cancel.clone());
        cancel.cancel();
        assert!(healthy.usage_snapshot().is_err());
    }

    #[test]
    fn native_process_nonce_does_not_restart_at_generation_one() {
        let process = USAGE_PROCESS
            .get_or_init(|| (uuid::Uuid::new_v4(), Instant::now()))
            .0
            .simple()
            .to_string();
        if std::env::var_os("AEROVE_USAGE_PROCESS_CHILD").is_some() {
            println!("USAGE_PROCESS={process}");
            return;
        }
        let output = std::process::Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "statistics_policies::usage_tests::native_process_nonce_does_not_restart_at_generation_one", "--nocapture"])
            .env("AEROVE_USAGE_PROCESS_CHILD", "1").output().unwrap();
        assert!(output.status.success());
        let text = String::from_utf8(output.stdout).unwrap();
        let other = text
            .split("USAGE_PROCESS=")
            .nth(1)
            .unwrap()
            .split_whitespace()
            .next()
            .unwrap();
        assert_eq!(other.len(), 32);
        assert_ne!(other, process);
    }
}
static USAGE_PROCESS: OnceLock<(uuid::Uuid, Instant)> = OnceLock::new();
pub struct StatisticsPolicies {
    current: RwLock<Arc<StatisticsPolicy>>,
    stats: Arc<Statistics>,
    generation: u64,
    plane: Arc<ControlPlane>,
    cancel: CancellationToken,
}
impl StatisticsPolicies {
    /// Live atomic loads of both continuously counted scopes; not one four-value cut.
    pub fn usage_snapshot(&self) -> io::Result<UsageSnapshot> {
        let gate = self.plane.gate.lock();
        if gate.closed || self.cancel.is_cancelled() {
            return Err(io::Error::other("usageOwnerRetired"));
        }
        let process = USAGE_PROCESS.get_or_init(|| (uuid::Uuid::new_v4(), Instant::now()));
        let started = process.1.elapsed().as_nanos().to_string();
        let (au, ad) = self.stats.totals_for(false);
        let (pu, pd) = self.stats.totals_for(true);
        let completed = process.1.elapsed().as_nanos().to_string();
        if self.cancel.is_cancelled() || [au, ad, pu, pd].iter().any(|value| *value < 0) {
            return Err(io::Error::other("usageCountersUnavailable"));
        }
        Ok(UsageSnapshot {
            schema_version: 1,
            process_instance: process.0.simple().to_string(),
            counter_epoch: self.stats.counter_epoch().simple().to_string(),
            kind: "sequential-atomic-cumulative",
            started_nanos: started,
            completed_nanos: completed,
            r#final: false,
            all: UsageCounters {
                up: au.to_string(),
                down: ad.to_string(),
            },
            proxy: UsageCounters {
                up: pu.to_string(),
                down: pd.to_string(),
            },
        })
    }
    pub fn new(
        stats: Arc<Statistics>,
        generation: u64,
        only: bool,
        plane: Arc<ControlPlane>,
        cancel: CancellationToken,
    ) -> io::Result<Arc<Self>> {
        let policy = Arc::new(StatisticsPolicy::new(
            stats.clone(),
            generation,
            only,
            cancel.clone(),
        )?);
        stats.set_only_proxy_view(only);
        Ok(Arc::new(Self {
            current: RwLock::new(policy),
            stats,
            generation,
            plane,
            cancel,
        }))
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
        if gate.closed || self.cancel.is_cancelled() {
            return Err(io::Error::other("cancelled"));
        }
        let view = self.current.read().read(gate.core_epoch)?;
        if self.stats.only_proxy_view() != view.only_statistics_proxy {
            return Err(io::Error::other("statisticsViewChanged"));
        }
        Ok(StatisticsStatus {
            core_epoch: view.core_epoch.to_string(),
            only_statistics_proxy: view.only_statistics_proxy,
        })
    }
    pub(crate) fn value_under_plane_gate(&self, epoch: u64) -> io::Result<bool> {
        self.current
            .read()
            .read(epoch)
            .map(|view| view.only_statistics_proxy)
    }
    pub fn apply(&self, expected: u64, only: bool) -> io::Result<StatisticsStatus> {
        let before = self.status()?;
        let candidate = Arc::new(StatisticsPolicy::new(
            self.stats.clone(),
            self.generation,
            only,
            self.cancel.clone(),
        )?);
        let (stamp, retired) = self
            .plane
            .commit_policy(expected, before.only_statistics_proxy != only, || {
                self.stats.set_only_proxy_view(only);
                let mut current = self.current.write();
                std::mem::replace(&mut *current, candidate)
            })
            .map_err(io::Error::other)?;
        drop(retired);
        Ok(StatisticsStatus {
            core_epoch: stamp.0.to_string(),
            only_statistics_proxy: only,
        })
    }
}
