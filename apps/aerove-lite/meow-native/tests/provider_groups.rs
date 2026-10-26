use aerove_meow_core::{api::CoreApi, control::ControlError, providers::{self, ControlPlane, LoadContext, OwnedLoadScope, OwnedWork}};
use axum::Router;
use meow_config::provider_events::ProviderObserver;
use meow_tunnel::Tunnel;
use std::{path::PathBuf, sync::{Arc, atomic::{AtomicU64, Ordering}}, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}, time::timeout};
use tokio_util::sync::CancellationToken;

const SECRET: &str = "provider-managed-group-secret-00000001";
static NEXT: AtomicU64 = AtomicU64::new(0);
struct Files(PathBuf);
impl Files {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("aerove-provider-groups-{}-{}-{}", std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos(), NEXT.fetch_add(1,Ordering::Relaxed)));
        std::fs::create_dir(&path).unwrap(); Self(path)
    }
    fn write(&self, name: &str, payload: &str) { std::fs::write(self.0.join(name),payload).unwrap(); }
}
impl Drop for Files { fn drop(&mut self) { let _ = std::fs::remove_dir_all(&self.0); } }
struct Context(Arc<ControlPlane>);
impl LoadContext for Context {
    fn provider_observer(&self) -> Option<Arc<dyn ProviderObserver>> { Some(self.0.clone()) }
    fn wrap(&self, work: OwnedWork) -> OwnedWork { work }
    fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) { work(); }
}
async fn fixture(files: &Files, yaml: &str) -> (Arc<CoreApi>, Tunnel, Arc<OwnedLoadScope>) {
    let cancel = CancellationToken::new(); let plane = ControlPlane::new(700, cancel.clone()).unwrap();
    let scope = OwnedLoadScope::with_context(Arc::new(Context(plane.clone())));
    let config = timeout(Duration::from_secs(5),providers::load_with_cache(yaml,&scope,&cancel,Some(&files.0))).await.unwrap().unwrap();
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    tunnel.set_mode(config.general.mode); tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
    let api = Arc::new(CoreApi::assemble_with_plane(&config,tunnel.clone(),"127.0.0.1:12345".parse().unwrap(),
        SECRET,None,Router::new(),plane).unwrap());
    tunnel.update_routing(config.proxies,config.rules,config.dialer_registry);
    (api,tunnel,scope)
}
fn file_yaml() -> &'static str {
    "strict: true\nproxy-providers:\n  feed: {type: file, path: feed.yaml, interval: 0}\n  spare: {type: file, path: spare.yaml, interval: 0}\nproxy-groups:\n - {name: PICK, type: select, use: [feed]}\n - {name: FILTER, type: select, use: [feed], filter: '^(a|c)$'}\n - {name: OUTER, type: select, proxies: [PICK, REJECT]}\nrules: ['MATCH,OUTER']\n"
}
async fn stop(api: &Arc<CoreApi>, scope: &Arc<OwnedLoadScope>, groups: tokio::task::JoinHandle<()>) {
    api.cancel(); api.control.cancel(); scope.cancel();
    timeout(Duration::from_secs(5),scope.close()).await.unwrap().unwrap();
    if let Err(error) = timeout(Duration::from_secs(5),groups).await.unwrap() { assert!(error.is_cancelled()); }
    timeout(Duration::from_secs(5),api.join()).await.unwrap(); assert_eq!(api.active_tasks(),0);
}
async fn headers(stream: &mut TcpStream) {
    timeout(Duration::from_secs(5),async {
        let mut bytes = Vec::new();
        while !bytes.ends_with(b"\r\n\r\n") { bytes.push(stream.read_u8().await.unwrap()); assert!(bytes.len()<8192); }
    }).await.unwrap();
}
async fn forward(tunnel: Tunnel, success: bool) {
    let target = TcpListener::bind("127.0.0.1:0").await.unwrap(); let destination = target.local_addr().unwrap();
    let responder = tokio::spawn(async move {
        let (mut socket,_) = target.accept().await.unwrap(); headers(&mut socket).await;
        socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 12\r\nConnection: close\r\n\r\nROUTE-PROOF!").await.unwrap();
    });
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
    let serving = tokio::spawn(async move {
        let (socket,peer) = listener.accept().await.unwrap();
        meow_listener::http_proxy::handle_http(&tunnel,socket,peer,None,None,"PROVIDER-GROUP-PROOF",address.port()).await;
    });
    let mut client = TcpStream::connect(address).await.unwrap();
    client.write_all(format!("GET http://{destination}/proof HTTP/1.1\r\nHost: {destination}\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut response = String::new(); timeout(Duration::from_secs(5),client.read_to_string(&mut response)).await.unwrap().unwrap();
    timeout(Duration::from_secs(5),serving).await.unwrap().unwrap();
    if success {
        assert!(response.starts_with("HTTP/1.1 200"),"{response}"); assert!(response.ends_with("ROUTE-PROOF!"));
        timeout(Duration::from_secs(5),responder).await.unwrap().unwrap();
    } else {
        assert!(!response.starts_with("HTTP/1.1 200"),"{response}"); assert!(!responder.is_finished());
        responder.abort(); assert!(responder.await.unwrap_err().is_cancelled());
    }
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn file_provider_nested_select_real_route_refresh_derived_health_and_stop() {
    let files = Files::new(); files.write("feed.yaml","proxies:\n - {name: a, type: direct}\n - {name: b, type: direct}\n");
    files.write("spare.yaml","proxies:\n - {name: unused, type: direct}\n");
    let (api,tunnel,scope) = fixture(&files,file_yaml()).await;
    let provider = api.state.proxy_providers.get("feed").unwrap().value().clone();
    let unused = api.state.proxy_providers.get("spare").unwrap().proxies()[0].clone();
    let retired = provider.proxies()[0].clone();
    let groups = api.control.activate_groups(&scope).unwrap(); api.activate_providers().unwrap();
    assert!(unused.health().observed()); assert!(provider.proxies().iter().all(|member|member.health().observed()));
    assert_eq!(tunnel.route_snapshot().proxies["FILTER"].members().unwrap(),vec!["a".to_owned()]);
    forward(tunnel.clone(),true).await;
    api.control.select("PICK","b").await.unwrap();
    assert_eq!(api.control.snapshot().await.unwrap().picks["PICK"],"b");
    api.control.select("OUTER","REJECT").await.unwrap(); forward(tunnel.clone(),false).await;
    api.control.select("OUTER","PICK").await.unwrap();
    let before = api.control.snapshot().await.unwrap(); let history = retired.health().delay_history().len();
    let mut changed = api.control.subscribe_groups();
    files.write("feed.yaml","proxies:\n - {name: c, type: direct}\n - {name: d, type: direct}\n");
    timeout(Duration::from_secs(5),provider.refresh_owned()).await.unwrap().unwrap();
    timeout(Duration::from_secs(5),changed.recv()).await.unwrap().unwrap();
    assert!(api.control.plane.proxy_snapshot(&api.state,Some("feed")).is_ok(), "group event escaped a durable/busy lease");
    let after = api.control.snapshot().await.unwrap(); assert_ne!(before.core_epoch,after.core_epoch);
    assert!(after.route_version.parse::<u64>().unwrap()>before.route_version.parse::<u64>().unwrap());
    assert_eq!(after.picks["PICK"],"c");
    assert_eq!(tunnel.route_snapshot().proxies["FILTER"].members().unwrap(),vec!["c".to_owned()]);
    assert!(provider.proxies().iter().all(|member|member.health().observed())); forward(tunnel.clone(),true).await;
    let stable = api.control.snapshot().await.unwrap(); retired.health().record_delay(0);
    assert_eq!(retired.health().delay_history().len(),history); assert_eq!(api.control.snapshot().await.unwrap(),stable);
    let current = provider.proxies()[0].clone(); let count = current.health().delay_history().len();
    current.health().record_delay(17); assert_eq!(current.health().delay_history().len(),count+1);
    let unchanged = api.control.snapshot().await.unwrap(); timeout(Duration::from_secs(5),provider.refresh_owned()).await.unwrap().unwrap();
    assert_eq!(api.control.snapshot().await.unwrap(),unchanged);
    stop(&api,&scope,groups).await;
    assert_eq!(api.control.select("PICK","c").await,Err(ControlError::Cancelled));
    let count = current.health().delay_history().len(); current.health().record_delay(19);
    assert_eq!(current.health().delay_history().len(),count);
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn http_provider_rejects_unintegrated_chain_before_cache_or_live_receipt_changes() {
    let files = Files::new(); let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = origin.local_addr().unwrap();
    let fetches = tokio::spawn(async move {
        for payload in ["proxies:\n - {name: a, type: direct}\n",
            "proxies:\n - {name: c, type: direct}\n",
            "ignored-root: true\nproxies:\n - {name: a, type: direct}\n",
            "proxies:\n - {name: a, type: direct, silent-field: true}\n",
            "proxies:\n - {name: chained, type: http, server: 127.0.0.1, port: 9, dialer-proxy: MISSING}\n"] {
            let (mut socket,_) = origin.accept().await.unwrap(); headers(&mut socket).await;
            socket.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{payload}",payload.len()).as_bytes()).await.unwrap();
        }
    });
    let yaml = format!("strict: true\nproxy-providers:\n  feed: {{type: http, url: 'http://{address}/feed', path: feed-cache.yaml, interval: 0}}\nproxy-groups:\n - {{name: PICK, type: select, use: [feed]}}\nrules: ['MATCH,PICK']\n");
    let (api,tunnel,scope) = fixture(&files,&yaml).await;
    let provider = api.state.proxy_providers.get("feed").unwrap().value().clone();
    let groups = api.control.activate_groups(&scope).unwrap(); api.activate_providers().unwrap();
    forward(tunnel.clone(),true).await;
    timeout(Duration::from_secs(5),provider.refresh_owned()).await.unwrap().unwrap();
    assert_eq!(provider.proxies()[0].name(),"c"); assert!(provider.proxies()[0].health().observed());
    assert_eq!(api.control.snapshot().await.unwrap().picks["PICK"],"c"); forward(tunnel,true).await;
    let cache = std::fs::read(files.0.join("feed-cache.yaml")).unwrap();
    let before = api.control.snapshot().await.unwrap(); let receipt = api.control.plane.provider_baseline()[0].revision.clone();
    for expected in ["payload.ignored-root", "silent-field", "managedProxyGraphNotIntegrated"] {
        let error = timeout(Duration::from_secs(5),provider.refresh_owned()).await.unwrap().unwrap_err(); assert!(error.contains("proxy-providers.feed"),"{error}");
        assert!(error.contains(expected),"{error}");
        assert_eq!(provider.proxies()[0].name(),"c"); assert_eq!(api.control.snapshot().await.unwrap(),before);
        assert_eq!(api.control.plane.provider_baseline()[0].revision,receipt);
        assert_eq!(std::fs::read(files.0.join("feed-cache.yaml")).unwrap(),cache);
    }
    timeout(Duration::from_secs(5),fetches).await.unwrap().unwrap(); stop(&api,&scope,groups).await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn provider_refresh_and_new_selection_fence_old_real_probe_without_retired_health() {
    let files = Files::new(); files.write("feed.yaml","proxies:\n - {name: a, type: direct}\n");
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = origin.local_addr().unwrap();
    let yaml = format!("strict: true\nproxy-providers:\n  feed: {{type: file, path: feed.yaml, interval: 0}}\nproxy-groups:\n - {{name: PICK, type: fallback, use: [feed], url: 'http://{address}/health', interval: 0}}\nrules: ['MATCH,PICK']\n");
    let (api,_,scope) = fixture(&files,&yaml).await; let provider = api.state.proxy_providers.get("feed").unwrap().value().clone();
    let retired = provider.proxies()[0].clone(); retired.health().record_delay(0); let count = retired.health().delay_history().len();
    let groups = api.control.activate_groups(&scope).unwrap(); api.activate_providers().unwrap();
    let control = api.control.clone(); let setter = tokio::spawn(async move { control.select("PICK","a").await });
    let (mut socket,_) = timeout(Duration::from_secs(5),origin.accept()).await.unwrap().unwrap(); headers(&mut socket).await;
    let mut committed_groups = api.control.subscribe_groups();
    let refresh_deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    files.write("feed.yaml","# replace adapter while preserving display name\nproxies:\n - {name: a, type: direct}\n");
    tokio::time::timeout_at(refresh_deadline,provider.refresh_owned()).await.unwrap().unwrap(); let current = provider.proxies()[0].clone(); assert!(!Arc::ptr_eq(&current,&retired));
    tokio::time::timeout_at(refresh_deadline, committed_groups.recv()).await.unwrap().unwrap();
    let stable = api.control.select("PICK","a").await.unwrap();
    socket.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap(); drop(socket);
    assert_eq!(timeout(Duration::from_secs(5),setter).await.unwrap().unwrap(),Err(ControlError::Superseded));
    assert_eq!(api.control.snapshot().await.unwrap(),stable); assert_eq!(retired.health().delay_history().len(),count);
    stop(&api,&scope,groups).await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn provider_node_delay_uses_official_lookup_and_publishes_observed_health() {
    let files = Files::new(); files.write("feed.yaml","proxies:\n - {name: only-in-provider, type: direct}\n");
    let yaml = "strict: true\nproxy-providers:\n  feed: {type: file, path: feed.yaml, interval: 0}\nproxy-groups:\n - {name: PICK, type: select, use: [feed]}\nrules: ['MATCH,PICK']\n";
    let (api,tunnel,scope) = fixture(&files,yaml).await;
    assert!(!tunnel.route_snapshot().proxies.contains_key("only-in-provider"));
    let member = api.state.proxy_providers.get("feed").unwrap().proxies()[0].clone();
    let groups = api.control.activate_groups(&scope).unwrap(); api.activate_providers().unwrap();
    let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let destination = origin.local_addr().unwrap();
    let responder = tokio::spawn(async move { let (mut socket,_) = origin.accept().await.unwrap(); headers(&mut socket).await;
        socket.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap(); });
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
    let token = CancellationToken::new(); let serving = { let api = api.clone(); let token = token.clone();
        tokio::spawn(async move { api.serve_private(listener,token).await }) };
    let count = member.health().delay_history().len();
    let mut client = TcpStream::connect(address).await.unwrap();
    client.write_all(format!("GET /proxies/only-in-provider/delay?url=http%3A%2F%2F{destination}%2Fhealth&timeout=2000 HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {SECRET}\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut response = String::new(); timeout(Duration::from_secs(5),client.read_to_string(&mut response)).await.unwrap().unwrap();
    assert!(response.starts_with("HTTP/1.1 200"),"{response}"); assert!(response.to_ascii_lowercase().contains("x-aerove-core-epoch:"));
    assert_eq!(member.health().delay_history().len(),count+1);
    timeout(Duration::from_secs(5),responder).await.unwrap().unwrap(); token.cancel();
    timeout(Duration::from_secs(5),serving).await.unwrap().unwrap().unwrap(); stop(&api,&scope,groups).await;
}

#[tokio::test]
async fn activation_waits_for_provider_lease_and_never_partially_installs_observers() {
    let files = Files::new(); files.write("feed.yaml","proxies:\n - {name: a, type: direct}\n");
    files.write("spare.yaml","proxies:\n - {name: unused, type: direct}\n");
    let (api,_,scope) = fixture(&files,file_yaml()).await;
    let provider = api.state.proxy_providers.get("feed").unwrap().value().clone();
    let held = api.control.plane.begin(provider.provider_identity()).unwrap();
    assert!(matches!(api.control.activate_groups(&scope),Err(ControlError::Superseded)));
    assert!(!provider.proxies()[0].health().observed()); drop(held);
    let groups = api.control.activate_groups(&scope).unwrap(); api.activate_providers().unwrap();
    assert!(provider.proxies()[0].health().observed()); stop(&api,&scope,groups).await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn existing_periodic_provider_owner_installs_new_graph_and_joins_at_stop() {
    let files = Files::new(); files.write("feed.yaml","proxies:\n - {name: a, type: direct}\n");
    files.write("spare.yaml","proxies:\n - {name: unused, type: direct}\n");
    let yaml = file_yaml().replace("interval: 0","interval: 1");
    let (api,tunnel,scope) = fixture(&files,&yaml).await;
    let provider = api.state.proxy_providers.get("feed").unwrap().value().clone();
    let groups = api.control.activate_groups(&scope).unwrap(); let mut events = api.control.subscribe_groups();
    api.activate_providers().unwrap(); let before = api.control.snapshot().await.unwrap();
    files.write("feed.yaml","proxies:\n - {name: c, type: direct}\n");
    timeout(Duration::from_secs(5),async {
        loop {
            events.recv().await.unwrap();
            if api.control.snapshot().await.unwrap().picks["PICK"] == "c" { break; }
        }
    }).await.unwrap();
    assert_ne!(api.control.snapshot().await.unwrap().core_epoch,before.core_epoch);
    assert!(provider.proxies()[0].health().observed()); forward(tunnel,true).await;
    stop(&api,&scope,groups).await;
}

#[tokio::test]
async fn explicit_provider_filter_excludes_rows_before_strict_projection() {
    let files = Files::new(); files.write("feed.yaml","proxies:\n - {name: a, type: direct}\n - {name: excluded, type: intentionally-excluded, unknown-field: true}\n");
    files.write("spare.yaml","proxies:\n - {name: unused, type: direct}\n");
    let yaml = file_yaml().replace("path: feed.yaml, interval: 0","path: feed.yaml, interval: 0, filter: '^a$'");
    let (api,_,scope) = fixture(&files,&yaml).await;
    let provider = api.state.proxy_providers.get("feed").unwrap().value().clone();
    assert_eq!(provider.proxies().len(),1); assert_eq!(provider.proxies()[0].name(),"a");
    let groups = api.control.activate_groups(&scope).unwrap(); api.activate_providers().unwrap();
    assert!(provider.proxies()[0].health().observed()); stop(&api,&scope,groups).await;
}
