#![cfg(any(target_os="linux",target_os="android",target_os="macos"))]
#[path="support/provider_general.rs"]mod fixture;
#[path="support/resource_roots.rs"]mod root_fixture;
use fixture::*;
use std::{sync::atomic::Ordering,time::Duration};
use tokio::{io::{AsyncReadExt,AsyncWriteExt},sync::Mutex,time::timeout};
static LOCK:Mutex<()>=Mutex::const_new(());
fn leaves(a:&Peer,b:&Peer)->String{format!("proxies:\n - {{name: A,type: http,server: 127.0.0.1,port: {}}}\n - {{name: B,type: http,server: 127.0.0.1,port: {}}}\n",a.address.port(),b.address.port())}
#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn nested_relay_real_hops_connection_chain_and_target_only_probe_commit(){
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let mut servers=Servers::new();let front=servers.proxy().await;let exit=servers.proxy().await;let target=servers.origin().await;
    let yaml=format!("strict: true\n{}proxy-groups:\n - {{name: INNER,type: relay,proxies: [A,B]}}\n - {{name: OUTER,type: relay,proxies: [DIRECT,INNER]}}\n - {{name: PICK,type: select,proxies: [OUTER,DIRECT]}}\nrules: ['MATCH,PICK']\n",leaves(&front,&exit));
    let addresses=ports().await;let(session,weak)=start(plan(&yaml,addresses,&roots.read,&roots.cache));timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();
    let mut connection=connect(addresses[1],target).await;let source_port=connection.local_addr().unwrap().port();connection.write_all(b"ping").await.unwrap();let mut reply=[0;4];timeout(Duration::from_secs(5),connection.read_exact(&mut reply)).await.unwrap().unwrap();assert_eq!(&reply,b"pong");
    assert_eq!(front.targets.lock().unwrap().as_slice(),[exit.address.to_string()]);assert_eq!(exit.targets.lock().unwrap().as_slice(),[target.to_string()]);
    let trace=servers.trace.lock().unwrap().clone();assert_eq!(trace[0],(front.address,exit.address.to_string()));assert_eq!(trace[1],(exit.address,target.to_string()));
    let connections=get(addresses[0],"/connections").await;let live=connections["connections"].as_array().unwrap().iter().find(|row|row["metadata"]["sourcePort"].as_u64()==Some(u64::from(source_port))).unwrap();let chains=live["chains"].as_array().unwrap();for name in ["A","B","OUTER","PICK"]{assert!(chains.iter().any(|value|value==name),"{chains:?}");}assert_eq!(chains.last().unwrap(),"PICK");
    connection.shutdown().await.unwrap();drop(connection);
    let a=get(addresses[0],"/proxies/A").await;let b=get(addresses[0],"/proxies/B").await;let outer=get(addresses[0],"/proxies/OUTER").await;let before=get(addresses[0],"/aerove/route").await;
    let measured=request(addresses[0],"GET",&delay_path("OUTER",target),"").await;assert!(measured.starts_with("HTTP/1.1 200"),"{measured}");assert!(json(&measured)["delay"].is_u64());
    assert_eq!(get(addresses[0],"/proxies/A").await["history"],a["history"]);assert_eq!(get(addresses[0],"/proxies/B").await["history"],b["history"],"exit RTT must not be copied onto hop health");
    assert_eq!(get(addresses[0],"/proxies/OUTER").await["history"].as_array().unwrap().len(),outer["history"].as_array().unwrap().len()+1);
    assert_eq!(get(addresses[0],"/aerove/route").await,before);assert_eq!(front.targets.lock().unwrap().last().unwrap(),&exit.address.to_string());assert_eq!(exit.targets.lock().unwrap().last().unwrap(),&target.to_string());
    assert!(request(addresses[0],"PUT","/proxies/OUTER",r#"{"name":"A"}"#).await.starts_with("HTTP/1.1 400"),"Relay must not pretend to be manually selectable");
    stopped(&session,weak,addresses).await;servers.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn round_robin_actual_business_order_survives_readback_and_complete_target_measurement(){
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let mut servers=Servers::new();let a=servers.proxy().await;let b=servers.proxy().await;let target=servers.origin().await;
    let yaml=format!("strict: true\n{}proxy-groups: [{{name: LB,type: load-balance,proxies: [A,B],strategy: round-robin,interval: 0}}]\nrules: ['MATCH,LB']\n",leaves(&a,&b));let addresses=ports().await;let(session,weak)=start(plan(&yaml,addresses,&roots.read,&roots.cache));timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();
    roundtrip(addresses[1],target).await;assert_eq!((a.targets.lock().unwrap().len(),b.targets.lock().unwrap().len()),(1,0));let row=get(addresses[0],"/proxies/LB").await;assert_eq!(row["type"],"LoadBalance");
    let before_a=get(addresses[0],"/proxies/A").await["history"].clone();let before_b=get(addresses[0],"/proxies/B").await["history"].clone();assert!(request(addresses[0],"GET",&delay_path("LB",target),"").await.starts_with("HTTP/1.1 200"));
    assert_eq!((a.targets.lock().unwrap().len(),b.targets.lock().unwrap().len()),(1,1),"pure measurement peeks at B without advancing business RR");assert_eq!(get(addresses[0],"/proxies/A").await["history"],before_a);assert_eq!(get(addresses[0],"/proxies/B").await["history"],before_b);
    roundtrip(addresses[1],target).await;assert_eq!((a.targets.lock().unwrap().len(),b.targets.lock().unwrap().len()),(1,2));roundtrip(addresses[1],target).await;assert_eq!((a.targets.lock().unwrap().len(),b.targets.lock().unwrap().len()),(2,2));
    assert!(request(addresses[0],"PUT","/proxies/LB",r#"{"name":"A"}"#).await.starts_with("HTTP/1.1 400"));stopped(&session,weak,addresses).await;servers.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn consistent_hash_actual_key_stability_and_real_member_failure_recovery(){
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let mut servers=Servers::new();let a=servers.proxy().await;let b=servers.proxy().await;let target=servers.origin().await;
    let yaml=format!("strict: true\n{}proxy-groups: [{{name: LB,type: load-balance,proxies: [A,B],strategy: consistent-hashing,interval: 0}}]\nrules: ['MATCH,LB']\n",leaves(&a,&b));let addresses=ports().await;let(session,weak)=start(plan(&yaml,addresses,&roots.read,&roots.cache));timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();
    roundtrip(addresses[1],target).await;let chose_a=a.targets.lock().unwrap().len()==1;roundtrip(addresses[1],target).await;assert_eq!((a.targets.lock().unwrap().len(),b.targets.lock().unwrap().len()),if chose_a{(2,0)}else{(0,2)});
    let (victim,name)=if chose_a{(&a,"A")}else{(&b,"B")};victim.healthy.store(false,Ordering::Release);let response=request(addresses[0],"GET",&delay_path(name,target),"").await;assert!(response.starts_with("HTTP/1.1 503"),"{response}");
    let counts=(a.targets.lock().unwrap().len(),b.targets.lock().unwrap().len());roundtrip(addresses[1],target).await;assert_eq!((a.targets.lock().unwrap().len(),b.targets.lock().unwrap().len()),if chose_a{(counts.0,counts.1+1)}else{(counts.0+1,counts.1)},"real failed member must be avoided");
    victim.healthy.store(true,Ordering::Release);assert!(request(addresses[0],"GET",&delay_path(name,target),"").await.starts_with("HTTP/1.1 200"));let counts=(a.targets.lock().unwrap().len(),b.targets.lock().unwrap().len());roundtrip(addresses[1],target).await;assert_eq!((a.targets.lock().unwrap().len(),b.targets.lock().unwrap().len()),if chose_a{(counts.0+1,counts.1)}else{(counts.0,counts.1+1)},"recovered member restores original stable key mapping");
    stopped(&session,weak,addresses).await;servers.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn relay_middle_handshake_stop_cancels_owned_pure_probe_and_joins_real_sockets(){
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let mut servers=Servers::new();let a=servers.proxy().await;let b=servers.proxy().await;let target=servers.origin().await;
    let yaml=format!("strict: true\n{}proxy-groups: [{{name: RELAY,type: relay,proxies: [A,B]}}]\nrules: ['MATCH,RELAY']\n",leaves(&a,&b));let addresses=ports().await;let(session,weak)=start(plan(&yaml,addresses,&roots.read,&roots.cache));timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();b.hold.store(true,Ordering::Release);
    let private=addresses[0];let path=delay_path("RELAY",target);let pending=tokio::spawn(async move{request_result(private,"GET",&path,"").await});timeout(Duration::from_secs(5),b.started.notified()).await.unwrap();assert!(!pending.is_finished());assert_eq!(a.targets.lock().unwrap().last().unwrap(),&b.address.to_string());assert_eq!(b.targets.lock().unwrap().last().unwrap(),&target.to_string());
    stopped(&session,weak,addresses).await;let outcome=timeout(Duration::from_secs(5),pending).await.unwrap().unwrap();assert!(outcome.is_err() || !outcome.unwrap().starts_with("HTTP/1.1 200"));timeout(Duration::from_secs(5),b.eof.notified()).await.unwrap();servers.close().await;
}

#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn multi_proxy_relay_udp_fails_without_any_hop_io_while_transparent_relay_roundtrips(){
    use tokio::net::{TcpStream,UdpSocket};
    let _serial=LOCK.lock().await;let roots=root_fixture::Roots::new();let mut servers=Servers::new();let a=servers.proxy().await;let b=servers.proxy().await;
    let destination=UdpSocket::bind("127.0.0.1:0").await.unwrap();let target=destination.local_addr().unwrap();
    for proxied in [true,false]{
        let group=if proxied{"[A,B]"}else{"[DIRECT,COMPATIBLE]"};let yaml=format!("strict: true\n{}proxy-groups: [{{name: RELAY,type: relay,proxies: {group}}}]\nrules: ['MATCH,RELAY', 'MATCH,REJECT']\n",leaves(&a,&b));let addresses=ports().await;let(session,weak)=start(plan(&yaml,addresses,&roots.read,&roots.cache));timeout(Duration::from_secs(5),session.ready()).await.unwrap().unwrap();
        let mut control=TcpStream::connect(addresses[1]).await.unwrap();control.write_all(&[5,1,0]).await.unwrap();let mut welcome=[0;2];control.read_exact(&mut welcome).await.unwrap();assert_eq!(welcome,[5,0]);control.write_all(&[5,3,0,1,127,0,0,1,0,0]).await.unwrap();let mut reply=[0;10];timeout(Duration::from_secs(5),control.read_exact(&mut reply)).await.unwrap().unwrap();assert_eq!(&reply[..4],&[5,0,0,1]);let relay=std::net::SocketAddr::from(([reply[4],reply[5],reply[6],reply[7]],u16::from_be_bytes([reply[8],reply[9]])));
        let client=UdpSocket::bind("127.0.0.1:0").await.unwrap();let mut packet=vec![0,0,0,1,127,0,0,1];packet.extend_from_slice(&target.port().to_be_bytes());packet.extend_from_slice(b"actual-relay-udp");client.send_to(&packet,relay).await.unwrap();let mut bytes=[0;512];
        if proxied{assert!(timeout(Duration::from_millis(100),destination.recv_from(&mut bytes)).await.is_err());assert!(a.targets.lock().unwrap().is_empty());assert!(b.targets.lock().unwrap().is_empty());assert!(timeout(Duration::from_millis(100),client.recv_from(&mut bytes)).await.is_err());}
        else{let(n,peer)=timeout(Duration::from_secs(5),destination.recv_from(&mut bytes)).await.unwrap().unwrap();assert_eq!(&bytes[..n],b"actual-relay-udp");destination.send_to(&bytes[..n],peer).await.unwrap();let(n,_)=timeout(Duration::from_secs(5),client.recv_from(&mut bytes)).await.unwrap().unwrap();assert_eq!(&bytes[10..n],b"actual-relay-udp");}
        drop(control);stopped(&session,weak,addresses).await;
    }
    servers.close().await;
}
