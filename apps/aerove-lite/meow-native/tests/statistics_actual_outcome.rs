//! Integration vectors for the composed native host graph; actual local TCP/HTTP peers.
use meow_common::{Metadata, Network, ProxyAdapter, dial_outcome::{DialReceipt, TrafficClass}};
use meow_tunnel::{statistics::Statistics, tcp::ConnectionGuard};
use std::{net::SocketAddr,sync::{Arc,atomic::{AtomicUsize,Ordering}}};
use tokio::{io::{AsyncReadExt,AsyncWriteExt},net::{TcpListener,TcpStream},task::{JoinHandle,JoinSet}};
use tokio_util::sync::CancellationToken;

struct Peers { echo:SocketAddr, http:SocketAddr, requests:Arc<AtomicUsize>, cancel:CancellationToken, tasks:Vec<JoinHandle<()>> }
impl Peers {
    async fn start()->Self {
        let echo=TcpListener::bind("127.0.0.1:0").await.unwrap();let echo_addr=echo.local_addr().unwrap();
        let http=TcpListener::bind("127.0.0.1:0").await.unwrap();let http_addr=http.local_addr().unwrap();
        let cancel=CancellationToken::new();let requests=Arc::new(AtomicUsize::new(0));let mut tasks=Vec::new();
        let stop=cancel.clone();tasks.push(tokio::spawn(async move {let mut children=JoinSet::new();loop {tokio::select!{biased;
            _=stop.cancelled()=>break,
            item=children.join_next(),if !children.is_empty()=>{item.unwrap().unwrap();},
            accepted=echo.accept()=>{let(mut conn,_)=accepted.unwrap();children.spawn(async move{let mut input=[0;3];conn.read_exact(&mut input).await.unwrap();assert_eq!(&input,b"req");conn.write_all(b"resp").await.unwrap();conn.shutdown().await.unwrap();});}
        }} children.abort_all();while children.join_next().await.is_some(){} }));
        let stop=cancel.clone();let count=requests.clone();tasks.push(tokio::spawn(async move {let mut children=JoinSet::new();loop {tokio::select!{biased;
            _=stop.cancelled()=>break,
            item=children.join_next(),if !children.is_empty()=>{item.unwrap().unwrap();},
            accepted=http.accept()=>{let(mut conn,_)=accepted.unwrap();let count=count.clone();children.spawn(async move {
                let mut header=Vec::new();while !header.ends_with(b"\r\n\r\n") {let b=conn.read_u8().await.unwrap();header.push(b);assert!(header.len()<8192);}
                let header=String::from_utf8(header).unwrap();let address=header.lines().next().unwrap().split_whitespace().nth(1).unwrap();
                let mut remote=TcpStream::connect(address).await.unwrap();count.fetch_add(1,Ordering::SeqCst);conn.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await.unwrap();
                let _=tokio::io::copy_bidirectional(&mut conn,&mut remote).await;
            });}
        }} children.abort_all();while children.join_next().await.is_some(){} }));
        Self{echo:echo_addr,http:http_addr,requests,cancel,tasks}
    }
    async fn close(self){self.cancel.cancel();for task in self.tasks{task.await.unwrap();}}
    fn yaml(&self,group:&str)->String {format!("strict: true\nproxies:\n  - {{name: named-local, type: direct}}\n  - {{name: through-http, type: http, server: 127.0.0.1, port: {}}}\nproxy-groups:\n  - {{name: PICK, type: {}, proxies: [named-local, through-http], strategy: round-robin}}\n",self.http.port(),group)}
    fn metadata(&self)->Metadata {Metadata{network:Network::Tcp,src_ip:Some("127.0.0.1".parse().unwrap()),src_port:12345,dst_ip:Some(self.echo.ip()),dst_port:self.echo.port(),..Default::default()}}
}
async fn exchange(adapter:&dyn ProxyAdapter, metadata:&Metadata, stats:Arc<Statistics>)->TrafficClass {
    let (meta,receipt)=DialReceipt::branch(metadata);
    let mut remote=adapter.dial_tcp_observed(&meta).await.unwrap();
    let outcome=receipt.outcome().unwrap();let class=outcome.traffic_class();
    let guard=ConnectionGuard::track(&stats,metadata.pure(),"MATCH".into(),"".into(),Default::default());
    stats.pending_dial(guard.counters());assert!(stats.complete_dial(guard.counters(),guard.id(),&outcome));
    let mut impostor=outcome.clone();impostor.leaf_type=meow_common::AdapterType::Reject;assert!(!stats.complete_dial(guard.counters(),guard.id(),&impostor));
    remote.write_all(b"req").await.unwrap();stats.record_upload(guard.counters(),3);
    let mut reply=[0u8;4];remote.read_exact(&mut reply).await.unwrap();assert_eq!(&reply,b"resp");stats.record_download(guard.counters(),4);
    remote.shutdown().await.unwrap();class
}
#[tokio::test]
async fn concurrent_round_robin_receipts_match_actual_http_handshakes_and_bytes() {
    let peers=Peers::start().await;let config=meow_config::load_config_from_str(&peers.yaml("load-balance")).await.unwrap();
    let group=config.proxies.get("PICK").unwrap().clone();let stats=Arc::new(Statistics::new());let mut jobs=JoinSet::new();
    for _ in 0..32 {let group=group.clone();let stats=stats.clone();let meta=peers.metadata();jobs.spawn(async move{exchange(group.as_ref(),&meta,stats).await});}
    let mut direct=0;let mut proxy=0;while let Some(done)=jobs.join_next().await{match done.unwrap(){TrafficClass::Direct=>direct+=1,TrafficClass::Proxy=>proxy+=1,_=>panic!("unexpected class")}}
    assert_eq!((direct,proxy),(16,16));assert_eq!(peers.requests.load(Ordering::SeqCst),proxy);
    assert_eq!(stats.totals_for(false),(96,128));assert_eq!(stats.totals_for(true),(48,64));peers.close().await;
}
#[tokio::test]
async fn selector_and_fallback_switch_do_not_reclassify_established_connection() {
    for kind in ["select","fallback"] {
        let peers=Peers::start().await;let config=meow_config::load_config_from_str(&peers.yaml(kind)).await.unwrap();let group=config.proxies.get("PICK").unwrap();
        let (meta,old)=DialReceipt::branch(&peers.metadata());let mut first=group.dial_tcp_observed(&meta).await.unwrap();
        assert_eq!(old.outcome().unwrap().traffic_class(),TrafficClass::Direct);
        if kind=="select" {group.selection().unwrap().set("through-http").await.unwrap();}
        else {config.proxies.get("named-local").unwrap().health().set_alive(false);}
        let stats=Arc::new(Statistics::new());assert_eq!(exchange(group.as_ref(),&peers.metadata(),stats.clone()).await,TrafficClass::Proxy);
        assert_eq!(old.outcome().unwrap().traffic_class(),TrafficClass::Direct);
        first.write_all(b"req").await.unwrap();let mut reply=[0;4];first.read_exact(&mut reply).await.unwrap();assert_eq!(&reply,b"resp");first.shutdown().await.unwrap();
        assert_eq!(peers.requests.load(Ordering::SeqCst),1);assert_eq!(stats.totals_for(true),(3,4));peers.close().await;
    }
}
#[tokio::test]
async fn relay_commits_last_actual_nontransparent_hop_after_all_connects() {
    let peers=Peers::start().await;
    let yaml=format!("strict: true\nproxies:\n  - {{name: local, type: direct}}\n  - {{name: H1, type: http, server: 127.0.0.1, port: {0}}}\n  - {{name: H2, type: http, server: 127.0.0.1, port: {0}}}\nproxy-groups:\n  - {{name: CHAIN, type: relay, proxies: [local,H1,H2,local]}}\n",peers.http.port());
    let config=meow_config::load_config_from_str(&yaml).await.unwrap();let group=config.proxies.get("CHAIN").unwrap();
    let stats=Arc::new(Statistics::new());assert_eq!(exchange(group.as_ref(),&peers.metadata(),stats.clone()).await,TrafficClass::Proxy);
    assert_eq!(peers.requests.load(Ordering::SeqCst),2);assert_eq!(stats.totals_for(true),(3,4));peers.close().await;
}
#[tokio::test]
async fn failed_or_cancelled_dial_never_commits_success_receipt() {
    let reject=meow_config::load_config_from_str("strict: true\n").await.unwrap();let meta=Metadata::default();let (meta,receipt)=DialReceipt::branch(&meta);
    // EOF/discard completion is not a successful forwarding outcome.
    let rejected = reject.proxies.get("REJECT").unwrap();
    let mut raw = rejected.dial_tcp(&meta).await.unwrap();
    assert_eq!(raw.write(b"discard").await.unwrap(),7);
    let mut eof = [0u8;1]; assert_eq!(raw.read(&mut eof).await.unwrap(),0);
    assert!(receipt.outcome().is_none());
    assert!(reject.proxies.get("REJECT").unwrap().dial_tcp_observed(&meta).await.is_err());assert!(receipt.outcome().is_none());
    let (udp_meta,udp_receipt)=DialReceipt::branch(&Metadata{network:Network::Udp,..Default::default()});
    assert!(rejected.dial_udp_observed(&udp_meta).await.is_err());assert!(udp_receipt.outcome().is_none());
    assert!(rejected.dial_tcp_observed(&Metadata::default()).await.is_err());
    let selecting_reject=meow_config::load_config_from_str("strict: true\nproxy-groups:\n - {name: PICK, type: select, proxies: [REJECT]}\n").await.unwrap();
    let (group_meta,group_receipt)=DialReceipt::branch(&Metadata::default());
    assert!(selecting_reject.proxies["PICK"].dial_tcp_observed(&group_meta).await.is_err());
    assert!(group_receipt.outcome().is_none());
    let (drop_meta,drop_receipt)=DialReceipt::branch(&Metadata::default());
    assert!(tokio::time::timeout(std::time::Duration::from_millis(25),
        reject.proxies["REJECT-DROP"].dial_tcp_observed(&drop_meta)).await.is_err());
    assert!(drop_receipt.outcome().is_none());
    let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let addr=listener.local_addr().unwrap();let(notify,started)=tokio::sync::oneshot::channel();let cancel=CancellationToken::new();let stop=cancel.clone();
    let peer=tokio::spawn(async move{let(mut conn,_)=listener.accept().await.unwrap();let mut byte=[0];conn.read_exact(&mut byte).await.unwrap();notify.send(()).unwrap();stop.cancelled().await;});
    let yaml=format!("strict: true\nproxies:\n  - {{name: blocked, type: http, server: 127.0.0.1, port: {}}}\n",addr.port());let config=meow_config::load_config_from_str(&yaml).await.unwrap();let proxy=config.proxies.get("blocked").unwrap();
    let(meta,receipt)=DialReceipt::branch(&Metadata{dst_ip:Some("127.0.0.1".parse().unwrap()),dst_port:443,..Default::default()});
    {let dial=proxy.dial_tcp_observed(&meta);tokio::pin!(dial);tokio::select!{_ = started=>{},result=&mut dial=>panic!("unexpected dial completion: {}",result.is_ok())}}
    assert!(receipt.outcome().is_none());cancel.cancel();peer.await.unwrap();
}

#[tokio::test]
async fn udp_association_actual_socks_and_direct_round_robin_classification() {
    use tokio::net::UdpSocket;
    let stop=CancellationToken::new();let cancel=stop.clone();
    let echo=UdpSocket::bind("127.0.0.1:0").await.unwrap();let target=echo.local_addr().unwrap();
    let echo_task=tokio::spawn(async move{let mut buf=[0;2048];loop{tokio::select!{biased;_ = cancel.cancelled()=>break,result=echo.recv_from(&mut buf)=>{let(n,from)=result.unwrap();echo.send_to(&buf[..n],from).await.unwrap();}}}});
    let control=TcpListener::bind("127.0.0.1:0").await.unwrap();let port=control.local_addr().unwrap().port();let count=Arc::new(AtomicUsize::new(0));let accepted=count.clone();let cancel=stop.clone();
    let socks_task=tokio::spawn(async move{let mut jobs=JoinSet::new();loop{tokio::select!{biased;
        _=cancel.cancelled()=>break,
        done=jobs.join_next(),if !jobs.is_empty()=>{done.unwrap().unwrap();},
        result=control.accept()=>{let(mut conn,_)=result.unwrap();let cancel=cancel.clone();let accepted=accepted.clone();jobs.spawn(async move{
            let mut greeting=[0;3];conn.read_exact(&mut greeting).await.unwrap();assert_eq!(greeting,[5,1,0]);conn.write_all(&[5,0]).await.unwrap();
            let mut assoc=[0;10];conn.read_exact(&mut assoc).await.unwrap();assert_eq!(assoc[1],3);
            let relay=UdpSocket::bind("127.0.0.1:0").await.unwrap();let port=relay.local_addr().unwrap().port();conn.write_all(&[5,0,0,1,127,0,0,1,(port>>8)as u8,port as u8]).await.unwrap();accepted.fetch_add(1,Ordering::SeqCst);
            let mut buf=[0;2048];let mut closed=[0;1];loop{tokio::select!{biased;
                _=cancel.cancelled()=>break,
                done=conn.read(&mut closed)=>{if done.unwrap_or(0)==0{break;}},
                result=relay.recv_from(&mut buf)=>{let(n,from)=result.unwrap();assert_eq!(&buf[..3],&[0,0,0]);relay.send_to(&buf[..n],from).await.unwrap();}
            }}
        });}
    }}jobs.abort_all();while jobs.join_next().await.is_some(){} });
    let yaml=format!("strict: true\nproxies:\n - {{name: local, type: direct}}\n - {{name: socks, type: socks5, server: 127.0.0.1, port: {}, udp: true}}\nproxy-groups:\n - {{name: PICK, type: load-balance, strategy: round-robin, proxies: [local,socks]}}\n",port);
    let config=meow_config::load_config_from_str(&yaml).await.unwrap();let tunnel=meow_tunnel::Tunnel::new_with_slot(config.dns.resolver_slot.clone());let group=config.proxies.get("PICK").unwrap().clone();
    let mut jobs=JoinSet::new();for _ in 0..16{let inner=tunnel.inner().clone();let group=group.clone();jobs.spawn(async move{
        let meta=Metadata{network:Network::Udp,src_ip:Some("127.0.0.1".parse().unwrap()),src_port:12000,dst_ip:Some(target.ip()),dst_port:target.port(),..Default::default()};
        let conn=inner.dial_udp(group.as_ref(),&meta).await.unwrap();assert_eq!(conn.write_packet(b"udp",&target).await.unwrap(),3);let mut buf=[0;32];let(n,_)=conn.read_packet(&mut buf).await.unwrap();assert_eq!(&buf[..n],b"udp");conn.close().unwrap();
    });}while let Some(done)=jobs.join_next().await{done.unwrap();}
    assert_eq!(count.load(Ordering::SeqCst),8);assert_eq!(tunnel.statistics().totals_for(false),(48,48));assert_eq!(tunnel.statistics().totals_for(true),(24,24));
    stop.cancel();echo_task.await.unwrap();socks_task.await.unwrap();
}

#[tokio::test]
async fn selector_and_fallback_changed_while_http_dial_pending_keep_actual_outcome() {
    for kind in ["select","fallback"] {
        let peers=Peers::start().await;
        let gate=TcpListener::bind("127.0.0.1:0").await.unwrap();let port=gate.local_addr().unwrap().port();
        let(started_tx,started_rx)=tokio::sync::oneshot::channel();let(release_tx,release_rx)=tokio::sync::oneshot::channel();
        let gated_peer=tokio::spawn(async move{let(mut incoming,_)=gate.accept().await.unwrap();let mut header=Vec::new();while !header.ends_with(b"\r\n\r\n"){header.push(incoming.read_u8().await.unwrap());}
            let text=String::from_utf8(header).unwrap();let target=text.lines().next().unwrap().split_whitespace().nth(1).unwrap();let mut outgoing=TcpStream::connect(target).await.unwrap();
            started_tx.send(()).unwrap();release_rx.await.unwrap();incoming.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await.unwrap();let _=tokio::io::copy_bidirectional(&mut incoming,&mut outgoing).await;
        });
        let yaml=peers.yaml(kind).replace(&format!("port: {}",peers.http.port()),&format!("port: {}",port));
        let config=meow_config::load_config_from_str(&yaml).await.unwrap();let group=config.proxies.get("PICK").unwrap();
        if kind=="select"{group.selection().unwrap().set("through-http").await.unwrap();}else{config.proxies.get("named-local").unwrap().health().set_alive(false);}
        let(meta,receipt)=DialReceipt::branch(&peers.metadata());let dial=group.dial_tcp_observed(&meta);tokio::pin!(dial);
        tokio::select!{_ = started_rx=>{},done=&mut dial=>panic!("dial completed before held HTTP response: {}",done.is_ok())}
        if kind=="select"{group.selection().unwrap().set("named-local").await.unwrap();}else{config.proxies.get("named-local").unwrap().health().set_alive(true);}
        let stats=Arc::new(Statistics::new());assert_eq!(exchange(group.as_ref(),&peers.metadata(),stats).await,TrafficClass::Direct);
        release_tx.send(()).unwrap();let mut old=dial.await.unwrap();assert_eq!(receipt.outcome().unwrap().traffic_class(),TrafficClass::Proxy);
        old.write_all(b"req").await.unwrap();let mut reply=[0;4];old.read_exact(&mut reply).await.unwrap();assert_eq!(&reply,b"resp");old.shutdown().await.unwrap();drop(old);
        gated_peer.await.unwrap();peers.close().await;
    }
}


#[tokio::test]
async fn dpa_first_hop_with_transparent_tail_has_owned_receipts_and_real_front_chain() {
    for tail in ["tail-local","COMPATIBLE"] {
        let peers=Peers::start().await;
        // Direct rejects an injected dialer; non-strict conversion uses the
        // official DialerProxyAdapter fallback, so this exercises a real DPA.
        let yaml=format!("proxies:\n - {{name: front-http, type: http, server: 127.0.0.1, port: {}}}\n - {{name: wrapped-local, type: direct, dialer-proxy: front-http}}\n - {{name: tail-local, type: direct}}\nproxy-groups:\n - {{name: OUTER, type: relay, proxies: [wrapped-local,{}]}}\n",peers.http.port(),tail);
        let config=meow_config::load_config_from_str(&yaml).await.unwrap();
        let stats=Arc::new(Statistics::new());
        // A leaf-typed DPA must preserve its semantic proxy-front receipt.
        assert_eq!(exchange(config.proxies.get("wrapped-local").unwrap().as_ref(),&peers.metadata(),stats.clone()).await,TrafficClass::Proxy);
        let outer=config.proxies.get("OUTER").unwrap();
        let(meta,receipt)=DialReceipt::branch(&peers.metadata());let mut remote=outer.dial_tcp_observed(&meta).await.unwrap();
        let outcome=receipt.outcome().unwrap();assert_eq!(outcome.traffic_class(),TrafficClass::Proxy);
        assert!(outcome.chains.iter().any(|name|name.as_ref()=="front-http"));
        assert_eq!(outcome.chains.last().unwrap().as_ref(),"OUTER");
        remote.write_all(b"req").await.unwrap();let mut reply=[0;4];remote.read_exact(&mut reply).await.unwrap();assert_eq!(&reply,b"resp");remote.shutdown().await.unwrap();drop(remote);
        assert_eq!(peers.requests.load(Ordering::SeqCst),2);assert_eq!(stats.totals_for(true),(3,4));peers.close().await;
    }
}

#[tokio::test]
async fn committed_view_switch_changes_existing_sampler_read_without_reset_or_connection_filter() {
    let peers=Peers::start().await;
    let config=meow_config::load_config_from_str(&peers.yaml("select")).await.unwrap();
    let stats=Arc::new(Statistics::new());
    assert!(!stats.only_proxy_view());
    assert_eq!(exchange(config.proxies["named-local"].as_ref(),&peers.metadata(),stats.clone()).await,TrafficClass::Direct);
    assert_eq!(exchange(config.proxies["through-http"].as_ref(),&peers.metadata(),stats.clone()).await,TrafficClass::Proxy);
    stats.sample_traffic();
    assert_eq!(stats.traffic_snapshot(),(6,8,6,8));
    assert_eq!(stats.traffic_snapshot_for(true),(3,4,3,4));
    let held=stats.track_connection(Metadata::default(),"MATCH".into(),"".into(),Default::default());
    let count=stats.active_connection_count();
    stats.set_only_proxy_view(true);
    assert!(stats.only_proxy_view());assert_eq!(stats.traffic_snapshot(),(3,4,3,4));
    assert_eq!(stats.active_connection_count(),count);assert!(stats.connection_counters(held).is_some());
    assert_eq!(stats.totals_for(false),(6,8));assert_eq!(stats.totals_for(true),(3,4));
    stats.set_only_proxy_view(false);
    assert_eq!(stats.traffic_snapshot(),(6,8,6,8));
    assert_eq!(exchange(config.proxies["named-local"].as_ref(),&peers.metadata(),stats.clone()).await,TrafficClass::Direct);
    stats.sample_traffic();
    assert_eq!(stats.traffic_snapshot(),(3,4,9,12));
    stats.set_only_proxy_view(true);assert_eq!(stats.traffic_snapshot(),(0,0,3,4));
    assert_eq!(stats.active_connection_count(),count);assert!(stats.connection_counters(held).is_some());
    peers.close().await;
}


#[tokio::test]
async fn plain_http_actual_dial_accounts_direct_and_proxy_before_view_switch() {
    let peers=Peers::start().await;
    let yaml=peers.yaml("select")+"mode: rule\nrules: ['MATCH,PICK']\n";
    let config=meow_config::load_config_from_str(&yaml).await.unwrap();
    let group=config.proxies["PICK"].clone();
    let tunnel=Arc::new(meow_tunnel::Tunnel::new_with_slot(config.dns.resolver_slot.clone()));
    tunnel.set_mode(config.general.mode);
    tunnel.update_routing(config.proxies,config.rules,config.dialer_registry);
    let stats=tunnel.statistics();
    assert_eq!(stats.totals_for(false),(0,0));assert_eq!(stats.totals_for(true),(0,0));
    let mut direct=(0,0);
    for proxy in [false,true] {
        group.selection().unwrap().set(if proxy {"through-http"} else {"named-local"}).await.unwrap();
        let origin=TcpListener::bind("127.0.0.1:0").await.unwrap();let destination=origin.local_addr().unwrap();
        let responder=tokio::spawn(async move {
            let(mut remote,_)=origin.accept().await.unwrap();let mut head=Vec::new();
            while !head.ends_with(b"\r\n\r\n") {head.push(remote.read_u8().await.unwrap());assert!(head.len()<8192);}
            assert!(head.starts_with(b"POST /tracked HTTP/1.1\r\n"));
            let mut body=[0;3];remote.read_exact(&mut body).await.unwrap();assert_eq!(&body,b"abc");
            remote.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nresp").await.unwrap();remote.shutdown().await.unwrap();
        });
        let inbound=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=inbound.local_addr().unwrap();let engine=tunnel.clone();
        let worker=tokio::spawn(async move {
            let(conn,source)=inbound.accept().await.unwrap();
            meow_listener::http_proxy::handle_http(&engine,conn,source,None,None,"statistics-test",address.port()).await;
        });
        let mut client=TcpStream::connect(address).await.unwrap();
        let request=format!("POST http://{destination}/tracked HTTP/1.1\r\nHost: {destination}\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc");
        client.write_all(request.as_bytes()).await.unwrap();let mut response=Vec::new();
        tokio::time::timeout(std::time::Duration::from_secs(5),client.read_to_end(&mut response)).await.unwrap().unwrap();
        assert!(response.starts_with(b"HTTP/1.1 200"));assert!(response.ends_with(b"resp"));
        tokio::time::timeout(std::time::Duration::from_secs(5),worker).await.unwrap().unwrap();responder.await.unwrap();
        assert_eq!(stats.active_connection_count(),0);
        let all=stats.totals_for(false);let only=stats.totals_for(true);
        if !proxy {
            direct=all;assert!(direct.0>=3 && direct.1>=4);assert_eq!(only,(0,0));
            stats.sample_traffic();stats.set_only_proxy_view(true);assert_eq!(stats.traffic_snapshot(),(0,0,0,0));
        } else {
            assert!(only.0>=3 && only.1>=4);assert_eq!((all.0-direct.0,all.1-direct.1),only);
            assert_eq!(peers.requests.load(Ordering::SeqCst),1);
            stats.sample_traffic();let proxy_view=stats.traffic_snapshot();assert_eq!((proxy_view.2,proxy_view.3),only);
            stats.set_only_proxy_view(false);let all_view=stats.traffic_snapshot();assert_eq!((all_view.2,all_view.3),all);
            assert_eq!(stats.totals_for(true),only);
        }
    }
    peers.close().await;
}
