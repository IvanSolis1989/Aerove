use super::*;
use super::super::{http_probe, service_check};

pub(super) async fn route_snapshot() {
    use crate::mobile::telemetry;
    let (components, _directory) = components(None, "DIRECT").await;
    let query = probe(components.clone(), vec![], TIMEOUT);
    let selector = components.outbound_manager.get_selector_control("GLOBAL").unwrap();
    selector.select("DIRECT").await.unwrap();
    let before = query.read_route().await.unwrap();
    assert_eq!(before["picks"]["GLOBAL"], "DIRECT");
    let api = query.routes().route_layer(crate::app::api::AuthMiddlewareLayer::new("session-secret".into()));
    let request = |path: &str, authenticated: bool| {
        let mut request = http::Request::builder().uri(path);
        if authenticated { request = request.header("Authorization", "Bearer session-secret"); }
        request.body(axum::body::Body::empty()).unwrap()
    };
    assert_eq!(api.clone().oneshot(request("/aerove/route", false)).await.unwrap().status(), StatusCode::UNAUTHORIZED);
    let response = api.clone().oneshot(request("/aerove/route", true)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()[http::header::CACHE_CONTROL], "no-store");
    let wire: Value = serde_json::from_slice(&axum::body::to_bytes(response.into_body(), 65536).await.unwrap()).unwrap();
    assert_eq!(wire, before);
    let guard = telemetry::route_mutation();
    let mut revision = telemetry::source().unwrap().subscribe().1;
    drop(revision.borrow_and_update());
    assert!(telemetry::route_stamp().is_err());
    assert_eq!(api.clone().oneshot(request("/aerove/route", true)).await.unwrap().status(), StatusCode::CONFLICT);
    drop(guard);
    assert_eq!(query.read_route().await.unwrap(), before);
    let guard = telemetry::route_mutation();
    let nested = telemetry::route_mutation();
    telemetry::selection_changed();
    assert!(!revision.has_changed().unwrap());
    drop(nested);
    assert!(!revision.has_changed().unwrap());
    drop(guard);
    assert!(revision.has_changed().unwrap());
    assert!(telemetry::route_stamp().is_ok());
    selector.select("REJECT").await.unwrap();
    let after = query.read_route().await.unwrap();
    assert_eq!(after["picks"]["GLOBAL"], "REJECT");
    assert_eq!(after["core-epoch"], before["core-epoch"]);
    assert!(after["picks-version"].as_u64().unwrap() > before["picks-version"].as_u64().unwrap());
    components.dispatcher.set_mode(crate::config::def::RunMode::Global).await;
    let mode = query.read_route().await.unwrap();
    assert_ne!(mode["core-epoch"], after["core-epoch"]);
    components.dispatcher.set_mode(crate::config::def::RunMode::Global).await;
    assert_eq!(query.read_route().await.unwrap(), mode);
    telemetry::structure_changed();
    assert_ne!(query.read_route().await.unwrap()["core-epoch"], mode["core-epoch"]);
    assert_eq!(api.clone().oneshot(request("/aerove/route?extra=1", true)).await.unwrap().status(), StatusCode::BAD_REQUEST);
    query.cancel.cancel();
    assert_eq!(api.oneshot(request("/aerove/route", true)).await.unwrap().status(), StatusCode::SERVICE_UNAVAILABLE);
    components.stop_all().await;
}

struct Server {
    address: SocketAddr,
    hits: Arc<AtomicUsize>,
    closed: Arc<AtomicUsize>,
    peak: Arc<AtomicUsize>,
    task: tokio::task::JoinHandle<()>,
}

impl Drop for Server { fn drop(&mut self) { self.task.abort(); } }

impl Server {
    async fn new(socks: bool, reply: impl Fn(&str, &str, &[u8]) -> Vec<u8> + Send + Sync + 'static, gate: Option<Arc<Notify>>) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let hits = Arc::new(AtomicUsize::new(0));
        let closed = Arc::new(AtomicUsize::new(0));
        let active = Arc::new(AtomicUsize::new(0));
        let peak = Arc::new(AtomicUsize::new(0));
        let (seen, disconnected, maximum) = (hits.clone(), closed.clone(), peak.clone());
        let reply = Arc::new(reply);
        let task = tokio::spawn(async move {
            let mut clients = JoinSet::new();
            loop {
                tokio::select! {
                    client = listener.accept() => {
                        let (mut stream, _) = client.unwrap();
                        let (reply, seen, active, maximum, gate, disconnected) = (reply.clone(), seen.clone(), active.clone(), maximum.clone(), gate.clone(), disconnected.clone());
                        clients.spawn(async move {
                            if socks {
                                let mut hello = [0; 2];
                                stream.read_exact(&mut hello).await.unwrap();
                                assert_eq!(hello[0], 5);
                                stream.read_exact(&mut vec![0; hello[1] as usize]).await.unwrap();
                                stream.write_all(&[5, 0]).await.unwrap();
                                let mut command = [0; 4];
                                stream.read_exact(&mut command).await.unwrap();
                                assert_eq!(&command[..3], &[5, 1, 0]);
                                let length = match command[3] { 1 => 6, 4 => 18, 3 => stream.read_u8().await.unwrap() as usize + 2, _ => panic!("bad SOCKS address") };
                                stream.read_exact(&mut vec![0; length]).await.unwrap();
                                stream.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0]).await.unwrap();
                            }
                            let mut bytes = Vec::new();
                            let mut byte = [0];
                            while !bytes.ends_with(b"\r\n\r\n") {
                                if stream.read(&mut byte).await.unwrap_or(0) == 0 { return; }
                                bytes.push(byte[0]);
                                assert!(bytes.len() < 16384);
                            }
                            let headers = String::from_utf8(bytes).unwrap();
                            let method = headers.split(' ').next().unwrap();
                            let value = |name: &str| headers.lines().find_map(|line| line.split_once(':')
                                .filter(|(key, _)| key.eq_ignore_ascii_case(name)).map(|(_, value)| value.trim()));
                            let target = value("x-aerove-fixture-url").unwrap_or_else(|| headers.split(' ').nth(1).unwrap());
                            assert!(value("user-agent").unwrap().starts_with("Mozilla/5.0"));
                            let mut body = vec![0; value("content-length").unwrap_or("0").parse::<usize>().unwrap()];
                            assert!(body.len() < 16384);
                            stream.read_exact(&mut body).await.unwrap();
                            seen.fetch_add(1, Ordering::SeqCst);
                            let count = active.fetch_add(1, Ordering::SeqCst) + 1;
                            maximum.fetch_max(count, Ordering::SeqCst);
                            if let Some(gate) = gate {
                                tokio::select! {
                                    _ = gate.notified() => {},
                                    result = stream.read(&mut byte) => {
                                        assert_eq!(result.unwrap_or(0), 0);
                                        disconnected.fetch_add(1, Ordering::SeqCst);
                                        active.fetch_sub(1, Ordering::SeqCst);
                                        return;
                                    }
                                }
                            }
                            let _ = stream.write_all(&reply(target, method, &body)).await;
                            active.fetch_sub(1, Ordering::SeqCst);
                        });
                    }
                    result = clients.join_next(), if !clients.is_empty() => { result.unwrap().unwrap(); }
                }
            }
        });
        Self { address, hits, closed, peak, task }
    }

    async fn wait(&self, counter: &AtomicUsize, count: usize) {
        tokio::time::timeout(Duration::from_secs(2), async {
            while counter.load(Ordering::SeqCst) < count { tokio::task::yield_now().await; }
        }).await.unwrap();
    }
}

fn raw(status: u16, body: &str) -> Vec<u8> {
    format!("HTTP/1.1 {status} Fixture\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).into_bytes()
}

fn successful(target: &str, method: &str, body: &[u8]) -> Vec<u8> {
    if target.ends_with("/devices") {
        assert_eq!(method, "POST");
        assert_eq!(serde_json::from_slice::<Value>(body).unwrap()["deviceFamily"], "browser");
        return raw(200, r#"{"assertion":"a&b=+"}"#);
    }
    if target.ends_with("/token") {
        assert_eq!(method, "POST");
        assert!(std::str::from_utf8(body).unwrap().contains("subject_token=a%26b%3D%2B"));
        return raw(200, r#"{"refresh_token":"fixture-refresh"}"#);
    }
    if target.ends_with("/graphql") {
        assert_eq!(method, "POST");
        assert_eq!(serde_json::from_slice::<Value>(body).unwrap()["variables"]["input"]["refreshToken"], "fixture-refresh");
        return raw(200, r#"{"countryCode":"SG","inSupportedLocation":true}"#);
    }
    assert_eq!(method, "GET");
    if target.contains("claude.ai/") || target.contains("chat.openai.com/cdn-cgi") { raw(200, "loc=SG\n") }
    else if target.contains("gemini.google") { raw(200, ",2,1,200,\"USA\"") }
    else if target.contains("youtube.com") { raw(200, r#"YouTube Premium {"GL":"SG"}"#) }
    else if target.contains("ios.chat.openai") { raw(200, "Request is not allowed. Please try again later.") }
    else if target.contains("fast.com/") { raw(200, r#"{"targets":[{"location":{"country":"SG"}}]}"#) }
    else if target.contains("spotify.com/") { raw(200, r#"{"countryCode":"SG"}"#) }
    else if target.contains("tiktok.com/") { raw(200, r#"{"region":"EU-TTP2","webapp.app-context":{"region":"SG"}}"#) }
    else if target.contains("discord.com/") { raw(200, r#"{"country_code":"SG"}"#) }
    else if target.contains("primevideo.com") { raw(200, r#"{"currentTerritory":"SG"}"#) }
    else if target.contains("bilibili.com/") { raw(200, r#"{"code":0,"data":{}}"#) }
    else { raw(200, "{}") }
}

fn request_for(names: &[&str], proxy: &str, timeout: u64) -> axum::extract::Request {
    http::Request::builder().method("POST").uri("/aerove/service-check")
        .header(http::header::AUTHORIZATION, "Bearer session-secret")
        .header(http::header::CONTENT_TYPE, "application/json")
        .body(axum::body::Body::from(json!({"names": names, "proxy-name":proxy, "timeout":timeout}).to_string())).unwrap()
}

fn router(query: Arc<Probe>, server: &Server) -> Router {
    *query.services.fixture_base.write().unwrap() = Some(format!("http://{}", server.address));
    query.routes().route_layer(crate::app::api::AuthMiddlewareLayer::new("session-secret".into()))
}

async fn answer(router: Router, names: &[&str], proxy: &str) -> Vec<Value> {
    let result = router.oneshot(request_for(names, proxy, 1000)).await.unwrap();
    assert_eq!(result.status(), StatusCode::OK);
    serde_json::from_slice(&axum::body::to_bytes(result.into_body(), 65536).await.unwrap()).unwrap()
}

pub(super) async fn targets() {
    let server = Server::new(false, successful, None).await;
    let (components, _directory) = components(None, "DIRECT").await;
    let query = probe(components.clone(), vec![], TIMEOUT);
    let names = service_check::TARGETS.iter().copied().rev().collect::<Vec<_>>();
    let result = answer(router(query.clone(), &server), &names, "").await;
    assert_eq!(result.len(), 13);
    for (item, name) in result.iter().zip(names) {
        assert_eq!(item["name"], name);
        assert_eq!(item["status"], "available", "{item}");
        assert_eq!(item["chains"], json!(["DIRECT"]));
        assert!(item["checked-at"].as_u64().unwrap() > 0);
        assert_eq!(item["core-epoch"], crate::mobile::telemetry::route_stamp().unwrap().0);
    }
    assert_eq!(server.hits.load(Ordering::SeqCst), 17);
    assert!(server.peak.load(Ordering::SeqCst) <= 4);
    components.stop_all().await;
}

pub(super) async fn statuses() {
    for (name, code, text, expected) in [
        ("google", 200, "", "available"), ("google", 503, "", "unavailable"), ("google", 403, "", "restricted"),
        ("chatgpt", 200, "You may be connected to a disallowed ISP", "disallowed-isp"),
        ("netflix", 403, "", "blocked"), ("claude", 200, "loc=CN\n", "unsupported-region"),
        ("netflix", 404, "", "originals-only"), ("disney-plus", 200, "", "coming-soon"),
        ("google", 200, "", "timeout"), ("claude", 200, "not a region", "failed"),
    ] {
        let gate = (expected == "timeout").then(|| Arc::new(Notify::new()));
        let server = Server::new(false, move |target, method, body| {
            if expected == "coming-soon" {
                if target.ends_with("/graphql") { return raw(200, r#"{"countryCode":"SG","inSupportedLocation":false}"#); }
                return successful(target, method, body);
            }
            raw(code, text)
        }, gate).await;
        let (components, _directory) = components(None, "DIRECT").await;
        let query = probe(components.clone(), vec![], TIMEOUT);
        let result = router(query, &server).oneshot(request_for(&[name], "", if expected == "timeout" { 40 } else { 1000 })).await.unwrap();
        assert_eq!(result.status(), StatusCode::OK, "{expected}");
        let result: Vec<Value> = serde_json::from_slice(&axum::body::to_bytes(result.into_body(), 65536).await.unwrap()).unwrap();
        assert_eq!(result[0]["status"], expected);
        if expected == "timeout" { assert_eq!(server.hits.load(Ordering::SeqCst), 2); }
        components.stop_all().await;
    }
}

pub(super) async fn routing() {
    let direct = Server::new(false, successful, None).await;
    let proxy = Server::new(true, successful, None).await;
    let (components, _directory) = components(Some(proxy.address), "fixture-proxy").await;
    let query = probe(components.clone(), vec![], TIMEOUT);
    let api = router(query, &direct);
    for (mode, expected) in [(crate::config::def::RunMode::Direct, json!(["DIRECT"])),
        (crate::config::def::RunMode::Rule, json!(["fixture-proxy"]))]
    {
        components.dispatcher.set_mode(mode).await;
        assert_eq!(answer(api.clone(), &["google"], "").await[0]["chains"], expected);
    }
    components.outbound_manager.get_selector_control("GLOBAL").unwrap().select("DIRECT").await.unwrap();
    components.outbound_manager.get_selector_control("GLOBAL").unwrap().select("fixture-proxy").await.unwrap();
    components.dispatcher.set_mode(crate::config::def::RunMode::Global).await;
    let global = answer(api.clone(), &["google"], "").await;
    assert_eq!(global[0]["chains"], json!(["fixture-proxy", "GLOBAL"]));
    assert_eq!(global[0]["picks-version"], crate::mobile::telemetry::route_stamp().unwrap().1);
    assert!(global[0]["picks-version"].as_u64().unwrap() > 0);
    assert_eq!(answer(api, &["google"], "DIRECT").await[0]["chains"], json!(["DIRECT"]));
    assert_eq!(direct.hits.load(Ordering::SeqCst), 2);
    assert_eq!(proxy.hits.load(Ordering::SeqCst), 2);
    components.stop_all().await;
}

pub(super) async fn cancellation() {
    let gate = Arc::new(Notify::new());
    let server = Server::new(false, successful, Some(gate.clone())).await;
    let proxy = Server::new(true, successful, None).await;
    let (components, _directory) = components(Some(proxy.address), "DIRECT").await;
    components.outbound_manager.get_selector_control("GLOBAL").unwrap().select("DIRECT").await.unwrap();
    components.dispatcher.set_mode(crate::config::def::RunMode::Global).await;
    let query = probe(components.clone(), vec![], TIMEOUT);
    let api = router(query.clone(), &server);
    let first_api = api.clone();
    let first = tokio::spawn(async move { first_api.oneshot(request_for(&["google"], "", 1000)).await.unwrap() });
    server.wait(&server.hits, 1).await;
    let second_api = api.clone();
    let second = tokio::spawn(async move { second_api.oneshot(request_for(&["google"], "", 1000)).await.unwrap() });
    assert_eq!(first.await.unwrap().status(), StatusCode::CONFLICT);
    server.wait(&server.closed, 1).await;
    server.wait(&server.hits, 2).await;
    gate.notify_waiters();
    assert_eq!(second.await.unwrap().status(), StatusCode::OK);
    let old_api = api.clone();
    let old = tokio::spawn(async move { old_api.oneshot(request_for(&["google"], "", 1000)).await.unwrap() });
    server.wait(&server.hits, 3).await;
    components.outbound_manager.get_selector_control("GLOBAL").unwrap().select("fixture-proxy").await.unwrap();
    gate.notify_waiters();
    assert_eq!(old.await.unwrap().status(), StatusCode::CONFLICT);
    components.outbound_manager.get_selector_control("GLOBAL").unwrap().select("DIRECT").await.unwrap();
    let stop_api = api.clone();
    let stopping = tokio::spawn(async move { stop_api.oneshot(request_for(&["google"], "", 1000)).await.unwrap() });
    server.wait(&server.hits, 4).await;
    query.cancel.cancel();
    assert_eq!(tokio::time::timeout(Duration::from_secs(1), stopping).await.unwrap().unwrap().status(), StatusCode::SERVICE_UNAVAILABLE);
    server.wait(&server.closed, 2).await;
    assert_eq!(api.oneshot(request_for(&["google"], "", 1000)).await.unwrap().status(), StatusCode::SERVICE_UNAVAILABLE);
    components.stop_all().await;
}

pub(super) async fn http() {
    use std::io::Write as _;
    let server = Server::new(false, successful, None).await;
    let (components, _directory) = components(None, "DIRECT").await;
    let query = probe(components.clone(), vec![], TIMEOUT);
    let api = router(query, &server);
    let mut missing = request_for(&["google"], "", 1000);
    missing.headers_mut().remove(http::header::AUTHORIZATION);
    assert_eq!(api.clone().oneshot(missing).await.unwrap().status(), StatusCode::UNAUTHORIZED);
    for names in [vec![], vec!["unknown"], vec!["google", "google"]] {
        assert_eq!(api.clone().oneshot(request_for(&names, "", 1000)).await.unwrap().status(), StatusCode::BAD_REQUEST);
    }
    assert_eq!(server.hits.load(Ordering::SeqCst), 0);
    let redirected = Server::new(false, |target, method, body| {
        assert_eq!(method, "GET");
        assert!(body.is_empty());
        match target {
            "/" => b"HTTP/1.1 302 Found\r\nLocation: /second\r\nContent-Length: 0\r\n\r\n".to_vec(),
            "/second" => raw(200, "arrived"),
            _ => panic!("unexpected redirect target {target}"),
        }
    }, None).await;
    let request = http_probe::ProbeRequest::get(&format!("http://{}/", redirected.address), 16, TIMEOUT);
    let result = http_probe::run_probe(components.dispatcher.clone(), None, request.clone()).await.unwrap();
    assert_eq!(result.body.as_slice(), b"arrived");
    assert!(result.url.ends_with("/second"));
    assert_eq!(result.chains, vec!["DIRECT".to_owned()]);
    assert_eq!(redirected.hits.load(Ordering::SeqCst), 2);
    let mut request = request;
    request.no_redirect = true;
    let result = http_probe::run_probe(components.dispatcher.clone(), None, request).await.unwrap();
    assert_eq!(result.status, StatusCode::FOUND);
    assert_eq!(result.headers[http::header::LOCATION], "/second");
    assert_eq!(redirected.hits.load(Ordering::SeqCst), 3);
    let text = format!("{},2,1,200,\"USA\"{}", "x".repeat(900_000), "x".repeat(9_000_000));
    let mut encoded = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    encoded.write_all(text.as_bytes()).unwrap();
    let encoded = encoded.finish().unwrap();
    let compressed = Server::new(false, move |_, _, _| {
        let mut raw = format!("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: {}\r\n\r\n", encoded.len()).into_bytes();
        raw.extend_from_slice(&encoded);
        raw
    }, None).await;
    let mut request = http_probe::ProbeRequest::get(&format!("http://{}/", compressed.address), 8 * 1024 * 1024, TIMEOUT);
    request.until = Some(|tail| tail.contains(",2,1,200,\"USA"));
    let result = http_probe::run_probe(components.dispatcher.clone(), None, request).await.unwrap();
    assert!(result.body.len() < 1_000_000, "compressed scan did not stop at its marker");
    assert!(String::from_utf8_lossy(&result.body).contains(",2,1,200,\"USA"));
    components.stop_all().await;
}

pub(super) async fn protection(count: Arc<AtomicUsize>, refused: Arc<AtomicBool>) {
    let server = Server::new(false, successful, None).await;
    let (components, _directory) = components(None, "DIRECT").await;
    let query = probe(components.clone(), vec![], TIMEOUT);
    let api = router(query, &server);
    let before = count.load(Ordering::SeqCst);
    refused.store(true, Ordering::SeqCst);
    let result = answer(api, &["google"], "").await;
    assert_eq!(result[0]["status"], "failed");
    assert!(count.load(Ordering::SeqCst) > before);
    assert_eq!(server.hits.load(Ordering::SeqCst), 0);
    refused.store(false, Ordering::SeqCst);
    components.stop_all().await;
}

pub(super) async fn partial() {
    let gate = Arc::new(Notify::new());
    let server = Server::new(false, successful, Some(gate.clone())).await;
    let (components, _directory) = components(None, "DIRECT").await;
    let query = probe(components.clone(), vec![], TIMEOUT);
    let api = router(query, &server);
    let old_api = api.clone();
    let old = tokio::spawn(async move { old_api.oneshot(request_for(&["google", "github"], "", 1000)).await.unwrap() });
    server.wait(&server.hits, 2).await;
    let new = tokio::spawn(async move { api.oneshot(request_for(&["google"], "", 1000)).await.unwrap() });
    server.wait(&server.hits, 3).await;
    server.wait(&server.closed, 1).await;
    assert!(!old.is_finished(), "replacing Google cancelled unrelated GitHub work");
    gate.notify_waiters();
    assert_eq!(new.await.unwrap().status(), StatusCode::OK);
    let result = old.await.unwrap();
    assert_eq!(result.status(), StatusCode::OK);
    let items: Vec<Value> = serde_json::from_slice(&axum::body::to_bytes(result.into_body(), 65536).await.unwrap()).unwrap();
    assert_eq!(items[0]["name"], "google");
    assert_eq!(items[0]["status"], "failed");
    assert_eq!(items[1]["name"], "github");
    assert_eq!(items[1]["status"], "available");
    assert_eq!(server.hits.load(Ordering::SeqCst), 3);
    components.stop_all().await;
}
