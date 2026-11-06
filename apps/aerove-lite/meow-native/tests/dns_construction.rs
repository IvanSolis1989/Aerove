#![cfg(any(target_os="linux",target_os="android",target_os="macos"))]
use aerove_meow_core::transport_tasks::TransportTasks;
use meow_config::owned_load::{LoadContext,OwnedLoadScope,OwnedWork,with_dns_transport_scope};
use meow_common::transport_tasks::{TransportTaskContext,TransportScope,Work};
use hickory_proto::{op::{Message,MessageType,OpCode,Query,ResponseCode},rr::{Record,RecordType,RData,rdata::{A,AAAA}}};
use hickory_proto::serialize::binary::{BinDecodable, BinEncodable};
use std::{io,net::{Ipv4Addr,SocketAddr},sync::{Arc,Mutex,Weak,atomic::{AtomicUsize,Ordering}},time::Duration};
use tokio::{net::UdpSocket,task::AbortHandle};
use tokio_util::sync::CancellationToken;

#[test]
fn dns_ipv6_wire_keeps_omission_and_explicit_boolean_values() {
    let omitted: meow_config::raw::RawDns =
        serde_json::from_value(serde_json::json!({"enable": true})).unwrap();
    assert!(serde_json::to_value(omitted).unwrap().get("ipv6").is_none());
    for enabled in [false, true] {
        let explicit: meow_config::raw::RawDns =
            serde_json::from_value(serde_json::json!({"ipv6": enabled})).unwrap();
        assert_eq!(
            serde_json::to_value(explicit).unwrap()["ipv6"].as_bool(),
            Some(enabled),
        );
    }
}

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
struct Server { address:SocketAddr, aaaa:Arc<AtomicUsize>, stop:CancellationToken, serving:tokio::task::JoinHandle<()> }
impl Server {
    async fn new()->Self {
        let socket=UdpSocket::bind("127.0.0.1:0").await.unwrap();let address=socket.local_addr().unwrap();let stop=CancellationToken::new();let cancel=stop.clone();
        let aaaa=Arc::new(AtomicUsize::new(0));let queries=aaaa.clone();
        let serving=tokio::spawn(async move {let mut buffer=[0;4096];loop{tokio::select!{biased;
            _=cancel.cancelled()=>return,
            packet=socket.recv_from(&mut buffer)=>{let(size,peer)=packet.unwrap();let query=Message::from_bytes(&buffer[..size]).unwrap();
                let mut reply=Message::new(query.metadata.id,MessageType::Response,OpCode::Query);reply.add_queries(query.queries.iter().cloned());
                let data=if query.queries[0].query_type()==RecordType::AAAA {queries.fetch_add(1,Ordering::SeqCst);RData::AAAA(AAAA("2001:db8::42".parse().unwrap()))}
                    else {RData::A(A(Ipv4Addr::new(192,0,2,42)))};
                reply.add_answer(Record::from_rdata(query.queries[0].name.clone(),60,data));
                socket.send_to(&reply.to_bytes().unwrap(),peer).await.unwrap();}
        }}});Self{address,aaaa,stop,serving}
    }
    async fn close(self){self.stop.cancel();self.serving.await.unwrap();}
}
async fn answer(resolver:&meow_dns::Resolver,name:&str){assert_eq!(tokio::time::timeout(Duration::from_secs(5),resolver.lookup_ipv4(name)).await.unwrap(),Some("192.0.2.42".parse().unwrap()));}

async fn answer6(resolver:&meow_dns::Resolver,name:&str,expected:Option<&str>){
    answer6_with_wire(resolver,name,expected,expected).await;
}
async fn answer6_with_wire(resolver:&meow_dns::Resolver,name:&str,lookup:Option<&str>,wire:Option<&str>){
    assert_eq!(tokio::time::timeout(Duration::from_secs(5),resolver.lookup_ipv6(name)).await.unwrap(),lookup.map(|ip|ip.parse().unwrap()));
    let mut query=Message::new(23,MessageType::Query,OpCode::Query);
    query.add_query(Query::query(name.parse().unwrap(),RecordType::AAAA));
    let bytes=tokio::time::timeout(Duration::from_secs(5),meow_dns::DnsServer::handle_query(&query.to_bytes().unwrap(),resolver)).await.unwrap().unwrap();
    let reply=Message::from_bytes(&bytes).unwrap();assert_eq!(reply.metadata.response_code,ResponseCode::NoError);
    assert_eq!(reply.answers.len(),usize::from(wire.is_some()));
    if let Some(ip)=wire {assert_eq!(reply.answers[0].data,RData::AAAA(AAAA(ip.parse().unwrap())));}
}
fn enabled_yaml(address:SocketAddr,root:Option<bool>,dns:Option<bool>,fake:bool)->String {
    let root=root.map(|value|format!("ipv6: {value}\n")).unwrap_or_default();
    let dns=dns.map(|value|format!(" ipv6: {value}\n")).unwrap_or_default();
    let mode=if fake {" enhanced-mode: fake-ip\n fake-ip-range: 'fd00::1/120'\n"} else {""};
    format!("strict: true\n{root}hosts:\n pinned6.example: '2001:db8::9'\ndns:\n enable: true\n{dns}{mode} use-system-hosts: false\n nameserver: ['udp://{address}']\n proxy-server-nameserver: ['udp://{address}']\nrules: ['MATCH,DIRECT']\n")
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn enabled_main_and_proxy_use_mihomo_root_and_dns_ipv6_semantics_before_hosts_and_cache() {
    let server=Server::new().await;let dns=Owner::new();
    let scope=OwnedLoadScope::with_context(Arc::new(Context{dns:Some(dns.clone()),physical:server.address}));dns.bind(&scope);
    for (root,local,allows) in [
        (Some(false),Some(false),false),(Some(false),Some(true),false),
        (Some(true),Some(false),false),(Some(true),Some(true),true),
        (Some(false),None,false),(Some(true),None,true),(None,Some(true),false),
    ] {
        let config=meow_config::load_config_from_str_owned(&enabled_yaml(server.address,root,local,false),&scope).await.unwrap();
        for resolver in [&config.dns.resolver,config.dns.proxy_resolver.as_ref().unwrap()] {
            resolver.preload_cache("cached6.example",&["2001:db8::7".parse().unwrap()],Duration::from_secs(60));
            let before=server.aaaa.load(Ordering::SeqCst);
            answer6(resolver,"wire6.example",if allows {Some("2001:db8::42")} else {None}).await;
            answer6_with_wire(resolver,"pinned6.example",if allows {Some("2001:db8::9")} else {None},Some("2001:db8::9")).await;
            answer6(resolver,"cached6.example",if allows {Some("2001:db8::7")} else {None}).await;
            assert_eq!(server.aaaa.load(Ordering::SeqCst)-before,usize::from(allows));
            answer(resolver,"wire4.example").await;
        }
    }
    let without_hosts=enabled_yaml(server.address,Some(true),Some(false),false).replace(" use-system-hosts:"," use-hosts: false\n use-system-hosts:");
    let config=meow_config::load_config_from_str_owned(&without_hosts,&scope).await.unwrap();
    for resolver in [&config.dns.resolver,config.dns.proxy_resolver.as_ref().unwrap()] {
        answer6(resolver,"pinned6.example",None).await;
    }
    dns.close().await;scope.close().await.unwrap();server.close().await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn dns_ipv6_blocks_fake_ipv6_without_changing_disabled_physical_dns() {
    let server=Server::new().await;let dns=Owner::new();
    let scope=OwnedLoadScope::with_context(Arc::new(Context{dns:Some(dns.clone()),physical:server.address}));dns.bind(&scope);
    for (root,local,allows) in [(false,true,false),(true,false,false),(true,true,true)] {
        let config=meow_config::load_config_from_str_owned(&enabled_yaml(server.address,Some(root),Some(local),true),&scope).await.unwrap();
        let result=tokio::time::timeout(Duration::from_secs(5),config.dns.resolver.lookup_ipv6("fake6.example")).await.unwrap();
        if allows {let ip=result.unwrap();assert!(ip.is_ipv6());assert_eq!(config.dns.resolver.reverse_lookup(ip).as_deref(),Some("fake6.example"));}
        else {assert_eq!(result,None);}
        assert_eq!(server.aaaa.load(Ordering::SeqCst),0);
    }
    for (root,local,allows) in [(false,true,false),(true,false,true)] {
        let yaml=format!("strict: true\nipv6: {root}\ndns: {{enable: false, ipv6: {local}}}\nrules: ['MATCH,DIRECT']\n");
        let config=meow_config::load_config_from_str_owned(&yaml,&scope).await.unwrap();
        answer6(&config.dns.resolver,"physical6.example",if allows {Some("2001:db8::42")} else {None}).await;
        answer(&config.dns.resolver,"physical4.example").await;
    }
    dns.close().await;scope.close().await.unwrap();server.close().await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn dns_ipv6_reload_rejects_geo_reuse_and_rebuild_restores_real_answers() {
    let server=Server::new().await;let dns=Owner::new();
    let scope=OwnedLoadScope::with_context(Arc::new(Context{dns:Some(dns.clone()),physical:server.address}));dns.bind(&scope);
    let previous=meow_config::load_config_from_str_owned(&enabled_yaml(server.address,Some(true),Some(true),false),&scope).await.unwrap();
    answer6(&previous.dns.resolver,"reload6.example",Some("2001:db8::42")).await;
    let mut changed=previous.raw.clone();changed.dns.as_mut().unwrap().ipv6=Some(false);
    let reuse=scope.run(meow_config::dns_parser::prepare_owned_geo_dns(&changed,&previous.raw,None,None,&previous.rule_providers,
        &previous.dns.resolver,previous.dns.proxy_resolver.clone())).await;
    assert!(reuse.err().unwrap().to_string().contains("owned Geo DNS source changed"));
    let blocked=scope.run(meow_config::dns_parser::parse_dns(&changed,None,None,&previous.proxies,None,&previous.rule_providers,Some(&previous.dns.resolver))).await.unwrap();
    answer6(&blocked.resolver,"reload6.example",None).await;
    answer6(blocked.proxy_resolver.as_ref().unwrap(),"reload6.example",None).await;
    let restored=scope.run(meow_config::dns_parser::parse_dns(&previous.raw,None,None,&previous.proxies,None,&previous.rule_providers,Some(&blocked.resolver))).await.unwrap();
    answer6(&restored.resolver,"reload6.example",Some("2001:db8::42")).await;
    answer6(restored.proxy_resolver.as_ref().unwrap(),"reload6.example",Some("2001:db8::42")).await;
    dns.close().await;scope.close().await.unwrap();server.close().await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn dns_ipv6_pending_query_is_joined_on_cancel_and_new_owner_recovers() {
    let pending=UdpSocket::bind("127.0.0.1:0").await.unwrap();let address=pending.local_addr().unwrap();
    let dns=Owner::new();let scope=OwnedLoadScope::with_context(Arc::new(Context{dns:Some(dns.clone()),physical:address}));dns.bind(&scope);
    let config=meow_config::load_config_from_str_owned(&enabled_yaml(address,Some(true),Some(true),false),&scope).await.unwrap();
    let resolver=config.dns.resolver.clone();let query=tokio::spawn(async move{resolver.lookup_ipv6("cancel6.example").await});
    let mut wire=[0;4096];let(size,_)=tokio::time::timeout(Duration::from_secs(5),pending.recv_from(&mut wire)).await.unwrap().unwrap();
    assert_eq!(Message::from_bytes(&wire[..size]).unwrap().queries[0].query_type(),RecordType::AAAA);
    dns.close().await;assert_eq!(tokio::time::timeout(Duration::from_secs(5),query).await.unwrap().unwrap(),None);
    scope.close().await.unwrap();
    let server=Server::new().await;let next=Owner::new();
    let scope=OwnedLoadScope::with_context(Arc::new(Context{dns:Some(next.clone()),physical:server.address}));next.bind(&scope);
    let recovered=meow_config::load_config_from_str_owned(&enabled_yaml(server.address,Some(true),Some(true),false),&scope).await.unwrap();
    answer6(&recovered.dns.resolver,"cancel6.example",Some("2001:db8::42")).await;
    next.close().await;scope.close().await.unwrap();server.close().await;
}

#[tokio::test(flavor="multi_thread",worker_threads=3)]
async fn dns_ipv6_invalid_types_fail_native_config_loading_before_dns_work() {
    let server=Server::new().await;let dns=Owner::new();
    let scope=OwnedLoadScope::with_context(Arc::new(Context{dns:Some(dns.clone()),physical:server.address}));dns.bind(&scope);
    for value in ["'true'","1","[]","{}"] {
        let yaml=format!("strict: true\ndns: {{enable: true, ipv6: {value}, nameserver: ['udp://{}']}}\n",server.address);
        assert!(meow_config::load_config_from_str_owned(&yaml,&scope).await.is_err(),"dns.ipv6={value}");
    }
    assert_eq!(dns.starts.load(Ordering::SeqCst),0);
    dns.close().await;scope.close().await.unwrap();server.close().await;
}

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
