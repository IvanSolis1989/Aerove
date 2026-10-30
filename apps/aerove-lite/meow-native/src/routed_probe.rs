use crate::control::RouteControl;
use std::{collections::BTreeMap, io, net::IpAddr, sync::Arc, time::Duration};
use axum::{Router, extract::{Request, State}, response::{IntoResponse, Response}, routing::{get, post}, Json};
use axum::http::StatusCode;
use futures::{StreamExt, stream::FuturesUnordered};
use meow_common::{AdapterType, Metadata, ProxyAdapter};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::sync::Semaphore;

#[path = "probe_http.rs"] mod http_probe;
#[path = "probe_services.rs"] mod service_check;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RouteHit { pub host: String, #[serde(default, skip_serializing_if = "String::is_empty")] pub proxy: String, pub exit: String }
#[derive(Clone, Default)]
struct Hits(Arc<parking_lot::Mutex<BTreeMap<(String, String), RouteHit>>>, Option<Arc<parking_lot::Mutex<BTreeMap<(String, String), RouteHit>>>>);
impl Hits {
    fn record(&self, host: &str, proxy: &str, exit: String) {
        let key = (host.into(), proxy.into());
        let hit = RouteHit { host: host.into(), proxy: proxy.into(), exit };
        self.0.lock().insert(key.clone(), hit.clone());
        if let Some(parent) = &self.1 { parent.lock().insert(key, hit); }
    }
    fn fork(&self) -> Self { Self(Arc::default(), Some(self.0.clone())) }
    fn values(&self) -> Vec<RouteHit> { self.0.lock().values().cloned().collect() }
}

struct Resolved {
    metadata: Metadata,
    adapter: Arc<dyn ProxyAdapter>,
    route: Arc<meow_tunnel::tunnel::RouteTable>,
    rule: String,
    rule_payload: String,
}
async fn resolve(control: &RouteControl, address: &str, proxy: &str) -> io::Result<Resolved> {
    let uri: axum::http::Uri = format!("http://{address}/").parse().map_err(io::Error::other)?;
    let host = uri.host().ok_or_else(|| io::Error::other("missing route host"))?.trim_matches(['[', ']']);
    let port = uri.port_u16().ok_or_else(|| io::Error::other("missing route port"))?;
    let mut metadata = Metadata { host: host.into(), dst_port: port, network: meow_common::Network::Tcp,
        conn_type: meow_common::ConnType::Http, process: "meow-rs".into(), internal: true, ..Metadata::default() };
    if matches!(control.state.tunnel.inner().pre_handle_metadata(&mut metadata), meow_tunnel::PreHandleVerdict::Drop) {
        return Err(io::Error::other("unmapped fake IP"));
    }
    if !proxy.is_empty() {
        let route = control.state.tunnel.route_snapshot();
        let adapter = match route.proxies.get(proxy) { Some(adapter) => adapter.clone(),
            None => control.routed_proxy(proxy).map_err(|_| io::Error::other("requested proxy is absent or ambiguous in applied config"))? };
        return Ok(Resolved { metadata, adapter: adapter as Arc<dyn ProxyAdapter>, route, rule: String::new(), rule_payload: String::new() });
    }
    let target = control.state.tunnel.inner().resolve_proxy_lazy(&mut metadata).await.ok_or_else(|| io::Error::other("route unavailable"))?;
    Ok(Resolved { metadata, adapter: target.adapter, route: target.route, rule: target.rule_name.to_string(), rule_payload: target.rule_payload.to_string() })
}
fn exit_name(proxy: &dyn ProxyAdapter) -> String { format!("{:?} {} {}", proxy.name(), proxy.adapter_type(), proxy.addr()) }
fn exit_through(mut adapter: Arc<dyn ProxyAdapter>, metadata: &Metadata) -> String {
    let mut visited = std::collections::HashSet::new();
    loop {
        if !visited.insert(Arc::as_ptr(&adapter) as *const () as usize) { return String::new(); }
        if adapter.adapter_type() == AdapterType::LoadBalance { return exit_name(adapter.as_ref()); }
        let next = match adapter.unwrap_proxy_owned(metadata, meow_common::adapter::SelectionOperation::Exit, false) {
            Ok(next) => next, Err(_) => return String::new(),
        };
        let Some(next) = next else { return exit_name(adapter.as_ref()); };
        adapter = next as Arc<dyn ProxyAdapter>;
    }
}
fn exit_from_chains(resolved: &Resolved, chains: &[String]) -> String {
    for name in chains.iter().rev() {
        if let Some(proxy) = resolved.route.proxies.get(name.as_str()) {
            if proxy.adapter_type() == AdapterType::LoadBalance { return exit_name(proxy.as_ref()); }
        }
    }
    chains.first().and_then(|name| resolved.route.proxies.get(name.as_str()))
        .map(|proxy| exit_name(proxy.as_ref())).unwrap_or_else(|| exit_through(resolved.adapter.clone(), &resolved.metadata))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CheckRequest { hits: Vec<Vec<RouteHit>> }

pub struct Probe { control: Arc<RouteControl>, slot: Semaphore, services: service_check::Services,
    outbound_claims: parking_lot::Mutex<Vec<(u64, Arc<tokio_util::sync::CancellationToken>)>>,
    #[cfg(test)] fixture_sources: parking_lot::Mutex<Option<Vec<Source>>>,
}
impl Probe {
    pub fn new(control: Arc<RouteControl>) -> Arc<Self> { Arc::new(Self { control, slot: Semaphore::new(1), services: service_check::Services::new(),
        outbound_claims: parking_lot::Mutex::new(Vec::new()), #[cfg(test)] fixture_sources: parking_lot::Mutex::new(None) }) }
    pub fn router(self: &Arc<Self>) -> Router {
        Router::new().route("/aerove/outbound", get(Self::outbound))
            .route("/aerove/probe", post(Self::shared_probe))
            .route("/aerove/outbound-ip", post(Self::shared_outbound))
            .route("/aerove/service-check", post(Self::services))
            .route("/aerove/check-routes", post(Self::check_routes))
            .route("/aerove/reset-connections", post(Self::reset_connections))
            .route("/aerove/ip-quality", post(Self::quality)).with_state(self.clone())
    }
    fn stamp(&self) -> io::Result<(u64, u64)> {
        let stamp = self.control.stamp().map_err(|_| io::Error::other("route changed"))?;
        if meow_common::probe_policy::operation_epoch().is_some_and(|epoch| epoch != stamp.0) {
            return Err(io::Error::other("stale captured probe epoch"));
        }
        Ok(stamp)
    }
    fn cancel(&self) -> &tokio_util::sync::CancellationToken { &self.control.plane.cancel }
    async fn services(State(probe): State<Arc<Self>>, request: Request) -> Response { probe.services.handle(&probe, request).await }
    async fn check_routes(State(probe): State<Arc<Self>>, request: Request) -> Response {
        if request.uri().query().is_some() { return failure(StatusCode::BAD_REQUEST, "no_query_parameters"); }
        let Ok(body) = request_body(&probe, request, 1024 * 1024).await else { return failure(StatusCode::BAD_REQUEST, "invalid_request_body"); };
        let Ok(params) = serde_json::from_slice::<CheckRequest>(&body) else { return failure(StatusCode::BAD_REQUEST, "invalid_route_hits"); };
        if params.hits.len() > 16 || params.hits.iter().any(|hits| hits.len() > 1024 || hits.iter().any(|hit| hit.host.len() > 4096 || hit.proxy.len() > 4096 || hit.exit.len() > 16384)) {
            return failure(StatusCode::BAD_REQUEST, "invalid_route_hits");
        }
        let Ok(stamp) = probe.stamp() else { return failure(StatusCode::CONFLICT, "route_changed"); };
        let Ok(trace) = probe.control.begin_consumption_trace(stamp) else { return failure(StatusCode::CONFLICT,"route_changed"); };
        let work = async {
            let mut resolved = BTreeMap::new();
            let mut held = Vec::with_capacity(params.hits.len());
            for hits in params.hits {
                let mut same = !hits.is_empty();
                for hit in hits {
                    let key = (hit.host.clone(), hit.proxy.clone());
                    if !resolved.contains_key(&key) {
                        let exit = resolve(&probe.control, &hit.host, &hit.proxy).await.map(|target| exit_through(target.adapter, &target.metadata)).unwrap_or_default();
                        resolved.insert(key.clone(), exit);
                    }
                    same &= !hit.exit.is_empty() && resolved.get(&key) == Some(&hit.exit);
                }
                held.push(same);
            }
            Ok::<_, io::Error>(held)
        };
        let held = tokio::select! { biased; _ = probe.cancel().cancelled() => return failure(StatusCode::GONE,"session_stopped"), result = tokio::time::timeout(Duration::from_secs(3),trace.clone().scope(work)) => result };
        let Ok(stamp) = probe.control.finish_consumption_trace(&trace) else { return failure(StatusCode::CONFLICT,"route_changed"); };
        match held { Ok(Ok(held)) => Json(json!({"core-epoch":stamp.0.to_string(),"route-version":stamp.1.to_string(),"held":held})).into_response(),
            _ => failure(StatusCode::GATEWAY_TIMEOUT,"route_timeout") }
    }
    async fn outbound(State(probe): State<Arc<Self>>, request: Request) -> Response {
        let direct = match request.uri().query() { None|Some("forceDirect=false") => false, Some("forceDirect=true") => true, _ => return failure(StatusCode::BAD_REQUEST,"invalid_parameters") };
        let Ok(stamp) = probe.stamp() else { return failure(StatusCode::CONFLICT,"outbound_changed"); };
        let Ok(_permit) = probe.slot.try_acquire() else { return failure(StatusCode::TOO_MANY_REQUESTS,"outbound_busy"); };
        let hits = Hits::default();
        let answer = tokio::select! { biased; _ = probe.cancel().cancelled() => return failure(StatusCode::GONE,"session_stopped"),
            result = tokio::time::timeout(TIMEOUT,probe.race(if direct {"DIRECT"} else {""},hits.clone())) => result };
        if probe.stamp().ok() != Some(stamp) { return failure(StatusCode::CONFLICT,"outbound_changed"); }
        match answer { Ok(Ok(info)) => { let mut value=serde_json::to_value(info).expect("serializable outbound");
                value["direct"]=json!(direct); value["coreEpoch"]=json!(stamp.0.to_string()); value["routeVersion"]=json!(stamp.1.to_string());
                value["hits"]=json!(hits.values()); Json(value).into_response() },
            Ok(Err(_)) => failure(StatusCode::BAD_GATEWAY,"outbound_unavailable"), Err(_) => failure(StatusCode::GATEWAY_TIMEOUT,"outbound_timeout") }
    }
    async fn race(&self, proxy: &str, hits: Hits) -> io::Result<OutboundInfo> {
        #[cfg(test)] let sources = self.fixture_sources.lock().clone().unwrap_or_else(sources);
        #[cfg(not(test))] let sources = sources();
        let mut sources = sources.into_iter();
        let mut pending = FuturesUnordered::new();
        if let Some(source) = sources.next() { pending.push(fetch(self.control.clone(),source,proxy.to_owned(),hits.clone())); }
        let stagger=tokio::time::sleep(Duration::from_millis(800)); tokio::pin!(stagger);
        while !pending.is_empty() {
            tokio::select! {
                answer=pending.next() => {
                    if let Some(Ok(info))=answer {return Ok(info);}
                    if let Some(source)=sources.next() {pending.push(fetch(self.control.clone(),source,proxy.to_owned(),hits.clone()));}
                }
                _=&mut stagger => {
                    if pending.len()<3 {if let Some(source)=sources.next() {pending.push(fetch(self.control.clone(),source,proxy.to_owned(),hits.clone()));}}
                    stagger.as_mut().reset(tokio::time::Instant::now()+Duration::from_millis(800));
                }
            }
        }
        Err(io::Error::other("no usable outbound answer"))
    }
    async fn reset_connections(State(probe): State<Arc<Self>>, request: Request) -> Response {
        if request.uri().query().is_some() || request_body(&probe, request, 0).await.is_err() {
            return failure(StatusCode::BAD_REQUEST, "no_parameters");
        }
        if probe.cancel().is_cancelled() { return failure(StatusCode::GONE, "session_stopped"); }
        probe.control.state.tunnel.resolver().reset_connections();
        StatusCode::NO_CONTENT.into_response()
    }
    async fn quality(State(probe): State<Arc<Self>>, request: Request) -> Response {
        #[derive(Deserialize)] #[serde(deny_unknown_fields)] struct Params { source: String, ip: IpAddr, #[serde(default)] direct: bool }
        let Ok(body)=request_body(&probe,request,1024).await else {return failure(StatusCode::BAD_REQUEST,"invalid_request_body");};
        let Ok(params)=serde_json::from_slice::<Params>(&body) else {return failure(StatusCode::BAD_REQUEST,"invalid_quality_parameters");};
        let ip=params.ip;
        let url=match params.source.as_str() {
            "IdentMe"=>"https://ident.me/json".into(),
            "IpApiCom"=>format!("http://ip-api.com/json/{ip}?fields=status,isp,org,as,mobile,proxy,hosting"),
            "IpQuery"=>format!("https://api.ipquery.io/{ip}?format=json"),
            "IpLocate"=>format!("https://iplocate.io/api/lookup/{ip}"),
            "ProxyCheck"=>format!("https://proxycheck.io/v3/{ip}"),
            "IpApiIs"=>format!("https://api.ipapi.is/?q={ip}"),
            _=>return failure(StatusCode::BAD_REQUEST,"invalid_quality_source"),
        };
        let Ok(stamp)=probe.stamp() else {return failure(StatusCode::CONFLICT,"quality_changed");};
        let hits=Hits::default();
        let mut request=http_probe::ProbeRequest::get(&url,1024*1024,Duration::from_secs(8)); request.no_redirect=false;
        let result=tokio::select! {biased;_=probe.cancel().cancelled()=>return failure(StatusCode::GONE,"session_stopped"),
            result=http_probe::run_probe(probe.control.clone(),if params.direct {"DIRECT".into()} else {String::new()},hits.clone(),request)=>result};
        if probe.stamp().ok()!=Some(stamp) {return failure(StatusCode::CONFLICT,"quality_changed");}
        match result {Ok(result)=>match String::from_utf8(result.body) {Ok(body)=>Json(json!({"status":result.status.as_u16(),"body":body,"hits":hits.values(),
                "core-epoch":stamp.0.to_string(),"route-version":stamp.1.to_string()})).into_response(), Err(_)=>failure(StatusCode::BAD_GATEWAY,"quality_encoding")},
            Err(_)=>failure(StatusCode::BAD_GATEWAY,"quality_unavailable")}
    }
}
const DEFAULT_SHARED_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_SHARED_BODY: i64 = 8 * 1024 * 1024;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SharedProbeParams {
    url: String,
    #[serde(default, rename = "proxy-name")] proxy_name: String,
    #[serde(default)] headers: BTreeMap<String, String>,
    timeout: i64,
    #[serde(default, rename = "max-body")] max_body: i64,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SharedOutboundParams {
    #[serde(default, rename = "proxy-name")] proxy_name: String,
    #[serde(default)] urls: Vec<String>,
    timeout: i64,
}

struct OutboundClaim<'a> { owner: &'a Probe, token: Arc<tokio_util::sync::CancellationToken> }
impl Drop for OutboundClaim<'_> {
    fn drop(&mut self) {
        self.token.cancel();
        self.owner.outbound_claims.lock().retain(|(_, token)| !Arc::ptr_eq(token, &self.token));
    }
}

fn shared_epoch(request: &Request) -> Result<u64, Response> {
    let Some(value) = request.uri().query().and_then(|query| query.strip_prefix("coreEpoch=")) else {
        return Err(failure(StatusCode::BAD_REQUEST, "invalid_core_epoch"));
    };
    match value.parse::<u64>() {
        Ok(epoch) if epoch > 0 && epoch.to_string() == value => Ok(epoch),
        _ => Err(failure(StatusCode::BAD_REQUEST, "invalid_core_epoch")),
    }
}

fn shared_timeout(milliseconds: i64) -> Result<Duration, Response> {
    if milliseconds > 60_000 { return Err(failure(StatusCode::BAD_REQUEST, "invalid_timeout")); }
    Ok(if milliseconds <= 0 { DEFAULT_SHARED_TIMEOUT } else { Duration::from_millis(milliseconds as u64) })
}

fn shared_url(value: &str) -> bool {
    value.len() <= 16384 && url::Url::parse(value).is_ok_and(|url| {
        matches!(url.scheme(), "http" | "https") && url.host_str().is_some()
            && url.username().is_empty() && url.password().is_none()
    })
}

fn private_json(value: Value) -> Response {
    ([(axum::http::header::CACHE_CONTROL, "no-store")], Json(value)).into_response()
}

fn probe_error(error: &io::Error) -> &'static str {
    if error.kind() == io::ErrorKind::TimedOut { "timeout" } else { "failed" }
}

impl Probe {
    fn claim_outbound(&self, epoch: u64) -> OutboundClaim<'_> {
        let token = Arc::new(self.cancel().child_token());
        let mut claims = self.outbound_claims.lock();
        claims.retain(|(prior_epoch, prior)| {
            if *prior_epoch < epoch { prior.cancel(); false } else { true }
        });
        claims.push((epoch, token.clone()));
        OutboundClaim { owner: self, token }
    }

    async fn shared_probe(State(probe): State<Arc<Self>>, request: Request) -> Response {
        let scope = match probe.control.routed_scope() { Ok(scope) => scope, Err(error) => return error.response() };
        match scope.run_captured(async move { probe.shared_probe_captured(request).await }).await {
            Ok(response) => response,
            Err(_) => failure(StatusCode::GONE, "session_stopped"),
        }
    }

    async fn shared_probe_captured(&self, request: Request) -> Response {
        let epoch = match shared_epoch(&request) { Ok(epoch) => epoch, Err(response) => return response };
        let body = match request_body(self, request, 65536).await {
            Ok(body) => body,
            Err(_) if self.cancel().is_cancelled() => return failure(StatusCode::GONE, "session_stopped"),
            Err(_) => return failure(StatusCode::BAD_REQUEST, "invalid_request_body"),
        };
        let Ok(params) = serde_json::from_slice::<SharedProbeParams>(&body) else {
            return failure(StatusCode::BAD_REQUEST, "invalid_probe_parameters");
        };
        if !shared_url(&params.url) || params.proxy_name.len() > 4096 || params.max_body > MAX_SHARED_BODY || params.headers.len() > 128 {
            return failure(StatusCode::BAD_REQUEST, "invalid_probe_parameters");
        }
        let timeout = match shared_timeout(params.timeout) { Ok(value) => value, Err(response) => return response };
        let mut request = http_probe::ProbeRequest::get(&params.url, params.max_body.max(0) as usize, timeout);
        request.truncate_body = true;
        for (name, value) in &params.headers {
            let Ok(name) = axum::http::HeaderName::from_bytes(name.as_bytes()) else {
                return failure(StatusCode::BAD_REQUEST, "invalid_probe_headers");
            };
            let Ok(value) = axum::http::HeaderValue::from_str(value) else {
                return failure(StatusCode::BAD_REQUEST, "invalid_probe_headers");
            };
            request.headers.insert(name, value);
        }
        let proof = match self.control.capture_routed_probe(epoch) { Ok(proof) => Arc::new(proof), Err(error) => return error.response() };
        request.proof = Some(proof.clone());
        let hits = Hits::default();
        let result = tokio::select! { biased;
            _ = self.cancel().cancelled() => return failure(StatusCode::GONE, "session_stopped"),
            result = tokio::time::timeout(timeout, http_probe::run_probe(self.control.clone(), params.proxy_name, hits.clone(), request)) =>
                result.unwrap_or_else(|error| Err(io::Error::new(io::ErrorKind::TimedOut, error))),
        };
        if let Err(error) = self.control.readback_routed_probe(&proof) { return error.response(); }
        let mut value = match result {
            Ok(result) => json!({"status-code":result.status.as_u16(), "delay":result.delay,
                "body":String::from_utf8_lossy(&result.body), "url":result.url, "chains":result.chains,
                "rule":result.rule, "rule-payload":result.rule_payload}),
            Err(error) => json!({"status-code":0, "delay":0, "body":"", "url":params.url, "chains":[],
                "rule":"", "rule-payload":"", "error":probe_error(&error), "message":error.to_string()}),
        };
        value["core-epoch"] = json!(proof.stamp.0.to_string());
        value["route-version"] = json!(proof.stamp.1.to_string());
        value["hits"] = json!(hits.values());
        private_json(value)
    }

    async fn shared_outbound(State(probe): State<Arc<Self>>, request: Request) -> Response {
        let scope = match probe.control.routed_scope() { Ok(scope) => scope, Err(error) => return error.response() };
        match scope.run_captured(async move { probe.shared_outbound_captured(request).await }).await {
            Ok(response) => response,
            Err(_) => failure(StatusCode::GONE, "session_stopped"),
        }
    }

    async fn shared_outbound_captured(&self, request: Request) -> Response {
        let epoch = match shared_epoch(&request) { Ok(epoch) => epoch, Err(response) => return response };
        let body = match request_body(self, request, 1024 * 1024).await {
            Ok(body) => body,
            Err(_) if self.cancel().is_cancelled() => return failure(StatusCode::GONE, "session_stopped"),
            Err(_) => return failure(StatusCode::BAD_REQUEST, "invalid_request_body"),
        };
        let Ok(params) = serde_json::from_slice::<SharedOutboundParams>(&body) else {
            return failure(StatusCode::BAD_REQUEST, "invalid_outbound_parameters");
        };
        if params.urls.len() > 64 || params.proxy_name.len() > 4096 || params.urls.iter().any(|url| !shared_url(url)) {
            return failure(StatusCode::BAD_REQUEST, "invalid_outbound_parameters");
        }
        let timeout = match shared_timeout(params.timeout) { Ok(value) => value, Err(response) => return response };
        let proof = match self.control.capture_routed_probe(epoch) { Ok(proof) => Arc::new(proof), Err(error) => return error.response() };
        let hits = Hits::default();
        let claim = self.claim_outbound(proof.stamp.0);
        let work = async {
            let (url, result, winning_hits) = self.shared_race(params.urls, &params.proxy_name, timeout, hits.clone(), proof.clone()).await?;
            let mut node_hits = Vec::new();
            if let Some(node) = result.chains.first().filter(|node| node.as_str() != params.proxy_name.as_str()) {
                for hit in winning_hits {
                    let exit = resolve(&self.control, &hit.host, node).await
                        .map(|target| exit_through(target.adapter, &target.metadata)).unwrap_or_default();
                    node_hits.push(RouteHit { host: hit.host, proxy: node.clone(), exit });
                }
            }
            Ok::<_, io::Error>(json!({"url":url, "body":String::from_utf8_lossy(&result.body), "delay":result.delay,
                "chains":result.chains, "node-hits":node_hits}))
        };
        let result = tokio::select! { biased;
            _ = self.cancel().cancelled() => return failure(StatusCode::GONE, "session_stopped"),
            _ = claim.token.cancelled() => return failure(StatusCode::CONFLICT, "outbound_superseded"),
            result = tokio::time::timeout(timeout, work) =>
                result.unwrap_or_else(|error| Err(io::Error::new(io::ErrorKind::TimedOut, error))),
        };
        if let Err(error) = self.control.readback_routed_probe(&proof) { return error.response(); }
        let mut value = result.unwrap_or_else(|error| json!({"url":"", "body":"", "delay":0, "chains":[],
            "node-hits":[], "error":probe_error(&error)}));
        value["core-epoch"] = json!(proof.stamp.0.to_string());
        value["route-version"] = json!(proof.stamp.1.to_string());
        value["hits"] = json!(hits.values());
        private_json(value)
    }

    async fn shared_race(&self, urls: Vec<String>, proxy: &str, timeout: Duration, hits: Hits,
        proof: Arc<crate::control::RoutedProbeProof>)
        -> io::Result<(String, http_probe::ProbeResult, Vec<RouteHit>)> {
        let mut urls = urls.into_iter();
        let mut pending = FuturesUnordered::new();
        let mut timed_out = false;
        if let Some(url) = urls.next() { pending.push(shared_fetch(self.control.clone(), url, proxy.to_owned(), timeout, hits.fork(), proof.clone())); }
        let stagger = tokio::time::sleep(Duration::from_millis(800));
        tokio::pin!(stagger);
        while !pending.is_empty() {
            tokio::select! {
                answer = pending.next() => {
                    match answer {
                        Some(Ok(answer)) => return Ok(answer),
                        Some(Err(error)) => timed_out |= error.kind() == io::ErrorKind::TimedOut,
                        None => {},
                    }
                    if let Some(url) = urls.next() { pending.push(shared_fetch(self.control.clone(), url, proxy.to_owned(), timeout, hits.fork(), proof.clone())); }
                }
                _ = &mut stagger => {
                    if pending.len() < 3 {
                        if let Some(url) = urls.next() { pending.push(shared_fetch(self.control.clone(), url, proxy.to_owned(), timeout, hits.fork(), proof.clone())); }
                    }
                    stagger.as_mut().reset(tokio::time::Instant::now() + Duration::from_millis(800));
                }
            }
        }
        Err(io::Error::new(if timed_out { io::ErrorKind::TimedOut } else { io::ErrorKind::Other },
            "no caller URL returned a usable public address"))
    }
}

async fn shared_fetch(control: Arc<RouteControl>, url: String, proxy: String, timeout: Duration, hits: Hits,
    proof: Arc<crate::control::RoutedProbeProof>)
    -> io::Result<(String, http_probe::ProbeResult, Vec<RouteHit>)> {
    let mut request = http_probe::ProbeRequest::get(&url, 4096, timeout);
    request.truncate_body = true;
    request.proof = Some(proof);
    request.headers.insert(axum::http::header::USER_AGENT, http_probe::USER_AGENT.parse().expect("fixed browser user agent"));
    let result = http_probe::run_probe(control, proxy, hits.clone(), request).await?;
    if result.status != StatusCode::OK || !mentions_ip(&String::from_utf8_lossy(&result.body)) {
        return Err(io::Error::other("caller source returned no usable public address"));
    }
    Ok((url, result, hits.values()))
}

fn mentions_ip(body: &str) -> bool {
    body.split(|character: char| !(character.is_ascii_alphanumeric() || matches!(character, '.' | ':')))
        .filter_map(|token| token.parse::<IpAddr>().ok()).any(|ip| {
            let ip = match ip { IpAddr::V6(ip) => ip.to_ipv4_mapped().map(IpAddr::V4).unwrap_or(IpAddr::V6(ip)), ip => ip };
            match ip {
                IpAddr::V4(ip) => !ip.is_unspecified() && !ip.is_loopback() && !ip.is_link_local()
                    && !ip.is_multicast() && !ip.is_broadcast() && !ip.is_private()
                    && !(ip.octets()[0] == 100 && (64..128).contains(&ip.octets()[1])),
                IpAddr::V6(ip) => !ip.is_unspecified() && !ip.is_loopback() && !ip.is_multicast()
                    && !ip.is_unicast_link_local() && !ip.is_unique_local(),
            }
        })
}

async fn request_body(probe: &Probe, request: Request, limit: usize) -> io::Result<Vec<u8>> {
    tokio::select! { biased; _=probe.cancel().cancelled()=>Err(io::Error::from(io::ErrorKind::Interrupted)),
        result=tokio::time::timeout(Duration::from_secs(5),axum::body::to_bytes(request.into_body(),limit))=>
            result.map_err(io::Error::other)?.map(|bytes|bytes.to_vec()).map_err(io::Error::other) }
}
fn failure(status: StatusCode, code: &str) -> Response {
    (status,[(axum::http::header::CACHE_CONTROL,"no-store")],Json(json!({"error":code}))).into_response()
}
const TIMEOUT: Duration=Duration::from_secs(6);
#[derive(Clone,Copy)] enum Format { Cloudflare,IpSb,IpInfo,IpWho,IpApi,GeoJs,CountryIs,IpQuery,IdentMe }
#[derive(Clone)] struct Source { url: String, format: Format }
fn sources()->Vec<Source> { vec![
    Source {url:"https://www.cloudflare.com/cdn-cgi/trace".into(),format:Format::Cloudflare},
    Source {url:"https://api.ip.sb/geoip".into(),format:Format::IpSb},
    Source {url:"https://ipinfo.io/json".into(),format:Format::IpInfo},
    Source {url:"https://ipwho.is".into(),format:Format::IpWho},
    Source {url:"http://ip-api.com/json".into(),format:Format::IpApi},
    Source {url:"https://get.geojs.io/v1/ip/geo.json".into(),format:Format::GeoJs},
    Source {url:"https://api.country.is".into(),format:Format::CountryIs},
    Source {url:"https://api.ipquery.io/?format=json".into(),format:Format::IpQuery},
    Source {url:"https://ident.me/json".into(),format:Format::IdentMe},
] }
#[derive(Serialize)] #[serde(rename_all="camelCase")]
struct OutboundInfo { ip:String,country_code:String,organization:Option<String>,kind:&'static str,chains:Vec<String> }
async fn fetch(control:Arc<RouteControl>,source:Source,proxy:String,hits:Hits)->io::Result<OutboundInfo> {
    let mut request=http_probe::ProbeRequest::get(&source.url,4096,TIMEOUT); request.strict=true;request.no_redirect=true;
    request.headers.insert(axum::http::header::ACCEPT,"application/json".parse().unwrap());
    let result=http_probe::run_probe(control,proxy,hits,request).await?;
    let mut info=parse(source.format,&result.body)?;info.chains=result.chains;Ok(info)
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
    if !public_ip(ip) { return Err(io::Error::other("probe did not return a public IP")); }
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


fn public_ip(ip:IpAddr)->bool {
    const RESERVED:&[&str]=&["0.0.0.0/8","10.0.0.0/8","100.64.0.0/10","127.0.0.0/8","169.254.0.0/16","172.16.0.0/12","192.0.0.0/24","192.0.2.0/24","192.168.0.0/16","198.18.0.0/15","198.51.100.0/24","203.0.113.0/24","224.0.0.0/4","240.0.0.0/4","::/128","::1/128","64:ff9b:1::/48","100::/64","2001:db8::/32","fc00::/7","fe80::/10","ff00::/8"];
    !RESERVED.iter().any(|prefix|prefix.parse::<ipnet::IpNet>().expect("fixed prefix").contains(&ip))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{api::CoreApi, providers::{self, ControlPlane, OwnedLoadScope}};
    use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};
    use tokio_util::sync::CancellationToken;
    const SECRET:&str="routed-probe-private-secret-00000001";
    async fn fixture() -> (Arc<CoreApi>, Arc<OwnedLoadScope>, std::net::SocketAddr, tokio::task::JoinHandle<Result<(),crate::api::ApiError>>) {
        let cancel=CancellationToken::new();
        let plane=ControlPlane::new(91,cancel.clone()).unwrap();
        let scope=OwnedLoadScope::new();
        let config=providers::load_with_cache("strict: true\nproxies:\n - {name: A, type: direct}\n - {name: B, type: direct}\nproxy-groups:\n - {name: PICK, type: select, proxies: [A,B]}\nrules: ['MATCH,PICK']\n",&scope,&cancel,None).await.unwrap();
        let tunnel=meow_tunnel::Tunnel::new_with_slot(config.dns.resolver_slot.clone());
        tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();
        let api=Arc::new(CoreApi::assemble_with_plane(&config,tunnel.clone(),"127.0.0.1:12345".parse().unwrap(),SECRET,None,Router::new(),plane).unwrap());
        tunnel.update_routing(config.proxies,config.rules,config.dialer_registry);
        let _events = api.control.activate_groups(&scope).unwrap();
        let serving=api.clone();let server=tokio::spawn(async move{serving.serve_private(listener,cancel).await});
        (api,scope,address,server)
    }
    async fn request(address:std::net::SocketAddr,method:&str,path:&str,body:&str)->String {
        let mut socket=TcpStream::connect(address).await.unwrap();
        socket.write_all(format!("{method} {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {SECRET}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).as_bytes()).await.unwrap();
        let mut response=String::new();tokio::time::timeout(Duration::from_secs(10),socket.read_to_string(&mut response)).await.unwrap().unwrap();response
    }
    async fn stop(api:Arc<CoreApi>,scope:Arc<OwnedLoadScope>,server:tokio::task::JoinHandle<Result<(),crate::api::ApiError>>) {
        api.cancel();scope.cancel();scope.close().await.unwrap();api.join().await;server.await.unwrap().unwrap();
    }
    #[tokio::test]
    async fn check_routes_resolves_without_dial_and_mode_moves_version_without_reset() {
        let(api,scope,address,server)=fixture().await;
        let origin=TcpListener::bind("127.0.0.1:0").await.unwrap();let host=origin.local_addr().unwrap().to_string();
        let target=resolve(&api.control,&host,"").await.unwrap();let exit=exit_through(target.adapter,&target.metadata);
        let body=json!({"hits":[[{"host":host,"exit":exit}]]}).to_string();
        let held=request(address,"POST","/aerove/check-routes",&body).await;
        assert!(held.contains("\"held\":[true]"),"{held}");
        assert!(tokio::time::timeout(Duration::from_millis(40),origin.accept()).await.is_err(),"route recheck opened a target socket");
        let before=api.control.snapshot().await.unwrap();api.control.set_mode(meow_common::TunnelMode::Direct).await.unwrap();
        let after=api.control.snapshot().await.unwrap();assert_eq!(before.core_epoch,after.core_epoch);assert_ne!(before.route_version,after.route_version);
        let moved=request(address,"POST","/aerove/check-routes",&body).await;assert!(moved.contains("\"held\":[false]"),"{moved}");
        stop(api,scope,server).await;
    }
    #[tokio::test]
    async fn actual_private_outbound_uses_the_real_adapter_and_reports_every_attempted_host() {
        let(api,scope,address,server)=fixture().await;
        let origin=TcpListener::bind("127.0.0.1:0").await.unwrap();let host=origin.local_addr().unwrap();
        let fetch=tokio::spawn(async move {let(mut socket,_)=origin.accept().await.unwrap();let mut head=Vec::new();while !head.ends_with(b"\r\n\r\n"){head.push(socket.read_u8().await.unwrap());assert!(head.len()<8192);}
            let body="ip=1.1.1.1\nloc=AU\n";socket.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).as_bytes()).await.unwrap();});
        *api.probes.fixture_sources.lock()=Some(vec![Source {url:format!("http://{host}/trace"),format:Format::Cloudflare}]);
        let response=request(address,"GET","/aerove/outbound","").await;
        assert!(response.starts_with("HTTP/1.1 200"),"{response}");let value:Value=serde_json::from_str(response.split_once("\r\n\r\n").unwrap().1).unwrap();
        assert_eq!(value["ip"],"1.1.1.1");assert_eq!(value["hits"][0]["host"],host.to_string());assert!(value["hits"][0]["exit"].as_str().unwrap().contains("A"));
        assert_eq!(value["chains"][0],"A");fetch.await.unwrap();stop(api,scope,server).await;
    }
    #[tokio::test]
    async fn rejected_service_dial_still_records_its_host_and_exit_for_later_recheck() {
        let(api,scope,address,server)=fixture().await;
        let origin=TcpListener::bind("127.0.0.1:0").await.unwrap();let host=origin.local_addr().unwrap();drop(origin);
        *api.probes.services.fixture_base.write().unwrap()=Some(format!("http://{host}"));
        let response=request(address,"POST","/aerove/service-check",r#"{"names":["google"],"proxy-name":"A","timeout":100}"#).await;
        assert!(response.starts_with("HTTP/1.1 200"),"{response}");let value:Value=serde_json::from_str(response.split_once("\r\n\r\n").unwrap().1).unwrap();
        assert_eq!(value[0]["status"],"failed");assert_eq!(value[0]["hits"][0]["host"],host.to_string());assert_eq!(value[0]["hits"][0]["proxy"],"A");assert!(!value[0]["hits"][0]["exit"].as_str().unwrap().is_empty());
        stop(api,scope,server).await;
    }

    fn response_json(response: &str) -> Value {
        assert!(response.starts_with("HTTP/1.1 200"), "{response}");
        serde_json::from_str(response.split_once("\r\n\r\n").unwrap().1).unwrap()
    }

    async fn accepted_head(listener: &TcpListener) -> (TcpStream, String) {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut head = Vec::new();
        while !head.ends_with(b"\r\n\r\n") {
            head.push(socket.read_u8().await.unwrap());
            assert!(head.len() < 65536);
        }
        (socket, String::from_utf8(head).unwrap())
    }

    #[tokio::test]
    async fn shared_probe_honors_caller_headers_proxy_status_and_body_cap() {
        let (api, scope, address, server) = fixture().await;
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let host = origin.local_addr().unwrap();
        let fetch = tokio::spawn(async move {
            let (mut socket, head) = accepted_head(&origin).await;
            let head = head.to_ascii_lowercase();
            assert!(head.starts_with("get /custom?q=1 http/1.1\r\n"));
            assert!(head.contains("user-agent: caller-agent\r\n"));
            assert!(head.contains("x-caller: exact-header\r\n"));
            socket.write_all(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 16\r\nConnection: close\r\n\r\nabcdefghijklmnop").await.unwrap();
        });
        let url = format!("http://{host}/custom?q=1");
        let body = json!({"url":url, "proxy-name":"B", "headers":{"User-Agent":"caller-agent","X-Caller":"exact-header"},
            "timeout":2000, "max-body":8}).to_string();
        let response = request(address, "POST", "/aerove/probe?coreEpoch=91", &body).await;
        let value = response_json(&response);
        assert_eq!(value["status-code"], 403); assert_eq!(value["body"], "abcdefgh");
        assert_eq!(value["url"], url); assert_eq!(value["chains"][0], "B");
        assert_eq!(value["rule"], ""); assert_eq!(value["rule-payload"], "");
        assert_eq!(value["core-epoch"], "91"); assert_eq!(value["route-version"], "0");
        assert_eq!(value["hits"][0]["host"], host.to_string()); assert_eq!(value["hits"][0]["proxy"], "B");
        assert!(value["delay"].is_u64()); assert!(value.get("error").is_none());
        fetch.await.unwrap(); stop(api, scope, server).await;
    }

    #[tokio::test]
    async fn shared_probe_redirect_reports_final_url_and_real_routing_rule_without_body_read() {
        let (api, scope, address, server) = fixture().await;
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let host = origin.local_addr().unwrap();
        let fetch = tokio::spawn(async move {
            let (mut first, _) = accepted_head(&origin).await;
            first.write_all(b"HTTP/1.1 302 Found\r\nLocation: /final\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await.unwrap();
            let (mut last, head) = accepted_head(&origin).await;
            assert!(head.starts_with("GET /final HTTP/1.1\r\n"));
            last.write_all(b"HTTP/1.1 201 Created\r\nContent-Length: 6\r\nConnection: close\r\n\r\nabcdef").await.unwrap();
        });
        let response = request(address, "POST", "/aerove/probe?coreEpoch=91",
            &json!({"url":format!("http://{host}/"),"timeout":2000}).to_string()).await;
        let value = response_json(&response);
        assert_eq!(value["status-code"], 201); assert_eq!(value["body"], "");
        assert_eq!(value["url"], format!("http://{host}/final"));
        assert_eq!(value["rule"], "MATCH"); assert_eq!(value["rule-payload"], "");
        assert_eq!(value["chains"][0], "A"); assert!(value["hits"][0]["exit"].as_str().unwrap().contains("A"));
        fetch.await.unwrap(); stop(api, scope, server).await;
    }

    #[tokio::test]
    async fn shared_probe_caps_decoded_gzip_without_turning_the_cap_into_failure() {
        let (api, scope, address, server) = fixture().await;
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let host = origin.local_addr().unwrap();
        let fetch = tokio::spawn(async move {
            let (mut socket, head) = accepted_head(&origin).await;
            assert!(head.to_ascii_lowercase().contains("accept-encoding: gzip\r\n"));
            let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
            let body = [b"abcdefgh".as_slice(), &vec![b'i'; 128 * 1024]].concat();
            std::io::Write::write_all(&mut encoder, &body).unwrap();
            let compressed = encoder.finish().unwrap();
            socket.write_all(format!("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", compressed.len()).as_bytes()).await.unwrap();
            socket.write_all(&compressed).await.unwrap();
        });
        let value = response_json(&request(address, "POST", "/aerove/probe?coreEpoch=91",
            &json!({"url":format!("http://{host}/"),"timeout":2000,"max-body":8}).to_string()).await);
        assert_eq!(value["status-code"], 200); assert_eq!(value["body"], "abcdefgh");
        assert!(value.get("error").is_none());
        fetch.await.unwrap(); stop(api, scope, server).await;
    }

    #[tokio::test]
    async fn shared_probe_timeout_drops_real_socket_and_keeps_failed_attempt_hit() {
        let (api, scope, address, server) = fixture().await;
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let host = origin.local_addr().unwrap();
        let fetch = tokio::spawn(async move {
            let (mut socket, _) = accepted_head(&origin).await;
            let mut byte = [0];
            assert_eq!(tokio::time::timeout(Duration::from_secs(2), socket.read(&mut byte)).await.unwrap().unwrap(), 0);
        });
        let value = response_json(&request(address, "POST", "/aerove/probe?coreEpoch=91",
            &json!({"url":format!("http://{host}/hang"),"proxy-name":"B","timeout":100,"max-body":10}).to_string()).await);
        assert_eq!(value["error"], "timeout"); assert_eq!(value["status-code"], 0);
        assert_eq!(value["hits"][0]["host"], host.to_string()); assert_eq!(value["hits"][0]["proxy"], "B");
        fetch.await.unwrap(); stop(api, scope, server).await;
    }

    #[tokio::test]
    async fn shared_probe_rejects_stale_route_response_after_actual_request() {
        let (api, scope, address, server) = fixture().await;
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let host = origin.local_addr().unwrap();
        let (sent, arrived) = tokio::sync::oneshot::channel();
        let (release, released) = tokio::sync::oneshot::channel();
        let fetch = tokio::spawn(async move {
            let (mut socket, _) = accepted_head(&origin).await;
            sent.send(()).unwrap(); released.await.unwrap();
            socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok").await.unwrap();
        });
        let pending = tokio::spawn(async move {
            request(address, "POST", "/aerove/probe?coreEpoch=91",
                &json!({"url":format!("http://{host}/"),"timeout":2000,"max-body":10}).to_string()).await
        });
        arrived.await.unwrap(); api.control.set_mode(meow_common::TunnelMode::Direct).await.unwrap();
        release.send(()).unwrap();
        let response = pending.await.unwrap(); assert!(response.starts_with("HTTP/1.1 409"), "{response}");
        fetch.await.unwrap(); stop(api, scope, server).await;
    }

    #[tokio::test]
    async fn shared_probe_scope_cancel_joins_request_and_closes_target() {
        let (api, scope, address, server) = fixture().await;
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let host = origin.local_addr().unwrap();
        let (sent, arrived) = tokio::sync::oneshot::channel();
        let fetch = tokio::spawn(async move {
            let (mut socket, _) = accepted_head(&origin).await; sent.send(()).unwrap();
            let mut byte = [0];
            assert_eq!(tokio::time::timeout(Duration::from_secs(2), socket.read(&mut byte)).await.unwrap().unwrap(), 0);
        });
        let pending = tokio::spawn(async move {
            request(address, "POST", "/aerove/probe?coreEpoch=91",
                &json!({"url":format!("http://{host}/"),"timeout":60000,"max-body":10}).to_string()).await
        });
        arrived.await.unwrap(); scope.cancel();
        let response = tokio::time::timeout(Duration::from_secs(2), pending).await.unwrap().unwrap();
        assert!(response.starts_with("HTTP/1.1 410"), "{response}");
        tokio::time::timeout(Duration::from_secs(2), scope.close()).await.unwrap().unwrap();
        fetch.await.unwrap(); stop(api, scope, server).await;
    }

    #[tokio::test]
    async fn shared_outbound_uses_caller_urls_preserves_requested_url_and_cancels_loser() {
        let (api, scope, address, server) = fixture().await;
        let slow = TcpListener::bind("127.0.0.1:0").await.unwrap(); let slow_host = slow.local_addr().unwrap();
        let fast = TcpListener::bind("127.0.0.1:0").await.unwrap(); let fast_host = fast.local_addr().unwrap();
        let loser = tokio::spawn(async move {
            let (mut socket, _) = accepted_head(&slow).await;
            let mut byte = [0];
            assert_eq!(tokio::time::timeout(Duration::from_secs(3), socket.read(&mut byte)).await.unwrap().unwrap(), 0);
        });
        let winner = tokio::spawn(async move {
            let (mut first, _) = accepted_head(&fast).await;
            first.write_all(b"HTTP/1.1 302 Found\r\nLocation: /final\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await.unwrap();
            let (mut last, head) = accepted_head(&fast).await;
            assert!(head.to_ascii_lowercase().contains("user-agent: mozilla/5.0"));
            let body = format!("ip=1.1.1.1\n{}", "x".repeat(5000));
            last.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).as_bytes()).await.unwrap();
        });
        let url = format!("http://{fast_host}/requested");
        let value = response_json(&request(address, "POST", "/aerove/outbound-ip?coreEpoch=91",
            &json!({"proxy-name":"PICK","urls":[format!("http://{slow_host}/hang"),url],"timeout":2500}).to_string()).await);
        assert_eq!(value["url"], url); assert_eq!(value["body"].as_str().unwrap().len(), 4096);
        assert_eq!(value["chains"][0], "A"); assert!(value.get("error").is_none());
        assert_eq!(value["core-epoch"], "91"); assert_eq!(value["route-version"], "0");
        let hits = value["hits"].as_array().unwrap(); assert_eq!(hits.len(), 2);
        assert!(hits.iter().any(|hit| hit["host"] == slow_host.to_string()));
        assert!(hits.iter().any(|hit| hit["host"] == fast_host.to_string()));
        assert_eq!(value["node-hits"][0]["proxy"], "A");
        assert!(value["node-hits"][0]["exit"].as_str().unwrap().contains("A"));
        loser.await.unwrap(); winner.await.unwrap(); stop(api, scope, server).await;
    }

    #[tokio::test]
    async fn shared_outbound_skips_non_200_and_private_addresses_without_parsing_countries() {
        let (api, scope, address, server) = fixture().await;
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let host = origin.local_addr().unwrap();
        let fetch = tokio::spawn(async move {
            for (path, status, body) in [("/status", "403 Forbidden", "ip=1.1.1.1"),
                ("/private", "200 OK", "ip=10.1.2.3"), ("/public", "200 OK", "raw=2606:4700:4700::1111")] {
                let (mut socket, head) = accepted_head(&origin).await;
                assert!(head.starts_with(&format!("GET {path} HTTP/1.1\r\n")));
                socket.write_all(format!("HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).as_bytes()).await.unwrap();
            }
        });
        let value = response_json(&request(address, "POST", "/aerove/outbound-ip?coreEpoch=91",
            &json!({"proxy-name":"B","urls":[format!("http://{host}/status"),format!("http://{host}/private"),format!("http://{host}/public")],"timeout":2000}).to_string()).await);
        assert_eq!(value["url"], format!("http://{host}/public"));
        assert_eq!(value["body"], "raw=2606:4700:4700::1111"); assert_eq!(value["chains"][0], "B");
        assert_eq!(value["node-hits"], json!([])); assert!(value.get("error").is_none());
        fetch.await.unwrap(); stop(api, scope, server).await;
    }

    #[tokio::test]
    async fn newer_epoch_outbound_claim_cancels_actual_old_socket_but_same_epoch_claims_coexist() {
        let (api, scope, address, server) = fixture().await;
        {
            let first = api.probes.claim_outbound(91); let second = api.probes.claim_outbound(91);
            assert!(!first.token.is_cancelled()); assert!(!second.token.is_cancelled());
            drop(second); assert!(!first.token.is_cancelled());
        }
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let host = origin.local_addr().unwrap();
        let (sent, arrived) = tokio::sync::oneshot::channel();
        let fetch = tokio::spawn(async move {
            let (mut socket, _) = accepted_head(&origin).await; sent.send(()).unwrap();
            let mut byte = [0];
            assert_eq!(tokio::time::timeout(Duration::from_secs(2), socket.read(&mut byte)).await.unwrap().unwrap(), 0);
        });
        let pending = tokio::spawn(async move {
            request(address, "POST", "/aerove/outbound-ip?coreEpoch=91",
                &json!({"urls":[format!("http://{host}/")],"timeout":60000}).to_string()).await
        });
        arrived.await.unwrap();
        let (stamp, _) = api.control.plane.commit_policy(91, true, || ()).unwrap();
        let value = response_json(&request(address, "POST", &format!("/aerove/outbound-ip?coreEpoch={}", stamp.0),
            "{\"urls\":[],\"timeout\":1000}").await);
        assert_eq!(value["error"], "failed");
        let response = tokio::time::timeout(Duration::from_secs(2), pending).await.unwrap().unwrap();
        assert!(response.starts_with("HTTP/1.1 409"), "{response}");
        fetch.await.unwrap(); stop(api, scope, server).await;
    }

    #[tokio::test]
    async fn shared_parameters_empty_sources_and_bearer_boundary_do_not_fall_back_to_fixed_sources() {
        let (api, scope, address, server) = fixture().await;
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let host = origin.local_addr().unwrap();
        let body = json!({"url":format!("http://{host}/"),"timeout":1000,"headers":{"bad header":"x"}}).to_string();
        for path in ["/aerove/probe", "/aerove/probe?coreEpoch=0", "/aerove/probe?coreEpoch=091", "/aerove/probe?coreEpoch=91"] {
            let response = request(address, "POST", path, &body).await;
            assert!(response.starts_with("HTTP/1.1 400"), "{response}");
        }
        let stale = request(address, "POST", "/aerove/probe?coreEpoch=90",
            &json!({"url":format!("http://{host}/"),"timeout":1000}).to_string()).await;
        assert!(stale.starts_with("HTTP/1.1 409"), "{stale}");
        let empty = response_json(&request(address, "POST", "/aerove/outbound-ip?coreEpoch=91", "{\"urls\":[],\"timeout\":1000}").await);
        assert_eq!(empty["error"], "failed"); assert_eq!(empty["hits"], json!([]));
        let missing = response_json(&request(address, "POST", "/aerove/probe?coreEpoch=91",
            &json!({"url":format!("http://{host}/"),"proxy-name":"missing","timeout":1000}).to_string()).await);
        assert_eq!(missing["error"], "failed"); assert_eq!(missing["hits"][0]["proxy"], "missing");
        assert!(tokio::time::timeout(Duration::from_millis(40), origin.accept()).await.is_err());
        let mut socket = TcpStream::connect(address).await.unwrap();
        socket.write_all(b"POST /aerove/probe?coreEpoch=91 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await.unwrap();
        let mut response = String::new(); socket.read_to_string(&mut response).await.unwrap();
        assert!(response.starts_with("HTTP/1.1 401"), "{response}");
        stop(api, scope, server).await;
    }

    #[test]
    fn shared_outbound_public_address_filter_matches_standard_global_unicast_gate() {
        for body in ["ip=1.1.1.1", "{\"ip\":\"2606:4700:4700::1111\"}", "ip=::ffff:8.8.8.8"] { assert!(mentions_ip(body), "{body}"); }
        for body in ["captcha", "127.0.0.1", "10.1.2.3", "100.64.0.1", "169.254.1.1", "::1", "fe80::1", "fc00::1", "224.0.0.1", "::ffff:192.168.1.1"] { assert!(!mentions_ip(body), "{body}"); }
    }
}
