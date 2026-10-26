use aerove_meow_core::api::CoreApi;
use axum::{Router, routing::get};
use futures::StreamExt;
use meow_tunnel::Tunnel;
use std::{future::{Future, poll_fn}, sync::Arc, task::Poll, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};
use tokio_tungstenite::connect_async;
use tokio_util::sync::CancellationToken;
use tracing_subscriber::prelude::*;

const PRIVATE: &str = "aerove-private-api-test-secret-00000001";
const USER: &str = "user-controller-测试-secret";
static LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

async fn fixture() -> (Arc<CoreApi>, TcpListener, TcpListener, TcpListener) {
    let config = meow_config::load_config_from_str("strict: true\nrules: ['MATCH,DIRECT']\n").await.unwrap();
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
    tunnel.set_mode(config.general.mode);
    // The public API assembly borrows this same installed routing generation.
    let private = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let user = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let mixed = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let api = CoreApi::assemble(&config, tunnel.clone(), mixed.local_addr().unwrap(), PRIVATE,
        Some(USER), Router::new().route("/aerove/proof", get(|| async { "private-owner" }))).unwrap();
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
    (Arc::new(api), private, user, mixed)
}

async fn http(address: std::net::SocketAddr, path: &str, secret: &str, method: &str) -> String {
    raw_http(address, &format!("{method} {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {secret}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")).await
}

async fn raw_http(address: std::net::SocketAddr, request: &str) -> String {
    let mut stream = TcpStream::connect(address).await.unwrap();
    stream.write_all(request.as_bytes()).await.unwrap();
    let mut body = String::new();
    tokio::time::timeout(Duration::from_secs(5), stream.read_to_string(&mut body)).await.unwrap().unwrap();
    body
}

async fn preflight(address: std::net::SocketAddr, path: &str, method: &str, headers: &str) -> String {
    raw_http(address, &format!("OPTIONS {path} HTTP/1.1\r\nHost: localhost\r\nOrigin: https://dashboard.example\r\nAccess-Control-Request-Method: {method}\r\nAccess-Control-Request-Headers: {headers}\r\nAccess-Control-Request-Private-Network: true\r\nConnection: close\r\n\r\n")).await
}

#[tokio::test]
async fn actual_api_views_share_tunnel_and_user_cannot_reach_private_or_mutators() {
    let _lock = LOCK.lock().await;
    let (api, private, user, mixed) = fixture().await;
    let private_address = private.local_addr().unwrap();
    let user_address = user.local_addr().unwrap();
    let cancel = CancellationToken::new();
    let owner = api.clone(); let token = cancel.clone();
    let private_task = tokio::spawn(async move { owner.serve_private(private, token).await });
    let owner = api.clone(); let token = cancel.clone();
    let user_task = tokio::spawn(async move { owner.serve_user(user, token).await });
    for endpoint in ["/configs", "/proxies", "/connections", "/statistics", "/memory"] {
        let response = http(private_address, endpoint, PRIVATE, "GET").await;
        assert!(response.starts_with("HTTP/1.1 200"), "{endpoint}: {response}");
    }
    let memory = http(private_address, "/memory", PRIVATE, "GET").await;
    let body: serde_json::Value = serde_json::from_str(memory.split_once("\r\n\r\n").unwrap().1).unwrap();
    assert_eq!(body.as_object().unwrap().len(), 2);
    assert!(body["inuse"].as_u64().unwrap() > 0);
    assert!(body["oslimit"].is_u64());
    let configs = http(user_address, "/configs", USER, "GET").await;
    assert!(configs.starts_with("HTTP/1.1 200"), "{configs}");
    assert!(configs.contains(&format!("\"mixed-port\":{}", mixed.local_addr().unwrap().port())));
    assert!(http(private_address, "/aerove/proof", PRIVATE, "GET").await.ends_with("private-owner"));
    for endpoint in ["/aerove/proof", "/ui", "/api/config/save"] {
        assert!(http(user_address, endpoint, USER, "GET").await.starts_with("HTTP/1.1 404"));
    }
    assert!(http(user_address, "/configs", USER, "PUT").await.starts_with("HTTP/1.1 404"));
    let before_configs = serde_json::to_value(&*api.state.raw_config.read()).unwrap();
    for (method, path) in [("POST", "/restart"), ("POST", "/configs"), ("PUT", "/configs")] {
        let request = |secret: &str| format!("{method} {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {secret}\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{{}}");
        let denied = raw_http(private_address, &request(PRIVATE)).await;
        assert!(denied.starts_with("HTTP/1.1 409"), "{method} {path}: {denied}");
        assert!(denied.ends_with("config mutation requires an owned endpoint"));
        for secret in ["", "incorrect-session-secret"] {
            let unauthorized = raw_http(private_address, &request(secret)).await;
            assert!(unauthorized.starts_with("HTTP/1.1 401"), "{method} {path}: {unauthorized}");
        }
        let hidden = raw_http(user_address, &request(USER)).await;
        assert!(hidden.starts_with("HTTP/1.1 404"), "{method} {path}: {hidden}");
    }
    assert_eq!(serde_json::to_value(&*api.state.raw_config.read()).unwrap(), before_configs);

    assert!(http(user_address, "/configs", PRIVATE, "GET").await.starts_with("HTTP/1.1 401"));
    let cors = preflight(user_address, "/configs", "GET", "Authorization, Content-Type").await.to_ascii_lowercase();
    assert!(cors.starts_with("http/1.1 204"), "{cors}");
    assert!(cors.contains("access-control-allow-origin: *\r\n"));
    assert!(cors.contains("access-control-allow-methods: get\r\n"));
    assert!(cors.contains("access-control-allow-headers: content-type, authorization\r\n"));
    assert!(cors.contains("access-control-max-age: 300\r\n"));
    assert!(cors.contains("access-control-allow-private-network: true\r\n"));
    assert!(!cors.contains("access-control-allow-credentials:"));
    assert!(!cors.contains("access-control-allow-origin: https://dashboard.example"));
    let allowed = raw_http(user_address, &format!("GET /configs HTTP/1.1\r\nHost: localhost\r\nOrigin: https://dashboard.example\r\nAuthorization: Bearer {USER}\r\nConnection: close\r\n\r\n")).await;
    assert!(allowed.starts_with("HTTP/1.1 200"));
    assert!(allowed.to_ascii_lowercase().contains("access-control-allow-origin: *\r\n"));
    for (address, path, method, headers) in [
        (private_address, "/configs", "GET", "Authorization"),
        (user_address, "/aerove/proof", "GET", "Authorization"),
        (user_address, "/configs", "POST", "Authorization"),
        (user_address, "/configs", "GET", "X-Private-Admin"),
    ] {
        let denied = preflight(address, path, method, headers).await;
        assert!(!denied.starts_with("HTTP/1.1 2"), "{denied}");
        assert!(!denied.to_ascii_lowercase().contains("access-control-allow-origin:"));
    }
    let patch_preflight = preflight(user_address, "/configs", "PATCH", "Authorization, Content-Type").await;
    assert!(patch_preflight.starts_with("HTTP/1.1 204"), "{patch_preflight}");    let malformed = raw_http(user_address, "OPTIONS /configs HTTP/1.1\r\nHost: localhost\r\nAccess-Control-Request-Method: GET\r\nConnection: close\r\n\r\n").await;
    assert!(malformed.starts_with("HTTP/1.1 400"));
    api.cancel(); cancel.cancel();
    private_task.await.unwrap().unwrap(); user_task.await.unwrap().unwrap();
    api.join().await;
    assert_eq!(api.active_tasks(), 0);
    assert!(TcpListener::bind(private_address).await.is_ok());
    assert!(TcpListener::bind(user_address).await.is_ok());
}

#[tokio::test]
async fn actual_idle_websockets_cancel_join_and_cancelled_wait_can_be_retried() {
    let _lock = LOCK.lock().await;
    let (api, private, _user, _mixed) = fixture().await;
    let address = private.local_addr().unwrap();
    let cancel = CancellationToken::new();
    let owner = api.clone(); let token = cancel.clone();
    let server = tokio::spawn(async move { owner.serve_private(private, token).await });
    let (mut logs, _) = connect_async(format!("ws://{address}/logs?level=debug&token={PRIVATE}")).await.unwrap();
    let subscriber = tracing_subscriber::registry().with(api.log_layer());
    tracing::subscriber::with_default(subscriber, || {
        tracing::trace!(target: "tokio::task::waker", "internal-waker-must-not-enter-log-channel");
        tracing::trace!(target: "aerove::meow", "application-trace-remains-visible");
    });
    let frame = tokio::time::timeout(Duration::from_secs(5), logs.next()).await.unwrap().unwrap().unwrap();
    let log: serde_json::Value = serde_json::from_str(frame.to_text().unwrap()).unwrap();
    assert_eq!(log["type"], "debug");
    assert_eq!(log["payload"], "application-trace-remains-visible");
    let (mut traffic, _) = connect_async(format!("ws://{address}/traffic?token={PRIVATE}")).await.unwrap();
    let frame = tokio::time::timeout(Duration::from_secs(5), traffic.next()).await.unwrap().unwrap().unwrap();
    assert!(serde_json::from_str::<serde_json::Value>(frame.to_text().unwrap()).unwrap()["upTotal"].is_number());
    let (mut memory, _) = connect_async(format!("ws://{address}/memory?token={PRIVATE}")).await.unwrap();
    let frame = tokio::time::timeout(Duration::from_secs(5), memory.next()).await.unwrap().unwrap().unwrap();
    assert!(serde_json::from_str::<serde_json::Value>(frame.to_text().unwrap()).unwrap()["inuse"].as_u64().unwrap() > 0);
    let mut first_wait = Box::pin(api.join());
    poll_fn(|cx| { assert!(first_wait.as_mut().poll(cx).is_pending()); Poll::Ready(()) }).await;
    drop(first_wait);
    api.cancel(); cancel.cancel();
    server.await.unwrap().unwrap();
    tokio::time::timeout(Duration::from_secs(5), api.join()).await.unwrap();
    assert_eq!(api.active_tasks(), 0);
    for socket in [&mut logs, &mut traffic, &mut memory] {
        tokio::time::timeout(Duration::from_secs(5), async {
            for _ in 0..64 {
                let frame = socket.next().await;
                if frame.is_none() || frame.as_ref().is_some_and(|f| f.is_err() || f.as_ref().is_ok_and(|m| m.is_close())) {
                    return;
                }
            }
            panic!("transport kept producing frames after owner join");
        }).await.unwrap();
    }
    assert!(TcpListener::bind(address).await.is_ok());
}

#[tokio::test]
async fn private_connection_count_reads_same_live_statistics_without_exposing_connections() {
    let _lock = LOCK.lock().await;
    let (api, private, user, _mixed) = fixture().await;
    let private_address = private.local_addr().unwrap();
    let user_address = user.local_addr().unwrap();
    let cancel = CancellationToken::new();
    let owner = api.clone(); let token = cancel.clone();
    let private_task = tokio::spawn(async move { owner.serve_private(private, token).await });
    let owner = api.clone(); let token = cancel.clone();
    let user_task = tokio::spawn(async move { owner.serve_user(user, token).await });
    const PATH: &str = "/aerove/connections/count";
    assert!(http(private_address, PATH, "wrong-secret", "GET").await.starts_with("HTTP/1.1 401"));
    assert!(http(user_address, PATH, USER, "GET").await.starts_with("HTTP/1.1 404"));
    assert!(http(private_address, PATH, PRIVATE, "POST").await.starts_with("HTTP/1.1 405"));
    async fn count(address: std::net::SocketAddr, expected: u64) {
        let response = http(address, PATH, PRIVATE, "GET").await;
        assert!(response.starts_with("HTTP/1.1 200"), "{response}");
        let (headers, body) = response.split_once("\r\n\r\n").unwrap();
        assert!(headers.to_ascii_lowercase().contains("content-type: application/json"));
        let value: serde_json::Value = serde_json::from_str(body).unwrap();
        assert_eq!(value, serde_json::json!({"count":expected}));
    }
    count(private_address, 0).await;
    let stats = api.state.tunnel.statistics();
    let first = meow_tunnel::tcp::ConnectionGuard::track(&stats, meow_common::Metadata::default(),
        "MATCH".into(), "".into(), Default::default());
    first.counters().set_direct(true);
    count(private_address, 1).await;
    let second = meow_tunnel::tcp::ConnectionGuard::track(&stats, meow_common::Metadata::default(),
        "MATCH".into(), "".into(), Default::default());
    count(private_address, 2).await;
    drop(first); count(private_address, 1).await;
    drop(second); count(private_address, 0).await;
    api.control.cancel();
    let gone = http(private_address, PATH, PRIVATE, "GET").await;
    assert!(gone.starts_with("HTTP/1.1 410"), "{gone}");
    let value: serde_json::Value = serde_json::from_str(gone.split_once("\r\n\r\n").unwrap().1).unwrap();
    assert_eq!(value, serde_json::json!({"error":"cancelled"}));
    api.cancel(); cancel.cancel();
    private_task.await.unwrap().unwrap(); user_task.await.unwrap().unwrap();
    tokio::time::timeout(Duration::from_secs(5), api.join()).await.unwrap();
    assert_eq!(api.active_tasks(), 0);
    assert!(TcpListener::bind(private_address).await.is_ok());
    assert!(TcpListener::bind(user_address).await.is_ok());
}
