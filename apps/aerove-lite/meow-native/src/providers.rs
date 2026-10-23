pub use meow_config::owned_load::{LoadContext, OwnedLoadScope, OwnedWork, ProviderRoots};
use std::sync::Arc;
use tokio_util::sync::CancellationToken;

pub async fn load(
    yaml: &str, scope: &Arc<OwnedLoadScope>, cancel: &CancellationToken,
) -> Result<meow_config::Config, String> {
    load_with_cache(yaml, scope, cancel, None).await
}

/// The caller must supply a trusted app-private root captured by this Session.
pub async fn load_with_cache(
    yaml: &str, scope: &Arc<OwnedLoadScope>, cancel: &CancellationToken,
    cache_dir: Option<&std::path::Path>,
) -> Result<meow_config::Config, String> {
    let result = tokio::select! {
        biased;
        _ = cancel.cancelled() => Err("configLoadCancelled".to_owned()),
        result = meow_config::load_config_from_str_owned_with_cache(yaml, scope, cache_dir) => result.map_err(|error| error.to_string()),
    };
    if result.is_err() {
        scope.cancel();
        scope.close().await.map_err(str::to_owned)?;
    }
    result
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
    active: bool,
    live: BTreeMap<ProviderKey, u64>,
    admitted: BTreeMap<ProviderKey, u64>,
    sources: BTreeMap<u64, SourceReceipt>,
    busy: BTreeSet<u64>,
    emitting: BTreeSet<u64>,
    pending: BTreeMap<ProviderKey, (u64, ProviderLoaded)>,
    sequence: u64,
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
            core_epoch: epoch, revision: 0, closed: false, active: false, live: BTreeMap::new(), admitted: BTreeMap::new(),
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
                self.gate.lock().emitting.remove(&source);
            }
            tokio::select! { biased; _ = self.cancel.cancelled() => return, _ = notified => {} }
        }
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
struct ActiveLease { plane: Arc<ControlPlane>, identity: ProviderIdentity }
impl Drop for ActiveLease {
    fn drop(&mut self) {
        self.plane.gate.lock().busy.remove(&self.identity.source);
        self.plane.changed.notify_one();
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
        let mut gate = self.gate.lock();
        if gate.closed || self.cancel.is_cancelled() { return Err("cancelled".into()); }
        let key = (identity.kind, identity.name.clone());
        if gate.active && gate.live.get(&key) != Some(&identity.source) { return Err("staleProviderSource".into()); }
        if !gate.busy.insert(identity.source) { return Err("providerBusy".into()); }
        gate.sources.entry(identity.source).or_default();
        Ok(Arc::new(ActiveLease { plane: self.this.upgrade().ok_or("controlPlaneRetired")?, identity: identity.clone() }))
    }
}
impl ProviderLease for ActiveLease {
    fn commit(&self, changed: bool, install: &mut dyn FnMut()) -> Result<(), String> {
        let mut gate = self.plane.gate.lock();
        if gate.closed || self.plane.cancel.is_cancelled() { return Err("cancelled".into()); }
        let key = (self.identity.kind, self.identity.name.clone());
        if gate.active && gate.live.get(&key) != Some(&self.identity.source) { return Err("staleProviderSource".into()); }
        let previous = gate.sources.get(&self.identity.source).ok_or("providerNotRegistered")?.revision;
        let next = previous.checked_add(1).ok_or("providerRevisionExhausted")?;
        let epoch_next = if changed { next_core_epoch(gate.core_epoch)? } else { gate.core_epoch };
        let sequence = gate.sequence.checked_add(1).ok_or("providerSequenceExhausted")?;
        install();
        gate.sources.insert(self.identity.source, SourceReceipt { revision: next, changed });
        gate.core_epoch = epoch_next;
        gate.sequence = sequence;
        if gate.active {
            gate.pending.insert(key, (sequence, ProviderLoaded { kind: self.identity.kind, name: self.identity.name.clone(),
                revision: next.to_string(), phase: if previous == 0 { "initial" } else { "update" }, changed }));
        }
        drop(gate);
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
