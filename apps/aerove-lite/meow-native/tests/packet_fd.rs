#![cfg(unix)]
use aerove_meow_core::packet_fd::PacketFd;
use std::{io, os::{fd::AsFd, unix::net::UnixDatagram}, sync::Arc};
use tokio_util::sync::CancellationToken;

fn pair() -> (UnixDatagram, tokio::net::UnixDatagram) {
    let (caller, peer) = UnixDatagram::pair().unwrap();
    caller.set_nonblocking(true).unwrap();
    peer.set_nonblocking(true).unwrap();
    (caller, tokio::net::UnixDatagram::from_std(peer).unwrap())
}

#[tokio::test]
async fn packet_fd_preserves_full_mtu_and_caller_ownership() {
    let (caller, peer) = pair();
    let fd = PacketFd::duplicate(caller.as_fd(), 65535, CancellationToken::new()).unwrap();
    assert_eq!(fd.mtu(), 65535);
    let packet = vec![0x55; 65535];
    peer.send(&packet).await.unwrap();
    assert_eq!(fd.read_packet().await.unwrap(), packet);
    fd.write_packet(&packet).await.unwrap();
    let mut response = vec![0; 65536];
    assert_eq!(peer.recv(&mut response).await.unwrap(), packet.len());
    assert_eq!(&response[..packet.len()], packet);
    drop(fd);
    caller.send(b"caller still owns descriptor").unwrap();
    assert_eq!(peer.recv(&mut response).await.unwrap(), 28);
}

#[tokio::test]
async fn packet_fd_idle_cancel_joins_reader_and_releases_duplicate() {
    let (caller, peer) = pair();
    let cancel = CancellationToken::new();
    let fd = Arc::new(PacketFd::duplicate(caller.as_fd(), 9000, cancel.clone()).unwrap());
    let owned = fd.clone();
    let task = tokio::spawn(async move { owned.read_packet().await });
    tokio::task::yield_now().await;
    cancel.cancel();
    assert_eq!(task.await.unwrap().unwrap_err().kind(), io::ErrorKind::Interrupted);
    drop(fd);
    caller.send(b"owner").unwrap();
    let mut buffer = [0; 32];
    assert_eq!(peer.recv(&mut buffer).await.unwrap(), 5);
}

#[tokio::test]
async fn packet_fd_rejects_oversized_packets_without_truncated_success() {
    let (caller, peer) = pair();
    let fd = PacketFd::duplicate(caller.as_fd(), 1280, CancellationToken::new()).unwrap();
    peer.send(&vec![7; 1500]).await.unwrap();
    assert_eq!(fd.read_packet().await.unwrap_err().kind(), io::ErrorKind::InvalidData);
    assert_eq!(fd.write_packet(&vec![7; 1500]).await.unwrap_err().kind(), io::ErrorKind::InvalidInput);
    peer.send(&[9; 1280]).await.unwrap();
    assert_eq!(fd.read_packet().await.unwrap(), vec![9; 1280]);
}

#[tokio::test]
async fn packet_fd_bad_admission_never_changes_or_closes_caller() {
    let (caller, peer) = UnixDatagram::pair().unwrap();
    assert!(PacketFd::duplicate(caller.as_fd(), 9000, CancellationToken::new()).is_err());
    caller.send(b"blocking original").unwrap();
    let mut buffer = [0; 32];
    assert_eq!(peer.recv(&mut buffer).unwrap(), 17);
    caller.set_nonblocking(true).unwrap();
    assert!(PacketFd::duplicate(caller.as_fd(), 1279, CancellationToken::new()).is_err());
    let cancel = CancellationToken::new(); cancel.cancel();
    assert!(PacketFd::duplicate(caller.as_fd(), 9000, cancel).is_err());
    caller.send(b"still open").unwrap();
    assert_eq!(peer.recv(&mut buffer).unwrap(), 10);
}
