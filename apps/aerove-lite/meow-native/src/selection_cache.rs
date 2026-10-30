//! Raw startup history is distinct from explicit user selection: never probe or flush here.
use meow_common::{AdapterType, Proxy};
use serde::Serialize;
use std::{collections::BTreeMap, sync::Arc};

pub(crate) struct CacheSelection {
    pub name: String,
    pub group: Arc<dyn Proxy>,
    pub effective: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct SelectionReadback {
    #[serde(rename = "type")]
    pub kind: String,
    pub current: String,
    pub members: Vec<String>,
    pub fixed: Option<String>,
}

#[derive(Debug)]
pub(crate) struct CacheError {
    pub group: String,
    pub reason: &'static str,
}

pub(crate) fn prepare(
    cache: &BTreeMap<String, String>,
    lookup: impl Fn(&str) -> Option<Arc<dyn Proxy>>,
    _managed_startup: bool,
) -> Result<Vec<CacheSelection>, CacheError> {
    let mut plan = Vec::new();
    for (name, requested) in cache {
        let Some(group) = lookup(name) else { continue; };
        let fail = |reason| CacheError { group: name.clone(), reason };
        if group.selection().is_none() { return Err(fail("groupDoesNotSupportSelection")); }
        let members = group.members().filter(|members| !members.is_empty())
            .ok_or_else(|| fail("groupHasNoActualMembers"))?;
        let valid = members.iter().any(|member| member == requested);
        let effective = match group.adapter_type() {
            AdapterType::Selector => if valid { requested.clone() } else { members[0].clone() },
            AdapterType::UrlTest => if valid { requested.clone() } else { String::new() },
            AdapterType::Fallback => requested.clone(),
            _ => return Err(fail("unsupportedCacheSelectionGroup")),
        };
        plan.push(CacheSelection { name: name.clone(), group, effective });
    }
    Ok(plan)
}

pub(crate) fn apply_memory(plan: &[CacheSelection]) -> Result<BTreeMap<String, StartupConsumption>, CacheError> {
    // Callers retain an unpublished graph or hold the existing managed plane gate.
    // Validate the whole batch before any memory mutation.
    for intent in plan {
        let fail = |reason| CacheError { group: intent.name.clone(), reason };
        let selection = intent.group.selection().ok_or_else(|| fail("selectionDisappeared"))?;
        if intent.effective.is_empty() {
            if !selection.can_unfix() { return Err(fail("groupCannotUnfix")); }
        } else if intent.group.adapter_type() != AdapterType::Fallback && !intent.group.members().is_some_and(|members| members.contains(&intent.effective)) {
            return Err(fail("groupMemberChanged"));
        }
    }
    // Standard installs the entire raw map before setup's readPicks consumes Now.
    for intent in plan {
        intent.group.selection().expect("validated selection").restore_startup_cache_memory(&intent.effective)
            .map_err(|_| CacheError { group: intent.name.clone(), reason: "memoryCommitFailed" })?;
    }
    let mut consumed = BTreeMap::new();
    for intent in plan {
        let selection = intent.group.selection().expect("validated selection");
        let transition = selection.consume_startup_cache();
        selection.reconcile_health();
        let expected_fixed = transition.as_ref().map(|value| value.fixed_after.as_str()).unwrap_or(&intent.effective);
        let applied = if selection.can_unfix() { selection.fixed().as_deref() == Some(expected_fixed) }
            else { intent.group.current().as_deref() == Some(intent.effective.as_str()) };
        if !applied { return Err(CacheError { group: intent.name.clone(), reason: "memoryReadbackMismatch" }); }
        if let Some(value) = transition {
            consumed.insert(intent.name.clone(), StartupConsumption {
                selected: value.selected, fixed_before: value.fixed_before, fixed_after: value.fixed_after,
            });
        }
    }
    Ok(consumed)
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StartupConsumption {
    pub selected: String,
    pub fixed_before: String,
    pub fixed_after: String,
}

pub(crate) fn readback(group: &Arc<dyn Proxy>) -> Option<SelectionReadback> {
    group.selection()?;
    Some(SelectionReadback { kind: group.adapter_type().to_string(), current: group.current()?,
        members: group.members()?, fixed: group.selection().and_then(|selection| selection.fixed()) })
}
