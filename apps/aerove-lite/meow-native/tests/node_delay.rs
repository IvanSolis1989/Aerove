use aerove_meow_core::{api::CoreApi, providers::OwnedLoadScope};
use axum::Router;
use meow_tunnel::Tunnel;
use std::{sync::Arc, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};
use tokio_util::sync::CancellationToken;

const PRIVATE: &str = "node-delay-private-secret-00000001";
const USER: &str = "node-delay-user-secret";

async fn headers(stream: &mut TcpStream) {
    let mut head = Vec::new();
    while !head.ends_with(b"\r\n\r\n") { head.push(stream.read_u8().await.unwrap()); assert!(head.len() < 8192); }
}
async fn get(address: std::net::SocketAddr, path: &str) -> String {
    let mut stream = TcpStream::connect(address).await.unwrap();
    stream.write_all(format!("GET {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {USER}\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut response = String::new();
    tokio::time::timeout(Duration::from_secs(5), stream.read_to_string(&mut response)).await.unwrap().unwrap();
    response
}
async fn fixture() -> (Arc<CoreApi>, Tunnel, Arc<OwnedLoadScope>, std::net::SocketAddr,
    CancellationToken, tokio::task::JoinHandle<()>, tokio::task::JoinHandle<()>) {
    let scope = OwnedLoadScope::new();
    let config = meow_config::load_config_from_str_owned("rules: ['MATCH,DIRECT']\n", &scope).await.unwrap();
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
    let api = Arc::new(CoreApi::assemble_with_epoch(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(),
        PRIVATE, Some(USER), Router::new(), 91, CancellationToken::new()).unwrap());
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
    let events = api.control.activate_groups(&scope).unwrap();
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
    let cancel = CancellationToken::new();
    let serve = { let api = api.clone(); let cancel = cancel.clone(); tokio::spawn(async move { api.serve_user(listener, cancel).await.unwrap(); }) };
    (api, tunnel, scope, address, cancel, serve, events)
}
async fn stop(api: Arc<CoreApi>, scope: Arc<OwnedLoadScope>, cancel: CancellationToken,
    serve: tokio::task::JoinHandle<()>, events: tokio::task::JoinHandle<()>) {
    api.control.cancel(); scope.close().await.unwrap();
    if let Err(error) = events.await { assert!(error.is_cancelled()); }
    cancel.cancel(); api.cancel(); serve.await.unwrap(); api.join().await;
}
fn path(origin: std::net::SocketAddr) -> String {
    format!("/proxies/DIRECT/delay?url=http%3A%2F%2F127.0.0.1%3A{}%2Fobserved-policy&timeout=2000", origin.port())
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn real_node_delay_public_get_records_committed_history_and_returns_same_plane_stamp() {
    let (api, tunnel, scope, address, cancel, serve, events) = fixture().await;
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let origin_address = origin.local_addr().unwrap();
    let direct = tunnel.route_snapshot().proxies["DIRECT"].clone(); let before = direct.health().delay_history().len();
    let invalid = get(address, &format!("/proxies/DIRECT/delay?url=http://{origin_address}/invalid&timeout=0")).await;
    assert!(invalid.starts_with("HTTP/1.1 400")); assert_eq!(direct.health().delay_history().len(), before);
    let stale = get(address, &(path(origin_address) + "&coreEpoch=90")).await;
    assert!(stale.starts_with("HTTP/1.1 409")); assert_eq!(direct.health().delay_history().len(), before);
    let url = path(origin_address); let request = tokio::spawn(async move { get(address, &url).await });
    let (mut stream, _) = origin.accept().await.unwrap(); headers(&mut stream).await;
    stream.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();
    let response = request.await.unwrap(); assert!(response.starts_with("HTTP/1.1 200"));
    assert!(response.contains("x-aerove-core-epoch: 91\r\n")); assert!(response.contains("x-aerove-picks-version: 0\r\n"));
    let body: serde_json::Value = serde_json::from_str(response.split("\r\n\r\n").nth(1).unwrap()).unwrap();
    assert!(body["delay"].is_u64()); assert_eq!(body.as_object().unwrap().len(), 1);
    assert_eq!(direct.health().delay_history().len(), before + 1);
    assert_eq!(api.control.plane.stamp(), (91, 0));
    stop(api, scope, cancel, serve, events).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 3)]
async fn latest_real_node_probe_and_session_cancel_fence_old_health_results() {
    let (api, tunnel, scope, address, cancel, serve, events) = fixture().await;
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let origin_address = origin.local_addr().unwrap();
    let direct = tunnel.route_snapshot().proxies["DIRECT"].clone(); let before = direct.health().delay_history().len();
    let url = path(origin_address); let first = { let url = url.clone(); tokio::spawn(async move { get(address, &url).await }) };
    let (mut slow, _) = origin.accept().await.unwrap(); headers(&mut slow).await;
    let second = { let url = url.clone(); tokio::spawn(async move { get(address, &url).await }) };
    let (mut fast, _) = origin.accept().await.unwrap(); headers(&mut fast).await;
    fast.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();
    assert!(second.await.unwrap().starts_with("HTTP/1.1 200"));
    slow.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();
    assert!(first.await.unwrap().starts_with("HTTP/1.1 409"));
    assert_eq!(direct.health().delay_history().len(), before + 1);
    let pending = tokio::spawn(async move { get(address, &url).await });
    let (mut cancelled, _) = origin.accept().await.unwrap(); headers(&mut cancelled).await;
    api.control.cancel(); assert!(pending.await.unwrap().starts_with("HTTP/1.1 410"));
    let mut tail = Vec::new(); tokio::time::timeout(Duration::from_secs(5), cancelled.read_to_end(&mut tail)).await.unwrap().unwrap();
    assert_eq!(direct.health().delay_history().len(), before + 1);
    stop(api, scope, cancel, serve, events).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn caller_abort_drops_captured_io_and_lease_before_close_returns() {
    let scope = OwnedLoadScope::new();
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = origin.local_addr().unwrap();
    let task = { let scope = scope.clone(); tokio::spawn(async move {
        scope.run_captured(async move {
            let mut socket = TcpStream::connect(address).await.unwrap();
            socket.write_all(b"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n").await.unwrap();
            let mut tail = Vec::new(); socket.read_to_end(&mut tail).await.unwrap();
        }).await
    }) };
    let (mut socket, _) = origin.accept().await.unwrap(); headers(&mut socket).await;
    task.abort(); assert!(task.await.unwrap_err().is_cancelled());
    scope.close().await.unwrap();
    let mut tail = Vec::new(); tokio::time::timeout(Duration::from_secs(5), socket.read_to_end(&mut tail)).await.unwrap().unwrap();
    assert!(tail.is_empty());
    let polled = Arc::new(std::sync::atomic::AtomicBool::new(false)); let output = polled.clone();
    assert_eq!(scope.run_captured(async move { output.store(true, std::sync::atomic::Ordering::SeqCst); }).await, Err("ownedLoadScopeClosed"));
    assert!(!polled.load(std::sync::atomic::Ordering::SeqCst));
}
