#![cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
use aerove_meow_core::{Registry, network_context::NetworkContext};
use meow_common::socket_protect::SocketProtector;
use std::{io, net::{IpAddr, SocketAddr}, os::fd::RawFd, sync::{Arc, atomic::{AtomicUsize, Ordering}}, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream, UdpSocket}, sync::Mutex, time::timeout};
use tokio_util::sync::CancellationToken;

#[path = "support/resource_roots.rs"] mod root_fixture;
use root_fixture::Roots;
static LOCK: Mutex<()> = Mutex::const_new(());
struct Protect(Arc<AtomicUsize>);
impl SocketProtector for Protect {
    fn protect(&self, fd: RawFd) -> io::Result<()> {
        assert!(unsafe { libc::fcntl(fd, libc::F_GETFD) } >= 0);
        self.0.fetch_add(1, Ordering::Relaxed); Ok(())
    }
}
async fn free_ports() -> [SocketAddr; 2] {
    let private = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let mixed = TcpListener::bind("127.0.0.1:0").await.unwrap();
    [private.local_addr().unwrap(), mixed.local_addr().unwrap()]
}
fn plan(ports: [SocketAddr; 2], upstream: SocketAddr, kind: &str, roots: &Roots) -> String {
    let udp = if kind == "socks5" { ", udp: true" } else { "" };
    serde_json::json!({"schemaVersion":1,"runtimeMode":"proxy","initiallyParked":false,
        "engineYaml":format!("strict: true\nmode: rule\nproxies:\n  - {{name: upstream, type: {kind}, server: 127.0.0.1, port: {}{udp}}}\nproxy-groups:\n  - {{name: INNER, type: select, proxies: [upstream, DIRECT]}}\n  - {{name: OUTER, type: select, proxies: [INNER, REJECT]}}\nrules: ['MATCH,OUTER']\ndns: {{enable: false, use-system-hosts: false}}\ntun: {{enable: false, auto-route: false}}\n",upstream.port()),
        "privateControl":{"address":ports[0].to_string(),"secret":"transport-fixture-private-secret-000001"},
        "inbounds":{"configYaml":format!("mixed-port: {}\nbind-address: 127.0.0.1\n",ports[1].port())},
        "tun":null,"physicalNetwork":{"handle":null,"dns":{"defaultInterface":"fixture","interfaces":{"fixture":["127.0.0.1"]}}},
        "hostPolicyYaml":"{}","profileId":null,"revision":null,"resourceGeneration":null,
        "resourceReadRoot":roots.read.to_str().unwrap(),"cacheRoot":roots.cache.to_str().unwrap()}).to_string()
}
async fn header(stream: &mut TcpStream) -> Vec<u8> {
    let mut bytes = Vec::new(); let mut byte = [0];
    while !bytes.ends_with(b"\r\n\r\n") {
        assert!(bytes.len() < 8192); stream.read_exact(&mut byte).await.unwrap(); bytes.push(byte[0]);
    }
    bytes
}
async fn socks_request(stream: &mut TcpStream) -> (u8, SocketAddr) {
    let version = stream.read_u8().await.unwrap(); assert_eq!(version,5);
    let methods = stream.read_u8().await.unwrap(); let mut values = vec![0;usize::from(methods)]; stream.read_exact(&mut values).await.unwrap(); assert!(values.contains(&0));
    stream.write_all(&[5,0]).await.unwrap();
    assert_eq!(stream.read_u8().await.unwrap(),5); let command = stream.read_u8().await.unwrap(); assert_eq!(stream.read_u8().await.unwrap(),0);
    let ip = match stream.read_u8().await.unwrap() {
        1 => { let mut bytes = [0;4]; stream.read_exact(&mut bytes).await.unwrap(); IpAddr::from(bytes) },
        3 => { let n = stream.read_u8().await.unwrap(); let mut bytes = vec![0;usize::from(n)]; stream.read_exact(&mut bytes).await.unwrap(); String::from_utf8(bytes).unwrap().parse().unwrap() },
        _ => panic!("fixture requires numeric IPv4"),
    };
    (command, SocketAddr::new(ip,stream.read_u16().await.unwrap()))
}
async fn socks_connect(address: SocketAddr, target: SocketAddr) -> TcpStream {
    let mut stream = TcpStream::connect(address).await.unwrap(); stream.write_all(&[5,1,0]).await.unwrap();
    let mut reply = [0;2]; stream.read_exact(&mut reply).await.unwrap(); assert_eq!(reply,[5,0]);
    let IpAddr::V4(ip) = target.ip() else { panic!("IPv4 fixture") };
    let mut request = vec![5,1,0,1]; request.extend_from_slice(&ip.octets()); request.extend_from_slice(&target.port().to_be_bytes()); stream.write_all(&request).await.unwrap();
    let mut reply = [0;10]; stream.read_exact(&mut reply).await.unwrap(); assert_eq!(&reply[..4],&[5,0,0,1]); stream
}

#[tokio::test]
async fn full_factory_http_and_socks_tcp_keepalive_stop_join_and_ports_rebind() {
    let _serial = LOCK.lock().await;
    for kind in ["http","socks5"] {
        let roots = Roots::new(); let ports = free_ports().await;
        let target = TcpListener::bind("127.0.0.1:0").await.unwrap(); let target_address = target.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut stream,_) = target.accept().await.unwrap();
            for path in ["/first","/second"] {
                assert!(header(&mut stream).await.starts_with(format!("GET {path} ").as_bytes()));
                stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: keep-alive\r\n\r\nok!").await.unwrap();
            }
            assert_eq!(stream.read_u8().await.unwrap_err().kind(),io::ErrorKind::UnexpectedEof);
        });
        let peer = TcpListener::bind("127.0.0.1:0").await.unwrap(); let peer_address = peer.local_addr().unwrap();
        let protocol = kind.to_owned();
        let proxy = tokio::spawn(async move {
            let (mut stream,_) = peer.accept().await.unwrap();
            if protocol == "http" {
                let request = header(&mut stream).await;
                assert!(request.starts_with(format!("CONNECT {target_address} ").as_bytes()));
                stream.write_all(b"HTTP/1.1 200 Connection established\r\n\r\n").await.unwrap();
            } else {
                assert_eq!(socks_request(&mut stream).await,(1,target_address));
                stream.write_all(&[5,0,0,1,127,0,0,1,0,0]).await.unwrap();
            }
            let mut remote = TcpStream::connect(target_address).await.unwrap();
            tokio::io::copy_bidirectional(&mut stream,&mut remote).await.unwrap();
        });
        let calls = Arc::new(AtomicUsize::new(0)); let cancel = CancellationToken::new();
        let network = NetworkContext::protected(Arc::new(Protect(calls.clone())),None,cancel).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
        let session = Registry::start_owned(plan(ports,peer_address,kind,&roots),None,network,None).unwrap();
        let readiness = timeout(Duration::from_secs(5),session.ready()).await;
        {
            use std::io::Write as _;
            let owner = session.owned_runtime();
            let diagnostic = serde_json::json!({
                "stage":"ready", "protocol":kind, "generation":session.generation().to_string(),
                "privateControl":ports[0].to_string(), "mixed":ports[1].to_string(),
                "target":target_address.to_string(), "upstream":peer_address.to_string(),
                "readyResult":format!("{readiness:?}"), "session":session.snapshot(),
                "ownedStatus":owner.as_ref().map(|owner|owner.status()),
                "typedFailure":readiness.as_ref().ok().and_then(|result|result.as_ref().err()),
                "listeners":owner.as_ref().map(|owner|owner.listener_status()),
                "cleanupConfirmed":owner.as_ref().map(|owner|owner.cleanup_confirmed()),
            });
            let diagnostic = diagnostic.to_string();
            let diagnostic = if diagnostic.len() <= 16 * 1024 { diagnostic } else {
                serde_json::json!({"stage":"ready", "protocol":kind, "diagnosticOmitted":true,
                    "diagnosticBytes":diagnostic.len(), "maximumBytes":16 * 1024}).to_string()
            };
            // Preserve the original readiness failure even if diagnostic output cannot be saved.
            let _ = writeln!(std::io::stdout().lock(), "RUNTIME_TRANSPORT_STARTUP_DIAGNOSTIC {diagnostic}");
        }
        readiness.unwrap().unwrap();
        let mut client = socks_connect(ports[1],target_address).await;
        for path in ["/first","/second"] {
            client.write_all(format!("GET {path} HTTP/1.1\r\nHost: localhost\r\n\r\n").as_bytes()).await.unwrap();
            assert!(timeout(Duration::from_secs(5),header(&mut client)).await.unwrap().starts_with(b"HTTP/1.1 200"));
            let mut body = [0;3]; client.read_exact(&mut body).await.unwrap(); assert_eq!(&body,b"ok!");
        }
        assert!(calls.load(Ordering::Relaxed)>=1);
        timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap(); assert!(session.owned_runtime().unwrap().cleanup_confirmed());
        assert_eq!(timeout(Duration::from_secs(5),client.read_u8()).await.unwrap().unwrap_err().kind(),io::ErrorKind::UnexpectedEof);
        timeout(Duration::from_secs(5),proxy).await.unwrap().unwrap(); timeout(Duration::from_secs(5),server).await.unwrap().unwrap();
        for port in ports { assert!(TcpListener::bind(port).await.is_ok()); }
        assert!(TcpListener::bind(peer_address).await.is_ok()); assert!(TcpListener::bind(target_address).await.is_ok());
    }
}

#[tokio::test]
async fn socks_udp_associate_persistent_replies_park_joins_control_and_same_core_resumes() {
    let _serial = LOCK.lock().await;
    let roots = Roots::new(); let ports = free_ports().await;
    let remote = TcpListener::bind("127.0.0.1:0").await.unwrap(); let remote_address = remote.local_addr().unwrap();
    let relay = UdpSocket::bind("127.0.0.1:0").await.unwrap(); let relay_address = relay.local_addr().unwrap();
    let target = UdpSocket::bind("127.0.0.1:0").await.unwrap(); let target_address = target.local_addr().unwrap();
    let replies = tokio::spawn(async move {
        let mut bytes = [0;1024];
        for expected in [b"one".as_slice(),b"two".as_slice()] {
            let (n,peer) = target.recv_from(&mut bytes).await.unwrap(); assert_eq!(&bytes[..n],expected);
            let mut response = b"actual-target:".to_vec(); response.extend_from_slice(expected); target.send_to(&response,peer).await.unwrap();
        }
    });
    let peer = tokio::spawn(async move {
        let (mut stream,_) = remote.accept().await.unwrap(); assert_eq!(socks_request(&mut stream).await.0,3);
        let mut reply = vec![5,0,0,1,127,0,0,1]; reply.extend_from_slice(&relay_address.port().to_be_bytes()); stream.write_all(&reply).await.unwrap();
        let outgoing = UdpSocket::bind("127.0.0.1:0").await.unwrap(); let mut bytes = [0;2048];
        for _ in 0..2 {
            let (n,client) = relay.recv_from(&mut bytes).await.unwrap(); assert!(n>=10); assert_eq!(&bytes[..8],&[0,0,0,1,127,0,0,1]); assert_eq!(u16::from_be_bytes([bytes[8],bytes[9]]),target_address.port());
            let prefix = bytes[..10].to_vec(); outgoing.send_to(&bytes[10..n],target_address).await.unwrap();
            let (n,_) = outgoing.recv_from(&mut bytes).await.unwrap(); let mut response = prefix; response.extend_from_slice(&bytes[..n]); relay.send_to(&response,client).await.unwrap();
        }
        assert_eq!(stream.read_u8().await.unwrap_err().kind(),io::ErrorKind::UnexpectedEof);
    });
    let calls = Arc::new(AtomicUsize::new(0));
    let network = NetworkContext::protected(Arc::new(Protect(calls.clone())),None,CancellationToken::new()).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);
    let session = Registry::start_owned(plan(ports,remote_address,"socks5",&roots),None,network,None).unwrap();
    timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap(); let owner = session.owned_runtime().unwrap(); let epoch = owner.route_stamp();
    let mut control = TcpStream::connect(ports[1]).await.unwrap(); control.write_all(&[5,1,0]).await.unwrap(); let mut hello = [0;2]; control.read_exact(&mut hello).await.unwrap(); assert_eq!(hello,[5,0]);
    control.write_all(&[5,3,0,1,127,0,0,1,0,0]).await.unwrap(); let mut bound = [0;10]; control.read_exact(&mut bound).await.unwrap(); assert_eq!(&bound[..4],&[5,0,0,1]);
    let ingress = SocketAddr::from(([bound[4],bound[5],bound[6],bound[7]],u16::from_be_bytes([bound[8],bound[9]])));
    let client = UdpSocket::bind("127.0.0.1:0").await.unwrap(); let mut bytes = [0;2048];
    for payload in [b"one".as_slice(),b"two".as_slice()] {
        let mut packet = vec![0,0,0,1,127,0,0,1]; packet.extend_from_slice(&target_address.port().to_be_bytes()); packet.extend_from_slice(payload); client.send_to(&packet,ingress).await.unwrap();
        let (n,_) = timeout(Duration::from_secs(5),client.recv_from(&mut bytes)).await.unwrap().unwrap();
        let mut expected = b"actual-target:".to_vec(); expected.extend_from_slice(payload); assert_eq!(&bytes[10..n],expected.as_slice());
    }
    assert!(calls.load(Ordering::Relaxed)>=2);
    timeout(Duration::from_secs(5),owner.park()).await.unwrap().unwrap(); assert!(!owner.listener_status().listening); assert_eq!(owner.route_stamp(),epoch);
    timeout(Duration::from_secs(5),peer).await.unwrap().unwrap(); timeout(Duration::from_secs(5),replies).await.unwrap().unwrap(); assert!(UdpSocket::bind(ingress).await.is_ok());
    timeout(Duration::from_secs(5),owner.resume(None)).await.unwrap().unwrap(); assert!(owner.listener_status().listening); assert_eq!(owner.route_stamp(),epoch);
    timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap(); assert!(owner.cleanup_confirmed());
    for port in ports { assert!(TcpListener::bind(port).await.is_ok()); }
    assert!(TcpListener::bind(remote_address).await.is_ok()); assert!(UdpSocket::bind(relay_address).await.is_ok());
}
