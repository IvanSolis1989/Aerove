//! Android owner identity; the dispatcher captures one runtime owner, never a successor.
use std::{collections::HashMap, io, net::SocketAddr, sync::{Arc, Mutex, OnceLock, atomic::{AtomicU8, Ordering}}};
use serde::Serialize;
use tokio_util::sync::CancellationToken;

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct Identity { pub uid: u32, pub process: String, pub process_path: String }
pub type Resolver = Arc<dyn Fn(i32, SocketAddr, SocketAddr) -> io::Result<Identity> + Send + Sync>;
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FindProcessMode { Strict = 0, Always = 1, Off = 2 }
impl FindProcessMode {
    pub fn wire(self) -> &'static str {
        match self { Self::Strict => "strict", Self::Always => "always", Self::Off => "off" }
    }
    pub fn parse(value: &str) -> io::Result<Self> {
        match value.to_ascii_lowercase().as_str() {
            "strict" => Ok(Self::Strict), "always" => Ok(Self::Always), "off" => Ok(Self::Off),
            _ => Err(io::Error::new(io::ErrorKind::InvalidInput, "find-process-mode: expected strict, always or off")),
        }
    }
}
type Key = (i32, SocketAddr, SocketAddr);
struct Cached { flow: Arc<Flow>, last_active: std::time::Instant }
struct Cache { generation: u64, entries: HashMap<Key, Cached> }
pub struct Owner { resolver: Option<Resolver>, cancel: CancellationToken, mode: AtomicU8, cache: Mutex<Cache> }
impl Owner {
    pub fn new(resolver: Option<Resolver>, cancel: CancellationToken, mode: FindProcessMode) -> Arc<Self> {
        Arc::new(Self { resolver, cancel, mode: AtomicU8::new(mode as u8), cache: Mutex::new(Cache { generation: 0, entries: HashMap::new() }) })
    }
    pub fn set_mode(&self, mode: FindProcessMode) { self.mode.store(mode as u8, Ordering::Release); }
    pub fn mode(&self) -> FindProcessMode {
        match self.mode.load(Ordering::Acquire) {
            1 => FindProcessMode::Always,
            2 => FindProcessMode::Off,
            _ => FindProcessMode::Strict,
        }
    }
    pub fn set_network_generation(&self, generation: u64) {
        let mut cache = self.cache.lock().unwrap();
        if cache.generation != generation { cache.generation = generation; cache.entries.clear(); }
    }
    pub fn bind(self: &Arc<Self>, protocol: i32, source: SocketAddr, destination: SocketAddr) -> Arc<Flow> {
        let mut cache = self.cache.lock().unwrap();
        let generation = cache.generation;
        let key = (protocol, source, destination);
        let now = std::time::Instant::now();
        let timeout = std::time::Duration::from_secs(10); // Same idle interval as core UDP NAT.
        if protocol == 17 {
            if let Some(entry) = cache.entries.get_mut(&key) {
                if now.duration_since(entry.last_active) < timeout {
                    entry.last_active = now;
                    return entry.flow.clone();
                }
            }
            cache.entries.remove(&key);
        }
        let flow = Arc::new(Flow { owner: Arc::downgrade(self), generation, key, result: OnceLock::new() });
        if protocol == 17 {
            // A full cache refuses attribution for new tuples instead of evicting active flows
            // and repeatedly calling Java on every packet of an adversarial UDP stream.
            if cache.entries.len() >= 4096 { cache.entries.retain(|_, entry| now.duration_since(entry.last_active) < timeout); }
            if cache.entries.len() < 4096 { cache.entries.insert(key, Cached { flow: flow.clone(), last_active: now }); }
            else { let _ = flow.result.set(None); }
        }
        flow
    }
}
pub struct Flow { owner: std::sync::Weak<Owner>, generation: u64, key: Key, result: OnceLock<Option<Identity>> }
impl Flow {
    pub fn identity(&self) -> Option<&Identity> {
        let owner = self.owner.upgrade()?;
        if owner.cancel.is_cancelled() || owner.mode.load(Ordering::Acquire) == FindProcessMode::Off as u8 { return None; }
        self.result.get_or_init(|| {
            if owner.cache.lock().unwrap().generation != self.generation { return None; }
            let identity = (owner.resolver.as_ref()?)(self.key.0, self.key.1, self.key.2).ok()?;
            if owner.cancel.is_cancelled() || owner.cache.lock().unwrap().generation != self.generation { return None; }
            Some(identity)
        }).as_ref()
    }
    pub fn known(&self) -> Option<&Identity> {
        if self.owner.upgrade()?.cancel.is_cancelled() { return None; }
        self.result.get().and_then(Option::as_ref)
    }
    pub fn prefetch(&self) {
        if self.owner.upgrade().is_some_and(|owner| owner.mode.load(Ordering::Acquire) == FindProcessMode::Always as u8) { self.identity(); }
    }
}
pub fn bind(sess: &mut crate::session::Session, owner: &Option<Arc<Owner>>, destination: Option<SocketAddr>) {
    if sess.typ != crate::session::Type::Tun { return; }
    if let (Some(owner), Some(destination)) = (owner, destination) {
        sess.process_identity = Some(owner.bind(match sess.network { crate::session::Network::Tcp => 6, crate::session::Network::Udp => 17 }, sess.source, destination));
        if let Some(flow) = &sess.process_identity { flow.prefetch(); }
    }
}
