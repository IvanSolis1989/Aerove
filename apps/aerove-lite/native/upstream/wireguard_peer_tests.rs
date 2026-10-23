use super::*;
use boringtun::{
    noise::{Tunn, TunnResult},
    x25519::{PublicKey, StaticSecret},
};
use crate::{
    proxy::{datagram::UdpPacket, wg::{Handler, HandlerOptions}, OutboundHandler},
    session::{Session, SocksAddr},
};
use futures::{SinkExt, StreamExt};
use tokio::net::UdpSocket;

const CLIENT_IP: [u8; 4] = [10, 66, 0, 1];
const TARGET_IP: [u8; 4] = [10, 66, 0, 2];
const TARGET_PORT: u16 = 7301;
const LIMIT: Duration = Duration::from_secs(8);
const PAYLOADS: [&[u8]; 2] = [b"aerove-real-wireguard-peer", b"\x00\xff\x80second-datagram\x00"];

#[derive(Default)]
struct Receipt {
    initiations: usize,
    responses: usize,
    plaintext: Vec<Vec<u8>>,
}

fn invalid(message: impl Into<String>) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message.into())
}

fn hex_key(bytes: &[u8; 32]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn handler(address: SocketAddr) -> Handler {
    let peer_private = StaticSecret::from([0x22; 32]);
    Handler::new(HandlerOptions {
        name: "loopback-wireguard".into(),
        common_opts: Default::default(),
        server: address.ip().to_string(),
        port: address.port(),
        ip: CLIENT_IP.into(),
        ipv6: None,
        private_key: hex_key(&[0x11; 32]),
        public_key: hex_key(PublicKey::from(&peer_private).as_bytes()),
        pre_shared_key: Some(hex_key(&[0x33; 32])),
        remote_dns_resolve: false,
        dns: None,
        mtu: Some(1420),
        udp: true,
        allowed_ips: Some(vec!["10.66.0.2/32".into()]),
        reserved_bits: None,
    })
}

fn echo_packet(packet: &[u8], receipt: &mut Receipt) -> io::Result<Vec<u8>> {
    if packet.len() < 28 || packet[0] != 0x45 || packet[9] != 17 {
        return Err(invalid("expected an IPv4 UDP packet without IP options"));
    }
    let length = u16::from_be_bytes([packet[2], packet[3]]) as usize;
    let udp_length = u16::from_be_bytes([packet[24], packet[25]]) as usize;
    if length != packet.len() || udp_length != length - 20
        || packet[12..16] != CLIENT_IP || packet[16..20] != TARGET_IP
        || packet[22..24] != TARGET_PORT.to_be_bytes()
        || packet[6] & 0x3f != 0 || packet[7] != 0
    {
        return Err(invalid("unexpected plaintext endpoint, length or fragmentation"));
    }
    let expected = PAYLOADS.get(receipt.plaintext.len())
        .ok_or_else(|| invalid("unexpected extra plaintext datagram"))?;
    if &packet[28..] != *expected {
        return Err(invalid("peer decrypted different application bytes"));
    }
    receipt.plaintext.push(packet[28..].to_vec());
    let mut reply = packet.to_vec();
    reply[12..16].copy_from_slice(&TARGET_IP);
    reply[16..20].copy_from_slice(&CLIENT_IP);
    reply[20..22].copy_from_slice(&packet[22..24]);
    reply[22..24].copy_from_slice(&packet[20..22]);
    // Swapping both endpoint pairs preserves the IPv4 and UDP checksum sums.
    Ok(reply)
}

async fn peer(socket: UdpSocket, cancel: CancellationToken) -> io::Result<Receipt> {
    let client_private = StaticSecret::from([0x11; 32]);
    let mut tunnel = Tunn::new(
        StaticSecret::from([0x22; 32]),
        PublicKey::from(&client_private),
        Some([0x33; 32]),
        None,
        1,
        None,
    );
    let mut receipt = Receipt::default();
    let mut network = vec![0u8; 65535];
    let mut output = vec![0u8; 65535];
    let mut encrypted = vec![0u8; 65535];
    let mut client = None;
    loop {
        let (length, address) = tokio::select! {
            biased;
            _ = cancel.cancelled() => return Ok(receipt),
            result = socket.recv_from(&mut network) => result?,
        };
        if !address.ip().is_loopback() || client.is_some_and(|old| old != address) {
            return Err(invalid("WireGuard underlay changed its loopback endpoint"));
        }
        client = Some(address);
        if length < 4 {
            return Err(invalid("truncated WireGuard underlay packet"));
        }
        if network[..4] == [1, 0, 0, 0] {
            receipt.initiations += 1;
        }
        for payload in PAYLOADS {
            if network[..length].windows(payload.len()).any(|window| window == payload) {
                return Err(invalid("application plaintext leaked onto UDP underlay"));
            }
        }
        let mut input = &network[..length];
        let mut drained = false;
        for _ in 0..32 {
            match tunnel.decapsulate(Some(address.ip()), input, &mut output) {
                TunnResult::Done => { drained = true; break; }
                TunnResult::Err(error) => return Err(invalid(format!("Noise receive: {error:?}"))),
                TunnResult::WriteToNetwork(packet) => {
                    if packet[..4] == [2, 0, 0, 0] {
                        receipt.responses += 1;
                    }
                    socket.send_to(packet, address).await?;
                }
                TunnResult::WriteToTunnelV4(packet, source) => {
                    if source.octets() != CLIENT_IP {
                        return Err(invalid("unexpected authenticated plaintext source"));
                    }
                    let reply = echo_packet(packet, &mut receipt)?;
                    match tunnel.encapsulate(&reply, &mut encrypted) {
                        TunnResult::WriteToNetwork(packet) => {
                            socket.send_to(packet, address).await?;
                        }
                        other => return Err(invalid(format!("Noise echo: {other:?}"))),
                    }
                    drained = true;
                    break;
                }
                TunnResult::WriteToTunnelV6(..) => return Err(invalid("unexpected IPv6 plaintext")),
            }
            input = &[];
        }
        if !drained {
            return Err(invalid("Noise output did not drain within its packet bound"));
        }
    }
}

pub(super) async fn run(count: Arc<AtomicUsize>, refused: Arc<AtomicBool>) {
    let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let address = socket.local_addr().unwrap();
    let target: SocketAddr = (std::net::Ipv4Addr::from(TARGET_IP), TARGET_PORT).into();
    let session = Session { destination: target.into(), ..Default::default() };
    let (components, _directory) = components(None, "DIRECT").await;
    let resolver = components.dns_resolver.clone();

    let denied_handler = handler(address);
    refused.store(true, Ordering::SeqCst);
    let before = count.load(Ordering::SeqCst);
    let denied = tokio::time::timeout(LIMIT, denied_handler.connect_datagram(&session, resolver.clone())).await;
    refused.store(false, Ordering::SeqCst);
    assert!(denied.unwrap().is_err());
    assert_eq!(count.load(Ordering::SeqCst), before + 1);
    let mut quiet = [0u8; 256];
    assert!(tokio::time::timeout(Duration::from_millis(80), socket.recv_from(&mut quiet)).await.is_err());
    drop(denied_handler);

    let cancel = CancellationToken::new();
    let peer_cancel = cancel.clone();
    let mut task = tokio::spawn(async move {
        tokio::time::timeout(LIMIT + Duration::from_secs(2), peer(socket, peer_cancel)).await
    });
    let live_handler = handler(address);
    let before = count.load(Ordering::SeqCst);
    let traffic = tokio::time::timeout(LIMIT, async {
        let mut datagram = live_handler.connect_datagram(&session, resolver).await?;
        for payload in PAYLOADS {
            datagram.send(UdpPacket::new(payload.to_vec(), SocksAddr::any_ipv4(), target.into())).await?;
            let reply = datagram.next().await.ok_or_else(|| invalid("WireGuard datagram closed"))?;
            if reply.data != payload || reply.src_addr.to_string() != target.to_string() {
                return Err(invalid("application received incorrect WireGuard echo"));
            }
        }
        Ok::<_, io::Error>(())
    }).await;
    cancel.cancel();
    let peer_result = tokio::time::timeout(Duration::from_secs(2), &mut task).await;
    if peer_result.is_err() {
        task.abort();
        let _ = task.await;
    }
    drop(live_handler);
    tokio::time::timeout(LIMIT, components.stop_all()).await
        .expect("WireGuard fixture components did not stop");
    traffic.expect("WireGuard plaintext exchange timed out").unwrap();
    let receipt = peer_result.expect("loopback peer did not cancel and join").unwrap()
        .expect("loopback peer exceeded its own deadline").unwrap();
    assert_eq!(count.load(Ordering::SeqCst), before + 1);
    assert!(receipt.initiations >= 1);
    assert!(receipt.responses >= 1);
    assert_eq!(receipt.plaintext, PAYLOADS.iter().map(|payload| payload.to_vec()).collect::<Vec<_>>());
}
