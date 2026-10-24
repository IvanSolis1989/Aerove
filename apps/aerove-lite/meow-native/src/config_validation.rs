//! Bounded offline resource parsing. No Core/runtime, sockets, fetches, listeners or global offline flags.
//! JNI must preserve Result: validation Ok -> null, Err -> error string; inspection/dump Err -> exception.
use std::{io::{Cursor, Read}, path::Path};
use meow_config::offline_rules::{self as rules, RuleSetBehavior};
use serde_yaml::Value;

pub const MAX_INPUT_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_TEXT_BYTES: usize = 8 * 1024 * 1024;
pub const MAX_ENTRIES: usize = 250_000;
pub const MAX_EXPANDED_BYTES: usize = 64 * 1024 * 1024;

/// Real typed syntax validation; deliberately distinct from full conversion/start admission.
pub fn validate_config_syntax(source: &str) -> Result<(), String> {
    if source.len() > MAX_TEXT_BYTES { return Err("$: configuration input limit exceeded".into()); }
    meow_config::parse_raw_yaml(source).map_err(|e| format!("$: {e}"))?;
    Ok(())
}

/// Runs the real fixed parser/converter inside an owned offline load scope; never flips CLI globals.
pub async fn validate_config(source: &str) -> Result<(), String> {
    let normalized = normalize_config_fields(source)?;
    meow_config::validate_offline_config(&normalized).await
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
    let accepted = read_rules(path, None, None)?;
    serde_json::to_string(&serde_json::json!({"format": accepted.format, "behavior": name(accepted.behavior), "count": accepted.count})).map_err(|e| e.to_string())
}

pub fn dump_rule_set(path: &Path, behavior: &str, format: &str) -> Result<String, String> {
    let accepted = read_rules(path, None, None)?;
    if parse_behavior(behavior).map_err(|e| invalid_rule("$.behavior", e))? != accepted.behavior { return Err(invalid_rule("$.behavior", "rule_set_behavior_mismatch")); }
    if canonical_format(format).map_err(|e| invalid_rule("$.format", e))? != accepted.format { return Err(invalid_rule("$.format", "rule_set_format_mismatch")); }
    let size = accepted.entries.iter().try_fold(0usize, |n, s| n.checked_add(s.len() + 1)).ok_or_else(|| invalid_rule("$.payload", "rule_set_dump_size_overflow"))?;
    if size > MAX_EXPANDED_BYTES { return Err(invalid_rule("$.payload", "rule_set_dump_limit_exceeded")); }
    let mut text = String::with_capacity(size);
    for entry in accepted.entries { text.push_str(&entry); text.push('\n'); }
    Ok(text)
}

pub async fn validate_resource(kind: &str, path: &Path, behavior: &str, format: &str) -> Result<(), String> {
    match kind.to_ascii_lowercase().as_str() {
        "ruleset" | "rule-provider" => { read_rules(path, Some(parse_behavior(behavior).map_err(|e| invalid_rule("$.behavior", e))?), Some(canonical_format(format).map_err(|e| invalid_rule("$.format", e))?))?; Ok(()) }
        "mmdb" => {
            if !behavior.is_empty() || !matches!(format.to_ascii_lowercase().as_str(), "" | "mmdb") { return Err("mmdb expects empty behavior and empty/mmdb format".into()); }
            let bytes = read_input(path, MAX_INPUT_BYTES)?;
            let bytes = unpack_geo(bytes)?;
            let reader = meow_config::offline_maxminddb::Reader::from_source(bytes).map_err(|e| format!("MMDB decode: {e}"))?;
            if reader.metadata.node_count as usize > MAX_EXPANDED_BYTES / 6 { return Err("MMDB search tree limit exceeded".into()); }
            reader.verify().map_err(|e| format!("MMDB structure verification: {e}"))
        }
        "geosite" => {
            if !behavior.is_empty() || !matches!(format.to_ascii_lowercase().as_str(), "" | "dat" | "protobuf") { return Err("geosite expects empty behavior and empty/dat/protobuf format".into()); }
            let bytes = read_input(path, MAX_INPUT_BYTES)?;
            // Checked entry point must reject skipped empty/bad-regex entries, unlike original warn-only loader.
            let bytes = unpack_geo(bytes)?;
            let db = rules::geosite_dat::from_dat_bytes_checked(&bytes, None).map_err(|e| e.to_string())?;
            if db.category_count() == 0 { return Err("geosite has no accepted categories".into()); }
            Ok(())
        }
        "proxy-provider" => validate_proxy_payload(path, behavior, format).await,
        other => Err(format!("unsupported resource kind: {other}")),
    }
}

struct AcceptedRules { format: &'static str, behavior: RuleSetBehavior, entries: Vec<String>, count: usize }
fn read_rules(path: &Path, behavior: Option<RuleSetBehavior>, format: Option<&'static str>) -> Result<AcceptedRules, String> {
    let bytes = read_input(path, MAX_INPUT_BYTES)?;
    read_rule_payload(&bytes, behavior, format).map_err(|reason| {
        if reason.starts_with("rule_set_invalid:") { reason } else { invalid_rule("$.payload", reason) }
    })
}
fn invalid_rule(field: &str, reason: impl std::fmt::Display) -> String {
    format!("rule_set_invalid: {field}: {reason}")
}
fn read_rule_payload(bytes: &[u8], behavior: Option<RuleSetBehavior>, format: Option<&'static str>) -> Result<AcceptedRules, String> {
    let is_mrs = rules::is_mrs_bytes(bytes);
    let (actual_format, actual_behavior, entries, source_count) = if is_mrs {
        let (b, entries, count) = decode_mrs(bytes).map_err(|e| invalid_rule("$.payload.mrs", e))?;
        ("mrs", b, entries, count)
    } else {
        if bytes.len() > MAX_TEXT_BYTES { return Err("rule-provider text input limit exceeded".into()); }
        let source = std::str::from_utf8(bytes).map_err(|e| invalid_rule("$.payload.utf8", e))?;
        let yaml = yaml_payload(source);
        let (fmt, lines) = match (format, yaml) {
            (Some("yaml"), r) => ("yaml", r.map_err(|e| invalid_rule("$.payload.yaml", e))?),
            (Some("text"), _) => ("text", text_lines(source)),
            (_, Ok(lines)) => ("yaml", lines),
            _ => ("text", text_lines(source)),
        };
        let (classified, normalized) = classify(lines, behavior)?;
        let count = normalized.len();
        (fmt, classified, normalized, count)
    };
    if let Some(expected) = format { if expected != actual_format { return Err(invalid_rule("$.format", "rule_set_format_mismatch")); } }
    if let Some(expected) = behavior { if expected != actual_behavior { return Err(invalid_rule("$.behavior", "rule_set_behavior_mismatch")); } }
    bound_entries(&entries)?;
    let compiled = rules::build_rule_set_checked(actual_behavior, &entries, &Default::default()).map_err(|e| invalid_rule("$.payload", e))?;
    // Checked core conversion is authoritative; no source rows are returned before conversion succeeds.
    if compiled.len() != entries.len() { return Err("rule_set_accepted_count_mismatch".into()); }
    Ok(AcceptedRules { format: actual_format, behavior: actual_behavior, entries, count: source_count })
}

fn decode_mrs(bytes: &[u8]) -> Result<(RuleSetBehavior, Vec<String>, usize), String> {
    use rules::mrs_parser as mrs;
    if bytes.starts_with(&mrs::ZSTD_MAGIC) {
        // Public bounded decoder is reused before opening the official reader; no custom decompressor.
        mrs::decompress_payload_bounded(bytes, MAX_EXPANDED_BYTES as u64).map_err(|e| e.to_string())?;
        let reader = mrs::UpstreamRuleSetReader::open(bytes).map_err(|e| e.to_string())?;
        if reader.count() > MAX_ENTRIES { return Err("rule_set_dump_count_limit_exceeded".into()); }
        let behavior = behavior_tag(reader.behavior())?;
        let mut entries = Vec::new();
        let mut overflow = false;
        let mut size = 0usize;
        if behavior == RuleSetBehavior::Domain {
            reader.for_each_domain(|s| {
                size = size.saturating_add(s.len() + 1);
                if entries.len() < MAX_ENTRIES && size <= MAX_EXPANDED_BYTES { entries.push(s.to_owned()); } else { overflow = true; }
            }).map_err(|e| e.to_string())?;
        } else {
            reader.for_each_net(|n| {
                let s = n.to_string(); size = size.saturating_add(s.len() + 1);
                if entries.len() < MAX_ENTRIES && size <= MAX_EXPANDED_BYTES { entries.push(s); } else { overflow = true; }
            }).map_err(|e| e.to_string())?;
        }
        if overflow { return Err("rule_set_dump_count_limit_exceeded".into()); }
        return Ok((behavior, entries, reader.count()));
    }
    let (header, payload) = mrs::parse_header(bytes).map_err(|e| e.to_string())?;
    if header.count as usize > MAX_ENTRIES { return Err("rule_set_dump_count_limit_exceeded".into()); }
    let expanded = mrs::decompress_payload_bounded(payload, MAX_EXPANDED_BYTES as u64).map_err(|e| e.to_string())?;
    let behavior = behavior_tag(header.type_tag)?;
    let mut entries = Vec::new();
    let mut overflow = false;
    let mut append = |s: String| { if entries.len() < MAX_ENTRIES { entries.push(s); } else { overflow = true; } };
    if behavior == RuleSetBehavior::IpCidr { mrs::stream_ipcidr_list(Cursor::new(expanded), |n| append(n.to_string())).map_err(|e| e.to_string())?; }
    else { mrs::stream_string_list(Cursor::new(expanded), |s| append(s.to_owned())).map_err(|e| e.to_string())?; }
    if overflow || entries.len() != header.count as usize { return Err("rule_set_mrs_count_mismatch".into()); }
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

async fn validate_proxy_payload(path: &Path, behavior: &str, format: &str) -> Result<(), String> {
    if !behavior.is_empty() || !matches!(format.to_ascii_lowercase().as_str(), "yaml" | "yml") { return Err("proxy-provider expects empty behavior and YAML format".into()); }
    let bytes = read_input(path, MAX_TEXT_BYTES)?;
    let source = std::str::from_utf8(&bytes).map_err(|e| e.to_string())?;
    // Provider bytes are a real config-shaped node payload. Use the same native offline factory,
    // with external process admission disabled by the owned scope, rather than a syntax-only pass.
    let mut payload: Value = serde_yaml::from_str(source).map_err(|e| e.to_string())?;
    payload.apply_merge().map_err(|e| e.to_string())?;
    let mapping = payload.as_mapping().ok_or("proxy-provider expects a mapping")?;
    if mapping.keys().any(|k| k.as_str() != Some("proxies")) { return Err("proxy-provider payload accepts only proxies".into()); }
    let nodes = payload.get("proxies").and_then(Value::as_sequence).ok_or("proxy-provider requires proxies list")?;
    if nodes.is_empty() || nodes.len() > MAX_ENTRIES { return Err("proxy-provider empty or excessive proxies list".into()); }
    validate_config(source).await
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

fn yaml_payload(source: &str) -> Result<Vec<String>, String> {
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
fn text_lines(source: &str) -> Vec<String> { source.lines().map(str::trim).filter(|s| !s.is_empty() && !s.starts_with('#') && !s.starts_with("//")).map(str::to_owned).collect() }
fn bound_entries(entries: &[String]) -> Result<(), String> {
    if entries.len() > MAX_ENTRIES || entries.iter().any(|s| s.len() > 2048) { return Err("rule-provider payload count/line limit exceeded".into()); }
    let nodes: usize = entries.iter().map(|s| s.split('.').count()).sum();
    if nodes > 500_000 || entries.iter().any(|s| s.split('.').count() > 128) { return Err("rule-provider trie node/depth limit exceeded".into()); }
    Ok(())
}
fn unpack_geo(bytes: Vec<u8>) -> Result<Vec<u8>, String> { unpack_geodata(&bytes, MAX_EXPANDED_BYTES) }

/// Pure bounded mature-codec wrapper; consumes all members/trailers, never accepts partial output.
pub fn unpack_geodata(bytes: &[u8], max_expanded: usize) -> Result<Vec<u8>, String> {
    if bytes.len() > MAX_INPUT_BYTES { return Err("geo input limit exceeded".into()); }
    let limit = max_expanded.min(MAX_EXPANDED_BYTES);
    if bytes.starts_with(&rules::mrs_parser::ZSTD_MAGIC) {
        return rules::mrs_parser::decompress_payload_bounded(bytes, limit as u64).map_err(|e| e.to_string());
    }
    if bytes.starts_with(&[0x1f, 0x8b]) {
        let decoder = flate2::read::MultiGzDecoder::new(bytes);
        let mut result = Vec::new();
        decoder.take(limit as u64 + 1).read_to_end(&mut result).map_err(|e| format!("gzip decode/CRC/trailer: {e}"))?;
        if result.len() > limit { return Err("geo expanded limit exceeded".into()); }
        return Ok(result);
    }
    if bytes.len() > limit { return Err("geo expanded limit exceeded".into()); }
    Ok(bytes.to_vec())
}
fn read_input(path: &Path, limit: usize) -> Result<Vec<u8>, String> {
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
    if meta.len() > limit as u64 { return Err("resource input limit exceeded".into()); }
    let mut bytes = Vec::new(); file.take(limit as u64 + 1).read_to_end(&mut bytes).map_err(|e| e.to_string())?;
    if bytes.len() > limit { return Err("resource input limit exceeded".into()); } Ok(bytes)
}
fn parse_behavior(s: &str) -> Result<RuleSetBehavior, String> { match s.to_ascii_lowercase().as_str() { "domain" => Ok(RuleSetBehavior::Domain), "ipcidr" => Ok(RuleSetBehavior::IpCidr), "classic" | "classical" => Ok(RuleSetBehavior::Classical), _ => Err(format!("unsupported rule-provider behavior: {s}")) } }
fn behavior_tag(tag: u8) -> Result<RuleSetBehavior, String> { match tag { 0 => Ok(RuleSetBehavior::Domain), 1 => Ok(RuleSetBehavior::IpCidr), 2 => Ok(RuleSetBehavior::Classical), _ => Err("rule_set_invalid_mrs_behavior".into()) } }
fn name(b: RuleSetBehavior) -> &'static str { match b { RuleSetBehavior::Domain => "domain", RuleSetBehavior::IpCidr => "ipcidr", RuleSetBehavior::Classical => "classical" } }
fn canonical_format(s: &str) -> Result<&'static str, String> { match s.to_ascii_lowercase().as_str() { "yaml" | "yml" => Ok("yaml"), "text" => Ok("text"), "mrs" => Ok("mrs"), _ => Err(format!("unsupported rule-provider format: {s}")) } }
