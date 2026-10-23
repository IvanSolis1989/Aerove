//! One session's user listener; the private controller remains the session owner.
use crate::{api::{CoreApi, UserEndpoint}, providers::ControlPlane};
use serde::Serialize;
use meow_config::owned_load::LoadContext;
use std::{io, net::SocketAddr, sync::{Arc, atomic::{AtomicU64, Ordering}}, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}, sync::{Mutex, oneshot, watch}, task::JoinHandle};
use tokio_util::sync::CancellationToken;

pub const ADDRESS: &str = "127.0.0.1:9090";
const IO_LIMIT: Duration = Duration::from_secs(3);
fn error(code: &'static str) -> io::Error { io::Error::other(code) }

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Readback {
    pub enabled: bool, pub address: Option<SocketAddr>, pub authenticated: bool,
    pub private_routes_excluded: bool, pub core_epoch: String,
}

struct State { closed: bool, running: Option<Running>, retired: Vec<Running> }
pub struct Owner {
    api: Arc<CoreApi>, plane: Arc<ControlPlane>, parent: CancellationToken,
    context: Arc<dyn LoadContext>, failure: watch::Sender<Option<&'static str>>,
    address: SocketAddr, private_address: SocketAddr, intents: AtomicU64, intent_gate: parking_lot::Mutex<()>, state: Mutex<State>,
}

impl Owner {
    pub fn new(api: Arc<CoreApi>, private_address: SocketAddr, context: Arc<dyn LoadContext>) -> io::Result<Arc<Self>> {
        Self::at(api, private_address, ADDRESS.parse().expect("fixed loopback address"), context)
    }

    fn at(api: Arc<CoreApi>, private_address: SocketAddr, address: SocketAddr, context: Arc<dyn LoadContext>) -> io::Result<Arc<Self>> {
        if !address.ip().is_loopback() { return Err(error("userControllerRequiresLoopback")); }
        let parent = api.control.plane.cancel.clone();
        let (failure, _) = watch::channel(None);
        Ok(Arc::new(Self { plane: api.control.plane.clone(), api, parent, context, failure, address, private_address,
            intents: AtomicU64::new(0), intent_gate: parking_lot::Mutex::new(()), state: Mutex::new(State { closed: false, running: None, retired: Vec::new() }) }))
    }

    /// Native observes this event before reducing a shared-token cancellation to normal stop.
    pub fn subscribe_failure(&self) -> watch::Receiver<Option<&'static str>> { self.failure.subscribe() }
    pub fn failure(&self) -> Option<&'static str> { *self.failure.borrow() }

    fn check(&self, state: &State, intent: u64) -> io::Result<()> {
        if state.closed || self.parent.is_cancelled() || self.plane.cancel.is_cancelled() { return Err(error("cancelled")); }
        if self.intents.load(Ordering::Acquire) != intent { return Err(error("userControllerSuperseded")); }
        Ok(())
    }

    pub async fn configure(&self, expected_epoch: u64, enabled: bool, input: &str) -> io::Result<Readback> {
        if enabled && self.address.port() != 0 && self.address.port() == self.private_address.port() {
            return Err(error("userControllerAddressConflict"));
        }
        let secret = if enabled {
            let value = input.trim();
            if value.is_empty() || value.encode_utf16().count() > 512
                || axum::http::HeaderValue::from_bytes(format!("Bearer {value}").as_bytes()).is_err() {
                return Err(error("invalidUserSecret"));
            }
            Arc::<str>::from(value)
        } else { Arc::from("") };
        let intent = {
            let _fence = self.intent_gate.lock();
            self.intents.fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| n.checked_add(1))
                .map_err(|_| error("userControllerIntentExhausted"))? + 1
        };
        let mut state = self.state.lock().await;
        self.cleanup_locked(&mut state).await?;
        self.check(&state, intent)?;
        let unchanged = state.running.as_ref().is_some_and(|running| enabled && running.secret == secret && !running.finished())
            || (!enabled && state.running.is_none());
        if unchanged {
            {
                let _fence = self.intent_gate.lock();
                self.check(&state, intent)?;
                self.plane.commit_policy(expected_epoch, false, || ()).map_err(io::Error::other)?;
            }
            let receipt = self.read_locked(&state).await?;
            self.check(&state, intent)?;
            return Ok(receipt);
        }

        let mut candidate = if enabled {
            let endpoint = self.api.user_endpoint(&secret, self.context.clone()).map_err(|_| error("userControllerRouterFailed"))?;
            let socket = match state.running.as_ref() {
                Some(running) => running.socket.try_clone()?,
                None => {
                    let listener = tokio::select! { biased;
                        _ = self.parent.cancelled() => return Err(error("cancelled")),
                        result = TcpListener::bind(self.address) => result.map_err(|_| error("userControllerBindFailed"))?,
                    };
                    listener.into_std()?
                }
            };
            let address = socket.local_addr()?;
            let listener = TcpListener::from_std(socket.try_clone()?)?;
            let stop = self.parent.child_token();
            let stopping = stop.clone(); let serving = endpoint.clone(); let parent = self.parent.clone();
            let failure = self.failure.clone();
            let (activate, activated) = oneshot::channel();
            let task = tokio::spawn(async move {
                let _exit = ServeExit { failure: failure.clone(), parent: parent.clone(), stop: stopping.clone() };
                tokio::select! { biased;
                    _ = stopping.cancelled() => Ok(()),
                    result = activated => {
                        if result.is_err() { return Ok(()); }
                        let result = serving.serve(listener, stopping.clone()).await.map_err(|_| error("userControllerServeFailed"));
                        if !stopping.is_cancelled() { record_failure(&failure, "userControllerExited"); parent.cancel(); return Err(error("userControllerExited")); }
                        result
                    }
                }
            });
            Some(Running { socket, address, secret, stop, endpoint, task: Some(task), activate: Some(activate), business_failure: None, uncertain: None })
        } else { None };
        if let Err(failure) = self.check(&state, intent) {
            if let Some(running) = candidate.take() { state.retired.push(running); self.cleanup_locked(&mut state).await?; }
            return Err(failure);
        }
        let commit = {
            let _fence = self.intent_gate.lock();
            if self.intents.load(Ordering::Acquire) != intent { Err("userControllerSuperseded".to_owned()) }
            else { self.plane.commit_policy(expected_epoch, true, || {
                let retired = std::mem::replace(&mut state.running, candidate.take());
                retired
            }) }
        };
        let retired = match commit {
            Ok((_, retired)) => retired.flatten(),
            Err(failure) => {
                if let Some(running) = candidate.take() { state.retired.push(running); self.cleanup_locked(&mut state).await?; }
                return Err(io::Error::other(failure));
            }
        };
        if let Some(old) = &retired { old.stop.cancel(); old.endpoint.cancel(); }
        if let Some(old) = retired { state.retired.push(old); }
        if let Some(running) = state.running.as_mut() {
            if let Some(start) = running.activate.take() { let _ = start.send(()); }
        }
        self.cleanup_locked(&mut state).await?;
        self.check(&state, intent)?;
        match self.read_locked(&state).await {
            Ok(readback) => { self.check(&state, intent)?; Ok(readback) },
            Err(failure) if failure.to_string() == "staleCoreEpoch" => Err(failure),
            Err(failure) => { state.closed = true; record_failure(&self.failure, "userControllerReadbackFailed"); self.parent.cancel(); Err(failure) }
        }
    }

    pub async fn status(&self) -> io::Result<Readback> {
        let mut state = self.state.lock().await;
        self.cleanup_locked(&mut state).await?;
        self.read_locked(&state).await
    }

    async fn cleanup_locked(&self, state: &mut State) -> io::Result<()> {
        while let Some(old) = state.retired.last_mut() {
            if let Err(failure) = old.close().await {
                record_failure(&self.failure, old.uncertain.unwrap_or("userControllerCleanupFailed"));
                state.closed = true; self.parent.cancel(); return Err(failure);
            }
            if let Some(cause) = old.business_failure { record_failure(&self.failure,cause); self.parent.cancel(); }
            state.retired.pop();
        }
        Ok(())
    }

    async fn read_locked(&self, state: &State) -> io::Result<Readback> {
        if state.closed || self.parent.is_cancelled() || self.plane.cancel.is_cancelled() { return Err(error("cancelled")); }
        let stamp = self.plane.stamp();
        let address = match &state.running {
            Some(running) => {
                if running.finished() { return Err(error("userControllerExited")); }
                for (path, secret, expected) in [("/version", Some(running.secret.as_ref()), 200),
                    ("/version", None, 401), ("/aerove/route", Some(running.secret.as_ref()), 404)] {
                    if probe(running.address, path, secret, &self.parent).await? != expected {
                        return Err(error("userControllerReadbackFailed"));
                    }
                }
                Some(running.address)
            }
            None => None,
        };
        if self.parent.is_cancelled() || self.plane.cancel.is_cancelled() { return Err(error("cancelled")); }
        if self.plane.stamp() != stamp { return Err(error("staleCoreEpoch")); }
        Ok(Readback { enabled: address.is_some(), address, authenticated: address.is_some(),
            private_routes_excluded: true, core_epoch: stamp.0.to_string() })
    }

    pub async fn stop(&self) -> io::Result<()> {
        { let _fence = self.intent_gate.lock(); self.intents.fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| n.checked_add(1)).ok(); }
        let mut state = self.state.lock().await;
        state.closed = true;
        if let Some(running) = state.running.take() { state.retired.push(running); }
        self.cleanup_locked(&mut state).await?;
        Ok(())
    }
}

struct Running {
    socket: std::net::TcpListener, address: SocketAddr, secret: Arc<str>,
    stop: CancellationToken, endpoint: UserEndpoint,
    task: Option<JoinHandle<io::Result<()>>>, activate: Option<oneshot::Sender<()>>,
    // A root-task abort/panic cannot prove that all of its HTTP children joined.
    business_failure: Option<&'static str>,
    uncertain: Option<&'static str>,
}
impl Running {
    fn finished(&self) -> bool { self.task.as_ref().is_none_or(JoinHandle::is_finished) }
    async fn close(&mut self) -> io::Result<()> { self.close_with_limit(IO_LIMIT).await }
    async fn close_with_limit(&mut self, limit: Duration) -> io::Result<()> {
        self.stop.cancel(); self.endpoint.cancel(); self.activate.take();
        if let Some(cause) = self.uncertain { return Err(error(cause)); }
        if let Some(task) = self.task.as_mut() {
            let cause = match tokio::time::timeout(limit, &mut *task).await {
                Ok(Ok(Ok(()))) => None,
                Ok(Ok(Err(_))) => { self.business_failure = Some("userControllerServeFailed"); None },
                Ok(Err(_)) => Some("userControllerTaskPanicked"),
                Err(_) => {
                    // Store uncertainty before awaiting: cancellation of close must not erase it.
                    self.uncertain = Some("userControllerJoinTimeout");
                    // Do not add an unbounded post-timeout await; retain the handle and unknown state.
                    task.abort();
                    Some("userControllerJoinTimeout")
                }
            };
            if let Some(cause) = cause { self.uncertain = Some(cause); return Err(error(cause)); }
        }
        self.task.take();
        if tokio::time::timeout(limit, self.endpoint.join()).await.is_err() {
            self.uncertain = Some("userControllerScopeJoinTimeout");
            return Err(error("userControllerScopeJoinTimeout"));
        }
        Ok(())
    }
}
impl Drop for Running {
    fn drop(&mut self) { self.stop.cancel(); self.endpoint.cancel(); if let Some(task) = &self.task { task.abort(); } }
}

fn record_failure(sender: &watch::Sender<Option<&'static str>>, cause: &'static str) {
    sender.send_if_modified(|current| { if current.is_some() { false } else { *current = Some(cause); true } });
}

struct ServeExit { failure: watch::Sender<Option<&'static str>>, parent: CancellationToken, stop: CancellationToken }
impl Drop for ServeExit {
    fn drop(&mut self) {
        if !self.stop.is_cancelled() {
            record_failure(&self.failure, if std::thread::panicking() { "userControllerTaskPanicked" } else { "userControllerExited" });
            self.parent.cancel();
        }
    }
}

fn readback_status(response: &[u8]) -> io::Result<u16> {
    let invalid = || error("userControllerReadbackInvalid");
    let head_end = response.windows(4).position(|part| part == b"\r\n\r\n").ok_or_else(invalid)?;
    let line_end = response[..head_end + 2].windows(2).position(|part| part == b"\r\n").ok_or_else(invalid)?;
    let line = &response[..line_end];
    let status = line.strip_prefix(b"HTTP/1.1 ").or_else(|| line.strip_prefix(b"HTTP/1.0 ")).ok_or_else(invalid)?;
    if status.len() < 4 || status[3] != b' '
        || status[4..].iter().any(|byte| !matches!(*byte, b'\t' | b' '..=b'~' | 0x80..=0xff)) {
        return Err(invalid());
    }
    axum::http::StatusCode::from_bytes(&status[..3]).map(|code| code.as_u16()).map_err(|_| invalid())
}

async fn probe(address: SocketAddr, path: &str, secret: Option<&str>, cancel: &CancellationToken) -> io::Result<u16> {
    let exchange = async {
        let mut stream = TcpStream::connect(address).await?;
        let authorization = secret.map(|secret| format!("Authorization: Bearer {secret}\r\n")).unwrap_or_default();
        stream.write_all(format!("GET {path} HTTP/1.1\r\nHost: {address}\r\n{authorization}Connection: close\r\n\r\n").as_bytes()).await?;
        let mut bytes = Vec::new(); stream.take(8193).read_to_end(&mut bytes).await?;
        if bytes.len() > 8192 { return Err(error("userControllerReadbackTooLarge")); }
        readback_status(&bytes)
    };
    tokio::select! { biased;
        _ = cancel.cancelled() => Err(error("cancelled")),
        result = tokio::time::timeout(IO_LIMIT, exchange) => result.map_err(|_| error("userControllerReadbackTimeout"))?,
    }
}

#[cfg(test)]
#[path = "user_controller_tests.rs"]
mod tests;
