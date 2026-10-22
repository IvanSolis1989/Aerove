use std::{io, net::SocketAddr, os::fd::BorrowedFd, sync::{Arc, atomic::{AtomicUsize, Ordering}}, time::Duration};
use aerove_ts_underlay::{Protector, Resolver, Underlay};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_util::sync::CancellationToken;

fn resolver() -> Resolver { Arc::new(|_, _| Box::pin(async { panic!("loopback IP must not invoke DNS") })) }
fn owner(protector: Protector) -> Underlay { Underlay::protected(protector, resolver(), CancellationToken::new()) }

#[tokio::test]
async fn loopback_tcp_is_protected_before_connect_and_closes() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let calls = Arc::new(AtomicUsize::new(0));
    let count = calls.clone();
    let policy = owner(Arc::new(move |fd| {
        let fd = unsafe { BorrowedFd::borrow_raw(fd) };
        assert!(socket2::SockRef::from(&fd).peer_addr().is_err());
        count.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }));
    let mut stream = policy.connect(listener.local_addr().unwrap()).await.unwrap();
    let (mut peer, _) = listener.accept().await.unwrap();
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    stream.write_all(b"ok").await.unwrap();
    let mut buf = [0; 2];
    peer.read_exact(&mut buf).await.unwrap();
    assert_eq!(&buf, b"ok");
    drop(stream);
    assert_eq!(peer.read(&mut buf).await.unwrap(), 0);
}

#[tokio::test]
async fn loopback_udp_is_protected_before_bind_and_refusal_sends_nothing() {
    let peer = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let policy = owner(Arc::new(|fd| {
        let fd = unsafe { BorrowedFd::borrow_raw(fd) };
        assert_eq!(socket2::SockRef::from(&fd).local_addr()?.as_socket().unwrap().port(), 0);
        Ok(())
    }));
    let socket = policy.bind("127.0.0.1:0".parse().unwrap()).await.unwrap();
    socket.send_to(b"ok", peer.local_addr().unwrap()).await.unwrap();
    let mut buf = [0; 8];
    assert_eq!(peer.recv_from(&mut buf).await.unwrap().0, 2);
    let denied = owner(Arc::new(|_| Err(io::ErrorKind::PermissionDenied.into())));
    assert!(denied.bind("127.0.0.1:0".parse().unwrap()).await.is_err());
    assert!(tokio::time::timeout(Duration::from_millis(40), peer.recv_from(&mut buf)).await.is_err());
}

#[tokio::test]
async fn refusal_has_zero_tcp_connects_and_missing_policy_fails_closed() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let denied = owner(Arc::new(|_| Err(io::ErrorKind::PermissionDenied.into())));
    assert!(denied.connect(listener.local_addr().unwrap()).await.is_err());
    assert!(Underlay::default().connect(listener.local_addr().unwrap()).await.is_err());
    assert!(tokio::time::timeout(Duration::from_millis(40), listener.accept()).await.is_err());
}

#[tokio::test]
async fn cancellation_interrupts_old_owner_io_and_does_not_change_new_owner() {
    let peer = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let old = owner(Arc::new(|_| Ok(())));
    let new = owner(Arc::new(|_| Ok(())));
    let old_udp = old.bind("127.0.0.1:0".parse().unwrap()).await.unwrap();
    let new_udp = new.bind("127.0.0.1:0".parse().unwrap()).await.unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let mut old_tcp = old.connect(listener.local_addr().unwrap()).await.unwrap();
    let (_accepted, _) = listener.accept().await.unwrap();
    old.cancel();
    assert!(old_udp.send_to(b"old", peer.local_addr().unwrap()).await.is_err());
    assert!(old_tcp.write_all(b"old").await.is_err());
    let mut byte = [0];
    assert_eq!(old_tcp.read(&mut byte).await.unwrap_err().kind(), io::ErrorKind::Interrupted);
    assert!(old.connect(listener.local_addr().unwrap()).await.is_err());
    new_udp.send_to(b"new", peer.local_addr().unwrap()).await.unwrap();
    let mut buf = [0; 8];
    assert_eq!(peer.recv_from(&mut buf).await.unwrap().0, 3);
}

#[tokio::test]
async fn cancellation_during_resolution_never_creates_or_protects_a_socket() {
    let calls = Arc::new(AtomicUsize::new(0));
    let count = calls.clone();
    let cancel = CancellationToken::new();
    let resolver: Resolver = Arc::new(|_, _| Box::pin(std::future::pending::<io::Result<Vec<SocketAddr>>>()));
    let policy = Underlay::protected(Arc::new(move |_| { count.fetch_add(1, Ordering::SeqCst); Ok(()) }), resolver, cancel.clone());
    let dial = policy.connect_host("pending.example", 443);
    tokio::pin!(dial);
    tokio::select! { result = &mut dial => panic!("unexpected dial result: {}", result.is_ok()), _ = tokio::task::yield_now() => {} }
    cancel.cancel();
    assert_eq!(dial.await.unwrap_err().kind(), io::ErrorKind::Interrupted);
    assert_eq!(calls.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn cancellation_wakes_pending_tcp_and_udp_reads() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let policy = owner(Arc::new(|_| Ok(())));
    let mut stream = policy.connect(listener.local_addr().unwrap()).await.unwrap();
    let (_peer, _) = listener.accept().await.unwrap();
    let udp = policy.bind("127.0.0.1:0".parse().unwrap()).await.unwrap();
    let mut byte = [0];
    let pending = async { tokio::join!(stream.read(&mut byte), udp.readable()) };
    tokio::pin!(pending);
    tokio::select! { _ = &mut pending => panic!("read completed before cancellation"), _ = tokio::task::yield_now() => {} }
    policy.cancel();
    let (tcp, udp) = tokio::time::timeout(Duration::from_secs(1), pending).await.unwrap();
    assert_eq!(tcp.unwrap_err().kind(), io::ErrorKind::Interrupted);
    assert_eq!(udp.unwrap_err().kind(), io::ErrorKind::Interrupted);
}

#[tokio::test]
async fn cancellation_inside_protect_prevents_connect_and_callbacks_are_instance_owned() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let old_count = Arc::new(AtomicUsize::new(0));
    let count = old_count.clone();
    let cancel = CancellationToken::new();
    let signal = cancel.clone();
    let old = Underlay::protected(Arc::new(move |_| { count.fetch_add(1, Ordering::SeqCst); signal.cancel(); Ok(()) }), resolver(), cancel);
    let new_count = Arc::new(AtomicUsize::new(0));
    let count = new_count.clone();
    let new = owner(Arc::new(move |_| { count.fetch_add(1, Ordering::SeqCst); Ok(()) }));
    assert!(old.connect(listener.local_addr().unwrap()).await.is_err());
    assert!(tokio::time::timeout(Duration::from_millis(40), listener.accept()).await.is_err());
    let _new = new.connect(listener.local_addr().unwrap()).await.unwrap();
    let _accepted = listener.accept().await.unwrap();
    assert_eq!(old_count.load(Ordering::SeqCst), 1);
    assert_eq!(new_count.load(Ordering::SeqCst), 1);
}
