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
    entries: HashMap<String, Arc<CancellationToken>>,
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
        // The known ID set bounds distinct claims; the unchanged 8 KiB body
        // budget bounds original-order measurements, including duplicates.
        if params.names.is_empty() || params.timeout > 60_000 || params.proxy_name.len() > 4096
            || params.names.iter().any(|name| !TARGETS.contains(&name.as_str()))
        { return failure(StatusCode::BAD_REQUEST, "invalid_service_targets_or_budget"); }
        let timeout = if params.timeout <= 0 { DEFAULT_TIMEOUT } else { Duration::from_millis(params.timeout as u64) };
        let deadline = Instant::now() + timeout * 6;
        let Ok(stamp) = probe.stamp() else { return failure(StatusCode::CONFLICT, "service_check_superseded"); };
        let entries = {
            let mut claims = self.claims.lock().unwrap();
            let mut entries = HashMap::with_capacity(params.names.len().min(TARGETS.len()));
            for name in &params.names {
                if !entries.contains_key(name) {
                    let token = Arc::new(probe.cancel().child_token());
                    if let Some(previous) = claims.insert(name.clone(), token.clone()) { previous.cancel(); }
                    entries.insert(name.clone(), token);
                }
            }
            entries
        };
        let claims = Claims { owner: self, entries };
        let mut pending = FuturesUnordered::new();
        for (index, name) in params.names.iter().enumerate() {
            let token = &claims.entries[name];
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{api::CoreApi, providers::{self, ControlPlane, OwnedLoadScope}};
    use axum::Router;
    use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};

    const SECRET: &str = "service-duplicates-private-secret-0001";

    struct Fixture {
        api: Arc<CoreApi>, scope: Arc<OwnedLoadScope>, address: std::net::SocketAddr,
        server: tokio::task::JoinHandle<Result<(), crate::api::ApiError>>, events: tokio::task::JoinHandle<()>,
    }

    impl Fixture {
        async fn new() -> Self {
            let cancel = CancellationToken::new();
            let plane = ControlPlane::new(91, cancel.clone()).unwrap();
            let scope = OwnedLoadScope::new();
            let config = providers::load_with_cache("strict: true\nproxies:\n - {name: A, type: direct}\n - {name: B, type: direct}\nrules: ['MATCH,A']\n",
                &scope, &cancel, None).await.unwrap();
            let tunnel = meow_tunnel::Tunnel::new_with_slot(config.dns.resolver_slot.clone());
            tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
            let api = Arc::new(CoreApi::assemble_with_plane(&config, tunnel.clone(), "127.0.0.1:12345".parse().unwrap(),
                SECRET, None, Router::new(), plane).unwrap());
            tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
            let events = api.control.activate_groups(&scope).unwrap();
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let serving = api.clone();
            let server = tokio::spawn(async move { serving.serve_private(listener, cancel).await });
            Self { api, scope, address, server, events }
        }

        fn origin(&self, address: std::net::SocketAddr) {
            *self.api.probes.services.fixture_base.write().unwrap() = Some(format!("http://{address}"));
        }

        async fn close(self) {
            self.api.cancel(); self.scope.cancel(); self.scope.close().await.unwrap();
            if let Err(error) = self.events.await { assert!(error.is_cancelled()); }
            self.api.join().await; self.server.await.unwrap().unwrap();
        }
    }

    fn parameters(names: &[&str], proxy: &str) -> String {
        serde_json::json!({"names":names,"proxy-name":proxy,"timeout":2000}).to_string()
    }

    async fn post(address: std::net::SocketAddr, body: String) -> String {
        let mut socket = TcpStream::connect(address).await.unwrap();
        socket.write_all(format!("POST /aerove/service-check HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {SECRET}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).as_bytes()).await.unwrap();
        let mut response = String::new();
        tokio::time::timeout(Duration::from_secs(10), socket.read_to_string(&mut response)).await.unwrap().unwrap();
        response
    }

    fn rows(response: &str) -> Vec<serde_json::Value> {
        assert!(response.starts_with("HTTP/1.1 200"), "{response}");
        serde_json::from_str::<serde_json::Value>(response.split_once("\r\n\r\n").unwrap().1).unwrap()
            .as_array().unwrap().clone()
    }

    async fn accept(listener: &TcpListener) -> (TcpStream, String) {
        tokio::time::timeout(Duration::from_secs(5), async {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut head = Vec::new();
            while !head.ends_with(b"\r\n\r\n") { head.push(socket.read_u8().await.unwrap()); assert!(head.len() < 8192); }
            (socket, String::from_utf8(head).unwrap())
        }).await.unwrap()
    }

    fn google(head: &str) -> bool {
        head.starts_with("GET /generate_204 HTTP/1.1\r\n")
            && head.to_ascii_lowercase().contains("x-aerove-fixture-url: https://www.google.com/generate_204\r\n")
    }

    async fn reply(socket: &mut TcpStream, status: &str) {
        socket.write_all(format!("HTTP/1.1 {status}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    }

    async fn eof(socket: &mut TcpStream) {
        let mut bytes = Vec::new();
        tokio::time::timeout(Duration::from_secs(5), socket.read_to_end(&mut bytes)).await.unwrap().unwrap();
        assert!(bytes.is_empty());
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn duplicate_items_make_independent_real_measurements_and_keep_original_positions() {
        let fixture = Fixture::new().await;
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); let host = origin.local_addr().unwrap(); fixture.origin(host);
        let request = tokio::spawn(post(fixture.address, parameters(&["google", "github", "google"], "A")));
        let mut googles = Vec::new(); let mut github = None;
        for _ in 0..3 {
            let (socket, head) = accept(&origin).await;
            if google(&head) { googles.push(socket); } else { assert!(head.starts_with("GET / HTTP/1.1\r\n")); assert!(github.is_none()); github = Some(socket); }
        }
        assert_eq!(googles.len(), 2);
        {
            let claims = fixture.api.probes.services.claims.lock().unwrap();
            assert_eq!(claims.len(), 2); assert!(!claims["google"].is_cancelled()); assert!(!claims["github"].is_cancelled());
        }
        reply(&mut googles[1], "204 No Content").await;
        reply(github.as_mut().unwrap(), "500 Failure").await;
        reply(&mut googles[0], "403 Forbidden").await;
        let values = rows(&request.await.unwrap());
        assert_eq!(values.iter().map(|row| row["name"].as_str().unwrap()).collect::<Vec<_>>(), vec!["google", "github", "google"]);
        assert_eq!(values[1]["status"], "unavailable");
        let mut statuses = vec![values[0]["status"].as_str().unwrap(), values[2]["status"].as_str().unwrap()]; statuses.sort();
        assert_eq!(statuses, vec!["available", "restricted"]);
        for row in &values {
            assert_eq!(row["chains"][0], "A"); assert_eq!(row["core-epoch"], 91); assert_eq!(row["route-version"], 0);
            assert!(row["checked-at"].as_u64().unwrap() > 0); assert_eq!(row["hits"][0]["host"], host.to_string());
        }
        fixture.close().await;
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn newer_request_cancels_active_and_queued_old_duplicates_and_old_cleanup_cannot_cancel_it() {
        let fixture = Fixture::new().await;
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); fixture.origin(origin.local_addr().unwrap());
        let old = tokio::spawn(post(fixture.address, parameters(&["google", "google", "google", "google", "google", "github"], "A")));
        let mut old_sockets = Vec::new();
        for _ in 0..4 { let (socket, head) = accept(&origin).await; assert!(google(&head)); old_sockets.push(socket); }
        let (old_google, old_github) = {
            let claims = fixture.api.probes.services.claims.lock().unwrap(); assert_eq!(claims.len(), 2);
            (claims["google"].clone(), claims["github"].clone())
        };
        assert!(!old_google.is_cancelled()); assert!(!old_github.is_cancelled());
        let newer = tokio::spawn(post(fixture.address, parameters(&["google"], "B")));
        let mut new_google = None; let mut github = None;
        for _ in 0..2 {
            let (socket, head) = accept(&origin).await;
            if google(&head) { assert!(new_google.is_none()); new_google = Some(socket); }
            else { assert!(head.starts_with("GET / HTTP/1.1\r\n")); assert!(github.is_none()); github = Some(socket); }
        }
        assert!(old_google.is_cancelled()); assert!(!old_github.is_cancelled());
        for socket in &mut old_sockets { eof(socket).await; }
        let new_token = fixture.api.probes.services.claims.lock().unwrap()["google"].clone();
        assert!(!new_token.is_cancelled()); assert!(!Arc::ptr_eq(&old_google, &new_token));
        reply(github.as_mut().unwrap(), "200 OK").await;
        let old_values = rows(&old.await.unwrap()); assert_eq!(old_values.len(), 6);
        for row in &old_values[..5] { assert_eq!(row["name"], "google"); assert_eq!(row["status"], "failed"); }
        assert_eq!(old_values[5]["name"], "github"); assert_eq!(old_values[5]["status"], "available");
        assert!(!newer.is_finished()); assert!(!new_token.is_cancelled());
        assert!(Arc::ptr_eq(&fixture.api.probes.services.claims.lock().unwrap()["google"], &new_token));
        assert!(tokio::time::timeout(Duration::from_millis(40), origin.accept()).await.is_err(), "old queued duplicate opened a late request");
        reply(new_google.as_mut().unwrap(), "200 OK").await;
        let new_values = rows(&newer.await.unwrap()); assert_eq!(new_values.len(), 1);
        assert_eq!(new_values[0]["status"], "available"); assert_eq!(new_values[0]["chains"][0], "B");
        assert_eq!(new_values[0]["hits"][0]["proxy"], "B");
        fixture.close().await;
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn more_than_thirteen_original_items_fit_the_same_body_budget_and_all_are_measured() {
        let fixture = Fixture::new().await;
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); fixture.origin(origin.local_addr().unwrap());
        let names = (0..15).map(|index| if index % 2 == 0 { "google" } else { "github" }).collect::<Vec<_>>();
        let body = parameters(&names, "A"); assert!(body.len() <= 8192);
        let responding = tokio::spawn(async move {
            let mut counts = (0, 0);
            for _ in 0..15 {
                let (mut socket, head) = accept(&origin).await;
                if google(&head) { counts.0 += 1; reply(&mut socket, "204 No Content").await; }
                else { counts.1 += 1; assert!(head.starts_with("GET / HTTP/1.1\r\n")); reply(&mut socket, "403 Forbidden").await; }
            }
            counts
        });
        let values = rows(&post(fixture.address, body).await); assert_eq!(values.len(), names.len());
        for (row, name) in values.iter().zip(&names) {
            assert_eq!(row["name"], *name); assert_eq!(row["status"], if *name == "google" { "available" } else { "restricted" });
        }
        assert_eq!(responding.await.unwrap(), (8, 7));
        fixture.close().await;
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn original_body_proxy_and_timeout_limits_reject_without_stealing_a_live_claim() {
        let fixture = Fixture::new().await;
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap(); fixture.origin(origin.local_addr().unwrap());
        let live = tokio::spawn(post(fixture.address, parameters(&["google"], "A")));
        let (mut peer, head) = accept(&origin).await; assert!(google(&head));
        let token = fixture.api.probes.services.claims.lock().unwrap()["google"].clone();
        let mut oversized = parameters(&vec!["google"; 900], "B"); assert!(oversized.len() <= 8192);
        oversized.push_str(&" ".repeat(8193 - oversized.len())); assert_eq!(oversized.len(), 8193);
        let too_slow = serde_json::json!({"names":["google"],"proxy-name":"B","timeout":60001}).to_string();
        let too_long = parameters(&["google"], &"B".repeat(4097));
        for (body, error) in [(oversized, "invalid_request_body"), (too_slow, "invalid_service_targets_or_budget"),
            (too_long, "invalid_service_targets_or_budget"), (parameters(&["google", "unknown"], "B"), "invalid_service_targets_or_budget")] {
            let response = post(fixture.address, body).await; assert!(response.starts_with("HTTP/1.1 400"), "{response}");
            let value: serde_json::Value = serde_json::from_str(response.split_once("\r\n\r\n").unwrap().1).unwrap();
            assert_eq!(value["error"], error); assert!(!token.is_cancelled());
            assert!(Arc::ptr_eq(&fixture.api.probes.services.claims.lock().unwrap()["google"], &token));
        }
        assert!(!live.is_finished()); assert!(tokio::time::timeout(Duration::from_millis(40), origin.accept()).await.is_err());
        reply(&mut peer, "204 No Content").await;
        assert_eq!(rows(&live.await.unwrap())[0]["status"], "available");
        fixture.close().await;
    }
}
