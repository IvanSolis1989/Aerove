//! Same-engine API assembly. Not connected to the production Kotlin facade.
use axum::{
    Extension, Router,
    extract::{Query, Request, State},
    http::{HeaderValue, Method, StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
};
use hyper_util::{rt::TokioIo, service::TowerToHyperService};
use meow_api::{lifecycle::ApiLifecycle, routes::AppState};
use meow_tunnel::Tunnel;
use parking_lot::RwLock;
use std::{collections::HashMap, net::SocketAddr, sync::{Arc, OnceLock, Weak}};
use subtle::ConstantTimeEq;
use tokio::{net::{TcpListener, TcpStream}, sync::broadcast, task::JoinSet};
use tokio_util::sync::CancellationToken;
use tracing_subscriber::{Layer, layer::Context, prelude::*};

static LOGGER: OnceLock<Result<(), ()>> = OnceLock::new();
static LOG_SINK: OnceLock<RwLock<Weak<LogSink>>> = OnceLock::new();

struct LogSink {
    layer: meow_api::log_stream::LogBroadcastLayer,
    minimum: meow_api::log_stream::LogLevel,
}

struct RuntimeLogLayer;

impl<S: tracing::Subscriber> Layer<S> for RuntimeLogLayer {
    fn on_event(&self, event: &tracing::Event<'_>, context: Context<'_, S>) {
        if event.metadata().target() == "tokio::task::waker" { return; }
        let sink = LOG_SINK.get().and_then(|sink| sink.read().upgrade());
        let Some(sink) = sink else { return; };
        use meow_api::log_stream::LogLevel;
        let level = match *event.metadata().level() {
            tracing::Level::TRACE | tracing::Level::DEBUG => LogLevel::Debug,
            tracing::Level::INFO => LogLevel::Info,
            tracing::Level::WARN => LogLevel::Warning,
            tracing::Level::ERROR => LogLevel::Error,
        };
        if level >= sink.minimum { sink.layer.on_event(event, context); }
    }
}

#[derive(Debug)]
pub struct ApiError(pub &'static str);

pub struct CoreApi {
    pub state: Arc<AppState>,
    pub control: Arc<crate::control::RouteControl>,
    private_scope: ApiLifecycle,
    user_scope: ApiLifecycle,
    private_router: Router,
    user_router: Option<Router>,
    log_sink: Arc<LogSink>,
    provider_tasks_started: std::sync::atomic::AtomicBool,
}

impl Drop for CoreApi {
    fn drop(&mut self) { self.cancel(); }
}

impl CoreApi {
    pub fn assemble(
        config: &meow_config::Config,
        tunnel: Tunnel,
        actual_mixed: SocketAddr,
        private_secret: &str,
        user_secret: Option<&str>,
        aerove_router: Router,
    ) -> Result<Self, ApiError> {
        Self::assemble_with_epoch(config, tunnel, actual_mixed, private_secret, user_secret,
            aerove_router, 1, CancellationToken::new())
    }

    pub fn assemble_with_epoch(
        config: &meow_config::Config, tunnel: Tunnel, actual_mixed: SocketAddr,
        private_secret: &str, user_secret: Option<&str>, aerove_router: Router,
        epoch: u64, cancel: CancellationToken,
    ) -> Result<Self, ApiError> {
        let plane = crate::providers::ControlPlane::new(epoch, cancel).map_err(ApiError)?;
        Self::assemble_with_plane(config, tunnel, actual_mixed, private_secret, user_secret, aerove_router, plane)
    }

    pub fn assemble_with_plane(
        config: &meow_config::Config, tunnel: Tunnel, actual_mixed: SocketAddr,
        private_secret: &str, user_secret: Option<&str>, aerove_router: Router,
        plane: Arc<crate::providers::ControlPlane>,
    ) -> Result<Self, ApiError> {
        if private_secret.len() < 32 || private_secret.len() > 512 || !private_secret.is_ascii()
            || private_secret.bytes().any(|c| c.is_ascii_control()) {
            return Err(ApiError("invalidPrivateSecret"));
        }
        let mut raw = config.raw.clone();
        raw.mixed_port = Some(actual_mixed.port());
        raw.bind_address = Some(actual_mixed.ip().to_string());
        let listeners = meow_config::resolve_named_listeners(&raw)
            .map_err(|_| ApiError("invalidManagedListener"))?;
        let (log_tx, _) = broadcast::channel(1024);
        let state = Arc::new(AppState {
            tunnel,
            secret: Some(private_secret.into()),
            config_path: None,
            raw_config: Arc::new(RwLock::new(raw)),
            log_tx,
            proxy_providers: Arc::new(config.proxy_providers.iter()
                .map(|(name, provider)| (name.clone(), provider.clone())).collect()),
            provider_dialer_registry: config.provider_dialer_registry.clone(),
            rule_providers: Arc::new(RwLock::new(config.rule_providers.clone())),
            rule_provider_refresh: Arc::new(Default::default()),
            proxy_provider_refresh: Arc::new(Default::default()),
            listeners,
            external_ui: None,
            traffic_feed: Default::default(),
            dns_server: Arc::new(RwLock::new(None)),
        });
        let control = crate::control::RouteControl::with_plane(state.clone(), plane.clone());
        let aerove_router = aerove_router.merge(crate::control::router(control.clone()));
        let authorization = HeaderValue::from_str(&format!("Bearer {private_secret}"))
            .map_err(|_| ApiError("invalidPrivateSecret"))?;
        let official = meow_api::routes::create_router(state.clone());
        let private_scope = ApiLifecycle::default();
        let user_scope = ApiLifecycle::default();
        let private_router = official.clone().merge(aerove_router)
            .layer(Extension(private_scope.clone()))
            .layer(middleware::from_fn_with_state(Boundary {
                user: false, secret: Arc::from(private_secret), authorization: authorization.clone(), control: control.clone(), state: state.clone(),
            }, boundary));
        let user_router = user_secret.map(|secret| {
            if secret.is_empty() || secret.encode_utf16().count() > 512 {
                return Err(ApiError("invalidUserSecret"));
            }
            Ok(official.clone().layer(Extension(user_scope.clone()))
                .layer(middleware::from_fn_with_state(Boundary {
                    user: true, secret: Arc::from(secret), authorization: authorization.clone(), control: control.clone(), state: state.clone(),
                }, boundary)))
        }).transpose()?;
        if !meow_api::log_stream::is_valid_log_level(&config.general.log_level) {
            return Err(ApiError("invalidLogLevel"));
        }
        let log_sink = Arc::new(LogSink {
            layer: meow_api::log_stream::LogBroadcastLayer { tx: state.log_tx.clone() },
            minimum: meow_api::log_stream::parse_log_level(&config.general.log_level),
        });
        private_scope.spawn(async move { plane.run_events().await; });
        Ok(Self { state, control, private_scope, user_scope, private_router, user_router, log_sink, provider_tasks_started: std::sync::atomic::AtomicBool::new(false) })
    }

    pub fn activate_providers(&self) -> Result<(), ApiError> {
        let providers = self.state.proxy_providers.iter()
            .map(|entry| entry.value().clone()).collect::<Vec<_>>();
        let mut owner = None;
        for provider in &providers {
            let scope = provider.owned_scope().ok_or(ApiError("providerOwnerMissing"))?;
            if owner.as_ref().is_some_and(|prior| !Arc::ptr_eq(prior, &scope)) {
                return Err(ApiError("providerOwnerMismatch"));
            }
            owner = Some(scope);
        }
        use std::sync::atomic::Ordering;
        if self.provider_tasks_started.compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire).is_err() {
            return Err(ApiError("providersAlreadyActivated"));
        }
        if self.control.plane.activate(&providers.iter().map(|provider| provider.provider_identity().clone()).collect::<Vec<_>>()).is_err() {
            self.provider_tasks_started.store(false, Ordering::Release);
            return Err(ApiError("providerActivationFailed"));
        }
        if let Some(owner) = owner {
            let registry = self.state.proxy_providers.clone();
            let supervisor = self.state.proxy_provider_refresh.clone();
            let raws = self.state.raw_config.read().proxy_providers.clone();
            // The API owner leases registration; each resulting task is registered
            // in the same Session load scope and wraps its captured network context.
            self.private_scope.spawn(async move {
                owner.run(async move {
                    meow_config::proxy_provider::spawn_deferred_initial_fetches(&registry);
                    supervisor.reconcile(&registry, raws.as_ref());
                }).await;
            });
        }
        Ok(())
    }

    pub fn private_router(&self) -> Router { self.private_router.clone() }
    pub fn user_router(&self) -> Option<Router> { self.user_router.clone() }

    pub fn log_layer(&self) -> meow_api::log_stream::LogBroadcastLayer {
        meow_api::log_stream::LogBroadcastLayer { tx: self.state.log_tx.clone() }
    }

    pub fn install_log_sink(&self) -> Result<(), ApiError> {
        if LOGGER.get_or_init(|| tracing_subscriber::registry().with(RuntimeLogLayer)
            .try_init().map_err(|_| ())).is_err() {
            return Err(ApiError("logSubscriberAlreadyOwned"));
        }
        *LOG_SINK.get_or_init(|| RwLock::new(Weak::new())).write() = Arc::downgrade(&self.log_sink);
        Ok(())
    }

    pub async fn serve_private(&self, listener: TcpListener, cancel: CancellationToken) -> Result<(), ApiError> {
        serve(listener, self.private_router(), self.private_scope.clone(), cancel).await
    }

    pub async fn serve_user(&self, listener: TcpListener, cancel: CancellationToken) -> Result<(), ApiError> {
        let router = self.user_router().ok_or(ApiError("userControllerDisabled"))?;
        serve(listener, router, self.user_scope.clone(), cancel).await
    }

    pub fn cancel(&self) {
        self.control.cancel();
        self.private_scope.cancel();
        self.user_scope.cancel();
        self.state.traffic_feed.lifecycle.cancel();
    }

    /// Caller first stops admission and joins its owned HTTP connection tasks.
    pub async fn join(&self) {
        self.private_scope.join().await;
        self.user_scope.join().await;
        self.state.traffic_feed.lifecycle.join().await;
    }

    pub fn active_tasks(&self) -> usize {
        self.private_scope.active_tasks() + self.user_scope.active_tasks()
            + self.state.traffic_feed.lifecycle.active_tasks()
    }
}

/// Upgrade callbacks are leased by the patched handlers before Hyper hands over IO.
pub async fn serve_connection(stream: TcpStream, router: Router) {
    let service = TowerToHyperService::new(router);
    let _ = hyper::server::conn::http1::Builder::new()
        .serve_connection(TokioIo::new(stream), service).with_upgrades().await;
}

async fn serve(listener: TcpListener, router: Router, scope: ApiLifecycle,
    cancel: CancellationToken) -> Result<(), ApiError> {
    let mut children = JoinSet::new();
    let result = loop {
        tokio::select! {
            biased;
            _ = cancel.cancelled() => break Ok(()),
            done = children.join_next(), if !children.is_empty() => {
                if done.is_some_and(|r| r.is_err()) { break Err(ApiError("apiConnectionPanicked")); }
            }
            accepted = listener.accept(), if children.len() < 256 => {
                let (stream, _) = match accepted {
                    Ok(accepted) => accepted,
                    Err(_) => break Err(ApiError("apiAcceptFailed")),
                };
                children.spawn(serve_connection(stream, router.clone()));
            }
        }
    };
    drop(listener);
    scope.cancel();
    children.abort_all();
    while children.join_next().await.is_some() {}
    scope.join().await;
    result
}

#[derive(Clone)]
struct Boundary {
    user: bool,
    secret: Arc<str>,
    authorization: HeaderValue,
    control: Arc<crate::control::RouteControl>,
    state: Arc<AppState>,
}

async fn boundary(State(policy): State<Boundary>, mut request: Request, next: Next) -> Response {
    let path = request.uri().path();
    let aerove = path.split('/').nth(1) == Some("aerove");
    if policy.user && aerove { return StatusCode::NOT_FOUND.into_response(); }
    if request.method() == Method::OPTIONS {
        return if policy.user { user_preflight(&request) }
            else { StatusCode::NOT_FOUND.into_response() };
    }
    if !aerove && !allowed(request.method(), path) {
        return StatusCode::NOT_FOUND.into_response();
    }
    let websocket = request.headers().get(header::UPGRADE)
        .is_some_and(|h| h.as_bytes().eq_ignore_ascii_case(b"websocket"))
        && request.headers().get(header::CONNECTION).and_then(|h| h.to_str().ok())
            .is_some_and(|h| h.split(',').any(|v| v.trim().eq_ignore_ascii_case("upgrade")));
    let query = if websocket {
        Query::<HashMap<String, String>>::try_from_uri(request.uri()).ok()
    } else { None };
    let bearer = request.headers().get(header::AUTHORIZATION)
        .and_then(|h| h.as_bytes().strip_prefix(b"Bearer "));
    let provided = bearer.or_else(|| query.as_ref().and_then(|q| q.get("token")).map(String::as_bytes));
    let authorized = provided.is_some_and(|p| p.len() == policy.secret.len()
        && bool::from(p.ct_eq(policy.secret.as_bytes())));
    if !authorized { return StatusCode::UNAUTHORIZED.into_response(); }
    // Authentication has completed at the outer boundary; only this internal hop uses its token.
    request.headers_mut().insert(header::AUTHORIZATION, policy.authorization.clone());
    let origin = request.headers().contains_key(header::ORIGIN);
    let mut response = if public_control_route(request.method(), request.uri().path()) {
        public_control(&policy, request).await
    } else { next.run(request).await };
    if policy.user && origin {
        response.headers_mut().insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, HeaderValue::from_static("*"));
    }
    if !policy.user {
        for key in ["access-control-allow-origin", "access-control-allow-credentials",
            "access-control-allow-methods", "access-control-allow-headers"] {
            response.headers_mut().remove(key);
        }
    }
    response
}

fn user_preflight(request: &Request) -> Response {
    if !request.headers().get(header::ORIGIN)
        .is_some_and(|value| !value.as_bytes().is_empty()) {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let Some(method) = request.headers().get(header::ACCESS_CONTROL_REQUEST_METHOD)
        .and_then(|value| Method::from_bytes(value.as_bytes()).ok()) else {
            return StatusCode::BAD_REQUEST.into_response();
        };
    if !allowed(&method, request.uri().path()) { return StatusCode::NOT_FOUND.into_response(); }
    if let Some(headers) = request.headers().get(header::ACCESS_CONTROL_REQUEST_HEADERS) {
        let valid = headers.to_str().ok().is_some_and(|headers| headers.split(',').all(|name| {
            let name = name.trim();
            name.eq_ignore_ascii_case("authorization") || name.eq_ignore_ascii_case("content-type")
        }));
        if !valid { return StatusCode::FORBIDDEN.into_response(); }
    }
    // Standard defaults to wildcard origins, explicit Bearer headers, and a 300s cache.
    let mut response = StatusCode::NO_CONTENT.into_response();
    let headers = response.headers_mut();
    headers.insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, HeaderValue::from_static("*"));
    headers.insert(header::ACCESS_CONTROL_ALLOW_METHODS,
        HeaderValue::from_bytes(method.as_str().as_bytes()).expect("allowlisted HTTP method"));
    headers.insert(header::ACCESS_CONTROL_ALLOW_HEADERS, HeaderValue::from_static("content-type, authorization"));
    headers.insert(header::ACCESS_CONTROL_MAX_AGE, HeaderValue::from_static("300"));
    headers.insert(header::VARY, HeaderValue::from_static("access-control-request-method, access-control-request-headers"));
    if request.headers().get("access-control-request-private-network")
        .is_some_and(|value| value.as_bytes() == b"true") {
        headers.insert("access-control-allow-private-network", HeaderValue::from_static("true"));
    }
    response
}

fn allowed(method: &Method, path: &str) -> bool {
    let segments: Vec<_> = path.split('/').skip(1).collect();
    if public_control_route(method, path) { return true; }
    if method == Method::DELETE {
        return matches!(segments.as_slice(), ["connections"] | ["connections", _]);
    }
    if method != Method::GET { return false; }
    match segments.as_slice() {
        [""] | ["version"] | ["configs"] | ["proxies"] | ["group"] | ["rules"]
        | ["connections"] | ["statistics"] | ["traffic"] | ["logs"] | ["memory"]
        | ["listeners"] | ["dns", "results"] | ["providers", "proxies"]
        | ["providers", "rules"] => true,
        ["proxies", name] | ["group", name] | ["connections", name]
        | ["providers", "proxies", name] | ["providers", "rules", name] => !name.is_empty(),
        _ => false,
    }
}

fn public_control_route(method: &Method, path: &str) -> bool {
    let parts: Vec<_> = path.split('/').skip(1).collect();
    match parts.as_slice() {
        ["configs"] => method == Method::PATCH,
        ["proxies", name] => method == Method::PUT && !name.is_empty(),
        ["providers", "proxies"] => method == Method::GET,
        ["providers", "proxies", name] => !name.is_empty() && (method == Method::GET || method == Method::PUT),
        _ => false,
    }
}

async fn public_control(policy: &Boundary, request: Request) -> Response {
    use axum::{Json, extract::{FromRequestParts, Path}};
    fn failure(code: &str) -> Response {
        let status = match code {
            "providerBusy" => StatusCode::CONFLICT,
            "providerNotFound" => StatusCode::NOT_FOUND,
            "cancelled" => StatusCode::GONE,
            "staleProviderSource" => StatusCode::CONFLICT,
            _ => StatusCode::SERVICE_UNAVAILABLE,
        };
        (status, Json(serde_json::json!({"error":code}))).into_response()
    }
    let method = request.method().clone();
    let path = request.uri().path().to_owned();
    let (mut parts, body) = request.into_parts();
    let name = if path != "/configs" && path != "/providers/proxies" {
        match Path::<String>::from_request_parts(&mut parts, &()).await {
            Ok(Path(name)) => Some(name),
            Err(_) => return StatusCode::BAD_REQUEST.into_response(),
        }
    } else { None };
    if path.starts_with("/providers/proxies") {
        if method == Method::GET {
            return match policy.control.plane.proxy_snapshot(&policy.state, name.as_deref()) {
                Ok(value) => Json(value).into_response(), Err(code) => failure(code),
            };
        }
        let Some(name) = name else { return StatusCode::NOT_FOUND.into_response(); };
        let provider = match policy.state.proxy_providers.get(&name) {
            Some(provider) => provider.value().clone(), None => return failure("providerNotFound"),
        };
        return match provider.refresh_owned().await {
            Ok(()) => StatusCode::NO_CONTENT.into_response(), Err(code) => failure(&code),
        };
    }
    let bytes = match axum::body::to_bytes(body, 4096).await {
        Ok(bytes) => bytes, Err(_) => return StatusCode::PAYLOAD_TOO_LARGE.into_response(),
    };
    if path == "/configs" {
        #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
        struct Mode { mode: meow_common::TunnelMode }
        let body: Mode = match serde_json::from_slice(&bytes) {
            Ok(body) => body, Err(_) => return StatusCode::BAD_REQUEST.into_response(),
        };
        return match policy.control.set_mode(body.mode).await {
            Ok(_) => StatusCode::NO_CONTENT.into_response(), Err(error) => error.response(),
        };
    }
    #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
    struct Selection { name: String }
    let body: Selection = match serde_json::from_slice(&bytes) {
        Ok(body) => body, Err(_) => return StatusCode::BAD_REQUEST.into_response(),
    };
    match policy.control.select(&name.expect("matched proxy name"), &body.name).await {
        Ok(_) => StatusCode::NO_CONTENT.into_response(), Err(error) => error.response(),
    }
}
