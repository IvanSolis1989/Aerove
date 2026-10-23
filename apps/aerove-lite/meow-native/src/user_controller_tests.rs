use meow_config::owned_load::{LoadContext, OwnedWork};
tokio::task_local! { static SESSION_SCOPE: (); }
struct TestContext;
impl LoadContext for TestContext {
    fn wrap(&self, work: OwnedWork) -> OwnedWork { Box::pin(SESSION_SCOPE.scope((), work)) }
    fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) { work(); }
}
use super::*;
use axum::{Router, routing::get};
use meow_tunnel::Tunnel;

const PRIVATE: &str = "aerove-private-controller-0000000000000001";
const USER: &str = "saved-用户-secret";

async fn keep_alive(address: SocketAddr, secret: &str) -> TcpStream {
    let mut stream = TcpStream::connect(address).await.unwrap();
    stream.write_all(format!("GET /version HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {secret}\r\nConnection: keep-alive\r\n\r\n").as_bytes()).await.unwrap();
    let mut bytes = Vec::new();
    tokio::time::timeout(IO_LIMIT, async {
        while !bytes.ends_with(b"\r\n\r\n") {
            bytes.push(stream.read_u8().await.unwrap());
            assert!(bytes.len() <= 8192);
        }
    }).await.unwrap();
    assert!(bytes.starts_with(b"HTTP/1.1 200"));
    stream
}

struct Fixture {
    owner: Arc<Owner>, api: Arc<CoreApi>, cancel: CancellationToken,
    private_address: SocketAddr, private_task: JoinHandle<Result<(), crate::api::ApiError>>,
    _mixed: TcpListener,
}
impl Fixture {
    async fn at(address: SocketAddr) -> Self {
        let config = meow_config::load_config_from_str("strict: true\nrules: ['MATCH,DIRECT']\n").await.unwrap();
        let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
        tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
        let private = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let private_address = private.local_addr().unwrap();
        let mixed = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let cancel = CancellationToken::new();
        let api = Arc::new(CoreApi::assemble_with_epoch(&config, tunnel.clone(), mixed.local_addr().unwrap(), PRIVATE,
            None, Router::new().route("/aerove/proof", get(|| async { "private-proof" })), 17, cancel.clone()).unwrap());
        tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
        let serving = api.clone(); let stopping = cancel.clone();
        let private_task = tokio::spawn(async move { serving.serve_private(private, stopping).await });
        let context = Arc::new(TestContext);
        let owner = Owner::at(api.clone(), private_address, address, context).unwrap();
        Self { owner, api, cancel, private_address, private_task, _mixed: mixed }
    }
    fn epoch(&self) -> u64 { self.api.control.plane.stamp().0 }
    async fn new() -> Self { Self::at("127.0.0.1:0".parse().unwrap()).await }
    async fn close(self) {
        self.cancel.cancel(); self.owner.stop().await.unwrap(); self.api.cancel();
        tokio::time::timeout(IO_LIMIT, self.private_task).await.unwrap().unwrap().unwrap();
        tokio::time::timeout(IO_LIMIT, self.api.join()).await.unwrap();
    }
    async fn private_alive(&self) {
        assert_eq!(probe(self.private_address, "/aerove/proof", Some(PRIVATE), &self.cancel).await.unwrap(), 200);
        assert_eq!(probe(self.private_address, "/version", Some(USER), &self.cancel).await.unwrap(), 401);
    }
}

#[tokio::test]
async fn user_controller_real_tcp_auth_cors_and_private_boundary() {
    let fixture = Fixture::new().await;
    let receipt = fixture.owner.configure(fixture.epoch(), true, USER).await.unwrap();
    let address = receipt.address.unwrap();
    assert_eq!(receipt.core_epoch, fixture.epoch().to_string()); assert!(fixture.epoch() > 17); assert!(receipt.authenticated && receipt.private_routes_excluded);
    assert_eq!(probe(address, "/version", Some(USER), &fixture.cancel).await.unwrap(), 200);
    assert_eq!(probe(address, "/version", Some(PRIVATE), &fixture.cancel).await.unwrap(), 401);
    assert_eq!(probe(address, "/aerove/proof", Some(USER), &fixture.cancel).await.unwrap(), 404);
    let mut stream = TcpStream::connect(address).await.unwrap();
    stream.write_all(b"OPTIONS /configs HTTP/1.1\r\nHost: localhost\r\nOrigin: https://dashboard.example\r\nAccess-Control-Request-Method: GET\r\nAccess-Control-Request-Headers: Authorization\r\nConnection: close\r\n\r\n").await.unwrap();
    let mut reply = String::new(); tokio::time::timeout(IO_LIMIT, stream.read_to_string(&mut reply)).await.unwrap().unwrap();
    assert!(reply.starts_with("HTTP/1.1 204"));
    assert!(reply.to_ascii_lowercase().contains("access-control-allow-origin: *"));
    assert!(!reply.to_ascii_lowercase().contains("access-control-allow-credentials:"));
    fixture.private_alive().await; fixture.close().await;
}

#[tokio::test]
async fn user_controller_secret_hotswap_retires_old_transports_and_readbacks_same_port() {
    let fixture = Fixture::new().await;
    let first = fixture.owner.configure(fixture.epoch(), true, USER).await.unwrap();
    assert!(fixture.owner.configure(first.core_epoch.parse().unwrap(), true, "invalid\r\nsecret").await.is_err());
    assert_eq!(fixture.owner.status().await.unwrap(), first);
    let address = first.address.unwrap();
    let mut old = keep_alive(address, USER).await;
    let changed = fixture.owner.configure(first.core_epoch.parse().unwrap(), true, "next-saved-secret").await.unwrap();
    assert_eq!(changed.address, first.address); assert!(changed.core_epoch.parse::<u64>().unwrap() > first.core_epoch.parse::<u64>().unwrap());
    assert_eq!(probe(address, "/version", Some(USER), &fixture.cancel).await.unwrap(), 401);
    assert_eq!(probe(address, "/version", Some("next-saved-secret"), &fixture.cancel).await.unwrap(), 200);
    let mut bytes = Vec::new(); let _ = tokio::time::timeout(IO_LIMIT, old.read_to_end(&mut bytes)).await.unwrap();
    assert!(!bytes.starts_with(b"HTTP/1.1 200"));
    assert_eq!(fixture.owner.status().await.unwrap(), changed);
    assert_eq!(fixture.owner.configure(changed.core_epoch.parse().unwrap(), true, "next-saved-secret").await.unwrap(), changed);
    fixture.private_alive().await; fixture.close().await;
}

#[tokio::test]
async fn user_controller_disable_releases_bind_without_changing_private_listener() {
    let fixture = Fixture::new().await;
    let enabled = fixture.owner.configure(fixture.epoch(), true, USER).await.unwrap(); let address = enabled.address.unwrap();
    let disabled = fixture.owner.configure(enabled.core_epoch.parse().unwrap(), false, "").await.unwrap();
    assert!(!disabled.enabled && !disabled.authenticated); assert_eq!(disabled.address, None); assert!(disabled.core_epoch.parse::<u64>().unwrap() > enabled.core_epoch.parse::<u64>().unwrap());
    let rebound = TcpListener::bind(address).await.unwrap(); drop(rebound);
    assert_eq!(fixture.owner.status().await.unwrap(), disabled);
    fixture.private_alive().await; fixture.close().await;
}

#[tokio::test]
async fn user_controller_bind_failure_and_stale_cas_leave_current_policy_unchanged() {
    let occupied = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let fixture = Fixture::at(occupied.local_addr().unwrap()).await;
    assert!(fixture.owner.configure(fixture.epoch(), true, USER).await.is_err());
    assert_eq!(fixture.api.control.plane.stamp().0, 17);
    assert!(!fixture.owner.status().await.unwrap().enabled); drop(occupied);
    let stale = fixture.epoch();
    let first = fixture.owner.configure(stale, true, USER).await.unwrap();
    assert!(fixture.owner.configure(stale, true, "stale-secret").await.is_err());
    assert_eq!(fixture.owner.status().await.unwrap(), first);
    assert_eq!(probe(first.address.unwrap(), "/version", Some(USER), &fixture.cancel).await.unwrap(), 200);
    fixture.private_alive().await; fixture.close().await;
}

#[tokio::test]
async fn user_controller_newest_queued_intent_wins_without_a_second_registry() {
    let fixture = Fixture::new().await;
    let gate = fixture.owner.state.lock().await;
    let expected = fixture.epoch();
    let first_owner = fixture.owner.clone();
    let first = tokio::spawn(async move { first_owner.configure(expected, true, "old-request").await });
    tokio::task::yield_now().await;
    let second_owner = fixture.owner.clone();
    let second = tokio::spawn(async move { second_owner.configure(expected, true, USER).await });
    tokio::task::yield_now().await; drop(gate);
    assert!(tokio::time::timeout(IO_LIMIT, first).await.unwrap().unwrap().is_err());
    let latest = tokio::time::timeout(IO_LIMIT, second).await.unwrap().unwrap().unwrap();
    assert_eq!(latest.core_epoch, fixture.epoch().to_string()); assert!(fixture.epoch() > expected);
    assert_eq!(probe(latest.address.unwrap(), "/version", Some(USER), &fixture.cancel).await.unwrap(), 200);
    fixture.close().await;
}

#[tokio::test]
async fn user_controller_session_cancel_and_join_close_accepted_tcp_and_bind() {
    let fixture = Fixture::new().await;
    let enabled = fixture.owner.configure(fixture.epoch(), true, USER).await.unwrap(); let address = enabled.address.unwrap();
    let mut held = keep_alive(address, USER).await;
    fixture.cancel.cancel(); tokio::time::timeout(IO_LIMIT, fixture.owner.stop()).await.unwrap().unwrap();
    let mut bytes = Vec::new(); let _ = tokio::time::timeout(IO_LIMIT, held.read_to_end(&mut bytes)).await.unwrap();
    assert!(!bytes.starts_with(b"HTTP/1.1 200"));
    assert!(fixture.owner.status().await.is_err());
    let rebound = TcpListener::bind(address).await.unwrap(); drop(rebound);
    fixture.close().await;
}

async fn running_with_task(fixture: &Fixture, task: JoinHandle<io::Result<()>>) -> Running {
    let socket = TcpListener::bind("127.0.0.1:0").await.unwrap().into_std().unwrap();
    Running { address: socket.local_addr().unwrap(), socket, secret: Arc::from(USER),
        stop: fixture.cancel.child_token(),
        endpoint: fixture.api.user_endpoint(USER, fixture.owner.context.clone()).unwrap(),
        task: Some(task), activate: None, business_failure: None, uncertain: None }
}

#[tokio::test]
async fn user_controller_join_timeout_stays_unknown_on_second_stop_and_status() {
    let fixture = Fixture::new().await;
    let mut failures = fixture.owner.subscribe_failure();
    let mut retired = running_with_task(&fixture, tokio::spawn(std::future::pending())).await;
    let first = retired.close_with_limit(Duration::from_millis(1)).await.unwrap_err();
    assert_eq!(first.to_string(), "userControllerJoinTimeout");
    assert!(retired.task.is_some());
    assert_eq!(retired.close().await.unwrap_err().to_string(), first.to_string());
    fixture.owner.state.lock().await.retired.push(retired);
    assert_eq!(fixture.owner.stop().await.unwrap_err().to_string(), first.to_string());
    tokio::time::timeout(IO_LIMIT, failures.changed()).await.unwrap().unwrap();
    assert_eq!(*failures.borrow_and_update(), Some("userControllerJoinTimeout"));
    assert_eq!(fixture.owner.status().await.unwrap_err().to_string(), first.to_string());
    assert_eq!(fixture.owner.stop().await.unwrap_err().to_string(), first.to_string());
    assert_eq!(fixture.owner.state.lock().await.retired.len(), 1);
    assert_eq!(fixture.owner.failure(), Some("userControllerJoinTimeout"));
    // The failed owner deliberately never acknowledges shutdown; fixture teardown joins only its private server.
    fixture.api.cancel();
    tokio::time::timeout(IO_LIMIT, fixture.private_task).await.unwrap().unwrap().unwrap();
    tokio::time::timeout(IO_LIMIT, fixture.api.join()).await.unwrap();
}

#[tokio::test]
async fn user_controller_panicked_root_stays_unknown_but_confirmed_close_is_idempotent() {
    let fixture = Fixture::new().await;
    let failure = fixture.owner.failure.clone(); let parent = fixture.cancel.clone();
    let mut panicked = running_with_task(&fixture, tokio::spawn(async move {
        let _exit = ServeExit { failure, parent, stop: CancellationToken::new() };
        panic!("root fixture panic")
    })).await;
    assert_eq!(panicked.close().await.unwrap_err().to_string(), "userControllerTaskPanicked");
    assert_eq!(fixture.owner.failure(), Some("userControllerTaskPanicked"));
    assert_eq!(panicked.close().await.unwrap_err().to_string(), "userControllerTaskPanicked");
    let mut confirmed = running_with_task(&fixture, tokio::spawn(async { Ok(()) })).await;
    confirmed.close().await.unwrap(); confirmed.close().await.unwrap();
    assert!(confirmed.task.is_none() && confirmed.uncertain.is_none());
    fixture.close().await;
}

// Uses the same HTTP connection factory and lifecycle upgrade hook as UserEndpoint.
// The deliberate probe route isolates task propagation; the six tests above exercise the authenticated CoreApi router.
#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
#[tokio::test]
async fn user_controller_http_ws_and_background_preserve_real_session_protector_and_policy() {
    use axum::{Extension, extract::ws::{WebSocketUpgrade, Message}};
    use futures::StreamExt;
    use meow_common::{socket_protect::SocketProtector, request_policy::{PolicySource, RequestPolicy}};
    use std::{os::fd::RawFd, sync::atomic::AtomicUsize};
    struct Protect(Arc<AtomicUsize>);
    impl SocketProtector for Protect {
        fn protect(&self, _fd: RawFd) -> io::Result<()> { self.0.fetch_add(1, Ordering::AcqRel); Ok(()) }
    }
    struct Policy;
    impl PolicySource for Policy {
        fn snapshot(&self) -> io::Result<Arc<RequestPolicy>> {
            Ok(Arc::new(RequestPolicy::new("same-session-test-UA".into(), false)?))
        }
    }
    async fn exchange(address: SocketAddr) -> Vec<u8> {
        let policy = meow_common::request_policy::current().unwrap().expect("captured policy missing");
        assert_eq!(policy.global_user_agent, "same-session-test-UA"); assert!(!policy.check_certificate);
        let mut stream = meow_common::socket_protect::connect_tcp(address).await.unwrap();
        let payload = [0, 255, 128, 1];
        stream.write_all(&payload).await.unwrap();
        let mut reply = [0; 4]; stream.read_exact(&mut reply).await.unwrap();
        assert_eq!(reply, payload); reply.to_vec()
    }
    let peer = TcpListener::bind("127.0.0.1:0").await.unwrap(); let peer_address = peer.local_addr().unwrap();
    let peer_task = tokio::spawn(async move {
        tokio::time::timeout(IO_LIMIT, async {
            for _ in 0..3 {
                let (mut socket, _) = peer.accept().await.unwrap();
                let mut payload = [0; 4]; socket.read_exact(&mut payload).await.unwrap();
                assert_eq!(payload, [0, 255, 128, 1]); socket.write_all(&payload).await.unwrap();
            }
        }).await.unwrap();
    });
    let cancel = CancellationToken::new(); let protects = Arc::new(AtomicUsize::new(0));
    let context: Arc<dyn LoadContext> = Arc::new(crate::network_context::NetworkContext::protected(
        Arc::new(Protect(protects.clone())), None, cancel.clone()).with_request_policy(Arc::new(Policy)));
    let scope = meow_api::lifecycle::ApiLifecycle::with_context(context.clone());
    let router = Router::new().route("/http", get(move || async move { exchange(peer_address).await }))
        .route("/ws", get(move |ws: WebSocketUpgrade, Extension(scope): Extension<meow_api::lifecycle::ApiLifecycle>| async move {
            scope.on_upgrade(ws, move |mut socket| async move {
                socket.send(Message::Binary(exchange(peer_address).await.into())).await.unwrap();
                let _ = socket.recv().await;
            })
        })).layer(Extension(scope.clone()));
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
    let stopping = cancel.clone(); let serving_scope = scope.clone();
    let server = tokio::spawn(async move { crate::api::serve_scoped(listener, router, serving_scope, stopping, Some(context)).await });
    assert_eq!(probe(address, "/http", None, &cancel).await.unwrap(), 200);
    let (mut ws, _) = tokio::time::timeout(IO_LIMIT, tokio_tungstenite::connect_async(format!("ws://{address}/ws"))).await.unwrap().unwrap();
    let message = tokio::time::timeout(IO_LIMIT, ws.next()).await.unwrap().unwrap().unwrap();
    assert_eq!(message.into_data().as_ref(), &[0, 255, 128, 1]);
    let (done, completed) = oneshot::channel();
    scope.spawn(async move { exchange(peer_address).await; let _ = done.send(()); });
    tokio::time::timeout(IO_LIMIT, completed).await.unwrap().unwrap();
    tokio::time::timeout(IO_LIMIT, peer_task).await.unwrap().unwrap();
    assert_eq!(protects.load(Ordering::Acquire), 3);
    cancel.cancel();
    tokio::time::timeout(IO_LIMIT, server).await.unwrap().unwrap().unwrap();
    tokio::time::timeout(IO_LIMIT, scope.join()).await.unwrap(); assert_eq!(scope.active_tasks(), 0);
    let rebound = TcpListener::bind(address).await.unwrap(); drop(rebound);
}

#[tokio::test]
async fn user_controller_joined_serve_failure_preserves_failure_without_false_unknown_ack() {
    let fixture = Fixture::new().await;
    let retired = running_with_task(&fixture,tokio::spawn(async { Err(error("apiConnectionPanicked")) })).await;
    fixture.owner.state.lock().await.retired.push(retired);
    fixture.owner.stop().await.unwrap();
    assert_eq!(fixture.owner.failure(),Some("userControllerServeFailed"));
    assert!(fixture.owner.state.lock().await.retired.is_empty());
    fixture.owner.stop().await.unwrap();
    fixture.api.cancel();
    tokio::time::timeout(IO_LIMIT,fixture.private_task).await.unwrap().unwrap().unwrap();
    tokio::time::timeout(IO_LIMIT,fixture.api.join()).await.unwrap();
}

#[test]
fn user_controller_readback_decodes_status_without_decoding_binary_entity() {
    for (response, expected) in [
        (b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n\x00\xff\x80\x01".as_slice(), 200),
        (b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 2\r\n\r\n\xff\x80".as_slice(), 401),
        (b"HTTP/1.1 404 Not Found\r\nContent-Length: 1\r\n\r\n\xff".as_slice(), 404),
        (b"HTTP/1.0 200 OK\r\n\r\n\xff".as_slice(), 200),
    ] {
        assert_eq!(readback_status(response).unwrap(), expected);
    }
}

#[test]
fn user_controller_readback_rejects_malformed_status_or_incomplete_head() {
    for response in [
        b"".as_slice(), b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n".as_slice(),
        b"HTTP/1.1 200 OK\n\n".as_slice(), b"garbage 200 OK\r\n\r\n".as_slice(),
        b"HTTP/2.0 200 OK\r\n\r\n".as_slice(), b"HTTP/1.1 20 OK\r\n\r\n".as_slice(),
        b"HTTP/1.1 2000 OK\r\n\r\n".as_slice(), b"HTTP/1.1 099 Invalid\r\n\r\n".as_slice(),
        b"HTTP/1.1 abc Invalid\r\n\r\n".as_slice(), b"HTTP/1.1 200\r\n\r\n".as_slice(),
        b"HTTP/1.1 200 bad\x00reason\r\n\r\n".as_slice(),
    ] {
        assert_eq!(readback_status(response).unwrap_err().to_string(), "userControllerReadbackInvalid");
    }
}
