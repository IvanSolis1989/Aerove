use meow_common::socket_protect::{HostResolver, NetworkScope, SocketProtector};
use meow_config::owned_load::{LoadContext, OwnedWork};
use std::{future::Future, io, net::SocketAddr, os::fd::RawFd, sync::Arc};
use tokio_util::sync::CancellationToken;

#[derive(Clone)]
pub struct NetworkContext { scope: NetworkScope, cancel: CancellationToken, network_handle: Option<u64>, physical_dns: Option<Vec<SocketAddr>>, plane: Option<Arc<crate::providers::ControlPlane>>, resource_roots: Option<Arc<crate::resource_roots::ResourceRoots>>, request_policy: Option<meow_common::request_policy::PolicyScope>, transport: Option<Arc<crate::transport_tasks::CapturedTransport>>, probe: Option<meow_common::probe_policy::ProbeScope> }

struct FencedProtector { callback: Option<Arc<dyn SocketProtector>>, cancel: CancellationToken, network_handle: Option<u64> }
impl SocketProtector for FencedProtector {
    fn protect(&self, fd: RawFd) -> io::Result<()> {
        if self.cancel.is_cancelled() { return Err(cancelled()); }
        if let Some(network) = self.network_handle { bind_network(network, fd)?; }
        if let Some(callback) = &self.callback { callback.protect(fd)?; }
        if self.cancel.is_cancelled() { return Err(cancelled()); }
        Ok(())
    }
}
fn cancelled() -> io::Error { io::Error::new(io::ErrorKind::Interrupted, "captured network owner cancelled") }

impl NetworkContext {
    pub fn protected(callback: Arc<dyn SocketProtector>, resolver: Option<Arc<dyn HostResolver>>, cancel: CancellationToken) -> Self {
        Self { scope: NetworkScope { protector: Some(Arc::new(FencedProtector { callback: Some(callback), cancel: cancel.clone(), network_handle: None })), resolver }, cancel, network_handle: None, physical_dns: None, plane: None, resource_roots: None, request_policy: None, transport: None, probe: None }
    }
    pub fn protected_on_network(callback: Arc<dyn SocketProtector>, resolver: Option<Arc<dyn HostResolver>>, network: u64, cancel: CancellationToken) -> io::Result<Self> {
        if network == 0 { return Err(io::Error::new(io::ErrorKind::InvalidInput, "network handle must be nonzero")); }
        #[cfg(not(target_os = "android"))]
        { let _ = (callback, resolver, cancel); return Err(io::Error::from(io::ErrorKind::Unsupported)); }
        #[cfg(target_os = "android")]
        Ok(Self { scope: NetworkScope { protector: Some(Arc::new(FencedProtector { callback: Some(callback), cancel: cancel.clone(), network_handle: Some(network) })), resolver }, cancel, network_handle: Some(network), physical_dns: None, plane: None, resource_roots: None, request_policy: None, transport: None, probe: None })
    }
    pub fn platform(cancel: CancellationToken) -> Self {
        Self { scope: NetworkScope { protector: Some(Arc::new(FencedProtector { callback: None, cancel: cancel.clone(), network_handle: None })), resolver: None }, cancel, network_handle: None, physical_dns: None, plane: None, resource_roots: None, request_policy: None, transport: None, probe: None }
    }
    pub fn platform_on_network(network: u64, cancel: CancellationToken) -> io::Result<Self> {
        if network == 0 { return Err(io::Error::new(io::ErrorKind::InvalidInput, "network handle must be nonzero")); }
        #[cfg(not(target_os = "android"))]
        { let _ = cancel; Err(io::Error::from(io::ErrorKind::Unsupported)) }
        #[cfg(target_os = "android")]
        { Ok(Self { scope: NetworkScope { protector: Some(Arc::new(FencedProtector { callback: None, cancel: cancel.clone(), network_handle: Some(network) })), resolver: None }, cancel, network_handle: Some(network), physical_dns: None, plane: None, resource_roots: None, request_policy: None, transport: None, probe: None }) }
    }
    pub fn with_request_policy(&self, source: Arc<dyn meow_common::request_policy::PolicySource>) -> Self {
        Self { request_policy: Some(meow_common::request_policy::PolicyScope::new(source)), ..self.clone() }
    }
    pub fn with_resource_roots(&self, roots: Arc<crate::resource_roots::ResourceRoots>) -> Self {
        Self { resource_roots: Some(roots), ..self.clone() }
    }
    pub fn with_control_plane(&self, plane: Arc<crate::providers::ControlPlane>) -> Self {
        Self { plane: Some(plane), ..self.clone() }
    }
    pub fn with_physical_dns(&self, servers: Vec<SocketAddr>) -> Self {
        Self { physical_dns: Some(servers), ..self.clone() }
    }
    pub fn with_probe_policy(&self, probe: meow_common::probe_policy::ProbeScope) -> Self {
        Self { probe: Some(probe), ..self.clone() }
    }
    pub(crate) fn with_transport(&self, tasks: Arc<crate::transport_tasks::TransportTasks>, scope: crate::transport_tasks::ScopeSlot) -> Self {
        let base = Self { transport: None, ..self.clone() };
        Self { transport: Some(crate::transport_tasks::CapturedTransport::new(base, tasks, scope)), ..self.clone() }
    }
    pub fn network_handle(&self) -> Option<u64> { self.network_handle }
    pub fn with_resolver(&self, resolver: Arc<dyn HostResolver>) -> Self {
        Self { scope: NetworkScope { protector: self.scope.protector.clone(), resolver: Some(resolver) },
            cancel: self.cancel.clone(), network_handle: self.network_handle, physical_dns: self.physical_dns.clone(), plane: self.plane.clone(), resource_roots: self.resource_roots.clone(), request_policy: self.request_policy.clone(), transport: self.transport.clone(), probe: self.probe.clone() }
    }
    pub fn child(&self) -> Self {
        let cancel = self.cancel.child_token();
        let protector = self.scope.protector.as_ref().map(|callback| Arc::new(FencedProtector {
            callback: Some(callback.clone()), cancel: cancel.clone(), network_handle: None,
        }) as Arc<dyn SocketProtector>);
        Self { scope: NetworkScope { protector, resolver: self.scope.resolver.clone() }, cancel, network_handle: self.network_handle, physical_dns: self.physical_dns.clone(), plane: self.plane.clone(), resource_roots: self.resource_roots.clone(), request_policy: self.request_policy.clone(), transport: self.transport.clone(), probe: self.probe.clone() }
    }
    pub async fn scope<F: Future>(&self, work: F) -> F::Output {
        let protected = async { match &self.request_policy {
            Some(policy) => policy.scope(self.scope.scope(work)).await,
            None => self.scope.scope(work).await,
        } };
        let probed = async { match &self.probe { Some(probe) => probe.scope(protected).await, None => protected.await } };
        match &self.transport {
            Some(context) => meow_common::transport_tasks::TransportScope::new(context.clone()).scope(probed).await,
            None => probed.await,
        }
    }
    pub fn cancelled(&self) -> bool { self.cancel.is_cancelled() }
    pub(crate) fn cancellation(&self) -> CancellationToken { self.cancel.clone() }
    pub async fn cancelled_signal(&self) { self.cancel.cancelled().await; }
    pub fn cancel(&self) { self.cancel.cancel(); }
}

impl LoadContext for NetworkContext {
    fn requires_provider_roots(&self) -> bool { true }
    fn provider_roots(&self) -> Option<meow_config::owned_load::ProviderRoots> {
        if self.cancelled() { return None; }
        self.resource_roots.as_ref().and_then(|roots| roots.provider_roots().ok())
    }
    fn provider_observer(&self) -> Option<Arc<dyn meow_config::provider_events::ProviderObserver>> {
        self.plane.clone().map(|plane| plane as Arc<dyn meow_config::provider_events::ProviderObserver>)
    }
    fn requires_physical_dns(&self) -> bool { true }
    fn physical_dns_servers(&self) -> Option<Vec<SocketAddr>> { self.physical_dns.clone() }
    fn wrap(&self, work: OwnedWork) -> OwnedWork {
        let context = self.clone();
        Box::pin(async move { context.scope(work).await })
    }
    fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) {
        let protected = || match &self.request_policy {
            Some(policy) => policy.run_blocking(|| self.scope.run_blocking(work)),
            None => self.scope.run_blocking(work),
        };
        match &self.transport {
            Some(context) => meow_common::transport_tasks::TransportScope::new(context.clone()).run_blocking(protected),
            None => protected(),
        }
    }
}

#[cfg(target_os = "android")]
#[link(name = "android")]
unsafe extern "C" { fn android_setsocknetwork(network: u64, fd: i32) -> i32; }

fn bind_network(network: u64, fd: RawFd) -> io::Result<()> {
    #[cfg(target_os = "android")]
    {
        if unsafe { android_setsocknetwork(network, fd) } != 0 { return Err(io::Error::last_os_error()); }
        Ok(())
    }
    #[cfg(not(target_os = "android"))]
    { let _ = (network, fd); Err(io::Error::from(io::ErrorKind::Unsupported)) }
}
