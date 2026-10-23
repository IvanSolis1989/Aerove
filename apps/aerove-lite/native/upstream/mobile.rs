//! AEROVE embedding changes for clash-rs v0.10.10; upstream retains its copyright.
use std::{
    io,
    net::{IpAddr, SocketAddr},
    os::fd::{AsRawFd, IntoRawFd, OwnedFd, RawFd},
    path::PathBuf,
    sync::{Arc, OnceLock, RwLock},
    time::Duration,
};

use hickory_proto::{
    op::{Message, MessageType, Query, ResponseCode},
    rr::{Name, RData, RecordType},
};
use serde_yaml::Value;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    sync::{Mutex, broadcast, mpsc},
};
use tokio_util::sync::CancellationToken;
use tracing_subscriber::{Layer, prelude::*};

use crate::{
    Config, Error, GlobalState, InternalConfig, Result,
    app::{
        api::ApiRunner,
        dns::config::DNSNetMode,
        logging::{EventCollector, LogEvent},
    },
    config::internal::proxy::OutboundProxyProtocol,
    runner::Runner,
};

#[cfg(test)]
use crate::proxy::utils as socket_helpers;

pub type Protector = Arc<dyn Fn(RawFd) -> io::Result<()> + Send + Sync>;

#[derive(Clone, Copy, Debug, Eq, PartialEq, serde::Serialize)]
#[serde(rename_all = "lowercase")]
pub enum RuntimeMode { Tun, Proxy }

impl RuntimeMode {
    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "tun" => Ok(Self::Tun),
            "proxy" => Ok(Self::Proxy),
            _ => Err(invalid("runtimeMode: expected tun or proxy")),
        }
    }
}

pub enum RuntimeInput { Tun { fd: OwnedFd, protector: Protector }, Proxy, ParkedTun { protector:Protector }, ParkedProxy }

impl RuntimeInput {
    pub fn initially_parked(&self)->bool{matches!(self,Self::ParkedTun {..}|Self::ParkedProxy)}
    pub fn mode(&self) -> RuntimeMode {
        match self { Self::Tun { .. }|Self::ParkedTun { .. } => RuntimeMode::Tun, Self::Proxy|Self::ParkedProxy => RuntimeMode::Proxy }
    }
}

enum SocketPolicy { Vpn(Protector), PlatformDefault }

impl SocketPolicy {
    fn hook(self) -> Protector {
        Arc::new(move |fd| match &self {
            Self::Vpn(protector) => protector(fd),
            Self::PlatformDefault => Ok(()),
        })
    }
}

pub struct ReadyInfo {
    pub address: SocketAddr,
    pub runtime_mode: RuntimeMode,
    pub tun_ready: bool,
    pub user_listeners_ready: bool,
}

pub mod resources;
pub mod outbound;
pub mod telemetry;
pub(crate) mod provider_loaded;
pub mod runtime_control;
pub mod user_controller;
#[cfg(feature="aerove-mobile-tests")]
pub mod probe_dial_tests;
#[cfg(feature="aerove-mobile-tests")]
pub mod mtu_tests;
#[cfg(feature="aerove-mobile-tests")]
pub mod initial_park_tests;
pub mod http_policy;
pub mod dns_network;
pub mod protocol_transport;
pub mod process_identity;
#[cfg(feature="aerove-mobile-tests")]
pub mod process_identity_tests;
#[cfg(feature="aerove-mobile-tests")]
pub mod dns_network_tests;
#[cfg(feature="aerove-mobile-tests")]
pub mod http_policy_tests;
#[cfg(feature = "aerove-mobile-tests")]
pub mod runtime_control_tests;
pub mod observations;
#[cfg(feature = "aerove-mobile-tests")]
pub mod observation_tests;
pub mod inbound;

#[cfg(feature = "aerove-mobile-tests")]
#[path = "runtime_mode_tests.rs"]
pub mod runtime_mode_tests;

enum Event {
    TunReady,
    DnsReady,
    ApiReady(SocketAddr),
    InboundReady(inbound::Bound),
    Failed(String),
}

struct Context {
    protector: Protector,
    mode:RuntimeMode,
    process_owner:Option<Arc<process_identity::Owner>>,
    network_generation:std::sync::atomic::AtomicU64,
    bootstrap: Vec<SocketAddr>,
    network_dns: RwLock<Option<dns_network::NetworkDns>>,
    dns_enabled:bool, dns_hijack_all:bool,
    events: mpsc::UnboundedSender<Event>,
    logs: broadcast::Sender<LogEvent>,
    level: tracing::level_filters::LevelFilter,
    cancel: CancellationToken,
    telemetry: Arc<telemetry::Bus>,
    network: Option<Arc<inbound::Owner>>,
}

static CONTEXT: RwLock<Option<Arc<Context>>> = RwLock::new(None);
static LOGGING: OnceLock<std::result::Result<(), String>> = OnceLock::new();

fn context() -> io::Result<Arc<Context>> {
    CONTEXT.read().unwrap().clone().ok_or_else(|| {
        io::Error::new(io::ErrorKind::PermissionDenied, "no active mobile runtime")
    })
}

#[cfg(feature="tailscale")]
pub(crate) fn tailscale_underlay()->Option<::tailscale::Underlay> {
    let context=context().ok()?;let mode=context.mode;let cancel=context.cancel.clone();let protector=context.protector.clone();
    let resolver: ::tailscale::Resolver=Arc::new(move|host,port| {let context=context.clone();Box::pin(async move {lookup_host_on(context,format!("{host}:{port}")).await.map(Iterator::collect)})});
    Some(match mode {RuntimeMode::Tun=>::tailscale::Underlay::protected(protector,resolver,cancel),RuntimeMode::Proxy=>::tailscale::Underlay::platform(resolver,cancel)})
}

pub(crate) fn process_owner()->Option<Arc<process_identity::Owner>> {context().ok().and_then(|context|context.process_owner.clone())}

#[cfg(feature = "aerove-mobile-tests")]
fn lifecycle_phase(stage: &str) { eprintln!("[dns-phase] owner {stage}"); }

struct ContextLease;

impl Drop for ContextLease {
    fn drop(&mut self) {
        let retired = {
            #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("context lease write-lock begin");
            let mut active = CONTEXT.write().unwrap();
            #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("context lease write-lock acquired");
            active.take()
        };
        #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("context lease write-lock released");
        drop(retired);
        #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("context lease drop complete");
    }
}

#[cfg(feature = "aerove-mobile-tests")]
pub fn context_lease_destructors_run_after_global_unlock() {
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    struct DropSentinel { unlocked: Arc<AtomicBool>, dropped: Arc<AtomicUsize> }
    impl Drop for DropSentinel {
        fn drop(&mut self) {
            self.unlocked.store(CONTEXT.try_read().is_ok(), Ordering::SeqCst);
            self.dropped.fetch_add(1, Ordering::SeqCst);
        }
    }
    outbound::regression::with_runtime_lock(|| {
        assert!(CONTEXT.read().unwrap().is_none());
        let unlocked = Arc::new(AtomicBool::new(false));
        let dropped = Arc::new(AtomicUsize::new(0));
        let sentinel = DropSentinel { unlocked: unlocked.clone(), dropped: dropped.clone() };
        let (events, _) = mpsc::unbounded_channel();
        let (logs, _) = broadcast::channel(8);
        let cancel = CancellationToken::new();
        *CONTEXT.write().unwrap() = Some(Arc::new(Context {
            protector: Arc::new(move |_| { let _keep = &sentinel; Ok(()) }),
            mode: RuntimeMode::Proxy, process_owner: None,
            network_generation: std::sync::atomic::AtomicU64::new(0),
            bootstrap: vec![], network_dns: RwLock::new(None),
            dns_enabled: false, dns_hijack_all: false, events, logs,
            level: tracing::level_filters::LevelFilter::OFF,
            cancel: cancel.clone(), telemetry: telemetry::Bus::new(cancel), network: None,
        }));
        drop(ContextLease);
        assert_eq!(dropped.load(Ordering::SeqCst), 1);
        assert!(unlocked.load(Ordering::SeqCst), "Context field destructor ran under the global write lock");
        assert!(CONTEXT.read().unwrap().is_none());
    });
}

pub(crate) fn protect(fd: RawFd) -> io::Result<()> {
    let context = context()?;
    if context.cancel.is_cancelled() {
        return Err(io::Error::new(io::ErrorKind::Interrupted, "core is stopping"));
    }
    (context.protector)(fd).inspect_err(|error| task_failed(format!("protect: {error}")))
}

pub(crate) fn dns_ready(){if let Ok(context)=context(){let _=context.events.send(Event::DnsReady);}}

pub(crate) fn tun_ready() {
    if let Ok(context) = context() {
        let _ = context.events.send(Event::TunReady);
    }
}

pub(crate) fn api_ready(address: SocketAddr) {
    if let Ok(context) = context() {
        let _ = context.events.send(Event::ApiReady(address));
    }
}

pub(crate) fn task_failed(error: impl Into<String>) {
    if let Ok(context) = context() {
        let _ = context.events.send(Event::Failed(error.into()));
    }
}

struct LogSource;

impl<S: tracing::Subscriber> Layer<S> for LogSource {
    fn on_event(
        &self,
        event: &tracing::Event<'_>,
        scope: tracing_subscriber::layer::Context<'_, S>,
    ) {
        // Tokio emits these synchronously while holding the broadcast tail lock.
        if event.metadata().target() == "tokio::task::waker" { return; }
        if let Ok(context) = context()
            && *event.metadata().level() <= context.telemetry.log_level().unwrap_or(context.level)
            && context.logs.receiver_count() > 0
        {
            EventCollector::new(vec![context.logs.clone()]).on_event(event, scope);
        }
    }
}


#[cfg(feature = "aerove-mobile-tests")]
pub fn log_source_keeps_application_events_without_waker_lock_reentry(trace: bool) {
    use std::sync::atomic::{AtomicUsize, Ordering};
    struct WakerClones(Arc<AtomicUsize>);
    impl<S: tracing::Subscriber> Layer<S> for WakerClones {
        fn on_event(&self, event: &tracing::Event<'_>, _: tracing_subscriber::layer::Context<'_, S>) {
            struct Operation<'a>(&'a AtomicUsize);
            impl tracing::field::Visit for Operation<'_> {
                fn record_debug(&mut self, _: &tracing::field::Field, _: &dyn std::fmt::Debug) {}
                fn record_str(&mut self, field: &tracing::field::Field, value: &str) {
                    if field.name() == "op" && value == "waker.clone" { self.0.fetch_add(1, Ordering::SeqCst); }
                }
            }
            if event.metadata().target() == "tokio::task::waker" { event.record(&mut Operation(&self.0)); }
        }
    }
    outbound::regression::with_runtime_lock(|| {
        assert!(CONTEXT.read().unwrap().is_none());
        let clones = Arc::new(AtomicUsize::new(0));
        let subscriber = tracing_subscriber::registry().with(WakerClones(clones.clone())).with(LogSource);
        let (events, _) = mpsc::unbounded_channel();
        let (logs, _) = broadcast::channel(8);
        let cancel = CancellationToken::new();
        let telemetry = telemetry::Bus::new(cancel.clone());
        if trace { telemetry.set_log_level(tracing::level_filters::LevelFilter::TRACE); }
        *CONTEXT.write().unwrap() = Some(Arc::new(Context {
            protector: Arc::new(|_| Ok(())), mode: RuntimeMode::Proxy, process_owner: None,
            network_generation: std::sync::atomic::AtomicU64::new(0), bootstrap: vec![],
            network_dns: RwLock::new(None), dns_enabled: false, dns_hijack_all: false,
            events, logs: logs.clone(), level: tracing::level_filters::LevelFilter::WARN,
            cancel, telemetry, network: None,
        }));
        let lease = ContextLease;
        tracing::subscriber::with_default(subscriber, || {
            tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
                let mut receiver = logs.subscribe();
                let entered = Arc::new(tokio::sync::Notify::new());
                let polling = entered.clone();
                let waiting = tokio::spawn(async move { polling.notify_one(); receiver.recv().await.unwrap() });
                entered.notified().await;
                assert!(clones.load(Ordering::SeqCst) > 0, "real Tokio broadcast waiter did not clone an instrumented task waker");
                tracing::warn!(target: "aerove::log_source_regression", "application-warning");
                let warning = tokio::time::timeout(Duration::from_secs(2), waiting).await.unwrap().unwrap();
                assert!(matches!(warning.level, crate::def::LogLevel::Warning));
                assert!(warning.msg.contains("application-warning"));
                let mut application = logs.subscribe();
                tracing::trace!(target: "tokio::task::waker", op = "waker.clone");
                assert!(matches!(application.try_recv(), Err(broadcast::error::TryRecvError::Empty)));
                tracing::trace!(target: "aerove::log_source_regression", "application-trace");
                if trace {
                    let event = application.try_recv().unwrap();
                    assert!(matches!(event.level, crate::def::LogLevel::Trace));
                    assert!(event.msg.contains("application-trace"));
                } else {
                    assert!(matches!(application.try_recv(), Err(broadcast::error::TryRecvError::Empty)));
                }
                tracing::warn!(target: "aerove::log_source_regression", "application-warning-after-trace");
                assert!(application.try_recv().unwrap().msg.contains("application-warning-after-trace"));
            });
        });
        drop(lease);
        assert!(CONTEXT.read().unwrap().is_none());
    });
}

fn setup_logging() -> Result<()> {
    LOGGING.get_or_init(|| {
        tracing_subscriber::registry()
            .with(LogSource)
            .try_init()
            .map_err(|error| error.to_string())
    }).clone().map_err(Error::Operation)
}

pub(crate) async fn guard_controller(
    request: axum::extract::Request,
    next: axum::middleware::Next,
) -> axum::response::Response {
    use axum::response::IntoResponse;
    let path = request.uri().path();
    if matches!(path,"/configs"|"/configs/") && request.method()==http::Method::PATCH && request.uri().query().is_none() {
        let (parts,body)=request.into_parts();
        let body=match tokio::time::timeout(Duration::from_secs(5),axum::body::to_bytes(body,4096)).await {Ok(Ok(body))=>body,_=>return (http::StatusCode::BAD_REQUEST,"invalid config patch").into_response()};
        let allowed=serde_json::from_slice::<serde_json::Value>(&body).ok().and_then(|v|v.as_object().map(|o|!o.is_empty()&&o.keys().all(|k|matches!(k.as_str(),"mode"|"log-level")))).unwrap_or(false);
        if !allowed {return (http::StatusCode::CONFLICT,"config patch requires an owned endpoint").into_response();}
        return next.run(axum::extract::Request::from_parts(parts,axum::body::Body::from(body))).await;
    }
    if path == "/restart" || (path.starts_with("/configs") && request.method() != http::Method::GET) {
        return (http::StatusCode::CONFLICT,"config mutation requires an owned endpoint").into_response();
    }
    next.run(request).await
}

fn invalid(message: impl Into<String>) -> Error {
    Error::InvalidConfig(message.into())
}

fn check_value(value: &Value, path: &str) -> Result<()> {
    match value {
        Value::Mapping(map) => {
            for (key, item) in map {
                let key = key.as_str().ok_or_else(|| invalid(format!("{path}: non-string key")))?;
                if matches!(key, "client-fingerprint" | "packet-encoding") {
                    return Err(invalid(format!("{path}.{key}: not implemented by this core")));
                }
                check_value(item, &format!("{path}.{key}"))?;
            }
        }
        Value::Sequence(items) => {
            for (index, item) in items.iter().enumerate() {
                check_value(item, &format!("{path}[{index}]"))?;
            }
        }
        Value::String(value) if path.starts_with("$.rules[")
            || path.contains(".payload[") => {
            let upper = value.to_ascii_uppercase();
            if ["PROCESS-NAME-REGEX,", "PROCESS-PATH-REGEX,", "PROCESS-NAME-WILDCARD,", "PROCESS-PATH-WILDCARD,", "UID,", "IN-USER,"].iter()
                .any(|token| upper.contains(token))
            {
                return Err(invalid(format!("{path}: Android UID/user or process pattern matcher is unavailable")));
            }
        }
        _ => {}
    }
    Ok(())
}

pub fn validate(source: &str) -> Result<()> {
    parse(source).map(|_| ())
}

fn parse(source: &str) -> Result<InternalConfig> {
    let mut value: Value = serde_yaml::from_str(source)
        .map_err(|error| invalid(error.to_string()))?;
    value.apply_merge().map_err(|error| invalid(error.to_string()))?;
    check_value(&value, "$")?;
    inbound::check_source(&value)?;
    if let Some(proxies) = value.get("proxies").and_then(Value::as_sequence) {
        for (index, proxy) in proxies.iter().enumerate() {
            resources::check_proxy_fields(proxy)
                .map_err(|error| invalid(format!("$.proxies[{index}]: {error:#}")))?;
            let mut unknown = Vec::new();
            serde_ignored::deserialize::<_, _, OutboundProxyProtocol>(proxy.clone(),
                |path| unknown.push(path.to_string()))
                .map_err(|error| invalid(format!("$.proxies[{index}]: {error}")))?;
            if !unknown.is_empty() {
                return Err(invalid(format!("$.proxies[{index}]: unknown fields {}", unknown.join(", "))));
            }
        }
    }
    let config=Config::Str(source.to_owned()).try_parse_strict()?;
    http_policy::Policy::new(config.general.global_ua.as_deref(),config.general.check_certificate)?;
    process_identity::FindProcessMode::parse(&config.general.find_process_mode).map_err(|_|invalid("$.find-process-mode: expected strict, always or off"))?;
    if let Some(snapshot)=&config.general.aerove_system_dns {snapshot.validate()?;}
    Ok(config)
}

fn bootstrap(config: &InternalConfig) -> Result<Vec<SocketAddr>> {
    config.dns.default_nameserver.iter().filter(|server|!matches!(server.net,DNSNetMode::System|DNSNetMode::Dhcp)).map(|server| {
        if server.net != DNSNetMode::Udp || server.interface.is_some() || server.proxy.is_some() {
            return Err(invalid("dns.default-nameserver: bootstrap requires plain IP UDP addresses"));
        }
        let ip = match &server.host {
            url::Host::Ipv4(ip) => IpAddr::V4(*ip),
            url::Host::Ipv6(ip) => IpAddr::V6(*ip),
            url::Host::Domain(_) => return Err(invalid("dns.default-nameserver: expected IP literal")),
        };
        Ok(SocketAddr::new(ip, server.port))
    }).collect()
}

pub async fn run(
    source: String,
    working_directory: PathBuf,
    tun: OwnedFd,
    protector: Protector,
    cancel: CancellationToken,
    ready: impl FnOnce(SocketAddr),
) -> Result<()> {
    run_runtime(source, working_directory, RuntimeInput::Tun { fd: tun, protector }, cancel,
        |info| ready(info.address)).await
}

pub async fn run_runtime(
    source: String,
    working_directory: PathBuf,
    input: RuntimeInput,
    cancel: CancellationToken,
    ready: impl FnOnce(ReadyInfo),
) -> Result<()> {
    let control=runtime_control::RuntimeControl::new();
    run_runtime_control(source,working_directory,input,cancel,control,ready).await
}

pub async fn run_runtime_control(
    source:String, working_directory:PathBuf, input:RuntimeInput, cancel:CancellationToken,
    control:Arc<runtime_control::RuntimeControl>, ready:impl FnOnce(ReadyInfo),
)->Result<()> {
    run_runtime_identity(source,working_directory,input,cancel,control,None,ready).await
}
pub async fn run_runtime_identity(
    source:String,working_directory:PathBuf,input:RuntimeInput,cancel:CancellationToken,
    control:Arc<runtime_control::RuntimeControl>,resolver:Option<process_identity::Resolver>,ready:impl FnOnce(ReadyInfo),
)->Result<()> {
    let mut commands=control.take();
    let mut config = parse(&source)?;
    let log_level = config.general.log_level;
    let http_policy=http_policy::Policy::new(config.general.global_ua.as_deref(),config.general.check_certificate)?;
    crate::app::remote_content_manager::set_unified_delay(config.general.unified_delay);
    crate::proxy::utils::set_tcp_concurrent(config.general.tcp_concurrent);
    let mode = input.mode();
    let initially_parked=input.initially_parked();
    if mode==RuntimeMode::Proxy&&resolver.is_some(){return Err(invalid("process owner resolver: proxy runtime requires null"));}
    let process_mode=process_identity::FindProcessMode::parse(&config.general.find_process_mode).map_err(|_|invalid("$.find-process-mode: expected strict, always or off"))?;
    match mode {
        RuntimeMode::Tun if !config.tun.enable => {
            return Err(invalid("$.tun.enable: tun runtime requires true"));
        },
        RuntimeMode::Proxy if config.tun.enable => return Err(invalid("$.tun.enable: proxy runtime requires false")),
        _ => {},
    }
    if mode==RuntimeMode::Tun&&config.tun.dns_hijack&&!config.dns.enable {return Err(invalid("$.tun.dns-hijack: requires dns.enable=true"));}
    let (tun, policy) = match input {
        RuntimeInput::Tun { fd, protector } => (Some(fd), SocketPolicy::Vpn(protector)),
        RuntimeInput::Proxy|RuntimeInput::ParkedProxy => (None, SocketPolicy::PlatformDefault),
        RuntimeInput::ParkedTun {protector} => (None,SocketPolicy::Vpn(protector)),
    };
    if initially_parked&&config.tun.device_id.starts_with("fd://"){return Err(invalid("$.tun.device-id: initially parked runtime owns no descriptor"));}
    let controller = config.general.controller.clone();
    let address = inbound::controller(&config)?;
    dns_network::validate_runtime(&config)?;
    let bootstrap = bootstrap(&config)?;
    if bootstrap.is_empty()&&config.general.aerove_system_dns.is_none() {
        return Err(invalid("dns.default-nameserver must not be empty"));
    }
    let (events, mut receiver) = mpsc::unbounded_channel();
    let (logs, _) = broadcast::channel(512);
    let telemetry = telemetry::Bus::new(cancel.clone());
    telemetry.set_http_policy(http_policy);
    let network = inbound::runtime(&source, &config, address, cancel.clone())?;
    network.set_parked(initially_parked);
    let mut inbound_ready = network.expected();
    {
        let mut active = CONTEXT.write().unwrap();
        if active.is_some() {
            return Err(Error::Operation("another mobile runtime is active".into()));
        }
        *active = Some(Arc::new(Context {
            protector: policy.hook(),mode,network_generation:std::sync::atomic::AtomicU64::new(0),process_owner:Some(process_identity::Owner::new(resolver,cancel.clone(),process_mode)), bootstrap, network_dns:RwLock::new(config.general.aerove_system_dns.clone()),dns_enabled:config.dns.enable,dns_hijack_all:config.tun.dns_hijack, events, logs: logs.clone(),
            level: log_level.into(), cancel: cancel.clone(),
            telemetry: telemetry.clone(),
            network: Some(network.clone()),
        }));
    }
    let _lease = ContextLease;
    setup_logging()?;
    crate::setup_default_crypto_provider();
    let tun = match tun {
        Some(tun) => {
            config.tun.device_id = format!("fd://{}", tun.as_raw_fd());
            Some(unsafe { tun_rs::AsyncDevice::from_fd(tun.into_raw_fd())? })
        },
        None => None,
    };
    let components = tokio::select! {
        biased;
        _ = cancel.cancelled() => return Ok(()),
        result = crate::create_components(working_directory.clone(), config) => Arc::new(result?),
    };
    components.statistics_manager.set_mobile_outbounds(Arc::downgrade(&components.outbound_manager)).await;
    if let Some(tun) = tun { components.tun_runner.set_device(tun); }
    let (reload_tx, _reload_rx) = mpsc::channel(1);
    let global_state = Arc::new(Mutex::new(GlobalState {
        log_level,
        tunnel_runner: components.tun_runner.clone(),
        dns_listener: components.dns_listener.clone(),
        reload_tx,
        cwd: working_directory.to_string_lossy().into_owned(),
        config_path: None,
    }));
    let user_controller=user_controller::Owner::new(cancel.clone());
    let shared_components=Arc::new(RwLock::new(components.clone()));
    let api = ApiRunner::new(controller, logs,
        shared_components.clone(), global_state.clone(),
        working_directory.to_string_lossy().into_owned(), Some(cancel.child_token()));
    api.run_async();
    if !initially_parked {components.start_all();}
    let mut tun_ready = false;
    let mut api_address = None;
    let mut ready = Some(ready);
    let result = loop {
        tokio::select! {
            biased;
            _ = cancel.cancelled() => break Ok(()),
            Some(command)=commands.recv()=> {
                let operation=commands.handle(command,mode,shared_components.clone(),network.clone(),user_controller.clone(),global_state.clone());
                tokio::pin!(operation);
                let mut failure=None;
                loop {
                    tokio::select! {
                        result=&mut operation=>{if failure.is_none() {failure=result.err();}break;},
                        event=receiver.recv()=>match event {
                            Some(Event::Failed(error))=>{failure=Some(Error::Operation(error));cancel.cancel();},
                            Some(Event::TunReady)=>tun_ready=true,
                            Some(Event::DnsReady)=>{},
                            Some(Event::ApiReady(address))=>api_address=Some(address),
                            Some(Event::InboundReady(_))=>{},
                            None=>{failure=Some(Error::Operation("core event channel closed".into()));cancel.cancel();},
                        }
                    }
                }
                if let Some(error)=failure {break Err(error);}
            },
            event = receiver.recv() => match event {
                Some(Event::TunReady) => tun_ready = true,
                Some(Event::DnsReady) => {},
                Some(Event::ApiReady(address)) => api_address = Some(address),
                Some(Event::InboundReady(bound)) => {
                    if ready.is_some() && let Err(error) = inbound::accept_ready(&mut inbound_ready, bound) { break Err(error); }
                },
                Some(Event::Failed(error)) => break Err(Error::Operation(error)),
                None => break Err(Error::Operation("core readiness channel closed".into())),
            }
        }
        if (initially_parked||((mode == RuntimeMode::Proxy || tun_ready) && inbound_ready.is_empty()))
            && let Some(address) = api_address && ready.is_some() {
            let status=match runtime_control::readback(mode,&components,&network).await {Ok(status)=>status,Err(error)=>break Err(error)};
            if !initially_parked&&!status.listening {continue;}
            if initially_parked&&(status.listening||status.tun_ready||status.user_listeners_ready||!status.active_ports.is_empty()){break Err(invalid("initially parked runtime unexpectedly has active listeners"));}
            network.mark_ready();
            let tun_ready=status.tun_ready;
            let user_listeners_ready=status.user_listeners_ready;
            control.running(status);
            tracing::info!("AEROVE mobile {mode:?} private controller ready at {address}");
            ready.take().unwrap()(ReadyInfo { address, runtime_mode: mode, tun_ready, user_listeners_ready });
        }
    };
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("cancel begin");
    cancel.cancel();
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("cancel complete");
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("api shutdown begin");
    api.shutdown();
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("api shutdown complete");
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("user controller stop begin");
    let user_controller_result=user_controller.stop().await;
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("user controller stop complete");
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("network join begin");
    let network_result = network.join().await;
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("network join complete");
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("components stop_all begin");
    components.stop_all().await;
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("components stop_all complete");
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("telemetry stop begin");
    let telemetry_result = telemetry.stop().await;
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("telemetry stop complete");
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("api join begin");
    api.join().await?;
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("api join complete");
    network_result?;
    user_controller_result?;
    telemetry_result.map_err(|error| Error::Operation(format!("delay notification task: {error}")))?;
    #[cfg(feature = "aerove-mobile-tests")] lifecycle_phase("return result");
    result
}

pub(crate) async fn lookup_host(host_port: String) -> io::Result<std::vec::IntoIter<SocketAddr>> {
    lookup_host_on(context()?,host_port).await
}
async fn lookup_host_on(context:Arc<Context>,host_port:String)->io::Result<std::vec::IntoIter<SocketAddr>> {
    let (host, port) = host_port.rsplit_once(':')
        .ok_or_else(|| io::Error::other("invalid bootstrap hostname"))?;
    let port: u16 = port.parse().map_err(io::Error::other)?;
    if context.cancel.is_cancelled(){return Err(io::Error::new(io::ErrorKind::Interrupted,"owning runtime stopped"));}
    if let Ok(ip)=host.trim_matches(['[',']']).parse::<IpAddr>() {return Ok(vec![SocketAddr::new(ip,port)].into_iter());}
    let scope=protocol_transport::Scope {protector:context.protector.clone(),cancel:context.cancel.clone()};
    let mut addresses = Vec::new();
    let mut last_error = None;
    let servers=match context.network_dns.read().unwrap().as_ref(){Some(snapshot)=>snapshot.servers(None)?,None=>context.bootstrap.clone()};
    for record_type in [RecordType::A, RecordType::AAAA] {
        for server in &servers {
            match query_on(&scope,host, record_type, *server).await {
                Ok(ips) => {
                    addresses.extend(ips.into_iter().map(|ip| SocketAddr::new(ip, port)));
                    break;
                }
                Err(error) => last_error = Some(error),
            }
        }
    }
    if addresses.is_empty() && let Some(error) = last_error {
        return Err(error);
    }
    Ok(addresses.into_iter())
}

#[cfg(test)]
async fn query(host: &str, record_type: RecordType, server: SocketAddr) -> io::Result<Vec<IpAddr>> {
    let scope=protocol_transport::Scope::current().ok_or_else(||io::Error::new(io::ErrorKind::PermissionDenied,"no owning runtime"))?;query_on(&scope,host,record_type,server).await
}
async fn query_on(scope:&protocol_transport::Scope,host:&str,record_type:RecordType,server:SocketAddr)->io::Result<Vec<IpAddr>> {
    let mut message = Message::query();
    message.metadata.id = rand::random();
    message.metadata.recursion_desired = true;
    let mut question = Query::new();
    question.set_name(Name::from_str_relaxed(host).map_err(io::Error::other)?);
    question.set_query_type(record_type);
    message.add_query(question);
    let response = observations::dns_message(&message, async {
        observations::dns_source(server.to_string(), false);
        tokio::time::timeout(Duration::from_secs(5), query_exchange_on(scope,&message, server)).await
            .map_err(|e| io::Error::new(io::ErrorKind::TimedOut, e))?
    }).await?;
    Ok(response.answers.iter().filter_map(|record| match &record.data {
        RData::A(ip) if record_type == RecordType::A => Some(IpAddr::V4(**ip)),
        RData::AAAA(ip) if record_type == RecordType::AAAA => Some(IpAddr::V6(**ip)),
        _ => None,
    }).collect())
}

async fn query_exchange(message: &Message, server: SocketAddr) -> io::Result<Message> {
    let scope=protocol_transport::Scope::current().ok_or_else(||io::Error::new(io::ErrorKind::PermissionDenied,"no owning runtime"))?;query_exchange_on(&scope,message,server).await
}
async fn query_exchange_on(scope:&protocol_transport::Scope,message:&Message,server:SocketAddr)->io::Result<Message> {
    let exchange=async {
        let bytes=message.to_vec().map_err(io::Error::other)?;
        let socket=scope.udp(server)?;let bind:SocketAddr=if server.is_ipv4(){"0.0.0.0:0".parse().unwrap()}else{"[::]:0".parse().unwrap()};socket.bind(&bind.into())?;
        let socket=tokio::net::UdpSocket::from_std(std::net::UdpSocket::from(socket))?;
        socket.connect(server).await?;scope.check()?;socket.send(&bytes).await?;
        let mut buffer=vec![0;65535];let count=socket.recv(&mut buffer).await?;
        let mut response=Message::from_vec(&buffer[..count]).map_err(io::Error::other)?;check_response(message,&response)?;
        if response.metadata.truncation {
            let mut stream=scope.tcp(server).await?;stream.write_u16(bytes.len() as u16).await?;stream.write_all(&bytes).await?;
            let count=stream.read_u16().await? as usize;buffer.resize(count,0);stream.read_exact(&mut buffer).await?;
            response=Message::from_vec(&buffer).map_err(io::Error::other)?;check_response(message,&response)?;
            if response.metadata.truncation {return Err(io::Error::other("truncated TCP bootstrap response"));}
        }
        Ok(response)
    };
    tokio::select!{biased;_=scope.cancel.cancelled()=>Err(io::Error::new(io::ErrorKind::Interrupted,"owning runtime stopped")),result=exchange=>result}
}

fn check_response(query: &Message, response: &Message) -> io::Result<()> {
    if response.metadata.id != query.metadata.id
        || response.metadata.message_type != MessageType::Response
        || response.queries.len() != query.queries.len()
        || response.queries.iter().zip(&query.queries).any(|(response,query)| {
            // FQDN-ness is an in-memory Name flag, absent from the DNS wire format.
            let mut response=response.clone();response.name.set_fqdn(true);
            let mut query=query.clone();query.name.set_fqdn(true);
            response!=query
        })
        || !matches!(response.metadata.response_code, ResponseCode::NoError | ResponseCode::NXDomain)
    {
        return Err(io::Error::other("invalid bootstrap DNS response"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Mutex as StdMutex, atomic::{AtomicUsize, Ordering}};

    static TEST_LOCK: StdMutex<()> = StdMutex::new(());

    fn test_context(protector: Protector) -> (ContextLease, mpsc::UnboundedReceiver<Event>) {
        let (events, receiver) = mpsc::unbounded_channel();
        let (logs, _) = broadcast::channel(32);
        let cancel = CancellationToken::new();
        *CONTEXT.write().unwrap() = Some(Arc::new(Context {
            protector,mode:RuntimeMode::Tun,process_owner:None,network_generation:std::sync::atomic::AtomicU64::new(0), bootstrap: vec![], network_dns:RwLock::new(None),dns_enabled:false,dns_hijack_all:false, events, logs,
            level: tracing::level_filters::LevelFilter::TRACE,
            cancel: cancel.clone(), telemetry: telemetry::Bus::new(cancel), network: None,
        }));
        (ContextLease, receiver)
    }

    #[test]
    fn rejects_unknown_and_android_unavailable_capabilities() {
        for source in ["unknown-aerove-key: true", "sniffer: {enable: true}",
            "rules: [\"UID,1000,DIRECT\"]", "dns: {nameserver: [system]}",
            "proxies: [{name: node, type: mieru, server: 127.0.0.1, port: 443}]",
            "proxies: [{name: node, type: direct, imaginary: true}]"]
        {
            assert!(validate(source).is_err(), "accepted {source}");
        }
        validate("rules: [MATCH,DIRECT]").unwrap_err();
        validate("rules: [\"MATCH,DIRECT\"]").unwrap();
    }

    #[test]
    fn rejects_mismatched_bootstrap_response() {
        let query = Message::query();
        let mut response = Message::response(query.metadata.id, query.metadata.op_code);
        check_response(&query, &response).unwrap();
        response.metadata.id = response.metadata.id.wrapping_add(1);
        assert!(check_response(&query, &response).is_err());
    }

    #[tokio::test]
    async fn protect_denial_prevents_tcp_connect_and_udp_send() {
        let _serial = TEST_LOCK.lock().unwrap();
        let calls = Arc::new(AtomicUsize::new(0));
        let callback_calls = calls.clone();
        let (_lease, mut events) = test_context(Arc::new(move |_| {
            callback_calls.fetch_add(1, Ordering::SeqCst);
            Err(io::Error::new(io::ErrorKind::PermissionDenied, "test refusal"))
        }));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        assert!(socket_helpers::new_tcp_stream(listener.local_addr().unwrap(), None,
            #[cfg(target_os = "linux")] None).await.is_err());
        assert!(socket_helpers::new_udp_socket(None, None,
            #[cfg(target_os = "linux")] None, Some(listener.local_addr().unwrap())).await.is_err());
        assert_eq!(calls.load(Ordering::SeqCst), 2);
        for _ in 0..2 {
            assert!(matches!(events.try_recv(), Ok(Event::Failed(_))));
        }
        assert!(tokio::time::timeout(Duration::from_millis(20), listener.accept()).await.is_err());
    }

    #[tokio::test]
    async fn bootstrap_udp_truncation_retries_on_protected_tcp() {
        let _serial = TEST_LOCK.lock().unwrap();
        let calls = Arc::new(AtomicUsize::new(0));
        let callback_calls = calls.clone();
        let (_lease, _events) = test_context(Arc::new(move |fd| {
            assert_ne!(unsafe { libc::fcntl(fd, libc::F_GETFD) }, -1);
            callback_calls.fetch_add(1, Ordering::SeqCst);
            Ok(())
        }));
        let udp = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let address = udp.local_addr().unwrap();
        let tcp = tokio::net::TcpListener::bind(address).await.unwrap();
        let server = tokio::spawn(async move {
            let mut buffer = vec![0; 4096];
            let (count, peer) = udp.recv_from(&mut buffer).await.unwrap();
            let request = Message::from_vec(&buffer[..count]).unwrap();
            let mut truncated = Message::response(request.metadata.id, request.metadata.op_code);
            truncated.queries = request.queries;
            truncated.metadata.truncation = true;
            udp.send_to(&truncated.to_vec().unwrap(), peer).await.unwrap();
            let (mut socket, _) = tcp.accept().await.unwrap();
            let count = socket.read_u16().await.unwrap() as usize;
            buffer.resize(count, 0);
            socket.read_exact(&mut buffer).await.unwrap();
            let request = Message::from_vec(&buffer).unwrap();
            let mut response = Message::response(request.metadata.id, request.metadata.op_code);
            response.queries = request.queries;
            response.add_answer(hickory_proto::rr::Record::from_rdata(
                response.queries[0].name().clone(), 60,
                RData::A(hickory_proto::rr::rdata::A("192.0.2.7".parse().unwrap()))));
            let bytes = response.to_vec().unwrap();
            socket.write_u16(bytes.len() as u16).await.unwrap();
            socket.write_all(&bytes).await.unwrap();
        });
        let result = tokio::time::timeout(Duration::from_secs(2), query("node.test.", RecordType::A, address))
            .await.unwrap().unwrap();
        assert_eq!(result, vec!["192.0.2.7".parse::<IpAddr>().unwrap()]);
        assert_eq!(calls.load(Ordering::SeqCst), 2);
        server.await.unwrap();
    }

    #[test]
    fn tracing_routes_logs_to_the_current_instance_after_restart() {
        let _serial = TEST_LOCK.lock().unwrap();
        setup_logging().unwrap();
        let (_lease, _events) = test_context(Arc::new(|_| Ok(())));
        let mut first = context().unwrap().logs.subscribe();
        tracing::info!("aerove first session");
        assert!(first.try_recv().unwrap().msg.contains("aerove first session"));
        let mut second_context = CONTEXT.write().unwrap();
        let (logs, mut second) = broadcast::channel(32);
        let old = second_context.take().unwrap();
        let cancel = CancellationToken::new();
        *second_context = Some(Arc::new(Context {
            protector: old.protector.clone(),mode:old.mode,process_owner:old.process_owner.clone(),network_generation:std::sync::atomic::AtomicU64::new(old.network_generation.load(std::sync::atomic::Ordering::Acquire)), bootstrap: vec![], events: old.events.clone(),
            network_dns:std::sync::RwLock::new(None),dns_enabled:false,dns_hijack_all:false,
            logs, level: old.level, cancel: cancel.clone(), telemetry: telemetry::Bus::new(cancel), network: None,
        }));
        drop(second_context);
        tracing::info!("aerove second session");
        assert!(second.try_recv().unwrap().msg.contains("aerove second session"));
        assert!(first.try_recv().is_err());
    }
}
