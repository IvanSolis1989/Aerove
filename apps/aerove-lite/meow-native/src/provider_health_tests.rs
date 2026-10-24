use super::*;
use crate::{api::CoreApi, providers::{self, LoadContext, OwnedWork}};
use axum::Router;
use meow_config::provider_events::ProviderObserver;
use meow_tunnel::Tunnel;
use tokio::{io::{AsyncReadExt,AsyncWriteExt},net::{TcpListener,TcpStream},time::timeout};
use tokio_util::sync::CancellationToken;

struct Context(Arc<ControlPlane>);
impl LoadContext for Context {
    fn provider_observer(&self)->Option<Arc<dyn ProviderObserver>>{Some(self.0.clone())}
    fn wrap(&self,work:OwnedWork)->OwnedWork{work}
    fn run_blocking(&self,work:Box<dyn FnOnce()+Send>){work();}
}
struct Fixture { api:Arc<CoreApi>,plane:Arc<ControlPlane>,scope:Arc<OwnedLoadScope>,tunnel:Tunnel,
    files:std::path::PathBuf,events:tokio::task::JoinHandle<()>,origin:TcpListener,policy:Policy,
    probes:Arc<crate::probe_policy::ProbePolicies> }
impl Fixture {
    async fn new(lazy:bool)->Self {
        Self::with_timeout(lazy,3000).await
    }
    async fn with_timeout(lazy:bool,timeout_ms:u64)->Self {
        let origin=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let files=std::env::temp_dir().join(format!("aerove-provider-health-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir(&files).unwrap();std::fs::write(files.join("feed.yaml"),"proxies: [{name: A,type: direct}]\n").unwrap();
        let yaml=format!("strict: true\nproxy-providers:\n P: {{type: file,path: feed.yaml,interval: 0,health-check: {{enable: true,url: 'http://{}/provider-only',interval: 1,timeout: {timeout_ms},expected-status: 204,lazy: {lazy}}}}}\nproxy-groups: [{{name: PICK,type: select,use: [P]}}]\nrules: ['MATCH,PICK']\n",origin.local_addr().unwrap());
        let plane=ControlPlane::new(700,CancellationToken::new()).unwrap();let scope=OwnedLoadScope::with_context(Arc::new(Context(plane.clone())));
        let probes=crate::probe_policy::ProbePolicies::new(meow_common::probe_policy::ProbePolicy{unified_delay:false,tcp_concurrent:true},plane.clone(),plane.cancel.clone());
        let config=providers::load_with_cache(&yaml,&scope,&plane.cancel,Some(&files)).await.unwrap();
        let tunnel=Tunnel::new_with_slot(config.dns.resolver_slot.clone());tunnel.set_dialer_registry(config.provider_dialer_registry.clone());
        let api=Arc::new(CoreApi::assemble_with_plane(&config,tunnel.clone(),"127.0.0.1:12345".parse().unwrap(),
            "provider-health-test-secret-000000001",None,Router::new(),plane.clone()).unwrap());
        tunnel.update_routing(config.proxies,config.rules,config.dialer_registry);
        let events=api.control.activate_groups(&scope).unwrap();api.activate_providers().unwrap();
        let policy=Policy::parse(&yaml).unwrap().remove(0);
        Self{api,plane,scope,tunnel,files,events,origin,policy,probes}
    }
    fn start(&self)->tokio::task::JoinHandle<()> {
        self.start_in(self.scope.clone())
    }
    fn start_in(&self,scope:Arc<OwnedLoadScope>)->tokio::task::JoinHandle<()> {
        let control=self.api.control.clone();let plane=self.plane.clone();let policy=self.policy.clone();
        let probe=self.probes.scope();
        let owner=scope.clone();
        scope.spawn_owned(async move{let _=probe.run_owned(run(control,plane,policy,owner)).await;}).unwrap()
    }
    async fn request(&self)->TcpStream {
        let(mut stream,_)=timeout(Duration::from_secs(5),self.origin.accept()).await.unwrap().unwrap();
        let mut head=Vec::new();while !head.ends_with(b"\r\n\r\n"){head.push(stream.read_u8().await.unwrap());assert!(head.len()<8192);}
        assert!(String::from_utf8(head).unwrap().contains("/provider-only"));stream
    }
    fn delay(&self)->u16 {
        self.api.state.proxy_providers.get("P").unwrap().proxies()[0].last_delay()
    }
    async fn close(self,task:tokio::task::JoinHandle<()>) -> TcpListener {
        let health_scope=self.scope.clone();
        self.close_with_health_scope(task,health_scope).await
    }
    async fn close_with_health_scope(self,task:tokio::task::JoinHandle<()>,health_scope:Arc<OwnedLoadScope>) -> TcpListener {
        self.api.cancel();health_scope.cancel();self.scope.cancel();
        let task_join=timeout(Duration::from_secs(5),task).await.unwrap();
        let scope_close=timeout(Duration::from_secs(5),self.scope.close()).await.unwrap();
        let health_close=if Arc::ptr_eq(&health_scope,&self.scope){None}
            else{Some(timeout(Duration::from_secs(5),health_scope.close()).await.unwrap())};
        let events_join=timeout(Duration::from_secs(5),self.events).await.unwrap();
        self.api.join().await;
        assert!(health_scope.is_cancelled() && self.scope.is_cancelled());
        for joined in [task_join,events_join] {
            if let Err(failure)=joined {assert!(failure.is_cancelled(),"owned task panicked: {failure}");}
        }
        scope_close.unwrap();if let Some(result)=health_close{result.unwrap();}
        std::fs::remove_dir_all(self.files).unwrap();
        self.origin
    }
}

#[test]
fn provider_policy_fields_defaults_disabled_and_fail_closed() {
    let base="proxy-providers: {P: {health-check: %s}}";
    for value in ["{}","{enable: false}","{enable: true,url: ''}"]{assert!(Policy::parse(&base.replace("%s",value)).unwrap().is_empty());}
    let policies=Policy::parse(&base.replace("%s","{enable: true,url: 'http://127.0.0.1/health',interval: 0,timeout: 0}")).unwrap();
    assert_eq!(policies[0].interval,Duration::from_secs(300));assert_eq!(policies[0].timeout,Duration::from_millis(5000));assert!(policies[0].lazy);
    assert!(Policy::parse(&base.replace("%s","{enable: true,url: 'ftp://bad/',unknown: true}")).is_err());
}

#[test]
fn providers_keep_distinct_policies_and_only_declared_group_touch_mapping() {
    let policies=Policy::parse("proxy-providers:\n P: {health-check: {enable: true,url: 'http://127.0.0.1/p',interval: 2,timeout: 90,expected-status: 204,lazy: false}}\n Q: {health-check: {enable: true,url: 'http://127.0.0.1/q',interval: 3,timeout: 120,expected-status: '200-299'}}\n OFF: {health-check: {enable: false,url: 'http://127.0.0.1/off'}}\nproxy-groups: [{name: P_ONLY,use: [P]},{name: Q_ONLY,use: [Q]},{name: ALL,include-all-providers: true},{name: STATIC,proxies: [DIRECT]}]\n").unwrap();
    assert_eq!(policies.len(),2);
    let p=policies.iter().find(|policy|policy.name=="P").unwrap();
    let q=policies.iter().find(|policy|policy.name=="Q").unwrap();
    assert_eq!(p.groups,vec!["P_ONLY","ALL"]);assert_eq!(q.groups,vec!["Q_ONLY","ALL"]);
    assert_eq!(p.interval,Duration::from_secs(2));assert_eq!(q.interval,Duration::from_secs(3));
    assert_eq!(p.timeout,Duration::from_millis(90));assert_eq!(q.timeout,Duration::from_millis(120));
    assert_eq!(p.expected.as_deref(),Some("204"));assert_eq!(q.expected.as_deref(),Some("200-299"));
    assert!(!p.lazy);assert!(q.lazy);assert_ne!(p.url,q.url);
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn independent_initial_periodic_status_and_stop_read_back() {
    let f=Fixture::new(false).await;let task=f.start();
    let mut first=f.request().await;first.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();drop(first);
    let mut second=f.request().await;
    assert!(f.delay()>0);
    let snapshot=f.plane.proxy_snapshot(&f.api.state,Some("P")).unwrap();
    assert_eq!(snapshot["testUrl"],f.policy.url);assert_eq!(snapshot["expectedStatus"],"204");
    assert!(snapshot["revision"].as_str().unwrap().parse::<u64>().unwrap()>0);
    second.write_all(b"HTTP/1.1 500 Failure\r\nConnection: close\r\n\r\n").await.unwrap();drop(second);
    let third=f.request().await;assert_eq!(f.delay(),0);drop(third);
    let origin=f.close(task).await;
    assert!(timeout(Duration::from_millis(1150),origin.accept()).await.is_err());
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn lazy_only_real_group_business_touch_and_no_probe_self_touch() {
    let f=Fixture::new(true).await;let task=f.start();
    let mut first=f.request().await;first.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();drop(first);
    assert!(timeout(Duration::from_millis(1250),f.origin.accept()).await.is_err());
    let route=f.tunnel.route_snapshot();let group=route.proxies.get("PICK").unwrap();
    let mut meta=meow_common::Metadata{host:"127.0.0.1".into(),dst_port:f.origin.local_addr().unwrap().port(),..Default::default()};
    let _=group.member_proxies();let _=group.unwrap_proxy(&meta,false);meta.internal=true;let _=group.unwrap_proxy(&meta,true);
    assert!(!f.api.control.provider_health_recent_use(&f.policy));
    meta.internal=false;
    let business=group.dial_tcp(&meta).await.unwrap();let(_accepted,_)=f.origin.accept().await.unwrap();drop(business);
    assert!(f.api.control.provider_health_recent_use(&f.policy));
    let mut check=f.request().await;check.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();drop(check);
    drop(route);f.close(task).await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn epoch_and_cancel_fence_actual_pending_health() {
    let f=Fixture::new(false).await;let task=f.start();let mut pending=f.request().await;
    let before=f.delay();let epoch=f.plane.stamp().0;
    f.plane.commit_policy(epoch,true,||()).unwrap();
    pending.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();drop(pending);
    let next=f.request().await;assert_eq!(f.delay(),before);drop(next);
    f.close(task).await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn live_network_context_refreshes_each_round_after_hot_network_and_probe_epoch() {
    use crate::network_context::{NetworkContext,NetworkContextSlot};
    use std::sync::{OnceLock,atomic::{AtomicUsize,Ordering}};
    // Real host socket protection callbacks expose which captured network the
    // actual HTTP dial used; the production NetworkContext/transport wrapping
    // and policy snapshot machinery are not replaced with an identity context.
    struct Protect(Arc<AtomicUsize>);
    impl meow_common::socket_protect::SocketProtector for Protect {
        fn protect(&self,_:std::os::fd::RawFd)->std::io::Result<()> {
            self.0.fetch_add(1,Ordering::SeqCst);Ok(())
        }
    }
    let f=Fixture::new(false).await;
    let old_dials=Arc::new(AtomicUsize::new(0));let next_dials=Arc::new(AtomicUsize::new(0));
    let base=NetworkContext::protected(Arc::new(Protect(old_dials.clone())),None,f.plane.cancel.clone())
        .with_control_plane(f.plane.clone()).with_probe_policies(f.probes.clone());
    let slot=NetworkContextSlot::new(&base);let transports=crate::transport_tasks::TransportTasks::new();
    let scope_slot=Arc::new(OnceLock::new());
    let live=slot.view(&base).with_transport(transports.clone(),scope_slot.clone());
    let timer_scope=OwnedLoadScope::with_context(Arc::new(live));
    assert!(scope_slot.set(Arc::downgrade(&timer_scope)).is_ok());
    let task=f.start_in(timer_scope.clone());let mut initial=f.request().await;
    initial.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();drop(initial);
    let mut old=f.request().await;assert!(f.delay()>0);
    let old_count=old_dials.load(Ordering::SeqCst);assert!(old_count>0);
    let next=NetworkContext::protected(Arc::new(Protect(next_dials.clone())),None,f.plane.cancel.clone())
        .with_control_plane(f.plane.clone()).with_probe_policies(f.probes.clone());
    let initial_epoch=f.plane.stamp().0;
    assert!(initial_epoch>700,"initial provider install must precede the hot policy CAS");
    let (network_stamp,retired)=f.plane.commit_policy(initial_epoch,true,||slot.replace(&next)).unwrap();drop(retired);
    let command=format!(r#"{{"schemaVersion":1,"requestId":"a687578a-10f5-4a78-a924-44357aa95ae7","expectedCoreEpoch":"{}","probeDial":{{"unifiedDelay":false,"tcpConcurrent":false}}}}"#,network_stamp.0);
    let receipt=f.probes.apply(&command).unwrap();
    assert_ne!(receipt.status.core_epoch,network_stamp.0.to_string());
    assert!(!receipt.status.probe_dial.unified_delay);assert!(!receipt.status.probe_dial.tcp_concurrent);
    // Old round can finish I/O, but must not commit into the new policy epoch.
    old.write_all(b"HTTP/1.1 500 Failure\r\nConnection: close\r\n\r\n").await.unwrap();drop(old);
    let mut fresh=f.request().await;assert!(f.delay()>0);
    assert!(next_dials.load(Ordering::SeqCst)>0);assert_eq!(old_dials.load(Ordering::SeqCst),old_count);
    fresh.write_all(b"HTTP/1.1 500 Failure\r\nConnection: close\r\n\r\n").await.unwrap();drop(fresh);
    // Further actual requests prove the new context can commit both failure
    // and recovery while the same timer continues past its first admission.
    let mut continued=f.request().await;assert_eq!(f.delay(),0);
    continued.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();drop(continued);
    let pending=f.request().await;assert!(f.delay()>0);
    assert_eq!(f.api.control.snapshot().await.unwrap().core_epoch,receipt.status.core_epoch);
    assert_eq!(f.plane.proxy_snapshot(&f.api.state,Some("P")).unwrap()["testUrl"],f.policy.url);
    assert_eq!(old_dials.load(Ordering::SeqCst),old_count);drop(pending);
    f.close_with_health_scope(task,timer_scope).await;transports.close().await.unwrap();
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn manual_intent_wins_and_member_replacement_retires_old_result() {
    let f=Fixture::new(false).await;let task=f.start();let mut old=f.request().await;
    let control=f.api.control.clone();let url=f.policy.url.clone();
    let probe=f.probes.scope();
    let expected_epoch=f.plane.stamp().0.to_string();
    let manual=tokio::spawn(async move{probe.run_owned(control.node_delay("A".into(),crate::control::DelayQuery{
        url:Some(url),timeout:Some("3000".into()),expected:Some("204".into()),core_epoch:Some(expected_epoch)})).await.unwrap()});
    let mut newer=f.request().await;newer.write_all(b"HTTP/1.1 500 Failure\r\nConnection: close\r\n\r\n").await.unwrap();drop(newer);
    assert_eq!(manual.await.unwrap().status(),axum::http::StatusCode::SERVICE_UNAVAILABLE);
    old.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();drop(old);
    let mut next=f.request().await;assert_eq!(f.delay(),0);
    std::fs::write(f.files.join("feed.yaml"),"proxies: [{name: B,type: direct}]\n").unwrap();
    let provider=f.api.state.proxy_providers.get("P").unwrap().value().clone();
    provider.refresh_owned().await.unwrap();
    next.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();drop(next);
    let mut replaced=f.request().await;assert_eq!(provider.proxies()[0].name(),"B");assert_eq!(provider.proxies()[0].last_delay(),0);
    replaced.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();drop(replaced);
    f.close(task).await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn newer_automatic_intent_fences_pending_manual_real_response() {
    let f=Fixture::new(false).await;
    let control=f.api.control.clone();let url=f.policy.url.clone();let probe=f.probes.scope();
    let expected_epoch=f.plane.stamp().0.to_string();
    let manual=tokio::spawn(async move{probe.run_owned(control.node_delay("A".into(),crate::control::DelayQuery{
        url:Some(url),timeout:Some("3000".into()),expected:Some("204".into()),core_epoch:Some(expected_epoch)})).await.unwrap()});
    let mut old_manual=f.request().await;
    let task=f.start();let mut automatic=f.request().await;
    automatic.write_all(b"HTTP/1.1 500 Failure\r\nConnection: close\r\n\r\n").await.unwrap();drop(automatic);
    // Holding the following request proves the previous automatic commit is done.
    let pending=f.request().await;assert_eq!(f.delay(),0);
    old_manual.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();drop(old_manual);
    assert_eq!(manual.await.unwrap().status(),axum::http::StatusCode::CONFLICT);
    assert_eq!(f.delay(),0);drop(pending);f.close(task).await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn configured_timeout_is_real_and_stop_joins_the_pending_socket() {
    let f=Fixture::with_timeout(false,80).await;let task=f.start();
    let mut blocked=f.request().await;
    // The only bound here is a failure deadline; the endpoint intentionally
    // never supplies an HTTP response, so the production timeout must fire.
    let mut next=f.request().await;assert_eq!(f.delay(),0);
    let mut byte=[0u8;1];assert_eq!(timeout(Duration::from_secs(1),blocked.read(&mut byte)).await.unwrap().unwrap(),0);
    f.close(task).await;
    assert_eq!(timeout(Duration::from_secs(1),next.read(&mut byte)).await.unwrap().unwrap(),0);
}
