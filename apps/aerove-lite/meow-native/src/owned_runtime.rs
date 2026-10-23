use crate::{api::CoreApi, control::RouteControl, network_context::{NetworkContext, NetworkContextSlot, TunnelCapture}, runtime_plan::{RuntimeMode, RuntimePlan}, tun_driver::TunDriver, tunnel_packets::TunnelPackets};
use axum::Router;
use futures::FutureExt;
use meow_config::{ListenerSpec, NamedListener, owned_load::OwnedLoadScope};
use meow_tunnel::Tunnel;
use parking_lot::Mutex;
use serde::Serialize;
use std::{io, net::SocketAddr, os::fd::{AsFd, AsRawFd, OwnedFd}, panic::AssertUnwindSafe, sync::{Arc, atomic::{AtomicBool, AtomicU64, Ordering}}, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream, UdpSocket}, sync::{mpsc, oneshot, watch}, task::{JoinHandle, JoinSet}};
use tokio_util::sync::CancellationToken;
use crate::flow_owner::FlowOwnerLookup;

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OwnedStatus {
    pub state: &'static str, pub error: Option<String>, pub api_address: Option<SocketAddr>,
    pub runtime_mode: &'static str, pub tun_ready: bool, pub user_listeners_ready: bool,
    pub listening: bool, pub core_ready: bool,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ListenerStatus {
    pub listening: bool, pub tun_ready: bool, pub user_listeners_ready: bool, pub active_ports: Vec<u16>,
    pub core_epoch: String, pub only_statistics_proxy: bool, pub unified_delay: bool,
    pub tcp_concurrent: bool, pub find_process_mode: &'static str,
}
pub(crate) type RegisterControl = Arc<dyn Fn(Arc<RouteControl>, Tunnel) -> bool + Send + Sync>;
enum Command { HostPolicy(String, oneshot::Sender<io::Result<String>>), Park(oneshot::Sender<io::Result<()>>), Resume(Option<OwnedFd>, oneshot::Sender<io::Result<()>>) }

#[derive(Default)]
struct CleanupState { confirmed: AtomicBool, uncertain: AtomicBool }
impl CleanupState {
    async fn park(&self, active: &mut ActiveListeners) -> io::Result<()> {
        self.uncertain.store(true, Ordering::Release);
        active.close().await?;
        self.uncertain.store(false, Ordering::Release);
        Ok(())
    }
    fn complete(&self, loaded: bool, closed: bool) -> bool {
        let confirmed = loaded && closed && !self.uncertain.load(Ordering::Acquire);
        self.confirmed.store(confirmed, Ordering::Release);
        confirmed
    }
}

pub struct OwnedRuntime {
    plane: Arc<crate::providers::ControlPlane>,
    policies: Arc<crate::request_policies::RequestPolicies>,
    probes: Arc<crate::probe_policy::ProbePolicies>,
    processes: Arc<crate::process_policies::ProcessPolicies>,
    network_slot: Arc<NetworkContextSlot>,
    network_policy: Mutex<Option<Arc<crate::network_policy::NetworkPolicyOwner>>>,
    user_controller: Mutex<Option<Arc<crate::user_controller::Owner>>>,
    statistics: Mutex<Option<Arc<crate::statistics_policies::StatisticsPolicies>>>,
    tunnel_capture: Arc<std::sync::OnceLock<std::sync::Weak<TunnelCapture>>>,
    cancel: CancellationToken, scope: Mutex<Option<Arc<OwnedLoadScope>>>, network: Mutex<Option<NetworkContext>>,
    status: watch::Sender<OwnedStatus>, listeners: Mutex<ListenerStatus>, commands: mpsc::Sender<Command>,
    task: tokio::sync::Mutex<Option<JoinHandle<()>>>,
    listener_intents: AtomicU64,
    cleanup: CleanupState,
    transports: Arc<crate::transport_tasks::TransportTasks>,
    transport_scope: crate::transport_tasks::ScopeSlot,
}
fn error(message: &'static str) -> io::Error { io::Error::other(message) }

impl OwnedRuntime {
    pub(crate) fn start(json: String, fd: Option<OwnedFd>, network: NetworkContext, generation: u64,
        cancel: CancellationToken, register: RegisterControl, lookup: Option<Arc<dyn FlowOwnerLookup>>) -> io::Result<Arc<Self>> {
        let raw_fd = fd.as_ref().map_or(-1, AsRawFd::as_raw_fd);
        let mode = serde_json::from_str::<serde_json::Value>(&json).ok().and_then(|v| v.get("runtimeMode").and_then(|v| v.as_str()).map(str::to_owned));
        let plan = Arc::new(RuntimePlan::parse(&json, raw_fd, mode.as_deref() == Some("tun"), false)?);
        let initial_probe = crate::probe_policy::ProbePolicies::from_host(&plan.host_policy_yaml)?;
        let initial_policy = crate::request_policies::RequestPolicies::from_host(&plan.host_policy_yaml)?;
        let find_mode = crate::process_policies::ProcessPolicies::from_host(&plan.host_policy_yaml)?.wire();
        if find_mode == "always" && plan.runtime_mode == RuntimeMode::Tun && lookup.is_none() { return Err(error("connectionOwnerRequired")); }
        let lookup = if plan.runtime_mode == RuntimeMode::Tun { lookup } else { None };
        let runtime = tokio::runtime::Handle::try_current().map_err(|_| error("runtimeUnavailable"))?;
        let plane = crate::providers::ControlPlane::new(generation, cancel.clone()).map_err(error)?;
        if cancel.is_cancelled() { plane.cancel(); }
        let policies = crate::request_policies::RequestPolicies::new(initial_policy, plane.clone(), cancel.clone())?;
        let processes = crate::process_policies::ProcessPolicies::new(generation,lookup.clone(),crate::process_policy::FindProcessMode::parse(find_mode)?,plane.clone(),cancel.clone())?;
        let probes = crate::probe_policy::ProbePolicies::new(initial_probe.clone(), plane.clone(), cancel.clone());
        let tunnel_capture = Arc::new(std::sync::OnceLock::new());
        let network = network.with_control_plane(plane.clone()).with_request_policy(policies.clone()).with_probe_policies(probes.clone()).with_tunnel_capture(tunnel_capture.clone());
        let network = match network.dns_owner() {
            Some(owner) => network.with_dns_transport(owner.transport_scope(&network)),
            None => {
                let raw = meow_config::parse_raw_yaml(&plan.engine_yaml).map_err(|_| error("invalidConfig"))?;
                let ipv6 = meow_config::effective_ipv6(raw.ipv6);
                if plan.physical_network.handle.is_none() && network.network_handle().is_none()
                    && plan.physical_network.dns.default_interface.is_none() && plan.physical_network.dns.interfaces.is_empty()
                    && (plan.runtime_mode == RuntimeMode::Proxy || plan.initially_parked) {
                    crate::physical_dns::PhysicalResolver::not_connected(network,ipv6)?.network()
                } else {
                    let servers = network.physical_dns_snapshot().ok_or_else(|| error("physicalDnsSnapshotMissing"))?;
                    crate::physical_dns::PhysicalResolver::from_servers(servers,network,ipv6)?.network()
                }
            }
        };
        let network = network.with_physical_dns_plan(plan.physical_network.dns.clone());
        let network_slot = NetworkContextSlot::new(&network);
        let network = network_slot.view(&network);
        let transports = crate::transport_tasks::TransportTasks::new();
        let transport_scope = Arc::new(std::sync::OnceLock::new());
        let network = network.with_transport(transports.clone(), transport_scope.clone());
        let scope = OwnedLoadScope::with_context(Arc::new(network.clone()));
        transport_scope.set(Arc::downgrade(&scope)).map_err(|_| error("transportScopeAlreadySet"))?;
        let (status, _) = watch::channel(OwnedStatus { state: "Starting", error: None, api_address: None,
            runtime_mode: if plan.runtime_mode == RuntimeMode::Tun { "tun" } else { "proxy" },
            tun_ready: false, user_listeners_ready: false, listening: false, core_ready: false });
        let (commands, receiver) = mpsc::channel(8);
        let owner = Arc::new(Self { plane, policies, probes, processes, network_slot, network_policy: Mutex::new(None), user_controller: Mutex::new(None), statistics: Mutex::new(None), tunnel_capture, cancel: cancel.clone(), scope: Mutex::new(Some(scope.clone())), network: Mutex::new(Some(network.clone())),
            status, commands, listeners: Mutex::new(ListenerStatus { listening: false, tun_ready: false,
                user_listeners_ready: false, active_ports: Vec::new(), core_epoch: generation.to_string(),
                only_statistics_proxy: crate::statistics_policies::StatisticsPolicies::from_host(&plan.host_policy_yaml)?, unified_delay: initial_probe.unified_delay, tcp_concurrent: initial_probe.tcp_concurrent, find_process_mode: find_mode }),
            task: tokio::sync::Mutex::new(None), listener_intents: AtomicU64::new(0), cleanup: CleanupState::default(), transports, transport_scope });
        let worker = owner.clone();
        let task = runtime.spawn(async move {
            let mut core = None;
            let serving = Box::pin(worker.serve(json, plan, fd, network.clone(), scope.clone(), generation, register, receiver, &mut core));
            let result = AssertUnwindSafe(serving).catch_unwind().await;
            let stopping = cancel.is_cancelled();
            worker.plane.cancel(); scope.cancel();
            let user = worker.user_controller.lock().clone();
            let user_closed = match &user { Some(owner) => owner.stop().await, None => Ok(()) };
            let closed = match core { Some(mut core) => core.close().await, None => Ok(()) };
            let closed = closed.and(user_closed);
            let network_policy = worker.network_policy.lock().clone();
            let network_closed = match &network_policy {
                Some(owner) => match owner.close().await { Ok(()) => Ok(()), Err(_) if owner.cleanup_confirmed() => Ok(()), Err(cause) => Err(io::Error::other(cause)) },
                None => match network.dns_owner() {
                    Some(owner) => match owner.close().await { Ok(()) => Ok(()), Err(_) if owner.cleanup_confirmed() => Ok(()), Err(cause) => Err(io::Error::other(cause)) },
                    None => Ok(()),
                }
            };
            let closed = closed.and(network_closed);
            let transports = worker.transports.close_joined().await.map(|_| ());
            let closed = closed.and(transports);
            worker.probes.close_and_join().await;
            let loaded = scope.close().await;
            let closed = closed.and(worker.processes.close_after_flows_join());
            network.cancel();
            let old_scope = worker.scope.lock().take(); drop(old_scope);
            let old_network = worker.network.lock().take(); drop(old_network);
            let cleanup_confirmed = worker.cleanup.complete(matches!(loaded, Ok(()) | Err("providerThreadPanicked")), closed.is_ok());
            if cleanup_confirmed {
                worker.network_slot.retire_after_join();
                let old_user = worker.user_controller.lock().take();
                let old_network = worker.network_policy.lock().take();
                let old_statistics = worker.statistics.lock().take();
                drop((old_user,old_network,old_statistics));
            }
            let failure = match (result, loaded, closed) {
                (_, Ok(()), Ok(())) if !cleanup_confirmed => Some("runtimeCleanupFailed".to_owned()),
                (_, _, Err(_)) => Some("runtimeCleanupFailed".to_owned()),
                (_, Err(_), _) => Some("loadJoinFailed".to_owned()),
                (_, _, _) if network_policy.as_ref().and_then(|owner| owner.failure()).is_some() => network_policy.as_ref().and_then(|owner| owner.failure()).map(str::to_owned),
                (_, _, _) if user.as_ref().and_then(|owner| owner.failure()).is_some() => user.as_ref().and_then(|owner| owner.failure()).map(str::to_owned),
                (_, _, _) if worker.transports.panicked() => Some("transportTaskPanicked".to_owned()),
                (Err(_), _, _) => Some("ownedRuntimePanicked".to_owned()),
                (Ok(Err(failure)), _, _) if !stopping => Some(failure.to_string()),
                _ => None,
            };
            worker.status.send_modify(|status| {
                status.state = if failure.is_some() { "Failed" } else { "Exited" }; status.error = failure.clone();
                status.core_ready = false; status.listening = false; status.tun_ready = false;
                status.user_listeners_ready = false; status.api_address = None;
            });
            worker.publish_inactive();
        });
        *owner.task.try_lock().expect("new owned runtime task") = Some(task);
        Ok(owner)
    }

    pub fn user_controller(&self) -> io::Result<Arc<crate::user_controller::Owner>> { self.user_controller.lock().clone().ok_or_else(|| error("runtimeNotReady")) }
    pub fn statistics(&self) -> io::Result<Arc<crate::statistics_policies::StatisticsPolicies>> { self.statistics.lock().clone().ok_or_else(|| error("runtimeNotReady")) }
    pub fn network_policy(&self) -> io::Result<Arc<crate::network_policy::NetworkPolicyOwner>> { self.network_policy.lock().clone().ok_or_else(|| error("runtimeNotReady")) }
    pub fn network_identity(&self) -> (Option<u64>,Option<Vec<SocketAddr>>) { self.network_slot.current_identity() }
    pub(crate) fn publish_statistics(&self, only: bool) { self.listeners.lock().only_statistics_proxy = only; }
    pub fn policies(&self) -> Arc<crate::request_policies::RequestPolicies> { self.policies.clone() }
    pub fn processes(&self) -> Arc<crate::process_policies::ProcessPolicies> { self.processes.clone() }
    pub(crate) fn publish_process_mode(&self, mode: &'static str) { self.listeners.lock().find_process_mode = mode; }
    pub(crate) fn publish_probe_policy(&self, policy: &meow_common::probe_policy::ProbePolicy) {
        let mut status = self.listeners.lock(); status.unified_delay = policy.unified_delay; status.tcp_concurrent = policy.tcp_concurrent;
    }
    pub fn probes(&self) -> Arc<crate::probe_policy::ProbePolicies> { self.probes.clone() }
    pub async fn host_policy_command(&self, json: String) -> io::Result<String> {
        if json.len() > 65536 { return Err(error("hostPolicyCommandTooLarge")); }
        let (reply, receipt) = oneshot::channel();
        tokio::select! { biased;
            _ = self.cancel.cancelled() => return Err(error("runtimeExited")),
            result = self.commands.send(Command::HostPolicy(json, reply)) => result.map_err(|_| error("runtimeExited"))?,
        }
        receipt.await.map_err(|_| error("runtimeExited"))?
    }
    pub fn cleanup_confirmed(&self) -> bool { self.cleanup.confirmed.load(Ordering::Acquire) }
    pub fn status(&self) -> OwnedStatus { self.status.borrow().clone() }
    pub fn route_stamp(&self) -> (u64, u64) { self.plane.stamp() }
    pub fn listener_status(&self) -> ListenerStatus {
        let gate = self.plane.gate.lock();
        let mut status = self.listeners.lock().clone();
        status.core_epoch = gate.core_epoch.to_string();
        if !gate.closed {
            let actual = self.probes.policy_under_plane_gate();
            status.unified_delay = actual.unified_delay; status.tcp_concurrent = actual.tcp_concurrent;
            status.find_process_mode = self.processes.mode_under_plane_gate();
            if let Some(statistics) = self.statistics.lock().as_ref() { if let Ok(only) = statistics.value_under_plane_gate(gate.core_epoch) { status.only_statistics_proxy = only; } }
        }
        status
    }
    pub fn subscribe(&self) -> watch::Receiver<OwnedStatus> { self.status.subscribe() }
    pub async fn ready(&self) -> io::Result<OwnedStatus> {
        let mut changed = self.subscribe();
        loop {
            let status = changed.borrow_and_update().clone();
            match status.state { "Running" => return Ok(status), "Failed" | "Exited" => return Err(error("runtimeNotReady")), _ => {} }
            changed.changed().await.map_err(|_| error("runtimeExited"))?;
        }
    }
    pub(crate) fn cancel(&self) {
        self.plane.cancel();
        if let Some(scope) = self.scope.lock().as_ref() { scope.cancel(); }
    }
    pub(crate) async fn wait(&self) -> io::Result<OwnedStatus> {
        let mut slot = self.task.lock().await;
        if let Some(task) = slot.as_mut() {
            let result = task.await; slot.take(); result.map_err(|_| error("runtimeOwnerPanicked"))?;
        }
        Ok(self.status())
    }
    pub async fn park(&self) -> io::Result<ListenerStatus> {
        self.admit_listener_intent()?;
        let (reply, receipt) = oneshot::channel();
        tokio::select! { biased;
            _ = self.cancel.cancelled() => return Err(error("runtimeExited")),
            sent = self.commands.send(Command::Park(reply)) => sent.map_err(|_| error("runtimeExited"))?,
        }
        receipt.await.map_err(|_| error("runtimeExited"))??;
        Ok(self.listener_status())
    }
    pub async fn resume(&self, fd: Option<OwnedFd>) -> io::Result<ListenerStatus> {
        if (self.status().runtime_mode == "tun") != fd.is_some() { return Err(error("resumeFdModeMismatch")); }
        self.admit_listener_intent()?;
        let (reply, receipt) = oneshot::channel();
        tokio::select! { biased;
            _ = self.cancel.cancelled() => return Err(error("runtimeExited")),
            sent = self.commands.send(Command::Resume(fd, reply)) => sent.map_err(|_| error("runtimeExited"))?,
        }
        receipt.await.map_err(|_| error("runtimeExited"))??;
        Ok(self.listener_status())
    }
    fn admit_listener_intent(&self) -> io::Result<()> {
        if self.cancel.is_cancelled() { return Err(error("runtimeExited")); }
        self.listener_intents.fetch_update(Ordering::AcqRel, Ordering::Acquire, |value| value.checked_add(1))
            .map_err(|_| error("listenerIntentExhausted"))?;
        Ok(())
    }
    fn publish_inactive(&self) {
        let mut listeners = self.listeners.lock(); listeners.listening = false; listeners.tun_ready = false;
        listeners.user_listeners_ready = false; listeners.active_ports.clear();
        self.status.send_modify(|status| { status.listening = false; status.tun_ready = false; status.user_listeners_ready = false; });
    }

    async fn serve(&self, json: String, plan: Arc<RuntimePlan>, fd: Option<OwnedFd>, network: NetworkContext,
        scope: Arc<OwnedLoadScope>, _generation: u64, register: RegisterControl,
        mut commands: mpsc::Receiver<Command>, core_slot: &mut Option<RunningCore>) -> io::Result<()> {
        let engine = managed_yaml(&plan)?;
        let config_work = Box::pin(meow_config::load_config_from_str_owned(&engine, &scope));
        let loading = scope.run(network.serve_scope(config_work));
        let config = tokio::select! { biased;
            _ = self.cancel.cancelled() => return Ok(()),
            result = loading => result.map_err(|_| error("invalidConfig"))?,
        };
        if self.cancel.is_cancelled() { return Ok(()); }
        let specs = config.listeners.named.clone();
        for spec in &specs { if !matches!(spec.spec, ListenerSpec::Mixed | ListenerSpec::Http | ListenerSpec::Socks5) { return Err(error("managedListenerNotIntegrated")); } }
        let mixed = specs.iter().find(|spec| matches!(spec.spec, ListenerSpec::Mixed)).ok_or_else(|| error("mixedListenerRequired"))?;
        let mixed_address = listener_address(mixed)?;
        let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
        let capture = Arc::new(TunnelCapture { tunnel: tunnel.clone() });
        self.tunnel_capture.set(Arc::downgrade(&capture)).map_err(|_| error("tunnelCaptureAlreadySet"))?;
        let resolver_slot = tunnel.resolver_slot();
        let initial_resolver = Arc::clone(&resolver_slot.read());
        let retired_initial = self.network_slot.replace(&network.captured().with_resolver_pair(crate::network_context::ResolverPair { main: initial_resolver, proxy: config.dns.proxy_resolver.clone() }));
        drop(retired_initial);
        let dns_raw = config.raw.clone(); let dns_proxies = config.proxies.clone();
        let dns_rules = config.rule_providers.clone(); let dns_dialers = config.dialer_registry.clone();
        let factory: crate::network_policy::DnsFactory = Arc::new(move |context, prior| {
            let raw = dns_raw.clone(); let proxies = dns_proxies.clone(); let rules = dns_rules.clone(); let dialers = dns_dialers.clone();
            let dns_transport = meow_config::owned_load::LoadContext::dns_transport_scope(&context);
            let loads = OwnedLoadScope::with_context(Arc::new(context));
            crate::network_policy::DnsBuild { scope: loads, work: Box::pin(async move {
                let work = Box::pin(meow_config::parse_dns_from_raw(&raw,None,&proxies,Some(&rules),None,Some(&prior),Some(&dialers)));
                let transport = dns_transport.ok_or_else(|| "dnsTransportOwnerMissing".to_owned())?;
                let dns = meow_config::owned_load::with_dns_transport_scope(transport,work).await.map_err(|_| "dnsRebuildFailed".to_owned())?;
                Ok(crate::network_context::ResolverPair { main: dns.resolver, proxy: dns.proxy_resolver })
            }) }
        });
        let network_policy = crate::network_policy::NetworkPolicyOwner::new(self.plane.clone(),tunnel.clone(),self.network_slot.clone(),network.captured(),factory,config.general.ipv6,self.cancel.clone(),0).map_err(error)?;
        network_policy.bind_process_policy(self.processes.clone());
        *self.network_policy.lock() = Some(network_policy);
        let only = crate::statistics_policies::StatisticsPolicies::from_host(&plan.host_policy_yaml)?;
        let statistics = crate::statistics_policies::StatisticsPolicies::new(tunnel.statistics().clone(),_generation,only,self.plane.clone(),self.cancel.clone())?;
        *self.statistics.lock() = Some(statistics);
        tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
        tunnel.set_mode(config.general.mode); tunnel.set_connection_notes(config.general.connection_notes);
        let listener = TcpListener::bind(plan.private_control.address).await.map_err(|_| error("privateApiBindFailed"))?;
        let address = listener.local_addr()?;
        let api = Arc::new(CoreApi::assemble_with_plane(&config, tunnel.clone(), mixed_address,
            &plan.private_control.secret, None, Router::new(), self.plane.clone()).map_err(|_| error("apiAssemblyFailed"))?);
        let user = crate::user_controller::Owner::new(api.clone(),address,Arc::new(network.clone()))?;
        let mut user_failure = user.subscribe_failure();
        *self.user_controller.lock() = Some(user.clone());
        if !register(api.control.clone(), tunnel.clone()) { api.cancel(); api.join().await; return Ok(()); }
        let api_cancel = self.cancel.child_token();
        let (fault, mut faults) = mpsc::channel(8);
        *core_slot = Some(RunningCore { tunnel: tunnel.clone(), api: api.clone(), private_task: None,
            private_cancel: api_cancel.clone(), active: None, fault: fault.clone(), dns_enabled: config.dns.enabled, group_events: None, health: Vec::new(), _capture: capture });
        api.install_log_sink().map_err(|_| error("logSinkFailed"))?;
        let auth = config.auth.clone();
        let health_specs = meow_config::extract_health_check_specs(config.raw.proxy_groups.as_deref().unwrap_or(&[]));
        tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
        let group_events = api.control.activate_groups(&scope).map_err(|_| error("groupActivationFailed"))?;
        core_slot.as_mut().expect("installed core").group_events = Some(group_events);
        for spec in health_specs {
            let inner = tunnel.weak_inner(); let probe = self.probes.scope();
            let health_fault = fault.clone(); let health_cancel = self.cancel.clone();
            let work = async move {
                let result = AssertUnwindSafe(probe.run_owned(meow_tunnel::health_check::run_health_check_loop(inner, spec))).catch_unwind().await;
                if !health_cancel.is_cancelled() || result.is_err() { let _ = health_fault.try_send("healthOwnerExited"); }
            };
            let task = scope.spawn_owned(work).map_err(|_| error("healthOwnerAdmissionFailed"))?;
            core_slot.as_mut().expect("installed core").health.push(task);
        }
        api.activate_providers().map_err(|_| error("providerActivationFailed"))?;
        let serving = api.clone(); let stop = api_cancel.clone();
        let private_fault = fault.clone();
        let context = network.clone();
        let private_router = crate::network_context::scope_router(crate::probe_policy::scope_router(serving.private_router(), &self.probes), &network);
        let private_task = tokio::spawn(async move {
            let result = AssertUnwindSafe(context.serve_scope(serve_private(listener, private_router, stop.clone(), context.clone()))).catch_unwind().await
                .unwrap_or(Err(crate::api::ApiError("privateApiPanicked")));
            if !stop.is_cancelled() { let _ = private_fault.try_send("privateApiOwnerExited"); }
            result
        });
        core_slot.as_mut().expect("installed core").private_task = Some(private_task);
        let startup_user = crate::host_policies::startup_user(&plan.host_policy_yaml)?;
        if startup_user.enabled { user.configure(self.route_stamp().0,true,&startup_user.secret).await?; }
        self.status.send_modify(|status| { status.api_address = Some(address); status.core_ready = true; });
        if !plan.initially_parked && self.listener_intents.load(Ordering::Acquire) == 0 {
            self.activate(core_slot.as_mut().expect("installed core"), &json, &plan, fd, &network, &specs, &auth).await?;
        } else { drop(fd); }
        self.status.send_modify(|status| { if !self.cancel.is_cancelled() { status.state = "Running"; } });
        loop { tokio::select! { biased;
            _ = self.cancel.cancelled() => { if let Some(cause) = user.failure() { return Err(error(cause)); } if let Some(cause) = self.network_policy()?.failure() { return Err(error(cause)); } return Ok(()); },
            changed = user_failure.changed() => { if changed.is_err() { return Err(error("userControllerFailureChannelClosed")); } if let Some(cause) = user.failure() { return Err(error(cause)); } },
            fault = faults.recv() => return Err(error(fault.unwrap_or("listenerOwnerExited"))),
            command = commands.recv() => match command {
                Some(Command::HostPolicy(json, reply)) => {
                    let result = crate::host_policies::execute(self, &json).await;
                    let _ = reply.send(result);
                },
                Some(Command::Park(reply)) => {
                    let core = core_slot.as_mut().expect("installed core");
                    core.tunnel.close_all_udp_sessions();
                    if let Some(mut active) = core.active.take() { self.cleanup.park(&mut active).await?; }
                    self.publish_inactive();
                    if self.transports.panicked() { return Err(error("transportTaskPanicked")); }
                    let _ = reply.send(Ok(()));
                },
                Some(Command::Resume(fd, reply)) => {
                    let core = core_slot.as_mut().expect("installed core");
                    if core.active.is_some() { let _ = reply.send(Err(error("alreadyListening"))); continue; }
                    let result = self.activate(core, &json, &plan, fd, &network, &specs, &auth).await;
                    let failed = result.is_err(); let _ = reply.send(result);
                    if failed { return Err(error("listenerResumeFailed")); }
                },
                None => return Ok(()),
            },
        } }
    }

    async fn activate(&self, core: &mut RunningCore, json: &str, plan: &RuntimePlan, fd: Option<OwnedFd>,
        network: &NetworkContext, specs: &[NamedListener], auth: &Arc<meow_common::AuthConfig>) -> io::Result<()> {
        let mut bound = Vec::new(); let mut ports = Vec::new();
        for spec in specs { let socket = TcpListener::bind(listener_address(spec)?).await.map_err(|_| error("userListenerBindFailed"))?; ports.push(socket.local_addr()?.port()); bound.push((socket, spec.clone())); }
        let dns_address = if core.dns_enabled { dns_listen(plan)? } else { None };
        let dns = match dns_address {
            Some(address) => { let tcp = TcpListener::bind(address).await.map_err(|_| error("dnsTcpBindFailed"))?;
                let udp = UdpSocket::bind(tcp.local_addr()?).await.map_err(|_| error("dnsUdpBindFailed"))?; ports.push(tcp.local_addr()?.port()); Some((tcp, udp)) },
            None => None,
        };
        let transports = self.transports.child();
        let network = network.child().with_transport(transports.clone(), self.transport_scope.clone());
        let stop = network.cancellation();
        core.active = Some(ActiveListeners { cancel: stop.clone(), tasks: JoinSet::new(), driver: None, ingress: meow_listener::owned_ingress::OwnedIngress::new(), transports });
        let active = core.active.as_mut().expect("registered listeners");
        if plan.runtime_mode == RuntimeMode::Tun {
            let fd = fd.ok_or_else(|| error("attachedTunFdRequired"))?;
            let (driver_json, driver_network) = attachment_plan(json, &network)?;
            let tunnel = core.tunnel.clone(); let processes = self.processes.clone(); let handler_network = network.clone();
            let driver = TunDriver::start(&driver_json, fd.as_fd(), driver_network, stop.clone(), move |_, _| async move {
                TunnelPackets::with_context(tunnel, Duration::from_secs(60), processes, handler_network).map(|handler| handler as Arc<dyn crate::tun_driver::PacketHandler>)
            })?;
            active.driver = Some(driver.clone());
            driver.await_ready().await?;
            let fault = core.fault.clone(); let cancel = stop.clone();
            active.tasks.spawn(async move {
                let result = driver.exited().await;
                if !cancel.is_cancelled() || result.is_err() { let _ = fault.try_send("tunDriverExited"); }
            });
        } else if fd.is_some() { return Err(error("proxyFdForbidden")); }
        for (socket, spec) in bound {
            let tunnel = core.tunnel.clone(); let auth = auth.clone(); let context = network.clone(); let cancel = stop.clone(); let ingress = active.ingress.clone(); let fault = core.fault.clone();
            active.tasks.spawn(async move {
                let result = AssertUnwindSafe(ingress.scope(context.serve_scope(serve_inbound(socket, spec, tunnel, auth, cancel.clone(), context.clone())))).catch_unwind().await;
                if !cancel.is_cancelled() || result.is_err() { let _ = fault.try_send("userListenerOwnerExited"); }
            });
        }
        if let Some((tcp, udp)) = dns {
            let tunnel = core.tunnel.clone(); let cancel = stop.clone(); let context = network.clone(); let fault = core.fault.clone();
            active.tasks.spawn(async move {
                let result = AssertUnwindSafe(context.serve_scope(serve_dns(tcp, udp, tunnel, cancel.clone(), context.clone()))).catch_unwind().await;
                if !cancel.is_cancelled() || result.is_err() { let _ = fault.try_send("dnsListenerOwnerExited"); }
            });
        }
        ports.sort_unstable(); ports.dedup();
        if self.cancel.is_cancelled() { return Err(error("cancelled")); }
        let tun = plan.runtime_mode == RuntimeMode::Tun;
        { let mut listeners = self.listeners.lock(); listeners.listening = true; listeners.tun_ready = tun;
            listeners.user_listeners_ready = true; listeners.active_ports = ports; }
        self.status.send_modify(|status| { status.listening = true; status.tun_ready = tun; status.user_listeners_ready = true; });
        Ok(())
    }
}

struct RunningCore { tunnel: Tunnel, api: Arc<CoreApi>, private_task: Option<JoinHandle<Result<(), crate::api::ApiError>>>, private_cancel: CancellationToken, active: Option<ActiveListeners>, fault: mpsc::Sender<&'static str>, dns_enabled: bool, group_events: Option<JoinHandle<()>>, health: Vec<JoinHandle<()>>, _capture: Arc<TunnelCapture> }
impl RunningCore {
    async fn close(&mut self) -> io::Result<()> {
        self.api.cancel(); self.private_cancel.cancel();
        let active_result = match self.active.take() { Some(mut active) => active.close().await, None => Ok(()) };
        self.tunnel.statistics().close_all_connections(); self.tunnel.close_all_udp_sessions();
        let private_result = match self.private_task.as_mut() { Some(task) => task.await.map_err(|_| error("privateApiOwnerPanicked")).and_then(|result| result.map_err(|_| error("privateApiOwnerFailed"))), None => Ok(()) };
        self.private_task.take();
        self.api.join().await;
        let mut task_result = Ok(());
        while let Some(task) = self.health.last_mut() {
            if task.await.is_err_and(|joined| !joined.is_cancelled()) { task_result = Err(error("healthOwnerPanicked")); }
            self.health.pop();
        }
        if let Some(task) = self.group_events.as_mut() {
            if task.await.is_err_and(|joined| !joined.is_cancelled()) { task_result = Err(error("groupEventOwnerPanicked")); }
        }
        self.group_events.take();
        active_result.and(private_result).and(task_result)
    }
}
struct ActiveListeners { cancel: CancellationToken, tasks: JoinSet<()>, driver: Option<Arc<TunDriver>>, ingress: Arc<meow_listener::owned_ingress::OwnedIngress>, transports: Arc<crate::transport_tasks::TransportTasks> }
impl ActiveListeners {
    async fn close(&mut self) -> io::Result<()> {
        self.cancel.cancel();
        self.ingress.close().await;
        let driver = match self.driver.as_ref() { Some(driver) => driver.stop().await.map(|_| ()), None => Ok(()) };
        self.driver.take();
        while self.tasks.join_next().await.is_some() {}
        let transports = self.transports.close_joined().await.map(|_| ());
        driver.and(transports)
    }
}
fn listener_address(spec: &NamedListener) -> io::Result<SocketAddr> {
    Ok(SocketAddr::new(spec.listen.parse().map_err(|_| error("listenerMustUseIpLiteral"))?, spec.port))
}
fn attachment_plan(json: &str, network: &NetworkContext) -> io::Result<(String,NetworkContext)> {
    let captured = network.captured();
    let dns = captured.captured_dns_plan().ok_or_else(|| error("physicalDnsPlanMissing"))?;
    let mut value: serde_json::Value = serde_json::from_str(json).map_err(|_| error("invalidPlan"))?;
    value["initiallyParked"] = false.into();
    value["physicalNetwork"] = serde_json::json!({"handle":captured.network_handle().map(|handle|handle.to_string()),"dns":dns.as_ref()});
    Ok((value.to_string(),captured))
}
fn dns_listen(plan: &RuntimePlan) -> io::Result<Option<SocketAddr>> {
    let value: serde_yaml::Value = serde_yaml::from_str(&plan.inbounds.config_yaml).map_err(|_| error("invalidInbounds"))?;
    match value.get("dns-listen") { None | Some(serde_yaml::Value::Null) => Ok(None), Some(serde_yaml::Value::String(value)) => value.parse().map(Some).map_err(|_| error("invalidDnsListen")), _ => Err(error("invalidDnsListen")) }
}
fn runtime_protocol_enabled(kind: &str) -> bool {
    match kind {
        "http" | "socks5" | "direct" => true,
        "ss" => cfg!(feature = "ss"), "trojan" => cfg!(feature = "trojan"),
        "vmess" => cfg!(feature = "vmess"), "vless" => cfg!(feature = "vless"),
        "snell" => cfg!(feature = "snell"), "hysteria2" => cfg!(feature = "hysteria2"),
        "anytls" => cfg!(feature = "anytls"), "trusttunnel" => cfg!(feature = "trusttunnel"),
        _ => false,
    }
}
fn managed_yaml(plan: &RuntimePlan) -> io::Result<String> {
    let host: serde_yaml::Value = serde_yaml::from_str(&plan.host_policy_yaml).map_err(|_| error("invalidHostPolicy"))?;
    for (key, value) in host.as_mapping().ok_or_else(|| error("invalidHostPolicy"))? {
        if value.is_null() { return Err(io::Error::other(format!("hostPolicy.{}: null is invalid",key.as_str().unwrap_or("field")))); }
        match key.as_str() {
            Some("find-process-mode") => {},
            Some("aerove-system-dns") => {
                let snapshot: crate::runtime_plan::PhysicalDns = serde_yaml::from_value(value.clone()).map_err(|_| error("invalidHostPolicy.aerove-system-dns"))?;
                if snapshot != plan.physical_network.dns { return Err(error("hostPolicy.aerove-system-dnsMismatch")); }
            },
            Some("global-ua" | "check-certificate") => {},
            Some("unified-delay" | "tcp-concurrent") => {},
            Some("cors-allow-origins") if value.as_sequence().is_some_and(Vec::is_empty) => {},
            Some("cors-allow-origins") => return Err(error("privateControllerCorsRequiresEmptyOrigins")),
            Some("only-statistics-proxy" | "user-controller") => {},
            _ => return Err(error("unknownHostPolicyField")),
        }
    }
    let mut root: serde_yaml::Value = serde_yaml::from_str(&plan.engine_yaml).map_err(|_| error("invalidEngineYaml"))?;
    let map = root.as_mapping_mut().ok_or_else(|| error("invalidEngineYaml"))?;
    for key in ["proxy-providers", "rule-providers", "external-controller", "secret", "listeners", "mixed-port", "port", "socks-port"] {
        if map.contains_key(&serde_yaml::Value::String(key.into())) { return Err(error("engineFieldNotIntegrated")); }
    }
    if let Some(proxies) = map.get(&serde_yaml::Value::String("proxies".into())) {
        for (index, proxy) in proxies.as_sequence().ok_or_else(|| error("proxiesMustBeSequence"))?.iter().enumerate() {
            if !proxy.get("type").and_then(serde_yaml::Value::as_str).is_some_and(runtime_protocol_enabled) {
                return Err(io::Error::other(format!("proxies[{index}].type: protocol is unavailable in this compiled runtime")));
            }
        }
    }
    if map.get(&serde_yaml::Value::String("strict".into())).and_then(serde_yaml::Value::as_bool) != Some(true) { return Err(error("strictConfigRequired")); }
    if let Some(groups) = map.get(&serde_yaml::Value::String("proxy-groups".into())) {
        for (index, group) in groups.as_sequence().ok_or_else(|| error("groupsMustBeSequence"))?.iter().enumerate() {
            if !matches!(group.get("type").and_then(serde_yaml::Value::as_str), Some("select" | "fallback" | "url-test")) {
                return Err(io::Error::other(format!("proxy-groups[{index}].type: runtime health ownership not integrated")));
            }
        }
    }
    if let Some(tun) = map.get(&serde_yaml::Value::String("tun".into())) {
        if tun.get("enable").and_then(serde_yaml::Value::as_bool) != Some(false) || tun.get("auto-route").and_then(serde_yaml::Value::as_bool) != Some(false) { return Err(error("engineTunMustBeDisabled")); }
        if !tun.as_mapping().is_some_and(|map| map.keys().all(|key| matches!(key.as_str(), Some("enable" | "auto-route")))) { return Err(error("engineTunMustBeManaged")); }
    }
    if map.get(&serde_yaml::Value::String("dns".into())).and_then(|dns| dns.get("listen")).is_some_and(|value| !value.is_null()) { return Err(error("engineDnsListenMustBeManaged")); }
    if map.get(&serde_yaml::Value::String("geodata".into())).and_then(|geo| geo.get("auto-update")).and_then(serde_yaml::Value::as_bool) == Some(true) { return Err(error("geodataAutoUpdateNotIntegrated")); }
    let inbounds: serde_yaml::Value = serde_yaml::from_str(&plan.inbounds.config_yaml).map_err(|_| error("invalidInbounds"))?;
    for (key, value) in inbounds.as_mapping().ok_or_else(|| error("invalidInbounds"))? {
        let key_string = key.as_str().ok_or_else(|| error("invalidInbounds"))?;
        if key_string == "dns-listen" { continue; }
        if matches!(key_string, "redir-port" | "tproxy-port") && value.as_u64() == Some(0) { continue; }
        if !["mixed-port", "port", "socks-port", "allow-lan", "bind-address", "authentication", "listeners"].contains(&key_string) { return Err(error("inboundFieldNotIntegrated")); }
        if map.insert(key.clone(), value.clone()).is_some() { return Err(error("duplicateManagedField")); }
    }
    serde_yaml::to_string(&root).map_err(|_| error("invalidConfig"))
}
async fn serve_inbound(socket: TcpListener, spec: NamedListener, tunnel: Tunnel, auth: Arc<meow_common::AuthConfig>, cancel: CancellationToken, network: NetworkContext) {
    let mut connections = JoinSet::new();
    loop { tokio::select! { biased;
        _ = cancel.cancelled() => break,
        result = connections.join_next(), if !connections.is_empty() => { if result.is_some_and(|result| result.is_err()) { break; } },
        accepted = socket.accept(), if spec.max_connections == 0 || connections.len() < spec.max_connections => {
            let Ok((stream, peer)) = accepted else { break; };
            let tunnel = tunnel.clone(); let auth = auth.clone(); let spec = spec.clone();
            let context = network.clone();
            let ingress = meow_listener::owned_ingress::OwnedIngress::current();
            connections.spawn(async move {
                let work = inbound_connection(stream, peer, spec, tunnel, auth);
                if let Some(ingress) = ingress { ingress.scope(context.scope(work)).await; }
            });
        },
    } }
    connections.abort_all(); while connections.join_next().await.is_some() {}
}
async fn serve_private(listener: TcpListener, router: Router, cancel: CancellationToken, network: NetworkContext) -> Result<(), crate::api::ApiError> {
    let mut tasks = JoinSet::new();
    let result = loop { tokio::select! { biased;
        _ = cancel.cancelled() => break Ok(()),
        joined = tasks.join_next(), if !tasks.is_empty() => {
            if joined.is_some_and(|result| result.is_err()) { break Err(crate::api::ApiError("apiConnectionPanicked")); }
        },
        accepted = listener.accept(), if tasks.len() < 256 => {
            let (stream, _) = match accepted { Ok(accepted) => accepted, Err(_) => break Err(crate::api::ApiError("apiAcceptFailed")) };
            let context = network.clone(); let router = router.clone();
            tasks.spawn(async move { context.scope(crate::api::serve_connection(stream, router)).await; });
        },
    } };
    tasks.abort_all(); while tasks.join_next().await.is_some() {}
    result
}
async fn inbound_connection(stream: TcpStream, peer: SocketAddr, spec: NamedListener, tunnel: Tunnel, auth: Arc<meow_common::AuthConfig>) {
    let socks = match spec.spec {
        ListenerSpec::Socks5 => true, ListenerSpec::Http => false,
        ListenerSpec::Mixed => { let mut byte = [0u8];
            if !matches!(tokio::time::timeout(meow_listener::DEFAULT_HANDSHAKE_TIMEOUT, stream.peek(&mut byte)).await, Ok(Ok(1))) { return; } byte[0] == 5 },
        _ => return,
    };
    if socks { meow_listener::socks5::handle_socks5(&tunnel, stream, peer, None, Some(&auth), &spec.name, spec.port).await; }
    else { meow_listener::http_proxy::handle_http(&tunnel, stream, peer, None, Some(&auth), &spec.name, spec.port).await; }
}
async fn serve_dns(tcp: TcpListener, udp: UdpSocket, tunnel: Tunnel, cancel: CancellationToken, network: NetworkContext) {
    let udp = Arc::new(udp); let mut tasks = JoinSet::new(); let mut buffer = vec![0u8; 65535];
    loop { tokio::select! { biased;
        _ = cancel.cancelled() => break,
        _ = tasks.join_next(), if !tasks.is_empty() => {},
        request = udp.recv_from(&mut buffer), if tasks.len() < 256 => {
            let Ok((count, peer)) = request else { break; }; let request = buffer[..count].to_vec();
            let socket = udp.clone(); let (context, resolver) = network.captured_with_resolver();
            let resolver = resolver.unwrap_or_else(|| tunnel.resolver());
            tasks.spawn(async move { context.scope(async move {
                if let Ok(answer) = meow_dns::DnsServer::handle_query(&request, &resolver).await { let _ = socket.send_to(&answer, peer).await; }
            }).await; });
        },
        accepted = tcp.accept(), if tasks.len() < 256 => {
            let Ok((mut stream, _)) = accepted else { break; };
            let network = network.clone(); let tunnel = tunnel.clone();
            tasks.spawn(async move {
                loop {
                    let Ok(length) = stream.read_u16().await else { break; }; let mut request = vec![0u8; usize::from(length)];
                    if stream.read_exact(&mut request).await.is_err() { break; }
                    let (context, resolver) = network.captured_with_resolver();
                    let resolver = resolver.unwrap_or_else(|| tunnel.resolver());
                    let Ok(answer) = context.scope(meow_dns::DnsServer::handle_query(&request, &resolver)).await else { break; };
                    let Ok(length) = u16::try_from(answer.len()) else { break; };
                    if stream.write_u16(length).await.is_err() || stream.write_all(&answer).await.is_err() { break; }
                }
            });
        },
    } }
    tasks.abort_all(); while tasks.join_next().await.is_some() {}
}

#[cfg(test)]
#[path = "../tests/support/resource_roots.rs"]
mod resume_roots;

#[cfg(test)]
mod cleanup_ack_tests {
    use super::*;
    use std::os::{fd::{AsFd, AsRawFd}, unix::net::UnixDatagram};
    fn active(driver: Option<Arc<TunDriver>>) -> ActiveListeners {
        ActiveListeners { cancel: CancellationToken::new(), tasks: JoinSet::new(), driver,
            ingress: meow_listener::owned_ingress::OwnedIngress::new(), transports: crate::transport_tasks::TransportTasks::new() }
    }
    #[tokio::test]
    async fn park_failed_driver_join_cannot_be_acknowledged_by_later_empty_cleanup() {
        let (caller, _peer) = UnixDatagram::pair().unwrap(); caller.set_nonblocking(true).unwrap();
        let json = serde_json::json!({"schemaVersion":1,"runtimeMode":"tun","initiallyParked":false,
            "engineYaml":"{}","privateControl":{"address":"127.0.0.1:19090","secret":"cleanup-failure-regression-private-0001"},
            "inbounds":{"configYaml":"mixed-port: 17890"},"tun":{"mtu":1500,"ipv6":false,
                "inet4Address":"198.18.0.1/30","inet6Address":null,"dnsHijack":["any:53"],"autoRoute":false},
            "physicalNetwork":{"handle":null,"dns":{"defaultInterface":null,"interfaces":{}}},
            "hostPolicyYaml":"{}","profileId":null,"revision":null,"resourceGeneration":null});
        let driver = TunDriver::start(&json.to_string(),caller.as_fd(),NetworkContext::platform(CancellationToken::new()),
            CancellationToken::new(),|_,_| async { Err(error("fixture factory failed before netstack")) }).unwrap();
        assert!(driver.await_ready().await.is_err());
        let cleanup = CleanupState::default(); let mut listeners = Some(active(Some(driver)));
        let mut taken = listeners.take().unwrap();
        assert!(cleanup.park(&mut taken).await.is_err()); drop(taken);
        assert!(listeners.is_none());
        assert!(!cleanup.complete(true,true));
        assert!(!cleanup.confirmed.load(Ordering::Acquire));
        assert!(unsafe { libc::fcntl(caller.as_raw_fd(),libc::F_GETFD) } >= 0);
    }
    #[tokio::test]
    async fn joined_cleanup_is_acknowledged_without_erasing_failed_business_status() {
        let cleanup = CleanupState::default(); let mut listeners = active(None);
        cleanup.park(&mut listeners).await.unwrap();
        assert!(cleanup.complete(true,true));
        assert!(cleanup.confirmed.load(Ordering::Acquire));
    }
    fn attachment_fixture(roots: Option<&super::resume_roots::Roots>, private: SocketAddr, mixed: u16, mode: &str, host: &str) -> String {
        serde_json::json!({"schemaVersion":1,"runtimeMode":mode,"initiallyParked":true,
            "engineYaml":"strict: true\ndns: {enable: false}\nrules: ['MATCH,DIRECT']\ntun: {enable: false, auto-route: false}\n",
            "privateControl":{"address":private.to_string(),"secret":"attachment-private-secret-000000000001"},
            "inbounds":{"configYaml":format!("mixed-port: {mixed}")},
            "tun":if mode=="tun" {serde_json::json!({"mtu":1500,"ipv6":false,"inet4Address":"198.18.0.1/30","inet6Address":null,"dnsHijack":["any:53"],"autoRoute":false})} else {serde_json::Value::Null},
            "physicalNetwork":{"handle":if mode=="tun" {Some("1")} else {None},"dns":{"defaultInterface":"first","interfaces":{"first":["127.0.0.1"]}}},
            "hostPolicyYaml":host,"profileId":null,"revision":null,"resourceGeneration":null,
            "resourceReadRoot":roots.map(|roots|roots.read.to_str().unwrap()),"cacheRoot":roots.map(|roots|roots.cache.to_str().unwrap())}).to_string()
    }
    #[test]
    fn explicit_null_startup_policies_are_rejected_but_missing_defaults_remain() {
        for field in ["only-statistics-proxy","user-controller","cors-allow-origins","unknown-field"] {
            let json = attachment_fixture(None,"127.0.0.1:39875".parse().unwrap(),39876,"proxy",&format!("{field}: null"));
            let plan = RuntimePlan::parse(&json,-1,false,false).unwrap();
            assert!(managed_yaml(&plan).unwrap_err().to_string().contains(field));
        }
        assert!(crate::statistics_policies::StatisticsPolicies::from_host("only-statistics-proxy: null").is_err());
        assert!(crate::host_policies::startup_user("user-controller: null").is_err());
        assert!(crate::statistics_policies::StatisticsPolicies::from_host("{}").unwrap());
        assert!(!crate::host_policies::startup_user("{}").unwrap().enabled);
        let json = attachment_fixture(None,"127.0.0.1:39875".parse().unwrap(),39876,"proxy","{}");
        assert!(managed_yaml(&RuntimePlan::parse(&json,-1,false,false).unwrap()).is_ok());
    }
    #[test]
    fn protocol_and_urltest_admission_preserves_fields_and_rejects_unowned_groups() {
        let cases = [("http",true),("socks5",true),("direct",true),("ss",cfg!(feature="ss")),
            ("trojan",cfg!(feature="trojan")),("vmess",cfg!(feature="vmess")),("vless",cfg!(feature="vless")),
            ("snell",cfg!(feature="snell")),("hysteria2",cfg!(feature="hysteria2")),
            ("anytls",cfg!(feature="anytls")),("trusttunnel",cfg!(feature="trusttunnel"))];
        for (kind,enabled) in cases {
            let json = attachment_fixture(None,"127.0.0.1:39875".parse().unwrap(),39876,"proxy","{}");
            let mut plan = RuntimePlan::parse(&json,-1,false,false).unwrap();
            let yaml = format!("strict: true\nproxies:\n - {{name: NODE,type: {kind},server: 127.0.0.1,port: 443,udp: true}}\nproxy-groups:\n - {{name: AUTO,type: url-test,proxies: [NODE],url: 'http://probe.invalid/health',interval: 17,lazy: false,tolerance: 55}}\nrules: ['MATCH,AUTO']\n");
            plan.engine_yaml = yaml.clone();
            match managed_yaml(&plan) {
                Ok(result) => { assert!(enabled,"{kind}"); let result:serde_yaml::Value=serde_yaml::from_str(&result).unwrap();
                    let source:serde_yaml::Value=serde_yaml::from_str(&yaml).unwrap(); assert_eq!(result["proxies"],source["proxies"]);assert_eq!(result["proxy-groups"],source["proxy-groups"]); },
                Err(_) => assert!(!enabled,"compiled protocol {kind} was rejected"),
            }
        }
        for kind in ["ssh","wireguard","tailscale","shadowquic","tuic","unknown"] { assert!(!runtime_protocol_enabled(kind)); }
        for kind in ["relay","load-balance","unknown"] {
            let json = attachment_fixture(None,"127.0.0.1:39875".parse().unwrap(),39876,"proxy","{}");let mut plan=RuntimePlan::parse(&json,-1,false,false).unwrap();
            plan.engine_yaml=format!("strict: true\nproxy-groups: [{{name: BLOCKED,type: {kind},proxies: [DIRECT]}}]\n"); assert!(managed_yaml(&plan).is_err());
        }
    }
    #[tokio::test]
    async fn explicitly_disconnected_proxy_keeps_private_api_and_owned_dns_then_joins_without_user_ports() {
        use tokio::{net::TcpListener,time::{timeout,Duration}};
        let roots = super::resume_roots::Roots::new();
        let private = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = private.local_addr().unwrap(); drop(private);
        let mixed = TcpListener::bind("127.0.0.1:0").await.unwrap(); let mixed_address = mixed.local_addr().unwrap(); drop(mixed);
        let mut json: serde_json::Value = serde_json::from_str(&attachment_fixture(Some(&roots),address,mixed_address.port(),"proxy","{}")).unwrap();
        json["physicalNetwork"] = serde_json::json!({"handle":null,"dns":{"defaultInterface":null,"interfaces":{}}});
        let upstream = TcpListener::bind("127.0.0.1:0").await.unwrap(); let upstream_address = upstream.local_addr().unwrap();
        json["engineYaml"] = format!("strict: true\nrules: ['MATCH,DIRECT']\ntun: {{enable: false, auto-route: false}}\ndns:\n  enable: true\n  use-system-hosts: false\n  nameserver: ['tcp://{upstream_address}']\n").into();
        let session = crate::Registry::start_owned(json.to_string(),None,NetworkContext::platform(CancellationToken::new()),None).unwrap();
        timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap(); let owner = session.owned_runtime().unwrap();
        assert!(!owner.listener_status().listening);
        let network = owner.network.lock().as_ref().unwrap().clone(); let captured = network.captured();
        let dns_owner = captured.dns_owner().unwrap(); assert!(dns_owner.is_unavailable_not_connected());
        assert!(matches!(meow_config::owned_load::LoadContext::disabled_dns_source(&captured).unwrap(),meow_config::owned_load::DisabledDnsSource::UnavailableNotConnected));
        let resolver = owner.network_slot.current_core_resolver().unwrap();
        let failure = timeout(Duration::from_secs(2),network.scope(resolver.resolve_ips("not-connected.invalid"))).await.unwrap().unwrap_err();
        assert_eq!(failure.kind(),io::ErrorKind::NotConnected);
        assert!(timeout(Duration::from_millis(50),upstream.accept()).await.is_err());
        let mut client = TcpStream::connect(address).await.unwrap();
        client.write_all(b"GET /aerove/route HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer attachment-private-secret-000000000001\r\nConnection: close\r\n\r\n").await.unwrap();
        let mut response = String::new(); timeout(Duration::from_secs(2),client.read_to_string(&mut response)).await.unwrap().unwrap();
        assert!(response.starts_with("HTTP/1.1 200"));
        let port = TcpListener::bind(mixed_address).await.unwrap(); drop(port);
        timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap(); assert!(owner.cleanup_confirmed());
        assert!(dns_owner.cleanup_confirmed()); assert!(TcpListener::bind(address).await.is_ok());
    }
    #[tokio::test]
    async fn hot_binding_park_resume_uses_one_live_plan_and_joins_real_stack_without_closing_caller() {
        use tokio::{net::TcpListener, time::{timeout,Duration}};
        let roots = super::resume_roots::Roots::new();
        let private = TcpListener::bind("127.0.0.1:0").await.unwrap(); let private_address = private.local_addr().unwrap(); drop(private);
        let mixed = TcpListener::bind("127.0.0.1:0").await.unwrap(); let mixed_address = mixed.local_addr().unwrap(); drop(mixed);
        let (caller,_peer) = UnixDatagram::pair().unwrap(); caller.set_nonblocking(true).unwrap();
        let json = attachment_fixture(Some(&roots),private_address,mixed_address.port(),"tun","find-process-mode: off");
        let initial = NetworkContext::platform(CancellationToken::new()).fixture_network_identity(1).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
        let session = crate::Registry::start_owned(json.clone(),None,initial,None).unwrap();
        timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap(); let owner = session.owned_runtime().unwrap(); let generation = session.generation();
        assert!(timeout(Duration::from_secs(5),owner.resume(Some(caller.try_clone().unwrap().into()))).await.unwrap().unwrap().tun_ready);
        assert!(!timeout(Duration::from_secs(5),owner.park()).await.unwrap().unwrap().listening);
        let host_dns = crate::runtime_plan::PhysicalDns { default_interface:Some("second".into()),interfaces:std::collections::BTreeMap::from([("second".into(),vec!["127.0.0.2".into()])]) };
        let base = owner.network.lock().as_ref().unwrap().clone().fixture_network_identity(2);
        let physical = crate::physical_dns::PhysicalResolver::from_snapshot(&host_dns,base,false).unwrap();
        let next = physical.network().with_core_resolver(physical.core_resolver());
        let capture = owner.tunnel_capture.get().and_then(std::sync::Weak::upgrade).unwrap();
        let identity = owner.processes.prepare_network_invalidation().unwrap();
        let (stamp,retired) = owner.plane.commit_policy(owner.route_stamp().0,true,|| {
            let old_identity = owner.processes.install_network_invalidation(identity);
            capture.tunnel.set_resolver(physical.core_resolver());
            (owner.network_slot.replace(&next),old_identity)
        }).unwrap();
        let (old,old_identity) = retired.unwrap();
        if let Some(dns) = old.dns_owner() { timeout(Duration::from_secs(5),dns.close()).await.unwrap().unwrap(); }
        drop((old,old_identity));
        let live = owner.network.lock().as_ref().unwrap().clone();
        let (attachment,captured) = attachment_plan(&json,&live).unwrap(); let attachment:serde_json::Value=serde_json::from_str(&attachment).unwrap();
        assert_eq!(attachment["physicalNetwork"]["handle"],"2"); assert_eq!(attachment["physicalNetwork"]["dns"],serde_json::to_value(&host_dns).unwrap());
        assert_eq!(captured.network_handle(),Some(2)); assert_eq!(captured.physical_dns_snapshot(),Some(vec!["127.0.0.2:53".parse().unwrap()]));
        assert_eq!(attachment["tun"]["mtu"],1500); assert_eq!(attachment["tun"]["dnsHijack"],serde_json::json!(["any:53"]));
        let mut stale: serde_json::Value = serde_json::from_str(&json).unwrap(); stale["initiallyParked"] = false.into();
        let stale = TunDriver::start(&stale.to_string(),caller.as_fd(),captured,CancellationToken::new(),|_,_| {
            std::future::ready(Err::<Arc<dyn crate::tun_driver::PacketHandler>,io::Error>(error("stalePlanFactoryMustNotRun")))
        });
        match stale {
            Err(error) => assert_eq!(error.to_string(),"physical network binding context mismatch"),
            Ok(driver) => { let _ = driver.stop().await; panic!("stale network binding was admitted"); },
        }
        let resumed = timeout(Duration::from_secs(5),owner.resume(Some(caller.try_clone().unwrap().into()))).await.unwrap().unwrap();
        assert!(resumed.tun_ready && resumed.listening); assert_eq!(resumed.core_epoch,stamp.0.to_string());
        assert_eq!(session.generation(),generation);
        assert!(!timeout(Duration::from_secs(5),owner.park()).await.unwrap().unwrap().listening);
        timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap(); assert!(owner.cleanup_confirmed());
        assert!(unsafe{libc::fcntl(caller.as_raw_fd(),libc::F_GETFD)}>=0);
        assert!(TcpListener::bind(private_address).await.is_ok()); assert!(TcpListener::bind(mixed_address).await.is_ok());
    }

}
