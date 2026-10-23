use serde::Serialize;
use std::io;

static VERIFIED: tokio::sync::OnceCell<Capabilities> = tokio::sync::OnceCell::const_new();

const COMMIT: &str = "727ddcd06f592e2f12c5222c4de6cce328df808c";
const PROVENANCE: &str = include_str!("../.upstream/meow-rs/AEROVE_SOURCE_PROVENANCE.json");
const FEATURES: &[(&str, bool)] = &[
    ("ss", cfg!(feature = "ss")), ("trojan", cfg!(feature = "trojan")), ("vless", cfg!(feature = "vless")),
    ("vless-vision", cfg!(feature = "vless-vision")), ("vless-encryption", cfg!(feature = "vless-encryption")),
    ("ech-tls-tunnel", cfg!(feature = "ech-tls-tunnel")), ("vmess", cfg!(feature = "vmess")), ("snell", cfg!(feature = "snell")),
    ("hysteria2", cfg!(feature = "hysteria2")), ("mux", cfg!(feature = "mux")), ("kcptun", cfg!(feature = "kcptun")),
    ("anytls", cfg!(feature = "anytls")), ("trusttunnel", cfg!(feature = "trusttunnel")), ("trusttunnel-h3", cfg!(feature = "trusttunnel-h3")),
];
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceIdentity {
    repository: String, commit: String, tag: String, raw_file_manifest_sha256: String,
    adapted_file_manifest_sha256: String, overlay_sha256: String,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Capabilities {
    schema_version: u8, source: SourceIdentity, compiled_features: Vec<&'static str>,
    verified_features: Vec<&'static str>, built_in_protocols: Vec<&'static str>,
    verification: &'static str, wire_verified: bool,
}
fn invalid() -> io::Error { io::Error::other("capabilitySourceIdentityInvalid") }
fn source_identity() -> io::Result<SourceIdentity> {
    let value: serde_json::Value = serde_json::from_str(PROVENANCE).map_err(|_| invalid())?;
    let text = |key: &str| value.get(key).and_then(serde_json::Value::as_str).map(str::to_owned).ok_or_else(invalid);
    let hash = |key: &str| -> io::Result<String> { let value = text(key)?;
        if value.len() != 64 || !value.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) { return Err(invalid()); } Ok(value) };
    let source = SourceIdentity { repository: text("repository")?, commit: text("commit")?, tag: text("tag")?,
        raw_file_manifest_sha256: hash("rawFileManifestSha256")?, adapted_file_manifest_sha256: hash("fileManifestSha256")?, overlay_sha256: hash("overlaySha256")? };
    if source.commit != COMMIT || source.repository != "https://github.com/meow-rs/meow-rs" || source.tag != "v0.26.1"
        || source.raw_file_manifest_sha256 != "a1948157edb77700f6f9152f3467907fb6a050c66cd153edfe98295bbf56e550"
        || value.get("archiveSha256").and_then(serde_json::Value::as_str) != Some("4273e027c5db3f7c052b255730f29165908772bf417ec89d9ab54478f81dcd9a")
        || value.get("licenseSha256").and_then(serde_json::Value::as_str) != Some("b7a39018faacfd98597da373045fbacf2c3f134fd755e553894a6a9adad38db5")
        || value.get("upstreamLockSha256").and_then(serde_json::Value::as_str) != Some("1a2daa4bca6871fdde99aeed6ec3e20dc2e9a9d357957ba02ae6ae6135bd7c4e")
        || value.get("archiveHashBootstrapped").and_then(serde_json::Value::as_bool) != Some(false) { return Err(invalid()); }
    Ok(source)
}
pub fn compiled_features() -> Vec<&'static str> { FEATURES.iter().filter(|(_, enabled)| *enabled).map(|(name,_)| *name).collect() }
const SS: &str = "type: ss\nserver: 127.0.0.1\nport: 443\ncipher: aes-256-gcm\npassword: fixture";
const VLESS: &str = "type: vless\nserver: 127.0.0.1\nport: 443\nuuid: b831381d-6324-4d53-ad4f-8cda48b30811";
fn node(feature: &str) -> Option<String> {
    Some(match feature {
        "ss" => SS.into(),
        "trojan" => "type: trojan\nserver: 127.0.0.1\nport: 443\npassword: fixture".into(),
        "vless" => VLESS.into(),
        "vless-vision" => format!("{VLESS}\ntls: true\nflow: xtls-rprx-vision"),
        "vless-encryption" => format!("{VLESS}\ntls: true\nflow: xtls-rprx-vision\nencryption: mlkem768x25519plus.native.0rtt.DA7B2WRj7X2zGFwMelbIbcaoUrpLjzoPpmydYW8NvQW"),
        "vmess" => "type: vmess\nserver: 127.0.0.1\nport: 443\nuuid: b831381d-6324-4d53-ad4f-8cda48b30811\nalterId: 0\ncipher: auto".into(),
        "snell" => "type: snell\nserver: 127.0.0.1\nport: 443\npsk: fixture\nversion: 4".into(),
        "anytls" => "type: anytls\nserver: 127.0.0.1\nport: 443\npassword: fixture".into(),
        "hysteria2" => "type: hysteria2\nserver: 127.0.0.1\nport: 443\npassword: fixture".into(),
        "trusttunnel" | "trusttunnel-h3" => format!("type: trusttunnel\nserver: 127.0.0.1\nport: 443\nusername: fixture\npassword: fixture\nquic: {}",feature == "trusttunnel-h3"),
        "mux" => format!("{SS}\nsmux: {{enabled: true}}"),
        "kcptun" => format!("{SS}\nplugin: kcptun\nplugin-opts: {{crypt: salsa20, mode: fast3, conn: 2, nocomp: true, datashard: 6, parityshard: 3}}"),
        "ech-tls-tunnel" => format!("{SS}\nplugin: ech-tls-tunnel\nplugin-opts:\n  mode: client\n  sni: tunnel.example.com\n  path: /fixture\n  ech_config: AEX+DQBBRwAgACBs1mZ0bOlNXgMxOAxJgD/h+pSyM6F7nGhmGyR0R0p3agAEAAEAAQAOcHVibGljLmV4YW1wbGUAAA=="),
        "direct" => "type: direct".into(),
        "http" => "type: http\nserver: 127.0.0.1\nport: 443".into(),
        "socks5" => "type: socks5\nserver: 127.0.0.1\nport: 443".into(),
        _ => return None,
    })
}
fn configuration(node: &str) -> String {
    format!("dns: {{enable: false}}\nproxies:\n  - name: capability-node\n{}\nrules: ['MATCH,capability-node']\n",node.lines().map(|line|format!("    {line}")).collect::<Vec<_>>().join("\n"))
}
pub async fn verify_feature(feature: &str) -> io::Result<()> {
    if !FEATURES.iter().any(|(name,enabled)| *name == feature && *enabled) && !["direct","http","socks5"].contains(&feature) {
        return Err(io::Error::new(io::ErrorKind::Unsupported,"capabilityFeatureNotCompiled"));
    }
    let node = node(feature).ok_or_else(invalid)?;
    meow_config::validate_offline_config(&configuration(&node)).await
        .map_err(|_| io::Error::other(format!("capabilityFactoryRejected:{feature}")))
}
async fn build() -> io::Result<Capabilities> {
    let source = source_identity()?;
    let compiled_features = compiled_features(); let mut verified_features = Vec::new();
    for feature in &compiled_features { verify_feature(feature).await?; verified_features.push(*feature); }
    let built_in_protocols = vec!["direct","http","socks5"];
    for protocol in &built_in_protocols { verify_feature(protocol).await?; }
    Ok(Capabilities { schema_version:1, source, compiled_features, verified_features, built_in_protocols,
        verification:"fixedOfflineFactory", wire_verified:false })
}
pub async fn inspect() -> io::Result<&'static Capabilities> { VERIFIED.get_or_try_init(build).await }
pub async fn json() -> io::Result<String> { serde_json::to_string(&inspect().await?).map_err(|_| invalid()) }

#[cfg(test)]
mod tests {
    use super::*;
    macro_rules! protocol {
        ($test:ident, $name:literal) => {
            #[tokio::test]
            async fn $test() {
                let enabled = compiled_features().contains(&$name);
                let sample = configuration(&node($name).unwrap());
                let actual = meow_config::validate_offline_config(&sample).await;
                assert_eq!(actual.is_ok(),enabled,"feature {} actual converter result: {actual:?}",$name);
                assert_eq!(verify_feature($name).await.is_ok(),enabled);
                if enabled {
                    assert!(sample.contains("port: 443"));
                    assert!(meow_config::validate_offline_config(&sample.replace("port: 443","port: 0")).await.is_err());
                }
            }
        };
    }
    protocol!(ss_fixed_factory,"ss");
    protocol!(trojan_fixed_factory,"trojan");
    protocol!(vless_fixed_factory,"vless");
    protocol!(vless_vision_fixed_factory,"vless-vision");
    protocol!(vless_encryption_fixed_factory,"vless-encryption");
    protocol!(ech_plugin_fixed_factory,"ech-tls-tunnel");
    protocol!(vmess_fixed_factory,"vmess");
    protocol!(snell_fixed_factory,"snell");
    protocol!(hysteria2_fixed_factory,"hysteria2");
    protocol!(mux_fixed_factory,"mux");
    protocol!(kcptun_fixed_factory,"kcptun");
    protocol!(anytls_fixed_factory,"anytls");
    protocol!(trusttunnel_h2_fixed_factory,"trusttunnel");
    protocol!(trusttunnel_h3_fixed_factory,"trusttunnel-h3");
    #[tokio::test]
    async fn built_in_protocols_use_actual_offline_factory_without_starting_registry() {
        for protocol in ["direct","http","socks5"] { verify_feature(protocol).await.unwrap(); }
    }
    #[tokio::test]
    async fn unknown_feature_and_fixed_core_absent_protocols_fail_explicitly() {
        for name in ["not-a-feature","external-ui-download","full","ssh","wireguard","tailscale","shadowquic","tuic"] {
            assert!(verify_feature(name).await.is_err());
        }
        for name in ["ssh","wireguard","tailscale","shadowquic","tuic"] {
            let sample = configuration(&format!("type: {name}\nserver: 127.0.0.1\nport: 443"));
            assert!(meow_config::validate_offline_config(&sample).await.is_err());
        }
    }
    #[tokio::test]
    async fn attestation_is_compiled_source_bound_and_verified_once() {
        let first = inspect().await.unwrap(); let second = inspect().await.unwrap();
        assert!(std::ptr::eq(first,second));
        assert_eq!(first.source.commit,COMMIT); assert_eq!(first.compiled_features,compiled_features());
        assert_eq!(first.compiled_features,first.verified_features);
        assert_eq!(first.built_in_protocols,["direct","http","socks5"]);
        assert!(!first.wire_verified); assert_eq!(first.verification,"fixedOfflineFactory");
        let value: serde_json::Value = serde_json::from_str(&json().await.unwrap()).unwrap();
        assert_eq!(value["schemaVersion"],1); assert_eq!(value["source"]["commit"],COMMIT);
        assert_eq!(value["compiledFeatures"],value["verifiedFeatures"]);
    }
}
