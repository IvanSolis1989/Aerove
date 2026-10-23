use crate::{network_context::NetworkContext, runtime_plan::PhysicalDns};
use async_trait::async_trait;
use meow_common::{DnsMode, HostResolver};
use meow_config::owned_load::OwnedLoadScope;
use meow_dns::{Resolver, ResolverHostHook};
use parking_lot::Mutex;
use std::{ffi::CString, future::Future, pin::Pin, io, net::{IpAddr, SocketAddr, SocketAddrV6}, sync::{Arc, atomic::{AtomicBool, Ordering}}};
use tokio::sync::oneshot;
use tokio_util::{sync::CancellationToken};

pub struct PhysicalDnsOwner { cancel: CancellationToken, closed: AtomicBool, gate: Mutex<()>, tasks: Arc<crate::transport_tasks::TransportTasks>, joined: AtomicBool, loads: Arc<OwnedLoadScope>, unavailable_not_connected: AtomicBool }
impl PhysicalDnsOwner {
    fn new(network: &NetworkContext) -> Arc<Self> {
        Arc::new(Self { cancel: network.cancellation().child_token(), closed: AtomicBool::new(false), gate: Mutex::new(()),
            tasks: crate::transport_tasks::TransportTasks::new(), joined: AtomicBool::new(false), loads: OwnedLoadScope::new(), unavailable_not_connected: AtomicBool::new(false) })
    }
    pub(crate) fn run<T: Send + 'static>(self: &Arc<Self>, work: impl Future<Output = io::Result<T>> + Send + 'static)
        -> Pin<Box<dyn Future<Output = io::Result<T>> + Send>> {
        let work = Box::pin(work); let owner = self.clone();
        Box::pin(async move {
            let (tx, rx) = oneshot::channel();
            {
                let _gate = owner.gate.lock();
                if owner.closed.load(Ordering::Acquire) { return Err(cancelled()); }
            }
            let stop = owner.cancel.clone(); let loads = owner.loads.clone();
            owner.tasks.spawn_with(async move {
                let result = tokio::select! { biased;
                    _ = stop.cancelled() => Err(cancelled()),
                    result = loads.run(work) => result,
                };
                let _ = tx.send(result);
            }, |work| Ok(tokio::spawn(work)))?;
            rx.await.map_err(|_| cancelled())?
        })
    }
    pub(crate) fn transport_scope(&self, network: &NetworkContext) -> meow_common::transport_tasks::TransportScope {
        let slot = Arc::new(std::sync::OnceLock::new());
        slot.set(Arc::downgrade(&self.loads)).expect("new DNS generation load scope");
        let binding = if self.is_unavailable_not_connected() { network.unavailable_dns_binding_context() } else { network.dns_binding_context() };
        meow_common::transport_tasks::TransportScope::new(crate::transport_tasks::CapturedTransport::new(binding,self.tasks.clone(),slot))
    }
    pub(crate) fn is_unavailable_not_connected(&self) -> bool { self.unavailable_not_connected.load(Ordering::Acquire) }
    pub fn cleanup_confirmed(&self) -> bool { self.joined.load(Ordering::Acquire) }
    pub async fn close(&self) -> Result<(), &'static str> {
        {
            let _gate = self.gate.lock();
            self.closed.store(true, Ordering::Release);
        }
        self.cancel.cancel();
        self.loads.cancel();
        let joined = self.tasks.close_joined().await.map_err(|_| "physicalDnsJoinUnconfirmed")?;
        let loads = self.loads.close().await;
        let confirmed = matches!(loads, Ok(()) | Err("providerThreadPanicked"));
        self.joined.store(confirmed, Ordering::Release);
        if joined.panicked || loads == Err("providerThreadPanicked") { Err("physicalDnsTaskPanicked") } else { loads }

    }
}

pub struct PhysicalResolver { hook: Arc<ResolverHostHook>, network: NetworkContext, resolver: Arc<Resolver>, owner: Arc<PhysicalDnsOwner> }
fn invalid() -> io::Error { io::Error::new(io::ErrorKind::InvalidInput, "physicalNetwork.dns") }
fn unavailable() -> io::Error { io::Error::new(io::ErrorKind::NotFound, "physical DNS unavailable") }
fn cancelled() -> io::Error { io::Error::new(io::ErrorKind::Interrupted, "physical DNS generation retired") }
pub fn selected_servers(snapshot: &PhysicalDns) -> io::Result<Vec<SocketAddr>> {
    let interface = snapshot.default_interface.as_deref().ok_or_else(unavailable)?;
    if interface.is_empty() || interface.len() > 128 || !interface.bytes().all(|c| c.is_ascii_alphanumeric() || b"_.-".contains(&c)) { return Err(invalid()); }
    let servers = snapshot.interfaces.get(interface).ok_or_else(unavailable)?;
    if servers.is_empty() { return Err(unavailable()); }
    if servers.len() > 16 { return Err(invalid()); }
    servers.iter().map(|server| {
        let (literal, zone) = server.split_once('%').map_or((server.as_str(), None), |(ip, zone)| (ip, Some(zone)));
        let ip: IpAddr = literal.parse().map_err(|_| invalid())?;
        if ip.is_unspecified() || ip.is_multicast() { return Err(invalid()); }
        match ip {
            IpAddr::V4(_) if zone.is_some() => Err(invalid()),
            IpAddr::V4(ip) => Ok(SocketAddr::from((ip, 53))),
            IpAddr::V6(ip) => {
                let index = match zone {
                    Some(zone) if zone.bytes().all(|c| c.is_ascii_digit()) => zone.parse::<u32>().ok().filter(|index| *index != 0).ok_or_else(invalid)?,
                    Some(zone) if zone != interface => return Err(invalid()),
                    Some(_) => interface_index(interface)?,
                    None if ip.is_unicast_link_local() => interface_index(interface)?,
                    None => 0,
                };
                Ok(SocketAddr::V6(SocketAddrV6::new(ip, 53, 0, index)))
            }
        }
    }).collect()
}
fn interface_index(interface: &str) -> io::Result<u32> {
    let interface = CString::new(interface).map_err(|_| invalid())?;
    let index = unsafe { libc::if_nametoindex(interface.as_ptr()) };
    if index == 0 { return Err(unavailable()); }
    Ok(index)
}
impl PhysicalResolver {
    pub fn from_snapshot(snapshot: &PhysicalDns, network: NetworkContext, ipv6: bool) -> io::Result<Arc<Self>> {
        Self::from_servers(selected_servers(snapshot)?, network.with_physical_dns_plan(snapshot.clone()), ipv6)
    }
    pub(crate) fn not_connected(network: NetworkContext, ipv6: bool) -> io::Result<Arc<Self>> {
        if network.network_handle().is_some() { return Err(invalid()); }
        let network = network.dns_binding_context().with_physical_dns(Vec::new());
        let owner = PhysicalDnsOwner::new(&network);
        owner.unavailable_not_connected.store(true,Ordering::Release);
        let resolver = Arc::new(Resolver::new(Vec::new(), Vec::new(), DnsMode::Normal, Default::default(), false, ipv6)
            .unavailable(io::ErrorKind::NotConnected).with_transport_scope(owner.transport_scope(&network)));
        Ok(Arc::new(Self { hook: Arc::new(ResolverHostHook::new(Arc::clone(&resolver))), network, resolver, owner }))
    }
    pub fn from_servers(servers: Vec<SocketAddr>, network: NetworkContext, ipv6: bool) -> io::Result<Arc<Self>> {
        if servers.is_empty() || servers.len() > 16 || servers.iter().any(|server| server.port() == 0 || server.ip().is_unspecified() || server.ip().is_multicast()) { return Err(invalid()); }
        let network = network.dns_binding_context().with_physical_dns(servers.clone());
        let owner = PhysicalDnsOwner::new(&network);
        let resolver = Arc::new(Resolver::new(servers, Vec::new(), DnsMode::Normal, Default::default(), false, ipv6).with_transport_scope(owner.transport_scope(&network)));
        Ok(Arc::new(Self { hook: Arc::new(ResolverHostHook::new(Arc::clone(&resolver))), network, resolver, owner }))
    }
    pub fn network(self: &Arc<Self>) -> NetworkContext {
        let context = self.network.with_resolver(self.clone()).with_dns_owner(self.owner.clone());
        context.with_dns_transport(self.owner.transport_scope(&context))
    }
    pub fn core_resolver(&self) -> Arc<Resolver> { Arc::clone(&self.resolver) }
    pub fn dns_owner(&self) -> Arc<PhysicalDnsOwner> { self.owner.clone() }
    pub async fn retire(&self) -> Result<(), &'static str> { self.owner.close().await }
}
#[async_trait]
impl HostResolver for PhysicalResolver {
    async fn resolve(&self, host: &str) -> io::Result<IpAddr> {
        self.resolve_all(host).await?.into_iter().next().ok_or_else(unavailable)
    }
    async fn resolve_all(&self, host: &str) -> io::Result<Vec<IpAddr>> {
        if self.network.cancelled() {
            self.owner.close().await.map_err(io::Error::other)?;
            return Err(cancelled());
        }
        let network = self.network.clone(); let hook = self.hook.clone(); let host = host.to_owned();
        let answer = self.owner.run(async move { network.scope(hook.resolve_all(&host)).await }).await;
        if self.network.cancelled() {
            self.owner.close().await.map_err(io::Error::other)?;
            return Err(cancelled());
        }
        answer
    }
    fn resolve_all_local(&self, host: &str) -> Option<Vec<IpAddr>> {
        if self.network.cancelled() || self.owner.closed.load(Ordering::Acquire) { None } else { self.hook.resolve_all_local(host) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::sync::Notify;

    #[tokio::test]
    async fn explicit_not_connected_owner_is_distinct_from_missing_or_empty_physical_dns() {
        use meow_config::owned_load::{LoadContext,DisabledDnsSource};
        let network = NetworkContext::platform(CancellationToken::new());
        assert!(network.disabled_dns_source().is_err());
        assert!(network.with_physical_dns(Vec::new()).disabled_dns_source().is_err());
        assert!(PhysicalResolver::from_servers(Vec::new(),network.clone(),false).is_err());
        let offline = PhysicalResolver::not_connected(network.clone(),false).unwrap();
        assert!(matches!(offline.network().disabled_dns_source().unwrap(),DisabledDnsSource::UnavailableNotConnected));
        assert_eq!(offline.core_resolver().resolve_ips("no-network.invalid").await.unwrap_err().kind(),io::ErrorKind::NotConnected);
        let socket = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = socket.local_addr().unwrap();
        for context in [offline.network(),offline.network().child()] {
            let failure = context.scope(meow_common::socket_protect::connect_tcp(address)).await.unwrap_err();
            assert_eq!(failure.kind(),io::ErrorKind::NotConnected);
        }
        let (sent,received) = tokio::sync::oneshot::channel();
        let task = offline.dns_owner().transport_scope(&network).spawn_join(Box::pin(async move {
            let result = meow_common::socket_protect::connect_tcp(address).await.map(|_|()).map_err(|error|error.kind());
            let _ = sent.send(result);
        })).unwrap();
        assert_eq!(received.await.unwrap().unwrap_err(),io::ErrorKind::NotConnected);
        let outcome = task.wait().await; assert!(!outcome.panicked && !outcome.cancelled);
        let slot = crate::network_context::NetworkContextSlot::new(&offline.network());
        let live = slot.view(&offline.network());
        assert_eq!(live.scope(meow_common::socket_protect::connect_tcp(address)).await.unwrap_err().kind(),io::ErrorKind::NotConnected);
        assert!(tokio::time::timeout(std::time::Duration::from_millis(50),socket.accept()).await.is_err());
        assert!(PhysicalResolver::not_connected(network.fixture_network_identity(1),false).is_err());
        offline.retire().await.unwrap(); assert!(offline.dns_owner().cleanup_confirmed());
    }
    #[tokio::test]
    async fn retiring_dns_owner_cancels_and_joins_only_its_queries() {
        let session = CancellationToken::new();
        let network = NetworkContext::platform(session.clone());
        let owner = PhysicalDnsOwner::new(&network);
        let started = Arc::new(Notify::new());
        let query_owner = owner.clone();
        let query_started = started.clone();
        let query = tokio::spawn(async move {
            query_owner.run(async move {
                query_started.notify_one();
                std::future::pending::<io::Result<()>>().await
            }).await
        });
        started.notified().await;

        owner.close().await.expect("DNS generation should join");
        let result = query.await.expect("query task joined");

        assert!(result.is_err(), "retired DNS query must be cancelled");
        assert!(!session.is_cancelled(), "DNS retirement must not cancel TCP/UDP/session owners");
    }
}
