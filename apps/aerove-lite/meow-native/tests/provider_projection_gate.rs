#![cfg(any(target_os="linux",target_os="android",target_os="macos"))]
#[path="support/provider_general.rs"]mod fixture;
#[path="support/resource_roots.rs"]mod root_fixture;
use aerove_meow_core::{api::CoreApi,providers::{self,ControlPlane,LoadContext,OwnedLoadScope,OwnedWork}};
use meow_config::provider_events::{ProviderObserver,ProviderLease,ProviderIdentity,ProviderKind};
use std::{sync::{Arc,atomic::{AtomicBool,Ordering}},time::Duration};
use tokio::{sync::Notify,time::timeout};
use tokio_util::sync::CancellationToken;
struct Pause { enabled:AtomicBool,replace:AtomicBool,provider:std::sync::Mutex<Option<std::sync::Weak<meow_config::proxy_provider::ProxyProvider>>>,entered:Notify,release:std::sync::Mutex<std::sync::mpsc::Receiver<()>> }
struct Observer { plane:Arc<ControlPlane>,pause:Arc<Pause> }
struct Lease { actual:Arc<dyn ProviderLease>,pause:Arc<Pause> }
impl ProviderObserver for Observer {
    fn validate(&self,kind:ProviderKind,name:&str)->Result<(),String>{self.plane.validate(kind,name)}
    fn begin(&self,identity:&ProviderIdentity)->Result<Arc<dyn ProviderLease>,String>{Ok(Arc::new(Lease {actual:self.plane.begin(identity)?,pause:self.pause.clone()}))}
}
impl ProviderLease for Lease {
    fn normalize_proxy_nodes(&self,document:&serde_yaml::Value,nodes:&mut Vec<std::collections::HashMap<String,serde_yaml::Value>>)->Result<(),String>{self.actual.normalize_proxy_nodes(document,nodes)}
    fn validate_proxy_members(&self,members:&[Arc<dyn meow_common::Proxy>],dialers:&[String])->Result<(),String>{self.actual.validate_proxy_members(members,dialers)}
    fn validate_proxy_projection(&self,projection:&meow_config::ProxyProviderProjection,dialers:&[String])->Result<(),String>{
        self.actual.validate_proxy_projection(projection,dialers)?;
        if self.pause.enabled.load(Ordering::Acquire){self.pause.entered.notify_one();self.pause.release.lock().unwrap().recv_timeout(Duration::from_secs(5)).unwrap();}Ok(())
    }
    fn commit_proxy_projection(&self,projection:&meow_config::ProxyProviderProjection,dialers:&[String],install:&mut dyn FnMut())->Result<(),String>{
        if self.pause.replace.load(Ordering::Acquire) {
            let provider=self.pause.provider.lock().unwrap().as_ref().unwrap().upgrade().unwrap();
            let alternate=provider.project_members(projection.members().to_vec());
            assert!(!alternate.same_candidate(projection));
            return self.actual.commit_proxy_projection(&alternate,dialers,install);
        }
        self.actual.commit_proxy_projection(projection,dialers,install)
    }
    fn commit_proxy(&self,members:&[Arc<dyn meow_common::Proxy>],dialers:&[String],install:&mut dyn FnMut())->Result<(),String>{self.actual.commit_proxy(members,dialers,install)}
    fn commit(&self,changed:bool,install:&mut dyn FnMut())->Result<(),String>{self.actual.commit(changed,install)}
}
struct Context(Arc<Observer>);
impl LoadContext for Context {fn wrap(&self,work:OwnedWork)->OwnedWork{work}fn run_blocking(&self,work:Box<dyn FnOnce()+Send>){work();}fn provider_observer(&self)->Option<Arc<dyn ProviderObserver>>{Some(self.0.clone())}}
async fn rejected_after_real_preflight(mode:&str){
    let roots=root_fixture::Roots::new();let mut servers=fixture::Servers::new();let a=servers.proxy().await;let b=servers.proxy().await;let feed=servers.feed(format!("proxies: [{{name: old,type: http,server: 127.0.0.1,port: {},dialer-proxy: FRONT}}]\n",b.address.port())).await;
    *feed.userinfo.write().await=Some("upload=10; download=20; total=1000; expire=4000000000".into());
    let cancel=CancellationToken::new();let plane=ControlPlane::new(700,cancel.clone()).unwrap();let(send,receive)=std::sync::mpsc::channel();let pause=Arc::new(Pause{enabled:AtomicBool::new(false),replace:AtomicBool::new(false),provider:std::sync::Mutex::new(None),entered:Notify::new(),release:std::sync::Mutex::new(receive)});let observer=Arc::new(Observer{plane:plane.clone(),pause:pause.clone()});let scope=OwnedLoadScope::with_context(Arc::new(Context(observer)));
    let yaml=format!("strict: true\nproxies: [{{name: FRONT,type: http,server: 127.0.0.1,port: {}}}]\nproxy-providers: {{feed: {{type: http,url: 'http://{}/feed',path: gate-cache.yaml,interval: 0}}}}\nproxy-groups: [{{name: PICK,type: select,use: [feed]}}]\nrules: ['MATCH,PICK']\n",a.address.port(),feed.address);
    let config=timeout(Duration::from_secs(5),providers::load_with_cache(&yaml,&scope,&cancel,Some(&roots.cache))).await.unwrap().unwrap();let registry=config.provider_dialer_registry.clone();let tunnel=meow_tunnel::Tunnel::new_with_slot(config.dns.resolver_slot.clone());tunnel.set_dialer_registry(registry.clone());let api=Arc::new(CoreApi::assemble_with_plane(&config,tunnel.clone(),"127.0.0.1:12345".parse().unwrap(),"projection-gate-private-secret-0000001",None,axum::Router::new(),plane.clone()).unwrap());tunnel.update_routing(config.proxies,config.rules,config.dialer_registry);let groups=api.control.activate_groups(&scope).unwrap();api.activate_providers().unwrap();let provider=api.state.proxy_providers.get("feed").unwrap().value().clone();*pause.provider.lock().unwrap()=Some(Arc::downgrade(&provider));
    let cache=std::fs::read(roots.cache.join("gate-cache.yaml")).unwrap();let userinfo=std::fs::read(roots.cache.join("gate-cache.yaml.subscription-userinfo")).unwrap();let receipt=plane.provider_baseline()[0].revision.clone();let before=plane.stamp();let old=provider.proxies()[0].clone();let old_health=serde_json::to_value(old.health().delay_history()).unwrap();
    if mode=="captured_epoch" {
        let requests=feed.requests.load(Ordering::Relaxed);
        let policy=meow_common::probe_policy::ProbeScope::new(Arc::new(OldPolicy(before.0-1)));
        let snapshot=policy.snapshot().unwrap();
        let error=snapshot.scope(provider.refresh_owned()).await.unwrap_err();assert!(error.contains("staleProviderEpoch"),"{error}");
        assert_eq!(feed.requests.load(Ordering::Relaxed),requests);assert_eq!(std::fs::read(roots.cache.join("gate-cache.yaml")).unwrap(),cache);assert_eq!(plane.stamp(),before);assert_eq!(plane.provider_baseline()[0].revision,receipt);
        api.control.cancel();api.cancel();scope.cancel();scope.close().await.unwrap();if let Err(error)=groups.await{assert!(error.is_cancelled());}api.join().await;servers.close().await;return;
    }
    *feed.payload.write().await=format!("proxies: [{{name: fresh,type: http,server: 127.0.0.1,port: {},dialer-proxy: FRONT}}]\n",b.address.port());*feed.userinfo.write().await=Some("upload=900; download=901; total=9999; expire=4000000001".into());pause.enabled.store(true,Ordering::Release);let pending={let provider=provider.clone();tokio::spawn(async move{provider.refresh_owned().await})};timeout(Duration::from_secs(5),pause.entered.notified()).await.unwrap();
    // This wraps the actual Native lease. It only pauses after successful
    // preflight; the factory, cache paths, final gate and error are production.
    let expected=match mode {"epoch"=>plane.commit_policy(before.0,true,||()).unwrap().0,"aba"=>{registry.publish(tunnel.route_snapshot().proxies.clone());before},"cancel"=>{api.control.cancel();before},"candidate"=>{pause.replace.store(true,Ordering::Release);before},"intent"=>{let mode=if tunnel.mode()==meow_common::TunnelMode::Global{meow_common::TunnelMode::Rule}else{meow_common::TunnelMode::Global};api.control.set_mode(mode).await.unwrap();plane.stamp()},_=>unreachable!()};send.send(()).unwrap();let error=timeout(Duration::from_secs(5),pending).await.unwrap().unwrap().unwrap_err();assert!(error.contains(match mode{"epoch"=>"staleProviderEpoch","aba"|"candidate"=>"staleProviderProjection","intent"=>"staleProviderIntent",_=>"cancelled"}),"{error}");
    assert_eq!(std::fs::read(roots.cache.join("gate-cache.yaml")).unwrap(),cache);assert_eq!(std::fs::read(roots.cache.join("gate-cache.yaml.subscription-userinfo")).unwrap(),userinfo);assert_eq!(plane.provider_baseline()[0].revision,receipt);assert_eq!(plane.stamp(),expected);assert!(Arc::ptr_eq(&provider.proxies()[0],&old));assert_eq!(serde_json::to_value(old.health().delay_history()).unwrap(),old_health);assert!(a.targets.lock().unwrap().is_empty());assert!(b.targets.lock().unwrap().is_empty());
    api.control.cancel();api.cancel();scope.cancel();timeout(Duration::from_secs(5),scope.close()).await.unwrap().unwrap();if let Err(error)=timeout(Duration::from_secs(5),groups).await.unwrap(){assert!(error.is_cancelled());}api.join().await;servers.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=4)]async fn actual_projection_preflight_then_epoch_change_rejects_before_cache_and_receipt(){rejected_after_real_preflight("epoch").await;}
#[tokio::test(flavor="multi_thread",worker_threads=4)]async fn actual_projection_preflight_then_same_arc_registry_republish_rejects_before_cache_and_receipt(){rejected_after_real_preflight("aba").await;}
#[tokio::test(flavor="multi_thread",worker_threads=4)]async fn actual_projection_preflight_then_session_cancel_rejects_before_cache_and_receipt(){rejected_after_real_preflight("cancel").await;}

#[tokio::test(flavor="multi_thread",worker_threads=4)]async fn actual_projection_preflight_then_equal_members_different_candidate_token_is_rejected_before_cache(){rejected_after_real_preflight("candidate").await;}

struct OldPolicy(u64);
impl meow_common::probe_policy::ProbeSource for OldPolicy {
    fn snapshot(&self)->std::io::Result<Arc<meow_common::probe_policy::ProbePolicy>>{Ok(Arc::new(Default::default()))}
    fn snapshot_with_epoch(&self)->std::io::Result<(Arc<meow_common::probe_policy::ProbePolicy>,Option<u64>)>{Ok((self.snapshot()?,Some(self.0)))}
    fn cancelled(&self)->std::pin::Pin<Box<dyn std::future::Future<Output=()>+Send+'_>>{Box::pin(std::future::pending())}
}
#[tokio::test(flavor="multi_thread",worker_threads=4)]async fn captured_old_network_policy_epoch_rejects_provider_before_http_fetch(){rejected_after_real_preflight("captured_epoch").await;}

#[tokio::test(flavor="multi_thread",worker_threads=4)]async fn actual_projection_preflight_then_new_control_intent_rejects_before_cache_and_receipt(){rejected_after_real_preflight("intent").await;}
