use axum::{Json, Router, extract::State, http::{HeaderMap, StatusCode}, routing::get};
use meow_tunnel::Tunnel;
use parking_lot::Mutex;
use serde::Serialize;
use std::{collections::BTreeMap, net::SocketAddr, sync::{Arc, OnceLock, Weak, atomic::{AtomicU64, Ordering}}};
use tokio::{net::{TcpListener, TcpStream}, sync::watch, task::{JoinHandle, JoinSet}};
use tokio_util::sync::CancellationToken;

const MAX_CONFIG_BYTES: usize = 4 * 1024 * 1024;
const MAX_CONNECTIONS: usize = 256;
static NEXT_GENERATION: AtomicU64 = AtomicU64::new(1);
static OWNER: OnceLock<Mutex<Weak<Session>>> = OnceLock::new();

pub struct StartOptions {
    pub config_yaml: String,
    pub private_api: SocketAddr,
    pub mixed: SocketAddr,
    pub secret: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Phase { Loading, Running, Stopped, Failed }

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Failure {
    pub code: &'static str,
    pub field: Option<String>,
}

impl Failure {
    fn new(code: &'static str) -> Self { Self { code, field: None } }
    fn field(field: &str) -> Self {
        Self { code: "candidateFieldNotIntegrated", field: Some(field.to_owned()) }
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    pub generation: String,
    pub phase: Phase,
    pub runtime_mode: &'static str,
    pub api_address: Option<SocketAddr>,
    pub mixed_address: Option<SocketAddr>,
    pub api_ready: bool,
    pub user_listeners_ready: bool,
    pub tun_ready: bool,
    pub failure: Option<Failure>,
    pub upload_bytes: String,
    pub download_bytes: String,
    pub proxy_traffic: BTreeMap<String, (String, String)>,
}

pub struct Session {
    generation: u64,
    cancel: CancellationToken,
    state: watch::Sender<Snapshot>,
    task: tokio::sync::Mutex<Option<JoinHandle<()>>>,
    stop_gate: tokio::sync::Mutex<()>,
    tunnel: Mutex<Option<Tunnel>>,
    control: Mutex<Option<Arc<crate::control::RouteControl>>>,
}

pub struct Registry;

impl Registry {
    pub fn current() -> Option<Arc<Session>> {
        OWNER.get_or_init(|| Mutex::new(Weak::new())).lock().upgrade()
    }

    /// Reserves the sole process owner before asynchronous config loading starts.
    pub fn start(options: StartOptions) -> Result<Arc<Session>, Failure> {
        let runtime = tokio::runtime::Handle::try_current().map_err(|_| Failure::new("runtimeUnavailable"))?;
        let mut owner = OWNER.get_or_init(|| Mutex::new(Weak::new())).lock();
        if owner.upgrade().is_some_and(|s| !s.is_terminal()) {
            return Err(Failure::new("runtimeAlreadyOwned"));
        }
        let generation = loop {
            let n = NEXT_GENERATION.load(Ordering::Relaxed);
            let next = n.checked_add(1).ok_or_else(|| Failure::new("generationExhausted"))?;
            if NEXT_GENERATION.compare_exchange_weak(n, next, Ordering::Relaxed, Ordering::Relaxed).is_ok() {
                break n;
            }
        };
        let (state, _) = watch::channel(Snapshot {
            generation: generation.to_string(), phase: Phase::Loading, runtime_mode: "proxy",
            api_address: None, mixed_address: None, api_ready: false,
            user_listeners_ready: false, tun_ready: false, failure: None,
            upload_bytes: "0".into(), download_bytes: "0".into(),
            proxy_traffic: BTreeMap::new(),
        });
        let session = Arc::new(Session { generation, cancel: CancellationToken::new(), state,
            task: tokio::sync::Mutex::new(None), stop_gate: tokio::sync::Mutex::new(()),
            tunnel: Mutex::new(None), control: Mutex::new(None) });
        *owner = Arc::downgrade(&session);
        let worker = session.clone();
        // Store without an await so stop cannot overtake task registration.
        let task = runtime.spawn(async move { worker.run(options).await; });
        *session.task.try_lock().expect("new session task mutex") = Some(task);
        Ok(session)
    }
}

impl Session {
    pub fn generation(&self) -> u64 { self.generation }

    pub fn snapshot(&self) -> Snapshot {
        let mut result = self.state.borrow().clone();
        if let Some(tunnel) = self.tunnel.lock().as_ref() {
            let (up, down) = tunnel.statistics().snapshot();
            result.upload_bytes = up.to_string();
            result.download_bytes = down.to_string();
            result.proxy_traffic = tunnel.statistics().proxy_traffic().into_iter()
                .map(|(name, (up, down))| (name.to_string(), (up.to_string(), down.to_string())))
                .collect();
        }
        result
    }

    pub fn subscribe(&self) -> watch::Receiver<Snapshot> { self.state.subscribe() }

    pub async fn ready(&self) -> Result<Snapshot, Failure> {
        let mut state = self.subscribe();
        loop {
            let snapshot = state.borrow_and_update().clone();
            match snapshot.phase {
                Phase::Running => return Ok(snapshot),
                Phase::Failed => return Err(snapshot.failure.expect("failed receipt")),
                Phase::Stopped => return Err(Failure::new("cancelled")),
                Phase::Loading => {}
            }
            state.changed().await.map_err(|_| Failure::new("ownerExited"))?;
        }
    }

    pub async fn stop(&self) -> Result<Snapshot, Failure> {
        {
            let control = self.control.lock();
            if let Some(control) = control.as_ref() { control.cancel(); }
            self.cancel.cancel();
        }
        let _stop = self.stop_gate.lock().await;
        let mut task_slot = self.task.lock().await;
        if let Some(task) = task_slot.as_mut() {
            let joined = task.await;
            drop(task_slot.take());
            joined.map_err(|_| Failure::new("ownerPanicked"))?;
        }
        Ok(self.snapshot())
    }

    fn is_terminal(&self) -> bool {
        matches!(self.state.borrow().phase, Phase::Stopped | Phase::Failed)
    }

    async fn run(self: Arc<Self>, options: StartOptions) {
        let outcome = self.serve(options).await;
        // All accepted transports have joined before either terminal receipt.
        let final_snapshot = self.snapshot();
        let old_control = self.control.lock().take();
        drop(old_control);
        let old = self.tunnel.lock().take();
        drop(old);
        self.state.send_modify(|s| {
            s.phase = if outcome.is_err() { Phase::Failed } else { Phase::Stopped };
            s.failure = outcome.err();
            s.api_ready = false;
            s.user_listeners_ready = false;
            s.api_address = None;
            s.mixed_address = None;
            s.upload_bytes = final_snapshot.upload_bytes;
            s.download_bytes = final_snapshot.download_bytes;
            s.proxy_traffic = final_snapshot.proxy_traffic;
        });
    }

    async fn serve(self: &Arc<Self>, options: StartOptions) -> Result<(), Failure> {
        if self.cancel.is_cancelled() { return Ok(()); }
        if !options.private_api.ip().is_loopback() || !options.mixed.ip().is_loopback() {
            return Err(Failure::new("candidateRequiresLoopback"));
        }
        if options.secret.len() < 32 || options.secret.len() > 512 || !options.secret.is_ascii()
            || options.secret.bytes().any(|b| b.is_ascii_control()) {
            return Err(Failure::new("invalidPrivateSecret"));
        }
        validate_candidate(&options.config_yaml)?;
        let config = tokio::select! {
            biased;
            _ = self.cancel.cancelled() => return Ok(()),
            config = meow_config::load_config_from_str(&options.config_yaml) =>
                config.map_err(|_| Failure::new("invalidConfig"))?,
        };
        if self.cancel.is_cancelled() { return Ok(()); }
        let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
        tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
        tunnel.set_mode(config.general.mode);
        tunnel.set_connection_notes(config.general.connection_notes);
        let api = TcpListener::bind(options.private_api).await
            .map_err(|_| Failure::new("privateApiBindFailed"))?;
        let mixed = TcpListener::bind(options.mixed).await
            .map_err(|_| Failure::new("mixedBindFailed"))?;
        let api_addr = api.local_addr().map_err(|_| Failure::new("privateApiAddressFailed"))?;
        let mixed_addr = mixed.local_addr().map_err(|_| Failure::new("mixedAddressFailed"))?;
        if self.cancel.is_cancelled() { return Ok(()); }
        let aerove_router = Router::new().route("/aerove/runtime", get(api_snapshot))
            .with_state(ApiState { session: Arc::downgrade(self), secret: options.secret.clone() });
        let core_api = crate::api::CoreApi::assemble_with_epoch(&config, tunnel.clone(), mixed_addr,
            &options.secret, None, aerove_router, self.generation, self.cancel.clone());
        let core_api = core_api.map_err(|error| Failure::new(error.0))?;
        {
            let mut control = self.control.lock();
            if self.cancel.is_cancelled() { core_api.control.cancel(); }
            *control = Some(core_api.control.clone());
        }
        core_api.install_log_sink().map_err(|error| Failure::new(error.0))?;
        tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
        *self.tunnel.lock() = Some(tunnel.clone());
        let router = core_api.private_router();
        {
            let _control_admission = self.control.lock();
            self.state.send_modify(|s| {
                if self.cancel.is_cancelled() { return; }
                s.phase = Phase::Running; s.api_address = Some(api_addr); s.mixed_address = Some(mixed_addr);
                s.api_ready = true; s.user_listeners_ready = true;
            });
        }
        let mut children = JoinSet::new();
        let outcome = loop {
            tokio::select! {
                biased;
                _ = self.cancel.cancelled() => break Ok(()),
                result = children.join_next(), if !children.is_empty() => {
                    if result.is_some_and(|r| r.is_err()) { break Err(Failure::new("connectionTaskPanicked")); }
                }
                accepted = api.accept(), if children.len() < MAX_CONNECTIONS => {
                    let (stream, _) = match accepted { Ok(s) => s, Err(_) => break Err(Failure::new("privateApiAcceptFailed")) };
                    children.spawn(crate::api::serve_connection(stream, router.clone()));
                }
                accepted = mixed.accept(), if children.len() < MAX_CONNECTIONS => {
                    let (stream, peer) = match accepted { Ok(s) => s, Err(_) => break Err(Failure::new("mixedAcceptFailed")) };
                    let tunnel = tunnel.clone();
                    let auth = config.auth.clone();
                    children.spawn(async move {
                        mixed_connection(tunnel, stream, peer, mixed_addr.port(), auth).await;
                    });
                }
            }
        };
        drop(api);
        drop(mixed);
        core_api.cancel();
        tunnel.statistics().close_all_connections();
        tunnel.close_all_udp_sessions();
        children.abort_all();
        while children.join_next().await.is_some() {}
        core_api.join().await;
        outcome
    }
}

#[derive(Clone)]
struct ApiState { session: Weak<Session>, secret: String }

async fn api_snapshot(State(state): State<ApiState>, headers: HeaderMap)
    -> Result<Json<Snapshot>, StatusCode> {
    let expected = format!("Bearer {}", state.secret);
    if headers.get("authorization").map(|v| v.as_bytes()) != Some(expected.as_bytes()) {
        return Err(StatusCode::UNAUTHORIZED);
    }
    state.session.upgrade().map(|s| Json(s.snapshot())).ok_or(StatusCode::GONE)
}

// Uses the exact dispatch boundary of meow-listener/mixed.rs, but owns each task.
async fn mixed_connection(tunnel: Tunnel, stream: TcpStream, peer: SocketAddr, port: u16,
    auth: Arc<meow_common::AuthConfig>) {
    let mut first = [0];
    if !matches!(tokio::time::timeout(meow_listener::DEFAULT_HANDSHAKE_TIMEOUT,
        stream.peek(&mut first)).await, Ok(Ok(1))) { return; }
    if first[0] == 5 {
        meow_listener::socks5::handle_socks5(&tunnel, stream, peer, None, Some(&auth), "AEROVE-MIXED", port).await;
    } else {
        meow_listener::http_proxy::handle_http(&tunnel, stream, peer, None, Some(&auth), "AEROVE-MIXED", port).await;
    }
}

fn validate_candidate(yaml: &str) -> Result<(), Failure> {
    if yaml.len() > MAX_CONFIG_BYTES { return Err(Failure::new("configTooLarge")); }
    let value: serde_yaml::Value = serde_yaml::from_str(yaml).map_err(|_| Failure::new("invalidConfig"))?;
    let map = value.as_mapping().ok_or_else(|| Failure::new("invalidConfig"))?;
    // This isolated candidate does not admit features whose task owners are not migrated yet.
    for key in map.keys() {
        let key = key.as_str().ok_or_else(|| Failure::new("invalidConfig"))?;
        if !["mode", "rules", "dns", "ipv6", "strict", "hosts", "connection-notes", "authentication", "log-level", "proxy-groups"].contains(&key) {
            return Err(Failure::field(key));
        }
    }
    if let Some(groups) = map.get(&serde_yaml::Value::String("proxy-groups".into())) { validate_leaf_groups(groups)?; }
    if map.get(&serde_yaml::Value::String("strict".into())).and_then(|v| v.as_bool()) != Some(true) {
        return Err(Failure::new("candidateRequiresStrictConfig"));
    }
    if map.get(&serde_yaml::Value::String("dns".into()))
        .and_then(|v| v.as_mapping())
        .is_some_and(|dns| dns.contains_key(&serde_yaml::Value::String("listen".into()))) {
        return Err(Failure::field("dns.listen"));
    }
    Ok(())
}

fn validate_leaf_groups(groups: &serde_yaml::Value) -> Result<(), Failure> {
    use serde_yaml::Value;
    let groups = groups.as_sequence().ok_or_else(|| Failure::field("proxy-groups"))?;
    let mut names = std::collections::BTreeSet::from(["DIRECT", "REJECT"]);
    for group in groups {
        let group = group.as_mapping().ok_or_else(|| Failure::field("proxy-groups"))?;
        let get = |name: &str| group.get(&Value::String(name.into()));
        let name = get("name").and_then(Value::as_str).filter(|name| !name.is_empty())
            .ok_or_else(|| Failure::field("proxy-groups.name"))?;
        if !names.insert(name) { return Err(Failure::field("proxy-groups.name")); }
        let kind = get("type").and_then(Value::as_str).ok_or_else(|| Failure::field("proxy-groups.type"))?;
        if !matches!(kind, "select" | "fallback") { return Err(Failure::field("proxy-groups.type")); }
        for key in group.keys() {
            let key = key.as_str().ok_or_else(|| Failure::field("proxy-groups"))?;
            if !matches!(key, "name" | "type" | "proxies" | "hidden" | "icon")
                && !(kind == "fallback" && matches!(key, "interval" | "url" | "expected-status")) {
                return Err(Failure::field(&format!("proxy-groups.{key}")));
            }
        }
        let members = get("proxies").and_then(Value::as_sequence).filter(|members| !members.is_empty())
            .ok_or_else(|| Failure::field("proxy-groups.proxies"))?;
        if members.iter().any(|member| !matches!(member.as_str(), Some("DIRECT" | "REJECT"))) {
            return Err(Failure::field("proxy-groups.proxies"));
        }
        if kind == "fallback" && get("interval").and_then(Value::as_u64) != Some(0) {
            return Err(Failure::field("proxy-groups.interval"));
        }
        if get("url").is_some_and(|v| v.as_str().is_none()) || get("icon").is_some_and(|v| v.as_str().is_none())
            || get("hidden").is_some_and(|v| v.as_bool().is_none()) { return Err(Failure::field("proxy-groups")); }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{future::{Future, poll_fn}, task::Poll};
    use tokio::sync::Notify;

    #[tokio::test]
    async fn cancelled_stop_waiter_retains_join_for_retry() {
        let (state, _) = watch::channel(Snapshot {
            generation: "1".into(), phase: Phase::Loading, runtime_mode: "proxy",
            api_address: None, mixed_address: None, api_ready: false,
            user_listeners_ready: false, tun_ready: false, failure: None,
            upload_bytes: "0".into(), download_bytes: "0".into(),
            proxy_traffic: BTreeMap::new(),
        });
        let session = Session {
            generation: 1, cancel: CancellationToken::new(), state,
            task: tokio::sync::Mutex::new(None), stop_gate: tokio::sync::Mutex::new(()),
            tunnel: Mutex::new(None), control: Mutex::new(None),
        };
        let cleanup_started = Arc::new(Notify::new());
        let cleanup_release = Arc::new(Notify::new());
        let cancelled = session.cancel.clone();
        let started = cleanup_started.clone();
        let release = cleanup_release.clone();
        let state = session.state.clone();
        *session.task.lock().await = Some(tokio::spawn(async move {
            cancelled.cancelled().await;
            started.notify_one();
            release.notified().await;
            state.send_modify(|s| s.phase = Phase::Stopped);
        }));

        let mut first_stop = Box::pin(session.stop());
        poll_fn(|cx| {
            assert!(first_stop.as_mut().poll(cx).is_pending());
            Poll::Ready(())
        }).await;
        cleanup_started.notified().await;
        drop(first_stop);

        let mut retry = Box::pin(session.stop());
        poll_fn(|cx| {
            assert!(retry.as_mut().poll(cx).is_pending(), "retry returned before cleanup joined");
            Poll::Ready(())
        }).await;
        cleanup_release.notify_one();
        assert_eq!(retry.await.unwrap().phase, Phase::Stopped);
        assert!(session.task.lock().await.is_none());
    }

    #[test]
    fn candidate_leaf_group_admission_rejects_health_workers_and_nested_members() {
        for group in ["{name: PICK, type: select, proxies: [DIRECT, REJECT]}",
            "{name: FALLBACK, type: fallback, interval: 0, proxies: [DIRECT, REJECT]}"] {
            assert!(validate_candidate(&format!("strict: true\nproxy-groups: [{group}]\n")).is_ok());
        }
        for group in ["{name: FALLBACK, type: fallback, proxies: [DIRECT]}",
            "{name: FALLBACK, type: fallback, interval: 1, proxies: [DIRECT]}",
            "{name: FALLBACK, type: fallback, interval: '0', proxies: [DIRECT]}",
            "{name: PICK, type: select, proxies: [OTHER]}",
            "{name: PICK, type: select, proxies: [DIRECT], use: [provider]}",
            "{name: PICK, type: select, proxies: [DIRECT], include-all: true}",
            "{name: AUTO, type: url-test, interval: 0, proxies: [DIRECT]}",
            "{name: DIRECT, type: select, proxies: [REJECT]}"] {
            assert!(validate_candidate(&format!("strict: true\nproxy-groups: [{group}]\n")).is_err());
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn registry_stop_cancels_real_http_fallback_prepare_without_committing() {
        use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}, sync::Notify};
        use std::{sync::Arc, time::Duration};
        let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let target_address = target.local_addr().unwrap();
        let requested = Arc::new(Notify::new());
        let request_seen = requested.clone();
        let target_task = tokio::spawn(async move {
            let (mut socket, _) = target.accept().await.unwrap();
            let mut headers = Vec::new();
            while !headers.ends_with(b"\r\n\r\n") {
                headers.push(socket.read_u8().await.unwrap());
                assert!(headers.len() < 8192);
            }
            request_seen.notify_one();
            let mut byte = [0];
            assert_eq!(socket.read(&mut byte).await.unwrap(), 0);
        });
        let secret = "registry-fallback-private-secret-00001";
        let yaml = format!("strict: true\nproxy-groups:\n  - name: FALLBACK\n    type: fallback\n    interval: 0\n    url: http://{target_address}/health\n    proxies: [DIRECT, REJECT]\nrules: ['MATCH,FALLBACK']\n");
        let session = Registry::start(StartOptions { config_yaml: yaml.clone(), private_api: "127.0.0.1:0".parse().unwrap(),
            mixed: "127.0.0.1:0".parse().unwrap(), secret: secret.into() }).unwrap();
        let ready = tokio::time::timeout(Duration::from_secs(10), session.ready()).await.unwrap().unwrap();
        let tunnel = session.tunnel.lock().as_ref().unwrap().clone();
        let control = session.control.lock().as_ref().unwrap().clone();
        let route = tunnel.route_snapshot();
        let group = route.proxies.get("FALLBACK").unwrap();
        let direct = route.proxies.get("DIRECT").unwrap();
        direct.health().record_delay(0);
        let before = control.snapshot().await.unwrap();
        assert_eq!(before.picks["FALLBACK"], "REJECT");
        assert_eq!(before.picks_version, "0");
        let api_address = ready.api_address.unwrap();
        let client_task = tokio::spawn(async move {
            let mut socket = TcpStream::connect(api_address).await.unwrap();
            let body = "{\"name\":\"DIRECT\"}";
            socket.write_all(format!("PUT /aerove/selection/FALLBACK HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {secret}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).as_bytes()).await.unwrap();
            let mut response = String::new();
            match socket.read_to_string(&mut response).await {
                Ok(_) => {},
                Err(error) => assert!(matches!(error.kind(), std::io::ErrorKind::ConnectionReset | std::io::ErrorKind::UnexpectedEof)),
            }
            assert!(!response.starts_with("HTTP/1.1 200"), "{response}");
        });
        tokio::time::timeout(Duration::from_secs(3), requested.notified()).await.unwrap();
        assert_eq!(group.selection().unwrap().fixed().as_deref(), Some(""));
        assert!(!direct.alive());
        let stopped = tokio::time::timeout(Duration::from_secs(5), session.stop()).await.unwrap().unwrap();
        assert_eq!(stopped.phase, Phase::Stopped);
        assert_eq!(group.selection().unwrap().fixed().as_deref(), Some(""));
        assert!(!direct.alive());
        assert_eq!(group.current().as_deref(), Some("REJECT"));
        assert_eq!(control.snapshot().await, Err(crate::control::ControlError::Cancelled));
        tokio::time::timeout(Duration::from_secs(3), client_task).await.unwrap().unwrap();
        tokio::time::timeout(Duration::from_secs(3), target_task).await.unwrap().unwrap();
        let successor = Registry::start(StartOptions { config_yaml: yaml, private_api: api_address,
            mixed: ready.mixed_address.unwrap(), secret: secret.into() }).unwrap();
        assert!(successor.generation() > session.generation());
        tokio::time::timeout(Duration::from_secs(10), successor.ready()).await.unwrap().unwrap();
        let successor_control = successor.control.lock().as_ref().unwrap().clone();
        assert_eq!(successor_control.snapshot().await.unwrap().picks_version, "0");
        tokio::time::timeout(Duration::from_secs(5), successor.stop()).await.unwrap().unwrap();
    }
}
