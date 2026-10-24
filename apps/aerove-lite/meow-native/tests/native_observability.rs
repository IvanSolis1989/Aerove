use aerove_meow_core::api::CoreApi;
use axum::Router;
use futures::StreamExt;
use meow_tunnel::Tunnel;
use std::{sync::Arc,time::Duration};
use tokio::{io::{AsyncReadExt,AsyncWriteExt},net::{TcpListener,TcpStream,UdpSocket}};
use tokio_tungstenite::{connect_async,MaybeTlsStream,WebSocketStream,tungstenite::{Message,Error}};
use tokio_util::sync::CancellationToken;
const PRIVATE:&str="native-telemetry-private-000000001";
const USER:&str="native-telemetry-user-secret";
type Socket=WebSocketStream<MaybeTlsStream<TcpStream>>;
struct Fixture{api:Arc<CoreApi>,tunnel:Tunnel,private:std::net::SocketAddr,user:std::net::SocketAddr,
    cancel:CancellationToken,tasks:Vec<tokio::task::JoinHandle<()>>}
impl Fixture{
    async fn new()->Self{Self::from_yaml("strict: true\nrules: ['MATCH,DIRECT']\n").await}
    async fn from_yaml(yaml:&str)->Self{
        let config=meow_config::load_config_from_str(yaml).await.unwrap();
        let tunnel=Tunnel::new_with_slot(config.dns.resolver_slot.clone());tunnel.set_dialer_registry(config.provider_dialer_registry.clone());tunnel.set_mode(config.general.mode);
        let private=TcpListener::bind("127.0.0.1:0").await.unwrap();let private_address=private.local_addr().unwrap();
        let user=TcpListener::bind("127.0.0.1:0").await.unwrap();let user_address=user.local_addr().unwrap();
        let api=Arc::new(CoreApi::assemble_with_epoch(&config,tunnel.clone(),"127.0.0.1:12345".parse().unwrap(),PRIVATE,Some(USER),Router::new(),501,CancellationToken::new()).unwrap());
        tunnel.update_routing(config.proxies,config.rules,config.dialer_registry);
        let cancel=CancellationToken::new();let mut tasks=Vec::new();
        let owner=api.clone();let token=cancel.clone();tasks.push(tokio::spawn(async move{owner.serve_private(private,token).await.unwrap();}));
        let owner=api.clone();let token=cancel.clone();tasks.push(tokio::spawn(async move{owner.serve_user(user,token).await.unwrap();}));
        Self{api,tunnel,private:private_address,user:user_address,cancel,tasks}
    }
    async fn close(self){
        self.cancel.cancel();self.api.cancel();
        for task in self.tasks{tokio::time::timeout(Duration::from_secs(5),task).await.unwrap().unwrap();}
        tokio::time::timeout(Duration::from_secs(5),self.api.join()).await.unwrap();
        assert_eq!(self.api.active_tasks(),0);assert_eq!(self.api.state.traffic_feed.lifecycle.active_tasks(),0);
        assert!(!self.tunnel.statistics().observation_feed().is_active(),"joined WebSocket must drop its real observation lease");
    }
}
async fn frame(socket:&mut Socket,deadline:tokio::time::Instant)->serde_json::Value{
    let message=tokio::time::timeout_at(deadline,socket.next()).await.unwrap().unwrap().unwrap();
    serde_json::from_str(message.to_text().unwrap()).unwrap()
}
fn validate(sample:&serde_json::Value){
    assert_eq!(sample["type"],"traffic");assert_eq!(sample.as_object().unwrap().len(),5);
    for key in ["up","down","uploadTotal","downloadTotal"]{assert!(sample[key].as_i64().is_some_and(|n|n>=0));}
}
async fn real_plain_http(tunnel:Tunnel){
    let origin=TcpListener::bind("127.0.0.1:0").await.unwrap();let destination=origin.local_addr().unwrap();
    let peer=tokio::spawn(async move{
        let(mut stream,_)=origin.accept().await.unwrap();let mut head=Vec::new();
        while !head.ends_with(b"\r\n\r\n"){head.push(stream.read_u8().await.unwrap());assert!(head.len()<8192);}
        assert!(head.starts_with(b"POST /traffic-proof HTTP/1.1\r\n"));let mut body=[0;3];stream.read_exact(&mut body).await.unwrap();assert_eq!(&body,b"req");
        stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nresp").await.unwrap();stream.shutdown().await.unwrap();
    });
    let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();
    let relay=tokio::spawn(async move{let(stream,source)=listener.accept().await.unwrap();meow_listener::http_proxy::handle_http(&tunnel,stream,source,None,None,"telemetry-test",address.port()).await;});
    let mut client=TcpStream::connect(address).await.unwrap();client.write_all(format!("POST http://{destination}/traffic-proof HTTP/1.1\r\nHost: {destination}\r\nContent-Length: 3\r\nConnection: close\r\n\r\nreq").as_bytes()).await.unwrap();
    let mut response=Vec::new();tokio::time::timeout(Duration::from_secs(5),client.read_to_end(&mut response)).await.unwrap().unwrap();assert!(response.starts_with(b"HTTP/1.1 200"));assert!(response.ends_with(b"resp"));
    tokio::time::timeout(Duration::from_secs(5),peer).await.unwrap().unwrap();tokio::time::timeout(Duration::from_secs(5),relay).await.unwrap().unwrap();
}

fn uint(value:&serde_json::Value)->u64 {
    let source=value.as_str().expect("canonical unsigned string");
    let n=source.parse::<u64>().unwrap();assert_eq!(source,n.to_string());n
}
fn observation(window:&serde_json::Value,session:Option<&str>,generation:Option<u64>,activation:Option<u64>){
    assert_eq!(window["type"],"observations");
    let id=window["sessionId"].as_str().unwrap();
    assert_eq!(id.len(),36);assert_eq!(&id[8..9],"-");assert_eq!(&id[13..14],"-");
    assert_eq!(&id[18..19],"-");assert_eq!(&id[23..24],"-");
    assert!(id.bytes().enumerate().all(|(i,b)|[8,13,18,23].contains(&i)||b.is_ascii_digit()||(b'a'..=b'f').contains(&b)));
    if let Some(expected)=session{assert_eq!(id,expected);}
    assert!(uint(&window["generation"])>0);if let Some(expected)=generation{assert_eq!(uint(&window["generation"]),expected);}
    let watermark=uint(&window["activationCursor"]);if let Some(expected)=activation{assert_eq!(watermark,expected);}
    let tail=uint(&window["cursor"]);assert!(tail>=watermark);uint(&window["dropped"]);
    let events=window["events"].as_array().unwrap();assert!(events.len()<=32);assert!(window.to_string().len()<=65536);
    let mut last=watermark;
    for event in events{let cursor=uint(&event["cursor"]);assert!(cursor>last&&cursor<=tail);last=cursor;assert!(["request","requestTraffic","requestClosed","dns"].contains(&event["kind"].as_str().unwrap()));}
    if let Some(event)=events.last(){assert_eq!(uint(&event["cursor"]),tail);}
}
async fn window(socket:&mut Socket,deadline:tokio::time::Instant)->serde_json::Value{
    loop{let value=frame(socket,deadline).await;match value["type"].as_str(){
        Some("observations")=>return value,
        Some("traffic")=>validate(&value),
        _=>panic!("unexpected telemetry event: {value}"),
    }}
}
async fn activate(f:&Fixture)->(Socket,serde_json::Value){
    let(mut socket,_)=connect_async(format!("ws://{}/aerove/telemetry?token={PRIVATE}&observations=true",f.private)).await.unwrap();
    // ACK must be the first frame even if traffic/provider events are ready.
    let ack=frame(&mut socket,tokio::time::Instant::now()+Duration::from_secs(5)).await;
    observation(&ack,None,None,None);assert_eq!(ack["events"],serde_json::json!([]));assert_eq!(ack["cursor"],ack["activationCursor"]);assert_eq!(ack["dropped"],"0");
    (socket,ack)
}
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn observation_socket_delivers_actual_http_lifecycle_after_atomic_ack(){
    let f=Fixture::new().await;let(mut socket,ack)=activate(&f).await;
    let feed=f.tunnel.statistics().observation_feed();assert!(feed.is_active());
    assert_eq!(f.api.state.traffic_feed.lifecycle.active_tasks(),1);
    real_plain_http(f.tunnel.clone()).await;
    let deadline=tokio::time::Instant::now()+Duration::from_secs(5);let mut events=Vec::new();let mut previous=uint(&ack["cursor"]);
    while !events.iter().any(|event:&serde_json::Value|event["kind"]=="requestClosed"){
        let next=window(&mut socket,deadline).await;observation(&next,ack["sessionId"].as_str(),Some(uint(&ack["generation"])),Some(uint(&ack["activationCursor"])));
        assert_eq!(uint(&next["dropped"]),0);for event in next["events"].as_array().unwrap(){assert!(uint(&event["cursor"])>previous);previous=uint(&event["cursor"]);events.push(event.clone());}
    }
    let request=events.iter().find(|e|e["kind"]=="request").expect("actual registered HTTP request");
    let traffic=events.iter().find(|e|e["kind"]=="requestTraffic").expect("final actual byte counts flushed before close");
    let closed=events.iter().find(|e|e["kind"]=="requestClosed").unwrap();
    assert_eq!(request["data"]["id"],traffic["data"]["id"]);assert_eq!(request["data"]["id"],closed["data"]["id"]);
    assert!(uint(&traffic["data"]["upload"])>=3);assert!(uint(&traffic["data"]["download"])>=4);
    assert_eq!(traffic["data"]["upload"],closed["data"]["upload"]);assert_eq!(traffic["data"]["download"],closed["data"]["download"]);
    assert!(request["data"]["metadata"]["network"].is_string());assert!(request["data"]["start"].as_str().unwrap().contains('T'));assert!(closed["data"]["end"].as_str().unwrap().contains('T'));
    socket.close(None).await.unwrap();f.close().await;
}
#[derive(Default)]
struct DnsTasks(std::sync::Mutex<Vec<tokio::task::JoinHandle<()>>>);
impl meow_common::transport_tasks::TransportTaskContext for DnsTasks{
    fn spawn(&self,work:meow_common::transport_tasks::Work)->std::io::Result<tokio::task::AbortHandle>{
        let task=tokio::spawn(work);let abort=task.abort_handle();self.0.lock().unwrap().push(task);Ok(abort)
    }
}
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn observation_socket_delivers_real_udp_dns_then_cache_and_joins_query_owner(){
    use hickory_proto::{op::{Message,MessageType,OpCode,ResponseCode},rr::{Record,RData,rdata::A},serialize::binary::{BinDecodable,BinEncodable}};
    let f=Fixture::new().await;let(mut socket,ack)=activate(&f).await;
    let udp=UdpSocket::bind("127.0.0.1:0").await.unwrap();let address=udp.local_addr().unwrap();
    let server=tokio::spawn(async move{
        let mut buffer=[0u8;1024];let(n,peer)=udp.recv_from(&mut buffer).await.unwrap();let query=Message::from_bytes(&buffer[..n]).unwrap();
        assert_eq!(query.queries[0].name.to_string(),"observability.test.");
        let mut reply=Message::new(query.metadata.id,MessageType::Response,OpCode::Query);reply.metadata.response_code=ResponseCode::NoError;reply.add_query(query.queries[0].clone());
        reply.add_answer(Record::from_rdata(query.queries[0].name.clone(),60,RData::A(A(std::net::Ipv4Addr::LOCALHOST))));
        udp.send_to(&reply.to_bytes().unwrap(),peer).await.unwrap();
    });
    let owner=Arc::new(DnsTasks::default());
    let resolver=Arc::new(meow_dns::Resolver::new(vec![address],vec![],meow_common::DnsMode::Normal,Default::default(),false,false)
        .with_transport_scope(meow_common::transport_tasks::TransportScope::new(owner.clone())));
    // Publish under the existing plane CAS while retaining the retired resolver
    // outside its gate. The observation delivery remains the same runtime lease.
    let before=f.api.control.plane.stamp();let retired=f.tunnel.resolver();
    let (after,installed)=f.api.control.plane.commit_policy(before.0,true,||f.tunnel.set_resolver(resolver.clone())).unwrap();
    assert!(after.0>before.0);assert!(installed.is_some());drop(retired);
    assert_eq!(f.api.control.plane.commit_policy(before.0,true,|| -> () { panic!("stale resolver installed") }).unwrap_err(),"staleCoreEpoch");
    let actual=meow_common::observations::DnsInitiator::App.scope(resolver.resolve_ips("observability.test")).await.unwrap().unwrap();assert!(actual.contains(&std::net::IpAddr::V4(std::net::Ipv4Addr::LOCALHOST)));
    meow_common::observations::DnsInitiator::Rule.scope(resolver.resolve_ips("observability.test")).await.unwrap();
    tokio::time::timeout(Duration::from_secs(5),server).await.unwrap().unwrap();
    let deadline=tokio::time::Instant::now()+Duration::from_secs(5);let mut dns=Vec::new();
    while dns.len()<2{
        let next=window(&mut socket,deadline).await;observation(&next,ack["sessionId"].as_str(),Some(uint(&ack["generation"])),Some(uint(&ack["activationCursor"])));
        for event in next["events"].as_array().unwrap(){if event["kind"]=="dns"{dns.push(event["data"].clone());}}
    }
    assert_eq!(dns.len(),2);assert_eq!(dns[0]["cached"],false);assert_eq!(dns[0]["initiator"],"App");assert_eq!(dns[1]["cached"],true);assert_eq!(dns[1]["initiator"],"Rule");
    for data in dns{assert_eq!(data["domain"],"observability.test");assert_eq!(data["type"],"A");assert_eq!(data["rcode"],"NOERROR");assert_eq!(data["upstream"],address.to_string());assert_eq!(data["answers"],serde_json::json!(["127.0.0.1"]));assert!(data["delay"].as_u64().is_some());}
    let tasks=std::mem::take(&mut *owner.0.lock().unwrap());assert_eq!(tasks.len(),1);for task in tasks{tokio::time::timeout(Duration::from_secs(5),task).await.unwrap().unwrap();}
    socket.close(None).await.unwrap();f.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn observation_opt_in_resume_watermark_and_cancel_release_real_delivery(){
    let f=Fixture::new().await;
    match connect_async(format!("ws://{}/aerove/telemetry?token={PRIVATE}&observations=invalid",f.private)).await{
        Err(Error::Http(response))=>assert_eq!(response.status().as_u16(),400),_=>panic!("invalid observation demand accepted"),
    }
    assert!(!f.tunnel.statistics().observation_feed().is_active());
    let(mut plain,_)=connect_async(format!("ws://{}/aerove/telemetry?token={PRIVATE}",f.private)).await.unwrap();
    validate(&frame(&mut plain,tokio::time::Instant::now()+Duration::from_secs(5)).await);
    assert!(!f.tunnel.statistics().observation_feed().is_active());plain.close(None).await.unwrap();
    real_plain_http(f.tunnel.clone()).await;
    let(mut socket,ack)=activate(&f).await;assert!(uint(&ack["activationCursor"])>0,"old actual requests advance watermark but are never backfilled");
    socket.close(None).await.unwrap();
    let deadline=tokio::time::Instant::now()+Duration::from_secs(5);
    while f.tunnel.statistics().observation_feed().is_active(){assert!(tokio::time::Instant::now()<deadline);tokio::task::yield_now().await;}
    real_plain_http(f.tunnel.clone()).await;
    let(mut resumed,next)=activate(&f).await;
    assert_eq!(ack["sessionId"],next["sessionId"]);assert!(uint(&next["generation"])>uint(&ack["generation"]));assert!(uint(&next["activationCursor"])>uint(&ack["activationCursor"]));
    real_plain_http(f.tunnel.clone()).await;
    let live=window(&mut resumed,tokio::time::Instant::now()+Duration::from_secs(5)).await;
    observation(&live,next["sessionId"].as_str(),Some(uint(&next["generation"])),Some(uint(&next["activationCursor"])));
    assert!(!live["events"].as_array().unwrap().is_empty());assert!(live["events"].as_array().unwrap().iter().all(|event|uint(&event["cursor"])>uint(&next["activationCursor"])));
    f.api.cancel();
    let deadline=tokio::time::Instant::now()+Duration::from_secs(5);
    loop{match tokio::time::timeout_at(deadline,resumed.next()).await.unwrap(){None|Some(Err(_))|Some(Ok(Message::Close(_)))=>break,Some(Ok(Message::Text(text)))=>{let value:serde_json::Value=serde_json::from_str(text.as_str()).unwrap();assert!(["traffic","observations"].contains(&value["type"].as_str().unwrap()));},other=>panic!("unexpected socket ending: {other:?}")}}
    f.close().await;
}

#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn continuous_real_dns_observations_do_not_starve_traffic_group_change_or_peer_close(){
    use hickory_proto::{op::{Message as DnsMessage,MessageType,OpCode},rr::{Record,RData,rdata::A},serialize::binary::{BinDecodable,BinEncodable}};
    let f=Fixture::from_yaml("strict: true\nproxy-groups: [{name: PICK, type: select, proxies: [DIRECT, REJECT]}]\nrules: ['MATCH,DIRECT']\n").await;
    let scope=meow_config::owned_load::OwnedLoadScope::new();let group_worker=f.api.control.activate_groups(&scope).unwrap();
    let udp=UdpSocket::bind("127.0.0.1:0").await.unwrap();let address=udp.local_addr().unwrap();
    let server=tokio::spawn(async move{
        let mut bytes=[0u8;1024];let(n,peer)=udp.recv_from(&mut bytes).await.unwrap();let query=DnsMessage::from_bytes(&bytes[..n]).unwrap();
        let mut reply=DnsMessage::new(query.metadata.id,MessageType::Response,OpCode::Query);reply.add_query(query.queries[0].clone());
        reply.add_answer(Record::from_rdata(query.queries[0].name.clone(),60,RData::A(A(std::net::Ipv4Addr::LOCALHOST))));udp.send_to(&reply.to_bytes().unwrap(),peer).await.unwrap();
    });
    let query_owner=Arc::new(DnsTasks::default());
    let resolver=Arc::new(meow_dns::Resolver::new(vec![address],vec![],meow_common::DnsMode::Normal,Default::default(),false,false)
        .with_transport_scope(meow_common::transport_tasks::TransportScope::new(query_owner.clone())));
    f.tunnel.set_resolver(resolver.clone());assert!(resolver.resolve_ips("flood.test").await.unwrap().is_some());
    tokio::time::timeout(Duration::from_secs(5),server).await.unwrap().unwrap();
    let(mut socket,ack)=activate(&f).await;
    // Consume the baseline before starting the sustained source: the traffic
    // proof below must be a subsequent original one-second sampler frame.
    validate(&frame(&mut socket,tokio::time::Instant::now()+Duration::from_secs(5)).await);
    let running=CancellationToken::new();let stop=running.clone();let produced=Arc::new(std::sync::atomic::AtomicU64::new(0));let count=produced.clone();
    let flood=tokio::spawn(async move{
        while !stop.is_cancelled(){
            for _ in 0..256{if stop.is_cancelled(){return;}assert!(resolver.resolve_ips("flood.test").await.unwrap().is_some());count.fetch_add(1,std::sync::atomic::Ordering::Relaxed);}
            tokio::task::yield_now().await;
        }
    });
    let deadline=tokio::time::Instant::now()+Duration::from_secs(5);
    while produced.load(std::sync::atomic::Ordering::Relaxed)<1024{assert!(tokio::time::Instant::now()<deadline);tokio::task::yield_now().await;}
    let mut request=TcpStream::connect(f.user).await.unwrap();let body=r#"{"name":"REJECT"}"#;
    request.write_all(format!("PUT /proxies/PICK HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {USER}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).as_bytes()).await.unwrap();
    let mut response=String::new();tokio::time::timeout_at(deadline,request.read_to_string(&mut response)).await.unwrap().unwrap();assert!(response.starts_with("HTTP/1.1 204"));
    let(mut traffic,mut group,mut observed)=(false,false,false);
    while !(traffic&&group&&observed){
        let next=frame(&mut socket,deadline).await;
        match next["type"].as_str(){
            Some("traffic")=>{validate(&next);traffic=true;},
            Some("groupsChanged")=>{assert!(next["revision"].as_u64().unwrap()>0);group=true;},
            Some("observations")=>{observation(&next,ack["sessionId"].as_str(),Some(uint(&ack["generation"])),Some(uint(&ack["activationCursor"])));assert!(next["events"].as_array().unwrap().iter().any(|e|e["kind"]=="dns"));observed=true;},
            _=>panic!("unexpected flooded telemetry event: {next}"),
        }
        assert!(!flood.is_finished(),"the real observation source must remain active until all branches are served");
    }
    assert_eq!(f.api.control.snapshot().await.unwrap().picks["PICK"],"REJECT");assert_eq!(f.api.state.traffic_feed.lifecycle.active_tasks(),1);
    socket.close(None).await.unwrap();
    let close_deadline=tokio::time::Instant::now()+Duration::from_secs(5);
    // Keep draining already-sent data while the close handshake is in flight,
    // so TCP backpressure cannot substitute for the branch fairness under test.
    loop {
        assert!(!flood.is_finished());
        match tokio::time::timeout_at(close_deadline,socket.next()).await.unwrap(){
            None|Some(Err(_))|Some(Ok(Message::Close(_)))=>break,
            Some(Ok(Message::Text(text)))=>{let value:serde_json::Value=serde_json::from_str(text.as_str()).unwrap();assert!(["traffic","groupsChanged","observations"].contains(&value["type"].as_str().unwrap()));},
            other=>panic!("unexpected close handshake frame: {other:?}"),
        }
    }
    while f.tunnel.statistics().observation_feed().is_active(){
        assert!(tokio::time::Instant::now()<close_deadline,"flood starved peer Close processing");assert!(!flood.is_finished());tokio::task::yield_now().await;
    }
    // Only after the real delivery has gone may the producer stop. No owner
    // cancellation is used to hide failure to receive the peer Close frame.
    running.cancel();tokio::time::timeout_at(close_deadline,flood).await.unwrap().unwrap();
    f.api.control.cancel();scope.close().await.unwrap();if let Err(error)=group_worker.await{assert!(error.is_cancelled());}
    for task in std::mem::take(&mut *query_owner.0.lock().unwrap()){tokio::time::timeout_at(close_deadline,task).await.unwrap().unwrap();}
    f.close().await;
}
