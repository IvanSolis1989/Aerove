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
            let frame = next_non_traffic(&mut socket).await;
            let event: serde_json::Value = serde_json::from_str(frame.to_text().unwrap()).unwrap();
            assert_eq!(event["type"], "groupsChanged"); let revision = event["revision"].as_u64().unwrap();
            assert!(revision > previous); previous = revision;
            let object = event.as_object().unwrap(); assert_eq!(object.len(), 3);
            assert!(["type", "revision", "routeVersion"].iter().all(|key| object.contains_key(*key)));
            let route_version = event["routeVersion"].as_u64().unwrap();
            assert_eq!(event["revision"].to_string(), revision.to_string());
            assert_eq!(event["routeVersion"].to_string(), route_version.to_string());
            let snapshot = api.control.snapshot().await.unwrap();
            let snapshot_version = snapshot.route_version.parse::<u64>().unwrap();
            assert_eq!(snapshot_version.to_string(), snapshot.route_version);
            assert!(snapshot_version >= route_version);
            if snapshot.picks["AUTO"] == "DIRECT" { break snapshot; }
        }
    }).await.unwrap();
    assert_eq!(changed.core_epoch, "77"); assert!(changed.route_version.parse::<u64>().unwrap() > initial.route_version.parse::<u64>().unwrap());
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
    assert_eq!(result.picks["OUTER"], "REJECT"); assert_eq!(result.route_version, "1");
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

#[cfg(unix)]
#[path="support/provider_general.rs"]mod pure_servers;
#[cfg(unix)]
async fn managed_delay(address:std::net::SocketAddr,path:&str)->String{
    let mut stream=TcpStream::connect(address).await.unwrap();stream.write_all(format!("GET {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {PRIVATE}\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();let mut bytes=String::new();tokio::time::timeout(Duration::from_secs(5),stream.read_to_string(&mut bytes)).await.unwrap().unwrap();bytes
}
#[cfg(unix)]
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn complete_nested_measurement_commits_requested_group_once_without_leaf_rtt_copy(){
    let mut servers=pure_servers::Servers::new();let proxy=servers.proxy().await;let target=servers.origin().await;
    let yaml=format!("strict: true\nproxies: [{{name: NODE,type: http,server: 127.0.0.1,port: {}}}]\nproxy-groups: [{{name: INNER,type: select,proxies: [NODE,REJECT]}}]\nrules: ['MATCH,DIRECT']\n",proxy.address.port());
    let(api,tunnel,scope,_)=fixture(&yaml).await;let groups=api.control.activate_groups(&scope).unwrap();let route=tunnel.route_snapshot();let inner=route.proxies["INNER"].clone();let leaf=route.proxies["NODE"].clone();let own=inner.health().delay_history().len();let child=serde_json::to_value(leaf.health().delay_history()).unwrap();
    let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();let serving_cancel=CancellationToken::new();let serving={let api=api.clone();let token=serving_cancel.clone();tokio::spawn(async move{api.serve_private(listener,token).await.unwrap();})};
    let response=managed_delay(address,&pure_servers::delay_path("INNER",target)).await;assert!(response.starts_with("HTTP/1.1 200"),"{response}");assert_eq!(proxy.targets.lock().unwrap().as_slice(),[target.to_string()]);assert_eq!(inner.health().delay_history().len(),own+1);assert_eq!(serde_json::to_value(leaf.health().delay_history()).unwrap(),child,"complete target RTT belongs to the requested group, not its leaf");
    serving_cancel.cancel();api.cancel();scope.close().await.unwrap();if let Err(error)=groups.await{assert!(error.is_cancelled());}serving.await.unwrap();api.join().await;servers.close().await;
}
#[cfg(unix)]
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn slow_complete_nested_measurement_epoch_change_publishes_no_health_or_pick(){
    let mut servers=pure_servers::Servers::new();let proxy=servers.proxy().await;let target=servers.origin().await;proxy.hold.store(true,std::sync::atomic::Ordering::Release);
    let yaml=format!("strict: true\nproxies: [{{name: NODE,type: http,server: 127.0.0.1,port: {}}}]\nproxy-groups: [{{name: INNER,type: select,proxies: [NODE,REJECT]}}]\nrules: ['MATCH,DIRECT']\n",proxy.address.port());
    let(api,tunnel,scope,_)=fixture(&yaml).await;let groups=api.control.activate_groups(&scope).unwrap();let route=tunnel.route_snapshot();let inner=route.proxies["INNER"].clone();let leaf=route.proxies["NODE"].clone();let own=serde_json::to_value(inner.health().delay_history()).unwrap();let child=serde_json::to_value(leaf.health().delay_history()).unwrap();let before=api.control.snapshot().await.unwrap();
    let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();let serving_cancel=CancellationToken::new();let serving={let api=api.clone();let token=serving_cancel.clone();tokio::spawn(async move{api.serve_private(listener,token).await.unwrap();})};
    let path=pure_servers::delay_path("INNER",target);let pending=tokio::spawn(async move{managed_delay(address,&path).await});tokio::time::timeout(Duration::from_secs(5),proxy.started.notified()).await.unwrap();assert!(!pending.is_finished());assert_eq!(serde_json::to_value(inner.health().delay_history()).unwrap(),own);assert_eq!(serde_json::to_value(leaf.health().delay_history()).unwrap(),child);
    let stamp=api.control.plane.stamp();let(committed,_)=api.control.plane.commit_policy(stamp.0,true,||()).unwrap();proxy.release.notify_one();let response=pending.await.unwrap();assert!(response.starts_with("HTTP/1.1 409"),"{response}");assert_eq!(api.control.plane.stamp(),committed);let after=api.control.snapshot().await.unwrap();assert_eq!(after.picks,before.picks);assert_eq!(after.route_version,before.route_version);assert_eq!(serde_json::to_value(inner.health().delay_history()).unwrap(),own);assert_eq!(serde_json::to_value(leaf.health().delay_history()).unwrap(),child);
    serving_cancel.cancel();api.cancel();scope.close().await.unwrap();if let Err(error)=groups.await{assert!(error.is_cancelled());}serving.await.unwrap();api.join().await;servers.close().await;
}

#[cfg(unix)]
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn actual_injected_dialer_same_arc_publication_invalidates_slow_measurement_without_health_commit(){
    let mut servers=pure_servers::Servers::new();let front=servers.proxy().await;let exit=servers.proxy().await;let target=servers.origin().await;exit.hold.store(true,std::sync::atomic::Ordering::Release);
    let yaml=format!("strict: true\nproxies:\n - {{name: FRONT,type: http,server: 127.0.0.1,port: {}}}\n - {{name: NODE,type: http,server: 127.0.0.1,port: {},dialer-proxy: FRONT}}\nrules: ['MATCH,NODE']\n",front.address.port(),exit.address.port());
    let(api,tunnel,scope,_)=fixture(&yaml).await;let groups=api.control.activate_groups(&scope).unwrap();let route=tunnel.route_snapshot();let node=route.proxies["NODE"].clone();let history=serde_json::to_value(node.health().delay_history()).unwrap();let before=api.control.snapshot().await.unwrap();
    let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();let token=CancellationToken::new();let serving={let api=api.clone();let token=token.clone();tokio::spawn(async move{api.serve_private(listener,token).await.unwrap();})};let path=pure_servers::delay_path("NODE",target);let pending=tokio::spawn(async move{managed_delay(address,&path).await});tokio::time::timeout(Duration::from_secs(5),exit.started.notified()).await.unwrap();
    let proof=meow_config::managed_probe_dependencies(&node).unwrap();assert!(proof.is_current());
    // Deliberate publication fault injection: the same actual Arc map and
    // unchanged Native epoch must still not authorize an earlier measurement.
    route.dialer_registry.publish(route.proxies.clone());assert!(!proof.is_current());exit.release.notify_one();let response=tokio::time::timeout(Duration::from_secs(5),pending).await.unwrap().unwrap();assert!(response.starts_with("HTTP/1.1 409"),"{response}");assert_eq!(api.control.snapshot().await.unwrap(),before);assert_eq!(serde_json::to_value(node.health().delay_history()).unwrap(),history);assert!(front.targets.lock().unwrap().iter().all(|value|value==&exit.address.to_string()));
    token.cancel();api.cancel();scope.close().await.unwrap();if let Err(error)=groups.await{assert!(error.is_cancelled());}serving.await.unwrap();api.join().await;servers.close().await;
}

fn startup_request(control: &aerove_meow_core::control::RouteControl, before: &aerove_meow_core::control::RouteSnapshot, entries: &[(&str, &str)])
    -> aerove_meow_core::control::StartupSelectionRequest {
    aerove_meow_core::control::StartupSelectionRequest {
        expected_core_epoch: before.core_epoch.clone(), expected_route_version: before.route_version.clone(),
        expected_selection_intent: control.startup_selection_snapshot().unwrap().selection_intent,
        expected_graph_token: control.startup_selection_snapshot().unwrap().graph_token,
        requested: entries.iter().map(|(group, member)| (group.to_string(), member.to_string())).collect(),
    }
}

#[tokio::test]
async fn startup_history_memory_commit_reads_real_urltest_and_fallback_fixed_without_probing() {
    let yaml = "strict: true\nproxy-groups:\n - {name: UT,type: url-test,proxies: [DIRECT,REJECT],url: 'http://127.0.0.1:9',interval: 0}\n - {name: FB,type: fallback,proxies: [DIRECT,REJECT],url: 'http://127.0.0.1:9',interval: 0}\nrules: ['MATCH,UT']\n";
    let (api, tunnel, scope, _) = fixture(yaml).await;
    let events = api.control.activate_groups(&scope).unwrap();
    let direct = tunnel.route_snapshot().proxies["DIRECT"].clone();
    let history = serde_json::to_value(direct.health().delay_history()).unwrap();
    let before = api.control.snapshot().await.unwrap();
    let request = startup_request(&api.control, &before, &[("UT", "DIRECT"), ("FB", "DIRECT"), ("DELETED", "OLD")]);
    let raw = request.requested.clone();
    let receipt = api.control.restore_startup_selection(request).unwrap();
    assert_eq!(receipt.requested, raw);
    assert_eq!(receipt.groups["UT"].fixed.as_deref(), Some("DIRECT"));
    assert_eq!(receipt.groups["FB"].fixed.as_deref(), Some("DIRECT"));
    assert_eq!(receipt.route.picks["UT"], receipt.groups["UT"].current);
    assert_eq!(serde_json::to_value(direct.health().delay_history()).unwrap(), history, "startup cache must not invoke Fallback Set/probe");
    let next = api.control.restore_startup_selection(startup_request(&api.control, &receipt.route, &[("UT", "REMOVED"), ("FB", "")])).unwrap();
    assert_eq!(next.requested["UT"], "REMOVED");
    assert_eq!(next.groups["UT"].fixed.as_deref(), Some(""));
    assert_eq!(next.groups["FB"].fixed.as_deref(), Some(""));
    api.cancel(); scope.close().await.unwrap(); if let Err(error) = events.await { assert!(error.is_cancelled()); } api.join().await;
}

#[tokio::test]
async fn startup_stale_fallback_and_urltest_restore_together_with_real_raw_consumption() {
    let yaml = "strict: true\nproxy-groups:\n - {name: AUTO,type: url-test,proxies: [DIRECT,REJECT],url: 'http://127.0.0.1:9',interval: 0}\n - {name: FB,type: fallback,proxies: [DIRECT,REJECT],url: 'http://127.0.0.1:9',interval: 0}\nrules: ['MATCH,AUTO']\n";
    let (api, tunnel, scope, _) = fixture(yaml).await;
    let events = api.control.activate_groups(&scope).unwrap();
    tunnel.route_snapshot().proxies["DIRECT"].health().set_alive(false);
    let before = api.control.startup_selection_snapshot().unwrap();
    let applied = api.control.restore_startup_selection(startup_request(&api.control, &before.route, &[("AUTO", "REJECT"), ("FB", "REMOVED")])).unwrap();
    assert_eq!(applied.requested["FB"], "REMOVED");
    assert_eq!(applied.groups["AUTO"].fixed.as_deref(), Some("REJECT"));
    assert_eq!(applied.consumed["FB"].selected, "DIRECT");
    assert_eq!(applied.consumed["FB"].fixed_before, "REMOVED"); assert_eq!(applied.consumed["FB"].fixed_after, "REMOVED");
    let actual = api.control.startup_selection_snapshot().unwrap();
    assert_eq!(actual.groups["FB"].fixed.as_deref(), Some("REMOVED")); assert_eq!(actual.groups["FB"].current, "DIRECT");
    assert_eq!(actual.route, applied.route); assert_eq!(actual.selection_intent, applied.selection_intent);
    api.cancel(); scope.close().await.unwrap(); if let Err(error) = events.await { assert!(error.is_cancelled()); } api.join().await;
}

#[tokio::test]
async fn startup_cache_epoch_route_and_terminal_cancel_are_checked_before_any_mutation() {
    let yaml = "strict: true\nproxy-groups: [{name: AUTO,type: url-test,proxies: [DIRECT,REJECT],url: 'http://127.0.0.1:9',interval: 0}]\nrules: ['MATCH,AUTO']\n";
    let (api, _tunnel, scope, _) = fixture(yaml).await;
    let events = api.control.activate_groups(&scope).unwrap();
    let before = api.control.snapshot().await.unwrap();
    let mut old = startup_request(&api.control, &before, &[("AUTO", "REJECT")]); old.expected_core_epoch = "76".into();
    assert!(matches!(api.control.restore_startup_selection(old), Err(aerove_meow_core::control::StartupSelectionError::Control(ControlError::Superseded))));
    let mut old = startup_request(&api.control, &before, &[("AUTO", "REJECT")]); old.expected_route_version = "999".into();
    assert!(matches!(api.control.restore_startup_selection(old), Err(aerove_meow_core::control::StartupSelectionError::Control(ControlError::Superseded))));
    assert_eq!(api.control.snapshot().await.unwrap(), before);
    let stopped_request = startup_request(&api.control, &before, &[("AUTO", "REJECT")]);
    api.cancel();
    assert!(matches!(api.control.restore_startup_selection(stopped_request), Err(aerove_meow_core::control::StartupSelectionError::Control(ControlError::Cancelled))));
    scope.close().await.unwrap(); if let Err(error) = events.await { assert!(error.is_cancelled()); } api.join().await;
}

#[tokio::test]
async fn startup_urltest_dead_then_revived_pin_keeps_fixed_while_actual_route_recovers() {
    let yaml = "strict: true\nproxy-groups: [{name: AUTO,type: url-test,proxies: [DIRECT,REJECT],url: 'http://127.0.0.1:9',interval: 0}]\nrules: ['MATCH,AUTO']\n";
    let (api, tunnel, scope, _) = fixture(yaml).await;
    let events = api.control.activate_groups(&scope).unwrap();
    tunnel.route_snapshot().proxies["REJECT"].health().record_delay(10);
    let before = api.control.snapshot().await.unwrap();
    api.control.restore_startup_selection(startup_request(&api.control, &before, &[("AUTO", "DIRECT")])).unwrap();
    let direct = tunnel.route_snapshot().proxies["DIRECT"].clone();
    direct.health().set_alive(false);
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let actual = api.control.startup_selection_snapshot().unwrap();
            assert_eq!(actual.groups["AUTO"].fixed.as_deref(), Some("DIRECT"));
            if actual.groups["AUTO"].current == "REJECT" { break; }
            tokio::task::yield_now().await;
        }
    }).await.unwrap();
    direct.health().record_delay(20);
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let actual = api.control.startup_selection_snapshot().unwrap();
            assert_eq!(actual.groups["AUTO"].fixed.as_deref(), Some("DIRECT"));
            if actual.groups["AUTO"].current == "DIRECT" { break; }
            tokio::task::yield_now().await;
        }
    }).await.unwrap();
    api.cancel(); scope.close().await.unwrap(); if let Err(error) = events.await { assert!(error.is_cancelled()); } api.join().await;
}

#[tokio::test]
async fn startup_dead_fallback_consumption_is_captured_then_actual_business_read_is_auto() {
    let yaml = "strict: true\nproxy-groups: [{name: FB,type: fallback,proxies: [DIRECT,REJECT],url: 'http://127.0.0.1:9',interval: 0}]\nrules: ['MATCH,FB']\n";
    let (api, tunnel, scope, _) = fixture(yaml).await;
    let direct = tunnel.route_snapshot().proxies["DIRECT"].clone(); direct.health().set_alive(false);
    let events = api.control.activate_groups(&scope).unwrap();
    let before = api.control.startup_selection_snapshot().unwrap();
    let applied = api.control.restore_startup_selection(startup_request(&api.control, &before.route, &[("FB", "DIRECT")])).unwrap();
    assert_eq!(applied.consumed["FB"].fixed_before, "DIRECT"); assert_eq!(applied.consumed["FB"].fixed_after, "");
    assert_eq!(applied.consumed["FB"].selected, "REJECT");
    let after = api.control.startup_selection_snapshot().unwrap();
    assert_eq!(after.groups["FB"].fixed.as_deref(), Some("")); assert_eq!(after.groups["FB"].current, "REJECT");
    assert_eq!(after.route, applied.route); assert_eq!(after.selection_intent, applied.selection_intent);
    api.cancel(); scope.close().await.unwrap(); if let Err(error) = events.await { assert!(error.is_cancelled()); } api.join().await;
}

async fn startup_http(address: std::net::SocketAddr, secret: &str, method: &str, body: &str) -> String {
    let mut stream = TcpStream::connect(address).await.unwrap();
    stream.write_all(format!("{method} /aerove/selection-cache HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {secret}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).as_bytes()).await.unwrap();
    let mut response = String::new();
    tokio::time::timeout(Duration::from_secs(5), stream.read_to_string(&mut response)).await.unwrap().unwrap(); response
}

#[tokio::test]
async fn startup_cache_real_private_http_is_cas_guarded_and_absent_from_user_controller() {
    let yaml = "strict: true\nproxy-groups: [{name: AUTO,type: url-test,proxies: [DIRECT,REJECT],url: 'http://127.0.0.1:9',interval: 0}]\nrules: ['MATCH,AUTO']\n";
    let (api, _tunnel, scope, _) = fixture(yaml).await;
    let events = api.control.activate_groups(&scope).unwrap();
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
    let user = TcpListener::bind("127.0.0.1:0").await.unwrap(); let user_address = user.local_addr().unwrap();
    let token = CancellationToken::new();
    let private_task = { let api = api.clone(); let token = token.clone(); tokio::spawn(async move { api.serve_private(listener, token).await.unwrap(); }) };
    let user_task = { let api = api.clone(); let token = token.clone(); tokio::spawn(async move { api.serve_user(user, token).await.unwrap(); }) };
    assert!(startup_http(user_address, USER, "GET", "").await.starts_with("HTTP/1.1 404"));
    let before = api.control.snapshot().await.unwrap();
    let body = serde_json::json!({"expectedCoreEpoch":before.core_epoch,"expectedRouteVersion":before.route_version,"expectedGraphToken":api.control.startup_selection_snapshot().unwrap().graph_token,"expectedSelectionIntent":api.control.startup_selection_snapshot().unwrap().selection_intent,"requested":{"AUTO":"DIRECT"}}).to_string();
    // DELETE auto->auto is a newer intent even though it leaves the route version unchanged.
    assert!(startup_http(address, PRIVATE, "DELETE", "").await.starts_with("HTTP/1.1 204"));
    assert_eq!(api.control.snapshot().await.unwrap(), before);
    assert!(startup_http(address, PRIVATE, "POST", &body).await.starts_with("HTTP/1.1 409"));
    let body = serde_json::json!({"expectedCoreEpoch":before.core_epoch,"expectedRouteVersion":before.route_version,
        "expectedGraphToken":api.control.startup_selection_snapshot().unwrap().graph_token,"expectedSelectionIntent":api.control.startup_selection_snapshot().unwrap().selection_intent,"requested":{"AUTO":"DIRECT"}}).to_string();
    let response = startup_http(address, PRIVATE, "POST", &body).await; assert!(response.starts_with("HTTP/1.1 200"), "{response}");
    let payload: serde_json::Value = serde_json::from_str(response.split("\r\n\r\n").nth(1).unwrap()).unwrap();
    assert_eq!(payload["requested"]["AUTO"], "DIRECT"); assert_eq!(payload["groups"]["AUTO"]["fixed"], "DIRECT");
    let actual = startup_http(address, PRIVATE, "GET", "").await; assert!(actual.starts_with("HTTP/1.1 200"));
    assert!(startup_http(address, PRIVATE, "POST", &body).await.starts_with("HTTP/1.1 409"));
    token.cancel(); api.cancel(); scope.close().await.unwrap(); if let Err(error) = events.await { assert!(error.is_cancelled()); }
    private_task.await.unwrap(); user_task.await.unwrap(); api.join().await;
}


#[tokio::test]
async fn startup_same_arc_registry_republication_invalidates_the_original_graph_token() {
    let yaml = "strict: true\nproxies:\n - {name: FRONT,type: direct}\n - {name: NODE,type: http,server: 127.0.0.1,port: 9,dialer-proxy: FRONT}\nproxy-groups: [{name: AUTO,type: url-test,proxies: [NODE,DIRECT],url: 'http://127.0.0.1:9',interval: 0}]\nrules: ['MATCH,AUTO']\n";
    let (api, tunnel, scope, _) = fixture(yaml).await;
    let events = api.control.activate_groups(&scope).unwrap();
    let before = api.control.startup_selection_snapshot().unwrap();
    let request = startup_request(&api.control, &before.route, &[("AUTO", "DIRECT")]);
    let route = tunnel.route_snapshot(); route.dialer_registry.publish(route.proxies.clone());
    assert!(matches!(api.control.restore_startup_selection(request), Err(aerove_meow_core::control::StartupSelectionError::Control(ControlError::Superseded))));
    let after = api.control.startup_selection_snapshot().unwrap();
    assert_ne!(after.graph_token, before.graph_token); assert_eq!(after.groups["AUTO"].fixed, before.groups["AUTO"].fixed);
    api.cancel(); scope.close().await.unwrap(); if let Err(error) = events.await { assert!(error.is_cancelled()); } api.join().await;
}


#[cfg(unix)]
#[tokio::test(flavor = "multi_thread", worker_threads = 3)]
async fn startup_fallback_first_consumption_then_actual_nested_http_uses_the_standard_business_member() {
    for stale in [true, false] {
        let mut servers = pure_servers::Servers::new();
        let a = servers.proxy().await; let b = servers.proxy().await; let c = servers.proxy().await;
        let target = servers.origin().await;
        let yaml = format!("strict: true\nproxies:\n - {{name: A,type: http,server: 127.0.0.1,port: {}}}\n - {{name: B,type: http,server: 127.0.0.1,port: {}}}\n - {{name: C,type: http,server: 127.0.0.1,port: {}}}\nproxy-groups:\n - {{name: FB,type: fallback,proxies: [A,B,C],url: 'http://127.0.0.1:9',interval: 0}}\n - {{name: OUTER,type: select,proxies: [FB,REJECT]}}\nrules: ['MATCH,OUTER']\n", a.address.port(), b.address.port(), c.address.port());
        let (api, tunnel, scope, _) = fixture(&yaml).await;
        let events = api.control.activate_groups(&scope).unwrap();
        let route = tunnel.route_snapshot();
        route.proxies[if stale { "A" } else { "B" }].health().set_alive(false);
        let before = api.control.startup_selection_snapshot().unwrap();
        let raw = if stale { "REMOVED" } else { "B" };
        let applied = api.control.restore_startup_selection(startup_request(&api.control, &before.route, &[("FB", raw)])).unwrap();
        assert_eq!(applied.requested["FB"], raw);
        assert_eq!(applied.consumed["FB"].selected, if stale { "A" } else { "C" });
        assert_eq!(applied.groups["FB"].fixed.as_deref(), Some(if stale { "REMOVED" } else { "" }));
        assert_eq!(applied.groups["FB"].current, "A");
        let after = api.control.startup_selection_snapshot().unwrap(); assert_eq!(after.route, applied.route);
        assert!(a.targets.lock().unwrap().is_empty()); assert!(b.targets.lock().unwrap().is_empty()); assert!(c.targets.lock().unwrap().is_empty());
        let meta = meow_common::Metadata { dst_ip: Some(target.ip()), dst_port: target.port(), ..Default::default() };
        let selected = route.proxies["OUTER"].clone();
        let work = Box::pin(async move { selected.dial_tcp(&meta).await });
        let mut stream = tokio::time::timeout(Duration::from_secs(5), scope.run_captured(work)).await.unwrap().unwrap().unwrap();
        stream.write_all(b"GET /business HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").await.unwrap();
        let mut body = Vec::new(); tokio::time::timeout(Duration::from_secs(5), stream.read_to_end(&mut body)).await.unwrap().unwrap();
        assert!(String::from_utf8_lossy(&body).starts_with("HTTP/1.1 204")); drop(stream);
        assert_eq!(a.targets.lock().unwrap().as_slice(), [target.to_string()]);
        assert!(b.targets.lock().unwrap().is_empty()); assert!(c.targets.lock().unwrap().is_empty(), "setup's C is a consumed Now result, not a cached override of the later business dial");
        api.cancel(); scope.close().await.unwrap(); if let Err(error) = events.await { assert!(error.is_cancelled()); } api.join().await; servers.close().await;
    }
}
