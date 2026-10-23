#![cfg(any(target_os="linux",target_os="android",target_os="macos"))]
use aerove_meow_core::{network_context::{NetworkContext,NetworkContextSlot,ResolverPair},physical_dns::PhysicalResolver};
use hickory_proto::{op::{Message,MessageType,OpCode},rr::{Name,Record,RecordType,RData,rdata::{HTTPS,SVCB,svcb::{EchConfigList,SvcParamKey,SvcParamValue}}},serialize::binary::{BinDecodable,BinEncodable}};
use meow_common::{DnsMode,socket_protect::SocketProtector};
use meow_config::{EchScope,owned_load::LoadContext};
use meow_dns::Resolver;
use meow_transport::{Transport,tls::{TlsConfig,TlsLayer,EchOpts}};
use std::{io,os::fd::RawFd,sync::{Arc,atomic::{AtomicUsize,Ordering}},time::Duration};
use tokio::{io::AsyncReadExt,net::UdpSocket,time::timeout};
use tokio_util::sync::CancellationToken;
struct Protect(Arc<AtomicUsize>);
impl SocketProtector for Protect {fn protect(&self,fd:RawFd)->io::Result<()>{assert!(unsafe{libc::fcntl(fd,libc::F_GETFD)}>=0);self.0.fetch_add(1,Ordering::SeqCst);Ok(())}}
struct Fixture{physical:Arc<PhysicalResolver>,physical_peer:UdpSocket,main:UdpSocket,proxy:UdpSocket,network:NetworkContext,pair:ResolverPair,calls:Arc<AtomicUsize>}
impl Fixture{
    async fn new(with_proxy:bool)->Self{
        let physical_peer=UdpSocket::bind("127.0.0.1:0").await.unwrap();let main=UdpSocket::bind("127.0.0.1:0").await.unwrap();let proxy=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let calls=Arc::new(AtomicUsize::new(0));let base=NetworkContext::protected(Arc::new(Protect(calls.clone())),None,CancellationToken::new());
        let physical=PhysicalResolver::from_servers(vec![physical_peer.local_addr().unwrap()],base,false).unwrap();let network=physical.network();let transport=network.dns_transport_scope().unwrap();
        let pair=transport.scope(async{ResolverPair{
            main:Arc::new(Resolver::new(vec![main.local_addr().unwrap()],Vec::new(),DnsMode::Normal,Default::default(),false,false)),
            proxy:with_proxy.then(||Arc::new(Resolver::new(vec![proxy.local_addr().unwrap()],Vec::new(),DnsMode::Normal,Default::default(),false,false))),
        }}).await;let network=network.with_resolver_pair(pair.clone());
        Self{physical,physical_peer,main,proxy,network,pair,calls}
    }
    async fn query(socket:&UdpSocket)->(Message,std::net::SocketAddr){let mut buffer=[0;4096];let(count,peer)=timeout(Duration::from_secs(5),socket.recv_from(&mut buffer)).await.unwrap().unwrap();let query=Message::from_bytes(&buffer[..count]).unwrap();assert_eq!(query.queries.len(),1);assert_eq!(query.queries[0].query_type,RecordType::HTTPS);assert_eq!(query.queries[0].name,Name::from_utf8("ech.example.").unwrap());(query,peer)}
    async fn answer(socket:&UdpSocket,query:Message,peer:std::net::SocketAddr,bytes:Vec<u8>){let mut result=Message::new(query.metadata.id,MessageType::Response,OpCode::Query);result.add_queries(query.queries.iter().cloned());result.add_answer(Record::from_rdata(query.queries[0].name.clone(),1,RData::HTTPS(HTTPS(SVCB::new(1,Name::root(),vec![(SvcParamKey::EchConfigList,SvcParamValue::EchConfigList(EchConfigList(bytes)))])))));socket.send_to(&result.to_bytes().unwrap(),peer).await.unwrap();}
    fn no_packet(socket:&UdpSocket){assert!(socket.try_recv_from(&mut[0;4096]).is_err());}
}
fn config_list()->Vec<u8>{
    let mut contents=vec![1,0,32,0,32];let mut public=[0u8;32];public[0]=9;contents.extend(public);contents.extend([0,4,0,1,0,1,0]);let cover=b"cover.example";contents.push(cover.len()as u8);contents.extend(cover);contents.extend([0,0]);
    let mut config=vec![0xfe,0x0d];config.extend((contents.len()as u16).to_be_bytes());config.extend(contents);let mut list=(config.len()as u16).to_be_bytes().to_vec();list.extend(config);list
}
async fn hello(stream:&mut tokio::io::DuplexStream)->Vec<(u16,Vec<u8>)>{
    let mut header=[0;5];timeout(Duration::from_secs(5),stream.read_exact(&mut header)).await.unwrap().unwrap();assert_eq!(header[0],22);let mut body=vec![0;u16::from_be_bytes([header[3],header[4]])as usize];stream.read_exact(&mut body).await.unwrap();assert_eq!(body[0],1);
    let mut index=4+2+32;index+=1+body[index]as usize;let cipher=u16::from_be_bytes([body[index],body[index+1]])as usize;index+=2+cipher;index+=1+body[index]as usize;let size=u16::from_be_bytes([body[index],body[index+1]])as usize;index+=2;let end=index+size;assert!(end<=body.len());let mut extensions=Vec::new();while index<end{let kind=u16::from_be_bytes([body[index],body[index+1]]);let size=u16::from_be_bytes([body[index+2],body[index+3]])as usize;index+=4;assert!(index+size<=end);extensions.push((kind,body[index..index+size].to_vec()));index+=size;}extensions
}
#[tokio::test]
async fn native_operation_actual_boring_hello_uses_captured_proxy_not_main_or_physical_dns(){
    let f=Fixture::new(true).await;let weak_main=Arc::downgrade(&f.pair.main);let weak_proxy=Arc::downgrade(f.pair.proxy.as_ref().unwrap());
    let mut config=TlsConfig::new("inner.example");config.skip_cert_verify=true;config.ech=Some(EchOpts::Dns("ech.example".into()));let layer=TlsLayer::new(&config).unwrap();let(client,mut server)=tokio::io::duplex(16384);
    let network=f.network.clone();let handshake=tokio::spawn(async move{network.scope(layer.connect(Box::new(client))).await});
    let(query,peer)=Fixture::query(&f.proxy).await;Fixture::answer(&f.proxy,query,peer,config_list()).await;
    let extensions=hello(&mut server).await;assert!(extensions.iter().any(|(kind,_)|*kind==0xfe0d));let sni=&extensions.iter().find(|(kind,_)|*kind==0).unwrap().1;
    assert!(sni.windows(b"cover.example".len()).any(|bytes|bytes==b"cover.example"));assert!(!sni.windows(b"inner.example".len()).any(|bytes|bytes==b"inner.example"));
    Fixture::no_packet(&f.main);Fixture::no_packet(&f.physical_peer);assert!(f.calls.load(Ordering::SeqCst)>0);drop(server);assert!(timeout(Duration::from_secs(5),handshake).await.unwrap().unwrap().is_err());
    f.physical.retire().await.unwrap();assert!(f.physical.dns_owner().cleanup_confirmed());drop(f);assert!(weak_main.upgrade().is_none());assert!(weak_proxy.upgrade().is_none());assert!(EchScope::current().is_none());
}
#[tokio::test]
async fn first_poll_captures_new_atomic_pair_and_old_query_retires_without_using_successor(){
    let old=Fixture::new(true).await;let next=Fixture::new(true).await;
    let seed=old.physical.network();let slot=NetworkContextSlot::new(&old.network);let live=slot.view(&seed);
    let query_context=live.clone();let inflight=tokio::spawn(async move{query_context.scope(EchScope::resolve("ech.example".into())).await});let _=Fixture::query(&old.proxy).await;
    let pending=live.scope(EchScope::resolve("ech.example".into()));
    let retired=slot.replace(&next.network);old.physical.retire().await.unwrap();assert!(old.physical.dns_owner().cleanup_confirmed());assert!(timeout(Duration::from_secs(5),inflight).await.unwrap().unwrap().is_err());drop(retired);
    let bytes=config_list();let expected=bytes.clone();let(result,())=tokio::join!(pending,async{let(query,peer)=Fixture::query(&next.proxy).await;Fixture::answer(&next.proxy,query,peer,bytes).await;});assert_eq!(result.unwrap(),expected);
    let pair=slot.current_resolver_pair().unwrap();assert!(Arc::ptr_eq(&pair.main,&next.pair.main));assert!(Arc::ptr_eq(pair.proxy.as_ref().unwrap(),next.pair.proxy.as_ref().unwrap()));
    Fixture::no_packet(&old.main);Fixture::no_packet(&old.physical_peer);Fixture::no_packet(&next.main);Fixture::no_packet(&next.physical_peer);assert!(!live.cancelled());
    next.physical.retire().await.unwrap();assert!(next.physical.dns_owner().cleanup_confirmed());let retired=slot.replace(&NetworkContext::platform(CancellationToken::new()));drop((retired,pair));
    let weak_main=Arc::downgrade(&old.pair.main);let weak_proxy=Arc::downgrade(old.pair.proxy.as_ref().unwrap());drop(old);assert!(weak_main.upgrade().is_none());assert!(weak_proxy.upgrade().is_none());
}
#[tokio::test]
async fn no_proxy_resolver_uses_main_and_caller_cancel_joins_real_query_owner(){
    let f=Fixture::new(false).await;let context=f.network.clone();let work=tokio::spawn(async move{context.scope(EchScope::resolve("ech.example".into())).await});let _=Fixture::query(&f.main).await;
    Fixture::no_packet(&f.proxy);Fixture::no_packet(&f.physical_peer);work.abort();assert!(work.await.unwrap_err().is_cancelled());
    f.physical.retire().await.unwrap();assert!(f.physical.dns_owner().cleanup_confirmed());assert!(EchScope::current().is_none());
    let successor=Fixture::new(false).await;let context=successor.network.clone();let query=context.scope(EchScope::resolve("ech.example".into()));let bytes=config_list();let expected=bytes.clone();let(result,())=tokio::join!(query,async{let(q,peer)=Fixture::query(&successor.main).await;Fixture::answer(&successor.main,q,peer,bytes).await;});assert_eq!(result.unwrap(),expected);successor.physical.retire().await.unwrap();
}
