#![cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
use aerove_meow_core::{network_context::NetworkContext, runtime_plan::RuntimePlan, tun_driver::{PacketHandler, PacketWork, TunDriver}};
use meow_common::socket_protect::{self, SocketProtector};
use meow_tunnel::Tunnel;
use std::{future::{Future, poll_fn}, io, net::SocketAddr, os::{fd::{AsFd, RawFd}, unix::net::UnixDatagram}, sync::{Arc, atomic::{AtomicUsize, Ordering}}, task::Poll, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::TcpListener, sync::{Mutex, mpsc}, time::timeout};
use tokio_util::sync::CancellationToken;

static TEST_LOCK: Mutex<()> = Mutex::const_new(());
type Observed = (SocketAddr, SocketAddr, bool, usize);

struct Protector { calls: Arc<AtomicUsize>, deny: bool }
impl SocketProtector for Protector {
    fn protect(&self, fd: RawFd) -> io::Result<()> {
        assert!(unsafe { libc::fcntl(fd, libc::F_GETFD) } >= 0);
        let mut peer: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
        let mut length = std::mem::size_of_val(&peer) as libc::socklen_t;
        assert_eq!(unsafe { libc::getpeername(fd, (&mut peer as *mut libc::sockaddr_storage).cast(), &mut length) }, -1);
        self.calls.fetch_add(1, Ordering::Relaxed);
        if self.deny { Err(io::Error::from(io::ErrorKind::PermissionDenied)) } else { Ok(()) }
    }
}

struct EchoPackets { _same_tunnel: Tunnel, observed: mpsc::Sender<Observed> }
impl PacketHandler for EchoPackets {
    fn tcp(&self, _stream: lwip::TcpStream, _source: SocketAddr, _target: SocketAddr) -> PacketWork<()> {
        Box::pin(async { Err(io::Error::from(io::ErrorKind::Unsupported)) })
    }
    fn udp(&self, payload: Vec<u8>, source: SocketAddr, target: SocketAddr, dns: bool) -> PacketWork<Option<Vec<u8>>> {
        let observed = self.observed.clone();
        Box::pin(async move {
            observed.send((source, target, dns, payload.len())).await.unwrap();
            Ok(Some(payload))
        })
    }
}

fn plan() -> serde_json::Value {
    serde_json::json!({"schemaVersion":1,"runtimeMode":"tun","initiallyParked":false,
        "engineYaml":"strict: true\nrules: ['MATCH,DIRECT']\ndns: {enable: true, use-system-hosts: false, nameserver: ['127.0.0.1:53']}\n", "privateControl":{"address":"127.0.0.1:19090","secret":"candidate-tun-private-secret-0000000001"},
        "inbounds":{"configYaml":"mixed-port: 17890\n"},
        "tun":{"mtu":9000,"ipv6":true,"inet4Address":"198.18.0.1/30","inet6Address":"fdfe:dcba:9876::1/126","dnsHijack":["any:53"],"autoRoute":false},
        "physicalNetwork":{"handle":null,"dns":{"defaultInterface":null,"interfaces":{}}},
        "hostPolicyYaml":"{}","profileId":null,"revision":null,"resourceGeneration":null})
}
fn pair() -> (UnixDatagram, tokio::net::UnixDatagram) {
    let (caller, peer) = UnixDatagram::pair().unwrap();
    caller.set_nonblocking(true).unwrap(); peer.set_nonblocking(true).unwrap();
    (caller, tokio::net::UnixDatagram::from_std(peer).unwrap())
}
fn context(cancel: &CancellationToken, deny: bool, calls: Arc<AtomicUsize>) -> NetworkContext {
    NetworkContext::protected(Arc::new(Protector { calls, deny }), None, cancel.clone())
}
async fn handler(plan: Arc<RuntimePlan>, scope: Arc<meow_config::owned_load::OwnedLoadScope>, observed: mpsc::Sender<Observed>) -> io::Result<Arc<dyn PacketHandler>> {
    let config = meow_config::load_config_from_str_owned(&plan.engine_yaml, &scope).await.map_err(|_| io::Error::other("config failed"))?;
    let tunnel = Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    tunnel.set_mode(config.general.mode); tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
    tunnel.update_routing(config.proxies, config.rules, config.dialer_registry);
    Ok(Arc::new(EchoPackets { _same_tunnel: tunnel, observed }))
}

// Same wire shape as the fixed meow-lwip core_tests: inbound checksum checks are disabled there.
fn udp_frame(ipv6: bool, payload: &[u8], port: u16) -> Vec<u8> {
    let udp_len = u16::try_from(8 + payload.len()).unwrap();
    let mut frame = if ipv6 {
        let mut frame = vec![0x60,0,0,0]; frame.extend_from_slice(&udp_len.to_be_bytes()); frame.extend_from_slice(&[17,64]);
        frame.extend_from_slice(&"fdfe:dcba:9876::2".parse::<std::net::Ipv6Addr>().unwrap().octets());
        frame.extend_from_slice(&"2001:db8::5".parse::<std::net::Ipv6Addr>().unwrap().octets()); frame
    } else {
        let mut frame = vec![0x45,0]; frame.extend_from_slice(&(20+udp_len).to_be_bytes());
        frame.extend_from_slice(&[0,0,0,0,64,17,0,0,198,18,0,2,198,18,0,5]); frame
    };
    frame.extend_from_slice(&41000u16.to_be_bytes()); frame.extend_from_slice(&port.to_be_bytes());
    frame.extend_from_slice(&udp_len.to_be_bytes()); frame.extend_from_slice(&[0,0]); frame.extend_from_slice(payload); frame
}
async fn receive_reply(peer: &tokio::net::UnixDatagram, ipv6: bool, payload: &[u8]) {
    timeout(Duration::from_secs(5), async {
        let mut buffer = vec![0;65536];
        loop {
            let n = peer.recv(&mut buffer).await.unwrap(); let frame = &buffer[..n];
            if frame[0] >> 4 != if ipv6 {6} else {4} { continue; }
            let header = if ipv6 {40} else {usize::from(frame[0]&15)*4};
            if frame[if ipv6 {6} else {9}] != 17 { continue; }
            assert_eq!(u16::from_be_bytes(frame[header+2..header+4].try_into().unwrap()),41000);
            assert_eq!(&frame[header+8..],payload);
            assert!(frame.len()>1500 && frame.len()<=9000);
            return;
        }
    }).await.unwrap();
}

#[tokio::test]
async fn plan_owned_fd_factory_protect_netstack_packets_cancel_join_and_caller_survives() {
    let _serial=TEST_LOCK.lock().await;
    let (caller, peer)=pair(); let cancel=CancellationToken::new();
    let calls=Arc::new(AtomicUsize::new(0)); let network=context(&cancel,false,calls.clone());
    let server=TcpListener::bind("127.0.0.1:0").await.unwrap(); let address=server.local_addr().unwrap();
    let accepted=tokio::spawn(async move { let (mut socket,_)=server.accept().await.unwrap(); socket.write_all(b"factory-protected").await.unwrap(); });
    let (tx,mut observed)=mpsc::channel(8);
    let captured=Arc::new(Mutex::new(None)); let captured_factory=captured.clone();
    let driver=TunDriver::load(&plan().to_string(),caller.as_fd(),network,cancel.clone(),move|plan,scope|async move {
        *captured_factory.lock().await=socket_protect::socket_protector();
        let mut probe=socket_protect::connect_tcp(address).await?;
        let mut body=[0;17]; probe.read_exact(&mut body).await?; assert_eq!(&body,b"factory-protected");
        assert!(socket_protect::resolve_host("no-system-fallback.invalid", 443).await.is_err());
        handler(plan,scope,tx).await
    }).await.unwrap();
    accepted.await.unwrap(); assert_eq!(calls.load(Ordering::Relaxed),1);
    assert!(driver.receipt().ready); assert!(!driver.receipt().core_done); assert_eq!(driver.receipt().mtu,9000);
    for ipv6 in [false,true] {
        let payload=vec![if ipv6 {6} else {4};2048]; let port=if ipv6 {53} else {443};
        peer.send(&udp_frame(ipv6,&payload,port)).await.unwrap();
        receive_reply(&peer,ipv6,&payload).await;
        let (source,target,hijacked,length)=timeout(Duration::from_secs(5),observed.recv()).await.unwrap().unwrap();
        assert_eq!(source.port(),41000); assert_eq!(target.port(),port); assert_eq!(hijacked,ipv6); assert_eq!(length,payload.len());
    }
    let second_cancel=CancellationToken::new();
    let invoked=Arc::new(AtomicUsize::new(0)); let invoked_factory=invoked.clone();
    let collision=TunDriver::load(&plan().to_string(),caller.as_fd(),context(&second_cancel,false,calls.clone()),second_cancel,move|_,_| {
        invoked_factory.fetch_add(1,Ordering::Relaxed);
        std::future::ready(Err(io::Error::other("second factory must not run while a stack is active")))
    }).await;
    assert!(collision.is_err()); assert_eq!(invoked.load(Ordering::Relaxed),0);
    // No worker is released between the two polls: cancellation of the waiter cannot consume its JoinHandle.
    let mut first=Box::pin(driver.stop());
    poll_fn(|cx| { assert!(first.as_mut().poll(cx).is_pending()); Poll::Ready(()) }).await; drop(first);
    let receipt=timeout(Duration::from_secs(5),driver.stop()).await.unwrap().unwrap();
    assert!(!receipt.ready && receipt.core_done); assert_eq!(receipt.packets_in,2); assert!(receipt.packets_out>=2);
    let old_network=socket_protect::NetworkScope { protector:captured.lock().await.take(), resolver:None };
    assert!(old_network.scope(socket_protect::connect_tcp(address)).await.is_err()); assert_eq!(calls.load(Ordering::Relaxed),1);
    caller.send(b"original-fd-alive").unwrap(); let mut retained=[0;32]; assert_eq!(peer.recv(&mut retained).await.unwrap(),17);
    let next_cancel=CancellationToken::new(); let (tx,_)=mpsc::channel(8);
    let next=TunDriver::load(&plan().to_string(),caller.as_fd(),context(&next_cancel,false,calls.clone()),next_cancel,move|plan,scope|handler(plan,scope,tx)).await.unwrap();
    assert!(next.receipt().ready); assert!(timeout(Duration::from_secs(5),next.stop()).await.unwrap().unwrap().core_done);
}

#[tokio::test]
async fn rejected_protector_prevents_factory_dial_and_never_admits_a_stack() {
    let _serial=TEST_LOCK.lock().await; let (caller,peer)=pair(); let cancel=CancellationToken::new(); let calls=Arc::new(AtomicUsize::new(0));
    let target=TcpListener::bind("127.0.0.1:0").await.unwrap(); let address=target.local_addr().unwrap();
    let result=TunDriver::load(&plan().to_string(),caller.as_fd(),context(&cancel,true,calls.clone()),cancel,move|_,_|async move {
        socket_protect::connect_tcp(address).await?;
        Err(io::Error::other("must not construct packet handler"))
    }).await;
    assert!(result.is_err()); assert_eq!(calls.load(Ordering::Relaxed),1);
    assert!(timeout(Duration::from_millis(50),target.accept()).await.is_err());
    caller.send(b"caller").unwrap(); let mut buffer=[0;16]; assert_eq!(peer.recv(&mut buffer).await.unwrap(),6);
}

#[test]
fn schema_one_rejects_fd_mode_precision_and_dns_scope_mismatches() {
    let good=plan(); assert!(RuntimePlan::parse(&good.to_string(),3,true,false).is_ok());
    for (field,value) in [("mtu",serde_json::json!(1279)),("mtu",serde_json::json!(65536)),
        ("ipv6",serde_json::json!(false)),("inet4Address",serde_json::json!("invalid")),
        ("dnsHijack",serde_json::json!(["any:5353"]))] {
        let mut bad=good.clone(); bad["tun"][field]=value;
        assert!(RuntimePlan::parse(&bad.to_string(),3,true,false).is_err());
    }
    let mut bad=good.clone(); bad["physicalNetwork"]["handle"]="18446744073709551616".into(); assert!(RuntimePlan::parse(&bad.to_string(),3,true,false).is_err());
    bad["physicalNetwork"]["handle"]="01".into(); assert!(RuntimePlan::parse(&bad.to_string(),3,true,false).is_err());
    assert!(RuntimePlan::parse(&good.to_string(),-1,true,false).is_err());
    assert!(RuntimePlan::parse(&good.to_string(),3,false,false).is_err());
    let mut bad=good.clone(); bad["tun"]["autoRoute"]=true.into(); assert!(RuntimePlan::parse(&bad.to_string(),3,true,false).is_err());
    let mut bad=good.clone(); bad["physicalNetwork"]["dns"]=serde_json::json!({"defaultInterface":"wlan0","interfaces":{"wlan0":["fe80::1%other"]}}); assert!(RuntimePlan::parse(&bad.to_string(),3,true,false).is_err());
    let mut physical=good.clone(); physical["physicalNetwork"]["handle"]="42".into();
    assert!(RuntimePlan::parse(&physical.to_string(),3,true,false).is_ok());
    let mut parked=good; parked["initiallyParked"]=true.into(); assert!(RuntimePlan::parse(&parked.to_string(),-1,true,false).is_ok());
}

#[tokio::test]
async fn cancel_during_owned_factory_joins_without_ready_or_a_stack_and_keeps_caller_fd() {
    let _serial=TEST_LOCK.lock().await; let (caller,peer)=pair();
    let cancel=CancellationToken::new(); let calls=Arc::new(AtomicUsize::new(0));
    let entered=Arc::new(tokio::sync::Notify::new()); let entered_factory=entered.clone();
    let owner=TunDriver::start(&plan().to_string(),caller.as_fd(),context(&cancel,false,calls.clone()),cancel,move|_,_|async move {
        entered_factory.notify_one();
        std::future::pending::<()>().await;
        Err(io::Error::other("cancelled factory must not return a handler"))
    }).unwrap();
    entered.notified().await;
    assert!(!owner.receipt().ready);
    let stopped=timeout(Duration::from_secs(5),owner.stop()).await.unwrap().unwrap();
    assert!(!stopped.ready && !stopped.core_done);
    assert_eq!(calls.load(Ordering::Relaxed),0);
    assert!(owner.await_ready().await.is_err());
    caller.send(b"original").unwrap(); let mut buffer=[0;16]; assert_eq!(peer.recv(&mut buffer).await.unwrap(),8);
}

#[tokio::test]
async fn captured_network_wraps_actual_blocking_thread_dial_and_restores_ambient_hooks() {
    use meow_config::owned_load::LoadContext;
    let _serial=TEST_LOCK.lock().await;
    let cancel=CancellationToken::new(); let calls=Arc::new(AtomicUsize::new(0)); let captured=context(&cancel,false,calls.clone());
    let server=TcpListener::bind("127.0.0.1:0").await.unwrap(); let address=server.local_addr().unwrap();
    let accepted=tokio::spawn(async move { let (mut stream,_)=server.accept().await.unwrap(); stream.write_all(b"captured-thread").await.unwrap(); });
    assert!(socket_protect::socket_protector().is_none());
    tokio::task::spawn_blocking(move || {
        assert!(socket_protect::socket_protector().is_none());
        LoadContext::run_blocking(&captured, Box::new(move || {
            tokio::runtime::Handle::current().block_on(async move {
                let mut stream=socket_protect::connect_tcp(address).await.unwrap();
                let mut bytes=[0;15]; stream.read_exact(&mut bytes).await.unwrap(); assert_eq!(&bytes,b"captured-thread");
            });
        }));
        assert!(socket_protect::socket_protector().is_none());
    }).await.unwrap();
    accepted.await.unwrap(); assert_eq!(calls.load(Ordering::Relaxed),1);
}
