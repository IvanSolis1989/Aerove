pub use meow_config::owned_load::{LoadContext, OwnedLoadScope, OwnedWork, ProviderRoots};
use std::sync::Arc;
use tokio_util::sync::CancellationToken;

pub type OwnedConfigLoad<'a> = std::pin::Pin<Box<dyn std::future::Future<Output = Result<meow_config::Config, String>> + Send + 'a>>;

pub fn load<'a>(
    yaml: &'a str, scope: &'a Arc<OwnedLoadScope>, cancel: &'a CancellationToken,
) -> OwnedConfigLoad<'a> {
    load_with_cache(yaml, scope, cancel, None)
}

/// The caller must supply a trusted app-private root captured by this Session.
pub fn load_with_cache<'a>(
    yaml: &'a str, scope: &'a Arc<OwnedLoadScope>, cancel: &'a CancellationToken,
    cache_dir: Option<&'a std::path::Path>,
) -> OwnedConfigLoad<'a> {
    Box::pin(async move {
        let factory = meow_config::load_config_from_str_owned_with_cache(yaml, scope, cache_dir);
        let result = tokio::select! {
            biased;
            _ = cancel.cancelled() => Err("configLoadCancelled".to_owned()),
            result = factory => result.map_err(|error| error.to_string()),
        };
        if result.is_err() {
            scope.cancel();
            scope.close().await.map_err(str::to_owned)?;
        }
        result
    })
}

use meow_config::provider_events::{ProviderIdentity, ProviderKind, ProviderLease, ProviderObserver};
use parking_lot::Mutex;
use serde::Serialize;
use std::collections::{BTreeMap, BTreeSet};
use std::sync::Weak;
use tokio::sync::{broadcast, Notify};

type ProviderKey = (ProviderKind, String);
#[derive(Clone, Debug, Serialize)]
pub struct ProviderLoaded {
    pub kind: ProviderKind, pub name: String, pub revision: String,
    pub phase: &'static str, pub changed: bool,
}
#[derive(Default)]
struct SourceReceipt { revision: u64, changed: bool }
pub(crate) struct PlaneState {
    pub core_epoch: u64, pub revision: u64, pub closed: bool,
    pub(crate) groups_sequence: u64,
    pub(crate) groups_emitting: bool,
    pub(crate) selection_serial: u64,
    pub(crate) selection_intents: BTreeMap<String, u64>,
    pub(crate) delay_intents: BTreeMap<usize, u64>,
    active: bool,
    pub(crate) group_control: Option<Weak<crate::control::RouteControl>>,
    live: BTreeMap<ProviderKey, u64>,
    admitted: BTreeMap<ProviderKey, u64>,
    sources: BTreeMap<u64, SourceReceipt>,
    pub(crate) busy: BTreeSet<u64>,
    pub(crate) emitting: BTreeSet<u64>,
    pending: BTreeMap<ProviderKey, (u64, ProviderLoaded)>,
    sequence: u64,
}
impl PlaneState {
    pub(crate) fn group_events_ready(&self) -> bool {
        !self.groups_emitting && self.busy.is_empty() && self.pending.is_empty() && self.emitting.is_empty()
    }
}
pub struct ControlPlane {
    pub(crate) gate: Mutex<PlaneState>,
    pub(crate) cancel: CancellationToken,
    this: Weak<ControlPlane>,
    changed: Notify,
    events: broadcast::Sender<ProviderLoaded>,
}
impl ControlPlane {
    pub fn new(epoch: u64, cancel: CancellationToken) -> Result<Arc<Self>, &'static str> {
        if epoch == 0 { return Err("invalidCoreEpoch"); }
        let (events, _) = broadcast::channel(1024);
        Ok(Arc::new_cyclic(|this| Self { gate: Mutex::new(PlaneState {
            core_epoch: epoch, revision: 0, closed: false, groups_sequence: 0, groups_emitting: false, selection_serial: 0, selection_intents: BTreeMap::new(), delay_intents: BTreeMap::new(), active: false, group_control: None, live: BTreeMap::new(), admitted: BTreeMap::new(),
            sources: BTreeMap::new(), busy: BTreeSet::new(), emitting: BTreeSet::new(), pending: BTreeMap::new(), sequence: 0,
        }), cancel, this: this.clone(), changed: Notify::new(), events }))
    }
    /// `install` only swaps validated Rust state and returns retired objects.
    /// Notifications, I/O and final retired-object destruction happen after return.
    pub fn commit_policy<T>(&self, expected: u64, changed: bool, install: impl FnOnce() -> T)
        -> Result<((u64, u64), Option<T>), String> {
        let mut gate = self.gate.lock();
        if gate.closed || self.cancel.is_cancelled() { return Err("cancelled".into()); }
        if gate.core_epoch != expected { return Err("staleCoreEpoch".into()); }
        if !changed {
            let stamp = (gate.core_epoch, gate.revision); drop(gate); drop(install);
            return Ok((stamp, None));
        }
        let epoch = next_core_epoch(gate.core_epoch)?;
        let retired = install(); gate.core_epoch = epoch;
        let stamp = (gate.core_epoch, gate.revision); drop(gate);
        Ok((stamp, Some(retired)))
    }
    pub fn stamp(&self) -> (u64, u64) {
        let gate = self.gate.lock();
        (gate.core_epoch, gate.revision)
    }
    pub fn cancel(&self) {
        self.gate.lock().closed = true;
        self.cancel.cancel();
    }
    pub fn subscribe(&self) -> broadcast::Receiver<ProviderLoaded> { self.events.subscribe() }
    pub fn activate(&self, identities: &[ProviderIdentity]) -> Result<(), String> {
        let mut gate = self.gate.lock();
        if gate.closed || self.cancel.is_cancelled() { return Err("cancelled".into()); }
        if !gate.busy.is_empty() || !gate.emitting.is_empty() { return Err("providerBusy".into()); }
        let mut live = BTreeMap::new();
        for identity in identities {
            if gate.admitted.get(&(identity.kind, identity.name.clone())).is_some_and(|source| *source != identity.source) {
                return Err("providerSourceReplacementNotIntegrated".into());
            }
            if live.insert((identity.kind, identity.name.clone()), identity.source).is_some() {
                return Err("duplicateProviderIdentity".into());
            }
        }
        gate.pending.clear();
        gate.admitted.extend(live.clone());
        gate.live = live;
        gate.active = true;
        for identity in identities {
            if let Some(receipt) = gate.sources.get(&identity.source) {
                if receipt.revision > 0 {
                    let event = ProviderLoaded { kind: identity.kind, name: identity.name.clone(),
                        revision: receipt.revision.to_string(), phase: "initial", changed: receipt.changed };
                    gate.sequence += 1;
                    let sequence = gate.sequence;
                    gate.pending.insert((identity.kind, identity.name.clone()), (sequence, event));
                }
            }
        }
        drop(gate);
        self.changed.notify_one();
        Ok(())
    }
    pub fn provider_baseline(&self) -> Vec<ProviderLoaded> {
        let gate = self.gate.lock();
        gate.live.iter().filter_map(|((kind, name), source)| {
            if gate.busy.contains(source) { return None; }
            let receipt = gate.sources.get(source)?;
            (receipt.revision > 0).then(|| ProviderLoaded { kind: *kind, name: name.clone(),
                revision: receipt.revision.to_string(), phase: "initial", changed: receipt.changed })
        }).collect()
    }
    pub async fn run_events(&self) {
        loop {
            let notified = self.changed.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            let mut pending: Vec<_> = {
                let mut gate = self.gate.lock();
                let ready: Vec<_> = gate.pending.keys().filter(|key|
                    gate.live.get(*key).is_some_and(|source| !gate.busy.contains(source))).cloned().collect();
                ready.into_iter().filter_map(|key| {
                    let source = *gate.live.get(&key)?;
                    let (sequence, event) = gate.pending.remove(&key)?;
                    gate.emitting.insert(source);
                    Some((sequence, source, event))
                }).collect()
            };
            pending.sort_by_key(|(sequence, _, _)| *sequence);
            for (_, source, event) in pending {
                let _ = self.events.send(event);
                self.finish_emitting(source);
            }
            tokio::select! { biased; _ = self.cancel.cancelled() => return, _ = notified => {} }
        }
    }
    pub(crate) fn finish_emitting(&self, source: u64) {
        let control = {
            let mut gate = self.gate.lock();
            gate.emitting.remove(&source);
            gate.group_control.as_ref().and_then(Weak::upgrade)
        };
        if let Some(control) = control { control.notify_provider_groups(); }
    }
    pub fn rule_snapshot(&self, state: &meow_api::routes::AppState, name: Option<&str>) -> Result<serde_json::Value, &'static str> {
        let captured = {
            let gate = self.gate.lock();
            if gate.closed || self.cancel.is_cancelled() { return Err("cancelled"); }
            let providers = state.rule_providers.read();
            let mut captured = Vec::new();
            for (key, provider) in providers.iter() {
                if name.is_some_and(|name| name != key) { continue; }
                let identity = provider.provider_identity();
                if gate.busy.contains(&identity.source) || provider.is_busy() { return Err("providerBusy"); }
                if gate.live.get(&(identity.kind, identity.name.clone())) != Some(&identity.source) { return Err("staleProviderSource"); }
                let receipt = gate.sources.get(&identity.source).filter(|receipt| receipt.revision > 0).ok_or("providerBusy")?;
                captured.push((key.clone(), meow_api::routes::rule_provider_snapshot(provider), receipt.revision.to_string()));
            }
            captured
        };
        let mut snapshots = serde_json::Map::new();
        for (key, provider, revision) in captured {
            let mut value = serde_json::to_value(provider).map_err(|_| "providerSerializationFailed")?;
            value["revision"] = serde_json::Value::String(revision); snapshots.insert(key, value);
        }
        match name {
            Some(name) => snapshots.remove(name).ok_or("providerNotFound"),
            None => Ok(serde_json::json!({"providers":snapshots})),
        }
    }
    pub async fn rule_page(&self, state: &meow_api::routes::AppState, name: &str, offset: usize, limit: usize, expected: Option<&str>)
        -> Result<serde_json::Value, String> {
        if limit == 0 || limit > 10000 { return Err("invalidRulePageLimit".into()); }
        if expected.is_some_and(|epoch| epoch.parse::<u64>().ok().is_none_or(|value| value == 0 || value.to_string() != epoch)) {
            return Err("invalidRulePageEpoch".into());
        }
        let (provider, pages, stamp, source) = {
            let gate = self.gate.lock();
            if gate.closed || self.cancel.is_cancelled() { return Err("cancelled".into()); }
            if expected.is_some_and(|expected| expected != gate.core_epoch.to_string()) { return Err("staleRulePage".into()); }
            let provider = state.rule_providers.read().get(name).cloned().ok_or("providerNotFound")?;
            let identity = provider.provider_identity(); let source = identity.source;
            if gate.live.get(&(identity.kind, identity.name.clone())) != Some(&source) { return Err("staleProviderSource".into()); }
            if gate.busy.contains(&source) || provider.is_busy() { return Err("providerBusy".into()); }
            let pages = provider.page_snapshot().ok_or("providerBusy")?;
            (provider, pages, (gate.core_epoch, gate.revision), source)
        };
        let page = provider.read_page_owned(pages, offset, limit).await?;
        {
            let gate = self.gate.lock();
            if gate.closed || self.cancel.is_cancelled() { return Err("cancelled".into()); }
            if (gate.core_epoch, gate.revision) != stamp || gate.live.get(&(meow_config::provider_events::ProviderKind::Rule, name.to_owned())) != Some(&source) {
                return Err("staleRulePage".into());
            }
        }
        let mut value = serde_json::to_value(page).map_err(|_| "providerSerializationFailed")?;
        value["coreEpoch"] = serde_json::Value::String(stamp.0.to_string());
        Ok(value)
    }
    pub fn proxy_snapshot(&self, state: &meow_api::routes::AppState, name: Option<&str>)
        -> Result<serde_json::Value, &'static str> {
        let gate = self.gate.lock();
        if gate.closed { return Err("cancelled"); }
        let mut captured = Vec::new();
        for entry in state.proxy_providers.iter() {
            if name.is_some_and(|name| name != entry.key()) { continue; }
            let provider = entry.value();
            if gate.busy.contains(&provider.provider_identity().source) { return Err("providerBusy"); }
            let identity = provider.provider_identity();
            if gate.live.get(&(identity.kind, identity.name.clone())) != Some(&identity.source) { return Err("staleProviderSource"); }
            let receipt = gate.sources.get(&identity.source).ok_or("providerNotLoaded")?;
            if receipt.revision == 0 { return Err("providerNotLoaded"); }
            let value = meow_api::routes::provider_snapshot(entry.key(), provider);
            captured.push((entry.key().clone(), value, receipt.revision.to_string()));
        }
        drop(gate);
        let mut snapshots = serde_json::Map::new();
        for (name, captured, revision) in captured {
            let mut value = serde_json::to_value(captured).map_err(|_| "providerSerializationFailed")?;
            value.as_object_mut().ok_or("providerSerializationFailed")?.insert("revision".into(), revision.into());
            snapshots.insert(name, value);
        }
        match name {
            Some(name) => snapshots.remove(name).ok_or("providerNotFound"),
            None => Ok(serde_json::json!({"providers": snapshots})),
        }
    }
}
struct ProjectionPermit {
    candidate: meow_config::ProxyProviderProjection,
    graph: Option<crate::control::ProviderProjectionGraph>,
    revision:u64, selection_serial:u64,
}
struct ActiveLease { plane: Arc<ControlPlane>, identity: ProviderIdentity,
    admitted_epoch:Option<u64>, projection:Mutex<Option<ProjectionPermit>> }
impl ActiveLease {
    fn check_admitted_epoch(&self,gate:&PlaneState)->Result<(),String> {
        if self.admitted_epoch.is_some_and(|epoch|epoch!=gate.core_epoch) { return Err("staleProviderEpoch".into()); }
        Ok(())
    }
    fn check_projection(&self, projection:&meow_config::ProxyProviderProjection, gate:&PlaneState)->Result<(),String> {
        self.check_admitted_epoch(gate)?;
        if !gate.busy.contains(&self.identity.source) { return Err("providerLeaseNotActive".into()); }
        if projection.identity()!=&self.identity || !projection.is_current() { return Err("staleProviderProjection".into()); }
        if let Some(control)=&gate.group_control {
            let control=control.upgrade().ok_or("groupOwnerRetired")?;
            if !control.provider_projection_matches(projection) { return Err("providerProjectionSlotMismatch".into()); }
        }
        Ok(())
    }
}
impl Drop for ActiveLease {
    fn drop(&mut self) {
        let control = {
            let mut gate = self.plane.gate.lock();
            gate.busy.remove(&self.identity.source);
            // Any last lease may release a sequence deferred by another source.
            gate.group_control.as_ref().and_then(Weak::upgrade)
        };
        self.plane.changed.notify_one();
        if let Some(control) = control { control.notify_provider_groups(); }
    }
}
impl ProviderObserver for ControlPlane {
    fn validate(&self, kind: ProviderKind, name: &str) -> Result<(), String> {
        {
            let gate = self.gate.lock();
            if gate.closed || self.cancel.is_cancelled() { return Err("cancelled".into()); }
        }
        if name.chars().all(kotlin_whitespace) || name == "." || name == ".." || name.encode_utf16().count() > 1024 || name.chars().any(char::is_control) {
            return Err(format!("{kind:?}-providers.name: invalidProviderName"));
        }
        Ok(())
    }
    fn begin(&self, identity: &ProviderIdentity) -> Result<Arc<dyn ProviderLease>, String> {
        // Pure task-local read, never re-enter a ProbeSource while holding gate.
        let operation_epoch=meow_common::probe_policy::operation_epoch();
        let mut gate = self.gate.lock();
        if gate.closed || self.cancel.is_cancelled() { return Err("cancelled".into()); }
        let key = (identity.kind, identity.name.clone());
        if gate.active && gate.live.get(&key) != Some(&identity.source) { return Err("staleProviderSource".into()); }
        if gate.active && identity.kind==ProviderKind::Proxy && operation_epoch.is_some_and(|epoch|epoch!=gate.core_epoch) { return Err("staleProviderEpoch".into()); }
        // A reserved group delivery represents a durable provider-free state.
        // Do not admit a new load between its gate snapshot and door-out wake.
        if gate.groups_emitting { return Err("providerBusy".into()); }
        if !gate.busy.insert(identity.source) { return Err("providerBusy".into()); }
        gate.sources.entry(identity.source).or_default();
        Ok(Arc::new(ActiveLease { plane: self.this.upgrade().ok_or("controlPlaneRetired")?, identity: identity.clone(), admitted_epoch:(gate.active && identity.kind==ProviderKind::Proxy).then_some(gate.core_epoch), projection:Mutex::new(None) }))
    }
}
impl ProviderLease for ActiveLease {
    fn normalize_proxy_nodes(&self, document: &serde_yaml::Value, nodes: &mut Vec<std::collections::HashMap<String, serde_yaml::Value>>) -> Result<(), String> {
        {
            let gate = self.plane.gate.lock();
            if gate.closed || self.plane.cancel.is_cancelled() { return Err("cancelled".into()); }
        self.check_admitted_epoch(&gate)?;
            if gate.active && gate.live.get(&(self.identity.kind, self.identity.name.clone())) != Some(&self.identity.source) {
                return Err("staleProviderSource".into());
            }
        }
        if let Some(mapping) = document.as_mapping() {
            for key in mapping.keys() {
                let key = key.as_str().ok_or_else(|| format!("proxy-providers.{}.payload: expected string keys", self.identity.name))?;
                if key != "proxies" { return Err(format!("proxy-providers.{}.payload.{key}: no consumed payload field", self.identity.name)); }
            }
        }
        let mut root = serde_yaml::Mapping::new();
        root.insert(serde_yaml::Value::String("proxies".into()), serde_yaml::to_value(&*nodes).map_err(|error| error.to_string())?);
        let source = serde_yaml::to_string(&root).map_err(|error| error.to_string())?;
        let canonical = crate::config_validation::normalize_config_fields(&source)
            .map_err(|error| error.replace("$.proxies", &format!("proxy-providers.{}.proxies", self.identity.name)))?;
        let root: serde_yaml::Value = serde_yaml::from_str(&canonical).map_err(|error| error.to_string())?;
        *nodes = serde_yaml::from_value(root.get("proxies").cloned().ok_or("providerProjectionLostProxies")?)
            .map_err(|error| error.to_string())?;
        Ok(())
    }
    fn validate_proxy_members(&self, members: &[Arc<dyn meow_common::Proxy>], dialers: &[String]) -> Result<(), String> {
        let gate = self.plane.gate.lock();
        if gate.closed || self.plane.cancel.is_cancelled() { return Err("cancelled".into()); }
        self.check_admitted_epoch(&gate)?;
        if gate.active && gate.live.get(&(self.identity.kind, self.identity.name.clone())) != Some(&self.identity.source) {
            return Err("staleProviderSource".into());
        }
        let _=dialers; // Actual captured handles, never raw names, prove the graph.
        if let Some(control) = &gate.group_control {
            control.upgrade().ok_or("groupOwnerRetired")?.validate_member_observers(members)?;
        }
        for (index, member) in members.iter().enumerate() {
            crate::control::RouteControl::validate_provider_member(member.clone(), gate.group_control.is_some()).map_err(|_| format!(
                "proxy-providers.{}.proxies[{index}] ({}): managedProxyGraphNotIntegrated", self.identity.name, member.name()))?;
        }
        Ok(())
    }
    fn validate_proxy_projection(&self, projection:&meow_config::ProxyProviderProjection, _dialers:&[String])->Result<(),String> {
        let gate=self.plane.gate.lock();
        if gate.closed || self.plane.cancel.is_cancelled() { return Err("cancelled".into()); }
        if gate.active && gate.live.get(&(self.identity.kind,self.identity.name.clone()))!=Some(&self.identity.source) { return Err("staleProviderSource".into()); }
        self.check_projection(projection,&gate)?;
        let mut permit=self.projection.lock();
        if let Some(previous)=permit.as_ref() {
            if !previous.candidate.same_candidate(projection) || previous.graph.as_ref().is_some_and(|graph|!graph.is_current()) { return Err("providerProjectionCandidateChanged".into()); }
            return Ok(());
        }
        let graph=match &gate.group_control {
            Some(control)=>Some(control.upgrade().ok_or("groupOwnerRetired")?.validate_projected_provider_graph(projection,&gate)
                .map_err(|error|format!("proxy-providers.{}.proxies: {error}",self.identity.name))?),
            None=>{ for member in projection.members() { crate::control::RouteControl::validate_provider_member(member.clone(),false)
                .map_err(|_|format!("proxy-providers.{}.proxies: managedProxyGraphNotIntegrated",self.identity.name))?; } None },
        };
        *permit=Some(ProjectionPermit { candidate:projection.clone(),graph,revision:gate.revision,selection_serial:gate.selection_serial });
        Ok(())
    }
    fn commit_proxy_projection(&self, projection:&meow_config::ProxyProviderProjection, _dialers:&[String], install:&mut dyn FnMut())->Result<(),String> {
        self.commit_generation(true,Some(projection.members()),Some(projection),install)
    }
    fn commit_proxy(&self, members: &[Arc<dyn meow_common::Proxy>], dialers: &[String], install: &mut dyn FnMut()) -> Result<(), String> {
        self.validate_proxy_members(members, dialers)?;
        self.commit_generation(true, Some(members), None, install)
    }
    fn commit(&self, changed: bool, install: &mut dyn FnMut()) -> Result<(), String> {
        self.commit_generation(changed, None, None, install)
    }
}
impl ActiveLease {
    fn commit_generation(&self, changed: bool, members: Option<&[Arc<dyn meow_common::Proxy>]>, projection:Option<&meow_config::ProxyProviderProjection>,
        install: &mut dyn FnMut()) -> Result<(), String> {
        let mut gate = self.plane.gate.lock();
        if gate.closed || self.plane.cancel.is_cancelled() { return Err("cancelled".into()); }
        self.check_admitted_epoch(&gate)?;
        let key = (self.identity.kind, self.identity.name.clone());
        if gate.active && gate.live.get(&key) != Some(&self.identity.source) { return Err("staleProviderSource".into()); }
        if let Some(projection)=projection {
            self.check_projection(projection,&gate)?;
            let permit=self.projection.lock();let permit=permit.as_ref().ok_or("providerProjectionNotValidated")?;
            if gate.active && (permit.revision!=gate.revision || permit.selection_serial!=gate.selection_serial) { return Err("staleProviderIntent".into()); }
            if !permit.candidate.same_candidate(projection) || permit.graph.as_ref().is_some_and(|graph|!graph.is_current()) { return Err("staleProviderProjection".into()); }
        }
        let previous = gate.sources.get(&self.identity.source).ok_or("providerNotRegistered")?.revision;
        let next = previous.checked_add(1).ok_or("providerRevisionExhausted")?;
        let epoch_next = if changed { next_core_epoch(gate.core_epoch)? } else { gate.core_epoch };
        let sequence = gate.sequence.checked_add(1).ok_or("providerSequenceExhausted")?;
        let group = if self.identity.kind == ProviderKind::Proxy && changed {
            match &gate.group_control {
                Some(control) => Some(control.upgrade().ok_or("groupOwnerRetired")?), None => None,
            }
        } else { None };
        let prepared = if let Some(control) = &group {
            let members = members.ok_or("providerMembersRequired")?;
            let revision = gate.revision.checked_add(1).ok_or("picksRevisionExhausted")?;
            let groups_sequence = gate.groups_sequence.checked_add(1).ok_or("groupsSequenceExhausted")?;
            Some((match projection {
                Some(projection)=>control.prepare_projected_provider_commit(projection,&gate)?,
                None=>control.prepare_provider_commit(&self.identity.name,members,&gate)?,
            }, revision, groups_sequence))
        } else { None };
        install();
        if let (Some(control), Some((prepared, revision, groups_sequence))) = (&group, &prepared) {
            control.finish_provider_commit(prepared, &mut gate, *revision, *groups_sequence);
        }
        gate.sources.insert(self.identity.source, SourceReceipt { revision: next, changed });
        gate.core_epoch = epoch_next;
        gate.sequence = sequence;
        if gate.active {
            gate.pending.insert(key, (sequence, ProviderLoaded { kind: self.identity.kind, name: self.identity.name.clone(),
                revision: next.to_string(), phase: if previous == 0 { "initial" } else { "update" }, changed }));
        }
        drop(gate);
        // Retired adapters may own cancellable clients. Destructors stay outside
        // the short publish gate, like the upstream provider's retired slot.
        drop(prepared);
        Ok(())
    }
}

fn kotlin_whitespace(value: char) -> bool {
    matches!(value, '\u{0009}'..='\u{000d}' | '\u{001c}'..='\u{0020}' | '\u{00a0}' | '\u{1680}' |
        '\u{2000}'..='\u{200a}' | '\u{2028}' | '\u{2029}' | '\u{202f}' | '\u{205f}' | '\u{3000}')
}

fn next_core_epoch(previous: u64) -> Result<u64, String> {
    use std::sync::atomic::Ordering;
    let minimum = previous.checked_add(1).ok_or("coreEpochExhausted")?;
    let counter = crate::runtime::identity_counter();
    let mut current = counter.load(Ordering::Relaxed);
    loop {
        let epoch = current.max(minimum);
        let next = epoch.checked_add(1).ok_or("coreEpochExhausted")?;
        match counter.compare_exchange_weak(current, next, Ordering::Relaxed, Ordering::Relaxed) {
            Ok(_) => return Ok(epoch),
            Err(observed) => current = observed,
        }
    }
}
