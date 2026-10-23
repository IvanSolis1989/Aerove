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
fn invalid() -> io::Error { io::Error::other("invalidHostPolicyCommand") }

pub(crate) fn execute(owner: &OwnedRuntime, json: &str) -> io::Result<String> {
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
            (actual.status.core_epoch, serde_json::to_value(actual.status.probe_dial).map_err(|_| invalid())?)
        },
        "snapshot" => {
            if request.value.as_object().is_none_or(|value| !value.is_empty()) { return Err(invalid()); }
            let before = owner.route_stamp();
            if before.0 != expected { return Err(io::Error::other("staleCoreEpoch")); }
            let http = owner.policies().status()?;
            let probe = owner.probes().status()?;
            if http.core_epoch != expected.to_string() || probe.core_epoch != expected.to_string() || owner.route_stamp() != before {
                return Err(io::Error::other("policySnapshotChanged"));
            }
            (expected.to_string(), serde_json::json!({"http":http.http,"probeDial":probe.probe_dial}))
        },
        _ => return Err(io::Error::other("unknownHostPolicyOperation")),
    };
    serde_json::to_string(&Receipt { request_id: request.request_id, core_epoch, operation: request.operation, value }).map_err(|_| invalid())
}
