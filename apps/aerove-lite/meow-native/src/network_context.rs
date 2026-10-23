use meow_common::socket_protect::{HostResolver, NetworkScope, SocketProtector};
use meow_config::owned_load::{LoadContext, OwnedWork};
use std::{future::Future, io, os::fd::RawFd, sync::Arc};
use tokio_util::sync::CancellationToken;

#[derive(Clone)]
pub struct NetworkContext { scope: NetworkScope, cancel: CancellationToken, network_handle: Option<u64> }

struct FencedProtector { callback: Arc<dyn SocketProtector>, cancel: CancellationToken, network_handle: Option<u64> }
impl SocketProtector for FencedProtector {
    fn protect(&self, fd: RawFd) -> io::Result<()> {
        if self.cancel.is_cancelled() { return Err(cancelled()); }
        if let Some(network) = self.network_handle { bind_network(network, fd)?; }
        self.callback.protect(fd)?;
        if self.cancel.is_cancelled() { return Err(cancelled()); }
        Ok(())
    }
}
fn cancelled() -> io::Error { io::Error::new(io::ErrorKind::Interrupted, "captured network owner cancelled") }

impl NetworkContext {
    pub fn protected(callback: Arc<dyn SocketProtector>, resolver: Option<Arc<dyn HostResolver>>, cancel: CancellationToken) -> Self {
        Self { scope: NetworkScope { protector: Some(Arc::new(FencedProtector { callback, cancel: cancel.clone(), network_handle: None })), resolver }, cancel, network_handle: None }
    }
    pub fn protected_on_network(callback: Arc<dyn SocketProtector>, resolver: Option<Arc<dyn HostResolver>>, network: u64, cancel: CancellationToken) -> io::Result<Self> {
        if network == 0 { return Err(io::Error::new(io::ErrorKind::InvalidInput, "network handle must be nonzero")); }
        #[cfg(not(target_os = "android"))]
        { let _ = (callback, resolver, cancel); return Err(io::Error::from(io::ErrorKind::Unsupported)); }
        #[cfg(target_os = "android")]
        Ok(Self { scope: NetworkScope { protector: Some(Arc::new(FencedProtector { callback, cancel: cancel.clone(), network_handle: Some(network) })), resolver }, cancel, network_handle: Some(network) })
    }
    pub fn network_handle(&self) -> Option<u64> { self.network_handle }
    pub fn with_resolver(&self, resolver: Arc<dyn HostResolver>) -> Self {
        Self { scope: NetworkScope { protector: self.scope.protector.clone(), resolver: Some(resolver) },
            cancel: self.cancel.clone(), network_handle: self.network_handle }
    }
    pub fn child(&self) -> Self {
        let cancel = self.cancel.child_token();
        let protector = self.scope.protector.as_ref().map(|callback| Arc::new(FencedProtector {
            callback: callback.clone(), cancel: cancel.clone(), network_handle: None,
        }) as Arc<dyn SocketProtector>);
        Self { scope: NetworkScope { protector, resolver: self.scope.resolver.clone() }, cancel, network_handle: self.network_handle }
    }
    pub async fn scope<F: Future>(&self, work: F) -> F::Output { self.scope.scope(work).await }
    pub fn cancelled(&self) -> bool { self.cancel.is_cancelled() }
    pub async fn cancelled_signal(&self) { self.cancel.cancelled().await; }
    pub fn cancel(&self) { self.cancel.cancel(); }
}

impl LoadContext for NetworkContext {
    fn wrap(&self, work: OwnedWork) -> OwnedWork {
        let scope = self.scope.clone();
        Box::pin(async move { scope.scope(work).await })
    }
    fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) { self.scope.run_blocking(work); }
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
