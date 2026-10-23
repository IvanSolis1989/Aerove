use aerove_meow_core::{api::CoreApi, providers::{ControlPlane, OwnedLoadScope}};
use axum::Router;
use meow_common::{probe_policy::{ProbePolicy,ProbeScope,ProbeSource},transport_tasks::{TransportTaskContext,TransportScope,Work}};
use meow_tunnel::Tunnel;
use std::{io,future::Future,pin::Pin,sync::Arc,time::Duration};
use tokio::{io::{AsyncReadExt,AsyncWriteExt},net::TcpListener,sync::mpsc,task::AbortHandle};
use tokio_util::sync::CancellationToken;

struct Source { plane:Arc<ControlPlane>, policy:Arc<ProbePolicy>, cancel:CancellationToken }
impl ProbeSource for Source {
    fn snapshot(&self)->io::Result<Arc<ProbePolicy>>{if self.cancel.is_cancelled(){Err(io::ErrorKind::Interrupted.into())}else{Ok(self.policy.clone())}}
    fn snapshot_with_epoch(&self)->io::Result<(Arc<ProbePolicy>,Option<u64>)>{Ok((self.snapshot()?,Some(self.plane.stamp().0)))}
    fn cancelled(&self)->Pin<Box<dyn Future<Output=()>+Send+'_>>{Box::pin(self.cancel.cancelled())}
}
struct Transport { source:Arc<Source>, probe:ProbeScope, finished:mpsc::UnboundedSender<u64> }
impl TransportTaskContext for Transport {
    fn spawn(&self,_work:Work)->io::Result<AbortHandle>{Err(io::Error::other("unexpected health task spawn"))}
    fn wrap(&self,work:Work)->Work {
        let epoch=self.source.plane.stamp().0;let probe=self.probe.clone();let finished=self.finished.clone();
        Box::pin(async move{probe.scope(work).await;let _=finished.send(epoch);})
    }
}
async fn request(origin:&TcpListener)->tokio::net::TcpStream {
    let(mut stream,_)=tokio::time::timeout(Duration::from_secs(5),origin.accept()).await.unwrap().unwrap();
    let mut header=Vec::new();while !header.ends_with(b"\r\n\r\n"){header.push(stream.read_u8().await.unwrap());assert!(header.len()<8192);}
    assert!(header.starts_with(b"HEAD /health HTTP/1.1\r\n"));stream
}
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn real_periodic_round_reenters_context_and_rejects_old_epoch_health_before_next_round() {
    let origin=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=origin.local_addr().unwrap();
    let scope=OwnedLoadScope::new();let yaml=format!("strict: true\nproxy-groups: [{{name: AUTO,type: fallback,proxies: [DIRECT],url: 'http://{address}/health',interval: 1,lazy: false}}]\nrules: ['MATCH,AUTO']\n");
    let config=meow_config::load_config_from_str_owned(&yaml,&scope).await.unwrap();
    let specs=meow_config::extract_health_check_specs(config.raw.proxy_groups.as_deref().unwrap_or(&[]));assert_eq!(specs.len(),1);
    let tunnel=Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    let cancel=CancellationToken::new();let api=CoreApi::assemble_with_epoch(&config,tunnel.clone(),"127.0.0.1:12345".parse().unwrap(),
        "health-round-private-secret-00000001",None,Router::new(),600,cancel.clone()).unwrap();
    tunnel.update_routing(config.proxies,config.rules,config.dialer_registry);let events=api.control.activate_groups(&scope).unwrap();
    let source=Arc::new(Source{plane:api.control.plane.clone(),policy:Arc::new(ProbePolicy{unified_delay:false,tcp_concurrent:true}),cancel});
    let probe=ProbeScope::new(source.clone());let (finished,mut rounds)=mpsc::unbounded_channel();
    let transport=TransportScope::new(Arc::new(Transport{source,probe:probe.clone(),finished}));
    let weak=tunnel.weak_inner();let spec=specs[0].clone();
    let health=scope.spawn_owned(async move{transport.scope(meow_tunnel::health_check::run_health_check_loop(weak,spec)).await}).unwrap();
    let direct=tunnel.route_snapshot().proxies["DIRECT"].clone();let history=direct.health().delay_history().len();
    let mut old=request(&origin).await;let old_snapshot=probe.snapshot().unwrap();
    let (stamp,installed)=api.control.plane.commit_policy(600,true,||()).unwrap();assert!(installed.is_some());assert!(stamp.0>600);
    old_snapshot.scope(async{direct.health().record_delay(777);}).await;assert_eq!(direct.health().delay_history().len(),history);
    old.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();
    assert_eq!(tokio::time::timeout(Duration::from_secs(5),rounds.recv()).await.unwrap(),Some(600));
    assert_eq!(direct.health().delay_history().len(),history);
    let mut fresh=request(&origin).await;fresh.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();
    assert_eq!(tokio::time::timeout(Duration::from_secs(5),rounds.recv()).await.unwrap(),Some(stamp.0));
    assert_eq!(direct.health().delay_history().len(),history+1);assert!(direct.health().last_delay()>0);
    api.control.cancel();scope.close().await.unwrap();assert!(health.await.unwrap_err().is_cancelled());if let Err(error)=events.await{assert!(error.is_cancelled());}api.cancel();api.join().await;
}
