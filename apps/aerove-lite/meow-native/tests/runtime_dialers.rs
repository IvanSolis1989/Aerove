#![cfg(any(target_os="linux",target_os="android",target_os="macos"))]
#[path="support/provider_general.rs"]mod fixture;
#[path="support/resource_roots.rs"]mod root_fixture;
use fixture::*;
use std::{sync::atomic::Ordering,time::Duration};
use tokio::{sync::Mutex,time::timeout};
static LOCK:Mutex<()>=Mutex::const_new(());
#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn injected_http_actual_two_hops_and_complete_probe_commit_only_requested_health(){
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let mut servers=Servers::new();let front=servers.proxy().await;let exit=servers.proxy().await;let target=servers.origin().await;
    let yaml=format!("strict: true\nproxies:\n - {{name: FRONT,type: http,server: 127.0.0.1,port: {}}}\n - {{name: NODE,type: http,server: 127.0.0.1,port: {},dialer-proxy: FRONT}}\nproxy-groups: [{{name: PICK,type: select,proxies: [NODE,DIRECT]}}]\nrules: ['MATCH,PICK']\n",front.address.port(),exit.address.port());
    let ports=ports().await;let(session,weak)=start(plan(&yaml,ports,&roots.read,&roots.cache));timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();roundtrip(ports[1],target).await;
    assert_eq!(front.targets.lock().unwrap().as_slice(),[exit.address.to_string()]);assert_eq!(exit.targets.lock().unwrap().as_slice(),[target.to_string()]);
    let before=get(ports[0],"/proxies/NODE").await["history"].as_array().unwrap().len();let upstream=get(ports[0],"/proxies/FRONT").await["history"].clone();
    assert!(request(ports[0],"GET",&delay_path("NODE",target),"").await.starts_with("HTTP/1.1 200"));assert_eq!(get(ports[0],"/proxies/NODE").await["history"].as_array().unwrap().len(),before+1);assert_eq!(get(ports[0],"/proxies/FRONT").await["history"],upstream);
    assert_eq!(front.targets.lock().unwrap().len(),2);assert_eq!(exit.targets.lock().unwrap().len(),2);stopped(&session,weak,ports).await;servers.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn provider_dpa_bootstrap_private_health_alias_and_static_front_refresh_use_actual_path(){
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let mut servers=Servers::new();let a=servers.proxy().await;let b=servers.proxy().await;let target=servers.origin().await;
    let file=roots.read.join("dpa.yaml");std::fs::write(&file,"proxies: [{name: NODE,type: direct,dialer-proxy: A}]\n").unwrap();
    let yaml=format!("strict: true\nproxies:\n - {{name: A,type: http,server: 127.0.0.1,port: {}}}\n - {{name: B,type: http,server: 127.0.0.1,port: {}}}\nproxy-providers: {{feed: {{type: file,path: dpa.yaml,interval: 0,health-check: {{}}}}}}\nproxy-groups: [{{name: PICK,type: select,use: [feed]}}]\nrules: ['MATCH,PICK']\n",a.address.port(),b.address.port());
    let ports=ports().await;let(session,weak)=start(plan(&yaml,ports,&roots.read,&roots.cache));timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();roundtrip(ports[1],target).await;assert_eq!(a.targets.lock().unwrap().as_slice(),[target.to_string()]);
    let before=get(ports[0],"/proxies/NODE").await["history"].as_array().unwrap().len();assert!(request(ports[0],"GET",&delay_path("NODE",target),"").await.starts_with("HTTP/1.1 200"));assert_eq!(get(ports[0],"/proxies/NODE").await["history"].as_array().unwrap().len(),before+1,"private inner/outer share one health handle and commit once");assert!(get(ports[0],"/proxies/A").await["history"].as_array().unwrap().is_empty());
    std::fs::write(&file,"proxies: [{name: NODE,type: direct,dialer-proxy: B}]\n").unwrap();assert!(request(ports[0],"PUT","/providers/proxies/feed","").await.starts_with("HTTP/1.1 204"));assert_eq!(get(ports[0],"/providers/proxies/feed").await["revision"],"2");roundtrip(ports[1],target).await;assert_eq!(a.targets.lock().unwrap().len(),2);assert_eq!(b.targets.lock().unwrap().as_slice(),[target.to_string()]);stopped(&session,weak,ports).await;servers.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn provider_dpa_slow_old_measurement_is_rejected_after_refresh_and_stop_joins_new_probe(){
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let mut servers=Servers::new();let front=servers.proxy().await;let target=servers.origin().await;let file=roots.read.join("dpa.yaml");std::fs::write(&file,"proxies: [{name: old,type: direct,dialer-proxy: FRONT}]\n").unwrap();
    let yaml=format!("strict: true\nproxies: [{{name: FRONT,type: http,server: 127.0.0.1,port: {}}}]\nproxy-providers: {{feed: {{type: file,path: dpa.yaml,interval: 0}}}}\nproxy-groups: [{{name: PICK,type: select,use: [feed]}}]\nrules: ['MATCH,PICK']\n",front.address.port());let ports=ports().await;let(session,weak)=start(plan(&yaml,ports,&roots.read,&roots.cache));timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();
    front.hold.store(true,Ordering::Release);let private=ports[0];let path=delay_path("old",target);let pending=tokio::spawn(async move{request_result(private,"GET",&path,"").await});timeout(Duration::from_secs(5),front.started.notified()).await.unwrap();
    std::fs::write(&file,"proxies: [{name: fresh,type: direct,dialer-proxy: FRONT}]\n").unwrap();assert!(request(ports[0],"PUT","/providers/proxies/feed","").await.starts_with("HTTP/1.1 204"));let stamp=get(ports[0],"/aerove/route").await;front.release.notify_one();let response=timeout(Duration::from_secs(5),pending).await.unwrap().unwrap().unwrap();assert!(response.starts_with("HTTP/1.1 409"),"{response}");assert_eq!(get(ports[0],"/aerove/route").await,stamp);assert!(get(ports[0],"/proxies/fresh").await["history"].as_array().unwrap().is_empty());
    let private=ports[0];let path=delay_path("fresh",target);let pending=tokio::spawn(async move{request_result(private,"GET",&path,"").await});timeout(Duration::from_secs(5),front.started.notified()).await.unwrap();stopped(&session,weak,ports).await;let response=timeout(Duration::from_secs(5),pending).await.unwrap().unwrap();assert!(response.is_err()||!response.unwrap().starts_with("HTTP/1.1 200"));timeout(Duration::from_secs(5),front.eof.notified()).await.unwrap();servers.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn duplicate_static_names_only_effective_last_definition_controls_dialer_admission(){
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let mut servers=Servers::new();let front=servers.proxy().await;let exit=servers.proxy().await;let target=servers.origin().await;
    let yaml=format!("strict: true\nproxies:\n - {{name: FIRST,type: http,server: 127.0.0.1,port: {}}}\n - {{name: NODE,type: http,server: 127.0.0.1,port: 9,dialer-proxy: FIRST}}\n - {{name: NODE,type: http,server: 127.0.0.1,port: {}}}\nrules: ['MATCH,NODE']\n",front.address.port(),exit.address.port());let ports=ports().await;let(session,weak)=start(plan(&yaml,ports,&roots.read,&roots.cache));timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();roundtrip(ports[1],target).await;assert!(front.targets.lock().unwrap().is_empty());assert_eq!(exit.targets.lock().unwrap().as_slice(),[target.to_string()]);stopped(&session,weak,ports).await;servers.close().await;
}

#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn hot_provider_dependency_touching_its_retiring_slot_is_rejected_before_publication(){
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let file=roots.read.join("cycle.yaml");std::fs::write(&file,"proxies: [{name: old,type: direct}]\n").unwrap();
    let yaml="strict: true\nproxy-providers: {feed: {type: file,path: cycle.yaml,interval: 0}}\nproxy-groups:\n - {name: FRONT,type: select,use: [feed]}\n - {name: PICK,type: select,use: [feed]}\nrules: ['MATCH,PICK']\n";
    let ports=ports().await;let(session,weak)=start(plan(yaml,ports,&roots.read,&roots.cache));timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();let stamp=get(ports[0],"/aerove/route").await;let receipt=get(ports[0],"/providers/proxies/feed").await;
    std::fs::write(&file,"proxies: [{name: new,type: direct,dialer-proxy: FRONT}]\n").unwrap();let response=request(ports[0],"PUT","/providers/proxies/feed","").await;assert!(!response.starts_with("HTTP/1.1 204"),"unproven replacement cycle was published");assert_eq!(get(ports[0],"/aerove/route").await,stamp);let current=get(ports[0],"/providers/proxies/feed").await;assert_eq!(current["revision"],receipt["revision"]);assert_eq!(current["proxies"][0]["name"],"old");stopped(&session,weak,ports).await;
}
