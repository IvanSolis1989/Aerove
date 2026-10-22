//! Real HEAD connection and shared candidate-scheduler regressions.
use std::{io, net::SocketAddr, sync::{Arc, atomic::{AtomicUsize, Ordering}}, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt, DuplexStream}, time::Instant};
use crate::{app::remote_content_manager::aerove_probe_head_exchange, proxy::utils::aerove_dial_candidates};

async fn headers(stream: &mut DuplexStream) -> io::Result<String> {
    let mut data = Vec::new();
    while !data.ends_with(b"\r\n\r\n") {
        data.push(stream.read_u8().await?);
        assert!(data.len() < 8192);
    }
    Ok(String::from_utf8(data).unwrap())
}

async fn probe(case: &str) {
    let (client, mut server) = tokio::io::duplex(8192);
    let requests = Arc::new(AtomicUsize::new(0));
    let count = requests.clone();
    let case = case.to_owned();
    let second = !matches!(case.as_str(), "probe-single" | "probe-first-failure" | "probe-cancel");
    let server_case = case.clone();
    let owner = tokio::spawn(async move {
        let first = headers(&mut server).await.unwrap();
        assert!(first.starts_with("HEAD /generate_204?fixture=1 HTTP/1.1\r\n"));
        assert!(first.to_ascii_lowercase().contains("host: fixture.test:8080\r\n"));
        assert!(!first.to_ascii_lowercase().contains("connection: close"));
        count.fetch_add(1, Ordering::SeqCst);
        if matches!(server_case.as_str(), "probe-first-failure" | "probe-cancel") {
            let mut remaining = Vec::new();
            server.read_to_end(&mut remaining).await.unwrap();
            return;
        }
        tokio::time::sleep(Duration::from_millis(80)).await;
        server.write_all(b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n").await.unwrap();
        if second {
            let request = headers(&mut server).await.unwrap();
            assert!(request.starts_with("HEAD /generate_204?fixture=1 HTTP/1.1\r\n"));
            count.fetch_add(1, Ordering::SeqCst);
            match server_case.as_str() {
                "probe-second-failure" => return,
                "probe-second-timeout" => {
                    let mut remaining = Vec::new();
                    server.read_to_end(&mut remaining).await.unwrap();
                    return;
                }
                _ => {}
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
            server.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n").await.unwrap();
        }
        let mut remaining = Vec::new();
        server.read_to_end(&mut remaining).await.unwrap();
        assert!(remaining.is_empty());
    });
    let started = Instant::now();
    let limit = if case == "probe-second-timeout" { 180 } else { 1000 };
    let future = aerove_probe_head_exchange(client, "http://fixture.test:8080/generate_204?fixture=1",
        case != "probe-single", started, started + Duration::from_millis(limit));
    if case == "probe-cancel" {
        assert!(tokio::time::timeout(Duration::from_millis(30), future).await.is_err());
    } else {
        let result = future.await;
        if case == "probe-first-failure" { assert!(result.is_err()); }
        else {
            let (selected, overall) = result.unwrap();
            assert!(selected >= Duration::from_millis(1));
            if case == "probe-single" { assert!(selected >= Duration::from_millis(70)); }
            if matches!(case.as_str(), "probe-second-failure" | "probe-second-timeout") {
                assert!(selected >= Duration::from_millis(70));
                if case == "probe-second-timeout" { assert!(overall > selected + Duration::from_millis(30)); }
            }
            if case == "probe-reuse" { assert!(overall > selected + Duration::from_millis(60)); }
        }
    }
    tokio::time::timeout(Duration::from_secs(2), owner).await.unwrap().unwrap();
    assert_eq!(requests.load(Ordering::SeqCst), if second { 2 } else { 1 });
}

struct Active(Arc<AtomicUsize>);
impl Drop for Active { fn drop(&mut self) { self.0.fetch_sub(1, Ordering::SeqCst); } }

async fn dial(case: &str) {
    let active = Arc::new(AtomicUsize::new(0));
    let maximum = Arc::new(AtomicUsize::new(0));
    let calls = Arc::new(AtomicUsize::new(0));
    let endpoints: Vec<SocketAddr> = if case == "dial-families" {
        vec!["[::1]:1".parse().unwrap(), "127.0.0.1:2".parse().unwrap()]
    } else { (1..=3).map(|port| SocketAddr::from(([127,0,0,1],port))).collect() };
    let concurrent = !matches!(case, "dial-serial" | "dial-families");
    let future = aerove_dial_candidates(&endpoints, concurrent, |addr| {
        let active = active.clone(); let maximum = maximum.clone(); let calls = calls.clone();
        async move {
            let now = active.fetch_add(1, Ordering::SeqCst) + 1;
            maximum.fetch_max(now, Ordering::SeqCst);
            calls.fetch_add(1, Ordering::SeqCst);
            let _active = Active(active);
            tokio::time::sleep(Duration::from_millis(if addr.port() == 1 { 80 } else { 10 })).await;
            if case == "dial-all-fail" || addr.port() == 1 { Err(io::Error::other("fixture refused")) }
            else { Ok(addr.port()) }
        }
    });
    if matches!(case, "dial-cancel" | "dial-timeout") {
        // All attempts must still be pending when the caller cancels.
        let future = aerove_dial_candidates(&endpoints, concurrent, |_| {
            let active = active.clone(); async move {
                active.fetch_add(1, Ordering::SeqCst); let _active = Active(active);
                std::future::pending::<io::Result<u16>>().await
            }
        });
        assert!(tokio::time::timeout(Duration::from_millis(20), future).await.is_err());
    } else if case == "dial-all-fail" {
        let error = future.await.unwrap_err().to_string();
        for addr in &endpoints { assert!(error.contains(&addr.to_string())); }
    } else {
        assert_eq!(future.await.unwrap(), 2);
        match case {
            "dial-parallel" => { assert_eq!(calls.load(Ordering::SeqCst), 3); assert_eq!(maximum.load(Ordering::SeqCst), 3); }
            "dial-serial" => { assert_eq!(calls.load(Ordering::SeqCst), 2); assert_eq!(maximum.load(Ordering::SeqCst), 1); }
            "dial-families" => { assert_eq!(calls.load(Ordering::SeqCst), 2); assert_eq!(maximum.load(Ordering::SeqCst), 2); }
            _ => unreachable!(),
        }
    }
    assert_eq!(active.load(Ordering::SeqCst), 0);
    assert!(aerove_dial_candidates::<u16,_,_>(&[], concurrent, |_| async { Ok(0) }).await.is_err());
}

async fn history() {
    use crate::{app::{dns::SystemResolver, remote_content_manager::{ProxyManager, healthcheck::HealthCheck,
        providers::proxy_provider::{PlainProvider, ArcProxyProvider}}}, proxy::{AnyOutboundHandler, direct, group::selector}};
    let listener = tokio::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let url = format!("http://{}/generate_204", listener.local_addr().unwrap());
    let requests = Arc::new(AtomicUsize::new(0));
    let count = requests.clone();
    let owner = tokio::spawn(async move {
        for connection in 0..4 {
            let (mut stream, _) = listener.accept().await.unwrap();
            for _ in 0..if connection < 3 { 2 } else { 1 } {
                let mut bytes = Vec::new();
                while !bytes.ends_with(b"\r\n\r\n") { bytes.push(stream.read_u8().await.unwrap()); assert!(bytes.len() < 8192); }
                assert!(bytes.starts_with(b"HEAD /generate_204 HTTP/1.1\r\n"));
                count.fetch_add(1, Ordering::SeqCst);
                stream.write_all(b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n").await.unwrap();
            }
            let mut end = Vec::new(); stream.read_to_end(&mut end).await.unwrap();
        }
    });
    let original = crate::app::remote_content_manager::get_unified_delay();
    struct Restore(bool);
    impl Drop for Restore { fn drop(&mut self) { crate::app::remote_content_manager::set_unified_delay(self.0); } }
    let _restore = Restore(original);
    crate::app::remote_content_manager::set_unified_delay(true);
    let manager = ProxyManager::new(Arc::new(SystemResolver::new(true).unwrap()), None);
    let node: AnyOutboundHandler = Arc::new(direct::Handler::new("probe-node"));
    let first = manager.url_test(node.clone(), &url, Some(Duration::from_secs(2))).await.unwrap();
    assert_eq!(manager.last_delay("probe-node").await, Some(first.0));
    assert_eq!(manager.delay_history("probe-node").await.last().unwrap().delay, first.0);
    let hc = HealthCheck::new(vec![node.clone()], url.clone(), 0, false, manager.clone());
    let provider: ArcProxyProvider = Arc::new(PlainProvider::new("probe-provider".into(), vec![node.clone()],
        HealthCheck::new(vec![node.clone()], url.clone(), 0, false, manager.clone())).unwrap());
    let group: AnyOutboundHandler = Arc::new(selector::Handler::new(selector::HandlerOptions {
        name: "probe-group".into(), ..Default::default()
    }, vec![provider], None).await);
    let second = manager.url_test(group, &url, Some(Duration::from_secs(2))).await.unwrap();
    assert_eq!(manager.last_delay("probe-group").await, Some(second.0));
    hc.check(true).await;
    assert_eq!(manager.delay_history("probe-node").await.len(), 2);
    crate::app::remote_content_manager::set_unified_delay(false);
    let final_result = manager.url_test(node, &url, Some(Duration::from_secs(2))).await.unwrap();
    assert_eq!(manager.last_delay("probe-node").await, Some(final_result.0));
    assert_eq!(manager.delay_history("probe-node").await.len(), 3);
    tokio::time::timeout(Duration::from_secs(3), owner).await.unwrap().unwrap();
    assert_eq!(requests.load(Ordering::SeqCst), 7);
}

/// Called from the existing protected regression fixture, which owns one Context.
pub async fn run(case: &str) {
    match case {
        "probe-reuse" | "probe-single" | "probe-first-failure" | "probe-second-failure" | "probe-second-timeout" | "probe-cancel" => probe(case).await,
        "dial-parallel" | "dial-serial" | "dial-families" | "dial-all-fail" | "dial-cancel" | "dial-timeout" => dial(case).await,
        "probe-history" => history().await,
        _ => panic!("unknown probe/dial regression {case}"),
    }
}
