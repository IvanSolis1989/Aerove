use std::{fs, path::PathBuf, sync::atomic::{AtomicU64, Ordering}};
use aerove_meow_core::config_validation::{self as validation, dump_rule_set, inspect_rule_set, validate_resource};
#[path="support/resource_roots.rs"] mod preflight_roots;
#[path="support/upstream_mrs.rs"] mod upstream_mrs;

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
    assert_eq!(inspect_rule_set(&path).unwrap_err(), "rule_set_invalid: $.payload.mrs: rule_set_dump_count_limit_exceeded");
    assert_eq!(dump_rule_set(&path, "domain", "mrs").unwrap_err(), "rule_set_invalid: $.payload.mrs: rule_set_dump_count_limit_exceeded");
}

#[test]
fn upstream_mrs_preflight_rejects_identity_corruption_and_64m_expansion_without_relaxing_other_caps() {
    let valid = Fixture::new(&controlled_upstream_mrs());
    assert_eq!(validation::preflight_rule_resource(&valid.0, "ipcidr", "mrs").unwrap_err(),
        "rule_set_invalid: $.behavior: rule_set_behavior_mismatch");
    assert_eq!(validation::preflight_rule_resource(&valid.0, "domain", "text").unwrap_err(),
        "rule_set_invalid: $.format: rule_set_format_mismatch");
    let broken = Fixture::new(&[0x28,0xb5,0x2f,0xfd,0]);
    let error = validation::preflight_rule_resource(&broken.0, "domain", "mrs").unwrap_err();
    assert!(error.starts_with("rule_set_invalid: $.payload.mrs: ")); assert!(error.contains("zstd"));
    let expansion = std::io::Read::take(std::io::repeat(0), validation::MAX_EXPANDED_BYTES as u64 + 1);
    let excessive = Fixture::new(&zstd::encode_all(expansion, 0).unwrap());
    let error = validation::preflight_rule_resource(&excessive.0, "domain", "mrs").unwrap_err();
    assert!(error.starts_with("rule_set_invalid: $.payload.mrs: ")); assert!(error.contains("decompressed_payload"));
    let text = Fixture::new("example.test\n".repeat(validation::MAX_ENTRIES + 1).as_bytes());
    assert!(validation::preflight_rule_resource(&text.0, "domain", "text").unwrap_err().contains("count/line limit exceeded"));
    let mut legacy = meow_config::offline_rules::mrs_parser::write_ruleset_mrs(0, &["example.test"]).unwrap();
    legacy[6..10].copy_from_slice(&((validation::MAX_ENTRIES + 1) as u32).to_be_bytes());
    let legacy = Fixture::new(&legacy);
    assert_eq!(validation::preflight_rule_resource(&legacy.0, "domain", "mrs").unwrap_err(),
        "rule_set_invalid: $.payload.mrs: rule_set_dump_count_limit_exceeded");
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
    assert!(dump_rule_set(&f.0, "domain", "text").unwrap_err().contains("format_mismatch"));
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
        "rule_set_invalid: $.payload: rule-provider payload count/line limit exceeded");
    // File identity failures are resource errors, not rewritten as bad rules.
    assert_eq!(validate_resource("ruleset", &std::env::temp_dir(), "domain", "yaml").await.unwrap_err(),
        "resource candidate must be a regular file");
}
