use super::*;
use tokio::{net::{TcpListener,TcpStream,UdpSocket},sync::oneshot};
use std::net::Ipv4Addr;
use runtime_control::{RuntimeControl,ListenerStatus};

async fn fixture(socks:bool)->(SocketAddr,tokio::task::JoinHandle<()>) {
    let listener=TcpListener::bind((Ipv4Addr::LOCALHOST,0)).await.unwrap();let address=listener.local_addr().unwrap();
    let task=tokio::spawn(async move {loop {
        let (mut stream,_)=listener.accept().await.unwrap();
        if socks {
            let mut greeting=[0;2];stream.read_exact(&mut greeting).await.unwrap();assert_eq!(greeting[0],5);
            let mut methods=vec![0;greeting[1] as usize];stream.read_exact(&mut methods).await.unwrap();assert!(methods.contains(&0));stream.write_all(&[5,0]).await.unwrap();
            let mut head=[0;4];stream.read_exact(&mut head).await.unwrap();assert_eq!(&head[..3],&[5,1,0]);
            let size=match head[3]{1=>6,4=>18,3=>stream.read_u8().await.unwrap() as usize+2,_=>panic!("invalid SOCKS destination")};
            stream.read_exact(&mut vec![0;size]).await.unwrap();
            stream.write_all(&[5,0,0,1,127,0,0,1,0,0]).await.unwrap();
        }
        let mut header=Vec::new();let mut byte=[0;1];while !header.ends_with(b"\r\n\r\n"){if stream.read_exact(&mut byte).await.is_err(){break;}header.push(byte[0]);assert!(header.len()<8192);}
        let _=stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\ncontrol-ok").await;
    }});(address,task)
}
async fn request(port:u16,target:u16)->String {
    let mut stream=TcpStream::connect((Ipv4Addr::LOCALHOST,port)).await.unwrap();stream.write_all(format!("GET http://127.0.0.1:{target}/ HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut response=String::new();tokio::time::timeout(Duration::from_secs(5),stream.read_to_string(&mut response)).await.unwrap().unwrap();response
}
async fn api(port:u16,path:&str)->String {
    let mut stream=TcpStream::connect((Ipv4Addr::LOCALHOST,port)).await.unwrap();stream.write_all(format!("GET {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer 0123456789abcdef0123456789abcdef\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
    let mut response=String::new();tokio::time::timeout(Duration::from_secs(5),stream.read_to_string(&mut response)).await.unwrap().unwrap();response
}
fn ports()->Vec<u16>{let held:Vec<_>=(22000..49151).filter_map(|p|std::net::TcpListener::bind((Ipv4Addr::LOCALHOST,p)).ok()).take(3).collect();held.iter().map(|s|s.local_addr().unwrap().port()).collect()}
async fn command(control:Arc<RuntimeControl>,action:&str)->ListenerStatus {let action=action.to_owned();tokio::task::spawn_blocking(move||match action.as_str(){"park"=>control.park(),"resume"=>control.resume_proxy(),_=>control.status()}).await.unwrap().unwrap()}
async fn io(failure:bool){
    let ports=ports();let (direct,direct_task)=fixture(false).await;let (proxy,proxy_task)=fixture(true).await;
    let source=format!("mode: rule\nmmdb: null\nasn-mmdb: null\ngeosite: null\ntun: {{enable: false}}\ndns: {{enable: false}}\nmixed-port: {}\nallow-lan: false\nbind-address: 127.0.0.1\nexternal-controller: 127.0.0.1:{}\nsecret: '0123456789abcdef0123456789abcdef'\ncors-allow-origins: []\nproxies: [{{name: actual-proxy, type: socks5, server: 127.0.0.1, port: {}}}]\nproxy-groups: [{{name: selected, type: select, proxies: [actual-proxy,DIRECT]}}]\nrules: ['DST-PORT,{},DIRECT','MATCH,selected']\n",ports[0],ports[1],proxy.port(),direct.port());
    let directory=tempfile::tempdir().unwrap();let path=directory.path().to_owned();let cancel=CancellationToken::new();let stop=cancel.clone();let control=RuntimeControl::new();let running=control.clone();let (ready,rx)=oneshot::channel();
    let owner=tokio::spawn(run_runtime_control(source,path,RuntimeInput::Proxy,stop,running,move|r|{let _=ready.send(r);}));
    tokio::time::timeout(Duration::from_secs(5),rx).await.unwrap().unwrap();
    let before=command(control.clone(),"read").await;assert!(before.listening&&before.user_listeners_ready&&!before.tun_ready);assert_eq!(before.active_ports,vec![ports[0]]);
    let bus=telemetry::source().unwrap();let mut traffic=bus.subscribe().0;
    assert!(request(ports[0],direct.port()).await.ends_with("control-ok"));
    tokio::time::timeout(Duration::from_secs(3),async{loop{if traffic.borrow_and_update().download_total>0{break;}traffic.changed().await.unwrap();}}).await.unwrap();
    let ctl=control.clone();tokio::task::spawn_blocking(move||ctl.statistics(true)).await.unwrap().unwrap();assert_eq!(traffic.borrow_and_update().download_total,0);
    let ctl=control.clone();tokio::task::spawn_blocking(move||ctl.statistics(false)).await.unwrap().unwrap();let direct_total=traffic.borrow_and_update().download_total;assert!(direct_total>0);
    assert!(request(ports[0],ports[2]).await.ends_with("control-ok"));
    tokio::time::timeout(Duration::from_secs(3),async{loop{if traffic.borrow_and_update().download_total>direct_total{break;}traffic.changed().await.unwrap();}}).await.unwrap();
    let all=*traffic.borrow_and_update();let ctl=control.clone();tokio::task::spawn_blocking(move||ctl.statistics(true)).await.unwrap().unwrap();let proxied=*traffic.borrow_and_update();assert!(proxied.download_total>0&&all.download_total>proxied.download_total);
    let mut idle=TcpStream::connect((Ipv4Addr::LOCALHOST,ports[0])).await.unwrap();idle.write_all(b"GET / HTTP/1.1\r\n").await.unwrap();
    let mut udp=TcpStream::connect((Ipv4Addr::LOCALHOST,ports[0])).await.unwrap();udp.write_all(&[5,1,0]).await.unwrap();let mut greeting=[0;2];udp.read_exact(&mut greeting).await.unwrap();assert_eq!(greeting,[5,0]);udp.write_all(&[5,3,0,1,0,0,0,0,0,0]).await.unwrap();let mut relay=[0;10];udp.read_exact(&mut relay).await.unwrap();let relay_port=u16::from_be_bytes([relay[8],relay[9]]);
    let parked=command(control.clone(),"park").await;assert!(!parked.listening&&!parked.tun_ready&&!parked.user_listeners_ready);assert!(parked.active_ports.is_empty());assert!(parked.core_epoch.parse::<u64>().unwrap()>before.core_epoch.parse::<u64>().unwrap());
    let mut byte=[0;1];
    for stream in [&mut idle,&mut udp] {
        match tokio::time::timeout(Duration::from_secs(2),stream.read(&mut byte)).await.unwrap() {
            Ok(0)=>{},Err(error) if matches!(error.kind(),std::io::ErrorKind::ConnectionReset|std::io::ErrorKind::BrokenPipe|std::io::ErrorKind::UnexpectedEof)=>{},
            result=>panic!("parked connection remained usable: {result:?}"),
        }
    }
    assert!(UdpSocket::bind((Ipv4Addr::UNSPECIFIED,relay_port)).await.is_ok());
    assert!(api(ports[1],"/configs").await.starts_with("HTTP/1.1 200"));let network=api(ports[1],"/aerove/network").await;assert!(network.starts_with("HTTP/1.1 200")&&network.contains("\"activePorts\":[]"));
    let held=TcpListener::bind((Ipv4Addr::LOCALHOST,ports[0])).await.unwrap();
    if failure {
        let ctl=control.clone();assert!(tokio::task::spawn_blocking(move||ctl.resume_proxy()).await.unwrap().is_err());assert!(tokio::time::timeout(Duration::from_secs(5),owner).await.unwrap().unwrap().is_err());
        assert!(CONTEXT.read().unwrap().is_none());drop(held);
    } else {
        drop(held);let resumed=command(control.clone(),"resume").await;assert!(resumed.listening&&resumed.user_listeners_ready&&!resumed.tun_ready);assert_eq!(resumed.active_ports,vec![ports[0]]);assert!(resumed.core_epoch.parse::<u64>().unwrap()>parked.core_epoch.parse::<u64>().unwrap());assert!(resumed.only_statistics_proxy);
        assert!(Arc::ptr_eq(&bus,&telemetry::source().unwrap()));assert!(request(ports[0],direct.port()).await.ends_with("control-ok"));
        command(control.clone(),"park").await;cancel.cancel();tokio::time::timeout(Duration::from_secs(5),owner).await.unwrap().unwrap().unwrap();
    }
    direct_task.abort();proxy_task.abort();let _=direct_task.await;let _=proxy_task.await;
}
pub async fn run(case:&str){match case{"control-io"=>io(false).await,"control-failure"=>io(true).await,_=>panic!("unknown runtime control regression")}}
