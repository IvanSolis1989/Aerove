#![cfg(any(target_os="linux",target_os="android",target_os="macos"))]
use aerove_meow_core::transport_tasks::TransportTasks;
use meow_config::owned_load::{LoadContext,OwnedLoadScope,OwnedWork,with_dns_transport_scope};
use meow_common::transport_tasks::{TransportTaskContext,TransportScope,Work};
use hickory_proto::{op::{Message,MessageType,OpCode},rr::{Record,RData,rdata::A}};
use std::{io,net::{Ipv4Addr,SocketAddr},sync::{Arc,Mutex,Weak,atomic::{AtomicUsize,Ordering}},time::Duration};
use tokio::{net::UdpSocket,task::AbortHandle};
use tokio_util::sync::CancellationToken;

struct Owner { tasks:Arc<TransportTasks>, scope:Mutex<Weak<OwnedLoadScope>>, starts:AtomicUsize }
impl Owner {
    fn new()->Arc<Self>{Arc::new(Self{tasks:TransportTasks::new(),scope:Mutex::new(Weak::new()),starts:AtomicUsize::new(0)})}
    fn bind(&self,scope:&Arc<OwnedLoadScope>){*self.scope.lock().unwrap()=Arc::downgrade(scope);}
    fn transport(self:&Arc<Self>)->TransportScope {TransportScope::new(self.clone())}
    async fn close(&self){assert!(!self.tasks.close_joined().await.unwrap().panicked);}
}
impl TransportTaskContext for Owner {
    fn spawn(&self,work:Work)->io::Result<AbortHandle> {
        let scope=self.scope.lock().unwrap().upgrade().ok_or_else(||io::Error::other("factoryScopeGone"))?;
        let abort=self.tasks.spawn_with(work,move|work|scope.spawn_owned(work).map_err(io::Error::other))?;
        self.starts.fetch_add(1,Ordering::SeqCst);Ok(abort)
    }
}
struct Context { dns:Option<Arc<Owner>>, physical:SocketAddr }
impl LoadContext for Context {
    fn requires_dns_transport_scope(&self)->bool{true}
    fn dns_transport_scope(&self)->Option<TransportScope>{self.dns.as_ref().map(Owner::transport)}
    fn requires_physical_dns(&self)->bool{true}
    fn physical_dns_servers(&self)->Option<Vec<SocketAddr>>{Some(vec![self.physical])}
    fn wrap(&self,work:OwnedWork)->OwnedWork{work}
    fn run_blocking(&self,work:Box<dyn FnOnce()+Send>){work();}
}
struct Server { address:SocketAddr, stop:CancellationToken, serving:tokio::task::JoinHandle<()> }
impl Server {
    async fn new()->Self {
        let socket=UdpSocket::bind("127.0.0.1:0").await.unwrap();let address=socket.local_addr().unwrap();let stop=CancellationToken::new();let cancel=stop.clone();
        let serving=tokio::spawn(async move {let mut buffer=[0;4096];loop{tokio::select!{biased;
            _=cancel.cancelled()=>return,
            packet=socket.recv_from(&mut buffer)=>{let(size,peer)=packet.unwrap();let query=Message::from_bytes(&buffer[..size]).unwrap();
                let mut reply=Message::new(query.metadata.id,MessageType::Response,OpCode::Query);reply.add_queries(query.queries.iter().cloned());
                reply.add_answer(Record::from_rdata(query.queries[0].name.clone(),60,RData::A(A(Ipv4Addr::new(192,0,2,42)))));
                socket.send_to(&reply.to_bytes().unwrap(),peer).await.unwrap();}
        }}});Self{address,stop,serving}
    }
    async fn close(self){self.stop.cancel();self.serving.await.unwrap();}
}
async fn answer(resolver:&meow_dns::Resolver,name:&str){assert_eq!(tokio::time::timeout(Duration::from_secs(5),resolver.lookup_ipv4(name)).await.unwrap(),Some("192.0.2.42".parse().unwrap()));}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn initial_disabled_main_proxy_and_explicit_rebuild_capture_dns_owner_instead_of_session_owner() {
    let server=Server::new().await;let dns=Owner::new();let session=Owner::new();
    let scope=OwnedLoadScope::with_context(Arc::new(Context{dns:Some(dns.clone()),physical:server.address}));dns.bind(&scope);session.bind(&scope);
    let disabled=session.transport().scope(meow_config::load_config_from_str_owned("strict: true\nipv6: false\ndns: {enable: false}\nrules: ['MATCH,DIRECT']\n",&scope)).await.unwrap();
    answer(&disabled.dns.resolver,"physical-owned.example").await;assert!(dns.starts.load(Ordering::SeqCst)>0);assert_eq!(session.starts.load(Ordering::SeqCst),0);
    let yaml=format!("strict: true\nipv6: false\ndns:\n enable: true\n use-system-hosts: false\n nameserver: ['udp://{}']\n proxy-server-nameserver: ['udp://{}']\nrules: ['MATCH,DIRECT']\n",server.address,server.address);
    let enabled=session.transport().scope(meow_config::load_config_from_str_owned(&yaml,&scope)).await.unwrap();
    let before=dns.starts.load(Ordering::SeqCst);answer(&enabled.dns.resolver,"main-owned.example").await;assert!(dns.starts.load(Ordering::SeqCst)>before);
    let before=dns.starts.load(Ordering::SeqCst);answer(enabled.dns.proxy_resolver.as_ref().unwrap(),"proxy-owned.example").await;assert!(dns.starts.load(Ordering::SeqCst)>before);
    let next=Owner::new();next.bind(&scope);let before=dns.starts.load(Ordering::SeqCst);
    let rebuilt=scope.run(with_dns_transport_scope(next.transport(),meow_config::dns_parser::parse_dns(&enabled.raw,None,None,&enabled.proxies,None,&enabled.rule_providers,None))).await.unwrap();
    answer(&rebuilt.resolver,"new-main-owned.example").await;answer(rebuilt.proxy_resolver.as_ref().unwrap(),"new-proxy-owned.example").await;
    assert!(next.starts.load(Ordering::SeqCst)>=2);assert_eq!(dns.starts.load(Ordering::SeqCst),before);assert_eq!(session.starts.load(Ordering::SeqCst),0);
    next.close().await;dns.close().await;session.close().await;scope.close().await.unwrap();server.close().await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn required_dns_owner_fails_closed_and_retirement_joins_actual_pending_exchange() {
    let socket=UdpSocket::bind("127.0.0.1:0").await.unwrap();let address=socket.local_addr().unwrap();
    let missing=OwnedLoadScope::with_context(Arc::new(Context{dns:None,physical:address}));
    let error=match meow_config::load_config_from_str_owned("strict: true\ndns: {enable: false}\n",&missing).await{Ok(_)=>panic!("missing DNS owner admitted"),Err(error)=>error};
    assert!(error.to_string().contains("dnsTransportOwnerMissing"));missing.close().await.unwrap();
    let dns=Owner::new();let scope=OwnedLoadScope::with_context(Arc::new(Context{dns:Some(dns.clone()),physical:address}));dns.bind(&scope);
    let config=meow_config::load_config_from_str_owned("strict: true\nipv6: false\ndns: {enable: false}\n",&scope).await.unwrap();
    let resolver=config.dns.resolver.clone();let query=tokio::spawn(async move{resolver.lookup_ipv4("pending-owned.example").await});
    let mut wire=[0;4096];tokio::time::timeout(Duration::from_secs(5),socket.recv_from(&mut wire)).await.unwrap().unwrap();
    dns.close().await;assert_eq!(tokio::time::timeout(Duration::from_secs(5),query).await.unwrap().unwrap(),None);
    assert!(dns.starts.load(Ordering::SeqCst)>0);scope.close().await.unwrap();
}
