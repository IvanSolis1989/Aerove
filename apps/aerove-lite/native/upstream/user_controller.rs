//! A separately authenticated user API. The random private API remains the runtime owner.
use std::{
    collections::HashMap, io,
    net::{Shutdown, SocketAddr},
    pin::Pin,
    sync::{Arc, Mutex as StdMutex, RwLock, atomic::{AtomicBool, AtomicU64, Ordering}},
    task::{Context, Poll}, time::Duration,
};
use axum::{
    Router, ServiceExt,
    extract::{Query, Request}, middleware::{self, Next},
    response::{IntoResponse, Response},
};
use http::{HeaderValue, Method, StatusCode};
use serde::Serialize;
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf},
    net::{TcpListener, TcpStream}, sync::Mutex, task::JoinHandle,
};
use tokio_util::sync::CancellationToken;
use tower::{Layer, util::MapRequestLayer};
use crate::{Error, GlobalState, RuntimeComponents};

pub const ADDRESS: &str = "127.0.0.1:9090";
const IO_TIMEOUT: Duration = Duration::from_secs(5);

fn failed(message: &'static str) -> Error { Error::Operation(message.into()) }

struct Secret(String);
impl std::fmt::Debug for Secret {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { f.write_str("<redacted>") }
}

fn canonical_secret(enabled: bool, input: &str) -> crate::Result<Secret> {
    if !enabled { return Ok(Secret(String::new())); }
    let secret = input.trim();
    if secret.is_empty() || secret.encode_utf16().count() > 512 {
        return Err(super::invalid("$.external-controller.secret: expected a saved secret of 1..512 characters"));
    }
    // HTTP forbids CR/LF and controls in field values. Unicode UTF-8 bytes are valid obs-text.
    if HeaderValue::from_bytes(format!("Bearer {secret}").as_bytes()).is_err() {
        return Err(super::invalid("$.external-controller.secret: invalid HTTP bearer field value"));
    }
    Ok(Secret(secret.to_owned()))
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Readback {
    pub enabled: bool,
    pub address: Option<SocketAddr>,
    pub authenticated: bool,
    pub private_routes_excluded: bool,
}
impl Readback {
    fn disabled() -> Self {
        Self { enabled: false, address: None, authenticated: false, private_routes_excluded: true }
    }
}

struct State { closed: bool, running: Option<Running> }

/// The existing command owner serializes latest intent and supplies current shared Components.
pub struct Owner { cancel: CancellationToken, state: Mutex<State> }
impl Owner {
    pub fn new(cancel: CancellationToken) -> Arc<Self> {
        Arc::new(Self { cancel, state: Mutex::new(State { closed: false, running: None }) })
    }

    pub async fn configure(&self, enabled: bool, secret: &str,
        components: Arc<RwLock<Arc<RuntimeComponents>>>, global_state: Arc<Mutex<GlobalState>>,
    ) -> crate::Result<Readback> {
        // Never generate a second unknown credential here: settings ensures and saves it first.
        let secret = canonical_secret(enabled, secret)?;
        let context = super::context()?;
        let router = crate::app::api::user_controller_router(components, global_state, context.logs.clone());
        self.configure_router(enabled, secret, router, ADDRESS.parse().unwrap()).await
    }

    async fn configure_router(&self, enabled: bool, secret: Secret, router: Router, address: SocketAddr) -> crate::Result<Readback> {
        let mut state = self.state.lock().await;
        if state.closed || self.cancel.is_cancelled() { return Err(failed("user controller owner stopped")); }
        if enabled && let Some(running) = &state.running
            && running.secret.0 == secret.0 && !running.finished()
        { return running.readback(&self.cancel).await; }
        if let Some(running) = state.running.take() { running.stop().await?; }
        if !enabled { return Ok(Readback::disabled()); }
        let listener = tokio::select! {
            biased;
            _ = self.cancel.cancelled() => return Err(failed("user controller owner stopped")),
            listener = TcpListener::bind(address) => listener.map_err(|_| failed("user controller bind failed"))?,
        };
        let address = listener.local_addr().map_err(|_| failed("user controller bind readback failed"))?;
        if !address.ip().is_loopback() { return Err(failed("user controller requires loopback")); }
        let secret = Arc::new(secret);
        let auth = secret.clone();
        let sockets = Arc::new(Sockets::default());
        let cancel = self.cancel.child_token();
        let stop = cancel.clone();
        let closing = sockets.clone();
        let listener = TrackedListener { listener, sockets: sockets.clone() };
        // The outer service authenticates before rewriting/routing; neither layer logs token URIs.
        let app = MapRequestLayer::new(rewrite_websocket).layer(router);
        let app = middleware::from_fn(move |request, next| authenticate(auth.clone(), request, next)).layer(app);
        let task = tokio::spawn(async move {
            axum::serve(listener, app.into_make_service())
                .with_graceful_shutdown(async move { stop.cancelled().await; closing.close(); }).await
        });
        let running = Running { address, secret, cancel, sockets, task: Some(task) };
        let result = running.readback(&self.cancel).await;
        match result {
            Ok(readback) => { state.running = Some(running); Ok(readback) },
            Err(error) => { let _ = running.stop().await; Err(error) },
        }
    }

    /// Actual request-time readback, with no periodic probe or cached readiness assertion.
    pub async fn readback(&self) -> crate::Result<Readback> {
        let state = self.state.lock().await;
        match &state.running {
            Some(running) => running.readback(&self.cancel).await,
            None => Ok(Readback::disabled()),
        }
    }

    /// Terminal for this runtime owner. Parent cancellation also closes every accepted transport.
    pub async fn stop(&self) -> crate::Result<Readback> {
        let mut state = self.state.lock().await;
        state.closed = true;
        if let Some(running) = state.running.take() { running.stop().await?; }
        Ok(Readback::disabled())
    }
}

struct Running {
    address: SocketAddr, secret: Arc<Secret>, cancel: CancellationToken,
    sockets: Arc<Sockets>, task: Option<JoinHandle<io::Result<()>>>,
}
impl Running {
    fn finished(&self) -> bool { self.task.as_ref().is_none_or(JoinHandle::is_finished) }
    async fn readback(&self, owner: &CancellationToken) -> crate::Result<Readback> {
        if owner.is_cancelled() || self.finished() { return Err(failed("user controller stopped before readback")); }
        let operation = async {
            let wrong = format!("{}-", self.secret.0);
            if status(self.address, "/version", None).await? != 401
                || status(self.address, "/version", Some(&wrong)).await? != 401
                || status(self.address, "/version", Some(&self.secret.0)).await? != 200
                || status(self.address, "/aerove/route", Some(&self.secret.0)).await? != 404
            { return Err(failed("user controller authentication or route readback failed")); }
            Ok(Readback { enabled: true, address: Some(self.address), authenticated: true, private_routes_excluded: true })
        };
        tokio::select! {
            biased;
            _ = owner.cancelled() => Err(failed("user controller owner stopped")),
            result = tokio::time::timeout(IO_TIMEOUT, operation) => result.map_err(|_| failed("user controller readback timed out"))?,
        }
    }
    async fn stop(mut self) -> crate::Result<()> {
        self.sockets.close();
        self.cancel.cancel();
        if let Some(mut task) = self.task.take() {
            if tokio::time::timeout(IO_TIMEOUT, &mut task).await.is_err() {
                task.abort();
                let _ = task.await;
            }
        }
        Ok(())
    }
}
impl Drop for Running {
    fn drop(&mut self) {
        self.sockets.close(); self.cancel.cancel();
        if let Some(task) = self.task.take() { task.abort(); }
    }
}

#[derive(Default)]
struct Sockets {
    closed: AtomicBool, next: AtomicU64, values: StdMutex<HashMap<u64, std::net::TcpStream>>,
}
impl Sockets {
    fn close(&self) {
        self.closed.store(true, Ordering::Release);
        for socket in self.values.lock().unwrap().values() { let _ = socket.shutdown(Shutdown::Both); }
    }
    fn register(self: &Arc<Self>, stream: TcpStream) -> io::Result<TrackedStream> {
        let stream = stream.into_std()?;
        let copy = stream.try_clone()?;
        let stream = TcpStream::from_std(stream)?;
        let id = self.next.fetch_add(1, Ordering::Relaxed);
        let mut values = self.values.lock().unwrap();
        if self.closed.load(Ordering::Acquire) {
            let _ = copy.shutdown(Shutdown::Both);
            return Err(io::ErrorKind::Interrupted.into());
        }
        values.insert(id, copy);
        Ok(TrackedStream { stream, id, sockets: self.clone() })
    }
}
struct TrackedStream { stream: TcpStream, id: u64, sockets: Arc<Sockets> }
impl Drop for TrackedStream {
    fn drop(&mut self) { self.sockets.values.lock().unwrap().remove(&self.id); }
}
impl AsyncRead for TrackedStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buffer: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.stream).poll_read(cx, buffer)
    }
}
impl AsyncWrite for TrackedStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, bytes: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.stream).poll_write(cx, bytes)
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> { Pin::new(&mut self.stream).poll_flush(cx) }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> { Pin::new(&mut self.stream).poll_shutdown(cx) }
}
struct TrackedListener { listener: TcpListener, sockets: Arc<Sockets> }
impl axum::serve::Listener for TrackedListener {
    type Io = TrackedStream;
    type Addr = SocketAddr;
    async fn accept(&mut self) -> (Self::Io, Self::Addr) {
        loop {
            match self.listener.accept().await {
                Ok((socket, address)) => if let Ok(socket) = self.sockets.register(socket) { return (socket, address); },
                Err(_) => tokio::time::sleep(Duration::from_secs(1)).await,
            }
        }
    }
    fn local_addr(&self) -> io::Result<Self::Addr> { self.listener.local_addr() }
}

fn equal_bytes(left: &[u8], right: &[u8]) -> bool {
    let mut difference = left.len() ^ right.len();
    for (index, byte) in right.iter().enumerate() { difference |= usize::from(left.get(index).copied().unwrap_or(0) ^ *byte); }
    difference == 0
}

async fn authenticate(secret: Arc<Secret>, mut request: Request, next: Next) -> Response {
    let path = request.uri().path();
    if path == "/aerove" || path.starts_with("/aerove/") || path == "/ws/aerove" || path.starts_with("/ws/aerove/") {
        return StatusCode::NOT_FOUND.into_response();
    }
    let preflight = request.method() == Method::OPTIONS && request.headers().contains_key(http::header::ORIGIN);
    let ui = path == "/ui" || path.starts_with("/ui/");
    let websocket = request.headers().get(http::header::UPGRADE).is_some_and(|header| header.as_bytes().eq_ignore_ascii_case(b"websocket"));
    let query = Query::<HashMap<String, String>>::try_from_uri(request.uri()).ok();
    let token_matches = websocket && query.as_ref().and_then(|query| query.get("token"))
        .is_some_and(|token| equal_bytes(token.as_bytes(), secret.0.as_bytes()));
    let expected = format!("Bearer {}", secret.0);
    let header_matches = request.headers().get(http::header::AUTHORIZATION)
        .is_some_and(|header| equal_bytes(header.as_bytes(), expected.as_bytes()));
    if !preflight && !ui && !token_matches && !header_matches { return StatusCode::UNAUTHORIZED.into_response(); }
    // Don't pass WebSocket auth tokens or bearer values into downstream handler logging.
    request.headers_mut().remove(http::header::AUTHORIZATION);
    if websocket && let Some(raw) = request.uri().query() {
        let query = url::form_urlencoded::parse(raw.as_bytes()).filter(|(key, _)| key != "token")
            .fold(url::form_urlencoded::Serializer::new(String::new()), |mut result, (key, value)| { result.append_pair(&key, &value); result }).finish();
        let path = request.uri().path();
        let uri = if query.is_empty() { path.to_owned() } else { format!("{path}?{query}") };
        if let Ok(uri) = uri.parse() { *request.uri_mut() = uri; }
    }
    next.run(request).await
}

fn rewrite_websocket(mut request: Request) -> Request {
    if request.headers().get(http::header::UPGRADE).is_some_and(|header| header.as_bytes().eq_ignore_ascii_case(b"websocket")) {
        let path = request.uri().path();
        if path != "/ws" && !path.starts_with("/ws/") {
            let path = format!("/ws{}", path.trim_end_matches('/'));
            let uri = request.uri().query().map_or_else(|| path.clone(), |query| format!("{path}?{query}"));
            if let Ok(uri) = uri.parse() { *request.uri_mut() = uri; }
        }
    }
    request
}

async fn status(address: SocketAddr, path: &str, secret: Option<&str>) -> crate::Result<u16> {
    let mut socket = TcpStream::connect(address).await.map_err(|_| failed("user controller probe connect failed"))?;
    let authorization = secret.map(|secret| format!("Authorization: Bearer {secret}\r\n")).unwrap_or_default();
    socket.write_all(format!("GET {path} HTTP/1.1\r\nHost: localhost\r\n{authorization}Connection: close\r\n\r\n").as_bytes())
        .await.map_err(|_| failed("user controller probe write failed"))?;
    let mut first = Vec::with_capacity(128);
    while first.len() < 1024 {
        let byte = socket.read_u8().await.map_err(|_| failed("user controller probe read failed"))?;
        first.push(byte);
        if first.ends_with(b"\r\n") { break; }
    }
    if !first.ends_with(b"\r\n") || !(first.starts_with(b"HTTP/1.1 ") || first.starts_with(b"HTTP/1.0 ")) {
        return Err(failed("user controller probe invalid status"));
    }
    std::str::from_utf8(&first).ok().and_then(|line| line.split_whitespace().nth(1))
        .and_then(|status| status.parse().ok()).ok_or_else(|| failed("user controller probe invalid status"))
}

#[cfg(feature = "aerove-mobile-tests")]
pub mod regression {
    use super::*;
    use tokio::sync::{broadcast, mpsc};
    use tower::ServiceExt as _;

    pub fn run(case: &str) {
        crate::mobile::outbound::regression::with_runtime_lock(|| {
            crate::setup_default_crypto_provider();
            tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
                match case {
                    "validation" => validation(),
                    "shared-auth" => shared_auth().await,
                    "transport" => transport().await,
                    "bind-cancel" => bind_cancel().await,
                    _ => panic!("unknown user controller regression"),
                }
            });
        });
    }

    fn validation() {
        assert_eq!(ADDRESS, "127.0.0.1:9090");
        assert_eq!(canonical_secret(false, "discard").unwrap().0, "");
        assert!(canonical_secret(true, "   ").is_err());
        assert_eq!(canonical_secret(true, "  密钥🛰  ").unwrap().0, "密钥🛰");
        assert!(canonical_secret(true, &"🛰".repeat(256)).is_ok());
        assert!(canonical_secret(true, &"🛰".repeat(257)).is_err());
        assert!(canonical_secret(true, "a\r\ninjected: value").is_err());
        assert!(!format!("{:?}", canonical_secret(true, "must-remain-private").unwrap()).contains("must-remain-private"));
        assert!(equal_bytes(b"abc", b"abc"));
        assert!(!equal_bytes(b"abc", b"abcd"));
    }

    struct Fixture {
        components: Arc<RwLock<Arc<RuntimeComponents>>>, router: Router,
        _directory: tempfile::TempDir,
    }
    impl Fixture {
        async fn new() -> Self {
            let directory = tempfile::tempdir().unwrap();
            let source = "mode: rule\nmmdb: null\nasn-mmdb: null\ngeosite: null\ndns: {enable: false}\nproxies: []\nproxy-groups: []\nrules: ['MATCH,DIRECT']\n";
            let config = crate::Config::Str(source.to_owned()).try_parse_strict().unwrap();
            let active = Arc::new(crate::create_components(directory.path().to_owned(), config).await.unwrap());
            let (reload_tx, _reload_rx) = mpsc::channel(1);
            let state = Arc::new(Mutex::new(GlobalState {
                log_level: crate::config::def::LogLevel::Info,
                #[cfg(feature = "tun")]
                tunnel_runner: active.tun_runner.clone(),
                dns_listener: active.dns_listener.clone(),
                reload_tx, cwd: directory.path().to_string_lossy().into_owned(), config_path: None,
            }));
            let components = Arc::new(RwLock::new(active));
            let (logs, _) = broadcast::channel(8);
            let router = crate::app::api::user_controller_router(components.clone(), state, logs);
            Self { components, router, _directory: directory }
        }
        async fn configure(&self, owner: &Owner, secret: &str) -> Readback {
            owner.configure_router(true, canonical_secret(true, secret).unwrap(), self.router.clone(), "127.0.0.1:0".parse().unwrap()).await.unwrap()
        }
        async fn stop(self) { let active = self.components.read().unwrap().clone(); active.stop_all().await; }
    }

    async fn wire(address: SocketAddr, method: &str, path: &str, secret: &str, body: &str) -> (u16, Vec<u8>) {
        tokio::time::timeout(IO_TIMEOUT, async {
            let mut socket = TcpStream::connect(address).await.unwrap();
            socket.write_all(format!("{method} {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {secret}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).as_bytes()).await.unwrap();
            let mut data = Vec::new(); socket.read_to_end(&mut data).await.unwrap();
            let status = std::str::from_utf8(&data[..data.iter().position(|byte| *byte == b'\r').unwrap()]).unwrap()
                .split_whitespace().nth(1).unwrap().parse().unwrap();
            (status, data)
        }).await.unwrap()
    }

    async fn shared_auth() {
        let fixture = Fixture::new().await;
        let owner = Owner::new(CancellationToken::new());
        let receipt = fixture.configure(&owner, "密钥🛰").await;
        assert!(receipt.enabled && receipt.authenticated && receipt.private_routes_excluded);
        let address = receipt.address.unwrap();
        assert_eq!(status(address, "/version", None).await.unwrap(), 401);
        assert_eq!(status(address, "/version", Some("private-api-distinct-secret-00000000000000")).await.unwrap(), 401);
        for path in ["/aerove/route", "/aerove/outbound", "/aerove/telemetry", "/ws/aerove/events"] {
            assert_eq!(status(address, path, Some("密钥🛰")).await.unwrap(), 404);
        }
        assert_eq!(wire(address, "PATCH", "/configs", "密钥🛰", r#"{"mode":"direct"}"#).await.0, 202);
        let active = fixture.components.read().unwrap().clone();
        assert_eq!(active.dispatcher.get_mode().await.to_string(), "direct");
        assert_eq!(wire(address, "PUT", "/configs", "密钥🛰", r#"{"payload":"secret: should-not-apply"}"#).await.0, 409);
        assert_eq!(wire(address, "POST", "/restart", "密钥🛰", "").await.0, 409);
        assert_eq!(wire(address, "PATCH", "/configs?token=not-a-websocket", "密钥🛰", r#"{"mode":"rule"}"#).await.0, 409);
        // A separate normal-tree reader sees the same changed Components, not a second runtime.
        let response = fixture.router.clone().oneshot(Request::builder().uri("/configs").body(axum::body::Body::empty()).unwrap()).await.unwrap();
        let bytes = axum::body::to_bytes(response.into_body(), 8192).await.unwrap();
        let config: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(config["mode"], "direct");
        assert_eq!(wire(address, "PATCH", "/configs", "密钥🛰",
            r#"{"mixed-port":9091,"tun":{"enable":true},"external-controller":"0.0.0.0:9999"}"#).await.0, 409);
        let after = fixture.router.clone().oneshot(Request::builder().uri("/configs").body(axum::body::Body::empty()).unwrap()).await.unwrap();
        let after = axum::body::to_bytes(after.into_body(), 8192).await.unwrap();
        assert_eq!(config, serde_json::from_slice::<serde_json::Value>(&after).unwrap());
        assert!(owner.readback().await.unwrap().enabled);
        owner.stop().await.unwrap(); fixture.stop().await;
    }

    async fn websocket(address: SocketAddr, secret: &str) -> TcpStream {
        let mut query = url::form_urlencoded::Serializer::new(String::new());
        query.append_pair("token", secret);
        let token = query.finish();
        let mut socket = TcpStream::connect(address).await.unwrap();
        socket.write_all(format!("GET /traffic?{token} HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").as_bytes()).await.unwrap();
        let mut headers = Vec::new();
        tokio::time::timeout(IO_TIMEOUT, async {
            while !headers.ends_with(b"\r\n\r\n") && headers.len() < 8192 { headers.push(socket.read_u8().await.unwrap()); }
        }).await.unwrap();
        assert!(headers.starts_with(b"HTTP/1.1 101"));
        socket
    }

    async fn closed(socket: &mut TcpStream) {
        let mut discarded = Vec::new();
        let result = tokio::time::timeout(IO_TIMEOUT, socket.read_to_end(&mut discarded)).await;
        assert!(result.is_ok(), "old authenticated transport remained open");
    }

    async fn transport() {
        let fixture = Fixture::new().await;
        let owner = Owner::new(CancellationToken::new());
        let first = fixture.configure(&owner, "密钥old").await.address.unwrap();
        let mut socket = websocket(first, "密钥old").await;
        let mut unfinished = TcpStream::connect(first).await.unwrap();
        unfinished.write_all(b"GET /version HTTP/1.1\r\n").await.unwrap();
        let second = fixture.configure(&owner, "new-secret").await.address.unwrap();
        closed(&mut socket).await; closed(&mut unfinished).await;
        assert_eq!(status(second, "/version", Some("密钥old")).await.unwrap(), 401);
        assert_eq!(status(second, "/version", Some("new-secret")).await.unwrap(), 200);
        let mut socket = websocket(second, "new-secret").await;
        let disabled = owner.configure_router(false, canonical_secret(false, "ignored").unwrap(), fixture.router.clone(), second).await.unwrap();
        assert!(!disabled.enabled);
        closed(&mut socket).await;
        let released = TcpListener::bind(second).await.unwrap(); drop(released);
        owner.stop().await.unwrap();
        assert!(owner.configure_router(true, canonical_secret(true, "new-secret").unwrap(), fixture.router.clone(), second).await.is_err());
        fixture.stop().await;
    }

    async fn bind_cancel() {
        let fixture = Fixture::new().await;
        let parent = CancellationToken::new();
        let owner = Owner::new(parent.clone());
        let occupied = TcpListener::bind("127.0.0.1:0").await.unwrap();
        assert!(owner.configure_router(true, canonical_secret(true, "new-secret").unwrap(), fixture.router.clone(), occupied.local_addr().unwrap()).await.is_err());
        assert!(!owner.readback().await.unwrap().enabled);
        // Existing private endpoint transport stays independently live on bind error and shutdown.
        let private_address = occupied.local_addr().unwrap();
        let private_router = fixture.router.clone().route_layer(crate::app::api::AuthMiddlewareLayer::new("private-api-distinct-secret-00000000000000".into()));
        let private_cancel = CancellationToken::new(); let stop = private_cancel.clone();
        let private = tokio::spawn(async move { axum::serve(occupied, private_router).with_graceful_shutdown(stop.cancelled_owned()).await.unwrap(); });
        let receipt = fixture.configure(&owner, "user-secret").await;
        let mut socket = websocket(receipt.address.unwrap(), "user-secret").await;
        assert_eq!(status(private_address, "/version", Some("private-api-distinct-secret-00000000000000")).await.unwrap(), 200);
        parent.cancel(); closed(&mut socket).await;
        assert!(owner.readback().await.is_err());
        owner.stop().await.unwrap();
        assert_eq!(status(private_address, "/version", Some("private-api-distinct-secret-00000000000000")).await.unwrap(), 200);
        private_cancel.cancel(); private.await.unwrap(); fixture.stop().await;
    }
}
