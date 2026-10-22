use std::{
    collections::HashMap,
    io,
    net::{IpAddr, SocketAddr},
    sync::{Arc, Mutex, RwLock},
    time::Duration,
};

use axum::{Router, extract::Request, middleware::Next, response::{IntoResponse, Response}, routing::get};
use bytes::Bytes;
use futures::{StreamExt, stream::FuturesUnordered};
use http::{Method, StatusCode, Uri};
use http_body_util::{BodyExt, Empty};
use hyper_util::rt::TokioIo;
use serde::Serialize;
use serde_json::{Value, json};
use tokio::{io::{AsyncRead, AsyncWrite}, sync::Semaphore};
use tokio_util::sync::CancellationToken;

use crate::{RuntimeComponents, app::dispatcher::Dispatcher, common::tls::GLOBAL_ROOT_STORE,
    session::{Network, Session, SocksAddr, Type}};

const MAX_BODY: usize = 4096;
const TIMEOUT: Duration = Duration::from_secs(6);
const STAGGER: Duration = Duration::from_millis(800);
const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

#[derive(Clone, Copy)]
enum Format { Cloudflare, IpSb, IpInfo, IpWho, IpApi, GeoJs, CountryIs, IpQuery, IdentMe }

#[derive(Clone)]
struct Source { url: String, format: Format }

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OutboundInfo {
    pub ip: String,
    pub country_code: String,
    pub organization: Option<String>,
    pub kind: &'static str,
    pub chains: Vec<String>,
}

pub(crate) struct Probe {
    components: Arc<RwLock<Arc<RuntimeComponents>>>,
    cancel: CancellationToken,
    slot: Semaphore,
    changes: Mutex<HashMap<String, usize>>,
    timeout: Duration,
    sources: Vec<Source>,
}

struct SelectionChange { probe: Arc<Probe>, name: String }

impl Drop for SelectionChange {
    fn drop(&mut self) {
        let mut changes = self.probe.changes.lock().unwrap();
        if let Some(count) = changes.get_mut(&self.name) {
            *count -= 1;
            if *count == 0 { changes.remove(&self.name); }
        }
    }
}

impl Probe {
    pub(crate) fn new(components: Arc<RwLock<Arc<RuntimeComponents>>>, cancel: CancellationToken) -> Arc<Self> {
        Arc::new(Self {
            components, cancel, slot: Semaphore::new(1),
            changes: Mutex::new(HashMap::new()), timeout: TIMEOUT,
            sources: vec![
                Source { url: "https://www.cloudflare.com/cdn-cgi/trace".into(), format: Format::Cloudflare },
                Source { url: "https://api.ip.sb/geoip".into(), format: Format::IpSb },
                Source { url: "https://ipinfo.io/json".into(), format: Format::IpInfo },
                Source { url: "https://ipwho.is".into(), format: Format::IpWho },
                Source { url: "http://ip-api.com/json".into(), format: Format::IpApi },
                Source { url: "https://get.geojs.io/v1/ip/geo.json".into(), format: Format::GeoJs },
                Source { url: "https://api.country.is".into(), format: Format::CountryIs },
                Source { url: "https://api.ipquery.io/?format=json".into(), format: Format::IpQuery },
                Source { url: "https://ident.me/json".into(), format: Format::IdentMe },
            ],
        })
    }

    pub(crate) fn routes(self: &Arc<Self>) -> Router {
        let probe = self.clone();
        Router::new().route("/aerove/outbound", get(move |request: Request| {
            let probe = probe.clone();
            async move { probe.handle(request).await }
        }))
    }

    pub(crate) async fn guard(self: Arc<Self>, request: Request, next: Next) -> Response {
        let path = request.extensions().get::<axum::extract::OriginalUri>()
            .map(|original| original.0.path()).unwrap_or_else(|| request.uri().path());
        let _change = if request.method() == Method::PUT {
            selection_name(path).map(|name| {
                *self.changes.lock().unwrap().entry(name.clone()).or_default() += 1;
                SelectionChange { probe: self.clone(), name }
            })
        } else { None };
        next.run(request).await
    }

    async fn handle(&self, request: Request) -> Response {
        if request.uri().query().is_some() { return failure(StatusCode::BAD_REQUEST, "no_parameters"); }
        if self.cancel.is_cancelled() { return failure(StatusCode::SERVICE_UNAVAILABLE, "session_stopped"); }
        let Ok(_permit) = self.slot.try_acquire() else { return failure(StatusCode::TOO_MANY_REQUESTS, "outbound_busy"); };
        let components = self.components.read().unwrap().clone();
        let answer = tokio::select! {
            biased;
            _ = self.cancel.cancelled() => return failure(StatusCode::SERVICE_UNAVAILABLE, "session_stopped"),
            result = tokio::time::timeout(self.timeout, async {
                let mode = components.dispatcher.get_mode().await;
                let info = self.race(components.dispatcher.clone()).await
                    .map_err(|_| (StatusCode::BAD_GATEWAY, "outbound_unavailable"))?;
                if !self.fresh(&components, mode, &info.chains).await {
                    return Err((StatusCode::CONFLICT, "outbound_changed"));
                }
                Ok(info)
            }) => result,
        };
        if self.cancel.is_cancelled() { return failure(StatusCode::SERVICE_UNAVAILABLE, "session_stopped"); }
        match answer {
            Ok(Ok(info)) => ([(http::header::CACHE_CONTROL, "no-store")], axum::Json(info)).into_response(),
            Ok(Err((status, code))) => failure(status, code),
            Err(_) => failure(StatusCode::GATEWAY_TIMEOUT, "outbound_timeout"),
        }
    }

    fn selecting(&self, chains: &[String]) -> bool {
        let changes = self.changes.lock().unwrap();
        chains.iter().skip(1).any(|name| changes.contains_key(name))
    }

    async fn fresh(&self, components: &Arc<RuntimeComponents>, mode: crate::config::def::RunMode, chains: &[String]) -> bool {
        if chains.is_empty() || self.selecting(chains) { return false; }
        for pair in chains.windows(2) {
            let Some(handler) = components.outbound_manager.get_outbound(&pair[1]).await else { return false; };
            if let Some(group) = handler.try_as_group_handler()
                && let Some(active) = group.get_active_proxy().await
                && active.name() != pair[0]
            {
                return false;
            }
        }
        if std::mem::discriminant(&components.dispatcher.get_mode().await) != std::mem::discriminant(&mode) { return false; }
        Arc::ptr_eq(components, &self.components.read().unwrap()) && !self.selecting(chains)
    }

    async fn race(&self, dispatcher: Arc<Dispatcher>) -> io::Result<OutboundInfo> {
        let mut sources = self.sources.iter();
        let mut pending = FuturesUnordered::new();
        if let Some(source) = sources.next() { pending.push(fetch(dispatcher.clone(), source.clone())); }
        let stagger = tokio::time::sleep(STAGGER);
        tokio::pin!(stagger);
        while !pending.is_empty() {
            tokio::select! {
                answer = pending.next() => {
                    if let Some(Ok(info)) = answer { return Ok(info); }
                    if let Some(source) = sources.next() { pending.push(fetch(dispatcher.clone(), source.clone())); }
                }
                _ = &mut stagger => {
                    if pending.len() < 3 && let Some(source) = sources.next() {
                        pending.push(fetch(dispatcher.clone(), source.clone()));
                    }
                    stagger.as_mut().reset(tokio::time::Instant::now() + STAGGER);
                }
            }
        }
        Err(io::Error::other("no usable outbound answer"))
    }
}

fn selection_name(path: &str) -> Option<String> {
    let raw = path.strip_prefix("/proxies/")?;
    let raw = raw.strip_suffix('/').unwrap_or(raw);
    if raw.is_empty() || raw.contains('/') { return None; }
    let mut bytes = Vec::with_capacity(raw.len());
    let mut input = raw.as_bytes().iter().copied();
    while let Some(byte) = input.next() {
        if byte == b'%' {
            let first = (input.next()? as char).to_digit(16)?;
            let second = (input.next()? as char).to_digit(16)?;
            bytes.push((first * 16 + second) as u8);
        } else { bytes.push(byte); }
    }
    String::from_utf8(bytes).ok()
}

fn failure(status: StatusCode, code: &str) -> Response {
    (status, [(http::header::CACHE_CONTROL, "no-store")], axum::Json(json!({"error": code}))).into_response()
}

async fn fetch(dispatcher: Arc<Dispatcher>, source: Source) -> io::Result<OutboundInfo> {
    let (body, chains) = routed_http(dispatcher, &source.url).await?;
    let mut info = parse(source.format, &body)?;
    info.chains = chains;
    Ok(info)
}

async fn routed_http(dispatcher: Arc<Dispatcher>, url: &str) -> io::Result<(Vec<u8>, Vec<String>)> {
    let uri: Uri = url.parse().map_err(io::Error::other)?;
    let host = uri.host().ok_or_else(|| io::Error::other("missing probe host"))?.to_owned();
    let https = uri.scheme_str() == Some("https");
    if !https && uri.scheme_str() != Some("http") { return Err(io::Error::other("unsupported probe scheme")); }
    let port = uri.port_u16().unwrap_or(if https { 443 } else { 80 });
    let destination = match host.parse::<IpAddr>() {
        Ok(ip) => SocksAddr::Ip(SocketAddr::new(ip, port)),
        Err(_) => SocksAddr::Domain(host.clone(), port),
    };
    let session = Session { network: Network::Tcp, typ: Type::Ignore, destination, ..Default::default() };
    let (stream, ingress) = tokio::io::duplex(16 * 1024);
    let (route_tx, route_rx) = tokio::sync::oneshot::channel();
    let routed = dispatcher.dispatch_probe_stream(session, Box::new(ingress), route_tx);
    let query = async {
        if https {
            let tls = rustls::ClientConfig::builder().with_root_certificates(GLOBAL_ROOT_STORE.clone()).with_no_client_auth();
            let name = rustls::pki_types::ServerName::try_from(host).map_err(io::Error::other)?;
            let stream = tokio_rustls::TlsConnector::from(Arc::new(tls)).connect(name, stream).await?;
            exchange(stream, uri).await
        } else { exchange(stream, uri).await }
    };
    tokio::pin!(query, routed);
    // Both futures own their streams; dropping a timed-out or superseded query closes the entire route.
    let body = tokio::select! { result = &mut query => result, _ = &mut routed => query.await }?;
    let chains = route_rx.await.map_err(|_| io::Error::other("dispatcher did not report the probe route"))?;
    if chains.is_empty() { return Err(io::Error::other("dispatcher reported an empty probe route")); }
    Ok((body, chains))
}

async fn exchange<S: AsyncRead + AsyncWrite + Unpin + Send + 'static>(stream: S, uri: Uri) -> io::Result<Vec<u8>> {
    let request = http::Request::builder()
        .uri(uri.path_and_query().map(|path| path.as_str()).unwrap_or("/"))
        .header(http::header::HOST, uri.authority().ok_or_else(|| io::Error::other("missing authority"))?.as_str())
        .header(http::header::USER_AGENT, USER_AGENT)
        .header(http::header::ACCEPT, "application/json")
        .header(http::header::ACCEPT_ENCODING, "identity")
        .header(http::header::CONNECTION, "close")
        .body(Empty::<Bytes>::new()).map_err(io::Error::other)?;
    let (mut sender, connection) = hyper::client::conn::http1::Builder::new()
        .max_buf_size(8192).handshake(TokioIo::new(stream)).await.map_err(io::Error::other)?;
    let read = async {
        let response = sender.send_request(request).await.map_err(io::Error::other)?;
        if response.status() != StatusCode::OK { return Err(io::Error::other("probe returned a non-200 status")); }
        if response.headers().get(http::header::CONTENT_ENCODING).is_some_and(|value| value != "identity") {
            return Err(io::Error::other("encoded probe response is not supported"));
        }
        if let Some(length) = response.headers().get(http::header::CONTENT_LENGTH) {
            let length: usize = length.to_str().map_err(io::Error::other)?.parse().map_err(io::Error::other)?;
            if length > MAX_BODY { return Err(io::Error::other("probe body limit exceeded")); }
        }
        let mut body = response.into_body();
        let mut bytes = Vec::new();
        while let Some(frame) = body.frame().await {
            let frame = frame.map_err(io::Error::other)?;
            if let Ok(chunk) = frame.into_data() {
                if chunk.len() > MAX_BODY - bytes.len() { return Err(io::Error::other("probe body limit exceeded")); }
                bytes.extend_from_slice(&chunk);
            }
        }
        Ok(bytes)
    };
    tokio::pin!(read, connection);
    tokio::select! {
        result = &mut read => result,
        result = &mut connection => { result.map_err(io::Error::other)?; read.await }
    }
}

fn parse(format: Format, body: &[u8]) -> io::Result<OutboundInfo> {
    let value: Value = if matches!(format, Format::Cloudflare) {
        let mut fields = serde_json::Map::new();
        for line in std::str::from_utf8(body).map_err(io::Error::other)?.lines() {
            if let Some((key, value)) = line.split_once('=') { fields.insert(key.into(), Value::String(value.into())); }
        }
        Value::Object(fields)
    } else { serde_json::from_slice(body).map_err(io::Error::other)? };
    if value.get("success") == Some(&Value::Bool(false)) || value.get("status").and_then(Value::as_str) == Some("fail")
        || value.get("error").is_some_and(|error| !error.is_null())
    { return Err(io::Error::other("probe source rejected lookup")); }
    let ip = if matches!(format, Format::IpApi) { &value["query"] } else { &value["ip"] };
    let ip: IpAddr = ip.as_str().ok_or_else(|| io::Error::other("missing public IP"))?
        .parse().map_err(io::Error::other)?;
    let ip = match ip { IpAddr::V6(ip) => ip.to_ipv4_mapped().map(IpAddr::V4).unwrap_or(IpAddr::V6(ip)), ip => ip };
    if !ip.is_global() { return Err(io::Error::other("probe did not return a public IP")); }
    let (country, organization) = match format {
        Format::Cloudflare => (&value["loc"], None),
        Format::IpInfo => (&value["country"], text(&value["org"])),
        Format::IpApi => (&value["countryCode"], text(&value["org"]).or_else(|| text(&value["isp"]))),
        Format::GeoJs => (&value["country_code"], text(&value["organization_name"]).or_else(|| text(&value["organization"]))),
        Format::CountryIs => (&value["country"], None),
        Format::IpQuery => (&value["location"]["country_code"], text(&value["isp"]["org"]).or_else(|| text(&value["isp"]["isp"]))),
        Format::IdentMe => (&value["cc"], text(&value["aso"])),
        Format::IpWho => (&value["country_code"], text(&value["connection"]["org"]).or_else(|| text(&value["connection"]["isp"]))),
        Format::IpSb => (&value["country_code"], text(&value["organization"]).or_else(|| text(&value["isp"]))),
    };
    let country = country.as_str().ok_or_else(|| io::Error::other("missing country code"))?.to_ascii_uppercase();
    if country.len() != 2 || !country.bytes().all(|byte| byte.is_ascii_uppercase()) {
        return Err(io::Error::other("invalid country code"));
    }
    let kind = if matches!(format, Format::IpQuery) {
        if value["risk"]["is_datacenter"] == true { "hosting" }
        else if value["risk"]["is_mobile"] == true { "mobile" }
        else { "unknown" }
    } else if matches!(format, Format::IdentMe) {
        match value["type"].as_str() {
            Some("isp" | "residential") => "residential",
            Some("mobile") => "mobile",
            Some("business" | "education" | "government" | "banking") => "business",
            Some("hosting") => "hosting",
            _ => "unknown",
        }
    } else { "unknown" };
    Ok(OutboundInfo { ip: ip.to_string(), country_code: country, organization, kind, chains: vec![] })
}

fn text(value: &Value) -> Option<String> {
    value.as_str().map(str::trim).filter(|value| !value.is_empty() && value.chars().count() <= 256)
        .map(str::to_owned)
}

#[cfg(feature = "aerove-mobile-tests")]
#[path = "outbound_tests.rs"]
pub mod regression;
