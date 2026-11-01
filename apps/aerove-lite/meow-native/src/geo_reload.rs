//! Private owned Geo transaction. Source paths and effective sealed snapshots are distinct.
use crate::runtime_plan::HostBinding;
use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, fs::File, io::{self, Read, Write, Seek}, path::{Path,PathBuf}, sync::Arc};
use tokio_util::sync::CancellationToken;

#[derive(Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
pub(crate) struct Expected {
    pub core_epoch:String, pub route_version:String, pub selection_intent:String, pub host_binding:HostBinding,
}
#[derive(Clone, Deserialize, Serialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all="lowercase")]
pub(crate) enum Kind { Geoip, Asn, Geosite }
#[derive(Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
pub(crate) struct Asset {
    pub kind:Kind, pub relative_path:String, pub bytes:String, pub sha256:String,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
pub(crate) struct Candidate {
    pub compiled_yaml:String, pub compiled_sha256:String, pub engine_yaml:String,
    pub read_root:String, pub resource_lease_id:String, pub assets:Vec<Asset>,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
pub(crate) struct Prepare {
    pub schema_version:u8, pub transaction_id:String, pub operation_serial:String,
    pub expected:Expected, pub candidate:Candidate,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
pub(crate) struct Token {
    pub schema_version:u8, pub transaction_id:String, pub operation_serial:String,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
pub(crate) struct Commit { pub token:Token, pub expected:Expected }
fn bad(code:&'static str)->io::Error {io::Error::new(io::ErrorKind::InvalidInput,code)}
pub(crate) fn decimal(value:&str, positive:bool)->io::Result<u64> {
    value.parse::<u64>().ok().filter(|n|(!positive || *n>0) && n.to_string()==value).ok_or_else(||bad("geo.invalidInteger"))
}
fn hash_valid(value:&str)->bool {value.len()==64 && value.bytes().all(|c|c.is_ascii_digit() || (b'a'..=b'f').contains(&c))}
pub(crate) fn sha256(bytes:&[u8])->String {
    let mut hash=meow_transport::tls::ResourceSha256::new();hash.update(bytes);
    hash.finish().iter().map(|b|format!("{b:02x}")).collect()
}
impl Token {
    pub fn validate(&self)->io::Result<u64> {
        if self.schema_version!=1 || uuid::Uuid::parse_str(&self.transaction_id).ok().is_none_or(|id|id.is_nil() || id.to_string()!=self.transaction_id) {return Err(bad("geo.invalidToken"));}
        decimal(&self.operation_serial,true)
    }
}
impl Prepare {
    pub fn token(&self)->Token {Token{schema_version:self.schema_version,transaction_id:self.transaction_id.clone(),operation_serial:self.operation_serial.clone()}}
    pub fn validate(&self)->io::Result<()> {
        self.token().validate()?;self.expected.host_binding.validate()?;
        decimal(&self.expected.core_epoch,true)?;decimal(&self.expected.route_version,false)?;decimal(&self.expected.selection_intent,false)?;
        if self.candidate.compiled_yaml.len()>crate::config_validation::MAX_TEXT_BYTES || self.candidate.engine_yaml.len()>crate::config_validation::MAX_TEXT_BYTES
            || !hash_valid(&self.candidate.compiled_sha256) || sha256(self.candidate.compiled_yaml.as_bytes())!=self.candidate.compiled_sha256
            || uuid::Uuid::parse_str(&self.candidate.resource_lease_id).ok().is_none_or(|id|id.is_nil() || id.to_string()!=self.candidate.resource_lease_id)
            || Path::new(&self.candidate.read_root).file_name().and_then(|value|value.to_str())!=Some(self.candidate.resource_lease_id.as_str())
            || self.candidate.assets.len()>3 {return Err(bad("geo.invalidCandidate"));}
        let mut kinds=std::collections::BTreeSet::new();let mut paths=std::collections::BTreeSet::new();
        for asset in &self.candidate.assets {
            if !kinds.insert(asset.kind.clone()) || !paths.insert(asset.relative_path.clone()) || !hash_valid(&asset.sha256)
                || decimal(&asset.bytes,true)?>crate::config_validation::MAX_INPUT_BYTES as u64 {return Err(bad("geo.invalidAsset"));}
            relative(&asset.relative_path)?;
        }
        Ok(())
    }
}
fn relative(value:&str)->io::Result<()> {
    if value.is_empty() || value.len()>16384 || value.contains(['\0','\\',':']) || value.starts_with('/')
        || value.split('/').any(|part|part.is_empty() || part=="." || part=="..") {return Err(bad("geo.invalidRelativePath"));}Ok(())
}
#[cfg(unix)]
fn open_dir(path:&Path)->io::Result<File> {
    use std::{ffi::CString,os::{fd::{AsRawFd,FromRawFd},unix::ffi::OsStrExt},path::Component};
    if !path.is_absolute(){return Err(bad("geo.invalidPath"));}
    let root=CString::new("/").unwrap();
    let fd=unsafe{libc::open(root.as_ptr(),libc::O_RDONLY|libc::O_DIRECTORY|libc::O_NOFOLLOW|libc::O_CLOEXEC)};
    if fd<0{return Err(io::Error::last_os_error());}let mut parent=unsafe{File::from_raw_fd(fd)};
    for component in path.components() {
        let part=match component{Component::RootDir=>continue,Component::Normal(part)=>part,_=>return Err(bad("geo.invalidPath"))};
        let name=CString::new(part.as_bytes()).map_err(|_|bad("geo.invalidPath"))?;
        let fd=unsafe{libc::openat(parent.as_raw_fd(),name.as_ptr(),libc::O_RDONLY|libc::O_DIRECTORY|libc::O_NOFOLLOW|libc::O_CLOEXEC)};
        if fd<0{return Err(io::Error::last_os_error());}parent=unsafe{File::from_raw_fd(fd)};
    }Ok(parent)
}
#[cfg(unix)]
fn open_at(root:&File,relative_path:&str)->io::Result<File> {
    use std::{ffi::CString,os::fd::{AsRawFd,FromRawFd}};
    relative(relative_path)?;let parts:Vec<_>=relative_path.split('/').collect();let mut parent=root.try_clone()?;
    for (index,part) in parts.iter().enumerate() {
        let name=CString::new(*part).map_err(|_|bad("geo.invalidPath"))?;
        let directory=index+1<parts.len();
        #[cfg(all(test,any(target_os="linux",target_os="android")))]
        if !directory{fifo_regression::before_leaf_open(root,relative_path);}
        // A replaced FIFO must not wait for a writer before fstat can reject it.
        let fd=unsafe{libc::openat(parent.as_raw_fd(),name.as_ptr(),libc::O_RDONLY|libc::O_NOFOLLOW|libc::O_CLOEXEC|if directory{libc::O_DIRECTORY}else{libc::O_NONBLOCK})};
        if fd<0{return Err(io::Error::last_os_error());}parent=unsafe{File::from_raw_fd(fd)};
        if !directory{identity(&parent)?;}
    }
    Ok(parent)
}
#[derive(Clone, PartialEq, Eq)]
struct FileIdentity {dev:u64,ino:u64,len:u64,mtime:i64,mtime_ns:i64,ctime:i64,ctime_ns:i64}
#[cfg(unix)]
fn identity(file:&File)->io::Result<FileIdentity> {
    use std::os::unix::fs::MetadataExt;
    let meta=file.metadata()?;
    if !meta.is_file() || meta.nlink()!=1 {return Err(bad("geo.notPrivateRegularFile"));}
    Ok(FileIdentity{dev:meta.dev(),ino:meta.ino(),len:meta.len(),mtime:meta.mtime(),mtime_ns:meta.mtime_nsec(),ctime:meta.ctime(),ctime_ns:meta.ctime_nsec()})
}
pub(crate) struct SealedAsset {pub record:Asset,pub snapshot:Arc<File>,source:File,identity:FileIdentity}
pub(crate) struct Snapshots {pub root:PathBuf,root_fd:File,pub assets:BTreeMap<Kind,SealedAsset>}
impl Snapshots {
    /// No namespace guessing: authority is captured only by the original trusted start plan.
    pub fn capture(authority:&Path, read_root:&str, assets:&[Asset], cancel:&CancellationToken)->io::Result<Arc<Self>> {
        let root=PathBuf::from(read_root);
        if root.parent()!=Some(authority) || root.file_name().and_then(|name|name.to_str()).is_none_or(|name|uuid::Uuid::parse_str(name).ok().is_none_or(|id|id.to_string()!=name))
            || root.canonicalize()?!=root || authority.canonicalize()?!=authority {return Err(bad("geo.unauthorizedReadRoot"));}
        let root_fd=open_dir(&root)?;let mut captured=BTreeMap::new();
        for record in assets {
            if cancel.is_cancelled(){return Err(io::Error::new(io::ErrorKind::Interrupted,"geo.cancelled"));}
            let mut source=open_at(&root_fd,&record.relative_path)?;let before=identity(&source)?;
            if before.len!=decimal(&record.bytes,true)? {return Err(bad("geo.sourceSizeChanged"));}
            let snapshot=sealed_copy(&mut source,record,cancel)?;
            validate_snapshot(&snapshot,&record.kind,cancel)?;
            if identity(&source)?!=before{return Err(bad("geo.sourceChanged"));}
            captured.insert(record.kind.clone(),SealedAsset{record:record.clone(),snapshot:Arc::new(snapshot),source,identity:before});
        }
        let result=Arc::new(Self{root,root_fd,assets:captured});result.verify_source()?;Ok(result)
    }
    pub fn verify_source(&self)->io::Result<()> {
        if self.root.canonicalize()?!=self.root{return Err(bad("geo.sourceRootChanged"));}
        use std::os::unix::fs::MetadataExt;
        let current=open_dir(&self.root)?;let before=self.root_fd.metadata()?;let after=current.metadata()?;
        if before.dev()!=after.dev() || before.ino()!=after.ino(){return Err(bad("geo.sourceRootChanged"));}
        for asset in self.assets.values() {
            if identity(&asset.source)?!=asset.identity || identity(&open_at(&self.root_fd,&asset.record.relative_path)?)?!=asset.identity {return Err(bad("geo.sourceChanged"));}
            verify_seals(&asset.snapshot)?;
        }Ok(())
    }
}
#[cfg(any(target_os="linux",target_os="android"))]
fn verify_seals(file:&File)->io::Result<()> {
    use std::os::fd::AsRawFd;
    let required=libc::F_SEAL_WRITE|libc::F_SEAL_SHRINK|libc::F_SEAL_GROW|libc::F_SEAL_SEAL;
    let actual=unsafe{libc::fcntl(file.as_raw_fd(),libc::F_GET_SEALS)};
    if actual<0 || actual&required!=required{return Err(bad("geo.snapshotNotSealed"));}Ok(())
}
#[cfg(any(target_os="linux",target_os="android"))]
fn sealed_copy(source:&mut File,record:&Asset,cancel:&CancellationToken)->io::Result<File> {
    use std::{ffi::CString,os::fd::{AsRawFd,FromRawFd}};
    let name=CString::new("aerove-owned-geo").unwrap();
    let fd=unsafe{libc::memfd_create(name.as_ptr(),libc::MFD_CLOEXEC|libc::MFD_ALLOW_SEALING)};
    if fd<0{return Err(io::Error::last_os_error());}let mut output=unsafe{File::from_raw_fd(fd)};
    let mut hash=meow_transport::tls::ResourceSha256::new();let mut total=0u64;let mut buffer=[0u8;65536];
    loop {
        if cancel.is_cancelled(){return Err(io::Error::new(io::ErrorKind::Interrupted,"geo.cancelled"));}
        let count=match source.read(&mut buffer){Err(error) if error.kind()==io::ErrorKind::Interrupted=>continue,other=>other?};
        if count==0{break;}total=total.checked_add(count as u64).ok_or_else(||bad("geo.inputLimit"))?;
        if total>crate::config_validation::MAX_INPUT_BYTES as u64 || total>decimal(&record.bytes,true)?{return Err(bad("geo.inputLimit"));}
        hash.update(&buffer[..count]);output.write_all(&buffer[..count])?;
    }
    let actual:String=hash.finish().iter().map(|b|format!("{b:02x}")).collect();
    if total!=decimal(&record.bytes,true)? || actual!=record.sha256 {return Err(bad("geo.sourceHashMismatch"));}
    output.rewind()?;
    let seals=libc::F_SEAL_WRITE|libc::F_SEAL_SHRINK|libc::F_SEAL_GROW|libc::F_SEAL_SEAL;
    if unsafe{libc::fcntl(output.as_raw_fd(),libc::F_ADD_SEALS,seals)}!=0{return Err(io::Error::last_os_error());}
    verify_seals(&output)?;Ok(output)
}
#[cfg(not(any(target_os="linux",target_os="android")))]
fn sealed_copy(_source:&mut File,_record:&Asset,_cancel:&CancellationToken)->io::Result<File>{Err(io::Error::new(io::ErrorKind::Unsupported,"geo.sealedSnapshotUnavailable"))}
#[cfg(not(any(target_os="linux",target_os="android")))]
fn verify_seals(_file:&File)->io::Result<()>{Err(io::Error::new(io::ErrorKind::Unsupported,"geo.sealedSnapshotUnavailable"))}


// A candidate substitutes unpublished rule/Geo capabilities; session roots stay shared.
struct GeoContext {
    network:crate::network_context::NetworkContext,
    stage:Option<Arc<crate::providers::GeoProviderStage>>,
    _sources:Option<Arc<meow_config::SealedGeoSources>>,
    _dns_activation:Option<Arc<DnsActivation>>,
}
impl meow_config::owned_load::LoadContext for GeoContext {
    fn disabled_dns_source(&self)->Result<meow_config::owned_load::DisabledDnsSource,&'static str>{meow_config::owned_load::LoadContext::disabled_dns_source(&self.network)}
    fn requires_dns_transport_scope(&self)->bool{meow_config::owned_load::LoadContext::requires_dns_transport_scope(&self.network)}
    fn dns_transport_scope(&self)->Option<meow_common::transport_tasks::TransportScope>{meow_config::owned_load::LoadContext::dns_transport_scope(&self.network)}
    fn offline_validation(&self)->bool{meow_config::owned_load::LoadContext::offline_validation(&self.network)}
    fn requires_provider_roots(&self)->bool{true}
    fn provider_roots(&self)->Option<meow_config::owned_load::ProviderRoots>{meow_config::owned_load::LoadContext::provider_roots(&self.network)}
    fn provider_observer(&self)->Option<Arc<dyn meow_config::provider_events::ProviderObserver>>{
        self.stage.as_ref().map(|stage|stage.observer()).or_else(||meow_config::owned_load::LoadContext::provider_observer(&self.network))
    }
    fn upstream_rule_admission(&self)->Option<Arc<dyn meow_config::owned_load::UpstreamRuleAdmission>>{meow_config::owned_load::LoadContext::upstream_rule_admission(&self.network)}
    fn requires_physical_dns(&self)->bool{true}
    fn physical_dns_servers(&self)->Option<Vec<std::net::SocketAddr>>{self.network.physical_dns_snapshot()}
    fn wrap(&self,work:meow_config::owned_load::OwnedWork)->meow_config::owned_load::OwnedWork{meow_config::owned_load::LoadContext::wrap(&self.network,work)}
    fn run_blocking(&self,work:Box<dyn FnOnce()+Send>){meow_config::owned_load::LoadContext::run_blocking(&self.network,work)}
}
struct DnsActivation {
    published:std::sync::atomic::AtomicBool,
    scope:std::sync::OnceLock<std::sync::Weak<meow_config::owned_load::OwnedLoadScope>>,
}
impl DnsActivation {
    fn admit(&self)->io::Result<Arc<meow_config::owned_load::OwnedLoadScope>>{
        let scope=self.scope.get().and_then(std::sync::Weak::upgrade).filter(|scope|!scope.is_cancelled()).ok_or_else(||bad("geo.dnsScopeRetired"))?;
        if !self.published.load(std::sync::atomic::Ordering::Acquire){return Err(bad("geo.dnsNotPublished"));}Ok(scope)
    }
}
struct GeoDnsTransport{base:meow_common::transport_tasks::TransportScope,activation:std::sync::Weak<DnsActivation>}
impl meow_common::transport_tasks::TransportTaskContext for GeoDnsTransport {
    fn wrap(&self,work:meow_common::transport_tasks::Work)->meow_common::transport_tasks::Work{
        let activation=self.activation.clone();let base=self.base.clone();
        Box::pin(async move{let Some(activation)=activation.upgrade() else{return;};let Ok(scope)=activation.admit() else{return;};
            let _=scope.run_captured(base.wrap(work)).await;})
    }
    fn spawn(&self,work:meow_common::transport_tasks::Work)->io::Result<tokio::task::AbortHandle>{self.spawn_join(work).map(|task|task.abort_handle())}
    fn spawn_join(&self,work:meow_common::transport_tasks::Work)->io::Result<Arc<meow_transport::transport_tasks::SharedUnitJoin>>{
        let activation=self.activation.upgrade().ok_or_else(||bad("geo.dnsScopeRetired"))?;let scope=Arc::downgrade(&activation.admit()?);
        // The physical owner still owns this task/join. The existing load lease
        // exists only while its work is actually polled, and close cancels it.
        self.base.spawn_join(Box::pin(async move{let Some(scope)=scope.upgrade() else{return;};let _=scope.run_captured(work).await;}))
    }
}
fn dns_scope(network:crate::network_context::NetworkContext,sources:Option<Arc<meow_config::SealedGeoSources>>,published:bool)
    ->(Arc<meow_config::owned_load::OwnedLoadScope>,Option<meow_common::transport_tasks::TransportScope>,Arc<DnsActivation>){
    use meow_config::owned_load::LoadContext;
    let base=network.dns_transport_scope();let activation=Arc::new(DnsActivation{published:std::sync::atomic::AtomicBool::new(published),scope:std::sync::OnceLock::new()});
    let transport=base.map(|base|meow_common::transport_tasks::TransportScope::new(Arc::new(GeoDnsTransport{base,activation:Arc::downgrade(&activation)})));
    let scope=meow_config::owned_load::OwnedLoadScope::with_context(Arc::new(GeoContext{network,stage:None,_sources:sources,_dns_activation:Some(activation.clone())}));
    activation.scope.set(Arc::downgrade(&scope)).ok().expect("fresh DNS scope");(scope,transport,activation)
}
/// Retire initial DNS consumers independently of the session's provider/health scope.
pub(crate) async fn rehome_initial_dns(pair:crate::network_context::ResolverPair,network:crate::network_context::NetworkContext)
    ->io::Result<(crate::network_context::ResolverPair,Arc<meow_config::owned_load::OwnedLoadScope>)>{
    let(scope,transport,_activation)=dns_scope(network,None,true);let transport=transport.ok_or_else(||bad("dnsTransportOwnerMissing"))?;
    let pair=transport.scope(async move{crate::network_context::ResolverPair{main:Arc::new(pair.main.owned_same_sources_generation()),proxy:pair.proxy.map(|resolver|Arc::new(resolver.owned_same_sources_generation()))}}).await;
    Ok((pair,scope))
}
pub(crate) fn dns_build(inputs:Arc<meow_config::GeoReloadInputs>,network:crate::network_context::NetworkContext,prior:Arc<meow_dns::Resolver>)->crate::network_policy::DnsBuild {
    dns_build_internal(inputs,network,prior,true,None).0
}
fn dns_build_internal(inputs:Arc<meow_config::GeoReloadInputs>,network:crate::network_context::NetworkContext,prior:Arc<meow_dns::Resolver>,published:bool,previous:Option<(meow_config::raw::RawConfig,Option<Arc<meow_dns::Resolver>>)>)
    ->(crate::network_policy::DnsBuild,Arc<DnsActivation>){
    let (scope,transport,activation)=dns_scope(network,inputs.sources.clone(),published);
    (crate::network_policy::DnsBuild {scope,work:Box::pin(async move{
        let transport=transport.ok_or("dnsTransportOwnerMissing")?;
        let work=async{match previous{Some((raw,proxy))=>inputs.prepare_dns(&prior,proxy,&raw).await,None=>inputs.build_dns(&prior).await}};
        let dns=meow_config::owned_load::with_dns_transport_scope(transport,work).await.map_err(|cause|format!("geo.dnsBuild:{cause:#}"))?;
        Ok(crate::network_context::ResolverPair{main:dns.resolver,proxy:dns.proxy_resolver})
    })},activation)
}

#[derive(Clone,Serialize)]
#[serde(rename_all="camelCase")]
pub(crate) struct Stamp {pub core_epoch:String,pub route_version:String,pub selection_intent:String}
impl Stamp {fn capture(gate:&crate::providers::PlaneState)->Self{Self{core_epoch:gate.core_epoch.to_string(),route_version:gate.revision.to_string(),selection_intent:gate.selection_serial.to_string()}}}
#[derive(Clone,Serialize)]
#[serde(rename_all="camelCase")]
pub(crate) struct Applied {
    pub token:Token,pub before:Stamp,pub after:Stamp,pub read_root:String,pub resource_lease_id:String,
    pub compiled_sha256:String,pub engine_sha256:String,pub actual_config_sha256:String,
    pub source_assets:Vec<Asset>,pub effective_storage:&'static str,pub sealed_snapshot:bool,
}
#[derive(Clone,Serialize)]
#[serde(rename_all="camelCase")]
pub(crate) struct Status {
    pub token:Token,pub state:&'static str,pub error:Option<String>,pub source_reads_joined:bool,
    pub applied:Option<Applied>,pub current:Stamp,
}
struct Prepared {
    published:bool,
    snapshots:Arc<Snapshots>, stage:Arc<crate::providers::GeoProviderStage>,
    rules:Option<meow_tunnel::tunnel::PreparedRulesUpdate>,
    rules_scope:Arc<meow_config::owned_load::OwnedLoadScope>,dns_scope:Arc<meow_config::owned_load::OwnedLoadScope>,
    pair:crate::network_context::ResolverPair,dns_activation:Arc<DnsActivation>,inputs:Arc<meow_config::GeoReloadInputs>,
    raw_before:String,raw:Option<meow_config::raw::RawConfig>,before:Stamp,
}
impl Drop for Prepared {fn drop(&mut self){if !self.published{self.stage.abort();self.rules_scope.cancel();self.dns_scope.cancel();}}}
impl Prepared {async fn close(self)->io::Result<()> {self.stage.abort();self.rules_scope.cancel();self.dns_scope.cancel();
    let rules=self.rules_scope.close().await.map_err(io::Error::other);let dns=self.dns_scope.close().await.map_err(io::Error::other);rules.and(dns)}}
struct Entry {request:Prepare,fingerprint:String,cancel:CancellationToken,state:&'static str,error:Option<String>,source_reads_joined:bool,prepared:Option<Prepared>}
struct Ledger {binding:Option<HostBinding>,bound_raw:Option<BTreeMap<String,String>>,bound_witness:Option<(String,String)>,engine_yaml:String,engine_sha256:String,latest:u64,entry:Option<Entry>,last:Option<Applied>,retired:Vec<Prepared>}
pub(crate) struct Transactions {
    api:Arc<crate::api::CoreApi>,network:crate::network_context::NetworkContext,
    policy:Arc<crate::network_policy::NetworkPolicyOwner>,cancel:CancellationToken,
    authority:Option<PathBuf>,ledger:parking_lot::Mutex<Ledger>,
    pub inputs:Arc<parking_lot::RwLock<Arc<meow_config::GeoReloadInputs>>>,
    current_rules_scope:parking_lot::Mutex<Option<Arc<meow_config::owned_load::OwnedLoadScope>>>,
    dns_enabled:bool,
    preparing_scopes:parking_lot::Mutex<Vec<Arc<meow_config::owned_load::OwnedLoadScope>>>,
    #[cfg(test)] barrier:parking_lot::Mutex<Option<Barrier>>,
}
fn raw_hash(raw:&meow_config::raw::RawConfig)->io::Result<String>{serde_json::to_vec(raw).map(|bytes|sha256(&bytes)).map_err(io::Error::other)}
fn same_token(a:&Token,b:&Token)->bool{a.schema_version==b.schema_version && a.transaction_id==b.transaction_id && a.operation_serial==b.operation_serial}
impl Transactions {
    pub(crate) fn new(api:Arc<crate::api::CoreApi>,network:crate::network_context::NetworkContext,policy:Arc<crate::network_policy::NetworkPolicyOwner>,
        plan:&crate::runtime_plan::RuntimePlan,inputs:Arc<parking_lot::RwLock<Arc<meow_config::GeoReloadInputs>>>,cancel:CancellationToken,dns_enabled:bool)->io::Result<Arc<Self>> {
        let authority=if let (Some(_),Some(authority),Some(root))=(&plan.host_binding,&plan.geo_read_root_authority,&plan.resource_read_root) {
            let authority=PathBuf::from(authority);let root=PathBuf::from(root);
            if authority.canonicalize()?!=authority || root.canonicalize()?!=root || root.parent()!=Some(authority.as_path()) ||
                root.file_name().and_then(|name|name.to_str()).is_none_or(|name|uuid::Uuid::parse_str(name).ok().is_none_or(|id|id.to_string()!=name)) {return Err(bad("geo.invalidInitialAuthority"));}
            let _authority_fd=open_dir(&authority)?;let _root_fd=open_dir(&root)?;Some(authority)
        }else{None};
        Ok(Arc::new(Self{api,network,policy,cancel,authority,ledger:parking_lot::Mutex::new(Ledger{binding:plan.host_binding.clone(),bound_raw:plan.host_raw_selected_map.clone(),bound_witness:None,engine_yaml:plan.engine_yaml.clone(),engine_sha256:sha256(plan.engine_yaml.as_bytes()),latest:0,entry:None,last:None,retired:Vec::new()}),inputs,current_rules_scope:parking_lot::Mutex::new(None),dns_enabled,preparing_scopes:parking_lot::Mutex::new(Vec::new()),#[cfg(test)] barrier:parking_lot::Mutex::new(None)}))
    }
    fn check_expected(&self,gate:&crate::providers::PlaneState,ledger:&Ledger,expected:&Expected)->io::Result<()> {
        if gate.closed || self.cancel.is_cancelled(){return Err(bad("geo.cancelled"));}
        if self.authority.is_none() || ledger.binding.is_none(){return Err(bad("geo.hostBindingMissing"));}
        if (gate.core_epoch.to_string(),gate.revision.to_string(),gate.selection_serial.to_string())!=(expected.core_epoch.clone(),expected.route_version.clone(),expected.selection_intent.clone()) || ledger.binding.as_ref()!=Some(&expected.host_binding) {return Err(bad("geo.staleOwner"));}
        Ok(())
    }
    fn check_bound_selection(&self,gate:&crate::providers::PlaneState,ledger:&Ledger)->io::Result<()> {
        let raw=ledger.bound_raw.as_ref().ok_or_else(||bad("geo.hostRawSelectedMapMissing"))?;
        let witness=self.api.control.selection_witness_locked(gate).map_err(|_|bad("geo.selectionNotApplied"))?;
        if !witness.complete || crate::control::RouteControl::normalize_raw(raw,&witness.groups)!=witness.normalized_raw_requested {return Err(bad("geo.bindingSelectionChanged"));}
        match &ledger.bound_witness {
            Some((id,graph)) if id==&witness.witness_id && graph==&witness.graph_version=>Ok(()),
            // The trusted startup raw map can bind only the first real full
            // startup commit. Any later explicit mutation, even same->same,
            // requires the existing JNI metadata owner to attest its witness.
            None if witness.initial_startup_witness_id.as_ref()==Some(&witness.witness_id)=>Ok(()),
            _=>Err(bad("geo.bindingWitnessChanged")),
        }
    }
    fn status_locked(&self,gate:&crate::providers::PlaneState,ledger:&Ledger,token:&Token)->io::Result<Status> {
        if gate.closed || self.cancel.is_cancelled(){return Err(bad("geo.cancelled"));}
        if let Some(applied)=ledger.last.as_ref().filter(|value|same_token(&value.token,token)) {
            return Ok(Status{token:token.clone(),state:"committed",error:ledger.entry.as_ref().filter(|entry|same_token(&entry.request.token(),token)).and_then(|entry|entry.error.clone()),source_reads_joined:true,applied:Some(applied.clone()),current:Stamp::capture(gate)});
        }
        let entry=ledger.entry.as_ref().filter(|entry|same_token(&entry.request.token(),token)).ok_or_else(||bad("geo.unknownToken"))?;
        Ok(Status{token:token.clone(),state:entry.state,error:entry.error.clone(),source_reads_joined:entry.source_reads_joined,applied:None,current:Stamp::capture(gate)})
    }
    pub(crate) fn status(&self,token:&Token)->io::Result<Status>{token.validate()?;let gate=self.api.control.plane.gate.lock();self.status_locked(&gate,&self.ledger.lock(),token)}
    // Called before enqueue so a newer Host intent cancels a preparing request
    // even while the existing command lane is awaiting its real worker join.
    pub(crate) fn admit(&self,request:&Prepare)->io::Result<bool> {
        request.validate()?;let fingerprint=sha256(&serde_json::to_vec(request).map_err(io::Error::other)?);let serial=decimal(&request.operation_serial,true)?;
        let gate=self.api.control.plane.gate.lock();let mut ledger=self.ledger.lock();
        if let Some(entry)=ledger.entry.as_ref().filter(|entry|same_token(&entry.request.token(),&request.token())) {
            if entry.fingerprint!=fingerprint{return Err(bad("geo.tokenPayloadChanged"));}return Ok(false);
        }
        self.check_expected(&gate,&ledger,&request.expected)?;self.check_bound_selection(&gate,&ledger)?;
        if serial<=ledger.latest{return Err(bad("geo.staleOperation"));}
        if let Some(mut entry)=ledger.entry.take(){entry.cancel.cancel();if let Some(prepared)=entry.prepared.take(){ledger.retired.push(prepared);}}
        ledger.latest=serial;ledger.entry=Some(Entry{request:request.clone(),fingerprint,cancel:CancellationToken::new(),state:"preparing",error:None,source_reads_joined:false,prepared:None});Ok(true)
    }
    fn track_scope(&self,scope:&Arc<meow_config::owned_load::OwnedLoadScope>){let mut scopes=self.preparing_scopes.lock();if !scopes.iter().any(|old|Arc::ptr_eq(old,scope)){scopes.push(scope.clone());}}
    fn untrack_scope(&self,scope:&Arc<meow_config::owned_load::OwnedLoadScope>){let retired={let mut scopes=self.preparing_scopes.lock();scopes.iter().position(|old|Arc::ptr_eq(old,scope)).map(|i|scopes.swap_remove(i))};drop(retired);}
    async fn drain_preparing(&self)->io::Result<()> {let scopes=std::mem::take(&mut *self.preparing_scopes.lock());let mut error=None;for scope in scopes{if let Err(cause)=scope.close().await{error=Some(io::Error::other(cause));}}error.map_or(Ok(()),Err)}
    async fn discard(&self,prepared:Prepared)->io::Result<()> {let rules=prepared.rules_scope.clone();let dns=prepared.dns_scope.clone();self.track_scope(&rules);self.track_scope(&dns);let result=prepared.close().await;if result.is_ok(){self.untrack_scope(&rules);self.untrack_scope(&dns);}result}
    async fn drain_retired(&self)->io::Result<()> {
        let retired={let _gate=self.api.control.plane.gate.lock();std::mem::take(&mut self.ledger.lock().retired)};
        let mut failure=None;for prepared in retired {if let Err(cause)=self.discard(prepared).await{failure=Some(cause);}}
        failure.map_or(Ok(()),Err)
    }
    pub(crate) async fn prepare(&self,request:Prepare,http_cancel:CancellationToken)->io::Result<Status> {
        self.drain_retired().await?;let token=request.token();
        let operation={let gate=self.api.control.plane.gate.lock();let ledger=self.ledger.lock();self.check_expected(&gate,&ledger,&request.expected)?;self.check_bound_selection(&gate,&ledger)?;
            let entry=ledger.entry.as_ref().filter(|entry|same_token(&entry.request.token(),&token)).ok_or_else(||bad("geo.superseded"))?;entry.cancel.clone()};
        let result=self.build(&request,&operation,&http_cancel).await;
        match result {
            Ok(prepared)=>{
                #[cfg(test)] self.boundary(Boundary::Prepared).await;
                let rules_scope=prepared.rules_scope.clone();let dns_scope=prepared.dns_scope.clone();
                let failed={let gate=self.api.control.plane.gate.lock();let mut ledger=self.ledger.lock();
                    if self.check_expected(&gate,&ledger,&request.expected).is_err() || self.check_bound_selection(&gate,&ledger).is_err() || operation.is_cancelled() || http_cancel.is_cancelled() ||
                        !ledger.entry.as_ref().is_some_and(|entry|same_token(&entry.request.token(),&token)) {Some(prepared)}
                    else{let entry=ledger.entry.as_mut().unwrap();entry.prepared=Some(prepared);entry.state="prepared";None}};
                self.untrack_scope(&rules_scope);self.untrack_scope(&dns_scope);
                if let Some(prepared)=failed{self.discard(prepared).await?;self.fail_entry(&token,"geo.superseded",true);return Err(bad("geo.superseded"));}
                self.status(&token)
            },
            Err(cause)=>{let joined=self.drain_preparing().await;self.fail_entry(&token,&cause.to_string(),joined.is_ok());joined?;Err(cause)},
        }
    }
    fn fail_entry(&self,token:&Token,error:&str,joined:bool){let _gate=self.api.control.plane.gate.lock();let mut ledger=self.ledger.lock();if let Some(entry)=ledger.entry.as_mut().filter(|entry|same_token(&entry.request.token(),token)){entry.state="failed";entry.error=Some(error.to_owned());entry.source_reads_joined=joined;}}
    async fn build(&self,request:&Prepare,operation:&CancellationToken,http_cancel:&CancellationToken)->io::Result<Prepared> {
        let (raw,before,engine)={let gate=self.api.control.plane.gate.lock();let ledger=self.ledger.lock();self.check_expected(&gate,&ledger,&request.expected)?;self.check_bound_selection(&gate,&ledger)?;
            let raw=self.api.state.raw_config.read().clone();(raw,Stamp::capture(&gate),ledger.engine_yaml.clone())};
        let raw_before=raw_hash(&raw)?;
        let dns_previous_raw=raw.clone();let candidate=geo_only_raw(&engine,&request.candidate,raw)?;
        let authority=self.authority.clone().ok_or_else(||bad("geo.hostBindingMissing"))?;
        let source_scope=meow_config::owned_load::OwnedLoadScope::with_context(Arc::new(self.network.clone()));
        self.track_scope(&source_scope);
        let root=request.candidate.read_root.clone();let assets=request.candidate.assets.clone();let stop=operation.clone();
        let worker=source_scope.spawn_blocking_owned(move||Snapshots::capture(&authority,&root,&assets,&stop)).map_err(io::Error::other)?;
        let snapshots=tokio::select!{biased;
            _=self.cancel.cancelled()=>Err(bad("geo.cancelled")),_ =operation.cancelled()=>Err(bad("geo.superseded")),_=http_cancel.cancelled()=>Err(bad("geo.cancelled")),
            value=worker=>value.map_err(io::Error::other).and_then(|value|value),
        };
        if snapshots.is_err(){operation.cancel();}
        source_scope.close().await.map_err(io::Error::other)?;self.untrack_scope(&source_scope);
        let snapshots=snapshots?;
        let source=|kind:&Kind|snapshots.assets.get(kind).map(|asset|asset.snapshot.clone());
        let sources=meow_config::SealedGeoSources::new(source(&Kind::Geoip),source(&Kind::Asn),source(&Kind::Geosite)).map_err(io::Error::other)?;
        let stage=self.api.control.plane.stage_geo_providers().map_err(io::Error::other)?;
        let rules_scope=meow_config::owned_load::OwnedLoadScope::with_context(Arc::new(GeoContext{network:self.network.captured(),stage:Some(stage.clone()),_sources:Some(sources.clone()),_dns_activation:None}));
        self.track_scope(&rules_scope);
        let route=self.api.state.tunnel.route_snapshot();let proxies=route.proxies.as_ref().clone();let dialers=route.dialer_registry.clone();
        let work=meow_config::prepare_geo_reload(candidate.clone(),sources.clone(),proxies.clone(),dialers.clone(),None);
        let prepared=tokio::select!{biased;
            _=self.cancel.cancelled()=>Err(bad("geo.cancelled")),_=operation.cancelled()=>Err(bad("geo.superseded")),_=http_cancel.cancelled()=>Err(bad("geo.cancelled")),
            result=rules_scope.run(work)=>result.map_err(io::Error::other),
        };
        let prepared=match prepared{Ok(value)=>value,Err(cause)=>{stage.abort();rules_scope.close().await.map_err(io::Error::other)?;return Err(cause);}};
        let inputs=Arc::new(meow_config::GeoReloadInputs::new(candidate.clone(),proxies,dialers,prepared.rule_providers,Some(prepared.payloads),Some(sources)).with_geo_context(prepared.context));
        let context=self.network.captured();let prior=context.captured_resolver_pair().ok_or_else(||bad("geo.liveResolverMissing"))?;let (build,dns_activation)=dns_build_internal(inputs.clone(),context,prior.main,false,Some((dns_previous_raw,prior.proxy)));
        self.track_scope(&build.scope);
        let pair=tokio::select!{biased;
            _=self.cancel.cancelled()=>Err(bad("geo.cancelled")),_=operation.cancelled()=>Err(bad("geo.superseded")),_=http_cancel.cancelled()=>Err(bad("geo.cancelled")),
            result=build.scope.run(build.work)=>result.map_err(io::Error::other),
        };
        let pair=match pair{Ok(pair)=>pair,Err(cause)=>{stage.abort();rules_scope.close().await.map_err(io::Error::other)?;build.scope.close().await.map_err(io::Error::other)?;return Err(cause);}};
        if let Err(cause)=crate::network_policy::bind_resolver_observations(&self.api.state.tunnel,&pair){stage.abort();rules_scope.close().await.map_err(io::Error::other)?;build.scope.close().await.map_err(io::Error::other)?;return Err(cause);}
        Ok(Prepared{published:false,snapshots,stage,rules:Some(self.api.state.tunnel.prepare_rules_update(prepared.rules)),rules_scope,dns_scope:build.scope,pair,dns_activation,inputs,raw_before,raw:Some(candidate),before})
    }
}
fn geo_only_raw(previous_engine:&str,candidate:&Candidate,mut actual:meow_config::raw::RawConfig)->io::Result<meow_config::raw::RawConfig> {
    let before=meow_config::parse_raw_yaml(previous_engine).map_err(io::Error::other)?;
    let next=meow_config::parse_raw_yaml(&candidate.engine_yaml).map_err(io::Error::other)?;
    let mut before_value=serde_json::to_value(&before).map_err(io::Error::other)?;let mut next_value=serde_json::to_value(&next).map_err(io::Error::other)?;
    for value in [&mut before_value,&mut next_value] {if let Some(geo)=value.get_mut("geodata").and_then(serde_json::Value::as_object_mut){for key in ["mmdb-path","asn-path","geosite-path"]{geo.remove(key);}}}
    if before_value!=next_value{return Err(bad("geo.nonGeoEngineChanged"));}
    if candidate.assets.is_empty() {
        if [&before,&next].into_iter().any(|raw|raw.geodata.as_ref().is_some_and(|geo|geo.mmdb_path.is_some() || geo.asn_path.is_some() || geo.geosite_path.is_some())) {return Err(bad("geo.emptyAssetsExplicitPath"));}
        if next.geodata.as_ref().is_some_and(|geo|geo.auto_update){return Err(bad("geo.coreAutoUpdateDenied"));}
        actual.geodata=next.geodata;return Ok(actual);
    }
    let geo=next.geodata.as_ref().ok_or_else(||bad("geo.geodataMissing"))?;
    if geo.auto_update{return Err(bad("geo.coreAutoUpdateDenied"));}
    let root=Path::new(&candidate.read_root);let mut count=0;
    for (kind,path) in [(Kind::Geoip,&geo.mmdb_path),(Kind::Asn,&geo.asn_path),(Kind::Geosite,&geo.geosite_path)] {
        if let Some(path)=path {count+=1;let asset=candidate.assets.iter().find(|asset|asset.kind==kind).ok_or_else(||bad("geo.assetMissing"))?;
            if Path::new(path)!=root.join(&asset.relative_path){return Err(bad("geo.assetPathMismatch"));}}
    }
    if count!=candidate.assets.len(){return Err(bad("geo.unusedAsset"));}
    actual.geodata=next.geodata;Ok(actual)
}


#[derive(Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
pub(crate) struct Renew {
    pub expected:Expected,pub engine_sha256:String,pub selection_witness_id:String,
    pub raw_selected_map:BTreeMap<String,String>,pub new_source_guard:String,
}
#[derive(Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct RenewEnvelope {schema_version:u8,request_id:String,expected_core_epoch:String,operation:String,value:Renew}
pub(crate) fn parse_renew(json:&str)->io::Result<Option<(String,Renew)>> {
    let value:serde_json::Value=serde_json::from_str(json).map_err(|_|bad("geo.invalidRenew"))?;
    if value.get("operation").and_then(serde_json::Value::as_str)!=Some("renewGeoBinding"){return Ok(None);}
    let envelope:RenewEnvelope=serde_json::from_str(json).map_err(|_|bad("geo.invalidRenew"))?;
    if envelope.schema_version!=1 || envelope.operation!="renewGeoBinding" ||
        uuid::Uuid::parse_str(&envelope.request_id).ok().is_none_or(|id|id.is_nil() || id.to_string()!=envelope.request_id) ||
        envelope.expected_core_epoch!=envelope.value.expected.core_epoch {return Err(bad("geo.invalidRenew"));}
    Ok(Some((envelope.request_id,envelope.value)))
}
impl Transactions {
    pub(crate) fn request_abort(&self,token:&Token)->io::Result<()> {
        token.validate()?;let gate=self.api.control.plane.gate.lock();let mut ledger=self.ledger.lock();
        if ledger.last.as_ref().is_some_and(|value|same_token(&value.token,token)){return Ok(());}
        if gate.closed || self.cancel.is_cancelled(){return Err(bad("geo.cancelled"));}
        let entry=ledger.entry.as_mut().filter(|entry|same_token(&entry.request.token(),token)).ok_or_else(||bad("geo.unknownToken"))?;
        entry.cancel.cancel();if !matches!(entry.state,"failed"|"aborted"){entry.state="aborting";}Ok(())
    }
    pub(crate) async fn abort(&self,token:&Token)->io::Result<Status> {
        self.request_abort(token)?;let status=self.status(token)?;if status.state=="committed"{return Ok(status);}
        let prepared={let _gate=self.api.control.plane.gate.lock();let mut ledger=self.ledger.lock();ledger.entry.as_mut().filter(|entry|same_token(&entry.request.token(),token)).and_then(|entry|entry.prepared.take())};
        if let Some(prepared)=prepared{self.discard(prepared).await?;}
        self.drain_retired().await?;
        {let _gate=self.api.control.plane.gate.lock();let mut ledger=self.ledger.lock();if let Some(entry)=ledger.entry.as_mut().filter(|entry|same_token(&entry.request.token(),token)){entry.state="aborted";entry.source_reads_joined=true;}}
        self.status(token)
    }
    pub(crate) async fn commit(&self,request:Commit,http_cancel:CancellationToken,fence:&parking_lot::Mutex<()>)->io::Result<Status> {
        request.token.validate()?;
        // An already committed stable token is a read, even after its original
        // expected binding/stamp has advanced. It never executes a second load.
        {let gate=self.api.control.plane.gate.lock();let ledger=self.ledger.lock();
            if ledger.last.as_ref().is_some_and(|value|same_token(&value.token,&request.token)){return self.status_locked(&gate,&ledger,&request.token);}}
        let _dns_lane=self.policy.geo_update_guard().await;
        let _config=meow_api::routes::CONFIG_MUTATION.lock().await;
        let admission={let gate=self.api.control.plane.gate.lock();let mut ledger=self.ledger.lock();
            let validation=self.check_expected(&gate,&ledger,&request.expected).and_then(|()|self.check_bound_selection(&gate,&ledger)).and_then(|()|{
                let entry=ledger.entry.as_ref().filter(|entry|same_token(&entry.request.token(),&request.token)).ok_or_else(||bad("geo.unknownToken"))?;
                if entry.cancel.is_cancelled() || http_cancel.is_cancelled() || entry.state!="prepared"{return Err(bad("geo.notPrepared"));}Ok(())});
            match validation{Ok(())=>{let entry=ledger.entry.as_mut().unwrap();entry.state="committing";Ok(entry.prepared.take().expect("prepared state owns its candidate"))},
                Err(cause)=>{let retired=ledger.entry.as_mut().filter(|entry|same_token(&entry.request.token(),&request.token)).and_then(|entry|{entry.cancel.cancel();entry.state="failed";entry.error=Some(cause.to_string());entry.prepared.take()});Err((cause,retired))}}
        };
        let mut prepared=match admission{Ok(prepared)=>prepared,Err((cause,retired))=>{if let Some(prepared)=retired{self.discard(prepared).await?;self.fail_entry(&request.token,&cause.to_string(),true);}return Err(cause);}};
        self.track_scope(&prepared.rules_scope);self.track_scope(&prepared.dns_scope);
        if let Err(cause)=prepared.snapshots.verify_source(){self.discard(prepared).await?;self.fail_entry(&request.token,&cause.to_string(),true);return Err(cause);}
        // CONFIG_MUTATION excludes API raw-config writers; same-plane expected
        // stamps below additionally reject Native policy/choice/provider changes.
        let live_raw=self.api.state.raw_config.read().clone();
        let hashes=raw_hash(&live_raw).and_then(|live|raw_hash(prepared.raw.as_ref().unwrap()).map(|next|(live,next)));
        let (live_raw_hash,actual_config_hash)=match hashes{Ok(value)=>value,Err(cause)=>{self.discard(prepared).await?;self.fail_entry(&request.token,&cause.to_string(),true);return Err(cause);}};
        let engine_yaml={let _gate=self.api.control.plane.gate.lock();let ledger=self.ledger.lock();ledger.entry.as_ref().filter(|entry|same_token(&entry.request.token(),&request.token)).map(|entry|entry.request.candidate.engine_yaml.clone())};
        let engine_sha=engine_yaml.as_ref().map(|engine|sha256(engine.as_bytes()));
        let next_network=self.network.captured().with_resolver_pair(prepared.pair.clone());
        let committed=(||->io::Result<_>{
            let _http=fence.lock();let _intent=self.policy.geo_intent_guard();let mut gate=self.api.control.plane.gate.lock();let mut ledger=self.ledger.lock();
            self.check_expected(&gate,&ledger,&request.expected)?;self.check_bound_selection(&gate,&ledger)?;
            let entry=ledger.entry.as_ref().filter(|entry|same_token(&entry.request.token(),&request.token)).ok_or_else(||bad("geo.superseded"))?;
            if entry.cancel.is_cancelled() || http_cancel.is_cancelled() || live_raw_hash!=prepared.raw_before || !prepared.rules.as_ref().unwrap().is_current(&self.api.state.tunnel) {return Err(bad("geo.superseded"));}
            prepared.stage.validate_commit_locked(&gate).map_err(io::Error::other)?;
            let revision=gate.revision.checked_add(1).ok_or_else(||bad("geo.routeVersionExhausted"))?;
            // Every validation and all allocation/hash/parser work precedes the
            // publication point. No owner getter is called from this gate.
            let mut next_binding=ledger.binding.clone().ok_or_else(||bad("geo.hostBindingMissing"))?;
            next_binding.compiled_sha256=entry.request.candidate.compiled_sha256.clone();
            let applied=Applied{token:request.token.clone(),before:prepared.before.clone(),after:Stamp{core_epoch:gate.core_epoch.to_string(),route_version:revision.to_string(),selection_intent:gate.selection_serial.to_string()},
                read_root:entry.request.candidate.read_root.clone(),resource_lease_id:entry.request.candidate.resource_lease_id.clone(),
                compiled_sha256:entry.request.candidate.compiled_sha256.clone(),engine_sha256:engine_sha.clone().ok_or_else(||bad("geo.superseded"))?,actual_config_sha256:actual_config_hash,
                source_assets:entry.request.candidate.assets.clone(),effective_storage:if entry.request.candidate.assets.is_empty(){"none"}else{"owned-sealed-memfd"},sealed_snapshot:!entry.request.candidate.assets.is_empty()};
            // Route CAS cannot fail while this owner excludes every managed route
            // publisher; preserve its error as a rejection before any other swap.
            let old_route=match self.api.state.tunnel.commit_rules_update(prepared.rules.take().unwrap()) {Ok(route)=>route,Err(rules)=>{prepared.rules=Some(rules);return Err(bad("geo.routeChanged"));}};
            prepared.stage.install_locked(&mut gate);
            let old_providers=std::mem::replace(&mut *self.api.state.rule_providers.write(),prepared.inputs.rule_providers.clone());
            let old_raw=std::mem::replace(&mut *self.api.state.raw_config.write(),prepared.raw.take().unwrap());
            prepared.dns_activation.published.store(true,std::sync::atomic::Ordering::Release);
            meow_api::routes::install_owned_resolver_pair(&self.api.state.tunnel,&self.api.state.dns_server,prepared.pair.main.clone(),prepared.pair.proxy.clone(),self.dns_enabled);
            let old_dns=self.policy.install_geo_dns(&next_network,prepared.dns_scope.clone());
            let old_inputs=std::mem::replace(&mut *self.inputs.write(),prepared.inputs.clone());
            let old_rules_scope=self.current_rules_scope.lock().replace(prepared.rules_scope.clone());
            prepared.published=true;gate.revision=revision;ledger.binding=Some(next_binding);
            ledger.engine_yaml=engine_yaml.clone().unwrap();ledger.engine_sha256=engine_sha.clone().unwrap();
            ledger.last=Some(applied);ledger.entry.as_mut().unwrap().state="committed";
            Ok((old_route,old_providers,old_raw,old_dns,old_inputs,old_rules_scope))
        })();
        // Remaining cleanup must not turn a committed transaction into an
        // apparent pre-commit failure or authorize a blind client replay.
        match committed {
            Ok((old_route,old_providers,old_raw,(old_network,old_dns_scope),old_inputs,old_rules_scope))=>{
                self.untrack_scope(&prepared.rules_scope);self.untrack_scope(&prepared.dns_scope);
                drop((old_route,old_providers,old_raw,old_network,old_inputs));
                // Rule providers are file-only and the completed preparation has
                // no detached jobs. Old rule/reader Arcs keep their own held
                // capabilities; do not cancel them when a DNS scope retires.
                drop(old_rules_scope);
                if let Some(scope)=old_dns_scope {if let Err(cause)=self.policy.retire_geo_dns(scope).await {
                    let _gate=self.api.control.plane.gate.lock();if let Some(entry)=self.ledger.lock().entry.as_mut().filter(|entry|same_token(&entry.request.token(),&request.token)){entry.error=Some(cause.into());}
                }}
                self.status(&request.token)
            },
            Err(cause)=>{self.discard(prepared).await?;self.fail_entry(&request.token,&cause.to_string(),true);Err(cause)},
        }
    }
    // Only the existing JNI HostPolicy command lane invokes this. There is no
    // HTTP route and no caller-supplied bootstrap of an absent initial binding.
    pub(crate) async fn renew(&self,request:Renew,reply_closed:impl Fn()->bool)->io::Result<serde_json::Value> {
        request.expected.host_binding.validate()?;
        if !hash_valid(&request.engine_sha256) || !hash_valid(&request.new_source_guard) || request.raw_selected_map.len()>12000 {return Err(bad("geo.invalidRenew"));}
        {let gate=self.api.control.plane.gate.lock();let mut ledger=self.ledger.lock();self.check_expected(&gate,&ledger,&request.expected)?;
            if ledger.engine_sha256!=request.engine_sha256{return Err(bad("geo.engineChanged"));}
            self.api.control.verify_selection_witness_locked(&gate,&request.selection_witness_id,&request.raw_selected_map).map_err(|_|bad("geo.selectionNotApplied"))?;
            if let Some(entry)=ledger.entry.as_mut(){entry.cancel.cancel();if let Some(prepared)=entry.prepared.take(){ledger.retired.push(prepared);}}
        }
        self.drain_retired().await?;
        #[cfg(test)] self.boundary(Boundary::Renew).await;
        let gate=self.api.control.plane.gate.lock();let mut ledger=self.ledger.lock();self.check_expected(&gate,&ledger,&request.expected)?;
        if reply_closed(){return Err(bad("geo.cancelledRenew"));}
        if ledger.engine_sha256!=request.engine_sha256{return Err(bad("geo.engineChanged"));}
        self.api.control.verify_selection_witness_locked(&gate,&request.selection_witness_id,&request.raw_selected_map).map_err(|_|bad("geo.selectionNotApplied"))?;
        let witness=self.api.control.selection_witness_locked(&gate).map_err(|_|bad("geo.selectionNotApplied"))?;
        ledger.bound_raw=Some(request.raw_selected_map.clone());ledger.bound_witness=Some((witness.witness_id,witness.graph_version));
        ledger.binding.as_mut().ok_or_else(||bad("geo.hostBindingMissing"))?.source_guard=request.new_source_guard;
        if let Some(entry)=ledger.entry.as_mut(){if entry.state!="committed"{entry.state="aborted";entry.source_reads_joined=true;}}
        Ok(serde_json::json!({"hostBinding":ledger.binding,"current":Stamp::capture(&gate),"selectionWitnessId":request.selection_witness_id,"engineSha256":request.engine_sha256,"pendingSourceReadsJoined":true}))
    }
    pub(crate) async fn close(&self)->io::Result<()> {
        let prepared={let _gate=self.api.control.plane.gate.lock();let mut ledger=self.ledger.lock();if let Some(entry)=ledger.entry.as_mut(){entry.cancel.cancel();entry.prepared.take()}else{None}};
        let result=match prepared{Some(prepared)=>self.discard(prepared).await,None=>Ok(())};let retired=self.drain_retired().await;let preparing=self.drain_preparing().await;
        let current=self.current_rules_scope.lock().take();let rules=match current{Some(scope)=>scope.close().await.map_err(io::Error::other),None=>Ok(())};result.and(retired).and(preparing).and(rules)
    }
}



pub(crate) fn router(owner:std::sync::Weak<crate::owned_runtime::OwnedRuntime>)->axum::Router {
    use axum::{Router,routing::post,extract::{State,DefaultBodyLimit},body::Bytes,Json,http::StatusCode,response::{Response,IntoResponse}};
    fn failure(error:io::Error)->Response{(StatusCode::CONFLICT,Json(serde_json::json!({"error":error.to_string()}))).into_response()}
    Router::new()
        .route("/aerove/geo/prepare",post(|State(owner):State<std::sync::Weak<crate::owned_runtime::OwnedRuntime>>,body:Bytes|async move{
            let request:Prepare=serde_json::from_slice(&body).map_err(|_|failure(bad("geo.invalidRequest")))?;
            owner.upgrade().ok_or_else(||failure(bad("geo.runtimeExited")))?.geo_prepare(request).await.map(Json).map_err(failure)
        }))
        .route("/aerove/geo/commit",post(|State(owner):State<std::sync::Weak<crate::owned_runtime::OwnedRuntime>>,body:Bytes|async move{
            let request:Commit=serde_json::from_slice(&body).map_err(|_|failure(bad("geo.invalidRequest")))?;
            owner.upgrade().ok_or_else(||failure(bad("geo.runtimeExited")))?.geo_commit(request).await.map(Json).map_err(failure)
        }))
        .route("/aerove/geo/status",post(|State(owner):State<std::sync::Weak<crate::owned_runtime::OwnedRuntime>>,body:Bytes|async move{
            let token:Token=serde_json::from_slice(&body).map_err(|_|failure(bad("geo.invalidRequest")))?;
            owner.upgrade().ok_or_else(||failure(bad("geo.runtimeExited")))?.geo_status(&token).map(Json).map_err(failure)
        }))
        .route("/aerove/geo/abort",post(|State(owner):State<std::sync::Weak<crate::owned_runtime::OwnedRuntime>>,body:Bytes|async move{
            let token:Token=serde_json::from_slice(&body).map_err(|_|failure(bad("geo.invalidRequest")))?;
            owner.upgrade().ok_or_else(||failure(bad("geo.runtimeExited")))?.geo_abort(token).await.map(Json).map_err(failure)
        }))
        .layer(DefaultBodyLimit::max(2*crate::config_validation::MAX_TEXT_BYTES+65536)).with_state(owner)
}


fn validate_snapshot(file:&File,kind:&Kind,cancel:&CancellationToken)->io::Result<()> {
    use std::os::unix::fs::FileExt;
    let mut bytes=Vec::new();let mut buffer=[0u8;65536];let mut offset=0;
    loop{if cancel.is_cancelled(){return Err(bad("geo.cancelled"));}let count=file.read_at(&mut buffer,offset)?;if count==0{break;}
        if bytes.len()+count>crate::config_validation::MAX_INPUT_BYTES{return Err(bad("geo.inputLimit"));}bytes.extend_from_slice(&buffer[..count]);offset+=count as u64;}
    match kind {
        Kind::Geoip|Kind::Asn=>{let reader=meow_config::offline_maxminddb::Reader::from_source(bytes).map_err(io::Error::other)?;
            if reader.metadata.node_count as usize>crate::config_validation::MAX_EXPANDED_BYTES/6{return Err(bad("geo.mmdbTreeLimit"));}reader.verify().map_err(io::Error::other)?;},
        Kind::Geosite=>{let db=meow_config::offline_rules::geosite_dat::from_dat_bytes_checked(&bytes,None).map_err(io::Error::other)?;
            if db.category_count()==0{return Err(bad("geo.emptyGeosite"));}},
    }
    if cancel.is_cancelled(){return Err(bad("geo.cancelled"));}Ok(())
}


#[cfg(all(test,any(target_os="linux",target_os="android")))]
mod tests {
    use super::*;
    use std::{os::unix::{fs::{symlink,FileExt},net::UnixDatagram},sync::atomic::{AtomicU64,Ordering}};
    use tokio::{io::{AsyncReadExt,AsyncWriteExt},net::{TcpListener,TcpStream},time::{timeout,Duration}};
    static NEXT:AtomicU64=AtomicU64::new(1);
    const OLD:&str="00000000-0000-4000-8000-000000000001";
    const NEW:&str="00000000-0000-4000-8000-000000000002";
    const SECRET:&str="geo-private-actual-owner-test-secret-00001";
    struct Roots{base:PathBuf,authority:PathBuf,old:PathBuf,new:PathBuf,cache:PathBuf}
    impl Roots{fn new()->Self{
        let base=std::env::temp_dir().canonicalize().unwrap().join(format!("aerove-geo92-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));
        let authority=base.join("meow-read");let old=authority.join(OLD);let new=authority.join(NEW);let cache=base.join("cache");
        for path in [&old,&new,&cache]{std::fs::create_dir_all(path).unwrap();}
        std::fs::write(old.join("rules.yaml"),"payload: ['GEOSITE,unit,DIRECT']\n").unwrap();
        std::fs::write(old.join("geosite.dat"),dat("old.test")).unwrap();std::fs::write(new.join("geosite.dat"),dat("new.test")).unwrap();
        Self{base,authority,old,new,cache}
    }}
    impl Drop for Roots{fn drop(&mut self){let _=std::fs::remove_dir_all(&self.base);}}
    // Fixed V2Ray GeoSiteList: one Full domain per unit, using the Core parser.
    fn dat(host:&str)->Vec<u8>{assert!(host.len()<100);let mut domain=vec![8,3,18,host.len() as u8];domain.extend_from_slice(host.as_bytes());
        let mut site=vec![10,4,b'u',b'n',b'i',b't',18,domain.len() as u8];site.extend(domain);let mut list=vec![10,site.len() as u8];list.extend(site);list}
    fn asset(roots:&Roots)->Asset{let bytes=std::fs::read(roots.new.join("geosite.dat")).unwrap();Asset{kind:Kind::Geosite,relative_path:"geosite.dat".into(),bytes:bytes.len().to_string(),sha256:sha256(&bytes)}}
    fn engine(root:&Path)->String{format!("strict: true\ndns: {{enable: true,nameserver: ['udp://127.0.0.1:9'],default-nameserver: ['127.0.0.1'],nameserver-policy: {{'geosite:unit': ['udp://127.0.0.1:10']}}}}\ngeodata: {{auto-update: false, geosite-path: '{}'}}\nproxies: [{{name: A,type: direct}},{{name: B,type: direct}}]\nproxy-groups: [{{name: P,type: select,proxies: [A,B]}}]\nrule-providers: {{RP: {{type: file,behavior: classical,format: yaml,path: '{}'}}}}\nrules: ['RULE-SET,RP,B','MATCH,A']\ntun: {{enable: false,auto-route: false}}\n",root.join("geosite.dat").display(),root.parent().unwrap().join(OLD).join("rules.yaml").display())}
    fn binding(engine:&str)->HostBinding{HostBinding{service_owner:"92".into(),session_generation:"9201".into(),intent_epoch:"7".into(),compiled_sha256:sha256(engine.as_bytes()),source_guard:"a".repeat(64)}}
    struct Fixture{owner:Arc<crate::owned_runtime::OwnedRuntime>,control:Arc<crate::control::RouteControl>,roots:Roots,binding:HostBinding,initial_engine:String,address:std::net::SocketAddr,mixed:std::net::SocketAddr,_tun_peer:Option<UnixDatagram>,_tun_caller:Option<UnixDatagram>,tun_fd:Option<i32>}
    impl Fixture {
        async fn new(tun:bool,with_binding:bool)->Self{Self::with_dns(tun,with_binding,None).await}
        async fn with_dns(tun:bool,with_binding:bool,dns:Option<String>)->Self{Self::with_no_geo(tun,with_binding,dns,false).await}
        async fn with_no_geo(tun:bool,with_binding:bool,dns:Option<String>,no_geo:bool)->Self{
            use std::os::fd::{AsRawFd,OwnedFd};
            let roots=Roots::new();let mut engine=engine(&roots.old);
            if no_geo {
                engine=engine.lines().filter(|line|!line.starts_with("geodata:")).map(|line|if line.starts_with("dns:"){"dns: {enable: true,nameserver: ['udp://127.0.0.1:9'],default-nameserver: ['127.0.0.1']}"}else{line}).collect::<Vec<_>>().join("\n");
                std::fs::write(roots.old.join("rules.yaml"),"payload: ['DOMAIN,old.test']\n").unwrap();
            }if let Some(dns)=dns{let start=engine.find('\n').unwrap()+1;let end=start+engine[start..].find('\n').unwrap()+1;engine.replace_range(start..end,&format!("{dns}\n"));}let mut binding=binding(&engine);if no_geo{binding.compiled_sha256=sha256(format!("{engine}\ngeodata: {{auto-update: true,auto-update-interval: 24,url: {{asn: https://old.invalid/asn}}}}\n").as_bytes());}
            let private=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=private.local_addr().unwrap();
            let mixed_socket=TcpListener::bind("127.0.0.1:0").await.unwrap();let mixed=mixed_socket.local_addr().unwrap();
            let (fd,peer,caller,tun_fd)=if tun{let(a,b)=UnixDatagram::pair().unwrap();a.set_nonblocking(true).unwrap();let raw=a.as_raw_fd();(Some(OwnedFd::from(a.try_clone().unwrap())),Some(b),Some(a),Some(raw))}else{(None,None,None,None)};
            let mut plan=serde_json::json!({"schemaVersion":1,"runtimeMode":if tun{"tun"}else{"proxy"},"initiallyParked":false,"engineYaml":engine,
                "privateControl":{"address":address.to_string(),"secret":SECRET},"inbounds":{"configYaml":format!("mixed-port: {}\nbind-address: 127.0.0.1\n",mixed.port())},
                "tun":if tun{serde_json::json!({"mtu":1500,"ipv6":false,"inet4Address":"198.18.0.1/30","inet6Address":null,"dnsHijack":["any:53"],"autoRoute":false})}else{serde_json::Value::Null},
                "physicalNetwork":{"handle":null,"dns":{"defaultInterface":"first","interfaces":{"first":["127.0.0.1"]}}},"hostPolicyYaml":"{}",
                "profileId":null,"revision":null,"resourceGeneration":OLD,"resourceReadRoot":roots.old.to_str(),"cacheRoot":roots.cache.to_str()});
            if with_binding{plan["hostBinding"]=serde_json::to_value(&binding).unwrap();plan["geoReadRootAuthority"]=roots.authority.to_str().unwrap().into();plan["hostRawSelectedMap"]=serde_json::json!({});}
            let roots_cap=Arc::new(crate::resource_roots::ResourceRoots::validate(roots.old.to_str(),roots.cache.to_str()).unwrap());
            let cancel=CancellationToken::new();let network=crate::network_context::NetworkContext::platform(cancel.clone()).with_resource_roots(roots_cap).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
            let captured=Arc::new(parking_lot::Mutex::new(None));let slot=captured.clone();drop((private,mixed_socket));
            let owner=crate::owned_runtime::OwnedRuntime::start(plan.to_string(),fd,network,9201,cancel,Arc::new(move|control,_|{*slot.lock()=Some(control);true}),None,|_|{}).unwrap();
            timeout(Duration::from_secs(5),owner.ready()).await.unwrap().unwrap();let control=captured.lock().take().unwrap();
            let before=control.startup_selection_snapshot().unwrap();control.restore_startup_selection(crate::control::StartupSelectionRequest{expected_core_epoch:before.route.core_epoch,expected_route_version:before.route.route_version,expected_selection_intent:before.selection_intent,expected_graph_token:before.graph_token,requested:BTreeMap::new()}).unwrap();
            Self{owner,control,roots,binding,initial_engine:engine,address,mixed,_tun_peer:peer,_tun_caller:caller,tun_fd}
        }
        fn expected(&self)->Expected{let witness=self.control.selection_witness().unwrap();Expected{core_epoch:witness.core_epoch,route_version:witness.route_version,selection_intent:witness.selection_intent,host_binding:self.binding.clone()}}
        fn request(&self,serial:u64)->Prepare{let yaml=self.initial_engine.replace(self.roots.old.join("geosite.dat").to_str().unwrap(),self.roots.new.join("geosite.dat").to_str().unwrap());Prepare{schema_version:1,transaction_id:format!("10000000-0000-4000-8000-{serial:012x}"),operation_serial:serial.to_string(),expected:self.expected(),candidate:Candidate{compiled_sha256:sha256(yaml.as_bytes()),compiled_yaml:yaml.clone(),engine_yaml:yaml,read_root:self.roots.new.to_str().unwrap().into(),resource_lease_id:NEW.into(),assets:vec![asset(&self.roots)]}}}
        async fn target(&self,host:&str)->String{let mut metadata=meow_common::Metadata::default();metadata.host=host.into();metadata.dst_port=443;self.control.state.tunnel.inner().resolve_proxy(&metadata).await.unwrap().adapter.name().to_string()}
        async fn close(self){self.owner.cancel();let status=timeout(Duration::from_secs(5),self.owner.wait()).await.unwrap().unwrap();assert_eq!(status.state,"Exited");assert!(self.owner.cleanup_confirmed());}
        async fn http(&self,path:&str,body:&str)->serde_json::Value{let mut stream=TcpStream::connect(self.address).await.unwrap();let wire=format!("POST {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {SECRET}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len());stream.write_all(wire.as_bytes()).await.unwrap();let mut bytes=Vec::new();timeout(Duration::from_secs(5),stream.read_to_end(&mut bytes)).await.unwrap().unwrap();assert!(bytes.starts_with(b"HTTP/1.1 200"),"{}",String::from_utf8_lossy(&bytes));let body=bytes.windows(4).position(|v|v==b"\r\n\r\n").unwrap()+4;serde_json::from_slice(&bytes[body..]).unwrap()}
    }
    #[tokio::test]
    async fn real_geo_prepare_preserves_live_rules_sockets_and_tun_fd_then_commit_reads_new_rules() {
        use std::os::fd::AsRawFd;
        let fixture=Fixture::new(true,true).await;let before=fixture.owner.route_stamp();let route=fixture.control.state.tunnel.route_snapshot();
        let mut tun_stat=std::mem::MaybeUninit::<libc::stat>::uninit();assert_eq!(unsafe{libc::fstat(fixture.tun_fd.unwrap(),tun_stat.as_mut_ptr())},0);let tun_stat=unsafe{tun_stat.assume_init()};
        assert_eq!(fixture.target("old.test").await,"B");assert_eq!(fixture.target("new.test").await,"A");let request=fixture.request(1);
        let prepared=fixture.http("/aerove/geo/prepare",&serde_json::to_string(&request).unwrap()).await;assert_eq!(prepared["state"],"prepared");assert_eq!(prepared["sourceReadsJoined"],false);
        assert!(Arc::ptr_eq(&route,&fixture.control.state.tunnel.route_snapshot()));assert_eq!(fixture.owner.route_stamp(),before);assert_eq!(fixture.target("old.test").await,"B");
        assert!(TcpListener::bind(fixture.address).await.is_err());assert!(TcpListener::bind(fixture.mixed).await.is_err());
        let applied=fixture.http("/aerove/geo/commit",&serde_json::to_string(&Commit{token:request.token(),expected:request.expected.clone()}).unwrap()).await;
        assert_eq!(applied["state"],"committed");assert_eq!(applied["applied"]["before"]["coreEpoch"],before.0.to_string());assert_eq!(applied["applied"]["after"]["coreEpoch"],before.0.to_string());
        assert_eq!(fixture.target("old.test").await,"A");assert_eq!(fixture.target("new.test").await,"B");assert_eq!(fixture.owner.status().api_address,Some(fixture.address));assert!(fixture.owner.status().tun_ready);
        let mut now=std::mem::MaybeUninit::<libc::stat>::uninit();assert_eq!(unsafe{libc::fstat(fixture.tun_fd.unwrap(),now.as_mut_ptr())},0);let now=unsafe{now.assume_init()};assert_eq!((now.st_dev,now.st_ino),(tun_stat.st_dev,tun_stat.st_ino));
        assert_eq!(fixture._tun_peer.as_ref().unwrap().as_raw_fd()>=0,true);drop(route);fixture.close().await;
    }
    #[tokio::test]
    async fn committed_token_is_read_only_after_lost_response_and_source_gc_keeps_effective_reader() {
        let fixture=Fixture::new(false,true).await;let request=fixture.request(1);fixture.owner.geo_prepare(request.clone()).await.unwrap();
        let first=fixture.owner.geo_commit(Commit{token:request.token(),expected:request.expected.clone()}).await.unwrap();let after=fixture.owner.route_stamp();
        std::fs::remove_dir_all(&fixture.roots.new).unwrap();assert_eq!(fixture.target("new.test").await,"B");
        let geo=fixture.owner.geo_transactions().unwrap();let inputs=geo.inputs.read().clone();let prior=fixture.control.state.tunnel.resolver();
        let build=dns_build(inputs,geo.network.captured(),prior);let pair=build.scope.run(build.work).await.unwrap();
        assert!(pair.main.nameserver_policy().unwrap().lookup("new.test").is_some());assert!(pair.main.nameserver_policy().unwrap().lookup("old.test").is_none());
        build.scope.close().await.unwrap();assert_eq!(fixture.target("new.test").await,"B");
        let again=fixture.owner.geo_commit(Commit{token:request.token(),expected:request.expected.clone()}).await.unwrap();assert_eq!(again.applied.unwrap().engine_sha256,first.applied.unwrap().engine_sha256);assert_eq!(fixture.owner.route_stamp(),after);
        let status=fixture.http("/aerove/geo/status",&serde_json::to_string(&request.token()).unwrap()).await;assert_eq!(status["state"],"committed");assert_eq!(status["sourceReadsJoined"],true);assert_eq!(status["applied"]["effectiveStorage"],"owned-sealed-memfd");
        fixture.close().await;
    }
    #[tokio::test]
    async fn source_mutation_or_epoch_choice_race_never_publishes_candidate_and_keeps_old_socket() {
        for action in ["source","epoch","choice","provider","abort"]{
            let fixture=Fixture::new(false,true).await;let request=fixture.request(1);let route=fixture.control.state.tunnel.route_snapshot();fixture.owner.geo_prepare(request.clone()).await.unwrap();
            match action{
                "source"=>std::fs::write(fixture.roots.new.join("geosite.dat"),dat("bad.test")).unwrap(),
                "epoch"=>{fixture.control.plane.commit_policy(fixture.owner.route_stamp().0,true,||()).unwrap();},
                "choice"=>{fixture.control.select("P","B").await.unwrap();},
                "provider"=>{let provider=fixture.control.state.rule_providers.read()["RP"].clone();provider.refresh_owned().await.unwrap();},
                "abort"=>{let status=fixture.owner.geo_abort(request.token()).await.unwrap();assert!(status.source_reads_joined);assert_eq!(status.state,"aborted");},_=>unreachable!(),
            }
            assert!(fixture.owner.geo_commit(Commit{token:request.token(),expected:request.expected.clone()}).await.is_err());assert!(Arc::ptr_eq(&route,&fixture.control.state.tunnel.route_snapshot()));assert_eq!(fixture.target("old.test").await,"B");assert!(TcpListener::bind(fixture.mixed).await.is_err());drop(route);fixture.close().await;
        }
    }
    #[tokio::test]
    async fn missing_jni_binding_bad_assets_and_non_geo_engine_changes_fail_closed_on_live_core() {
        let fixture=Fixture::new(false,false).await;assert!(fixture.owner.geo_prepare(fixture.request(1)).await.is_err());assert_eq!(fixture.target("old.test").await,"B");fixture.close().await;
        for action in ["hash","format","engine","lease"]{
            let fixture=Fixture::new(false,true).await;let mut request=fixture.request(1);
            match action{
                "hash"=>request.candidate.assets[0].sha256="0".repeat(64),
                "format"=>{std::fs::write(fixture.roots.new.join("geosite.dat"),b"invalid").unwrap();request.candidate.assets=vec![asset(&fixture.roots)];},
                "engine"=>{request.candidate.engine_yaml.push_str("allow-lan: true\n");},
                "lease"=>request.candidate.resource_lease_id=OLD.into(),_=>unreachable!(),
            }
            assert!(fixture.owner.geo_prepare(request).await.is_err());assert_eq!(fixture.target("old.test").await,"B");fixture.close().await;
        }
    }
    #[test]
    fn actual_seals_reject_write_and_nofollow_hardlink_or_namespace_replacement() {
        let roots=Roots::new();let record=asset(&roots);let snapshots=Snapshots::capture(&roots.authority,roots.new.to_str().unwrap(),&[record.clone()],&CancellationToken::new()).unwrap();
        let held=snapshots.assets[&Kind::Geosite].snapshot.clone();verify_seals(&held).unwrap();assert!(held.write_at(b"x",0).is_err());
        std::fs::remove_file(roots.new.join("geosite.dat")).unwrap();symlink(roots.old.join("geosite.dat"),roots.new.join("geosite.dat")).unwrap();assert!(Snapshots::capture(&roots.authority,roots.new.to_str().unwrap(),&[record.clone()],&CancellationToken::new()).is_err());assert!(snapshots.verify_source().is_err());
        std::fs::remove_file(roots.new.join("geosite.dat")).unwrap();std::fs::hard_link(roots.old.join("geosite.dat"),roots.new.join("geosite.dat")).unwrap();assert!(Snapshots::capture(&roots.authority,roots.new.to_str().unwrap(),&[record],&CancellationToken::new()).is_err());
        std::fs::remove_dir_all(&roots.new).unwrap();let mut bytes=vec![0;dat("new.test").len()];assert_eq!(held.read_at(&mut bytes,0).unwrap(),bytes.len());assert_eq!(bytes,dat("new.test"));
    }
    #[tokio::test]
    async fn newer_operation_and_stop_cancel_pending_scopes_without_reactivating_old_transaction() {
        let fixture=Fixture::new(false,true).await;let old=fixture.request(1);fixture.owner.geo_prepare(old.clone()).await.unwrap();let new=fixture.request(2);fixture.owner.geo_prepare(new.clone()).await.unwrap();
        assert!(fixture.owner.geo_commit(Commit{token:old.token(),expected:old.expected}).await.is_err());assert_eq!(fixture.target("old.test").await,"B");let status=fixture.owner.geo_abort(new.token()).await.unwrap();assert_eq!(status.state,"aborted");assert!(status.source_reads_joined);fixture.close().await;
        let fixture=Fixture::new(false,true).await;let request=fixture.request(1);fixture.owner.geo_prepare(request).await.unwrap();fixture.close().await;
    }
    #[tokio::test]
    async fn dropped_prepare_handler_and_cancelled_jni_renew_join_scopes_before_result() {
        let fixture=Fixture::new(false,true).await;let geo=fixture.owner.geo_transactions().unwrap();
        let (reached,arrived)=tokio::sync::oneshot::channel();let(release,waiting)=tokio::sync::oneshot::channel();
        *geo.barrier.lock()=Some(Barrier{boundary:Boundary::Prepared,reached,release:waiting});
        let request=fixture.request(1);let task_owner=fixture.owner.clone();let task_request=request.clone();
        let task=tokio::spawn(async move{task_owner.geo_prepare(task_request).await});timeout(Duration::from_secs(5),arrived).await.unwrap().unwrap();task.abort();assert!(matches!(task.await,Err(cause) if cause.is_cancelled()));release.send(()).unwrap();
        let status=fixture.owner.geo_abort(request.token()).await.unwrap();assert_eq!(status.state,"aborted");assert!(status.source_reads_joined);assert_eq!(fixture.target("old.test").await,"B");
        let witness=fixture.control.selection_witness().unwrap();let command=serde_json::json!({"schemaVersion":1,"requestId":"30000000-0000-4000-8000-000000000001","expectedCoreEpoch":witness.core_epoch,"operation":"renewGeoBinding","value":{"expected":fixture.expected(),"engineSha256":sha256(engine(&fixture.roots.old).as_bytes()),"selectionWitnessId":witness.witness_id,"rawSelectedMap":{},"newSourceGuard":"b".repeat(64)}}).to_string();
        let (reached,arrived)=tokio::sync::oneshot::channel();let(release,waiting)=tokio::sync::oneshot::channel();*geo.barrier.lock()=Some(Barrier{boundary:Boundary::Renew,reached,release:waiting});
        let task_owner=fixture.owner.clone();let task=tokio::spawn(async move{task_owner.host_policy_command(command).await});timeout(Duration::from_secs(5),arrived).await.unwrap().unwrap();task.abort();assert!(matches!(task.await,Err(cause) if cause.is_cancelled()));release.send(()).unwrap();
        // Queueing the real next prepare waits behind the cancelled renew in the
        // SAME mpsc lane, then proves its old guard was never replaced.
        assert_eq!(fixture.owner.geo_prepare(fixture.request(2)).await.unwrap().state,"prepared");fixture.close().await;
    }
    #[tokio::test]
    async fn running_and_nested_requests_keep_paired_old_route_dns_while_fresh_and_rebound_requests_use_new() {
        let fixture=Fixture::new(false,true).await;let geo=fixture.owner.geo_transactions().unwrap();
        let before=fixture.control.state.tunnel.route_snapshot();let weak=Arc::downgrade(&before);drop(before);
        let idle=geo.network.captured();let nested=idle.clone();let running=geo.network.clone();let control=fixture.control.clone();
        let(reached,arrived)=tokio::sync::oneshot::channel();let(release,waiting)=tokio::sync::oneshot::channel();
        let task=tokio::spawn(async move{running.scope(async move{
            let route=control.state.tunnel.inner().route();let resolver=control.state.tunnel.resolver();reached.send(()).unwrap();waiting.await.unwrap();
            assert!(Arc::ptr_eq(&route,&control.state.tunnel.inner().route()));assert!(!Arc::ptr_eq(&route,&control.state.tunnel.route_snapshot()));
            assert!(Arc::ptr_eq(&resolver,&control.state.tunnel.resolver()));assert!(resolver.nameserver_policy().unwrap().lookup("old.test").is_some());assert!(resolver.nameserver_policy().unwrap().lookup("new.test").is_none());
            let mut metadata=meow_common::Metadata::default();metadata.host="old.test".into();metadata.dst_port=443;
            assert_eq!(control.state.tunnel.inner().resolve_proxy(&metadata).await.unwrap().adapter.name(),"B");
            nested.scope(async{assert!(Arc::ptr_eq(&route,&control.state.tunnel.inner().route()));assert!(Arc::ptr_eq(&resolver,&control.state.tunnel.resolver()));}).await;
        }).await});
        timeout(Duration::from_secs(5),arrived).await.unwrap().unwrap();let request=fixture.request(1);fixture.owner.geo_prepare(request.clone()).await.unwrap();fixture.owner.geo_commit(Commit{token:request.token(),expected:request.expected}).await.unwrap();
        geo.network.scope(async{let resolver=fixture.control.state.tunnel.resolver();assert!(resolver.nameserver_policy().unwrap().lookup("new.test").is_some());assert_eq!(fixture.target("new.test").await,"B");}).await;
        release.send(()).unwrap();timeout(Duration::from_secs(5),task).await.unwrap().unwrap();assert!(weak.upgrade().is_none());
        // This idle context holds no old route Arc and cannot borrow the new
        // graph with its old resolver after its actual parent operation ended.
        idle.scope(async{let mut metadata=meow_common::Metadata::default();metadata.host="new.test".into();metadata.dst_port=443;assert!(fixture.control.state.tunnel.inner().resolve_proxy(&metadata).await.is_none());}).await;
        let rebound=idle.rebind_seed_context().with_core_resolver(fixture.control.state.tunnel.resolver());
        rebound.scope(async{assert_eq!(fixture.target("new.test").await,"B");assert!(fixture.control.state.tunnel.resolver().nameserver_policy().unwrap().lookup("new.test").is_some());}).await;
        fixture.close().await;
    }
    #[tokio::test]
    async fn staged_dns_transport_denies_prepare_then_retire_cancels_and_joins_actual_polled_work() {
        use meow_common::transport_tasks::{TransportTaskContext,TransportScope,Work};
        struct Base(Arc<std::sync::atomic::AtomicUsize>);
        impl TransportTaskContext for Base {
            fn spawn(&self,work:Work)->io::Result<tokio::task::AbortHandle>{self.spawn_join(work).map(|task|task.abort_handle())}
            fn spawn_join(&self,work:Work)->io::Result<Arc<meow_transport::transport_tasks::SharedUnitJoin>>{self.0.fetch_add(1,Ordering::SeqCst);Ok(meow_transport::transport_tasks::SharedUnitJoin::new(tokio::spawn(work)))}
        }
        struct Dropped(Arc<std::sync::atomic::AtomicUsize>);impl Drop for Dropped{fn drop(&mut self){self.0.fetch_add(1,Ordering::SeqCst);}}
        let calls=Arc::new(std::sync::atomic::AtomicUsize::new(0));let dropped=Arc::new(std::sync::atomic::AtomicUsize::new(0));let scope=meow_config::owned_load::OwnedLoadScope::new();
        let activation=Arc::new(DnsActivation{published:std::sync::atomic::AtomicBool::new(false),scope:std::sync::OnceLock::new()});activation.scope.set(Arc::downgrade(&scope)).unwrap();
        let transport=GeoDnsTransport{base:TransportScope::new(Arc::new(Base(calls.clone()))),activation:Arc::downgrade(&activation)};
        assert!(transport.spawn_join(Box::pin(async{})).is_err());assert_eq!(calls.load(Ordering::SeqCst),0);
        activation.published.store(true,Ordering::Release);let(tx,rx)=tokio::sync::oneshot::channel();let count=dropped.clone();
        let task=transport.spawn_join(Box::pin(async move{let _drop=Dropped(count);tx.send(()).unwrap();std::future::pending::<()>().await})).unwrap();rx.await.unwrap();
        timeout(Duration::from_secs(2),scope.close()).await.unwrap().unwrap();task.wait().await;assert_eq!(dropped.load(Ordering::SeqCst),1);assert!(transport.spawn_join(Box::pin(async{})).is_err());
        let idle=transport.wrap(Box::pin(async{panic!("retired idle transport work must never run")}));drop(idle);assert_eq!(calls.load(Ordering::SeqCst),1);
    }
    #[tokio::test]
    async fn real_physical_network_refresh_keeps_raw_ack_id_but_fences_old_live_stamp() {
        let fixture=Fixture::new(false,true).await;
        fixture.owner.network_policy().unwrap().set_network_rebind_fixture(|base,handle| {
            assert_eq!(handle,1);assert!(!base.cancelled());
            Ok(base.fixture_network_identity(handle))
        });let before=fixture.control.selection_witness().unwrap();let stale=fixture.request(1);
        let command=serde_json::json!({"schemaVersion":1,"requestId":"40000000-0000-4000-8000-000000000001","expectedCoreEpoch":before.core_epoch,"operation":"network","value":{"intent":"1","physicalNetwork":{"handle":"1","dns":{"defaultInterface":"first","interfaces":{"first":["127.0.0.2"]}}}}}).to_string();
        let response=fixture.owner.host_policy_command(command).await.unwrap();let response:serde_json::Value=serde_json::from_str(&response).unwrap();assert_eq!(response["value"]["retiredDnsJoined"],true);assert_eq!(response["value"]["handle"],"1");assert_eq!(response["value"]["dnsServers"],serde_json::json!(["127.0.0.2:53"]));
        let after=fixture.control.selection_witness().unwrap();assert!(after.complete);assert_eq!(after.witness_id,before.witness_id);assert_eq!(after.raw_requested,before.raw_requested);assert_eq!(after.graph_version,before.graph_version);assert_ne!(after.core_epoch,before.core_epoch);assert_eq!(after.acknowledged_core_epoch,before.acknowledged_core_epoch);
        assert!(fixture.owner.geo_prepare(stale).await.is_err());let fresh=fixture.request(2);assert_eq!(fixture.owner.geo_prepare(fresh.clone()).await.unwrap().state,"prepared");fixture.owner.geo_abort(fresh.token()).await.unwrap();fixture.close().await;
    }
    #[tokio::test(flavor="multi_thread",worker_threads=3)]
    async fn hostname_doh_dot_prepare_reuses_real_clients_without_bootstrap_then_new_query_uses_new_scope() {
        use hickory_proto::{op::{Message,MessageType,OpCode},rr::{Record,RData,rdata::A},serialize::binary::{BinDecodable,BinEncodable}};
        use std::net::Ipv4Addr;
        let bootstrap=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();let bootstrap_address=bootstrap.local_addr().unwrap();
        let upstream=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();let upstream_address=upstream.local_addr().unwrap();
        let bootstrap_count=Arc::new(AtomicU64::new(0));let query_count=Arc::new(AtomicU64::new(0));let stop=CancellationToken::new();
        let serve=|socket:tokio::net::UdpSocket,count:Arc<AtomicU64>,ip:Ipv4Addr,stop:CancellationToken|tokio::spawn(async move{let mut buffer=[0;4096];loop{tokio::select!{biased;_=stop.cancelled()=>return,
            packet=socket.recv_from(&mut buffer)=>{let(size,peer)=packet.unwrap();count.fetch_add(1,Ordering::SeqCst);let query=Message::from_bytes(&buffer[..size]).unwrap();
                let mut response=Message::new(query.metadata.id,MessageType::Response,OpCode::Query);response.add_queries(query.queries.iter().cloned());response.add_answer(Record::from_rdata(query.queries[0].name.clone(),60,RData::A(A(ip))));socket.send_to(&response.to_bytes().unwrap(),peer).await.unwrap();}
        }}});
        let boot=serve(bootstrap,bootstrap_count.clone(),Ipv4Addr::LOCALHOST,stop.clone());let server=serve(upstream,query_count.clone(),Ipv4Addr::new(192,0,2,92),stop.clone());
        let dns=format!("dns: {{enable: true,use-system-hosts: false,nameserver: ['https://resolver.test:443/dns-query'],fallback: ['tls://resolver.test:853'],default-nameserver: ['udp://{bootstrap_address}'],proxy-server-nameserver: ['https://resolver.test:443/dns-query'],nameserver-policy: {{'geosite:unit': ['udp://{upstream_address}']}}}}");
        let fixture=Fixture::with_dns(false,true,Some(dns)).await;let geo=fixture.owner.geo_transactions().unwrap();let previous=geo.network.captured_resolver_pair().unwrap();let before=bootstrap_count.load(Ordering::SeqCst);assert!(before>0);assert_eq!(query_count.load(Ordering::SeqCst),0);
        let request=fixture.request(1);fixture.owner.geo_prepare(request.clone()).await.unwrap();assert_eq!(bootstrap_count.load(Ordering::SeqCst),before);assert_eq!(query_count.load(Ordering::SeqCst),0);
        let prepared={let _gate=fixture.control.plane.gate.lock();let ledger=geo.ledger.lock();ledger.entry.as_ref().unwrap().prepared.as_ref().unwrap().pair.clone()};
        assert!(Arc::ptr_eq(&previous.main.main_nameservers()[0],&prepared.main.main_nameservers()[0]));assert!(Arc::ptr_eq(&previous.proxy.as_ref().unwrap().main_nameservers()[0],&prepared.proxy.as_ref().unwrap().main_nameservers()[0]));
        assert_eq!(timeout(Duration::from_secs(2),prepared.main.lookup_ipv4("new.test")).await.unwrap(),None);assert_eq!(query_count.load(Ordering::SeqCst),0);
        fixture.owner.geo_commit(Commit{token:request.token(),expected:request.expected}).await.unwrap();let current=geo.network.captured_resolver_pair().unwrap();assert!(!Arc::ptr_eq(&previous.main,&current.main));
        assert_eq!(timeout(Duration::from_secs(3),current.main.lookup_ipv4("new.test")).await.unwrap(),Some("192.0.2.92".parse().unwrap()));let sent=query_count.load(Ordering::SeqCst);assert!(sent>0);assert_eq!(bootstrap_count.load(Ordering::SeqCst),before);
        // The retained old resolver is an actual old object; its retired scope
        // cannot send another packet even though the shared clients stay usable
        // through the new resolver's new scope.
        assert_eq!(timeout(Duration::from_secs(2),previous.main.lookup_ipv4("old.test")).await.unwrap(),None);assert_eq!(query_count.load(Ordering::SeqCst),sent);
        fixture.close().await;stop.cancel();boot.await.unwrap();server.await.unwrap();
    }
    #[tokio::test]
    async fn real_user_controller_cannot_prepare_geo_read_raw_witness_or_renew_binding() {
        let fixture=Fixture::new(false,true).await;let geo=fixture.owner.geo_transactions().unwrap();let before=fixture.owner.route_stamp();
        let endpoint=Arc::new(geo.api.user_endpoint("user-controller-test-secret",Arc::new(geo.network.clone())).unwrap());let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();
        let stop=CancellationToken::new();let task_endpoint=endpoint.clone();let task_stop=stop.clone();let serving=tokio::spawn(async move{task_endpoint.serve(listener,task_stop).await});
        for (method,path,body) in [("POST","/aerove/geo/prepare",serde_json::to_string(&fixture.request(1)).unwrap()),("GET","/aerove/selection-witness",String::new()),("POST","/aerove/geo/renew","{}".into())]{
            let mut stream=TcpStream::connect(address).await.unwrap();let wire=format!("{method} {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer user-controller-test-secret\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len());stream.write_all(wire.as_bytes()).await.unwrap();let mut response=Vec::new();timeout(Duration::from_secs(3),stream.read_to_end(&mut response)).await.unwrap().unwrap();assert!(!response.starts_with(b"HTTP/1.1 200"));assert!(!String::from_utf8_lossy(&response).contains("rawRequested"));
        }
        assert_eq!(fixture.owner.route_stamp(),before);assert!(geo.ledger.lock().entry.is_none());endpoint.cancel();stop.cancel();serving.await.unwrap().unwrap();endpoint.join().await;fixture.close().await;
    }
    #[tokio::test]
    async fn empty_geo_assets_commit_host_auto_off_metadata_with_real_zero_source_receipt() {
        let fixture=Fixture::with_no_geo(false,true,None,true).await;let before=fixture.owner.route_stamp();
        let mut request=fixture.request(1);request.candidate.assets.clear();request.candidate.compiled_yaml.push_str("\ngeodata: {auto-update: false,auto-update-interval: 72,url: {asn: https://global.invalid/asn}}\n");request.candidate.compiled_sha256=sha256(request.candidate.compiled_yaml.as_bytes());
        for root in [&fixture.roots.old,&fixture.roots.new]{std::fs::remove_file(root.join("geosite.dat")).unwrap();}
        assert_eq!(fixture.target("old.test").await,"B");assert_eq!(fixture.target("new.test").await,"A");
        let prepared=fixture.owner.geo_prepare(request.clone()).await.unwrap();assert_eq!(prepared.state,"prepared");assert!(!prepared.source_reads_joined);assert_eq!(fixture.owner.route_stamp(),before);
        let committed=fixture.owner.geo_commit(Commit{token:request.token(),expected:request.expected}).await.unwrap();let applied=committed.applied.unwrap();
        assert_eq!(committed.state,"committed");assert!(committed.source_reads_joined);assert!(applied.source_assets.is_empty());assert_eq!(applied.effective_storage,"none");assert!(!applied.sealed_snapshot);assert_eq!(applied.compiled_sha256,request.candidate.compiled_sha256);
        assert_eq!(fixture.target("old.test").await,"B");assert_eq!(fixture.target("new.test").await,"A");fixture.close().await;
    }
    #[tokio::test]
    async fn empty_geo_assets_cancelled_prepare_joins_empty_scopes_preserving_old_graph() {
        let fixture=Fixture::with_no_geo(false,true,None,true).await;let before=fixture.control.state.tunnel.route_snapshot();let mut request=fixture.request(1);request.candidate.assets.clear();
        let geo=fixture.owner.geo_transactions().unwrap();let(reached,arrived)=tokio::sync::oneshot::channel();let(release,waiting)=tokio::sync::oneshot::channel();*geo.barrier.lock()=Some(Barrier{boundary:Boundary::Prepared,reached,release:waiting});
        let owner=fixture.owner.clone();let command=request.clone();let task=tokio::spawn(async move{owner.geo_prepare(command).await});timeout(Duration::from_secs(5),arrived).await.unwrap().unwrap();task.abort();assert!(matches!(task.await,Err(cause) if cause.is_cancelled()));release.send(()).unwrap();
        let status=fixture.owner.geo_abort(request.token()).await.unwrap();assert_eq!(status.state,"aborted");assert!(status.source_reads_joined);assert!(Arc::ptr_eq(&before,&fixture.control.state.tunnel.route_snapshot()));assert_eq!(fixture.target("old.test").await,"B");drop(before);fixture.close().await;
    }
    #[tokio::test]
    async fn empty_geo_assets_reject_hidden_provider_subrule_and_dns_dependencies_before_geo_read() {
        let fixture=Fixture::with_no_geo(false,true,None,true).await;
        let geo=fixture.owner.geo_transactions().unwrap();let before=fixture.control.state.tunnel.route_snapshot();
        for case in ["provider","subrule","dns-fallback","dns-policy"] {
            std::fs::write(fixture.roots.old.join("rules.yaml"),if case=="provider"{"payload: ['GEOSITE,unit']\n"}else{"payload: ['DOMAIN,old.test']\n"}).unwrap();
            if case=="provider" {
                let mut request=fixture.request(1);request.candidate.assets.clear();let token=request.token();
                let error=fixture.owner.geo_prepare(request).await.err().expect("hidden provider Geo accepted by owner");assert!(error.to_string().contains("empty sources have a rule/provider/subrule or DNS Geo dependency"));
                let status=fixture.owner.geo_status(&token).unwrap();assert_eq!(status.state,"failed");assert!(status.source_reads_joined);
            }
            let mut raw=meow_config::parse_raw_yaml(&fixture.initial_engine).unwrap();
            if case=="subrule"{raw.sub_rules=Some(std::collections::HashMap::from([("hidden".into(),vec!["AND,((GEOSITE,unit),(NETWORK,TCP)),B".into()]) ]));}
            if case=="dns-fallback"{raw.dns.as_mut().unwrap().fallback=Some(vec!["udp://127.0.0.1:10".into()]);}
            if case=="dns-policy"{raw.dns.as_mut().unwrap().nameserver_policy=Some(std::collections::HashMap::from([("+.safe.test,geosite:unit".into(),meow_config::raw::RawNspValue::One("udp://127.0.0.1:10".into()))]));}
            let sources=meow_config::SealedGeoSources::new(None,None,None).unwrap();
            let scope=meow_config::owned_load::OwnedLoadScope::with_context(Arc::new(GeoContext{network:geo.network.captured(),stage:None,_sources:Some(sources.clone()),_dns_activation:None}));
            let result=scope.run(meow_config::prepare_geo_reload(raw,sources,before.proxies.as_ref().clone(),before.dialer_registry.clone(),None)).await;
            scope.close().await.unwrap();let error=result.err().expect("hidden Geo dependency accepted");assert!(error.to_string().contains("empty sources have a rule/provider/subrule or DNS Geo dependency"),"{case}: {error}");
            assert!(Arc::ptr_eq(&before,&fixture.control.state.tunnel.route_snapshot()));
        }
        std::fs::write(fixture.roots.old.join("rules.yaml"),"payload: ['DOMAIN,old.test']\n").unwrap();assert_eq!(fixture.target("old.test").await,"B");drop(before);fixture.close().await;
        let fixture=Fixture::new(false,true).await;let mut request=fixture.request(1);request.candidate.assets.clear();assert!(fixture.owner.geo_prepare(request).await.is_err());assert_eq!(fixture.target("old.test").await,"B");fixture.close().await;
    }
    #[tokio::test(flavor="multi_thread",worker_threads=3)]
    async fn fifo_replacement_fails_prepare_and_commit_without_blocking_cancel_or_join() {
        for phase in ["prepare","commit"] {
            let fixture=Fixture::new(false,true).await;let before=fixture.control.state.tunnel.route_snapshot();let mut request=fixture.request(1);
            let relative=if phase=="commit"{"nested/geosite.dat"}else{"geosite.dat"};
            if phase=="commit" {
                std::fs::create_dir(fixture.roots.new.join("nested")).unwrap();
                std::fs::rename(fixture.roots.new.join("geosite.dat"),fixture.roots.new.join(relative)).unwrap();
                request.candidate.engine_yaml=request.candidate.engine_yaml.replace("/geosite.dat","/nested/geosite.dat");
                request.candidate.compiled_yaml=request.candidate.engine_yaml.clone();request.candidate.compiled_sha256=sha256(request.candidate.compiled_yaml.as_bytes());request.candidate.assets[0].relative_path=relative.into();
                fixture.owner.geo_prepare(request.clone()).await.unwrap();
                // Moving the DIRECTORY preserves the original held file's
                // regular/single-link identity, so verify_source really reaches
                // open_at on the replacement instead of short-circuiting early.
                let geo=fixture.owner.geo_transactions().unwrap();
                let held={let _gate=fixture.control.plane.gate.lock();let ledger=geo.ledger.lock();let asset=&ledger.entry.as_ref().unwrap().prepared.as_ref().unwrap().snapshots.assets[&Kind::Geosite];asset.source.try_clone().unwrap()};
                let identity_before=identity(&held).unwrap();std::fs::rename(fixture.roots.new.join("nested"),fixture.roots.new.join("kept")).unwrap();
                std::fs::create_dir(fixture.roots.new.join("nested")).unwrap();fifo_regression::make_fifo(&fixture.roots.new.join(relative));assert!(identity(&held).unwrap()==identity_before);
            }else{fifo_regression::replace_with_fifo(&fixture.roots.new.join(relative));}
            let watchdog=fifo_regression::Watchdog::new(&fixture.roots.new.join(relative));
            let(mut latch,arrived)=fifo_regression::LeafLatch::install(&fixture.roots.new,relative);
            let owner=fixture.owner.clone();let command=request.clone();let preparing=phase=="prepare";
            let operation=tokio::spawn(async move{if preparing{owner.geo_prepare(command).await}else{owner.geo_commit(Commit{token:command.token(),expected:command.expected}).await}});
            timeout(Duration::from_secs(5),arrived).await.unwrap().unwrap();
            operation.abort();let caller=operation.await;
            let owner=fixture.owner.clone();let token=request.token();let aborting=tokio::spawn(async move{owner.geo_abort(token).await});
            watchdog.arm();latch.release();
            let status=timeout(Duration::from_secs(6),aborting).await;
            // Watchdog rescue is evidence of the old bug, NEVER a success path.
            // We confirm the real owner cleanup before disarming its rescue FD.
            let joined=status.as_ref().ok().and_then(|value|value.as_ref().ok()).and_then(|value|value.as_ref().ok()).is_some_and(|value|value.source_reads_joined && value.state=="aborted");
            let cleanup_confirmed=if joined{true}else{fixture.owner.cancel();matches!(timeout(Duration::from_secs(5),fixture.owner.wait()).await,Ok(Ok(_))) && fixture.owner.cleanup_confirmed()};
            let rescued=if cleanup_confirmed{watchdog.joined().unwrap()}else{drop(watchdog);panic!("{phase}: owner cleanup not confirmed; rescue retained")};
            assert!(matches!(caller,Err(cause) if cause.is_cancelled()));assert!(joined,"{phase}: actual cancelled scope did not join");
            assert!(Arc::ptr_eq(&before,&fixture.control.state.tunnel.route_snapshot()));assert_eq!(fixture.target("old.test").await,"B");
            std::fs::remove_file(fixture.roots.new.join(relative)).unwrap();
            if phase=="commit"{std::fs::rename(fixture.roots.new.join("kept/geosite.dat"),fixture.roots.new.join("geosite.dat")).unwrap();}
            else{std::fs::write(fixture.roots.new.join("geosite.dat"),dat("new.test")).unwrap();}
            let valid=fixture.request(2);fixture.owner.geo_prepare(valid.clone()).await.unwrap();let actual=fixture.owner.geo_commit(Commit{token:valid.token(),expected:valid.expected}).await.unwrap();assert_eq!(actual.state,"committed");assert_eq!(fixture.target("new.test").await,"B");
            drop(before);fixture.close().await;assert!(!rescued,"{phase}: FIFO open needed watchdog rescue");
        }
    }
    #[tokio::test]
    async fn metadata_renew_requires_applied_raw_choice_and_preserves_strict_owner_engine_identity() {
        let mut fixture=Fixture::new(false,true).await;fixture.control.select("P","B").await.unwrap();assert!(fixture.owner.geo_prepare(fixture.request(1)).await.is_err());let witness=fixture.control.selection_witness().unwrap();
        let command=|raw:serde_json::Value,engine:String,guard:&str|serde_json::json!({"schemaVersion":1,"requestId":"20000000-0000-4000-8000-000000000001","expectedCoreEpoch":witness.core_epoch,"operation":"renewGeoBinding","value":{"expected":fixture.expected(),"engineSha256":engine,"selectionWitnessId":witness.witness_id,"rawSelectedMap":raw,"newSourceGuard":guard}}).to_string();
        assert!(fixture.owner.host_policy_command(command(serde_json::json!({}),sha256(engine(&fixture.roots.old).as_bytes()),&"b".repeat(64))).await.is_err());
        assert!(fixture.owner.host_policy_command(command(serde_json::json!({"P":"B"}),"0".repeat(64),&"b".repeat(64))).await.is_err());
        let response=fixture.owner.host_policy_command(command(serde_json::json!({"P":"B"}),sha256(engine(&fixture.roots.old).as_bytes()),&"b".repeat(64))).await.unwrap();let response:serde_json::Value=serde_json::from_str(&response).unwrap();assert_eq!(response["value"]["pendingSourceReadsJoined"],true);
        fixture.binding.source_guard="b".repeat(64);let request=fixture.request(1);assert_eq!(fixture.owner.geo_prepare(request.clone()).await.unwrap().state,"prepared");fixture.owner.geo_abort(request.token()).await.unwrap();fixture.close().await;
    }
}


#[cfg(test)]
#[derive(Clone,Copy,PartialEq,Eq)]
enum Boundary{Prepared,Renew}
#[cfg(test)]
struct Barrier{boundary:Boundary,reached:tokio::sync::oneshot::Sender<()>,release:tokio::sync::oneshot::Receiver<()>}
#[cfg(test)]
impl Transactions {
    async fn boundary(&self,boundary:Boundary){let barrier={let mut slot=self.barrier.lock();if slot.as_ref().is_some_and(|barrier|barrier.boundary==boundary){slot.take()}else{None}};
        if let Some(barrier)=barrier{let _=barrier.reached.send(());let _=barrier.release.await;}}
}


#[cfg(all(test,any(target_os="linux",target_os="android")))]
mod fifo_regression {
    use super::*;
    use std::{ffi::CString,os::{fd::{AsRawFd,FromRawFd},unix::{ffi::OsStrExt,fs::MetadataExt}},sync::{mpsc,atomic::{AtomicBool,Ordering}},thread};
    type Key=(u64,u64,String);
    struct Hook {reached:tokio::sync::oneshot::Sender<()>,release:mpsc::Receiver<()>}
    static HOOKS:std::sync::OnceLock<parking_lot::Mutex<BTreeMap<Key,Hook>>>=std::sync::OnceLock::new();
    fn hooks()->&'static parking_lot::Mutex<BTreeMap<Key,Hook>>{HOOKS.get_or_init(Default::default)}
    pub(super) struct LeafLatch {key:Key,release:Option<mpsc::Sender<()>>}
    impl LeafLatch {
        pub(super) fn install(root:&Path,relative:&str)->(Self,tokio::sync::oneshot::Receiver<()>) {
            let meta=open_dir(root).unwrap().metadata().unwrap();let key=(meta.dev(),meta.ino(),relative.to_owned());
            let(reached,arrived)=tokio::sync::oneshot::channel();let(release,waiting)=mpsc::channel();
            assert!(hooks().lock().insert(key.clone(),Hook{reached,release:waiting}).is_none());
            (Self{key,release:Some(release)},arrived)
        }
        pub(super) fn release(&mut self){if let Some(release)=self.release.take(){let _=release.send(());}}
    }
    impl Drop for LeafLatch {fn drop(&mut self){hooks().lock().remove(&self.key);self.release();}}
    pub(super) fn before_leaf_open(root:&File,relative:&str) {
        let Ok(meta)=root.metadata() else{return;};let hook=hooks().lock().remove(&(meta.dev(),meta.ino(),relative.to_owned()));
        if let Some(hook)=hook{let _=hook.reached.send(());let _=hook.release.recv();}
    }
    pub(super) fn replace_with_fifo(path:&Path) {
        std::fs::remove_file(path).unwrap();make_fifo(path);
    }
    pub(super) fn make_fifo(path:&Path) {
        let path=CString::new(path.as_os_str().as_bytes()).unwrap();assert_eq!(unsafe{libc::mkfifo(path.as_ptr(),0o600)},0,"{}",io::Error::last_os_error());
    }
    enum Signal {Arm,Joined,Cleanup}
    /// Only the failure watchdog uses a proc path, anchored to this exact FIFO
    /// inode through O_PATH. It cannot admit a path to the production reader.
    /// Opening its writer is NONBLOCK too, so neither watchdog branch can wait
    /// for a peer. The old blocking implementation is rescued and FAILS the test.
    pub(super) struct Watchdog {
        signal:Option<mpsc::Sender<Signal>>,thread:Option<thread::JoinHandle<io::Result<Option<File>>>>,
        rescued:Arc<AtomicBool>,
    }
    impl Watchdog {
        pub(super) fn new(path:&Path)->Self {
            let name=CString::new(path.as_os_str().as_bytes()).unwrap();
            let fd=unsafe{libc::open(name.as_ptr(),libc::O_PATH|libc::O_NOFOLLOW|libc::O_CLOEXEC)};
            assert!(fd>=0,"{}",io::Error::last_os_error());let anchor=unsafe{File::from_raw_fd(fd)};
            // Prove the exact-inode rescue can be opened before any tested work.
            let exact=CString::new(format!("/proc/self/fd/{}",anchor.as_raw_fd())).unwrap();
            let probe=unsafe{libc::open(exact.as_ptr(),libc::O_RDWR|libc::O_NONBLOCK|libc::O_CLOEXEC)};
            assert!(probe>=0,"{}",io::Error::last_os_error());drop(unsafe{File::from_raw_fd(probe)});
            let(signal,receive)=mpsc::channel();let rescued=Arc::new(AtomicBool::new(false));let rescue=rescued.clone();
            let thread=thread::spawn(move||{
                let needed=match receive.recv(){
                    Ok(Signal::Arm)=>!matches!(receive.recv_timeout(std::time::Duration::from_secs(3)),Ok(Signal::Joined)),
                    Ok(Signal::Joined)=>false,Ok(Signal::Cleanup)|Err(_)=>true,
                };
                if !needed{return Ok(None);}
                rescue.store(true,Ordering::SeqCst);
                let exact=CString::new(format!("/proc/self/fd/{}",anchor.as_raw_fd())).unwrap();
                let fd=unsafe{libc::open(exact.as_ptr(),libc::O_RDWR|libc::O_NONBLOCK|libc::O_CLOEXEC)};
                if fd<0{return Err(io::Error::last_os_error());}
                let writer=unsafe{File::from_raw_fd(fd)};
                // Keep the writer until the actual request/scope join or test
                // cleanup; a failed assertion cannot strand a reader in open().
                let _=receive.recv();Ok(Some(writer))
            });
            Self{signal:Some(signal),thread:Some(thread),rescued}
        }
        pub(super) fn arm(&self){self.signal.as_ref().unwrap().send(Signal::Arm).unwrap();}
        pub(super) fn joined(mut self)->io::Result<bool>{
            if let Some(signal)=self.signal.take(){let _=signal.send(Signal::Joined);}
            let writer=self.thread.take().unwrap().join().map_err(|_|io::Error::other("FIFO watchdog panicked"))??;
            drop(writer);Ok(self.rescued.load(Ordering::SeqCst))
        }
    }
    impl Drop for Watchdog {
        fn drop(&mut self){
            if let Some(signal)=self.signal.take(){let _=signal.send(Signal::Cleanup);}
            if let Some(thread)=self.thread.take(){
                // On assertion unwind no join has been proved. Keep this single
                // failure-only rescue FD until test-process exit so even a late
                // blocked opener cannot miss its peer. Normal joined() drops it.
                if let Ok(Ok(Some(writer)))=thread.join(){std::mem::forget(writer);}
            }
        }
    }
}
