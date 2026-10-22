//! Actual stack/framed-FD regressions; a datagram FD is not a TUN-readiness test.
use std::{os::fd::IntoRawFd, time::Duration};

use futures::{SinkExt, StreamExt};

fn checksum(bytes: &[u8]) -> u16 {
    let mut sum = 0u32;
    for pair in bytes.chunks(2) {
        sum += u16::from_be_bytes([pair[0], *pair.get(1).unwrap_or(&0)]) as u32;
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

fn ipv4_packet(length: usize, protocol: u8) -> Vec<u8> {
    let mut packet = vec![0u8; length];
    packet[0] = 0x45;
    packet[2..4].copy_from_slice(&(length as u16).to_be_bytes());
    packet[8] = 64;
    packet[9] = protocol;
    packet[12..16].copy_from_slice(&[10, 1, 2, 3]);
    packet[16..20].copy_from_slice(&[8, 8, 8, 8]);
    let check = checksum(&packet[..20]);
    packet[10..12].copy_from_slice(&check.to_be_bytes());
    packet
}

pub fn config_and_constructor_bounds() {
    use crate::{Config, proxy::tun::runner::mtu_for_config};
    for mtu in [1280, 1500, 9000, 65535] {
        let config = Config::Str(format!("tun: {{enable: true, mtu: {mtu}}}"))
            .try_parse_strict().unwrap();
        assert_eq!(config.tun.mtu.map(usize::from), Some(mtu));
        assert_eq!(mtu_for_config(&config.tun).unwrap(), mtu);
    }
    let config = Config::Str("tun: {enable: true}".into()).try_parse_strict().unwrap();
    assert_eq!(mtu_for_config(&config.tun).unwrap(),
        if cfg!(windows) { 65535 } else { 1500 });
    for mtu in [0, 1279, 65536] {
        assert!(Config::Str(format!("tun: {{enable: true, mtu: {mtu}}}"))
            .try_parse_strict().is_err(), "config admitted out-of-range MTU {mtu}");
    }
    // Constructor validation is synchronous and must fail before tokio spawning.
    for mtu in [0, 1279, 65536, usize::MAX] {
        assert!(watfaq_netstack::NetStack::with_mtu(mtu).is_err());
    }
}

pub async fn tcp_mss_uses_actual_mtu(mtu: usize) {
    tcp_mss(watfaq_netstack::NetStack::with_mtu(mtu).unwrap(), mtu).await;
}

pub async fn legacy_constructor_keeps_1500_mss() {
    tcp_mss(watfaq_netstack::NetStack::new(), 1500).await;
}

async fn tcp_mss(
    (stack, _tcp, _udp): (watfaq_netstack::NetStack, watfaq_netstack::TcpListener, watfaq_netstack::UdpSocket),
    mtu: usize,
) {
    let (mut input, mut output) = stack.split();
    // A valid IPv4 SYN advertises a peer MSS larger than either tested MTU.
    let mut syn = ipv4_packet(44, 6);
    syn[20..22].copy_from_slice(&40000u16.to_be_bytes());
    syn[22..24].copy_from_slice(&443u16.to_be_bytes());
    syn[24..28].copy_from_slice(&7u32.to_be_bytes());
    syn[32] = 0x60;
    syn[33] = 0x02;
    syn[34..36].copy_from_slice(&65535u16.to_be_bytes());
    syn[40..44].copy_from_slice(&[2, 4, 0xff, 0xff]);
    let mut pseudo = syn[12..20].to_vec();
    pseudo.extend_from_slice(&[0, 6, 0, 24]);
    pseudo.extend_from_slice(&syn[20..]);
    syn[36..38].copy_from_slice(&checksum(&pseudo).to_be_bytes());
    input.send(syn.into()).await.unwrap();
    let response = tokio::time::timeout(Duration::from_secs(3), output.next())
        .await.unwrap().unwrap().unwrap();
    let bytes = response.data();
    let ip_header = usize::from(bytes[0] & 15) * 4;
    let tcp = &bytes[ip_header..];
    assert_eq!(tcp[13] & 0x12, 0x12, "expected a real SYN/ACK");
    let header = usize::from(tcp[12] >> 4) * 4;
    let mut option = 20;
    let mut advertised_mss = None;
    while option < header {
        match tcp[option] {
            0 => break,
            1 => option += 1,
            kind => {
                let length = usize::from(tcp[option + 1]);
                assert!(length >= 2 && option + length <= header);
                if kind == 2 && length == 4 {
                    advertised_mss = Some(u16::from_be_bytes([tcp[option + 2], tcp[option + 3]]));
                }
                option += length;
            }
        }
    }
    assert_eq!(advertised_mss, Some((mtu - 40) as u16));
}

#[cfg(target_os = "linux")]
pub async fn framed_fd_preserves_entire_packet(mtu: usize) {
    use crate::proxy::tun::runner::framed_device;
    let (device_fd, peer_fd) = std::os::unix::net::UnixDatagram::pair().unwrap();
    device_fd.set_nonblocking(true).unwrap();
    peer_fd.set_nonblocking(true).unwrap();
    // tun-rs owns only this fixture FD. This never announces Native/TUN ready.
    let device = unsafe { tun_rs::AsyncDevice::from_fd(device_fd.into_raw_fd()) }.unwrap();
    let peer = tokio::net::UnixDatagram::from_std(peer_fd).unwrap();
    let mut framed = framed_device(device, mtu).unwrap();
    assert!(framed.read_buffer_size() >= mtu);
    assert!(framed.write_buffer_size() >= mtu);
    let mut packet = ipv4_packet(mtu, 17);
    packet[20..22].copy_from_slice(&12345u16.to_be_bytes());
    packet[22..24].copy_from_slice(&54321u16.to_be_bytes());
    packet[24..26].copy_from_slice(&((mtu - 20) as u16).to_be_bytes());
    for (index, byte) in packet[28..].iter_mut().enumerate() {
        *byte = (index % 251) as u8;
    }
    assert_eq!(peer.send(&packet).await.unwrap(), mtu);
    let received = tokio::time::timeout(Duration::from_secs(3), framed.next())
        .await.unwrap().unwrap().unwrap();
    assert_eq!(received.as_ref(), packet.as_slice(), "framed FD truncated ingress");
    framed.send(bytes::Bytes::from(packet.clone())).await.unwrap();
    let mut buffer = vec![0; mtu + 1];
    let size = tokio::time::timeout(Duration::from_secs(3), peer.recv(&mut buffer))
        .await.unwrap().unwrap();
    assert_eq!(&buffer[..size], packet.as_slice(), "framed FD truncated egress");
}
