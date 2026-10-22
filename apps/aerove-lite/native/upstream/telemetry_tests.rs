use super::*;
use crate::{
    app::remote_content_manager::{
        DelayHistory, ProxyManager, healthcheck::HealthCheck,
        providers::{Provider, ProviderVehicle, ProviderVehicleType, proxy_provider::{
            ProxyProvider, plain_provider::PlainProvider, proxy_set_provider::ProxySetProvider,
        }},
    },
    mobile::telemetry::{self, Traffic},
    proxy::group::{GroupProxyAPIResponse, selector::SelectorControl},
};

pub(super) async fn counters() {
    let bus = telemetry::source().unwrap();
    let (mut traffic, mut groups) = bus.subscribe();
    let (components, _directory) = components(None, "DIRECT").await;
    tokio::time::timeout(Duration::from_secs(2), traffic.changed()).await.unwrap().unwrap();
    drop(traffic.borrow_and_update());
    drop(groups.borrow_and_update());
    components.statistics_manager.push_uploaded(123);
    components.statistics_manager.push_downloaded(456);
    tokio::time::timeout(Duration::from_secs(2), traffic.changed()).await.unwrap().unwrap();
    assert_eq!(*traffic.borrow_and_update(), Traffic {
        up: 123, down: 456, upload_total: 123, download_total: 456,
    });
    assert!(!groups.has_changed().unwrap());
    let mut second = bus.subscribe().0;
    components.statistics_manager.push_uploaded(7);
    components.statistics_manager.push_downloaded(9);
    tokio::time::timeout(Duration::from_secs(2), traffic.changed()).await.unwrap().unwrap();
    assert!(second.has_changed().unwrap());
    assert_eq!(*traffic.borrow_and_update(), *second.borrow_and_update());
    assert_eq!(*traffic.borrow(), Traffic {
        up: 7, down: 9, upload_total: 130, download_total: 465,
    });
    tokio::time::timeout(Duration::from_secs(2), traffic.changed()).await.unwrap().unwrap();
    assert_eq!(*traffic.borrow(), Traffic {
        up: 0, down: 0, upload_total: 130, download_total: 465,
    });
    assert!(!groups.has_changed().unwrap());
    components.stop_all().await;
    let cancel = crate::mobile::context().unwrap().cancel.clone();
    cancel.cancel();
    drop(traffic.borrow_and_update());
    bus.publish(Traffic { up: 99, ..Default::default() });
    telemetry::groups_changed();
    assert!(!traffic.has_changed().unwrap());
    assert!(!groups.has_changed().unwrap());
}

pub(super) async fn groups() {
    let bus = telemetry::source().unwrap();
    let (components, _directory) = components(None, "DIRECT").await;
    let selector = components.outbound_manager.get_selector_control("GLOBAL").unwrap();
    selector.select("DIRECT").await.unwrap();
    let mut revision = bus.subscribe().1;
    let before = *revision.borrow_and_update();
    selector.select("REJECT").await.unwrap();
    assert_eq!(*revision.borrow_and_update(), before + 1);
    selector.select("REJECT").await.unwrap();
    assert!(selector.select("not-a-proxy").await.is_err());
    assert!(!revision.has_changed().unwrap());
    let manager = ProxyManager::new(components.dns_resolver.clone(), None);
    manager.report_alive("DIRECT", false, None).await;
    assert!(revision.has_changed().unwrap());
    drop(revision.borrow_and_update());
    manager.report_alive("DIRECT", false, None).await;
    assert!(!revision.has_changed().unwrap());
    manager.report_alive("DIRECT", true, None).await;
    drop(revision.borrow_and_update());
    let server = Fixture::new(response(""), false, None).await;
    let direct = components.outbound_manager.get_outbound("DIRECT").await.unwrap();
    manager.url_test(direct, &server.source().url, Some(Duration::from_secs(1))).await.unwrap();
    assert_eq!(manager.delay_history("DIRECT").await.len(), 1);
    assert!(!revision.has_changed().unwrap());
    tokio::time::pause();
    advance(Duration::from_secs(4)).await;
    assert!(!revision.has_changed().unwrap());
    advance(Duration::from_secs(1)).await;
    revision.changed().await.unwrap();
    components.stop_all().await;
    bus.stop().await.unwrap();
}

pub(super) async fn urltest() {
    let (components, _directory) = components(None, "DIRECT").await;
    let manager = ProxyManager::new(components.dns_resolver.clone(), None);
    let direct = components.outbound_manager.get_outbound("DIRECT").await.unwrap();
    let reject = components.outbound_manager.get_outbound("REJECT").await.unwrap();
    let proxies = vec![direct, reject];
    let health = HealthCheck::new(proxies.clone(), "invalid://fixture/".into(), 0, true, manager.clone());
    let provider = Arc::new(PlainProvider::new("fixture".into(), proxies, health).unwrap());
    let handler = crate::proxy::group::urltest::Handler::new(
        crate::proxy::group::urltest::HandlerOptions { name: "AUTO".into(), udp: true, ..Default::default() },
        0, vec![provider.clone()], manager.clone(),
    );
    let mut revision = telemetry::source().unwrap().subscribe().1;
    handler.select("REJECT").await.unwrap();
    assert!(revision.has_changed().unwrap());
    drop(revision.borrow_and_update());
    handler.select("REJECT").await.unwrap();
    assert!(!revision.has_changed().unwrap());
    let server = Fixture::new(response(""), false, None).await;
    manager.url_test(components.outbound_manager.get_outbound("DIRECT").await.unwrap(),
        &server.source().url, Some(Duration::from_secs(1))).await.unwrap();
    handler.select("").await.unwrap();
    drop(revision.borrow_and_update());
    handler.force_fastest();
    assert_eq!(handler.get_active_proxy().await.unwrap().name(), "DIRECT");
    assert!(revision.has_changed().unwrap());
    drop(revision.borrow_and_update());
    assert_eq!(handler.get_active_proxy().await.unwrap().name(), "DIRECT");
    assert!(!revision.has_changed().unwrap());
    let fallback = crate::proxy::group::fallback::Handler::new(
        crate::proxy::group::fallback::HandlerOptions { name: "FALLBACK".into(), udp: true, ..Default::default() },
        vec![provider], manager.clone(),
    );
    assert_eq!(fallback.get_active_proxy().await.unwrap().name(), "DIRECT");
    assert!(!revision.has_changed().unwrap());
    let history = manager.delay_history("DIRECT").await.pop().unwrap();
    manager.report_alive("DIRECT", false, Some(history)).await;
    assert!(!revision.has_changed().unwrap());
    assert_eq!(fallback.get_active_proxy().await.unwrap().name(), "REJECT");
    assert!(revision.has_changed().unwrap());
    drop(revision.borrow_and_update());
    assert_eq!(fallback.get_active_proxy().await.unwrap().name(), "REJECT");
    assert!(!revision.has_changed().unwrap());
    components.stop_all().await;
    telemetry::source().unwrap().stop().await.unwrap();
}

struct Vehicle { path: String, content: StdMutex<Vec<u8>> }

#[async_trait::async_trait]
impl ProviderVehicle for Vehicle {
    async fn read(&self) -> io::Result<Vec<u8>> { Ok(self.content.lock().unwrap().clone()) }
    fn path(&self) -> &str { &self.path }
    fn typ(&self) -> ProviderVehicleType { ProviderVehicleType::Compatible }
}

pub(super) async fn provider() {
    let (components, directory) = components(None, "DIRECT").await;
    let manager = ProxyManager::new(components.dns_resolver.clone(), None);
    let nodes = |name: &str| format!("proxies: [{{name: {name}, type: socks5, server: 192.0.2.1, port: 1080}}]").into_bytes();
    let vehicle = Arc::new(Vehicle {
        path: directory.path().join("provider.yaml").to_str().unwrap().into(),
        content: StdMutex::new(nodes("one")),
    });
    let health = HealthCheck::new(vec![], "invalid://fixture/".into(), 0, true, manager.clone());
    let provider = ProxySetProvider::new("fixture".into(), Duration::ZERO, vehicle.clone(), health, None).unwrap();
    let mut revision = telemetry::source().unwrap().subscribe().1;
    provider.initialize().await.unwrap();
    assert!(revision.has_changed().unwrap());
    health_result(&manager, "one").await;
    drop(revision.borrow_and_update());
    provider.update().await.unwrap();
    assert!(!revision.has_changed().unwrap());
    *vehicle.content.lock().unwrap() = nodes("two");
    provider.update().await.unwrap();
    assert_eq!(provider.proxies().await[0].name(), "two");
    assert!(revision.has_changed().unwrap());
    health_result(&manager, "two").await;
    drop(revision.borrow_and_update());
    *vehicle.content.lock().unwrap() = b"proxies: [{name: invalid, type: imaginary}]".to_vec();
    assert!(provider.update().await.is_err());
    assert_eq!(provider.proxies().await[0].name(), "two");
    assert!(!revision.has_changed().unwrap());
    components.stop_all().await;
    telemetry::source().unwrap().stop().await.unwrap();
}

async fn measured_delay() -> (ProxyManager, DelayHistory) {
    let (components, _directory) = components(None, "DIRECT").await;
    let manager = ProxyManager::new(components.dns_resolver.clone(), None);
    let server = Fixture::new(response(""), false, None).await;
    let direct = components.outbound_manager.get_outbound("DIRECT").await.unwrap();
    manager.url_test(direct, &server.source().url, Some(Duration::from_secs(1))).await.unwrap();
    let history = manager.delay_history("DIRECT").await.pop().unwrap();
    components.stop_all().await;
    (manager, history)
}

async fn advance(duration: Duration) {
    tokio::time::advance(duration).await;
    for _ in 0..4 { tokio::task::yield_now().await; }
}

pub(super) async fn delay_budget() {
    let (manager, history) = measured_delay().await;
    let bus = telemetry::source().unwrap();
    let mut revision = bus.subscribe().1;
    let baseline = *revision.borrow_and_update();
    tokio::time::pause();
    for index in 0..10 {
        manager.report_alive(&format!("measured-{index}"), index % 2 == 0, Some(history.clone())).await;
        assert!(!revision.has_changed().unwrap());
        if index < 9 { advance(Duration::from_secs(1)).await; }
        assert!(!revision.has_changed().unwrap());
    }
    advance(Duration::from_millis(4999)).await;
    assert!(!revision.has_changed().unwrap());
    advance(Duration::from_millis(1)).await;
    revision.changed().await.unwrap();
    assert_eq!(*revision.borrow_and_update(), baseline + 1);
    advance(Duration::from_secs(60)).await;
    assert!(!revision.has_changed().unwrap());
    manager.report_alive("another-batch", true, Some(history)).await;
    advance(Duration::from_secs(4)).await;
    telemetry::groups_changed();
    assert_eq!(*revision.borrow_and_update(), baseline + 2);
    advance(Duration::from_millis(999)).await;
    assert!(!revision.has_changed().unwrap());
    advance(Duration::from_millis(1)).await;
    revision.changed().await.unwrap();
    assert_eq!(*revision.borrow_and_update(), baseline + 3);
    bus.stop().await.unwrap();
}

pub(super) async fn delay_stop() {
    let (manager, history) = measured_delay().await;
    let bus = telemetry::source().unwrap();
    let mut revision = bus.subscribe().1;
    let baseline = *revision.borrow_and_update();
    tokio::time::pause();
    manager.report_alive("pending", false, Some(history.clone())).await;
    advance(Duration::from_millis(4999)).await;
    assert!(!revision.has_changed().unwrap());
    bus.stop().await.unwrap();
    assert!(telemetry::source().is_none());
    advance(Duration::from_secs(60)).await;
    manager.report_alive("after-stop", true, Some(history)).await;
    telemetry::delay_changed();
    telemetry::groups_changed();
    advance(Duration::from_secs(5)).await;
    assert!(!revision.has_changed().unwrap());
    assert_eq!(*revision.borrow(), baseline);
    bus.stop().await.unwrap();
}

async fn health_result(manager: &ProxyManager, name: &str) {
    tokio::time::timeout(Duration::from_secs(2), async {
        while manager.delay_history(name).await.is_empty() { tokio::task::yield_now().await; }
    }).await.unwrap();
}

type Client = tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

async fn frame(client: &mut Client) -> Value {
    let frame = tokio::time::timeout(Duration::from_secs(2), client.next()).await.unwrap().unwrap().unwrap();
    serde_json::from_str(frame.into_text().unwrap().as_ref()).unwrap()
}

pub(super) async fn websocket() {
    use tokio_tungstenite::{connect_async, tungstenite::{Error, client::IntoClientRequest}};
    let first = crate::mobile::context().unwrap();
    let bus = telemetry::source().unwrap();
    let cancel = CancellationToken::new();
    let router = telemetry::routes(cancel.clone())
        .route_layer(crate::app::api::AuthMiddlewareLayer::new("session-secret".into()));
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("ws://{}/aerove/telemetry", listener.local_addr().unwrap());
    let stop = cancel.clone();
    let server = tokio::spawn(async move {
        axum::serve(listener, router).with_graceful_shutdown(stop.cancelled_owned()).await.unwrap();
    });
    for token in [None, Some("Bearer old-session-secret")] {
        let mut request = url.clone().into_client_request().unwrap();
        if let Some(token) = token { request.headers_mut().insert(http::header::AUTHORIZATION, token.parse().unwrap()); }
        let Error::Http(response) = connect_async(request).await.unwrap_err() else { panic!("expected auth rejection") };
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }
    let mut request = url.clone().into_client_request().unwrap();
    request.headers_mut().insert(http::header::AUTHORIZATION, "Bearer session-secret".parse().unwrap());
    let (mut client, _) = connect_async(request).await.unwrap();
    assert_eq!(frame(&mut client).await, json!({"type":"traffic","up":0,"down":0,"uploadTotal":0,"downloadTotal":0}));
    assert_eq!(frame(&mut client).await, json!({"type":"groupsChanged","revision":0}));
    bus.publish(Traffic { up: 12, down: 34, upload_total: 56, download_total: 78 });
    assert_eq!(frame(&mut client).await, json!({"type":"traffic","up":12,"down":34,"uploadTotal":56,"downloadTotal":78}));
    for _ in 0..3 { telemetry::groups_changed(); }
    assert_eq!(frame(&mut client).await, json!({"type":"groupsChanged","revision":3}));
    let next_cancel = CancellationToken::new();
    *crate::mobile::CONTEXT.write().unwrap() = Some(Arc::new(crate::mobile::Context {
        protector: first.protector.clone(), bootstrap: vec![], events: first.events.clone(),
            network_dns:std::sync::RwLock::new(None),dns_enabled:false,dns_hijack_all:false,
        logs: first.logs.clone(), level: first.level, cancel: next_cancel.clone(),
        telemetry: telemetry::Bus::new(next_cancel), network: None,
    }));
    telemetry::source().unwrap().publish(Traffic { up: 999, ..Default::default() });
    telemetry::groups_changed();
    assert!(tokio::time::timeout(Duration::from_millis(40), client.next()).await.is_err());
    first.cancel.cancel();
    let closed = tokio::time::timeout(Duration::from_secs(2), client.next()).await.unwrap();
    assert!(!matches!(closed, Some(Ok(tokio_tungstenite::tungstenite::Message::Text(_)))));
    cancel.cancel();
    tokio::time::timeout(Duration::from_secs(2), server).await.unwrap().unwrap();
}
