use meow_config::owned_load::OwnedLoadScope;
use futures::FutureExt;
use parking_lot::Mutex;
use std::{future::Future, io, sync::{Arc, OnceLock, Weak}};
use tokio::{sync::Notify, task::{AbortHandle, JoinHandle}};

#[derive(Default)]
struct State {
    closed: bool,
    registering: usize,
    handles: Vec<JoinHandle<()>>,
    failed: bool,
}

#[derive(Default)]
pub struct TransportTasks {
    state: Mutex<State>,
    changed: Notify,
    retiring: tokio::sync::Mutex<Vec<JoinHandle<()>>>,
}

impl TransportTasks {
    pub fn new() -> Arc<Self> { Arc::new(Self::default()) }

    pub fn spawn_with<F, S>(self: &Arc<Self>, work: F, spawn: S) -> io::Result<AbortHandle>
    where F: Future<Output = ()> + Send + 'static,
        S: FnOnce(F) -> io::Result<JoinHandle<()>>,
    {
        {
            let mut state = self.state.lock();
            if state.closed { return Err(io::Error::other("transportOwnerClosed")); }
            state.registering += 1;
        }
        let mut reservation = Reservation { owner: self.clone(), finished: false };
        let mut finished = Vec::new();
        {
            let mut state = self.state.lock();
            let mut index = 0;
            while index < state.handles.len() {
                if state.handles[index].is_finished() { finished.push(state.handles.swap_remove(index)); }
                else { index += 1; }
            }
        }
        for mut handle in finished {
            match (&mut handle).now_or_never() {
                Some(Err(error)) if !error.is_cancelled() => self.state.lock().failed = true,
                Some(_) => {},
                None => self.state.lock().handles.push(handle),
            }
        }
        let spawned = spawn(work);
        let result = match spawned {
            Ok(handle) => {
                let abort = handle.abort_handle();
                let mut state = self.state.lock();
                if state.closed { abort.abort(); }
                state.handles.push(handle);
                Ok(abort)
            },
            Err(error) => Err(error),
        };
        reservation.finish();
        result
    }

    pub fn cancel(&self) {
        let mut state = self.state.lock();
        state.closed = true;
        for handle in &state.handles { handle.abort(); }
        drop(state);
        self.changed.notify_waiters();
    }

    pub async fn close(&self) -> io::Result<()> {
        self.cancel();
        // Retain each handle in this owner across cancellation of a close waiter.
        let mut retiring = self.retiring.lock().await;
        loop {
            let notified = self.changed.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            let pending = {
                let mut state = self.state.lock();
                if state.registering == 0 { retiring.append(&mut state.handles); false }
                else { true }
            };
            if !pending { break; }
            notified.await;
        }
        while let Some(handle) = retiring.last_mut() {
            if handle.await.is_err_and(|error| !error.is_cancelled()) { self.state.lock().failed = true; }
            retiring.pop();
        }
        if self.state.lock().failed { Err(io::Error::other("transportTaskPanicked")) } else { Ok(()) }
    }
}

struct Reservation { owner: Arc<TransportTasks>, finished: bool }
impl Reservation {
    fn finish(&mut self) {
        self.owner.state.lock().registering -= 1;
        self.finished = true;
        self.owner.changed.notify_waiters();
    }
}
impl Drop for Reservation {
    fn drop(&mut self) {
        if !self.finished {
            let mut state = self.owner.state.lock();
            state.failed = true;
            state.registering -= 1;
            drop(state);
            self.owner.changed.notify_waiters();
        }
    }
}

pub(crate) type ScopeSlot = Arc<OnceLock<Weak<OwnedLoadScope>>>;

pub(crate) struct CapturedTransport {
    network: crate::network_context::NetworkContext,
    tasks: Arc<TransportTasks>,
    scope: ScopeSlot,
}
impl CapturedTransport {
    pub(crate) fn new(network: crate::network_context::NetworkContext, tasks: Arc<TransportTasks>, scope: ScopeSlot) -> Arc<Self> {
        Arc::new(Self { network, tasks, scope })
    }
}
impl meow_common::transport_tasks::TransportTaskContext for CapturedTransport {
    fn spawn(&self, work: meow_common::transport_tasks::Work) -> io::Result<AbortHandle> {
        let scope = self.scope.get().and_then(Weak::upgrade).ok_or_else(|| io::Error::other("transportLoadScopeMissing"))?;
        let network = self.network.with_transport(self.tasks.clone(), self.scope.clone());
        self.tasks.spawn_with(async move { network.scope(work).await }, |work| {
            scope.spawn_owned(work).map_err(io::Error::other)
        })
    }
}
