#![cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
use aerove_meow_core::{Registry, network_context::NetworkContext};
use meow_common::socket_protect::SocketProtector;
use std::{io, net::SocketAddr, os::fd::RawFd, sync::{Arc, atomic::{AtomicUsize, Ordering}}, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}, time::timeout};
use tokio_util::sync::CancellationToken;
use futures::StreamExt;
#[path = "support/resource_roots.rs"] mod root_fixture;
const SECRET: &str = "host-policy-real-api-private-secret-0001";
struct Protect(Arc<AtomicUsize>);
impl SocketProtector for Protect {
    fn protect(&self, fd: RawFd) -> io::Result<()> {
        assert!(unsafe { libc::fcntl(fd,libc::F_GETFD) }>=0); self.0.fetch_add(1,Ordering::Relaxed); Ok(())
    }
}
async fn head(stream: &mut TcpStream) -> Vec<u8> {
    let mut bytes = Vec::new(); let mut byte = [0];
    while !bytes.ends_with(b"\r\n\r\n") { assert!(bytes.len()<8192); stream.read_exact(&mut byte).await.unwrap(); bytes.push(byte[0]); }
    bytes
}
async fn get(address: SocketAddr, path: &str) -> Vec<u8> {
    let mut stream = TcpStream::connect(address).await.unwrap();
    stream.write_all(format!("GET {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {SECRET}\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut bytes = Vec::new(); timeout(Duration::from_secs(5),stream.read_to_end(&mut bytes)).await.unwrap().unwrap();
    assert!(bytes.starts_with(b"HTTP/1.1 200"),"{}",String::from_utf8_lossy(&bytes)); bytes
}
fn command(epoch: &str, operation: &str, value: serde_json::Value) -> String {
    serde_json::json!({"schemaVersion":1,"requestId":"3ad953cb-6401-42ef-bd5a-69576b9b3fa4","expectedCoreEpoch":epoch,"operation":operation,"value":value}).to_string()
}

#[tokio::test]
async fn startup_hot_policy_private_api_real_same_connection_heads_and_cancelled_owner_rejects() {
    let roots = root_fixture::Roots::new();
    let private = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = private.local_addr().unwrap();
    let mixed = TcpListener::bind("127.0.0.1:0").await.unwrap(); let mixed_address = mixed.local_addr().unwrap(); drop(private); drop(mixed);
    let target = TcpListener::bind("127.0.0.1:0").await.unwrap(); let target_address = target.local_addr().unwrap();
    let target_task = tokio::spawn(async move {
        for requests in [1,2] {
            let (mut stream,_) = target.accept().await.unwrap();
            for _ in 0..requests {
                assert!(head(&mut stream).await.starts_with(b"HEAD /observed-policy HTTP/1.1\r\n"));
                stream.write_all(b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n").await.unwrap();
            }
            assert_eq!(stream.read_u8().await.unwrap_err().kind(),io::ErrorKind::UnexpectedEof);
        }
    });
    let calls = Arc::new(AtomicUsize::new(0));
    let network = NetworkContext::protected(Arc::new(Protect(calls.clone())),None,CancellationToken::new()).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
    let plan = serde_json::json!({"schemaVersion":1,"runtimeMode":"proxy","initiallyParked":true,
        "engineYaml":"strict: true\ndns: {enable: false}\nrules: ['MATCH,DIRECT']\ntun: {enable: false, auto-route: false}\n",
        "privateControl":{"address":address.to_string(),"secret":SECRET},"inbounds":{"configYaml":format!("mixed-port: {}",mixed_address.port())},"tun":null,
        "physicalNetwork":{"handle":null,"dns":{"defaultInterface":"fixture","interfaces":{"fixture":["127.0.0.1"]}}},
        "hostPolicyYaml":"global-ua: Aerove/policy\ncheck-certificate: true\nunified-delay: false\ntcp-concurrent: false\n",
        "profileId":null,"revision":null,"resourceGeneration":null,"resourceReadRoot":roots.read.to_str().unwrap(),"cacheRoot":roots.cache.to_str().unwrap()});
    let session = Registry::start_owned(plan.to_string(),None,network,None).unwrap();
    let ready = timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap(); assert!(ready.api_ready && !ready.user_listeners_ready);
    let owner = session.owned_runtime().unwrap(); let before = owner.listener_status(); assert!(!before.unified_delay && !before.tcp_concurrent);
    let path = format!("/proxies/DIRECT/delay?url=http%3A%2F%2F127.0.0.1%3A{}%2Fobserved-policy&timeout=2000",target_address.port());
    get(address,&path).await;
    let stale = command(&before.core_epoch,"probeDial",serde_json::json!({"unifiedDelay":true,"tcpConcurrent":true}));
    let receipt: serde_json::Value = serde_json::from_str(&owner.host_policy_command(stale.clone()).await.unwrap()).unwrap();
    assert_ne!(receipt["coreEpoch"],before.core_epoch); assert_eq!(receipt["value"],serde_json::json!({"unifiedDelay":true,"tcpConcurrent":true}));
    assert!(owner.host_policy_command(stale).await.is_err());
    let actual = owner.listener_status(); assert!(actual.unified_delay && actual.tcp_concurrent); assert_eq!(actual.core_epoch,receipt["coreEpoch"].as_str().unwrap());
    let snapshot: serde_json::Value = serde_json::from_str(&owner.host_policy_command(command(&actual.core_epoch,"snapshot",serde_json::json!({}))).await.unwrap()).unwrap();
    assert_eq!(snapshot["value"]["http"]["globalUserAgent"],"Aerove/policy"); assert_eq!(snapshot["value"]["probeDial"],receipt["value"]);
    get(address,&path).await; timeout(Duration::from_secs(5),target_task).await.unwrap().unwrap(); assert_eq!(calls.load(Ordering::Relaxed),2);
    timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap(); assert!(owner.cleanup_confirmed());
    assert!(owner.host_policy_command(command(&actual.core_epoch,"snapshot",serde_json::json!({}))).await.is_err());
    assert!(TcpListener::bind(address).await.is_ok()); assert!(TcpListener::bind(mixed_address).await.is_ok()); assert!(TcpListener::bind(target_address).await.is_ok());
}

#[tokio::test]
async fn native_periodic_nested_fallback_actual_health_event_route_and_stop_join() {
    let roots = root_fixture::Roots::new();
    let private = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = private.local_addr().unwrap();
    let mixed = TcpListener::bind("127.0.0.1:0").await.unwrap(); let mixed_address = mixed.local_addr().unwrap(); drop(private); drop(mixed);
    let target = TcpListener::bind("127.0.0.1:0").await.unwrap(); let target_address = target.local_addr().unwrap();
    let (request_seen, seen) = tokio::sync::oneshot::channel(); let (release, proceed) = tokio::sync::oneshot::channel();
    let target_task = tokio::spawn(async move {
        let (mut stream,_) = target.accept().await.unwrap(); assert!(head(&mut stream).await.starts_with(b"HEAD /native-health "));
        request_seen.send(()).unwrap(); proceed.await.unwrap();
        stream.write_all(b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await.unwrap();
        assert_eq!(stream.read_u8().await.unwrap_err().kind(),io::ErrorKind::UnexpectedEof);
    });
    let bad = TcpListener::bind("127.0.0.1:0").await.unwrap(); let bad_address = bad.local_addr().unwrap();
    let (release_bad, proceed_bad) = tokio::sync::oneshot::channel();
    let bad_task = tokio::spawn(async move {
        let (mut stream,_) = bad.accept().await.unwrap(); assert!(head(&mut stream).await.starts_with(b"CONNECT "));
        proceed_bad.await.unwrap(); stream.write_all(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await.unwrap();
    });
    let calls = Arc::new(AtomicUsize::new(0));
    let network = NetworkContext::protected(Arc::new(Protect(calls.clone())),None,CancellationToken::new()).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
    let yaml = format!("strict: true\nmode: rule\nproxies:\n - {{name: BAD, type: http, server: 127.0.0.1, port: {}}}\nproxy-groups:\n - {{name: AUTO, type: fallback, proxies: [BAD, DIRECT], url: 'http://{target_address}/native-health', interval: 3600, lazy: false}}\n - {{name: OUTER, type: select, proxies: [AUTO, REJECT]}}\nrules: ['MATCH,OUTER']\ndns: {{enable: false}}\ntun: {{enable: false, auto-route: false}}\n",bad_address.port());
    let plan = serde_json::json!({"schemaVersion":1,"runtimeMode":"proxy","initiallyParked":true,"engineYaml":yaml,
        "privateControl":{"address":address.to_string(),"secret":SECRET},"inbounds":{"configYaml":format!("mixed-port: {}",mixed_address.port())},"tun":null,
        "physicalNetwork":{"handle":null,"dns":{"defaultInterface":"fixture","interfaces":{"fixture":["127.0.0.1"]}}},
        "hostPolicyYaml":"unified-delay: false\ntcp-concurrent: true\n","profileId":null,"revision":null,"resourceGeneration":null,
        "resourceReadRoot":roots.read.to_str().unwrap(),"cacheRoot":roots.cache.to_str().unwrap()});
    let session = Registry::start_owned(plan.to_string(),None,network,None).unwrap();
    timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap(); let owner = session.owned_runtime().unwrap(); let initial = owner.route_stamp();
    timeout(Duration::from_secs(5),seen).await.unwrap().unwrap();
    let (mut socket,_) = tokio_tungstenite::connect_async(format!("ws://{address}/aerove/telemetry?token={SECRET}")).await.unwrap();
    release.send(()).unwrap(); release_bad.send(()).unwrap();
    timeout(Duration::from_secs(5),async {
        loop {
            let frame = socket.next().await.unwrap().unwrap(); let event: serde_json::Value = serde_json::from_str(frame.to_text().unwrap()).unwrap();
            if event["type"] == "groupsChanged" && owner.route_stamp().1 > initial.1 { break; }
        }
    }).await.unwrap();
    let route = get(address,"/aerove/route").await; let start = route.windows(4).position(|bytes| bytes==b"\r\n\r\n").unwrap()+4;
    let route: serde_json::Value = serde_json::from_slice(&route[start..]).unwrap(); assert_eq!(route["picks"]["AUTO"],"DIRECT"); assert_eq!(owner.route_stamp().0,initial.0);
    timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap(); assert!(owner.cleanup_confirmed());
    timeout(Duration::from_secs(5),target_task).await.unwrap().unwrap(); timeout(Duration::from_secs(5),bad_task).await.unwrap().unwrap();
    assert!(TcpListener::bind(bad_address).await.is_ok()); assert!(calls.load(Ordering::Relaxed)>=2); assert!(TcpListener::bind(address).await.is_ok()); assert!(TcpListener::bind(mixed_address).await.is_ok()); assert!(TcpListener::bind(target_address).await.is_ok());
}

#[tokio::test]
async fn startup_full_policy_snapshot_hot_commands_real_user_endpoint_and_failed_network_preserve_session() {
    async fn user_get(path: &str, secret: Option<&str>) -> Vec<u8> {
        let mut stream = TcpStream::connect("127.0.0.1:9090").await.unwrap();
        let authorization = secret.map(|value|format!("Authorization: Bearer {value}\r\n")).unwrap_or_default();
        stream.write_all(format!("GET {path} HTTP/1.1\r\nHost: localhost\r\n{authorization}Connection: close\r\n\r\n").as_bytes()).await.unwrap();
        let mut result = Vec::new(); timeout(Duration::from_secs(5),stream.read_to_end(&mut result)).await.unwrap().unwrap(); result
    }
    async fn apply(owner: &aerove_meow_core::owned_runtime::OwnedRuntime, epoch: &str, op: &str, value: serde_json::Value) -> serde_json::Value {
        serde_json::from_str(&owner.host_policy_command(command(epoch,op,value)).await.unwrap()).unwrap()
    }
    let roots = root_fixture::Roots::new();
    let private = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = private.local_addr().unwrap(); drop(private);
    let mixed = TcpListener::bind("127.0.0.1:0").await.unwrap(); let mixed_address = mixed.local_addr().unwrap(); drop(mixed);
    let callback = Arc::new(Protect(Arc::new(AtomicUsize::new(0)))); let weak_callback = Arc::downgrade(&callback);
    let network = NetworkContext::protected(callback.clone(),None,CancellationToken::new()).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
    drop(callback);
    let plan = serde_json::json!({"schemaVersion":1,"runtimeMode":"proxy","initiallyParked":true,
        "engineYaml":"strict: true\ndns: {enable: false}\nrules: ['MATCH,DIRECT']\ntun: {enable: false, auto-route: false}\n",
        "privateControl":{"address":address.to_string(),"secret":SECRET},"inbounds":{"configYaml":format!("mixed-port: {}",mixed_address.port())},"tun":null,
        "physicalNetwork":{"handle":null,"dns":{"defaultInterface":"fixture","interfaces":{"fixture":["127.0.0.1"]}}},
        "hostPolicyYaml":"global-ua: Aerove/full-policy\ncheck-certificate: true\nunified-delay: true\ntcp-concurrent: true\nfind-process-mode: strict\nonly-statistics-proxy: true\nuser-controller: {enabled: false, secret: ''}\ncors-allow-origins: []\n",
        "profileId":null,"revision":null,"resourceGeneration":null,"resourceReadRoot":roots.read.to_str().unwrap(),"cacheRoot":roots.cache.to_str().unwrap()});
    let session = Registry::start_owned(plan.to_string(),None,network,None).unwrap();
    timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();
    let owner = session.owned_runtime().unwrap(); let mut epoch = owner.listener_status().core_epoch;
    let snapshot = apply(&owner,&epoch,"snapshot",serde_json::json!({})).await;
    assert_eq!(snapshot["value"].as_object().unwrap().len(),7);
    assert_eq!(snapshot["value"]["http"],serde_json::json!({"globalUserAgent":"Aerove/full-policy","checkCertificate":true}));
    assert_eq!(snapshot["value"]["findProcessMode"],"strict"); assert_eq!(snapshot["value"]["statistics"]["onlyStatisticsProxy"],true);
    assert_eq!(snapshot["value"]["userController"]["enabled"],false); assert_eq!(snapshot["value"]["privateCorsOrigins"],serde_json::json!([]));
    let statistics = apply(&owner,&epoch,"statistics",serde_json::json!({"onlyStatisticsProxy":false})).await;
    assert_ne!(statistics["coreEpoch"],epoch); epoch=statistics["coreEpoch"].as_str().unwrap().to_owned();
    assert!(!owner.listener_status().only_statistics_proxy); assert!(!owner.statistics().unwrap().status().unwrap().only_statistics_proxy);
    let process = apply(&owner,&epoch,"process",serde_json::json!({"findProcessMode":"off"})).await;
    epoch=process["coreEpoch"].as_str().unwrap().to_owned(); assert_eq!(owner.listener_status().find_process_mode,"off");
    let invalidated = apply(&owner,&epoch,"invalidateIdentity",serde_json::json!({})).await;
    epoch=invalidated["coreEpoch"].as_str().unwrap().to_owned(); assert_eq!(invalidated["value"]["invalidated"],true);
    let enabled = apply(&owner,&epoch,"userController",serde_json::json!({"enabled":true,"secret":"full-policy-user-secret"})).await;
    epoch=enabled["coreEpoch"].as_str().unwrap().to_owned(); assert_eq!(enabled["value"]["authenticated"],true);
    assert!(user_get("/proxies",Some("full-policy-user-secret")).await.starts_with(b"HTTP/1.1 200"));
    assert!(user_get("/proxies",None).await.starts_with(b"HTTP/1.1 401"));
    assert!(user_get("/aerove/route",Some("full-policy-user-secret")).await.starts_with(b"HTTP/1.1 404"));
    get(address,"/aerove/route").await;
    #[cfg(not(target_os="android"))]
    {
        let rejected = owner.host_policy_command(command(&epoch,"network",serde_json::json!({"intent":"1","physicalNetwork":{"handle":"1","dns":{"defaultInterface":"fixture","interfaces":{"fixture":["127.0.0.1"]}}}}))).await.unwrap_err();
        assert!(rejected.to_string().starts_with("physicalNetworkUnavailable"));
        assert_eq!(owner.listener_status().core_epoch,epoch); assert_eq!(owner.status().state,"Running");
        let snapshot = apply(&owner,&epoch,"snapshot",serde_json::json!({})).await;
        assert_eq!(snapshot["value"]["physicalNetwork"]["intent"],"1");
        assert!(snapshot["value"]["physicalNetwork"]["handle"].is_null());
        get(address,"/aerove/route").await;
    }
    let disabled = apply(&owner,&epoch,"userController",serde_json::json!({"enabled":false,"secret":""})).await;
    assert_eq!(disabled["value"]["enabled"],false); assert!(TcpListener::bind("127.0.0.1:9090").await.is_ok());
    timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap(); assert!(owner.cleanup_confirmed());
    assert!(weak_callback.upgrade().is_none(), "a retained terminal Session must not retain the callback after confirmed join");
    assert!(TcpListener::bind(address).await.is_ok()); assert!(TcpListener::bind(mixed_address).await.is_ok());
}
