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
    if case.starts_with("initial-park-") {
        tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(crate::mobile::initial_park_tests::run(case=="initial-park-tun",case=="initial-park-cancel"));return;
    }
    if case.starts_with("dns-runtime-") {
        tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(crate::mobile::dns_network_tests::io(case=="dns-runtime-failure"));return;
    }
    if case.starts_with("control-") {
        tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap()
            .block_on(crate::mobile::runtime_control_tests::run(case));
        return;
    }
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
            mode:crate::mobile::RuntimeMode::Proxy,process_owner:None,network_generation:std::sync::atomic::AtomicU64::new(0),network_dns:std::sync::RwLock::new(None),dns_enabled:false,dns_hijack_all:false,
        cancel: cancel.clone(), telemetry: crate::mobile::telemetry::Bus::new(cancel), network: None,
    }));
    let _lease = super::super::ContextLease;
    crate::setup_default_crypto_provider();
    crate::app::remote_content_manager::set_unified_delay(true);
    crate::proxy::utils::set_tcp_concurrent(true);
    tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
        match case {
            case if case.starts_with("probe-")||case.starts_with("dial-")=>crate::mobile::probe_dial_tests::run(case).await,
            "outbound-direct"=>forced_direct().await,
            "reset-connections"=>reset_dns_connections().await,
            "protocol-types"=>protocol_types().await,
            "protocol-sockets"=>protocol_sockets(count,refused).await,
            "dns-typed"=>crate::mobile::dns_network_tests::typed().await,
            "rules-dump"=>crate::mobile::dns_network_tests::rules().await,
            "http-policy-io"=>crate::mobile::http_policy_tests::io().await,
            "http-policy-typed"=>crate::mobile::http_policy_tests::typed().await,
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

async fn forced_direct(){
    let direct=Fixture::new(response(&identity("8.8.8.8")),false,None).await;
    let proxy=Fixture::new(response(&identity("9.9.9.9")),true,None).await;
    let (components,_directory)=components(Some(proxy.address),"fixture-proxy").await;
    components.dispatcher.set_mode(crate::config::def::RunMode::Rule).await;
    let probe=probe(components.clone(),vec![direct.source()],Duration::from_secs(2));
    for (path,expected,is_direct) in [("/aerove/outbound","9.9.9.9",false),("/aerove/outbound?forceDirect=true","8.8.8.8",true)] {
        let reply=probe.handle(request(path)).await;assert_eq!(reply.status(),StatusCode::OK);let body=axum::body::to_bytes(reply.into_body(),MAX_BODY).await.unwrap();let value:Value=serde_json::from_slice(&body).unwrap();assert_eq!(value["ip"],expected);assert_eq!(value["direct"],is_direct);let epoch=value["coreEpoch"].as_str().unwrap();assert_eq!(epoch.parse::<u64>().unwrap().to_string(),epoch);
        if is_direct {assert!(value["chains"].as_array().unwrap().iter().any(|v|v=="DIRECT"));assert!(!value["chains"].as_array().unwrap().iter().any(|v|v=="fixture-proxy"));}
    }
    assert_eq!(proxy.hits.load(Ordering::SeqCst),1);assert_eq!(direct.hits.load(Ordering::SeqCst),1);
    for path in ["/aerove/outbound?forceDirect=1","/aerove/outbound?forceDirect=true&forceDirect=false","/aerove/outbound?other=true"]{assert_eq!(probe.handle(request(path)).await.status(),StatusCode::BAD_REQUEST);}
    components.stop_all().await;
}

async fn reset_dns_connections() {
    use hickory_proto::{op::{Message,Query},rr::{Name,RecordType}};
    let dns=TcpListener::bind("127.0.0.1:0").await.unwrap();let dns_address=dns.local_addr().unwrap();
    let accepted=Arc::new(AtomicUsize::new(0));let closed=Arc::new(AtomicUsize::new(0));let accepts=accepted.clone();let closes=closed.clone();
    let dns_task=tokio::spawn(async move {let mut children=JoinSet::new();loop {tokio::select! {
        result=dns.accept()=>{let (mut stream,_)=result.unwrap();accepts.fetch_add(1,Ordering::SeqCst);let closed=closes.clone();children.spawn(async move {loop {
            let Ok(length)=stream.read_u16().await else {closed.fetch_add(1,Ordering::SeqCst);return;};let mut bytes=vec![0;length as usize];stream.read_exact(&mut bytes).await.unwrap();let query=Message::from_vec(&bytes).unwrap();let mut answer=Message::response(query.metadata.id,query.metadata.op_code);answer.queries=query.queries;answer.metadata.recursion_available=true;let bytes=answer.to_vec().unwrap();stream.write_u16(bytes.len() as u16).await.unwrap();stream.write_all(&bytes).await.unwrap();
        }});},_=children.join_next(),if !children.is_empty()=>{},
    }}});
    let directory=tempfile::tempdir().unwrap();let config=crate::Config::Str(format!("mode: direct\nmmdb: null\nasn-mmdb: null\ngeosite: null\ndns: {{enable: true, nameserver: ['tcp://{dns_address}'], default-nameserver: ['tcp://{dns_address}']}}\nrules: ['MATCH,DIRECT']\n")).try_parse_strict().unwrap();let components=Arc::new(crate::create_components(directory.path().to_owned(),config).await.unwrap());
    let echo=TcpListener::bind("127.0.0.1:0").await.unwrap();let target=echo.local_addr().unwrap();let echo_task=tokio::spawn(async move {let (mut stream,_)=echo.accept().await.unwrap();let mut byte=[0;1];while stream.read_exact(&mut byte).await.is_ok(){stream.write_all(&byte).await.unwrap();}});
    let (mut application,inbound)=tokio::io::duplex(64);let dispatcher=components.dispatcher.clone();let user=tokio::spawn(async move {dispatcher.dispatch_stream(crate::session::Session {destination:crate::session::SocksAddr::Ip(target),..Default::default()},Box::new(inbound)).await;});
    application.write_all(b"a").await.unwrap();let mut byte=[0;1];tokio::time::timeout(Duration::from_secs(3),application.read_exact(&mut byte)).await.unwrap().unwrap();assert_eq!(&byte,b"a");
    let (mut dns_app,dns_input)=tokio::io::duplex(4096);let resolver=components.resolver.clone();let dns_bridge=tokio::spawn(crate::mobile::dns_network::tcp(dns_input,resolver));
    let query=|host:&str|{let mut message=Message::query();message.metadata.id=91;let mut question=Query::new();question.set_name(Name::from_ascii(host).unwrap());question.set_query_type(RecordType::A);message.add_query(question);message};
    dns_frame(&mut dns_app,&query("before-reset.test")).await;assert_eq!(accepted.load(Ordering::SeqCst),1);
    let probe=Probe::new(Arc::new(RwLock::new(components.clone())),CancellationToken::new());assert_eq!(probe.reset_connections(request("/aerove/reset-connections")).await.status(),StatusCode::NO_CONTENT);
    tokio::time::timeout(Duration::from_secs(3),async {while closed.load(Ordering::SeqCst)!=1 {tokio::task::yield_now().await;}}).await.unwrap();
    application.write_all(b"b").await.unwrap();tokio::time::timeout(Duration::from_secs(3),application.read_exact(&mut byte)).await.unwrap().unwrap();assert_eq!(&byte,b"b");
    dns_frame(&mut dns_app,&query("after-reset.test")).await;assert_eq!(accepted.load(Ordering::SeqCst),2);
    assert_eq!(probe.reset_connections(request("/aerove/reset-connections?unknown=1")).await.status(),StatusCode::BAD_REQUEST);
    let body=Request::builder().body(axum::body::Body::from("{}")).unwrap();assert_eq!(probe.reset_connections(body).await.status(),StatusCode::BAD_REQUEST);
    probe.cancel.cancel();assert_eq!(probe.reset_connections(request("/aerove/reset-connections")).await.status(),StatusCode::SERVICE_UNAVAILABLE);
    drop(dns_app);tokio::time::timeout(Duration::from_secs(3),dns_bridge).await.unwrap().unwrap();
    drop(application);tokio::time::timeout(Duration::from_secs(3),user).await.unwrap().unwrap();echo_task.await.unwrap();components.resolver.reset_connections().await.unwrap();components.stop_all().await;dns_task.abort();let _=dns_task.await;
}

async fn protocol_types() {
    use crate::config::internal::proxy::OutboundProxyProtocol;
    let base="type: shadowquic\nname: sq\nserver: 127.0.0.1\nport: 443\n";
    for extra in ["","sni: sq.test\nudp-over-stream: true\ncongestion-controller: new_reno\ndisable-mtu-discovery: true\n","server-name: sq.test\nover-stream: false\ncongestion-control: cubic\nmtu-discovery: false\n"] {
        let proxy:Value=serde_yaml::from_str(&format!("{base}{extra}")).unwrap();crate::mobile::resources::check_proxy_fields(&proxy).unwrap();let parsed:OutboundProxyProtocol=serde_yaml::from_value(proxy).unwrap();
        let OutboundProxyProtocol::ShadowQuic(options)=parsed else{panic!("wrong protocol")};let handler:crate::proxy::shadowquic::Handler=options.try_into().unwrap();let map=crate::proxy::PlainProxyAPIResponse::as_map(&handler).await;let map=serde_json::to_value(map).unwrap();assert_eq!(map["server-name"],if extra.is_empty(){"127.0.0.1"}else{"sq.test"});assert_eq!(map["username"],"");assert_eq!(map["password"],"");
    }
    for extra in ["server-name: a\nsni: a\n","over-stream: true\nudp-over-stream: true\n","mtu-discovery: true\ndisable-mtu-discovery: false\n","congestion-control: bbr\ncongestion-controller: bbr\n"] {let proxy:Value=serde_yaml::from_str(&format!("{base}{extra}")).unwrap();assert!(crate::mobile::resources::check_proxy_fields(&proxy).is_err());}
    let key="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    for ipv6 in ["2001:db8::1","2001:db8::1/128"] {
        let source=format!("type: wireguard\nname: wg\nserver: 127.0.0.1\nport: 51820\nip: 10.0.0.1\nipv6: '{ipv6}'\nprivate-key: {key}\npublic-key: {key}\nreserved-bits: [1,2,3]\n");let value:Value=serde_yaml::from_str(&source).unwrap();crate::mobile::resources::check_proxy_fields(&value).unwrap();let OutboundProxyProtocol::Wireguard(options)=serde_yaml::from_value(value).unwrap() else{panic!("wrong protocol")};let _handler:crate::proxy::wg::Handler=options.try_into().unwrap();
    }
    let bad=format!("type: wireguard\nname: wg\nserver: 127.0.0.1\nport: 51820\nip: 10.0.0.1\nipv6: bad-literal\nprivate-key: {key}\npublic-key: {key}\n");let value:Value=serde_yaml::from_str(&bad).unwrap();assert!(crate::mobile::resources::check_proxy_fields(&value).is_err());let OutboundProxyProtocol::Wireguard(options)=serde_yaml::from_value(value).unwrap() else{panic!("wrong protocol")};assert!(crate::proxy::wg::Handler::try_from(options).is_err());
    let value:Value=serde_yaml::from_str("type: ssh\nname: ssh\nserver: 127.0.0.1\nport: 22\nusername: user\nhost-key-algorithms: [not-real]\n").unwrap();crate::mobile::resources::check_proxy_fields(&value).unwrap();let OutboundProxyProtocol::Ssh(options)=serde_yaml::from_value(value).unwrap() else{panic!("wrong protocol")};assert!(crate::proxy::ssh::Handler::try_from(options).is_err());
    let value:Value=serde_yaml::from_str("type: tailscale\nname: ts\nephemeral: true\n").unwrap();crate::mobile::resources::check_proxy_fields(&value).unwrap();assert!(matches!(serde_yaml::from_value::<OutboundProxyProtocol>(value).unwrap(),OutboundProxyProtocol::Tailscale(_)));
}
async fn protocol_sockets(count:Arc<AtomicUsize>,refused:Arc<AtomicBool>) {
    use shadowquic::utils::socket_opt::SocketFactory;
    let receiver=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();let remote=receiver.local_addr().unwrap();let scope=crate::mobile::protocol_transport::Scope::current().unwrap();let factory=crate::mobile::protocol_transport::ShadowSocket {scope:scope.clone(),remote,interface:None,mark:None};
    let before=count.load(Ordering::SeqCst);let socket=factory.create_socket().await.unwrap();assert_eq!(count.load(Ordering::SeqCst),before+1);let socket=tokio::net::UdpSocket::from_std(std::net::UdpSocket::from(socket)).unwrap();socket.send_to(b"real-protected-sq-underlay",remote).await.unwrap();let mut bytes=[0;64];let (n,_)=tokio::time::timeout(Duration::from_secs(2),receiver.recv_from(&mut bytes)).await.unwrap().unwrap();assert_eq!(&bytes[..n],b"real-protected-sq-underlay");drop(socket);
    refused.store(true,Ordering::SeqCst);assert!(factory.create_socket().await.is_err());refused.store(false,Ordering::SeqCst);assert!(tokio::time::timeout(Duration::from_millis(40),receiver.recv_from(&mut bytes)).await.is_err());
    let config:shadowquic::config::ShadowQuicClientCfg=serde_yaml::from_str("addr: 127.0.0.1:443\nserver-name: localhost\nusername: ''\npassword: ''\n").unwrap();let mut client=shadowquic::shadowquic::outbound::ShadowQuicClient::new(config);client.socket_factory=Arc::new(factory);refused.store(true,Ordering::SeqCst);assert!(client.init_endpoint().await.is_err());refused.store(false,Ordering::SeqCst);
    scope.cancel.cancel();let before=count.load(Ordering::SeqCst);assert!(scope.udp(remote).is_err());assert_eq!(count.load(Ordering::SeqCst),before);
}

async fn dns_frame(stream:&mut tokio::io::DuplexStream,query:&hickory_proto::op::Message){
    tokio::time::timeout(Duration::from_secs(3),async {let bytes=query.to_vec().unwrap();stream.write_u16(bytes.len() as u16).await.unwrap();stream.write_all(&bytes).await.unwrap();let count=stream.read_u16().await.unwrap() as usize;let mut bytes=vec![0;count];stream.read_exact(&mut bytes).await.unwrap();let response=hickory_proto::op::Message::from_vec(&bytes).unwrap();assert_eq!(response.metadata.id,query.metadata.id);}).await.unwrap();
}
