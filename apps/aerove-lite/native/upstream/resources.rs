//! Offline validation uses the same parsers as the embedded core, with bounded allocations.
use std::{
    fs::File,
    io::{Cursor, Read},
    net::{IpAddr, Ipv6Addr},
    path::Path,
};

use anyhow::{Context, Result, bail, ensure};
use prost::Message;
use serde::Deserialize;
use serde_yaml::Value;

use crate::{
    app::remote_content_manager::providers::{
        proxy_provider::proxy_set_provider::parse_proxy_set,
        rule_provider::{RuleSetBehavior, mobile_make_rules, mobile_parse_classical_rule,
            mobile_range_to_cidrs, mobile_rules_mrs_parse},
    },
    common::geodata::geodata_proto,
};

pub const MAX_INPUT_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_EXPANDED_BYTES: usize = 64 * 1024 * 1024;
const MAX_TEXT_BYTES: usize = 8 * 1024 * 1024;
const MAX_ENTRIES: usize = 250_000;
const MAX_TRIE_NODES: usize = 4_000_000;
const MAX_GEOSITE_MESSAGES: usize = 1_000_000;
const MAX_GEOSITE_SITES: usize = 4096;
const MAX_GEOSITE_DOMAINS: usize = 500_000;
const MAX_GEOSITE_REGEXES: usize = 4096;
const MAX_SITE_DOMAINS: usize = 100_000;
const MAX_SITE_REGEXES: usize = 256;
const MAX_SITE_TRIE_NODES: usize = 250_000;

pub fn validate_resource(kind: &str, path: &Path, behavior: &str, format: &str) -> Result<()> {
    let kind = kind.to_ascii_lowercase();
    let format = format.to_ascii_lowercase();
    let behavior = behavior.to_ascii_lowercase();
    match kind.as_str() {
        "mmdb" => {
            ensure!(behavior.is_empty() && matches!(format.as_str(), "" | "mmdb"), "mmdb expects empty behavior and empty/mmdb format");
            load_mmdb_file(path).map(|_| ())
        }
        "geosite" => {
            ensure!(behavior.is_empty() && matches!(format.as_str(), "" | "dat" | "protobuf"), "geosite expects empty behavior and empty/dat/protobuf format");
            let bytes = read_input(path, MAX_INPUT_BYTES)?;
            decode_geosite(&bytes).map(|_| ())
        }
        "proxy-provider" => {
            ensure!(behavior.is_empty() && matches!(format.as_str(), "yaml" | "yml"), "proxy-provider expects empty behavior and YAML format");
            let bytes = read_input(path, MAX_TEXT_BYTES)?;
            crate::setup_default_crypto_provider();
            parse_proxy_set(&bytes, "candidate").map(|_| ())
        }
        "ruleset" | "rule-provider" => {
            let behavior = match behavior.as_str() {
                "domain" => RuleSetBehavior::Domain,
                "ipcidr" => RuleSetBehavior::Ipcidr,
                "classic" | "classical" => RuleSetBehavior::Classical,
                _ => bail!("unsupported rule-provider behavior: {behavior}"),
            };
            match format.as_str() {
                "mrs" => {
                    let bytes = read_input(path, MAX_INPUT_BYTES)?;
                    mobile_rules_mrs_parse(&bytes, behavior).map(|_| ())
                }
                "yaml" | "yml" | "text" => {
                    let bytes = read_input(path, MAX_TEXT_BYTES)?;
                    let payload = rule_payload(&bytes, &format)?;
                    if behavior == RuleSetBehavior::Classical {
                        for (index, line) in payload.iter().enumerate() {
                            mobile_parse_classical_rule(line).with_context(|| format!("payload[{index}]"))?;
                        }
                    } else {
                        let nodes: usize = payload.iter().map(|line| line.split('.').count()).sum();
                        ensure!(nodes <= 500_000 && payload.iter().all(|line| line.split('.').count() <= 128), "rule-provider trie node/depth limit exceeded");
                        mobile_make_rules(behavior, payload, None, None)?;
                    }
                    Ok(())
                }
                _ => bail!("unsupported rule-provider format: {format}"),
            }
        }
        _ => bail!("unsupported resource kind: {kind}"),
    }
}

pub(crate) fn read_input(path: &Path, limit: usize) -> Result<Vec<u8>> {
    let file = File::open(path).context("open resource candidate")?;
    ensure!(file.metadata()?.is_file(), "resource candidate must be a regular file");
    ensure!(file.metadata()?.len() <= limit as u64, "resource input exceeds {limit} bytes");
    read_bounded(file, limit)
}

fn read_bounded(reader: impl Read, limit: usize) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    reader.take(limit as u64 + 1).read_to_end(&mut bytes)?;
    ensure!(bytes.len() <= limit, "expanded resource exceeds {limit} bytes");
    Ok(bytes)
}

fn unpack_geo(bytes: &[u8]) -> Result<Vec<u8>> {
    ensure!(bytes.len() <= MAX_INPUT_BYTES, "geo input limit exceeded");
    if bytes.starts_with(&[0x1f, 0x8b]) {
        read_bounded(flate2::read::MultiGzDecoder::new(bytes), MAX_EXPANDED_BYTES)
    } else if bytes.starts_with(&[0x28, 0xb5, 0x2f, 0xfd]) {
        unpack_zstd(bytes)
    } else {
        ensure!(bytes.len() <= MAX_EXPANDED_BYTES, "geo expanded limit exceeded");
        Ok(bytes.to_vec())
    }
}

fn unpack_zstd(bytes: &[u8]) -> Result<Vec<u8>> {
    ensure!(bytes.len() <= MAX_INPUT_BYTES, "compressed input limit exceeded");
    let mut decoder = zstd::Decoder::new(Cursor::new(bytes))?;
    decoder.window_log_max(26)?;
    read_bounded(decoder, MAX_EXPANDED_BYTES)
}

pub(crate) fn load_mmdb_file(path: &Path) -> Result<maxminddb::Reader<Vec<u8>>> {
    let bytes = read_input(path, MAX_INPUT_BYTES)?;
    let reader = maxminddb::Reader::from_source(unpack_geo(&bytes)?)?;
    ensure!(reader.metadata().node_count as usize <= MAX_EXPANDED_BYTES / 6, "MMDB search tree limit exceeded");
    reader.verify().context("MMDB structure verification")?;
    Ok(reader)
}

pub(crate) fn decode_geosite(bytes: &[u8]) -> Result<geodata_proto::GeoSiteList> {
    let bytes = unpack_geo(bytes)?;
    let mut budget = GeoBudget::default();
    check_proto(&bytes, 0, &mut budget)?;
    let list = geodata_proto::GeoSiteList::decode(bytes.as_slice()).context("geosite protobuf decode")?;
    ensure!(!list.entry.is_empty(), "geosite contains no entries");
    let mut codes = std::collections::HashSet::new();
    let mut total_regexes = 0usize;
    for site in &list.entry {
        let mut trie_nodes = 0usize;
        let mut regex_count = 0usize;
        ensure!(!site.country_code.is_empty() && site.country_code.len() <= 128, "geosite contains invalid country_code");
        ensure!(codes.insert(site.country_code.to_ascii_lowercase()), "duplicate geosite country_code: {}", site.country_code);
        ensure!(!site.domain.is_empty(), "geosite {} has no domains", site.country_code);
        for domain in &site.domain {
            ensure!((0..=3).contains(&domain.r#type), "geosite {} has unknown domain type {}", site.country_code, domain.r#type);
            ensure!(!domain.value.is_empty() && domain.value.len() <= 2048, "geosite {} has invalid domain value", site.country_code);
            if domain.r#type == 1 {
                regex_count += 1;
                total_regexes += 1;
                ensure!(regex_count <= MAX_SITE_REGEXES, "geosite {} per-site regex count limit exceeded", site.country_code);
                ensure!(total_regexes <= MAX_GEOSITE_REGEXES, "geosite total regex count limit exceeded");
                regex::RegexBuilder::new(&domain.value).case_insensitive(true).size_limit(128 * 1024).build()?;
            } else {
                trie_nodes += domain.value.split('.').count();
                ensure!(trie_nodes <= MAX_SITE_TRIE_NODES, "geosite {} per-site matcher node limit exceeded", site.country_code);
            }
        }
        crate::app::router::rules::geodata::mobile_validate_domains(site.domain.clone())
            .with_context(|| format!("geosite {}", site.country_code))?;
    }
    Ok(list)
}

fn varint(bytes: &mut &[u8]) -> Result<u64> {
    let mut value = 0u64;
    for shift in (0..70).step_by(7) {
        let byte = *bytes.first().context("truncated protobuf varint")?;
        *bytes = &bytes[1..];
        ensure!(shift < 63 || byte <= 1, "protobuf varint overflow");
        value |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 { return Ok(value); }
    }
    bail!("protobuf varint overflow")
}

#[derive(Default)]
struct GeoBudget { messages: usize, sites: usize, domains: usize }

fn check_proto(mut bytes: &[u8], depth: u8, budget: &mut GeoBudget) -> Result<()> {
    budget.messages += 1;
    ensure!(budget.messages <= MAX_GEOSITE_MESSAGES, "geosite message/attribute count limit exceeded");
    let domains_before = budget.domains;
    if depth == 1 {
        budget.sites += 1;
        ensure!(budget.sites <= MAX_GEOSITE_SITES, "geosite site count limit exceeded");
    } else if depth == 2 {
        budget.domains += 1;
        ensure!(budget.domains <= MAX_GEOSITE_DOMAINS, "geosite total domain count limit exceeded");
    }
    while !bytes.is_empty() {
        let tag = varint(&mut bytes)?;
        let (field, wire) = (tag >> 3, tag & 7);
        let nested = match (depth, field, wire) {
            (0, 1, 2) | (1, 2, 2) | (2, 3, 2) => true,
            (1, 1, 2) | (2, 2, 2) | (3, 1, 2) => false,
            (2, 1, 0) | (3, 2 | 3, 0) => { varint(&mut bytes)?; continue; }
            _ => bail!("geosite unknown field/wire at level {depth}: {field}/{wire}"),
        };
        let length = usize::try_from(varint(&mut bytes)?)?;
        ensure!(length <= bytes.len(), "truncated geosite protobuf field");
        let (value, rest) = bytes.split_at(length);
        if nested {
            check_proto(value, depth + 1, budget)?;
        } else {
            ensure!(value.len() <= if depth == 2 { 2048 } else { 128 }, "geosite string size limit exceeded");
            std::str::from_utf8(value).context("geosite invalid UTF-8")?;
        }
        bytes = rest;
    }
    if depth == 1 {
        ensure!(budget.domains - domains_before <= MAX_SITE_DOMAINS, "geosite per-site domain count limit exceeded");
    }
    Ok(())
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RulePayload { payload: Vec<String> }

pub(crate) fn rule_payload(bytes: &[u8], format: &str) -> Result<Vec<String>> {
    ensure!(bytes.len() <= MAX_TEXT_BYTES, "rule-provider text input limit exceeded");
    let payload = if format == "text" {
        std::str::from_utf8(bytes)?.lines().map(str::trim)
            .filter(|line| !line.is_empty() && !line.starts_with('#') && !line.starts_with("//"))
            .map(str::to_owned).collect()
    } else {
        let value = bounded_yaml(bytes)?;
        super::check_value(&value, "$")?;
        serde_yaml::from_value::<RulePayload>(value)?.payload
    };
    ensure!(!payload.is_empty() && payload.len() <= MAX_ENTRIES, "rule-provider payload count must be 1..={MAX_ENTRIES}");
    for (index, line) in payload.iter().enumerate() {
        ensure!(!line.is_empty() && line.len() <= 2048, "payload[{index}] is empty or exceeds 2048 bytes");
        let upper = line.to_ascii_uppercase();
        ensure!(!["PROCESS-NAME,", "PROCESS-PATH,", "UID,", "IN-USER,"].iter().any(|key| upper.contains(key)),
            "payload[{index}] requires unavailable Android process/UID attribution");
    }
    Ok(payload)
}

pub(crate) fn check_proxy_source(bytes: &[u8]) -> Result<()> {
    let value = bounded_yaml(bytes)?;
    let map = value.as_mapping().context("proxy-provider must be a mapping")?;
    ensure!(map.len() == 1 && value.get("proxies").is_some(), "proxy-provider accepts only the proxies field");
    let proxies = value.get("proxies").and_then(Value::as_sequence).context("proxies must be a sequence")?;
    ensure!(!proxies.is_empty() && proxies.len() <= MAX_ENTRIES, "proxy-provider node count limit exceeded or empty");
    for (index, proxy) in proxies.iter().enumerate() {
        ensure!(proxy.is_mapping(), "proxies[{index}] must be a mapping");
    }
    let source = serde_yaml::to_string(&value)?;
    super::parse(&source)?;
    Ok(())
}

fn bounded_yaml(bytes: &[u8]) -> Result<Value> {
    ensure!(bytes.len() <= MAX_TEXT_BYTES, "YAML input limit exceeded");
    // Anchors/aliases are unnecessary in downloaded payloads and can multiply decoded allocations.
    let text = std::str::from_utf8(bytes)?;
    reject_yaml_aliases(text)?;
    let value: Value = serde_yaml::from_slice(bytes)?;
    let mut budget = 0usize;
    check_yaml(&value, 0, &mut budget)?;
    Ok(value)
}

fn reject_yaml_aliases(text: &str) -> Result<()> {
    let mut quote = None;
    let mut escaped = false;
    let mut comment = false;
    let mut separated = true;
    let mut characters = text.chars().peekable();
    while let Some(character) = characters.next() {
        if comment {
            if character == '\n' { comment = false; separated = true; }
            continue;
        }
        if let Some(delimiter) = quote {
            if escaped { escaped = false; }
            else if delimiter == '"' && character == '\\' { escaped = true; }
            else if character == delimiter {
                if delimiter == '\'' && characters.peek() == Some(&'\'') { characters.next(); }
                else { quote = None; }
            }
            continue;
        }
        if separated && matches!(character, '\'' | '"') { quote = Some(character); }
        else if separated && character == '#' { comment = true; }
        else { ensure!(!separated || !matches!(character, '&' | '*'), "provider YAML anchors/aliases are not supported"); }
        separated = character.is_whitespace() || matches!(character, ':' | '[' | ']' | '{' | '}' | ',');
    }
    Ok(())
}

pub(crate) fn check_classical_rule(rule: &str) -> crate::Result<()> {
    let upper = rule.to_ascii_uppercase();
    if ["PROCESS-NAME,", "PROCESS-PATH,", "UID,", "IN-USER,"].iter().any(|token| upper.contains(token)) {
        return Err(crate::Error::InvalidConfig("classical rule requires unavailable Android process/UID attribution".into()));
    }
    if upper.starts_with("RULE-SET,") {
        return Err(crate::Error::InvalidConfig("classical provider cannot nest RULE-SET references".into()));
    }
    Ok(())
}

fn check_keys(value: &Value, fields: &str) -> Result<()> {
    let map = value.as_mapping().context("expected options mapping")?;
    for key in map.keys() {
        let key = key.as_str().context("option keys must be strings")?;
        ensure!(fields.split_whitespace().any(|allowed| allowed == key), "unknown or unsupported option: {key}");
    }
    Ok(())
}

pub(crate) fn check_proxy_fields(proxy: &Value) -> Result<()> {
    let kind = proxy.get("type").and_then(Value::as_str).context("missing proxy type")?;
    let fields = match kind {
        "direct" | "reject" => "",
        "ss" => "cipher password udp plugin plugin-opts",
        "socks5" => "username password tls sni skip-cert-verify udp",
        "anytls" => "password alpn sni skip-cert-verify udp tls-cert tls-key",
        "trojan" => "password alpn sni skip-cert-verify udp network grpc-opts ws-opts tls-cert tls-key",
        "vmess" => "uuid alter-id alterId cipher udp tls skip-cert-verify server-name servername network ws-opts h2-opts grpc-opts xhttp-opts splithttp-opts tls-cert tls-key",
        "vless" => "uuid udp tls skip-cert-verify server-name servername network ws-opts h2-opts grpc-opts xhttp-opts splithttp-opts reality-opts flow tls-cert tls-key",
        "tuic" => "uuid password ip heartbeat-interval alpn disable-sni reduce-rtt request-timeout udp-relay-mode congestion-controller max-udp-relay-packet-size fast-open skip-cert-verify max-open-stream sni gc-interval gc-lifetime send-window receive-window tls-cert tls-key",
        "hysteria2" => "ports hop-interval hop_interval password obfs obfs-password alpn up down sni skip-cert-verify ca ca-str fingerprint udp-mtu disable-mtu-discovery cwnd tls-cert tls-key",
        _ => bail!("unsupported Android proxy type: {kind}"),
    };
    let common = if matches!(kind, "direct" | "reject") { "type name" }
        else if kind == "hysteria2" { "type name server port" }
        else { "type name server port connect-via dialer-proxy" };
    check_keys(proxy, &format!("{common} {fields}"))?;
    if kind == "ss" {
        let cipher = proxy.get("cipher").and_then(Value::as_str).context("missing SS cipher")?;
        #[cfg(feature = "shadowsocks")]
        crate::proxy::shadowsocks::map_cipher(cipher)?;
        #[cfg(not(feature = "shadowsocks"))]
        bail!("shadowsocks is not enabled: {cipher}");
    }
    for (key, fields) in [
        ("ws-opts", "path headers max-early-data early-data-header-name"),
        ("h2-opts", "host path"), ("grpc-opts", "grpc-service-name"),
        ("xhttp-opts", "path host mode headers x-padding-bytes"),
        ("splithttp-opts", "path host mode headers x-padding-bytes"),
        ("reality-opts", "public-key short-id"),
    ] {
        if let Some(value) = proxy.get(key) { check_keys(value, fields).with_context(|| key.to_owned())?; }
    }
    if let Some(options) = proxy.get("plugin-opts") {
        let fields = match proxy.get("plugin").and_then(Value::as_str) {
            Some("obfs") => "host mode",
            Some("v2ray-plugin") => "host mode path mux tls port skip-cert-verify headers",
            Some("shadow-tls") => "host password strict",
            _ => bail!("plugin-opts requires a supported plugin"),
        };
        check_keys(options, fields)?;
        for key in ["host", "mode", "path", "password"] {
            if let Some(value) = options.get(key) { ensure!(value.is_string(), "plugin-opts.{key} must be a string"); }
        }
        for key in ["mux", "tls", "strict", "skip-cert-verify"] {
            if let Some(value) = options.get(key) { ensure!(value.is_bool(), "plugin-opts.{key} must be boolean"); }
        }
        if let Some(value) = options.get("port") { ensure!(value.as_u64().is_some_and(|port| (1..=65535).contains(&port)), "invalid plugin port"); }
        if let Some(headers) = options.get("headers") {
            let headers = headers.as_mapping().context("plugin headers must be a mapping")?;
            ensure!(headers.iter().all(|(key, value)| key.is_string() && value.is_string()), "plugin headers must be strings");
        }
    }
    Ok(())
}

fn check_yaml(value: &Value, depth: usize, budget: &mut usize) -> Result<()> {
    *budget += 1;
    ensure!(depth <= 24 && *budget <= MAX_ENTRIES, "provider YAML nesting/node limit exceeded");
    match value {
        Value::Mapping(map) => for (key, value) in map {
            ensure!(key.is_string(), "provider YAML requires string keys");
            check_yaml(value, depth + 1, budget)?;
        },
        Value::Sequence(items) => for value in items { check_yaml(value, depth + 1, budget)?; },
        Value::String(value) => ensure!(value.len() <= 16 * 1024, "provider scalar size limit exceeded"),
        Value::Tagged(_) => bail!("provider YAML tags are not supported"),
        _ => {}
    }
    Ok(())
}

pub(crate) fn bounded_mrs(bytes: &[u8], expected: RuleSetBehavior) -> Result<Vec<u8>> {
    ensure!(expected != RuleSetBehavior::Classical, "MRS does not support classical behavior");
    let expanded = unpack_zstd(bytes)?;
    let mut cursor = Cursor::new(expanded.as_slice());
    let mut magic = [0; 4];
    cursor.read_exact(&mut magic)?;
    ensure!(magic == [b'M', b'R', b'S', 1], "invalid MRS magic");
    let behavior = read_bytes(&mut cursor, 1)?[0];
    ensure!(behavior == if expected == RuleSetBehavior::Domain { 0 } else { 1 }, "MRS behavior mismatch");
    let count = read_length(&mut cursor)?;
    ensure!(count <= MAX_ENTRIES, "MRS declared rule count limit exceeded");
    ensure!(read_length(&mut cursor)? == 0, "MRS v1 extra length must be zero");
    ensure!(read_bytes(&mut cursor, 1)?[0] == 1, "unsupported MRS payload version");
    if expected == RuleSetBehavior::Domain {
        let leaves = read_words(&mut cursor)?;
        let bitmap = read_words(&mut cursor)?;
        let labels_len = read_length(&mut cursor)?;
        ensure!(labels_len < MAX_TRIE_NODES, "MRS domain trie size limit exceeded");
        read_bytes(&mut cursor, labels_len)?;
        let nodes = labels_len + 1;
        let bits = nodes * 2 - 1;
        ensure!(leaves.len() == nodes.div_ceil(64) && bitmap.len() == bits.div_ceil(64), "MRS domain bitmap lengths do not match labels");
        ensure!(leaves.last().is_none_or(|last| nodes % 64 == 0 || last >> (nodes % 64) == 0), "MRS leaf padding is not zero");
        ensure!(bitmap.last().is_none_or(|last| bits % 64 == 0 || last >> (bits % 64) == 0), "MRS bitmap padding is not zero");
        let mut pending = 1usize;
        let mut finished = 0usize;
        for bit in 0..bits {
            if bitmap[bit / 64] & (1u64 << (bit % 64)) == 0 { pending += 1; }
            else { ensure!(pending > 0, "MRS invalid tree topology"); pending -= 1; finished += 1; }
            ensure!(pending > 0 || bit + 1 == bits, "MRS tree ends before payload");
        }
        ensure!(pending == 0 && finished == nodes, "MRS invalid tree node count");
    } else {
        let ranges = read_length(&mut cursor)?;
        ensure!(ranges <= MAX_ENTRIES, "MRS range count limit exceeded");
        let mut cidrs = 0usize;
        for _ in 0..ranges {
            let range = read_bytes(&mut cursor, 32)?;
            let from = IpAddr::V6(Ipv6Addr::from(<[u8; 16]>::try_from(&range[..16])?));
            let to = IpAddr::V6(Ipv6Addr::from(<[u8; 16]>::try_from(&range[16..])?));
            ensure!(from <= to, "MRS reversed IP range");
            cidrs += mobile_range_to_cidrs(from, to)?.len();
            ensure!(cidrs <= MAX_ENTRIES, "MRS expanded CIDR count limit exceeded");
        }
    }
    ensure!(cursor.position() as usize == expanded.len(), "MRS contains trailing data");
    Ok(expanded)
}

fn read_length(cursor: &mut Cursor<&[u8]>) -> Result<usize> {
    let bytes = read_bytes(cursor, 8)?;
    let value = i64::from_be_bytes(bytes.try_into()?);
    ensure!(value >= 0, "negative MRS length");
    Ok(usize::try_from(value)?)
}

fn read_bytes<'a>(cursor: &mut Cursor<&'a [u8]>, count: usize) -> Result<&'a [u8]> {
    let bytes = *cursor.get_ref();
    let start = usize::try_from(cursor.position())?;
    let end = start.checked_add(count).context("MRS length overflow")?;
    ensure!(end <= bytes.len(), "MRS length exceeds remaining payload");
    cursor.set_position(end as u64);
    Ok(&bytes[start..end])
}

fn read_words(cursor: &mut Cursor<&[u8]>) -> Result<Vec<u64>> {
    let count = read_length(cursor)?;
    let bytes = read_bytes(cursor, count.checked_mul(8).context("MRS word count overflow")?)?;
    Ok(bytes.chunks_exact(8).map(|chunk| u64::from_be_bytes(chunk.try_into().unwrap())).collect())
}
