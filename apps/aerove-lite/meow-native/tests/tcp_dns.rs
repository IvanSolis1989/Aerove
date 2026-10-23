#![cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
use aerove_meow_core::{tcp_dns::{serve, Limits}, runtime_plan::TunPlan, network_context::NetworkContext};
use hickory_proto::{op::Message, rr::{RData, rdata::A}, serialize::binary::BinDecodable};
use meow_common::socket_protect::SocketProtector;
use meow_tunnel::Tunnel;
use std::{io, net::Ipv4Addr, os::fd::RawFd, sync::{Arc, atomic::{AtomicUsize, Ordering}}, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream, UdpSocket}, time::timeout};
use tokio_util::sync::CancellationToken;

fn query(id: u16) -> Vec<u8> {
    let mut bytes = vec![0,0,1,0,0,1,0,0,0,0,0,0,7];
    bytes[..2].copy_from_slice(&id.to_be_bytes());
    bytes.extend_from_slice(b"tcp-dns"); bytes.push(7); bytes.extend_from_slice(b"invalid");
    bytes.extend_from_slice(&[0,0,1,0,1]); bytes
}
fn frame(id: u16) -> Vec<u8> {
    let query = query(id); let mut bytes = (query.len() as u16).to_be_bytes().to_vec();
    bytes.extend(query); bytes
}
async fn core(ip: &str, upstream: &str) -> (Tunnel, meow_dns::ResolverSlot) { core_with_transport(ip,upstream,None).await }
async fn core_with_transport(ip: &str, upstream: &str, transport: Option<meow_common::transport_tasks::TransportScope>) -> (Tunnel, meow_dns::ResolverSlot) {
    let hosts = if ip.is_empty() { String::new() } else { format!("hosts:\n  tcp-dns.invalid: {ip}\n") };
    let yaml = format!(
        "strict: true\nrules: ['MATCH,DIRECT']\n{hosts}dns:\n  enable: true\n  use-hosts: true\n  use-system-hosts: false\n  enhanced-mode: redir-host\n  nameserver: ['{upstream}']\n  default-nameserver: ['{upstream}']\n"
    );
    let work = Box::pin(meow_config::load_config_from_str(&yaml));
    let config = match transport {
        Some(scope) => meow_config::owned_load::with_dns_transport_scope(scope,work).await,
        None => work.await,
    }.unwrap();
    let slot = config.dns.resolver_slot.clone();
    (Tunnel::new_with_slot(slot.clone()), slot)
}
async fn pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
    let (server, _) = listener.accept().await.unwrap(); (client, server)
}
async fn answer(client: &mut TcpStream, id: u16, ip: Ipv4Addr) {
    timeout(Duration::from_secs(2), async {
        let length = client.read_u16().await.unwrap(); assert!(length >= 12);
        let mut bytes = vec![0; usize::from(length)]; client.read_exact(&mut bytes).await.unwrap();
        let response = Message::from_bytes(&bytes).unwrap(); assert_eq!(response.metadata.id, id);
        assert!(response.answers.iter().any(|record| record.data == RData::A(A(ip))));
    }).await.unwrap();
}

#[tokio::test]
async fn real_core_fragmented_pipeline_and_live_resolver_reload() {
    let (tunnel, slot) = core("192.0.2.7", "127.0.0.1:9").await;
    let (mut client, server) = pair().await;
    let task = tokio::spawn(serve(server, tunnel, CancellationToken::new(), Limits::default()));
    let first = frame(1); client.write_all(&first[..1]).await.unwrap();
    client.write_all(&first[1..5]).await.unwrap(); client.write_all(&first[5..]).await.unwrap();
    let mut pipeline = frame(2); pipeline.extend(frame(3)); client.write_all(&pipeline).await.unwrap();
    for id in 1..=3 { answer(&mut client, id, Ipv4Addr::new(192,0,2,7)).await; }
    let (_, next) = core("192.0.2.8", "127.0.0.1:9").await; *slot.write() = next.read().clone();
    client.write_all(&frame(4)).await.unwrap(); answer(&mut client, 4, Ipv4Addr::new(192,0,2,8)).await;
    client.shutdown().await.unwrap(); timeout(Duration::from_secs(2), task).await.unwrap().unwrap().unwrap();
}

#[tokio::test]
async fn clean_eof_partial_prefix_partial_body_and_invalid_length() {
    for (bytes, kind) in [(vec![], None), (vec![0], Some(io::ErrorKind::UnexpectedEof)),
        (vec![0,12,0], Some(io::ErrorKind::UnexpectedEof)), (vec![0,0], Some(io::ErrorKind::InvalidData)),
        (vec![0,11], Some(io::ErrorKind::InvalidData))] {
        let (tunnel, _) = core("192.0.2.7", "127.0.0.1:9").await;
        let (mut client, server) = pair().await;
        let task = tokio::spawn(serve(server, tunnel, CancellationToken::new(), Limits::default()));
        client.write_all(&bytes).await.unwrap(); client.shutdown().await.unwrap();
        let result = timeout(Duration::from_secs(2), task).await.unwrap().unwrap();
        match kind { None => result.unwrap(), Some(kind) => assert_eq!(result.unwrap_err().kind(), kind) }
    }
}

#[tokio::test]
async fn maximum_wire_length_is_bounded_and_core_rejects_malformed_dns() {
    let (tunnel, _) = core("192.0.2.7", "127.0.0.1:9").await;
    let (mut client, server) = pair().await;
    let task = tokio::spawn(serve(server, tunnel, CancellationToken::new(), Limits::default()));
    // A response (QR=1) is invalid as a query, even with the largest wire frame.
    let mut body = vec![0; usize::from(u16::MAX)]; body[2] = 0x80;
    client.write_u16(u16::MAX).await.unwrap(); client.write_all(&body).await.unwrap();
    assert_eq!(timeout(Duration::from_secs(2), task).await.unwrap().unwrap().unwrap_err().kind(), io::ErrorKind::InvalidData);
}

#[tokio::test]
async fn stalled_fragment_read_timeout_and_cancel_are_joinable() {
    for cancelled in [false, true] {
        let (tunnel, _) = core("192.0.2.7", "127.0.0.1:9").await;
        let (mut client, server) = pair().await; let cancel = CancellationToken::new();
        let limits = Limits { read: Duration::from_millis(40), ..Limits::default() };
        let task = tokio::spawn(serve(server, tunnel, cancel.clone(), limits));
        client.write_all(&[0]).await.unwrap(); if cancelled { cancel.cancel(); }
        let result = timeout(Duration::from_secs(2), task).await.unwrap().unwrap();
        if cancelled { result.unwrap(); } else { assert_eq!(result.unwrap_err().kind(), io::ErrorKind::TimedOut); }
        assert_eq!(timeout(Duration::from_secs(2), client.read(&mut [0])).await.unwrap().unwrap(), 0);
    }
}

struct Protector(Arc<AtomicUsize>);
impl SocketProtector for Protector {
    fn protect(&self, fd: RawFd) -> io::Result<()> {
        assert!(unsafe { libc::fcntl(fd, libc::F_GETFD) } >= 0);
        self.0.fetch_add(1, Ordering::Relaxed); Ok(())
    }
}

#[tokio::test]
async fn real_upstream_uses_same_network_scope_and_cancel_joins_inflight_exchange() {
    let upstream = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let (mut client, server) = pair().await; let cancel = CancellationToken::new();
    let calls = Arc::new(AtomicUsize::new(0));
    let context = NetworkContext::protected(Arc::new(Protector(calls.clone())), None, cancel.clone());
    let physical = aerove_meow_core::physical_dns::PhysicalResolver::from_servers(vec![upstream.local_addr().unwrap()],context,false).unwrap();
    let context = physical.network();
    let transport = meow_config::owned_load::LoadContext::dns_transport_scope(&context).unwrap();
    let (tunnel, _) = core_with_transport("", &upstream.local_addr().unwrap().to_string(),Some(transport)).await;
    let stop = cancel.clone();
    let task = tokio::spawn(async move { context.scope(serve(server, tunnel, stop, Limits::default())).await });
    client.write_all(&frame(11)).await.unwrap();
    let mut bytes = [0;4096];
    let (length, _) = timeout(Duration::from_secs(2), upstream.recv_from(&mut bytes)).await.unwrap().unwrap();
    assert_eq!(Message::from_bytes(&bytes[..length]).unwrap().queries.len(), 1);
    assert!(calls.load(Ordering::Relaxed) > 0); cancel.cancel();
    timeout(Duration::from_secs(2), task).await.unwrap().unwrap().unwrap();
    assert_eq!(timeout(Duration::from_secs(2), client.read(&mut [0])).await.unwrap().unwrap(), 0);
    timeout(Duration::from_secs(2),physical.retire()).await.unwrap().unwrap();
    assert!(physical.dns_owner().cleanup_confirmed());
}

#[tokio::test]
async fn stalled_real_upstream_exchange_has_bounded_timeout() {
    let upstream = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let (tunnel, _) = core("", &upstream.local_addr().unwrap().to_string()).await;
    let (mut client, server) = pair().await;
    let limits = Limits { exchange:Duration::from_millis(100), ..Limits::default() };
    let task = tokio::spawn(serve(server, tunnel, CancellationToken::new(), limits));
    client.write_all(&frame(12)).await.unwrap();
    let mut bytes = [0;4096];
    timeout(Duration::from_secs(2), upstream.recv_from(&mut bytes)).await.unwrap().unwrap();
    assert_eq!(timeout(Duration::from_secs(2), task).await.unwrap().unwrap().unwrap_err().kind(), io::ErrorKind::TimedOut);
}

#[tokio::test]
async fn blocked_response_write_is_bounded_and_cancellable() {
    for cancelled in [false, true] {
        let (tunnel, _) = core("192.0.2.7", "127.0.0.1:9").await;
        // Deliberately tiny bounded transport: request can be consumed, response
        // cannot complete until the client reads. The DNS answer is still real.
        let (mut client, server) = tokio::io::duplex(1); let cancel = CancellationToken::new();
        let limits = Limits { write:Duration::from_millis(50), ..Limits::default() };
        let task = tokio::spawn(serve(server, tunnel, cancel.clone(), limits));
        timeout(Duration::from_secs(2), client.write_all(&frame(13))).await.unwrap().unwrap();
        if cancelled {
            let mut prefix = [0];
            timeout(Duration::from_secs(2), client.read_exact(&mut prefix)).await.unwrap().unwrap();
            cancel.cancel();
        }
        let result = timeout(Duration::from_secs(2), task).await.unwrap().unwrap();
        if cancelled { result.unwrap(); } else { assert_eq!(result.unwrap_err().kind(), io::ErrorKind::TimedOut); }
    }
}

#[test]
fn typed_selective_targets_disabled_and_non_dns_remain_normal_tcp() {
    let mut tun = TunPlan { mtu:1500, ipv6:true, inet4_address:"198.18.0.1/30".into(),
        inet6_address:Some("fdfe::1/126".into()), dns_hijack:vec![], auto_route:false };
    assert!(!tun.hijacks("192.0.2.1:53".parse().unwrap()));
    tun.dns_hijack = vec!["192.0.2.1:53".into(), "[2001:db8::1]:53".into()];
    for target in ["192.0.2.1:53", "[2001:db8::1]:53"] { assert!(tun.hijacks(target.parse().unwrap())); }
    for target in ["192.0.2.2:53", "192.0.2.1:443", "[2001:db8::2]:53"] { assert!(!tun.hijacks(target.parse().unwrap())); }
    tun.dns_hijack = vec!["any:53".into()];
    assert!(tun.hijacks("[2001:db8::2]:53".parse().unwrap()));
    assert!(!tun.hijacks("192.0.2.1:853".parse().unwrap()));
}
