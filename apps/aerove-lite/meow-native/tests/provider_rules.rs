use aerove_meow_core::{api::CoreApi, providers::{ControlPlane, LoadContext, OwnedLoadScope, OwnedWork, ProviderRoots}};
use axum::Router;
use futures::StreamExt;
use meow_config::provider_events::ProviderObserver;
use meow_tunnel::Tunnel;
use std::{path::PathBuf, sync::Arc, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};
use tokio_util::sync::CancellationToken;
const SECRET: &str = "rule-provider-owner-secret-0000000001";
struct Directory(PathBuf);
impl Directory {
    fn new(label: &str) -> Self {
        let path = std::env::temp_dir().join(format!("aerove-rule-{label}-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir(&path).unwrap(); Self(path)
    }
    fn write(&self, bytes: &[u8]) { std::fs::write(self.0.join("feed.yaml"), bytes).unwrap(); }
}
impl Drop for Directory { fn drop(&mut self) { let _ = std::fs::remove_dir_all(&self.0); } }
struct Blocker { armed: std::sync::atomic::AtomicBool, started: tokio::sync::Notify, release: std::sync::Mutex<bool>, condition: std::sync::Condvar }
impl Blocker {
    fn new() -> Arc<Self> { Arc::new(Self { armed: std::sync::atomic::AtomicBool::new(false), started: tokio::sync::Notify::new(), release: std::sync::Mutex::new(false), condition: std::sync::Condvar::new() }) }
    fn arm(&self) { *self.release.lock().unwrap() = false; self.armed.store(true, std::sync::atomic::Ordering::SeqCst); }
    fn release(&self) { *self.release.lock().unwrap() = true; self.condition.notify_all(); }
}
struct ReleaseOnDrop(Arc<Blocker>);
impl Drop for ReleaseOnDrop { fn drop(&mut self) { self.0.release(); } }
struct Context { plane: Arc<ControlPlane>, roots: ProviderRoots, blocker: Option<Arc<Blocker>> }
impl LoadContext for Context {
    fn requires_provider_roots(&self) -> bool { true }
    fn provider_roots(&self) -> Option<ProviderRoots> { Some(self.roots.clone()) }
    fn provider_observer(&self) -> Option<Arc<dyn ProviderObserver>> { Some(self.plane.clone()) }
    fn wrap(&self, work: OwnedWork) -> OwnedWork { work }
    fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) {
        if let Some(blocker) = self.blocker.as_ref().filter(|blocker| blocker.armed.swap(false, std::sync::atomic::Ordering::SeqCst)) {
            blocker.started.notify_one(); let mut released = blocker.release.lock().unwrap();
            while !*released { released = blocker.condition.wait(released).unwrap(); }
        }
        work();
    }
}
async fn request(address: std::net::SocketAddr, method: &str, path: &str) -> String {
    let mut stream = TcpStream::connect(address).await.unwrap();
    stream.write_all(format!("{method} {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {SECRET}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut text = String::new(); tokio::time::timeout(Duration::from_secs(5), stream.read_to_string(&mut text)).await.unwrap().unwrap(); text
}
fn body(text: &str) -> serde_json::Value { serde_json::from_str(text.split_once("\r\n\r\n").unwrap().1).unwrap() }

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn actual_accepted_rules_receipt_pages_and_same_count_change_are_coherent() {
    let read = Directory::new("read"); let cache = Directory::new("cache");
    read.write(b"payload:\n - a.example\n - b.example\n - ''\n - 123\n");
    let cancel = CancellationToken::new(); let plane = ControlPlane::new(71, cancel.clone()).unwrap();
    let scope = OwnedLoadScope::with_context(Arc::new(Context { plane: plane.clone(), roots: ProviderRoots { resource_read_root: Some(read.0.clone()), cache_root: cache.0.clone() }, blocker: None }));
    let mut events = plane.subscribe();
    let yaml = "strict: false\nrule-providers:\n feed:\n  type: file\n  behavior: domain\n  path: feed.yaml\n  interval: 0\nrules: ['RULE-SET,feed,REJECT', 'MATCH,DIRECT']\n";
    let config = meow_config::load_config_from_str_owned(yaml, &scope).await.unwrap();
    let provider = config.rule_providers.get("feed").unwrap().clone();
    assert!(provider.snapshot().matches_domain("a.example")); assert_eq!(provider.rule_count(), 2);
    assert!(events.try_recv().is_err(), "initial notification escaped before live routing publication");
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
    let api = Arc::new(CoreApi::assemble_with_plane(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(), SECRET, None, Router::new(), plane.clone()).unwrap());
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry); api.activate_providers().unwrap();
    let serving = api.clone(); let token = cancel.clone(); let server = tokio::spawn(async move { serving.serve_private(listener, token).await });
    let initial = tokio::time::timeout(Duration::from_secs(5), events.recv()).await.unwrap().unwrap();
    assert_eq!(serde_json::to_value(&initial).unwrap()["kind"], "rule"); assert_eq!(initial.revision, "1"); assert_eq!(initial.phase, "initial");
    let (mut telemetry, _) = tokio_tungstenite::connect_async(format!("ws://{address}/aerove/telemetry?token={SECRET}")).await.unwrap();
    let first = tokio::time::timeout(Duration::from_secs(5), telemetry.next()).await.unwrap().unwrap().unwrap();
    let frame: serde_json::Value = serde_json::from_str(first.to_text().unwrap()).unwrap();
    assert_eq!(frame["providers"][0]["kind"], "rule"); assert_eq!(frame["providers"][0]["revision"], "1");
    let single = body(&request(address, "GET", "/providers/rules/feed").await);
    let list = body(&request(address, "GET", "/providers/rules").await);
    assert_eq!(single["revision"], "1"); assert_eq!(single["ruleCount"], 2); assert_eq!(list["providers"]["feed"], single);
    let first = request(address, "GET", "/providers/rules/feed/rules?offset=0&limit=1").await;
    assert!(first.starts_with("HTTP/1.1 200"), "{first}"); let first = body(&first);
    assert_eq!(first["rules"], serde_json::json!(["a.example"])); assert_eq!(first["ruleCount"], 2);
    assert_eq!(first["offset"], 0); assert_eq!(first["nextOffset"], 1); assert_eq!(first["complete"], false);
    let epoch = first["coreEpoch"].as_str().unwrap().to_owned(); assert_eq!(epoch.parse::<u64>().unwrap().to_string(), epoch);
    let second = body(&request(address, "GET", &format!("/providers/rules/feed/rules?offset=1&limit=1&coreEpoch={epoch}")).await);
    assert_eq!(second["rules"], serde_json::json!(["b.example"])); assert_eq!(second["nextOffset"], serde_json::Value::Null); assert_eq!(second["complete"], true);
    for path in ["/providers/rules/feed/rules?limit=0", "/providers/rules/feed/rules?coreEpoch=01", "/providers/rules/feed/rules?unexpected=1"] {
        assert!(request(address, "GET", path).await.starts_with("HTTP/1.1 400"));
    }
    let past = body(&request(address, "GET", &format!("/providers/rules/feed/rules?offset=20&limit=1&coreEpoch={epoch}")).await);
    assert_eq!(past["rules"], serde_json::json!([])); assert_eq!(past["offset"], 20); assert_eq!(past["complete"], true); assert_eq!(past["nextOffset"], serde_json::Value::Null);
    let before = plane.stamp(); read.write(b"payload:\n - b.example\n - c.example\n");
    assert!(request(address, "PUT", "/providers/rules/feed").await.starts_with("HTTP/1.1 204"));
    let changed = tokio::time::timeout(Duration::from_secs(5), events.recv()).await.unwrap().unwrap();
    assert_eq!(changed.revision, "2"); assert!(changed.changed); assert!(plane.stamp().0 > before.0); assert!(plane.stamp().1 > before.1);
    assert!(!provider.snapshot().matches_domain("a.example")); assert!(provider.snapshot().matches_domain("c.example"));
    assert!(request(address, "GET", &format!("/providers/rules/feed/rules?offset=1&limit=1&coreEpoch={epoch}")).await.starts_with("HTTP/1.1 409"), "same-size mutation allowed pages from different live snapshots");
    let current = body(&request(address, "GET", "/providers/rules/feed/rules?limit=10").await);
    assert_eq!(current["rules"], serde_json::json!(["b.example", "c.example"])); assert_eq!(current["ruleCount"], 2);
    let stamp = plane.stamp();
    assert!(request(address, "PUT", "/providers/rules/feed").await.starts_with("HTTP/1.1 204"));
    let unchanged = tokio::time::timeout(Duration::from_secs(5), events.recv()).await.unwrap().unwrap();
    assert_eq!(unchanged.revision, "3"); assert!(!unchanged.changed); assert_eq!(plane.stamp(), stamp);
    read.write(b"# same accepted live entries, different payload bytes\npayload: [b.example, c.example]\n");
    assert!(request(address, "PUT", "/providers/rules/feed").await.starts_with("HTTP/1.1 204"));
    let reformatted = tokio::time::timeout(Duration::from_secs(5), events.recv()).await.unwrap().unwrap();
    assert_eq!(reformatted.revision, "4"); assert!(!reformatted.changed); assert_eq!(plane.stamp(), stamp);
    read.write(b"not-a-provider: true\n");
    assert!(request(address, "PUT", "/providers/rules/feed").await.starts_with("HTTP/1.1 503"));
    assert!(events.try_recv().is_err()); assert_eq!(plane.stamp(), stamp);
    let last = body(&request(address, "GET", "/providers/rules/feed").await); assert_eq!(last["revision"], "4");
    assert_eq!(body(&request(address, "GET", "/providers/rules/feed/rules?limit=10").await)["rules"], current["rules"]);
    plane.activate(&[]).unwrap();
    assert!(request(address, "GET", "/providers/rules/feed/rules?limit=1").await.starts_with("HTTP/1.1 409"));
    api.cancel(); scope.cancel(); server.await.unwrap().unwrap(); api.join().await; scope.close().await.unwrap(); assert_eq!(api.active_tasks(), 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 3)]
async fn page_io_change_fences_old_result_and_cancel_joins_before_successor() {
    let read = Directory::new("race-read"); let cache = Directory::new("race-cache");
    read.write(b"payload:\n - a.example\n");
    let blocker = Blocker::new(); let _release = ReleaseOnDrop(blocker.clone());
    let cancel = CancellationToken::new(); let plane = ControlPlane::new(72, cancel.clone()).unwrap();
    let scope = OwnedLoadScope::with_context(Arc::new(Context { plane: plane.clone(), roots: ProviderRoots { resource_read_root: Some(read.0.clone()), cache_root: cache.0.clone() }, blocker: Some(blocker.clone()) }));
    let yaml = "strict: true\nrule-providers:\n feed:\n  type: file\n  behavior: domain\n  path: feed.yaml\n  interval: 0\nrules: ['RULE-SET,feed,REJECT', 'MATCH,DIRECT']\n";
    let config = meow_config::load_config_from_str_owned(yaml, &scope).await.unwrap();
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone()); let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
    let api = Arc::new(CoreApi::assemble_with_plane(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(), SECRET, None, Router::new(), plane.clone()).unwrap());
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry); api.activate_providers().unwrap();
    let serving = api.clone(); let token = cancel.clone(); let server = tokio::spawn(async move { serving.serve_private(listener, token).await });
    let first = body(&request(address, "GET", "/providers/rules/feed/rules?limit=1").await); let epoch = first["coreEpoch"].as_str().unwrap().to_owned();
    blocker.arm();
    let pending = tokio::spawn(async move { request(address, "GET", "/providers/rules/feed/rules?limit=1").await });
    tokio::time::timeout(Duration::from_secs(5), blocker.started.notified()).await.unwrap();
    read.write(b"payload:\n - b.example\n");
    assert!(request(address, "PUT", "/providers/rules/feed").await.starts_with("HTTP/1.1 204"));
    blocker.release(); let stale = pending.await.unwrap();
    assert!(stale.starts_with("HTTP/1.1 409"), "old page was published after live commit: {stale}");
    assert!(request(address, "GET", &format!("/providers/rules/feed/rules?coreEpoch={epoch}")).await.starts_with("HTTP/1.1 409"));
    let current = body(&request(address, "GET", "/providers/rules/feed/rules?limit=1").await); assert_eq!(current["rules"], serde_json::json!(["b.example"]));
    read.write(b"payload: [c.example]\n"); blocker.arm();
    let refreshing = tokio::spawn(async move { request(address, "PUT", "/providers/rules/feed").await });
    tokio::time::timeout(Duration::from_secs(5), blocker.started.notified()).await.unwrap();
    for path in ["/providers/rules", "/providers/rules/feed", "/providers/rules/feed/rules?limit=1"] {
        assert!(request(address, "GET", path).await.starts_with("HTTP/1.1 409"));
    }
    assert!(request(address, "PUT", "/providers/rules/feed").await.starts_with("HTTP/1.1 409"));
    blocker.release(); assert!(refreshing.await.unwrap().starts_with("HTTP/1.1 204"));
    blocker.arm();
    let pending = tokio::spawn(async move { request(address, "GET", "/providers/rules/feed/rules?limit=1").await });
    tokio::time::timeout(Duration::from_secs(5), blocker.started.notified()).await.unwrap();
    api.cancel(); scope.cancel(); server.await.unwrap().unwrap(); api.join().await;
    let closing = scope.clone(); let close = tokio::spawn(async move { closing.close().await });
    tokio::task::yield_now().await; assert!(!close.is_finished(), "page filesystem job escaped Session close");
    blocker.release(); tokio::time::timeout(Duration::from_secs(5), close).await.unwrap().unwrap().unwrap();
    let cancelled = pending.await.unwrap(); assert!(!cancelled.starts_with("HTTP/1.1 200")); assert_eq!(api.active_tasks(), 0);
    let next_cache = Directory::new("next-cache"); let next_plane = ControlPlane::new(73, CancellationToken::new()).unwrap();
    let next = OwnedLoadScope::with_context(Arc::new(Context { plane: next_plane, roots: ProviderRoots { resource_read_root: Some(read.0.clone()), cache_root: next_cache.0.clone() }, blocker: None }));
    let config = meow_config::load_config_from_str_owned(yaml, &next).await.unwrap();
    assert!(config.rule_providers.get("feed").unwrap().snapshot().matches_domain("c.example")); next.close().await.unwrap();
}
