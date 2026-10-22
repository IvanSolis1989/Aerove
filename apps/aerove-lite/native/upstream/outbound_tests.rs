use super::*;
use std::net::SocketAddr;
use std::sync::{Mutex as StdMutex, atomic::{AtomicBool, AtomicUsize, Ordering}};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::TcpListener, sync::Notify, task::JoinSet};
use tower::ServiceExt;

static LOCK: StdMutex<()> = StdMutex::new(());

pub fn with_runtime_lock(operation: impl FnOnce()) {
    let _lock = LOCK.lock().unwrap_or_else(|error| error.into_inner());
    operation();
}

#[path = "telemetry_tests.rs"]
mod telemetry_tests;

#[path = "service_check_tests.rs"]
mod service_check_tests;

#[path = "outbound_route_tests.rs"]
mod route_tests;

pub fn run(case: &str) {
    let _lock = LOCK.lock().unwrap_or_else(|error| error.into_inner());
    if case.starts_with("observations-") {
        tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap()
            .block_on(crate::mobile::observation_tests::run(case));
        return;
    }
    if case.starts_with("runtime-") {
        tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap()
            .block_on(crate::mobile::runtime_mode_tests::run(case));
        return;
    }
    let count = Arc::new(AtomicUsize::new(0));
    let refused = Arc::new(AtomicBool::new(false));
    let (events, _receiver) = tokio::sync::mpsc::unbounded_channel();
    let (logs, _) = tokio::sync::broadcast::channel(8);
    let calls = count.clone();
    let deny = refused.clone();
    let cancel = CancellationToken::new();
    *super::super::CONTEXT.write().unwrap() = Some(Arc::new(super::super::Context {
        protector: Arc::new(move |_| {
            calls.fetch_add(1, Ordering::SeqCst);
            if deny.load(Ordering::SeqCst) { Err(io::ErrorKind::PermissionDenied.into()) } else { Ok(()) }
        }),
        bootstrap: vec![], events, logs, level: tracing::level_filters::LevelFilter::OFF,
        cancel: cancel.clone(), telemetry: crate::mobile::telemetry::Bus::new(cancel), network: None,
    }));
    let _lease = super::super::ContextLease;
    crate::setup_default_crypto_provider();
    tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
        match case {
            "network-validation" => crate::mobile::inbound::regression::validation().await,
            "network-listeners" => crate::mobile::inbound::regression::listeners().await,
            "network-udp" => crate::mobile::inbound::regression::udp().await,
            "network-failure" => crate::mobile::inbound::regression::failure().await,
            "network-update" => crate::mobile::inbound::regression::update().await,
            "network-cancellation" => crate::mobile::inbound::regression::cancellation().await,
            "route-snapshot" => service_check_tests::route_snapshot().await,
            "service-targets" => service_check_tests::targets().await,
            "service-statuses" => service_check_tests::statuses().await,
            "service-routing" => service_check_tests::routing().await,
            "service-cancel" => service_check_tests::cancellation().await,
            "service-partial" => service_check_tests::partial().await,
            "service-protect" => service_check_tests::protection(count, refused).await,
            "service-http" => service_check_tests::http().await,
            "http-fixture" => http_fixture().await,
            "routing" => routing(count).await,
            "auth" => auth(count).await,
            "bounds" => bounds().await,
            "loader" => loader().await,
            "protect" => protection(count, refused).await,
            "chains" => route_tests::chains().await,
            "freshness" => route_tests::freshness().await,
            "automatic" => route_tests::automatic().await,
            "source-budget" => route_tests::budget().await,
            "http-cancel" => route_tests::http_cancel().await,
            "telemetry-counters" => telemetry_tests::counters().await,
            "telemetry-groups" => telemetry_tests::groups().await,
            "telemetry-urltest" => telemetry_tests::urltest().await,
            "telemetry-provider" => telemetry_tests::provider().await,
            "telemetry-websocket" => telemetry_tests::websocket().await,
            "telemetry-delay-budget" => telemetry_tests::delay_budget().await,
            "telemetry-delay-stop" => telemetry_tests::delay_stop().await,
            _ => panic!("unknown probe regression"),
        }
    });
}

struct Fixture {
    address: SocketAddr,
    hits: Arc<AtomicUsize>,
    accepted: Arc<AtomicUsize>,
    closed: Arc<AtomicUsize>,
    task: tokio::task::JoinHandle<()>,
}

impl Drop for Fixture { fn drop(&mut self) { self.task.abort(); } }

impl Fixture {
    async fn new(response: Vec<u8>, socks: bool, gate: Option<Arc<Notify>>) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let hits = Arc::new(AtomicUsize::new(0));
        let accepted = Arc::new(AtomicUsize::new(0));
        let closed = Arc::new(AtomicUsize::new(0));
        let seen = hits.clone();
        let connections = accepted.clone();
        let disconnected = closed.clone();
        let task = tokio::spawn(async move {
            let mut clients = JoinSet::new();
            loop {
                tokio::select! {
                    accepted = listener.accept() => {
                        let (mut stream, _) = accepted.unwrap();
                        connections.fetch_add(1, Ordering::SeqCst);
                        let response = response.clone();
                        let seen = seen.clone();
                        let gate = gate.clone();
                        let disconnected = disconnected.clone();
                        clients.spawn(async move {
                            if socks {
                                let mut hello = [0; 2];
                                stream.read_exact(&mut hello).await.unwrap();
                                assert_eq!(hello[0], 5);
                                let mut methods = vec![0; hello[1] as usize];
                                stream.read_exact(&mut methods).await.unwrap();
                                stream.write_all(&[5, 0]).await.unwrap();
                                let mut request = [0; 4];
                                stream.read_exact(&mut request).await.unwrap();
                                assert_eq!(&request[..3], &[5, 1, 0]);
                                let length = match request[3] {
                                    1 => 6, 4 => 18, 3 => stream.read_u8().await.unwrap() as usize + 2,
                                    _ => panic!("invalid SOCKS address"),
                                };
                                stream.read_exact(&mut vec![0; length]).await.unwrap();
                                stream.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0]).await.unwrap();
                            }
                            let mut request = Vec::new();
                            let mut byte = [0];
                            while !request.ends_with(b"\r\n\r\n") {
                                if stream.read(&mut byte).await.unwrap_or(0) == 0 { return; }
                                request.push(byte[0]);
                                assert!(request.len() < 4096);
                            }
                            let response = fixture_response(&request, &response);
                            seen.fetch_add(1, Ordering::SeqCst);
                            if let Some(gate) = gate {
                                tokio::select! {
                                    _ = gate.notified() => {},
                                    result = stream.read(&mut byte) => {
                                        assert_eq!(result.unwrap_or(0), 0);
                                        disconnected.fetch_add(1, Ordering::SeqCst);
                                        return;
                                    }
                                }
                            }
                            let _ = stream.write_all(response).await;
                        });
                    }
                    result = clients.join_next(), if !clients.is_empty() => { result.unwrap().unwrap(); }
                }
            }
        });
        Self { address, hits, accepted, closed, task }
    }
    fn source(&self) -> Source { Source { url: format!("http://{}/", self.address), format: Format::IpWho } }
    async fn wait_for(&self, hits: usize) {
        tokio::time::timeout(Duration::from_secs(2), async {
            while self.hits.load(Ordering::SeqCst) < hits { tokio::time::sleep(Duration::from_millis(1)).await; }
        }).await.unwrap();
    }
}

fn fixture_response<'a>(request: &[u8], response: &'a [u8]) -> &'a [u8] {
    let request = std::str::from_utf8(request).unwrap();
    let line = request.split("\r\n").next().unwrap();
    let parts = line.split(' ').collect::<Vec<_>>();
    assert_eq!(parts.len(), 3, "invalid request line: {line}");
    assert!(matches!(parts[0], "GET" | "HEAD"), "unexpected method: {line}");
    let uri = parts[1].parse::<http::Uri>().unwrap();
    assert_eq!(uri.path_and_query().map(|path| path.as_str()), Some("/"), "unexpected target: {line}");
    assert!(matches!(parts[2], "HTTP/1.0" | "HTTP/1.1"), "unexpected version: {line}");
    if parts[0] == "HEAD" {
        let end = response.windows(4).position(|bytes| bytes == b"\r\n\r\n").unwrap() + 4;
        &response[..end]
    } else {
        response
    }
}

async fn http_fixture() {
    let body = "fixture body";
    let expected = response(body);
    let server = Fixture::new(expected.clone(), false, None).await;
    for target in ["/".to_owned(), server.source().url] {
        for method in ["GET", "HEAD"] {
            let mut stream = tokio::net::TcpStream::connect(server.address).await.unwrap();
            let request = format!("{method} {target} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n\r\n", server.address);
            stream.write_all(request.as_bytes()).await.unwrap();
            let mut received = Vec::new();
            tokio::time::timeout(Duration::from_secs(2), stream.read_to_end(&mut received)).await.unwrap().unwrap();
            let length = if method == "HEAD" { expected.len() - body.len() } else { expected.len() };
            assert_eq!(received.as_slice(), &expected[..length]);
        }
    }
    assert_eq!(server.hits.load(Ordering::SeqCst), 4);
    for request in [b"POST / HTTP/1.1\r\n\r\n".as_slice(), b"GET /other HTTP/1.1\r\n\r\n",
        b"HEAD /?query HTTP/1.1\r\n\r\n", b"GET http://example.com/other HTTP/1.1\r\n\r\n"]
    {
        assert!(std::panic::catch_unwind(|| fixture_response(request, &expected)).is_err());
    }
}

fn response(body: &str) -> Vec<u8> {
    format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).into_bytes()
}

fn identity(ip: &str) -> String {
    format!(r#"{{"ip":"{ip}","success":true,"country_code":"SG","connection":{{"isp":"Fixture ISP"}}}}"#)
}

async fn components(proxy: Option<SocketAddr>, rule: &str) -> (Arc<RuntimeComponents>, tempfile::TempDir) {
    let directory = tempfile::tempdir().unwrap();
    let proxy = proxy.map(|address| format!("proxies: [{{name: fixture-proxy, type: socks5, server: 127.0.0.1, port: {}}}]\n", address.port())).unwrap_or_default();
    let source = format!("mode: direct\nmmdb: null\nasn-mmdb: null\ngeosite: null\ndns: {{enable: false}}\nrules: ['MATCH,{rule}']\n{proxy}");
    let config = crate::Config::Str(source).try_parse_strict().unwrap();
    let components = crate::create_components(directory.path().to_owned(), config).await.unwrap();
    (Arc::new(components), directory)
}

fn probe(components: Arc<RuntimeComponents>, sources: Vec<Source>, timeout: Duration) -> Arc<Probe> {
    let mut probe = Probe::new(Arc::new(RwLock::new(components)), CancellationToken::new());
    let mutable = Arc::get_mut(&mut probe).unwrap();
    mutable.sources = sources;
    mutable.timeout = timeout;
    probe
}

fn request(path: &str) -> Request { Request::builder().uri(path).body(axum::body::Body::empty()).unwrap() }

async fn routing(count: Arc<AtomicUsize>) {
    let direct = Fixture::new(response(&identity("8.8.8.8")), false, None).await;
    let proxy = Fixture::new(response(&identity("9.9.9.9")), true, None).await;
    let (components, _directory) = components(Some(proxy.address), "fixture-proxy").await;
    let probe = probe(components.clone(), vec![direct.source()], Duration::from_secs(2));
    for (mode, expected) in [(crate::config::def::RunMode::Direct, "8.8.8.8"),
        (crate::config::def::RunMode::Rule, "9.9.9.9"), (crate::config::def::RunMode::Global, "9.9.9.9")]
    {
        components.outbound_manager.get_selector_control("GLOBAL").unwrap().select("fixture-proxy").await.unwrap();
        components.dispatcher.set_mode(mode).await;
        let reply = probe.handle(request("/aerove/outbound")).await;
        assert_eq!(reply.status(), StatusCode::OK);
        let body = axum::body::to_bytes(reply.into_body(), MAX_BODY).await.unwrap();
        assert_eq!(serde_json::from_slice::<Value>(&body).unwrap()["ip"], expected);
    }
    components.outbound_manager.get_selector_control("GLOBAL").unwrap().select("DIRECT").await.unwrap();
    let reply = probe.handle(request("/aerove/outbound")).await;
    let body = axum::body::to_bytes(reply.into_body(), MAX_BODY).await.unwrap();
    assert_eq!(serde_json::from_slice::<Value>(&body).unwrap()["ip"], "8.8.8.8");
    assert!(count.load(Ordering::SeqCst) >= 4);
    assert_eq!(direct.hits.load(Ordering::SeqCst), 2);
    assert_eq!(proxy.hits.load(Ordering::SeqCst), 2);
    components.stop_all().await;
}

async fn auth(count: Arc<AtomicUsize>) {
    let (components, _directory) = components(None, "DIRECT").await;
    let probe = Probe::new(Arc::new(RwLock::new(components.clone())), CancellationToken::new());
    let router = probe.routes().route_layer(crate::app::api::AuthMiddlewareLayer::new("session-secret".into()));
    let before = count.load(Ordering::SeqCst);
    assert_eq!(router.clone().oneshot(request("/aerove/outbound")).await.unwrap().status(), StatusCode::UNAUTHORIZED);
    let mut wrong_session = request("/aerove/outbound");
    wrong_session.headers_mut().insert(http::header::AUTHORIZATION, "Bearer previous-session-secret".parse().unwrap());
    assert_eq!(router.clone().oneshot(wrong_session).await.unwrap().status(), StatusCode::UNAUTHORIZED);
    let mut query = request("/aerove/outbound?url=http://attacker.invalid/");
    query.headers_mut().insert(http::header::AUTHORIZATION, "Bearer session-secret".parse().unwrap());
    assert_eq!(router.clone().oneshot(query).await.unwrap().status(), StatusCode::BAD_REQUEST);
    probe.cancel.cancel();
    let mut query = request("/aerove/outbound");
    query.headers_mut().insert(http::header::AUTHORIZATION, "Bearer session-secret".parse().unwrap());
    assert_eq!(router.oneshot(query).await.unwrap().status(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(before, count.load(Ordering::SeqCst));
    components.stop_all().await;
}

async fn bounds() {
    let gate = Arc::new(Notify::new());
    let server = Fixture::new(response(&identity("8.8.8.8")), false, Some(gate.clone())).await;
    let (components, _directory) = components(None, "DIRECT").await;
    let query = probe(components.clone(), vec![server.source()], Duration::from_secs(2));
    let running = query.clone();
    let task = tokio::spawn(async move { running.handle(request("/aerove/outbound")).await });
    server.wait_for(1).await;
    assert_eq!(query.handle(request("/aerove/outbound")).await.status(), StatusCode::TOO_MANY_REQUESTS);
    query.cancel.cancel();
    assert_eq!(task.await.unwrap().status(), StatusCode::SERVICE_UNAVAILABLE);
    tokio::time::timeout(Duration::from_secs(2), async {
        while server.closed.load(Ordering::SeqCst) == 0 { tokio::time::sleep(Duration::from_millis(1)).await; }
    }).await.unwrap();
    let query = probe(components.clone(), vec![server.source()], Duration::from_millis(40));
    assert_eq!(query.handle(request("/aerove/outbound")).await.status(), StatusCode::GATEWAY_TIMEOUT);
    let query = probe(components.clone(), vec![server.source()], Duration::from_secs(2));
    let next_hit = server.hits.load(Ordering::SeqCst) + 1;
    let running = query.clone();
    let task = tokio::spawn(async move { running.handle(request("/aerove/outbound")).await });
    server.wait_for(next_hit).await;
    task.abort();
    assert!(task.await.unwrap_err().is_cancelled());
    assert_eq!(query.slot.available_permits(), 1);
    let running = query.clone();
    let task = tokio::spawn(async move { running.handle(request("/aerove/outbound")).await });
    server.wait_for(next_hit + 1).await;
    gate.notify_waiters();
    assert_eq!(task.await.unwrap().status(), StatusCode::OK);
    components.stop_all().await;
}

async fn loader() {
    for (format, body) in [
        (Format::Cloudflare, "ip=8.8.8.8\nloc=SG\n"),
        (Format::IpSb, r#"{"ip":"8.8.8.8","country_code":"SG","organization":"ISP"}"#),
        (Format::IpInfo, r#"{"ip":"8.8.8.8","country":"SG","org":"AS1 ISP"}"#),
        (Format::IpApi, r#"{"status":"success","query":"8.8.8.8","countryCode":"SG","isp":"ISP"}"#),
        (Format::GeoJs, r#"{"ip":"8.8.8.8","country_code":"SG","organization_name":"ISP"}"#),
        (Format::CountryIs, r#"{"ip":"8.8.8.8","country":"SG"}"#),
    ] {
        let parsed = parse(format, body.as_bytes()).unwrap();
        assert_eq!(parsed.ip, "8.8.8.8");
        assert_eq!(parsed.country_code, "SG");
    }
    assert!(parse(Format::Cloudflare, b"ip=127.0.0.1\nloc=SG\n").is_err());
    assert!(parse(Format::IpApi, br#"{"status":"fail","query":"8.8.8.8","countryCode":"SG"}"#).is_err());
    assert_eq!(selection_name("/proxies/other%20%2B%20%E7%BB%84").as_deref(), Some("other + 组"));
    assert_eq!(selection_name("/proxies/a+b/").as_deref(), Some("a+b"));
    assert!(selection_name("/proxies/a/delay").is_none());
    for bad in [identity("127.0.0.1"), identity("100.64.0.1"), identity("::ffff:192.168.1.1"),
        "{\"ip\":\"8.8.8.8\",\"country_code\":\"Singapore\"}".into(), "<html>captcha</html>".into(),
        "{\"success\":false}".into()]
    { assert!(parse(Format::IpWho, bad.as_bytes()).is_err()); }
    assert_eq!(parse(Format::IpWho, identity("8.8.8.8").as_bytes()).unwrap().kind, "unknown");
    let detailed = br#"{"ip":"8.8.8.8","location":{"country_code":"us"},"isp":{"org":"Actual organization"},"risk":{"is_datacenter":true}}"#;
    let detailed = parse(Format::IpQuery, detailed).unwrap();
    assert_eq!(detailed.country_code, "US");
    assert_eq!(detailed.kind, "hosting");
    assert_eq!(detailed.organization.as_deref(), Some("Actual organization"));
    for (declared, expected) in [("isp", "residential"), ("business", "business"), ("hosting", "hosting"), ("IPv4", "unknown")] {
        let body = format!(r#"{{"ip":"8.8.8.8","cc":"US","aso":"Source ASN","type":"{declared}"}}"#);
        assert_eq!(parse(Format::IdentMe, body.as_bytes()).unwrap().kind, expected);
    }
    let (components, _directory) = components(None, "DIRECT").await;
    for raw in [b"HTTP/1.1 302 Found\r\nLocation: http://attacker.invalid/\r\nContent-Length: 0\r\n\r\n".to_vec(),
        b"HTTP/1.1 200 OK\r\nContent-Length: 4097\r\n\r\n".to_vec(),
        format!("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1001\r\n{}\r\n0\r\n\r\n", "x".repeat(4097)).into_bytes()]
    {
        let server = Fixture::new(raw, false, None).await;
        assert!(routed_http(components.dispatcher.clone(), &server.source().url).await.is_err());
    }
    let bad = Fixture::new(response("<html>captcha</html>"), false, None).await;
    let good = Fixture::new(response(&identity("8.8.8.8")), false, None).await;
    let query = probe(components.clone(), vec![bad.source(), good.source()], Duration::from_secs(2));
    assert_eq!(query.handle(request("/aerove/outbound")).await.status(), StatusCode::OK);
    components.stop_all().await;
}

async fn protection(count: Arc<AtomicUsize>, refused: Arc<AtomicBool>) {
    let server = Fixture::new(response(&identity("8.8.8.8")), false, None).await;
    let (components, _directory) = components(None, "DIRECT").await;
    let query = probe(components.clone(), vec![server.source()], Duration::from_secs(1));
    refused.store(true, Ordering::SeqCst);
    let before = count.load(Ordering::SeqCst);
    assert_eq!(query.handle(request("/aerove/outbound")).await.status(), StatusCode::BAD_GATEWAY);
    assert!(count.load(Ordering::SeqCst) > before);
    assert_eq!(server.accepted.load(Ordering::SeqCst), 0);
    assert_eq!(server.hits.load(Ordering::SeqCst), 0);
    components.stop_all().await;
}
