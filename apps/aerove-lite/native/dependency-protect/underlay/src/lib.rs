use std::{
    fmt, future::Future, io,
    net::{IpAddr, SocketAddr},
    os::fd::{AsRawFd, RawFd},
    pin::Pin,
    sync::Arc,
    task::{Context, Poll},
    time::Duration,
};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio_util::sync::CancellationToken;

pub type Protector = Arc<dyn Fn(RawFd) -> io::Result<()> + Send + Sync>;
pub type Resolver = Arc<dyn Fn(String, u16) -> Pin<Box<dyn Future<Output = io::Result<Vec<SocketAddr>>> + Send>> + Send + Sync>;

#[derive(Debug)]
pub enum UnderlayError { MissingPolicy, Stopped, Protect(io::Error) }
impl fmt::Display for UnderlayError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::MissingPolicy => f.write_str("Tailscale underlay policy was not supplied"),
            Self::Stopped => f.write_str("Tailscale underlay owner stopped"),
            Self::Protect(e) => write!(f, "Tailscale socket protection rejected: {e}"),
        }
    }
}
impl std::error::Error for UnderlayError {}
fn stopped() -> io::Error { io::Error::new(io::ErrorKind::Interrupted, UnderlayError::Stopped) }

#[derive(Clone)]
pub struct Underlay {
    protector: Option<Protector>,
    resolver: Option<Resolver>,
    cancel: CancellationToken,
}
impl fmt::Debug for Underlay {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Underlay").field("configured", &self.protector.is_some()).field("stopped", &self.cancel.is_cancelled()).finish()
    }
}
impl Default for Underlay {
    fn default() -> Self { Self { protector: None, resolver: None, cancel: CancellationToken::new() } }
}
impl Underlay {
    pub fn protected(protector: Protector, resolver: Resolver, cancel: CancellationToken) -> Self {
        Self { protector: Some(protector), resolver: Some(resolver), cancel }
    }
    pub fn platform(resolver: Resolver, cancel: CancellationToken) -> Self {
        Self::protected(Arc::new(|_| Ok(())), resolver, cancel)
    }
    pub fn child(&self) -> Self { Self { cancel: self.cancel.child_token(), ..self.clone() } }
    pub fn cancel(&self) { self.cancel.cancel(); }
    pub async fn cancelled(&self) { self.cancel.cancelled().await; }
    pub fn check(&self) -> io::Result<()> {
        if self.cancel.is_cancelled() { return Err(stopped()); }
        if self.protector.is_none() { return Err(io::Error::new(io::ErrorKind::PermissionDenied, UnderlayError::MissingPolicy)); }
        Ok(())
    }
    fn protect(&self, fd: RawFd) -> io::Result<()> {
        self.check()?;
        self.protector.as_ref().unwrap()(fd).map_err(|e| io::Error::new(io::ErrorKind::PermissionDenied, UnderlayError::Protect(e)))?;
        self.check()
    }
    pub async fn bounded<T>(&self, future: impl Future<Output = io::Result<T>>) -> io::Result<T> {
        self.check()?;
        tokio::select! {
            biased;
            _ = self.cancel.cancelled() => Err(stopped()),
            result = tokio::time::timeout(Duration::from_secs(30), future) => {
                let value = result.map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "Tailscale underlay operation timed out"))??;
                self.check()?;
                Ok(value)
            }
        }
    }
    pub async fn resolve(&self, host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
        self.check()?;
        if let Ok(ip) = host.parse::<IpAddr>() { return Ok(vec![SocketAddr::new(ip, port)]); }
        self.bounded(self.resolver.as_ref().ok_or_else(|| io::Error::new(io::ErrorKind::PermissionDenied, UnderlayError::MissingPolicy))?(host.to_owned(), port)).await
    }
    pub async fn connect_host(&self, host: &str, port: u16) -> io::Result<TcpStream> {
        let mut last = io::Error::new(io::ErrorKind::AddrNotAvailable, "no Tailscale underlay address");
        for addr in self.resolve(host, port).await? {
            match self.connect(addr).await { Ok(s) => return Ok(s), Err(e) => { self.check()?; last = e; } }
        }
        Err(last)
    }
    pub async fn connect(&self, addr: SocketAddr) -> io::Result<TcpStream> {
        self.check()?;
        let socket = if addr.is_ipv4() { tokio::net::TcpSocket::new_v4()? } else { tokio::net::TcpSocket::new_v6()? };
        self.protect(socket.as_raw_fd())?;
        let inner = self.bounded(socket.connect(addr)).await?;
        let cancelled = Box::pin(self.cancel.clone().cancelled_owned());
        Ok(TcpStream { inner, owner: self.clone(), cancelled })
    }
    pub async fn bind(&self, addr: SocketAddr) -> io::Result<UdpSocket> {
        self.check()?;
        let socket = socket2::Socket::new(socket2::Domain::for_address(addr), socket2::Type::DGRAM, Some(socket2::Protocol::UDP))?;
        self.protect(socket.as_raw_fd())?;
        if addr.is_ipv6() { socket.set_only_v6(true)?; }
        socket.set_nonblocking(true)?;
        socket.bind(&addr.into())?;
        self.check()?;
        Ok(UdpSocket { inner: tokio::net::UdpSocket::from_std(socket.into())?, owner: self.clone() })
    }
}

pub struct TcpStream {
    inner: tokio::net::TcpStream,
    owner: Underlay,
    cancelled: Pin<Box<dyn Future<Output = ()> + Send + Sync>>,
}
impl fmt::Debug for TcpStream {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { f.debug_struct("ProtectedTcpStream").field("peer", &self.inner.peer_addr()).finish() }
}
impl TcpStream {
    pub fn peer_addr(&self) -> io::Result<SocketAddr> { self.inner.peer_addr() }
    fn poll_owner(&mut self, cx: &mut Context<'_>) -> io::Result<()> {
        if self.cancelled.as_mut().poll(cx).is_ready() { return Err(stopped()); }
        self.owner.check()
    }
}
impl AsyncRead for TcpStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        if let Err(e) = self.poll_owner(cx) { return Poll::Ready(Err(e)); }
        Pin::new(&mut self.inner).poll_read(cx, buf)
    }
}
impl AsyncWrite for TcpStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        if let Err(e) = self.poll_owner(cx) { return Poll::Ready(Err(e)); }
        Pin::new(&mut self.inner).poll_write(cx, buf)
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        if let Err(e) = self.poll_owner(cx) { return Poll::Ready(Err(e)); }
        Pin::new(&mut self.inner).poll_flush(cx)
    }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> { Pin::new(&mut self.inner).poll_shutdown(cx) }
}

pub struct UdpSocket { inner: tokio::net::UdpSocket, owner: Underlay }
impl UdpSocket {
    pub fn local_addr(&self) -> io::Result<SocketAddr> { self.inner.local_addr() }
    pub async fn readable(&self) -> io::Result<()> {
        self.owner.check()?;
        tokio::select! { biased; _ = self.owner.cancelled() => Err(stopped()), r = self.inner.readable() => r }
    }
    pub fn try_recv_from(&self, buf: &mut [u8]) -> io::Result<(usize, SocketAddr)> { self.owner.check()?; self.inner.try_recv_from(buf) }
    pub async fn recv_buf_from<B: bytes::BufMut>(&self, buf: &mut B) -> io::Result<(usize, SocketAddr)> {
        self.owner.check()?;
        tokio::select! { biased; _ = self.owner.cancelled() => Err(stopped()), r = self.inner.recv_buf_from(buf) => r }
    }
    pub async fn send_to(&self, buf: &[u8], addr: SocketAddr) -> io::Result<usize> {
        self.owner.check()?;
        tokio::select! { biased; _ = self.owner.cancelled() => Err(stopped()), r = self.inner.send_to(buf, addr) => r }
    }
}
