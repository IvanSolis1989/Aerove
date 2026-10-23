use crate::providers::ControlPlane;
use meow_common::request_policy::{PolicySource, RequestPolicy};
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::{io, sync::Arc};
use tokio_util::sync::CancellationToken;

fn invalid(field: &'static str) -> io::Error { io::Error::new(io::ErrorKind::InvalidInput, field) }
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PolicyRequest { schema_version: u8, request_id: String, expected_core_epoch: String, http: RequestPolicy }
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PolicyStatus { pub core_epoch: String, pub http: RequestPolicy }
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PolicyReceipt { pub request_id: String, #[serde(flatten)] pub status: PolicyStatus }
pub struct RequestPolicies { current: RwLock<Arc<RequestPolicy>>, plane: Arc<ControlPlane>, cancel: CancellationToken }
impl RequestPolicies {
    pub fn from_host(yaml: &str) -> io::Result<RequestPolicy> {
        let value: serde_yaml::Value = serde_yaml::from_str(yaml).map_err(|_| invalid("hostPolicyYaml"))?;
        if !value.is_mapping() { return Err(invalid("hostPolicyYaml")); }
        let default = RequestPolicy::default();
        let user_agent = match value.get("global-ua") {
            None | Some(serde_yaml::Value::Null) => default.global_user_agent,
            Some(serde_yaml::Value::String(value)) => value.clone(),
            _ => return Err(invalid("hostPolicy.global-ua")),
        };
        let check_certificate = match value.get("check-certificate") {
            None | Some(serde_yaml::Value::Null) => true,
            Some(serde_yaml::Value::Bool(value)) => *value,
            _ => return Err(invalid("hostPolicy.check-certificate")),
        };
        RequestPolicy::new(user_agent, check_certificate)
    }
    pub fn new(initial: RequestPolicy, plane: Arc<ControlPlane>, cancel: CancellationToken) -> io::Result<Arc<Self>> {
        initial.validate()?;
        Ok(Arc::new(Self { current: RwLock::new(Arc::new(initial)), plane, cancel }))
    }
    pub fn status(&self) -> io::Result<PolicyStatus> {
        let before = self.plane.stamp();
        let policy = self.current.read().clone();
        let after = self.plane.stamp();
        if before != after { return Err(io::Error::new(io::ErrorKind::WouldBlock, "policyChanged")); }
        Ok(PolicyStatus { core_epoch: after.0.to_string(), http: (*policy).clone() })
    }
    pub fn configure_json(&self, json: &str) -> io::Result<PolicyReceipt> {
        if json.len() > 65536 { return Err(invalid("policyRequest")); }
        let request: PolicyRequest = serde_json::from_str(json).map_err(|_| invalid("policyRequest"))?;
        if request.schema_version != 1 { return Err(invalid("policyRequest.schemaVersion")); }
        let id = uuid::Uuid::parse_str(&request.request_id).map_err(|_| invalid("policyRequest.requestId"))?;
        if id.is_nil() || id.hyphenated().to_string() != request.request_id { return Err(invalid("policyRequest.requestId")); }
        let expected = request.expected_core_epoch.parse::<u64>().ok().filter(|epoch| *epoch != 0)
            .filter(|epoch| epoch.to_string() == request.expected_core_epoch).ok_or_else(|| invalid("policyRequest.expectedCoreEpoch"))?;
        request.http.validate()?;
        let before = self.current.read().clone();
        let changed = before.as_ref() != &request.http;
        let policy = Arc::new(request.http);
        let (stamp, installed) = self.plane.commit_policy(expected, changed, || {
            let mut current = self.current.write();
            let retired = std::mem::replace(&mut *current, policy);
            (retired, current.clone())
        }).map_err(io::Error::other)?;
        let actual = match installed { Some((retired, actual)) => { drop(retired); actual }, None => before };
        Ok(PolicyReceipt { request_id: request.request_id, status: PolicyStatus { core_epoch: stamp.0.to_string(), http: (*actual).clone() } })
    }
}
impl PolicySource for RequestPolicies {
    fn snapshot(&self) -> io::Result<Arc<RequestPolicy>> {
        if self.cancel.is_cancelled() { return Err(io::Error::from(io::ErrorKind::Interrupted)); }
        let policy = self.current.read().clone();
        if self.cancel.is_cancelled() { return Err(io::Error::from(io::ErrorKind::Interrupted)); }
        Ok(policy)
    }
}
