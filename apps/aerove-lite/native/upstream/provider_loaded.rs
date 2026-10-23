use std::{collections::BTreeMap, io, sync::{Arc, Mutex, Weak}};
use serde::Serialize;
use tokio::sync::{watch, Mutex as OperationMutex, OwnedMutexGuard};
use super::telemetry::Bus;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Serialize)]
#[serde(rename_all="lowercase")]
pub(crate) enum Kind { Proxy, Rule }
#[derive(Clone, Copy, Debug, Serialize)]
#[serde(rename_all="lowercase")]
pub(crate) enum Phase { Initial, Update }
#[derive(Clone, Debug, Serialize)]
pub(crate) struct Loaded {
    pub kind: Kind, pub name: String, pub revision: String, pub phase: Phase, pub changed: bool,
}
type Key = (Kind, String);
struct Entry { registration: u64, busy: bool, latest: Option<Loaded> }
#[derive(Default)]
struct State { sequence: u64, registration: u64, entries: BTreeMap<Key, Entry> }
pub(crate) struct Store { state: Mutex<State>, changed: watch::Sender<u64> }
impl Store {
    pub(crate) fn new() -> Self { Self { state:Mutex::new(State::default()), changed:watch::channel(0).0 } }
    pub(crate) fn subscribe(&self) -> watch::Receiver<u64> { self.changed.subscribe() }
    pub(crate) fn since(&self, cursor:u64) -> Vec<Loaded> {
        let state=self.state.lock().unwrap();
        let mut values=state.entries.values().filter_map(|e|e.latest.as_ref())
            .filter(|v|v.revision.parse::<u64>().unwrap()>cursor).collect::<Vec<_>>();
        values.sort_by_key(|value|value.revision.parse::<u64>().unwrap());
        values.into_iter().cloned().collect()
    }
    pub(crate) fn batch(&self,cursor:u64)->io::Result<Vec<Loaded>> {
        let mut bytes=b"{\"type\":\"providersLoaded\",\"providers\":[]}".len();let mut result=Vec::new();
        for value in self.since(cursor) {
            let size=serde_json::to_vec(&value).map_err(io::Error::other)?.len()+(if result.is_empty(){0}else{1});
            if bytes+size>65536 {if result.is_empty() {return Err(io::Error::other("provider name exceeds telemetry frame limit"));}break;}
            bytes+=size;result.push(value);if result.len()==32 {break;}
        }
        Ok(result)
    }
    fn register(&self,key:Key)->u64 {
        let mut state=self.state.lock().unwrap();
        state.registration=state.registration.checked_add(1).expect("provider registration exhausted");
        let registration=state.registration;
        state.entries.insert(key,Entry {registration,busy:false,latest:None});registration
    }
}
pub(crate) struct Observer { bus:Weak<Bus>, key:Key, registration:u64, cancel:tokio_util::sync::CancellationToken, operation:Arc<OperationMutex<()>> }
pub(crate) struct Operation { observer:Arc<Observer>, _guard:OwnedMutexGuard<()> }
impl Observer {
    pub(crate) fn capture(kind:Kind,name:&str)->Option<Arc<Self>> {
        super::telemetry::source().map(|bus|Self::for_bus(&bus,kind,name))
    }
    pub(crate) fn for_bus(bus:&Arc<Bus>,kind:Kind,name:&str)->Arc<Self> {
        let key=(kind,name.to_owned());let registration=bus.providers.register(key.clone());
        Arc::new(Self {bus:Arc::downgrade(bus),key,registration,cancel:bus.cancellation().child_token(),operation:Arc::new(OperationMutex::new(()))})
    }
    pub(crate) fn stop(&self) {self.cancel.cancel();}
    pub(crate) async fn begin(self:&Arc<Self>)->Operation {
        let guard=self.operation.clone().lock_owned().await;
        if let Some(bus)=self.bus.upgrade() {
            let mut state=bus.providers.state.lock().unwrap();
            if let Some(entry)=state.entries.get_mut(&self.key).filter(|e|e.registration==self.registration) {entry.busy=true;}
        }
        Operation {observer:self.clone(),_guard:guard}
    }
    pub(crate) fn revision(&self)->Result<Option<String>,()> {
        if self.cancel.is_cancelled() {return Err(());}
        let bus=self.bus.upgrade().filter(|bus|!bus.cancellation().is_cancelled()).ok_or(())?;
        let state=bus.providers.state.lock().unwrap();
        let entry=state.entries.get(&self.key).filter(|e|e.registration==self.registration&&!e.busy).ok_or(())?;
        Ok(entry.latest.as_ref().map(|v|v.revision.clone()))
    }
}
impl Operation {
    pub(crate) fn active(&self)->bool {
        if self.observer.cancel.is_cancelled() {return false;}
        self.observer.bus.upgrade().is_some_and(|bus| {
            if bus.cancellation().is_cancelled() {return false;}
            let state=bus.providers.state.lock().unwrap();
            state.entries.get(&self.observer.key).is_some_and(|entry|entry.registration==self.observer.registration)
        })
    }
    pub(crate) fn success(&self,phase:Phase,changed:bool)->io::Result<()> {
        if self.observer.cancel.is_cancelled() {return Ok(());}
        let Some(bus)=self.observer.bus.upgrade().filter(|bus|!bus.cancellation().is_cancelled()) else {return Ok(());};
        let next={
            let mut state=bus.providers.state.lock().unwrap();
            if !state.entries.get(&self.observer.key).is_some_and(|e|e.registration==self.observer.registration) {return Ok(());}
            if self.observer.cancel.is_cancelled()||bus.cancellation().is_cancelled() {return Ok(());}
            let next=state.sequence.checked_add(1).ok_or_else(||io::Error::other("provider revision exhausted"))?;
            state.sequence=next;
            let entry=state.entries.get_mut(&self.observer.key).unwrap();
            entry.latest=Some(Loaded {kind:self.observer.key.0,name:self.observer.key.1.clone(),revision:next.to_string(),phase,changed});
            entry.busy=false;next
        };
        bus.providers.changed.send_modify(|revision|*revision=(*revision).max(next));Ok(())
    }
}
impl Drop for Operation {
    fn drop(&mut self) {
        if let Some(bus)=self.observer.bus.upgrade() {
            let mut state=bus.providers.state.lock().unwrap();
            if let Some(entry)=state.entries.get_mut(&self.observer.key)
                .filter(|e|e.registration==self.observer.registration) {entry.busy=false;}
        }
    }
}
