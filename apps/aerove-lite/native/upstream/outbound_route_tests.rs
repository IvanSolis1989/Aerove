use super::*;
use axum::{Json, Router, extract::Path};

async fn nested(proxy: SocketAddr, kind: &str) -> (Arc<RuntimeComponents>, tempfile::TempDir) {
    nested_rules(proxy, kind, "['MATCH,outer']").await
}

async fn nested_rules(proxy: SocketAddr, kind: &str, rules: &str) -> (Arc<RuntimeComponents>, tempfile::TempDir) {
    let directory = tempfile::tempdir().unwrap();
    let options = if kind == "select" { "" } else { ", interval: 0, lazy: true, url: 'invalid://fixture/'" };
    let source = format!("mode: rule\nmmdb: null\nasn-mmdb: null\ngeosite: null\ndns: {{enable: false}}\n\
        proxies: [{{name: fixture-proxy, type: socks5, server: 127.0.0.1, port: {}}}]\n\
        proxy-groups:\n\
        - {{name: inner, type: {kind}, proxies: [fixture-proxy, DIRECT]{options}}}\n\
        - {{name: outer, type: select, proxies: [inner, DIRECT]}}\n\
        - {{name: 'other + 组', type: select, proxies: [DIRECT, REJECT]}}\n\
        rules: {rules}\n", proxy.port());
    let config = crate::Config::Str(source).try_parse_strict().unwrap();
    (Arc::new(crate::create_components(directory.path().to_owned(), config).await.unwrap()), directory)
}

fn router(probe: Arc<Probe>, components: Arc<RuntimeComponents>, ack: Option<Arc<Notify>>) -> Router {
    let guard = probe.clone();
    probe.routes().nest("/proxies", Router::new().route("/{group}", axum::routing::put(
        move |Path(group): Path<String>, Json(payload): Json<Value>| {
            let components = components.clone();
            let ack = ack.clone();
            async move {
                let Some(control) = components.outbound_manager.get_selector_control(&group) else { return StatusCode::NOT_FOUND; };
                if control.select(payload["name"].as_str().unwrap()).await.is_err() { return StatusCode::BAD_REQUEST; }
                if let Some(ack) = ack { ack.notified().await; }
                StatusCode::ACCEPTED
            }
        },
    ))).layer(axum::middleware::from_fn(move |request, next| guard.clone().guard(request, next)))
}

fn selection(path: &str, name: &str) -> Request {
    Request::builder().method(Method::PUT).uri(path).header(http::header::CONTENT_TYPE, "application/json")
        .body(axum::body::Body::from(json!({"name":name}).to_string())).unwrap()
}

async fn body(response: Response) -> Value {
    assert_eq!(response.status(), StatusCode::OK);
    serde_json::from_slice(&axum::body::to_bytes(response.into_body(), 8192).await.unwrap()).unwrap()
}

pub(super) async fn chains() {
    let direct = Fixture::new(response(&identity("8.8.8.8")), false, None).await;
    let proxy = Fixture::new(response(&identity("9.9.9.9")), true, None).await;
    let (components, _directory) = nested(proxy.address, "select").await;
    let query = probe(components.clone(), vec![direct.source()], TIMEOUT);
    let answer = body(query.handle(request("/aerove/outbound")).await).await;
    assert_eq!(answer["ip"], "9.9.9.9");
    assert_eq!(answer["chains"], json!(["fixture-proxy", "inner", "outer"]));
    components.outbound_manager.get_selector_control("GLOBAL").unwrap().select("outer").await.unwrap();
    components.dispatcher.set_mode(crate::config::def::RunMode::Global).await;
    assert_eq!(body(query.handle(request("/aerove/outbound")).await).await["chains"],
        json!(["fixture-proxy", "inner", "outer", "GLOBAL"]));
    components.dispatcher.set_mode(crate::config::def::RunMode::Direct).await;
    assert_eq!(body(query.handle(request("/aerove/outbound")).await).await["chains"], json!(["DIRECT"]));
    components.stop_all().await;
    let gate = Arc::new(Notify::new());
    let slow_proxy = Fixture::new(response(&identity("9.9.9.9")), true, Some(gate)).await;
    let rules = format!("['DST-PORT,{},DIRECT','MATCH,outer']", direct.address.port());
    let (components, _directory) = nested_rules(slow_proxy.address, "select", &rules).await;
    let unused_destination = Fixture::new(response(&identity("1.1.1.1")), false, None).await;
    let query = probe(components.clone(), vec![unused_destination.source(), direct.source()], TIMEOUT);
    let answer = body(query.handle(request("/aerove/outbound")).await).await;
    assert_eq!(answer["ip"], "8.8.8.8");
    assert_eq!(answer["chains"], json!(["DIRECT"]));
    assert_eq!(slow_proxy.hits.load(Ordering::SeqCst), 1);
    assert_eq!(unused_destination.hits.load(Ordering::SeqCst), 0);
    tokio::time::timeout(Duration::from_secs(2), async {
        while slow_proxy.closed.load(Ordering::SeqCst) == 0 { tokio::task::yield_now().await; }
    }).await.unwrap();
    components.stop_all().await;
}

pub(super) async fn freshness() {
    let gate = Arc::new(Notify::new());
    let direct = Fixture::new(response(&identity("8.8.8.8")), false, None).await;
    let proxy = Fixture::new(response(&identity("9.9.9.9")), true, Some(gate.clone())).await;
    let (components, _directory) = nested(proxy.address, "select").await;
    for (path, name, expected) in [
        ("/proxies/other%20%2B%20%E7%BB%84", "REJECT", StatusCode::OK),
        ("/proxies/inner", "DIRECT", StatusCode::CONFLICT),
    ] {
        let query = probe(components.clone(), vec![direct.source()], TIMEOUT);
        let api = router(query, components.clone(), None);
        let hit = proxy.hits.load(Ordering::SeqCst) + 1;
        let running = api.clone();
        let task = tokio::spawn(async move { running.oneshot(request("/aerove/outbound")).await.unwrap() });
        proxy.wait_for(hit).await;
        assert_eq!(api.oneshot(selection(path, name)).await.unwrap().status(), StatusCode::ACCEPTED);
        gate.notify_waiters();
        assert_eq!(task.await.unwrap().status(), expected);
    }
    let control = components.outbound_manager.get_selector_control("inner").unwrap();
    control.select("fixture-proxy").await.unwrap();
    let query = probe(components.clone(), vec![direct.source()], TIMEOUT);
    let api = router(query, components.clone(), None);
    let hit = proxy.hits.load(Ordering::SeqCst) + 1;
    let running = api.clone();
    let task = tokio::spawn(async move { running.oneshot(request("/aerove/outbound")).await.unwrap() });
    proxy.wait_for(hit).await;
    for name in ["DIRECT", "fixture-proxy"] {
        assert_eq!(api.clone().oneshot(selection("/proxies/inner", name)).await.unwrap().status(), StatusCode::ACCEPTED);
    }
    gate.notify_waiters();
    assert_eq!(task.await.unwrap().status(), StatusCode::OK);
    for (path, name, expected) in [
        ("/proxies/other%20%2B%20%E7%BB%84", "DIRECT", StatusCode::OK),
        ("/proxies/inner", "fixture-proxy", StatusCode::CONFLICT),
    ] {
        let query = probe(components.clone(), vec![direct.source()], TIMEOUT);
        let ack = Arc::new(Notify::new());
        let api = router(query.clone(), components.clone(), Some(ack.clone()));
        let hit = proxy.hits.load(Ordering::SeqCst) + 1;
        let running = api.clone();
        let task = tokio::spawn(async move { running.oneshot(request("/aerove/outbound")).await.unwrap() });
        proxy.wait_for(hit).await;
        let put = tokio::spawn(async move { api.oneshot(selection(path, name)).await.unwrap() });
        tokio::time::timeout(Duration::from_secs(2), async {
            while query.changes.lock().unwrap().is_empty() { tokio::task::yield_now().await; }
        }).await.unwrap();
        gate.notify_waiters();
        assert_eq!(task.await.unwrap().status(), expected);
        ack.notify_one();
        assert_eq!(put.await.unwrap().status(), StatusCode::ACCEPTED);
        assert!(query.changes.lock().unwrap().is_empty());
    }
    let query = probe(components.clone(), vec![direct.source()], TIMEOUT);
    let hit = proxy.hits.load(Ordering::SeqCst) + 1;
    let running = query.clone();
    let task = tokio::spawn(async move { running.handle(request("/aerove/outbound")).await });
    proxy.wait_for(hit).await;
    components.dispatcher.set_mode(crate::config::def::RunMode::Direct).await;
    gate.notify_waiters();
    assert_eq!(task.await.unwrap().status(), StatusCode::CONFLICT);
    components.stop_all().await;
}

pub(super) async fn automatic() {
    for kind in ["fallback", "url-test"] {
        let gate = Arc::new(Notify::new());
        let direct = Fixture::new(response(&identity("8.8.8.8")), false, None).await;
        let proxy = Fixture::new(response(&identity("9.9.9.9")), true, Some(gate.clone())).await;
        let (components, _directory) = nested(proxy.address, kind).await;
        if let Some(control) = components.outbound_manager.get_selector_control("inner") {
            control.select("fixture-proxy").await.unwrap();
        }
        let query = probe(components.clone(), vec![direct.source()], TIMEOUT);
        let running = query.clone();
        let task = tokio::spawn(async move { running.handle(request("/aerove/outbound")).await });
        proxy.wait_for(1).await;
        let node = components.outbound_manager.get_outbound("fixture-proxy").await.unwrap();
        assert!(components.outbound_manager.url_test(&vec![node], "invalid://fixture/", Duration::from_secs(1), true)
            .await[0].is_err());
        if let Some(control) = components.outbound_manager.get_selector_control("inner") {
            let direct_node = components.outbound_manager.get_outbound("DIRECT").await.unwrap();
            assert!(components.outbound_manager.url_test(&vec![direct_node], &direct.source().url, Duration::from_secs(1), true)
                .await[0].is_ok());
            control.select("").await.unwrap();
        }
        gate.notify_waiters();
        assert_eq!(task.await.unwrap().status(), StatusCode::CONFLICT);
        components.stop_all().await;
    }
    crate::mobile::telemetry::source().unwrap().stop().await.unwrap();
}

pub(super) async fn budget() {
    let gate = Arc::new(Notify::new());
    let server = Fixture::new(response(&identity("8.8.8.8")), false, Some(gate.clone())).await;
    let (components, _directory) = components(None, "DIRECT").await;
    let production = Probe::new(Arc::new(RwLock::new(components.clone())), CancellationToken::new());
    assert_eq!(production.timeout, Duration::from_secs(6));
    assert_eq!(production.sources.iter().map(|source| source.url.as_str()).collect::<Vec<_>>(), vec![
        "https://www.cloudflare.com/cdn-cgi/trace", "https://api.ip.sb/geoip", "https://ipinfo.io/json",
        "https://ipwho.is", "http://ip-api.com/json", "https://get.geojs.io/v1/ip/geo.json",
        "https://api.country.is", "https://api.ipquery.io/?format=json", "https://ident.me/json",
    ]);
    let query = probe(components.clone(), vec![server.source(); 9], TIMEOUT);
    let running = query.clone();
    let task = tokio::spawn(async move { running.handle(request("/aerove/outbound")).await });
    server.wait_for(3).await;
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(server.hits.load(Ordering::SeqCst), 3);
    assert_eq!(tokio::time::timeout(Duration::from_secs(6), task).await.unwrap().unwrap().status(), StatusCode::GATEWAY_TIMEOUT);
    assert_eq!(server.hits.load(Ordering::SeqCst), 3);
    assert_eq!(query.slot.available_permits(), 1);
    let bad = Fixture::new(b"HTTP/1.1 302 Found\r\nContent-Length: 0\r\n\r\n".to_vec(), false, None).await;
    let query = probe(components.clone(), vec![bad.source(); 9], TIMEOUT);
    assert_eq!(query.handle(request("/aerove/outbound")).await.status(), StatusCode::BAD_GATEWAY);
    assert_eq!(bad.hits.load(Ordering::SeqCst), 9);
    components.stop_all().await;
}

pub(super) async fn http_cancel() {
    let gate = Arc::new(Notify::new());
    let source = Fixture::new(response(&identity("8.8.8.8")), false, Some(gate.clone())).await;
    let (components, _directory) = components(None, "DIRECT").await;
    let query = probe(components.clone(), vec![source.source()], TIMEOUT);
    let app = query.routes().route_layer(crate::app::api::AuthMiddlewareLayer::new("secret".into()));
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let cancel = CancellationToken::new();
    let stop = cancel.clone();
    let server = tokio::spawn(async move { axum::serve(listener, app).with_graceful_shutdown(stop.cancelled_owned()).await.unwrap(); });
    let mut client = tokio::net::TcpStream::connect(address).await.unwrap();
    client.write_all(b"GET /aerove/outbound HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer secret\r\n\r\n").await.unwrap();
    source.wait_for(1).await;
    drop(client);
    tokio::time::timeout(Duration::from_secs(2), async {
        while query.slot.available_permits() != 1 || source.closed.load(Ordering::SeqCst) == 0 {
            tokio::task::yield_now().await;
        }
    }).await.unwrap();
    let running = query.clone();
    let next = tokio::spawn(async move { running.handle(request("/aerove/outbound")).await });
    source.wait_for(2).await;
    gate.notify_waiters();
    assert_eq!(next.await.unwrap().status(), StatusCode::OK);
    components.stop_all().await;
    cancel.cancel();
    tokio::time::timeout(Duration::from_secs(2), server).await.unwrap().unwrap();
}
