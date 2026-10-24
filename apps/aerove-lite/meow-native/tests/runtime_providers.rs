#![cfg(any(target_os="linux",target_os="android",target_os="macos"))]
#[path="support/provider_general.rs"]mod fixture;
#[path="support/resource_roots.rs"]mod root_fixture;
use fixture::*;
use futures::StreamExt;
use std::{sync::atomic::Ordering,time::Duration};
use tokio::{net::TcpListener,sync::Mutex,time::timeout};
static LOCK:Mutex<()>=Mutex::const_new(());
#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn owned_http_provider_changes_actual_route_file_rule_receipts_and_busy_stop_join(){
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let mut servers=Servers::new();let a=servers.proxy().await;let b=servers.proxy().await;let target=servers.origin().await;
    let feed=servers.feed(nodes("a",a.address.port())).await;let remote=servers.feed("payload: [old.policy.test]\n".into()).await;std::fs::write(roots.read.join("domains.yaml"),"payload: ['+.provider.test']\n").unwrap();let seed=std::fs::read(roots.read.join("domains.yaml")).unwrap();
    let yaml=format!("strict: true\nproxy-providers:\n  feed: {{type: http,url: 'http://{}/feed',path: live.yaml,interval: 0}}\nrule-providers:\n  domains: {{type: file,behavior: domain,path: domains.yaml,interval: 0}}\n  remote: {{type: http,behavior: domain,url: 'http://{}/rules',path: rules-cache.yaml,interval: 0}}\nproxy-groups: [{{name: PICK,type: select,use: [feed]}}]\nrules: ['RULE-SET,domains,REJECT','RULE-SET,remote,REJECT','MATCH,PICK']\n",feed.address,remote.address);
    let addresses=ports().await;let(session,weak)=start(plan(&yaml,addresses,&roots.read,&roots.cache));timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();
    let first=get(addresses[0],"/providers/proxies/feed").await;assert_eq!(first["revision"],"1");assert_eq!(first["proxies"][0]["name"],"a");
    roundtrip(addresses[1],target).await;assert_eq!(a.targets.lock().unwrap().as_slice(),[target.to_string()]);assert!(b.targets.lock().unwrap().is_empty());
    let before=get(addresses[0],"/aerove/route").await;*feed.payload.write().await=nodes("b",b.address.port());
    assert!(request(addresses[0],"PUT","/providers/proxies/feed","").await.starts_with("HTTP/1.1 204"));
    let changed=get(addresses[0],"/providers/proxies/feed").await;assert_eq!(changed["revision"],"2");assert_eq!(changed["proxies"][0]["name"],"b");
    let after=get(addresses[0],"/aerove/route").await;assert_ne!(before["core-epoch"],after["core-epoch"]);assert_eq!(after["picks"]["PICK"],"b");
    roundtrip(addresses[1],target).await;assert_eq!(a.targets.lock().unwrap().len(),1);assert_eq!(b.targets.lock().unwrap().as_slice(),[target.to_string()]);
    assert!(request(addresses[0],"PUT","/providers/proxies/feed","").await.starts_with("HTTP/1.1 204"));assert_eq!(get(addresses[0],"/providers/proxies/feed").await["revision"],"3");assert_eq!(get(addresses[0],"/aerove/route").await,after,"unchanged success must not fake a structural epoch");
    let rule=get(addresses[0],"/providers/rules/domains").await;assert_eq!(rule["revision"],"1");assert!(request(addresses[0],"PUT","/providers/rules/domains","").await.starts_with("HTTP/1.1 204"));assert_eq!(get(addresses[0],"/providers/rules/domains").await["revision"],"2");assert_eq!(std::fs::read(roots.read.join("domains.yaml")).unwrap(),seed,"HTTP cache must not overwrite prepared resource files");assert!(!roots.read.join("live.yaml").exists());assert_eq!(get(addresses[0],"/aerove/route").await,after);let cached:serde_yaml::Value=serde_yaml::from_slice(&std::fs::read(roots.cache.join("live.yaml")).unwrap()).unwrap();assert_eq!(cached["proxies"][0]["name"].as_str(),Some("b"));
    let page=get(addresses[0],"/providers/rules/remote/rules?limit=1").await;assert_eq!(page["rules"].as_array().unwrap().len(),1);let old_epoch=page["coreEpoch"].as_str().unwrap();
    *remote.payload.write().await="payload: [new.policy.test]\n".into();assert!(request(addresses[0],"PUT","/providers/rules/remote","").await.starts_with("HTTP/1.1 204"));assert_eq!(get(addresses[0],"/providers/rules/remote").await["revision"],"2");
    assert!(request(addresses[0],"GET",&format!("/providers/rules/remote/rules?limit=1&coreEpoch={old_epoch}"),"").await.starts_with("HTTP/1.1 409"));let next=get(addresses[0],"/providers/rules/remote/rules?limit=1").await;assert_eq!(next["rules"].as_array().unwrap().len(),1);assert_ne!(page["rules"],next["rules"]);
    assert!(request(addresses[0],"PUT","/providers/rules/remote","").await.starts_with("HTTP/1.1 204"));assert_eq!(get(addresses[0],"/providers/rules/remote").await["revision"],"3");assert_eq!(get(addresses[0],"/providers/rules/remote/rules?limit=1").await["coreEpoch"],next["coreEpoch"]);
    feed.hold.store(true,Ordering::Release);let private=addresses[0];let refresh=tokio::spawn(async move{request_result(private,"PUT","/providers/proxies/feed","").await});timeout(Duration::from_secs(5),feed.started.notified()).await.unwrap();
    for path in ["/providers/proxies/feed","/providers/proxies"]{let response=request(addresses[0],"GET",path,"").await;assert!(response.starts_with("HTTP/1.1 409"),"{response}");}
    stopped(&session,weak,addresses).await;let ended=timeout(Duration::from_secs(5),refresh).await.unwrap().unwrap();assert!(ended.is_err() || !ended.unwrap().starts_with("HTTP/1.1 204"));timeout(Duration::from_secs(5),feed.eof.notified()).await.unwrap();servers.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn owned_http_provider_timer_receipt_replaces_live_load_balance_members_without_new_poller(){
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let mut servers=Servers::new();let a=servers.proxy().await;let b=servers.proxy().await;let target=servers.origin().await;let feed=servers.feed(nodes("a",a.address.port())).await;
    let yaml=format!("strict: true\nproxy-providers: {{feed: {{type: http,url: 'http://{}/feed',path: timer.yaml,interval: 1}}}}\nproxy-groups: [{{name: PICK,type: load-balance,use: [feed],strategy: round-robin,interval: 0}}]\nrules: ['MATCH,PICK']\n",feed.address);
    let addresses=ports().await;let(session,weak)=start(plan(&yaml,addresses,&roots.read,&roots.cache));timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();roundtrip(addresses[1],target).await;
    let initial=get(addresses[0],"/providers/proxies/feed").await["revision"].as_str().unwrap().parse::<u64>().unwrap();let(mut socket,_)=tokio_tungstenite::connect_async(format!("ws://{}/aerove/telemetry?token={SECRET}",addresses[0])).await.unwrap();*feed.payload.write().await=nodes("b",b.address.port());
    let deadline=tokio::time::Instant::now()+Duration::from_secs(5);loop{let frame=tokio::time::timeout_at(deadline,socket.next()).await.unwrap().unwrap().unwrap();let value:serde_json::Value=serde_json::from_str(frame.to_text().unwrap()).unwrap();match value["type"].as_str(){
        Some("traffic")=>{for key in ["up","down","uploadTotal","downloadTotal"]{assert!(value[key].as_i64().is_some_and(|n|n>=0));}},
        Some("groupsChanged")=>{assert!(value["revision"].is_u64());},
        Some("providersLoaded")=>{if value["providers"].as_array().unwrap().iter().any(|event|event["name"]=="feed"&&event["changed"]==true&&event["revision"].as_str().unwrap().parse::<u64>().unwrap()>initial){break;}},
        _=>panic!("unexpected telemetry event {value}"),
    }}
    assert!(feed.requests.load(Ordering::Relaxed)>=2);let row=get(addresses[0],"/proxies/PICK").await;assert_eq!(row["type"],"LoadBalance");assert_eq!(row["now"],"b");roundtrip(addresses[1],target).await;assert_eq!(a.targets.lock().unwrap().len(),1);assert_eq!(b.targets.lock().unwrap().len(),1);
    socket.close(None).await.unwrap();stopped(&session,weak,addresses).await;servers.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn cancelling_initial_provider_fetch_joins_before_same_port_successor(){
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let mut servers=Servers::new();let feed=servers.feed("proxies: [{name: a,type: direct}]\n".into()).await;feed.hold.store(true,Ordering::Release);
    let yaml=format!("strict: true\nproxy-providers: {{feed: {{type: http,url: 'http://{}/initial',path: initial.yaml,interval: 0}}}}\nproxy-groups: [{{name: PICK,type: select,use: [feed]}}]\nrules: ['MATCH,PICK']\n",feed.address);let addresses=ports().await;let json=plan(&yaml,addresses,&roots.read,&roots.cache);let(session,weak)=start(json.clone());timeout(Duration::from_secs(5),feed.started.notified()).await.unwrap();
    stopped(&session,weak,addresses).await;timeout(Duration::from_secs(5),feed.eof.notified()).await.unwrap();feed.hold.store(false,Ordering::Release);
    let(next,weak)=start(json);timeout(Duration::from_secs(5),next.ready()).await.unwrap().unwrap();assert!(next.generation()>session.generation());assert_eq!(get(addresses[0],"/proxies/PICK").await["now"],"a");stopped(&next,weak,addresses).await;servers.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn unsupported_provider_fields_fail_before_fetch_without_removing_other_runtime_guards(){
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=listener.local_addr().unwrap();
    for extra in ["override: {dialer-proxy: DIRECT}","proxy: PICK","allow-external-plugin: true","unknown-field: true","health-check: {enable: true,unknown-field: 1}","health-check: {enable: true,interval: 1}"]{
        let yaml=format!("strict: true\nproxy-providers: {{feed: {{type: http,url: 'http://{peer}/must-not-fetch', {extra}}}}}\nrules: ['MATCH,DIRECT']\n");let addresses=ports().await;let(session,_)=start(plan(&yaml,addresses,&roots.read,&roots.cache));let failure=timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap_err();assert_eq!(failure.code,"providerConfigRejected");assert_eq!(failure.field.as_deref(),Some("engineYaml"));assert!(failure.reason.as_deref().unwrap().contains(extra.split(':').next().unwrap()));let terminal=timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap();assert_eq!(terminal.phase,aerove_meow_core::Phase::Failed);if let Some(owner)=session.owned_runtime(){assert!(owner.cleanup_confirmed());}assert!(timeout(Duration::from_millis(50),listener.accept()).await.is_err(),"rejected field still caused fetch: {extra}");for address in addresses{assert!(TcpListener::bind(address).await.is_ok());}
    }
}
