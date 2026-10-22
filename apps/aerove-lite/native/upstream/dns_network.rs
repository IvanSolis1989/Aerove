//! DNS supplied by the owning Android physical-network callback, never public fallback.
use std::{io,net::{IpAddr,SocketAddr,SocketAddrV6}};
use async_trait::async_trait;
use hickory_proto::op::Message;
pub use crate::config::def::NetworkDns;
impl NetworkDns {
    pub(crate) fn validate(&self)->crate::Result<()> {
        if self.interfaces.len()>64{return Err(super::invalid("$.aerove-system-dns.interfaces: too many interfaces"));}
        for (name,servers) in &self.interfaces {
            if name.is_empty()||name.len()>128||!name.chars().all(|c|c.is_ascii_alphanumeric()||"_.-".contains(c))||servers.len()>16{return Err(super::invalid("$.aerove-system-dns.interfaces: invalid interface or DNS count"));}
            for server in servers {literal(server,name,false).map_err(|_|super::invalid("$.aerove-system-dns.interfaces: DNS must be unicast IP literals with a valid IPv6 scope"))?;}
        }
        if self.default_interface.as_ref().is_some_and(|name|!self.interfaces.contains_key(name)){return Err(super::invalid("$.aerove-system-dns.defaultInterface: interface is absent"));}
        Ok(())
    }
    pub(crate) fn servers(&self,interface:Option<&str>)->io::Result<Vec<SocketAddr>>{
        let name=match interface {None|Some("auto")=>self.default_interface.as_deref().ok_or_else(||io::Error::new(io::ErrorKind::NotConnected,"system DNS: no physical default network"))?,Some(name)=>name};
        let servers=self.interfaces.get(name).ok_or_else(||io::Error::new(io::ErrorKind::NotFound,format!("DHCP DNS: physical interface {name} is unavailable")))?;
        if servers.is_empty(){return Err(io::Error::new(io::ErrorKind::NotConnected,"physical network has no DNS servers"));}
        servers.iter().map(|ip|literal(ip,name,true)).collect()
    }
}
#[derive(Debug)]
pub(crate) struct NetworkClient {interface:Option<String>}
impl NetworkClient {pub(crate) fn new(interface:Option<String>)->Self{Self {interface}}}
#[async_trait]
impl crate::app::dns::Client for NetworkClient {
    fn id(&self)->String {self.interface.as_ref().map(|i|format!("dhcp#{i}")).unwrap_or_else(||"system".into())}
    async fn exchange(&self,message:&Message)->anyhow::Result<Message>{Ok(exchange(self.interface.as_deref(),message).await?)}
}
pub(crate) async fn exchange(interface:Option<&str>,message:&Message)->io::Result<Message>{
    let context=super::context()?;
    let snapshot=context.network_dns.read().unwrap().clone().ok_or_else(||io::Error::new(io::ErrorKind::NotConnected,"system DNS: physical network snapshot is unavailable"))?;
    let servers=snapshot.servers(interface)?;
    let mut last=None;
    for server in servers {
        let operation=super::observations::dns_message(message,async {super::observations::dns_source(server.to_string(),false);tokio::time::timeout(std::time::Duration::from_secs(5),super::query_exchange(message,server)).await.map_err(|e|io::Error::new(io::ErrorKind::TimedOut,e))?});
        let result=tokio::select!{biased;_=context.cancel.cancelled()=>return Err(io::Error::new(io::ErrorKind::Interrupted,"core is stopping")),result=operation=>result};
        match result {Ok(response)=>return Ok(response),Err(error)=>last=Some(error)}
    }
    Err(last.unwrap_or_else(||io::Error::new(io::ErrorKind::NotConnected,"physical network has no DNS servers")))
}

fn literal(value:&str,interface:&str,resolve_scope:bool)->io::Result<SocketAddr>{
    let (address,zone)=value.rsplit_once('%').map(|(a,z)|(a,Some(z))).unwrap_or((value,None));
    let ip:IpAddr=address.parse().map_err(|_|io::Error::other("DNS server must be an IP literal"))?;
    if ip.is_unspecified()||ip.is_multicast(){return Err(io::Error::other("DNS server must be unicast"));}
    match ip {
        IpAddr::V4(_) if zone.is_some()=>Err(io::Error::other("IPv4 DNS has an IPv6 scope")),
        IpAddr::V6(ip) if zone.is_some()||ip.is_unicast_link_local()=>{
            let zone=zone.unwrap_or(interface);
            if zone.is_empty()||zone.len()>128||!zone.chars().all(|c|c.is_ascii_alphanumeric()||"_.-".contains(c)){return Err(io::Error::other("invalid IPv6 DNS scope"));}
            let scope=if let Ok(index)=zone.parse::<u32>() {if index==0{return Err(io::Error::other("IPv6 DNS scope cannot be zero"));}index}
            else {if zone!=interface{return Err(io::Error::other("IPv6 DNS scope does not match physical interface"));}if resolve_scope {let name=std::ffi::CString::new(zone).map_err(io::Error::other)?;let index=unsafe {libc::if_nametoindex(name.as_ptr())};if index==0{return Err(io::Error::new(io::ErrorKind::NotFound,"IPv6 DNS physical interface is unavailable"));}index}else{0}};
            Ok(SocketAddr::V6(SocketAddrV6::new(ip,53,0,scope)))
        },
        _=>Ok(SocketAddr::new(ip,53)),
    }
}

/// Standard Android false means synthetic DNS only; true means every port 53.
pub(crate) fn hijack(target:SocketAddr)->bool{
    if target.port()!=53{return false;}
    let Ok(context)=super::context() else{return false;};
    context.dns_enabled&&(context.dns_hijack_all||target.ip()=="198.18.0.2".parse::<IpAddr>().unwrap()||target.ip()=="fdfe:dcba:9876::2".parse::<IpAddr>().unwrap())
}
pub(crate) async fn tcp<S:tokio::io::AsyncRead+tokio::io::AsyncWrite+Unpin>(mut stream:S,resolver:crate::app::dns::ThreadSafeDNSResolver){
    use tokio::io::{AsyncReadExt,AsyncWriteExt};
    let Ok(context)=super::context() else{return;};
    let serve=async {
        loop {let count=stream.read_u16().await? as usize;if count==0{return Err(io::Error::other("empty DNS TCP frame"));}let mut data=vec![0;count];stream.read_exact(&mut data).await?;
            let message=Message::from_vec(&data).map_err(io::Error::other)?;
            let response=crate::app::dns::exchange_with_resolver(&resolver,&message,true).await.map_err(io::Error::other)?;
            let data=response.to_vec().map_err(io::Error::other)?;let count=u16::try_from(data.len()).map_err(io::Error::other)?;stream.write_u16(count).await?;stream.write_all(&data).await?;
        }
        #[allow(unreachable_code)] Ok::<(),io::Error>(())
    };
    tokio::select!{biased;_=context.cancel.cancelled()=>{},_=serve=>{}}
}

pub(crate) fn validate_runtime(config:&crate::InternalConfig)->crate::Result<()>{
    let snapshot=config.general.aerove_system_dns.as_ref();
    for server in config.dns.nameserver.iter().chain(&config.dns.fallback).chain(&config.dns.default_nameserver).chain(config.dns.proxy_server_nameserver.iter().flatten()).chain(config.dns.nameserver_policy.values()) {
        if matches!(server.net,crate::app::dns::config::DNSNetMode::System|crate::app::dns::config::DNSNetMode::Dhcp) {
            let snapshot=snapshot.ok_or_else(||super::invalid("$.aerove-system-dns: system/DHCP requires a physical-network snapshot"))?;
            if matches!(server.net,crate::app::dns::config::DNSNetMode::Dhcp) {
                let iface=server.host.to_string();
                if iface!="auto"&&!snapshot.interfaces.contains_key(&iface){return Err(super::invalid(format!("$.dns: DHCP physical interface {iface} is unavailable")));}
            }
        }
    }
    Ok(())
}
