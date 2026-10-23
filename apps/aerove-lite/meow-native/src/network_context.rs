use meow_common::socket_protect::{HostResolver, NetworkScope, SocketProtector};
use meow_config::owned_load::{LoadContext, OwnedWork};
use parking_lot::RwLock;
use std::{future::Future, io, net::SocketAddr, os::fd::RawFd, sync::{Arc, Weak, OnceLock}};
use tokio_util::sync::CancellationToken;

#[derive(Clone)]
pub struct NetworkContext {
    scope: NetworkScope,
    cancel: CancellationToken,
    network_handle: Option<u64>,
    physical_dns: Option<Vec<SocketAddr>>,
    physical_dns_plan: Option<Arc<crate::runtime_plan::PhysicalDns>>,
    plane: Option<Arc<crate::providers::ControlPlane>>,
    resource_roots: Option<Arc<crate::resource_roots::ResourceRoots>>,
    live: Option<Arc<NetworkContextSlot>>,
    live_weak: Option<Weak<NetworkContextSlot>>,
    resolver_weak: Option<Weak<meow_dns::Resolver>>,
    protector: Option<Arc<dyn SocketProtector>>,
    dns_owner: Option<Arc<crate::physical_dns::PhysicalDnsOwner>>,
    dns_transport: Option<meow_common::transport_tasks::TransportScope>,
    core_resolver: Option<Arc<meow_dns::Resolver>>,
    request_policy: Option<meow_common::request_policy::PolicyScope>,
    transport: Option<Arc<crate::transport_tasks::CapturedTransport>>,
    probe: Option<meow_common::probe_policy::ProbeScope>,
    tunnel_capture: Option<Arc<OnceLock<Weak<TunnelCapture>>>>,
}
pub(crate) struct TunnelCapture { pub tunnel: meow_tunnel::Tunnel }

#[derive(Clone)]
struct NetworkBinding { scope: NetworkScope, network_handle: Option<u64>, physical_dns: Option<Vec<SocketAddr>>, physical_dns_plan: Option<Arc<crate::runtime_plan::PhysicalDns>>,
    dns_owner: Option<Arc<crate::physical_dns::PhysicalDnsOwner>>, dns_transport: Option<meow_common::transport_tasks::TransportScope>, core_resolver: Option<Arc<meow_dns::Resolver>> }
pub struct NetworkContextSlot { current: RwLock<NetworkBinding> }
impl NetworkContextSlot {
    pub fn new(initial: &NetworkContext) -> Arc<Self> {
        Arc::new(Self { current: RwLock::new(NetworkBinding { scope: initial.scope.clone(), network_handle: initial.network_handle, physical_dns: initial.physical_dns.clone(), physical_dns_plan: initial.physical_dns_plan.clone(),
            dns_owner: initial.dns_owner.clone(), dns_transport: initial.dns_transport.clone(), core_resolver: initial.core_resolver.clone() }) })
    }
    pub fn view(self: &Arc<Self>, base: &NetworkContext) -> NetworkContext { NetworkContext { live: Some(self.clone()), live_weak: None, ..base.clone() } }
    pub fn replace(&self, next: &NetworkContext) -> NetworkContext {
        let mut current = self.current.write();
        let old = NetworkContext { scope: current.scope.clone(), cancel: next.cancel.clone(), network_handle: current.network_handle,
            physical_dns: current.physical_dns.clone(), physical_dns_plan: current.physical_dns_plan.clone(), plane: next.plane.clone(), resource_roots: next.resource_roots.clone(), live: None, live_weak: None, resolver_weak: None,
            protector: next.protector.clone(), dns_owner: current.dns_owner.clone(), dns_transport: current.dns_transport.clone(), core_resolver: current.core_resolver.clone(), request_policy: next.request_policy.clone(), transport: next.transport.clone(), probe: next.probe.clone(), tunnel_capture: next.tunnel_capture.clone() };
        *current = NetworkBinding { scope: next.scope.clone(), network_handle: next.network_handle, physical_dns: next.physical_dns.clone(), physical_dns_plan: next.physical_dns_plan.clone(),
            dns_owner: next.dns_owner.clone(), dns_transport: next.dns_transport.clone(), core_resolver: next.core_resolver.clone() };
        old
    }
    pub(crate) fn retire_after_join(&self) {
        let retired = {
            let mut current = self.current.write();
            let empty = NetworkBinding { scope: NetworkScope { protector: None, resolver: None }, network_handle: current.network_handle,
                physical_dns: current.physical_dns.clone(), physical_dns_plan: current.physical_dns_plan.clone(), dns_owner: None, dns_transport: None, core_resolver: None };
            std::mem::replace(&mut *current,empty)
        };
        drop(retired);
    }
    fn capture(&self) -> NetworkBinding { self.current.read().clone() }
    pub fn current_dns_owner(&self) -> Option<Arc<crate::physical_dns::PhysicalDnsOwner>> { self.current.read().dns_owner.clone() }
    pub fn current_core_resolver(&self) -> Option<Arc<meow_dns::Resolver>> { self.current.read().core_resolver.clone() }
    pub fn current_identity(&self) -> (Option<u64>, Option<Vec<SocketAddr>>) {
        let current = self.current.read(); (current.network_handle, current.physical_dns.clone())
    }
}
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
struct UnavailableProtector { cancel: CancellationToken }
impl SocketProtector for UnavailableProtector {
    fn protect(&self, _: RawFd) -> io::Result<()> {
        if self.cancel.is_cancelled() { return Err(cancelled()); }
        Err(io::Error::new(io::ErrorKind::NotConnected,"no captured physical network"))
    }
}
fn binding_protector(callback: Option<Arc<dyn SocketProtector>>, cancel: CancellationToken,
    handle: Option<u64>, owner: Option<&Arc<crate::physical_dns::PhysicalDnsOwner>>) -> Arc<dyn SocketProtector> {
    if handle.is_none() && owner.is_some_and(|owner|owner.is_unavailable_not_connected()) {
        Arc::new(UnavailableProtector { cancel })
    } else { Arc::new(FencedProtector { callback, cancel, network_handle: handle }) }
}
fn cancelled() -> io::Error { io::Error::new(io::ErrorKind::Interrupted, "captured network owner cancelled") }
impl NetworkContext {
    pub fn protected(callback: Arc<dyn SocketProtector>, resolver: Option<Arc<dyn HostResolver>>, cancel: CancellationToken) -> Self {
        Self { scope: NetworkScope { protector: Some(Arc::new(FencedProtector { callback: Some(callback.clone()), cancel: cancel.clone(), network_handle: None })), resolver },
            cancel, network_handle: None, physical_dns: None, physical_dns_plan: None, plane: None, resource_roots: None, live: None, live_weak: None, resolver_weak: None, protector: Some(callback), dns_owner: None, dns_transport: None, core_resolver: None, request_policy: None, transport: None, probe: None, tunnel_capture: None }
    }
    pub fn protected_on_network(callback: Arc<dyn SocketProtector>, resolver: Option<Arc<dyn HostResolver>>, network: u64, cancel: CancellationToken) -> io::Result<Self> {
        if network == 0 { return Err(io::Error::new(io::ErrorKind::InvalidInput, "network handle must be nonzero")); }
        #[cfg(not(target_os = "android"))]
        { let _ = (callback, resolver, cancel); return Err(io::Error::from(io::ErrorKind::Unsupported)); }
        #[cfg(target_os = "android")]
        Ok(Self { scope: NetworkScope { protector: Some(Arc::new(FencedProtector { callback: Some(callback.clone()), cancel: cancel.clone(), network_handle: Some(network) })), resolver },
            cancel, network_handle: Some(network), physical_dns: None, physical_dns_plan: None, plane: None, resource_roots: None, live: None, live_weak: None, resolver_weak: None, protector: Some(callback), dns_owner: None, dns_transport: None, core_resolver: None, request_policy: None, transport: None, probe: None, tunnel_capture: None })
    }
    pub fn platform(cancel: CancellationToken) -> Self {
        Self { scope: NetworkScope { protector: Some(Arc::new(FencedProtector { callback: None, cancel: cancel.clone(), network_handle: None })), resolver: None },
            cancel, network_handle: None, physical_dns: None, physical_dns_plan: None, plane: None, resource_roots: None, live: None, live_weak: None, resolver_weak: None, protector: None, dns_owner: None, dns_transport: None, core_resolver: None, request_policy: None, transport: None, probe: None, tunnel_capture: None }
    }
    pub fn platform_on_network(network: u64, cancel: CancellationToken) -> io::Result<Self> {
        if network == 0 { return Err(io::Error::new(io::ErrorKind::InvalidInput, "network handle must be nonzero")); }
        #[cfg(not(target_os = "android"))]
        { let _ = cancel; Err(io::Error::from(io::ErrorKind::Unsupported)) }
        #[cfg(target_os = "android")]
        Ok(Self { scope: NetworkScope { protector: Some(Arc::new(FencedProtector { callback: None, cancel: cancel.clone(), network_handle: Some(network) })), resolver: None },
            cancel, network_handle: Some(network), physical_dns: None, physical_dns_plan: None, plane: None, resource_roots: None, live: None, live_weak: None, resolver_weak: None, protector: None, dns_owner: None, dns_transport: None, core_resolver: None, request_policy: None, transport: None, probe: None, tunnel_capture: None })
    }
    pub fn with_request_policy(&self, source: Arc<dyn meow_common::request_policy::PolicySource>) -> Self {
        Self { request_policy: Some(meow_common::request_policy::PolicyScope::new(source)), ..self.clone() }
    }
    pub fn with_probe_policy(&self, probe: meow_common::probe_policy::ProbeScope) -> Self {
        Self { probe: Some(probe), ..self.clone() }
    }
    pub(crate) fn with_tunnel_capture(&self, capture: Arc<OnceLock<Weak<TunnelCapture>>>) -> Self {
        Self { tunnel_capture: Some(capture), ..self.clone() }
    }
    pub(crate) fn with_transport(&self, tasks: Arc<crate::transport_tasks::TransportTasks>, scope: crate::transport_tasks::ScopeSlot) -> Self {
        let base = Self { transport: None, ..self.clone() };
        Self { transport: Some(crate::transport_tasks::CapturedTransport::new(base, tasks, scope)), ..self.clone() }
    }
    pub fn with_resource_roots(&self, roots: Arc<crate::resource_roots::ResourceRoots>) -> Self { Self { resource_roots: Some(roots), ..self.clone() } }
    pub fn with_control_plane(&self, plane: Arc<crate::providers::ControlPlane>) -> Self { Self { plane: Some(plane), ..self.clone() } }
    pub fn with_physical_dns(&self, servers: Vec<SocketAddr>) -> Self { Self { physical_dns: Some(servers), ..self.clone() } }
    pub(crate) fn with_physical_dns_plan(&self, snapshot: crate::runtime_plan::PhysicalDns) -> Self { Self { physical_dns_plan: Some(Arc::new(snapshot)), ..self.clone() } }
    pub(crate) fn captured_dns_plan(&self) -> Option<Arc<crate::runtime_plan::PhysicalDns>> { self.physical_dns_plan.clone() }
    #[cfg(test)]
    pub(crate) fn fixture_network_identity(&self, handle: u64) -> Self { Self { network_handle: Some(handle), ..self.captured() } }

    pub fn with_dns_owner(&self, owner: Arc<crate::physical_dns::PhysicalDnsOwner>) -> Self { Self { dns_owner: Some(owner), ..self.clone() } }
    pub(crate) fn with_dns_transport(&self, scope: meow_common::transport_tasks::TransportScope) -> Self { Self { dns_transport: Some(scope), ..self.clone() } }
    pub(crate) fn dns_binding_context(&self) -> Self {
        let captured = self.captured();
        Self { live: None, live_weak: None, core_resolver: None, resolver_weak: None, transport: None, dns_transport: None, dns_owner: None, ..captured }
    }
    pub(crate) fn unavailable_dns_binding_context(&self) -> Self {
        let mut captured = self.dns_binding_context();
        captured.scope.protector = Some(Arc::new(UnavailableProtector { cancel: captured.cancel.clone() }));
        captured
    }
    fn captured_dns_transport(&self) -> Option<meow_common::transport_tasks::TransportScope> {
        self.live_slot().as_ref().map_or_else(|| self.dns_transport.clone(), |slot|slot.capture().dns_transport)
    }

    pub fn with_core_resolver(&self, resolver: Arc<meow_dns::Resolver>) -> Self { Self { core_resolver: Some(resolver), ..self.clone() } }
    fn live_slot(&self) -> Option<Arc<NetworkContextSlot>> { self.live.clone().or_else(|| self.live_weak.as_ref().and_then(Weak::upgrade)) }
    pub(crate) fn transport_context(&self) -> Self {
        let mut context = Self { transport: None, ..self.clone() };
        if let Some(slot) = context.live.take() { context.live_weak = Some(Arc::downgrade(&slot)); }
        if let Some(resolver) = context.core_resolver.take() { context.resolver_weak = Some(Arc::downgrade(&resolver)); }
        context
    }
    pub fn dns_owner(&self) -> Option<Arc<crate::physical_dns::PhysicalDnsOwner>> { self.live_slot().as_ref().map_or_else(|| self.dns_owner.clone(), |slot| slot.capture().dns_owner) }
    pub fn network_handle(&self) -> Option<u64> { self.live_slot().as_ref().map_or(self.network_handle, |slot| slot.capture().network_handle) }
    pub fn physical_dns_snapshot(&self) -> Option<Vec<SocketAddr>> { self.live_slot().as_ref().map_or_else(|| self.physical_dns.clone(), |slot| slot.capture().physical_dns) }
    fn current_scope(&self) -> NetworkScope {
        let Some(slot) = self.live_slot() else { return self.scope.clone(); };
        let binding = slot.capture();
        NetworkScope { protector: Some(binding_protector(self.protector.clone(),self.cancel.clone(),binding.network_handle,binding.dns_owner.as_ref())),
            resolver: binding.scope.resolver }
    }
    pub fn captured_with_resolver(&self) -> (Self, Option<Arc<meow_dns::Resolver>>) {
        let Some(slot) = self.live_slot() else {
            let mut captured = self.clone();
            if captured.network_handle.is_none() && captured.dns_owner.as_ref().is_some_and(|owner|owner.is_unavailable_not_connected()) {
                captured.scope.protector = Some(binding_protector(captured.protector.clone(),captured.cancel.clone(),captured.network_handle,captured.dns_owner.as_ref()));
            }
            if let Some(transport) = &self.transport { captured.transport = Some(transport.with_network(Self { transport: None, ..captured.clone() })); }
            let resolver = self.core_resolver.clone().or_else(|| self.resolver_weak.as_ref().and_then(Weak::upgrade));
            return (captured,resolver);
        };
        let current = slot.capture();
        let scope = NetworkScope { protector: Some(binding_protector(self.protector.clone(),self.cancel.clone(),current.network_handle,current.dns_owner.as_ref())),
            resolver: current.scope.resolver };
        let resolver = current.core_resolver.clone();
        let mut captured = Self { scope, cancel: self.cancel.clone(), network_handle: current.network_handle, physical_dns: current.physical_dns, physical_dns_plan: current.physical_dns_plan,
            plane: self.plane.clone(), resource_roots: self.resource_roots.clone(), live: None, live_weak: None, resolver_weak: None, protector: self.protector.clone(), dns_owner: current.dns_owner, dns_transport: current.dns_transport,
            core_resolver: resolver.clone(), request_policy: self.request_policy.clone(), transport: self.transport.clone(), probe: self.probe.clone(), tunnel_capture: self.tunnel_capture.clone() };
        if let Some(transport) = &self.transport {
            captured.transport = Some(transport.with_network(Self { transport: None, ..captured.clone() }));
        }
        (captured, resolver)
    }
    pub fn captured(&self) -> Self { self.captured_with_resolver().0 }
    pub fn rebind_network(&self, network: u64) -> io::Result<Self> {
        if network == 0 { return Err(io::Error::new(io::ErrorKind::InvalidInput, "network handle must be nonzero")); }
        #[cfg(not(target_os = "android"))]
        { let _ = network; return Err(io::Error::from(io::ErrorKind::Unsupported)); }
        #[cfg(target_os = "android")]
        Ok(Self { scope: NetworkScope { protector: Some(Arc::new(FencedProtector { callback: self.protector.clone(), cancel: self.cancel.clone(), network_handle: Some(network) })), resolver: None },
            cancel: self.cancel.clone(), network_handle: Some(network), physical_dns: None, physical_dns_plan: None, plane: self.plane.clone(), resource_roots: self.resource_roots.clone(), live: None, live_weak: None, resolver_weak: None,
            protector: self.protector.clone(), dns_owner: None, dns_transport: None, core_resolver: None, request_policy: self.request_policy.clone(), transport: self.transport.clone(), probe: self.probe.clone(), tunnel_capture: self.tunnel_capture.clone() })
    }
    pub fn with_resolver(&self, resolver: Arc<dyn HostResolver>) -> Self {
        let callback = self.protector.clone();
        let network = self.network_handle();
        Self { scope: NetworkScope { protector: Some(Arc::new(FencedProtector { callback, cancel: self.cancel.clone(), network_handle: network })), resolver: Some(resolver) },
            cancel: self.cancel.clone(), network_handle: self.network_handle, physical_dns: self.physical_dns_snapshot(), physical_dns_plan: self.physical_dns_plan.clone(), plane: self.plane.clone(),
            resource_roots: self.resource_roots.clone(), live: self.live.clone(), live_weak: self.live_weak.clone(), resolver_weak: self.resolver_weak.clone(), protector: self.protector.clone(), dns_owner: self.dns_owner.clone(), dns_transport: self.dns_transport.clone(), core_resolver: self.core_resolver.clone(), request_policy: self.request_policy.clone(), transport: self.transport.clone(), probe: self.probe.clone(), tunnel_capture: self.tunnel_capture.clone() }
    }
    pub fn child(&self) -> Self {
        let cancel = self.cancel.child_token();
        let current = self.captured();
        let network_handle = current.network_handle;
        let protector = Some(binding_protector(current.protector.clone(),cancel.clone(),network_handle,current.dns_owner.as_ref()));
        Self { scope: NetworkScope { protector, resolver: current.scope.resolver.clone() }, cancel, network_handle, physical_dns: current.physical_dns, physical_dns_plan: current.physical_dns_plan,
            plane: self.plane.clone(), resource_roots: self.resource_roots.clone(), live: self.live.clone(), live_weak: self.live_weak.clone(), resolver_weak: self.resolver_weak.clone(), protector: self.protector.clone(), dns_owner: self.dns_owner.clone(), dns_transport: self.dns_transport.clone(),
            core_resolver: current.core_resolver, request_policy: self.request_policy.clone(), transport: self.transport.clone(), probe: self.probe.clone(), tunnel_capture: self.tunnel_capture.clone() }
    }
    fn bound_scope<F: Future>(&self, work: F) -> impl Future<Output = F::Output> {
        self.bound_scope_boxed(Box::pin(work))
    }
    async fn bound_scope_boxed<F: Future>(&self, work: std::pin::Pin<Box<F>>) -> F::Output {
        let network = self.current_scope();
        let protected = async { match &self.request_policy {
            Some(policy) => policy.scope(network.scope(work)).await,
            None => network.scope(work).await,
        } };
        let probed = async { match &self.probe { Some(probe) => probe.scope(protected).await, None => protected.await } };
        match &self.transport {
            Some(context) => meow_common::transport_tasks::TransportScope::new(context.clone()).scope(probed).await,
            None => probed.await,
        }
    }
    pub fn scope<F: Future>(&self, work: F) -> impl Future<Output = F::Output> {
        self.scope_captured(Box::pin(work))
    }
    async fn scope_captured<F: Future>(&self, work: std::pin::Pin<Box<F>>) -> F::Output {
        let (captured, resolver) = self.captured_with_resolver();
        let owner = captured.tunnel_capture.as_ref().and_then(|slot| slot.get()).and_then(Weak::upgrade);
        let scoped = Box::pin(captured.bound_scope_boxed(work));
        match (owner, resolver) {
            (Some(owner), Some(resolver)) => owner.tunnel.with_resolver_snapshot(resolver, scoped).await,
            _ => scoped.await,
        }
    }
    pub(crate) fn serve_scope<F: Future>(&self, work: F) -> impl Future<Output = F::Output> { self.bound_scope(work) }

    pub fn cancelled(&self) -> bool { self.cancel.is_cancelled() }
    pub(crate) fn cancellation(&self) -> CancellationToken { self.cancel.clone() }
    pub async fn cancelled_signal(&self) { self.cancel.cancelled().await; }
    pub fn cancel(&self) { self.cancel.cancel(); }
}
pub(crate) fn scope_router(router: axum::Router, context: &NetworkContext) -> axum::Router {
    router.layer(axum::middleware::from_fn_with_state(context.clone(), |axum::extract::State(context): axum::extract::State<NetworkContext>, request: axum::extract::Request, next: axum::middleware::Next| async move {
        context.scope(next.run(request)).await
    }))
}

impl LoadContext for NetworkContext {
    fn requires_dns_transport_scope(&self) -> bool { true }
    fn dns_transport_scope(&self) -> Option<meow_common::transport_tasks::TransportScope> { self.captured_dns_transport() }
    fn requires_provider_roots(&self) -> bool { true }
    fn provider_roots(&self) -> Option<meow_config::owned_load::ProviderRoots> {
        if self.cancelled() { return None; }
        self.resource_roots.as_ref().and_then(|roots| roots.provider_roots().ok())
    }
    fn provider_observer(&self) -> Option<Arc<dyn meow_config::provider_events::ProviderObserver>> {
        self.plane.clone().map(|plane| plane as Arc<dyn meow_config::provider_events::ProviderObserver>)
    }
    fn disabled_dns_source(&self) -> Result<meow_config::owned_load::DisabledDnsSource, &'static str> {
        use meow_config::owned_load::DisabledDnsSource;
        let captured = self.captured();
        if captured.network_handle.is_none() && captured.dns_owner.as_ref().is_some_and(|owner|owner.is_unavailable_not_connected()) {
            return Ok(DisabledDnsSource::UnavailableNotConnected);
        }
        let servers = captured.physical_dns.ok_or("physicalDnsSnapshotMissing")?;
        if servers.is_empty() { return Err("physicalDnsSnapshotEmpty"); }
        if servers.iter().any(|server|server.port()==0 || server.ip().is_unspecified() || server.ip().is_multicast()) { return Err("physicalDnsSnapshotInvalid"); }
        Ok(DisabledDnsSource::Physical(servers))
    }
    fn requires_physical_dns(&self) -> bool { true }
    fn physical_dns_servers(&self) -> Option<Vec<SocketAddr>> { self.physical_dns_snapshot() }
    fn wrap(&self, work: OwnedWork) -> OwnedWork {
        let context = self.clone(); Box::pin(async move { context.scope(work).await })
    }
    fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) {
        let captured = self.captured();
        let protected = || match &captured.request_policy {
            Some(policy) => policy.run_blocking(|| captured.current_scope().run_blocking(work)),
            None => captured.current_scope().run_blocking(work),
        };
        match &captured.transport {
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
    { if unsafe { android_setsocknetwork(network, fd) } != 0 { return Err(io::Error::last_os_error()); } Ok(()) }
    #[cfg(not(target_os = "android"))]
    { let _ = (network, fd); Err(io::Error::from(io::ErrorKind::Unsupported)) }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr};

    #[test]
    fn captured_context_stays_old_while_live_view_moves_forward() {
        let session_cancel = CancellationToken::new();
        let old_dns = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 53)), 53);
        let new_dns = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 51, 100, 53)), 53);
        let old_resolver = Arc::new(meow_dns::Resolver::new(vec![old_dns], Vec::new(), meow_common::DnsMode::Normal, Default::default(), false, false));
        let new_resolver = Arc::new(meow_dns::Resolver::new(vec![new_dns], Vec::new(), meow_common::DnsMode::Normal, Default::default(), false, false));
        let initial = NetworkContext::platform(session_cancel.clone()).with_physical_dns(vec![old_dns]).with_core_resolver(old_resolver.clone());
        let slot = NetworkContextSlot::new(&initial);
        let live = slot.view(&initial);
        let old_flow = live.captured();

        let next = NetworkContext::platform(session_cancel.clone()).with_physical_dns(vec![new_dns]).with_core_resolver(new_resolver.clone());
        let retired = slot.replace(&next);
        let (old_snapshot, old_dns_resolver) = old_flow.captured_with_resolver();
        let (new_snapshot, new_dns_resolver) = live.captured_with_resolver();

        assert_eq!(old_flow.physical_dns_snapshot(), Some(vec![old_dns]));
        assert_eq!(retired.physical_dns_snapshot(), Some(vec![old_dns]));
        assert_eq!(live.physical_dns_snapshot(), Some(vec![new_dns]));
        assert_eq!(old_snapshot.physical_dns_snapshot(), Some(vec![old_dns]));
        assert_eq!(new_snapshot.physical_dns_snapshot(), Some(vec![new_dns]));
        assert!(Arc::ptr_eq(old_dns_resolver.as_ref().unwrap(), &old_resolver));
        assert!(Arc::ptr_eq(new_dns_resolver.as_ref().unwrap(), &new_resolver));
        assert!(!session_cancel.is_cancelled(), "network replacement must not cancel the session scope");
    }
    #[test]
    fn persistent_transport_capture_does_not_keep_live_registry_or_resolver_alive() {
        let resolver = Arc::new(meow_dns::Resolver::new(vec!["127.0.0.1:53".parse().unwrap()], Vec::new(), meow_common::DnsMode::Normal, Default::default(), false, false));
        let weak_resolver = Arc::downgrade(&resolver);
        let base = NetworkContext::platform(CancellationToken::new()).with_core_resolver(resolver.clone());
        let slot = NetworkContextSlot::new(&base); let weak_slot = Arc::downgrade(&slot);
        let live = slot.view(&base);
        let persistent = live.transport_context();
        assert!(persistent.live.is_none()); assert!(persistent.core_resolver.is_none());
        let (flow, captured) = persistent.captured_with_resolver();
        drop((live,base,slot,resolver));
        assert!(weak_slot.upgrade().is_none());
        assert!(weak_resolver.upgrade().is_some(), "the active flow must retain its resolver");
        drop((flow,captured));
        assert!(weak_resolver.upgrade().is_none(), "an idle protocol pool must not retain the resolver registry");
    }
    #[tokio::test]
    async fn platform_child_preserves_explicit_dial_policy_and_cancellation() {
        use tokio::{net::TcpListener,time::{timeout,Duration}};
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let child = NetworkContext::platform(CancellationToken::new()).child();
        let connection = timeout(Duration::from_secs(2),child.scope(meow_common::socket_protect::connect_tcp(listener.local_addr().unwrap()))).await.unwrap().unwrap();
        let (peer,_) = timeout(Duration::from_secs(2),listener.accept()).await.unwrap().unwrap();
        drop((connection,peer)); child.cancel();
        assert!(child.scope(meow_common::socket_protect::connect_tcp(listener.local_addr().unwrap())).await.is_err());
    }
    struct CountProtect(Arc<std::sync::atomic::AtomicUsize>);
    impl SocketProtector for CountProtect {
        fn protect(&self, _: RawFd) -> io::Result<()> {
            self.0.fetch_add(1, std::sync::atomic::Ordering::SeqCst); Ok(())
        }
    }
    #[tokio::test]
    async fn same_http_connection_refreshes_network_per_request_without_rebinding_old_flow() {
        use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}, time::{timeout, Duration}};
        use std::sync::atomic::{AtomicUsize, Ordering};
        async fn request(stream: &mut TcpStream) {
            stream.write_all(b"GET /network HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n").await.unwrap();
            let mut header = Vec::new();
            while !header.ends_with(b"\r\n\r\n") { header.push(stream.read_u8().await.unwrap()); assert!(header.len()<8192); }
            assert!(header.starts_with(b"HTTP/1.1 200"));
            let mut body = [0;2]; stream.read_exact(&mut body).await.unwrap(); assert_eq!(&body,b"ok");
        }
        let cancel = CancellationToken::new();
        let old_count = Arc::new(AtomicUsize::new(0)); let new_count = Arc::new(AtomicUsize::new(0));
        let old = NetworkContext::protected(Arc::new(CountProtect(old_count.clone())),None,cancel.clone());
        let slot = NetworkContextSlot::new(&old); let live = slot.view(&old);
        let pinned_connection = live.captured();
        let router = scope_router(axum::Router::new().route("/network", axum::routing::get(|| async {
            NetworkScope::current().unwrap().protector.unwrap().protect(-1).unwrap(); "ok"
        })), &live);
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (stream,_) = listener.accept().await.unwrap();
            pinned_connection.scope(crate::api::serve_connection(stream,router)).await;
        });
        let mut stream = TcpStream::connect(address).await.unwrap();
        timeout(Duration::from_secs(5),request(&mut stream)).await.unwrap();
        assert_eq!(old_count.load(Ordering::SeqCst),1); assert_eq!(new_count.load(Ordering::SeqCst),0);
        let next = NetworkContext::protected(Arc::new(CountProtect(new_count.clone())),None,cancel.clone());
        drop(slot.replace(&next));
        timeout(Duration::from_secs(5),request(&mut stream)).await.unwrap();
        assert_eq!(old_count.load(Ordering::SeqCst),1); assert_eq!(new_count.load(Ordering::SeqCst),1);
        assert!(!cancel.is_cancelled());
        drop(stream); timeout(Duration::from_secs(5),server).await.unwrap().unwrap();
    }

}

#[cfg(test)]
mod scope_layout_tests {
    use super::*;
    use std::{pin::Pin, sync::atomic::{AtomicUsize, Ordering}, task::{Context, Poll}};

    struct LargeWork { bytes: [u8; 32768], polls: Arc<AtomicUsize> }
    impl Future for LargeWork {
        type Output = usize;
        fn poll(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<usize> {
            assert!(NetworkScope::is_captured());
            self.polls.fetch_add(1, Ordering::SeqCst);
            Poll::Ready(usize::from(self.bytes[0]) + usize::from(self.bytes[32767]))
        }
    }
    // Preserve the failed source's inline layout only to measure this regression.
    async fn prior_inline_scope<F: Future>(context: &NetworkContext, work: F) -> F::Output {
        let protected = async { match &context.request_policy {
            Some(policy) => policy.scope(context.scope.scope(work)).await,
            None => context.scope.scope(work).await,
        } };
        let probed = async { match &context.probe { Some(probe) => probe.scope(protected).await, None => protected.await } };
        match &context.transport {
            Some(owner) => meow_common::transport_tasks::TransportScope::new(owner.clone()).scope(probed).await,
            None => probed.await,
        }
    }
    #[tokio::test]
    async fn boxed_scope_bounds_layout_and_preserves_lazy_first_poll_and_context() {
        let context = NetworkContext::platform(CancellationToken::new());
        let polls = Arc::new(AtomicUsize::new(0));
        fn layout<F: Future>(_: impl FnOnce() -> F) -> usize { std::mem::size_of::<F>() }
        let before = layout(|| prior_inline_scope(&context, LargeWork { bytes: [7; 32768], polls: polls.clone() }));
        let work = context.scope(LargeWork { bytes: [7; 32768], polls: polls.clone() });
        let after = std::mem::size_of_val(&work);
        eprintln!("[network-scope-layout] input={} inline={before} boxed={after} before-first-poll", std::mem::size_of::<LargeWork>());
        assert_eq!(polls.load(Ordering::SeqCst), 0);
        assert!(after < 2048, "boxed scope must not embed the input future");
        assert!(before > after * 16, "the failed inline layout must remain represented");
        assert_eq!(work.await, 14);
        assert_eq!(polls.load(Ordering::SeqCst), 1);
        assert!(!NetworkScope::is_captured());
        eprintln!("[network-scope-layout] first-poll-complete context-restored");
    }
}
