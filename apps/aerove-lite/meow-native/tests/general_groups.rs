use aerove_meow_core::{api::CoreApi, control::ControlError, providers::OwnedLoadScope};
use axum::Router;
use futures::StreamExt;
use meow_tunnel::Tunnel;
use std::{sync::Arc, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};
use tokio_util::sync::CancellationToken;

const PRIVATE: &str = "owned-group-private-secret-00000001";
const USER: &str = "owned-group-user-secret";
struct ReentrantWake {
    plane: Arc<aerove_meow_core::providers::ControlPlane>, done: tokio::sync::Notify,
}
impl std::task::Wake for ReentrantWake {
    fn wake(self: Arc<Self>) { self.wake_by_ref(); }
    fn wake_by_ref(self: &Arc<Self>) { let _ = self.plane.stamp(); self.done.notify_one(); }
}

async fn headers(stream: &mut TcpStream) {
    let mut head = Vec::new();
    while !head.ends_with(b"\r\n\r\n") { head.push(stream.read_u8().await.unwrap()); assert!(head.len() < 8192); }
}
async fn request(address: std::net::SocketAddr, path: &str, body: &str) -> String {
    let mut stream = TcpStream::connect(address).await.unwrap();
    stream.write_all(format!("PUT {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {USER}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).as_bytes()).await.unwrap();
    let mut response = String::new();
    tokio::time::timeout(Duration::from_secs(5), stream.read_to_string(&mut response)).await.unwrap().unwrap();
    response
}
async fn fixture(yaml: &str) -> (Arc<CoreApi>, Tunnel, Arc<OwnedLoadScope>, Vec<meow_common::HealthCheckSpec>) {
    let scope = OwnedLoadScope::new();
    let config = meow_config::load_config_from_str_owned(yaml, &scope).await.unwrap();
    let specs = meow_config::extract_health_check_specs(config.raw.proxy_groups.as_deref().unwrap_or(&[]));
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    tunnel.set_dialer_registry(config.provider_dialer_registry.clone()); tunnel.set_mode(config.general.mode);
    let api = CoreApi::assemble_with_epoch(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(),
        PRIVATE, Some(USER), Router::new(), 77, CancellationToken::new()).unwrap();
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
    (Arc::new(api), tunnel, scope, specs)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 3)]
async fn real_periodic_fallback_nested_put_route_and_group_event_share_one_stamp_and_stop_joins() {
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = origin.local_addr().unwrap();
    let responder = tokio::spawn(async move {
        loop {
            let (mut stream, _) = origin.accept().await.unwrap(); headers(&mut stream).await;
            stream.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();
        }
    });
    let failed = TcpListener::bind("127.0.0.1:0").await.unwrap(); let failed_port = failed.local_addr().unwrap().port();
    let failures = tokio::spawn(async move {
        loop {
            let (mut stream, _) = failed.accept().await.unwrap(); headers(&mut stream).await;
            stream.write_all(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await.unwrap();
        }
    });
    let yaml = format!("strict: true\nproxies:\n - {{name: BAD, type: http, server: 127.0.0.1, port: {failed_port}}}\nproxy-groups:\n - {{name: AUTO, type: fallback, proxies: [BAD, DIRECT], url: 'http://{address}/health', interval: 1, lazy: false}}\n - {{name: OUTER, type: select, proxies: [AUTO, REJECT]}}\nrules: ['MATCH,OUTER']\n");
    let (api, tunnel, scope, specs) = fixture(&yaml).await;
    let events_task = api.control.activate_groups(&scope).unwrap();
    assert_eq!(specs.len(), 1); assert_eq!(specs[0].interval_secs, 1);
    let initial = api.control.snapshot().await.unwrap(); assert_eq!(initial.picks["AUTO"], "BAD");
    let private = TcpListener::bind("127.0.0.1:0").await.unwrap(); let private_address = private.local_addr().unwrap();
    let user = TcpListener::bind("127.0.0.1:0").await.unwrap(); let user_address = user.local_addr().unwrap();
    let serving_cancel = CancellationToken::new();
    let serving = { let api = api.clone(); let cancel = serving_cancel.clone(); tokio::spawn(async move { api.serve_private(private, cancel).await.unwrap(); }) };
    let user_serving = { let api = api.clone(); let cancel = serving_cancel.clone(); tokio::spawn(async move { api.serve_user(user, cancel).await.unwrap(); }) };
    let (mut socket, _) = tokio_tungstenite::connect_async(format!("ws://{private_address}/aerove/telemetry?token={PRIVATE}")).await.unwrap();
    let reentry = Arc::new(ReentrantWake { plane: api.control.plane.clone(), done: tokio::sync::Notify::new() });
    let waker = std::task::Waker::from(reentry.clone()); let mut events = api.control.subscribe_groups();
    let mut receive = Box::pin(events.recv());
    { use std::future::Future; assert!(receive.as_mut().poll(&mut std::task::Context::from_waker(&waker)).is_pending()); }
    let loop_task = scope.spawn_owned(meow_tunnel::health_check::run_health_check_loop(tunnel.weak_inner(), specs[0].clone())).unwrap();
    let changed = tokio::time::timeout(Duration::from_secs(5), async {
        let mut previous = 0;
        loop {
            let frame = socket.next().await.unwrap().unwrap();
            let event: serde_json::Value = serde_json::from_str(frame.to_text().unwrap()).unwrap();
            assert_eq!(event["type"], "groupsChanged"); let revision = event["revision"].as_u64().unwrap();
            assert!(revision > previous); previous = revision; assert_eq!(event.as_object().unwrap().len(), 2);
            let snapshot = api.control.snapshot().await.unwrap();
            if snapshot.picks["AUTO"] == "DIRECT" { break snapshot; }
        }
    }).await.unwrap();
    assert_eq!(changed.core_epoch, "77"); assert!(changed.picks_version.parse::<u64>().unwrap() > initial.picks_version.parse::<u64>().unwrap());
    tokio::time::timeout(Duration::from_secs(5), reentry.done.notified()).await.unwrap(); drop(receive);
    forward(tunnel.clone(), true).await;
    assert!(request(user_address, "/proxies/OUTER", "{\"name\":\"REJECT\"}").await.starts_with("HTTP/1.1 204"));
    forward(tunnel.clone(), false).await;
    assert!(request(user_address, "/proxies/OUTER", "{\"name\":\"AUTO\"}").await.starts_with("HTTP/1.1 204"));
    assert_eq!(api.control.snapshot().await.unwrap().picks["OUTER"], "AUTO"); forward(tunnel.clone(), true).await;
    api.control.cancel(); scope.close().await.unwrap(); assert!(loop_task.await.unwrap_err().is_cancelled());
    if let Err(error) = events_task.await { assert!(error.is_cancelled()); }
    let stamp = api.control.plane.stamp(); let direct = tunnel.route_snapshot().proxies["DIRECT"].clone();
    let history = direct.health().delay_history().len(); direct.health().record_delay(123);
    assert_eq!(direct.health().delay_history().len(), history); assert_eq!(api.control.plane.stamp(), stamp);
    serving_cancel.cancel(); api.cancel(); serving.await.unwrap(); user_serving.await.unwrap(); api.join().await;
    responder.abort(); assert!(responder.await.unwrap_err().is_cancelled()); failures.abort(); assert!(failures.await.unwrap_err().is_cancelled());
}

async fn nested_slow() -> (Arc<CoreApi>, Tunnel, Arc<OwnedLoadScope>, TcpListener, tokio::task::JoinHandle<()>) {
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = origin.local_addr().unwrap();
    let yaml = format!("strict: true\nproxy-groups:\n - {{name: INNER, type: select, proxies: [DIRECT, REJECT]}}\n - {{name: OUTER, type: fallback, proxies: [INNER, REJECT], url: 'http://{address}/health', interval: 0}}\nrules: ['MATCH,OUTER']\n");
    let (api, tunnel, scope, _) = fixture(&yaml).await;
    tunnel.route_snapshot().proxies["DIRECT"].health().record_delay(0);
    let events = api.control.activate_groups(&scope).unwrap();
    (api, tunnel, scope, origin, events)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn nested_real_slow_probe_caller_abort_and_session_cancel_publish_no_pin_or_health() {
    for session_cancel in [false, true] {
        let (api, tunnel, scope, origin, events) = nested_slow().await;
        let before = api.control.snapshot().await.unwrap(); let route = tunnel.route_snapshot();
        let direct = route.proxies["DIRECT"].clone(); let history = direct.health().delay_history().len();
        let control = api.control.clone(); let select = tokio::spawn(async move { control.select("OUTER", "INNER").await });
        let (mut stream, _) = tokio::time::timeout(Duration::from_secs(5), origin.accept()).await.unwrap().unwrap(); headers(&mut stream).await;
        assert!(!select.is_finished()); assert_eq!(api.control.snapshot().await.unwrap(), before);
        assert_eq!(route.proxies["OUTER"].selection().unwrap().fixed().as_deref(), Some(""));
        if session_cancel {
            api.control.cancel(); assert_eq!(select.await.unwrap(), Err(ControlError::Cancelled));
        } else {
            select.abort(); assert!(select.await.unwrap_err().is_cancelled());
            assert_eq!(api.control.snapshot().await.unwrap(), before); api.control.cancel();
        }
        let mut remaining = Vec::new(); tokio::time::timeout(Duration::from_secs(5), stream.read_to_end(&mut remaining)).await.unwrap().unwrap();
        scope.close().await.unwrap(); assert_eq!(direct.health().delay_history().len(), history);
        if let Err(error) = events.await { assert!(error.is_cancelled()); }
        assert!(!direct.health().alive()); assert_eq!(route.proxies["OUTER"].selection().unwrap().fixed().as_deref(), Some(""));
        api.cancel(); api.join().await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn later_selection_fences_old_real_nested_probe_before_any_health_commit() {
    let (api, tunnel, scope, origin, events) = nested_slow().await;
    let direct = tunnel.route_snapshot().proxies["DIRECT"].clone(); let history = direct.health().delay_history().len();
    let control = api.control.clone(); let slow = tokio::spawn(async move { control.select("OUTER", "INNER").await });
    let (mut stream, _) = tokio::time::timeout(Duration::from_secs(5), origin.accept()).await.unwrap().unwrap(); headers(&mut stream).await;
    let current = api.control.select("OUTER", "REJECT").await.unwrap();
    stream.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap(); drop(stream);
    assert_eq!(slow.await.unwrap(), Err(ControlError::Superseded));
    assert_eq!(api.control.snapshot().await.unwrap(), current); assert_eq!(direct.health().delay_history().len(), history); assert!(!direct.health().alive());
    api.control.cancel(); scope.close().await.unwrap(); if let Err(error) = events.await { assert!(error.is_cancelled()); } api.cancel(); api.join().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn owned_selection_flushes_real_store_after_memory_commit_and_owner_joins_it() {
    let cache = std::env::temp_dir().join(format!("aerove-selection-{}-{}", std::process::id(),
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
    std::fs::create_dir(&cache).unwrap();
    let scope = OwnedLoadScope::new();
    let config = meow_config::load_config_from_str_owned_with_cache(
        "strict: true\nproxy-groups: [{name: OUTER, type: select, proxies: [DIRECT, REJECT]}]\nrules: ['MATCH,OUTER']\n", &scope, Some(&cache)).await.unwrap();
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    let api = CoreApi::assemble_with_epoch(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(),
        PRIVATE, None, Router::new(), 78, CancellationToken::new()).unwrap();
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
    let events = api.control.activate_groups(&scope).unwrap();
    let result = api.control.select("OUTER", "REJECT").await.unwrap();
    assert_eq!(result.picks["OUTER"], "REJECT"); assert_eq!(result.picks_version, "1");
    let persisted: serde_json::Value = serde_json::from_slice(&std::fs::read(cache.join("selector-cache.json")).unwrap()).unwrap();
    assert_eq!(persisted["OUTER"], "REJECT");
    api.control.cancel(); scope.close().await.unwrap(); if let Err(error) = events.await { assert!(error.is_cancelled()); } api.cancel(); api.join().await;
    std::fs::remove_file(cache.join("selector-cache.json")).unwrap(); std::fs::remove_dir(&cache).unwrap();
}

async fn forward(tunnel: Tunnel, expected_success: bool) {
    use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};
    use std::time::Duration;
    let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let destination = target.local_addr().unwrap();
    let responder = tokio::spawn(async move {
        let (mut socket, _) = target.accept().await.unwrap();
        let mut headers = Vec::new();
        while !headers.ends_with(b"\r\n\r\n") {
            headers.push(socket.read_u8().await.unwrap());
            assert!(headers.len() < 8192);
        }
        socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 12\r\nConnection: close\r\n\r\nROUTE-PROOF!").await.unwrap();
    });
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let serving = tokio::spawn(async move {
        let (socket, peer) = listener.accept().await.unwrap();
        meow_listener::http_proxy::handle_http(&tunnel, socket, peer, None, None, "CONTROL-PROOF", address.port()).await;
    });
    let mut client = TcpStream::connect(address).await.unwrap();
    client.write_all(format!("GET http://{destination}/proof HTTP/1.1\r\nHost: {destination}\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut response = String::new();
    tokio::time::timeout(Duration::from_secs(5), client.read_to_string(&mut response)).await.unwrap().unwrap();
    tokio::time::timeout(Duration::from_secs(5), serving).await.unwrap().unwrap();
    if expected_success {
        assert!(response.starts_with("HTTP/1.1 200"), "{response}");
        assert!(response.ends_with("ROUTE-PROOF!"));
        responder.await.unwrap();
    } else {
        assert!(!response.starts_with("HTTP/1.1 200"), "{response}");
        assert!(!responder.is_finished(), "rejected route reached target");
        responder.abort();
        assert!(responder.await.unwrap_err().is_cancelled());
    }
}
