use crate::{network_context::{NetworkContext, NetworkContextSlot, ResolverPair}, physical_dns::PhysicalResolver,
    resource_roots::ResourceRoots, transport_tasks::TransportTasks};
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
        {
            self.tasks.spawn_with(async move {
                let result=tokio::select! { biased; _=request.cancelled()=>Err(error("resourceRequestCancelled")), result=work=>result };
                let _=tx.send(result);
            }, move |work| Ok(tokio::spawn(async move { network.scope(scope.run(work)).await })))?;
        }
        rx.await.map_err(|_|error("resourceRequestOwnerEnded"))?
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
                crate::config_validation::validate_resource("mmdb",&canonical,"","mmdb").await.map_err(error)?;
            }
            if meow_config::dns_policy_uses_geosite(&raw) {
                let path=raw.geodata.as_ref().and_then(|geo|geo.geosite_path.as_deref())
                    .ok_or_else(||error("$.dns: GEOSITE policy requires an already prepared geodata.geosite-path for transport bootstrap"))?;
                let file=Path::new(path);let canonical=file.canonicalize()?;
                if file!=canonical.as_path() || owner.read_root.as_ref().is_none_or(|root|!canonical.starts_with(root)) {
                    return Err(error("$.geodata.geosite-path: transport DNS resource must belong to captured resourceReadRoot"));
                }
                crate::config_validation::validate_resource("geosite",&canonical,"","dat").await.map_err(error)?;
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
pub async fn validate_prepared(source:&str, read_root:&str, cache:&str)->Result<(),String> {
    let roots=ResourceRoots::validate(Some(read_root),Some(cache)).map_err(|cause|cause.to_string())?;
    let roots=roots.provider_roots().map_err(|cause|cause.to_string())?;
    let normalized=crate::config_validation::normalize_config_fields(source)?;
    let raw=meow_config::parse_raw_yaml(&normalized).map_err(|cause|cause.to_string())?;
    if let Some(geo)=raw.geodata.as_ref() {
        for (path,kind,format) in [(geo.mmdb_path.as_ref(),"mmdb","mmdb"),(geo.asn_path.as_ref(),"mmdb","mmdb"),(geo.geosite_path.as_ref(),"geosite","dat")] {
            if let Some(path)=path {
                let file=Path::new(path);let canonical=file.canonicalize().map_err(|cause|cause.to_string())?;
                if file!=canonical.as_path() || !canonical.starts_with(roots.resource_read_root.as_ref().unwrap()) {return Err("$.geodata: path outside prepared resource root".into());}
                crate::config_validation::validate_resource(kind,&canonical,"",format).await?;
            }
        }
    }
    let scope=OwnedLoadScope::with_context(Arc::new(PreparedContext(roots)));
    meow_config::validate_prepared_config_with_scope(&normalized,&scope).await
}
