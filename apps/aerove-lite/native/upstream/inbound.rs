use std::{collections::{HashMap, HashSet}, io, net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr}, sync::{Arc, RwLock, atomic::{AtomicBool, Ordering}}, time::Duration};
use axum::{Router, body::to_bytes, extract::Request, response::{IntoResponse, Response}, routing::get};
use futures::{FutureExt, future::BoxFuture};
use http::StatusCode;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::{sync::{Mutex, broadcast, oneshot}, task::JoinSet};
use tokio_util::sync::CancellationToken;
use crate::{InternalConfig, RuntimeComponents, common::auth::{PlainAuthenticator, ThreadSafeAuthenticator, User}, config::listener::{CommonInboundOpts, InboundOpts}};

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum Kind { Http, Socks, Mixed }

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub(crate) struct Identity { kind: Kind, port: u16 }

#[derive(Clone, Copy)]
pub(crate) struct Bound { identity: Identity, address: SocketAddr }

#[derive(Clone, Deserialize)]
#[serde(rename_all = "kebab-case", deny_unknown_fields)]
struct Plan {
    mixed_port: u16,
    #[serde(default)] port: u16,
    #[serde(default)] socks_port: u16,
    allow_lan: bool,
    bind_address: IpAddr,
    authentication: Vec<String>,
}

impl Plan {
    fn validate(&self, controller: SocketAddr, ipv6: bool) -> crate::Result<()> {
        let mut ports = HashSet::new();
        for (field, port) in [("mixed-port", self.mixed_port), ("port", self.port), ("socks-port", self.socks_port)] {
            if port == 0 && field != "mixed-port" { continue; }
            if !(1024..=49151).contains(&port) { return Err(super::invalid(format!("$.{field}: port must be 1024..49151"))); }
            if port == controller.port() || !ports.insert(port) { return Err(super::invalid(format!("$.{field}: listener port conflict"))); }
        }
        let expected = if !self.allow_lan { IpAddr::V4(Ipv4Addr::LOCALHOST) }
            else if ipv6 { IpAddr::V6(Ipv6Addr::UNSPECIFIED) } else { IpAddr::V4(Ipv4Addr::UNSPECIFIED) };
        if self.bind_address != expected { return Err(super::invalid("$.bind-address: does not match allow-lan/ipv6 plan")); }
        if self.authentication.len() > 1 { return Err(super::invalid("$.authentication: requires at most one governed account")); }
        for account in &self.authentication {
            let Some((user, password)) = account.split_once(':') else { return Err(super::invalid("$.authentication: expected account and password")); };
            if user.is_empty() || user.len() > 255 || user.chars().count() > 128 || password.len() > 255
                || account.chars().any(char::is_control)
            { return Err(super::invalid("$.authentication: invalid account or SOCKS credential byte length")); }
        }
        Ok(())
    }

    fn listeners(&self) -> HashSet<InboundOpts> {
        let common = |name: &str, port| CommonInboundOpts { name: name.into(), listen: crate::config::internal::config::BindAddress(self.bind_address),
            allow_lan: self.allow_lan, port, fw_mark: None };
        let mut listeners = HashSet::from([InboundOpts::Mixed { common_opts: common("MIXED-IN", self.mixed_port), udp: true }]);
        if self.port != 0 { listeners.insert(InboundOpts::Http { common_opts: common("HTTP-IN", self.port) }); }
        if self.socks_port != 0 { listeners.insert(InboundOpts::Socks { common_opts: common("SOCKS-IN", self.socks_port), udp: true }); }
        listeners
    }

    fn expected(&self) -> HashMap<Identity, SocketAddr> {
        [(Kind::Mixed, self.mixed_port), (Kind::Http, self.port), (Kind::Socks, self.socks_port)].into_iter()
            .filter(|(_, port)| *port != 0).map(|(kind, port)| (Identity { kind, port }, SocketAddr::new(self.bind_address, port))).collect()
    }

    fn authenticator(&self) -> ThreadSafeAuthenticator {
        Arc::new(PlainAuthenticator::new(self.authentication.iter().map(|account| {
            let (user, password) = account.split_once(':').unwrap();
            User::new(user.into(), password.into())
        }).collect()))
    }

}

pub(crate) fn check_source(value: &serde_yaml::Value) -> crate::Result<()> {
    for field in ["redir-port", "tproxy-port"] {
        if let Some(port) = value.get(field) && port.as_u64() != Some(0) {
            return Err(super::invalid(format!("$.{field}: fixed Android core has no platform handler")));
        }
    }
    Ok(())
}

pub(crate) fn controller(config: &InternalConfig) -> crate::Result<SocketAddr> {
    let controller = &config.general.controller;
    let address: SocketAddr = controller.external_controller.as_deref()
        .ok_or_else(|| super::invalid("$.external-controller: runtime requires a private controller"))?
        .parse().map_err(|_| super::invalid("$.external-controller: expected loopback IP:port"))?;
    if address.ip() != IpAddr::V4(Ipv4Addr::LOCALHOST) || address.port() == 0 || controller.external_controller_ipc.is_some()
        || controller.secret.as_deref().is_none_or(|secret| secret.len() < 32 || !secret.bytes().all(|value| (33..=126).contains(&value)))
        || controller.external_ui.is_some() || controller.external_ui_download_url.is_some()
        || controller.cors_allow_origins.as_ref().is_some_and(|origins| !origins.is_empty())
    { return Err(super::invalid("runtime controller requires private 127.0.0.1 TCP, a strong secret and no IPC/UI/CORS")); }
    Ok(address)
}

pub(crate) fn runtime(source: &str, config: &InternalConfig, controller: SocketAddr, cancel: CancellationToken) -> crate::Result<Arc<Owner>> {
    let mut raw: serde_yaml::Value = serde_yaml::from_str(source).map_err(|_| super::invalid("runtime network plan is malformed"))?;
    raw.apply_merge().map_err(|_| super::invalid("runtime network merge is malformed"))?;
    for field in ["listeners", "inbound-providers"] {
        if raw.get(field).is_some_and(|value| match value {
            serde_yaml::Value::Sequence(items) => !items.is_empty(),
            serde_yaml::Value::Mapping(items) => !items.is_empty(),
            _ => true,
        }) { return Err(super::invalid(format!("$.{field}: runtime requires service-planned top-level ports"))); }
    }
    if !config.inbound_providers.is_empty() { return Err(super::invalid("$.inbound-providers: runtime providers are not service-owned")); }
    let mut plan = Plan { mixed_port: 0, port: 0, socks_port: 0, allow_lan: false,
        bind_address: config.general.bind_address.0, authentication: config.general.authentication.clone() };
    for listener in &config.listeners {
        let common = listener.common_opts();
        let expected_name = match listener {
            InboundOpts::Http { .. } => { plan.port = common.port; "HTTP-IN" },
            InboundOpts::Socks { udp: true, .. } => { plan.socks_port = common.port; "SOCKS-IN" },
            InboundOpts::Mixed { udp: true, .. } => { plan.mixed_port = common.port; "MIXED-IN" },
            _ => return Err(super::invalid("$.listeners: only planned HTTP/SOCKS/Mixed Android handlers are admitted")),
        };
        if common.name != expected_name || common.fw_mark.is_some() { return Err(super::invalid("$.listeners: unowned listener name or routing mark")); }
        plan.allow_lan = common.allow_lan;
        plan.bind_address = common.listen.0;
    }
    plan.validate(controller, config.general.ipv6)?;
    if plan.listeners() != config.listeners { return Err(super::invalid("$.listeners: runtime differs from governed network plan")); }
    Ok(Arc::new(Owner { controller, ipv6: config.general.ipv6, plan: RwLock::new(plan),
        serial: Mutex::new(()), events: broadcast::channel(32).0, tasks: Mutex::new(JoinSet::new()),
        connections: Mutex::new(JoinSet::new()), cleanup: std::sync::Mutex::new(JoinSet::new()), running: AtomicBool::new(false), cancel }))
}

pub(crate) struct Owner {
    controller: SocketAddr,
    ipv6: bool,
    plan: RwLock<Plan>,
    serial: Mutex<()>,
    events: broadcast::Sender<Bound>,
    tasks: Mutex<JoinSet<()>>,
    connections: Mutex<JoinSet<()>>,
    cleanup: std::sync::Mutex<JoinSet<()>>,
    running: AtomicBool,
    cancel: CancellationToken,
}

impl Owner {
    pub(crate) fn expected(&self) -> HashMap<Identity, SocketAddr> { self.plan.read().unwrap().expected() }
    pub(crate) fn mark_ready(&self) { self.running.store(true, Ordering::SeqCst); }

    pub(crate) async fn join(&self) -> crate::Result<()> {
        let mut failed = None;
        let mut tasks = self.tasks.lock().await;
        while let Some(result) = tasks.join_next().await {
            if let Err(error) = result { failed = Some(format!("network update task: {error}")); }
        }
        drop(tasks);
        let mut connections = self.connections.lock().await;
        while let Some(result) = connections.join_next().await {
            if let Err(error) = result { failed = Some(format!("user connection task: {error}")); }
        }
        drop(connections);
        loop {
            let mut tasks = std::mem::replace(&mut *self.cleanup.lock().unwrap(), JoinSet::new());
            if tasks.is_empty() { break; }
            while let Some(result) = tasks.join_next().await {
                if let Err(error) = result { failed = Some(format!("user datagram cleanup task: {error}")); }
            }
        }
        failed.map(|error| Err(crate::Error::Operation(error))).unwrap_or(Ok(()))
    }

    async fn apply(&self, components: Arc<RwLock<Arc<RuntimeComponents>>>, request: Update) -> std::result::Result<Value, (StatusCode, &'static str)> {
        let _serial = self.serial.lock().await;
        if self.cancel.is_cancelled() { return Err((StatusCode::SERVICE_UNAVAILABLE, "session_stopped")); }
        let stamp = super::telemetry::route_stamp().map_err(|_| (StatusCode::CONFLICT, "network_changed"))?;
        if Some(stamp.0) != request.epoch() { return Err((StatusCode::CONFLICT, "network_epoch_changed")); }
        let active = components.read().unwrap().clone();
        let changed = super::telemetry::route_mutation();
        let mut events = self.events.subscribe();
        let mut expected = request.network.expected();
        if let Err(error) = active.inbound_manager.mobile_replace(request.network.listeners(), request.network.authenticator()).await {
            super::task_failed(format!("user listener replacement: {error}"));
            return Err((StatusCode::INTERNAL_SERVER_ERROR, "network_listener_failed"));
        }
        let bound = async {
            while !expected.is_empty() {
                let event = events.recv().await.map_err(|_| ())?;
                if expected.get(&event.identity) != Some(&event.address) { return Err(()); }
                expected.remove(&event.identity);
            }
            Ok::<(), ()>(())
        };
        tokio::select! {
            biased;
            _ = self.cancel.cancelled() => return Err((StatusCode::SERVICE_UNAVAILABLE, "session_stopped")),
            result = tokio::time::timeout(Duration::from_secs(8), bound) => {
                if !matches!(result, Ok(Ok(()))) {
                    super::task_failed("user listener binding did not complete");
                    return Err((StatusCode::INTERNAL_SERVER_ERROR, "network_listener_failed"));
                }
            }
        }
        if !Arc::ptr_eq(&active, &components.read().unwrap()) { return Err((StatusCode::CONFLICT, "network_changed")); }
        *self.plan.write().unwrap() = request.network;
        super::telemetry::structure_changed();
        drop(changed);
        let stamp = super::telemetry::route_stamp().map_err(|_| (StatusCode::CONFLICT, "network_changed"))?;
        let readback = self.readback(&active, stamp).await?;
        Ok(json!({"requestId": request.request_id, "coreEpoch": readback["coreEpoch"]}))
    }

    async fn readback(&self, active: &RuntimeComponents, stamp: (u64, u64)) -> std::result::Result<Value, (StatusCode, &'static str)> {
        let manager = &active.inbound_manager;
        let ports = manager.get_ports().await;
        let mixed_port = ports.mixed_port.ok_or((StatusCode::CONFLICT, "network_changed"))?;
        let allow_lan = manager.get_allow_lan().await;
        let bind_address = manager.get_bind_address().await.0.to_string();
        let listeners = manager.get_listeners().await;
        if self.cancel.is_cancelled() || super::telemetry::route_stamp().ok() != Some(stamp) || listeners.iter().any(|listener| !listener.active) {
            return Err((StatusCode::CONFLICT, "network_changed"));
        }
        let mut active_ports: Vec<_> = listeners.iter().filter(|listener| listener.active).map(|listener| listener.port).collect();
        active_ports.sort_unstable();
        Ok(json!({"coreEpoch": stamp.0.to_string(), "network": {"mixed-port": mixed_port, "port": ports.port.unwrap_or(0),
                "socks-port": ports.socks_port.unwrap_or(0), "allow-lan": allow_lan, "bind-address": bind_address,
                "authEnabled": manager.mobile_authentication_enabled()}, "activePorts": active_ports}))
    }
}

pub(crate) struct ReadySignal { context: Arc<super::Context>, identity: Identity }

pub(crate) fn ready_signal(kind: Kind, port: u16) -> io::Result<ReadySignal> {
    let context = super::context()?;
    if context.cancel.is_cancelled() { return Err(io::ErrorKind::Interrupted.into()); }
    Ok(ReadySignal { context, identity: Identity { kind, port } })
}

pub(crate) fn is_stopping() -> bool { super::context().map(|context| context.cancel.is_cancelled()).unwrap_or(true) }

pub(crate) fn track_cleanup(task: tokio::task::JoinHandle<()>) {
    let Ok(context) = super::context() else { task.abort(); return; };
    let Some(owner) = &context.network else { task.abort(); return; };
    let mut tasks = owner.cleanup.lock().unwrap();
    while tasks.try_join_next().is_some() {}
    let events = context.events.clone();
    let cancel = context.cancel.clone();
    tasks.spawn(async move {
        if let Err(error) = task.await && !cancel.is_cancelled() {
            let _ = events.send(super::Event::Failed(format!("user datagram cleanup task: {error}")));
        }
    });
}

pub(crate) async fn spawn_connection<F>(connection: F)
where F: std::future::Future + Send + 'static, F::Output: Send + 'static {
    let Ok(context) = super::context() else { return; };
    let Some(owner) = &context.network else { return; };
    let mut tasks = owner.connections.lock().await;
    if context.cancel.is_cancelled() { return; }
    while tasks.try_join_next().is_some() {}
    let cancel = context.cancel.clone();
    let events = context.events.clone();
    tasks.spawn(async move {
        tokio::select! {
            biased;
            _ = cancel.cancelled() => {},
            result = std::panic::AssertUnwindSafe(connection).catch_unwind() => {
                if result.is_err() && !cancel.is_cancelled() {
                    let _ = events.send(super::Event::Failed("user connection task panicked".into()));
                }
            },
        }
    });
}

impl ReadySignal {
    pub(crate) fn bound(&self, address: SocketAddr) -> io::Result<()> {
        if self.context.cancel.is_cancelled() || !Arc::ptr_eq(&self.context, &super::context()?) {
            return Err(io::ErrorKind::Interrupted.into());
        }
        let event = Bound { identity: self.identity, address };
        let _ = self.context.events.send(super::Event::InboundReady(event));
        let _ = self.context.network.as_ref().map(|owner| owner.events.send(event));
        Ok(())
    }
}

pub(crate) fn accept_ready(expected: &mut HashMap<Identity, SocketAddr>, bound: Bound) -> crate::Result<()> {
    if expected.get(&bound.identity) != Some(&bound.address) { return Err(super::invalid("user listener reported an unplanned bind identity/address")); }
    expected.remove(&bound.identity);
    Ok(())
}

pub(crate) async fn listen(name: String, runners: Vec<BoxFuture<'static, crate::Result<()>>>, cancel: CancellationToken) {
    let context = match super::context() { Ok(context) => context, Err(_) => return };
    tokio::select! {
        biased;
        _ = cancel.cancelled() => {},
        _ = context.cancel.cancelled() => {},
        result = std::panic::AssertUnwindSafe(futures::future::try_join_all(runners)).catch_unwind() => {
            if !cancel.is_cancelled() && !context.cancel.is_cancelled() {
                let error = match result {
                    Ok(Err(error)) => format!("{name} inbound listener: {error}"),
                    Ok(Ok(_)) => format!("{name} inbound listener exited unexpectedly"),
                    Err(_) => format!("{name} inbound listener panicked"),
                };
                let _ = context.events.send(super::Event::Failed(error));
            }
        }
    }
}

pub(crate) async fn startup(setup: impl std::future::Future<Output = ()>) {
    let Ok(context) = super::context() else { return; };
    if std::panic::AssertUnwindSafe(setup).catch_unwind().await.is_err() && !context.cancel.is_cancelled() {
        let _ = context.events.send(super::Event::Failed("user listener startup panicked".into()));
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Update {
    request_id: String,
    expected_core_epoch: String,
    network: Plan,
}

impl Update {
    fn epoch(&self) -> Option<u64> {
        self.expected_core_epoch.parse::<u64>().ok().filter(|value| value.to_string() == self.expected_core_epoch)
    }

    fn valid_id(&self) -> bool {
        self.request_id.len() == 36 && self.request_id.bytes().enumerate().all(|(index, value)| {
            if [8, 13, 18, 23].contains(&index) { value == b'-' } else { value.is_ascii_hexdigit() }
        })
    }
}

fn failure(status: StatusCode, error: &str) -> Response { (status, axum::Json(json!({"error": error}))).into_response() }

pub(crate) fn routes(components: Arc<RwLock<Arc<RuntimeComponents>>>) -> Router {
    let read_components = components.clone();
    Router::new().route("/aerove/network", get(move |request: Request| {
        let components = read_components.clone();
        async move {
            if request.uri().query().is_some() { return failure(StatusCode::BAD_REQUEST, "no_parameters"); }
            let Ok(context) = super::context() else { return failure(StatusCode::SERVICE_UNAVAILABLE, "session_stopped"); };
            let Some(owner) = &context.network else { return failure(StatusCode::SERVICE_UNAVAILABLE, "network_unavailable"); };
            if !owner.running.load(Ordering::SeqCst) { return failure(StatusCode::CONFLICT, "network_starting"); }
            let _serial = owner.serial.lock().await;
            let Ok(stamp) = super::telemetry::route_stamp() else { return failure(StatusCode::CONFLICT, "network_changed"); };
            if context.cancel.is_cancelled() { return failure(StatusCode::SERVICE_UNAVAILABLE, "session_stopped"); }
            let active = components.read().unwrap().clone();
            match owner.readback(&active, stamp).await {
                Ok(snapshot) => ([(http::header::CACHE_CONTROL, "no-store")], axum::Json(snapshot)).into_response(),
                Err((status, error)) => failure(status, error),
            }
        }
    }).post(move |request: Request| {
        let components = components.clone();
        async move {
            if request.uri().query().is_some() { return failure(StatusCode::BAD_REQUEST, "no_parameters"); }
            let Ok(context) = super::context() else { return failure(StatusCode::SERVICE_UNAVAILABLE, "session_stopped"); };
            let Some(owner) = context.network.clone() else { return failure(StatusCode::SERVICE_UNAVAILABLE, "network_unavailable"); };
            if !owner.running.load(Ordering::SeqCst) { return failure(StatusCode::CONFLICT, "network_starting"); }
            let body = tokio::select! {
                biased;
                _ = context.cancel.cancelled() => return failure(StatusCode::SERVICE_UNAVAILABLE, "session_stopped"),
                result = tokio::time::timeout(Duration::from_secs(5), to_bytes(request.into_body(), 8192)) => result,
            };
            let Ok(Ok(body)) = body else { return failure(StatusCode::BAD_REQUEST, "invalid_network_request"); };
            let Ok(update) = serde_json::from_slice::<Update>(&body) else { return failure(StatusCode::BAD_REQUEST, "invalid_network_request"); };
            if !update.valid_id() || update.epoch().is_none() || update.network.validate(owner.controller, owner.ipv6).is_err() {
                return failure(StatusCode::BAD_REQUEST, "invalid_network_plan");
            }
            if super::telemetry::route_stamp().ok().map(|stamp| stamp.0) != update.epoch() {
                return failure(StatusCode::CONFLICT, "network_epoch_changed");
            }
            let (reply, result) = oneshot::channel();
            let task_owner = owner.clone();
            {
                let mut tasks = owner.tasks.lock().await;
                if context.cancel.is_cancelled() { return failure(StatusCode::SERVICE_UNAVAILABLE, "session_stopped"); }
                while tasks.try_join_next().is_some() {}
                tasks.spawn(async move {
                    let result = match std::panic::AssertUnwindSafe(task_owner.apply(components, update)).catch_unwind().await {
                        Ok(result) => result,
                        Err(_) => { super::task_failed("network update owner panicked"); Err((StatusCode::INTERNAL_SERVER_ERROR, "network_update_failed")) },
                    };
                    let _ = reply.send(result);
                });
            }
            match result.await {
                Ok(Ok(snapshot)) => ([(http::header::CACHE_CONTROL, "no-store")], axum::Json(snapshot)).into_response(),
                Ok(Err((status, error))) => failure(status, error),
                Err(_) => { super::task_failed("network update owner failed"); failure(StatusCode::INTERNAL_SERVER_ERROR, "network_update_failed") },
            }
        }
    }))
}

#[cfg(feature = "aerove-mobile-tests")]
#[path = "inbound_tests.rs"]
pub mod regression;
