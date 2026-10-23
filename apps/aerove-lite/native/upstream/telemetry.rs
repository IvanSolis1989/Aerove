use std::{sync::{Arc, Mutex, atomic::{AtomicU64, Ordering}}, time::Duration};

use axum::{
    Router,
    extract::{Query, WebSocketUpgrade, ws::{Message, WebSocket}},
    http::StatusCode,
    response::IntoResponse,
    routing::get,
};
use serde::{Deserialize, Serialize};
use tokio::{sync::{Notify, watch}, task::JoinHandle, time::Instant};
use tokio_util::sync::CancellationToken;

static NEXT_EPOCH: AtomicU64 = AtomicU64::new(1);

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Traffic {
    pub(crate) up: u64,
    pub(crate) down: u64,
    pub(crate) upload_total: u64,
    pub(crate) download_total: u64,
}

#[derive(Serialize)]
#[serde(tag = "type")]
enum Frame {
    #[serde(rename = "traffic")]
    Traffic(Traffic),
    #[serde(rename = "groupsChanged")]
    GroupsChanged { revision: u64 },
    #[serde(rename = "providersLoaded")]
    ProvidersLoaded { providers: Vec<super::provider_loaded::Loaded> },
    #[serde(rename = "observations")]
    Observations(super::observations::Window),
}

pub(crate) struct Bus {
    pub(crate) providers: super::provider_loaded::Store,
    pub(crate) observations: Arc<super::observations::Collector>,
    traffic: watch::Sender<Traffic>,
    groups: watch::Sender<u64>,
    cancel: CancellationToken,
    statistics: Mutex<(bool, Traffic, Traffic)>,
    http_policy: std::sync::RwLock<Arc<super::http_policy::Policy>>,
    log_level: Mutex<Option<tracing::level_filters::LevelFilter>>,
    delay: Mutex<Delay>,
    delay_updated: Notify,
    route: Mutex<RouteState>,
}

struct RouteState {
    stamp: (u64, u64),
    active: usize,
    pending: bool,
}

pub(crate) struct RouteMutation(Option<Arc<Bus>>);

impl Drop for RouteMutation {
    fn drop(&mut self) {
        if let Some(bus) = &self.0 {
            let mut route = bus.route.lock().unwrap();
            route.active -= 1;
            let notify = route.active == 0 && route.pending;
            if notify { route.pending = false; }
            drop(route);
            if notify { bus.groups_changed(); }
        }
    }
}

pub(crate) fn route_mutation() -> RouteMutation {
    let bus = source();
    if let Some(bus) = &bus { bus.route.lock().unwrap().active += 1; }
    RouteMutation(bus)
}

#[derive(Default)]
struct Delay {
    deadline: Option<Instant>,
    task: Option<JoinHandle<()>>,
}

impl Bus {
    pub(crate) fn new(cancel: CancellationToken) -> Arc<Self> {
        Arc::new(Self {
            providers: super::provider_loaded::Store::new(),
            observations: super::observations::Collector::new(cancel.clone()),
            traffic: watch::channel(Traffic::default()).0,
            groups: watch::channel(0).0,
            cancel,
            http_policy: std::sync::RwLock::new(Arc::new(super::http_policy::Policy::default())),
            statistics: Mutex::new((false,Traffic::default(),Traffic::default())),
            log_level: Mutex::new(None),
            delay: Mutex::new(Delay::default()),
            delay_updated: Notify::new(),
            route: Mutex::new(RouteState { stamp: (NEXT_EPOCH.fetch_add(1, Ordering::SeqCst), 0), active: 0, pending: false }),
        })
    }

    pub(crate) fn cancellation(&self) -> CancellationToken { self.cancel.clone() }

    pub(crate) fn log_level(&self) -> Option<tracing::level_filters::LevelFilter> { *self.log_level.lock().unwrap() }

    pub(crate) fn set_log_level(&self, level: tracing::level_filters::LevelFilter) { if !self.cancel.is_cancelled() { *self.log_level.lock().unwrap() = Some(level); } }

    pub(crate) fn http_policy(&self)->Arc<super::http_policy::Policy> { self.http_policy.read().unwrap().clone() }
    pub(crate) fn set_http_policy(&self,policy:super::http_policy::Policy) { *self.http_policy.write().unwrap()=Arc::new(policy); }
    pub(crate) fn only_statistics_proxy(&self)->bool { self.statistics.lock().unwrap().0 }
    pub(crate) fn set_only_statistics_proxy(&self,only:bool) {
        let mut state=self.statistics.lock().unwrap();state.0=only;
        let sample=if only {state.2}else{state.1};drop(state);self.publish(sample);
    }
    pub(crate) fn publish_statistics(&self,all:Traffic,proxy:Traffic) {
        let mut state=self.statistics.lock().unwrap();state.1=all;state.2=proxy;
        let sample=if state.0{proxy}else{all};drop(state);self.publish(sample);
    }
    pub(crate) fn publish(&self, sample: Traffic) {
        if !self.cancel.is_cancelled() {
            let _ = self.traffic.send_replace(sample);
        }
    }

    pub(crate) fn subscribe(&self) -> (watch::Receiver<Traffic>, watch::Receiver<u64>) {
        (self.traffic.subscribe(), self.groups.subscribe())
    }

    fn groups_changed(&self) {
        if !self.cancel.is_cancelled() {
            self.groups.send_modify(|revision| *revision = revision.wrapping_add(1));
        }
    }

    fn route_changed(&self, structure: bool) {
        let mut route = self.route.lock().unwrap();
        if structure { route.stamp.0 = NEXT_EPOCH.fetch_add(1, Ordering::SeqCst); }
        else { route.stamp.1 = route.stamp.1.wrapping_add(1); }
        route.pending = route.active != 0;
        let notify = !route.pending;
        drop(route);
        if notify { self.groups_changed(); }
    }

    fn delay_changed(self: &Arc<Self>) {
        let mut delay = self.delay.lock().unwrap();
        if self.cancel.is_cancelled() { return; }
        delay.deadline = Some(Instant::now() + Duration::from_secs(5));
        if delay.task.is_none() {
            delay.task = Some(tokio::spawn(self.clone().flush_delays()));
        }
        self.delay_updated.notify_one();
    }

    async fn flush_delays(self: Arc<Self>) {
        loop {
            let deadline = self.delay.lock().unwrap().deadline;
            tokio::select! {
                biased;
                _ = self.cancel.cancelled() => break,
                _ = self.delay_updated.notified() => {},
                _ = async {
                    match deadline {
                        Some(deadline) => tokio::time::sleep_until(deadline).await,
                        None => std::future::pending::<()>().await,
                    }
                } => {
                    let mut delay = self.delay.lock().unwrap();
                    if delay.deadline.is_some_and(|deadline| deadline <= Instant::now()) {
                        delay.deadline = None;
                        self.groups_changed();
                    }
                }
            }
        }
        self.delay.lock().unwrap().deadline = None;
    }

    pub(crate) async fn stop(&self) -> Result<(), tokio::task::JoinError> {
        self.cancel.cancel();
        let task = self.delay.lock().unwrap().task.take();
        if let Some(task) = task { task.await?; }
        Ok(())
    }
}

pub(crate) fn source() -> Option<Arc<Bus>> {
    super::context().ok().filter(|context| !context.cancel.is_cancelled())
        .map(|context| context.telemetry.clone())
}

pub(crate) fn route_stamp() -> std::io::Result<(u64, u64)> {
    let bus = super::context()?.telemetry.clone();
    let route = bus.route.lock().unwrap();
    if bus.cancel.is_cancelled() || route.active != 0 {
        return Err(std::io::Error::new(std::io::ErrorKind::Interrupted, "route is changing"));
    }
    Ok(route.stamp)
}

pub(crate) fn selection_changed() {
    if let Some(bus) = source() {
        bus.route_changed(false);
    }
}

pub(crate) fn structure_changed() {
    if let Some(bus) = source() {
        bus.route_changed(true);
    }
}

pub(crate) fn groups_changed() {
    if let Some(bus) = source() {
        bus.groups_changed();
    }
}

pub(crate) fn delay_changed() {
    if let Some(bus) = source() {
        bus.delay_changed();
    }
}

#[derive(Default, Deserialize)]
struct Options { #[serde(default)] observations: bool }

pub(crate) fn routes(cancel: CancellationToken) -> Router {
    // Capture this runtime's bus; a surviving socket must never read its successor.
    let bus = source();
    Router::new().route("/aerove/telemetry", get(move |upgrade: WebSocketUpgrade, Query(options): Query<Options>| {
        let bus = bus.clone();
        let cancel = cancel.clone();
        async move {
            let Some(bus) = bus.filter(|bus| !bus.cancel.is_cancelled() && !cancel.is_cancelled()) else {
                return StatusCode::SERVICE_UNAVAILABLE.into_response();
            };
            upgrade.max_message_size(1024).max_frame_size(1024)
                .on_upgrade(move |socket| stream(socket, bus, cancel, options.observations))
        }
    }))
}

async fn send(socket: &mut WebSocket, frame: Frame, bus: &Bus, cancel: &CancellationToken) -> bool {
    let Ok(text) = serde_json::to_string(&frame) else { return false; };
    tokio::select! {
        biased;
        _ = cancel.cancelled() => false,
        _ = bus.cancel.cancelled() => false,
        result = tokio::time::timeout(Duration::from_secs(2), socket.send(Message::Text(text.into()))) => {
            matches!(result, Ok(Ok(())))
        }
    }
}

async fn stream(mut socket: WebSocket, bus: Arc<Bus>, cancel: CancellationToken, observe: bool) {
    let lease = observe.then(|| bus.observations.lease());
    let mut observation_cursor = lease.as_ref().map_or(0, |l| l.cursor);
    if let Some(l) = &lease {
        l.collector.resume().await;
        let Some(window) = l.collector.window(l.generation, observation_cursor, true) else { return; };
        if !send(&mut socket, Frame::Observations(window), &bus, &cancel).await { return; }
    }
    let (mut traffic, mut groups) = bus.subscribe();
    let mut providers=bus.providers.subscribe();
    let mut provider_cursor=0u64;
    let mut provider_pending=true;
    let sample = *traffic.borrow_and_update();
    let revision = *groups.borrow_and_update();
    if !send(&mut socket, Frame::Traffic(sample), &bus, &cancel).await
        || !send(&mut socket, Frame::GroupsChanged { revision }, &bus, &cancel).await
    {
        return;
    }
    loop {
        if provider_pending {
            drop(providers.borrow_and_update());
            let Ok(pending_providers)=bus.providers.batch(provider_cursor) else {break;};
            if !pending_providers.is_empty() {
                let next=pending_providers.last().unwrap().revision.parse::<u64>().unwrap();
                if !send(&mut socket,Frame::ProvidersLoaded {providers:pending_providers},&bus,&cancel).await {break;}
                provider_cursor=next;continue;
            }
            provider_pending=false;
        }
        // Register before inspecting the queue: a publication between inspection
        // and await must not strand an event until unrelated traffic arrives.
        let notified = bus.observations.changed.notified();
        tokio::pin!(notified);
        notified.as_mut().enable();
        let pending = if let Some(l) = &lease {
            let Some(window) = l.collector.window(l.generation, observation_cursor, false) else { break; };
            (window.cursor.parse::<u64>().unwrap() > observation_cursor).then_some(window)
        } else { None };
        let has_observation = pending.is_some();
        let frame = tokio::select! {
            biased;
            _ = cancel.cancelled() => break,
            _ = bus.cancel.cancelled() => break,
            message = socket.recv() => {
                match message {
                    Some(Ok(Message::Ping(_) | Message::Pong(_))) => continue,
                    _ => break,
                }
            }
            changed = providers.changed() => {
                if changed.is_err() {break;}
                provider_pending=true;continue;
            }
            changed = groups.changed() => {
                if changed.is_err() { break; }
                Frame::GroupsChanged { revision: *groups.borrow_and_update() }
            }
            changed = traffic.changed() => {
                if changed.is_err() { break; }
                Frame::Traffic(*traffic.borrow_and_update())
            }
            _ = async { if !has_observation { (&mut notified).await; } }, if observe => {
                let Some(window) = pending else { continue; };
                observation_cursor = window.cursor.parse().unwrap();
                Frame::Observations(window)
            }
        };
        if !send(&mut socket, frame, &bus, &cancel).await { break; }
    }
}
