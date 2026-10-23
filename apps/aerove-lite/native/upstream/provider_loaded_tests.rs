use crate::mobile::telemetry;
use super::*;
use crate::{app::remote_content_manager::{ProxyManager,healthcheck::HealthCheck,
    providers::{Provider,ProviderVehicle,ProviderVehicleType,proxy_provider::{ProxyProvider,proxy_set_provider::ProxySetProvider},
        rule_provider::{RuleProviderImpl,RuleSetBehavior,RuleSetFormat}}},
    mobile::provider_loaded::{Kind,Observer,Phase}};
use std::sync::{Mutex,atomic::{AtomicBool,Ordering}};
use serde_json::Value;

struct Vehicle { path:String, content:Mutex<Vec<u8>> }
#[async_trait::async_trait]
impl ProviderVehicle for Vehicle {
    async fn read(&self)->io::Result<Vec<u8>> {Ok(self.content.lock().unwrap().clone())}
    fn path(&self)->&str {&self.path}
    fn typ(&self)->ProviderVehicleType {ProviderVehicleType::Compatible}
}
fn last(bus:&Arc<crate::mobile::telemetry::Bus>,kind:Kind,name:&str)->crate::mobile::provider_loaded::Loaded {
    bus.providers.since(0).into_iter().find(|v|v.kind==kind&&v.name==name).unwrap()
}
async fn changed(bus:&Arc<crate::mobile::telemetry::Bus>,kind:Kind,name:&str,after:u64,want:bool) {
    let mut receiver=bus.providers.subscribe();
    tokio::time::timeout(Duration::from_secs(5),async {
        loop {
            drop(receiver.borrow_and_update());
            if bus.providers.since(after).iter().any(|v|v.kind==kind&&v.name==name&&v.changed==want) {break;}
            receiver.changed().await.unwrap();
        }
    }).await.unwrap();
}
async fn state() {
    let cancel=CancellationToken::new();let bus=crate::mobile::telemetry::Bus::new(cancel.clone());
    let a=Observer::for_bus(&bus,Kind::Proxy,"same");let b=Observer::for_bus(&bus,Kind::Rule,"same");
    let operation=a.begin().await;assert!(a.revision().is_err());
    let waiting=a.clone();let acquired=Arc::new(AtomicBool::new(false));let flag=acquired.clone();
    let second=tokio::spawn(async move {let operation=waiting.begin().await;flag.store(true,Ordering::SeqCst);operation.success(Phase::Update,false).unwrap();});
    tokio::task::yield_now().await;assert!(!acquired.load(Ordering::SeqCst));
    operation.success(Phase::Initial,true).unwrap();drop(operation);
    second.await.unwrap();let first=a.revision().unwrap().unwrap();assert_eq!(first,"2");
    b.begin().await.success(Phase::Initial,true).unwrap();
    assert_eq!(bus.providers.since(0).len(),2);assert_eq!(last(&bus,Kind::Rule,"same").revision,"3");
    let failed=a.begin().await;drop(failed);assert_eq!(a.revision().unwrap(),Some(first));
    let late=a.begin().await;cancel.cancel();
    let next=crate::mobile::telemetry::Bus::new(CancellationToken::new());
    Observer::for_bus(&next,Kind::Proxy,"same").begin().await.success(Phase::Initial,true).unwrap();
    late.success(Phase::Update,true).unwrap();drop(late);
    assert_eq!(last(&next,Kind::Proxy,"same").revision,"1");
    assert_eq!(last(&bus,Kind::Proxy,"same").revision,"2");assert!(a.revision().is_err());
    let removed=Observer::for_bus(&next,Kind::Rule,"removed");let removed_operation=removed.begin().await;removed.stop();
    removed_operation.success(Phase::Initial,true).unwrap();assert!(!next.providers.since(0).iter().any(|v|v.name=="removed"));
    for n in 0..70 {Observer::for_bus(&next,Kind::Rule,&format!("{n:02}{}","長".repeat(1020))).begin().await.success(Phase::Initial,true).unwrap();}
    let mut cursor=0;let mut delivered=0;
    loop {let batch=next.providers.batch(cursor).unwrap();if batch.is_empty(){break;}
        let frame=serde_json::to_vec(&serde_json::json!({"type":"providersLoaded","providers":&batch})).unwrap();assert!(frame.len()<=65536);
        assert!(batch.len()<=32);delivered+=batch.len();let next=batch.last().unwrap().revision.parse::<u64>().unwrap();assert!(next>cursor);cursor=next;
    }
    assert_eq!(delivered,71);
}
async fn manual() {
    let (components,directory)=components(None,"DIRECT").await;
    let bus=telemetry::source().unwrap();let manager=ProxyManager::new(components.dns_resolver.clone(),None);
    let nodes=|name:&str|format!("proxies: [{{name: {name}, type: direct}}]").into_bytes();
    let vehicle=Arc::new(Vehicle {path:directory.path().join("proxy.yaml").to_str().unwrap().into(),content:Mutex::new(nodes("one"))});
    let provider=ProxySetProvider::new("proxy".into(),Duration::ZERO,vehicle.clone(),
        HealthCheck::new(vec![],"invalid://fixture/".into(),0,true,manager),None).unwrap();
    assert_eq!(provider.loaded_revision().unwrap(),None);
    provider.initialize().await.unwrap();let initial=last(&bus,Kind::Proxy,"proxy");
    assert_eq!(serde_json::to_value(&initial).unwrap()["phase"],"initial");assert!(initial.changed);
    let first=initial.revision.parse::<u64>().unwrap();
    provider.update().await.unwrap();let same=last(&bus,Kind::Proxy,"proxy");assert!(!same.changed);assert!(same.revision.parse::<u64>().unwrap()>first);
    assert_eq!(provider.loaded_revision().unwrap(),Some(same.revision.clone()));
    *vehicle.content.lock().unwrap()=nodes("two");provider.update().await.unwrap();
    assert_eq!(provider.proxies().await[0].name(),"two");let updated=last(&bus,Kind::Proxy,"proxy");assert!(updated.changed);
    *vehicle.content.lock().unwrap()=b"broken: []".to_vec();assert!(provider.update().await.is_err());
    assert_eq!(provider.loaded_revision().unwrap(),Some(updated.revision));
    let inline=RuleProviderImpl::new("inline".into(),RuleSetBehavior::Domain,RuleSetFormat::Text,None,None,None,None,Some(vec!["example.com".into()]));
    inline.initialize().await.unwrap();let initial=last(&bus,Kind::Rule,"inline");assert!(initial.changed);
    inline.update().await.unwrap();assert_eq!(inline.loaded_revision().unwrap(),Some(initial.revision));
    let plain=crate::app::remote_content_manager::providers::proxy_provider::plain_provider::PlainProvider::new(
        "internal".into(),vec![components.outbound_manager.get_outbound("DIRECT").await.unwrap()],HealthCheck::new(vec![],"invalid://fixture/".into(),0,true,ProxyManager::new(components.dns_resolver.clone(),None))).unwrap();
    plain.initialize().await.unwrap();plain.update().await.unwrap();assert_eq!(plain.loaded_revision().unwrap(),None);
    assert!(!bus.providers.since(0).iter().any(|v|v.name=="internal"));
    components.stop_all().await;bus.stop().await.unwrap();
}
async fn automatic(file:bool) {
    let (components,directory)=components(None,"DIRECT").await;let bus=telemetry::source().unwrap();
    let path=directory.path().join("rules.txt");
    let memory=Arc::new(Vehicle {path:path.to_str().unwrap().into(),content:Mutex::new(b"example.com".to_vec())});
    let vehicle:crate::app::remote_content_manager::providers::ThreadSafeProviderVehicle=if file {
        std::fs::write(&path,b"example.com").unwrap();
        Arc::new(crate::app::remote_content_manager::providers::file_vehicle::Vehicle::new(path.to_str().unwrap()))
    }else{memory.clone()};
    let provider=RuleProviderImpl::new("auto".into(),RuleSetBehavior::Domain,RuleSetFormat::Text,
        Some(if file{Duration::ZERO}else{Duration::from_millis(25)}),Some(vehicle),None,None,None);
    provider.initialize().await.unwrap();let initial=last(&bus,Kind::Rule,"auto").revision.parse().unwrap();
    if file {std::fs::write(&path,b"other.example.com").unwrap();}else{*memory.content.lock().unwrap()=b"other.example.com".to_vec();}
    changed(&bus,Kind::Rule,"auto",initial,true).await;
    let before=last(&bus,Kind::Rule,"auto").revision.parse().unwrap();
    if file {std::fs::write(&path,b"other.example.com").unwrap();}
    changed(&bus,Kind::Rule,"auto",before,false).await;
    drop(provider);components.stop_all().await;bus.stop().await.unwrap();
}
struct Snapshot { observer:Arc<Observer>, entered:Notify, release:Notify }
#[async_trait::async_trait]
impl Provider for Snapshot {
    fn name(&self)->&str {"race"}
    fn vehicle_type(&self)->ProviderVehicleType {ProviderVehicleType::Http}
    fn typ(&self)->crate::app::remote_content_manager::providers::ProviderType {crate::app::remote_content_manager::providers::ProviderType::Rule}
    async fn initialize(&self)->io::Result<()> {Ok(())}
    async fn update(&self)->io::Result<()> {Ok(())}
    fn loaded_revision(&self)->Result<Option<String>,()> {self.observer.revision()}
    async fn as_map(&self)->std::collections::HashMap<String,Box<dyn erased_serde::Serialize+Send>> {
        self.entered.notify_one();self.release.notified().await;std::collections::HashMap::new()
    }
}
async fn snapshot() {
    let bus=crate::mobile::telemetry::Bus::new(CancellationToken::new());let observer=Observer::for_bus(&bus,Kind::Rule,"race");
    observer.begin().await.success(Phase::Initial,true).unwrap();
    let provider=Arc::new(Snapshot {observer:observer.clone(),entered:Notify::new(),release:Notify::new()});
    provider.release.notify_one();
    let response=crate::app::api::provider_snapshot(provider.as_ref()).await;
    assert_eq!(response.status(),StatusCode::OK);
    let bytes=axum::body::to_bytes(response.into_body(),1024).await.unwrap();assert_eq!(serde_json::from_slice::<Value>(&bytes).unwrap()["revision"],"1");
    provider.entered.notified().await;
    let operation=observer.begin().await;
    assert_eq!(crate::app::api::provider_snapshot(provider.as_ref()).await.status(),StatusCode::CONFLICT);drop(operation);
    let copy=provider.clone();let get=tokio::spawn(async move {crate::app::api::provider_snapshot(copy.as_ref()).await});
    provider.entered.notified().await;observer.begin().await.success(Phase::Update,false).unwrap();provider.release.notify_one();
    let response=get.await.unwrap();assert_eq!(response.status(),StatusCode::CONFLICT);
    let bytes=axum::body::to_bytes(response.into_body(),1024).await.unwrap();assert_eq!(serde_json::from_slice::<Value>(&bytes).unwrap()["code"],"provider_changed");
}
async fn http_rows() {
    use tower::ServiceExt;
    let directory=tempfile::tempdir().unwrap();
    std::fs::write(directory.path().join("provider.yaml"),b"proxies: [{type: direct, name: named-direct}]").unwrap();
    let source="mode: direct\nmmdb: null\nasn-mmdb: null\ngeosite: null\ndns: {enable: false}\nproxy-providers: {p: {type: file, path: provider.yaml, interval: 0}}\nrules: ['MATCH,DIRECT']\n";
    let components=Arc::new(crate::create_components(directory.path().to_owned(),crate::Config::Str(source.into()).try_parse_strict().unwrap()).await.unwrap());
    let holder=Arc::new(RwLock::new(components.clone()));
    let routes=crate::app::api::provider_test_routes(holder.clone()).with_state(Arc::new(crate::app::api::AppState {
        log_source_tx:crate::mobile::context().unwrap().logs.clone(),components:holder,
    }));
    let list=routes.clone().oneshot(request("/")).await.unwrap();assert_eq!(list.status(),StatusCode::OK);
    let list:Value=serde_json::from_slice(&axum::body::to_bytes(list.into_body(),64*1024).await.unwrap()).unwrap();
    let single=routes.clone().oneshot(request("/p/")).await.unwrap();assert_eq!(single.status(),StatusCode::OK);
    let row:Value=serde_json::from_slice(&axum::body::to_bytes(single.into_body(),64*1024).await.unwrap()).unwrap();
    assert_eq!(row["proxies"],list["providers"]["p"]["proxies"]);
    assert_eq!(row["proxies"][0]["name"],"named-direct");assert!(row["proxies"][0]["type"].is_string());assert!(row["proxies"][0]["history"].is_array());
    let initial=row["revision"].as_str().unwrap().parse::<u64>().unwrap();assert!(initial>0);
    let put=routes.clone().oneshot(Request::builder().method(Method::PUT).uri("/p/").body(axum::body::Body::empty()).unwrap()).await.unwrap();
    assert_eq!(put.status(),StatusCode::ACCEPTED);
    let single=routes.oneshot(request("/p/")).await.unwrap();assert_eq!(single.status(),StatusCode::OK);
    let row:Value=serde_json::from_slice(&axum::body::to_bytes(single.into_body(),64*1024).await.unwrap()).unwrap();
    assert!(row["revision"].as_str().unwrap().parse::<u64>().unwrap()>initial);assert_eq!(row["proxies"][0]["name"],"named-direct");
    components.stop_all().await;telemetry::source().unwrap().stop().await.unwrap();
}
async fn websocket() {
    use futures::StreamExt;
    use tokio_tungstenite::{connect_async,tungstenite::client::IntoClientRequest};
    let bus=telemetry::source().unwrap();
    for (kind,name) in [(Kind::Proxy,"one"),(Kind::Proxy,"two"),(Kind::Rule,"one")] {
        Observer::capture(kind,name).unwrap().begin().await.success(Phase::Initial,true).unwrap();
    }
    let cancel=CancellationToken::new();let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();
    let routes=telemetry::routes(cancel.clone()).route_layer(crate::app::api::AuthMiddlewareLayer::new("provider-secret".into()));
    let stop=cancel.clone();let server=tokio::spawn(async move {axum::serve(listener,routes).with_graceful_shutdown(stop.cancelled_owned()).await.unwrap();});
    let mut request=format!("ws://{address}/aerove/telemetry").into_client_request().unwrap();
    request.headers_mut().insert(http::header::AUTHORIZATION,"Bearer provider-secret".parse().unwrap());
    let (mut ws,_)=connect_async(request).await.unwrap();
    for expected in ["traffic","groupsChanged","providersLoaded"] {
        let value=tokio::time::timeout(Duration::from_secs(2),ws.next()).await.unwrap().unwrap().unwrap().into_text().unwrap();
        let value:Value=serde_json::from_str(&value).unwrap();assert_eq!(value["type"],expected);
        if expected=="providersLoaded" {assert_eq!(value["providers"].as_array().unwrap().len(),3);}
    }
    let observer=Observer::capture(Kind::Rule,"three").unwrap();observer.begin().await.success(Phase::Initial,true).unwrap();
    let value=tokio::time::timeout(Duration::from_secs(2),ws.next()).await.unwrap().unwrap().unwrap().into_text().unwrap();
    let value:Value=serde_json::from_str(&value).unwrap();assert_eq!(value["providers"].as_array().unwrap().len(),1);assert_eq!(value["providers"][0]["name"],"three");
    ws.close(None).await.unwrap();drop(ws);cancel.cancel();tokio::time::timeout(Duration::from_secs(2),server).await.unwrap().unwrap();bus.stop().await.unwrap();
}
pub(super) async fn run(case:&str) {
    match case {"provider-loaded-state"=>state().await,"provider-loaded-manual"=>manual().await,
        "provider-loaded-interval"=>automatic(false).await,"provider-loaded-file"=>automatic(true).await,
        "provider-loaded-http"=>http_rows().await,"provider-loaded-snapshot"=>snapshot().await,"provider-loaded-websocket"=>websocket().await,_=>panic!("unknown provider case")}
}
