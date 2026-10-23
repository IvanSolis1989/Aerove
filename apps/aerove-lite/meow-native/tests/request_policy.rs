#![cfg(unix)]
use aerove_meow_core::{network_context::NetworkContext, providers::{self, ControlPlane, OwnedLoadScope}, request_policies::RequestPolicies, resource_roots::ResourceRoots};
use meow_common::request_policy::{self, RequestPolicy};
use std::{io, sync::Arc, time::Duration};
use tokio::{io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt}, net::{TcpListener, TcpStream}, sync::{mpsc, oneshot}, time::timeout};
use tokio_util::sync::CancellationToken;
#[path = "support/resource_roots.rs"] mod root_fixture;

fn policies(agent: &str, check: bool, epoch: u64) -> (Arc<RequestPolicies>, NetworkContext, CancellationToken) {
    let cancel = CancellationToken::new();
    let plane = ControlPlane::new(epoch, cancel.clone()).unwrap();
    let policy = RequestPolicies::new(RequestPolicy::new(agent.into(), check).unwrap(), plane, cancel.clone()).unwrap();
    let context = NetworkContext::platform(cancel.clone()).with_request_policy(policy.clone());
    (policy, context, cancel)
}
fn change(policy: &RequestPolicies, epoch: &str, agent: &str, check: bool) -> serde_json::Value {
    serde_json::from_str(&serde_json::to_string(&policy.configure_json(&serde_json::json!({
        "schemaVersion":1,"requestId":"37337b38-f2bf-4d64-b906-df47a122b625","expectedCoreEpoch":epoch,
        "http":{"globalUserAgent":agent,"checkCertificate":check}}).to_string()).unwrap()).unwrap()).unwrap()
}
async fn header<S: AsyncRead + Unpin>(stream: &mut S) -> io::Result<String> {
    timeout(Duration::from_secs(5), async {
        let mut bytes = Vec::new(); let mut byte = [0];
        while !bytes.ends_with(b"\r\n\r\n") {
            if bytes.len() == 16384 { return Err(io::Error::other("fixture header bound")); }
            if stream.read(&mut byte).await? == 0 { return Err(io::Error::from(io::ErrorKind::UnexpectedEof)); }
            bytes.push(byte[0]);
        }
        String::from_utf8(bytes).map_err(io::Error::other)
    }).await.unwrap()
}
async fn reply<S: AsyncWrite + Unpin>(stream: &mut S, body: &[u8]) {
    stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len()).as_bytes()).await.unwrap();
    stream.write_all(body).await.unwrap(); stream.shutdown().await.unwrap();
}
fn ua(header: &str) -> &str {
    header.lines().find_map(|line| line.split_once(':').filter(|(key, _)| key.eq_ignore_ascii_case("user-agent")).map(|(_,value)| value.trim())).unwrap()
}
async fn fetch(context: &NetworkContext, url: &str, headers: &[(String,String)]) -> Result<Vec<u8>, String> {
    context.scope(meow_config::internal_http::fetch(url, None, headers)).await.map_err(|error| error.to_string())
}

#[tokio::test]
async fn http_hot_policy_preserves_redirect_snapshot_and_explicit_headers() {
    let (policy, context, cancel) = policies("Aerove/old", true, 70);
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
    let (headers_tx, mut headers_rx) = mpsc::channel(4); let (continue_tx, continue_rx) = oneshot::channel();
    let server = tokio::spawn(async move {
        let mut resume = Some(continue_rx);
        for index in 0..4 {
            let (mut stream, _) = listener.accept().await.unwrap();
            headers_tx.send(header(&mut stream).await.unwrap()).await.unwrap();
            if index == 0 {
                resume.take().unwrap().await.unwrap();
                stream.write_all(format!("HTTP/1.1 302 Found\r\nLocation: http://{address}/redirected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
                stream.shutdown().await.unwrap();
            } else { reply(&mut stream,b"ok").await; }
        }
    });
    let child_context = context.clone();
    let first = tokio::spawn(async move { fetch(&child_context,&format!("http://{address}/start"),&[]).await });
    assert_eq!(ua(&headers_rx.recv().await.unwrap()),"Aerove/old");
    let receipt = change(&policy,"70","Aerove/new",true); assert_ne!(receipt["coreEpoch"],"70");
    continue_tx.send(()).unwrap(); assert_eq!(first.await.unwrap().unwrap(),b"ok");
    assert_eq!(ua(&headers_rx.recv().await.unwrap()),"Aerove/old");
    assert_eq!(fetch(&context,&format!("http://{address}/fresh"),&[]).await.unwrap(),b"ok");
    assert_eq!(ua(&headers_rx.recv().await.unwrap()),"Aerove/new");
    assert_eq!(fetch(&context,&format!("http://{address}/explicit"),&[("User-Agent".into(),"Provider/explicit".into())]).await.unwrap(),b"ok");
    assert_eq!(ua(&headers_rx.recv().await.unwrap()),"Provider/explicit");
    server.await.unwrap(); cancel.cancel();
    assert!(fetch(&context,&format!("http://{address}/cancelled"),&[]).await.is_err());
    assert!(request_policy::current().unwrap().is_none());
}

fn tls_acceptor() -> boring::ssl::SslAcceptor {
    use boring::{asn1::Asn1Time, bn::BigNum, hash::MessageDigest, pkey::PKey, rsa::Rsa, x509::{X509, X509NameBuilder}};
    let key = PKey::from_rsa(Rsa::generate(2048).unwrap()).unwrap();
    let mut name = X509NameBuilder::new().unwrap(); name.append_entry_by_text("CN","localhost").unwrap(); let name = name.build();
    let mut cert = X509::builder().unwrap(); cert.set_version(2).unwrap();
    let serial = BigNum::from_u32(1).unwrap().to_asn1_integer().unwrap(); cert.set_serial_number(&serial).unwrap();
    cert.set_subject_name(&name).unwrap(); cert.set_issuer_name(&name).unwrap(); cert.set_pubkey(&key).unwrap();
    let before = Asn1Time::days_from_now(0).unwrap(); let after = Asn1Time::days_from_now(1).unwrap();
    cert.set_not_before(&before).unwrap(); cert.set_not_after(&after).unwrap(); cert.sign(&key,MessageDigest::sha256()).unwrap();
    let mut acceptor = boring::ssl::SslAcceptor::mozilla_intermediate_v5(boring::ssl::SslMethod::tls()).unwrap();
    acceptor.set_certificate(&cert.build()).unwrap(); acceptor.set_private_key(&key).unwrap(); acceptor.build()
}
#[tokio::test]
async fn tls_download_policy_is_hot_and_does_not_disable_outbound_tls_verification() {
    let (policy, context, cancel) = policies("Aerove/tls",true,80);
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
    let acceptor = tls_acceptor();
    let server = tokio::spawn(async move {
        let mut completed = 0;
        for _ in 0..4 {
            let (tcp,_) = listener.accept().await.unwrap();
            if let Ok(mut stream) = tokio_boring::accept(&acceptor,tcp).await {
                assert_eq!(ua(&header(&mut stream).await.unwrap()),"Aerove/tls"); reply(&mut stream,b"verified-policy").await; completed += 1;
            }
        }
        completed
    });
    let url = format!("https://{address}/download");
    assert!(fetch(&context,&url,&[]).await.is_err());
    let receipt = change(&policy,"80","Aerove/tls",false);
    assert_eq!(fetch(&context,&url,&[]).await.unwrap(),b"verified-policy");
    let tcp = TcpStream::connect(address).await.unwrap();
    let tls = meow_transport::tls::TlsLayer::new(&meow_transport::tls::TlsConfig::new("127.0.0.1")).unwrap();
    assert!(context.scope(tls.connect_typed(tcp)).await.is_err());
    change(&policy,receipt["coreEpoch"].as_str().unwrap(),"Aerove/tls",true);
    assert!(fetch(&context,&url,&[]).await.is_err());
    assert_eq!(server.await.unwrap(),1); cancel.cancel();
}

#[test]
fn policy_cas_strict_inputs_unchanged_and_cancel_fence() {
    let (policy,_,cancel) = policies("Aerove/test",true,90);
    let unchanged = change(&policy,"90","Aerove/test",true); assert_eq!(unchanged["coreEpoch"],"90");
    let changed = change(&policy,"90","Aerove/changed",false); let epoch = changed["coreEpoch"].as_str().unwrap();
    assert_ne!(epoch,"90");
    for (expected,agent) in [("90","stale"),("01","bad"),("18446744073709551616","bad"),(epoch,"bad\r\nInjected: yes")] {
        let request = serde_json::json!({"schemaVersion":1,"requestId":"37337b38-f2bf-4d64-b906-df47a122b625","expectedCoreEpoch":expected,"http":{"globalUserAgent":agent,"checkCertificate":false}});
        assert!(policy.configure_json(&request.to_string()).is_err());
    }
    assert_eq!(policy.status().unwrap().http.global_user_agent,"Aerove/changed");
    assert!(RequestPolicies::from_host("global-ua: true").is_err());
    assert!(RequestPolicies::from_host("check-certificate: 'false'").is_err());
    cancel.cancel(); assert!(policy.configure_json(&serde_json::json!({"schemaVersion":1,"requestId":"37337b38-f2bf-4d64-b906-df47a122b625","expectedCoreEpoch":epoch,"http":{"globalUserAgent":"cancelled","checkCertificate":true}}).to_string()).is_err());
    assert_eq!(policy.status().unwrap().http.global_user_agent,"Aerove/changed");
}

#[tokio::test]
async fn factory_owned_children_capture_policy_and_restore_blocking_context() {
    use meow_config::owned_load::LoadContext;
    let roots = root_fixture::Roots::new();
    let (policy, context, cancel) = policies("Aerove/factory",true,100);
    let context = context.with_resource_roots(Arc::new(ResourceRoots::validate(Some(roots.read.to_str().unwrap()),Some(roots.cache.to_str().unwrap())).unwrap()));
    let physical = aerove_meow_core::physical_dns::PhysicalResolver::from_servers(vec!["127.0.0.1:53".parse().unwrap()],context,false).unwrap();
    let context = physical.network();
    let scope = OwnedLoadScope::with_context(Arc::new(context.clone()));
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut stream,_) = listener.accept().await.unwrap(); let received = header(&mut stream).await.unwrap();
        reply(&mut stream,b"proxies:\n  - {name: direct-feed, type: direct}\n").await; received
    });
    let yaml = format!("dns: {{enable: false}}\nproxy-providers:\n  feed: {{type: http, url: 'http://{address}/feed', interval: 0}}\nproxy-groups:\n  - {{name: PICK, type: select, use: [feed]}}\nrules: ['MATCH,PICK']\n");
    let config = context.scope(providers::load(&yaml,&scope,&cancel)).await.unwrap();
    assert!(config.proxy_providers.contains_key("feed"));
    assert_eq!(ua(&server.await.unwrap()),"Aerove/factory");
    let blocking_context = context.clone();
    tokio::task::spawn_blocking(move || {
        blocking_context.run_blocking(Box::new(|| assert_eq!(request_policy::current().unwrap().unwrap().global_user_agent,"Aerove/factory")));
        assert!(request_policy::current().unwrap().is_none());
    }).await.unwrap();
    scope.cancel(); scope.close().await.unwrap(); physical.retire().await.unwrap(); cancel.cancel();
    assert!(physical.dns_owner().cleanup_confirmed());
    assert_eq!(policy.status().unwrap().http.global_user_agent,"Aerove/factory");
}

#[tokio::test]
async fn startup_host_http_defaults_are_installed_before_owned_factory() {
    let roots = root_fixture::Roots::new();
    let private = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = private.local_addr().unwrap();
    let mixed = TcpListener::bind("127.0.0.1:0").await.unwrap(); let mixed_address = mixed.local_addr().unwrap(); drop(private); drop(mixed);
    let cancel = CancellationToken::new();
    let context = NetworkContext::platform(cancel).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
    let plan = serde_json::json!({"schemaVersion":1,"runtimeMode":"proxy","initiallyParked":true,
        "engineYaml":"strict: true\ndns: {enable: false}\nrules: ['MATCH,DIRECT']\ntun: {enable: false, auto-route: false}\n",
        "privateControl":{"address":address.to_string(),"secret":"meow-policy-startup-private-00000001"},
        "inbounds":{"configYaml":format!("mixed-port: {}",mixed_address.port())},"tun":null,
        "physicalNetwork":{"handle":null,"dns":{"defaultInterface":"fixture","interfaces":{"fixture":["127.0.0.1"]}}},
        "hostPolicyYaml":"global-ua: Aerove/startup\ncheck-certificate: false\n",
        "profileId":null,"revision":null,"resourceGeneration":null,
        "resourceReadRoot":roots.read.to_str().unwrap(),"cacheRoot":roots.cache.to_str().unwrap()});
    let session = aerove_meow_core::Registry::start_owned(plan.to_string(),None,context,None).unwrap();
    timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();
    let owner = session.owned_runtime().unwrap(); let status = owner.policies().status().unwrap();
    assert_eq!(status.http.global_user_agent,"Aerove/startup"); assert!(!status.http.check_certificate);
    assert!(!owner.listener_status().listening); assert!(owner.listener_status().active_ports.is_empty());
    session.stop().await.unwrap(); assert!(owner.cleanup_confirmed());
    TcpListener::bind(address).await.unwrap();
}
