use std::{fs, path::PathBuf, sync::atomic::{AtomicU64, Ordering}};
use aerove_meow_core::config_validation::{self as validation, dump_rule_set, inspect_rule_set, validate_resource as validate_resource_future};
#[path="support/resource_roots.rs"] mod preflight_roots;
#[path="support/upstream_mrs.rs"] mod upstream_mrs;

// Keep the offline converter's concrete future out of each Tokio test's layout query.
fn validate_resource<'a>(kind: &'a str, path: &'a std::path::Path, behavior: &'a str, format: &'a str)
    -> std::pin::Pin<Box<dyn std::future::Future<Output=Result<(),String>>+Send+'a>> {
    Box::pin(validate_resource_future(kind, path, behavior, format))
}

struct Fixture(PathBuf);
impl Fixture {
    fn new(bytes: &[u8]) -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!("aerove-meow-offline-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        fs::write(&path, &bytes).unwrap(); Self(path)
    }
}
impl Drop for Fixture { fn drop(&mut self) { let _ = fs::remove_file(&self.0); } }

fn controlled_upstream_mrs() -> Vec<u8> {
    let entries: Vec<String> = (0..411_634).map(|index| format!("rule{index:06}.test")).collect();
    let borrowed: Vec<&str> = entries.iter().map(String::as_str).collect();
    let body = upstream_mrs::encode_domain_set(&borrowed);
    upstream_mrs::encode_upstream_mrs(meow_config::offline_rules::mrs_parser::TYPE_DOMAIN, entries.len(), &body)
}

#[tokio::test]
async fn large_controlled_upstream_mrs_preflight_uses_checked_core_and_still_requires_prepared_conversion() {
    let bytes = controlled_upstream_mrs();
    assert!(bytes.starts_with(&meow_config::offline_rules::mrs_parser::ZSTD_MAGIC));
    let roots = preflight_roots::Roots::new();
    let path = roots.read.join("ad-domain.mrs"); fs::write(&path, &bytes).unwrap();
    let receipt: serde_json::Value = serde_json::from_str(&validation::preflight_rule_resource(&path, "domain", "mrs").unwrap()).unwrap();
    assert_eq!(receipt, serde_json::json!({"state":"validated","format":"mrs","behavior":"domain","count":411_634,
        "requires":{"geoip":false,"asn":false,"geosite":false}}));
    let yaml = format!("dns: {{enable: false}}\nrule-providers:\n ad: {{type: file,behavior: domain,format: mrs,path: {:?}}}\nrules: ['RULE-SET,ad,REJECT','MATCH,DIRECT']\n", path.to_str().unwrap());
    let work: std::pin::Pin<Box<dyn std::future::Future<Output=Result<(),String>>+Send+'_>> = Box::pin(
        aerove_meow_core::resource_fetch::validate_prepared(&yaml, roots.read.to_str().unwrap(), roots.cache.to_str().unwrap()));
    work.await.unwrap();
    assert_eq!(fs::read(&path).unwrap(), bytes.as_slice());
    let summary: serde_json::Value = serde_json::from_str(&inspect_rule_set(&path).unwrap()).unwrap();
    assert_eq!(summary, serde_json::json!({"format":"mrs","behavior":"domain","count":411_634}));
    validate_resource("ruleset", &path, "domain", "mrs").await.unwrap();
    validate_resource("rule-provider", &path, "DOMAIN", "MRS").await.unwrap();
    // Official MRS dumps add no host entry cap, as Standard's handleDumpRuleSet; beyond the Core decode bounds only the 64 MiB text budget applies.
    let dump = dump_rule_set(&path, "domain", "mrs").unwrap();
    assert_eq!(dump.lines().count(), 411_634);
    assert_eq!(dump.lines().collect::<std::collections::BTreeSet<_>>().len(), 411_634);
    assert!(dump.len() <= validation::MAX_DUMP_TEXT_BYTES);
}

#[tokio::test]
async fn upstream_mrs_preflight_rejects_identity_corruption_and_256m_expansion_without_relaxing_other_caps() {
    let valid = Fixture::new(&controlled_upstream_mrs());
    assert_eq!(validation::preflight_rule_resource(&valid.0, "ipcidr", "mrs").unwrap_err(),
        "rule_set_invalid: $.behavior: rule_set_behavior_mismatch");
    assert_eq!(validation::preflight_rule_resource(&valid.0, "domain", "text").unwrap_err(),
        "rule_set_invalid: $.format: rule_set_format_mismatch");
    assert_eq!(validate_resource("ruleset", &valid.0, "ipcidr", "mrs").await.unwrap_err(),
        "rule_set_invalid: $.behavior: rule_set_behavior_mismatch");
    assert_eq!(validate_resource("ruleset", &valid.0, "domain", "text").await.unwrap_err(),
        "rule_set_invalid: $.format: rule_set_format_mismatch");
    let broken = Fixture::new(&[0x28,0xb5,0x2f,0xfd,0]);
    let error = validation::preflight_rule_resource(&broken.0, "domain", "mrs").unwrap_err();
    assert!(error.starts_with("rule_set_invalid: $.payload.mrs: ")); assert!(error.contains("zstd"));
    assert_eq!(inspect_rule_set(&broken.0).unwrap_err(), error);
    assert_eq!(validate_resource("ruleset", &broken.0, "domain", "mrs").await.unwrap_err(), error);
    // A valid compressed container/header must still fail its real payload traversal/strict matcher.
    let malformed = Fixture::new(&upstream_mrs::encode_upstream_mrs(
        meow_config::offline_rules::mrs_parser::TYPE_DOMAIN, 1, &[1]));
    let invalid_domain = Fixture::new(&upstream_mrs::encode_upstream_mrs(
        meow_config::offline_rules::mrs_parser::TYPE_DOMAIN, 1, &upstream_mrs::encode_domain_set(&["."])));
    for invalid in [&malformed, &invalid_domain] {
        let error = validation::preflight_rule_resource(&invalid.0, "domain", "mrs").unwrap_err();
        assert!(error.starts_with("rule_set_invalid: $.payload.mrs: "), "{error}");
        assert_eq!(inspect_rule_set(&invalid.0).unwrap_err(), error);
        assert_eq!(validate_resource("ruleset", &invalid.0, "domain", "mrs").await.unwrap_err(), error);
    }
    let expansion = std::io::Read::take(std::io::repeat(0), validation::MAX_MRS_EXPANDED_BYTES as u64 + 1);
    let excessive = Fixture::new(&zstd::encode_all(expansion, 0).unwrap());
    let error = validation::preflight_rule_resource(&excessive.0, "domain", "mrs").unwrap_err();
    assert!(error.starts_with("rule_set_invalid: $.payload.mrs: ")); assert!(error.contains("decompressed_payload"));
    assert_eq!(inspect_rule_set(&excessive.0).unwrap_err(), error);
    assert_eq!(validate_resource("ruleset", &excessive.0, "domain", "mrs").await.unwrap_err(), error);
    let oversized_input = Fixture::new(&[]);
    fs::OpenOptions::new().write(true).open(&oversized_input.0).unwrap()
        .set_len(validation::MAX_INPUT_BYTES as u64 + 1).unwrap();
    assert_eq!(inspect_rule_set(&oversized_input.0).unwrap_err(), "resource input exceeds 67108864 bytes");
    assert_eq!(validate_resource("ruleset", &oversized_input.0, "domain", "mrs").await.unwrap_err(), "resource input exceeds 67108864 bytes");
    assert_eq!(validation::preflight_rule_resource(&oversized_input.0, "domain", "mrs").unwrap_err(), "resource input exceeds 67108864 bytes");
    let text = Fixture::new("example.test\n".repeat(validation::MAX_ENTRIES + 1).as_bytes());
    assert!(validation::preflight_rule_resource(&text.0, "domain", "text").unwrap_err().contains("rule-provider payload exceeds 250000 entries"));
    let mut legacy = meow_config::offline_rules::mrs_parser::write_ruleset_mrs(0, &["example.test"]).unwrap();
    legacy[6..10].copy_from_slice(&((validation::MAX_ENTRIES + 1) as u32).to_be_bytes());
    let legacy = Fixture::new(&legacy);
    assert_eq!(validation::preflight_rule_resource(&legacy.0, "domain", "mrs").unwrap_err(),
        "rule_set_invalid: $.payload.mrs: MRS! header declares 250001 entries, over 250000");
}

#[tokio::test]
async fn syntax_success_is_not_fake_complete_configuration_admission() {
    validation::validate_config_syntax("mode: rule\nrules: ['MATCH,DIRECT']").unwrap();
    assert!(validation::validate_config_syntax("port: [broken]").is_err());
    validation::validate_config("{}").await.unwrap();
    assert!(validation::validate_config("mode: invalid").await.is_err());
    assert!(validation::validate_config("unknown-root: true").await.unwrap_err().contains("$.unknown-root"));
}

#[tokio::test]
async fn yaml_domain_resource_uses_core_checked_build_and_preserves_old_json_shape() {
    let f = Fixture::new(b"payload: ['example.com', '+.example.org']\n");
    validate_resource("RULESET", &f.0, "domain", "YAML").await.unwrap();
    let result: serde_json::Value = serde_json::from_str(&inspect_rule_set(&f.0).unwrap()).unwrap();
    assert_eq!(result, serde_json::json!({"format":"yaml","behavior":"domain","count":2}));
    assert_eq!(dump_rule_set(&f.0, "domain", "yml").unwrap(), "example.com\n+.example.org\n");
    assert!(dump_rule_set(&f.0, "ipcidr", "yaml").unwrap_err().contains("behavior_mismatch"));
    assert!(dump_rule_set(&f.0, "domain", "text").unwrap_err().contains("format_mismatch"),
        "actual text-format error: {}", dump_rule_set(&f.0, "domain", "text").unwrap_err());
}

#[tokio::test]
async fn text_format_mismatch_is_shared_by_dump_validation_and_preflight_without_rewriting_invalid_text() {
    let yaml = Fixture::new(b"payload: ['example.com', '+.example.org']\n");
    let mismatch = "rule_set_invalid: $.format: rule_set_format_mismatch";
    assert_eq!(dump_rule_set(&yaml.0, "domain", "text").unwrap_err(), mismatch);
    assert_eq!(validate_resource("ruleset", &yaml.0, "domain", "text").await.unwrap_err(), mismatch);
    assert_eq!(validation::preflight_rule_resource(&yaml.0, "domain", "text").unwrap_err(), mismatch);
    let invalid = Fixture::new(b".\n");
    let original = inspect_rule_set(&invalid.0).unwrap_err();
    assert!(!original.contains("format_mismatch"));
    assert_eq!(dump_rule_set(&invalid.0, "domain", "text").unwrap_err(), original);
    assert_eq!(validation::preflight_rule_resource(&invalid.0, "domain", "text").unwrap_err(), original);
}

#[tokio::test]
async fn malformed_domain_that_old_checked_builder_silently_accepted_is_rejected() {
    let f = Fixture::new(b"payload: ['.']\n");
    assert!(validate_resource("ruleset", &f.0, "domain", "yaml").await.is_err());
}

#[tokio::test]
async fn ip_literals_and_classical_rules_are_actually_converted() {
    let ip = Fixture::new(b"192.0.2.1\n2001:db8::/32\n");
    validate_resource("rule-provider", &ip.0, "ipcidr", "text").await.unwrap();
    assert_eq!(dump_rule_set(&ip.0, "ipcidr", "text").unwrap(), "192.0.2.1/32\n2001:db8::/32\n");
    let classical = Fixture::new(b"payload: ['DOMAIN-KEYWORD,example', 'DST-PORT,443']\n");
    validate_resource("ruleset", &classical.0, "classical", "yaml").await.unwrap();
    let bad = Fixture::new(b"payload: ['NOT-A-RULE,example']\n");
    assert!(validate_resource("ruleset", &bad.0, "classical", "yaml").await.is_err());
}

#[tokio::test]
async fn real_mrs_codec_roundtrip_count_mismatch_and_corruption() {
    let bytes = meow_config::offline_rules::mrs_parser::write_ruleset_mrs(0, &["example.com", "+.example.org"]).unwrap();
    let f = Fixture::new(&bytes);
    validate_resource("ruleset", &f.0, "domain", "mrs").await.unwrap();
    let result: serde_json::Value = serde_json::from_str(&inspect_rule_set(&f.0).unwrap()).unwrap();
    assert_eq!(result["format"], "mrs"); assert_eq!(result["count"], 2);
    assert_eq!(dump_rule_set(&f.0, "domain", "mrs").unwrap(), "example.com\n+.example.org\n");
    assert!(validate_resource("ruleset", &f.0, "ipcidr", "mrs").await.is_err());
    let mut mismatch = bytes.clone(); mismatch[9] = 3;
    let f = Fixture::new(&mismatch);
    assert!(validate_resource("ruleset", &f.0, "domain", "mrs").await.is_err());
    let broken = Fixture::new(b"MRS!\x01\x00\x00\x00\x00\x01broken");
    assert!(validate_resource("ruleset", &broken.0, "domain", "mrs").await.is_err());
}

#[tokio::test]
async fn bounds_and_resource_identity_are_real_errors() {
    let oversized_line = Fixture::new(format!("{}\n", "a".repeat(2049)).as_bytes());
    assert!(inspect_rule_set(&oversized_line.0).is_err());
    let bad_mmdb = Fixture::new(b"not a database");
    assert!(validate_resource("mmdb", &bad_mmdb.0, "", "mmdb").await.is_err());
    assert!(validate_resource("mmdb", &bad_mmdb.0, "domain", "mmdb").await.is_err());
    assert!(validate_resource("unknown", &bad_mmdb.0, "", "").await.is_err());
    let path = std::env::temp_dir();
    assert!(inspect_rule_set(&path).is_err());
}

#[tokio::test]
async fn no_proxy_syntax_only_pass_or_external_plugin_process_launch() {
    let f = Fixture::new(b"proxies: [{name: external, type: ss, server: '127.0.0.1', port: 1, cipher: aes-128-gcm, password: secret, plugin: should-never-execute}]\n");
    let error = validate_resource("proxy-provider", &f.0, "", "yaml").await.unwrap_err();
    assert!(error.contains("offline_external_plugin_forbidden"));
}

#[tokio::test]
async fn geosite_uses_strict_actual_protobuf_loader_not_nonempty_bytes() {
    fn dat(kind: u8, value: &[u8]) -> Vec<u8> {
        let mut domain = vec![8, kind, 18, value.len() as u8]; domain.extend_from_slice(value);
        let mut site = vec![10, 2, b'c', b'n', 18, domain.len() as u8]; site.extend(domain);
        let mut list = vec![10, site.len() as u8]; list.extend(site); list
    }
    let valid = Fixture::new(&dat(3, b"example.com"));
    validate_resource("geosite", &valid.0, "", "dat").await.unwrap();
    let bad_regex = Fixture::new(&dat(1, b"["));
    assert!(validate_resource("geosite", &bad_regex.0, "", "dat").await.is_err());
    let empty = Fixture::new(&dat(3, b""));
    assert!(validate_resource("geosite", &empty.0, "", "dat").await.is_err());
    let garbage = Fixture::new(b"notprotobuf");
    assert!(validate_resource("geosite", &garbage.0, "", "dat").await.is_err());
}

#[tokio::test]
async fn simultaneous_conversion_never_changes_cli_offline_global() {
    let before = meow_config::is_offline_validate();
    let (good, bad) = tokio::join!(validation::validate_config("mode: rule"), validation::validate_config("mode: invalid"));
    good.unwrap(); assert!(bad.is_err());
    assert_eq!(before, meow_config::is_offline_validate());
}

#[tokio::test]
async fn valid_proxy_resource_runs_fixed_offline_factory_and_unknown_field_is_rejected() {
    let f = Fixture::new(b"proxies: [{name: direct-node, type: direct}]\n");
    validate_resource("proxy-provider", &f.0, "", "yaml").await.unwrap();
    let bad = Fixture::new(b"proxies: [{name: direct-node, type: direct, ignored-field: true}]\n");
    let error = validate_resource("proxy-provider", &bad.0, "", "yaml").await.unwrap_err();
    assert!(error.contains("$.proxies[0].ignored-field"));
}

fn gzip(bytes: &[u8]) -> Vec<u8> {
    use std::io::Write;
    let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    encoder.write_all(bytes).unwrap(); encoder.finish().unwrap()
}
#[test]
fn gzip_all_members_crc_truncation_and_expansion_bound() {
    let first = gzip(b"first"); let second = gzip(b"second");
    let mut multi = first.clone(); multi.extend(second);
    assert_eq!(validation::unpack_geodata(&multi, 64).unwrap(), b"firstsecond");
    assert_eq!(validation::unpack_geodata(b"raw", 64).unwrap(), b"raw");
    assert!(validation::unpack_geodata(b"raw", 2).is_err());
    assert!(validation::unpack_geodata(&gzip(&[0u8; 1024]), 32).unwrap_err().contains("expanded limit"));
    let mut crc = first.clone(); let n = crc.len(); crc[n - 8] ^= 1;
    assert!(validation::unpack_geodata(&crc, 64).is_err());
    let mut size = first.clone(); let n = size.len(); size[n - 4] ^= 1;
    assert!(validation::unpack_geodata(&size, 64).is_err());
    assert!(validation::unpack_geodata(&first[..first.len()-1], 64).is_err());
    let mut trailing = first.clone(); trailing.extend_from_slice(b"not-a-gzip-member");
    assert!(validation::unpack_geodata(&trailing, 64).is_err());
    assert!(validation::unpack_geodata(&[0x1f,0x8b,0x00],64).is_err());
}
#[test]
fn zstd_geodata_streaming_matches_the_fixed_core_bounded_decoder() {
    use meow_config::offline_rules::mrs_parser::decompress_payload_bounded;
    let frame = |bytes: &[u8]| zstd::encode_all(std::io::Cursor::new(bytes.to_vec()), 0).unwrap();
    let mut two = frame(b"first"); two.extend(frame(b"second"));
    let exact = frame(&[7u8; 4096]);
    let truncated = { let whole = frame(&[9u8; 70_000]); whole[..whole.len() - 3].to_vec() };
    let mut trailing = frame(b"payload"); trailing.extend_from_slice(b"not-a-zstd-frame");
    let large = frame(&vec![3u8; 300 * 1024]);
    let cases = [(two, 64usize), (exact.clone(), 4096), (exact, 4095), (truncated, 1 << 20), (trailing, 64),
        (vec![0x28, 0xb5, 0x2f, 0xfd, 0], 64), (large.clone(), 300 * 1024), (large, 300 * 1024 - 1)];
    for (input, limit) in cases {
        let expected = decompress_payload_bounded(&input, limit as u64).map_err(|error| error.to_string());
        assert_eq!(validation::unpack_geodata(&input, limit), expected, "limit {limit}");
    }
    assert_eq!(validation::unpack_geodata(&frame(b"firstsecond"), 64).unwrap(), b"firstsecond");
}
fn ss_fields(plugin: &str, opts: &str, extra: &str) -> Result<String,String> {
    validation::normalize_config_fields(&format!("proxies: [{{name: node, type: ss, server: example.com, port: 443, cipher: aes-128-gcm, password: secret, plugin: '{plugin}', plugin-opts: {opts}{extra}}}]"))
}
#[test]
fn ss_plugin_opts_normalize_aliases_without_dropping_or_replacing_policies() {
    let simple = ss_fields("simple-obfs", "{obfs: tls, obfs-host: Case.Example}", "").unwrap();
    assert!(simple.contains("host=Case.Example;mode=tls"));
    let jls = ss_fields("jls", "{host: example.com, username: user, password: pass, skip_cert_verify: false, alpn: [h2, http/1.1]}", "").unwrap();
    assert!(jls.contains("skip-cert-verify=false")); assert!(jls.contains("alpn=h2,http/1.1"));
    let gost = ss_fields("gost-plugin", "{mode: websocket, tls: true, ech-opts: {enable: true, config: AQID}, headers: {X-Test: value}}", "").unwrap();
    assert!(gost.contains("ech-opts.enable=true")); assert!(gost.contains("header=X-Test:value"));
    let ech = ss_fields("ech-tls-tunnel", "{mode: client, sni: example.com, path: /, ech_config: AQID, fast_open: false}", "").unwrap();
    assert!(ech.contains("ech-config=AQID")); assert!(ech.contains("fast-open=false"));
    let raw = ss_fields("v2ray-plugin", "'mode=websocket;tls;host=Case.Example;header=X-Test:value'", "").unwrap();
    assert!(raw.contains("tls=true")); assert!(raw.contains("host=Case.Example"));
}
#[test]
fn ss_plugin_unknown_conflicting_ignored_and_unsafe_options_have_exact_paths() {
    let cases = [
        ("obfs", "{obfs: tls, mode: http}", "", "$.proxies[0].plugin-opts.mode"),
        ("v2ray-plugin", "{mode: quic}", "", "$.proxies[0].plugin-opts.mode"),
        ("restls", "{client-id: abc}", "", "$.proxies[0].plugin-opts.client-id"),
        ("gost-plugin", "{tls: typo}", "", "$.proxies[0].plugin-opts.tls"),
        ("v2ray-plugin", "{skip-cert-verify: true}", "", "$.proxies[0].plugin-opts.skip-cert-verify"),
        ("v2ray-plugin", "{headers: {X: 'value;tls'}}", "", "$.proxies[0].plugin-opts.headers.X"),
        ("jls", "{alpn: ['h2;host=other']}", "", "$.proxies[0].plugin-opts.alpn[0]"),
        ("gost-plugin", "{ech-enable: true, ech-opts: {enable: false}, tls: true}", "", "$.proxies[0].plugin-opts.ech-opts.enable"),
        ("restls", "{version-hint: tls14}", "", "$.proxies[0].plugin-opts.version-hint"),
        ("shadow-tls", "{version: 4}", "", "$.proxies[0].plugin-opts.version"),
        ("kcptun", "{smuxver: 2}", "", "$.proxies[0].plugin-opts.smuxver"),
        ("kcptun", "{crypt: made-up}", "", "$.proxies[0].plugin-opts.crypt"),
        ("kcptun", "{mode: fast, interval: 10}", "", "$.proxies[0].plugin-opts.interval"),
        ("kcptun", "{datashard: 200, parityshard: 200}", "", "$.proxies[0].plugin-opts.parityshard"),
        ("shadow-tls", "{host: example.com}", ", udp: true", "$.proxies[0].udp"),
        ("restls", "{host: example.com}", ", client-fingerprint: chrome", "$.proxies[0].client-fingerprint"),
    ];
    for (plugin, opts, extra, path) in cases { let error=ss_fields(plugin,opts,extra).unwrap_err(); assert!(error.starts_with(path), "{error} should point to {path}"); }
}


#[cfg(unix)]
#[test]
fn unix_fifo_child_probe() {
    let Some(path) = std::env::var_os("AEROVE_CONFIG_FIFO_CHILD_PATH") else { return; };
    let path = PathBuf::from(path);
    assert_eq!(inspect_rule_set(&path).unwrap_err(), "resource candidate must be a regular file");
    assert_eq!(dump_rule_set(&path, "domain", "yaml").unwrap_err(), "resource candidate must be a regular file");
}

#[cfg(unix)]
#[test]
fn unix_fifo_and_symlink_to_fifo_cannot_block_resource_open() {
    use std::{ffi::CString, os::unix::{ffi::OsStrExt, fs::symlink}, process::Command, time::{Duration, Instant}};
    let fifo = Fixture::new(b"placeholder");
    fs::remove_file(&fifo.0).unwrap();
    let name = CString::new(fifo.0.as_os_str().as_bytes()).unwrap();
    // SAFETY: CString lives across mkfifo, is NUL terminated, and names this fixture only.
    assert_eq!(unsafe { libc::mkfifo(name.as_ptr(), 0o600) }, 0);
    let link = Fixture::new(b"placeholder");
    fs::remove_file(&link.0).unwrap();
    symlink(&fifo.0, &link.0).unwrap();
    // Each probe is isolated: the old blocking open fails this regression within a bounded deadline.
    // No writer is ever opened, and a timed-out child is killed and reaped before failing the test.
    for path in [&fifo.0, &link.0] {
        let mut child = Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "unix_fifo_child_probe", "--nocapture"])
            .env("AEROVE_CONFIG_FIFO_CHILD_PATH", path)
            .spawn().unwrap();
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            if let Some(status) = child.try_wait().unwrap() { assert!(status.success(), "FIFO child failed: {status}"); break; }
            if Instant::now() >= deadline {
                let _ = child.kill();
                let _ = child.wait();
                panic!("resource open blocked on FIFO without a writer: {path:?}");
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    }
}

#[cfg(unix)]
#[test]
fn unix_regular_symlink_directory_and_empty_path_keep_resource_semantics() {
    use std::os::unix::fs::symlink;
    let regular = Fixture::new(b"payload: [example.com]\n");
    let link = Fixture::new(b"placeholder");
    fs::remove_file(&link.0).unwrap();
    symlink(&regular.0, &link.0).unwrap();
    assert_eq!(inspect_rule_set(&regular.0).unwrap(), inspect_rule_set(&link.0).unwrap());
    assert_eq!(dump_rule_set(&link.0, "domain", "yaml").unwrap(), "example.com\n");
    assert_eq!(inspect_rule_set(&std::env::temp_dir()).unwrap_err(), "resource candidate must be a regular file");
    assert!(inspect_rule_set(std::path::Path::new("")).unwrap_err().starts_with("open resource candidate:"));
}


#[tokio::test]
async fn rule_decode_errors_keep_typed_context_and_actual_decoder_reason() {
    let zstd = vec![0x28, 0xb5, 0x2f, 0xfd, 0];
    let zstd_reason = meow_config::offline_rules::mrs_parser::decompress_payload_bounded(
        &zstd, validation::MAX_EXPANDED_BYTES as u64).unwrap_err().to_string();
    let utf8 = vec![0xff, 0xfe];
    let utf8_reason = std::str::from_utf8(&utf8).unwrap_err().to_string();
    for (bytes, format, field, reason) in [
        (zstd, "mrs", "$.payload.mrs", zstd_reason),
        (utf8, "text", "$.payload.utf8", utf8_reason),
    ] {
        let fixture = Fixture::new(&bytes);
        let expected = format!("rule_set_invalid: {field}: {reason}");
        assert_eq!(inspect_rule_set(&fixture.0).unwrap_err(), expected);
        assert_eq!(dump_rule_set(&fixture.0, "domain", format).unwrap_err(), expected);
        assert_eq!(validate_resource("ruleset", &fixture.0, "domain", format).await.unwrap_err(), expected);
        assert_eq!(fs::read(&fixture.0).unwrap(), bytes, "failed decode changed its input");
    }
}

#[tokio::test]
async fn rule_content_errors_and_identity_mismatches_keep_typed_specific_reasons() {
    let mixed = Fixture::new(b"example.com\n192.0.2.0/24\n");
    let expected = "rule_set_invalid: $.payload: rule_set_mixed: domains and IP ranges";
    assert_eq!(inspect_rule_set(&mixed.0).unwrap_err(), expected);
    assert_eq!(validate_resource("ruleset", &mixed.0, "domain", "text").await.unwrap_err(), expected);
    let good = Fixture::new(b"example.com\n");
    assert_eq!(dump_rule_set(&good.0, "ipcidr", "text").unwrap_err(),
        "rule_set_invalid: $.behavior: rule_set_behavior_mismatch");
    assert_eq!(dump_rule_set(&good.0, "domain", "mrs").unwrap_err(),
        "rule_set_invalid: $.format: rule_set_format_mismatch");
    assert_eq!(validate_resource("ruleset", &good.0, "domain", "mrs").await.unwrap_err(),
        "rule_set_invalid: $.format: rule_set_format_mismatch");
    assert_eq!(validate_resource("ruleset", &good.0, "bad-behavior", "text").await.unwrap_err(),
        "rule_set_invalid: $.behavior: unsupported rule-provider behavior: bad-behavior");
    let malformed = Fixture::new(b"payload: [\n");
    let reason = meow_config::parse_raw_yaml("payload: [\n").unwrap_err().to_string();
    assert_eq!(validate_resource("ruleset", &malformed.0, "domain", "yaml").await.unwrap_err(),
        format!("rule_set_invalid: $.payload.yaml: {reason}"));
    let bad = Fixture::new(b"payload: ['.']\n");
    let reason = meow_config::offline_rules::build_rule_set_checked(
        meow_config::offline_rules::RuleSetBehavior::Domain, &[".".to_owned()], &Default::default()).err().unwrap().to_string();
    assert_eq!(inspect_rule_set(&bad.0).unwrap_err(), format!("rule_set_invalid: $.payload: {reason}"));
    assert_eq!(dump_rule_set(&good.0, "domain", "text").unwrap(), "example.com\n");
    assert_eq!(fs::read(&good.0).unwrap(), b"example.com\n");
    let excessive = Fixture::new(format!("{}\n", "a".repeat(2049)).as_bytes());
    assert_eq!(inspect_rule_set(&excessive.0).unwrap_err(),
        "rule_set_invalid: $.payload: rule-provider entry 0 exceeds 2048 bytes");
    // File identity failures are resource errors, not rewritten as bad rules.
    assert_eq!(validate_resource("ruleset", &std::env::temp_dir(), "domain", "yaml").await.unwrap_err(),
        "resource candidate must be a regular file");
}

#[test]
fn official_mrs_above_64m_expanded_reaches_the_actual_strict_matcher() {
    use std::io::{Cursor, Read};
    let body = upstream_mrs::encode_domain_set(&["example.test"]);
    // Reserved header bytes push the expansion past 64 MiB while staying under the Core's 256 MiB bound.
    let mut header = meow_config::offline_rules::mrs_parser::UPSTREAM_MRS_MAGIC.to_vec();
    header.push(0); header.extend_from_slice(&1i64.to_be_bytes());
    let reserved = 65 * 1024 * 1024;
    header.extend_from_slice(&(reserved as i64).to_be_bytes());
    let source = Cursor::new(header).chain(std::io::repeat(0).take(reserved)).chain(Cursor::new(body));
    let file = Fixture::new(&zstd::encode_all(source, 0).unwrap());
    let summary: serde_json::Value = serde_json::from_str(&inspect_rule_set(&file.0).unwrap()).unwrap();
    assert_eq!(summary["count"], 1);
    assert_eq!(dump_rule_set(&file.0, "domain", "mrs").unwrap(), "example.test\n");
    let bad_body = upstream_mrs::encode_domain_set(&["."]);
    let bad = Fixture::new(&upstream_mrs::encode_upstream_mrs(0, 1, &bad_body));
    assert!(inspect_rule_set(&bad.0).unwrap_err().contains("$.payload.mrs"));
}
#[test]
fn official_mrs_compressed_size_and_cancel_are_independent_of_expanded_budget() {
    let file = Fixture::new(&upstream_mrs::encode_upstream_mrs(0, 1, &upstream_mrs::encode_domain_set(&["example.test"])));
    let cancel = tokio_util::sync::CancellationToken::new(); cancel.cancel();
    assert!(validation::inspect_rule_set_controlled(&file.0, cancel.clone()).unwrap_err().contains("Cancelled"));
    assert!(validation::dump_rule_set_controlled(&file.0, "domain", "mrs", cancel.clone()).unwrap_err().contains("Cancelled"));
    assert!(validation::preflight_rule_resource_controlled(&file.0, "domain", "mrs", cancel).unwrap_err().contains("Cancelled"));
    fs::OpenOptions::new().write(true).open(&file.0).unwrap().set_len(validation::MAX_INPUT_BYTES as u64 + 1).unwrap();
    assert_eq!(inspect_rule_set(&file.0).unwrap_err(), "resource input exceeds 67108864 bytes");
}
#[tokio::test]
async fn official_mrs_compressed_input_between_32m_and_64m_is_admitted_and_the_64m_input_bound_holds() {
    use std::io::{Cursor, Read};
    // Pseudo-random reserved header bytes stay incompressible, so the zstd file lands between 32 and 64 MiB.
    let reserved = 40 * 1024 * 1024;
    let mut state = 0x9e37_79b9_7f4a_7c15u64;
    let mut noise = Vec::with_capacity(reserved);
    while noise.len() < reserved {
        state ^= state << 13; state ^= state >> 7; state ^= state << 17;
        noise.extend_from_slice(&state.to_le_bytes());
    }
    let mut header = meow_config::offline_rules::mrs_parser::UPSTREAM_MRS_MAGIC.to_vec();
    header.push(0); header.extend_from_slice(&1i64.to_be_bytes());
    header.extend_from_slice(&(reserved as i64).to_be_bytes());
    let body = upstream_mrs::encode_domain_set(&["example.test"]);
    let source = Cursor::new(header).chain(Cursor::new(noise)).chain(Cursor::new(body));
    let file = Fixture::new(&zstd::encode_all(source, 1).unwrap());
    let size = fs::metadata(&file.0).unwrap().len();
    assert!(size > 32 * 1024 * 1024 && size <= validation::MAX_INPUT_BYTES as u64, "{size}");
    let summary: serde_json::Value = serde_json::from_str(&inspect_rule_set(&file.0).unwrap()).unwrap();
    assert_eq!(summary, serde_json::json!({"format":"mrs","behavior":"domain","count":1}));
    validate_resource("ruleset", &file.0, "domain", "mrs").await.unwrap();
    let receipt: serde_json::Value = serde_json::from_str(&validation::preflight_rule_resource(&file.0, "domain", "mrs").unwrap()).unwrap();
    assert_eq!(receipt["state"], "validated");
    assert_eq!(dump_rule_set(&file.0, "domain", "mrs").unwrap(), "example.test\n");
    fs::OpenOptions::new().write(true).open(&file.0).unwrap().set_len(validation::MAX_INPUT_BYTES as u64 + 1).unwrap();
    let errors = [inspect_rule_set(&file.0).unwrap_err(), dump_rule_set(&file.0, "domain", "mrs").unwrap_err(),
        validate_resource("ruleset", &file.0, "domain", "mrs").await.unwrap_err(),
        validation::preflight_rule_resource(&file.0, "domain", "mrs").unwrap_err()];
    for error in errors { assert_eq!(error, "resource input exceeds 67108864 bytes"); }
}

#[test]
fn upstream_source_count_can_differ_from_the_accepted_matcher_count() {
    // Mihomo records successful source Insert calls, before deduplication/merging.
    let file = Fixture::new(&upstream_mrs::encode_upstream_mrs(0, 7, &upstream_mrs::encode_domain_set(&["example.test"])));
    let summary: serde_json::Value = serde_json::from_str(&inspect_rule_set(&file.0).unwrap()).unwrap();
    assert_eq!(summary["count"], 1);
    assert_eq!(dump_rule_set(&file.0, "domain", "mrs").unwrap(), "example.test\n");
}
#[tokio::test]
async fn text_rule_input_up_to_the_64m_text_budget_is_admitted_and_the_input_bound_holds() {
    use std::io::Write as _;
    let mut bytes = b"example.test\n#".to_vec();
    bytes.resize(validation::MAX_RULE_TEXT_BYTES - 1, b'x');
    bytes.push(b'\n');
    let file = Fixture::new(&bytes);
    drop(bytes);
    let summary: serde_json::Value = serde_json::from_str(&inspect_rule_set(&file.0).unwrap()).unwrap();
    assert_eq!(summary, serde_json::json!({"format":"text","behavior":"domain","count":1}));
    validate_resource("ruleset", &file.0, "domain", "text").await.unwrap();
    let receipt: serde_json::Value = serde_json::from_str(&validation::preflight_rule_resource(&file.0, "domain", "text").unwrap()).unwrap();
    assert_eq!(receipt["state"], "validated"); assert_eq!(receipt["count"], 1);
    assert_eq!(dump_rule_set(&file.0, "domain", "text").unwrap(), "example.test\n");
    fs::OpenOptions::new().append(true).open(&file.0).unwrap().write_all(b"\n").unwrap();
    assert_eq!(fs::metadata(&file.0).unwrap().len(), validation::MAX_RULE_TEXT_BYTES as u64 + 1);
    let errors = [inspect_rule_set(&file.0).unwrap_err(), validate_resource("ruleset", &file.0, "domain", "text").await.unwrap_err(),
        validation::preflight_rule_resource(&file.0, "domain", "text").unwrap_err(), dump_rule_set(&file.0, "domain", "text").unwrap_err()];
    for error in errors { assert_eq!(error, "resource input exceeds 67108864 bytes"); }
}
fn official_domain_mrs(entries: &[String]) -> Fixture {
    let borrowed: Vec<&str> = entries.iter().map(String::as_str).collect();
    Fixture::new(&upstream_mrs::encode_upstream_mrs(0, entries.len(), &upstream_mrs::encode_domain_set(&borrowed)))
}
#[test]
fn official_mrs_text_between_8m_and_64m_stays_previewable() {
    let label = "b".repeat(80);
    let entries: Vec<String> = (0..100_000).map(|index| format!("rule{index:06}.{label}.test")).collect();
    let file = official_domain_mrs(&entries);
    let dump = dump_rule_set(&file.0, "domain", "mrs").unwrap();
    assert!(dump.len() > validation::MAX_TEXT_BYTES && dump.len() <= validation::MAX_DUMP_TEXT_BYTES, "{}", dump.len());
    assert_eq!(dump.lines().count(), 100_000);
}
#[test]
fn official_mrs_text_over_budget_is_an_error_but_binary_inspection_still_succeeds() {
    let label = "a".repeat(60);
    let entries: Vec<String> = (0..345_000).map(|index| format!("rule{index:06}.{label}.{label}.{label}.test")).collect();
    assert!(entries.iter().map(|entry| entry.len() + 1).sum::<usize>() > validation::MAX_DUMP_TEXT_BYTES);
    let file = official_domain_mrs(&entries);
    let summary: serde_json::Value = serde_json::from_str(&inspect_rule_set(&file.0).unwrap()).unwrap();
    assert_eq!(summary["count"], 345_000);
    assert_eq!(dump_rule_set(&file.0, "domain", "mrs").unwrap_err(), "rule_set_invalid: $.payload: rule-set text exceeds 67108864 bytes");
}

#[tokio::test]
async fn controlled_native_entrypoints_reject_bad_utf8_inverted_range_and_negative_source_count() {
    // Core03's invalid-only domain fixture: one leaf carrying an invalid UTF-8 byte.
    let mut bad_utf8 = vec![1];
    bad_utf8.extend_from_slice(&1i64.to_be_bytes()); bad_utf8.extend_from_slice(&2u64.to_be_bytes());
    bad_utf8.extend_from_slice(&1i64.to_be_bytes()); bad_utf8.extend_from_slice(&6u64.to_be_bytes());
    bad_utf8.extend_from_slice(&1i64.to_be_bytes()); bad_utf8.push(0xff);
    let mut bad_range = vec![1]; bad_range.extend_from_slice(&1i64.to_be_bytes());
    bad_range.extend_from_slice(&std::net::Ipv6Addr::from(2u128).octets());
    bad_range.extend_from_slice(&std::net::Ipv6Addr::from(1u128).octets());
    for (behavior, tag, body, reason) in [("domain", 0, bad_utf8, "UTF-8"), ("ipcidr", 1, bad_range, "range")] {
        let file = Fixture::new(&upstream_mrs::encode_upstream_mrs(tag, 1, &body));
        let error = inspect_rule_set(&file.0).unwrap_err(); assert!(error.contains(reason), "{error}");
        assert_eq!(dump_rule_set(&file.0, behavior, "mrs").unwrap_err(), error);
        assert_eq!(validation::preflight_rule_resource(&file.0, behavior, "mrs").unwrap_err(), error);
        assert_eq!(validate_resource("ruleset", &file.0, behavior, "mrs").await.unwrap_err(), error);
    }
    let bytes = upstream_mrs::encode_upstream_mrs(0, 1, &upstream_mrs::encode_domain_set(&["example.test"]));
    let mut expanded = zstd::decode_all(std::io::Cursor::new(bytes)).unwrap();
    expanded[5..13].copy_from_slice(&(-1i64).to_be_bytes());
    let file = Fixture::new(&zstd::encode_all(std::io::Cursor::new(expanded), 0).unwrap());
    assert!(inspect_rule_set(&file.0).unwrap_err().contains("count"));
}
