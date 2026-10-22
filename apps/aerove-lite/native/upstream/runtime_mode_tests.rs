use super::*;
use std::{net::Ipv4Addr, sync::atomic::{AtomicBool, AtomicUsize, Ordering}};
use tokio::{net::{TcpListener, TcpStream, UdpSocket}, sync::oneshot, task::JoinHandle};

fn ports() -> Vec<u16> {
    let sockets: Vec<_> = (20000..49151).filter_map(|port| std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, port)).ok())
        .take(4).collect();
    assert_eq!(sockets.len(), 4);
    sockets.iter().map(|socket| socket.local_addr().unwrap().port()).collect()
}

fn source(ports: &[u16], dns: SocketAddr) -> String {
    format!("mode: direct\nmmdb: null\nasn-mmdb: null\ngeosite: null\nipv6: false\ntun: {{enable: false}}\ndns:\n  enable: false\n  default-nameserver: ['udp://{dns}']\nrules: ['MATCH,DIRECT']\nmixed-port: {}\nport: {}\nsocks-port: {}\nallow-lan: false\nbind-address: 127.0.0.1\nauthentication: ['user:pass']\nexternal-controller: 127.0.0.1:{}\nsecret: '0123456789abcdef0123456789abcdef'\ncors-allow-origins: []\n",
        ports[0], ports[1], ports[2], ports[3])
}

fn launch(source: String, directory: &std::path::Path) -> (CancellationToken, JoinHandle<Result<()>>, oneshot::Receiver<ReadyInfo>) {
    let cancel = CancellationToken::new();
    let stopping = cancel.clone();
    let (ready, receiver) = oneshot::channel();
    let path = directory.to_owned();
    let task = tokio::spawn(run_runtime(source, path, RuntimeInput::Proxy, stopping, move |info| { let _ = ready.send(info); }));
    (cancel, task, receiver)
}

async fn actual_ready(receiver: oneshot::Receiver<ReadyInfo>, api_port: u16) {
    let ready = tokio::time::timeout(Duration::from_secs(5), receiver).await.unwrap().unwrap();
    assert_eq!(ready.address, SocketAddr::from((Ipv4Addr::LOCALHOST, api_port)));
    assert_eq!(ready.runtime_mode, RuntimeMode::Proxy);
    assert!(!ready.tun_ready && ready.user_listeners_ready);
}

async fn http(port: u16, target: &str, authorization: &str) -> String {
    let mut socket = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await.unwrap();
    socket.write_all(format!("GET {target} HTTP/1.1\r\nHost: fixture.test\r\n{authorization}Connection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut response = String::new();
    tokio::time::timeout(Duration::from_secs(5), socket.read_to_string(&mut response)).await.unwrap().unwrap();
    response
}

async fn dns_server() -> (SocketAddr, Arc<AtomicUsize>, JoinHandle<()>) {
    let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let address = socket.local_addr().unwrap();
    let count = Arc::new(AtomicUsize::new(0));
    let hits = count.clone();
    let task = tokio::spawn(async move {
        let mut buffer = [0u8; 4096];
        loop {
            let (length, peer) = socket.recv_from(&mut buffer).await.unwrap();
            let request = Message::from_vec(&buffer[..length]).unwrap();
            hits.fetch_add(1, Ordering::SeqCst);
            let mut response = Message::response(request.metadata.id, request.metadata.op_code);
            response.queries = request.queries;
            for query in response.queries.clone() {
                if query.query_type() == RecordType::A {
                    response.add_answer(hickory_proto::rr::Record::from_rdata(query.name().clone(), 30,
                        RData::A(hickory_proto::rr::rdata::A(Ipv4Addr::LOCALHOST))));
                }
            }
            socket.send_to(&response.to_vec().unwrap(), peer).await.unwrap();
        }
    });
    (address, count, task)
}

async fn http_server() -> (SocketAddr, JoinHandle<()>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        loop {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut request = Vec::new(); let mut byte = [0u8; 1];
            while !request.ends_with(b"\r\n\r\n") && request.len() < 8192 {
                if socket.read_exact(&mut byte).await.is_err() { break; }
                request.push(byte[0]);
            }
            let _ = socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\nruntime-ok").await;
        }
    });
    (address, task)
}

async fn udp_control(port: u16) -> (TcpStream, u16) {
    let mut control = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await.unwrap();
    control.write_all(&[5, 1, 2]).await.unwrap();
    let mut answer = [0u8; 2]; control.read_exact(&mut answer).await.unwrap(); assert_eq!(answer, [5, 2]);
    control.write_all(b"\x01\x04user\x04pass").await.unwrap();
    control.read_exact(&mut answer).await.unwrap(); assert_eq!(answer, [1, 0]);
    control.write_all(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
    let mut reply = [0u8; 10]; control.read_exact(&mut reply).await.unwrap(); assert_eq!(&reply[..4], &[5, 0, 0, 1]);
    (control, u16::from_be_bytes([reply[8], reply[9]]))
}

async fn finish(cancel: CancellationToken, task: JoinHandle<Result<()>>, ports: &[u16]) {
    cancel.cancel();
    tokio::time::timeout(Duration::from_secs(5), task).await.unwrap().unwrap().unwrap();
    assert!(CONTEXT.read().unwrap().is_none());
    for port in ports { assert!(socket_helpers::try_create_dualstack_tcplistener((Ipv4Addr::LOCALHOST, *port).into()).is_ok()); }
}

async fn proxy_io() {
    let (dns, hits, dns_task) = dns_server().await;
    let (destination, http_task) = http_server().await;
    let ports = ports();
    let directory = tempfile::tempdir().unwrap();
    let (cancel, task, receiver) = launch(source(&ports, dns), directory.path());
    actual_ready(receiver, ports[3]).await;
    let target = format!("http://fixture.test:{}/", destination.port());
    for port in &ports[..2] {
        assert!(http(*port, &target, "").await.starts_with("HTTP/1.1 407"));
        assert!(http(*port, &target, "Proxy-Authorization: Basic dXNlcjpwYXNz\r\n").await.ends_with("runtime-ok"));
    }
    assert!(hits.load(Ordering::SeqCst) > 0);
    let response = http(ports[3], "/aerove/network", "Authorization: Bearer 0123456789abcdef0123456789abcdef\r\n").await;
    assert!(response.starts_with("HTTP/1.1 200"));
    let snapshot: serde_json::Value = serde_json::from_str(response.split_once("\r\n\r\n").unwrap().1).unwrap();
    assert_eq!(snapshot["network"]["authEnabled"], true);
    assert_eq!(snapshot["activePorts"], serde_json::json!(&ports[..3]));
    for port in [ports[0], ports[2]] {
        let (control, associated) = udp_control(port).await;
        let echo = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let destination = echo.local_addr().unwrap();
        let echo_task = tokio::spawn(async move { let mut data = [0u8; 1024]; let (length, peer) = echo.recv_from(&mut data).await.unwrap(); echo.send_to(&data[..length], peer).await.unwrap(); });
        let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let mut packet = vec![0, 0, 0, 1, 127, 0, 0, 1]; packet.extend(destination.port().to_be_bytes()); packet.extend(b"proxy-runtime-udp");
        client.send_to(&packet, (Ipv4Addr::LOCALHOST, associated)).await.unwrap();
        let mut response = [0u8; 1024]; let (length, _) = tokio::time::timeout(Duration::from_secs(5), client.recv_from(&mut response)).await.unwrap().unwrap();
        assert!(response[..length].ends_with(b"proxy-runtime-udp"));
        echo_task.await.unwrap(); drop(control);
    }
    finish(cancel, task, &ports).await;
    dns_task.abort(); let _ = dns_task.await;
    http_task.abort(); let _ = http_task.await;
}

async fn mode_failure() {
    let ports = ports();
    let directory = tempfile::tempdir().unwrap();
    let source = source(&ports, "127.0.0.1:53".parse().unwrap());
    let called = Arc::new(AtomicBool::new(false));
    let ready = called.clone();
    let result = run_runtime(source.replace("tun: {enable: false}", "tun: {enable: true}"), directory.path().to_owned(),
        RuntimeInput::Proxy, CancellationToken::new(), move |_| { ready.store(true, Ordering::SeqCst); }).await;
    assert!(result.unwrap_err().to_string().contains("$.tun.enable"));
    assert!(!called.load(Ordering::SeqCst)); assert!(CONTEXT.read().unwrap().is_none());
    let (fd, _) = std::os::unix::net::UnixStream::pair().unwrap();
    let result = run_runtime(source.clone(), directory.path().to_owned(), RuntimeInput::Tun { fd: fd.into(), protector: Arc::new(|_| Ok(())) },
        CancellationToken::new(), |_| panic!("invalid mode became ready")).await;
    assert!(result.unwrap_err().to_string().contains("$.tun.enable"));
    let collision = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, ports[0])).unwrap();
    let (_cancel, task, receiver) = launch(source, directory.path());
    assert!(tokio::time::timeout(Duration::from_secs(5), task).await.unwrap().unwrap().is_err());
    assert!(receiver.await.is_err());
    assert!(CONTEXT.read().unwrap().is_none()); drop(collision);
    for port in ports { assert!(socket_helpers::try_create_dualstack_tcplistener((Ipv4Addr::LOCALHOST, port).into()).is_ok()); }
}

async fn proxy_cancel() {
    let ports = ports();
    let directory = tempfile::tempdir().unwrap();
    let source = source(&ports, "127.0.0.1:53".parse().unwrap());
    let (cancel, task, receiver) = launch(source.clone(), directory.path());
    actual_ready(receiver, ports[3]).await;
    let (mut control, associated) = udp_control(ports[2]).await;
    finish(cancel, task, &ports).await;
    let mut byte = [0u8; 1];
    assert_eq!(tokio::time::timeout(Duration::from_secs(5), control.read(&mut byte)).await.unwrap().unwrap(), 0);
    assert!(UdpSocket::bind((Ipv4Addr::LOCALHOST, associated)).await.is_ok());
    let (cancel, task, receiver) = launch(source.clone(), directory.path());
    actual_ready(receiver, ports[3]).await;
    finish(cancel, task, &ports).await;
    let (cancel, task, receiver) = launch(source, directory.path());
    cancel.cancel();
    assert!(tokio::time::timeout(Duration::from_secs(5), task).await.unwrap().unwrap().is_ok());
    assert!(receiver.await.is_err()); assert!(CONTEXT.read().unwrap().is_none());
}

pub async fn run(case: &str) {
    assert!(CONTEXT.read().unwrap().is_none());
    match case {
        "runtime-proxy-io" => proxy_io().await,
        "runtime-mode-failure" => mode_failure().await,
        "runtime-proxy-cancel" => proxy_cancel().await,
        _ => panic!("unknown runtime mode case"),
    }
}
