#![cfg(all(feature="vless",any(target_os="linux",target_os="android",target_os="macos")))]
use aerove_meow_core::transport_tasks::TransportTasks;
use hickory_proto::{op::{Message,MessageType,OpCode},rr::{Name,Record,RecordType,RData,rdata::{HTTPS,SVCB,svcb::{EchConfigList,SvcParamKey,SvcParamValue}}}};
use meow_common::{Metadata,socket_protect::{NetworkScope,SocketProtector},transport_tasks::{TransportTaskContext,TransportScope,Work}};
use meow_config::{EchScope,EchSource,EchQuery,owned_load::{LoadContext,OwnedLoadScope,OwnedWork,DisabledDnsSource}};
use meow_transport::{Transport,tls::{TlsConfig,TlsLayer,EchOpts}};
use std::{io,sync::{Arc,Mutex,Weak,atomic::{AtomicUsize,Ordering}},time::Duration};
use tokio::{io::AsyncReadExt,net::{TcpListener,TcpStream,UdpSocket},task::AbortHandle};
struct Owner{tasks:Arc<TransportTasks>,scope:Mutex<Weak<OwnedLoadScope>>,starts:AtomicUsize}
impl Owner{
    fn new()->Arc<Self>{Arc::new(Self{tasks:TransportTasks::new(),scope:Mutex::new(Weak::new()),starts:AtomicUsize::new(0)})}
    fn bind(&self,scope:&Arc<OwnedLoadScope>){*self.scope.lock().unwrap()=Arc::downgrade(scope);}
    fn transport(self:&Arc<Self>)->TransportScope{TransportScope::new(self.clone())}
    async fn close(&self){assert!(!self.tasks.close_joined().await.unwrap().panicked);}
}
impl TransportTaskContext for Owner{
    fn spawn(&self,work:Work)->io::Result<AbortHandle>{let scope=self.scope.lock().unwrap().upgrade().ok_or_else(||io::Error::other("scopeGone"))?;
        let abort=self.tasks.spawn_with(work,move|work|scope.spawn_owned(work).map_err(io::Error::other))?;self.starts.fetch_add(1,Ordering::SeqCst);Ok(abort)}
}
struct Context{owner:Arc<Owner>,physical:Option<std::net::SocketAddr>,offline:bool}
impl LoadContext for Context{
    fn requires_dns_transport_scope(&self)->bool{true}
    fn dns_transport_scope(&self)->Option<TransportScope>{Some(self.owner.transport())}
    fn requires_physical_dns(&self)->bool{true}
    fn physical_dns_servers(&self)->Option<Vec<std::net::SocketAddr>>{self.physical.map(|server|vec![server])}
    fn disabled_dns_source(&self)->Result<DisabledDnsSource,&'static str>{if self.offline{Ok(DisabledDnsSource::UnavailableNotConnected)}else{self.physical.map(|server|DisabledDnsSource::Physical(vec![server])).ok_or("physicalDnsSnapshotMissing")}}
    fn wrap(&self,work:OwnedWork)->OwnedWork{work}
    fn run_blocking(&self,work:Box<dyn FnOnce()+Send>){work();}
}
struct DenyAll(AtomicUsize);
impl SocketProtector for DenyAll{fn protect(&self,_fd:std::os::fd::RawFd)->io::Result<()>{self.0.fetch_add(1,Ordering::SeqCst);Err(io::ErrorKind::PermissionDenied.into())}}
struct Fixture{scope:Arc<OwnedLoadScope>,owner:Arc<Owner>,config:meow_config::Config,main:UdpSocket,proxy:UdpSocket,tcp:TcpListener}
impl Fixture{
    async fn new()->Self{
        let main=UdpSocket::bind("127.0.0.1:0").await.unwrap();let proxy=UdpSocket::bind("127.0.0.1:0").await.unwrap();let tcp=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let owner=Owner::new();let scope=OwnedLoadScope::with_context(Arc::new(Context{owner:owner.clone(),physical:Some(proxy.local_addr().unwrap()),offline:false}));owner.bind(&scope);
        let yaml=format!("strict: true\nipv6: false\nproxies:\n - name: v\n   type: vless\n   server: 127.0.0.1\n   port: {}\n   uuid: 00000000-0000-0000-0000-000000000001\n   tls: true\n   servername: inner.example\n   skip-cert-verify: true\n   ech-opts: {{enable: true, query-server-name: ech.example}}\ndns:\n enable: true\n use-system-hosts: false\n nameserver: ['udp://{}']\n proxy-server-nameserver: ['udp://{}']\nrules: ['MATCH,v']\n",tcp.local_addr().unwrap().port(),main.local_addr().unwrap(),proxy.local_addr().unwrap());
        let deny=Arc::new(DenyAll(AtomicUsize::new(0)));let protection=NetworkScope{protector:Some(deny.clone()),resolver:None};
        let config=tokio::time::timeout(Duration::from_secs(5),protection.scope(meow_config::load_config_from_str_owned(&yaml,&scope))).await.unwrap().unwrap();
        assert_eq!(deny.0.load(Ordering::SeqCst),0,"factory must not create ECH DNS/bootstrap sockets");
        assert_eq!(owner.starts.load(Ordering::SeqCst),0);let mut buf=[0;4096];assert!(main.try_recv_from(&mut buf).is_err());assert!(proxy.try_recv_from(&mut buf).is_err());
        assert!(config.raw.proxies.as_ref().unwrap()[0]["ech-opts"].get("config").is_none(),"pure parse must not inject fetched inline bytes");
        Self{scope,owner,config,main,proxy,tcp}
    }
    fn ech(&self)->EchScope{meow_config::ech_dns::resolver_scope(self.config.dns.resolver.clone(),self.config.dns.proxy_resolver.clone())}
    fn dial(&self,ech:Option<EchScope>)->tokio::task::JoinHandle<meow_common::Result<Box<dyn meow_common::ProxyConn>>>{
        let proxy=self.config.proxies["v"].clone();tokio::spawn(async move{let work=Box::pin(async move{proxy.dial_tcp(&Metadata{host:"target.example".into(),dst_port:443,..Default::default()}).await});match ech{Some(scope)=>scope.scope(work).await,None=>work.await}})
    }
    async fn connected(&self)->TcpStream{tokio::time::timeout(Duration::from_secs(5),self.tcp.accept()).await.unwrap().unwrap().0}
    async fn query(&self)->(Message,std::net::SocketAddr){let mut b=[0;4096];let(size,peer)=tokio::time::timeout(Duration::from_secs(5),self.proxy.recv_from(&mut b)).await.unwrap().unwrap();let q=Message::from_bytes(&b[..size]).unwrap();assert_eq!(q.queries.len(),1);assert_eq!(q.queries[0].query_type,RecordType::HTTPS);assert_eq!(q.queries[0].name,Name::from_utf8("ech.example.").unwrap());(q,peer)}
    async fn answer(&self,q:Message,peer:std::net::SocketAddr,bytes:Vec<u8>){let mut r=Message::new(q.metadata.id,MessageType::Response,OpCode::Query);r.add_queries(q.queries.iter().cloned());r.add_answer(Record::from_rdata(q.queries[0].name.clone(),60,RData::HTTPS(HTTPS(SVCB::new(1,Name::root(),vec![(SvcParamKey::EchConfigList,SvcParamValue::EchConfigList(EchConfigList(bytes)))])))));self.proxy.send_to(&r.to_bytes().unwrap(),peer).await.unwrap();}
    async fn close(self){self.owner.close().await;self.scope.close().await.unwrap();}
}
fn config_list()->Vec<u8>{
    // Actual RFC ECHConfigList for X25519/HKDF-SHA256/AES-128-GCM. The test
    // inspects the real BoringSSL ClientHello; it does not claim a server
    // accepted the key or a VLESS protocol peer completed its handshake.
    let mut contents=vec![1,0,32,0,32];let mut public=[0u8;32];public[0]=9;contents.extend(public);contents.extend([0,4,0,1,0,1,0]);let cover=b"cover.example";contents.push(cover.len()as u8);contents.extend(cover);contents.extend([0,0]);
    let mut config=vec![0xfe,0x0d];config.extend((contents.len()as u16).to_be_bytes());config.extend(contents);let mut list=(config.len()as u16).to_be_bytes().to_vec();list.extend(config);list
}
async fn hello(stream:&mut TcpStream)->Vec<(u16,Vec<u8>)>{
    let mut h=[0;5];tokio::time::timeout(Duration::from_secs(5),stream.read_exact(&mut h)).await.unwrap().unwrap();assert_eq!(h[0],22);let mut b=vec![0;u16::from_be_bytes([h[3],h[4]])as usize];stream.read_exact(&mut b).await.unwrap();assert_eq!(b[0],1);
    let mut p=4+2+32;p+=1+b[p]as usize;let cipher=u16::from_be_bytes([b[p],b[p+1]])as usize;p+=2+cipher;p+=1+b[p]as usize;let n=u16::from_be_bytes([b[p],b[p+1]])as usize;p+=2;let end=p+n;assert!(end<=b.len());let mut extensions=Vec::new();while p<end{let kind=u16::from_be_bytes([b[p],b[p+1]]);let n=u16::from_be_bytes([b[p+2],b[p+3]])as usize;p+=4;assert!(p+n<=end);extensions.push((kind,b[p..p+n].to_vec()));p+=n;}extensions
}
async fn eof(stream:&mut TcpStream){let mut bytes=Vec::new();tokio::time::timeout(Duration::from_secs(5),stream.read_to_end(&mut bytes)).await.unwrap().unwrap();assert!(bytes.is_empty(),"ECH failure/cancel must not emit plaintext ClientHello");}
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn pure_factory_then_actual_vless_tls_queries_captured_proxy_resolver_and_encrypts_inner_name(){
    let f=Fixture::new().await;let dial=f.dial(Some(f.ech()));let mut peer=f.connected().await;let(q,address)=f.query().await;f.answer(q,address,config_list()).await;
    let extensions=hello(&mut peer).await;assert!(extensions.iter().any(|(kind,_)|*kind==0xfe0d));let sni=&extensions.iter().find(|(kind,_)|*kind==0).unwrap().1;assert!(sni.windows(b"cover.example".len()).any(|w|w==b"cover.example"));assert!(!sni.windows(b"inner.example".len()).any(|w|w==b"inner.example"));
    drop(peer);assert!(tokio::time::timeout(Duration::from_secs(5),dial).await.unwrap().unwrap().is_err());let mut b=[0;4096];assert!(f.main.try_recv_from(&mut b).is_err());assert!(f.owner.starts.load(Ordering::SeqCst)>0);f.close().await;
}
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn missing_scope_and_explicit_offline_error_fail_before_hello_while_inline_needs_no_dns(){
    let f=Fixture::new().await;let dial=f.dial(None);let mut peer=f.connected().await;let error=tokio::time::timeout(Duration::from_secs(5),dial).await.unwrap().unwrap().err().unwrap();assert!(error.to_string().contains("echResolverScopeMissing"));eof(&mut peer).await;assert_eq!(f.owner.starts.load(Ordering::SeqCst),0);
    let owner=Owner::new();let scope=OwnedLoadScope::with_context(Arc::new(Context{owner:owner.clone(),physical:None,offline:true}));owner.bind(&scope);let offline=meow_config::load_config_from_str_owned("strict: true\ndns: {enable: false}\n",&scope).await.unwrap();
    let ech=meow_config::ech_dns::resolver_scope(offline.dns.resolver.clone(),None);let mut cfg=TlsConfig::new("inner.example");cfg.skip_cert_verify=true;cfg.ech=Some(EchOpts::Dns("ech.example".into()));let layer=TlsLayer::new(&cfg).unwrap();let(client,mut server)=tokio::io::duplex(16384);let error=ech.scope(layer.connect(Box::new(client))).await.err().unwrap();assert!(error.to_string().contains("ech DNS"));eof_duplex(&mut server).await;assert_eq!(owner.starts.load(Ordering::SeqCst),0);
    cfg.ech=Some(EchOpts::Config(config_list()));let layer=TlsLayer::new(&cfg).unwrap();let(client,mut server)=tokio::io::duplex(16384);let inline=tokio::spawn(async move{layer.connect(Box::new(client)).await});let mut header=[0;5];tokio::time::timeout(Duration::from_secs(5),server.read_exact(&mut header)).await.unwrap().unwrap();assert_eq!(header[0],22);drop(server);assert!(tokio::time::timeout(Duration::from_secs(5),inline).await.unwrap().unwrap().is_err());owner.close().await;scope.close().await.unwrap();f.close().await;
}
async fn eof_duplex(stream:&mut tokio::io::DuplexStream){let mut b=Vec::new();tokio::time::timeout(Duration::from_secs(5),stream.read_to_end(&mut b)).await.unwrap().unwrap();assert!(b.is_empty());}
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn caller_abort_cancels_actual_https_query_then_owner_joins_and_next_scope_is_clean(){
    let f=Fixture::new().await;let dial=f.dial(Some(f.ech()));let mut peer=f.connected().await;let _=f.query().await;assert!(f.owner.starts.load(Ordering::SeqCst)>0);dial.abort();let cancelled=match dial.await{Err(error)=>error,Ok(_)=>panic!("aborted TLS caller completed")};assert!(cancelled.is_cancelled());eof(&mut peer).await;
    f.owner.close().await;let history=f.owner.starts.load(Ordering::SeqCst);assert!(EchScope::current().is_none());
    // Another real owner/resolver can perform the same name after cancellation;
    // ancestry is operation-local, never a shared global busy flag.
    let next=Fixture::new().await;let task=next.dial(Some(next.ech()));let mut peer=next.connected().await;let(q,address)=next.query().await;next.answer(q,address,vec![0,1,0]).await;assert!(tokio::time::timeout(Duration::from_secs(5),task).await.unwrap().unwrap().is_err());eof(&mut peer).await;assert_eq!(f.owner.starts.load(Ordering::SeqCst),history);next.close().await;f.close().await;
}
struct Recursive{owner:Arc<Owner>,calls:AtomicUsize}
impl EchSource for Recursive{
    fn query<'a>(&'a self,_name:&'a str)->EchQuery<'a>{Box::pin(async move{
        if self.calls.fetch_add(1,Ordering::SeqCst)>=4{return Err(io::Error::other("recursiveGuardMissing"));}let(send,receive)=tokio::sync::oneshot::channel();
        let work:Work=EchScope::wrap_current(async move{let _=send.send(EchScope::resolve("CYCLE.example.".into()).await);});
        let _ = self.owner.spawn(work)?;receive.await.map_err(|_|io::Error::other("ownedRecursiveQueryLost"))?
    })}
}
struct FreshContext(EchScope);
impl LoadContext for FreshContext{
    fn wrap(&self,work:OwnedWork)->OwnedWork{let fresh=self.0.clone();Box::pin(async move{fresh.scope(work).await})}
    fn run_blocking(&self,work:Box<dyn FnOnce()+Send>){work();}
}
#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn recursive_query_crosses_actual_registered_task_and_is_rejected_before_source_reentry(){
    let owner=Owner::new();let source=Arc::new(Recursive{owner:owner.clone(),calls:AtomicUsize::new(0)});
    let ech=EchScope::new(source.clone());let scope=OwnedLoadScope::with_context(Arc::new(FreshContext(ech.clone())));owner.bind(&scope);
    let error=tokio::time::timeout(Duration::from_secs(5),ech.scope(EchScope::resolve("cycle.example".into()))).await.unwrap().unwrap_err();assert_eq!(error.kind(),io::ErrorKind::InvalidInput);assert!(error.to_string().contains("echDnsCycle"));assert_eq!(source.calls.load(Ordering::SeqCst),1);assert_eq!(owner.starts.load(Ordering::SeqCst),1);assert!(EchScope::current().is_none());owner.close().await;scope.close().await.unwrap();
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn matching_dns_question_with_wrong_answer_owner_or_class_fails_before_client_hello(){
    for wrong_class in [false,true]{
        let f=Fixture::new().await;let dial=f.dial(Some(f.ech()));let mut peer=f.connected().await;let(q,address)=f.query().await;
        let mut response=Message::new(q.metadata.id,MessageType::Response,OpCode::Query);response.add_queries(q.queries.iter().cloned());
        let owner=if wrong_class{q.queries[0].name.clone()}else{Name::from_utf8("unrelated.example").unwrap()};
        let mut record=Record::from_rdata(owner,60,RData::HTTPS(HTTPS(SVCB::new(1,Name::root(),vec![(SvcParamKey::EchConfigList,SvcParamValue::EchConfigList(EchConfigList(config_list())))]))));
        if wrong_class{record.dns_class=hickory_proto::rr::DNSClass::CH;}
        response.add_answer(record);f.proxy.send_to(&response.to_bytes().unwrap(),address).await.unwrap();
        assert!(tokio::time::timeout(Duration::from_secs(5),dial).await.unwrap().unwrap().is_err());eof(&mut peer).await;
        assert!(f.owner.starts.load(Ordering::SeqCst)>0);f.close().await;
    }
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn incomplete_alias_uses_same_captured_dns_then_emits_ech_client_hello(){
    let f=Fixture::new().await;let dial=f.dial(Some(f.ech()));let mut peer=f.connected().await;let(q,address)=f.query().await;
    let mut response=Message::new(q.metadata.id,MessageType::Response,OpCode::Query);response.add_queries(q.queries.iter().cloned());
    response.add_answer(Record::from_rdata(q.queries[0].name.clone(),60,RData::CNAME(hickory_proto::rr::rdata::CNAME(Name::from_utf8("alias.example.").unwrap()))));
    f.proxy.send_to(&response.to_bytes().unwrap(),address).await.unwrap();
    let mut bytes=[0;4096];let(size,address)=tokio::time::timeout(Duration::from_secs(5),f.proxy.recv_from(&mut bytes)).await.unwrap().unwrap();
    let q=Message::from_bytes(&bytes[..size]).unwrap();assert_eq!(q.queries.len(),1);assert_eq!(q.queries[0].query_type,RecordType::HTTPS);assert_eq!(q.queries[0].name,Name::from_utf8("alias.example.").unwrap());
    f.answer(q,address,config_list()).await;let extensions=hello(&mut peer).await;assert!(extensions.iter().any(|(kind,_)|*kind==0xfe0d));
    drop(peer);assert!(tokio::time::timeout(Duration::from_secs(5),dial).await.unwrap().unwrap().is_err());assert!(f.main.try_recv_from(&mut bytes).is_err());f.close().await;
}
