use crate::owned_runtime::OwnedRuntime;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::io;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Request {
    schema_version: u32,
    request_id: String,
    expected_core_epoch: String,
    operation: String,
    value: Value,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Receipt { request_id: String, core_epoch: String, operation: String, value: Value }
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProcessValue { find_process_mode: String }
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StatisticsValue { only_statistics_proxy: bool }
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct UserValue { pub enabled: bool, pub secret: String }
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct NetworkValue { intent: String, physical_network: crate::runtime_plan::PhysicalNetwork }
pub(crate) fn startup_user(yaml: &str) -> io::Result<UserValue> {
    let value: serde_yaml::Value = serde_yaml::from_str(yaml).map_err(|_| invalid())?;
    match value.get("user-controller") {
        None => Ok(UserValue { enabled: false, secret: String::new() }),
        Some(value) => serde_yaml::from_value(value.clone()).map_err(|_| invalid()),
    }
}
fn canonical(value: &str) -> io::Result<u64> { value.parse::<u64>().ok().filter(|number| *number != 0 && number.to_string() == value).ok_or_else(invalid) }
fn invalid() -> io::Error { io::Error::other("invalidHostPolicyCommand") }

pub(crate) async fn execute(owner: &OwnedRuntime, json: &str) -> io::Result<String> {
    let request: Request = serde_json::from_str(json).map_err(|_| invalid())?;
    if request.schema_version != 1 { return Err(invalid()); }
    let id = uuid::Uuid::parse_str(&request.request_id).map_err(|_| invalid())?;
    if id.is_nil() || id.hyphenated().to_string() != request.request_id { return Err(invalid()); }
    let expected = request.expected_core_epoch.parse::<u64>().ok()
        .filter(|value| *value != 0 && value.to_string() == request.expected_core_epoch).ok_or_else(invalid)?;
    let (core_epoch, value) = match request.operation.as_str() {
        "probeDial" => {
            let policy: meow_common::probe_policy::ProbePolicy = serde_json::from_value(request.value).map_err(|_| invalid())?;
            let command = serde_json::json!({"schemaVersion":1,"requestId":request.request_id,
                "expectedCoreEpoch":request.expected_core_epoch,"probeDial":policy});
            let actual = owner.probes().apply(&command.to_string())?;
            owner.publish_probe_policy(&actual.status.probe_dial);
            (actual.status.core_epoch, serde_json::to_value(actual.status.probe_dial).map_err(|_| invalid())?)
        },
        "http" => {
            let http: meow_common::request_policy::RequestPolicy = serde_json::from_value(request.value).map_err(|_| invalid())?;
            let actual = owner.policies().configure_json(&serde_json::json!({"schemaVersion":1,"requestId":request.request_id,
                "expectedCoreEpoch":request.expected_core_epoch,"http":http}).to_string())?;
            (actual.status.core_epoch,serde_json::to_value(actual.status.http).map_err(|_| invalid())?)
        },
        "statistics" => {
            let value: StatisticsValue = serde_json::from_value(request.value).map_err(|_| invalid())?;
            let actual = owner.statistics()?.apply(expected,value.only_statistics_proxy)?;
            owner.publish_statistics(actual.only_statistics_proxy);
            (actual.core_epoch,serde_json::json!({"onlyStatisticsProxy":actual.only_statistics_proxy}))
        },
        "userController" => {
            let value: UserValue = serde_json::from_value(request.value).map_err(|_| invalid())?;
            let actual = owner.user_controller()?.configure(expected,value.enabled,&value.secret).await?;
            (actual.core_epoch.clone(),serde_json::to_value(actual).map_err(|_| invalid())?)
        },
        "network" => {
            let value: NetworkValue = serde_json::from_value(request.value).map_err(|_| invalid())?;
            let intent = canonical(&value.intent)?;
            let handle = canonical(value.physical_network.handle.as_deref().ok_or_else(invalid)?)?;
            let actual = owner.network_policy()?.apply(crate::network_policy::CapturedPhysicalNetwork {
                intent,core_epoch:expected,network_handle:handle,dns:value.physical_network.dns,
            }).await.map_err(io::Error::other)?;
            if !(actual.resolver_readback && actual.network_readback && actual.retired_dns_joined) { return Err(io::Error::other("networkReadbackFailed")); }
            (actual.core_epoch.to_string(),serde_json::json!({"intent":actual.intent.to_string(),"handle":actual.network_handle.to_string(),
                "dnsServers":actual.dns_servers.iter().map(ToString::to_string).collect::<Vec<_>>(),
                "resolverReadback":actual.resolver_readback,"networkReadback":actual.network_readback,"retiredDnsJoined":actual.retired_dns_joined}))
        },
        "process" => {
            let value: ProcessValue = serde_json::from_value(request.value).map_err(|_| invalid())?;
            let mode = crate::process_policy::FindProcessMode::parse(&value.find_process_mode)?;
            let actual = owner.processes().apply(expected,mode,false)?;
            owner.publish_process_mode(actual.find_process_mode);
            (actual.core_epoch,serde_json::json!({"findProcessMode":actual.find_process_mode}))
        },
        "invalidateIdentity" => {
            if request.value.as_object().is_none_or(|value| !value.is_empty()) { return Err(invalid()); }
            let mode = owner.processes().current().mode();
            let actual = owner.processes().apply(expected,mode,true)?;
            (actual.core_epoch,serde_json::json!({"findProcessMode":actual.find_process_mode,"invalidated":true}))
        },
        "snapshot" => {
            if request.value.as_object().is_none_or(|value| !value.is_empty()) { return Err(invalid()); }
            let before = owner.route_stamp();
            if before.0 != expected { return Err(io::Error::other("staleCoreEpoch")); }
            let http = owner.policies().status()?;
            let probe = owner.probes().status()?;
            let process = owner.processes().status()?;
            let statistics = owner.statistics()?.status()?;
            let controller = owner.user_controller()?.status().await?;
            let (handle,servers) = owner.network_identity();
            if http.core_epoch != expected.to_string() || probe.core_epoch != expected.to_string() || process.core_epoch != expected.to_string()
                || statistics.core_epoch != expected.to_string() || controller.core_epoch != expected.to_string() || owner.route_stamp() != before {
                return Err(io::Error::other("policySnapshotChanged"));
            }
            (expected.to_string(), serde_json::json!({"http":http.http,"probeDial":probe.probe_dial,"findProcessMode":process.find_process_mode,
                "physicalNetwork":{"handle":handle.map(|handle|handle.to_string()),"dnsServers":servers.unwrap_or_default().iter().map(ToString::to_string).collect::<Vec<_>>(),"intent":owner.network_policy()?.latest_intent().to_string()},
                "userController":controller,"statistics":{"onlyStatisticsProxy":statistics.only_statistics_proxy},"privateCorsOrigins":[]}))
        },
        _ => return Err(io::Error::other("unknownHostPolicyOperation")),
    };
    serde_json::to_string(&Receipt { request_id: request.request_id, core_epoch, operation: request.operation, value }).map_err(|_| invalid())
}
