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
    proxy::utils as socket_helpers,
    runner::Runner,
};

pub type Protector = Arc<dyn Fn(RawFd) -> io::Result<()> + Send + Sync>;

enum Event {
    TunReady,
    ApiReady(SocketAddr),
    Failed(String),
}

struct Context {
    protector: Protector,
    bootstrap: Vec<SocketAddr>,
    events: mpsc::UnboundedSender<Event>,
    logs: broadcast::Sender<LogEvent>,
    level: tracing::level_filters::LevelFilter,
    cancel: CancellationToken,
}

static CONTEXT: RwLock<Option<Arc<Context>>> = RwLock::new(None);
static LOGGING: OnceLock<std::result::Result<(), String>> = OnceLock::new();

fn context() -> io::Result<Arc<Context>> {
    CONTEXT.read().unwrap().clone().ok_or_else(|| {
        io::Error::new(io::ErrorKind::PermissionDenied, "no active VPN protector")
    })
}

struct ContextLease;

impl Drop for ContextLease {
    fn drop(&mut self) {
        *CONTEXT.write().unwrap() = None;
    }
}

pub(crate) fn protect(fd: RawFd) -> io::Result<()> {
    let context = context()?;
    if context.cancel.is_cancelled() {
        return Err(io::Error::new(io::ErrorKind::Interrupted, "core is stopping"));
    }
    (context.protector)(fd).inspect_err(|error| task_failed(format!("protect: {error}")))
}

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
        if let Ok(context) = context()
            && *event.metadata().level() <= context.level
        {
            EventCollector::new(vec![context.logs.clone()]).on_event(event, scope);
        }
    }
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
    if path == "/restart" || (path.starts_with("/configs")
        && request.method() != http::Method::GET)
    {
        return (http::StatusCode::CONFLICT,
            "VPN configuration changes require service-owned stop and recreate").into_response();
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
            if ["PROCESS-NAME,", "PROCESS-PATH,", "UID,", "IN-USER,"].iter()
                .any(|token| upper.contains(token))
            {
                return Err(invalid(format!("{path}: Android process/UID attribution is unavailable")));
            }
        }
        Value::String(value) if path.starts_with("$.dns.") => {
            if value == "system" || value.starts_with("dhcp://") {
                return Err(invalid(format!("{path}: {value} has no protected Android resolver")));
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
    if let Some(proxies) = value.get("proxies").and_then(Value::as_sequence) {
        for (index, proxy) in proxies.iter().enumerate() {
            let mut unknown = Vec::new();
            serde_ignored::deserialize::<_, _, OutboundProxyProtocol>(proxy.clone(),
                |path| unknown.push(path.to_string()))
                .map_err(|error| invalid(format!("$.proxies[{index}]: {error}")))?;
            if !unknown.is_empty() {
                return Err(invalid(format!("$.proxies[{index}]: unknown fields {}", unknown.join(", "))));
            }
        }
    }
    Config::Str(source.to_owned()).try_parse_strict()
}

fn bootstrap(config: &InternalConfig) -> Result<Vec<SocketAddr>> {
    config.dns.default_nameserver.iter().map(|server| {
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
    let mut config = parse(&source)?;
    if !config.tun.enable || !config.tun.dns_hijack || !config.dns.enable {
        return Err(invalid("runtime requires TUN, dns-hijack and DNS enabled"));
    }
    if !config.listeners.is_empty() || !config.inbound_providers.is_empty() {
        return Err(invalid("runtime cannot create additional inbound listeners"));
    }
    let controller = config.general.controller.clone();
    let address: SocketAddr = controller.external_controller.as_deref()
        .ok_or_else(|| invalid("runtime requires external-controller"))?
        .parse().map_err(|_| invalid("external-controller must be a loopback IP:port"))?;
    if !address.ip().is_loopback() || controller.external_controller_ipc.is_some()
        || controller.secret.as_deref().unwrap_or("").is_empty()
    {
        return Err(invalid("runtime controller requires loopback TCP and a secret"));
    }
    let bootstrap = bootstrap(&config)?;
    if bootstrap.is_empty() {
        return Err(invalid("dns.default-nameserver must not be empty"));
    }
    let (events, mut receiver) = mpsc::unbounded_channel();
    let (logs, _) = broadcast::channel(512);
    {
        let mut active = CONTEXT.write().unwrap();
        if active.is_some() {
            return Err(Error::Operation("another mobile runtime is active".into()));
        }
        *active = Some(Arc::new(Context {
            protector, bootstrap, events, logs: logs.clone(),
            level: config.general.log_level.into(), cancel: cancel.clone(),
        }));
    }
    let _lease = ContextLease;
    setup_logging()?;
    crate::setup_default_crypto_provider();
    config.tun.device_id = format!("fd://{}", tun.as_raw_fd());
    let tun = unsafe { tun_rs::AsyncDevice::from_fd(tun.into_raw_fd())? };
    let components = tokio::select! {
        biased;
        _ = cancel.cancelled() => return Ok(()),
        result = crate::create_components(working_directory.clone(), config) => Arc::new(result?),
    };
    components.tun_runner.set_device(tun);
    let (reload_tx, _reload_rx) = mpsc::channel(1);
    let global_state = Arc::new(Mutex::new(GlobalState {
        log_level: crate::config::def::LogLevel::Info,
        tunnel_runner: components.tun_runner.clone(),
        dns_listener: components.dns_listener.clone(),
        reload_tx,
        cwd: working_directory.to_string_lossy().into_owned(),
        config_path: None,
    }));
    let api = ApiRunner::new(controller, logs,
        Arc::new(RwLock::new(components.clone())), global_state,
        working_directory.to_string_lossy().into_owned(), Some(cancel.child_token()));
    api.run_async();
    components.start_all();
    let mut tun_ready = false;
    let mut api_address = None;
    let mut ready = Some(ready);
    let result = loop {
        tokio::select! {
            biased;
            _ = cancel.cancelled() => break Ok(()),
            event = receiver.recv() => match event {
                Some(Event::TunReady) => tun_ready = true,
                Some(Event::ApiReady(address)) => api_address = Some(address),
                Some(Event::Failed(error)) => break Err(Error::Operation(error)),
                None => break Err(Error::Operation("core readiness channel closed".into())),
            }
        }
        if tun_ready && let Some(address) = api_address && let Some(ready) = ready.take() {
            tracing::info!("AEROVE mobile TUN and controller ready at {address}");
            ready(address);
        }
    };
    cancel.cancel();
    api.shutdown();
    components.stop_all().await;
    api.join().await?;
    result
}

pub(crate) async fn lookup_host(host_port: String) -> io::Result<std::vec::IntoIter<SocketAddr>> {
    let (host, port) = host_port.rsplit_once(':')
        .ok_or_else(|| io::Error::other("invalid bootstrap hostname"))?;
    let port: u16 = port.parse().map_err(io::Error::other)?;
    let context = context()?;
    let mut addresses = Vec::new();
    let mut last_error = None;
    for record_type in [RecordType::A, RecordType::AAAA] {
        for server in &context.bootstrap {
            match tokio::time::timeout(Duration::from_secs(5), query(host, record_type, *server)).await {
                Ok(Ok(ips)) => {
                    addresses.extend(ips.into_iter().map(|ip| SocketAddr::new(ip, port)));
                    break;
                }
                Ok(Err(error)) => last_error = Some(error),
                Err(error) => last_error = Some(io::Error::new(io::ErrorKind::TimedOut, error)),
            }
        }
    }
    if addresses.is_empty() && let Some(error) = last_error {
        return Err(error);
    }
    Ok(addresses.into_iter())
}

async fn query(host: &str, record_type: RecordType, server: SocketAddr) -> io::Result<Vec<IpAddr>> {
    let mut message = Message::query();
    message.metadata.id = rand::random();
    message.metadata.recursion_desired = true;
    let mut question = Query::new();
    question.set_name(Name::from_str_relaxed(host).map_err(io::Error::other)?);
    question.set_query_type(record_type);
    message.add_query(question);
    let bytes = message.to_vec().map_err(io::Error::other)?;
    let socket = socket_helpers::new_udp_socket(None, None,
        #[cfg(target_os = "linux")] None, Some(server)).await?;
    socket.connect(server).await?;
    socket.send(&bytes).await?;
    let mut buffer = vec![0; 65535];
    let count = socket.recv(&mut buffer).await?;
    let mut response = Message::from_vec(&buffer[..count]).map_err(io::Error::other)?;
    check_response(&message, &response)?;
    if response.metadata.truncation {
        let mut stream = socket_helpers::new_tcp_stream(server, None,
            #[cfg(target_os = "linux")] None).await?;
        stream.write_u16(bytes.len() as u16).await?;
        stream.write_all(&bytes).await?;
        let count = stream.read_u16().await? as usize;
        buffer.resize(count, 0);
        stream.read_exact(&mut buffer).await?;
        response = Message::from_vec(&buffer).map_err(io::Error::other)?;
        check_response(&message, &response)?;
        if response.metadata.truncation {
            return Err(io::Error::other("truncated TCP bootstrap response"));
        }
    }
    Ok(response.answers.iter().filter_map(|record| match &record.data {
        RData::A(ip) if record_type == RecordType::A => Some(IpAddr::V4(**ip)),
        RData::AAAA(ip) if record_type == RecordType::AAAA => Some(IpAddr::V6(**ip)),
        _ => None,
    }).collect())
}

fn check_response(query: &Message, response: &Message) -> io::Result<()> {
    if response.metadata.id != query.metadata.id
        || response.metadata.message_type != MessageType::Response
        || response.queries != query.queries
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
        *CONTEXT.write().unwrap() = Some(Arc::new(Context {
            protector, bootstrap: vec![], events, logs,
            level: tracing::level_filters::LevelFilter::TRACE,
            cancel: CancellationToken::new(),
        }));
        (ContextLease, receiver)
    }

    #[test]
    fn rejects_unknown_and_android_unavailable_capabilities() {
        for source in ["unknown-aerove-key: true", "sniffer: {enable: true}",
            "rules: [\"PROCESS-NAME,app,DIRECT\"]", "dns: {nameserver: [system]}",
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
        *second_context = Some(Arc::new(Context {
            protector: old.protector.clone(), bootstrap: vec![], events: old.events.clone(),
            logs, level: old.level, cancel: CancellationToken::new(),
        }));
        drop(second_context);
        tracing::info!("aerove second session");
        assert!(second.try_recv().unwrap().msg.contains("aerove second session"));
        assert!(first.try_recv().is_err());
    }
}
