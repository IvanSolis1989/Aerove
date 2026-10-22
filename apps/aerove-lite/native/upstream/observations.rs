//! Foreground-only event transport. No timer, disk history, or DNS inference.
use std::{collections::{HashMap, VecDeque}, sync::{Arc, Mutex}, time::Instant};
use serde::Serialize;
use serde_json::{Value, json};
use tokio::sync::Notify;
use tokio_util::sync::CancellationToken;
use crate::app::dispatcher::TrackerInfo;
use hickory_proto::{op, rr};

const REQUEST_CAP: usize = 2000;
const DNS_CAP: usize = 3000;
const FRAME_BUDGET: usize = 60 * 1024;
const QUEUE_BYTE_BUDGET: usize = 1024 * 1024;

#[derive(Clone, Serialize)]
pub(crate) struct Event {
    cursor: String,
    kind: &'static str,
    data: Value,
    #[serde(skip)]
    sequence: u64,
    #[serde(skip)]
    bytes: usize,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Window {
    session_id: String,
    generation: String,
    pub(crate) cursor: String,
    dropped: String,
    events: Vec<Event>,
}
#[derive(Default)]
struct State {
    leases: usize,
    generation: u64,
    cursor: u64,
    dropped: u64,
    requests: VecDeque<Event>,
    dns: VecDeque<Event>,
    request_bytes: usize,
    dns_bytes: usize,
    watched_order: VecDeque<uuid::Uuid>,
    watched: HashMap<uuid::Uuid, (u64, u64)>,
}
pub(crate) struct Collector {
    session: uuid::Uuid,
    state: Mutex<State>,
    manager: Mutex<Option<std::sync::Weak<crate::app::dispatcher::StatisticsManager>>>,
    pub(crate) changed: Notify,
    cancel: CancellationToken,
}
pub(crate) struct Lease {
    pub(crate) collector: Arc<Collector>,
    pub(crate) generation: u64,
    pub(crate) cursor: u64,
}
#[derive(Clone)]
pub(crate) struct Ticket { collector: Arc<Collector>, generation: u64 }
impl Collector {
    pub(crate) fn new(cancel: CancellationToken) -> Arc<Self> {
        Arc::new(Self { session: uuid::Uuid::new_v4(), state: Mutex::new(State::default()), manager: Mutex::new(None), changed: Notify::new(), cancel })
    }
    pub(crate) fn attach(&self, manager: std::sync::Weak<crate::app::dispatcher::StatisticsManager>) {
        *self.manager.lock().unwrap() = Some(manager);
    }
    pub(crate) async fn resume(self: &Arc<Self>) {
        let manager = self.manager.lock().unwrap().as_ref().and_then(|m| m.upgrade());
        if let (Some(manager), Some(ticket)) = (manager, self.ticket()) {
            // Existing active connections only acquire a traffic watch; they
            // never become synthetic request-start history rows on resume.
            for info in manager.observation_snapshot().await { ticket.adopt(&info); }
        }
    }
    pub(crate) fn lease(self: &Arc<Self>) -> Lease {
        let mut s = self.state.lock().unwrap();
        if s.leases == 0 {
            s.generation = s.generation.checked_add(1).expect("observation generation exhausted");
            s.cursor = 0; s.dropped = 0;
        }
        s.leases += 1;
        Lease { collector: self.clone(), generation: s.generation, cursor: s.cursor }
    }
    pub(crate) fn ticket(self: &Arc<Self>) -> Option<Ticket> {
        let s = self.state.lock().unwrap();
        (s.leases > 0 && !self.cancel.is_cancelled()).then(|| Ticket { collector: self.clone(), generation: s.generation })
    }
    pub(crate) fn window(&self, generation: u64, cursor: u64, initial: bool) -> Option<Window> {
        let s = self.state.lock().unwrap();
        if s.generation != generation || s.leases == 0 || self.cancel.is_cancelled() { return None; }
        let mut events = Vec::new();
        let mut bytes = 512;
        if !initial {
            let mut requests = s.requests.iter().filter(|e| e.sequence > cursor).peekable();
            let mut dns = s.dns.iter().filter(|e| e.sequence > cursor).peekable();
            while events.len() < 32 {
                let event = match (requests.peek(), dns.peek()) {
                    (Some(r), Some(d)) if r.sequence <= d.sequence => requests.next(),
                    (Some(_), Some(_)) => dns.next(),
                    (Some(_), None) => requests.next(),
                    (None, Some(_)) => dns.next(),
                    (None, None) => break,
                }.unwrap();
                if bytes + event.bytes > FRAME_BUDGET { break; }
                bytes += event.bytes; events.push(event.clone());
            }
        }
        let next = if initial { cursor } else { events.last().map_or(s.cursor, |e| e.sequence) };
        Some(Window { session_id: self.session.to_string(), generation: generation.to_string(), cursor: next.to_string(), dropped: s.dropped.to_string(), events })
    }
}
impl Drop for Lease {
    fn drop(&mut self) {
        let mut s = self.collector.state.lock().unwrap();
        s.leases -= 1;
        if s.leases == 0 {
            s.requests.clear(); s.dns.clear(); s.request_bytes = 0; s.dns_bytes = 0; s.watched.clear(); s.watched_order.clear();
            self.collector.changed.notify_waiters();
        }
    }
}
impl Ticket {
    pub(crate) fn emit(&self, kind: &'static str, data: Value) {
        let mut s = self.collector.state.lock().unwrap();
        if s.leases == 0 || s.generation != self.generation || self.collector.cancel.is_cancelled() { return; }
        s.cursor = s.cursor.checked_add(1).expect("observation cursor exhausted");
        let mut event = Event { cursor: s.cursor.to_string(), sequence: s.cursor, kind, data, bytes: 0 };
        event.bytes = serde_json::to_vec(&event).map_or(usize::MAX, |v| v.len());
        // One large payload must not starve the stream or bypass the frame limit.
        if event.bytes > FRAME_BUDGET - 512 {
            s.dropped = s.dropped.saturating_add(1);
        } else {
            let dropped = {
                let state = &mut *s;
                let (queue, bytes, cap) = if kind == "dns" { (&mut state.dns, &mut state.dns_bytes, DNS_CAP) } else { (&mut state.requests, &mut state.request_bytes, REQUEST_CAP) };
                *bytes += event.bytes;
                queue.push_back(event);
                let mut dropped = 0;
                while queue.len() > cap || *bytes > QUEUE_BYTE_BUDGET {
                    if let Some(old) = queue.pop_front() { *bytes -= old.bytes; dropped += 1; }
                }
                dropped
            };
            s.dropped = s.dropped.saturating_add(dropped);
        }
        drop(s);
        self.collector.changed.notify_waiters();
    }
    fn adopt(&self, info: &TrackerInfo) {
        let mut s = self.collector.state.lock().unwrap();
        if s.leases == 0 || s.generation != self.generation || s.watched.contains_key(&info.uuid) || s.watched.len() >= REQUEST_CAP || self.collector.cancel.is_cancelled() { return; }
        let (up, down) = totals(info);
        s.watched.insert(info.uuid, (up, down)); s.watched_order.push_back(info.uuid);
        drop(s);
        self.emit("requestTraffic", json!({"id":info.uuid,"upload":up.to_string(),"download":down.to_string()}));
    }
    pub(crate) fn watched_ids(&self) -> Vec<uuid::Uuid> {
        let s = self.collector.state.lock().unwrap();
        if s.leases == 0 || s.generation != self.generation || self.collector.cancel.is_cancelled() { return vec![]; }
        s.watched_order.iter().copied().collect()
    }
    pub(crate) async fn request(&self, info: &TrackerInfo) {
        let chain = info.proxy_chain_holder.snapshot().await;
        let (up, down) = totals(info);
        {
            let mut s = self.collector.state.lock().unwrap();
            if s.generation != self.generation || s.leases == 0 { return; }
            if s.watched.len() == REQUEST_CAP {
                if let Some(id) = s.watched_order.pop_front() { s.watched.remove(&id); }
            }
            s.watched_order.push_back(info.uuid);
            s.watched.insert(info.uuid, (up, down));
        }
        self.emit("request", json!({"id":info.uuid,"start":info.start_time,"metadata":info.session_holder.as_map(),"chains":chain,"rule":info.rule,"rulePayload":info.rule_payload,"upload":up.to_string(),"download":down.to_string()}));
    }
    pub(crate) fn traffic(&self, info: &TrackerInfo, end: Option<chrono::DateTime<chrono::Utc>>) {
        let (up, down) = totals(info);
        let mut s = self.collector.state.lock().unwrap();
        if s.generation != self.generation || s.leases == 0 { return; }
        let changed = if let Some(previous) = s.watched.get_mut(&info.uuid) {
            let changed = *previous != (up, down); *previous = (up, down); changed
        } else {
            if end.is_none() { return; }
            false
        };
        if end.is_some() { s.watched.remove(&info.uuid); s.watched_order.retain(|id| id != &info.uuid); }
        drop(s);
        if let Some(end) = end { self.emit("requestClosed", json!({"id":info.uuid,"upload":up.to_string(),"download":down.to_string(),"end":end})); }
        else if changed { self.emit("requestTraffic", json!({"id":info.uuid,"upload":up.to_string(),"download":down.to_string()})); }
    }
}
fn totals(info: &TrackerInfo) -> (u64, u64) {
    use std::sync::atomic::Ordering::Relaxed;
    (info.upload_total.load(Relaxed), info.download_total.load(Relaxed))
}
pub(crate) fn source() -> Option<Arc<Collector>> { super::telemetry::source().map(|b| b.observations.clone()) }

#[derive(Clone, Copy)]
pub(crate) enum Origin { App, Rule, Direct, Proxy, Other }
impl Origin { fn name(self) -> &'static str { match self { Self::App=>"App",Self::Rule=>"Rule",Self::Direct=>"Direct",Self::Proxy=>"Proxy",Self::Other=>"Other" } } }
tokio::task_local! { static ORIGIN: Origin; static TRACE: Arc<Mutex<DnsTrace>>; }
struct DnsTrace { key: String, upstream: String, cached: bool }
pub(crate) fn dns_source(source: String, cached: bool) { let _ = TRACE.try_with(|t| { let mut t=t.lock().unwrap(); t.upstream=source; t.cached=cached; }); }
pub(crate) fn current_dns_source() -> String { TRACE.try_with(|t| t.lock().unwrap().upstream.clone()).unwrap_or_default() }

pub(crate) async fn dns_message<F, E>(message: &op::Message, future: F) -> Result<op::Message, E>
where F: std::future::Future<Output=Result<op::Message,E>>, E: std::fmt::Display {
    let Some(q) = message.queries.first() else { return future.await; };
    let key = q.to_string();
    if TRACE.try_with(|t| t.lock().unwrap().key == key).unwrap_or(false) { return future.await; }
    let ticket = source().and_then(|c| c.ticket());
    let trace = Arc::new(Mutex::new(DnsTrace { key, upstream:String::new(), cached:false }));
    let start = chrono::Utc::now(); let timer = Instant::now();
    let result = TRACE.scope(trace.clone(), future).await;
    let Some(ticket) = ticket else { return result; };
    let (answers,rcode,error) = match &result {
        Ok(m) => (m.answers.iter().map(|r| match &r.data {
            rr::RData::A(ip)=>ip.to_string(), rr::RData::AAAA(ip)=>ip.to_string(),
            rr::RData::CNAME(name)=>name.to_string().trim_end_matches('.').to_owned(),
            data=>data.to_string(),
        }).collect::<Vec<_>>(), match m.metadata.response_code { op::ResponseCode::NoError=>"NOERROR".to_owned(), op::ResponseCode::NXDomain=>"NXDOMAIN".to_owned(), op::ResponseCode::ServFail=>"SERVFAIL".to_owned(), other=>format!("{other:?}").to_ascii_uppercase() }, String::new()),
        Err(e)=>(vec![],String::new(),e.to_string()),
    };
    let trace = trace.lock().unwrap();
    ticket.emit("dns",json!({"domain":q.name().to_ascii().trim_end_matches('.'),"type":q.query_type().to_string(),"initiator":ORIGIN.try_with(|o|o.name()).unwrap_or("Other"),"upstream":if result.is_ok(){trace.upstream.clone()}else{String::new()},"cached":trace.cached,"answers":answers,"rcode":rcode,"error":error,"delay":timer.elapsed().as_millis().min(u64::MAX as u128) as u64,"time":start}));
    result
}

/// Carries origin through spawned UDP work because the wrapper, not task-local
/// inheritance, is retained by the datagram/connector.
pub(crate) fn resolver(inner: crate::app::dns::ThreadSafeDNSResolver, origin: Origin) -> crate::app::dns::ThreadSafeDNSResolver {
    Arc::new(OriginResolver { inner, origin })
}
struct OriginResolver { inner: crate::app::dns::ThreadSafeDNSResolver, origin: Origin }
#[async_trait::async_trait]
impl crate::app::dns::ClashResolver for OriginResolver {
    async fn resolve(&self,h:&str,e:bool)->anyhow::Result<Option<std::net::IpAddr>> { ORIGIN.scope(self.origin,self.inner.resolve(h,e)).await }
    async fn resolve_v4(&self,h:&str,e:bool)->anyhow::Result<Option<std::net::Ipv4Addr>> { ORIGIN.scope(self.origin,self.inner.resolve_v4(h,e)).await }
    async fn resolve_v6(&self,h:&str,e:bool)->anyhow::Result<Option<std::net::Ipv6Addr>> { ORIGIN.scope(self.origin,self.inner.resolve_v6(h,e)).await }
    async fn resolve_all(&self,h:&str,e:bool)->anyhow::Result<Vec<std::net::IpAddr>> { ORIGIN.scope(self.origin,self.inner.resolve_all(h,e)).await }
    async fn cached_for(&self,ip:std::net::IpAddr)->Option<String> { self.inner.cached_for(ip).await }
    async fn exchange(&self,m:&op::Message)->anyhow::Result<op::Message> { ORIGIN.scope(self.origin,self.inner.exchange(m)).await }
    async fn reverse_lookup(&self,ip:std::net::IpAddr)->Option<String> { self.inner.reverse_lookup(ip).await }
    async fn is_fake_ip(&self,ip:std::net::IpAddr)->bool { self.inner.is_fake_ip(ip).await }
    fn fake_ip_enabled(&self)->bool {self.inner.fake_ip_enabled()}
    fn ipv6(&self)->bool {self.inner.ipv6()}
    fn set_ipv6(&self,v:bool){self.inner.set_ipv6(v)}
    fn kind(&self)->crate::app::dns::ResolverKind {self.inner.kind()}
    async fn clear_cache(&self){self.inner.clear_cache().await}
    async fn flush_fakeip(&self){self.inner.flush_fakeip().await}
}

pub(crate) async fn app_dns<F, E>(message:&op::Message, future:F)->Result<op::Message,E>
where F:std::future::Future<Output=Result<op::Message,E>>, E:std::fmt::Display {
    ORIGIN.scope(Origin::App, dns_message(message,future)).await
}

/// Hosts and fake-IP branches have no wire exchange, but are genuine answers.
pub(crate) fn synthetic(host: &str, ip: std::net::IpAddr, start: (chrono::DateTime<chrono::Utc>, Instant)) {
    if TRACE.try_with(|_| ()).is_ok() { return; }
    if let Some(ticket) = source().and_then(|c|c.ticket()) {
        ticket.emit("dns", json!({"domain":host,"type":if ip.is_ipv4(){"A"}else{"AAAA"},"initiator":ORIGIN.try_with(|o|o.name()).unwrap_or("Other"),"upstream":"","cached":false,"answers":[ip.to_string()],"rcode":"NOERROR","error":"","delay":start.1.elapsed().as_millis().min(u64::MAX as u128) as u64,"time":start.0}));
    }
}
