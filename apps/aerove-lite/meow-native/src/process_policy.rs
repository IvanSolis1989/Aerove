//! Per-session process policy. Original TUN tuples survive DNS reversal in each flow.
use crate::flow_owner::FlowOwnerLookup;
use meow_common::{Metadata, process_lookup::{FlowProcessIdentity, ProcessInfo}};
use std::{fmt, io, net::SocketAddr, sync::{Arc, Mutex, OnceLock, Weak, atomic::{AtomicBool, Ordering}}};
use tokio_util::sync::CancellationToken;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FindProcessMode { Strict, Always, Off }
impl FindProcessMode {
    pub fn parse(value: &str) -> io::Result<Self> {
        match value { "strict" => Ok(Self::Strict), "always" => Ok(Self::Always), "off" => Ok(Self::Off),
            _ => Err(io::Error::new(io::ErrorKind::InvalidInput, "find-process-mode: expected strict, always or off")) }
    }
    pub fn wire(self) -> &'static str { match self { Self::Strict => "strict", Self::Always => "always", Self::Off => "off" } }
}
struct State { mode: FindProcessMode, revision: u64 }
pub struct ProcessPolicy {
    generation: u64, lookup: Option<Arc<dyn FlowOwnerLookup>>, cancel: CancellationToken, state: Mutex<State>, retired: AtomicBool,
}
impl ProcessPolicy {
    pub fn new(generation: u64, lookup: Option<Arc<dyn FlowOwnerLookup>>, cancel: CancellationToken, mode: FindProcessMode) -> io::Result<Arc<Self>> {
        if generation == 0 { return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid process generation")); }
        Ok(Arc::new(Self { generation, lookup, cancel, state: Mutex::new(State { mode, revision: 0 }), retired: AtomicBool::new(false) }))
    }
    pub fn retire(&self) { self.retired.store(true, Ordering::Release); }
    pub fn mode(&self) -> FindProcessMode { self.state.lock().unwrap().mode }
    pub fn generation(&self) -> u64 { self.generation }
    pub fn configure(&self, mode: FindProcessMode) -> io::Result<()> {
        let mut state = self.state.lock().unwrap();
        if self.cancel.is_cancelled() { return Err(io::Error::new(io::ErrorKind::Interrupted, "process owner stopped")); }
        if state.mode != mode { state.revision = next(state.revision)?; state.mode = mode; }
        Ok(())
    }
    /// Package/network identity events invalidate cached AND in-flight flow results.
    pub fn invalidate(&self) -> io::Result<()> {
        let mut state = self.state.lock().unwrap();
        if self.cancel.is_cancelled() { return Err(io::Error::new(io::ErrorKind::Interrupted, "process owner stopped")); }
        state.revision = next(state.revision)?; Ok(())
    }
    pub fn bind(self: &Arc<Self>, protocol: i32, source: SocketAddr, target: SocketAddr) -> io::Result<Arc<ProcessFlow>> {
        if !matches!(protocol, 6 | 17) || source.port() == 0 || target.port() == 0 || source.ip().is_unspecified() || target.ip().is_unspecified() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid process flow tuple"));
        }
        let revision = self.state.lock().unwrap().revision;
        Ok(Arc::new(ProcessFlow { owner: Arc::downgrade(self), revision, protocol, source, target,
            result: OnceLock::new(), initializing: Mutex::new(()) }))
    }
    /// Call at the single TUN metadata assembly point, before handing the flow to core.
    pub fn attach(self: &Arc<Self>, metadata: &mut Metadata, source: SocketAddr, target: SocketAddr) -> io::Result<()> {
        let protocol = if matches!(metadata.network, meow_common::Network::Tcp) { 6 } else { 17 };
        let flow = self.bind(protocol, source, target)?;
        if self.mode() == FindProcessMode::Always {
            let _ = flow.lookup();
        }
        metadata.process_identity = Some(flow);
        Ok(())
    }
}
fn next(revision: u64) -> io::Result<u64> { revision.checked_add(1).ok_or_else(|| io::Error::other("process revision exhausted")) }
pub struct ProcessFlow {
    owner: Weak<ProcessPolicy>, revision: u64, protocol: i32, source: SocketAddr, target: SocketAddr,
    result: OnceLock<Option<ProcessInfo>>, initializing: Mutex<()>,
}
impl fmt::Debug for ProcessFlow {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { f.write_str("ProcessFlow(<captured owner>)") }
}
impl ProcessFlow {
    fn valid(&self, owner: &ProcessPolicy) -> bool {
        let state = owner.state.lock().unwrap();
        !owner.retired.load(Ordering::Acquire) && !owner.cancel.is_cancelled() && state.revision == self.revision && state.mode != FindProcessMode::Off
    }
}
impl FlowProcessIdentity for ProcessFlow {
    fn known(&self) -> Option<ProcessInfo> {
        let owner = self.owner.upgrade()?;
        if self.valid(&owner) { self.result.get().cloned().flatten() } else { None }
    }
    fn lookup(&self) -> Option<ProcessInfo> {
        let owner = self.owner.upgrade()?;
        if !self.valid(&owner) { return None; }
        // A flow owns one result, including failure. No tuple cache or timer is added.
        let _initializing = self.initializing.lock().unwrap();
        if !self.valid(&owner) { return None; }
        let value = self.result.get_or_init(|| {
            let result = owner.lookup.as_ref()?.lookup(self.protocol, self.source, self.target)?;
            if !self.valid(&owner) { return None; }
            Some(ProcessInfo { name: result.process, path: result.process_path, uid: Some(result.uid) })
        });
        if self.valid(&owner) { value.clone() } else { None }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::flow_owner::FlowIdentity;
    use std::sync::atomic::AtomicUsize;
    struct Lookup(AtomicUsize);
    impl FlowOwnerLookup for Lookup {
        fn lookup(&self, protocol: i32, source: SocketAddr, target: SocketAddr) -> Option<FlowIdentity> {
            assert!(matches!(protocol,6|17)); assert_eq!(source.port(),1234); assert_eq!(target.ip().to_string(),"198.18.0.9");
            self.0.fetch_add(1, Ordering::SeqCst);
            Some(FlowIdentity { uid: 42, process: "app.test".into(), process_path: "/app/test".into() })
        }
    }
    fn pair() -> (SocketAddr,SocketAddr) { ("10.0.0.2:1234".parse().unwrap(),"198.18.0.9:443".parse().unwrap()) }
    #[test]
    fn strict_is_lazy_once_and_original_tuple_survives_rewrite() {
        let lookup = Arc::new(Lookup(AtomicUsize::new(0)));
        let policy = ProcessPolicy::new(7,Some(lookup.clone()),CancellationToken::new(),FindProcessMode::Strict).unwrap();
        let mut meta = Metadata::default(); let (src,dst)=pair(); policy.attach(&mut meta,src,dst).unwrap();
        assert_eq!(lookup.0.load(Ordering::SeqCst),0);
        meta.dst_ip=Some("1.1.1.1".parse().unwrap());
        let hook=meta.process_identity.as_ref().unwrap(); assert_eq!(hook.lookup().unwrap().uid,Some(42)); hook.lookup();
        assert_eq!(lookup.0.load(Ordering::SeqCst),1);
        policy.invalidate().unwrap(); assert!(hook.lookup().is_none());
        assert_eq!(lookup.0.load(Ordering::SeqCst),1);
    }
    #[test]
    fn always_prefetch_off_never_calls_and_mode_epoch_fences() {
        let lookup=Arc::new(Lookup(AtomicUsize::new(0))); let cancel=CancellationToken::new();
        let policy=ProcessPolicy::new(7,Some(lookup.clone()),cancel.clone(),FindProcessMode::Always).unwrap();
        let mut meta=Metadata::default(); let (src,dst)=pair(); policy.attach(&mut meta,src,dst).unwrap();
        assert_eq!(meta.pure().uid,Some(42)); assert_eq!(lookup.0.load(Ordering::SeqCst),1);
        policy.configure(FindProcessMode::Off).unwrap(); assert!(meta.process_identity.as_ref().unwrap().lookup().is_none());
        policy.configure(FindProcessMode::Strict).unwrap(); assert!(meta.process_identity.as_ref().unwrap().lookup().is_none());
        let flow=policy.bind(17,src,dst).unwrap(); assert_eq!(flow.lookup().unwrap().uid,Some(42));
        cancel.cancel(); assert!(flow.lookup().is_none()); assert!(policy.invalidate().is_err());
    }
    #[test]
    fn invalidation_during_callback_discards_result() {
        struct Racing(Mutex<Weak<ProcessPolicy>>);
        impl FlowOwnerLookup for Racing {
            fn lookup(&self,_:i32,_:SocketAddr,_:SocketAddr)->Option<FlowIdentity> {
                self.0.lock().unwrap().upgrade().unwrap().invalidate().unwrap();
                Some(FlowIdentity {uid:1,process:"stale".into(),process_path:"stale".into()})
            }
        }
        let callback=Arc::new(Racing(Mutex::new(Weak::new())));
        let policy=ProcessPolicy::new(1,Some(callback.clone()),CancellationToken::new(),FindProcessMode::Strict).unwrap();
        *callback.0.lock().unwrap()=Arc::downgrade(&policy);
        let(src,dst)=pair(); assert!(policy.bind(6,src,dst).unwrap().lookup().is_none());
    }
    #[tokio::test]
    async fn real_lazy_router_strict_skips_early_domain_then_matches_process() {
        for (domain_first, expected_calls, expected_rule) in [(true,0,"DOMAIN"),(false,1,"PROCESS-NAME")] {
            let rules = if domain_first { "rules: ['DOMAIN,early.test,DIRECT', 'PROCESS-NAME,app.test,DIRECT', 'MATCH,REJECT']" }
                else { "rules: ['PROCESS-NAME,app.test,DIRECT', 'MATCH,REJECT']" };
            let config = meow_config::load_config_from_str(rules).await.unwrap();
            let tunnel = meow_tunnel::Tunnel::new_with_slot(config.dns.resolver_slot.clone());
            tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
            tunnel.set_mode(config.general.mode);
            tunnel.update_routing(config.proxies,config.rules,config.dialer_registry);
            let lookup=Arc::new(Lookup(AtomicUsize::new(0)));
            let policy=ProcessPolicy::new(9,Some(lookup.clone()),CancellationToken::new(),FindProcessMode::Strict).unwrap();
            let (src,dst)=pair(); let mut meta=Metadata { host:"early.test".into(),src_ip:Some(src.ip()),src_port:src.port(),dst_ip:Some(dst.ip()),dst_port:dst.port(),..Default::default() };
            policy.attach(&mut meta,src,dst).unwrap();
            let result=tunnel.inner().resolve_proxy_lazy(&mut meta).await.unwrap();
            assert_eq!(result.rule_name.as_str(),expected_rule);
            assert_eq!(lookup.0.load(Ordering::SeqCst),expected_calls);
            if !domain_first { let history=meta.pure(); assert_eq!(history.uid,Some(42)); assert!(history.process_identity.is_none()); policy.invalidate().unwrap(); assert_eq!(history.uid,Some(42)); }
        }
    }    #[test]
    fn failed_lookup_is_cached_and_successor_is_not_consulted() {
        struct Missing(AtomicUsize);
        impl FlowOwnerLookup for Missing { fn lookup(&self,_:i32,_:SocketAddr,_:SocketAddr)->Option<FlowIdentity> { self.0.fetch_add(1,Ordering::SeqCst); None } }
        let missing=Arc::new(Missing(AtomicUsize::new(0)));
        let first=ProcessPolicy::new(10,Some(missing.clone()),CancellationToken::new(),FindProcessMode::Strict).unwrap();
        let (src,dst)=pair(); let old=first.bind(6,src,dst).unwrap(); old.lookup(); old.lookup(); assert_eq!(missing.0.load(Ordering::SeqCst),1);
        drop(first);
        let next=Arc::new(Lookup(AtomicUsize::new(0)));
        let successor=ProcessPolicy::new(11,Some(next.clone()),CancellationToken::new(),FindProcessMode::Always).unwrap();
        assert!(old.lookup().is_none()); assert_eq!(next.0.load(Ordering::SeqCst),0);
        assert!(successor.bind(6,src,dst).unwrap().lookup().is_some());
    }}
