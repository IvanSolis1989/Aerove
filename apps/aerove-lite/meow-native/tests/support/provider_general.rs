#![allow(dead_code)]
use aerove_meow_core::{Registry,Session,network_context::NetworkContext};
use meow_common::socket_protect::SocketProtector;
use std::{io,net::SocketAddr,os::fd::RawFd,sync::{Arc,atomic::{AtomicUsize,AtomicBool,Ordering}},time::Duration};
use tokio::{io::{AsyncReadExt,AsyncWriteExt},net::{TcpListener,TcpStream},sync::{Notify,RwLock},task::{JoinHandle,JoinSet},time::timeout};
use tokio_util::sync::CancellationToken;
pub const SECRET:&str="provider-general-private-secret-0000001";
pub struct Protect(pub AtomicUsize);
impl SocketProtector for Protect{fn protect(&self,fd:RawFd)->io::Result<()>{assert!(unsafe{libc::fcntl(fd,libc::F_GETFD)}>=0);self.0.fetch_add(1,Ordering::Relaxed);Ok(())}}
pub async fn ports()->[SocketAddr;2]{let a=TcpListener::bind("127.0.0.1:0").await.unwrap();let b=TcpListener::bind("127.0.0.1:0").await.unwrap();[a.local_addr().unwrap(),b.local_addr().unwrap()]}
pub fn plan(yaml:&str,ports:[SocketAddr;2],read:&std::path::Path,cache:&std::path::Path)->String{
    serde_json::json!({"schemaVersion":1,"runtimeMode":"proxy","initiallyParked":false,
    "engineYaml":format!("{yaml}\ndns: {{enable: false,use-system-hosts: false}}\ntun: {{enable: false,auto-route: false}}\n"),
    "privateControl":{"address":ports[0].to_string(),"secret":SECRET},"inbounds":{"configYaml":format!("mixed-port: {}\nbind-address: 127.0.0.1",ports[1].port())},"tun":null,
    "physicalNetwork":{"handle":null,"dns":{"defaultInterface":"fixture","interfaces":{"fixture":["127.0.0.1"]}}},"hostPolicyYaml":"unified-delay: false\ntcp-concurrent: false","profileId":null,"revision":null,"resourceGeneration":null,
    "resourceReadRoot":read.to_str().unwrap(),"cacheRoot":cache.to_str().unwrap()}).to_string()
}
pub fn start(json:String)->(Arc<Session>,std::sync::Weak<Protect>){let protect=Arc::new(Protect(AtomicUsize::new(0)));let weak=Arc::downgrade(&protect);let network=NetworkContext::protected(protect,None,CancellationToken::new()).with_physical_dns(vec!["127.0.0.1:53".parse().unwrap()]);(Registry::start_owned(json,None,network,None).unwrap(),weak)}
pub async fn head(stream:&mut TcpStream)->io::Result<Vec<u8>>{timeout(Duration::from_secs(5),async{let mut bytes=Vec::new();while !bytes.ends_with(b"\r\n\r\n"){bytes.push(stream.read_u8().await?);assert!(bytes.len()<16384);}Ok(bytes)}).await.unwrap()}
pub async fn request_result(address:SocketAddr,method:&str,path:&str,body:&str)->io::Result<String>{
    let mut stream=TcpStream::connect(address).await?;stream.write_all(format!("{method} {path} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {SECRET}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).as_bytes()).await?;let mut bytes=Vec::new();timeout(Duration::from_secs(5),stream.read_to_end(&mut bytes)).await.unwrap()?;String::from_utf8(bytes).map_err(|cause|io::Error::new(io::ErrorKind::InvalidData,cause))
}
pub async fn request(address:SocketAddr,method:&str,path:&str,body:&str)->String{request_result(address,method,path,body).await.unwrap()}

pub fn json(response:&str)->serde_json::Value{serde_json::from_str(response.split_once("\r\n\r\n").unwrap().1).unwrap()}
pub async fn get(address:SocketAddr,path:&str)->serde_json::Value{let value=request(address,"GET",path,"").await;assert!(value.starts_with("HTTP/1.1 200"),"{value}");json(&value)}
pub async fn connect(mixed:SocketAddr,destination:SocketAddr)->TcpStream{
    let mut stream=TcpStream::connect(mixed).await.unwrap();stream.write_all(format!("CONNECT {destination} HTTP/1.1\r\nHost: {destination}\r\n\r\n").as_bytes()).await.unwrap();let reply=head(&mut stream).await.unwrap();assert!(reply.starts_with(b"HTTP/1.1 200"),"{}",String::from_utf8_lossy(&reply));stream
}
pub async fn roundtrip(mixed:SocketAddr,destination:SocketAddr){let mut stream=connect(mixed,destination).await;stream.write_all(b"ping").await.unwrap();let mut reply=[0;4];timeout(Duration::from_secs(5),stream.read_exact(&mut reply)).await.unwrap().unwrap();assert_eq!(&reply,b"pong");stream.shutdown().await.unwrap();}
#[derive(Clone)]pub struct Peer{pub address:SocketAddr,pub targets:Arc<std::sync::Mutex<Vec<String>>>,pub healthy:Arc<AtomicBool>,pub hold:Arc<AtomicBool>,pub started:Arc<Notify>,pub release:Arc<Notify>,pub eof:Arc<Notify>}
#[derive(Clone)]pub struct Feed{pub address:SocketAddr,pub userinfo:Arc<RwLock<Option<String>>>,pub payload:Arc<RwLock<String>>,pub hold:Arc<AtomicBool>,pub started:Arc<Notify>,pub release:Arc<Notify>,pub requests:Arc<AtomicUsize>,pub eof:Arc<Notify>}
pub struct Servers{stop:CancellationToken,tasks:Vec<JoinHandle<()>>,pub trace:Arc<std::sync::Mutex<Vec<(SocketAddr,String)>>>}
impl Servers{
    pub fn new()->Self{Self{stop:CancellationToken::new(),tasks:Vec::new(),trace:Arc::new(std::sync::Mutex::new(Vec::new()))}}
    pub async fn origin(&mut self)->SocketAddr{
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();let stop=self.stop.clone();
        self.tasks.push(tokio::spawn(async move{let mut tasks=JoinSet::<()>::new();loop{tokio::select!{biased;_ =stop.cancelled()=>break,Some(joined)=tasks.join_next(),if !tasks.is_empty()=>{joined.unwrap();},accepted=listener.accept()=>{let(mut stream,_)=accepted.unwrap();tasks.spawn(async move{
            let mut first=[0;4];if stream.read_exact(&mut first).await.is_err(){return;}if &first==b"ping"{stream.write_all(b"pong").await.unwrap();let mut rest=Vec::new();let _=stream.read_to_end(&mut rest).await;}else{let mut bytes=first.to_vec();while !bytes.ends_with(b"\r\n\r\n"){bytes.push(stream.read_u8().await.unwrap());assert!(bytes.len()<16384);}stream.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();}
        });}}}tasks.abort_all();while tasks.join_next().await.is_some(){}}));address
    }
    pub async fn proxy(&mut self)->Peer{
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();let stop=self.stop.clone();let targets=Arc::new(std::sync::Mutex::new(Vec::new()));let seen=targets.clone();let peer=Peer{address,targets:targets.clone(),healthy:Arc::new(AtomicBool::new(true)),hold:Arc::new(AtomicBool::new(false)),started:Arc::new(Notify::new()),release:Arc::new(Notify::new()),eof:Arc::new(Notify::new())};let options=peer.clone();let trace=self.trace.clone();
        self.tasks.push(tokio::spawn(async move{let mut tasks=JoinSet::<()>::new();loop{tokio::select!{biased;_=stop.cancelled()=>break,Some(joined)=tasks.join_next(),if !tasks.is_empty()=>{joined.unwrap();},accepted=listener.accept()=>{let(mut stream,_)=accepted.unwrap();let seen=seen.clone();let options=options.clone();let trace=trace.clone();tasks.spawn(async move{let bytes=head(&mut stream).await.unwrap();let line=std::str::from_utf8(&bytes).unwrap().lines().next().unwrap();assert!(line.starts_with("CONNECT "),"{line}");let target=line.split_whitespace().nth(1).unwrap().to_owned();seen.lock().unwrap().push(target.clone());trace.lock().unwrap().push((address,target.clone()));if !options.healthy.load(Ordering::Acquire){stream.write_all(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n").await.unwrap();return;}let mut upstream=TcpStream::connect(target).await.unwrap();if options.hold.load(Ordering::Acquire){options.started.notify_one();let mut byte=[0];tokio::select!{_=options.release.notified()=>{},received=stream.read(&mut byte)=>{assert!(matches!(received,Ok(0)|Err(_)));options.eof.notify_one();return;}}}stream.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await.unwrap();let _=tokio::io::copy_bidirectional(&mut stream,&mut upstream).await;});}}}tasks.abort_all();while tasks.join_next().await.is_some(){}}));peer
    }
    pub async fn feed(&mut self,payload:String)->Feed{
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let feed=Feed{address:listener.local_addr().unwrap(),userinfo:Arc::new(RwLock::new(None)),payload:Arc::new(RwLock::new(payload)),hold:Arc::new(AtomicBool::new(false)),started:Arc::new(Notify::new()),release:Arc::new(Notify::new()),requests:Arc::new(AtomicUsize::new(0)),eof:Arc::new(Notify::new())};let state=feed.clone();let stop=self.stop.clone();
        self.tasks.push(tokio::spawn(async move{let mut tasks=JoinSet::<()>::new();loop{tokio::select!{biased;_=stop.cancelled()=>break,Some(joined)=tasks.join_next(),if !tasks.is_empty()=>{joined.unwrap();},accepted=listener.accept()=>{let(mut stream,_)=accepted.unwrap();let state=state.clone();let stop=stop.clone();tasks.spawn(async move{head(&mut stream).await.unwrap();state.requests.fetch_add(1,Ordering::Relaxed);if state.hold.load(Ordering::Acquire){state.started.notify_one();let mut byte=[0];tokio::select!{_=stop.cancelled()=>return,_=state.release.notified()=>{},result=stream.read(&mut byte)=>{assert!(matches!(result,Ok(0)|Err(_)));state.eof.notify_one();return;}}}let body=state.payload.read().await.clone();let userinfo=state.userinfo.read().await.as_ref().map(|value|format!("subscription-userinfo: {value}\r\n")).unwrap_or_default();let reply=format!("HTTP/1.1 200 OK\r\n{userinfo}Content-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len());stream.write_all(reply.as_bytes()).await.unwrap();});}}}tasks.abort_all();while tasks.join_next().await.is_some(){}}));feed
    }
    pub async fn close(self){self.stop.cancel();for task in self.tasks{timeout(Duration::from_secs(5),task).await.unwrap().unwrap();}}
}
pub fn nodes(name:&str,port:u16)->String{format!("proxies:\n - {{name: {name},type: http,server: 127.0.0.1,port: {port}}}\n")}
pub fn delay_path(name:&str,target:SocketAddr)->String{format!("/proxies/{name}/delay?url=http%3A%2F%2F{target}%2Fhealth&timeout=2000")}
pub async fn stopped(session:&Arc<Session>,weak:std::sync::Weak<Protect>,ports:[SocketAddr;2]){let owner=session.owned_runtime().unwrap();let state=timeout(Duration::from_secs(5),session.stop()).await.unwrap().unwrap();assert_eq!(state.phase,aerove_meow_core::Phase::Stopped);assert!(owner.cleanup_confirmed());assert!(weak.upgrade().is_none());for address in ports{assert!(TcpListener::bind(address).await.is_ok());}}
