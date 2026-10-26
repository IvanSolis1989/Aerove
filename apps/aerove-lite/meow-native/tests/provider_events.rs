use futures::StreamExt;
use aerove_meow_core::{api::CoreApi, providers::{ControlPlane, OwnedLoadScope, LoadContext, OwnedWork}};
use meow_config::provider_events::ProviderObserver;
use axum::Router;
use meow_tunnel::Tunnel;
use std::{sync::Arc, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}, sync::Notify};
use tokio_util::sync::CancellationToken;
const PRIVATE: &str = "provider-private-owner-secret-0000001";
const USER: &str = "provider-user-secret";
struct Context(Arc<ControlPlane>);
impl LoadContext for Context {
    fn provider_observer(&self) -> Option<Arc<dyn ProviderObserver>> { Some(self.0.clone()) }
    fn wrap(&self, work: OwnedWork) -> OwnedWork { work }
    fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) { work(); }
}
struct ReentrantWake { plane: Arc<ControlPlane>, observed: std::sync::atomic::AtomicBool, completed: Notify }
impl std::task::Wake for ReentrantWake {
    fn wake(self: Arc<Self>) { self.wake_by_ref(); }
    fn wake_by_ref(self: &Arc<Self>) {
        let receipt = self.plane.provider_baseline();
        assert!(receipt.iter().any(|receipt| receipt.revision.parse::<u64>().unwrap() >= 2));
        self.observed.store(true, std::sync::atomic::Ordering::SeqCst);
        self.completed.notify_one();
    }
}async fn request(address: std::net::SocketAddr, method: &str, path: &str, secret: &str, body: &str) -> String {
    let mut stream = TcpStream::connect(address).await.unwrap();
    stream.write_all(format!("{method} {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {secret}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).as_bytes()).await.unwrap();
    let mut output = String::new();
    tokio::time::timeout(Duration::from_secs(5), stream.read_to_string(&mut output)).await.unwrap().unwrap();
    output
}
fn body(response: &str) -> serde_json::Value { serde_json::from_str(response.split_once("\r\n\r\n").unwrap().1).unwrap() }

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn real_http_initial_manual_unchanged_receipts_busy_and_public_mutators_share_plane() {
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let source = origin.local_addr().unwrap();
    let manual_started = Arc::new(Notify::new());
    let release = Arc::new(Notify::new());
    let started = manual_started.clone(); let releasing = release.clone();
    let source_task = tokio::spawn(async move {
        for index in 0..4 {
            let (mut stream, _) = origin.accept().await.unwrap();
            let mut headers = Vec::new();
            while !headers.ends_with(b"\r\n\r\n") { headers.push(stream.read_u8().await.unwrap()); assert!(headers.len() < 8192); }
            if index == 1 { started.notify_one(); releasing.notified().await; }
            let payload = if index == 3 { "<invalid provider>" } else { "proxies:\n  - {name: d1, type: direct}\n" };
            let headers = format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nsubscription-userinfo: upload={}; download=2; total=30\r\nConnection: close\r\n\r\n{payload}",payload.len(),index+1);
            stream.write_all(headers.as_bytes()).await.unwrap();
        }
    });
    let cancel = CancellationToken::new();
    let plane = ControlPlane::new(42, cancel.clone()).unwrap();
    let mut events = plane.subscribe();
    let scope = OwnedLoadScope::with_context(Arc::new(Context(plane.clone())));
    let yaml = format!("strict: true\nproxy-providers:\n  feed:\n    type: http\n    url: http://{source}/feed\n    interval: 0\nproxy-groups:\n  - name: PICK\n    type: select\n    use: [feed]\nrules: ['MATCH,PICK']\n");
    let config = meow_config::load_config_from_str_owned(&yaml, &scope).await.unwrap();
    assert!(events.try_recv().is_err(), "initial success published before live routing installation");
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
    let private = TcpListener::bind("127.0.0.1:0").await.unwrap(); let private_address=private.local_addr().unwrap();
    let user = TcpListener::bind("127.0.0.1:0").await.unwrap(); let user_address=user.local_addr().unwrap();
    let api = CoreApi::assemble_with_plane(&config,tunnel.clone(),"127.0.0.1:12345".parse().unwrap(),PRIVATE,Some(USER),Router::new(),plane.clone()).unwrap();
    tunnel.update_routing(config.proxies,config.rules,config.dialer_registry);
    api.activate_providers().unwrap();
    let api=Arc::new(api);
    let owner=api.clone(); let token=cancel.clone(); let private_task=tokio::spawn(async move{owner.serve_private(private,token).await});
    let owner=api.clone(); let token=cancel.clone(); let user_task=tokio::spawn(async move{owner.serve_user(user,token).await});
    let initial=tokio::time::timeout(Duration::from_secs(5),events.recv()).await.unwrap().unwrap();
    assert_eq!(initial.name,"feed"); assert_eq!(initial.revision,"1"); assert_eq!(initial.phase,"initial"); assert!(initial.changed);
    let initial_get=request(user_address,"GET","/providers/proxies/feed",USER,"").await;
    assert!(initial_get.starts_with("HTTP/1.1 200"),"{initial_get}"); assert_eq!(body(&initial_get)["revision"],"1");
    let (mut telemetry, _) = tokio_tungstenite::connect_async(format!("ws://{private_address}/aerove/telemetry?token={PRIVATE}")).await.unwrap();
    let first_frame = next_non_traffic(&mut telemetry).await;
    let frame: serde_json::Value = serde_json::from_str(first_frame.to_text().unwrap()).unwrap();
    assert_eq!(frame["type"], "providersLoaded"); assert_eq!(frame["providers"][0]["revision"], "1");    let reentry = Arc::new(ReentrantWake { plane: plane.clone(), observed: std::sync::atomic::AtomicBool::new(false), completed: Notify::new() });
    let waker = std::task::Waker::from(reentry.clone());
    let mut reentry_events = plane.subscribe();
    let mut reentry_receive = Box::pin(reentry_events.recv());
    {
        use std::future::Future;
        assert!(reentry_receive.as_mut().poll(&mut std::task::Context::from_waker(&waker)).is_pending());
    }    let first=tokio::spawn(request(user_address,"PUT","/providers/proxies/feed",USER,""));
    tokio::time::timeout(Duration::from_secs(5),manual_started.notified()).await.unwrap();
    for (method,path) in [("GET","/providers/proxies/feed"),("GET","/providers/proxies"),("PUT","/providers/proxies/feed")] {
        let busy=request(private_address,method,path,PRIVATE,"").await; assert!(busy.starts_with("HTTP/1.1 409"),"{busy}");
    }
    release.notify_one();
    assert!(first.await.unwrap().starts_with("HTTP/1.1 204"));
    tokio::time::timeout(Duration::from_secs(5), reentry.completed.notified()).await.unwrap();
    assert!(reentry.observed.load(std::sync::atomic::Ordering::SeqCst));
    drop(reentry_receive);
    let updated=tokio::time::timeout(Duration::from_secs(5),events.recv()).await.unwrap().unwrap();
    let update_frame = next_non_traffic(&mut telemetry).await;
    let update_wire: serde_json::Value = serde_json::from_str(update_frame.to_text().unwrap()).unwrap();
    assert_eq!(update_wire["providers"][0]["revision"], "2");
    assert_eq!(updated.revision,"2"); assert_eq!(updated.phase,"update"); assert!(!updated.changed);
    let update_get=request(private_address,"GET","/providers/proxies/feed",PRIVATE,"").await;
    assert_eq!(body(&update_get)["revision"],"2"); assert_eq!(body(&update_get)["subscriptionInfo"]["Upload"],2);
    assert!(request(user_address,"PUT","/providers/proxies/feed",USER,"").await.starts_with("HTTP/1.1 204"));
    let unchanged=tokio::time::timeout(Duration::from_secs(5),events.recv()).await.unwrap().unwrap();
    let unchanged_frame = next_non_traffic(&mut telemetry).await;
    let unchanged_wire: serde_json::Value = serde_json::from_str(unchanged_frame.to_text().unwrap()).unwrap();
    assert_eq!(unchanged_wire["providers"][0]["revision"], "3");
    assert_eq!(unchanged.revision,"3"); assert!(!unchanged.changed);
    assert!(request(user_address,"PUT","/providers/proxies/feed",USER,"").await.starts_with("HTTP/1.1 503"));
    assert!(events.try_recv().is_err());
    let failed_get=request(user_address,"GET","/providers/proxies/feed",USER,"").await;
    assert_eq!(body(&failed_get)["revision"],"3");
    assert!(request(user_address,"PATCH","/configs",USER,"{\"mode\":\"direct\"}").await.starts_with("HTTP/1.1 204"));
    assert_eq!(tunnel.mode(),meow_common::TunnelMode::Direct);
    assert!(request(user_address,"PUT","/proxies/PICK",USER,"{\"name\":\"d1\"}").await.starts_with("HTTP/1.1 204"));
    assert_eq!(tunnel.route_snapshot().proxies.get("PICK").unwrap().current().as_deref(),Some("d1"));
    assert!(request(user_address,"GET","/aerove/route",USER,"").await.starts_with("HTTP/1.1 404"));
    api.cancel(); cancel.cancel(); scope.cancel();
    private_task.await.unwrap().unwrap(); user_task.await.unwrap().unwrap(); api.join().await; scope.close().await.unwrap();
    source_task.await.unwrap(); assert_eq!(api.active_tasks(),0);
}

#[tokio::test]
async fn retired_http_provider_source_is_rejected_before_fetch_or_receipt_change() {
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = origin.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut stream, _) = origin.accept().await.unwrap();
        let mut headers=Vec::new();
        while !headers.ends_with(b"\r\n\r\n") { headers.push(stream.read_u8().await.unwrap()); assert!(headers.len()<8192); }
        let payload="proxies:\n - {name: d1, type: direct}\n";
        stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{payload}",payload.len()).as_bytes()).await.unwrap();
    });
    let plane=ControlPlane::new(43,CancellationToken::new()).unwrap();
    let scope=OwnedLoadScope::with_context(Arc::new(Context(plane.clone())));
    let yaml=format!("strict: true\nproxy-providers:\n  feed:\n    type: http\n    url: http://{address}/feed\n    interval: 0\nrules: ['MATCH,DIRECT']\n");
    let config=meow_config::load_config_from_str_owned(&yaml,&scope).await.unwrap();
    server.await.unwrap();
    let provider=config.proxy_providers.get("feed").unwrap().clone();
    let tunnel=Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    tunnel.update_routing(config.proxies,config.rules,config.dialer_registry);
    plane.activate(&[provider.provider_identity().clone()]).unwrap();
    assert_eq!(plane.provider_baseline()[0].revision,"1");
    plane.activate(&[]).unwrap();
    assert_eq!(provider.refresh_owned().await.unwrap_err(),"staleProviderSource");
    assert!(plane.provider_baseline().is_empty());
    assert_eq!(provider.proxies().len(),1);
    scope.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn real_timer_receipts_join_stalled_fetch_before_successor() {
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = origin.local_addr().unwrap();
    let timer_started = Arc::new(Notify::new());
    let release_timer = Arc::new(Notify::new());
    let stalled = Arc::new(Notify::new());
    let started = timer_started.clone(); let release = release_timer.clone(); let pending = stalled.clone();
    let source = tokio::spawn(async move {
        for index in 0..4 {
            let (mut stream, _) = origin.accept().await.unwrap();
            let mut headers = Vec::new();
            while !headers.ends_with(b"\r\n\r\n") { headers.push(stream.read_u8().await.unwrap()); assert!(headers.len() < 8192); }
            if index == 1 { started.notify_one(); release.notified().await; }
            if index == 3 {
                pending.notify_one();
                let mut tail = Vec::new();
                stream.read_to_end(&mut tail).await.unwrap();
                assert!(tail.is_empty(), "cancelled fetch must close the original connection");
                return;
            }
            let payload = if index < 2 { "proxies:\n - {name: d1, type: direct}\n" } else { "proxies:\n - {name: d2, type: direct}\n" };
            stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{payload}", payload.len()).as_bytes()).await.unwrap();
        }
    });
    let cancel = CancellationToken::new();
    let plane = ControlPlane::new(44, cancel.clone()).unwrap();
    let scope = OwnedLoadScope::with_context(Arc::new(Context(plane.clone())));
    let mut events = plane.subscribe();
    let yaml = format!("strict: true\nproxy-providers:\n  feed:\n    type: http\n    url: http://{address}/feed\n    interval: 1\nrules: ['MATCH,DIRECT']\n");
    let config = meow_config::load_config_from_str_owned(&yaml, &scope).await.unwrap();
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let api_address = listener.local_addr().unwrap();
    let api = Arc::new(CoreApi::assemble_with_plane(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(), PRIVATE, None, Router::new(), plane.clone()).unwrap());
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
    api.activate_providers().unwrap();
    assert!(api.activate_providers().is_err(), "activation must not duplicate timer registration");
    let serving = api.clone(); let stopping = cancel.clone();
    let server = tokio::spawn(async move { serving.serve_private(listener, stopping).await });
    let initial = tokio::time::timeout(Duration::from_secs(5), events.recv()).await.unwrap().unwrap();
    assert_eq!(initial.revision, "1"); assert_eq!(initial.phase, "initial");
    let stamp_before = plane.stamp();
    let route_before = request(api_address, "GET", "/aerove/route", PRIVATE, "").await;
    tokio::time::timeout(Duration::from_secs(5), timer_started.notified()).await.unwrap();
    for path in ["/providers/proxies", "/providers/proxies/feed"] {
        assert!(request(api_address, "GET", path, PRIVATE, "").await.starts_with("HTTP/1.1 409"));
    }
    assert!(request(api_address, "PUT", "/providers/proxies/feed", PRIVATE, "").await.starts_with("HTTP/1.1 409"));
    release_timer.notify_one();
    let unchanged = tokio::time::timeout(Duration::from_secs(5), events.recv()).await.unwrap().unwrap();
    assert_eq!(unchanged.revision, "2"); assert_eq!(unchanged.phase, "update"); assert!(!unchanged.changed);
    let route_after = request(api_address, "GET", "/aerove/route", PRIVATE, "").await;
    assert_eq!(body(&route_before)["route-version"], body(&route_after)["route-version"]);
    assert_eq!(plane.stamp(), stamp_before, "unchanged success changed the published route stamp");
    let changed = tokio::time::timeout(Duration::from_secs(5), events.recv()).await.unwrap().unwrap();
    assert_eq!(changed.revision, "3"); assert!(changed.changed);
    let structural_stamp = plane.stamp();
    assert_eq!(structural_stamp.0, stamp_before.0); assert!(structural_stamp.1 > stamp_before.1);
    let receipt = request(api_address, "GET", "/providers/proxies/feed", PRIVATE, "").await;
    assert_eq!(body(&receipt)["revision"], "3");
    assert_eq!(body(&receipt)["proxies"][0]["name"], "d2");
    tokio::time::timeout(Duration::from_secs(5), stalled.notified()).await.unwrap();
    api.cancel(); scope.cancel();
    server.await.unwrap().unwrap(); api.join().await;
    tokio::time::timeout(Duration::from_secs(5), scope.close()).await.unwrap().unwrap();
    tokio::time::timeout(Duration::from_secs(5), source).await.unwrap().unwrap();
    assert_eq!(plane.provider_baseline()[0].revision, "3");
    assert!(events.try_recv().is_err(), "cancelled timer must not acknowledge a successful update");
    assert_eq!(api.active_tasks(), 0);
    let successor_plane = ControlPlane::new(45, CancellationToken::new()).unwrap();
    let successor = OwnedLoadScope::with_context(Arc::new(Context(successor_plane)));
    meow_config::load_config_from_str_owned("strict: true\nrules: ['MATCH,DIRECT']\n", &successor).await.unwrap();
    successor.close().await.unwrap();
}
#[test]
fn provider_names_preserve_sourcec_utf16_and_kotlin_blank_domain() {
    use meow_config::provider_events::ProviderKind;
    let plane = ControlPlane::new(46, CancellationToken::new()).unwrap();
    for name in ["", " ", "\u{00a0}", "\u{202f}", "\u{0085}", ".", "..", "x\u{007f}", "x\u{009f}"] {
        assert!(plane.validate(ProviderKind::Proxy, name).is_err(), "accepted {name:?}");
        assert!(plane.validate(ProviderKind::Rule, name).is_err(), "accepted {name:?}");
    }
    for name in [" . ", ".x", "\u{200b}", "\u{feff}", "中 文"] {
        assert!(plane.validate(ProviderKind::Proxy, name).is_ok(), "rejected {name:?}");
        assert!(plane.validate(ProviderKind::Rule, name).is_ok(), "rejected {name:?}");
    }
    let exact = "😀".repeat(512);
    assert!(plane.validate(ProviderKind::Proxy, &exact).is_ok());
    assert!(plane.validate(ProviderKind::Proxy, &(exact + "a")).is_err());
}
#[tokio::test]
async fn preloaded_source_replacement_cannot_regress_same_identity_receipt() {
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = origin.local_addr().unwrap();
    let server = tokio::spawn(async move {
        for _ in 0..3 {
            let (mut stream, _) = origin.accept().await.unwrap();
            let mut headers = Vec::new();
            while !headers.ends_with(b"\r\n\r\n") { headers.push(stream.read_u8().await.unwrap()); assert!(headers.len() < 8192); }
            let payload = "proxies:\n - {name: d1, type: direct}\n";
            stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{payload}", payload.len()).as_bytes()).await.unwrap();
        }
    });
    let plane = ControlPlane::new(47, CancellationToken::new()).unwrap();
    let scope = OwnedLoadScope::with_context(Arc::new(Context(plane.clone())));
    let yaml = format!("strict: true\nproxy-providers:\n  feed:\n    type: http\n    url: http://{address}/feed\n    interval: 0\nrules: ['MATCH,DIRECT']\n");
    let first = meow_config::load_config_from_str_owned(&yaml, &scope).await.unwrap();
    let second = meow_config::load_config_from_str_owned(&yaml, &scope).await.unwrap();
    let a = first.proxy_providers.get("feed").unwrap();
    let b = second.proxy_providers.get("feed").unwrap();
    assert_ne!(a.provider_identity().source, b.provider_identity().source);
    plane.activate(&[a.provider_identity().clone()]).unwrap();
    a.refresh_owned().await.unwrap();
    server.await.unwrap();
    assert_eq!(plane.provider_baseline()[0].revision, "2");
    assert_eq!(plane.activate(&[b.provider_identity().clone()]).unwrap_err(), "providerSourceReplacementNotIntegrated");
    assert_eq!(plane.provider_baseline()[0].revision, "2");
    plane.activate(&[]).unwrap();
    assert_eq!(plane.activate(&[b.provider_identity().clone()]).unwrap_err(), "providerSourceReplacementNotIntegrated");
    assert_eq!(a.refresh_owned().await.unwrap_err(), "staleProviderSource");
    assert!(plane.provider_baseline().is_empty());
    scope.close().await.unwrap();
}

#[tokio::test]
async fn cancelled_plane_rejects_initial_factory_before_http() {
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = origin.local_addr().unwrap();
    let plane = ControlPlane::new(48, CancellationToken::new()).unwrap();
    let scope = OwnedLoadScope::with_context(Arc::new(Context(plane.clone())));
    plane.cancel();
    let yaml = format!("strict: true\nproxy-providers:\n  feed:\n    type: http\n    url: http://{address}/feed\n    interval: 0\nrules: ['MATCH,DIRECT']\n");
    let result = meow_config::load_config_from_str_owned(&yaml, &scope).await;
    assert!(result.is_err());
    assert!(result.err().unwrap().to_string().contains("cancelled"));
    assert!(tokio::time::timeout(Duration::from_millis(100), origin.accept()).await.is_err(), "closed factory admission opened a socket");
    assert!(plane.provider_baseline().is_empty());
    scope.close().await.unwrap();
}

#[tokio::test]
async fn structural_epoch_exhaustion_rejects_real_initial_install_without_wrap() {
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = origin.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut stream, _) = origin.accept().await.unwrap();
        let mut headers = Vec::new();
        while !headers.ends_with(b"\r\n\r\n") { headers.push(stream.read_u8().await.unwrap()); assert!(headers.len() < 8192); }
        let payload = "proxies:\n - {name: d1, type: direct}\n";
        stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{payload}", payload.len()).as_bytes()).await.unwrap();
    });
    let plane = ControlPlane::new(u64::MAX, CancellationToken::new()).unwrap();
    let scope = OwnedLoadScope::with_context(Arc::new(Context(plane.clone())));
    let yaml = format!("strict: true\nproxy-providers:\n  feed:\n    type: http\n    url: http://{address}/feed\n    interval: 0\nrules: ['MATCH,DIRECT']\n");
    let result = meow_config::load_config_from_str_owned(&yaml, &scope).await;
    assert!(result.is_err()); assert!(result.err().unwrap().to_string().contains("coreEpochExhausted"));
    server.await.unwrap(); scope.close().await.unwrap();
    assert_eq!(plane.stamp(), (u64::MAX, 0)); assert!(plane.provider_baseline().is_empty());
}

// Multiplexing adds only the known traffic event to these existing scenarios.
// One absolute deadline covers every skipped frame; unknown/error events are
// returned to the unchanged scenario assertions, never swallowed.
async fn next_non_traffic<S>(socket:&mut S)->tokio_tungstenite::tungstenite::Message
where S:futures::Stream<Item=Result<tokio_tungstenite::tungstenite::Message,tokio_tungstenite::tungstenite::Error>>+Unpin {
    let deadline=tokio::time::Instant::now()+Duration::from_secs(5);
    loop {
        let frame=tokio::time::timeout_at(deadline,socket.next()).await.unwrap().unwrap().unwrap();
        let event:serde_json::Value=serde_json::from_str(frame.to_text().unwrap()).unwrap();
        if event["type"]!="traffic" { return frame; }
        assert_eq!(event.as_object().unwrap().len(),5);
        for key in ["up","down","uploadTotal","downloadTotal"] { assert!(event[key].as_i64().is_some_and(|value|value>=0)); }
    }
}
