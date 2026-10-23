use meow_config::owned_load::{LoadContext, OwnedLoadScope, OwnedWork};
use std::{sync::Arc, time::Duration};

#[cfg(all(unix, feature = "ss"))]
struct Directory(std::path::PathBuf);
#[cfg(all(unix, feature = "ss"))]
impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("aerove-offline-{}-{}", std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir(&path).unwrap(); Self(path)
    }
}
#[cfg(all(unix, feature = "ss"))]
impl Drop for Directory { fn drop(&mut self) { let _ = std::fs::remove_dir_all(&self.0); } }

struct Offline;
impl LoadContext for Offline {
    fn offline_validation(&self) -> bool { true }
    fn wrap(&self, work: OwnedWork) -> OwnedWork { work }
    fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) { work(); }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn actual_remote_provider_and_dns_conversion_have_zero_http_and_udp_io() {
    let cli = meow_config::is_offline_validate();
    let http = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let dns = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let origin = http.local_addr().unwrap(); let bootstrap = dns.local_addr().unwrap();
    let source = format!("proxy-providers:\n remote:\n  type: http\n  url: http://{origin}/proxies\n  interval: 1\ndns:\n enable: true\n use-system-hosts: true\n enhanced-mode: fake-ip\n store-fake-ip: true\n default-nameserver: ['{bootstrap}']\n nameserver: ['udp://offline.invalid:53']\nrules: ['MATCH,DIRECT']\n");
    tokio::time::timeout(Duration::from_secs(5), meow_config::validate_offline_config(&source)).await.unwrap().unwrap();
    assert!(tokio::time::timeout(Duration::from_millis(100), http.accept()).await.is_err(), "offline factory contacted the real HTTP origin");
    let mut buffer = [0; 1024];
    assert!(tokio::time::timeout(Duration::from_millis(100), dns.recv_from(&mut buffer)).await.is_err(), "offline factory bootstrapped via real UDP");
    assert_eq!(meow_config::is_offline_validate(), cli);
}

#[cfg(all(unix, feature = "ss"))]
#[tokio::test]
async fn executable_plugin_is_rejected_before_its_real_marker_can_be_written() {
    use std::os::unix::fs::PermissionsExt;
    let directory = Directory::new(); let executable = directory.0.join("plugin"); let marker = directory.0.join("spawned");
    std::fs::write(&executable, format!("#!/bin/sh\nprintf spawned > '{}'\n", marker.display())).unwrap();
    std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
    let source = format!("proxies:\n - name: guarded\n   type: ss\n   server: 127.0.0.1\n   port: 443\n   cipher: aes-128-gcm\n   password: secret\n   plugin: '{}'\nrules: ['MATCH,DIRECT']\n", executable.display());
    assert_eq!(meow_config::validate_offline_config(&source).await.unwrap_err(), "offline_external_plugin_forbidden");
    assert!(!marker.exists(), "validation ran the actual plugin executable");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 3)]
async fn concurrent_actual_conversions_do_not_change_live_scope_or_cli_flag() {
    let cli = meow_config::is_offline_validate();
    let live = OwnedLoadScope::new(); let offline = OwnedLoadScope::with_context(Arc::new(Offline));
    let good = "proxies: [{name: d, type: direct}]\nproxy-groups: [{name: PICK, type: select, proxies: [d, REJECT]}]\nrules: ['MATCH,PICK']\n";
    let bad = "strict: false\nproxies: [{name: bad, type: no-such-protocol}]\nrules: ['MATCH,DIRECT']\n";
    let (converted, rejected, (in_live, in_offline)) = tokio::join!(
        meow_config::validate_offline_config(good), meow_config::validate_offline_config(bad), async {
            tokio::join!(live.run(async { tokio::task::yield_now().await; meow_config::is_offline_validate() }),
                offline.run(async { tokio::task::yield_now().await; meow_config::is_offline_validate() }))
        });
    converted.unwrap(); assert!(rejected.is_err()); assert!(!in_live); assert!(in_offline);
    assert_eq!(meow_config::is_offline_validate(), cli); live.close().await.unwrap(); offline.close().await.unwrap();
    assert!(meow_config::validate_offline_config("unknown-setting: true\nrules: ['MATCH,DIRECT']").await.unwrap_err().contains("unsupported_config_field"));
}

struct Blocker { started: tokio::sync::Notify, release: std::sync::Mutex<bool>, condition: std::sync::Condvar }
impl Blocker {
    fn release(&self) { *self.release.lock().unwrap() = true; self.condition.notify_all(); }
}
struct Release(Arc<Blocker>);
impl Drop for Release { fn drop(&mut self) { self.0.release(); } }
struct BlockContext(Arc<Blocker>);
impl LoadContext for BlockContext {
    fn offline_validation(&self) -> bool { true }
    fn wrap(&self, work: OwnedWork) -> OwnedWork { work }
    fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) {
        self.0.started.notify_one(); let mut released = self.0.release.lock().unwrap();
        while !*released { released = self.0.condition.wait(released).unwrap(); } drop(released); work();
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn caller_abort_keeps_actual_factory_job_owned_until_join_then_successor_can_validate() {
    let cli = meow_config::is_offline_validate();
    let blocker = Arc::new(Blocker { started: tokio::sync::Notify::new(), release: std::sync::Mutex::new(false), condition: std::sync::Condvar::new() });
    let release = Release(blocker.clone()); let scope = OwnedLoadScope::with_context(Arc::new(BlockContext(blocker.clone())));
    let running = scope.clone();
    let caller = tokio::spawn(async move { meow_config::validate_offline_config_with_scope("rules: ['MATCH,DIRECT']", &running).await });
    tokio::time::timeout(Duration::from_secs(5), blocker.started.notified()).await.unwrap();
    caller.abort(); assert!(caller.await.unwrap_err().is_cancelled()); assert!(scope.is_cancelled());
    let joining = scope.clone(); let close = tokio::spawn(async move { joining.close().await });
    tokio::task::yield_now().await; assert!(!close.is_finished(), "the blocked real factory job escaped close");
    drop(release); tokio::time::timeout(Duration::from_secs(5), close).await.unwrap().unwrap().unwrap();
    assert_eq!(meow_config::is_offline_validate(), cli);
    meow_config::validate_offline_config("rules: ['MATCH,DIRECT']").await.unwrap();
}

#[tokio::test]
#[cfg(feature = "anytls")]
async fn actual_anytls_factory_converts_without_starting_a_runtime_client() {
    meow_config::validate_offline_config("proxies: [{name: any, type: anytls, server: example.com, port: 443, password: secret, udp: true}]\nproxy-groups: [{name: PICK, type: select, proxies: [any]}]\nrules: ['MATCH,PICK']\n").await.unwrap();
    assert!(meow_config::validate_offline_config("proxies: [{name: any, type: anytls, server: example.com, port: 0, password: secret}]\nrules: ['MATCH,DIRECT']\n").await.is_err());
}
