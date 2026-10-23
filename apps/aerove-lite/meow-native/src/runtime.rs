use axum::{Json, Router, extract::State, http::{HeaderMap, StatusCode}, routing::get};
use hyper_util::{rt::TokioIo, service::TowerToHyperService};
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
            tunnel: Mutex::new(None) });
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
        self.cancel.cancel();
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
        tunnel.set_dialer_registry(config.provider_dialer_registry);
        tunnel.set_mode(config.general.mode);
        tunnel.set_connection_notes(config.general.connection_notes);
        tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
        let api = TcpListener::bind(options.private_api).await
            .map_err(|_| Failure::new("privateApiBindFailed"))?;
        let mixed = TcpListener::bind(options.mixed).await
            .map_err(|_| Failure::new("mixedBindFailed"))?;
        let api_addr = api.local_addr().map_err(|_| Failure::new("privateApiAddressFailed"))?;
        let mixed_addr = mixed.local_addr().map_err(|_| Failure::new("mixedAddressFailed"))?;
        if self.cancel.is_cancelled() { return Ok(()); }
        *self.tunnel.lock() = Some(tunnel.clone());
        let router = Router::new().route("/aerove/runtime", get(api_snapshot))
            .with_state(ApiState { session: Arc::downgrade(self), secret: options.secret });
        self.state.send_modify(|s| {
            s.phase = Phase::Running; s.api_address = Some(api_addr); s.mixed_address = Some(mixed_addr);
            s.api_ready = true; s.user_listeners_ready = true;
        });
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
                    let service = TowerToHyperService::new(router.clone());
                    children.spawn(async move {
                        let _ = hyper::server::conn::http1::Builder::new().keep_alive(false)
                            .serve_connection(TokioIo::new(stream), service).await;
                    });
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
        tunnel.statistics().close_all_connections();
        tunnel.close_all_udp_sessions();
        children.abort_all();
        while children.join_next().await.is_some() {}
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
        if !["mode", "rules", "dns", "ipv6", "strict", "hosts", "connection-notes", "authentication"].contains(&key) {
            return Err(Failure::field(key));
        }
    }
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
            tunnel: Mutex::new(None),
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
}
