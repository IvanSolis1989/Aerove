#![cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
use aerove_meow_core::{Registry, Phase, network_context::NetworkContext};
use meow_common::socket_protect::SocketProtector;
use std::{io, net::SocketAddr, os::fd::RawFd, sync::{Arc,atomic::{AtomicBool,AtomicUsize,Ordering}},time::Duration};
use tokio::{io::{AsyncReadExt,AsyncWriteExt},net::{TcpListener,TcpStream},sync::{Mutex,Notify},task::JoinSet,time::timeout};
use tokio_util::sync::CancellationToken;
use futures::StreamExt;
#[path="support/resource_roots.rs"] mod root_fixture;
static LOCK:Mutex<()>=Mutex::const_new(());
const SECRET:&str="runtime-admission-private-secret-000001";
struct Protect(Arc<AtomicUsize>);
impl SocketProtector for Protect { fn protect(&self,fd:RawFd)->io::Result<()> {
    assert!(unsafe{libc::fcntl(fd,libc::F_GETFD)}>=0);self.0.fetch_add(1,Ordering::Relaxed);Ok(())
} }
async fn ports()->[SocketAddr;2] {
    let a=TcpListener::bind("127.0.0.1:0").await.unwrap();let b=TcpListener::bind("127.0.0.1:0").await.unwrap();
    [a.local_addr().unwrap(),b.local_addr().unwrap()]
}
fn plan(yaml:String,ports:[SocketAddr;2],roots:&root_fixture::Roots)->String {
    serde_json::json!({"schemaVersion":1,"runtimeMode":"proxy","initiallyParked":false,"engineYaml":yaml,
        "privateControl":{"address":ports[0].to_string(),"secret":SECRET},"inbounds":{"configYaml":format!("mixed-port: {}\nbind-address: 127.0.0.1",ports[1].port())},"tun":null,
        "physicalNetwork":{"handle":null,"dns":{"defaultInterface":"fixture","interfaces":{"fixture":["127.0.0.1"]}}},
        "hostPolicyYaml":"unified-delay: false\ntcp-concurrent: false","profileId":null,"revision":null,"resourceGeneration":null,
        "resourceReadRoot":roots.read.to_str().unwrap(),"cacheRoot":roots.cache.to_str().unwrap()}).to_string()
}
async fn headers(stream:&mut TcpStream)->io::Result<Vec<u8>> {
    let mut result=Vec::new();while !result.ends_with(b"\r\n\r\n") {result.push(stream.read_u8().await?);assert!(result.len()<8192);}Ok(result)
}
async fn get(address:SocketAddr,path:&str)->serde_json::Value {
    let mut stream=TcpStream::connect(address).await.unwrap();stream.write_all(format!("GET {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {SECRET}\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut response=Vec::new();timeout(Duration::from_secs(5),stream.read_to_end(&mut response)).await.unwrap().unwrap();
    assert!(response.starts_with(b"HTTP/1.1 200"),"{}",String::from_utf8_lossy(&response));
    let start=response.windows(4).position(|bytes|bytes==b"\r\n\r\n").unwrap()+4;serde_json::from_slice(&response[start..]).unwrap()
}
fn samples()->Vec<(&'static str,bool,&'static str,&'static str)> {
    vec![
        ("http",true,"type: http\nserver: 127.0.0.1\nport: 443","Http"),
        ("socks5",true,"type: socks5\nserver: 127.0.0.1\nport: 443\nudp: true","Socks5"),
        ("direct",true,"type: direct","Direct"),
        ("ss",cfg!(feature="ss"),"type: ss\nserver: 127.0.0.1\nport: 443\ncipher: aes-256-gcm\npassword: fixture","Shadowsocks"),
        ("trojan",cfg!(feature="trojan"),"type: trojan\nserver: 127.0.0.1\nport: 443\npassword: fixture","Trojan"),
        ("vmess",cfg!(feature="vmess"),"type: vmess\nserver: 127.0.0.1\nport: 443\nuuid: b831381d-6324-4d53-ad4f-8cda48b30811\nalterId: 0\ncipher: auto","Vmess"),
        ("vless",cfg!(feature="vless"),"type: vless\nserver: 127.0.0.1\nport: 443\nuuid: b831381d-6324-4d53-ad4f-8cda48b30811","Vless"),
        ("vless-vision",cfg!(feature="vless-vision"),"type: vless\nserver: 127.0.0.1\nport: 443\nuuid: b831381d-6324-4d53-ad4f-8cda48b30811\ntls: true\nflow: xtls-rprx-vision","Vless"),
        ("vless-encryption",cfg!(feature="vless-encryption"),"type: vless\nserver: 127.0.0.1\nport: 443\nuuid: b831381d-6324-4d53-ad4f-8cda48b30811\ntls: true\nflow: xtls-rprx-vision\nencryption: mlkem768x25519plus.native.0rtt.DA7B2WRj7X2zGFwMelbIbcaoUrpLjzoPpmydYW8NvQW","Vless"),
        ("snell",cfg!(feature="snell"),"type: snell\nserver: 127.0.0.1\nport: 443\npsk: fixture\nversion: 4","Snell"),
        ("anytls",cfg!(feature="anytls"),"type: anytls\nserver: 127.0.0.1\nport: 443\npassword: fixture","AnyTLS"),
        ("hysteria2",cfg!(feature="hysteria2"),"type: hysteria2\nserver: 127.0.0.1\nport: 443\npassword: fixture","Hysteria2"),
        ("trusttunnel",cfg!(feature="trusttunnel"),"type: trusttunnel\nserver: 127.0.0.1\nport: 443\nusername: fixture\npassword: fixture\nquic: false","TrustTunnel"),
        ("trusttunnel-h3",cfg!(feature="trusttunnel-h3"),"type: trusttunnel\nserver: 127.0.0.1\nport: 443\nusername: fixture\npassword: fixture\nquic: true","TrustTunnel"),
        ("mux",cfg!(feature="mux"),"type: ss\nserver: 127.0.0.1\nport: 443\ncipher: aes-256-gcm\npassword: fixture\nsmux: {enabled: true}","Shadowsocks"),
        ("kcptun",cfg!(feature="kcptun"),"type: ss\nserver: 127.0.0.1\nport: 443\ncipher: aes-256-gcm\npassword: fixture\nplugin: kcptun\nplugin-opts: {crypt: salsa20, mode: fast3, conn: 2, nocomp: true, datashard: 6, parityshard: 3}","Shadowsocks"),
        ("ech-tls-tunnel",cfg!(feature="ech-tls-tunnel"),"type: ss\nserver: 127.0.0.1\nport: 443\ncipher: aes-256-gcm\npassword: fixture\nplugin: ech-tls-tunnel\nplugin-opts:\n  mode: client\n  sni: tunnel.example.com\n  path: /fixture\n  ech_config: AEX+DQBBRwAgACBs1mZ0bOlNXgMxOAxJgD/h+pSyM6F7nGhmGyR0R0p3agAEAAEAAQAOcHVibGljLmV4YW1wbGUAAA==","Shadowsocks"),
    ]
}
#[tokio::test]
async fn compiled_protocol_options_pass_real_registry_factory_api_and_stop_join_without_wire_claim() {
    let _serial=LOCK.lock().await;
    for (name,enabled,node,kind) in samples() {
        if !enabled {continue;}
        let roots=root_fixture::Roots::new();let addresses=ports().await;
        let yaml=format!("strict: true\nmode: rule\nproxies:\n - name: NODE\n{}\nproxy-groups:\n - {{name: INNER,type: select,proxies: [NODE,DIRECT]}}\n - {{name: OUTER,type: select,proxies: [INNER,REJECT]}}\nrules: ['MATCH,OUTER']\ndns: {{enable: false,use-system-hosts: false}}\ntun: {{enable: false,auto-route: false}}\n",node.lines().map(|line|format!("   {line}")).collect::<Vec<_>>().join("\n"));
        let callback=Arc::new(Protect(Arc::new(AtomicUsize::new(0))));let weak=Arc::downgrade(&callback);
        let network=NetworkContext::protected(callback,None,CancellationToken::new()).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
        let session=Registry::start_owned(plan(yaml,addresses,&roots),None,network,None).unwrap();
        let ready=timeout(Duration::from_secs(5),session.ready()).await.unwrap();assert!(ready.is_ok(),"{name}: {ready:?}");
        let owner=session.owned_runtime().unwrap();assert!(owner.listener_status().user_listeners_ready);
        let row=get(addresses[0],"/proxies/NODE").await;assert_eq!(row["type"],kind,"{name}");
        let route=get(addresses[0],"/aerove/route").await;assert_eq!(route["picks"]["OUTER"],"INNER");assert_eq!(route["picks"]["INNER"],"NODE");
        let terminal=timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap();assert_eq!(terminal.phase,Phase::Stopped,"{name}");
        assert!(owner.cleanup_confirmed(),"{name}");assert!(weak.upgrade().is_none(),"{name} retained captured protect after confirmed stop");
        assert!(TcpListener::bind(addresses[0]).await.is_ok());assert!(TcpListener::bind(addresses[1]).await.is_ok());
    }
}
#[tokio::test]
async fn unsupported_protocol_unowned_group_provider_and_malformed_factory_still_fail_closed() {
    let _serial=LOCK.lock().await;
    let mut configurations=Vec::new();
    for kind in ["ssh","wireguard","tailscale","shadowquic","tuic","unknown"] {
        configurations.push(format!("strict: true\nproxies: [{{name: NODE,type: {kind},server: 127.0.0.1,port: 443}}]\nrules: ['MATCH,NODE']\n"));
    }
    for group in ["{name: GROUP,type: relay,proxies: [DIRECT,REJECT],use: [feed]}","{name: GROUP,type: load-balance,proxies: [DIRECT,REJECT],strategy: sticky-sessions}"] {
        configurations.push(format!("strict: true\nproxy-groups: [{group}]\nrules: ['MATCH,GROUP']\n"));
    }
    for groups in [
        "[{name: GROUP,type: relay,proxies: [MISSING,DIRECT]}]",
        "[{name: GROUP,type: relay,proxies: [GROUP,DIRECT]}]",
        "[{name: FRONT,type: relay,proxies: [BACK,DIRECT]},{name: BACK,type: load-balance,proxies: [FRONT,DIRECT],strategy: round-robin}]",
    ] { configurations.push(format!("strict: true\nproxy-groups: {groups}\nrules: ['MATCH,DIRECT']\n")); }
    configurations.push("strict: true\nproxy-providers: {P: {type: http,url: 'http://127.0.0.1:1/proxies',proxy: NOT_INTEGRATED}}\nrules: ['MATCH,DIRECT']\n".into());
    configurations.push("strict: true\nproxies: [{name: NODE,type: http,server: 127.0.0.1,port: 0}]\nrules: ['MATCH,NODE']\n".into());
    configurations.push("strict: true\nproxies:\n - {name: FIRST,type: http,server: 127.0.0.1,port: 443}\n - {name: NODE,type: http,server: 127.0.0.1,port: 444,dialer-proxy: MISSING}\nrules: ['MATCH,NODE']\n".into());
    for kind in ["http", "socks5"] {
        configurations.push(format!("strict: true\nproxies:\n - {{name: FIRST,type: http,server: 127.0.0.1,port: 443}}\n - {{name: NODE,type: {kind},server: 127.0.0.1,port: 444,dialer-proxy: MISSING,tls: true}}\nrules: ['MATCH,NODE']\n"));
    }
    for yaml in configurations {
        let roots=root_fixture::Roots::new();let addresses=ports().await;
        let network=NetworkContext::platform(CancellationToken::new()).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
        let session=Registry::start_owned(plan(format!("{yaml}dns: {{enable: false}}\ntun: {{enable: false,auto-route: false}}\n"),addresses,&roots),None,network,None).unwrap();
        assert!(timeout(Duration::from_secs(5),session.ready()).await.unwrap().is_err(),"{yaml}");
        let terminal=timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap();assert_eq!(terminal.phase,Phase::Failed);assert!(terminal.failure.is_some());
        if let Some(owner)=session.owned_runtime(){assert!(owner.cleanup_confirmed());}
        assert!(TcpListener::bind(addresses[0]).await.is_ok());assert!(TcpListener::bind(addresses[1]).await.is_ok());
    }
}
struct Gate { open:AtomicBool, changed:Notify, started:Notify }
impl Gate {async fn wait(&self) {self.started.notify_one();while !self.open.load(Ordering::Acquire){let changed=self.changed.notified();tokio::pin!(changed);changed.as_mut().enable();if !self.open.load(Ordering::Acquire){changed.await;}}}fn release(&self){self.open.store(true,Ordering::Release);self.changed.notify_waiters();}}
async fn peer(marker:u8,millis:u64,gate:Arc<Gate>,cancel:CancellationToken)->(SocketAddr,tokio::task::JoinHandle<()>) {
    let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();
    let task=tokio::spawn(async move{let mut children=JoinSet::new();loop{tokio::select!{biased;
        _=cancel.cancelled()=>break,
        joined=children.join_next(),if !children.is_empty()=>{assert!(joined.unwrap().is_ok());},
        accepted=listener.accept()=>{let(mut stream,_)=accepted.unwrap();let gate=gate.clone();children.spawn(async move{
            let request=headers(&mut stream).await.unwrap();assert!(request.starts_with(b"CONNECT "));let route=request.starts_with(b"CONNECT route.invalid:80 ");
            if stream.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await.is_err(){return;}
            while let Ok(request)=headers(&mut stream).await {
                if route {assert!(request.starts_with(b"GET /"));stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: keep-alive\r\n\r\n").await.unwrap();stream.write_all(&[marker]).await.unwrap();}
                else {assert!(request.starts_with(b"HEAD /health "));gate.wait().await;tokio::time::sleep(Duration::from_millis(millis)).await;if stream.write_all(b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n").await.is_err(){break;}}
            }
        });},
    }}children.abort_all();while let Some(joined)=children.join_next().await{if let Err(error)=joined{assert!(error.is_cancelled());}} });(address,task)
}
async fn connect(mixed:SocketAddr)->TcpStream {
    let mut stream=TcpStream::connect(mixed).await.unwrap();stream.write_all(b"CONNECT route.invalid:80 HTTP/1.1\r\nHost: route.invalid:80\r\n\r\n").await.unwrap();assert!(headers(&mut stream).await.unwrap().starts_with(b"HTTP/1.1 200"));stream
}
async fn response(stream:&mut TcpStream,path:&str)->u8 {
    stream.write_all(format!("GET /{path} HTTP/1.1\r\nHost: route.invalid\r\n\r\n").as_bytes()).await.unwrap();assert!(headers(stream).await.unwrap().starts_with(b"HTTP/1.1 200"));stream.read_u8().await.unwrap()
}
#[tokio::test]
async fn native_periodic_urltest_nested_http_route_keeps_old_flow_and_joins_owned_health() {
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let addresses=ports().await;
    let gate=Arc::new(Gate{open:AtomicBool::new(false),changed:Notify::new(),started:Notify::new()});let fixture=CancellationToken::new();
    let(a,a_task)=peer(b'A',150,gate.clone(),fixture.clone()).await;let(b,b_task)=peer(b'B',1,gate.clone(),fixture.clone()).await;
    let yaml=format!("strict: true\nmode: rule\nproxies:\n - {{name: A,type: http,server: 127.0.0.1,port: {}}}\n - {{name: B,type: http,server: 127.0.0.1,port: {}}}\nproxy-groups:\n - {{name: AUTO,type: url-test,proxies: [A,B],url: 'http://probe.invalid/health',interval: 1,lazy: false,tolerance: 0}}\n - {{name: OUTER,type: select,proxies: [AUTO,REJECT]}}\nrules: ['MATCH,OUTER']\ndns: {{enable: false,use-system-hosts: false}}\ntun: {{enable: false,auto-route: false}}\n",a.port(),b.port());
    let calls=Arc::new(AtomicUsize::new(0));let network=NetworkContext::protected(Arc::new(Protect(calls.clone())),None,CancellationToken::new()).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
    let session=Registry::start_owned(plan(yaml,addresses,&roots),None,network,None).unwrap();timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();let owner=session.owned_runtime().unwrap();
    timeout(Duration::from_secs(5),gate.started.notified()).await.unwrap();let before=owner.route_stamp();
    let initial=get(addresses[0],"/aerove/route").await;assert_eq!(initial["picks"]["AUTO"],"A");
    let mut old=timeout(Duration::from_secs(5),connect(addresses[1])).await.unwrap();assert_eq!(timeout(Duration::from_secs(5),response(&mut old,"before")).await.unwrap(),b'A');
    let(mut events,_)=tokio_tungstenite::connect_async(format!("ws://{}/aerove/telemetry?token={SECRET}",addresses[0])).await.unwrap();gate.release();
    timeout(Duration::from_secs(5),async{loop{let frame=events.next().await.unwrap().unwrap();let value:serde_json::Value=serde_json::from_str(frame.to_text().unwrap()).unwrap();if value["type"]=="groupsChanged"&&owner.route_stamp().1>before.1{break;}}}).await.unwrap();
    let after=get(addresses[0],"/aerove/route").await;assert_eq!(after["picks"]["AUTO"],"B");assert_eq!(after["picks"]["OUTER"],"AUTO");assert_eq!(owner.route_stamp().0,before.0);
    assert_eq!(timeout(Duration::from_secs(5),response(&mut old,"existing")).await.unwrap(),b'A');
    let mut fresh=timeout(Duration::from_secs(5),connect(addresses[1])).await.unwrap();assert_eq!(timeout(Duration::from_secs(5),response(&mut fresh,"new")).await.unwrap(),b'B');
    timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap();assert!(owner.cleanup_confirmed());
    assert_eq!(timeout(Duration::from_secs(5),old.read_u8()).await.unwrap().unwrap_err().kind(),io::ErrorKind::UnexpectedEof);assert_eq!(timeout(Duration::from_secs(5),fresh.read_u8()).await.unwrap().unwrap_err().kind(),io::ErrorKind::UnexpectedEof);
    assert!(calls.load(Ordering::Relaxed)>=4);fixture.cancel();timeout(Duration::from_secs(5),a_task).await.unwrap().unwrap();timeout(Duration::from_secs(5),b_task).await.unwrap().unwrap();
    for address in [addresses[0],addresses[1],a,b]{assert!(TcpListener::bind(address).await.is_ok());}
}


#[tokio::test]
async fn managed_dialer_failure_preserves_field_reason_through_ready_terminal_and_confirmed_cleanup() {
    let _serial=LOCK.lock().await;
    for kind in ["http","socks5"] {
        let roots=root_fixture::Roots::new();let addresses=ports().await;
        let yaml=format!("strict: true\nproxies:\n - {{name: FIRST,type: http,server: 127.0.0.1,port: 443}}\n - {{name: NODE,type: {kind},server: 127.0.0.1,port: 444,dialer-proxy: MISSING}}\nrules: ['MATCH,NODE']\ndns: {{enable: false,use-system-hosts: false}}\ntun: {{enable: false,auto-route: false}}\n");
        let network=NetworkContext::platform(CancellationToken::new()).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
        let session=Registry::start_owned(plan(yaml,addresses,&roots),None,network,None).unwrap();
        let generation=session.generation();
        let failure=timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap_err();
        assert_eq!(failure.code,"managedDialerProxyGraphRejected");
        assert_eq!(failure.field.as_deref(),Some("proxies.dialer-proxy"));
        assert!(failure.reason.as_deref().unwrap().contains("MISSING"));
        let json=serde_json::to_value(&failure).unwrap();
        assert_eq!(json["field"],"proxies.dialer-proxy");assert_eq!(json["reason"],failure.reason.as_deref().unwrap());
        let owner=session.owned_runtime().unwrap();assert!(owner.cleanup_confirmed());
        let status=owner.status();assert_eq!(status.state,"Failed");
        let message=failure.to_string();assert_eq!(status.error.as_deref(),Some(message.as_str()));
        let owner_failure = match timeout(Duration::from_secs(5),owner.ready()).await.unwrap() {
            Err(cause) => cause,
            Ok(_) => panic!("managed DPA unexpectedly became ready"),
        };
        assert_eq!(owner_failure.to_string(),failure.to_string());
        assert_eq!(timeout(Duration::from_secs(5),session.exited()).await.unwrap().unwrap_err(),failure);
        let terminal=timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap();
        assert_eq!(terminal.phase,Phase::Failed);assert_eq!(terminal.failure.as_ref(),Some(&failure));
        assert_eq!(session.generation(),generation);assert!(!terminal.api_ready);assert!(!terminal.user_listeners_ready);assert!(!terminal.tun_ready);
        assert_eq!(timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap().failure.as_ref(),Some(&failure));
        for address in addresses {assert!(TcpListener::bind(address).await.is_ok());}
    }
    // Untyped Loading cancellation must remain immediate.
    let roots=root_fixture::Roots::new();let addresses=ports().await;
    let yaml="strict: true\nproxies: [{name: NODE,type: http,server: 127.0.0.1,port: 0}]\nrules: ['MATCH,NODE']\ndns: {enable: false}\ntun: {enable: false,auto-route: false}\n";
    let network=NetworkContext::platform(CancellationToken::new()).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
    let session=Registry::start_owned(plan(yaml.into(),addresses,&roots),None,network,None).unwrap();
    let terminal_failure=timeout(Duration::from_secs(5),session.exited()).await.unwrap().unwrap_err();
    let failure=timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap_err();
    assert_eq!(failure,terminal_failure);
    assert_eq!(failure.code,"ownedRuntimeFailed");assert!(failure.field.is_none());assert!(failure.reason.is_none());
    assert!(serde_json::to_value(&failure).unwrap().get("reason").is_none());
    let terminal=timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap();
    assert_eq!(terminal.phase,Phase::Failed);assert_eq!(terminal.failure,Some(failure));
    assert!(session.owned_runtime().unwrap().cleanup_confirmed());
    for address in addresses {assert!(TcpListener::bind(address).await.is_ok());}
}


#[tokio::test]
async fn user_cancelled_running_ready_never_succeeds_and_stop_restart_preserves_successor() {
    let _serial=LOCK.lock().await;
    let roots=root_fixture::Roots::new();let addresses=ports().await;
    let yaml="strict: true\nrules: ['MATCH,DIRECT']\ndns: {enable: false,use-system-hosts: false}\ntun: {enable: false,auto-route: false}\n";
    let cancel=CancellationToken::new();
    let network=NetworkContext::platform(cancel.clone()).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
    let session=Registry::start_owned(plan(yaml.into(),addresses,&roots),None,network,None).unwrap();
    timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();
    assert_eq!(session.snapshot().phase,Phase::Running);
    cancel.cancel();
    assert_eq!(timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap_err().code,"cancelled");
    let stopped=timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap();
    assert_eq!(stopped.phase,Phase::Stopped);assert!(stopped.failure.is_none());
    assert!(session.owned_runtime().unwrap().cleanup_confirmed());
    let successor_network=NetworkContext::platform(CancellationToken::new()).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
    let successor=Registry::start_owned(plan(yaml.into(),addresses,&roots),None,successor_network,None).unwrap();
    timeout(Duration::from_secs(5),successor.ready()).await.unwrap().unwrap();
    assert!(successor.generation()>session.generation());
    assert_eq!(timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap().phase,Phase::Stopped);
    assert_eq!(timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap_err().code,"cancelled");
    assert_eq!(timeout(Duration::from_secs(5),successor.ready()).await.unwrap().unwrap().phase,Phase::Running);
    assert_eq!(timeout(Duration::from_secs(5),successor.stop()).await.unwrap().unwrap().phase,Phase::Stopped);
    assert!(successor.owned_runtime().unwrap().cleanup_confirmed());
    for address in addresses {assert!(TcpListener::bind(address).await.is_ok());}
}


#[tokio::test]
async fn user_cancelled_loading_bootstrap_ready_remains_cancelled_and_joins_without_late_running() {
    let _serial=LOCK.lock().await;
    let roots=root_fixture::Roots::new();let addresses=ports().await;
    let upstream=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();let dns=upstream.local_addr().unwrap();
    let (hit,waiting)=tokio::sync::oneshot::channel();
    let fixture=tokio::spawn(async move {
        let mut bytes=[0u8;4096];let received=upstream.recv_from(&mut bytes).await.unwrap();
        assert!(received.0>0);hit.send(()).unwrap();upstream
    });
    let yaml=format!("strict: true\nrules: ['MATCH,DIRECT']\ntun: {{enable: false,auto-route: false}}\ndns:\n enable: true\n use-system-hosts: false\n nameserver: ['udp://pending-owned.invalid:53']\n default-nameserver: ['{dns}']\n");
    let cancel=CancellationToken::new();
    let network=NetworkContext::platform(cancel.clone()).with_physical_dns(vec![dns]);
    let session=Registry::start_owned(plan(yaml,addresses,&roots),None,network,None).unwrap();
    timeout(Duration::from_secs(5),waiting).await.unwrap().unwrap();
    assert_eq!(session.snapshot().phase,Phase::Loading);
    cancel.cancel();
    assert_eq!(timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap_err().code,"cancelled");
    let terminal=timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap();
    assert_eq!(terminal.phase,Phase::Stopped);assert!(terminal.failure.is_none());
    assert!(!terminal.api_ready);assert!(!terminal.user_listeners_ready);assert!(!terminal.tun_ready);
    let owner=session.owned_runtime().unwrap();assert!(owner.cleanup_confirmed());assert!(!owner.status().core_ready);
    drop(timeout(Duration::from_secs(5),fixture).await.unwrap().unwrap());
    for address in addresses {assert!(TcpListener::bind(address).await.is_ok());}
}
