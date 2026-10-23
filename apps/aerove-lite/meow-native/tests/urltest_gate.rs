use aerove_meow_core::{api::CoreApi,providers::OwnedLoadScope};
use axum::Router;
use meow_common::Metadata;
use meow_tunnel::Tunnel;
use std::{path::PathBuf,sync::{Arc,atomic::{AtomicBool,AtomicU64,Ordering}},time::Duration};
use tokio::{io::{AsyncReadExt,AsyncWriteExt},net::{TcpListener,TcpStream}};
use tokio_util::sync::CancellationToken;
const PRIVATE:&str="urltest-gate-private-secret-00000001";
const USER:&str="urltest-gate-user-secret";
struct Peer { delay:Arc<AtomicU64>, failed:Arc<AtomicBool>, address:std::net::SocketAddr, task:tokio::task::JoinHandle<()> }
async fn head(stream:&mut TcpStream)->Vec<u8>{let mut h=Vec::new();while !h.ends_with(b"\r\n\r\n"){h.push(stream.read_u8().await.unwrap());assert!(h.len()<8192);}h}
async fn peer(scope:&Arc<OwnedLoadScope>,marker:u8,millis:u64)->Peer{
    let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();
    let delay=Arc::new(AtomicU64::new(millis));let failed=Arc::new(AtomicBool::new(false));
    let weak=Arc::downgrade(scope);let latency=delay.clone();let rejected=failed.clone();
    let task=scope.spawn_owned(async move{
        loop{
            let(mut stream,_)=listener.accept().await.unwrap();let delay=latency.load(Ordering::Acquire);let failed=rejected.load(Ordering::Acquire);
            let Some(scope)=weak.upgrade()else{return;};
            scope.spawn_owned(async move{
                let h=head(&mut stream).await;assert!(h.starts_with(b"CONNECT "));
                if failed {stream.write_all(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await.unwrap();return;}
                stream.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await.unwrap();
                if h.starts_with(b"CONNECT route.invalid:80 "){stream.write_all(&[marker]).await.unwrap();return;}
                let h=head(&mut stream).await;assert!(h.starts_with(b"GET /health ")||h.starts_with(b"HEAD /health "));
                tokio::time::sleep(Duration::from_millis(delay)).await;
                stream.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();
            }).unwrap();
        }
    }).unwrap();Peer{delay,failed,address,task}
}
struct Fixture{api:Arc<CoreApi>,tunnel:Tunnel,scope:Arc<OwnedLoadScope>,a:Peer,b:Peer,cache:PathBuf,address:std::net::SocketAddr,cancel:CancellationToken,serving:tokio::task::JoinHandle<()>,events:tokio::task::JoinHandle<()>,specs:Vec<meow_common::HealthCheckSpec>}
impl Fixture{
    async fn new(interval:u64,tolerance:u16)->Self{
        let scope=OwnedLoadScope::new();let a=peer(&scope,b'A',250).await;let b=peer(&scope,b'B',5).await;
        let cache=std::env::temp_dir().join(format!("aerove-urltest-gate-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));std::fs::create_dir(&cache).unwrap();
        let yaml=format!("strict: true\nproxies:\n - {{name: A,type: http,server: 127.0.0.1,port: {}}}\n - {{name: B,type: http,server: 127.0.0.1,port: {}}}\nproxy-groups:\n - {{name: AUTO,type: url-test,proxies: [A,B],tolerance: {tolerance},url: 'http://probe.invalid/health',interval: {interval},lazy: false}}\n - {{name: OUTER,type: select,proxies: [AUTO,REJECT]}}\nrules: ['MATCH,OUTER']\n",a.address.port(),b.address.port());
        let config=meow_config::load_config_from_str_owned_with_cache(&yaml,&scope,Some(&cache)).await.unwrap();
        let specs=meow_config::extract_health_check_specs(config.raw.proxy_groups.as_deref().unwrap_or(&[]));
        let tunnel=Tunnel::new_with_slot(config.dns.resolver_slot.clone());tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
        let api=Arc::new(CoreApi::assemble_with_epoch(&config,tunnel.clone(),"127.0.0.1:12345".parse().unwrap(),PRIVATE,Some(USER),Router::new(),800,CancellationToken::new()).unwrap());
        tunnel.update_routing(config.proxies,config.rules,config.dialer_registry);let events=api.control.activate_groups(&scope).unwrap();
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();let cancel=CancellationToken::new();
        let serving={let api=api.clone();let cancel=cancel.clone();tokio::spawn(async move{api.serve_user(listener,cancel).await.unwrap();})};
        Self{api,tunnel,scope,a,b,cache,address,cancel,serving,events,specs}
    }
    async fn command(&self,method:&str,path:&str,body:&str)->String{
        let mut stream=TcpStream::connect(self.address).await.unwrap();stream.write_all(format!("{method} {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {USER}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).as_bytes()).await.unwrap();
        let mut response=String::new();tokio::time::timeout(Duration::from_secs(5),stream.read_to_string(&mut response)).await.unwrap().unwrap();response
    }
    async fn group(&self){let r=self.command("GET","/group/AUTO/delay?url=http%3A%2F%2Fprobe.invalid%2Fhealth&timeout=2000&coreEpoch=800","").await;assert!(r.starts_with("HTTP/1.1 200"),"{r}");}
    async fn wire(&self,expected:u8){
        let before=self.api.control.plane.stamp();let metadata=Metadata{host:"route.invalid".into(),dst_port:80,..Default::default()};
        let selected=self.tunnel.inner().resolve_proxy(&metadata).await.unwrap().adapter;
        let mut stream=tokio::time::timeout(Duration::from_secs(5),selected.dial_tcp(&metadata)).await.unwrap().unwrap();
        assert_eq!(tokio::time::timeout(Duration::from_secs(5),stream.read_u8()).await.unwrap().unwrap(),expected);
        let group=self.tunnel.route_snapshot().proxies["AUTO"].clone();group.support_udp();let _ = group.unwrap_proxy(&metadata,false);
        assert_eq!(self.api.control.plane.stamp(),before);
    }
    fn fixed(&self)->String{self.tunnel.route_snapshot().proxies["AUTO"].selection().unwrap().fixed().unwrap()}
    fn persisted(&self)->String{let value:serde_json::Value=serde_json::from_slice(&std::fs::read(self.cache.join("selector-cache.json")).unwrap()).unwrap();value["AUTO"].as_str().unwrap().into()}
    async fn close(self){
        self.api.control.cancel();self.scope.close().await.unwrap();
        for task in [self.a.task,self.b.task,self.events]{if let Err(e)=task.await{assert!(e.is_cancelled());}}
        self.cancel.cancel();self.api.cancel();self.serving.await.unwrap();self.api.join().await;
        if self.cache.join("selector-cache.json").exists(){std::fs::remove_file(self.cache.join("selector-cache.json")).unwrap();}std::fs::remove_dir(self.cache).unwrap();
    }
}
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn actual_http_health_commits_urltest_before_dial_and_keeps_tolerance_put_unpin_coherent(){
    let f=Fixture::new(0,1000).await;let before=f.api.control.snapshot().await.unwrap();assert_eq!(before.picks["AUTO"],"A");
    f.group().await;let auto=f.api.control.snapshot().await.unwrap();assert_eq!(auto.picks["AUTO"],"B");assert_eq!(auto.core_epoch,"800");assert_eq!(auto.picks_version.parse::<u64>().unwrap(),before.picks_version.parse::<u64>().unwrap()+1);f.wire(b'B').await;
    f.a.delay.store(5,Ordering::Release);f.b.delay.store(50,Ordering::Release);f.group().await;assert_eq!(f.api.control.snapshot().await.unwrap(),auto);f.wire(b'B').await;
    let r=f.command("PUT","/proxies/AUTO",r#"{"name":"A"}"#).await;assert!(r.starts_with("HTTP/1.1 204"),"{r}");assert_eq!(f.fixed(),"A");assert_eq!(f.persisted(),"A");f.wire(b'A').await;
    let fixed=f.api.control.snapshot().await.unwrap();assert_eq!(fixed.picks["AUTO"],"A");assert_eq!(fixed.picks_version.parse::<u64>().unwrap(),auto.picks_version.parse::<u64>().unwrap()+1);
    // URLTest's existing fixed member survives temporary failure and resumes
    // after recovery. Health publication and every route change share gate.
    f.a.failed.store(true,Ordering::Release);let r=f.command("GET","/proxies/A/delay?url=http%3A%2F%2Fprobe.invalid%2Fhealth&timeout=2000","").await;assert!(r.starts_with("HTTP/1.1 503"),"{r}");assert_eq!(f.fixed(),"A");assert_eq!(f.api.control.snapshot().await.unwrap().picks["AUTO"],"B");f.wire(b'B').await;
    f.a.failed.store(false,Ordering::Release);let r=f.command("GET","/proxies/A/delay?url=http%3A%2F%2Fprobe.invalid%2Fhealth&timeout=2000","").await;assert!(r.starts_with("HTTP/1.1 200"),"{r}");assert_eq!(f.fixed(),"A");assert_eq!(f.api.control.snapshot().await.unwrap().picks["AUTO"],"A");f.wire(b'A').await;
    let before=f.api.control.plane.stamp();let r=f.command("DELETE","/proxies/AUTO?coreEpoch=800","").await;assert!(r.starts_with("HTTP/1.1 204"),"{r}");assert_eq!(f.fixed(),"");assert_eq!(f.persisted(),"");let after=f.api.control.snapshot().await.unwrap();assert_eq!(after.picks["AUTO"],"B");assert_eq!(after.picks_version.parse::<u64>().unwrap(),before.1+1);f.wire(b'B').await;f.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn actual_periodic_urltest_nested_route_events_and_session_owned_health_stop_join(){
    let f=Fixture::new(1,0).await;assert_eq!(f.specs.len(),1);assert_eq!(f.specs[0].interval_secs,1);
    let mut events=f.api.control.subscribe_groups();let weak=f.tunnel.weak_inner();let spec=f.specs[0].clone();
    let health=f.scope.spawn_owned(meow_tunnel::health_check::run_health_check_loop(weak,spec)).unwrap();
    tokio::time::timeout(Duration::from_secs(5),async{
        loop{events.recv().await.unwrap();if f.api.control.snapshot().await.unwrap().picks["AUTO"]=="B"{break;}}
    }).await.unwrap();
    // A single A->B route commit advances the defined initial version 0 to 1.
    // Exercise two further actual periodic changes before asserting >1,
    // rather than inventing a revision for unchanged health publication.
    for (a_millis,b_millis,pick,marker) in [(5,250,"A",b'A'),(250,5,"B",b'B')] {
        let before=f.api.control.snapshot().await.unwrap();
        f.a.delay.store(a_millis,Ordering::Release);f.b.delay.store(b_millis,Ordering::Release);
        tokio::time::timeout(Duration::from_secs(5),async{
            loop{events.recv().await.unwrap();if f.api.control.snapshot().await.unwrap().picks["AUTO"]==pick{break;}}
        }).await.unwrap();
        assert!(f.api.control.snapshot().await.unwrap().picks_version.parse::<u64>().unwrap()>before.picks_version.parse::<u64>().unwrap());
        f.wire(marker).await;
    }
    let snapshot=f.api.control.snapshot().await.unwrap();assert_eq!(snapshot.picks["OUTER"],"AUTO");assert!(snapshot.picks_version.parse::<u64>().unwrap()>1);f.wire(b'B').await;
    let a=f.tunnel.route_snapshot().proxies["A"].clone();let b=f.tunnel.route_snapshot().proxies["B"].clone();
    f.api.control.cancel();f.scope.close().await.unwrap();assert!(health.await.unwrap_err().is_cancelled());
    let history=(a.health().delay_history().len(),b.health().delay_history().len());let stamp=f.api.control.plane.stamp();
    a.health().set_alive(false);assert_eq!((a.health().delay_history().len(),b.health().delay_history().len()),history);assert_eq!(f.api.control.plane.stamp(),stamp);f.close().await;
}
