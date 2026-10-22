use super::*;
use crate::runner::Runner;
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream, UdpSocket}, task::JoinHandle};
use tower::ServiceExt;

fn free_ports(count: usize) -> Vec<u16> {
    let sockets: Vec<_> = (20000..49151).filter_map(|port| std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, port)).ok())
        .take(count).collect();
    assert_eq!(sockets.len(), count);
    sockets.iter().map(|socket| socket.local_addr().unwrap().port()).collect()
}

fn source(ports: &[u16], auth: bool, lan: bool) -> String {
    format!("mode: direct\nmmdb: null\nasn-mmdb: null\ngeosite: null\ndns: {{enable: false}}\nrules: ['MATCH,DIRECT']\nmixed-port: {}\n{}{}allow-lan: {lan}\nbind-address: {}\nauthentication: {}\nexternal-controller: 127.0.0.1:19000\nsecret: '0123456789abcdef0123456789abcdef'\ncors-allow-origins: []\n",
        ports[0], ports.get(1).map(|port| format!("port: {port}\n")).unwrap_or_default(),
        ports.get(2).map(|port| format!("socks-port: {port}\n")).unwrap_or_default(),
        if lan { "0.0.0.0" } else { "127.0.0.1" }, if auth { "['user:pass']" } else { "[]" })
}

async fn start(source: &str) -> (Arc<RuntimeComponents>, Arc<Owner>, tokio::sync::mpsc::UnboundedReceiver<super::super::Event>, tempfile::TempDir) {
    let config = super::super::parse(source).unwrap();
    let old = super::super::context().unwrap();
    let owner = runtime(source, &config, controller(&config).unwrap(), old.cancel.clone()).unwrap();
    let (events, receiver) = tokio::sync::mpsc::unbounded_channel();
    *super::super::CONTEXT.write().unwrap() = Some(Arc::new(super::super::Context {
        protector: old.protector.clone(), bootstrap: vec![], events, logs: old.logs.clone(), level: old.level,
        cancel: old.cancel.clone(), telemetry: old.telemetry.clone(), network: Some(owner.clone()),
    }));
    let directory = tempfile::tempdir().unwrap();
    let components = Arc::new(crate::create_components(directory.path().to_owned(), config).await.unwrap());
    components.inbound_manager.run_async();
    (components, owner, receiver, directory)
}

async fn ready(owner: &Owner, receiver: &mut tokio::sync::mpsc::UnboundedReceiver<super::super::Event>) {
    let mut expected = owner.expected();
    tokio::time::timeout(Duration::from_secs(3), async {
        while !expected.is_empty() {
            match receiver.recv().await.unwrap() {
                super::super::Event::InboundReady(bound) => accept_ready(&mut expected, bound).unwrap(),
                super::super::Event::Failed(error) => panic!("{error}"),
                _ => {},
            }
        }
    }).await.unwrap();
    owner.mark_ready();
}

async fn stop(components: &RuntimeComponents) {
    components.inbound_manager.shutdown();
    components.inbound_manager.join().await.unwrap();
    components.statistics_manager.stop().await;
}

pub(crate) async fn validation() {
    let ports = free_ports(3);
    let valid = source(&ports, true, false);
    let config = super::super::parse(&valid).unwrap();
    assert_eq!(runtime(&valid, &config, controller(&config).unwrap(), CancellationToken::new()).unwrap().expected().len(), 3);
    let mut request = json!({"requestId":"5a5e938f-8655-42dc-b156-7aab8b89fdde","expectedCoreEpoch":u64::MAX.to_string(),"network":{
        "mixed-port":ports[0],"allow-lan":false,"bind-address":"127.0.0.1","authentication":[]}});
    let decoded: Update = serde_json::from_value(request.clone()).unwrap();
    assert!(decoded.valid_id()); assert_eq!(decoded.epoch(), Some(u64::MAX));
    for invalid in ["01", "-1", "18446744073709551616"] {
        request["expectedCoreEpoch"] = json!(invalid);
        assert!(serde_json::from_value::<Update>(request.clone()).unwrap().epoch().is_none());
    }
    request["expectedCoreEpoch"] = json!(u64::MAX);
    assert!(serde_json::from_value::<Update>(request).is_err());
    for bad in [valid.replace("127.0.0.1:19000", "0.0.0.0:19000"), valid.replace("127.0.0.1:19000", "127.0.0.1:0"),
        valid.replace("0123456789abcdef0123456789abcdef", "short"), valid.replace("cors-allow-origins: []", "cors-allow-origins: ['*']"),
        format!("{valid}external-ui: dashboard\n")]
    { assert!(controller(&super::super::parse(&bad).unwrap()).is_err()); }
    for bad in [valid.replace(&format!("mixed-port: {}", ports[0]), "mixed-port: 0"),
        valid.replace(&format!("port: {}\n", ports[1]), &format!("port: {}\n", ports[0])),
        valid.replace("bind-address: 127.0.0.1", "bind-address: 0.0.0.0"),
        format!("{valid}listeners: [{{name: arbitrary, type: http, listen: 127.0.0.1, port: 25000}}]\n"),
        valid.replace("['user:pass']", "['no-colon']"), valid.replace("['user:pass']", &format!("['user:{}']", "x".repeat(256)))]
    {
        let config = super::super::parse(&bad).unwrap();
        assert!(runtime(&bad, &config, "127.0.0.1:19000".parse().unwrap(), CancellationToken::new()).is_err());
    }
    for field in ["redir-port", "tproxy-port"] {
        assert!(super::super::parse(&format!("{valid}{field}: 0\n")).is_ok());
        let error = match super::super::parse(&format!("{valid}{field}: 12345\n")) { Ok(_) => panic!("unsupported field accepted"), Err(error) => error };
        assert!(error.to_string().contains(field));
    }
}

async fn http_request(port: u16, destination: SocketAddr, authorized: bool) -> String {
    let mut socket = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await.unwrap();
    let auth = if authorized { "Proxy-Authorization: Basic dXNlcjpwYXNz\r\n" } else { "" };
    socket.write_all(format!("GET http://{destination}/ HTTP/1.1\r\nHost: {destination}\r\n{auth}Connection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut response = String::new();
    tokio::time::timeout(Duration::from_secs(3), socket.read_to_string(&mut response)).await.unwrap().unwrap();
    response
}

async fn http_server() -> (SocketAddr, JoinHandle<()>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        loop {
            let (mut socket, _) = listener.accept().await.unwrap();
            tokio::spawn(async move {
                let mut request = Vec::new(); let mut byte = [0u8; 1];
                while !request.ends_with(b"\r\n\r\n") && request.len() < 8192 {
                    if socket.read_exact(&mut byte).await.is_err() { return; }
                    request.push(byte[0]);
                }
                socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK").await.unwrap();
            });
        }
    });
    (address, task)
}

async fn socks(port: u16, correct: bool) -> TcpStream {
    let mut socket = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await.unwrap();
    socket.write_all(&[5, 1, 2]).await.unwrap(); let mut answer = [0u8; 2]; socket.read_exact(&mut answer).await.unwrap();
    assert_eq!(answer, [5, 2]);
    socket.write_all(if correct { b"\x01\x04user\x04pass" } else { b"\x01\x04user\x04fail" }).await.unwrap();
    socket.read_exact(&mut answer).await.unwrap(); assert_eq!(answer, if correct { [1, 0] } else { [1, 1] });
    socket
}

pub(crate) async fn listeners() {
    let ports = free_ports(3);
    let (components, owner, mut receiver, _directory) = start(&source(&ports, true, true)).await;
    ready(&owner, &mut receiver).await;
    assert!(components.inbound_manager.get_listeners().await.iter().all(|listener| listener.active));
    let (destination, server) = http_server().await;
    for port in [ports[0], ports[1]] {
        assert!(http_request(port, destination, false).await.starts_with("HTTP/1.1 407"));
        assert!(http_request(port, destination, true).await.ends_with("OK"));
    }
    for port in [ports[0], ports[2]] {
        drop(socks(port, false).await);
        let mut socket = socks(port, true).await;
        let mut connect = vec![5, 1, 0, 1, 127, 0, 0, 1]; connect.extend(destination.port().to_be_bytes());
        socket.write_all(&connect).await.unwrap(); let mut response = [0u8; 10]; socket.read_exact(&mut response).await.unwrap();
        assert_eq!(response[1], 0);
        socket.write_all(b"GET / HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n").await.unwrap();
        let mut body = String::new(); tokio::time::timeout(Duration::from_secs(3), socket.read_to_string(&mut body)).await.unwrap().unwrap();
        assert!(body.ends_with("OK"));
    }
    stop(&components).await;
    for port in ports { assert!(crate::proxy::utils::try_create_dualstack_tcplistener((Ipv4Addr::LOCALHOST, port).into()).is_ok()); }
    server.abort(); let _ = server.await;
}

pub(crate) async fn udp() {
    let ports = free_ports(3);
    let (components, owner, mut receiver, _directory) = start(&source(&ports, true, false)).await;
    ready(&owner, &mut receiver).await;
    for port in [ports[0], ports[2]] {
        let echo = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap(); let destination = echo.local_addr().unwrap();
        let echo_task = tokio::spawn(async move { let mut data = [0u8; 1024]; let (length, peer) = echo.recv_from(&mut data).await.unwrap(); echo.send_to(&data[..length], peer).await.unwrap(); });
        let mut control = socks(port, true).await;
        control.write_all(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
        let mut reply = [0u8; 10]; control.read_exact(&mut reply).await.unwrap(); assert_eq!(&reply[..4], &[5, 0, 0, 1]);
        let associated_port = u16::from_be_bytes([reply[8], reply[9]]); assert_ne!(associated_port, 0);
        let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let mut packet = vec![0, 0, 0, 1, 127, 0, 0, 1]; packet.extend(destination.port().to_be_bytes()); packet.extend(b"actual-udp");
        client.send_to(&packet, (Ipv4Addr::LOCALHOST, associated_port)).await.unwrap();
        let mut response = [0u8; 1024]; let (length, _) = tokio::time::timeout(Duration::from_secs(3), client.recv_from(&mut response)).await.unwrap().unwrap();
        assert!(response[..length].ends_with(b"actual-udp")); echo_task.await.unwrap();
        drop(control);
        tokio::time::timeout(Duration::from_secs(3), async {
            loop { if UdpSocket::bind((Ipv4Addr::LOCALHOST, associated_port)).await.is_ok() { break; } tokio::time::sleep(Duration::from_millis(10)).await; }
        }).await.unwrap();
    }
    stop(&components).await;
}

pub(crate) async fn failure() {
    let collision = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let port = collision.local_addr().unwrap().port();
    let held = if (1024..=49151).contains(&port) { collision } else { drop(collision); std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, free_ports(1)[0])).unwrap() };
    let ports = vec![held.local_addr().unwrap().port()];
    let (components, owner, mut receiver, _directory) = start(&source(&ports, false, false)).await;
    let mut expected = owner.expected();
    let error = tokio::time::timeout(Duration::from_secs(3), async {
        loop { match receiver.recv().await.unwrap() {
            super::super::Event::Failed(error) => break error,
            super::super::Event::InboundReady(bound) => accept_ready(&mut expected, bound).unwrap(),
            _ => {},
        } }
    }).await.unwrap();
    assert!(!expected.is_empty()); assert!(error.contains("MIXED-IN"));
    stop(&components).await; drop(held);
    assert!(crate::proxy::utils::try_create_dualstack_tcplistener((Ipv4Addr::LOCALHOST, ports[0]).into()).is_ok());
    let signal = ready_signal(Kind::Mixed, ports[0]).unwrap();
    let old = super::super::context().unwrap();
    let (events, mut late) = tokio::sync::mpsc::unbounded_channel();
    *super::super::CONTEXT.write().unwrap() = Some(Arc::new(super::super::Context {
        protector: old.protector.clone(), bootstrap: vec![], events, logs: old.logs.clone(), level: old.level,
        cancel: old.cancel.clone(), telemetry: old.telemetry.clone(), network: Some(owner),
    }));
    assert!(signal.bound((Ipv4Addr::LOCALHOST, ports[0]).into()).is_err()); assert!(late.try_recv().is_err());
}

pub(crate) async fn update() {
    let ports = free_ports(3);
    let (components, owner, mut receiver, _directory) = start(&source(&ports[..1], false, false)).await;
    ready(&owner, &mut receiver).await;
    let epoch = super::super::telemetry::route_stamp().unwrap().0;
    let pointer = Arc::as_ptr(&components);
    let api = routes(Arc::new(RwLock::new(components.clone())))
        .route_layer(crate::app::api::AuthMiddlewareLayer::new("session-secret".into()));
    let request_id = "5a5e938f-8655-42dc-b156-7aab8b89fdde";
    let payload = json!({"requestId":request_id,"expectedCoreEpoch":epoch.to_string(),"network":{
        "mixed-port":ports[1],"port":ports[0],"socks-port":ports[2],"allow-lan":false,
        "bind-address":"127.0.0.1","authentication":["user:pass"]}});
    let request = |body: &Value| http::Request::builder().method("POST").uri("/aerove/network")
        .header("Authorization", "Bearer session-secret").header("Content-Type", "application/json")
        .body(axum::body::Body::from(body.to_string())).unwrap();
    let response = api.clone().oneshot(request(&payload)).await.unwrap(); assert_eq!(response.status(), StatusCode::OK);
    let receipt: Value = serde_json::from_slice(&to_bytes(response.into_body(), 65536).await.unwrap()).unwrap();
    assert_eq!(receipt["requestId"], request_id); assert_ne!(receipt["coreEpoch"], epoch.to_string());
    assert!(!receipt.to_string().contains("user:pass")); assert_eq!(Arc::as_ptr(&components), pointer);
    assert_eq!(api.clone().oneshot(request(&payload)).await.unwrap().status(), StatusCode::CONFLICT);
    let get = http::Request::builder().uri("/aerove/network").header("Authorization", "Bearer session-secret").body(axum::body::Body::empty()).unwrap();
    let response = api.oneshot(get).await.unwrap(); assert_eq!(response.status(), StatusCode::OK);
    let readback: Value = serde_json::from_slice(&to_bytes(response.into_body(), 65536).await.unwrap()).unwrap();
    assert_eq!(readback["coreEpoch"], receipt["coreEpoch"]);
    assert_eq!(readback.as_object().unwrap().len(), 3);
    assert_eq!(readback["network"].as_object().unwrap().len(), 6);
    assert!(!readback.to_string().contains("user:pass"));
    assert_eq!(readback["network"]["mixed-port"], ports[1]); assert_eq!(readback["network"]["authEnabled"], true);
    assert_eq!(readback["activePorts"].as_array().unwrap().len(), 3);
    assert_eq!(readback["activePorts"], json!(ports));
    let (destination, server) = http_server().await;
    assert!(http_request(ports[1], destination, false).await.starts_with("HTTP/1.1 407"));
    assert!(http_request(ports[1], destination, true).await.ends_with("OK"));
    owner.cancel.cancel(); owner.join().await.unwrap(); stop(&components).await;
    server.abort(); let _ = server.await;
}

pub(crate) async fn cancellation() {
    let ports = free_ports(3);
    let (components, owner, mut receiver, _directory) = start(&source(&ports, true, false)).await;
    ready(&owner, &mut receiver).await;
    let runners: Vec<BoxFuture<'static, crate::Result<()>>> = vec![Box::pin(async { panic!("fixture listener panic") })];
    listen("MIXED-IN".into(), runners, CancellationToken::new()).await;
    assert!(matches!(receiver.recv().await, Some(super::super::Event::Failed(error)) if error.contains("panicked")));
    spawn_connection(async { panic!("fixture connection panic") }).await;
    assert!(matches!(tokio::time::timeout(Duration::from_secs(3), receiver.recv()).await.unwrap(),
        Some(super::super::Event::Failed(error)) if error.contains("panicked")));
    let mut control = socks(ports[2], true).await;
    control.write_all(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
    let mut reply = [0u8; 10]; control.read_exact(&mut reply).await.unwrap();
    let udp_port = u16::from_be_bytes([reply[8], reply[9]]);
    let serial = owner.serial.lock().await;
    let epoch = super::super::telemetry::route_stamp().unwrap().0;
    let api = routes(Arc::new(RwLock::new(components.clone())));
    let payload = json!({"requestId":"5a5e938f-8655-42dc-b156-7aab8b89fdde","expectedCoreEpoch":epoch.to_string(),"network":{
        "mixed-port":ports[0],"port":ports[1],"socks-port":ports[2],"allow-lan":false,
        "bind-address":"127.0.0.1","authentication":[]}});
    let request = http::Request::builder().method("POST").uri("/aerove/network")
        .body(axum::body::Body::from(payload.to_string())).unwrap();
    let pending = tokio::spawn(async move { api.oneshot(request).await.unwrap() });
    tokio::time::timeout(Duration::from_secs(3), async {
        loop { if !owner.tasks.lock().await.is_empty() { break; } tokio::task::yield_now().await; }
    }).await.unwrap();
    owner.cancel.cancel();
    drop(serial);
    tokio::time::timeout(Duration::from_secs(3), owner.join()).await.unwrap().unwrap();
    assert_eq!(pending.await.unwrap().status(), StatusCode::SERVICE_UNAVAILABLE);
    let mut byte = [0u8; 1];
    assert_eq!(tokio::time::timeout(Duration::from_secs(3), control.read(&mut byte)).await.unwrap().unwrap(), 0);
    tokio::time::timeout(Duration::from_secs(3), async {
        loop { if UdpSocket::bind((Ipv4Addr::LOCALHOST, udp_port)).await.is_ok() { break; } tokio::time::sleep(Duration::from_millis(10)).await; }
    }).await.unwrap();
    stop(&components).await;
    for port in ports { assert!(crate::proxy::utils::try_create_dualstack_tcplistener((Ipv4Addr::LOCALHOST, port).into()).is_ok()); }
    assert!(ready_signal(Kind::Mixed, udp_port).is_err());
}
