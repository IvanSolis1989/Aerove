use std::{io::{self, Write as _}, net::{IpAddr, SocketAddr}, sync::{Arc, OnceLock}, time::Duration};

use bytes::Bytes;
use http::{HeaderMap, Method, StatusCode, Uri};
use http_body_util::{BodyExt, Full};
use hyper_util::rt::TokioIo;
use tokio::{io::{AsyncRead, AsyncWrite}, sync::Semaphore, time::Instant};

use crate::{app::{dispatcher::Dispatcher, dns::ThreadSafeDNSResolver}, common::tls::GLOBAL_ROOT_STORE,
    proxy::AnyOutboundHandler, session::{Network, Session, SocksAddr, Type}};

static SLOTS: OnceLock<Semaphore> = OnceLock::new();
pub(super) const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

#[derive(Clone)]
pub(super) struct PinnedRoute {
    pub handler: AnyOutboundHandler,
    pub resolver: ThreadSafeDNSResolver,
}

#[derive(Clone)]
pub(super) struct ProbeRequest {
    pub method: Method,
    pub url: String,
    pub headers: HeaderMap,
    pub body: Vec<u8>,
    pub timeout: Duration,
    pub max_body: usize,
    pub no_redirect: bool,
    pub strict: bool,
    pub until: Option<fn(&str) -> bool>,
}

impl ProbeRequest {
    pub fn get(url: &str, max_body: usize, timeout: Duration) -> Self {
        Self { method: Method::GET, url: url.into(), headers: HeaderMap::new(), body: Vec::new(),
            timeout, max_body, no_redirect: false, strict: false, until: None }
    }
}

pub(super) struct ProbeResult {
    pub url: String,
    pub status: StatusCode,
    pub headers: HeaderMap,
    pub body: Vec<u8>,
    pub delay: u64,
    pub chains: Vec<String>,
}

pub(super) async fn run_probe(dispatcher: Arc<Dispatcher>, pin: Option<PinnedRoute>, mut request: ProbeRequest) -> io::Result<ProbeResult> {
    let _slot = tokio::time::timeout(request.timeout, SLOTS.get_or_init(|| Semaphore::new(8)).acquire()).await
        .map_err(|_| io::Error::other("probe queue timed out"))?.map_err(io::Error::other)?;
    let timeout = request.timeout;
    tokio::time::timeout(timeout, async {
        let start = Instant::now();
        for redirects in 0..10 {
            let result = round_trip(dispatcher.clone(), pin.clone(), &request, start).await?;
            if request.no_redirect || !matches!(result.status.as_u16(), 301 | 302 | 303 | 307 | 308) {
                return Ok(result);
            }
            let Some(location) = result.headers.get(http::header::LOCATION) else { return Ok(result); };
            if redirects == 9 { return Err(io::Error::other("probe redirect limit exceeded")); }
            let previous = url::Url::parse(&request.url).map_err(io::Error::other)?;
            let next = previous.join(location.to_str().map_err(io::Error::other)?).map_err(io::Error::other)?;
            if !matches!(next.scheme(), "http" | "https") || !next.username().is_empty() || next.password().is_some() {
                return Err(io::Error::other("invalid probe redirect"));
            }
            let old_host = previous.host_str().unwrap_or("");
            let next_host = next.host_str().unwrap_or("");
            if next_host != old_host && !next_host.ends_with(&format!(".{old_host}")) {
                request.headers.remove(http::header::AUTHORIZATION);
                request.headers.remove(http::header::COOKIE);
            }
            if matches!(result.status.as_u16(), 301 | 302 | 303) && request.method != Method::HEAD {
                request.method = Method::GET;
                request.body.clear();
            }
            request.url = next.to_string();
        }
        unreachable!()
    }).await.map_err(|error| io::Error::new(io::ErrorKind::TimedOut, error))?
}

async fn round_trip(dispatcher: Arc<Dispatcher>, pin: Option<PinnedRoute>, request: &ProbeRequest, start: Instant) -> io::Result<ProbeResult> {
    let uri: Uri = request.url.parse().map_err(io::Error::other)?;
    let host = uri.host().ok_or_else(|| io::Error::other("missing probe host"))?.to_owned();
    let https = uri.scheme_str() == Some("https");
    if !https && uri.scheme_str() != Some("http") { return Err(io::Error::other("unsupported probe scheme")); }
    let port = uri.port_u16().unwrap_or(if https { 443 } else { 80 });
    let destination = match host.parse::<IpAddr>() {
        Ok(ip) => SocksAddr::Ip(SocketAddr::new(ip, port)),
        Err(_) => SocksAddr::Domain(host.clone(), port),
    };
    let session = Session { network: Network::Tcp, typ: Type::Ignore, destination, ..Default::default() };
    let (stream, mut ingress) = tokio::io::duplex(16 * 1024);
    let (route_tx, route_rx) = tokio::sync::oneshot::channel();
    let routed = async {
        if let Some(pin) = pin {
            let mut stream = pin.handler.connect_stream(&session, pin.resolver).await?;
            let _ = route_tx.send(stream.chain().snapshot().await);
            tokio::io::copy_bidirectional(&mut ingress, &mut stream).await?;
        } else {
            dispatcher.dispatch_probe_stream(session, Box::new(ingress), route_tx).await;
        }
        Ok::<_, io::Error>(())
    };
    let query = async {
        if https {
            let tls = rustls::ClientConfig::builder().with_root_certificates(GLOBAL_ROOT_STORE.clone()).with_no_client_auth();
            let tls=super::http_policy::current().tls(Arc::new(tls))?;
            let name = rustls::pki_types::ServerName::try_from(host).map_err(io::Error::other)?;
            let stream = tokio_rustls::TlsConnector::from(tls).connect(name, stream).await?;
            exchange(stream, uri, request, start).await
        } else { exchange(stream, uri, request, start).await }
    };
    tokio::pin!(query, routed);
    let mut result = tokio::select! {
        result = &mut query => result,
        result = &mut routed => { result?; query.await }
    }?;
    result.chains = route_rx.await.map_err(|_| io::Error::other("dispatcher did not report the probe route"))?;
    if result.chains.is_empty() { return Err(io::Error::other("dispatcher reported an empty probe route")); }
    Ok(result)
}

async fn exchange<S: AsyncRead + AsyncWrite + Unpin + Send + 'static>(stream: S, uri: Uri, probe: &ProbeRequest, start: Instant) -> io::Result<ProbeResult> {
    let mut request = http::Request::builder().method(probe.method.clone())
        .uri(uri.path_and_query().map(|path| path.as_str()).unwrap_or("/"))
        .header(http::header::HOST, uri.authority().ok_or_else(|| io::Error::other("missing authority"))?.as_str())
        .header(http::header::USER_AGENT, USER_AGENT)
        .header(http::header::ACCEPT_ENCODING, if probe.strict { "identity" } else { "gzip" })
        .header(http::header::CONNECTION, "close")
        .body(Full::new(Bytes::copy_from_slice(&probe.body))).map_err(io::Error::other)?;
    request.headers_mut().extend(probe.headers.clone());
    let (mut sender, connection) = hyper::client::conn::http1::Builder::new()
        .max_buf_size(if probe.strict { 8192 } else { 65536 }).handshake(TokioIo::new(stream)).await.map_err(io::Error::other)?;
    let read = async {
        let response = sender.send_request(request).await.map_err(io::Error::other)?;
        let delay = start.elapsed().as_millis().min(u64::MAX as u128) as u64;
        let status = response.status();
        let headers = response.headers().clone();
        let gzip = headers.get(http::header::CONTENT_ENCODING).is_some_and(|value| value == "gzip");
        if probe.strict {
            if status != StatusCode::OK { return Err(io::Error::other("probe returned a non-200 status")); }
            if headers.get(http::header::CONTENT_ENCODING).is_some_and(|value| value != "identity") {
                return Err(io::Error::other("encoded probe response is not supported"));
            }
            if let Some(length) = headers.get(http::header::CONTENT_LENGTH) {
                let length: usize = length.to_str().map_err(io::Error::other)?.parse().map_err(io::Error::other)?;
                if length > probe.max_body { return Err(io::Error::other("probe body limit exceeded")); }
            }
        } else if headers.get(http::header::CONTENT_ENCODING).is_some_and(|value| value != "identity" && value != "gzip") {
            return Err(io::Error::other("unsupported probe content encoding"));
        }
        let limit = if !probe.no_redirect && status.is_redirection() && headers.contains_key(http::header::LOCATION) { 0 } else { probe.max_body };
        let mut body = response.into_body();
        let mut bytes = Vec::new();
        let mut decoder = gzip.then(|| flate2::write::GzDecoder::new(LimitedBody { bytes: Vec::new(), limit, until: probe.until, stopped: false }));
        while limit > 0 {
            let Some(frame) = body.frame().await else { break; };
            let frame = match frame { Ok(frame) => frame, Err(error) if probe.strict => return Err(io::Error::other(error)), Err(_) => break };
            if let Ok(chunk) = frame.into_data() {
                let previous;
                let output = if let Some(decoder) = decoder.as_mut() {
                    previous = decoder.get_ref().bytes.len();
                    if decoder.write_all(&chunk).is_err() || decoder.get_ref().stopped { break; }
                    &decoder.get_ref().bytes
                } else {
                    previous = bytes.len();
                    if probe.strict && chunk.len() > limit - previous { return Err(io::Error::other("probe body limit exceeded")); }
                    bytes.extend_from_slice(&chunk[..chunk.len().min(limit - previous)]);
                    &bytes
                };
                if let Some(until) = probe.until
                    && until(&String::from_utf8_lossy(&output[previous.saturating_sub(256)..])) { break; }
                if !probe.strict && output.len() == limit { break; }
            }
        }
        if let Some(mut decoder) = decoder {
            let _ = decoder.try_finish();
            bytes = std::mem::take(&mut decoder.get_mut().bytes);
        }
        Ok(ProbeResult { url: probe.url.clone(), status, headers, body: bytes, delay, chains: Vec::new() })
    };
    tokio::pin!(read, connection);
    tokio::select! {
        result = &mut read => result,
        result = &mut connection => { result.map_err(io::Error::other)?; read.await }
    }
}

struct LimitedBody { bytes: Vec<u8>, limit: usize, until: Option<fn(&str) -> bool>, stopped: bool }

impl io::Write for LimitedBody {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if self.stopped { return Err(io::Error::other("probe scan complete")); }
        let previous = self.bytes.len();
        let count = bytes.len().min(self.limit - previous);
        if count == 0 && !bytes.is_empty() { return Err(io::Error::other("probe decoded body limit reached")); }
        self.bytes.extend_from_slice(&bytes[..count]);
        self.stopped = self.until.is_some_and(|until| until(&String::from_utf8_lossy(&self.bytes[previous.saturating_sub(256)..])));
        Ok(count)
    }
    fn flush(&mut self) -> io::Result<()> { Ok(()) }
}
