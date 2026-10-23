use std::{fs, path::PathBuf, sync::atomic::{AtomicU64, Ordering}};

use crate::validate_catching_panic;
use clash_lib::mobile::resources::{MAX_EXPANDED_BYTES, MAX_INPUT_BYTES, validate_resource};

static NEXT: AtomicU64 = AtomicU64::new(1);

struct Files(PathBuf);

impl Files {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("aerove-resource-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::SeqCst)));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
    fn file(&self, name: &str, bytes: impl AsRef<[u8]>) -> PathBuf {
        let path = self.0.join(name);
        fs::write(&path, bytes).unwrap();
        path
    }
}

impl Drop for Files {
    fn drop(&mut self) {
        for entry in fs::read_dir(&self.0).unwrap() { let _ = fs::remove_file(entry.unwrap().path()); }
        let _ = fs::remove_dir(&self.0);
    }
}

#[test]
fn validation_panics_become_errors_instead_of_success_or_unwinding() {
    let result = validate_catching_panic(|| panic!("untrusted parser panic"));
    assert_eq!(result.unwrap_err(), "validation parser panicked: untrusted parser panic");
    assert!(validate_catching_panic(|| Ok(())).is_ok());
    assert_eq!(validate_catching_panic(|| anyhow::bail!("bad candidate")).unwrap_err(), "bad candidate");
    assert!(validate_catching_panic(|| clash_lib::mobile::validate("rules: [\"MATCH,DIRECT\"]").map_err(Into::into)).is_ok());
    assert!(validate_catching_panic(|| clash_lib::mobile::validate("sniffer: {enable: true}").map_err(Into::into)).is_err());
}

#[test]
fn rule_provider_formats_use_core_semantics_and_aliases() {
    let files = Files::new();
    for (behavior, yaml, text) in [
        ("domain", "payload: ['example.com', '+.example.net', '*.example.org']", "# comment\nexample.com\n+.example.net\n"),
        ("ipcidr", "payload: ['192.0.2.0/24', '2001:db8::/32']", "192.0.2.0/24\n2001:db8::/32"),
        ("classic", "payload: ['DOMAIN-SUFFIX,example.com', 'IP-CIDR,192.0.2.0/24,no-resolve', 'GEOSITE,cn']", "DOMAIN-SUFFIX,example.com\nIP-CIDR,192.0.2.0/24,no-resolve\nGEOIP,CN"),
    ] {
        let path = files.file("rules.yaml", yaml);
        validate_resource("ruleset", &path, behavior, "YAML").unwrap();
        validate_resource("rule-provider", &path, behavior, "yaml").unwrap();
        let path = files.file("rules.txt", text);
        validate_resource("ruleset", &path, behavior, "text").unwrap();
    }
}

#[test]
fn rules_reject_unknown_nested_bad_cidr_and_android_unsupported_payloads() {
    let files = Files::new();
    for (behavior, payload) in [
        ("domain", "payload: [example.com]\nignored: true"),
        ("domain", "payload: [{domain: example.com}]"),
        ("domain", "payload: ['invalid..example']"),
        ("ipcidr", "payload: ['not-a-cidr']"),
        ("classical", "payload: ['PROCESS-NAME-REGEX,app.*']"),
        ("classical", "payload: ['RULE-SET,nested']"),
        ("classical", "payload: ['UID,1000']"),
        ("classical", "payload: ['UNKNOWN-RULE,value']"),
    ] {
        let path = files.file("bad.yaml", payload);
        assert!(validate_resource("ruleset", &path, behavior, "yaml").is_err(), "accepted {payload}");
    }
    let path = files.file("exact-process.yaml", "payload: ['PROCESS-NAME,app']");
    validate_resource("ruleset", &path, "classical", "yaml").unwrap();
    let path = files.file("alias.yaml", "payload: &list [example.com]\nother: *list");
    assert!(validate_resource("ruleset", &path, "domain", "yaml").is_err());
}

#[test]
fn proxy_provider_constructs_real_handlers_and_checks_all_nested_options() {
    let files = Files::new();
    let good = "proxies: [{name: node, type: direct}]";
    let path = files.file("nodes.yaml", good);
    validate_resource("proxy-provider", &path, "", "YAML").unwrap();
    let vmess = "proxies: [{name: vm, type: vmess, server: example.com, port: 443, uuid: 'e780a527-1160-441b-ab6e-e2c2b6127e1b', alterId: 0, network: ws, ws-opts: {path: '/x', headers: {X-Token: 'a&b'}}}]";
    let path = files.file("vmess.yaml", vmess);
    validate_resource("proxy-provider", &path, "", "yaml").unwrap();
    for yaml in [
        "proxies: [{name: node, type: direct, ignored: true}]",
        "proxies: [{name: node, type: mieru, server: example.com, port: 443}]",
        "proxies: [{name: node, type: vless, server: example.com, port: 443, uuid: bad, ws-opts: {unknown: true}}]",
        "proxies: [{name: node, type: vless, server: example.com, port: 443, uuid: bad}]",
        "proxies: [{name: node, type: anytls, server: example.com, port: 443, password: secret, min-idle-session: 2}]",
        "proxies: [{name: node, type: ss, server: example.com, port: 443, password: secret, cipher: unknown}]",
        "proxies: [{name: node, type: ss, server: example.com, port: 443, password: secret, cipher: aes-128-gcm, plugin: obfs, plugin-opts: {mode: http, ignored: true}}]",
        "proxies: [{name: node, type: direct}, {name: broken, type: mieru}]",
        "proxies: []",
        "proxies: [{name: node, type: direct}]\npayload: []",
    ] {
        let path = files.file("bad-nodes.yaml", yaml);
        assert!(validate_resource("proxy-provider", &path, "", "yaml").is_err(), "accepted {yaml}");
    }
    assert_eq!(fs::read_to_string(files.0.join("nodes.yaml")).unwrap(), good);
}

#[test]
fn proxy_provider_validates_lazy_protocol_uuids_before_accepting_any_nodes() {
    let files = Files::new();
    for kind in ["vless", "vmess"] {
        let options = if kind == "vmess" { ", alterId: 0" } else { "" };
        for uuid in ["e780a527-1160-441b-ab6e-e2c2b6127e1b", "e780a5271160441bab6ee2c2b6127e1b"] {
            let yaml = format!("proxies: [{{name: node, type: {kind}, server: example.com, port: 443, uuid: '{uuid}'{options}}}]");
            let path = files.file("valid-uuid.yaml", &yaml);
            validate_resource("proxy-provider", &path, "", "yaml").unwrap();
        }
        for uuid in ["bad", "", "e780a527-1160-441b-ab6e-e2c2b6127e1z"] {
            let yaml = format!("proxies: [{{name: valid, type: direct}}, {{name: broken, type: {kind}, server: example.com, port: 443, uuid: '{uuid}'{options}}}]");
            let path = files.file("invalid-uuid.yaml", &yaml);
            let error = validate_resource("proxy-provider", &path, "", "yaml").unwrap_err();
            assert!(format!("{error:#}").contains(&format!("invalid {kind} UUID")), "{error:#}");
            assert!(clash_lib::mobile::validate(&yaml).is_err(), "accepted {yaml}");
        }
    }
}

fn zstd_raw(bytes: &[u8]) -> Vec<u8> {
    assert!(bytes.len() < 256);
    let mut frame = vec![0x28, 0xb5, 0x2f, 0xfd, 0x20, bytes.len() as u8];
    let header = ((bytes.len() as u32) << 3) | 1;
    frame.extend_from_slice(&header.to_le_bytes()[..3]);
    frame.extend_from_slice(bytes);
    frame
}

fn mrs_domain() -> Vec<u8> { mrs_domain_count(1) }

fn mrs_domain_count(count: i64) -> Vec<u8> {
    let labels = b"moc.elpmaxe";
    let mut bytes = b"MRS\x01\x00".to_vec();
    bytes.extend_from_slice(&count.to_be_bytes());
    bytes.extend_from_slice(&0i64.to_be_bytes());
    bytes.push(1);
    bytes.extend_from_slice(&1i64.to_be_bytes());
    bytes.extend_from_slice(&(1u64 << labels.len()).to_be_bytes());
    bytes.extend_from_slice(&1i64.to_be_bytes());
    let mut bitmap = 0u64;
    for index in 0..labels.len() { bitmap |= 1 << (index * 2 + 1); }
    bitmap |= 1 << (labels.len() * 2);
    bytes.extend_from_slice(&bitmap.to_be_bytes());
    bytes.extend_from_slice(&(labels.len() as i64).to_be_bytes());
    bytes.extend_from_slice(labels);
    zstd_raw(&bytes)
}

#[test]
fn mrs_uses_real_loader_rejecting_wrong_behavior_lengths_and_trailing_payload() {
    let files = Files::new();
    let path = files.file("domain.mrs", mrs_domain());
    validate_resource("ruleset", &path, "domain", "MRS").unwrap();
    assert!(validate_resource("ruleset", &path, "ipcidr", "mrs").is_err());
    assert!(validate_resource("ruleset", &path, "classic", "mrs").is_err());
    let mut ipcidr = b"MRS\x01\x01".to_vec();
    ipcidr.extend_from_slice(&1i64.to_be_bytes());
    ipcidr.extend_from_slice(&0i64.to_be_bytes());
    ipcidr.push(1);
    ipcidr.extend_from_slice(&1i64.to_be_bytes());
    ipcidr.extend_from_slice(&std::net::Ipv4Addr::new(192, 0, 2, 0).to_ipv6_mapped().octets());
    ipcidr.extend_from_slice(&std::net::Ipv4Addr::new(192, 0, 2, 255).to_ipv6_mapped().octets());
    let path = files.file("ipcidr.mrs", zstd_raw(&ipcidr));
    validate_resource("ruleset", &path, "ipcidr", "mrs").unwrap();
    let mut malformed = b"MRS\x01\x00".to_vec();
    malformed.extend_from_slice(&1i64.to_be_bytes());
    malformed.extend_from_slice(&0i64.to_be_bytes());
    malformed.push(1);
    malformed.extend_from_slice(&i64::MAX.to_be_bytes());
    let path = files.file("oversized-length.mrs", zstd_raw(&malformed));
    assert!(validate_resource("ruleset", &path, "domain", "mrs").is_err());
    let path = files.file("invalid.mrs", b"not compressed MRS");
    assert!(validate_resource("ruleset", &path, "domain", "mrs").is_err());
}

fn field(number: u8, bytes: &[u8]) -> Vec<u8> {
    let mut output = vec![(number << 3) | 2];
    let mut length = bytes.len();
    while length >= 128 {
        output.push((length as u8 & 127) | 128);
        length >>= 7;
    }
    output.push(length as u8);
    output.extend_from_slice(bytes);
    output
}

#[test]
#[ignore = "requires the fixed production dataset via build.py --test --test-geosite"]
fn production_default_geosite_is_accepted_by_the_real_core_loader() {
    let path = PathBuf::from(std::env::var_os("AEROVE_DEFAULT_GEOSITE")
        .expect("use build.py --test --test-geosite with the SHA256-verified fixed release fixture"));
    assert_eq!(fs::metadata(&path).unwrap().len(), 6111771);
    validate_resource("geosite", &path, "", "dat").unwrap();
}

#[test]
fn geosite_still_rejects_excessive_per_site_matcher_work() {
    let files = Files::new();
    let mut site = field(1, b"MANY-REGEX");
    let mut regex = vec![8, 1];
    regex.extend(field(2, b"example"));
    for _ in 0..257 { site.extend(field(2, &regex)); }
    let path = files.file("too-many-regex.dat", field(1, &site));
    let error = validate_resource("geosite", &path, "", "dat").unwrap_err();
    assert!(error.to_string().contains("per-site regex count limit"));
    let mut site = field(1, b"MANY-DOMAINS");
    let mut domain = vec![8, 2];
    domain.extend(field(2, b"example.com"));
    for _ in 0..100_001 { site.extend(field(2, &domain)); }
    let path = files.file("too-many-domains.dat", field(1, &site));
    let error = validate_resource("geosite", &path, "", "dat").unwrap_err();
    assert!(error.to_string().contains("per-site domain count limit"));
}

fn geosite(kind: u8, domain: &str) -> Vec<u8> {
    let mut node = vec![8, kind];
    node.extend(field(2, domain.as_bytes()));
    let mut site = field(1, b"TEST");
    site.extend(field(2, &node));
    field(1, &site)
}

#[test]
fn geosite_uses_protobuf_and_real_matcher_validation_with_bounded_compression() {
    let files = Files::new();
    let raw = geosite(2, "example.com");
    let path = files.file("geosite.dat", &raw);
    validate_resource("geosite", &path, "", "dat").unwrap();
    let path = files.file("geosite.dat.zst", zstd_raw(&raw));
    validate_resource("geosite", &path, "", "protobuf").unwrap();
    let path = files.file("geosite.dat.gz", include_bytes!("../tests/fixtures/geosite.dat.gz"));
    validate_resource("geosite", &path, "", "dat").unwrap();
    for bytes in [geosite(1, "["), geosite(99, "example.com"), vec![0x0a, 0x7f], Vec::new()] {
        let path = files.file("bad-geosite.dat", bytes);
        assert!(validate_resource("geosite", &path, "", "").is_err());
    }
}

#[test]
fn mmdb_uses_database_verification_instead_of_extension_or_magic_only() {
    let files = Files::new();
    let path = files.file("Country.mmdb", include_bytes!("../tests/fixtures/empty-country.mmdb"));
    validate_resource("mmdb", &path, "", "mmdb").unwrap();
    let path = files.file("Country.mmdb.zst", zstd_raw(include_bytes!("../tests/fixtures/empty-country.mmdb")));
    validate_resource("mmdb", &path, "", "mmdb").unwrap();
    let path = files.file("bad.mmdb", b"\xab\xcd\xefMaxMind.com");
    assert!(validate_resource("mmdb", &path, "", "mmdb").is_err());
}

fn oversized_zstd() -> Vec<u8> {
    let size = MAX_EXPANDED_BYTES + 1;
    let mut frame = vec![0x28, 0xb5, 0x2f, 0xfd, 0x80, 0x80];
    frame.extend_from_slice(&(size as u32).to_le_bytes());
    let mut remaining = size;
    while remaining > 0 {
        let block = remaining.min(128 * 1024);
        let header = ((block as u32) << 3) | 2 | u32::from(block == remaining);
        frame.extend_from_slice(&header.to_le_bytes()[..3]);
        frame.push(0);
        remaining -= block;
    }
    frame
}

#[test]
fn input_and_decompression_limits_reject_remote_bombs_without_core_start() {
    let files = Files::new();
    let path = files.file("large.dat", []);
    fs::File::options().write(true).open(&path).unwrap().set_len(MAX_INPUT_BYTES as u64 + 1).unwrap();
    assert!(validate_resource("geosite", &path, "", "dat").is_err());
    let path = files.file("bomb.zst", oversized_zstd());
    assert!(validate_resource("geosite", &path, "", "dat").is_err());
    assert!(validate_resource("ruleset", &path, "domain", "mrs").is_err());
    assert!(validate_resource("unknown-kind", &path, "", "").is_err());
    assert!(validate_resource("proxy-provider", &path, "", "text").is_err());
    assert!(validate_resource("mmdb", &files.0, "", "mmdb").is_err());
}

#[test]
fn offline_rule_sets_match_standard_local_classification_and_normalized_payloads() {
    use clash_lib::mobile::resources::{inspect_rule_set, dump_rule_set};
    let files = Files::new();
    for (name, source, format, behavior, count) in [
        ("empty", "", "text", "classical", 0),
        ("empty-yaml", "payload: []\n", "yaml", "classical", 0),
        ("domains", "# comment\nexample.com\n// comment\n+.example.org\n", "text", "domain", 2),
        ("rules-alias", "rules: ['DOMAIN-SUFFIX,example.com']\n", "yaml", "domain", 1),
        ("suffix-only", ".example.com\n", "text", "domain", 1),
        ("both-lists", "payload: [example.com]\nrules: [example.org]\n", "yaml", "domain", 2),
        ("addresses", "192.0.2.1\n2001:db8::1\n", "text", "ipcidr", 2),
        ("classical", "DOMAIN-KEYWORD,example\n", "text", "classical", 1),
    ] {
        let path = files.file(name, source);
        let info: serde_json::Value = serde_json::from_str(&inspect_rule_set(&path).unwrap()).unwrap();
        assert_eq!(info, serde_json::json!({"format":format,"behavior":behavior,"count":count}));
        let dump = dump_rule_set(&path, behavior, format).unwrap();
        if count == 0 { assert!(dump.is_empty()); }
        else {
            assert!(dump.ends_with('\n'));
            let materialized = files.file("materialized", &dump);
            validate_resource("ruleset", &materialized, behavior, "text").unwrap();
        }
        if name == "rules-alias" { assert_eq!(dump, "+.example.com\n"); }
        if name == "suffix-only" { assert_eq!(dump, ".example.com\n"); }
        if name == "addresses" { assert!(dump.contains("192.0.2.1/32\n")); assert!(dump.contains("2001:db8::1/128\n")); }
    }
    let duplicate = files.file("duplicates", "+.example.com\n+.example.com\nexample.com\n.example.com\n");
    let info: serde_json::Value = serde_json::from_str(&inspect_rule_set(&duplicate).unwrap()).unwrap();
    assert_eq!(info["count"], 4); // Standard Count preserves successfully inserted source entries.
    assert_eq!(dump_rule_set(&duplicate, "domain", "text").unwrap(), "+.example.com\n");
    let separate = files.file("separate", ".example.com\nexample.net\nexample.org\n.example.org\n");
    assert_eq!(dump_rule_set(&separate, "domain", "text").unwrap(), "+.example.org\n.example.com\nexample.net\n");
    let wildcards = files.file("wildcards", "*.example.com\n+.example.org\ntest.*.google.com\n.test.*.google.com\n.example.net\n");
    assert_eq!(dump_rule_set(&wildcards, "domain", "text").unwrap(), "*.example.com\n+.example.org\n+.test.*.google.com\n.example.net\n");
    validate_resource("ruleset", &files.file("empty-runtime", b""), "classical", "text").unwrap();
    validate_resource("ruleset", &files.file("empty-runtime-yaml", b"payload: []\n"), "classical", "yaml").unwrap();
}

#[test]
fn offline_rule_sets_dump_real_mrs_and_reject_mismatch_and_ambiguous_sources() {
    use clash_lib::mobile::resources::{inspect_rule_set, dump_rule_set};
    let files = Files::new();
    let path = files.file("domain.mrs", mrs_domain());
    assert_eq!(serde_json::from_str::<serde_json::Value>(&inspect_rule_set(&path).unwrap()).unwrap(), serde_json::json!({"format":"mrs","behavior":"domain","count":1}));
    assert_eq!(dump_rule_set(&path, "domain", "mrs").unwrap(), "example.com\n");
    let header_count = files.file("source-count.mrs", mrs_domain_count(4));
    let info: serde_json::Value = serde_json::from_str(&inspect_rule_set(&header_count).unwrap()).unwrap();
    assert_eq!(info["count"], 4); // Header logical count can differ from unique core terminals.
    assert_eq!(dump_rule_set(&header_count, "domain", "mrs").unwrap(), "example.com\n");
    assert!(inspect_rule_set(&files.file("empty-count.mrs", mrs_domain_count(0))).is_err());
    assert!(dump_rule_set(&path, "ipcidr", "mrs").is_err());
    assert!(dump_rule_set(&path, "domain", "text").is_err());
    for source in ["example.com\n192.0.2.0/24\n", "bad/name\n", "UNKNOWN-RULE,value\n"] {
        assert!(inspect_rule_set(&files.file("invalid", source)).is_err(), "accepted invalid source {source:?}");
    }
    let mixed_invalid = files.file("mixed-invalid", "example.com\ninvalid..example\n");
    assert!(inspect_rule_set(&mixed_invalid).is_err(), "A valid entry must not hide a rejected core trie insertion");
    assert!(inspect_rule_set(&files.file("broken.mrs", [0x28, 0xb5, 0x2f, 0xfd, 0])).is_err());
    assert!(inspect_rule_set(&files.0.join("absent")).is_err());
}
