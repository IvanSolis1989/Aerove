use aerove_meow_core::{api::CoreApi, providers::{self, ControlPlane, LoadContext, OwnedLoadScope, OwnedWork, ProviderRoots}};
use axum::Router;
use meow_config::provider_events::ProviderObserver;
use meow_tunnel::Tunnel;
use std::{path::PathBuf, sync::{Arc, atomic::{AtomicBool, AtomicU64, Ordering}}, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}, sync::Notify};
use tokio_util::sync::CancellationToken;
const SECRET: &str = "provider-file-owner-secret-0000000001";
static NEXT_DIR: AtomicU64 = AtomicU64::new(0);
struct Files(PathBuf);
impl Files {
    fn new() -> Self {
        let serial = NEXT_DIR.fetch_add(1, Ordering::Relaxed);
        let path = std::env::temp_dir().join(format!("aerove-provider-file-{}-{}-{serial}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir(&path).unwrap(); Self(path)
    }
    fn write(&self, payload: &str) { std::fs::write(self.0.join("feed.yaml"), payload).unwrap(); }
}
impl Drop for Files { fn drop(&mut self) { let _ = std::fs::remove_dir_all(&self.0); } }
struct Blocker { armed: AtomicBool, started: Notify, release: std::sync::Mutex<bool>, condition: std::sync::Condvar, completed: AtomicBool }
impl Blocker {
    fn new() -> Arc<Self> { Arc::new(Self { armed: AtomicBool::new(false), started: Notify::new(), release: std::sync::Mutex::new(false), condition: std::sync::Condvar::new(), completed: AtomicBool::new(false) }) }
    fn release(&self) { *self.release.lock().unwrap() = true; self.condition.notify_one(); }
}
struct ReleaseOnDrop(Arc<Blocker>);
impl Drop for ReleaseOnDrop { fn drop(&mut self) { self.0.release(); } }
struct Context { plane: Arc<ControlPlane>, blocker: Option<Arc<Blocker>>, roots: Option<ProviderRoots> }
impl LoadContext for Context {
    fn requires_provider_roots(&self) -> bool { self.roots.is_some() }
    fn provider_roots(&self) -> Option<ProviderRoots> { self.roots.clone() }
    fn provider_observer(&self) -> Option<Arc<dyn ProviderObserver>> { Some(self.plane.clone()) }
    fn wrap(&self, work: OwnedWork) -> OwnedWork { work }
    fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) {
        let held = self.blocker.as_ref().filter(|blocker| blocker.armed.swap(false, Ordering::SeqCst));
        if let Some(blocker) = held {
            blocker.started.notify_one();
            let mut released = blocker.release.lock().unwrap();
            while !*released { released = blocker.condition.wait(released).unwrap(); }
        }
        work();
        if let Some(blocker) = held { blocker.completed.store(true, Ordering::SeqCst); }
    }
}
fn yaml(interval: u64, path: &str) -> String { format!("strict: true\nproxy-providers:\n  feed:\n    type: file\n    path: {path}\n    interval: {interval}\nproxy-groups:\n - name: PICK\n   type: select\n   use: [feed]\nrules: ['MATCH,PICK']\n") }
async fn request(address: std::net::SocketAddr, method: &str, path: &str) -> String {
    let mut stream = TcpStream::connect(address).await.unwrap();
    stream.write_all(format!("{method} {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {SECRET}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut text = String::new();
    tokio::time::timeout(Duration::from_secs(5), stream.read_to_string(&mut text)).await.unwrap().unwrap(); text
}
fn body(text: &str) -> serde_json::Value { serde_json::from_str(text.split_once("\r\n\r\n").unwrap().1).unwrap() }

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn real_file_initial_manual_unchanged_receipts_and_containment() {
    let files = Files::new(); files.write("proxies:\n - {name: d1, type: direct}\n");
    let cancel = CancellationToken::new();
    let plane = ControlPlane::new(51, cancel.clone()).unwrap();
    let scope = OwnedLoadScope::with_context(Arc::new(Context { plane: plane.clone(), blocker: None, roots: None }));
    let mut events = plane.subscribe();
    let config = providers::load_with_cache(&yaml(0, "feed.yaml"), &scope, &cancel, Some(&files.0)).await.unwrap();
    assert!(events.try_recv().is_err());
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
    let api = Arc::new(CoreApi::assemble_with_plane(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(), SECRET, None, Router::new(), plane.clone()).unwrap());
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry); api.activate_providers().unwrap();
    let serving = api.clone(); let token = cancel.clone(); let server = tokio::spawn(async move { serving.serve_private(listener, token).await });
    let initial = tokio::time::timeout(Duration::from_secs(5), events.recv()).await.unwrap().unwrap();
    assert_eq!(initial.revision, "1"); assert_eq!(initial.phase, "initial"); assert!(initial.changed);
    let get = request(address, "GET", "/providers/proxies/feed").await;
    assert_eq!(body(&get)["vehicleType"], "File"); assert_eq!(body(&get)["revision"], "1");
    files.write("proxies:\n - {name: d2, type: direct}\n");
    assert!(request(address, "PUT", "/providers/proxies/feed").await.starts_with("HTTP/1.1 204"));
    let changed = tokio::time::timeout(Duration::from_secs(5), events.recv()).await.unwrap().unwrap();
    assert_eq!(changed.revision, "2"); assert!(changed.changed);
    let get = request(address, "GET", "/providers/proxies/feed").await;
    assert_eq!(body(&get)["proxies"][0]["name"], "d2"); assert_eq!(body(&get)["revision"], "2");
    // A byte-identical manual file read is still a successful update receipt.
    assert!(request(address, "PUT", "/providers/proxies/feed").await.starts_with("HTTP/1.1 204"));
    let timer = tokio::time::timeout(Duration::from_secs(5), events.recv()).await.unwrap().unwrap();
    assert_eq!(timer.revision, "3"); assert_eq!(timer.phase, "update"); assert!(!timer.changed);
    api.cancel(); scope.cancel(); server.await.unwrap().unwrap(); api.join().await; scope.close().await.unwrap();
    assert_eq!(api.active_tasks(), 0);
    let standalone = OwnedLoadScope::new();
    assert!(meow_config::load_config_from_str_owned(&yaml(0, "feed.yaml"), &standalone).await.is_err(), "file without a trusted root was admitted");
    let escape = OwnedLoadScope::new();
    assert!(meow_config::load_config_from_str_owned_with_cache(&yaml(0, "../outside.yaml"), &escape, Some(&files.0)).await.is_err(), "provider escaped containment");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn cancelled_file_operation_joins_real_blocking_read_before_successor() {
    let files = Files::new(); files.write("proxies:\n - {name: d1, type: direct}\n");
    let blocker = Blocker::new(); let _release_on_drop = ReleaseOnDrop(blocker.clone()); let cancel = CancellationToken::new();
    let plane = ControlPlane::new(52, cancel.clone()).unwrap();
    let scope = OwnedLoadScope::with_context(Arc::new(Context { plane: plane.clone(), blocker: Some(blocker.clone()), roots: None }));
    let config = providers::load_with_cache(&yaml(0, "feed.yaml"), &scope, &cancel, Some(&files.0)).await.unwrap();
    let provider = config.proxy_providers.get("feed").unwrap().clone();
    plane.activate(&[provider.provider_identity().clone()]).unwrap();
    files.write("proxies:\n - {name: d2, type: direct}\n"); blocker.armed.store(true, Ordering::SeqCst);
    let loading = tokio::spawn(async move { provider.refresh_owned().await });
    tokio::time::timeout(Duration::from_secs(5), blocker.started.notified()).await.unwrap();
    plane.cancel(); scope.cancel(); loading.abort(); assert!(loading.await.unwrap_err().is_cancelled());
    let closing_scope = scope.clone(); let close = tokio::spawn(async move { closing_scope.close().await });
    tokio::task::yield_now().await;
    assert!(!close.is_finished(), "scope forgot an executing filesystem job");
    assert!(!blocker.completed.load(Ordering::SeqCst)); blocker.release();
    tokio::time::timeout(Duration::from_secs(5), close).await.unwrap().unwrap().unwrap();
    assert!(blocker.completed.load(Ordering::SeqCst));
    assert_eq!(plane.provider_baseline()[0].revision, "1", "cancelled file read emitted a success receipt");
    let next_cancel = CancellationToken::new(); let next_plane = ControlPlane::new(53, next_cancel.clone()).unwrap();
    let next_scope = OwnedLoadScope::with_context(Arc::new(Context { plane: next_plane.clone(), blocker: None, roots: None }));
    let next = providers::load_with_cache(&yaml(0, "feed.yaml"), &next_scope, &next_cancel, Some(&files.0)).await.unwrap();
    let next_provider = next.proxy_providers.get("feed").unwrap();
    next_plane.activate(&[next_provider.provider_identity().clone()]).unwrap();
    assert_eq!(next_provider.proxies()[0].name(), "d2"); assert_eq!(next_plane.provider_baseline()[0].revision, "1");
    next_scope.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn real_file_timer_reads_current_payload_through_captured_scope() {
    let files = Files::new(); files.write("proxies:\n - {name: d1, type: direct}\n");
    let blocker = Blocker::new(); let _release_on_drop = ReleaseOnDrop(blocker.clone()); let cancel = CancellationToken::new();
    let plane = ControlPlane::new(54, cancel.clone()).unwrap();
    let scope = OwnedLoadScope::with_context(Arc::new(Context { plane: plane.clone(), blocker: Some(blocker.clone()), roots: None }));
    let mut events = plane.subscribe();
    let config = providers::load_with_cache(&yaml(1, "feed.yaml"), &scope, &cancel, Some(&files.0)).await.unwrap();
    let provider = config.proxy_providers.get("feed").unwrap().clone();
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    let api = CoreApi::assemble_with_plane(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(), SECRET, None, Router::new(), plane.clone()).unwrap();
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
    blocker.armed.store(true, Ordering::SeqCst); api.activate_providers().unwrap();
    let initial = tokio::time::timeout(Duration::from_secs(5), events.recv()).await.unwrap().unwrap();
    assert_eq!(initial.revision, "1");
    tokio::time::timeout(Duration::from_secs(5), blocker.started.notified()).await.unwrap();
    files.write("proxies:\n - {name: d2, type: direct}\n"); blocker.release();
    let update = tokio::time::timeout(Duration::from_secs(5), events.recv()).await.unwrap().unwrap();
    assert_eq!(update.revision, "2"); assert!(update.changed); assert_eq!(update.phase, "update");
    assert_eq!(provider.proxies()[0].name(), "d2");
    api.cancel(); scope.cancel(); api.join().await; scope.close().await.unwrap();
    assert_eq!(api.active_tasks(), 0);
}


#[tokio::test]
async fn captured_roots_keep_http_seed_immutable_and_cache_separate() {
    let read = Files::new(); let cache = Files::new();
    let seed = "proxies:\n - {name: d1, type: direct}\n"; read.write(seed);
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = origin.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut stream, _) = origin.accept().await.unwrap();
        let mut headers = Vec::new();
        while !headers.ends_with(b"\r\n\r\n") { headers.push(stream.read_u8().await.unwrap()); assert!(headers.len() < 8192); }
        let payload = "proxies:\n - {name: d2, type: direct}\n";
        stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{payload}", payload.len()).as_bytes()).await.unwrap();
    });
    let cancel = CancellationToken::new(); let plane = ControlPlane::new(55, cancel.clone()).unwrap();
    let scope = OwnedLoadScope::with_context(Arc::new(Context { plane: plane.clone(), blocker: None,
        roots: Some(ProviderRoots { resource_read_root: Some(read.0.clone()), cache_root: cache.0.clone() }) }));
    let document = format!("strict: true\nproxy-providers:\n  feed:\n    type: http\n    url: http://{address}/feed\n    path: {}\n    interval: 0\nrules: ['MATCH,DIRECT']\n", read.0.join("feed.yaml").display());
    let config = providers::load(&document, &scope, &cancel).await.unwrap();
    let provider = config.proxy_providers.get("feed").unwrap();
    plane.activate(&[provider.provider_identity().clone()]).unwrap();
    assert_eq!(plane.provider_baseline()[0].revision, "1"); assert_eq!(provider.proxies()[0].name(), "d1");
    assert!(!cache.0.join("feed.yaml").exists());
    provider.refresh_owned().await.unwrap(); server.await.unwrap();
    assert_eq!(plane.provider_baseline()[0].revision, "2"); assert_eq!(provider.proxies()[0].name(), "d2");
    assert_eq!(std::fs::read_to_string(read.0.join("feed.yaml")).unwrap(), seed, "HTTP refresh changed sealed generation bytes");
    assert!(std::fs::read_to_string(cache.0.join("feed.yaml")).unwrap().contains("d2"));
    scope.close().await.unwrap();
}

#[tokio::test]
async fn captured_file_root_is_readonly_and_cannot_alias_mutable_cache() {
    let read = Files::new(); let cache = Files::new();
    read.write("proxies:\n - {name: d1, type: direct}\n"); cache.write("proxies:\n - {name: d2, type: direct}\n");
    for (index, roots, document, succeeds) in [
        (0, ProviderRoots { resource_read_root: Some(read.0.clone()), cache_root: cache.0.clone() }, yaml(0, "feed.yaml"), true),
        (1, ProviderRoots { resource_read_root: None, cache_root: cache.0.clone() }, yaml(0, "feed.yaml"), false),
        (2, ProviderRoots { resource_read_root: Some(read.0.clone()), cache_root: read.0.clone() }, yaml(0, "feed.yaml"), false),
        (3, ProviderRoots { resource_read_root: Some(read.0.clone()), cache_root: cache.0.clone() }, yaml(0, &cache.0.join("feed.yaml").display().to_string()), false),
    ] {
        let cancel = CancellationToken::new(); let plane = ControlPlane::new(56 + index, cancel.clone()).unwrap();
        let scope = OwnedLoadScope::with_context(Arc::new(Context { plane, blocker: None, roots: Some(roots) }));
        let result = providers::load(&document, &scope, &cancel).await;
        assert_eq!(result.is_ok(), succeeds, "captured roots case {index}");
        if let Ok(config) = result { assert_eq!(config.proxy_providers.get("feed").unwrap().proxies()[0].name(), "d1"); }
        scope.close().await.unwrap();
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn cancelled_http_cache_write_keeps_busy_until_scope_joins_actual_job() {
    let read = Files::new(); let cache = Files::new();
    let seed = "proxies:\n - {name: d1, type: direct}\n"; read.write(seed);
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = origin.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut stream, _) = origin.accept().await.unwrap();
        let mut headers = Vec::new();
        while !headers.ends_with(b"\r\n\r\n") { headers.push(stream.read_u8().await.unwrap()); assert!(headers.len() < 8192); }
        let payload = "proxies:\n - {name: d2, type: direct}\n";
        stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{payload}", payload.len()).as_bytes()).await.unwrap();
    });
    let blocker = Blocker::new(); let _release_on_drop = ReleaseOnDrop(blocker.clone()); let cancel = CancellationToken::new(); let plane = ControlPlane::new(61, cancel.clone()).unwrap();
    let roots = ProviderRoots { resource_read_root: Some(read.0.clone()), cache_root: cache.0.clone() };
    let scope = OwnedLoadScope::with_context(Arc::new(Context { plane: plane.clone(), blocker: Some(blocker.clone()), roots: Some(roots.clone()) }));
    let document = format!("strict: true\nproxy-providers:\n  feed:\n    type: http\n    url: http://{address}/feed\n    path: {}\n    interval: 0\nrules: ['MATCH,DIRECT']\n", read.0.join("feed.yaml").display());
    let config = providers::load(&document, &scope, &cancel).await.unwrap();
    let provider = config.proxy_providers.get("feed").unwrap().clone();
    plane.activate(&[provider.provider_identity().clone()]).unwrap();
    blocker.armed.store(true, Ordering::SeqCst);
    let fetching = provider.clone(); let refresh = tokio::spawn(async move { fetching.refresh_owned().await });
    tokio::time::timeout(Duration::from_secs(5), blocker.started.notified()).await.unwrap();
    refresh.abort(); assert!(refresh.await.unwrap_err().is_cancelled());
    assert_eq!(tokio::time::timeout(Duration::from_secs(5), provider.refresh_owned()).await.unwrap().unwrap_err(), "providerBusy", "caller cancellation dropped admission before cache job finished");
    assert_eq!(provider.proxies()[0].name(), "d1");
    plane.cancel(); scope.cancel();
    let closing_scope = scope.clone(); let close = tokio::spawn(async move { closing_scope.close().await });
    tokio::task::yield_now().await; assert!(!close.is_finished()); blocker.release();
    tokio::time::timeout(Duration::from_secs(5), close).await.unwrap().unwrap().unwrap(); server.await.unwrap();
    assert_eq!(plane.provider_baseline()[0].revision, "1");
    assert_eq!(std::fs::read_to_string(read.0.join("feed.yaml")).unwrap(), seed);
    let next_cancel = CancellationToken::new(); let next_plane = ControlPlane::new(62, next_cancel.clone()).unwrap();
    let next_scope = OwnedLoadScope::with_context(Arc::new(Context { plane: next_plane.clone(), blocker: None, roots: Some(roots) }));
    let next = providers::load(&document, &next_scope, &next_cancel).await.unwrap();
    let next_provider = next.proxy_providers.get("feed").unwrap(); next_plane.activate(&[next_provider.provider_identity().clone()]).unwrap();
    assert_eq!(next_provider.proxies()[0].name(), "d2"); assert_eq!(next_plane.provider_baseline()[0].revision, "1");
    next_scope.close().await.unwrap();
}