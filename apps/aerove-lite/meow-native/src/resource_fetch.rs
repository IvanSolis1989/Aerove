use crate::{config_validation::OfflineConversion, network_context::{NetworkContext, NetworkContextSlot, ResolverPair},
    physical_dns::PhysicalResolver, resource_roots::ResourceRoots, transport_tasks::TransportTasks};
use meow_config::{Config, owned_load::{LoadContext, OwnedLoadScope, OwnedWork, ProviderRoots}};
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, future::Future, io, path::{Path, PathBuf}, sync::{Arc, OnceLock}};
use tokio::sync::oneshot;
use tokio_util::sync::CancellationToken;

const MAX_BYTES: usize = 64 * 1024 * 1024;
fn error(reason: impl Into<String>) -> io::Error { io::Error::other(reason.into()) }

#[derive(Deserialize)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
pub struct DownloadRequest {
    pub url: String, pub proxy: Option<String>, pub headers: BTreeMap<String, Vec<String>>,
    pub destination: String, pub max_bytes: usize,
}
#[derive(Serialize)]
#[serde(rename_all="camelCase")]
pub struct DownloadResult { pub bytes: usize, pub subscription_user_info: Option<String> }
#[derive(Default)]
struct State { closed: bool, loading: bool, config: Option<Arc<Config>> }
pub struct ResourceFetch {
    state: Mutex<State>, cancel: CancellationToken, scope: Arc<OwnedLoadScope>, tasks: Arc<TransportTasks>,
    network: NetworkContext, network_slot: Arc<NetworkContextSlot>, physical: Arc<PhysicalResolver>,
    staging: PathBuf, read_root: Option<PathBuf>, close_gate: tokio::sync::Mutex<()>,
}

/// The producer's cached join result, retained without changing the primary error.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ResourceRequestExit {
    Joined(meow_common::transport_tasks::TaskOutcome),
    ObservationPanicked,
}
impl std::fmt::Display for ResourceRequestExit {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Joined(outcome) => {
                let category = match (outcome.panicked, outcome.cancelled) {
                    (true, true) => "panicked-and-cancelled",
                    (true, false) => "panicked",
                    (false, true) => "cancelled",
                    (false, false) => "normal-no-send",
                };
                write!(f, "resourceRequestProducerExit stage=resourceProducerJoin outcome={category} panicked={} cancelled={}", outcome.panicked, outcome.cancelled)
            },
            Self::ObservationPanicked => f.write_str("resourceRequestProducerExit stage=resourceProducerJoin outcome=observation-panicked panicked=unknown cancelled=unknown"),
        }
    }
}
impl std::error::Error for ResourceRequestExit {}

#[derive(Debug)]
pub struct ResourceRequestOwnerEnded { pub exit: ResourceRequestExit }
impl std::fmt::Display for ResourceRequestOwnerEnded {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { f.write_str("resourceRequestOwnerEnded") }
}
impl std::error::Error for ResourceRequestOwnerEnded {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> { Some(&self.exit) }
}

async fn owner_ended_after(join: impl Future<Output=meow_common::transport_tasks::TaskOutcome>) -> io::Error {
    use futures::FutureExt;
    // Diagnostic collection must not replace the original sender-loss failure.
    let exit = match std::panic::AssertUnwindSafe(join).catch_unwind().await {
        Ok(outcome) => ResourceRequestExit::Joined(outcome),
        Err(payload) => {
            // The payload may itself panic when dropped outside this catch.
            std::mem::forget(payload);
            ResourceRequestExit::ObservationPanicked
        },
    };
    io::Error::other(ResourceRequestOwnerEnded { exit })
}

async fn receive_operation<T>(rx: oneshot::Receiver<io::Result<T>>, producer: Arc<meow_common::transport_tasks::SharedUnitJoin>) -> io::Result<T> {
    match rx.await {
        Ok(result) => result,
        Err(_) => Err(owner_ended_after(producer.wait()).await),
    }
}

struct CancelRequest(CancellationToken);
impl Drop for CancelRequest { fn drop(&mut self) { self.0.cancel(); } }

impl ResourceFetch {
    pub fn create(network: NetworkContext, servers: Vec<std::net::SocketAddr>, staging: &str, read_root: Option<&str>,
        cache: &str, cancel: CancellationToken) -> io::Result<Arc<Self>> {
        let roots = Arc::new(ResourceRoots::validate(read_root, Some(cache))?);
        let read_root = roots.provider_roots()?.resource_read_root;
        let staging = Path::new(staging);
        if !staging.is_absolute() || staging.canonicalize()?.as_path() != staging || !staging.is_dir() { return Err(error("stagingRoot: canonical directory required")); }
        let physical = PhysicalResolver::from_servers(servers, network, false)?;
        let network = physical.network().with_resource_roots(roots.clone());
        let slot = NetworkContextSlot::new(&network); let network = slot.view(&network);
        let tasks = TransportTasks::new(); let load_slot = Arc::new(OnceLock::new());
        let network = network.with_transport(tasks.clone(), load_slot.clone());
        let scope = OwnedLoadScope::with_context(Arc::new(network.clone()));
        load_slot.set(Arc::downgrade(&scope)).map_err(|_| error("resource scope registration failed"))?;
        Ok(Arc::new(Self { state:Mutex::new(State::default()),cancel,scope,tasks,network,network_slot:slot,physical,
            staging:staging.to_path_buf(),read_root,close_gate:tokio::sync::Mutex::new(()) }))
    }
    async fn operation<T: Send + 'static>(self: &Arc<Self>, work: impl Future<Output=io::Result<T>> + Send + 'static) -> io::Result<T> {
        let request = self.cancel.child_token(); let _cancel = CancelRequest(request.clone());
        let work: std::pin::Pin<Box<dyn Future<Output=io::Result<T>>+Send>>=Box::pin(work);
        let (tx,rx)=oneshot::channel();
        let network=self.network.clone();let scope=self.scope.clone();
        {
            let state=self.state.lock(); if state.closed || self.cancel.is_cancelled() { return Err(error("resourceScopeClosed")); }
        }
        let producer = {
            self.tasks.spawn_join_with(async move {
                let result=tokio::select! { biased; _=request.cancelled()=>Err(error("resourceRequestCancelled")), result=work=>result };
                let _=tx.send(result);
            }, move |work| Ok(tokio::spawn(async move { network.scope(scope.run(work)).await })))?
        };
        receive_operation(rx, producer).await
    }
    pub async fn load(self: &Arc<Self>, source: String) -> io::Result<()> {
        {
            let mut state=self.state.lock();
            if state.loading || state.config.is_some() { return Err(error("resourceGraphAlreadyLoaded")); }
            state.loading=true;
        }
        let owner=self.clone();
        self.operation(async move {
            let normalized=crate::config_validation::normalize_config_fields(&source).map_err(error)?;
            let mut raw=meow_config::parse_raw_yaml(&normalized).map_err(|cause|error(cause.to_string()))?;
            // Transport bootstrap is not full configuration validation. The original
            // candidate remains with Kotlin until all resources pass prepared conversion.
            raw.strict=Some(true); raw.rules=Some(vec!["MATCH,DIRECT".into()]); raw.rule_providers=None; raw.sub_rules=None;
            if let Some(dns)=raw.dns.as_mut() { dns.store_fake_ip=Some(false); }
            for (name, provider) in raw.proxy_providers.iter().flat_map(|providers| providers.iter()) {
                if provider.provider_type != "file" {
                    return Err(error(format!("$.proxy-providers.{name}.type: transport requires this candidate's materialized file payload")));
                }
                if provider.allow_external_plugin == Some(true) {
                    return Err(error(format!("$.proxy-providers.{name}.allow-external-plugin: forbidden in resource bootstrap")));
                }
                let path=provider.path.as_deref().ok_or_else(||error(format!("$.proxy-providers.{name}.path: materialized file required")))?;
                let file=Path::new(path);let canonical=file.canonicalize()?;
                if file!=canonical.as_path() || owner.read_root.as_ref().is_none_or(|root|!canonical.starts_with(root)) {
                    return Err(error(format!("$.proxy-providers.{name}.path: outside captured resourceReadRoot")));
                }
            }
            if raw.dns.as_ref().is_some_and(|dns|dns.fallback.as_ref().is_some_and(|servers|!servers.is_empty()) && dns.fallback_filter.as_ref().and_then(|filter|filter.geoip)!=Some(false)) {
                let path=raw.geodata.as_ref().and_then(|geo|geo.mmdb_path.as_deref())
                    .ok_or_else(||error("$.dns.fallback-filter.geoip: transport requires an already prepared geodata.mmdb-path"))?;
                let file=Path::new(path);let canonical=file.canonicalize()?;
                if file!=canonical.as_path() || owner.read_root.as_ref().is_none_or(|root|!canonical.starts_with(root)) {
                    return Err(error("$.geodata.mmdb-path: transport DNS resource must belong to captured resourceReadRoot"));
                }
                crate::config_validation::validate_resource_controlled("mmdb",&canonical,"","mmdb",owner.cancel.clone()).await.map_err(error)?;
            }
            if meow_config::dns_policy_uses_geosite(&raw) {
                let path=raw.geodata.as_ref().and_then(|geo|geo.geosite_path.as_deref())
                    .ok_or_else(||error("$.dns: GEOSITE policy requires an already prepared geodata.geosite-path for transport bootstrap"))?;
                let file=Path::new(path);let canonical=file.canonicalize()?;
                if file!=canonical.as_path() || owner.read_root.as_ref().is_none_or(|root|!canonical.starts_with(root)) {
                    return Err(error("$.geodata.geosite-path: transport DNS resource must belong to captured resourceReadRoot"));
                }
                crate::config_validation::validate_resource_controlled("geosite",&canonical,"","dat",owner.cancel.clone()).await.map_err(error)?;
            }
            for (i,node) in raw.proxies.iter().flatten().enumerate() {
                if let Some(plugin)=node.get("plugin").and_then(serde_yaml::Value::as_str) {
                    if !["obfs","simple-obfs","v2ray-plugin","gost-plugin","shadow-tls","restls","jls","kcptun","ech-tls-tunnel"].contains(&plugin) {
                        return Err(error(format!("$.proxies[{i}].plugin: external process is forbidden in resource bootstrap")));
                    }
                }
            }
            let yaml=serde_yaml::to_string(&raw).map_err(|cause|error(cause.to_string()))?;
            let config=meow_config::load_config_from_str_owned(&yaml,&owner.scope).await.map_err(|cause|error(format!("resource transport graph: {cause:#}")))?;
            let pair=ResolverPair { main:config.dns.resolver.clone(),proxy:config.dns.proxy_resolver.clone() };
            let hook=Arc::new(meow_dns::ResolverHostHook::new_with_proxy_resolver(pair.main.clone(),pair.proxy.clone()));
            let next=owner.network.with_resolver(hook).with_resolver_pair(pair);
            let retired={
                let mut state=owner.state.lock();
                if state.closed || owner.cancel.is_cancelled() { return Err(error("resourceScopeClosed")); }
                let retired=owner.network_slot.replace(&next);state.config=Some(Arc::new(config));retired
            };
            drop(retired); Ok(())
        }).await
    }
    pub async fn download(self: &Arc<Self>, request: DownloadRequest) -> io::Result<DownloadResult> {
        if request.max_bytes==0 || request.max_bytes>MAX_BYTES { return Err(error("maxBytes: must be within 1..64MiB")); }
        if request.url.len()>16384 || request.headers.len()>128 || request.headers.values().map(Vec::len).sum::<usize>()>256 {
            return Err(error("resource request limit exceeded"));
        }
        let destination=PathBuf::from(&request.destination);
        if destination.parent()!=Some(self.staging.as_path()) || destination.file_name().is_none() { return Err(error("destination: must be a new direct child of stagingRoot")); }
        if destination.symlink_metadata().is_ok() { return Err(error("destination: already exists")); }
        let config=self.state.lock().config.clone().ok_or_else(||error("resourceGraphNotReady"))?;
        let proxy=match request.proxy.as_deref() {
            None=>meow_config::internal_http::first_named_proxy(config.raw.proxies.as_deref(),&config.proxies),
            name=>meow_config::internal_http::resolve_download_proxy(&config.dialer_registry,name).map_err(|cause|error(format!("proxy: {cause:#}")))?,
        };
        let headers=request.headers.into_iter().flat_map(|(name,values)|values.into_iter().map(move|value|(name.clone(),value))).collect::<Vec<_>>();
        let owner=self.clone();
        self.operation(async move {
            let response=meow_config::internal_http::fetch_capped(&request.url,proxy.as_ref(),&headers,request.max_bytes).await.map_err(|cause|error(format!("resource HTTP: {cause:#}")))?;
            let info=response.header("subscription-userinfo").map(str::to_owned); let bytes=response.body.len();
            let job=owner.scope.spawn_blocking_owned(move || {
                use std::io::Write;
                let mut file=std::fs::OpenOptions::new().write(true).create_new(true).open(&destination)?;
                if let Err(cause)=file.write_all(&response.body).and_then(|_|file.sync_all()) {
                    drop(file);let _=std::fs::remove_file(&destination);return Err(cause);
                }
                Ok::<(),io::Error>(())
            }).map_err(error)?;
            job.await.map_err(|_|error("resource staging writer panicked"))??;
            if owner.cancel.is_cancelled() { return Err(error("resourceScopeClosed")); }
            Ok(DownloadResult {bytes,subscription_user_info:info})
        }).await
    }
    pub fn cancel(&self) {
        self.state.lock().closed=true; self.cancel.cancel(); self.tasks.cancel(); self.scope.cancel();
    }
    pub async fn close_and_join(&self) -> io::Result<()> {
        let _close=self.close_gate.lock().await; self.cancel();
        let config=self.state.lock().config.take(); drop(config);
        let tasks=self.tasks.close_joined().await;
        let scope=self.scope.close().await; let physical=self.physical.retire().await;
        if tasks?.panicked { return Err(error("resource transport task panicked")); }
        scope.map_err(error)?; physical.map_err(error)
    }
}

struct PreparedContext(ProviderRoots);
impl LoadContext for PreparedContext {
    fn offline_validation(&self)->bool { true }
    fn requires_provider_roots(&self)->bool { true }
    fn provider_roots(&self)->Option<ProviderRoots> { Some(self.0.clone()) }
    fn wrap(&self,work:OwnedWork)->OwnedWork { work }
    fn run_blocking(&self,work:Box<dyn FnOnce()+Send>) {work();}
}
/// Single-use owner for embedders and tests; JNI passes the handle's owner so cancel and closeJoin reach the conversion.
pub async fn validate_prepared(source:&str, read_root:&str, cache:&str)->Result<(),String> {
    let owner=OfflineResourceOwner::new();
    let result=owner.validate_prepared(source.to_owned(),read_root.to_owned(),cache.to_owned()).await;
    let closed=owner.close_and_join().await;
    result.map_err(|cause|cause.to_string())??;
    closed.map_err(|cause|cause.to_string())
}

#[cfg(test)]
mod operation_exit_tests {
    use super::*;
    use meow_common::transport_tasks::{SharedUnitJoin, TaskOutcome};
    use std::{error::Error, time::Duration};

    fn cause(error: &io::Error) -> ResourceRequestExit {
        assert_eq!(error.kind(), io::ErrorKind::Other);
        assert_eq!(error.to_string(), "resourceRequestOwnerEnded");
        let typed = error.get_ref().unwrap().downcast_ref::<ResourceRequestOwnerEnded>().unwrap();
        assert!(typed.source().unwrap().to_string().contains("resourceRequestProducerExit"));
        assert!(typed.exit.to_string().contains("stage=resourceProducerJoin"));
        assert!(typed.exit.to_string().len()<192);
        typed.exit
    }
    fn spawn(owner: &Arc<TransportTasks>, work: impl Future<Output=()> + Send + 'static) -> Arc<SharedUnitJoin> {
        owner.spawn_join_with(work, |work| Ok(tokio::spawn(work))).unwrap()
    }

    #[tokio::test]
    async fn sender_loss_reports_real_panicked_join_and_cleanup_keeps_it() {
        let owner=TransportTasks::new();let(tx,rx)=oneshot::channel::<io::Result<()>>();
        let record=spawn(&owner,async move {let _sender=tx;panic!("controlled resource producer panic");});
        let error=tokio::time::timeout(Duration::from_secs(5),receive_operation(rx,record.clone())).await.unwrap().unwrap_err();
        let expected=TaskOutcome {panicked:true,cancelled:false};
        assert_eq!(cause(&error),ResourceRequestExit::Joined(expected));
        assert!(cause(&error).to_string().contains("outcome=panicked panicked=true cancelled=false"));
        assert_eq!(record.wait().await,expected);
        assert!(owner.close_joined().await.unwrap().panicked);
        assert!(owner.close_joined().await.unwrap().panicked);
        println!("RESOURCE_OWNER_ENDED_DIAGNOSTIC panicked=true cancelled=false");
    }

    #[tokio::test]
    async fn sender_loss_reports_real_cancelled_join_without_a_success_result() {
        let owner=TransportTasks::new();let(tx,rx)=oneshot::channel::<io::Result<()>>();let(started,ready)=oneshot::channel();
        let record=spawn(&owner,async move {let _sender=tx;let _=started.send(());std::future::pending::<()>().await;});
        ready.await.unwrap();owner.cancel();
        let error=tokio::time::timeout(Duration::from_secs(5),receive_operation(rx,record.clone())).await.unwrap().unwrap_err();
        let expected=TaskOutcome {panicked:false,cancelled:true};
        assert_eq!(cause(&error),ResourceRequestExit::Joined(expected));
        assert!(cause(&error).to_string().contains("outcome=cancelled panicked=false cancelled=true"));
        assert_eq!(record.wait().await,expected);
        assert!(!owner.close_joined().await.unwrap().panicked);
        assert!(!owner.close_joined().await.unwrap().panicked);
        println!("RESOURCE_OWNER_ENDED_DIAGNOSTIC panicked=false cancelled=true");
    }

    #[tokio::test]
    async fn sender_loss_reports_a_normally_returned_producer_without_inventing_panic_or_cancel() {
        let owner=TransportTasks::new();let(tx,rx)=oneshot::channel::<io::Result<()>>();
        let record=spawn(&owner,async move {drop(tx);});
        let error=tokio::time::timeout(Duration::from_secs(5),receive_operation(rx,record.clone())).await.unwrap().unwrap_err();
        assert_eq!(cause(&error),ResourceRequestExit::Joined(TaskOutcome::default()));
        assert!(cause(&error).to_string().contains("outcome=normal-no-send panicked=false cancelled=false"));
        assert_eq!(record.wait().await,TaskOutcome::default());
        assert!(!owner.close_joined().await.unwrap().panicked);
        println!("RESOURCE_OWNER_ENDED_DIAGNOSTIC panicked=false cancelled=false");
    }

    #[derive(Debug)]
    struct BusinessFailure(Arc<()>);
    impl std::fmt::Display for BusinessFailure {
        fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result {f.write_str("original typed business failure")}
    }
    impl Error for BusinessFailure {}
    #[tokio::test]
    async fn delivered_success_and_original_typed_error_do_not_wait_or_rewrite_result() {
        for failure in [false,true] {
            let owner=TransportTasks::new();let(tx,rx)=oneshot::channel();let(release,held)=oneshot::channel();let identity=Arc::new(());let sent=identity.clone();
            let record=spawn(&owner,async move {
                let result=if failure {Err(io::Error::new(io::ErrorKind::PermissionDenied,BusinessFailure(sent)))} else {Ok(37)};
                let _=tx.send(result);let _=held.await;
            });
            let result=tokio::time::timeout(Duration::from_secs(5),receive_operation(rx,record.clone())).await.unwrap();
            assert!(!record.is_finished(),"normal result must not wait for producer join");
            if failure {
                let error=result.unwrap_err();assert_eq!(error.kind(),io::ErrorKind::PermissionDenied);
                assert_eq!(error.to_string(),"original typed business failure");
                assert!(Arc::ptr_eq(&error.get_ref().unwrap().downcast_ref::<BusinessFailure>().unwrap().0,&identity));
            } else {assert_eq!(result.unwrap(),37);}
            release.send(()).unwrap();assert!(!owner.close_joined().await.unwrap().panicked);
        }
    }

    #[tokio::test]
    async fn observation_panic_cannot_replace_the_primary_owner_ended_error() {
        let error=owner_ended_after(async {panic!("controlled join observation panic")}).await;
        assert_eq!(cause(&error),ResourceRequestExit::ObservationPanicked);
    }

    #[tokio::test]
    async fn cancelled_diagnostic_wait_keeps_the_original_join_owned_for_cleanup() {
        use futures::FutureExt;
        let owner=TransportTasks::new();let(tx,rx)=oneshot::channel::<io::Result<()>>();let(started,ready)=oneshot::channel();let(release,held)=oneshot::channel();
        let record=spawn(&owner,async move {drop(tx);let _=started.send(());let _=held.await;});
        ready.await.unwrap();
        let mut observing=Box::pin(receive_operation(rx,record.clone()));
        assert!(observing.as_mut().now_or_never().is_none());
        drop(observing);
        release.send(()).unwrap();
        let cleanup=tokio::time::timeout(Duration::from_secs(5),owner.close_joined()).await.unwrap().unwrap();
        assert!(!cleanup.panicked);
        assert!(record.is_finished());
        let cached=record.wait().await;
        assert_eq!(record.wait().await,cached);
    }

    #[tokio::test]
    async fn panicking_payload_drop_cannot_replace_owner_ended_or_escape_observation() {
        use futures::FutureExt;
        use std::sync::atomic::{AtomicUsize, Ordering};
        struct PanicOnDrop(Arc<AtomicUsize>);
        impl Drop for PanicOnDrop {
            fn drop(&mut self) {
                self.0.fetch_add(1, Ordering::SeqCst);
                panic!("controlled diagnostic payload Drop panic");
            }
        }
        let drops=Arc::new(AtomicUsize::new(0));
        let payload=PanicOnDrop(drops.clone());
        let observed=std::panic::AssertUnwindSafe(owner_ended_after(async move {
            std::panic::panic_any(payload)
        })).catch_unwind().await;
        let error=match observed {
            Ok(error)=>error,
            Err(_)=>panic!("diagnostic payload destruction replaced original sender-loss"),
        };
        assert_eq!(cause(&error),ResourceRequestExit::ObservationPanicked);
        assert_eq!(error.to_string(),"resourceRequestOwnerEnded");
        assert_eq!(error.kind(),io::ErrorKind::Other);
        assert_eq!(drops.load(Ordering::SeqCst),0,"captured payload must not be dropped outside catch");
    }

}

/// Offline work shares the resource handle lifecycle but owns no network or download graph.
/// Conversion scopes are owner children: cancel reaches them and close joins them, even after a converter panic.
pub struct OfflineResourceOwner {
    scope: Arc<OwnedLoadScope>, tasks: Arc<TransportTasks>, cancel: CancellationToken,
    children: Mutex<Vec<Arc<OwnedLoadScope>>>, close_gate: tokio::sync::Mutex<()>,
}
impl OfflineResourceOwner {
    pub fn new() -> Arc<Self> {
        Arc::new(Self { scope: OwnedLoadScope::with_context(Arc::new(OfflineConversion)), tasks: TransportTasks::new(),
            cancel: CancellationToken::new(), children: Mutex::default(), close_gate: tokio::sync::Mutex::new(()) })
    }
    pub async fn read<T: Send + 'static>(&self, work: impl FnOnce(CancellationToken) -> Result<T, String> + Send + 'static) -> io::Result<T> {
        self.read_async(move |token| async move { work(token) }).await
    }
    /// The owned worker already polls this future with block_on; async parsers must never start a nested runtime.
    pub async fn read_async<T, W, F>(&self, work: W) -> io::Result<T>
    where T: Send + 'static, W: FnOnce(CancellationToken) -> F + Send + 'static, F: Future<Output = Result<T, String>> + Send + 'static {
        use futures::FutureExt;
        if self.cancel.is_cancelled() { return Err(error("resourceScopeClosed")); }
        let token = self.cancel.clone();
        let scope = self.scope.clone();
        let (tx, rx) = oneshot::channel();
        // Store the actual blocking JoinHandle in the existing shared-join registry.
        // Aborting a started blocking task never substitutes for waiting for its completion.
        let producer = self.tasks.spawn_join_with(async move {
            let result: Result<T, String> = if token.is_cancelled() { Err("resourceReadCancelled".to_owned()) } else {
                let call = token.clone();
                let work: futures::future::BoxFuture<'static, Result<T, String>> = Box::pin(async move { work(call).await });
                match std::panic::AssertUnwindSafe(work).catch_unwind().await {
                    Err(_) => Err("native_validation_panicked".to_owned()),
                    Ok(_) if token.is_cancelled() => Err("resourceReadCancelled".to_owned()),
                    Ok(output) => output,
                }
            };
            let _ = tx.send(result.map_err(error));
        }, move |work| {
            let runtime = tokio::runtime::Handle::current();
            scope.spawn_blocking_owned(move || runtime.block_on(work)).map_err(error)
        })?;
        let result = receive_operation(rx, producer.clone()).await;
        let joined = producer.wait().await;
        if joined.panicked { return result.and(Err(error("resource reader worker panicked"))); }
        result
    }
    fn child_scope(&self, context: Arc<dyn LoadContext>) -> io::Result<Arc<OwnedLoadScope>> {
        let child = OwnedLoadScope::with_context(context);
        let mut children = self.children.lock();
        if self.cancel.is_cancelled() { return Err(error("resourceScopeClosed")); }
        children.push(child.clone());
        Ok(child)
    }
    async fn validate_owned<W, F>(&self, context: Arc<dyn LoadContext>, work: W) -> io::Result<Result<(), String>>
    where W: FnOnce(Arc<OwnedLoadScope>, CancellationToken) -> F + Send + 'static, F: Future<Output = Result<(), String>> + Send + 'static {
        use futures::FutureExt;
        let child = self.child_scope(context)?;
        let scope = child.clone();
        let result = self.read_async(move |cancel| async move {
            let work: futures::future::BoxFuture<'static, Result<(), String>> = Box::pin(async move { work(scope, cancel).await });
            Ok(std::panic::AssertUnwindSafe(work).catch_unwind().await
                .unwrap_or_else(|_| Err("native_validation_panicked".to_owned())))
        }).await;
        let joined = child.close().await;
        self.children.lock().retain(|owned| !Arc::ptr_eq(owned, &child));
        match (result?, joined) {
            (Ok(()), Err(cause)) => Ok(Err(cause.to_owned())),
            (validated, _) => Ok(validated),
        }
    }
    /// JNI MeowNativeCore.validate: Ok(Err(reason)) is the converter's diagnostic; Err is owner, cancel or bridge failure.
    pub async fn validate_config(&self, source: String) -> io::Result<Result<(), String>> {
        self.validate_owned(Arc::new(OfflineConversion), move |scope, _| async move {
            let normalized = crate::config_validation::normalize_config_fields(&source)?;
            crate::config_validation::scoped_offline_validation(&normalized, &scope).await
        }).await
    }
    pub async fn validate_prepared(&self, source: String, read_root: String, cache: String) -> io::Result<Result<(), String>> {
        let roots = match ResourceRoots::validate(Some(read_root.as_str()), Some(cache.as_str())).and_then(|roots| roots.provider_roots()) {
            Ok(roots) => roots,
            Err(cause) => return Ok(Err(cause.to_string())),
        };
        let read = roots.resource_read_root.clone();
        self.validate_owned(Arc::new(PreparedContext(roots)), move |scope, cancel| async move {
            let normalized = crate::config_validation::normalize_config_fields(&source)?;
            let raw = meow_config::parse_raw_yaml(&normalized).map_err(|cause| cause.to_string())?;
            if let Some(geo) = raw.geodata.as_ref() {
                for (path, kind, format) in [(geo.mmdb_path.as_ref(), "mmdb", "mmdb"), (geo.asn_path.as_ref(), "mmdb", "mmdb"), (geo.geosite_path.as_ref(), "geosite", "dat")] {
                    if let Some(path) = path {
                        let file = Path::new(path); let canonical = file.canonicalize().map_err(|cause| cause.to_string())?;
                        if file != canonical.as_path() || !read.as_ref().is_some_and(|root| canonical.starts_with(root)) { return Err("$.geodata: path outside prepared resource root".into()); }
                        crate::config_validation::validate_resource_controlled(kind, &canonical, "", format, cancel.clone()).await?;
                    }
                }
            }
            crate::config_validation::scoped_prepared_validation(&normalized, &scope).await
        }).await
    }
    pub fn cancel(&self) {
        self.cancel.cancel(); self.tasks.cancel(); self.scope.cancel();
        for child in self.children.lock().iter() { child.cancel(); }
    }
    pub async fn close_and_join(&self) -> io::Result<()> {
        let _close = self.close_gate.lock().await;
        self.cancel();
        let tasks = self.tasks.close_joined().await;
        let scope = self.scope.close().await.map_err(error);
        let children = std::mem::take(&mut *self.children.lock());
        let mut joined = Ok(());
        for child in children { if let Err(cause) = child.close().await { joined = Err(error(cause)); } }
        if tasks?.panicked { return Err(error("resource reader worker panicked")); }
        scope.and(joined)
    }
}

/// JNI validateResource composition: Ok(Err(reason)) is the validator's diagnostic; Err is owner, cancel or bridge failure.
pub async fn validate_resource_owned(owner: &OfflineResourceOwner, kind: String, path: String, behavior: String, format: String)
    -> io::Result<Result<(), String>> {
    use futures::FutureExt;
    owner.read_async(move |cancel| async move {
        // A validator panic keeps the original diagnostic-string contract instead of becoming an owner failure.
        Ok(std::panic::AssertUnwindSafe(crate::config_validation::validate_resource_controlled(&kind, Path::new(&path), &behavior, &format, cancel))
            .catch_unwind().await.unwrap_or_else(|_| Err("native_validation_panicked".into())))
    }).await
}

#[cfg(test)]
mod offline_read_owner_tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::Duration;
    #[test]
    fn offline_validation_future_layout_diagnostic() {
        let scope = OwnedLoadScope::with_context(Arc::new(OfflineConversion));
        let core = meow_config::validate_offline_config_with_scope("rules: ['MATCH,DIRECT']\n", &scope);
        let inline = std::mem::size_of_val(&core);
        drop(core);
        let boxed = crate::config_validation::scoped_offline_validation("rules: ['MATCH,DIRECT']\n", &scope);
        let prepared = crate::config_validation::scoped_prepared_validation("rules: ['MATCH,DIRECT']\n", &scope);
        let owner = OfflineResourceOwner::new();
        let owned = owner.validate_prepared("rules: ['MATCH,DIRECT']\n".into(), "unused-read".into(), "unused-cache".into());
        eprintln!("[offline-validation-layout] core-inline={inline} offline-boxed={} prepared-boxed={} owner-prepared={} before-first-poll",
            std::mem::size_of_val(&boxed), std::mem::size_of_val(&prepared), std::mem::size_of_val(&owned));
    }
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn cancel_and_close_wait_for_actual_blocking_worker_drop_without_a_download_graph() {
        let owner = OfflineResourceOwner::new();
        let exited = Arc::new(AtomicBool::new(false));
        let (started_tx, started_rx) = tokio::sync::oneshot::channel();
        let (cancelled_tx, cancelled_rx) = tokio::sync::oneshot::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let worker_owner = owner.clone(); let dropped = exited.clone();
        let worker = tokio::spawn(async move { worker_owner.read(move |token| {
            struct Exit(Arc<AtomicBool>);
            impl Drop for Exit { fn drop(&mut self) { self.0.store(true, Ordering::Release); } }
            let _exit = Exit(dropped);
            started_tx.send(()).unwrap();
            while !token.is_cancelled() { std::thread::sleep(Duration::from_millis(1)); }
            cancelled_tx.send(()).unwrap();
            release_rx.recv_timeout(Duration::from_secs(5)).unwrap();
            Ok("must not publish")
        }).await });
        tokio::time::timeout(Duration::from_secs(5), started_rx).await.unwrap().unwrap();
        owner.cancel();
        tokio::time::timeout(Duration::from_secs(5), cancelled_rx).await.unwrap().unwrap();
        let cleanup_owner = owner.clone();
        let mut cleanup = tokio::spawn(async move { cleanup_owner.close_and_join().await });
        assert!(tokio::time::timeout(Duration::from_millis(20), &mut cleanup).await.is_err());
        assert!(!exited.load(Ordering::Acquire));
        release_tx.send(()).unwrap();
        assert!(worker.await.unwrap().unwrap_err().to_string().contains("Cancelled"));
        tokio::time::timeout(Duration::from_secs(5), cleanup).await.unwrap().unwrap().unwrap();
        assert!(exited.load(Ordering::Acquire));
        assert!(owner.read(|_| Ok(())).await.unwrap_err().to_string().contains("Closed"));
        owner.close_and_join().await.unwrap();
    }
    fn fixture(name: &str, bytes: &[u8]) -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!("meow-owned-{name}-{}-{}", std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::write(&path, bytes).unwrap(); path
    }
    fn geosite_dat() -> Vec<u8> {
        let mut domain = vec![8, 3, 18, 11]; domain.extend_from_slice(b"example.com");
        let mut site = vec![10, 2, b'c', b'n', 18, domain.len() as u8]; site.extend(domain);
        let mut list = vec![10, site.len() as u8]; list.extend(site); list
    }
    #[test]
    fn jni_composed_validations_poll_real_conversions_inside_the_owned_worker() {
        // Same driver as meow_jni: a plain caller thread blocks on a shared two-worker runtime.
        let runtime = Arc::new(tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap());
        let owner = OfflineResourceOwner::new();
        let files = [fixture("rules", b"example.com\n"), fixture("proxies", b"proxies: [{name: direct-node, type: direct}]\n"),
            fixture("unknown", b"proxies: [{name: direct-node, type: direct, ignored-field: true}]\n"),
            fixture("plugin", b"proxies: [{name: external, type: ss, server: '127.0.0.1', port: 1, cipher: aes-128-gcm, password: secret, plugin: should-never-execute}]\n"),
            fixture("geosite", &geosite_dat()), fixture("mmdb", b"not a database")];
        let [rules, proxies, unknown, plugin, geosite, mmdb] = &files;
        let validate = |kind: &str, path: &std::path::Path, behavior: &str, format: &str| runtime.block_on(validate_resource_owned(
            &owner, kind.into(), path.to_string_lossy().into_owned(), behavior.into(), format.into())).unwrap();
        assert_eq!(validate("ruleset", rules, "domain", "text"), Ok(()));
        assert_eq!(validate("ruleset", rules, "ipcidr", "text"), Err("rule_set_invalid: $.behavior: rule_set_behavior_mismatch".to_owned()));
        assert_eq!(validate("proxy-provider", proxies, "", "yaml"), Ok(()));
        assert!(validate("proxy-provider", unknown, "", "yaml").unwrap_err().contains("$.proxies[0].ignored-field"));
        assert!(validate("proxy-provider", plugin, "", "yaml").unwrap_err().contains("offline_external_plugin_forbidden"));
        assert_eq!(validate("geosite", geosite, "", "dat"), Ok(()));
        assert!(validate("mmdb", mmdb, "", "mmdb").unwrap_err().starts_with("MMDB decode"));
        assert_eq!(runtime.block_on(owner.validate_config("mode: rule\nrules: ['MATCH,DIRECT']\n".into())).unwrap(), Ok(()));
        assert!(runtime.block_on(owner.validate_config("unknown-root: true\n".into())).unwrap().unwrap_err().contains("$.unknown-root"));
        // The pre-fix composition started a second runtime from this worker; the same driver reports it as a panic.
        let (nested, path) = (runtime.clone(), proxies.clone());
        let failed = runtime.block_on(owner.read(move |cancel| nested.block_on(
            crate::config_validation::validate_resource_controlled("proxy-provider", &path, "", "yaml", cancel)))).unwrap_err();
        assert!(failed.to_string().contains("native_validation_panicked"));
        runtime.block_on(owner.close_and_join()).unwrap();
        assert!(runtime.block_on(validate_resource_owned(&owner, "ruleset".into(), rules.to_string_lossy().into_owned(),
            "domain".into(), "text".into())).unwrap_err().to_string().contains("Closed"));
        for file in &files { std::fs::remove_file(file).unwrap(); }
    }
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn owner_cancel_stops_the_real_conversion_between_jobs_and_joins_its_child_scope() {
        use crate::config_validation::ConversionGate;
        let convert = |scope: Arc<OwnedLoadScope>, _: CancellationToken| async move {
            meow_config::validate_offline_config_with_scope("rules: ['MATCH,DIRECT']\n", &scope).await
        };
        let control = Arc::new(ConversionGate::default()); control.release();
        let finished = OfflineResourceOwner::new();
        assert_eq!(finished.validate_owned(control.clone(), convert).await.unwrap(), Ok(()));
        assert!(control.calls() > 1, "the real conversion runs several owned blocking jobs");
        finished.close_and_join().await.unwrap();
        let gate = Arc::new(ConversionGate::default());
        let owner = OfflineResourceOwner::new();
        let running = { let (owner, gate) = (owner.clone(), gate.clone()); tokio::spawn(async move { owner.validate_owned(gate, convert).await }) };
        tokio::time::timeout(Duration::from_secs(5), gate.observed.notified()).await.unwrap();
        owner.cancel();
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(!running.is_finished(), "cancel must join the started conversion job instead of abandoning it");
        assert!(!gate.first_done.load(Ordering::SeqCst));
        gate.release();
        let error = tokio::time::timeout(Duration::from_secs(5), running).await.unwrap().unwrap().unwrap_err();
        assert!(error.to_string().contains("Cancelled"));
        assert!(gate.first_done.load(Ordering::SeqCst));
        assert_eq!(gate.calls(), 1, "no later conversion job may start after the owner was cancelled");
        assert!(owner.children.lock().is_empty());
        owner.close_and_join().await.unwrap();
        assert!(owner.validate_config("rules: ['MATCH,DIRECT']\n".into()).await.unwrap_err().to_string().contains("Closed"));
    }
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn converter_panic_becomes_the_diagnostic_only_after_its_child_scope_is_joined() {
        use crate::config_validation::ConversionGate;
        use futures::FutureExt;
        async fn panicking(scope: Arc<OwnedLoadScope>, gate: Arc<ConversionGate>) -> Result<(), String> {
            tokio::select! {
                result = meow_config::validate_offline_config_with_scope("rules: ['MATCH,DIRECT']\n", &scope) => result,
                _ = gate.entered.notified() => panic!("controlled converter panic"),
            }
        }
        // Control: unwinding through the fixed converter only cancels its scope; the started job keeps running.
        let gate = Arc::new(ConversionGate::default());
        let scope = OwnedLoadScope::with_context(gate.clone());
        assert!(std::panic::AssertUnwindSafe(panicking(scope.clone(), gate.clone())).catch_unwind().await.is_err());
        assert!(scope.is_cancelled());
        assert!(!gate.first_done.load(Ordering::SeqCst));
        gate.release();
        let _ = tokio::time::timeout(Duration::from_secs(5), scope.close()).await.unwrap();
        let gate = Arc::new(ConversionGate::default());
        let owner = OfflineResourceOwner::new();
        let running = { let (owner, gate) = (owner.clone(), gate.clone());
            tokio::spawn(async move { let trigger = gate.clone(); owner.validate_owned(gate, move |scope, _| panicking(scope, trigger)).await }) };
        tokio::time::timeout(Duration::from_secs(5), gate.observed.notified()).await.unwrap();
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(!running.is_finished(), "the panic path must join the converter's started job");
        assert!(!gate.first_done.load(Ordering::SeqCst));
        gate.release();
        let result = tokio::time::timeout(Duration::from_secs(5), running).await.unwrap().unwrap().unwrap();
        assert_eq!(result, Err("native_validation_panicked".to_owned()));
        assert!(gate.first_done.load(Ordering::SeqCst));
        assert!(owner.children.lock().is_empty());
        owner.close_and_join().await.unwrap();
    }
    #[tokio::test]
    async fn parser_panic_is_an_error_and_the_owned_worker_is_still_joined() {
        let owner = OfflineResourceOwner::new();
        assert!(owner.read::<()>(|_| panic!("reader fixture")).await.unwrap_err().to_string().contains("native_validation_panicked"));
        owner.close_and_join().await.unwrap();
    }
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_concurrent_close_cannot_report_joined_before_the_first_closer_joins_its_children() {
        let owner = OfflineResourceOwner::new();
        let child = owner.child_scope(Arc::new(OfflineConversion)).unwrap();
        let (release, held) = std::sync::mpsc::channel::<()>();
        let finished = Arc::new(AtomicBool::new(false)); let done = finished.clone();
        let _job = child.spawn_blocking_owned(move || { held.recv_timeout(Duration::from_secs(10)).unwrap(); done.store(true, Ordering::SeqCst); }).unwrap();
        let closer = |owner: Arc<OfflineResourceOwner>| tokio::spawn(async move { owner.close_and_join().await });
        let first = closer(owner.clone());
        tokio::time::sleep(Duration::from_millis(50)).await;
        let second = closer(owner.clone());
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(!first.is_finished() && !second.is_finished(), "no closer may report joined while a child job still runs");
        release.send(()).unwrap();
        tokio::time::timeout(Duration::from_secs(5), first).await.unwrap().unwrap().unwrap();
        tokio::time::timeout(Duration::from_secs(5), second).await.unwrap().unwrap().unwrap();
        assert!(finished.load(Ordering::SeqCst));
    }
    /// Canonical read/cache roots with one invalid 1 MiB geodata payload; the probe pauses on its first cancellation check.
    struct GeoRoots { base: std::path::PathBuf, read: std::path::PathBuf, cache: std::path::PathBuf, mmdb: std::path::PathBuf }
    impl GeoRoots {
        fn new(name: &str) -> Self {
            let base = std::env::temp_dir().canonicalize().unwrap().join(format!("meow-f7-{name}-{}-{}", std::process::id(),
                std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
            let (read, cache) = (base.join("read"), base.join("cache"));
            for dir in [&base, &read, &cache] { std::fs::create_dir(dir).unwrap(); }
            let mmdb = read.join("geo.mmdb"); std::fs::write(&mmdb, vec![0x5au8; 1024 * 1024]).unwrap();
            Self { base, read, cache, mmdb }
        }
    }
    impl Drop for GeoRoots { fn drop(&mut self) { let _ = std::fs::remove_dir_all(&self.base); } }
    async fn paused_at_first_geo_check(probe: &Arc<crate::config_validation::geo_probe::Probe>) {
        let waiting = probe.clone();
        assert!(tokio::task::spawn_blocking(move || waiting.wait_entered()).await.unwrap(), "geodata reading never started");
    }
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn prepared_validation_forwards_the_owner_token_into_geodata_reading() {
        // Public entry: OfflineResourceOwner::validate_prepared. A replaced token keeps reading all 16 chunks.
        let roots = GeoRoots::new("prepared");
        let probe = crate::config_validation::geo_probe::register(&roots.mmdb);
        let yaml = format!("geodata: {{mmdb-path: {:?}}}\nrules: ['MATCH,DIRECT']\n", roots.mmdb.to_str().unwrap());
        let owner = OfflineResourceOwner::new();
        let running = { let (owner, read, cache) = (owner.clone(), roots.read.to_str().unwrap().to_owned(), roots.cache.to_str().unwrap().to_owned());
            tokio::spawn(async move { owner.validate_prepared(yaml, read, cache).await }) };
        paused_at_first_geo_check(&probe).await;
        owner.cancel(); probe.release();
        let error = tokio::time::timeout(Duration::from_secs(10), running).await.unwrap().unwrap().unwrap_err();
        assert!(error.to_string().contains("Cancelled"), "{error}");
        assert_eq!(probe.calls(), 1, "the owner token must stop geodata reading at its next check");
        assert!(owner.children.lock().is_empty());
        owner.close_and_join().await.unwrap();
    }
    #[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn transport_load_forwards_the_fetch_token_into_dns_geodata_reading() {
        let roots = GeoRoots::new("load");
        let stage = roots.base.join("stage"); std::fs::create_dir(&stage).unwrap();
        let probe = crate::config_validation::geo_probe::register(&roots.mmdb);
        let cancel = CancellationToken::new();
        let fetch = ResourceFetch::create(NetworkContext::platform(cancel.clone()), vec!["127.0.0.1:9".parse().unwrap()],
            stage.to_str().unwrap(), Some(roots.read.to_str().unwrap()), roots.cache.to_str().unwrap(), cancel).unwrap();
        let yaml = format!("dns: {{enable: true, fallback: ['udp://127.0.0.1:9']}}\ngeodata: {{mmdb-path: {:?}}}\nrules: ['MATCH,DIRECT']\n",
            roots.mmdb.to_str().unwrap());
        let running = { let fetch = fetch.clone(); tokio::spawn(async move { fetch.load(yaml).await }) };
        paused_at_first_geo_check(&probe).await;
        fetch.cancel(); probe.release();
        let error = tokio::time::timeout(Duration::from_secs(10), running).await.unwrap().unwrap().unwrap_err();
        assert_eq!(error.to_string(), "resourceReadCancelled");
        assert_eq!(probe.calls(), 1, "the fetch token must stop transport geodata reading at its next check");
        fetch.close_and_join().await.unwrap();
    }
}
