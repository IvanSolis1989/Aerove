use crate::{api::CoreApi, control::RouteControl, network_context::NetworkContext, runtime_plan::{RuntimeMode, RuntimePlan}, tun_driver::TunDriver, tunnel_packets::TunnelPackets};
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
        let find_mode = process_mode(&plan)?;
        if find_mode == "always" && (lookup.is_none() || plan.runtime_mode != RuntimeMode::Tun) { return Err(error("connectionOwnerRequired")); }
        let lookup = if find_mode == "always" { lookup } else { None };
        let runtime = tokio::runtime::Handle::try_current().map_err(|_| error("runtimeUnavailable"))?;
        let plane = crate::providers::ControlPlane::new(generation, cancel.clone()).map_err(error)?;
        if cancel.is_cancelled() { plane.cancel(); }
        let policies = crate::request_policies::RequestPolicies::new(initial_policy, plane.clone(), cancel.clone())?;
        let probes = crate::probe_policy::ProbePolicies::new(initial_probe.clone(), plane.clone(), cancel.clone());
        let network = network.with_control_plane(plane.clone()).with_request_policy(policies.clone()).with_probe_policy(probes.scope());
        let transports = crate::transport_tasks::TransportTasks::new();
        let transport_scope = Arc::new(std::sync::OnceLock::new());
        let network = network.with_transport(transports.clone(), transport_scope.clone());
        let scope = OwnedLoadScope::with_context(Arc::new(network.clone()));
        transport_scope.set(Arc::downgrade(&scope)).map_err(|_| error("transportScopeAlreadySet"))?;
        let (status, _) = watch::channel(OwnedStatus { state: "Starting", error: None, api_address: None,
            runtime_mode: if plan.runtime_mode == RuntimeMode::Tun { "tun" } else { "proxy" },
            tun_ready: false, user_listeners_ready: false, listening: false, core_ready: false });
        let (commands, receiver) = mpsc::channel(8);
        let owner = Arc::new(Self { plane, policies, probes, cancel: cancel.clone(), scope: Mutex::new(Some(scope.clone())), network: Mutex::new(Some(network.clone())),
            status, commands, listeners: Mutex::new(ListenerStatus { listening: false, tun_ready: false,
                user_listeners_ready: false, active_ports: Vec::new(), core_epoch: generation.to_string(),
                only_statistics_proxy: false, unified_delay: initial_probe.unified_delay, tcp_concurrent: initial_probe.tcp_concurrent, find_process_mode: find_mode }),
            task: tokio::sync::Mutex::new(None), listener_intents: AtomicU64::new(0), cleanup: CleanupState::default(), transports, transport_scope });
        let worker = owner.clone();
        let task = runtime.spawn(async move {
            let mut core = None;
            let serving = Box::pin(worker.serve(json, plan, fd, network.clone(), scope.clone(), generation, register, receiver, &mut core, lookup));
            let result = AssertUnwindSafe(serving).catch_unwind().await;
            let stopping = cancel.is_cancelled();
            worker.plane.cancel(); scope.cancel();
            let closed = match core { Some(mut core) => core.close().await, None => Ok(()) };
            let transports = worker.transports.close().await;
            let closed = closed.and(transports);
            worker.probes.close_and_join().await;
            let loaded = scope.close().await;
            network.cancel();
            let old_scope = worker.scope.lock().take(); drop(old_scope);
            let old_network = worker.network.lock().take(); drop(old_network);
            let cleanup_confirmed = worker.cleanup.complete(loaded.is_ok(), closed.is_ok());
            let failure = match (result, loaded, closed) {
                (_, Ok(()), Ok(())) if !cleanup_confirmed => Some("runtimeCleanupFailed".to_owned()),
                (_, _, Err(_)) => Some("runtimeCleanupFailed".to_owned()),
                (_, Err(_), _) => Some("loadJoinFailed".to_owned()),
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

    pub fn policies(&self) -> Arc<crate::request_policies::RequestPolicies> { self.policies.clone() }
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
        let mut status = self.listeners.lock().clone();
        match self.probes.status() {
            Ok(actual) => { status.core_epoch = actual.core_epoch; status.unified_delay = actual.probe_dial.unified_delay; status.tcp_concurrent = actual.probe_dial.tcp_concurrent; },
            Err(_) => status.core_epoch = self.route_stamp().0.to_string(),
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
        mut commands: mpsc::Receiver<Command>, core_slot: &mut Option<RunningCore>, lookup: Option<Arc<dyn FlowOwnerLookup>>) -> io::Result<()> {
        let engine = managed_yaml(&plan)?;
        let config_work = Box::pin(meow_config::load_config_from_str_owned(&engine, &scope));
        let loading = scope.run(network.scope(config_work));
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
        tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
        tunnel.set_mode(config.general.mode); tunnel.set_connection_notes(config.general.connection_notes);
        let listener = TcpListener::bind(plan.private_control.address).await.map_err(|_| error("privateApiBindFailed"))?;
        let address = listener.local_addr()?;
        let api = Arc::new(CoreApi::assemble_with_plane(&config, tunnel.clone(), mixed_address,
            &plan.private_control.secret, None, Router::new(), self.plane.clone()).map_err(|_| error("apiAssemblyFailed"))?);
        if !register(api.control.clone(), tunnel.clone()) { api.cancel(); api.join().await; return Ok(()); }
        let api_cancel = self.cancel.child_token();
        let (fault, mut faults) = mpsc::channel(8);
        *core_slot = Some(RunningCore { tunnel: tunnel.clone(), api: api.clone(), private_task: None,
            private_cancel: api_cancel.clone(), active: None, fault: fault.clone(), dns_enabled: config.dns.enabled, group_events: None, health: Vec::new() });
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
        let private_router = crate::probe_policy::scope_router(serving.private_router(), &self.probes);
        let private_task = tokio::spawn(async move {
            let result = AssertUnwindSafe(context.scope(serve_private(listener, private_router, stop.clone(), context.clone()))).catch_unwind().await
                .unwrap_or(Err(crate::api::ApiError("privateApiPanicked")));
            if !stop.is_cancelled() { let _ = private_fault.try_send("privateApiOwnerExited"); }
            result
        });
        core_slot.as_mut().expect("installed core").private_task = Some(private_task);
        self.status.send_modify(|status| { status.api_address = Some(address); status.core_ready = true; });
        if !plan.initially_parked && self.listener_intents.load(Ordering::Acquire) == 0 {
            self.activate(core_slot.as_mut().expect("installed core"), &json, &plan, fd, &network, &specs, &auth, lookup.clone()).await?;
        } else { drop(fd); }
        self.status.send_modify(|status| { if !self.cancel.is_cancelled() { status.state = "Running"; } });
        loop { tokio::select! { biased;
            _ = self.cancel.cancelled() => return Ok(()),
            fault = faults.recv() => return Err(error(fault.unwrap_or("listenerOwnerExited"))),
            command = commands.recv() => match command {
                Some(Command::HostPolicy(json, reply)) => {
                    let result = crate::host_policies::execute(self, &json);
                    let _ = reply.send(result);
                },
                Some(Command::Park(reply)) => {
                    let core = core_slot.as_mut().expect("installed core");
                    core.tunnel.close_all_udp_sessions();
                    if let Some(mut active) = core.active.take() { self.cleanup.park(&mut active).await?; }
                    self.publish_inactive(); let _ = reply.send(Ok(()));
                },
                Some(Command::Resume(fd, reply)) => {
                    let core = core_slot.as_mut().expect("installed core");
                    if core.active.is_some() { let _ = reply.send(Err(error("alreadyListening"))); continue; }
                    let result = self.activate(core, &json, &plan, fd, &network, &specs, &auth, lookup.clone()).await;
                    let failed = result.is_err(); let _ = reply.send(result);
                    if failed { return Err(error("listenerResumeFailed")); }
                },
                None => return Ok(()),
            },
        } }
    }

    async fn activate(&self, core: &mut RunningCore, json: &str, plan: &RuntimePlan, fd: Option<OwnedFd>,
        network: &NetworkContext, specs: &[NamedListener], auth: &Arc<meow_common::AuthConfig>, lookup: Option<Arc<dyn FlowOwnerLookup>>) -> io::Result<()> {
        let mut bound = Vec::new(); let mut ports = Vec::new();
        for spec in specs { let socket = TcpListener::bind(listener_address(spec)?).await.map_err(|_| error("userListenerBindFailed"))?; ports.push(socket.local_addr()?.port()); bound.push((socket, spec.clone())); }
        let dns_address = if core.dns_enabled { dns_listen(plan)? } else { None };
        let dns = match dns_address {
            Some(address) => { let tcp = TcpListener::bind(address).await.map_err(|_| error("dnsTcpBindFailed"))?;
                let udp = UdpSocket::bind(tcp.local_addr()?).await.map_err(|_| error("dnsUdpBindFailed"))?; ports.push(tcp.local_addr()?.port()); Some((tcp, udp)) },
            None => None,
        };
        let transports = crate::transport_tasks::TransportTasks::new();
        let network = network.child().with_transport(transports.clone(), self.transport_scope.clone());
        let stop = network.cancellation();
        core.active = Some(ActiveListeners { cancel: stop.clone(), tasks: JoinSet::new(), driver: None, ingress: meow_listener::owned_ingress::OwnedIngress::new(), transports });
        let active = core.active.as_mut().expect("registered listeners");
        if plan.runtime_mode == RuntimeMode::Tun {
            let fd = fd.ok_or_else(|| error("attachedTunFdRequired"))?;
            let mut value: serde_json::Value = serde_json::from_str(json).map_err(|_| error("invalidPlan"))?;
            value["initiallyParked"] = false.into();
            let tunnel = core.tunnel.clone();
            let driver = TunDriver::start(&value.to_string(), fd.as_fd(), network.clone(), stop.clone(), move |_, _| async move {
                TunnelPackets::with_owner(tunnel, Duration::from_secs(60), lookup).map(|handler| handler as Arc<dyn crate::tun_driver::PacketHandler>)
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
                let result = AssertUnwindSafe(ingress.scope(context.scope(serve_inbound(socket, spec, tunnel, auth, cancel.clone(), context.clone())))).catch_unwind().await;
                if !cancel.is_cancelled() || result.is_err() { let _ = fault.try_send("userListenerOwnerExited"); }
            });
        }
        if let Some((tcp, udp)) = dns {
            let tunnel = core.tunnel.clone(); let cancel = stop.clone(); let context = network.clone(); let fault = core.fault.clone();
            active.tasks.spawn(async move {
                let result = AssertUnwindSafe(context.scope(serve_dns(tcp, udp, tunnel, cancel.clone(), context.clone()))).catch_unwind().await;
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

struct RunningCore { tunnel: Tunnel, api: Arc<CoreApi>, private_task: Option<JoinHandle<Result<(), crate::api::ApiError>>>, private_cancel: CancellationToken, active: Option<ActiveListeners>, fault: mpsc::Sender<&'static str>, dns_enabled: bool, group_events: Option<JoinHandle<()>>, health: Vec<JoinHandle<()>> }
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
        let transports = self.transports.close().await;
        driver.and(transports)
    }
}
fn listener_address(spec: &NamedListener) -> io::Result<SocketAddr> {
    Ok(SocketAddr::new(spec.listen.parse().map_err(|_| error("listenerMustUseIpLiteral"))?, spec.port))
}
fn dns_listen(plan: &RuntimePlan) -> io::Result<Option<SocketAddr>> {
    let value: serde_yaml::Value = serde_yaml::from_str(&plan.inbounds.config_yaml).map_err(|_| error("invalidInbounds"))?;
    match value.get("dns-listen") { None | Some(serde_yaml::Value::Null) => Ok(None), Some(serde_yaml::Value::String(value)) => value.parse().map(Some).map_err(|_| error("invalidDnsListen")), _ => Err(error("invalidDnsListen")) }
}
fn managed_yaml(plan: &RuntimePlan) -> io::Result<String> {
    let host: serde_yaml::Value = serde_yaml::from_str(&plan.host_policy_yaml).map_err(|_| error("invalidHostPolicy"))?;
    for (key, value) in host.as_mapping().ok_or_else(|| error("invalidHostPolicy"))? {
        if value.is_null() { continue; }
        match key.as_str() {
            Some("find-process-mode") => {},
            Some("aerove-system-dns") => {
                let snapshot: crate::runtime_plan::PhysicalDns = serde_yaml::from_value(value.clone()).map_err(|_| error("invalidHostPolicy.aerove-system-dns"))?;
                if snapshot != plan.physical_network.dns { return Err(error("hostPolicy.aerove-system-dnsMismatch")); }
            },
            Some("global-ua" | "check-certificate") => {},
            Some("unified-delay" | "tcp-concurrent") => {},
            Some("cors-allow-origins") => return Err(error("hostPolicy.cors-allow-originsNotIntegrated")),
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
            if !matches!(proxy.get("type").and_then(serde_yaml::Value::as_str), Some("http" | "socks5" | "direct")) {
                return Err(io::Error::other(format!("proxies[{index}].type: runtime transport ownership not integrated")));
            }
        }
    }
    if map.get(&serde_yaml::Value::String("strict".into())).and_then(serde_yaml::Value::as_bool) != Some(true) { return Err(error("strictConfigRequired")); }
    if let Some(groups) = map.get(&serde_yaml::Value::String("proxy-groups".into())) {
        for (index, group) in groups.as_sequence().ok_or_else(|| error("groupsMustBeSequence"))?.iter().enumerate() {
            if !matches!(group.get("type").and_then(serde_yaml::Value::as_str), Some("select" | "fallback")) {
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
fn process_mode(plan: &RuntimePlan) -> io::Result<&'static str> {
    let host: serde_yaml::Value = serde_yaml::from_str(&plan.host_policy_yaml).map_err(|_| error("invalidHostPolicy"))?;
    match host.get("find-process-mode") {
        None | Some(serde_yaml::Value::Null) => Ok("off"),
        Some(serde_yaml::Value::String(mode)) if mode == "off" => Ok("off"),
        Some(serde_yaml::Value::String(mode)) if mode == "always" => Ok("always"),
        _ => Err(error("findProcessModeNotIntegrated")),
    }
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
            let socket = udp.clone(); let resolver = tunnel.resolver();
            let context = network.clone();
            tasks.spawn(async move { context.scope(async move {
                if let Ok(answer) = meow_dns::DnsServer::handle_query(&request, &resolver).await { let _ = socket.send_to(&answer, peer).await; }
            }).await; });
        },
        accepted = tcp.accept(), if tasks.len() < 256 => {
            let Ok((mut stream, _)) = accepted else { break; }; let resolver = tunnel.resolver();
            let context = network.clone();
            tasks.spawn(async move { context.scope(async move {
                loop {
                    let Ok(length) = stream.read_u16().await else { break; }; let mut request = vec![0u8; usize::from(length)];
                    if stream.read_exact(&mut request).await.is_err() { break; }
                    let Ok(answer) = meow_dns::DnsServer::handle_query(&request, &resolver).await else { break; };
                    let Ok(length) = u16::try_from(answer.len()) else { break; };
                    if stream.write_u16(length).await.is_err() || stream.write_all(&answer).await.is_err() { break; }
                }
            }).await; });
        },
    } }
    tasks.abort_all(); while tasks.join_next().await.is_some() {}
}

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
}
