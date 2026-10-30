use aerove_meow_core::{api::CoreApi, control::DelayQuery, providers::OwnedLoadScope};
use axum::{Router, body::to_bytes, response::Response};
use meow_tunnel::Tunnel;
use std::{sync::Arc, time::Duration, path::PathBuf};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};
use tokio_util::sync::CancellationToken;

const PRIVATE: &str = "group-delay-private-secret-00000001";
const USER: &str = "group-delay-user-secret";
struct Fixture {
    api: Arc<CoreApi>, tunnel: Tunnel, scope: Arc<OwnedLoadScope>, origin: TcpListener,
    address: std::net::SocketAddr, cache: PathBuf, cancel: CancellationToken,
    serving: tokio::task::JoinHandle<()>, events: tokio::task::JoinHandle<()>,
}
impl Fixture {
    async fn new() -> Self { Self::with_http_proxy(None).await }
    async fn with_http_proxy(proxy_port:Option<u16>) -> Self {
        let origin=TcpListener::bind("127.0.0.1:0").await.unwrap(); let target=origin.local_addr().unwrap();
        let cache=std::env::temp_dir().join(format!("aerove-group-delay-{}-{}",std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir(&cache).unwrap();
        let scope=OwnedLoadScope::new();
        let yaml=match proxy_port {
            Some(port)=>format!("strict: true\nproxies: [{{name: SLOW, type: http, server: 127.0.0.1, port: {port}}}]\nproxy-groups:\n - {{name: AUTO, type: fallback, proxies: [DIRECT,SLOW], url: 'http://{target}/health', interval: 0}}\nrules: ['MATCH,AUTO']\n"),
            None=>format!("strict: true\nproxy-groups:\n - {{name: AUTO, type: fallback, proxies: [DIRECT,REJECT], url: 'http://{target}/health', interval: 0}}\nrules: ['MATCH,AUTO']\n"),
        };
        let config=meow_config::load_config_from_str_owned_with_cache(&yaml,&scope,Some(&cache)).await.unwrap();
        let tunnel=Tunnel::new_with_slot(config.dns.resolver_slot.clone());
        tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
        let api=Arc::new(CoreApi::assemble_with_epoch(&config,tunnel.clone(),"127.0.0.1:12345".parse().unwrap(),
            PRIVATE,Some(USER),Router::new(),123,CancellationToken::new()).unwrap());
        tunnel.update_routing(config.proxies,config.rules,config.dialer_registry);
        let events=api.control.activate_groups(&scope).unwrap();
        api.control.select("AUTO","DIRECT").await.unwrap();
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();
        let cancel=CancellationToken::new();let serving={let api=api.clone();let cancel=cancel.clone();tokio::spawn(async move{api.serve_user(listener,cancel).await.unwrap();})};
        Self{api,tunnel,scope,origin,address,cache,cancel,serving,events}
    }
    fn query(&self)->DelayQuery { DelayQuery{url:Some(format!("http://{}/observed-policy",self.origin.local_addr().unwrap())),timeout:Some("2000".into()),expected:None,core_epoch:Some("123".into())} }
    fn path(&self,group:bool)->String {format!("/{}/{}{}?url=http%3A%2F%2F127.0.0.1%3A{}%2Fobserved-policy&timeout=2000&coreEpoch=123",
        if group{"group"}else{"proxies"},if group{"AUTO"}else{"DIRECT"},"/delay",self.origin.local_addr().unwrap().port())}
    fn fixed(&self)->String {self.tunnel.route_snapshot().proxies["AUTO"].selection().unwrap().fixed().unwrap()}
    fn persisted(&self)->String {let value:serde_json::Value=serde_json::from_slice(&std::fs::read(self.cache.join("selector-cache.json")).unwrap()).unwrap();value["AUTO"].as_str().unwrap().to_owned()}
    async fn accept(&self)->TcpStream {let(mut stream,_)=tokio::time::timeout(Duration::from_secs(5),self.origin.accept()).await.unwrap().unwrap();let mut head=Vec::new();while !head.ends_with(b"\r\n\r\n"){head.push(stream.read_u8().await.unwrap());assert!(head.len()<8192);}stream}
    async fn close(self){self.api.control.cancel();self.scope.close().await.unwrap();if let Err(error)=self.events.await{assert!(error.is_cancelled());}self.cancel.cancel();self.api.cancel();self.serving.await.unwrap();self.api.join().await;std::fs::remove_file(self.cache.join("selector-cache.json")).unwrap();std::fs::remove_dir(self.cache).unwrap();}
}
async fn get(address:std::net::SocketAddr,path:String)->String {
    let mut stream=TcpStream::connect(address).await.unwrap();
    stream.write_all(format!("GET {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {USER}\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut response=String::new();tokio::time::timeout(Duration::from_secs(5),stream.read_to_string(&mut response)).await.unwrap().unwrap();response
}
async fn reply(stream:&mut TcpStream){stream.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();}
async fn eof(stream:&mut TcpStream){let mut tail=Vec::new();tokio::time::timeout(Duration::from_secs(5),stream.read_to_end(&mut tail)).await.unwrap().unwrap();assert!(tail.is_empty());}
async fn json(response:Response)->serde_json::Value {serde_json::from_slice(&to_bytes(response.into_body(),4096).await.unwrap()).unwrap()}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn public_group_delay_unpins_in_gate_persists_before_probe_and_acks_same_stamp() {
    let fixture=Fixture::new().await;let before=fixture.api.control.snapshot().await.unwrap();
    let direct=fixture.tunnel.route_snapshot().proxies["DIRECT"].clone();let history=direct.health().delay_history().len();
    for (url,expected) in [("ftp://invalid".to_owned(),None),(fixture.query().url.unwrap(),Some("2-a".to_owned()))] {
        let response=fixture.api.control.group_delay("AUTO".into(),DelayQuery{url:Some(url),expected,timeout:Some("2000".into()),core_epoch:Some("123".into())}).await;
        assert_eq!(response.status(),400);assert_eq!(fixture.fixed(),"DIRECT");assert_eq!(fixture.persisted(),"DIRECT");assert_eq!(fixture.api.control.snapshot().await.unwrap(),before);
    }
    let response=fixture.api.control.group_delay("AUTO".into(),DelayQuery{core_epoch:Some("122".into()),..fixture.query()}).await;
    assert_eq!(response.status(),409);assert_eq!(fixture.fixed(),"DIRECT");assert_eq!(fixture.persisted(),"DIRECT");
    let request=tokio::spawn(get(fixture.address,fixture.path(true)));
    let mut peer=fixture.accept().await;
    assert_eq!(fixture.fixed(),"");assert_eq!(fixture.persisted(),"");
    let unpinned=fixture.api.control.snapshot().await.unwrap();
    assert_eq!(unpinned.route_version.parse::<u64>().unwrap(),before.route_version.parse::<u64>().unwrap()+1);
    assert_eq!(direct.health().delay_history().len(),history);
    reply(&mut peer).await;
    let response=request.await.unwrap();assert!(response.starts_with("HTTP/1.1 200"),"{response}");
    let body:serde_json::Value=serde_json::from_str(response.split("\r\n\r\n").nth(1).unwrap()).unwrap();
    assert_eq!(body.as_object().unwrap().len(),1);assert!(body["DIRECT"].is_u64());
    let after=fixture.api.control.snapshot().await.unwrap();assert_eq!(after,unpinned);
    assert!(response.contains("x-aerove-core-epoch: 123\r\n"));assert!(response.contains(&format!("x-aerove-route-version: {}\r\n",after.route_version)));
    assert_eq!(direct.health().delay_history().len(),history+1);
    let delays=fixture.api.control.delay_history(Some("123")).unwrap();assert!(delays.complete);
    assert_eq!(delays.delays.len(),2);
    assert!(delays.delays.iter().all(|row|row.url==fixture.query().url.as_deref().unwrap()));
    assert!(delays.delays.iter().any(|row|row.name=="DIRECT" && row.value==body["DIRECT"].as_i64().unwrap() as i32));
    assert!(delays.delays.iter().any(|row|row.name=="REJECT" && row.value==-2));
    fixture.close().await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn caller_abort_and_session_cancel_keep_committed_unpin_but_publish_no_partial_health() {
    for session_cancel in [false,true] {
        let fixture=Fixture::new().await;let before=fixture.api.control.snapshot().await.unwrap();
        let route=fixture.tunnel.route_snapshot();let direct=route.proxies["DIRECT"].clone();let reject=route.proxies["REJECT"].clone();
        let histories=(direct.health().delay_history().len(),reject.health().delay_history().len());
        let request={let control=fixture.api.control.clone();let query=fixture.query();tokio::spawn(async move{control.group_delay("AUTO".into(),query).await})};
        let mut peer=fixture.accept().await;assert_eq!(fixture.fixed(),"");assert_eq!(fixture.persisted(),"");
        let stamp=fixture.api.control.plane.stamp();assert_eq!(stamp.1,before.route_version.parse::<u64>().unwrap()+1);
        if session_cancel {fixture.api.control.cancel();let response=request.await.unwrap();assert_eq!(response.status(),410);}
        else {request.abort();assert!(request.await.unwrap_err().is_cancelled());}
        eof(&mut peer).await;
        assert_eq!((direct.health().delay_history().len(),reject.health().delay_history().len()),histories);
        assert_eq!(fixture.api.control.plane.stamp(),stamp);assert_eq!(fixture.fixed(),"");fixture.close().await;
    }
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn newer_node_intent_and_policy_epoch_fence_whole_old_group_result() {
    let fixture=Fixture::new().await;let direct=fixture.tunnel.route_snapshot().proxies["DIRECT"].clone();let before=direct.health().delay_history().len();
    let first={let control=fixture.api.control.clone();let query=fixture.query();tokio::spawn(async move{control.group_delay("AUTO".into(),query).await})};
    let mut old=fixture.accept().await;
    let node=tokio::spawn(get(fixture.address,fixture.path(false)));let mut current=fixture.accept().await;reply(&mut current).await;
    assert!(node.await.unwrap().starts_with("HTTP/1.1 200"));reply(&mut old).await;
    assert_eq!(first.await.unwrap().status(),409);assert_eq!(direct.health().delay_history().len(),before+1);
    fixture.api.control.select("AUTO","DIRECT").await.unwrap();
    let second={let control=fixture.api.control.clone();let query=fixture.query();tokio::spawn(async move{control.group_delay("AUTO".into(),query).await})};
    let mut old=fixture.accept().await;
    let (stamp,retired)=fixture.api.control.plane.commit_policy(123,true,||()).unwrap();assert!(stamp.0>123);assert!(retired.is_some());
    reply(&mut old).await;let response=second.await.unwrap();assert_eq!(response.status(),409);let body=json(response).await;assert_eq!(body["error"],"Superseded");
    assert_eq!(direct.health().delay_history().len(),before+1);assert_eq!(fixture.api.control.plane.stamp(),stamp);fixture.close().await;
}

async fn proxy_probe(listener:&TcpListener)->TcpStream {
    let(mut stream,_)=tokio::time::timeout(Duration::from_secs(5),listener.accept()).await.unwrap().unwrap();
    let mut header=Vec::new();while !header.ends_with(b"\r\n\r\n"){header.push(stream.read_u8().await.unwrap());assert!(header.len()<8192);}
    assert!(header.starts_with(b"CONNECT "));
    stream.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await.unwrap();
    header.clear();while !header.ends_with(b"\r\n\r\n"){header.push(stream.read_u8().await.unwrap());assert!(header.len()<8192);}
    assert!(header.starts_with(b"GET "));stream
}
async fn put_slow(address:std::net::SocketAddr)->String {
    let mut stream=TcpStream::connect(address).await.unwrap();let body="{\"name\":\"SLOW\"}";
    stream.write_all(format!("PUT /proxies/AUTO HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {USER}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).as_bytes()).await.unwrap();
    let mut response=String::new();tokio::time::timeout(Duration::from_secs(5),stream.read_to_string(&mut response)).await.unwrap().unwrap();response
}
async fn complete_group(fixture:&Fixture,proxy:&TcpListener)->String {
    let request=tokio::spawn(get(fixture.address,fixture.path(true)));
    let mut direct=fixture.accept().await;let mut proxied=proxy_probe(proxy).await;
    reply(&mut direct).await;reply(&mut proxied).await;request.await.unwrap()
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn fallback_owned_selection_records_its_real_group_url_without_relabeling_the_failed_node_test() {
    let proxy=TcpListener::bind("127.0.0.1:0").await.unwrap();
    let fixture=Fixture::with_http_proxy(Some(proxy.local_addr().unwrap().port())).await;
    let failed_path=fixture.path(false).replace("/proxies/DIRECT/","/proxies/SLOW/");
    let failed=tokio::spawn(get(fixture.address,failed_path));
    let mut peer=proxy_probe(&proxy).await;
    peer.write_all(b"HTTP/1.1 500 Failure\r\nConnection: close\r\n\r\n").await.unwrap();drop(peer);
    assert!(failed.await.unwrap().starts_with("HTTP/1.1 503"));
    let selected=tokio::spawn(put_slow(fixture.address));
    let mut peer=proxy_probe(&proxy).await;reply(&mut peer).await;drop(peer);
    assert!(selected.await.unwrap().starts_with("HTTP/1.1 204"));
    let history=fixture.api.control.delay_history(Some("123")).unwrap();
    let base=format!("http://{}",fixture.origin.local_addr().unwrap());
    assert_eq!(history.delays.len(),2);assert!(history.complete);
    assert!(history.delays.iter().any(|row|row.name=="SLOW" && row.url==format!("{base}/observed-policy") && row.value==-2));
    assert!(history.delays.iter().any(|row|row.name=="SLOW" && row.url==format!("{base}/health") && row.value>0));
    fixture.close().await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn newer_group_unpin_fences_older_slow_http_put_even_when_already_auto() {
    for initially_fixed in [true,false] {
        let proxy=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let fixture=Fixture::with_http_proxy(Some(proxy.local_addr().unwrap().port())).await;
        if !initially_fixed {assert!(complete_group(&fixture,&proxy).await.starts_with("HTTP/1.1 200"));assert_eq!(fixture.fixed(),"");}
        let slow=fixture.tunnel.route_snapshot().proxies["SLOW"].clone();slow.health().record_delay(0);
        let old=tokio::spawn(put_slow(fixture.address));let mut pending=proxy_probe(&proxy).await;
        assert!(!old.is_finished());let before=fixture.api.control.plane.stamp();
        let response=complete_group(&fixture,&proxy).await;assert!(response.starts_with("HTTP/1.1 200"),"{response}");
        assert_eq!(fixture.fixed(),"");assert_eq!(fixture.persisted(),"");
        let committed=fixture.api.control.plane.stamp();assert_eq!(committed.1,before.1+u64::from(initially_fixed));
        let history=slow.health().delay_history().len();reply(&mut pending).await;
        let response=old.await.unwrap();assert!(response.starts_with("HTTP/1.1 409"),"{response}");
        assert_eq!(slow.health().delay_history().len(),history);assert_eq!(fixture.fixed(),"");assert_eq!(fixture.persisted(),"");
        assert_eq!(fixture.api.control.plane.stamp(),committed);fixture.close().await;
    }
}

async fn delete_proxy(address:std::net::SocketAddr,path:&str)->String {
    let mut stream=TcpStream::connect(address).await.unwrap();
    stream.write_all(format!("DELETE {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {USER}\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut response=String::new();tokio::time::timeout(Duration::from_secs(5),stream.read_to_string(&mut response)).await.unwrap().unwrap();response
}
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn explicit_delete_unpin_is_cas_guarded_persisted_no_probe_and_noop_has_no_fake_revision(){
    let fixture=Fixture::new().await;let before=fixture.api.control.plane.stamp();
    for (path,status) in [("/proxies/AUTO?coreEpoch=0123",400),("/proxies/AUTO?coreEpoch=122",409),("/proxies/missing",404),("/proxies/DIRECT",400)]{
        let response=delete_proxy(fixture.address,path).await;assert!(response.starts_with(&format!("HTTP/1.1 {status}")),"{response}");
        assert_eq!(fixture.fixed(),"DIRECT");assert_eq!(fixture.persisted(),"DIRECT");assert_eq!(fixture.api.control.plane.stamp(),before);
    }
    let direct=fixture.tunnel.route_snapshot().proxies["DIRECT"].clone();let history=direct.health().delay_history().len();
    let response=delete_proxy(fixture.address,"/proxies/AUTO?coreEpoch=123").await;assert!(response.starts_with("HTTP/1.1 204"),"{response}");
    let after=fixture.api.control.plane.stamp();assert_eq!(after,(123,before.1+1));assert_eq!(fixture.fixed(),"");assert_eq!(fixture.persisted(),"");
    assert!(response.contains(&format!("x-aerove-route-version: {}\r\n",after.1)));
    assert!(delete_proxy(fixture.address,"/proxies/AUTO").await.starts_with("HTTP/1.1 204"));assert_eq!(fixture.api.control.plane.stamp(),after);
    assert_eq!(direct.health().delay_history().len(),history);assert!(tokio::time::timeout(Duration::from_millis(100),fixture.origin.accept()).await.is_err());
    fixture.api.control.cancel();assert!(delete_proxy(fixture.address,"/proxies/AUTO").await.starts_with("HTTP/1.1 410"));assert_eq!(fixture.api.control.plane.stamp(),after);fixture.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn explicit_delete_auto_intent_fences_older_real_slow_put_without_health_publish(){
    for initially_fixed in [true,false]{
        let proxy=TcpListener::bind("127.0.0.1:0").await.unwrap();let fixture=Fixture::with_http_proxy(Some(proxy.local_addr().unwrap().port())).await;
        if !initially_fixed{assert!(delete_proxy(fixture.address,"/proxies/AUTO").await.starts_with("HTTP/1.1 204"));}
        let slow=fixture.tunnel.route_snapshot().proxies["SLOW"].clone();slow.health().record_delay(0);
        let old=tokio::spawn(put_slow(fixture.address));let mut pending=proxy_probe(&proxy).await;assert!(!old.is_finished());
        let before=fixture.api.control.plane.stamp();let history=slow.health().delay_history().len();
        let response=delete_proxy(fixture.address,"/proxies/AUTO?coreEpoch=123").await;assert!(response.starts_with("HTTP/1.1 204"),"{response}");
        let after=fixture.api.control.plane.stamp();assert_eq!(after.1,before.1+u64::from(initially_fixed));assert_eq!(fixture.fixed(),"");assert_eq!(fixture.persisted(),"");
        reply(&mut pending).await;let response=old.await.unwrap();assert!(response.starts_with("HTTP/1.1 409"),"{response}");
        assert_eq!(slow.health().delay_history().len(),history);assert_eq!(fixture.api.control.plane.stamp(),after);assert_eq!(fixture.fixed(),"");fixture.close().await;
    }
}
