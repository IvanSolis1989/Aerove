use crate::{network_context::NetworkContext, runtime_plan::PhysicalDns};
use async_trait::async_trait;
use meow_common::{DnsMode, HostResolver};
use meow_dns::{Resolver, ResolverHostHook};
use std::{ffi::CString, io, net::{IpAddr, SocketAddr, SocketAddrV6}, sync::Arc};

pub struct PhysicalResolver { hook: ResolverHostHook, network: NetworkContext }

fn invalid() -> io::Error { io::Error::new(io::ErrorKind::InvalidInput, "physicalNetwork.dns") }
fn unavailable() -> io::Error { io::Error::new(io::ErrorKind::NotFound, "physical DNS unavailable") }
fn cancelled() -> io::Error { io::Error::new(io::ErrorKind::Interrupted, "physical DNS owner cancelled") }

pub fn selected_servers(snapshot: &PhysicalDns) -> io::Result<Vec<SocketAddr>> {
    let interface = snapshot.default_interface.as_deref().ok_or_else(unavailable)?;
    if interface.is_empty() || interface.len() > 128 || !interface.bytes().all(|c| c.is_ascii_alphanumeric() || b"_.-".contains(&c)) {
        return Err(invalid());
    }
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
        Self::from_servers(selected_servers(snapshot)?, network, ipv6)
    }

    pub fn from_servers(servers: Vec<SocketAddr>, network: NetworkContext, ipv6: bool) -> io::Result<Arc<Self>> {
        if servers.is_empty() || servers.len() > 16 || servers.iter().any(|server| server.port() == 0 || server.ip().is_unspecified() || server.ip().is_multicast()) {
            return Err(invalid());
        }
        let resolver = Arc::new(Resolver::new(servers, Vec::new(), DnsMode::Normal, Default::default(), false, ipv6));
        Ok(Arc::new(Self { hook: ResolverHostHook::new(resolver), network }))
    }

    pub fn network(self: &Arc<Self>) -> NetworkContext { self.network.with_resolver(self.clone()) }
}

#[async_trait]
impl HostResolver for PhysicalResolver {
    async fn resolve(&self, host: &str) -> io::Result<IpAddr> {
        self.resolve_all(host).await?.into_iter().next().ok_or_else(unavailable)
    }

    async fn resolve_all(&self, host: &str) -> io::Result<Vec<IpAddr>> {
        if self.network.cancelled() { return Err(cancelled()); }
        let answer = tokio::select! { biased;
            _ = self.network.cancelled_signal() => return Err(cancelled()),
            answer = self.network.scope(self.hook.resolve_all(host)) => answer,
        };
        if self.network.cancelled() { return Err(cancelled()); }
        answer
    }

    fn resolve_all_local(&self, host: &str) -> Option<Vec<IpAddr>> {
        if self.network.cancelled() { None } else { self.hook.resolve_all_local(host) }
    }
}
