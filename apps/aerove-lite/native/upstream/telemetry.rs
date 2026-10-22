use std::{sync::{Arc, Mutex}, time::Duration};

use axum::{
    Router,
    extract::{WebSocketUpgrade, ws::{Message, WebSocket}},
    http::StatusCode,
    response::IntoResponse,
    routing::get,
};
use serde::Serialize;
use tokio::{sync::{Notify, watch}, task::JoinHandle, time::Instant};
use tokio_util::sync::CancellationToken;

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
}

pub(crate) struct Bus {
    traffic: watch::Sender<Traffic>,
    groups: watch::Sender<u64>,
    cancel: CancellationToken,
    delay: Mutex<Delay>,
    delay_updated: Notify,
}

#[derive(Default)]
struct Delay {
    deadline: Option<Instant>,
    task: Option<JoinHandle<()>>,
}

impl Bus {
    pub(crate) fn new(cancel: CancellationToken) -> Arc<Self> {
        Arc::new(Self {
            traffic: watch::channel(Traffic::default()).0,
            groups: watch::channel(0).0,
            cancel,
            delay: Mutex::new(Delay::default()),
            delay_updated: Notify::new(),
        })
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

pub(crate) fn routes(cancel: CancellationToken) -> Router {
    // Capture this runtime's bus; a surviving socket must never read its successor.
    let bus = source();
    Router::new().route("/aerove/telemetry", get(move |upgrade: WebSocketUpgrade| {
        let bus = bus.clone();
        let cancel = cancel.clone();
        async move {
            let Some(bus) = bus.filter(|bus| !bus.cancel.is_cancelled() && !cancel.is_cancelled()) else {
                return StatusCode::SERVICE_UNAVAILABLE.into_response();
            };
            upgrade.max_message_size(1024).max_frame_size(1024)
                .on_upgrade(move |socket| stream(socket, bus, cancel))
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

async fn stream(mut socket: WebSocket, bus: Arc<Bus>, cancel: CancellationToken) {
    let (mut traffic, mut groups) = bus.subscribe();
    let sample = *traffic.borrow_and_update();
    let revision = *groups.borrow_and_update();
    if !send(&mut socket, Frame::Traffic(sample), &bus, &cancel).await
        || !send(&mut socket, Frame::GroupsChanged { revision }, &bus, &cancel).await
    {
        return;
    }
    loop {
        let frame = tokio::select! {
            _ = cancel.cancelled() => break,
            _ = bus.cancel.cancelled() => break,
            message = socket.recv() => {
                match message {
                    Some(Ok(Message::Ping(_) | Message::Pong(_))) => continue,
                    _ => break,
                }
            }
            changed = groups.changed() => {
                if changed.is_err() { break; }
                Frame::GroupsChanged { revision: *groups.borrow_and_update() }
            }
            changed = traffic.changed() => {
                if changed.is_err() { break; }
                Frame::Traffic(*traffic.borrow_and_update())
            }
        };
        if !send(&mut socket, frame, &bus, &cancel).await { break; }
    }
}
