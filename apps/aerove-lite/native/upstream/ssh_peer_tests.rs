use super::*;
use crate::{proxy::{OutboundHandler, ssh::{Handler, HandlerOptions}}, session::{Session, SocksAddr}};
use russh::{Channel, ChannelId, MethodKind, server::{self, Auth, Msg}};

const LIMIT: Duration = Duration::from_secs(4);
const USER: &str = "loopback-user";
const PASSWORD: &str = "loopback-password";
const PAYLOAD: &[u8] = b"ssh-direct-tcpip\0actual-peer-echo";

#[derive(Default)]
struct Receipts {
    accepted: AtomicUsize,
    authenticated: AtomicUsize,
    attempted: AtomicUsize,
    channels: AtomicUsize,
    bytes: StdMutex<Vec<u8>>,
}

struct PeerHandler(Arc<Receipts>);

#[async_trait::async_trait]
impl server::Handler for PeerHandler {
    type Error = russh::Error;

    async fn auth_password(&mut self, user: &str, password: &str) -> Result<Auth, Self::Error> {
        self.0.attempted.fetch_add(1, Ordering::SeqCst);
        if user == USER && password == PASSWORD {
            self.0.authenticated.fetch_add(1, Ordering::SeqCst);
            Ok(Auth::Accept)
        } else {
            Ok(Auth::reject())
        }
    }

    async fn channel_open_direct_tcpip(
        &mut self,
        _channel: Channel<Msg>,
        host: &str,
        port: u32,
        originator: &str,
        originator_port: u32,
        _session: &mut server::Session,
    ) -> Result<bool, Self::Error> {
        assert_eq!((host, port), ("127.0.0.1", 18080));
        assert_eq!((originator, originator_port), ("0.0.0.0", 0));
        self.0.channels.fetch_add(1, Ordering::SeqCst);
        Ok(true)
    }

    async fn data(&mut self, channel: ChannelId, data: &[u8], session: &mut server::Session) -> Result<(), Self::Error> {
        self.0.bytes.lock().unwrap().extend_from_slice(data);
        session.data(channel, data.to_vec())?;
        Ok(())
    }
}

struct Peer {
    address: SocketAddr,
    host_key: String,
    receipts: Arc<Receipts>,
    stop: CancellationToken,
    task: tokio::task::JoinHandle<()>,
}

impl Drop for Peer {
    fn drop(&mut self) {
        self.stop.cancel();
        self.task.abort();
    }
}

impl Peer {
    async fn new() -> Self {
        let key = russh::keys::PrivateKey::random(&mut rand::rng(), russh::keys::Algorithm::Ed25519).unwrap();
        let host_key = key.public_key().to_openssh().unwrap();
        let config = Arc::new(server::Config {
            keys: vec![key],
            methods: (&[MethodKind::Password][..]).into(),
            auth_rejection_time: Duration::ZERO,
            auth_rejection_time_initial: Some(Duration::ZERO),
            inactivity_timeout: Some(LIMIT),
            ..Default::default()
        });
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let receipts = Arc::new(Receipts::default());
        let observed = receipts.clone();
        let stop = CancellationToken::new();
        let stopping = stop.clone();
        let task = tokio::spawn(async move {
            let socket = tokio::select! {
                biased;
                _ = stopping.cancelled() => return,
                result = listener.accept() => result.unwrap().0,
            };
            observed.accepted.fetch_add(1, Ordering::SeqCst);
            let running = tokio::select! {
                biased;
                _ = stopping.cancelled() => return,
                result = tokio::time::timeout(LIMIT, server::run_stream(config, socket, PeerHandler(observed))) => result.unwrap().unwrap(),
            };
            let handle = running.handle();
            tokio::pin!(running);
            tokio::select! {
                _ = &mut running => {},
                _ = stopping.cancelled() => {
                    let _ = tokio::time::timeout(LIMIT, handle.disconnect(russh::Disconnect::ByApplication, "fixture stopped".into(), "".into())).await;
                    let _ = tokio::time::timeout(LIMIT, &mut running).await.expect("SSH session did not stop");
                }
            }
        });
        Self { address, host_key, receipts, stop, task }
    }

    fn handler(&self, user: &str, password: &str) -> Handler {
        Handler::new(HandlerOptions {
            name: "ssh-loopback".into(), common_opts: Default::default(),
            server: "127.0.0.1".into(), port: self.address.port(),
            username: user.into(), password: Some(password.into()), totp: None,
            private_key: None, private_key_passphrase: None,
            host_key: Some(vec![self.host_key.clone()]), host_key_algorithms: None,
        })
    }

    async fn finish(mut self) {
        self.stop.cancel();
        match tokio::time::timeout(LIMIT * 3, &mut self.task).await {
            Ok(result) => result.expect("SSH fixture panicked"),
            Err(_) => {
                self.task.abort();
                let _ = (&mut self.task).await;
                panic!("SSH fixture did not stop");
            }
        }
    }
}

pub(super) async fn run(count: Arc<AtomicUsize>, refused: Arc<AtomicBool>) {
    let (components, _directory) = components(None, "DIRECT").await;
    let session = Session {
        destination: SocksAddr::Ip("127.0.0.1:18080".parse().unwrap()),
        ..Default::default()
    };
    let peer = Peer::new().await;
    let before = count.load(Ordering::SeqCst);
    let mut stream = tokio::time::timeout(LIMIT, peer.handler(USER, PASSWORD).connect_stream(&session, components.dns_resolver.clone())).await.unwrap().unwrap();
    tokio::time::timeout(LIMIT, async {
        stream.write_all(PAYLOAD).await.unwrap();
        stream.flush().await.unwrap();
        let mut received = vec![0; PAYLOAD.len()];
        stream.read_exact(&mut received).await.unwrap();
        assert_eq!(received, PAYLOAD);
    }).await.unwrap();
    assert_eq!(count.load(Ordering::SeqCst), before + 1);
    assert_eq!(peer.receipts.accepted.load(Ordering::SeqCst), 1);
    assert_eq!(peer.receipts.attempted.load(Ordering::SeqCst), 1);
    assert_eq!(peer.receipts.authenticated.load(Ordering::SeqCst), 1);
    assert_eq!(peer.receipts.channels.load(Ordering::SeqCst), 1);
    assert_eq!(peer.receipts.bytes.lock().unwrap().as_slice(), PAYLOAD);
    drop(stream);
    peer.finish().await;

    for (user, password) in [("wrong-user", PASSWORD), (USER, "wrong-password")] {
        let peer = Peer::new().await;
        assert!(tokio::time::timeout(LIMIT, peer.handler(user, password).connect_stream(&session, components.dns_resolver.clone())).await.unwrap().is_err());
        assert_eq!(peer.receipts.attempted.load(Ordering::SeqCst), 1);
        assert_eq!(peer.receipts.authenticated.load(Ordering::SeqCst), 0);
        assert_eq!(peer.receipts.channels.load(Ordering::SeqCst), 0);
        peer.finish().await;
    }

    let peer = Peer::new().await;
    refused.store(true, Ordering::SeqCst);
    let before = count.load(Ordering::SeqCst);
    assert!(tokio::time::timeout(LIMIT, peer.handler(USER, PASSWORD).connect_stream(&session, components.dns_resolver.clone())).await.unwrap().is_err());
    refused.store(false, Ordering::SeqCst);
    assert_eq!(count.load(Ordering::SeqCst), before + 1);
    assert_eq!(peer.receipts.accepted.load(Ordering::SeqCst), 0);

    super::super::super::context().unwrap().cancel.cancel();
    let before = count.load(Ordering::SeqCst);
    assert!(tokio::time::timeout(LIMIT, peer.handler(USER, PASSWORD).connect_stream(&session, components.dns_resolver.clone())).await.unwrap().is_err());
    assert_eq!(count.load(Ordering::SeqCst), before);
    assert_eq!(peer.receipts.accepted.load(Ordering::SeqCst), 0);
    peer.finish().await;
    components.stop_all().await;
}
