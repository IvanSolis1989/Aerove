//! Private control protocol for the existing owned user-listener domain.
use crate::owned_runtime::OwnedRuntime;
use axum::{body::Bytes, extract::{DefaultBodyLimit, State}, http::StatusCode,
    response::{IntoResponse, Response}, routing::get, Json, Router};
use meow_config::{ListenerSpec, NamedListener};
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, future::Future, sync::{Arc, Weak, atomic::{AtomicBool, Ordering}}};
use tokio::sync::Notify;
use tokio_util::sync::CancellationToken;

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub(crate) struct Apply {
    pub request_id: String,
    pub expected_core_epoch: String,
    pub network: Settings,
}

// Deliberately neither Debug nor Serialize: request credentials never enter a
// diagnostic or GET response. Unknown/duplicate fields are protocol failures.
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "kebab-case")]
pub(crate) struct Settings {
    pub mixed_port: u16,
    #[serde(default)] pub port: u16,
    #[serde(default)] pub socks_port: u16,
    pub allow_lan: bool,
    pub bind_address: String,
    pub authentication: Vec<String>,
}

#[derive(Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub(crate) struct ReadSettings {
    pub mixed_port: u16, pub port: u16, pub socks_port: u16,
    pub allow_lan: bool, pub bind_address: String,
    #[serde(rename = "authEnabled")] pub auth_enabled: bool,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Snapshot {
    pub core_epoch: String, pub network: ReadSettings, pub active_ports: Vec<u16>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Receipt { pub request_id: String, pub core_epoch: String }

#[derive(Default)]
pub(crate) struct StateValue {
    pub settings: Option<ReadSettings>, pub active_ports: Vec<u16>, pub applying: bool,
}

impl Apply {
    pub fn expected(&self) -> Result<u64, Failure> {
        let epoch = self.expected_core_epoch.parse::<u64>().map_err(|_| Failure::invalid())?;
        if epoch == 0 || epoch.to_string() != self.expected_core_epoch
            || uuid::Uuid::parse_str(&self.request_id).ok().is_none_or(|id| id.to_string() != self.request_id) {
            return Err(Failure::invalid());
        }
        Ok(epoch)
    }
}
impl Settings {
    pub fn validate(&self) -> Result<(), Failure> {
        let ports = [self.mixed_port, self.port, self.socks_port];
        if !(1024..=49151).contains(&self.mixed_port)
            || ports.iter().any(|port| *port != 0 && !(1024..=49151).contains(port))
            || ports.iter().enumerate().any(|(index, port)| *port != 0 && ports[..index].contains(port))
            || !matches!((self.allow_lan, self.bind_address.as_str()),
                (false, "127.0.0.1") | (true, "0.0.0.0" | "::")) {
            return Err(Failure::invalid());
        }
        let mut users = std::collections::HashSet::new();
        for credential in &self.authentication {
            let (user, password) = credential.split_once(':').ok_or_else(Failure::invalid)?;
            if user.is_empty() || user.encode_utf16().count() > 128 || password.encode_utf16().count() > 512
                || user.len() > 255 || password.len() > 255
                || credential.chars().any(char::is_control) || !users.insert(user) {
                return Err(Failure::invalid());
            }
        }
        Ok(())
    }
    pub fn readback(&self) -> ReadSettings {
        ReadSettings { mixed_port: self.mixed_port, port: self.port, socks_port: self.socks_port,
            allow_lan: self.allow_lan, bind_address: self.bind_address.clone(), auth_enabled: !self.authentication.is_empty() }
    }
    pub fn listeners(&self, prior: &[NamedListener]) -> Vec<NamedListener> {
        [(ListenerSpec::Mixed, self.mixed_port, "mixed"), (ListenerSpec::Http, self.port, "http"),
            (ListenerSpec::Socks5, self.socks_port, "socks")].into_iter().filter(|(_, port, _)| *port != 0)
            .map(|(spec, port, name)| {
                let previous = prior.iter().find(|entry| entry.spec == spec);
                NamedListener { name: previous.map_or_else(|| name.to_owned(), |entry| entry.name.clone()),
                    spec, port, listen: self.bind_address.clone(), max_connections: previous.map_or(256, |entry| entry.max_connections) }
            }).collect()
    }
    pub fn auth(&self) -> Arc<meow_common::AuthConfig> {
        let credentials: HashMap<String, String> = self.authentication.iter().map(|value| {
            let (user, password) = value.split_once(':').expect("validated credential");
            (user.to_owned(), password.to_owned())
        }).collect();
        Arc::new(meow_common::AuthConfig::new(Arc::new(meow_common::Credentials::new(credentials)), Vec::new()))
    }
}

pub(crate) fn initial(specs: &[NamedListener], allow_lan: bool, bind_address: &str,
    auth: &meow_common::AuthConfig) -> ReadSettings {
    let port = |kind| specs.iter().find(|entry| entry.spec == kind).map_or(0, |entry| entry.port);
    ReadSettings { mixed_port: port(ListenerSpec::Mixed), port: port(ListenerSpec::Http),
        socks_port: port(ListenerSpec::Socks5), allow_lan, bind_address: bind_address.to_owned(), auth_enabled: !auth.credentials.is_empty() }
}

// Dropping a cancelled handler and publishing its receipt share this short
// fence. It never covers socket I/O or a join, and introduces no second actor.
pub(crate) struct Lease { pub cancel: CancellationToken, fence: Arc<Mutex<()>> }
impl Lease {
    pub fn new(fence: Arc<Mutex<()>>) -> Self { Self { cancel: CancellationToken::new(), fence } }
}
impl Drop for Lease {
    fn drop(&mut self) { let _fence = self.fence.lock(); self.cancel.cancel(); }
}

#[derive(Default)]
pub(crate) struct ConnectionExit { finished: AtomicBool, changed: Notify }
tokio::task_local! { static CONNECTION_EXIT: Arc<ConnectionExit>; }
struct ConnectionGuard(Arc<ConnectionExit>);
impl Drop for ConnectionGuard {
    fn drop(&mut self) { self.0.finished.store(true, Ordering::Release); self.0.changed.notify_waiters(); }
}
impl ConnectionExit {
    pub async fn wait(&self) {
        loop {
            let changed=self.changed.notified();tokio::pin!(changed);changed.as_mut().enable();
            if self.finished.load(Ordering::Acquire) { return; }
            changed.await;
        }
    }
}
pub(crate) fn connection_exit() -> Option<Arc<ConnectionExit>> { CONNECTION_EXIT.try_with(Clone::clone).ok() }
pub(crate) async fn connection_scope<F: Future>(work: F) -> F::Output {
    let completed=Arc::new(ConnectionExit::default());let _guard=ConnectionGuard(completed.clone());
    CONNECTION_EXIT.scope(completed,work).await
}

#[derive(Debug)]
pub(crate) struct Failure { pub status: StatusCode, pub code: &'static str, pub fatal: bool }
impl Failure {
    pub fn invalid() -> Self { Self { status: StatusCode::BAD_REQUEST, code: "invalidNetworkRequest", fatal: false } }
    pub fn conflict(code: &'static str) -> Self { Self { status: StatusCode::CONFLICT, code, fatal: false } }
    pub fn exited() -> Self { Self { status: StatusCode::SERVICE_UNAVAILABLE, code: "runtimeExited", fatal: false } }
    pub fn fatal(code: &'static str) -> Self { Self { status: StatusCode::SERVICE_UNAVAILABLE, code, fatal: true } }
}
impl IntoResponse for Failure {
    fn into_response(self) -> Response {
        let mut response=(self.status, Json(serde_json::json!({"error":self.code}))).into_response();
        if self.fatal { response.headers_mut().insert(axum::http::header::CONNECTION, axum::http::HeaderValue::from_static("close")); }
        response
    }
}

pub(crate) fn router(owner: Weak<OwnedRuntime>) -> Router {
    Router::new().route("/aerove/network", get(read).post(apply))
        .layer(DefaultBodyLimit::max(65536)).with_state(owner)
}
async fn read(State(owner): State<Weak<OwnedRuntime>>) -> Result<Json<Snapshot>, Failure> {
    Ok(Json(owner.upgrade().ok_or_else(Failure::exited)?.network_snapshot()?))
}
async fn apply(State(owner): State<Weak<OwnedRuntime>>, bytes: Bytes) -> Result<Json<Receipt>, Failure> {
    let request: Apply = serde_json::from_slice(&bytes).map_err(|_| Failure::invalid())?;
    request.expected()?; request.network.validate()?;
    Ok(Json(owner.upgrade().ok_or_else(Failure::exited)?.network_command(request).await?))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn value() -> serde_json::Value {
        serde_json::json!({"requestId":"11111111-1111-4111-8111-111111111111","expectedCoreEpoch":"1",
            "network":{"mixed-port":17890,"allow-lan":false,"bind-address":"127.0.0.1","authentication":[]}})
    }
    #[test]
    fn strict_network_protocol_matches_kotlin_fields_and_rejects_ambiguous_inputs() {
        let parsed: Apply = serde_json::from_value(value()).unwrap();
        assert_eq!(parsed.expected().unwrap(),1); parsed.network.validate().unwrap();
        let wire=serde_json::to_value(parsed.network.readback()).unwrap();
        assert_eq!(wire.as_object().unwrap().len(),6); assert_eq!(wire["port"],0); assert_eq!(wire["socks-port"],0);
        for epoch in ["9007199254740993","18446744073709551615"] {
            let mut exact=value();exact["expectedCoreEpoch"]=epoch.into();
            assert_eq!(serde_json::from_value::<Apply>(exact).unwrap().expected().unwrap().to_string(),epoch);
        }
        for epoch in ["0","01","+1","18446744073709551616"] {
            let mut bad=value();bad["expectedCoreEpoch"]=epoch.into();
            assert!(serde_json::from_value::<Apply>(bad).unwrap().expected().is_err());
        }
        for bad in [
            {let mut v=value();v["extra"]=true.into();v},
            {let mut v=value();v["network"]["skip-auth-prefixes"]=serde_json::json!([]);v},
            {let mut v=value();v["network"]["mixed-port"]=17890.5.into();v},
            {let mut v=value();v["network"]["authentication"]=serde_json::Value::Null;v},
        ] { assert!(serde_json::from_value::<Apply>(bad).is_err()); }
        let duplicate=r#"{"requestId":"11111111-1111-4111-8111-111111111111","expectedCoreEpoch":"1","network":{"mixed-port":17890,"mixed-port":17891,"allow-lan":false,"bind-address":"127.0.0.1","authentication":[]}}"#;
        assert!(serde_json::from_str::<Apply>(duplicate).is_err());
        for credentials in [vec!["no-colon".to_owned()],vec![":secret".to_owned()],vec!["user:line\nbreak".to_owned()],
            vec![format!("{}:p","x".repeat(129))],vec![format!("user:{}","字".repeat(86))],vec!["same:a".into(),"same:b".into()]] {
            let mut bad=value();bad["network"]["authentication"]=serde_json::json!(credentials);
            assert!(serde_json::from_value::<Apply>(bad).unwrap().network.validate().is_err());
        }
        for (mixed, http, lan, bind) in [(1023,0,false,"127.0.0.1"),(49152,0,false,"127.0.0.1"),
            (17890,17890,false,"127.0.0.1"),(17890,0,false,"0.0.0.0"),(17890,0,true,"localhost")] {
            let mut bad=value();bad["network"]["mixed-port"]=mixed.into();bad["network"]["port"]=http.into();
            bad["network"]["allow-lan"]=lan.into();bad["network"]["bind-address"]=bind.into();
            assert!(serde_json::from_value::<Apply>(bad).unwrap().network.validate().is_err());
        }
    }
    #[test]
    fn explicit_auth_has_no_implicit_loopback_bypass_or_readback_secret() {
        let mut v=value();v["network"]["authentication"]=serde_json::json!(["alice:secret:colon"]);
        let request:Apply=serde_json::from_value(v).unwrap();request.network.validate().unwrap();
        let auth=request.network.auth();
        assert!(auth.credentials.verify("alice","secret:colon"));assert!(!auth.credentials.verify("alice","wrong"));
        assert!(!auth.should_skip(&"127.0.0.1".parse().unwrap()));assert!(!auth.should_skip(&"::1".parse().unwrap()));
        let read=serde_json::to_string(&request.network.readback()).unwrap();
        assert!(!read.contains("alice"));assert!(!read.contains("secret"));assert!(read.contains("\"authEnabled\":true"));
    }
}
