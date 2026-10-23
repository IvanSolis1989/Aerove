#![cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
use aerove_meow_core::{network_context::NetworkContext, physical_dns::{PhysicalResolver, selected_servers}, runtime_plan::PhysicalDns};
use hickory_proto::{op::{Message, MessageType, OpCode}, rr::{RData, Record, RecordType, rdata::{A, AAAA}}};
use meow_common::{HostResolver, socket_protect::{self, SocketProtector}};
use std::{collections::BTreeMap, io, net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr}, os::fd::RawFd, sync::{Arc, atomic::{AtomicUsize, Ordering}}, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, UdpSocket}, sync::oneshot, time::timeout};
use tokio_util::sync::CancellationToken;

struct Protector { calls: Arc<AtomicUsize>, deny: bool }
impl SocketProtector for Protector {
    fn protect(&self, fd: RawFd) -> io::Result<()> {
        assert!(unsafe { libc::fcntl(fd, libc::F_GETFD) } >= 0);
        let mut local: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
        let mut length = std::mem::size_of_val(&local) as libc::socklen_t;
        assert_eq!(unsafe { libc::getsockname(fd, (&mut local as *mut libc::sockaddr_storage).cast(), &mut length) }, 0);
        let port = match i32::from(local.ss_family) {
            libc::AF_INET => unsafe { (*(&local as *const libc::sockaddr_storage).cast::<libc::sockaddr_in>()).sin_port },
            libc::AF_INET6 => unsafe { (*(&local as *const libc::sockaddr_storage).cast::<libc::sockaddr_in6>()).sin6_port },
            _ => panic!("unexpected socket family"),
        };
        assert_eq!(port, 0, "physical DNS protect must precede bind");
        self.calls.fetch_add(1, Ordering::Relaxed);
        if self.deny { Err(io::Error::from(io::ErrorKind::PermissionDenied)) } else { Ok(()) }
    }
}
fn context(cancel: &CancellationToken, calls: Arc<AtomicUsize>, deny: bool) -> NetworkContext {
    NetworkContext::protected(Arc::new(Protector { calls, deny }), None, cancel.clone())
}
fn response(request: &Message) -> Vec<u8> {
    let query = &request.queries[0];
    let mut response = Message::new(request.metadata.id, MessageType::Response, OpCode::Query);
    response.add_queries(request.queries.iter().cloned());
    response.metadata.recursion_available = true;
    response.metadata.recursion_desired = true;
    let data = match query.query_type {
        RecordType::A => RData::A(A(Ipv4Addr::new(127, 0, 0, 9))),
        RecordType::AAAA => RData::AAAA(AAAA(Ipv6Addr::LOCALHOST)),
        _ => panic!("unexpected DNS question"),
    };
    response.add_answer(Record::from_rdata(query.name.clone(), 60, data));
    response.to_bytes().unwrap()
}

#[tokio::test]
async fn physical_dns_ipv4_and_ipv6_sockets_are_protected_cached_and_cancelled() {
    for bind in ["127.0.0.1:0", "[::1]:0"] {
        let server = UdpSocket::bind(bind).await.unwrap();
        let address = server.local_addr().unwrap();
        let fixture = tokio::spawn(async move {
            let mut bytes = [0u8; 4096];
            for _ in 0..2 {
                let (count, peer) = server.recv_from(&mut bytes).await.unwrap();
                let request = Message::from_bytes(&bytes[..count]).unwrap();
                server.send_to(&response(&request), peer).await.unwrap();
            }
            server
        });
        let cancel = CancellationToken::new();
        let calls = Arc::new(AtomicUsize::new(0));
        let resolver = PhysicalResolver::from_servers(vec![address], context(&cancel, calls.clone(), false), true).unwrap();
        let network = resolver.network();
        let answer = timeout(Duration::from_secs(2), network.scope(socket_protect::resolve_host_all("physical.invalid", 443))).await.unwrap().unwrap();
        assert!(answer.contains(&SocketAddr::from(([127, 0, 0, 9], 443))));
        assert!(answer.contains(&SocketAddr::from((Ipv6Addr::LOCALHOST, 443))));
        let server = timeout(Duration::from_secs(2), fixture).await.unwrap().unwrap();
        assert_eq!(calls.load(Ordering::Relaxed), 2);
        let cached = network.scope(socket_protect::resolve_host_all("physical.invalid", 443)).await.unwrap();
        assert_eq!(answer, cached);
        assert_eq!(calls.load(Ordering::Relaxed), 2);
        assert!(resolver.resolve_all_local("physical.invalid").is_some());
        cancel.cancel();
        assert!(resolver.resolve_all_local("physical.invalid").is_none());
        assert_eq!(resolver.resolve_all("physical.invalid").await.unwrap_err().kind(), io::ErrorKind::Interrupted);
        assert!(network.scope(socket_protect::resolve_host_all("physical.invalid", 443)).await.is_err());
        let mut bytes = [0u8; 4096];
        assert!(timeout(Duration::from_millis(50), server.recv_from(&mut bytes)).await.is_err());
    }
}

#[tokio::test]
async fn physical_dns_denied_protector_sends_no_datagram_and_has_no_fallback() {
    let server = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let cancel = CancellationToken::new();
    let calls = Arc::new(AtomicUsize::new(0));
    let resolver = PhysicalResolver::from_servers(vec![server.local_addr().unwrap()], context(&cancel, calls.clone(), true), false).unwrap();
    assert!(timeout(Duration::from_secs(2), resolver.resolve_all("denied.invalid")).await.unwrap().is_err());
    assert!(calls.load(Ordering::Relaxed) > 0);
    let mut bytes = [0u8; 4096];
    assert!(timeout(Duration::from_millis(50), server.recv_from(&mut bytes)).await.is_err());
}

#[tokio::test]
async fn physical_dns_truncated_udp_retries_tcp_under_the_same_captured_protector() {
    let tcp = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = tcp.local_addr().unwrap();
    let udp = UdpSocket::bind(address).await.unwrap();
    let fixture = tokio::spawn(async move {
        let mut bytes = [0u8; 4096];
        let (count, peer) = udp.recv_from(&mut bytes).await.unwrap();
        let request = Message::from_bytes(&bytes[..count]).unwrap();
        let mut truncated = Message::new(request.metadata.id, MessageType::Response, OpCode::Query);
        truncated.add_queries(request.queries.iter().cloned());
        truncated.metadata.truncation = true;
        udp.send_to(&truncated.to_bytes().unwrap(), peer).await.unwrap();
        let (mut stream, _) = tcp.accept().await.unwrap();
        let length = stream.read_u16().await.unwrap();
        let mut bytes = vec![0u8; usize::from(length)];
        stream.read_exact(&mut bytes).await.unwrap();
        let answer = response(&Message::from_bytes(&bytes).unwrap());
        stream.write_u16(u16::try_from(answer.len()).unwrap()).await.unwrap();
        stream.write_all(&answer).await.unwrap();
        let mut last = [0u8; 1];
        assert_eq!(stream.read(&mut last).await.unwrap(), 0, "one-off DNS fallback must close");
    });
    let cancel = CancellationToken::new();
    let calls = Arc::new(AtomicUsize::new(0));
    let resolver = PhysicalResolver::from_servers(vec![address], context(&cancel, calls.clone(), false), false).unwrap();
    let answer = timeout(Duration::from_secs(2), resolver.resolve_all("truncated.invalid")).await.unwrap().unwrap();
    assert_eq!(answer, vec![IpAddr::V4(Ipv4Addr::new(127, 0, 0, 9))]);
    timeout(Duration::from_secs(2), fixture).await.unwrap().unwrap();
    assert_eq!(calls.load(Ordering::Relaxed), 2, "both UDP and TCP protect before connect");
}

#[tokio::test]
async fn physical_dns_cancel_drops_pending_query_and_does_not_reuse_successor_context() {
    let server = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let cancel = CancellationToken::new();
    let calls = Arc::new(AtomicUsize::new(0));
    let resolver = PhysicalResolver::from_servers(vec![server.local_addr().unwrap()], context(&cancel, calls.clone(), false), false).unwrap();
    let (sent, received) = oneshot::channel();
    let fixture = tokio::spawn(async move {
        let mut bytes = [0u8; 4096];
        let (count, peer) = server.recv_from(&mut bytes).await.unwrap();
        let request = Message::from_bytes(&bytes[..count]).unwrap();
        sent.send(()).unwrap();
        (server, peer, response(&request))
    });
    let lookup = tokio::spawn({ let resolver = resolver.clone(); async move { resolver.resolve_all("pending.invalid").await } });
    timeout(Duration::from_secs(2), received).await.unwrap().unwrap();
    cancel.cancel();
    assert_eq!(timeout(Duration::from_secs(1), lookup).await.unwrap().unwrap().unwrap_err().kind(), io::ErrorKind::Interrupted);
    let (server, peer, answer) = fixture.await.unwrap();
    server.send_to(&answer, peer).await.unwrap();
    let next = CancellationToken::new();
    let next_calls = Arc::new(AtomicUsize::new(0));
    let next_context = context(&next, next_calls.clone(), false);
    assert!(next_context.scope(resolver.resolve_all("pending.invalid")).await.is_err());
    assert_eq!(next_calls.load(Ordering::Relaxed), 0);
    assert_eq!(calls.load(Ordering::Relaxed), 1);
}

#[test]
fn physical_dns_uses_selected_interface_and_preserves_numeric_ipv6_scope() {
    let snapshot = PhysicalDns { default_interface: Some("wlan0".into()), interfaces: BTreeMap::from([
        ("wlan0".into(), vec!["192.0.2.53".into(), "fe80::53%7".into()]),
        ("other".into(), vec!["198.51.100.53".into()]),
    ]) };
    let selected = selected_servers(&snapshot).unwrap();
    assert_eq!(selected, vec!["192.0.2.53:53".parse::<SocketAddr>().unwrap(), "[fe80::53%7]:53".parse().unwrap()]);
    assert_eq!(selected[1].ip(), IpAddr::V6("fe80::53".parse().unwrap()));
    let mut missing = snapshot;
    missing.default_interface = None;
    assert_eq!(selected_servers(&missing).unwrap_err().kind(), io::ErrorKind::NotFound);
    missing.default_interface = Some("missing".into());
    assert_eq!(selected_servers(&missing).unwrap_err().kind(), io::ErrorKind::NotFound);
    missing.default_interface = Some("wlan0".into());
    missing.interfaces.insert("wlan0".into(), vec![]);
    assert_eq!(selected_servers(&missing).unwrap_err().kind(), io::ErrorKind::NotFound);
    missing.interfaces.insert("wlan0".into(), vec!["fe80::53%other".into()]);
    assert_eq!(selected_servers(&missing).unwrap_err().kind(), io::ErrorKind::InvalidInput);
    missing.interfaces.insert("wlan0".into(), vec!["fe80::53%0".into()]);
    assert_eq!(selected_servers(&missing).unwrap_err().kind(), io::ErrorKind::InvalidInput);
}
