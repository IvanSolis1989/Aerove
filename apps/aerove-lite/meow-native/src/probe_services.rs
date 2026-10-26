use super::{Probe, failure, http_probe::{ProbeRequest, ProbeResult, run_probe}};
use std::{collections::HashMap, io, sync::{Arc, Mutex}, time::{Duration, SystemTime, UNIX_EPOCH}};
use axum::{body::to_bytes, extract::Request, response::{IntoResponse, Response}};
use futures::{FutureExt, StreamExt, stream::FuturesUnordered};
use axum::http::StatusCode;
use serde::{Deserialize, Serialize};
use tokio::{sync::Semaphore, time::Instant};
use tokio_util::sync::CancellationToken;
use super::{Hits, RouteControl};

#[path = "service_rules.rs"]
mod rules;

pub(super) const TARGETS: &[&str] = &["google", "github", "youtube", "chatgpt", "claude", "gemini", "netflix", "disney-plus", "prime-video", "spotify", "tiktok", "discord", "bilibili"];
const DEFAULT_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Params {
    names: Vec<String>,
    #[serde(default, rename = "proxy-name")]
    proxy_name: String,
    #[serde(default)]
    timeout: i64,
}

#[derive(Clone, Default, Serialize)]
#[serde(rename_all = "kebab-case")]
pub(super) struct Item {
    pub name: String,
    pub status: String,
    pub region: String,
    pub delay: u64,
    pub chains: Vec<String>,
    pub checked_at: u64,
    pub core_epoch: u64,
    pub route_version: u64,
    pub hits: Vec<super::RouteHit>,
}

pub(super) struct Services {
    slots: Semaphore,
    claims: Mutex<HashMap<String, Arc<CancellationToken>>>,
    #[cfg(test)]
    pub(super) fixture_base: std::sync::RwLock<Option<String>>,
}

struct Claims<'a> {
    owner: &'a Services,
    entries: Vec<(String, Arc<CancellationToken>)>,
}

impl Drop for Claims<'_> {
    fn drop(&mut self) {
        let mut claims = self.owner.claims.lock().unwrap();
        for (name, token) in &self.entries {
            token.cancel();
            if claims.get(name).is_some_and(|current| Arc::ptr_eq(current, token)) { claims.remove(name); }
        }
    }
}

impl Services {
    pub(super) fn new() -> Self {
        Self { slots: Semaphore::new(4), claims: Mutex::new(HashMap::new()),
            #[cfg(test)] fixture_base: std::sync::RwLock::new(None) }
    }

    pub(super) async fn handle(&self, probe: &Arc<Probe>, request: Request) -> Response {
        if request.uri().query().is_some() { return failure(StatusCode::BAD_REQUEST, "no_query_parameters"); }
        if probe.cancel().is_cancelled() { return failure(StatusCode::SERVICE_UNAVAILABLE, "session_stopped"); }
        let body = tokio::select! {
            biased;
            _ = probe.cancel().cancelled() => return failure(StatusCode::SERVICE_UNAVAILABLE, "session_stopped"),
            body = tokio::time::timeout(Duration::from_secs(5), to_bytes(request.into_body(), 8192)) => body,
        };
        let Ok(Ok(body)) = body else { return failure(StatusCode::BAD_REQUEST, "invalid_request_body"); };
        let Ok(params) = serde_json::from_slice::<Params>(&body) else { return failure(StatusCode::BAD_REQUEST, "invalid_service_parameters"); };
        if params.names.is_empty() || params.names.len() > TARGETS.len() || params.timeout > 60_000 || params.proxy_name.len() > 4096
            || params.names.iter().enumerate().any(|(index, name)| !TARGETS.contains(&name.as_str()) || params.names[..index].contains(name))
        { return failure(StatusCode::BAD_REQUEST, "invalid_service_targets_or_budget"); }
        let timeout = if params.timeout <= 0 { DEFAULT_TIMEOUT } else { Duration::from_millis(params.timeout as u64) };
        let deadline = Instant::now() + timeout * 6;
        let Ok(stamp) = probe.stamp() else { return failure(StatusCode::CONFLICT, "service_check_superseded"); };
        let entries = {
            let mut claims = self.claims.lock().unwrap();
            params.names.iter().map(|name| {
                let token = Arc::new(probe.cancel().child_token());
                if let Some(previous) = claims.insert(name.clone(), token.clone()) { previous.cancel(); }
                (name.clone(), token)
            }).collect()
        };
        let claims = Claims { owner: self, entries };
        let mut pending = FuturesUnordered::new();
        for (index, (name, token)) in claims.entries.iter().enumerate() {
            let proxy_name = &params.proxy_name;
            pending.push(async move { (index, self.check(probe, name, proxy_name, timeout, deadline, token, stamp).await) });
        }
        let mut items = vec![Item::default(); params.names.len()];
        let mut superseded = 0;
        while let Some((index, result)) = pending.next().await {
            if probe.cancel().is_cancelled() { return failure(StatusCode::SERVICE_UNAVAILABLE, "session_stopped"); }
            match result {
                Ok(item) => items[index] = item,
                Err(_) => {
                    superseded += 1;
                    let Ok(checked_at) = timestamp() else { return failure(StatusCode::SERVICE_UNAVAILABLE, "clock_unavailable"); };
                    items[index] = Item { name: params.names[index].clone(), status: "failed".into(), checked_at,
                        core_epoch: stamp.0, route_version: stamp.1, ..Item::default() };
                }
            }
        }
        if superseded == items.len() || probe.stamp().ok() != Some(stamp) {
            return failure(StatusCode::CONFLICT, "service_check_superseded");
        }
        ([(axum::http::header::CACHE_CONTROL, "no-store")], axum::Json(items)).into_response()
    }

    async fn check(&self, probe: &Arc<Probe>, name: &str, proxy_name: &str, timeout: Duration, deadline: Instant, cancel: &CancellationToken, stamp: (u64, u64)) -> io::Result<Item> {
        let work = async {
            let slot = tokio::time::timeout_at(deadline, self.slots.acquire()).await;
            let env = Env { control: probe.control.clone(), hits: Hits::default(), proxy_name, timeout, deadline,
                #[cfg(test)] fixture_base: self.fixture_base.read().unwrap().clone() };
            let permit = slot.ok().transpose().map_err(io::Error::other)?;
            let mut item = if permit.is_some() { evaluate(name, &env).await }
                else { Item { status: "failed".into(), ..Item::default() } };
            if item.status == "timeout" && deadline.saturating_duration_since(Instant::now()) >= timeout {
                item = evaluate(name, &env).await;
            }
            if cancel.is_cancelled() || stamp != probe.stamp()?
            { return Err(io::Error::new(io::ErrorKind::Interrupted, "service route changed")); }
            item.hits = env.hits.values();
            item.name = name.into();
            item.checked_at = timestamp()?;
            (item.core_epoch, item.route_version) = stamp;
            Ok(item)
        };
        tokio::select! {
            biased;
            _ = cancel.cancelled() => Err(io::Error::new(io::ErrorKind::Interrupted, "service check cancelled")),
            result = work => result,
        }
    }
}

fn timestamp() -> io::Result<u64> {
    Ok(SystemTime::now().duration_since(UNIX_EPOCH).map_err(io::Error::other)?.as_millis() as u64)
}

async fn evaluate(name: &str, env: &Env<'_>) -> Item {
    std::panic::AssertUnwindSafe(rules::check(name, env)).catch_unwind().await
        .unwrap_or_else(|_| Item { status: "failed".into(), ..Item::default() })
}

struct Env<'a> {
    control: Arc<RouteControl>,
    hits: Hits,
    proxy_name: &'a str,
    timeout: Duration,
    deadline: Instant,
    #[cfg(test)]
    fixture_base: Option<String>,
}

impl Env<'_> {
    async fn get(&self, url: &str, max_body: usize) -> (Item, Option<ProbeResult>) {
        self.send(ProbeRequest::get(url, max_body, self.timeout)).await
    }

    async fn scan(&self, url: &str, until: fn(&str) -> bool) -> (Item, Option<ProbeResult>) {
        let mut request = ProbeRequest::get(url, 8 * 1024 * 1024, self.timeout);
        request.until = Some(until);
        self.send(request).await
    }

    async fn send(&self, mut request: ProbeRequest) -> (Item, Option<ProbeResult>) {
        request.timeout = self.timeout;
        #[cfg(test)]
        if let Some(base) = &self.fixture_base {
            let uri: axum::http::Uri = request.url.parse().unwrap();
            request.headers.insert("x-aerove-fixture-url", request.url.parse().unwrap());
            request.url = format!("{base}{}", uri.path_and_query().map(|path| path.as_str()).unwrap_or("/"));
        }
        let result = async {
            if Instant::now() >= self.deadline { return Err(io::Error::other("service sweep expired")); }
            tokio::time::timeout_at(self.deadline, run_probe(self.control.clone(), self.proxy_name.to_owned(), self.hits.clone(), request)).await
                .map_err(|error| io::Error::new(io::ErrorKind::TimedOut, error))?
        }.await;
        match result {
            Ok(result) => (Item { delay: result.delay, chains: result.chains.clone(), ..Item::default() }, Some(result)),
            Err(error) => (Item { status: if error.kind() == io::ErrorKind::TimedOut { "timeout" } else { "failed" }.into(), ..Item::default() }, None),
        }
    }
}
