use meow_config::owned_load::OwnedLoadScope;
use meow_common::transport_tasks::{SharedUnitJoin, TaskOutcome};
use futures::FutureExt;
use parking_lot::Mutex;
use std::{future::Future, io, sync::{Arc, OnceLock, Weak, atomic::{AtomicBool, Ordering}}};
use tokio::{sync::Notify, task::{AbortHandle, JoinHandle}};

#[derive(Default)]
struct State {
    closed: bool,
    registering: usize,
    handles: Vec<Arc<SharedUnitJoin>>,
    panicked: bool,
    registration_failed: bool,
}

#[derive(Default)]
pub struct TransportTasks {
    state: Mutex<State>,
    panicked: Arc<AtomicBool>,
    changed: Notify,
    retiring: tokio::sync::Mutex<Vec<Arc<SharedUnitJoin>>>,
}

impl TransportTasks {
    pub fn new() -> Arc<Self> { Arc::new(Self::default()) }
    pub fn child(&self) -> Arc<Self> { Arc::new(Self { panicked: self.panicked.clone(), ..Self::default() }) }
    pub fn panicked(&self) -> bool { self.panicked.load(Ordering::Acquire) }
    fn record(&self, outcome: TaskOutcome) {
        self.state.lock().panicked |= outcome.panicked;
        if outcome.panicked { self.panicked.store(true, Ordering::Release); }
    }

    pub fn spawn_with<F, S>(self: &Arc<Self>, work: F, spawn: S) -> io::Result<AbortHandle>
    where F: Future<Output = ()> + Send + 'static,
        S: FnOnce(F) -> io::Result<JoinHandle<()>>,
    {
        self.spawn_join_with(work, spawn).map(|record| record.abort_handle())
    }

    pub fn spawn_join_with<F, S>(self: &Arc<Self>, work: F, spawn: S) -> io::Result<Arc<SharedUnitJoin>>
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
        for record in finished {
            let outcome = record.wait().now_or_never();
            match outcome {
                Some(outcome) => self.record(outcome),
                None => self.state.lock().handles.push(record),
            }
        }
        let result = match spawn(work) {
            Ok(handle) => {
                let record = SharedUnitJoin::new(handle);
                let closed = {
                    let mut state = self.state.lock();
                    state.handles.push(record.clone());
                    state.closed
                };
                if closed { record.abort(); }
                Ok(record)
            },
            Err(error) => Err(error),
        };
        reservation.finish();
        result
    }

    pub fn cancel(&self) {
        let handles = {
            let mut state = self.state.lock();
            state.closed = true;
            state.handles.clone()
        };
        for handle in handles { handle.abort(); }
        self.changed.notify_waiters();
    }

    pub async fn close_joined(&self) -> io::Result<TaskOutcome> {
        self.cancel();
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
        while let Some(record) = retiring.last() {
            let outcome = record.wait().await;
            self.record(outcome);
            retiring.pop();
        }
        let state = self.state.lock();
        if state.registration_failed { Err(io::Error::other("transportRegistrationUnconfirmed")) }
        else { Ok(TaskOutcome { panicked: state.panicked, cancelled: false }) }
    }

    pub async fn close(&self) -> io::Result<()> {
        if self.close_joined().await?.panicked { Err(io::Error::other("transportTaskPanicked")) } else { Ok(()) }
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
            state.registration_failed = true;
            state.registering -= 1;
            drop(state);
            self.owner.changed.notify_waiters();
        }
    }
}

pub(crate) type ScopeSlot = Arc<OnceLock<Weak<OwnedLoadScope>>>;

pub(crate) struct CapturedTransport {
    network: crate::network_context::NetworkContext,
    fresh_network: crate::network_context::NetworkContext,
    tasks: Arc<TransportTasks>,
    scope: ScopeSlot,
}
impl CapturedTransport {
    pub(crate) fn with_network(&self, network: crate::network_context::NetworkContext) -> Arc<Self> {
        Arc::new(Self { network: network.transport_context(), fresh_network: self.fresh_network.clone(), tasks: self.tasks.clone(), scope: self.scope.clone() })
    }
    pub(crate) fn new(network: crate::network_context::NetworkContext, tasks: Arc<TransportTasks>, scope: ScopeSlot) -> Arc<Self> {
        let network = network.transport_context();
        Arc::new(Self { fresh_network: network.clone(), network, tasks, scope })
    }
}
impl meow_common::transport_tasks::TransportTaskContext for CapturedTransport {
    fn wrap(&self, work: meow_common::transport_tasks::Work) -> meow_common::transport_tasks::Work {
        let network = self.fresh_network.with_transport(self.tasks.clone(), self.scope.clone());
        Box::pin(async move { network.scope(work).await })
    }
    fn spawn(&self, work: meow_common::transport_tasks::Work) -> io::Result<AbortHandle> {
        self.spawn_join(work).map(|record| record.abort_handle())
    }
    fn spawn_join(&self, work: meow_common::transport_tasks::Work) -> io::Result<Arc<SharedUnitJoin>> {
        let scope = self.scope.get().and_then(Weak::upgrade).ok_or_else(|| io::Error::other("transportLoadScopeMissing"))?;
        let network = self.network.with_transport(self.tasks.clone(), self.scope.clone());
        self.tasks.spawn_join_with(async move { network.scope(work).await }, |work| {
            scope.spawn_owned(work).map_err(io::Error::other)
        })
    }
}
