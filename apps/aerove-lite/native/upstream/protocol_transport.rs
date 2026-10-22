//! Per-handler transport identity captures the owning session, never the next global runtime.
use std::{io,net::SocketAddr,os::fd::AsRawFd};
use socket2::{Domain,Protocol,Socket,Type};
use tokio_util::sync::CancellationToken;
#[derive(Clone)]
pub struct Scope {pub(crate) protector:super::Protector,pub cancel:CancellationToken}
impl Scope {
    pub(crate) fn current()->Option<Self>{super::context().ok().map(|owner|Self {protector:owner.protector.clone(),cancel:owner.cancel.clone()})}
    pub fn check(&self)->io::Result<()> {if self.cancel.is_cancelled(){Err(io::Error::new(io::ErrorKind::Interrupted,"owning runtime stopped"))}else{Ok(())}}
    pub async fn tcp(&self,remote:SocketAddr)->io::Result<tokio::net::TcpStream>{
        self.check()?;let socket=if remote.is_ipv4(){tokio::net::TcpSocket::new_v4()?}else{tokio::net::TcpSocket::new_v6()?};
        (self.protector)(socket.as_raw_fd())?;self.check()?;
        tokio::select!{biased;_=self.cancel.cancelled()=>Err(io::Error::new(io::ErrorKind::Interrupted,"owning runtime stopped")),result=socket.connect(remote)=>result}
    }
    pub fn udp(&self,remote:SocketAddr)->io::Result<Socket>{
        self.check()?;let socket=Socket::new(if remote.is_ipv4(){Domain::IPV4}else{Domain::IPV6},Type::DGRAM,Some(Protocol::UDP))?;
        (self.protector)(socket.as_raw_fd())?;self.check()?;socket.set_nonblocking(true)?;Ok(socket)
    }
}
#[cfg(feature="shadowquic")]
pub struct ShadowSocket {pub scope:Scope,pub remote:SocketAddr,pub interface:Option<shadowquic::config::Interface>,pub mark:Option<u32>}
#[cfg(feature="shadowquic")]
#[async_trait::async_trait]
impl shadowquic::utils::socket_opt::SocketFactory for ShadowSocket {
    async fn create_socket(&self)->io::Result<Socket>{
        let socket=self.scope.udp(self.remote)?;
        #[cfg(any(target_os="linux",target_os="android"))]
        if let Some(mark)=self.mark {socket.set_mark(mark)?;}
        let bind=match &self.interface {Some(shadowquic::config::Interface::Address(ip))=>SocketAddr::new(*ip,0),_=>if self.remote.is_ipv4(){"0.0.0.0:0".parse().unwrap()}else{"[::]:0".parse().unwrap()}};
        if let Some(shadowquic::config::Interface::Device(device))=&self.interface {if !self.remote.ip().is_loopback(){shadowquic::utils::platform::bind_device(&socket,device)?;}}
        socket.bind(&bind.into())?;self.scope.check()?;Ok(socket)
    }
}
