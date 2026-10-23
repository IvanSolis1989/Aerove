//! Session-owned physical network/DNS generation replacement.
//! A caller obtains handle and DNS in one AndroidTunResources capture, then
//! calls apply with the latest coreEpoch. Existing NetworkContext scopes keep
//! the binding captured when they started; future scopes observe the new slot.
use crate::{network_context::{NetworkContext, NetworkContextSlot}, physical_dns::{PhysicalResolver, selected_servers}, runtime_plan::PhysicalDns, providers::ControlPlane};
use meow_tunnel::Tunnel;
use parking_lot::Mutex;
use std::{future::Future, net::SocketAddr, pin::Pin, sync::{Arc, atomic::{AtomicU64, Ordering}}};
use tokio_util::sync::CancellationToken;

pub struct DnsBuild { pub scope: Arc<meow_config::owned_load::OwnedLoadScope>, pub work: Pin<Box<dyn Future<Output = Result<Arc<meow_dns::Resolver>, String>> + Send>> }
pub type DnsFactory = Arc<dyn Fn(NetworkContext, Arc<meow_dns::Resolver>) -> DnsBuild + Send + Sync>;
pub struct CapturedPhysicalNetwork { pub intent: u64, pub core_epoch: u64, pub network_handle: u64, pub dns: PhysicalDns }
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NetworkPolicyReceipt {
    pub intent: u64, pub core_epoch: u64, pub network_handle: u64, pub dns_servers: Vec<SocketAddr>,
    pub resolver_readback: bool, pub network_readback: bool, pub retired_dns_joined: bool,
}
pub struct NetworkPolicyOwner {
    pending_dns: Mutex<Vec<Arc<crate::physical_dns::PhysicalDnsOwner>>>,
    pending_scopes: Mutex<Vec<Arc<meow_config::owned_load::OwnedLoadScope>>>,
    current_scope: Mutex<Option<Arc<meow_config::owned_load::OwnedLoadScope>>>,
    failure: Mutex<Option<&'static str>>, joined: std::sync::atomic::AtomicBool,
    processes: parking_lot::RwLock<Option<Arc<crate::process_policies::ProcessPolicies>>>,
    control: Arc<ControlPlane>, tunnel: Tunnel, network: Arc<NetworkContextSlot>, base_network: NetworkContext,
    dns_factory: DnsFactory, ipv6: bool, cancel: CancellationToken, latest_intent: AtomicU64, intent_gate: Mutex<()>, update_gate: tokio::sync::Mutex<()>,
}
impl NetworkPolicyOwner {
    pub fn new(control: Arc<ControlPlane>, tunnel: Tunnel, network: Arc<NetworkContextSlot>, base_network: NetworkContext,
        dns_factory: DnsFactory, ipv6: bool, cancel: CancellationToken, initial_intent: u64) -> Result<Arc<Self>, &'static str> {
        if cancel.is_cancelled() { return Err("invalidNetworkPolicyOwner"); }
        Ok(Arc::new(Self { pending_dns: Mutex::new(Vec::new()), pending_scopes: Mutex::new(Vec::new()), current_scope: Mutex::new(None), failure: Mutex::new(None), joined: std::sync::atomic::AtomicBool::new(false), processes: parking_lot::RwLock::new(None), control, tunnel, network, base_network, dns_factory, ipv6, cancel, latest_intent: AtomicU64::new(initial_intent),
            intent_gate: Mutex::new(()), update_gate: tokio::sync::Mutex::new(()) }))
    }
    pub fn failure(&self) -> Option<&'static str> { *self.failure.lock() }
    pub fn cleanup_confirmed(&self) -> bool { self.joined.load(Ordering::Acquire) }
    fn fail(&self, cause: &'static str) {
        { let mut failure = self.failure.lock(); if failure.is_none() { *failure = Some(cause); } }
        self.control.cancel();
    }
    fn register_dns(&self, owner: Arc<crate::physical_dns::PhysicalDnsOwner>) {
        let mut owners = self.pending_dns.lock(); if !owners.iter().any(|old| Arc::ptr_eq(old,&owner)) { owners.push(owner); }
    }
    async fn retire_dns(&self, owner: Arc<crate::physical_dns::PhysicalDnsOwner>) -> Result<(), &'static str> {
        self.register_dns(owner.clone());
        let result = owner.close().await;
        if result.is_ok() {
            let retired = { let mut owners = self.pending_dns.lock(); owners.iter().position(|old|Arc::ptr_eq(old,&owner)).map(|index|owners.swap_remove(index)) };
            drop(retired);
        } else { self.fail("retiredDnsJoinFailed"); }
        result
    }
    async fn retire_scope(&self, scope: Arc<meow_config::owned_load::OwnedLoadScope>) -> Result<(), &'static str> {
        { let mut scopes = self.pending_scopes.lock(); if !scopes.iter().any(|old|Arc::ptr_eq(old,&scope)) { scopes.push(scope.clone()); } }
        scope.cancel();
        let result = scope.close().await;
        if result.is_ok() {
            let retired = { let mut scopes = self.pending_scopes.lock(); scopes.iter().position(|old|Arc::ptr_eq(old,&scope)).map(|index|scopes.swap_remove(index)) }; drop(retired);
        } else { self.fail("dnsFactoryScopeFailed"); }
        result
    }
    pub fn bind_process_policy(&self, processes: Arc<crate::process_policies::ProcessPolicies>) { *self.processes.write() = Some(processes); }
    pub fn latest_intent(&self) -> u64 { self.latest_intent.load(Ordering::Acquire) }
    pub async fn apply(&self, captured: CapturedPhysicalNetwork) -> Result<NetworkPolicyReceipt, String> {
        if captured.intent == 0 || captured.network_handle == 0 { return Err("invalidPhysicalNetworkCapture".into()); }
        if self.cancel.is_cancelled() { return Err("cancelled".into()); }
        {
            let _intent = self.intent_gate.lock();
            let observed = self.latest_intent.load(Ordering::Acquire);
            if captured.intent <= observed { return Err("staleNetworkIntent".into()); }
            self.latest_intent.store(captured.intent, Ordering::Release);
        }
        let _serial = self.update_gate.lock().await;
        if captured.intent != self.latest_intent.load(Ordering::Acquire) { return Err("supersededNetworkIntent".into()); }
        if self.cancel.is_cancelled() { return Err("cancelled".into()); }
        if self.control.stamp().0 != captured.core_epoch { return Err("staleCoreEpoch".into()); }
        let selected = selected_servers(&captured.dns).map_err(|error| format!("physicalDnsRejected:{}", error.kind()))?;
        let (current_handle, current_dns) = self.network.current_identity();
        if current_handle == Some(captured.network_handle) && current_dns.as_deref() == Some(selected.as_slice()) {
            let slot = self.tunnel.resolver_slot();
            let tunnel_resolver = Arc::clone(&slot.read());
            let binding_resolver = self.network.current_core_resolver().ok_or_else(|| "coreDnsResolverMissing".to_owned())?;
            let core_epoch = self.control.stamp().0;
            if core_epoch != captured.core_epoch { return Err("staleCoreEpoch".into()); }
            if captured.intent != self.latest_intent.load(Ordering::Acquire) { return Err("supersededNetworkIntent".into()); }
            return Ok(NetworkPolicyReceipt { intent: captured.intent, core_epoch, network_handle: captured.network_handle,
                dns_servers: selected, resolver_readback: Arc::ptr_eq(&tunnel_resolver, &binding_resolver),
                network_readback: Arc::ptr_eq(&tunnel_resolver, &binding_resolver),
                retired_dns_joined: true });
        }

        // Reuse the session's Java protect callback while pinning Android's
        // network bind and LinkProperties DNS list to the same captured handle.
        let network = self.base_network.rebind_network(captured.network_handle)
            .map_err(|error| format!("physicalNetworkUnavailable:{}", error.kind()))?;
        let next = PhysicalResolver::from_snapshot(&captured.dns, network, self.ipv6)
            .map_err(|error| format!("physicalDnsRejected:{}", error.kind()))?;
        let next_network = next.network();
        self.register_dns(next.dns_owner());
        let dns_servers = next_network.physical_dns_snapshot().ok_or_else(|| "physicalDnsSnapshotMissing".to_owned())?;
        if dns_servers.is_empty() { let _ = self.retire_dns(next.dns_owner()).await; return Err("physicalDnsSnapshotEmpty".into()); }
        let readback_slot = self.tunnel.resolver_slot();
        let retired_resolver = Arc::clone(&readback_slot.read());
        let build = (self.dns_factory)(next_network.clone(), Arc::clone(&retired_resolver));
        self.pending_scopes.lock().push(build.scope.clone());
        let resolver = match build.scope.run(build.work).await {
            Ok(resolver) => resolver,
            Err(error) => { let _ = self.retire_scope(build.scope).await; let _ = self.retire_dns(next.dns_owner()).await; return Err(error); }
        };
        let next_network = next_network.with_core_resolver(Arc::clone(&resolver));
        let tunnel = &self.tunnel; let network_slot = &self.network;
        let processes = self.processes.read().clone();
        let identity = match processes.as_ref().map(|owner| owner.prepare_network_invalidation()).transpose() {
            Ok(identity) => identity,
            Err(_) => {
                let _ = self.retire_scope(build.scope.clone()).await;
                let _ = self.retire_dns(next.dns_owner()).await;
                return Err("processPolicyPreparationFailed".into());
            }
        };
        let committed = {
            let _intent = self.intent_gate.lock();
            if captured.intent != self.latest_intent.load(Ordering::Acquire) { None }
            else { Some(self.control.commit_policy(captured.core_epoch, true, || {
                let retired_identity = processes.as_ref().zip(identity).map(|(owner,candidate)| owner.install_network_invalidation(candidate));
                tunnel.set_resolver(Arc::clone(&resolver));
                let retired_network = network_slot.replace(&next_network);
                let retired_scope = self.current_scope.lock().replace(build.scope.clone());
                (retired_resolver, retired_network, retired_identity, retired_scope)
            })) }
        };
        let ((epoch, _revision), retired) = match committed {
            Some(Ok(value)) => value,
            failed => {
                let cause = match failed { Some(Err(cause)) => cause, _ => "supersededNetworkIntent".to_owned() };
                let _ = self.retire_scope(build.scope.clone()).await;
                let _ = self.retire_dns(next.dns_owner()).await;
                return Err(cause);
            }
        };
        let Some((old_resolver, old_network, old_identity, old_scope)) = retired else {
            let _ = self.retire_scope(build.scope.clone()).await;
            let _ = self.retire_dns(next.dns_owner()).await;
            return Err("networkPolicyUnexpectedNoop".into());
        };
        // Retire the DNS generation without cancelling existing data flows.
        // Its old network binding remains captured by in-flight TCP/UDP flows;
        // the shared session scope and runtime stay alive.
        let current_owner = next.dns_owner();
        let registered = { let mut owners = self.pending_dns.lock(); owners.iter().position(|old|Arc::ptr_eq(old,&current_owner)).map(|index|owners.swap_remove(index)) }; drop(registered);
        let registered = { let mut scopes = self.pending_scopes.lock(); scopes.iter().position(|old|Arc::ptr_eq(old,&build.scope)).map(|index|scopes.swap_remove(index)) }; drop(registered);
        let retired_dns_joined = match old_network.dns_owner() { Some(owner) => self.retire_dns(owner).await.is_ok(), None => true };
        let retired_scope_joined = match old_scope { Some(scope) => self.retire_scope(scope).await.is_ok(), None => true };
        drop((old_resolver, old_network, old_identity));
        if !retired_scope_joined { return Err("retiredDnsFactoryJoinFailed".into()); }
        if !retired_dns_joined { return Err("retiredDnsJoinFailed".into()); }
        if captured.intent != self.latest_intent.load(Ordering::Acquire) { return Err("supersededNetworkIntent".into()); }
        let slot = self.tunnel.resolver_slot();
        let installed = Arc::clone(&slot.read());
        let resolver_readback = Arc::ptr_eq(&installed, &resolver);
        let (network_handle, servers) = self.network.current_identity();
        let binding_resolver = self.network.current_core_resolver();
        let network_readback = network_handle == Some(captured.network_handle) && servers.as_deref() == Some(dns_servers.as_slice())
            && binding_resolver.as_ref().is_some_and(|current| Arc::ptr_eq(current, &resolver));
        Ok(NetworkPolicyReceipt { intent: captured.intent, core_epoch: epoch, network_handle: captured.network_handle,
            dns_servers, resolver_readback, network_readback, retired_dns_joined })
    }
    pub async fn close(&self) -> Result<(), &'static str> {
        let _serial = self.update_gate.lock().await;
        if let Some(owner) = self.network.current_dns_owner() { self.register_dns(owner); }
        if let Some(scope) = self.current_scope.lock().clone() {
            let mut scopes = self.pending_scopes.lock(); if !scopes.iter().any(|old|Arc::ptr_eq(old,&scope)) { scopes.push(scope); }
        }
        let owners = self.pending_dns.lock().clone(); let scopes = self.pending_scopes.lock().clone();
        for scope in &scopes { scope.cancel(); }
        let mut confirmed = true; let mut failure = None;
        for owner in owners {
            if let Err(cause) = owner.close().await { failure.get_or_insert(cause); }
            confirmed &= owner.cleanup_confirmed();
        }
        for scope in scopes {
            let result = scope.close().await;
            confirmed &= matches!(result,Ok(()) | Err("providerThreadPanicked"));
            if let Err(cause) = result { failure.get_or_insert(cause); }
        }
        self.joined.store(confirmed,Ordering::Release);
        if let Some(cause) = failure { self.fail(cause); Err(cause) } else { Ok(()) }
    }
}
