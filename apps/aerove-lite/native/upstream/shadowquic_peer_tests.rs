//! Real loopback JLS/QUIC peer; no public server, privileged TUN, or second Core.
use super::*;
use crate::proxy::OutboundHandler;
use shadowquic::{
    config::{AuthUser, JlsUpstream, ShadowQuicClientCfg, ShadowQuicServerCfg},
    msgs::squic::SQReq,
    quic::{AuthedConn, QuicServer},
    shadowquic::EndServer,
    SDecode,
};

const LIMIT: Duration = Duration::from_secs(15);
const GREETING: &[u8] = b"authenticated-shadowquic-peer\0\xff";

// Inbound::init spawns an endless accept task and shutdown only persists users.
// Own the same official endpoint directly so every fixture task can be joined.
struct PeerEndpoint(EndServer);

impl Drop for PeerEndpoint {
    fn drop(&mut self) {
        self.0.close(0u8.into(), b"loopback fixture finished");
    }
}

pub(super) async fn run(count: Arc<AtomicUsize>, refused: Arc<AtomicBool>) {
    let (certificates, key) = crate::common::tls::resolve_server_cert_and_key(
        None, None, "shadowquic loopback camouflage",
    ).unwrap();
    let mut tls = rustls::ServerConfig::builder_with_protocol_versions(&[&rustls::version::TLS13])
        .with_no_client_auth().with_single_cert(certificates, key).unwrap();
    tls.alpn_protocols = vec![b"h3".to_vec()];
    let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(tls));
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let camouflage = listener.local_addr().unwrap();
    let cancel = CancellationToken::new();
    let tls_cancel = cancel.clone();
    let tls_hits = Arc::new(AtomicUsize::new(0));
    let hits = tls_hits.clone();
    let mut tasks = JoinSet::new();
    tasks.spawn(async move {
        let mut connections = JoinSet::new();
        loop {
            tokio::select! {
                biased;
                _ = tls_cancel.cancelled() => break,
                result = listener.accept() => {
                    let (stream, address) = result.unwrap();
                    assert!(address.ip().is_loopback());
                    hits.fetch_add(1, Ordering::SeqCst);
                    let acceptor = acceptor.clone();
                    connections.spawn(async move {
                        // JLS only needs the camouflage handshake transcript and may
                        // disconnect before completing this ordinary TLS handshake.
                        let _ = tokio::time::timeout(LIMIT, async move {
                            if let Ok(mut stream) = acceptor.accept(stream).await {
                                let mut byte = [0];
                                let _ = stream.read(&mut byte).await;
                            }
                        }).await;
                    });
                }
                result = connections.join_next(), if !connections.is_empty() => {
                    result.unwrap().unwrap();
                }
            }
        }
        connections.shutdown().await;
    });

    let peer = PeerEndpoint(tokio::time::timeout(LIMIT, EndServer::new(&ShadowQuicServerCfg {
        bind_addr: "127.0.0.1:0".parse().unwrap(),
        users: vec![AuthUser { username: "loopback-user".into(), password: "loopback-secret".into() }],
        server_name: Some("localhost".into()),
        jls_upstream: JlsUpstream { addr: camouflage.to_string(), ..Default::default() },
        alpn: vec!["h3".into()],
        zero_rtt: false,
        gso: false,
        mtu_discovery: false,
        ..Default::default()
    })).await.unwrap().unwrap());
    let address = peer.0.local_addr().unwrap();
    let endpoint = peer.0.clone();
    // This is an asserted wire destination, not a socket to dial outside the test.
    let destination: SocketAddr = "127.0.0.1:43210".parse().unwrap();
    tasks.spawn(async move {
        tokio::time::timeout(LIMIT, async move {
            let connection = QuicServer::accept(&endpoint).await.unwrap();
            assert_eq!(connection.authed_user().as_deref(), Some("loopback-user"));
            assert_eq!(connection.is_jls(), Some(true));
            for round in 0..2 {
                let (mut send, mut recv) = connection.accept_bi().await.unwrap();
                let request = SQReq::decode(&mut recv).await.unwrap();
                let SQReq::SQConnect(target) = request else { panic!("expected real SQConnect"); };
                assert!(target == shadowquic::msgs::socks5::SocksAddr::from(destination));
                send.write_all(GREETING).await.unwrap();
                let expected = payload(round);
                let mut received = vec![0; expected.len()];
                recv.read_exact(&mut received).await.unwrap();
                assert_eq!(received, expected);
                send.write_all(&received).await.unwrap();
                send.finish().unwrap();
                let mut trailing = [0];
                assert_eq!(recv.read(&mut trailing).await.unwrap(), None);
            }
        }).await.expect("ShadowQUIC peer handshake/streams exceeded limit");
    });

    let options = ShadowQuicClientCfg {
        addr: address.to_string(), server_name: "localhost".into(),
        username: "loopback-user".into(), password: "loopback-secret".into(),
        alpn: vec!["h3".into()], zero_rtt: false, gso: false,
        mtu_discovery: false, ..Default::default()
    };
    let resolver = Arc::new(crate::app::dns::SystemResolver::new(false).unwrap());
    let session = crate::session::Session {
        destination: crate::session::SocksAddr::Ip(destination), ..Default::default()
    };
    refused.store(false, Ordering::SeqCst);
    let before = count.load(Ordering::SeqCst);
    let handler = crate::proxy::shadowquic::Handler::new("loopback-shadowquic".into(), options.clone());
    tokio::time::timeout(LIMIT, async {
        for round in 0..2 {
            let mut stream = handler.connect_stream(&session, resolver.clone()).await.unwrap();
            let mut greeting = vec![0; GREETING.len()];
            stream.read_exact(&mut greeting).await.unwrap();
            assert_eq!(greeting, GREETING);
            let expected = payload(round);
            stream.write_all(&expected).await.unwrap();
            stream.shutdown().await.unwrap();
            let mut received = vec![0; expected.len()];
            stream.read_exact(&mut received).await.unwrap();
            assert_eq!(received, expected);
            let mut trailing = [0];
            assert_eq!(stream.read(&mut trailing).await.unwrap(), 0);
        }
    }).await.expect("production ShadowQUIC Handler handshake/echo exceeded limit");
    assert!(count.load(Ordering::SeqCst) > before, "production Handler did not protect its UDP socket");
    assert!(tls_hits.load(Ordering::SeqCst) > 0, "local TLS camouflage was bypassed");

    refused.store(true, Ordering::SeqCst);
    let before = count.load(Ordering::SeqCst);
    let denied = crate::proxy::shadowquic::Handler::new("denied-shadowquic".into(), options.clone());
    assert!(tokio::time::timeout(LIMIT, denied.connect_stream(&session, resolver.clone())).await.unwrap().is_err());
    assert!(count.load(Ordering::SeqCst) > before);
    refused.store(false, Ordering::SeqCst);
    drop(denied);

    crate::mobile::protocol_transport::Scope::current().unwrap().cancel.cancel();
    let before = count.load(Ordering::SeqCst);
    let stopped = crate::proxy::shadowquic::Handler::new("stopped-shadowquic".into(), options);
    assert!(tokio::time::timeout(LIMIT, stopped.connect_stream(&session, resolver)).await.unwrap().is_err());
    assert_eq!(count.load(Ordering::SeqCst), before);
    drop(stopped);
    drop(handler);
    peer.0.close(0u8.into(), b"loopback fixture finished");
    tokio::time::timeout(LIMIT, peer.0.wait_idle()).await.unwrap();
    cancel.cancel();
    tokio::time::timeout(LIMIT, async {
        while let Some(result) = tasks.join_next().await { result.unwrap(); }
    }).await.expect("ShadowQUIC fixture tasks failed to join");
}

fn payload(round: usize) -> Vec<u8> {
    (0..32768).map(|index| ((index * 17 + round * 113) % 256) as u8).collect()
}
