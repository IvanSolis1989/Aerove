//! Provider policy timers owned and joined by the existing Native runtime.
use crate::{control::RouteControl, providers::ControlPlane};
use meow_config::owned_load::OwnedLoadScope;
use std::{io, sync::Arc, time::Duration};
use serde::Deserialize;

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "kebab-case", deny_unknown_fields)]
struct Health {
    #[serde(default)] enable: bool,
    #[serde(default)] url: String,
    #[serde(default)] interval: u64,
    #[serde(default)] timeout: u64,
    #[serde(default, deserialize_with = "status")] expected_status: Option<String>,
    #[serde(default = "yes")] lazy: bool,
}
fn yes() -> bool { true }
fn status<'de,D:serde::Deserializer<'de>>(deserializer:D)->Result<Option<String>,D::Error> {
    let value=Option::<serde_yaml::Value>::deserialize(deserializer)?;
    match value {
        None=>Ok(None),Some(serde_yaml::Value::String(value))=>Ok(Some(value)),
        Some(serde_yaml::Value::Number(value))=>Ok(Some(value.to_string())),
        _=>Err(serde::de::Error::custom("expected-status must be a string or status code")),
    }
}

#[derive(Clone, Debug)]
pub struct Policy {
    pub name: String, pub url: String, pub interval: Duration,
    pub timeout: Duration, pub expected: Option<String>, pub lazy: bool,
    pub groups: Vec<String>,
}
impl Policy {
    pub fn parse(yaml: &str) -> io::Result<Vec<Self>> {
        let root: serde_yaml::Value = serde_yaml::from_str(yaml).map_err(io::Error::other)?;
        let mut policies = Vec::new();
        if let Some(providers) = root.get("proxy-providers").and_then(serde_yaml::Value::as_mapping) {
            for (name, raw) in providers {
                let name = name.as_str().ok_or_else(|| io::Error::other("invalidProviderName"))?;
                let Some(health) = raw.get("health-check") else { continue; };
                if health.get("enable").is_some_and(|value|!value.is_bool()) {
                    return Err(io::Error::other(format!("proxy-providers.{name}.health-check.enable: expected boolean")));
                }
                let health: Health = serde_yaml::from_value(health.clone()).map_err(|error|io::Error::other(format!("proxy-providers.{name}.health-check: {error}")))?;
                if !health.enable || health.url.trim().is_empty() { continue; }
                let interval = if health.interval == 0 { 300 } else { health.interval };
                let timeout = if health.timeout == 0 { 5000 } else { health.timeout };
                if interval > meow_common::MAX_DURATION_SECS || timeout > meow_common::MAX_DURATION_SECS.saturating_mul(1000) {
                    return Err(io::Error::other(format!("proxy-providers.{name}.health-check: duration out of range")));
                }
                meow_config::validate_delay_parameters(&health.url, health.expected_status.as_deref())
                    .map_err(|error| io::Error::other(format!("proxy-providers.{name}.health-check: {error:?}")))?;
                let groups = root.get("proxy-groups").and_then(serde_yaml::Value::as_sequence).into_iter().flatten()
                    .filter(|group| group.get("include-all").and_then(serde_yaml::Value::as_bool) == Some(true)
                        || group.get("include-all-providers").and_then(serde_yaml::Value::as_bool) == Some(true)
                        || group.get("use").and_then(serde_yaml::Value::as_sequence).is_some_and(|names| names.iter().any(|item| item.as_str() == Some(name))))
                    .filter_map(|group| group.get("name").and_then(serde_yaml::Value::as_str).map(str::to_owned)).collect();
                policies.push(Self { name: name.to_owned(), url: health.url, interval: Duration::from_secs(interval),
                    timeout: Duration::from_millis(timeout), expected: health.expected_status, lazy: health.lazy, groups });
            }
        }
        Ok(policies)
    }
}

/// Initial check is unconditional, matching Mihomo HealthCheck.process.
pub async fn run(control: Arc<RouteControl>, plane: Arc<ControlPlane>, policy: Policy, scope: Arc<OwnedLoadScope>) {
    let mut ticker = tokio::time::interval(policy.interval);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut completed_source = None;
    loop {
        tokio::select! { biased;
            _ = plane.cancel.cancelled() => return,
            _ = scope.cancelled() => return,
            _ = ticker.tick() => {},
        }
        let stamp = control.provider_health_stamp(&policy.name).ok();
        if stamp.is_some() && (completed_source != stamp || !policy.lazy || control.provider_health_recent_use(&policy)) {
            // Superseded rounds discard results; the next tick retries without publishing a stale timeout.
            // Timer entry retains startup context; refresh transport before capturing this round's ProbeSnapshot.
            let round_control=control.clone();let round_policy=policy.clone();let round_scope=scope.clone();
            let (send,receive)=tokio::sync::oneshot::channel();
            let work=Box::pin(async move {
                let result=round_control.provider_health_round(&round_policy,&round_scope).await;
                let _=send.send(result);
            });
            meow_common::transport_tasks::wrap_current(work).await;
            if let Ok(Ok(stamp))=receive.await { completed_source=Some(stamp); }
        }
    }
}

#[cfg(test)]
#[path = "provider_health_tests.rs"]
mod tests;
