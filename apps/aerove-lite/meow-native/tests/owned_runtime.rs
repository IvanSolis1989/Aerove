#![cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
use aerove_meow_core::{Registry, Phase, network_context::NetworkContext, physical_dns::PhysicalResolver};
use hickory_proto::{op::{Message, MessageType, OpCode, Query}, rr::{RData, Record, RecordType, rdata::A}, serialize::binary::{BinDecodable, BinEncodable}};
use meow_common::socket_protect::{self, SocketProtector};
use std::{future::{Future, poll_fn}, io, net::{Ipv4Addr, SocketAddr}, os::{fd::{AsRawFd, RawFd}, unix::net::UnixDatagram}, sync::{Arc, atomic::{AtomicUsize, Ordering}}, task::Poll, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream, UdpSocket}, sync::{Mutex, oneshot}, time::timeout};
use tokio_util::sync::CancellationToken;

#[path = "support/resource_roots.rs"]
mod root_fixture;
use root_fixture::Roots;

static LOCK: Mutex<()> = Mutex::const_new(());
const SECRET: &str = "meow-owned-runtime-private-secret-000001";
struct Protect(Arc<AtomicUsize>);
impl SocketProtector for Protect { fn protect(&self, fd: RawFd) -> io::Result<()> {
    assert!(unsafe { libc::fcntl(fd, libc::F_GETFD) } >= 0);
    self.0.fetch_add(1, Ordering::Relaxed); Ok(())
} }
async fn ports() -> [SocketAddr; 3] {
    let a = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let b = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let c = TcpListener::bind("127.0.0.1:0").await.unwrap();
    [a.local_addr().unwrap(), b.local_addr().unwrap(), c.local_addr().unwrap()]
}
fn pair() -> (UnixDatagram, tokio::net::UnixDatagram) {
    let (caller, peer) = UnixDatagram::pair().unwrap(); caller.set_nonblocking(true).unwrap(); peer.set_nonblocking(true).unwrap();
    (caller, tokio::net::UnixDatagram::from_std(peer).unwrap())
}
fn plan(ports: [SocketAddr; 3], dns: SocketAddr, parked: bool, roots: &Roots) -> serde_json::Value {
    serde_json::json!({"schemaVersion":1,"runtimeMode":"tun","initiallyParked":parked,
        "engineYaml":format!("strict: true\nmode: rule\nrules: ['MATCH,DIRECT']\ntun: {{enable: false, auto-route: false}}\ndns:\n  enable: true\n  use-system-hosts: false\n  nameserver: ['{dns}']\n"),
        "privateControl":{"address":ports[0].to_string(),"secret":SECRET},
        "inbounds":{"configYaml":format!("mixed-port: {}\nbind-address: 127.0.0.1\ndns-listen: '{}'\n",ports[1].port(),ports[2])},
        "tun":{"mtu":9000,"ipv6":false,"inet4Address":"198.18.0.1/30","inet6Address":null,"dnsHijack":["198.18.0.2:53"],"autoRoute":false},
        "physicalNetwork":{"handle":null,"dns":{"defaultInterface":"fixture","interfaces":{"fixture":["127.0.0.1"]}}},
        "hostPolicyYaml":"{}","profileId":null,"revision":null,"resourceGeneration":null,
        "resourceReadRoot":roots.read.to_str().unwrap(),"cacheRoot":roots.cache.to_str().unwrap()})
}
fn network(cancel: &CancellationToken, calls: Arc<AtomicUsize>, dns: SocketAddr) -> NetworkContext {
    PhysicalResolver::from_servers(vec![dns], NetworkContext::protected(Arc::new(Protect(calls)), None, cancel.clone()), false).unwrap().network()
}
fn question(name: &str) -> Vec<u8> {
    let mut message = Message::new(321, MessageType::Query, OpCode::Query);
    message.metadata.recursion_desired = true; message.add_query(Query::query(name.parse().unwrap(), RecordType::A)); message.to_bytes().unwrap()
}
fn answer(request: &[u8]) -> Vec<u8> {
    let request = Message::from_bytes(request).unwrap(); let mut message = Message::new(request.metadata.id, MessageType::Response, OpCode::Query);
    message.metadata.recursion_desired = true; message.metadata.recursion_available = true;
    message.add_queries(request.queries.iter().cloned());
    message.add_answer(Record::from_rdata(request.queries[0].name.clone(), 60, RData::A(A(Ipv4Addr::LOCALHOST)))); message.to_bytes().unwrap()
}
async fn dns_fixture(cancel: CancellationToken) -> (SocketAddr, tokio::task::JoinHandle<()>, Arc<AtomicUsize>) {
    let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap(); let address = socket.local_addr().unwrap();
    let hits = Arc::new(AtomicUsize::new(0)); let count = hits.clone();
    let task = tokio::spawn(async move { let mut buffer = [0u8; 4096]; loop { tokio::select! { biased;
        _ = cancel.cancelled() => break,
        received = socket.recv_from(&mut buffer) => { let (n, peer) = received.unwrap(); count.fetch_add(1, Ordering::Relaxed); socket.send_to(&answer(&buffer[..n]), peer).await.unwrap(); },
    } } });
    (address, task, hits)
}
async fn http(address: SocketAddr, path: &str) -> Vec<u8> {
    let mut socket = TcpStream::connect(address).await.unwrap();
    socket.write_all(format!("GET {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {SECRET}\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut bytes = Vec::new(); timeout(Duration::from_secs(5), socket.read_to_end(&mut bytes)).await.unwrap().unwrap();
    assert!(bytes.starts_with(b"HTTP/1.1 200"), "{}", String::from_utf8_lossy(&bytes)); bytes
}
fn http_json(bytes: &[u8]) -> serde_json::Value {
    let start = bytes.windows(4).position(|bytes| bytes == b"\r\n\r\n").unwrap() + 4;
    serde_json::from_slice(&bytes[start..]).unwrap()
}
fn udp_frame(target: SocketAddr, source_port: u16, payload: &[u8]) -> Vec<u8> {
    let SocketAddr::V4(target) = target else { panic!("IPv4 fixture"); };
    let length = u16::try_from(28 + payload.len()).unwrap(); let mut bytes = vec![0x45,0]; bytes.extend_from_slice(&length.to_be_bytes());
    bytes.extend_from_slice(&[0,0,0,0,64,17,0,0,198,18,0,1]); bytes.extend_from_slice(&target.ip().octets());
    bytes.extend_from_slice(&source_port.to_be_bytes()); bytes.extend_from_slice(&target.port().to_be_bytes());
    bytes.extend_from_slice(&(length-20).to_be_bytes()); bytes.extend_from_slice(&[0,0]); bytes.extend_from_slice(payload); bytes
}
async fn udp_reply(peer: &tokio::net::UnixDatagram, port: u16) -> Vec<u8> {
    timeout(Duration::from_secs(5), async { let mut bytes = vec![0u8; 65536]; loop {
        let n = peer.recv(&mut bytes).await.unwrap(); let packet = &bytes[..n];
        if packet.len() < 28 || packet[0] >> 4 != 4 || packet[9] != 17 { continue; }
        let offset = usize::from(packet[0]&15)*4;
        if u16::from_be_bytes(packet[offset+2..offset+4].try_into().unwrap()) == port { return packet[offset+8..].to_vec(); }
    } }).await.unwrap()
}
fn tcp_frame(target: SocketAddr, sequence: u32, ack: u32, flags: u8, payload: &[u8]) -> Vec<u8> {
    let SocketAddr::V4(target) = target else { panic!("IPv4 fixture"); };
    let length = u16::try_from(40+payload.len()).unwrap(); let mut frame = vec![0x45,0]; frame.extend_from_slice(&length.to_be_bytes());
    frame.extend_from_slice(&[0,0,0,0,64,6,0,0,198,18,0,1]); frame.extend_from_slice(&target.ip().octets());
    frame.extend_from_slice(&41000u16.to_be_bytes()); frame.extend_from_slice(&target.port().to_be_bytes()); frame.extend_from_slice(&sequence.to_be_bytes());
    frame.extend_from_slice(&ack.to_be_bytes()); frame.extend_from_slice(&[0x50,flags,0xff,0xff,0,0,0,0]); frame.extend_from_slice(payload); frame
}
async fn tcp_segment(peer: &tokio::net::UnixDatagram) -> (u32, u8, Vec<u8>) {
    timeout(Duration::from_secs(5), async { let mut bytes = vec![0u8;65536]; loop {
        let n = peer.recv(&mut bytes).await.unwrap(); let frame = &bytes[..n];
        if n < 40 || frame[0] >> 4 != 4 || frame[9] != 6 { continue; }
        let start = usize::from(frame[0]&15)*4; let tcp = &frame[start..];
        if u16::from_be_bytes(tcp[2..4].try_into().unwrap()) != 41000 { continue; }
        let header = usize::from(tcp[12]>>4)*4;
        return (u32::from_be_bytes(tcp[4..8].try_into().unwrap()), tcp[13], tcp[header..].to_vec());
    } }).await.unwrap()
}
async fn real_tcp_http(peer: &tokio::net::UnixDatagram, target: SocketAddr) {
    peer.send(&tcp_frame(target,1000,0,2,&[])).await.unwrap();
    let (server, flags, _) = tcp_segment(peer).await; assert_eq!(flags & 0x12, 0x12);
    let ack = server.wrapping_add(1); peer.send(&tcp_frame(target,1001,ack,0x10,&[])).await.unwrap();
    let request = b"GET /tun-direct HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    peer.send(&tcp_frame(target,1001,ack,0x18,request)).await.unwrap();
    let mut response = Vec::new();
    timeout(Duration::from_secs(5), async { while !response.ends_with(b"meow-tun-direct") {
        let (sequence, flags, payload) = tcp_segment(peer).await;
        let ack = sequence.wrapping_add(u32::try_from(payload.len()).unwrap()).wrapping_add(u32::from(flags&1));
        response.extend_from_slice(&payload);
        peer.send(&tcp_frame(target,1001+u32::try_from(request.len()).unwrap(),ack,0x10,&[])).await.unwrap();
    } }).await.unwrap();
    assert!(response.starts_with(b"HTTP/1.1 200"));
}

#[tokio::test]
async fn one_session_routes_actual_tun_tcp_udp_dns_and_socks_udp_then_joins_and_rebinds() {
    let _serial = LOCK.lock().await;
    let roots = Roots::new();
    let fixtures = CancellationToken::new(); let (dns, dns_task, _) = dns_fixture(fixtures.clone()).await;
    let udp = UdpSocket::bind("127.0.0.1:0").await.unwrap(); let udp_address = udp.local_addr().unwrap();
    let udp_stop = fixtures.clone(); let udp_task = tokio::spawn(async move { let mut bytes = vec![0u8;65535]; loop { tokio::select! { biased;
        _ = udp_stop.cancelled() => break,
        packet = udp.recv_from(&mut bytes) => { let (n,peer) = packet.unwrap(); udp.send_to(&bytes[..n],peer).await.unwrap(); udp.send_to(b"second-reply",peer).await.unwrap(); },
    } } });
    let tcp = TcpListener::bind("127.0.0.1:0").await.unwrap(); let tcp_address = tcp.local_addr().unwrap();
    let target = tokio::spawn(async move { let (mut stream,_) = tcp.accept().await.unwrap(); let mut request = Vec::new(); let mut byte = [0u8];
        while !request.ends_with(b"\r\n\r\n") { stream.read_exact(&mut byte).await.unwrap(); request.push(byte[0]); }
        assert!(request.starts_with(b"GET /tun-direct "));
        stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\nmeow-tun-direct").await.unwrap();
        stream.shutdown().await.unwrap(); let mut rest = Vec::new(); stream.read_to_end(&mut rest).await.unwrap(); tcp
    });
    let ports = ports().await;
    let mut candidate = plan(ports,dns,false,&roots);
    candidate["engineYaml"] = candidate["engineYaml"].as_str().unwrap().replace("rules: ['MATCH,DIRECT']",
        &format!("rules: ['DST-PORT,{},DIRECT', 'MATCH,DIRECT']", udp_address.port())).into();
    let json = candidate.to_string(); let (caller,peer) = pair();
    let cancel = CancellationToken::new(); let calls = Arc::new(AtomicUsize::new(0)); let context = network(&cancel,calls.clone(),dns);
    let session = Registry::start_owned(json.clone(),Some(caller.try_clone().unwrap().into()),context.clone(),None).unwrap();
    let ready = timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap(); assert!(ready.tun_ready && ready.api_ready && ready.user_listeners_ready);
    let owner = session.owned_runtime().unwrap(); let status = owner.listener_status(); assert!(status.listening); assert_eq!(status.active_ports,vec![ports[1].port(),ports[2].port()].into_iter().collect::<std::collections::BTreeSet<_>>().into_iter().collect::<Vec<_>>());
    http(ports[0],"/configs").await;
    real_tcp_http(&peer,tcp_address).await;
    let payload = vec![7u8;2048]; peer.send(&udp_frame(udp_address,41001,&payload)).await.unwrap();
    assert_eq!(udp_reply(&peer,41001).await,payload); assert_eq!(udp_reply(&peer,41001).await,b"second-reply");
    let connections = http_json(&http(ports[0],"/connections").await);
    let live = connections["connections"].as_array().unwrap().iter().find(|connection|
        connection["metadata"]["sourcePort"].as_u64() == Some(41001)
        && connection["metadata"]["destinationPort"].as_u64() == Some(u64::from(udp_address.port()))).unwrap();
    assert_eq!(live["rule"],"DST-PORT");
    assert_eq!(live["rulePayload"],udp_address.port().to_string());
    let udp_id = live["id"].as_str().unwrap().to_owned();
    let protected = calls.load(Ordering::Relaxed);
    peer.send(&udp_frame(udp_address,41001,b"same-udp-flow")).await.unwrap();
    assert_eq!(udp_reply(&peer,41001).await,b"same-udp-flow");
    assert_eq!(udp_reply(&peer,41001).await,b"second-reply");
    assert_eq!(calls.load(Ordering::Relaxed),protected);
    let connections = http_json(&http(ports[0],"/connections").await);
    let same = connections["connections"].as_array().unwrap().iter().find(|connection| connection["id"] == udp_id).unwrap();
    assert_eq!(same["rule"],"DST-PORT");
    assert_eq!(same["rulePayload"],udp_address.port().to_string());
    peer.send(&udp_frame("198.18.0.2:53".parse().unwrap(),41002,&question("tun-owned.invalid"))).await.unwrap();
    assert_eq!(Message::from_bytes(&udp_reply(&peer,41002).await).unwrap().answers.len(),1);
    let client = UdpSocket::bind("127.0.0.1:0").await.unwrap(); client.send_to(&question("listen-udp.invalid"),ports[2]).await.unwrap(); let mut bytes = [0u8;4096];
    let (n,_) = timeout(Duration::from_secs(5),client.recv_from(&mut bytes)).await.unwrap().unwrap(); assert_eq!(Message::from_bytes(&bytes[..n]).unwrap().answers.len(),1);
    let mut stream = TcpStream::connect(ports[2]).await.unwrap(); let request = question("listen-tcp.invalid"); stream.write_u16(u16::try_from(request.len()).unwrap()).await.unwrap(); stream.write_all(&request).await.unwrap();
    let n = timeout(Duration::from_secs(5),stream.read_u16()).await.unwrap().unwrap(); let mut reply = vec![0;usize::from(n)]; stream.read_exact(&mut reply).await.unwrap(); assert_eq!(Message::from_bytes(&reply).unwrap().answers.len(),1); drop(stream);
    let mut socks = TcpStream::connect(ports[1]).await.unwrap(); socks.write_all(&[5,1,0]).await.unwrap(); let mut negotiation = [0u8;2]; socks.read_exact(&mut negotiation).await.unwrap(); assert_eq!(negotiation,[5,0]);
    socks.write_all(&[5,3,0,1,127,0,0,1,0,0]).await.unwrap(); let mut associate = [0u8;10]; socks.read_exact(&mut associate).await.unwrap(); assert_eq!(&associate[..4],&[5,0,0,1]);
    let relay = SocketAddr::from(([associate[4],associate[5],associate[6],associate[7]],u16::from_be_bytes([associate[8],associate[9]])));
    let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap(); let mut packet = vec![0,0,0,1,127,0,0,1]; packet.extend_from_slice(&udp_address.port().to_be_bytes()); packet.extend_from_slice(b"socks-owned"); socket.send_to(&packet,relay).await.unwrap();
    let (n,_) = timeout(Duration::from_secs(5),socket.recv_from(&mut bytes)).await.unwrap().unwrap(); assert_eq!(&bytes[10..n],b"socks-owned");
    assert!(calls.load(Ordering::Relaxed)>=5); let totals = session.snapshot(); assert!(totals.upload_bytes.parse::<u64>().unwrap()>0 && totals.download_bytes.parse::<u64>().unwrap()>0);
    let exiting = { let session = session.clone(); tokio::spawn(async move { session.exited().await }) };
    assert!(!exiting.is_finished());
    let mut stopping = Box::pin(session.stop()); poll_fn(|cx| { assert!(stopping.as_mut().poll(cx).is_pending()); Poll::Ready(()) }).await; drop(stopping);
    assert_eq!(timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap().phase,Phase::Stopped);
    assert_eq!(timeout(Duration::from_secs(5),exiting).await.unwrap().unwrap().unwrap().phase,Phase::Stopped);
    assert!(owner.cleanup_confirmed());
    let listening = timeout(Duration::from_secs(5),target).await.unwrap().unwrap();
    assert_eq!(timeout(Duration::from_secs(5),socks.read(&mut bytes)).await.unwrap().unwrap(),0);
    assert!(UdpSocket::bind(relay).await.is_ok());
    for address in ports { assert!(TcpListener::bind(address).await.is_ok()); } assert!(UdpSocket::bind(ports[2]).await.is_ok());
    let before = calls.load(Ordering::Relaxed); assert!(context.scope(socket_protect::connect_tcp(tcp_address)).await.is_err()); assert_eq!(calls.load(Ordering::Relaxed),before);
    assert!(timeout(Duration::from_millis(50),listening.accept()).await.is_err());
    caller.send(b"caller-survived").unwrap(); timeout(Duration::from_secs(5),async { loop { let n = peer.recv(&mut bytes).await.unwrap(); if &bytes[..n]==b"caller-survived" { break; } } }).await.unwrap();
    let next_cancel = CancellationToken::new(); let next = Registry::start_owned(json,Some(caller.try_clone().unwrap().into()),network(&next_cancel,calls,dns),None).unwrap();
    assert!(timeout(Duration::from_secs(5),next.ready()).await.unwrap().unwrap().tun_ready); assert!(next.generation()>session.generation());
    next.stop().await.unwrap(); fixtures.cancel(); dns_task.await.unwrap(); udp_task.await.unwrap();
}

#[tokio::test]
async fn initially_parked_has_only_private_api_then_same_core_attaches_parks_and_resumes() {
    let _serial = LOCK.lock().await;
    let roots = Roots::new();
    let fixtures = CancellationToken::new(); let (dns, task, _) = dns_fixture(fixtures.clone()).await;
    let ports = ports().await; let cancel = CancellationToken::new(); let calls = Arc::new(AtomicUsize::new(0));
    let session = Registry::start_owned(plan(ports,dns,true,&roots).to_string(),None,network(&cancel,calls,dns),None).unwrap();
    let ready = timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();
    assert_eq!(ready.runtime_mode,"tun"); assert!(ready.api_ready && !ready.tun_ready && !ready.user_listeners_ready);
    let owner = session.owned_runtime().unwrap(); assert!(owner.status().core_ready); assert!(!owner.listener_status().listening); assert!(owner.listener_status().active_ports.is_empty());
    assert!(TcpListener::bind(ports[1]).await.is_ok()); assert!(TcpListener::bind(ports[2]).await.is_ok()); assert!(UdpSocket::bind(ports[2]).await.is_ok());
    let before = http_json(&http(ports[0],"/aerove/route").await);
    let dormant=http_json(&http(ports[0],"/aerove/network").await);assert_eq!(dormant["activePorts"],serde_json::json!([]));
    let held=editable_ports(1,&ports.map(|address|address.port())).await;let edited=held[0].local_addr().unwrap();drop(held);
    let deferred=network_body("77777777-7777-4777-8777-777777777777",dormant["coreEpoch"].as_str().unwrap(),edited.port(),0,0,false,&["parked:deferred"]);
    let rejected=network_http(ports[0],"POST",&deferred.to_string(),SECRET).await;
    assert!(rejected.starts_with(b"HTTP/1.1 409"));assert_eq!(http_json(&rejected)["error"],"listenersParked");
    assert_eq!(dormant,http_json(&http(ports[0],"/aerove/network").await));assert!(TcpListener::bind(edited).await.is_ok());
    let (caller,peer) = pair();
    let active = timeout(Duration::from_secs(5),owner.resume(Some(caller.try_clone().unwrap().into()))).await.unwrap().unwrap();
    assert!(active.tun_ready && active.user_listeners_ready && active.listening);
    let resumed_network=http_json(&http(ports[0],"/aerove/network").await);assert_eq!(resumed_network["network"]["mixed-port"],ports[1].port());assert_eq!(resumed_network["network"]["authEnabled"],false);
    assert!(TcpListener::bind(ports[1]).await.is_err()); assert!(UdpSocket::bind(ports[2]).await.is_err());
    assert!(owner.resume(None).await.is_err()); assert!(owner.status().core_ready);
    assert!(owner.resume(Some(caller.try_clone().unwrap().into())).await.is_err());
    assert!(unsafe { libc::fcntl(caller.as_raw_fd(),libc::F_GETFD) } >= 0);
    let parked = timeout(Duration::from_secs(5),owner.park()).await.unwrap().unwrap();
    assert!(!parked.listening && !parked.tun_ready && parked.active_ports.is_empty());
    assert_eq!(before,http_json(&http(ports[0],"/aerove/route").await)); assert!(owner.status().core_ready);
    assert!(TcpListener::bind(ports[1]).await.is_ok()); assert!(TcpListener::bind(ports[2]).await.is_ok()); assert!(UdpSocket::bind(ports[2]).await.is_ok());
    caller.send(b"fd-after-park").unwrap(); let mut bytes = [0u8;64]; assert_eq!(timeout(Duration::from_secs(5),peer.recv(&mut bytes)).await.unwrap().unwrap(),13);
    let resumed = timeout(Duration::from_secs(5),owner.resume(Some(caller.try_clone().unwrap().into()))).await.unwrap().unwrap();
    assert!(resumed.tun_ready); assert_eq!(resumed.core_epoch,parked.core_epoch); assert_eq!(session.generation().to_string(),resumed.core_epoch);
    timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap(); assert!(!owner.status().core_ready);
    fixtures.cancel(); task.await.unwrap();
}

#[tokio::test]
async fn conflicting_bind_fails_before_tun_and_cleans_partial_listener_admission() {
    let _serial = LOCK.lock().await;
    let roots = Roots::new();
    let fixtures = CancellationToken::new(); let (dns, task, _) = dns_fixture(fixtures.clone()).await;
    let ports = ports().await; let occupied = TcpListener::bind(ports[1]).await.unwrap(); let (caller,peer) = pair();
    let cancel = CancellationToken::new(); let calls = Arc::new(AtomicUsize::new(0));
    let session = Registry::start_owned(plan(ports,dns,false,&roots).to_string(),Some(caller.try_clone().unwrap().into()),network(&cancel,calls.clone(),dns),None).unwrap();
    assert!(timeout(Duration::from_secs(5),session.ready()).await.unwrap().is_err());
    assert_eq!(session.snapshot().phase,Phase::Failed); assert!(!session.owned_runtime().unwrap().status().tun_ready);
    session.stop().await.unwrap(); assert_eq!(calls.load(Ordering::Relaxed),0);
    assert!(session.owned_runtime().unwrap().cleanup_confirmed());
    assert_eq!(session.snapshot().phase,Phase::Failed);
    assert!(TcpListener::bind(ports[0]).await.is_ok()); assert!(UdpSocket::bind(ports[2]).await.is_ok());
    caller.send(b"fd-after-failure").unwrap(); let mut bytes = [0;32]; assert_eq!(peer.recv(&mut bytes).await.unwrap(),16);
    assert!(unsafe { libc::fcntl(caller.as_raw_fd(),libc::F_GETFD) }>=0);
    drop(occupied); fixtures.cancel(); task.await.unwrap();
}

#[tokio::test]
async fn stop_during_actual_bootstrap_query_joins_load_without_late_ready_or_fd_loss() {
    let _serial = LOCK.lock().await;
    let roots = Roots::new();
    let upstream = UdpSocket::bind("127.0.0.1:0").await.unwrap(); let address = upstream.local_addr().unwrap();
    let (hit, waiting) = oneshot::channel();
    let fixture = tokio::spawn(async move { let mut bytes = [0u8;4096]; let (n,peer) = upstream.recv_from(&mut bytes).await.unwrap();
        hit.send(()).unwrap(); (upstream,peer,answer(&bytes[..n])) });
    let ports = ports().await; let mut value = plan(ports,address,false,&roots);
    value["engineYaml"] = format!("strict: true\nrules: ['MATCH,DIRECT']\ntun: {{enable: false, auto-route: false}}\ndns:\n  enable: true\n  use-system-hosts: false\n  nameserver: ['udp://pending-owned.invalid:53']\n  default-nameserver: ['{address}']\n").into();
    let (caller,peer) = pair(); let cancel = CancellationToken::new(); let calls = Arc::new(AtomicUsize::new(0));
    let context = network(&cancel,calls.clone(),address);
    let session = Registry::start_owned(value.to_string(),Some(caller.try_clone().unwrap().into()),context,None).unwrap();
    timeout(Duration::from_secs(5),waiting).await.unwrap().unwrap();
    assert_eq!(session.snapshot().phase,Phase::Loading);
    assert_eq!(timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap().phase,Phase::Stopped);
    let (upstream,remote,response) = fixture.await.unwrap(); let _ = upstream.send_to(&response,remote).await;
    assert!(session.ready().await.is_err()); assert!(!session.owned_runtime().unwrap().status().core_ready);
    for address in ports { assert!(TcpListener::bind(address).await.is_ok()); }
    caller.send(b"fd-during-load").unwrap(); let mut bytes = [0;32]; assert_eq!(peer.recv(&mut bytes).await.unwrap(),14);
    assert!(calls.load(Ordering::Relaxed)>0);
}

// UI-editable ports intentionally use the same 1024..49151 range as Kotlin;
// existing bootstrap fixtures may still request OS-assigned ephemeral ports.
async fn editable_ports(count: usize, excluded: &[u16]) -> Vec<TcpListener> {
    let mut bound = Vec::new();
    for port in 30000..=49151 {
        if !excluded.contains(&port) {
            if let Ok(listener) = TcpListener::bind((Ipv4Addr::LOCALHOST, port)).await { bound.push(listener); }
        }
        if bound.len() == count { return bound; }
    }
    panic!("not enough available UI-editable fixture ports");
}
async fn network_http(address: SocketAddr, method: &str, body: &str, secret: &str) -> Vec<u8> {
    let mut socket = TcpStream::connect(address).await.unwrap();
    socket.write_all(format!("{method} /aerove/network HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {secret}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).as_bytes()).await.unwrap();
    let mut response = Vec::new();
    let read = timeout(Duration::from_secs(5), socket.read_to_end(&mut response)).await.unwrap();
    // A fatal bind failure can close private HTTP as part of joined exit.
    if let Err(cause) = read { assert!(matches!(cause.kind(), io::ErrorKind::ConnectionReset | io::ErrorKind::UnexpectedEof)); }
    response
}
fn network_body(id: &str, epoch: &str, mixed: u16, http: u16, socks: u16, lan: bool, credentials: &[&str]) -> serde_json::Value {
    serde_json::json!({"requestId":id,"expectedCoreEpoch":epoch,"network":{
        "mixed-port":mixed,"port":http,"socks-port":socks,"allow-lan":lan,
        "bind-address":if lan {"0.0.0.0"} else {"127.0.0.1"},"authentication":credentials}})
}
async fn apply_network(address: SocketAddr, value: &serde_json::Value) -> String {
    let bytes = network_http(address,"POST",&value.to_string(),SECRET).await;
    assert!(bytes.starts_with(b"HTTP/1.1 200"),"{}",String::from_utf8_lossy(&bytes));
    let receipt = http_json(&bytes);
    assert_eq!(receipt.as_object().unwrap().len(),2);
    assert_eq!(receipt["requestId"],value["requestId"]);
    let epoch = receipt["coreEpoch"].as_str().unwrap();
    assert!(epoch.parse::<u64>().unwrap() > value["expectedCoreEpoch"].as_str().unwrap().parse::<u64>().unwrap());
    epoch.to_owned()
}
async fn proxy_request(address: SocketAddr, target: SocketAddr, authorization: Option<&str>) -> Vec<u8> {
    let mut stream = TcpStream::connect(address).await.unwrap();
    let header = authorization.map_or(String::new(),|value|format!("Proxy-Authorization: Basic {value}\r\n"));
    stream.write_all(format!("GET http://{target}/network-hot HTTP/1.1\r\nHost: {target}\r\n{header}Connection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut bytes = Vec::new(); timeout(Duration::from_secs(5),stream.read_to_end(&mut bytes)).await.unwrap().unwrap(); bytes
}

#[tokio::test]
async fn private_network_hot_apply_joins_user_ingress_and_preserves_actual_tun_api_and_identity() {
    let _serial = LOCK.lock().await; let roots = Roots::new();
    let fixtures = CancellationToken::new(); let (dns,dns_task,_) = dns_fixture(fixtures.clone()).await;
    let ports = ports().await; let held = editable_ports(3,&ports.map(|address|address.port())).await;
    let next: Vec<_> = held.iter().map(|listener|listener.local_addr().unwrap()).collect(); drop(held);
    let target = TcpListener::bind("127.0.0.1:0").await.unwrap(); let target_address = target.local_addr().unwrap();
    let target_cancel = fixtures.clone();
    let target_task = tokio::spawn(async move { loop { tokio::select! { biased;
        _ = target_cancel.cancelled() => break,
        accepted = target.accept() => {
            let (mut stream,_) = accepted.unwrap(); let mut request = Vec::new(); let mut byte = [0];
            while !request.ends_with(b"\r\n\r\n") { stream.read_exact(&mut byte).await.unwrap(); request.push(byte[0]); }
            stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok").await.unwrap();
        },
    } } });
    let udp = UdpSocket::bind("127.0.0.1:0").await.unwrap(); let udp_address = udp.local_addr().unwrap(); let udp_cancel = fixtures.clone();
    let udp_task = tokio::spawn(async move { let mut bytes = [0;4096]; loop { tokio::select! { biased;
        _ = udp_cancel.cancelled() => break,
        received = udp.recv_from(&mut bytes) => { let (n,peer) = received.unwrap(); udp.send_to(&bytes[..n],peer).await.unwrap(); },
    } } });
    let (caller,peer) = pair(); let cancel = CancellationToken::new();
    let session = Registry::start_owned(plan(ports,dns,false,&roots).to_string(),Some(caller.try_clone().unwrap().into()),
        network(&cancel,Arc::new(AtomicUsize::new(0)),dns),None).unwrap();
    timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();
    let owner = session.owned_runtime().unwrap(); let generation = session.generation(); let api = owner.status().api_address;
    let before = http_json(&http(ports[0],"/aerove/network").await);
    assert_eq!(before.as_object().unwrap().len(),3); assert_eq!(before["network"].as_object().unwrap().len(),6);
    assert_eq!(before["activePorts"],serde_json::json!([ports[1].port()]));
    peer.send(&udp_frame(udp_address,41003,b"before-hot")).await.unwrap(); assert_eq!(udp_reply(&peer,41003).await,b"before-hot");
    let connections = http_json(&http(ports[0],"/connections").await);
    let flow = connections["connections"].as_array().unwrap().iter().find(|connection|connection["metadata"]["sourcePort"]==41003).unwrap()["id"].clone();
    let mut accepted = TcpStream::connect(ports[1]).await.unwrap();
    accepted.write_all(&[5,1,0]).await.unwrap(); let mut greeting=[0;2]; accepted.read_exact(&mut greeting).await.unwrap(); assert_eq!(greeting,[5,0]);
    let mut udp_control=TcpStream::connect(ports[1]).await.unwrap();udp_control.write_all(&[5,1,0]).await.unwrap();udp_control.read_exact(&mut greeting).await.unwrap();assert_eq!(greeting,[5,0]);
    udp_control.write_all(&[5,3,0,1,127,0,0,1,0,0]).await.unwrap();let mut associated=[0;10];udp_control.read_exact(&mut associated).await.unwrap();assert_eq!(&associated[..4],&[5,0,0,1]);
    let relay=SocketAddr::from(([associated[4],associated[5],associated[6],associated[7]],u16::from_be_bytes([associated[8],associated[9]])));
    let user_udp=UdpSocket::bind("127.0.0.1:0").await.unwrap();let mut packet=vec![0,0,0,1,127,0,0,1];packet.extend_from_slice(&udp_address.port().to_be_bytes());packet.extend_from_slice(b"old-user-udp");user_udp.send_to(&packet,relay).await.unwrap();
    let mut udp_bytes=[0;4096];let (n,_)=timeout(Duration::from_secs(5),user_udp.recv_from(&mut udp_bytes)).await.unwrap().unwrap();assert_eq!(&udp_bytes[10..n],b"old-user-udp");
    let body = network_body("11111111-1111-4111-8111-111111111111",before["coreEpoch"].as_str().unwrap(),next[0].port(),next[1].port(),next[2].port(),false,&[]);
    let epoch = apply_network(ports[0],&body).await;
    let mut byte=[0]; assert_eq!(timeout(Duration::from_secs(5),accepted.read(&mut byte)).await.unwrap().unwrap(),0);
    assert_eq!(timeout(Duration::from_secs(5),udp_control.read(&mut byte)).await.unwrap().unwrap(),0);assert!(UdpSocket::bind(relay).await.is_ok());
    assert!(TcpListener::bind(ports[1]).await.is_ok());
    assert!(proxy_request(next[0],target_address,None).await.starts_with(b"HTTP/1.1 200"));
    assert!(proxy_request(next[1],target_address,None).await.starts_with(b"HTTP/1.1 200"));
    let non_localhost=SocketAddr::from(([127,0,0,2],next[0].port()));
    assert!(TcpStream::connect(non_localhost).await.is_err());
    let mut socks = TcpStream::connect(next[2]).await.unwrap(); socks.write_all(&[5,1,0]).await.unwrap(); socks.read_exact(&mut greeting).await.unwrap(); assert_eq!(greeting,[5,0]); drop(socks);
    let lan = network_body("22222222-2222-4222-8222-222222222222",&epoch,next[0].port(),next[1].port(),next[2].port(),true,&["alice:secret"]);
    let epoch = apply_network(ports[0],&lan).await;
    let actual = http_json(&http(ports[0],"/aerove/network").await);
    assert_eq!(actual["coreEpoch"],epoch); assert_eq!(actual["network"]["bind-address"],"0.0.0.0");
    assert_eq!(actual["network"]["allow-lan"],true); assert_eq!(actual["network"]["authEnabled"],true);
    let official=http_json(&http(ports[0],"/configs").await);
    assert_eq!(official["mixed-port"],next[0].port()); assert_eq!(official["allow-lan"],true); assert_eq!(official["bind-address"],"0.0.0.0");
    let mut sorted: Vec<_> = next.iter().map(|address|address.port()).collect(); sorted.sort_unstable(); assert_eq!(actual["activePorts"],serde_json::json!(sorted));
    assert!(!actual.to_string().contains("alice")); assert!(!actual.to_string().contains("secret"));
    assert!(proxy_request(non_localhost,target_address,None).await.starts_with(b"HTTP/1.1 407"));
    assert!(proxy_request(next[0],target_address,None).await.starts_with(b"HTTP/1.1 407"));
    assert!(proxy_request(next[0],target_address,Some("YWxpY2U6d3Jvbmc=")).await.starts_with(b"HTTP/1.1 407"));
    assert!(proxy_request(next[0],target_address,Some("YWxpY2U6c2VjcmV0")).await.starts_with(b"HTTP/1.1 200"));
    let mut socks=TcpStream::connect(next[2]).await.unwrap(); socks.write_all(&[5,1,0]).await.unwrap(); socks.read_exact(&mut greeting).await.unwrap(); assert_eq!(greeting,[5,255]); drop(socks);
    let mut authenticating=TcpStream::connect(next[0]).await.unwrap();authenticating.write_all(&[5,1,2]).await.unwrap();authenticating.read_exact(&mut greeting).await.unwrap();assert_eq!(greeting,[5,2]);
    let replay=network_body("55555555-5555-4555-8555-555555555555",&epoch,next[0].port(),next[1].port(),next[2].port(),true,&["alice:secret"]);
    let epoch=apply_network(ports[0],&replay).await;
    authenticating.write_all(b"\x01\x05alice\x06secret").await.unwrap();authenticating.read_exact(&mut greeting).await.unwrap();assert_eq!(greeting,[1,0]);
    assert_eq!(http_json(&http(ports[0],"/aerove/network").await)["coreEpoch"],epoch);
    for address in &next {assert!(TcpListener::bind(*address).await.is_err());}
    let changed=network_body("66666666-6666-4666-8666-666666666666",&epoch,next[0].port(),next[1].port(),next[2].port(),true,&["alice:newsecret"]);
    let epoch=apply_network(ports[0],&changed).await;
    assert_eq!(timeout(Duration::from_secs(5),authenticating.read(&mut byte)).await.unwrap().unwrap(),0);
    assert!(proxy_request(next[0],target_address,Some("YWxpY2U6c2VjcmV0")).await.starts_with(b"HTTP/1.1 407"));
    assert!(proxy_request(next[0],target_address,Some("YWxpY2U6bmV3c2VjcmV0")).await.starts_with(b"HTTP/1.1 200"));
    assert_eq!(session.generation(),generation); assert_eq!(owner.status().api_address,api); assert!(owner.status().tun_ready);
    assert!(unsafe {libc::fcntl(caller.as_raw_fd(),libc::F_GETFD)}>=0);
    peer.send(&udp_frame(udp_address,41003,b"after-hot")).await.unwrap(); assert_eq!(udp_reply(&peer,41003).await,b"after-hot");
    let connections=http_json(&http(ports[0],"/connections").await);
    assert!(connections["connections"].as_array().unwrap().iter().any(|connection|connection["id"]==flow));
    let dns_client=UdpSocket::bind("127.0.0.1:0").await.unwrap();dns_client.send_to(&question("after-hot.invalid"),ports[2]).await.unwrap();
    let (n,_)=timeout(Duration::from_secs(5),dns_client.recv_from(&mut udp_bytes)).await.unwrap().unwrap();assert_eq!(Message::from_bytes(&udp_bytes[..n]).unwrap().answers.len(),1);
    // Park/resume must use the committed listener/auth state, not the startup plan.
    owner.park().await.unwrap(); assert_eq!(http_json(&http(ports[0],"/aerove/network").await)["activePorts"],serde_json::json!([]));
    owner.resume(Some(caller.try_clone().unwrap().into())).await.unwrap();
    assert!(proxy_request(next[0],target_address,None).await.starts_with(b"HTTP/1.1 407"));
    assert!(proxy_request(next[0],target_address,Some("YWxpY2U6bmV3c2VjcmV0")).await.starts_with(b"HTTP/1.1 200"));
    assert_eq!(http_json(&http(ports[0],"/aerove/network").await)["coreEpoch"],epoch);
    timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap(); assert!(owner.cleanup_confirmed());
    for address in ports.into_iter().chain(next) { assert!(TcpListener::bind(address).await.is_ok()); }
    fixtures.cancel(); dns_task.await.unwrap(); target_task.await.unwrap(); udp_task.await.unwrap();
}

#[tokio::test]
async fn private_network_rejects_stale_and_invalid_without_side_effects_then_bind_conflict_fails_and_joins() {
    let _serial=LOCK.lock().await; let roots=Roots::new(); let fixtures=CancellationToken::new(); let (dns,task,_)=dns_fixture(fixtures.clone()).await;
    let ports=ports().await; let held=editable_ports(1,&ports.map(|address|address.port())).await; let occupied=held[0].local_addr().unwrap();
    let (caller,peer)=pair(); let cancel=CancellationToken::new();
    let session=Registry::start_owned(plan(ports,dns,false,&roots).to_string(),Some(caller.try_clone().unwrap().into()),network(&cancel,Arc::new(AtomicUsize::new(0)),dns),None).unwrap();
    timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap(); let owner=session.owned_runtime().unwrap();
    let before=http_json(&http(ports[0],"/aerove/network").await); let epoch=before["coreEpoch"].as_str().unwrap();
    let body=network_body("33333333-3333-4333-8333-333333333333",epoch,occupied.port(),0,0,false,&[]);
    assert!(network_http(ports[0],"POST",&body.to_string(),"wrong-private-secret").await.starts_with(b"HTTP/1.1 401"));
    let mut stale=body.clone(); stale["expectedCoreEpoch"]=(epoch.parse::<u64>().unwrap()+1).to_string().into();
    assert!(network_http(ports[0],"POST",&stale.to_string(),SECRET).await.starts_with(b"HTTP/1.1 409"));
    for invalid in [
        {let mut value=body.clone();value["network"]["authentication"]=serde_json::json!(["missing-colon"]);value},
        {let mut value=body.clone();value["network"]["skip-auth-prefixes"]=serde_json::json!(["127.0.0.1/32"]);value},
        {let mut value=body.clone();value["expectedCoreEpoch"]="01".into();value},
        {let mut value=body.clone();value["network"]["mixed-port"]=65535.into();value},
        {let mut value=body.clone();value["network"]["mixed-port"]=ports[0].port().into();value},
    ] { assert!(network_http(ports[0],"POST",&invalid.to_string(),SECRET).await.starts_with(b"HTTP/1.1 400")); }
    assert_eq!(before,http_json(&http(ports[0],"/aerove/network").await)); assert!(owner.status().tun_ready);
    assert!(TcpListener::bind(ports[1]).await.is_err());
    let response=network_http(ports[0],"POST",&body.to_string(),SECRET).await;
    assert!(response.starts_with(b"HTTP/1.1 503"),"{}",String::from_utf8_lossy(&response));
    assert_eq!(http_json(&response)["error"],"userListenerBindFailed");
    assert!(timeout(Duration::from_secs(5),session.exited()).await.unwrap().is_err());
    assert_eq!(session.snapshot().phase,Phase::Failed); assert!(owner.cleanup_confirmed());
    assert!(owner.status().api_address.is_none()); assert!(!owner.status().tun_ready);
    for address in ports {assert!(TcpListener::bind(address).await.is_ok());} assert!(UdpSocket::bind(ports[2]).await.is_ok());
    assert!(TcpListener::bind(occupied).await.is_err()); drop(held); assert!(TcpListener::bind(occupied).await.is_ok());
    caller.send(b"caller-after-hot-bind-failure").unwrap(); let mut bytes=[0;64]; assert_eq!(timeout(Duration::from_secs(5),peer.recv(&mut bytes)).await.unwrap().unwrap(),29);
    assert!(unsafe {libc::fcntl(caller.as_raw_fd(),libc::F_GETFD)}>=0);
    let mut recovery=plan(ports,dns,false,&roots);
    recovery["inbounds"]["configYaml"]=format!("mixed-port: {}\nbind-address: 127.0.0.1\ndns-listen: '{}'\n",occupied.port(),ports[2]).into();
    let next_cancel=CancellationToken::new();
    let next=Registry::start_owned(recovery.to_string(),Some(caller.try_clone().unwrap().into()),network(&next_cancel,Arc::new(AtomicUsize::new(0)),dns),None).unwrap();
    assert!(timeout(Duration::from_secs(5),next.ready()).await.unwrap().unwrap().tun_ready);assert!(next.generation()>session.generation());
    let mut socks=TcpStream::connect(occupied).await.unwrap();socks.write_all(&[5,1,0]).await.unwrap();let mut greeting=[0;2];socks.read_exact(&mut greeting).await.unwrap();assert_eq!(greeting,[5,0]);drop(socks);
    timeout(Duration::from_secs(5),next.stop()).await.unwrap().unwrap();assert!(next.owned_runtime().unwrap().cleanup_confirmed());
    fixtures.cancel();task.await.unwrap();
}
