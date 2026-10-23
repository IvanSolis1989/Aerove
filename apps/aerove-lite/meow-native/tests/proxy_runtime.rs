use aerove_meow_core::{Phase, Registry, Session, StartOptions};
use std::{net::SocketAddr, sync::Arc, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};

static LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());
const SECRET: &str = "aerove-candidate-test-private-secret-0001";

fn options() -> StartOptions {
    StartOptions {
        config_yaml: "strict: true\nmode: rule\nrules: ['MATCH,DIRECT']\n".into(),
        private_api: "127.0.0.1:0".parse().unwrap(),
        mixed: "127.0.0.1:0".parse().unwrap(),
        secret: SECRET.into(),
    }
}

async fn ready(session: &Session) -> aerove_meow_core::Snapshot {
    tokio::time::timeout(Duration::from_secs(15), session.ready()).await.unwrap().unwrap()
}

async fn request(address: SocketAddr, request: &str) -> String {
    let mut socket = TcpStream::connect(address).await.unwrap();
    socket.write_all(request.as_bytes()).await.unwrap();
    let mut response = String::new();
    tokio::time::timeout(Duration::from_secs(5), socket.read_to_string(&mut response)).await.unwrap().unwrap();
    response
}

async fn stop(session: &Arc<Session>) {
    let stopped = tokio::time::timeout(Duration::from_secs(5), session.stop()).await.unwrap().unwrap();
    assert!(matches!(stopped.phase, Phase::Stopped | Phase::Failed));
    assert!(!stopped.api_ready && !stopped.user_listeners_ready && !stopped.tun_ready);
    assert!(stopped.api_address.is_none() && stopped.mixed_address.is_none());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn proxy_direct_http_snapshot_cancel_join_and_rebind() {
    let _lock = LOCK.lock().await;
    let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let destination = target.local_addr().unwrap();
    let responder = tokio::spawn(async move {
        let (mut stream, _) = target.accept().await.unwrap();
        let mut headers = Vec::new();
        while !headers.ends_with(b"\r\n\r\n") {
            headers.push(stream.read_u8().await.unwrap());
            assert!(headers.len() < 8192);
        }
        let headers = String::from_utf8(headers).unwrap();
        assert!(headers.starts_with("GET /through-meow HTTP/1.1\r\n"));
        stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 12\r\nConnection: close\r\n\r\nMEOW-DIRECT!").await.unwrap();
        stream.shutdown().await.unwrap();
    });
    let session = Registry::start(options()).unwrap();
    let running = ready(&session).await;
    assert_eq!(running.phase, Phase::Running);
    assert!(running.api_ready && running.user_listeners_ready && !running.tun_ready);
    let mixed = running.mixed_address.unwrap();
    let api = running.api_address.unwrap();
    let response = request(mixed, &format!("GET http://{destination}/through-meow HTTP/1.1\r\nHost: {destination}\r\nConnection: close\r\n\r\n")).await;
    assert!(response.starts_with("HTTP/1.1 200"), "{response}");
    assert!(response.ends_with("MEOW-DIRECT!"));
    responder.await.unwrap();
    let unauthorized = request(api, "GET /aerove/runtime HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").await;
    assert!(unauthorized.starts_with("HTTP/1.1 401"));
    let snapshot = request(api, &format!("GET /aerove/runtime HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {SECRET}\r\nConnection: close\r\n\r\n")).await;
    assert!(snapshot.starts_with("HTTP/1.1 200"));
    let body: serde_json::Value = serde_json::from_str(snapshot.split_once("\r\n\r\n").unwrap().1).unwrap();
    assert_eq!(body["generation"], running.generation);
    assert_eq!(body["runtimeMode"], "proxy");
    assert_eq!(body["mixedAddress"], mixed.to_string());
    assert!(body["downloadBytes"].as_str().unwrap().parse::<u64>().unwrap() >= 12);
    assert!(body["proxyTraffic"]["DIRECT"][1].as_str().unwrap().parse::<u64>().unwrap() >= 12);
    // An idle accepted transport must not delay owner stop or survive it.
    let mut idle = TcpStream::connect(mixed).await.unwrap();
    idle.write_all(&[5]).await.unwrap();
    stop(&session).await;
    let mut byte = [0];
    let read = tokio::time::timeout(Duration::from_secs(2), idle.read(&mut byte)).await.unwrap();
    assert!(matches!(read, Ok(0) | Err(_)));
    let mut restart = options();
    restart.private_api = api;
    restart.mixed = mixed;
    let successor = Registry::start(restart).unwrap();
    assert!(successor.generation() > session.generation());
    let actual = ready(&successor).await;
    assert_eq!(actual.api_address, Some(api));
    assert_eq!(actual.mixed_address, Some(mixed));
    stop(&successor).await;
    assert!(TcpListener::bind(api).await.is_ok());
    assert!(TcpListener::bind(mixed).await.is_ok());
}

#[tokio::test]
async fn bind_conflict_fails_and_releases_prebound_private_api() {
    let _lock = LOCK.lock().await;
    let conflict = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let reserve = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let api = reserve.local_addr().unwrap();
    drop(reserve);
    let mut start = options();
    start.private_api = api;
    start.mixed = conflict.local_addr().unwrap();
    let session = Registry::start(start).unwrap();
    let error = session.ready().await.unwrap_err();
    assert_eq!(error.code, "mixedBindFailed");
    assert_eq!(session.snapshot().phase, Phase::Failed);
    assert!(TcpListener::bind(api).await.is_ok());
    stop(&session).await;
}

#[tokio::test]
async fn bad_config_is_an_explicit_failure_without_listeners() {
    let _lock = LOCK.lock().await;
    let mut start = options();
    start.config_yaml = "strict: true\nrules: ['NO-SUCH-RULE,private']\n".into();
    let session = Registry::start(start).unwrap();
    assert_eq!(session.ready().await.unwrap_err().code, "invalidConfig");
    assert!(!session.snapshot().api_ready && !session.snapshot().user_listeners_ready);
    stop(&session).await;
    let mut unsupported = options();
    unsupported.config_yaml = "strict: true\ntun: {enable: true}\n".into();
    let session = Registry::start(unsupported).unwrap();
    let failure = session.ready().await.unwrap_err();
    assert_eq!(failure.code, "candidateFieldNotIntegrated");
    assert_eq!(failure.field.as_deref(), Some("tun"));
    stop(&session).await;
}

#[tokio::test]
async fn stop_while_loading_never_late_publishes_ready_or_steals_successor() {
    let _lock = LOCK.lock().await;
    let session = Registry::start(options()).unwrap();
    assert_eq!(session.snapshot().phase, Phase::Loading);
    assert_eq!(Registry::start(options()).err().unwrap().code, "runtimeAlreadyOwned");
    // current-thread runtime: cancellation is recorded before load can admit any socket.
    stop(&session).await;
    assert_eq!(session.ready().await.unwrap_err().code, "cancelled");
    let successor = Registry::start(options()).unwrap();
    ready(&successor).await;
    stop(&session).await;
    assert_eq!(successor.snapshot().phase, Phase::Running);
    stop(&successor).await;
}
