//! Bounded offline resource parsing. No Core/runtime, sockets, fetches, listeners or global offline flags.
//! JNI must preserve Result: validation Ok -> null, Err -> error string; inspection/dump Err -> exception.
use std::{io::{Cursor, Read, Seek}, path::Path, sync::Arc};
use meow_config::{offline_rules::{self as rules, RuleSetBehavior}, owned_load::{LoadContext, OwnedLoadScope, OwnedWork}};
use serde_yaml::Value;
use tokio_util::sync::CancellationToken;

/// Same captured context as the fixed Core's private offline validator; only the scope owner differs.
pub(crate) struct OfflineConversion;
impl LoadContext for OfflineConversion {
    fn offline_validation(&self) -> bool { true }
    fn wrap(&self, work: OwnedWork) -> OwnedWork { work }
    fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) { work(); }
}

/// Official MRS expansion: equal to the fixed Core's MAX_DECOMPRESSED_BYTES, which MrsReadControl clamps to anyway.
/// The Core also bounds decoded domain-set and IP-prefix output; the host adds no lower expansion fence.
pub const MAX_MRS_EXPANDED_BYTES: usize = 256 * 1024 * 1024;
/// Every resource input, official MRS compressed files included.
pub const MAX_INPUT_BYTES: usize = 64 * 1024 * 1024;
/// Configuration and proxy-provider text.
pub const MAX_TEXT_BYTES: usize = 8 * 1024 * 1024;
/// Rule-set text for preview and dump. Official MRS dumps add no host entry cap; the Core's decoded-output bounds apply.
pub const MAX_DUMP_TEXT_BYTES: usize = 64 * 1024 * 1024;
/// Text and YAML rule-provider input share the preview and dump text budget.
pub const MAX_RULE_TEXT_BYTES: usize = MAX_DUMP_TEXT_BYTES;
pub const MAX_ENTRIES: usize = 250_000;
/// One trimmed text or YAML rule entry.
pub const MAX_ENTRY_BYTES: usize = 2048;
/// Values one rule YAML may expand to, counted as the Kotlin reader counts them: the root mapping, its list and the entries.
pub const MAX_YAML_RULE_VALUES: usize = MAX_ENTRIES + 2;
pub const MAX_EXPANDED_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_TRIE_NODES: usize = 500_000;
pub const MAX_TRIE_DEPTH: usize = 128;
fn too_many_entries() -> String { format!("rule-provider payload exceeds {MAX_ENTRIES} entries") }
fn entry_too_wide(index: usize) -> String { format!("rule-provider entry {index} exceeds {MAX_ENTRY_BYTES} bytes") }

/// Real typed syntax validation; deliberately distinct from full conversion/start admission.
pub fn validate_config_syntax(source: &str) -> Result<(), String> {
    if source.len() > MAX_TEXT_BYTES { return Err("$: configuration input limit exceeded".into()); }
    meow_config::parse_raw_yaml(source).map_err(|e| format!("$: {e}"))?;
    Ok(())
}

/// Runs the real fixed parser/converter inside an owned offline load scope; never flips CLI globals.
pub async fn validate_config(source: &str) -> Result<(), String> {
    let normalized = normalize_config_fields(source)?;
    let work: futures::future::BoxFuture<'_, Result<(), String>> = Box::pin(meow_config::validate_offline_config(&normalized));
    work.await
}

// The Core conversion state must not expand inside Native cancellation and worker futures.
pub(crate) fn scoped_offline_validation<'a>(source: &'a str, scope: &'a Arc<OwnedLoadScope>)
    -> futures::future::BoxFuture<'a, Result<(), String>> {
    Box::pin(meow_config::validate_offline_config_with_scope(source, scope))
}

pub(crate) fn scoped_prepared_validation<'a>(source: &'a str, scope: &'a Arc<OwnedLoadScope>)
    -> futures::future::BoxFuture<'a, Result<(), String>> {
    Box::pin(meow_config::validate_prepared_config_with_scope(source, scope))
}

/// Strict field projection only; callers must still await validate_config for real conversion.
pub fn normalize_config_fields(source: &str) -> Result<String, String> {
    validate_config_syntax(source)?;
    let mut original: Value = serde_yaml::from_str(source).map_err(|e| e.to_string())?;
    original.apply_merge().map_err(|e| e.to_string())?;
    normalize_proxy_options(&mut original)?;
    let normalized = serde_yaml::to_string(&original).map_err(|e| e.to_string())?;
    let raw = meow_config::parse_raw_yaml(&normalized).map_err(|e| format!("$: {e}"))?;
    if raw.strict == Some(false) { return Err("$.strict: strict native conversion cannot be disabled".into()); }
    let typed = serde_yaml::to_value(&raw).map_err(|e| e.to_string())?;
    require_preserved_keys(&original, &typed, "$")?;
    if let Some(nodes) = original.get("proxies").and_then(Value::as_sequence) {
        for (index, node) in nodes.iter().enumerate() { proxy_fields(node, &format!("$.proxies[{index}]"))?; }
    }
    Ok(normalized)
}

pub fn inspect_rule_set(path: &Path) -> Result<String, String> {
    inspect_rule_set_controlled(path, Default::default())
}
pub fn inspect_rule_set_controlled(path: &Path, cancel: tokio_util::sync::CancellationToken) -> Result<String, String> {
    let accepted = read_rule_summary_controlled(path, None, None, cancel)?;
    serde_json::to_string(&serde_json::json!({"format": accepted.format, "behavior": name(accepted.behavior), "count": accepted.count})).map_err(|e| e.to_string())
}
pub fn dump_rule_set(path: &Path, behavior: &str, format: &str) -> Result<String, String> {
    dump_rule_set_controlled(path, behavior, format, Default::default())
}
pub fn dump_rule_set_controlled(path: &Path, behavior: &str, format: &str, cancel: tokio_util::sync::CancellationToken) -> Result<String, String> {
    let expected = parse_behavior(behavior).map_err(|e| invalid_rule("$.behavior", e))?;
    let format = canonical_format(format).map_err(|e| invalid_rule("$.format", e))?;
    let (file, magic) = rule_file(path, &cancel)?;
    if magic == rules::mrs_parser::ZSTD_MAGIC {
        return read_upstream_file(file, Some(expected), Some(format), cancel, true).map(|(_, text)| text.unwrap());
    }
    let bytes = read_rule_input(file, Some(format), magic, &cancel)?;
    let accepted = read_rule_payload(&bytes, Some(expected), Some(format)).map_err(payload_error)?;
    let mut text = String::new();
    for entry in accepted.entries {
        check_cancelled(&cancel)?;
        if text.len().saturating_add(entry.len()).saturating_add(1) > MAX_DUMP_TEXT_BYTES {
            return Err(invalid_rule("$.payload", format!("rule-set text exceeds {MAX_DUMP_TEXT_BYTES} bytes")));
        }
        text.push_str(&entry); text.push('\n');
    }
    check_cancelled(&cancel)?;
    Ok(text)
}
pub async fn validate_resource_controlled(kind: &str, path: &Path, behavior: &str, format: &str, cancel: tokio_util::sync::CancellationToken) -> Result<(), String> {
    check_cancelled(&cancel)?;
    if matches!(kind.to_ascii_lowercase().as_str(), "ruleset" | "rule-provider") {
        read_rule_summary_controlled(path, Some(parse_behavior(behavior).map_err(|e| invalid_rule("$.behavior", e))?),
            Some(canonical_format(format).map_err(|e| invalid_rule("$.format", e))?), cancel)?;
        return Ok(());
    }
    let result = validate_resource_with(kind, path, behavior, format, &cancel).await;
    check_cancelled(&cancel)?;
    result
}

pub async fn validate_resource(kind: &str, path: &Path, behavior: &str, format: &str) -> Result<(), String> {
    let cancel = CancellationToken::new();
    validate_resource_with(kind, path, behavior, format, &cancel).await
}

async fn validate_resource_with(kind: &str, path: &Path, behavior: &str, format: &str, cancel: &CancellationToken) -> Result<(), String> {
    match kind.to_ascii_lowercase().as_str() {
        "ruleset" | "rule-provider" => { read_rule_summary_controlled(path, Some(parse_behavior(behavior).map_err(|e| invalid_rule("$.behavior", e))?), Some(canonical_format(format).map_err(|e| invalid_rule("$.format", e))?), cancel.clone())?; Ok(()) }
        geo @ ("mmdb" | "geosite") => validate_geo(geo, path, behavior, format, &|| cancel.is_cancelled()),
        "proxy-provider" => validate_proxy_payload(path, behavior, format, cancel).await,
        other => Err(format!("unsupported resource kind: {other}")),
    }
}

fn validate_geo(kind: &str, path: &Path, behavior: &str, format: &str, cancelled: &(dyn Fn() -> bool + Sync)) -> Result<(), String> {
    if kind == "mmdb" {
        if !behavior.is_empty() || !matches!(format.to_ascii_lowercase().as_str(), "" | "mmdb") { return Err("mmdb expects empty behavior and empty/mmdb format".into()); }
        let bytes = read_geo(path, cancelled)?;
        let reader = meow_config::offline_maxminddb::Reader::from_source(bytes).map_err(|e| format!("MMDB decode: {e}"))?;
        if reader.metadata.node_count as usize > MAX_EXPANDED_BYTES / 6 { return Err("MMDB search tree limit exceeded".into()); }
        stop(cancelled)?;
        return reader.verify().map_err(|e| format!("MMDB structure verification: {e}"));
    }
    if !behavior.is_empty() || !matches!(format.to_ascii_lowercase().as_str(), "" | "dat" | "protobuf") { return Err("geosite expects empty behavior and empty/dat/protobuf format".into()); }
    let bytes = read_geo(path, cancelled)?;
    // Checked entry point must reject skipped empty/bad-regex entries, unlike original warn-only loader.
    let db = rules::geosite_dat::from_dat_bytes_checked(&bytes, None).map_err(|e| e.to_string())?;
    if db.category_count() == 0 { return Err("geosite has no accepted categories".into()); }
    Ok(())
}
fn read_geo(path: &Path, cancelled: &(dyn Fn() -> bool + Sync)) -> Result<Vec<u8>, String> {
    #[cfg(test)]
    let probe = geo_probe::find(path);
    #[cfg(test)]
    let observed = || probe.as_ref().map_or_else(|| cancelled(), |probe| probe.check(cancelled));
    #[cfg(test)]
    let cancelled: &(dyn Fn() -> bool + Sync) = &observed;
    let bytes = read_open_file_until(open_regular_file(path)?, MAX_INPUT_BYTES, cancelled)?;
    let bytes = unpack_geodata_until(&bytes, MAX_EXPANDED_BYTES, cancelled)?;
    stop(cancelled)?;
    Ok(bytes)
}

struct AcceptedRules { format: &'static str, behavior: RuleSetBehavior, entries: Vec<String>, count: usize }
struct RuleSummary { format: &'static str, behavior: RuleSetBehavior, count: usize }
fn read_rule_summary_controlled(path: &Path, behavior: Option<RuleSetBehavior>, format: Option<&'static str>, cancel: tokio_util::sync::CancellationToken) -> Result<RuleSummary, String> {
    let (file, magic) = rule_file(path, &cancel)?;
    if magic == rules::mrs_parser::ZSTD_MAGIC {
        return read_upstream_file(file, behavior, format, cancel, false).map(|(summary, _)| summary);
    }
    let bytes = read_rule_input(file, format, magic, &cancel)?;
    let accepted = read_rule_payload(&bytes, behavior, format).map_err(payload_error)?;
    check_cancelled(&cancel)?;
    Ok(RuleSummary { format: accepted.format, behavior: accepted.behavior, count: accepted.count })
}
fn check_cancelled(cancel: &tokio_util::sync::CancellationToken) -> Result<(), String> {
    stop(&|| cancel.is_cancelled())
}
fn stop(cancelled: &(dyn Fn() -> bool + Sync)) -> Result<(), String> {
    if cancelled() { Err("resourceReadCancelled".into()) } else { Ok(()) }
}
fn rule_file(path: &Path, cancel: &tokio_util::sync::CancellationToken) -> Result<(std::fs::File, [u8; 4]), String> {
    check_cancelled(cancel)?;
    let mut file = open_regular_file(path)?;
    let mut magic = [0; 4];
    let mut filled = 0;
    while filled < magic.len() {
        match file.read(&mut magic[filled..]) {
            Ok(0) => break,
            Ok(count) => filled += count,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e.to_string()),
        }
    }
    file.rewind().map_err(|e| e.to_string())?;
    if magic == rules::mrs_parser::ZSTD_MAGIC && file.metadata().map_err(|e| e.to_string())?.len() > MAX_INPUT_BYTES as u64 {
        return Err(format!("resource input exceeds {MAX_INPUT_BYTES} bytes"));
    }
    check_cancelled(cancel)?;
    Ok((file, magic))
}
struct BoundedRuleDump {
    text: parking_lot::Mutex<String>, overflow: std::sync::atomic::AtomicBool,
}
impl rules::rule_set::AcceptedEntrySink for BoundedRuleDump {
    fn accepted(&self, entry: &str) {
        use std::sync::atomic::Ordering;
        if self.overflow.load(Ordering::Acquire) { return; }
        let mut text = self.text.lock();
        if text.len().saturating_add(entry.len()).saturating_add(1) > MAX_DUMP_TEXT_BYTES {
            self.overflow.store(true, Ordering::Release);
        } else { text.push_str(entry); text.push('\n'); }
    }
}
fn read_upstream_file(file: std::fs::File, behavior: Option<RuleSetBehavior>, format: Option<&'static str>, cancel: tokio_util::sync::CancellationToken, dump: bool) -> Result<(RuleSummary, Option<String>), String> {
    use rules::mrs_parser::{MrsReadControl, UpstreamRuleSetReader};
    use std::sync::{Arc, atomic::Ordering};
    if format.is_some_and(|f| f != "mrs") { return Err(invalid_rule("$.format", "rule_set_format_mismatch")); }
    let sink = dump.then(|| Arc::new(BoundedRuleDump { text: Default::default(), overflow: Default::default() }));
    let observer = sink.clone();
    let control = MrsReadControl::new(MAX_INPUT_BYTES as u64, MAX_MRS_EXPANDED_BYTES as u64,
        move || cancel.is_cancelled() || observer.as_ref().is_some_and(|sink| sink.overflow.load(Ordering::Acquire)));
    let reader = UpstreamRuleSetReader::open_from_reader(file, control.clone()).map_err(|e| invalid_rule("$.payload.mrs", e))?;
    let actual = behavior_tag(reader.behavior()).map_err(|e| invalid_rule("$.payload.mrs", e))?;
    if behavior.is_some_and(|expected| expected != actual) { return Err(invalid_rule("$.behavior", "rule_set_behavior_mismatch")); }
    let result = rules::rule_set::build_rule_set_from_upstream_reader(reader, behavior, true,
        sink.clone().map(|sink| sink as Arc<dyn rules::rule_set::AcceptedEntrySink>));
    if sink.as_ref().is_some_and(|sink| sink.overflow.load(Ordering::Acquire)) {
        return Err(invalid_rule("$.payload", format!("rule-set text exceeds {MAX_DUMP_TEXT_BYTES} bytes")));
    }
    let matcher = result.map_err(|e| invalid_rule("$.payload.mrs", e))?;
    control.check().map_err(|e| invalid_rule("$.payload.mrs", e))?;
    let summary = RuleSummary { format: "mrs", behavior: actual, count: matcher.len() };
    Ok((summary, sink.map(|sink| std::mem::take(&mut *sink.text.lock()))))
}
fn payload_error(reason: String) -> String {
    if reason.starts_with("rule_set_invalid:") { reason } else { invalid_rule("$.payload", reason) }
}
fn invalid_rule(field: &str, reason: impl std::fmt::Display) -> String {
    format!("rule_set_invalid: {field}: {reason}")
}
fn read_rule_payload(bytes: &[u8], behavior: Option<RuleSetBehavior>, format: Option<&'static str>) -> Result<AcceptedRules, String> {
    let accepted = decode_rule_payload(bytes, behavior, format)?;
    compile_rule_payload(&accepted)?;
    Ok(accepted)
}
fn decode_rule_payload(bytes: &[u8], behavior: Option<RuleSetBehavior>, format: Option<&'static str>) -> Result<AcceptedRules, String> {
    let is_mrs = rules::is_mrs_bytes(bytes);
    let (actual_format, actual_behavior, entries, source_count) = if is_mrs {
        let (b, entries, count) = decode_mrs(bytes).map_err(|e| invalid_rule("$.payload.mrs", e))?;
        ("mrs", b, entries, count)
    } else {
        if bytes.len() > MAX_RULE_TEXT_BYTES { return Err(format!("rule-provider text input exceeds {MAX_RULE_TEXT_BYTES} bytes")); }
        let source = std::str::from_utf8(bytes).map_err(|e| invalid_rule("$.payload.utf8", e))?;
        // Text never pays for a YAML parse whose result it would discard.
        let (fmt, lines) = match format {
            Some("yaml") => ("yaml", yaml_payload(source).map_err(|e| invalid_rule("$.payload.yaml", e))?),
            Some("text") => ("text", text_lines(source)?),
            _ => match yaml_payload(source) { Ok(lines) => ("yaml", lines), Err(_) => ("text", text_lines(source)?) },
        };
        let (classified, normalized) = classify(lines, behavior)?;
        let count = normalized.len();
        (fmt, classified, normalized, count)
    };
    if let Some(expected) = format { if expected != actual_format { return Err(invalid_rule("$.format", "rule_set_format_mismatch")); } }
    if let Some(expected) = behavior { if expected != actual_behavior { return Err(invalid_rule("$.behavior", "rule_set_behavior_mismatch")); } }
    bound_entries(&entries)?;
    Ok(AcceptedRules { format: actual_format, behavior: actual_behavior, entries, count: source_count })
}
fn compile_rule_payload(accepted: &AcceptedRules) -> Result<(), String> {
    let compiled = rules::build_rule_set_checked(accepted.behavior, &accepted.entries, &Default::default()).map_err(|e| invalid_rule("$.payload", e))?;
    if compiled.len() != accepted.entries.len() { return Err("rule_set_accepted_count_mismatch".into()); }
    Ok(())
}

/// Preparation-only dependency receipt. Pending resources still require full prepared conversion.
pub fn preflight_rule_resource(path: &Path, behavior: &str, format: &str) -> Result<String, String> {
    preflight_rule_resource_controlled(path, behavior, format, Default::default())
}
pub fn preflight_rule_resource_controlled(path: &Path, behavior: &str, format: &str, cancel: tokio_util::sync::CancellationToken) -> Result<String, String> {
    let (file, magic) = rule_file(path, &cancel)?;
    if magic == rules::mrs_parser::ZSTD_MAGIC {
        let expected = parse_behavior(behavior).map_err(|e| invalid_rule("$.behavior", e))?;
        let format = canonical_format(format).map_err(|e| invalid_rule("$.format", e))?;
        let (accepted, _) = read_upstream_file(file, Some(expected), Some(format), cancel, false)?;
        return serde_json::to_string(&serde_json::json!({
            "state":"validated", "format":accepted.format, "behavior":name(accepted.behavior), "count":accepted.count,
            "requires":{"geoip":false,"asn":false,"geosite":false}
        })).map_err(|e| e.to_string());
    }
    let bytes = read_rule_input(file, canonical_format(format).ok(), magic, &cancel)?;
    let accepted = decode_rule_payload(&bytes,
        Some(parse_behavior(behavior).map_err(|e| invalid_rule("$.behavior", e))?),
        Some(canonical_format(format).map_err(|e| invalid_rule("$.format", e))?))?;
    let required = meow_config::rule_geodata_requirements(&accepted.entries);
    let pending = required.geoip || required.asn || required.geosite;
    if !pending { compile_rule_payload(&accepted)?; }
    else if accepted.behavior == RuleSetBehavior::Classical {
        for entry in &accepted.entries {
            let single = vec![entry.clone()];
            let deps = meow_config::rule_geodata_requirements(&single);
            if !(deps.geoip || deps.asn || deps.geosite) {
                rules::build_rule_set_checked(accepted.behavior, &single, &Default::default())
                    .map_err(|e| invalid_rule("$.payload", e))?;
            }
        }
    }
    serde_json::to_string(&serde_json::json!({
        "state": if pending { "requiresPreparedContext" } else { "validated" },
        "format": accepted.format, "behavior": name(accepted.behavior), "count": accepted.count,
        "requires": {"geoip":required.geoip,"asn":required.asn,"geosite":required.geosite}
    })).map_err(|e| e.to_string())
}

fn decode_mrs(bytes: &[u8]) -> Result<(RuleSetBehavior, Vec<String>, usize), String> {
    use rules::mrs_parser as mrs;
    if bytes.starts_with(&mrs::ZSTD_MAGIC) {
        return Err("official MRS requires the controlled file reader".into());
    }
    let (header, payload) = mrs::parse_header(bytes).map_err(|e| e.to_string())?;
    if header.count as usize > MAX_ENTRIES { return Err(format!("MRS! header declares {} entries, over {MAX_ENTRIES}", header.count)); }
    let expanded = mrs::decompress_payload_bounded(payload, MAX_EXPANDED_BYTES as u64).map_err(|e| e.to_string())?;
    let behavior = behavior_tag(header.type_tag)?;
    let mut entries = Vec::new();
    let mut overflow = false;
    let mut append = |s: String| { if entries.len() < MAX_ENTRIES { entries.push(s); } else { overflow = true; } };
    if behavior == RuleSetBehavior::IpCidr { mrs::stream_ipcidr_list(Cursor::new(expanded), |n| append(n.to_string())).map_err(|e| e.to_string())?; }
    else { mrs::stream_string_list(Cursor::new(expanded), |s| append(s.to_owned())).map_err(|e| e.to_string())?; }
    if overflow { return Err(format!("MRS! payload exceeds {MAX_ENTRIES} entries")); }
    if entries.len() != header.count as usize { return Err("rule_set_mrs_count_mismatch".into()); }
    Ok((behavior, entries, header.count as usize))
}

fn classify(entries: Vec<String>, requested: Option<RuleSetBehavior>) -> Result<(RuleSetBehavior, Vec<String>), String> {
    if entries.is_empty() { return Ok((requested.unwrap_or(RuleSetBehavior::Classical), entries)); }
    let mut domain = Vec::new(); let mut cidr = Vec::new(); let mut classical = false;
    for entry in &entries {
        if let Some((kind, value)) = entry.split_once(',') {
            let value = value.trim();
            if value.contains(',') { classical = true; break; }
            match kind.trim().to_ascii_uppercase().as_str() {
                "DOMAIN" => domain.push(value.to_owned()),
                "DOMAIN-SUFFIX" => domain.push(format!("+.{value}")),
                "IP-CIDR" | "IP-CIDR6" => { let net = value.parse::<ipnet::IpNet>().map_err(|e| e.to_string())?; cidr.push(net.to_string()); }
                _ => { classical = true; break; }
            }
        } else if let Ok(net) = entry.parse::<ipnet::IpNet>() { cidr.push(net.to_string()); }
        else if let Ok(ip) = entry.parse::<std::net::IpAddr>() { cidr.push(format!("{ip}/{}", if ip.is_ipv4() { 32 } else { 128 })); }
        else { domain.push(entry.to_owned()); }
    }
    if requested == Some(RuleSetBehavior::Classical) || classical || (!domain.is_empty() && !cidr.is_empty() && entries.iter().any(|e| e.contains(','))) { return Ok((RuleSetBehavior::Classical, entries)); }
    if !domain.is_empty() && !cidr.is_empty() { return Err("rule_set_mixed: domains and IP ranges".into()); }
    if cidr.is_empty() { Ok((RuleSetBehavior::Domain, domain)) } else { Ok((RuleSetBehavior::IpCidr, cidr)) }
}

async fn validate_proxy_payload(path: &Path, behavior: &str, format: &str, cancel: &CancellationToken) -> Result<(), String> {
    if !behavior.is_empty() || !matches!(format.to_ascii_lowercase().as_str(), "yaml" | "yml") { return Err("proxy-provider expects empty behavior and YAML format".into()); }
    let bytes = read_open_file(open_regular_file(path)?, MAX_TEXT_BYTES, cancel)?;
    let source = std::str::from_utf8(&bytes).map_err(|e| e.to_string())?;
    // Provider bytes are a real config-shaped node payload. Use the same native offline factory,
    // with external process admission disabled by the owned scope, rather than a syntax-only pass.
    let mut payload: Value = serde_yaml::from_str(source).map_err(|e| e.to_string())?;
    payload.apply_merge().map_err(|e| e.to_string())?;
    let mapping = payload.as_mapping().ok_or("proxy-provider expects a mapping")?;
    if mapping.keys().any(|k| k.as_str() != Some("proxies")) { return Err("proxy-provider payload accepts only proxies".into()); }
    let nodes = payload.get("proxies").and_then(Value::as_sequence).ok_or("proxy-provider requires proxies list")?;
    if nodes.is_empty() || nodes.len() > MAX_ENTRIES { return Err("proxy-provider empty or excessive proxies list".into()); }
    check_cancelled(cancel)?;
    let normalized = normalize_config_fields(source)?;
    validate_offline_cancellable(&normalized, cancel, Arc::new(OfflineConversion)).await
}

/// The fixed converter observes its scope between owned jobs; forward the request token there, and
/// join that scope before returning or unwinding because the converter only cancels it on drop.
async fn validate_offline_cancellable(normalized: &str, cancel: &CancellationToken, context: Arc<dyn LoadContext>) -> Result<(), String> {
    use futures::FutureExt;
    let scope = OwnedLoadScope::with_context(context);
    let forward = async {
        cancel.cancelled().await;
        scope.cancel();
        std::future::pending::<std::convert::Infallible>().await
    };
    let converted = std::panic::AssertUnwindSafe(async {
        tokio::select! {
            biased;
            result = scoped_offline_validation(normalized, &scope) => result,
            never = forward => match never {},
        }
    }).catch_unwind().await;
    let closed = scope.close().await.map_err(str::to_owned);
    match converted {
        Ok(result) => result.and(closed),
        Err(panic) => std::panic::resume_unwind(panic),
    }
}
fn normalize_proxy_options(root: &mut Value) -> Result<(), String> {
    let Some(nodes) = root.get_mut("proxies").and_then(Value::as_sequence_mut) else { return Ok(()); };
    for (i, node) in nodes.iter_mut().enumerate() {
        let path = format!("$.proxies[{i}]");
        let Some(m) = node.as_mapping_mut() else { return Err(format!("{path}: expected mapping")); };
        let kind = m.get(&Value::String("type".into())).and_then(Value::as_str).unwrap_or("").to_owned();
        if ["vmess", "vless"].contains(&kind.as_str()) {
            mapping_alias(m, "server-name", "servername", &path)?;
            if kind == "vmess" { mapping_alias(m, "alter-id", "alterId", &path)?; }
            if kind == "vless" { mapping_alias(m, "splithttp-opts", "xhttp-opts", &path)?; }
        }
        if ["ss", "trojan", "vmess", "vless"].contains(&kind.as_str()) { mapping_alias(m, "mux", "smux", &path)?; }
        if kind != "ss" { continue; }
        let plugin = m.get(&Value::String("plugin".into())).and_then(Value::as_str).map(str::to_owned);
        let opts = m.get(&Value::String("plugin-opts".into())).cloned();
        if opts.is_some() && plugin.is_none() { return Err(format!("{path}.plugin-opts: no selected plugin consumer")); }
        let Some(plugin) = plugin else { continue; };
        if m.contains_key(&Value::String("client-fingerprint".into())) && plugin != "shadow-tls" { return Err(format!("{path}.client-fingerprint: only shadow-tls consumes node TLS fingerprint")); }
        let builtin = ["obfs", "simple-obfs", "v2ray-plugin", "gost-plugin", "shadow-tls", "restls", "jls", "kcptun", "ech-tls-tunnel"].contains(&plugin.as_str());
        if !builtin { continue; } // actual owned converter rejects external plugins before execution
        if m.get(&Value::String("udp".into())).and_then(Value::as_bool) == Some(true) && !["obfs", "simple-obfs", "kcptun"].contains(&plugin.as_str()) { return Err(format!("{path}.udp: {plugin} does not implement UDP relay")); }
        if let Some(opts) = opts {
            let normalized = normalize_plugin_opts(&plugin, &opts, &format!("{path}.plugin-opts"))?;
            m.insert(Value::String("plugin-opts".into()), Value::String(normalized));
        }
    }
    Ok(())
}
fn mapping_alias(map: &mut serde_yaml::Mapping, old: &str, key: &str, path: &str) -> Result<(), String> {
    let old_key = Value::String(old.into()); let key_value = Value::String(key.into());
    if let Some(value) = map.remove(&old_key) {
        if map.get(&key_value).is_some_and(|v| *v != value) { return Err(format!("{path}.{old}: conflicts with {key}")); }
        map.insert(key_value, value);
    }
    Ok(())
}

fn normalize_plugin_opts(plugin: &str, opts: &Value, path: &str) -> Result<String, String> {
    let mut tokens = Vec::<(String, String)>::new();
    match opts {
        Value::String(raw) => for token in raw.split(';').map(str::trim).filter(|s| !s.is_empty()) {
            let (key, value) = token.split_once('=').unwrap_or((token, "true"));
            tokens.push((key.trim().to_ascii_lowercase(), value.trim().to_owned()));
        },
        Value::Mapping(map) => for (key, value) in map {
            let key = key.as_str().ok_or_else(|| format!("{path}: expected string option names"))?.to_ascii_lowercase();
            if key.contains([';', '=']) { return Err(format!("{path}.{key}: option name contains separator")); }
            if key == "headers" {
                let headers = value.as_mapping().ok_or_else(|| format!("{path}.headers: expected mapping"))?;
                for (name, value) in headers {
                    let name = name.as_str().ok_or_else(|| format!("{path}.headers: expected string header name"))?;
                    if name.is_empty() || name.contains([';', '=', ':']) { return Err(format!("{path}.headers.{name}: invalid header name")); }
                    tokens.push(("header".into(), format!("{name}:{}", plugin_scalar(value, &format!("{path}.headers.{name}"))?)));
                }
            } else if key == "ech-opts" {
                let nested = value.as_mapping().ok_or_else(|| format!("{path}.ech-opts: expected mapping"))?;
                for (name, value) in nested { let name = name.as_str().ok_or_else(|| format!("{path}.ech-opts: expected string key"))?; tokens.push((format!("ech-opts.{name}"), plugin_scalar(value, &format!("{path}.ech-opts.{name}"))?)); }
            } else if let Value::Sequence(items) = value {
                if key != "alpn" { return Err(format!("{path}.{key}: no list consumer")); }
                let mut strings = Vec::new();
                for (index, value) in items.iter().enumerate() { let v = value.as_str().ok_or_else(|| format!("{path}.{key}[{index}]: expected string"))?; if v.contains([',', ';']) { return Err(format!("{path}.{key}[{index}]: list separator would alter serialization")); } strings.push(v); }
                tokens.push((key, strings.join(",")));
            } else { tokens.push((key.clone(), plugin_scalar(value, &format!("{path}.{key}"))?)); }
        },
        _ => return Err(format!("{path}: expected SIP003 string or mapping")),
    }
    let allowed: &[&str] = match plugin {
        "obfs" | "simple-obfs" => &["mode", "host"],
        "v2ray-plugin" => &["mode", "tls", "host", "path", "mux", "skip-cert-verify", "header"],
        "gost-plugin" => &["mode", "tls", "host", "path", "mux", "skip-cert-verify", "name-cert-verify", "fingerprint", "certificate", "private-key", "ech-opts.enable", "ech-opts.config", "header"],
        "shadow-tls" => &["host", "password", "version", "alpn", "skip-cert-verify", "name-cert-verify", "fingerprint", "strict-mode", "certificate", "private-key"],
        "restls" => &["host", "password", "version-hint", "restls-script", "skip-cert-verify", "force-tls12", "name-cert-verify", "fingerprint"],
        "jls" => &["host", "username", "password", "alpn", "skip-cert-verify"],
        "kcptun" => &["key", "crypt", "mode", "conn", "autoexpire", "scavengettl", "mtu", "ratelimit", "sndwnd", "rcvwnd", "datashard", "parityshard", "dscp", "nocomp", "acknodelay", "nodelay", "interval", "resend", "nc", "sockbuf", "smuxver", "smuxbuf", "framesize", "streambuf", "keepalive"],
        "ech-tls-tunnel" => &["mode", "sni", "path", "ech-config", "client-fingerprint", "fast-open"],
        _ => return Err(format!("{path}: no built-in plugin schema")),
    };
    let mut accepted = std::collections::BTreeMap::<String, (String, String)>::new();
    for (mut key, mut value) in tokens {
        if ["obfs", "simple-obfs"].contains(&plugin) { key = match key.as_str() { "obfs" => "mode".into(), "obfs-host" => "host".into(), _ => key }; }
        if plugin == "gost-plugin" { key = match key.as_str() { "ech-enable" => "ech-opts.enable".into(), "ech-config" => "ech-opts.config".into(), _ => key }; }
        if plugin == "jls" && key == "skip_cert_verify" { key = "skip-cert-verify".into(); }
        if plugin == "ech-tls-tunnel" { key = match key.as_str() { "ech_config" => "ech-config".into(), "fingerprint" | "client_fingerprint" => "client-fingerprint".into(), "fast_open" => "fast-open".into(), _ => key }; }
        if !allowed.contains(&key.as_str()) { return Err(format!("{path}.{key}: no consumed field for {plugin}")); }
        if value.contains(';') { return Err(format!("{path}.{key}: separator would drop or inject options")); }
        if ["tls", "mux", "skip-cert-verify", "strict-mode", "force-tls12", "ech-opts.enable", "fast-open", "nocomp", "acknodelay"].contains(&key.as_str()) {
            value = match value.to_ascii_lowercase().as_str() { "1" | "true" | "yes" | "on" => "true".into(), "" | "0" | "false" | "no" | "off" => "false".into(), _ => return Err(format!("{path}.{key}: unrecognized boolean would be coerced")) };
        }
        if ["mode", "crypt", "version-hint"].contains(&key.as_str()) { value = value.to_ascii_lowercase(); }
        let identity = if key == "header" {
            let (name, _) = value.split_once(':').ok_or_else(|| format!("{path}.header: expected name:value"))?;
            if name.trim().is_empty() { return Err(format!("{path}.header: empty header name")); }
            format!("header:{}", name.trim().to_ascii_lowercase())
        } else { key.clone() };
        if let Some((_, prior)) = accepted.get(&identity) { let same = if key == "header" { prior.split_once(':').map(|(_, v)| v.trim()) == value.split_once(':').map(|(_, v)| v.trim()) } else { prior == &value };
            if !same { return Err(format!("{path}.{key}: conflicting aliases or duplicate options")); } }
        else { accepted.insert(identity, (key, value)); }
    }
    let get = |key: &str| accepted.get(key).map(|(_, value)| value.as_str());
    if ["obfs", "simple-obfs"].contains(&plugin) && get("mode").is_some_and(|v| !["http", "tls"].contains(&v)) { return Err(format!("{path}.mode: simple-obfs requires http or tls")); }
    if ["v2ray-plugin", "gost-plugin"].contains(&plugin) {
        if get("mode").is_some_and(|v| !["ws", "websocket"].contains(&v)) { return Err(format!("{path}.mode: only websocket is implemented")); }
        if get("tls") != Some("true") { for key in ["skip-cert-verify", "name-cert-verify", "fingerprint", "certificate", "private-key", "ech-opts.enable", "ech-opts.config"] { if get(key).is_some() { return Err(format!("{path}.{key}: TLS policy would be ignored without tls true")); } } }
    }
    if plugin == "restls" && get("version-hint").is_some_and(|v| !["tls12", "tls13"].contains(&v)) { return Err(format!("{path}.version-hint: only tls12/tls13 are implemented")); }
    if plugin == "shadow-tls" && get("version").is_some_and(|v| !["1", "2", "3"].contains(&v)) { return Err(format!("{path}.version: only shadow-tls v1/v2/v3 are implemented")); }
    if plugin == "kcptun" && get("smuxver").is_some_and(|v| !["0", "1"].contains(&v)) { return Err(format!("{path}.smuxver: core speaks smux v1 only")); }
    if plugin == "restls" && get("force-tls12") == Some("true") && get("version-hint") == Some("tls13") { return Err(format!("{path}.force-tls12: overrides declared tls13 version")); }
    if plugin == "kcptun" && get("crypt") == Some("sm4") { return Err(format!("{path}.crypt: core explicitly does not implement SM4")); }
    if plugin == "ech-tls-tunnel" && get("mode").is_some_and(|v| v != "client") { return Err(format!("{path}.mode: only client mode is implemented")); }
    if plugin == "kcptun" {
        if get("mode").is_some_and(|v| !["normal", "fast", "fast2", "fast3", "manual"].contains(&v)) { return Err(format!("{path}.mode: unknown mode would change KCP defaults")); }
        if get("crypt").is_some_and(|v| !["aes", "aes-256", "aes-128", "aes-192", "aes-128-gcm", "salsa20", "none", "null", "xor", "tea", "xtea", "blowfish", "twofish", "cast5", "3des", "sm4"].contains(&v)) { return Err(format!("{path}.crypt: unknown cipher would silently default")); }
        if get("mode") != Some("manual") { for key in ["nodelay", "interval", "resend", "nc"] { if get(key).is_some() { return Err(format!("{path}.{key}: nonmanual preset would overwrite option")); } } }
        let numeric = |key: &str| -> Result<u64, String> { get(key).unwrap_or("0").parse().map_err(|_| format!("{path}.{key}: expected unsigned integer")) };
        let data = match numeric("datashard")? { 0 => 10, n => n }; let parity = match numeric("parityshard")? { 0 => 3, n => n };
        if get("mode") == Some("manual") && get("interval").is_some() { let interval = numeric("interval")?; if interval != 0 && !(10..=5000).contains(&interval) { return Err(format!("{path}.interval: core would clamp manual interval")); } }
        if data.saturating_add(parity) > 256 { return Err(format!("{path}.parityshard: FEC limit would disable parity")); }
    }
    Ok(accepted.into_values().map(|(key, value)| format!("{key}={value}")).collect::<Vec<_>>().join(";"))
}
fn plugin_scalar(value: &Value, path: &str) -> Result<String, String> {
    let s = match value { Value::String(s) => s.clone(), Value::Bool(v) => v.to_string(), Value::Number(v) => v.to_string(), _ => return Err(format!("{path}: nonscalar option would be dropped")) };
    if s.contains(';') { return Err(format!("{path}: option separator would be dropped")); }
    Ok(s)
}

fn require_preserved_keys(source: &Value, typed: &Value, path: &str) -> Result<(), String> {
    if let Some(map) = source.as_mapping() {
        let typed = typed.as_mapping().ok_or_else(|| format!("{path}: expected typed mapping"))?;
        for (key, value) in map {
            let name = key.as_str().ok_or_else(|| format!("{path}: keys must be strings"))?;
            let target = typed.get(key).ok_or_else(|| format!("{path}.{name}: unknown field would be discarded"))?;
            require_preserved_keys(value, target, &format!("{path}.{name}"))?;
        }
    } else if let Some(items) = source.as_sequence() {
        if let Some(targets) = typed.as_sequence() { for (i, (s, t)) in items.iter().zip(targets).enumerate() { require_preserved_keys(s, t, &format!("{path}[{i}]"))?; } }
    }
    Ok(())
}

fn proxy_fields(node: &Value, path: &str) -> Result<(), String> {
    let row = node.as_mapping().ok_or_else(|| format!("{path}: expected proxy mapping"))?;
    let kind = node.get("type").and_then(Value::as_str).ok_or_else(|| format!("{path}.type: expected protocol"))?;
    let common = ["name", "type", "server", "port", "udp", "dialer-proxy"];
    let specific: &[&str] = match kind {
        "ss" => &["cipher", "password", "plugin", "plugin-opts", "client-fingerprint", "smux", "mux"],
        "trojan" => &["password", "sni", "skip-cert-verify", "smux", "mux"],
        "anytls" => &["password", "sni", "skip-cert-verify"],
        "vmess" => &["uuid", "alterId", "cipher", "tls", "servername", "skip-cert-verify", "alpn", "client-fingerprint", "network", "ws-opts", "smux", "mux"],
        "vless" => &["uuid", "tls", "servername", "skip-cert-verify", "alpn", "client-fingerprint", "network", "ws-opts", "grpc-opts", "h2-opts", "http-upgrade-opts", "xhttp-opts", "reality-opts", "ech-opts", "encryption", "flow", "smux", "mux"],
        "http" => &["username", "password", "tls", "skip-cert-verify", "headers"],
        "socks5" => &["username", "password", "tls", "skip-cert-verify"],
        "snell" => &["psk", "version", "reuse", "mode", "obfs-opts"],
        "hysteria2" => &["password", "sni", "skip-cert-verify", "up", "down", "obfs", "obfs-password", "ports", "hop-interval", "fingerprint", "fast-open", "alpn"],
        "trusttunnel" => &["username", "password", "sni", "skip-cert-verify", "name-cert-verify", "client-fingerprint", "platform", "app-name", "headers", "quic", "alpn", "max-connections", "min-streams", "max-streams"],
        "direct" => &["dns", "connect-timeout"],
        _ => return Err(format!("{path}.type: unsupported protocol {kind}")),
    };
    for key in row.keys() { let key = key.as_str().ok_or_else(|| format!("{path}: keys must be strings"))?; if !common.contains(&key) && !specific.contains(&key) { return Err(format!("{path}.{key}: no consumed field in fixed protocol factory")); } }
    if kind == "direct" { for key in row.keys() { let key = key.as_str().unwrap_or(""); if !["name", "type", "dns", "connect-timeout", "dialer-proxy"].contains(&key) { return Err(format!("{path}.{key}: direct factory has no consumer")); } } }
    for key in ["udp", "tls", "skip-cert-verify", "reuse", "fast-open", "quic"] {
        if let Some(value) = node.get(key) { if !value.is_bool() { return Err(format!("{path}.{key}: expected boolean")); } }
    }
    for key in ["name", "type", "server", "password", "cipher", "uuid", "network", "sni", "servername", "client-fingerprint", "flow", "encryption", "psk", "username", "obfs", "obfs-password", "fingerprint", "ports", "platform", "app-name", "name-cert-verify"] {
        if let Some(value) = node.get(key) { if !value.is_string() { return Err(format!("{path}.{key}: expected string")); } }
    }
    if ["vmess", "vless"].contains(&kind) && node.get("tls").and_then(Value::as_bool) != Some(true) {
        for key in ["skip-cert-verify", "alpn", "client-fingerprint", "reality-opts", "ech-opts"] { if node.get(key).is_some() { return Err(format!("{path}.{key}: TLS option would be ignored without tls true")); } }
    }
    if kind == "vmess" && node.get("alterId").is_some_and(|v| v.as_u64() != Some(0)) { return Err(format!("{path}.alterId: only AEAD alterId 0 preserves semantics")); }
    if kind == "trusttunnel" {
        let max = node.get("max-streams").and_then(Value::as_u64).unwrap_or(0);
        let connections = node.get("max-connections").and_then(Value::as_u64).unwrap_or(0);
        let min = node.get("min-streams").and_then(Value::as_u64).unwrap_or(0);
        if max > 0 && connections == 0 && min == 0 { return Err(format!("{path}.max-streams: legacy unbounded pool would be replaced with bounded policy")); }
    }
    for (field, allowed) in [
        ("ws-opts", &["path", "headers", "max-early-data", "early-data-header-name"][..]),
        ("grpc-opts", &["grpc-service-name"][..]),
        ("h2-opts", &["path", "host"][..]),
        ("http-upgrade-opts", &["path", "host", "headers"][..]),
        ("xhttp-opts", &["path", "host", "mode", "headers", "no-grpc-header", "x-padding-bytes"][..]),
        ("reality-opts", &["public-key", "short-id", "support-x25519mlkem768"][..]),
        ("ech-opts", &["enable", "config"][..]),
        ("smux", &["enabled", "protocol", "padding", "max-connections", "min-streams", "max-streams", "only-tcp"][..]),
        ("mux", &["enabled", "protocol", "padding", "max-connections", "min-streams", "max-streams", "only-tcp"][..]),
        ("obfs-opts", &["mode", "host"][..]),
    ] {
        if let Some(value) = node.get(field) {
            let options = value.as_mapping().ok_or_else(|| format!("{path}.{field}: expected mapping"))?;
            if ["ws-opts", "grpc-opts", "h2-opts", "http-upgrade-opts", "xhttp-opts"].contains(&field) {
                let expected = match field { "ws-opts" => "ws", "grpc-opts" => "grpc", "h2-opts" => "h2", "http-upgrade-opts" => "httpupgrade", _ => "xhttp" };
                if node.get("network").and_then(Value::as_str).unwrap_or("tcp") != expected { return Err(format!("{path}.{field}: options would be ignored for selected network")); }
            }
            if let Some(headers) = value.get("headers") {
                let headers = headers.as_mapping().ok_or_else(|| format!("{path}.{field}.headers: expected mapping"))?;
                for (key, value) in headers { if key.as_str().is_none() || value.as_str().is_none() { return Err(format!("{path}.{field}.headers: expected string pairs")); } }
            }
            for key in options.keys() { let key = key.as_str().ok_or_else(|| format!("{path}.{field}: expected string keys"))?; if !allowed.contains(&key) { return Err(format!("{path}.{field}.{key}: unknown or ignored protocol option")); } }
        }
    }
    if let Some(ws) = node.get("ws-opts") {
        if node.get("network").and_then(Value::as_str) != Some("ws") { return Err(format!("{path}.ws-opts: no consumer without network ws")); }
        if let Some(headers) = ws.get("headers").and_then(Value::as_mapping) { for key in headers.keys() { if key.as_str() != Some("Host") { return Err(format!("{path}.ws-opts.headers: fixed WS consumes Host only")); } } }
    }
    if node.get("smux").is_some() && node.get("mux").is_some() { return Err(format!("{path}.mux: normalize alias conflict before native conversion")); }

    if kind == "http" && node.get("udp").is_some() { return Err(format!("{path}.udp: HTTP factory does not consume UDP")); }
    Ok(())
}

/// The same bytes feed admission and the existing decoder; rejected input is drained without retaining its tail.
fn read_rule_input(file: std::fs::File, format: Option<&'static str>, magic: [u8; 4], cancel: &CancellationToken) -> Result<Vec<u8>, String> {
    if file.metadata().map_err(|e| e.to_string())?.len() > MAX_INPUT_BYTES as u64 {
        return Err(format!("resource input exceeds {MAX_INPUT_BYTES} bytes"));
    }
    if rules::is_mrs_bytes(&magic) { return read_open_file(file, MAX_INPUT_BYTES, cancel); }
    read_rule_input_from(file, format, cancel)
}

fn read_rule_input_from(reader: impl Read, format: Option<&'static str>, cancel: &CancellationToken) -> Result<Vec<u8>, String> {
    let mut input = StreamingRuleInput { reader, cancel, bytes: Vec::new(), total: 0, tail: Vec::new(),
        encoding: None, failure: None, text: TextAdmission::default(), depth: YamlDepthAdmission::default(), retain: true, format };
    let yaml_error = if format == Some("text") { None } else { streaming_yaml_bound(&mut input)? };
    let mut buffer = [0; 64 * 1024];
    while input.read_chunk(&mut buffer)? != 0 {}
    if input.encoding.is_none() && !input.tail.is_empty() {
        input.encoding = Some(invalid_rule("$.payload.utf8", format!("incomplete utf-8 byte sequence from index {}", input.total - input.tail.len())));
    }
    if let Some(error) = input.encoding { return Err(error); }
    input.text.end_line();
    if format == Some("yaml") {
        if let Some(error) = yaml_error { return Err(invalid_rule("$.payload.yaml", error)); }
    } else if format == Some("text") || yaml_error.is_some() {
        if let Some(error) = input.text.error { return Err(payload_error(error)); }
    }
    stop(&|| cancel.is_cancelled())?;
    Ok(input.bytes)
}

const YAML_DEPTH_ERROR: &str = "YAML document exceeds 128 levels of nesting";
#[derive(Default)]
struct YamlDepthAdmission { flow: usize, leading: usize, in_line: bool, exceeded: bool }
impl YamlDepthAdmission {
    fn observe(&mut self, text: &str) {
        for c in text.chars() {
            if self.exceeded { return; }
            match c {
                '[' | '{' => { self.flow += 1; if self.flow > 128 { self.exceeded = true; } }
                ']' | '}' => self.flow = self.flow.saturating_sub(1),
                _ => {}
            }
            if c == '\n' { self.leading = 0; self.in_line = false; }
            else if !self.in_line && c == ' ' {
                self.leading += 1; if self.leading > 256 { self.exceeded = true; }
            } else { self.in_line = true; }
        }
    }
}

struct StreamingRuleInput<'a, R> {
    reader: R, cancel: &'a CancellationToken, bytes: Vec<u8>, total: usize, tail: Vec<u8>,
    encoding: Option<String>, failure: Option<String>, text: TextAdmission, depth: YamlDepthAdmission, retain: bool, format: Option<&'static str>,
}
impl<R: Read> StreamingRuleInput<'_, R> {
    fn read_chunk(&mut self, output: &mut [u8]) -> Result<usize, String> {
        stop(&|| self.cancel.is_cancelled())?;
        let count = loop {
            match self.reader.read(output) {
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                value => break value.map_err(|e| e.to_string())?,
            }
        };
        if self.total.saturating_add(count) > MAX_INPUT_BYTES { return Err(format!("resource input exceeds {MAX_INPUT_BYTES} bytes")); }
        self.total += count;
        if self.encoding.is_none() {
            let mut decoded = std::mem::take(&mut self.tail);
            decoded.extend_from_slice(&output[..count]);
            match std::str::from_utf8(&decoded) {
                Ok(text) => { self.text.observe(text); if self.format != Some("text") { self.depth.observe(text); } },
                Err(error) => {
                    let valid = error.valid_up_to();
                    let prefix = std::str::from_utf8(&decoded[..valid]).unwrap();
                    self.text.observe(prefix); if self.format != Some("text") { self.depth.observe(prefix); }
                    if let Some(length) = error.error_len() {
                        self.encoding = Some(invalid_rule("$.payload.utf8", format!("invalid utf-8 sequence of {length} bytes from index {}", self.total - decoded.len() + valid)));
                    } else { self.tail.extend_from_slice(&decoded[valid..]); }
                }
            }
        }
        if (self.format == Some("text") && self.text.error.is_some()) || (self.format == Some("yaml") && self.depth.exceeded) { self.retain = false; }
        if self.retain { self.bytes.extend_from_slice(&output[..count]); }
        stop(&|| self.cancel.is_cancelled())?;
        Ok(count)
    }
}

#[derive(Default)]
struct TextAdmission { entries: usize, width: usize, gap: usize, prefix: u8, error: Option<String> }
impl TextAdmission {
    fn observe(&mut self, text: &str) {
        for c in text.chars() {
            if self.error.is_some() { return; }
            if c == '\n' { self.end_line(); continue; }
            if self.prefix == 3 { continue; }
            if self.prefix == 0 && c.is_whitespace() { continue; }
            if self.prefix == 0 {
                if c == '#' { self.prefix = 3; continue; }
                self.prefix = if c == '/' { 1 } else { 2 };
            } else if self.prefix == 1 {
                if c == '/' { self.prefix = 3; self.width = 0; continue; }
                self.prefix = 2;
            }
            if c.is_whitespace() { self.gap = self.gap.saturating_add(c.len_utf8()); }
            else {
                self.width = self.width.saturating_add(self.gap).saturating_add(c.len_utf8()); self.gap = 0;
                if self.width > MAX_ENTRY_BYTES { self.error = Some(entry_too_wide(self.entries)); }
            }
        }
    }
    fn end_line(&mut self) {
        if self.error.is_none() && matches!(self.prefix, 1 | 2) {
            if self.entries == MAX_ENTRIES { self.error = Some(too_many_entries()); } else { self.entries += 1; }
        }
        self.width = 0; self.gap = 0; self.prefix = 0;
    }
}

fn streaming_yaml_bound<R: Read>(input: &mut StreamingRuleInput<'_, R>) -> Result<Option<String>, String> {
    use unsafe_libyaml as sys;
    // 0.2.11 calls an unsafe Rust fn (not extern C); its size_t is u64 and the borrowed reader outlives parser deletion.
    unsafe fn read<R: Read>(data: *mut libc::c_void, buffer: *mut u8, size: u64, size_read: *mut u64) -> libc::c_int {
        let input = unsafe { &mut *(data as *mut StreamingRuleInput<'_, R>) };
        let length = (size as usize).min(64 * 1024);
        // libyaml's malloc buffer may be uninitialized; Read requires initialized bytes, even for an output slice.
        unsafe { std::ptr::write_bytes(buffer, 0, length); }
        let output = unsafe { std::slice::from_raw_parts_mut(buffer, length) };
        match input.read_chunk(output) {
            Ok(count) => { unsafe { *size_read = count as u64; } if input.depth.exceeded { 0 } else { 1 } }
            Err(error) => { input.failure = Some(error); unsafe { *size_read = 0; } 0 }
        }
    }
    struct Owned(*mut sys::yaml_parser_t);
    impl Drop for Owned { fn drop(&mut self) { unsafe { sys::yaml_parser_delete(self.0) } } }
    let mut slot = Box::new(std::mem::MaybeUninit::<sys::yaml_parser_t>::uninit());
    let parser = slot.as_mut_ptr();
    if unsafe { sys::yaml_parser_initialize(parser) }.fail { return Err("rule_set_invalid: $.payload.yaml: parser allocation failed".into()); }
    let _owned = Owned(parser);
    unsafe {
        sys::yaml_parser_set_encoding(parser, sys::YAML_UTF8_ENCODING);
        sys::yaml_parser_set_input(parser, read::<R>, input as *mut _ as *mut libc::c_void);
    }
    let mut budget = YamlBudget::default();
    loop {
        stop(&|| input.cancel.is_cancelled())?;
        let mut event = std::mem::MaybeUninit::<sys::yaml_event_t>::uninit();
        let event = event.as_mut_ptr();
        if unsafe { sys::yaml_parser_parse(parser, event) }.fail {
            if let Some(error) = input.failure.take() { return Err(error); }
            return Ok(input.depth.exceeded.then(|| YAML_DEPTH_ERROR.to_owned()));
        }
        let step = budget.observe(unsafe { &*event });
        unsafe { sys::yaml_event_delete(event) };
        match step {
            YamlStep::Next => {}
            YamlStep::Admit => return Ok(None),
            YamlStep::Over(path) => {
                if input.format == Some("yaml") || input.text.error.is_some() { input.retain = false; }
                return Ok(Some(format!("rule-provider YAML exceeds {MAX_YAML_RULE_VALUES} values at {path}")));
            }
        }
    }
}

#[cfg(test)]
mod streaming_rule_admission_tests {
    use super::*;
    fn input<'a>(source: &[u8], format: Option<&'static str>, cancel: &'a CancellationToken) -> StreamingRuleInput<'a, Cursor<Vec<u8>>> {
        StreamingRuleInput { reader: Cursor::new(source.to_vec()), cancel, bytes: Vec::new(), total: 0, tail: Vec::new(),
            encoding: None, failure: None, text: TextAdmission::default(), depth: YamlDepthAdmission::default(), retain: true, format }
    }
    #[test]
    fn streaming_text_admission_checks_the_entry_before_retaining_the_tail() {
        let cancel = CancellationToken::new();
        let mut source = format!("{}\n#", "a".repeat(MAX_ENTRY_BYTES + 1)).into_bytes();
        source.resize(1024 * 1024, b'x');
        let mut input = input(&source, Some("text"), &cancel);
        let mut chunk = [0; 1024];
        while input.text.error.is_none() { assert_ne!(input.read_chunk(&mut chunk).unwrap(), 0); }
        assert!(input.total < source.len());
        assert!(!input.retain);
        let retained = input.bytes.len();
        while input.read_chunk(&mut chunk).unwrap() != 0 {}
        assert_eq!(retained, input.bytes.len());
        assert_eq!(read_rule_input_from(Cursor::new(source), Some("text"), &cancel).unwrap_err(),
            "rule_set_invalid: $.payload: rule-provider entry 0 exceeds 2048 bytes");
        let fits = (0..MAX_ENTRIES).map(|i| format!("r{i}.test\n")).collect::<String>();
        assert!(read_rule_input_from(Cursor::new(fits.as_bytes()), Some("text"), &cancel).is_ok());
        assert!(read_rule_input_from(Cursor::new(format!("{fits}one.more\n")), Some("text"), &cancel).unwrap_err().contains("250000 entries"));
    }
    #[test]
    fn streaming_yaml_admission_counts_events_before_the_rest_is_read() {
        for (depth, admitted) in [(128, true), (129, false)] {
            let source = format!("payload: {}x{}", "[".repeat(depth), "]".repeat(depth));
            assert_eq!(read_rule_input_from(Cursor::new(source.as_bytes()), Some("yaml"), &CancellationToken::new()).is_ok(), admitted);
            assert_eq!(yaml_value_bound(&source).is_ok(), admitted);
        }
        for (spaces, admitted) in [(256, true), (257, false)] {
            let source = format!("{}payload: [x]", " ".repeat(spaces));
            assert_eq!(read_rule_input_from(Cursor::new(source.as_bytes()), Some("yaml"), &CancellationToken::new()).is_ok(), admitted);
        }
        let huge_key = "k".repeat(65536);
        let long = format!("? {huge_key}\n: &a [{}]\npayload: [{}]\n", vec!["x"; 5000].join(","), vec!["*a"; 50].join(","));
        let key_cancel = CancellationToken::new();
        let mut key_input = input(long.as_bytes(), Some("yaml"), &key_cancel);
        assert!(streaming_yaml_bound(&mut key_input).unwrap().unwrap().len() < 4200);
        let long_component = "k".repeat(4096);
        let prefix = format!("{{? \"{long_component}\" : ");
        let nested = format!("{}{{a: &a [{}], payload: [{}]}}{}", prefix.repeat(126),
            vec!["x"; 5000].join(","), vec!["*a"; 50].join(","), "}".repeat(126));
        let mut nested_input = input(nested.as_bytes(), Some("yaml"), &key_cancel);
        let diagnostic = streaming_yaml_bound(&mut nested_input).unwrap().unwrap();
        assert!(diagnostic.contains("250002 values"));
        assert!(diagnostic.contains("key@byte"));
        assert!(diagnostic.len() < 4200);
        let cancel = CancellationToken::new();
        let fits = format!("payload:\n{}", (0..MAX_ENTRIES).map(|i| format!("  - r{i}.test\n")).collect::<String>());
        assert!(read_rule_input_from(Cursor::new(fits.as_bytes()), Some("yaml"), &cancel).is_ok());
        let source = format!("{fits}  - extra.test\n#{}", "x".repeat(1024 * 1024));
        let mut input = input(source.as_bytes(), Some("yaml"), &cancel);
        let error = streaming_yaml_bound(&mut input).unwrap().unwrap();
        assert!(error.contains("250002 values at $.payload[250000]"));
        assert!(input.total < source.len());
        assert!(!input.retain);
        let retained = input.bytes.len();
        let mut chunk = [0; 1024];
        while input.read_chunk(&mut chunk).unwrap() != 0 {}
        assert_eq!(retained, input.bytes.len());
    }
    #[test]
    fn streaming_rule_admission_keeps_utf8_size_and_cancel_ahead_of_semantic_errors() {
        let cancel = CancellationToken::new();
        let mut invalid = format!("{}\n#", "a".repeat(MAX_ENTRY_BYTES + 1)).into_bytes();
        invalid.resize(256 * 1024, b'x'); invalid.push(0xff);
        assert!(read_rule_input_from(Cursor::new(invalid), Some("text"), &cancel).unwrap_err().starts_with("rule_set_invalid: $.payload.utf8:"));
        let mut incomplete = vec![b'#'; 65535]; incomplete.push(0xf0); incomplete.push(0x9f);
        assert!(read_rule_input_from(Cursor::new(incomplete), Some("text"), &cancel).unwrap_err().contains("incomplete utf-8 byte sequence from index 65535"));
        let unicode = " \u{2003}example.test\u{2003}\r\n// ignore\n";
        assert_eq!(read_rule_input_from(Cursor::new(unicode.as_bytes()), Some("text"), &cancel).unwrap(), unicode.as_bytes());
        let oversized = std::io::repeat(b'#').take(MAX_INPUT_BYTES as u64 + 1);
        assert_eq!(read_rule_input_from(oversized, Some("text"), &cancel).unwrap_err(), "resource input exceeds 67108864 bytes");
        cancel.cancel();
        assert_eq!(read_rule_input_from(Cursor::new(b"example.test\n"), Some("text"), &cancel).unwrap_err(), "resourceReadCancelled");
        struct CancellingRead(CancellationToken);
        impl Read for CancellingRead {
            fn read(&mut self, output: &mut [u8]) -> std::io::Result<usize> {
                output[0] = b'p'; self.0.cancel(); Ok(1)
            }
        }
        let during = CancellationToken::new();
        assert_eq!(read_rule_input_from(CancellingRead(during.clone()), Some("yaml"), &during).unwrap_err(), "resourceReadCancelled");
        struct InitializedRead(Cursor<&'static [u8]>);
        impl Read for InitializedRead {
            fn read(&mut self, output: &mut [u8]) -> std::io::Result<usize> {
                assert!(output.iter().all(|byte| *byte == 0), "callback must initialize libyaml output before Read");
                self.0.read(output)
            }
        }
        let yaml: &'static [u8] = b"payload: [a.test]\n";
        assert_eq!(read_rule_input_from(InitializedRead(Cursor::new(yaml)), Some("yaml"), &CancellationToken::new()).unwrap(), yaml);
        struct FailedRead;
        impl Read for FailedRead {
            fn read(&mut self, _: &mut [u8]) -> std::io::Result<usize> { Err(std::io::Error::other("specific rule IO error")) }
        }
        assert_eq!(read_rule_input_from(FailedRead, Some("yaml"), &CancellationToken::new()).unwrap_err(), "specific rule IO error");
    }
}

fn yaml_payload(source: &str) -> Result<Vec<String>, String> {
    yaml_value_bound(source)?;
    // Reuse fixed raw parser's depth guard before serde Value recursion/merge expansion.
    meow_config::parse_raw_yaml(source).map_err(|e| e.to_string())?;
    let mut value: Value = serde_yaml::from_str(source).map_err(|e| e.to_string())?;
    value.apply_merge().map_err(|e| e.to_string())?;
    let mut entries = Vec::new(); let mut found = false;
    for key in ["payload", "rules"] {
        if let Some(list) = value.get(key) { found = true; let list = list.as_sequence().ok_or("rule payload must be a string list")?;
            for item in list { let s = item.as_str().ok_or("rule payload entries must be strings")?.trim(); if !s.is_empty() { entries.push(s.to_owned()); } }
        }
    }
    if !found { return Err("rule payload mapping must contain payload/rules".into()); }
    bound_entries(&entries)?; Ok(entries)
}
/// Stops at the first entry past either bound, so no input collects more than MAX_ENTRIES lines of MAX_ENTRY_BYTES.
fn text_lines(source: &str) -> Result<Vec<String>, String> {
    let mut entries = Vec::new();
    for line in source.lines().map(str::trim).filter(|s| !s.is_empty() && !s.starts_with('#') && !s.starts_with("//")) {
        if entries.len() == MAX_ENTRIES { return Err(too_many_entries()); }
        if line.len() > MAX_ENTRY_BYTES { return Err(entry_too_wide(entries.len())); }
        entries.push(line.to_owned());
    }
    Ok(entries)
}
fn bound_entries(entries: &[String]) -> Result<(), String> {
    if entries.len() > MAX_ENTRIES { return Err(too_many_entries()); }
    if let Some(index) = entries.iter().position(|s| s.len() > MAX_ENTRY_BYTES) { return Err(entry_too_wide(index)); }
    let nodes: usize = entries.iter().map(|s| s.split('.').count()).sum();
    if nodes > MAX_TRIE_NODES { return Err(format!("rule-provider trie exceeds {MAX_TRIE_NODES} nodes")); }
    if let Some(index) = entries.iter().position(|s| s.split('.').count() > MAX_TRIE_DEPTH) {
        return Err(format!("rule-provider entry {index} exceeds {MAX_TRIE_DEPTH} labels"));
    }
    Ok(())
}

/// libyaml events, as serde_yaml 0.9.34 drives the same locked parser, counted before any event list or Value exists.
/// Only the value bound fails here; every other YAML error is left to serde_yaml so its message is unchanged.
fn yaml_value_bound(source: &str) -> Result<(), String> {
    let mut depth = YamlDepthAdmission::default(); depth.observe(source);
    if depth.exceeded { return Err(YAML_DEPTH_ERROR.to_owned()); }
    use unsafe_libyaml as sys;
    struct Owned(*mut sys::yaml_parser_t);
    impl Drop for Owned {
        fn drop(&mut self) { unsafe { sys::yaml_parser_delete(self.0) } }
    }
    let mut slot = Box::new(std::mem::MaybeUninit::<sys::yaml_parser_t>::uninit());
    let parser = slot.as_mut_ptr();
    let initialized = unsafe { sys::yaml_parser_initialize(parser) };
    if initialized.fail { return Ok(()); }
    let _owned = Owned(parser);
    unsafe {
        sys::yaml_parser_set_encoding(parser, sys::YAML_UTF8_ENCODING);
        sys::yaml_parser_set_input_string(parser, source.as_ptr(), source.len() as u64);
    }
    let mut budget = YamlBudget::default();
    loop {
        let mut event = std::mem::MaybeUninit::<sys::yaml_event_t>::uninit();
        let event = event.as_mut_ptr();
        let parsed = unsafe { sys::yaml_parser_parse(parser, event) };
        if parsed.fail { return Ok(()); }
        let step = budget.observe(unsafe { &*event });
        unsafe { sys::yaml_event_delete(event) };
        match step {
            YamlStep::Next => {}
            YamlStep::Admit => return Ok(()),
            YamlStep::Over(path) => return Err(format!("rule-provider YAML exceeds {MAX_YAML_RULE_VALUES} values at {path}")),
        }
    }
}
fn diagnostic_yaml_key(bytes: &[u8], offset: u64) -> String {
    if bytes.len() <= 128 { String::from_utf8_lossy(bytes).into_owned() }
    else { format!("<key@byte{offset}:{}bytes>", bytes.len()) }
}
enum YamlStep { Next, Admit, Over(String) }
struct YamlAnchor { size: usize, open: bool }
struct YamlFrame { mapping: bool, start: usize, anchor: Option<usize>, index: usize, key: Option<String> }
#[derive(Default)]
struct YamlBudget { values: usize, documents: usize, frames: Vec<YamlFrame>, slots: Vec<YamlAnchor>, anchors: std::collections::HashMap<Vec<u8>, usize> }
impl YamlBudget {
    fn observe(&mut self, event: &unsafe_libyaml::yaml_event_t) -> YamlStep {
        use unsafe_libyaml as sys;
        let name = |anchor: *const u8| (!anchor.is_null()).then(|| unsafe { std::ffi::CStr::from_ptr(anchor as *const std::ffi::c_char) }.to_bytes().to_vec());
        let result = match event.type_ {
            sys::YAML_STREAM_END_EVENT => return YamlStep::Admit,
            sys::YAML_DOCUMENT_START_EVENT => {
                self.documents += 1;
                if self.documents > 1 { return YamlStep::Admit; }
                Ok(())
            }
            sys::YAML_ALIAS_EVENT => {
                let Some(alias) = name(unsafe { event.data.alias.anchor }) else { return YamlStep::Admit };
                let Some(&slot) = self.anchors.get(&alias) else { return YamlStep::Admit };
                let size = if self.slots[slot].open { 1 } else { self.slots[slot].size };
                let counted = if self.at_key() { Ok(()) } else { self.count(size) };
                self.completed(Some(diagnostic_yaml_key(&alias, event.start_mark.index)));
                counted
            }
            sys::YAML_SCALAR_EVENT => {
                if let Some(anchor) = name(unsafe { event.data.scalar.anchor }) { self.define(anchor, false); }
                if self.at_key() {
                    let value = unsafe { std::slice::from_raw_parts(event.data.scalar.value, event.data.scalar.length as usize) };
                    self.completed(Some(diagnostic_yaml_key(value, event.start_mark.index)));
                    Ok(())
                } else {
                    let counted = self.count(1);
                    self.completed(None);
                    counted
                }
            }
            sys::YAML_SEQUENCE_START_EVENT => self.start(name(unsafe { event.data.sequence_start.anchor }), false),
            sys::YAML_MAPPING_START_EVENT => self.start(name(unsafe { event.data.mapping_start.anchor }), true),
            sys::YAML_SEQUENCE_END_EVENT | sys::YAML_MAPPING_END_EVENT => { self.end(); Ok(()) }
            _ => Ok(()),
        };
        match result { Ok(()) => YamlStep::Next, Err(path) => YamlStep::Over(path) }
    }
    fn at_key(&self) -> bool { self.frames.last().is_some_and(|top| top.mapping && top.key.is_none()) }
    fn path(&self) -> String {
        let mut path = "$".to_owned();
        for frame in &self.frames {
            let part = if frame.mapping { frame.key.as_ref().map(|key| format!(".{key}")) }
                else { Some(format!("[{}]", frame.index)) };
            if let Some(part) = part {
                if path.len() + part.len() > 4096 { path.push_str(&format!(".<path depth {}>", self.frames.len())); break; }
                path.push_str(&part);
            }
        }
        path
    }
    fn count(&mut self, amount: usize) -> Result<(), String> {
        self.values = self.values.saturating_add(amount);
        if self.values > MAX_YAML_RULE_VALUES { Err(self.path()) } else { Ok(()) }
    }
    fn completed(&mut self, key: Option<String>) {
        if let Some(top) = self.frames.last_mut() {
            if !top.mapping { top.index += 1 } else if top.key.is_none() { top.key = Some(key.unwrap_or_else(|| "?".to_owned())) } else { top.key = None }
        }
    }
    fn define(&mut self, anchor: Vec<u8>, open: bool) -> usize {
        self.slots.push(YamlAnchor { size: 1, open });
        self.anchors.insert(anchor, self.slots.len() - 1);
        self.slots.len() - 1
    }
    fn start(&mut self, anchor: Option<Vec<u8>>, mapping: bool) -> Result<(), String> {
        self.count(1)?;
        let anchor = anchor.map(|anchor| self.define(anchor, true));
        self.frames.push(YamlFrame { mapping, start: self.values - 1, anchor, index: 0, key: None });
        Ok(())
    }
    fn end(&mut self) {
        let Some(frame) = self.frames.pop() else { return };
        if let Some(slot) = frame.anchor { self.slots[slot] = YamlAnchor { size: self.values - frame.start, open: false }; }
        self.completed(None);
    }
}
/// Pure bounded mature-codec wrapper; consumes all members/trailers, never accepts partial output.
pub fn unpack_geodata(bytes: &[u8], max_expanded: usize) -> Result<Vec<u8>, String> {
    unpack_geodata_until(bytes, max_expanded, &|| false)
}
fn unpack_geodata_until(bytes: &[u8], max_expanded: usize, cancelled: &(dyn Fn() -> bool + Sync)) -> Result<Vec<u8>, String> {
    if bytes.len() > MAX_INPUT_BYTES { return Err("geo input limit exceeded".into()); }
    let limit = max_expanded.min(MAX_EXPANDED_BYTES);
    if bytes.starts_with(&rules::mrs_parser::ZSTD_MAGIC) {
        // Same decoder, bound and messages as the fixed Core's decompress_payload_bounded; only the cancellation steps are added.
        use rules::mrs_parser::MrsError;
        let mut decoder = zstd::stream::Decoder::new(Cursor::new(bytes)).map_err(|e| MrsError::from(e).to_string())?;
        let mut result = Vec::new();
        let mut buffer = vec![0; 64 * 1024];
        loop {
            stop(cancelled)?;
            let room = (limit + 1 - result.len()).min(buffer.len());
            let count = match decoder.read(&mut buffer[..room]) {
                Ok(count) => count,
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(e) => return Err(MrsError::from(e).to_string()),
            };
            if count == 0 { break; }
            result.extend_from_slice(&buffer[..count]);
            if result.len() > limit { return Err(MrsError::InvalidLength("decompressed_payload", limit as i64).to_string()); }
        }
        return Ok(result);
    }
    if bytes.starts_with(&[0x1f, 0x8b]) {
        let mut decoder = flate2::read::MultiGzDecoder::new(bytes);
        let mut result = Vec::new();
        let mut buffer = vec![0; 64 * 1024];
        loop {
            stop(cancelled)?;
            let room = (limit + 1 - result.len()).min(buffer.len());
            let count = match decoder.read(&mut buffer[..room]) {
                Ok(count) => count,
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(e) => return Err(format!("gzip decode/CRC/trailer: {e}")),
            };
            if count == 0 { break; }
            result.extend_from_slice(&buffer[..count]);
            if result.len() > limit { return Err("geo expanded limit exceeded".into()); }
        }
        return Ok(result);
    }
    if bytes.len() > limit { return Err("geo expanded limit exceeded".into()); }
    Ok(bytes.to_vec())
}
fn open_regular_file(path: &Path) -> Result<std::fs::File, String> {
    // Preserve regular symlinks, but never wait for a FIFO writer before checking the opened object.
    // O_NONBLOCK follows any raced symlink target too; fstat below checks this descriptor, not the path.
    #[cfg(unix)]
    let file = {
        use std::os::unix::fs::OpenOptionsExt;
        std::fs::OpenOptions::new().read(true)
            .custom_flags(libc::O_NONBLOCK | libc::O_CLOEXEC | libc::O_NOCTTY)
            .open(path)
    };
    #[cfg(not(unix))]
    let file = std::fs::File::open(path);
    let file = file.map_err(|e| format!("open resource candidate: {e}"))?;
    let meta = file.metadata().map_err(|e| e.to_string())?;
    if !meta.is_file() { return Err("resource candidate must be a regular file".into()); }
    Ok(file)
}
fn read_open_file(file: std::fs::File, limit: usize, cancel: &tokio_util::sync::CancellationToken) -> Result<Vec<u8>, String> {
    read_open_file_until(file, limit, &|| cancel.is_cancelled())
}
fn read_open_file_until(file: std::fs::File, limit: usize, cancelled: &(dyn Fn() -> bool + Sync)) -> Result<Vec<u8>, String> {
    if file.metadata().map_err(|e| e.to_string())?.len() > limit as u64 { return Err(format!("resource input exceeds {limit} bytes")); }
    read_bounded_until(file, limit, cancelled)
}
/// An interrupted read is retried, as std read_to_end does, rather than reported as a validation error.
fn read_bounded_until(mut file: impl Read, limit: usize, cancelled: &(dyn Fn() -> bool + Sync)) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    let mut buffer = [0; 64 * 1024];
    loop {
        stop(cancelled)?;
        let count = match file.read(&mut buffer) {
            Ok(count) => count,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e.to_string()),
        };
        if count == 0 { break; }
        if bytes.len().saturating_add(count) > limit { return Err(format!("resource input exceeds {limit} bytes")); }
        bytes.extend_from_slice(&buffer[..count]);
    }
    stop(cancelled)?;
    Ok(bytes)
}
fn parse_behavior(s: &str) -> Result<RuleSetBehavior, String> { match s.to_ascii_lowercase().as_str() { "domain" => Ok(RuleSetBehavior::Domain), "ipcidr" => Ok(RuleSetBehavior::IpCidr), "classic" | "classical" => Ok(RuleSetBehavior::Classical), _ => Err(format!("unsupported rule-provider behavior: {s}")) } }
fn behavior_tag(tag: u8) -> Result<RuleSetBehavior, String> { match tag { 0 => Ok(RuleSetBehavior::Domain), 1 => Ok(RuleSetBehavior::IpCidr), 2 => Ok(RuleSetBehavior::Classical), _ => Err("rule_set_invalid_mrs_behavior".into()) } }
fn name(b: RuleSetBehavior) -> &'static str { match b { RuleSetBehavior::Domain => "domain", RuleSetBehavior::IpCidr => "ipcidr", RuleSetBehavior::Classical => "classical" } }
fn canonical_format(s: &str) -> Result<&'static str, String> { match s.to_ascii_lowercase().as_str() { "yaml" | "yml" => Ok("yaml"), "text" => Ok("text"), "mrs" => Ok("mrs"), _ => Err(format!("unsupported rule-provider format: {s}")) } }

#[cfg(test)]
mod owned_dump_budget_tests {
    use super::*;
    use rules::rule_set::AcceptedEntrySink;
    #[test]
    fn exact_text_budget_is_complete_and_one_more_byte_is_rejected_without_append() {
        let sink = BoundedRuleDump { text: Default::default(), overflow: Default::default() };
        sink.accepted(&"a".repeat(MAX_DUMP_TEXT_BYTES - 1));
        assert_eq!(sink.text.lock().len(), MAX_DUMP_TEXT_BYTES);
        assert!(!sink.overflow.load(std::sync::atomic::Ordering::Acquire));
        sink.accepted("");
        assert_eq!(sink.text.lock().len(), MAX_DUMP_TEXT_BYTES);
        assert!(sink.overflow.load(std::sync::atomic::Ordering::Acquire));
    }
    #[test]
    fn text_rule_bytes_at_the_text_budget_decode_and_one_more_byte_keeps_the_text_input_error() {
        let mut bytes = b"example.test\n#".to_vec();
        bytes.resize(MAX_RULE_TEXT_BYTES - 1, b'x');
        bytes.push(b'\n');
        let accepted = decode_rule_payload(&bytes, Some(RuleSetBehavior::Domain), Some("text")).map(|accepted| accepted.entries);
        assert_eq!(accepted, Ok(vec!["example.test".to_owned()]));
        bytes.push(b'\n');
        let rejected = decode_rule_payload(&bytes, Some(RuleSetBehavior::Domain), Some("text")).err();
        assert_eq!(rejected.as_deref(), Some("rule-provider text input exceeds 67108864 bytes"));
    }
    #[test]
    fn text_rule_entries_stop_at_either_entry_bound_before_they_are_collected() {
        let fits: String = (0..MAX_ENTRIES).map(|index| format!("r{index}.test\n")).collect();
        assert_eq!(text_lines(&fits).map(|entries| entries.len()), Ok(MAX_ENTRIES));
        assert_eq!(text_lines(&format!("{fits}one.more\n")).err().as_deref(), Some("rule-provider payload exceeds 250000 entries"));
        let widest = format!("# {}\n  {}  \n", "c".repeat(4 * MAX_ENTRY_BYTES), "x".repeat(MAX_ENTRY_BYTES));
        assert_eq!(text_lines(&widest).map(|entries| entries[0].len()), Ok(MAX_ENTRY_BYTES));
        assert_eq!(text_lines(&format!("{}\n", "x".repeat(MAX_ENTRY_BYTES + 1))).err().as_deref(), Some("rule-provider entry 0 exceeds 2048 bytes"));
        let over = format!("{fits}one.more\n");
        assert_eq!(decode_rule_payload(over.as_bytes(), Some(RuleSetBehavior::Domain), Some("text")).err().as_deref(), Some("rule-provider payload exceeds 250000 entries"));
        let deep = format!("{}\n", vec!["a"; MAX_TRIE_DEPTH + 1].join("."));
        assert_eq!(bound_entries(&[deep.trim().to_owned()]).err().as_deref(), Some("rule-provider entry 0 exceeds 128 labels"));
    }
    #[test]
    fn yaml_event_pass_admits_the_value_bound_and_stops_one_value_past_it() {
        let fits = format!("payload:\n{}", (0..MAX_ENTRIES).map(|index| format!("  - r{index}.test\n")).collect::<String>());
        assert_eq!(yaml_value_bound(&fits), Ok(()));
        assert_eq!(yaml_payload(&fits).map(|entries| entries.len()), Ok(MAX_ENTRIES));
        let over = format!("{fits}  - one.more\n");
        let bound = "rule-provider YAML exceeds 250002 values at $.payload[250000]";
        assert_eq!(yaml_value_bound(&over).err().as_deref(), Some(bound));
        assert_eq!(yaml_payload(&over).err().as_deref(), Some(bound));
        assert_eq!(decode_rule_payload(over.as_bytes(), Some(RuleSetBehavior::Domain), Some("yaml")).err(), Some(format!("rule_set_invalid: $.payload.yaml: {bound}")));
        let aliases = format!("a: &a [{}]\npayload: [{}]\n", vec!["x"; 5_000].join(","), vec!["*a"; 50].join(","));
        assert_eq!(yaml_value_bound(&aliases).err().as_deref(), Some("rule-provider YAML exceeds 250002 values at $.payload[48]"));
        for other in ["payload: [*missing]\n", "payload: [a\n", "payload: [a]\n---\npayload: [b]\n"] {
            assert_eq!(yaml_value_bound(other), Ok(()));
            assert!(yaml_payload(other).is_err(), "{other}");
        }
    }
}

/// Test-only geodata probe keyed by the canonical path: the first predicate check pauses until released, every check is counted.
#[cfg(test)]
pub(crate) mod geo_probe {
    use std::{collections::HashMap, path::{Path, PathBuf}, sync::{Arc, Condvar, Mutex, OnceLock, atomic::{AtomicUsize, Ordering}}, time::Duration};
    #[derive(Default)]
    pub(crate) struct Probe { calls: AtomicUsize, entered: (Mutex<bool>, Condvar), released: (Mutex<bool>, Condvar) }
    impl Probe {
        pub(crate) fn check(&self, inner: &(dyn Fn() -> bool + Sync)) -> bool {
            if self.calls.fetch_add(1, Ordering::SeqCst) == 0 {
                *self.entered.0.lock().unwrap() = true; self.entered.1.notify_all();
                let open = self.released.0.lock().unwrap();
                let (open, timeout) = self.released.1.wait_timeout_while(open, Duration::from_secs(10), |open| !*open).unwrap();
                assert!(*open && !timeout.timed_out(), "geo probe was never released");
            }
            inner()
        }
        pub(crate) fn wait_entered(&self) -> bool {
            let entered = self.entered.0.lock().unwrap();
            *self.entered.1.wait_timeout_while(entered, Duration::from_secs(10), |entered| !*entered).unwrap().0
        }
        pub(crate) fn release(&self) { *self.released.0.lock().unwrap() = true; self.released.1.notify_all(); }
        pub(crate) fn calls(&self) -> usize { self.calls.load(Ordering::SeqCst) }
    }
    fn probes() -> &'static Mutex<HashMap<PathBuf, Arc<Probe>>> { static PROBES: OnceLock<Mutex<HashMap<PathBuf, Arc<Probe>>>> = OnceLock::new(); PROBES.get_or_init(Default::default) }
    pub(crate) fn register(path: &Path) -> Arc<Probe> {
        let probe = Arc::new(Probe::default());
        assert!(probes().lock().unwrap().insert(path.to_path_buf(), probe.clone()).is_none(), "one probe per path");
        probe
    }
    pub(crate) fn find(path: &Path) -> Option<Arc<Probe>> { probes().lock().unwrap().get(path).cloned() }
}

/// Test context: counts the fixed converter's owned blocking jobs and holds the first one until released.
#[cfg(test)]
#[derive(Default)]
pub(crate) struct ConversionGate {
    calls: std::sync::atomic::AtomicUsize,
    pub(crate) entered: tokio::sync::Notify,
    pub(crate) observed: tokio::sync::Notify,
    pub(crate) first_done: std::sync::atomic::AtomicBool,
    open: (std::sync::Mutex<bool>, std::sync::Condvar),
}
#[cfg(test)]
impl ConversionGate {
    pub(crate) fn release(&self) { *self.open.0.lock().unwrap() = true; self.open.1.notify_all(); }
    pub(crate) fn calls(&self) -> usize { self.calls.load(std::sync::atomic::Ordering::SeqCst) }
}
#[cfg(test)]
impl LoadContext for ConversionGate {
    fn offline_validation(&self) -> bool { true }
    fn wrap(&self, work: OwnedWork) -> OwnedWork { work }
    fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) {
        let first = self.calls.fetch_add(1, std::sync::atomic::Ordering::SeqCst) == 0;
        if first {
            self.entered.notify_one(); self.observed.notify_one();
            let mut open = self.open.0.lock().unwrap();
            while !*open { open = self.open.1.wait(open).unwrap(); }
        }
        work();
        if first { self.first_done.store(true, std::sync::atomic::Ordering::SeqCst); }
    }
}

#[cfg(test)]
mod in_work_cancellation_tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering::SeqCst};
    use std::time::Duration;
    #[test]
    fn geo_expansion_observes_cancellation_between_chunks_not_only_around_the_resource() {
        use std::io::Write;
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(&vec![0u8; 4 * 1024 * 1024]).unwrap();
        let path = std::env::temp_dir().join(format!("meow-in-work-geo-{}-{}", std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::write(&path, encoder.finish().unwrap()).unwrap();
        assert!(validate_geo("mmdb", &path, "", "mmdb", &|| false).unwrap_err().starts_with("MMDB decode"));
        let checks = AtomicUsize::new(0);
        let cancelled = || checks.fetch_add(1, SeqCst) + 1 >= 10;
        assert_eq!(validate_geo("mmdb", &path, "", "mmdb", &cancelled).unwrap_err(), "resourceReadCancelled");
        // A check only before and after the resource is consulted twice; the tenth falls inside the 4MiB gzip expansion.
        assert_eq!(checks.load(SeqCst), 10);
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn zstd_geo_expansion_observes_cancellation_between_chunks_not_only_around_the_resource() {
        let path = std::env::temp_dir().join(format!("meow-in-work-zstd-{}-{}", std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::write(&path, zstd::encode_all(std::io::Cursor::new(vec![0u8; 4 * 1024 * 1024]), 0).unwrap()).unwrap();
        assert!(validate_geo("mmdb", &path, "", "mmdb", &|| false).unwrap_err().starts_with("MMDB decode"));
        let checks = AtomicUsize::new(0);
        let cancelled = || checks.fetch_add(1, SeqCst) + 1 >= 10;
        assert_eq!(validate_geo("mmdb", &path, "", "mmdb", &cancelled).unwrap_err(), "resourceReadCancelled");
        // A check only around one bounded call is consulted twice; the tenth check falls inside the 4MiB zstd expansion.
        assert_eq!(checks.load(SeqCst), 10);
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn interrupted_reads_are_retried_like_read_to_end_and_other_errors_stay_specific() {
        struct Flaky { interrupts: usize, data: std::io::Cursor<Vec<u8>>, fail: bool }
        impl Read for Flaky {
            fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
                if self.interrupts > 0 { self.interrupts -= 1; return Err(std::io::ErrorKind::Interrupted.into()); }
                if self.fail { return Err(std::io::Error::other("fixture device failure")); }
                self.data.read(buffer)
            }
        }
        let payload = vec![7u8; 200 * 1024];
        let read = read_bounded_until(Flaky { interrupts: 3, data: std::io::Cursor::new(payload.clone()), fail: false }, payload.len(), &|| false);
        assert_eq!(read.unwrap(), payload);
        let failed = read_bounded_until(Flaky { interrupts: 1, data: Default::default(), fail: true }, 16, &|| false);
        assert_eq!(failed.unwrap_err(), "fixture device failure");
        assert_eq!(read_bounded_until(Flaky { interrupts: 0, data: std::io::Cursor::new(vec![1; 17]), fail: false }, 16, &|| false).unwrap_err(),
            "resource input exceeds 16 bytes");
    }
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn proxy_provider_conversion_stops_between_real_jobs_and_joins_the_started_one() {
        let normalized = normalize_config_fields("proxies: [{name: direct-node, type: direct}]\n").unwrap();
        let control = Arc::new(ConversionGate::default()); control.release();
        validate_offline_cancellable(&normalized, &CancellationToken::new(), control.clone()).await.unwrap();
        assert!(control.calls() > 1, "the real conversion runs several owned blocking jobs");
        let (gate, cancel) = (Arc::new(ConversionGate::default()), CancellationToken::new());
        let running = { let (gate, cancel, normalized) = (gate.clone(), cancel.clone(), normalized.clone());
            tokio::spawn(async move { validate_offline_cancellable(&normalized, &cancel, gate).await }) };
        tokio::time::timeout(Duration::from_secs(5), gate.observed.notified()).await.unwrap();
        cancel.cancel();
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(!running.is_finished(), "cancellation must still join the started conversion job");
        gate.release();
        assert_eq!(tokio::time::timeout(Duration::from_secs(5), running).await.unwrap().unwrap().unwrap_err(), "offline_validation_cancelled");
        assert!(gate.first_done.load(SeqCst));
        assert_eq!(gate.calls(), 1, "no later conversion job may start after cancellation");
    }
}

#[cfg(all(test, unix))]
mod opened_object_tests {
    use super::*;
    #[test]
    fn replacing_the_path_after_descriptor_open_cannot_replace_the_inspected_object() {
        let root = std::env::temp_dir().join(format!("meow-read-object-{}-{}", std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir(&root).unwrap();
        let path = root.join("payload"); std::fs::write(&path, b"original.test\n").unwrap();
        let (file, _) = rule_file(&path, &Default::default()).unwrap();
        std::fs::rename(&path, root.join("old")).unwrap();
        std::fs::create_dir(&path).unwrap();
        assert_eq!(read_open_file(file, MAX_TEXT_BYTES, &Default::default()).unwrap(), b"original.test\n");
        assert!(open_regular_file(&path).unwrap_err().contains("regular file"));
        std::fs::remove_dir(&path).unwrap(); std::fs::remove_file(root.join("old")).unwrap(); std::fs::remove_dir(root).unwrap();
    }
}
