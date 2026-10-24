use aerove_meow_core::api::CoreApi;
use axum::Router;
use futures::StreamExt;
use meow_tunnel::Tunnel;
use std::{sync::Arc,time::Duration};
use tokio::{io::{AsyncReadExt,AsyncWriteExt},net::{TcpListener,TcpStream}};
use tokio_tungstenite::{connect_async,MaybeTlsStream,WebSocketStream,tungstenite::{Message,Error}};
use tokio_util::sync::CancellationToken;
const PRIVATE:&str="native-telemetry-private-000000001";
const USER:&str="native-telemetry-user-secret";
type Socket=WebSocketStream<MaybeTlsStream<TcpStream>>;
struct Fixture{api:Arc<CoreApi>,tunnel:Tunnel,private:std::net::SocketAddr,user:std::net::SocketAddr,
    cancel:CancellationToken,tasks:Vec<tokio::task::JoinHandle<()>>}
impl Fixture{
    async fn new()->Self{
        let config=meow_config::load_config_from_str("strict: true\nrules: ['MATCH,DIRECT']\n").await.unwrap();
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
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn shared_telemetry_uses_one_official_sampler_and_actual_forwarded_traffic(){
    let f=Fixture::new().await;assert_eq!(f.api.state.traffic_feed.lifecycle.active_tasks(),0);
    let(mut telemetry,_)=connect_async(format!("ws://{}/aerove/telemetry?token={PRIVATE}",f.private)).await.unwrap();
    let(mut official,_)=connect_async(format!("ws://{}/traffic?token={PRIVATE}",f.private)).await.unwrap();
    let deadline=tokio::time::Instant::now()+Duration::from_secs(5);let initial=frame(&mut telemetry,deadline).await;validate(&initial);assert_eq!(initial["uploadTotal"],0);assert_eq!(initial["downloadTotal"],0);
    let baseline=frame(&mut official,deadline).await;assert_eq!(baseline["upTotal"],0);assert_eq!(baseline["downTotal"],0);
    assert_eq!(f.api.state.traffic_feed.lifecycle.active_tasks(),1,"both subscriptions must share the original single sampler");
    real_plain_http(f.tunnel.clone()).await;
    let deadline=tokio::time::Instant::now()+Duration::from_secs(5);
    let actual=loop{let sampled=frame(&mut telemetry,deadline).await;validate(&sampled);if sampled["uploadTotal"].as_i64().unwrap()>0&&sampled["downloadTotal"].as_i64().unwrap()>0{break sampled;}};
    assert!(actual["uploadTotal"].as_i64().unwrap()>=3);assert!(actual["downloadTotal"].as_i64().unwrap()>=4);
    let original=loop{let sampled=frame(&mut official,deadline).await;if sampled["upTotal"]==actual["uploadTotal"]&&sampled["downTotal"]==actual["downloadTotal"]{break sampled;}};
    assert_eq!(actual["up"],original["up"]);assert_eq!(actual["down"],original["down"]);
    assert_eq!(f.tunnel.statistics().active_connection_count(),0);assert_eq!(f.api.state.traffic_feed.lifecycle.active_tasks(),1);
    telemetry.close(None).await.unwrap();official.close(None).await.unwrap();f.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=2)]
async fn telemetry_keeps_private_boundary_and_cancel_joins_feed_and_socket(){
    let f=Fixture::new().await;
    for (address,secret,status) in [(f.private,"invalid",401),(f.user,USER,404)]{
        match connect_async(format!("ws://{address}/aerove/telemetry?token={secret}")).await{
            Err(Error::Http(response))=>assert_eq!(response.status().as_u16(),status),
            _=>panic!("invalid telemetry boundary admitted"),
        }
    }
    assert_eq!(f.api.state.traffic_feed.lifecycle.active_tasks(),0,"rejected upgrades must not activate sampler");
    let(mut telemetry,_)=connect_async(format!("ws://{}/aerove/telemetry?token={PRIVATE}",f.private)).await.unwrap();
    let first=frame(&mut telemetry,tokio::time::Instant::now()+Duration::from_secs(5)).await;validate(&first);
    f.api.cancel();
    let ended=tokio::time::timeout(Duration::from_secs(5),telemetry.next()).await.unwrap();
    assert!(matches!(ended,None|Some(Ok(Message::Close(_)))|Some(Err(_))),"cancelled telemetry kept sending data");
    f.close().await;
}
