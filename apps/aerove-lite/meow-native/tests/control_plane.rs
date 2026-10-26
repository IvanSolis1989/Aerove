use aerove_meow_core::{api::CoreApi, control::ControlError};
use axum::Router;
use meow_common::TunnelMode;
use meow_tunnel::Tunnel;
use tokio_util::sync::CancellationToken;

#[tokio::test]
async fn committed_mode_and_selector_share_route_and_cancelled_generation_is_terminal() {
    let config = meow_config::load_config_from_str(
        "strict: true\nproxy-groups:\n  - name: PICK\n    type: select\n    proxies: [DIRECT, REJECT]\nrules: ['MATCH,PICK']\n"
    ).await.unwrap();
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
    tunnel.set_mode(config.general.mode);
    let cancel = CancellationToken::new();
    let api = CoreApi::assemble_with_epoch(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(),
        "control-plane-private-secret-00000001", None, Router::new(), u64::MAX, cancel.clone()).unwrap();
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
    let initial = api.control.snapshot().await.unwrap();
    assert_eq!(initial.core_epoch, u64::MAX.to_string());
    assert_eq!(initial.route_version, "0");
    assert_eq!(initial.picks["PICK"], "DIRECT");
    forward(tunnel.clone(), true).await;
    let selected = api.control.select("PICK", "REJECT").await.unwrap();
    assert_eq!(selected.picks["PICK"], "REJECT");
    assert_eq!(selected.route_version, "1");
    forward(tunnel.clone(), false).await;
    assert_eq!(tunnel.route_snapshot().proxies.get("PICK").unwrap().current().unwrap(), "REJECT");
    assert_eq!(api.control.select("PICK", "missing").await, Err(ControlError::InvalidSelection));
    assert_eq!(api.control.snapshot().await.unwrap(), selected);
    let mode = api.control.set_mode(TunnelMode::Direct).await.unwrap();
    assert_eq!(mode.route_version, "2");
    assert_eq!(tunnel.mode(), TunnelMode::Direct);
    forward(tunnel.clone(), true).await;
    assert_eq!(api.state.raw_config.read().mode.as_deref(), Some("direct"));
    assert_eq!(api.control.set_mode(TunnelMode::Direct).await.unwrap(), mode);
    cancel.cancel();
    assert_eq!(api.control.select("PICK", "DIRECT").await, Err(ControlError::Cancelled));
    assert_eq!(api.control.set_mode(TunnelMode::Global).await, Err(ControlError::Cancelled));
    assert_eq!(tunnel.mode(), TunnelMode::Direct);
    forward(tunnel.clone(), true).await;
    assert_eq!(tunnel.route_snapshot().proxies.get("PICK").unwrap().current().unwrap(), "REJECT");
    api.cancel();
    api.join().await;
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

async fn fallback_fixture(url: &str) -> (CoreApi, Tunnel) {
    let yaml = format!("strict: true\nproxy-groups:\n  - name: FALLBACK\n    type: fallback\n    url: {url}\n    interval: 0\n    proxies: [DIRECT, REJECT]\nrules: ['MATCH,FALLBACK']\n");
    let config = meow_config::load_config_from_str(&yaml).await.unwrap();
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
    let api = CoreApi::assemble_with_epoch(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(),
        "fallback-control-private-secret-0001", None, Router::new(), 17, CancellationToken::new()).unwrap();
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
    tunnel.route_snapshot().proxies.get("DIRECT").unwrap().health().record_delay(0);
    (api, tunnel)
}

async fn fallback_cancel_case(session_cancel: bool) {
    use std::sync::Arc;
    use tokio::{io::AsyncReadExt, net::TcpListener, sync::Notify};
    let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = target.local_addr().unwrap();
    let requested = Arc::new(Notify::new());
    let request_seen = requested.clone();
    let server = tokio::spawn(async move {
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
    let (api, tunnel) = fallback_fixture(&format!("http://{address}/health")).await;
    let before = api.control.snapshot().await.unwrap();
    assert_eq!(before.picks["FALLBACK"], "REJECT");
    let route = tunnel.route_snapshot();
    let group = route.proxies.get("FALLBACK").unwrap();
    let direct = route.proxies.get("DIRECT").unwrap();
    assert_eq!(group.selection().unwrap().fixed().as_deref(), Some(""));
    let mut selecting = Box::pin(api.control.select("FALLBACK", "DIRECT"));
    tokio::time::timeout(std::time::Duration::from_secs(3), async {
        tokio::select! {
            result = &mut selecting => panic!("selection completed before delayed response: {result:?}"),
            _ = requested.notified() => {},
        }
    }).await.unwrap();
    assert_eq!(group.selection().unwrap().fixed().as_deref(), Some(""));
    assert!(!direct.alive());
    if session_cancel {
        api.control.cancel();
        assert_eq!(selecting.await, Err(ControlError::Cancelled));
    } else {
        drop(selecting);
        assert_eq!(api.control.snapshot().await.unwrap(), before);
    }
    assert_eq!(group.selection().unwrap().fixed().as_deref(), Some(""));
    assert!(!direct.alive());
    assert_eq!(group.current().as_deref(), Some("REJECT"));
    tokio::time::timeout(std::time::Duration::from_secs(3), server).await.unwrap().unwrap();
    api.cancel(); api.join().await;
}

#[tokio::test]
async fn fallback_delayed_probe_caller_cancellation_does_not_commit_pin_or_health() {
    fallback_cancel_case(false).await;
}

#[tokio::test]
async fn fallback_delayed_probe_session_cancellation_does_not_commit_pin_or_health() {
    fallback_cancel_case(true).await;
}

#[tokio::test]
async fn fallback_same_current_selection_still_commits_auto_to_fixed() {
    let (api, tunnel) = fallback_fixture("http://127.0.0.1:1/health").await;
    let initial = api.control.snapshot().await.unwrap();
    assert_eq!(initial.picks["FALLBACK"], "REJECT");
    let receipt = api.control.select("FALLBACK", "REJECT").await.unwrap();
    assert_eq!(receipt.route_version, "1");
    assert_eq!(receipt.picks, initial.picks);
    assert_eq!(tunnel.route_snapshot().proxies.get("FALLBACK").unwrap()
        .selection().unwrap().fixed().as_deref(), Some("REJECT"));
    api.cancel(); api.join().await;
}

#[tokio::test]
async fn nested_fallback_is_rejected_before_dead_inner_pin_or_health_can_change() {
    let config = meow_config::load_config_from_str(
        "strict: true\nproxy-groups:\n  - name: INNER\n    type: fallback\n    interval: 0\n    url: http://127.0.0.1:1/inner\n    proxies: [DIRECT, REJECT]\n  - name: OUTER\n    type: fallback\n    interval: 0\n    url: http://127.0.0.1:1/outer\n    proxies: [INNER, REJECT]\nrules: ['MATCH,OUTER']\n"
    ).await.unwrap();
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
    let api = CoreApi::assemble_with_epoch(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(),
        "nested-control-private-secret-00001", None, Router::new(), 18, CancellationToken::new()).unwrap();
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
    let route = tunnel.route_snapshot();
    let inner = route.proxies.get("INNER").unwrap();
    let outer = route.proxies.get("OUTER").unwrap();
    let direct = route.proxies.get("DIRECT").unwrap();
    inner.selection().unwrap().force_set(Some("DIRECT"));
    outer.selection().unwrap().force_set(Some("REJECT"));
    direct.health().record_delay(0);
    let health_before = direct.delay_history();
    assert_eq!(inner.selection().unwrap().fixed().as_deref(), Some("DIRECT"));
    for name in ["INNER", "REJECT"] {
        assert_eq!(api.control.select("OUTER", name).await, Err(ControlError::SelectionMemberNotIntegrated));
        assert_eq!(inner.selection().unwrap().fixed().as_deref(), Some("DIRECT"));
        assert_eq!(outer.selection().unwrap().fixed().as_deref(), Some("REJECT"));
        assert!(!direct.alive());
        assert_eq!(direct.delay_history().len(), health_before.len());
    }
    api.cancel(); api.join().await;
}
