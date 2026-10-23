use super::*;
use futures::StreamExt;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::{pin::Pin,task::{Context as TaskContext,Poll}};
use tokio::io::{AsyncRead,ReadBuf};
use serde_json::{Value, json};
use tokio::{net::{TcpListener,TcpStream,UdpSocket},sync::oneshot};
use tokio_tungstenite::{connect_async, tungstenite::client::IntoClientRequest};

type Client=tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<TcpStream>>;
async fn frame(ws:&mut Client)->Value {
    let m=tokio::time::timeout(Duration::from_secs(5),ws.next()).await.unwrap().unwrap().unwrap();
    let text=m.into_text().unwrap(); assert!(text.len()<=64*1024);
    serde_json::from_str(text.as_ref()).unwrap()
}
async fn socket(port:u16, observations:bool)->Client {
    let mut request=format!("ws://127.0.0.1:{port}/aerove/telemetry{}",if observations{"?observations=true"}else{""}).into_client_request().unwrap();
    request.headers_mut().insert("authorization","Bearer 0123456789abcdef0123456789abcdef".parse().unwrap());
    connect_async(request).await.unwrap().0
}
async fn api(port:u16, method:&str, path:&str, body:&str)->String {
    let mut socket=TcpStream::connect((std::net::Ipv4Addr::LOCALHOST,port)).await.unwrap();
    socket.write_all(format!("{method} {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer 0123456789abcdef0123456789abcdef\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).as_bytes()).await.unwrap();
    let mut reply=String::new();tokio::time::timeout(Duration::from_secs(5),socket.read_to_string(&mut reply)).await.unwrap().unwrap();reply
}
#[derive(Default,Debug)]
struct FixtureProgress {
    stage: AtomicUsize,
    dns_received: AtomicUsize, dns_replied: AtomicUsize,
    target_accepted: AtomicUsize, target_headers: AtomicUsize, target_read_error: AtomicUsize, target_read_bytes: AtomicUsize,
    target_written: AtomicUsize, target_closed: AtomicUsize,
}
fn diagnostic_stage(progress:&FixtureProgress,label:&str) {
    let stage=progress.stage.fetch_add(1,Ordering::SeqCst)+1;
    eprintln!("[observation-stage] {stage} {label} fixture={progress:?}");
    let _=std::io::Write::flush(&mut std::io::stderr());
}
struct ResponseProgress<'a> { inner: &'a mut TcpStream, bytes: usize, prefix: Vec<u8>, eof: bool }
impl AsyncRead for ResponseProgress<'_> {
    fn poll_read(self: Pin<&mut Self>, cx: &mut TaskContext<'_>, buf: &mut ReadBuf<'_>) -> Poll<std::io::Result<()>> {
        let this=self.get_mut();let before=buf.filled().len();let capacity=buf.remaining();
        let result=Pin::new(&mut *this.inner).poll_read(cx,buf);
        if matches!(&result,Poll::Ready(Ok(()))) {
            let added=&buf.filled()[before..];this.bytes+=added.len();this.eof|=added.is_empty()&&capacity>0;
            let keep=added.len().min(512-this.prefix.len());this.prefix.extend_from_slice(&added[..keep]);
        }
        result
    }
}
async fn io() {
    let progress=Arc::new(FixtureProgress::default());
    diagnostic_stage(&progress,"io-enter");
    let dns_progress=progress.clone();
    let dns=UdpSocket::bind("127.0.0.1:0").await.unwrap();let dns_addr=dns.local_addr().unwrap();
    let dns_task=tokio::spawn(async move {
        let mut buffer=[0u8;4096]; loop {
            let (n,peer)=dns.recv_from(&mut buffer).await.unwrap();
            dns_progress.dns_received.fetch_add(1,Ordering::SeqCst);
            let request=Message::from_vec(&buffer[..n]).unwrap();
            let mut reply=Message::response(request.metadata.id,request.metadata.op_code);reply.queries=request.queries;
            for q in reply.queries.clone() { if q.query_type()==RecordType::A {
                reply.add_answer(hickory_proto::rr::Record::from_rdata(q.name().clone(),60,RData::A(hickory_proto::rr::rdata::A(std::net::Ipv4Addr::LOCALHOST))));
            }}
            dns.send_to(&reply.to_vec().unwrap(),peer).await.unwrap();
            dns_progress.dns_replied.fetch_add(1,Ordering::SeqCst);
        }
    });
    let target=TcpListener::bind("127.0.0.1:0").await.unwrap();let target_port=target.local_addr().unwrap().port();
    let target_progress=progress.clone();
    let target_task=tokio::spawn(async move { loop {
        let (mut s,_)=target.accept().await.unwrap(); let mut b=Vec::new();let mut c=[0;1];
        target_progress.target_accepted.fetch_add(1,Ordering::SeqCst);
        while !b.ends_with(b"\r\n\r\n") { if s.read_exact(&mut c).await.is_err(){target_progress.target_read_error.fetch_add(1,Ordering::SeqCst);break;} b.push(c[0]);target_progress.target_read_bytes.fetch_add(1,Ordering::SeqCst); }
        if b.ends_with(b"\r\n\r\n") {target_progress.target_headers.fetch_add(1,Ordering::SeqCst);}
        s.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\nobserved-ok").await.unwrap();
        target_progress.target_written.fetch_add(1,Ordering::SeqCst);
        drop(s);target_progress.target_closed.fetch_add(1,Ordering::SeqCst);
    }});
    diagnostic_stage(&progress,"fixtures-bound");
    let reserved:Vec<_>=(21000..49000).filter_map(|p|std::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST,p)).ok()).take(2).collect();
    let ports:Vec<_>=reserved.iter().map(|s|s.local_addr().unwrap().port()).collect();drop(reserved);
    let source=format!("mode: direct\nlog-level: warning\nmmdb: null\nasn-mmdb: null\ngeosite: null\nipv6: false\ntun: {{enable: false}}\ndns:\n  enable: true\n  nameserver: ['udp://{dns_addr}']\n  default-nameserver: ['udp://{dns_addr}']\nmixed-port: {}\nallow-lan: false\nbind-address: 127.0.0.1\nexternal-controller: 127.0.0.1:{}\nsecret: '0123456789abcdef0123456789abcdef'\ncors-allow-origins: []\nrules: ['MATCH,DIRECT']\n",ports[0],ports[1]);
    let directory=tempfile::tempdir().unwrap();let path=directory.path().to_owned();
    let cancel=CancellationToken::new();let stopping=cancel.clone();let (ready,rx)=oneshot::channel();
    diagnostic_stage(&progress,"runtime-spawn");
    let owner=tokio::spawn(run_runtime(source,path,RuntimeInput::Proxy,stopping,move|r|{let _=ready.send(r);}));
    diagnostic_stage(&progress,"runtime-ready-wait");
    tokio::time::timeout(Duration::from_secs(5),rx).await.unwrap().unwrap();
    diagnostic_stage(&progress,"runtime-ready-complete");
    diagnostic_stage(&progress,"configs-begin");
    let startup=api(ports[1],"GET","/configs","").await;assert!(startup.contains(r#""log-level":"warning""#));
    diagnostic_stage(&progress,"configs-complete/source-begin");
    let collector=telemetry::source().unwrap().observations.clone();
    diagnostic_stage(&progress,"source-complete/ticket-begin");
    assert!(collector.ticket().is_none());
    diagnostic_stage(&progress,"ticket-complete/legacy-connect-begin");
    let mut legacy=socket(ports[1],false).await;
    diagnostic_stage(&progress,"legacy-connect-complete/traffic-frame-begin");
    assert_eq!(frame(&mut legacy).await["type"],"traffic");
    diagnostic_stage(&progress,"legacy-traffic-complete/groups-frame-begin");
    assert_eq!(frame(&mut legacy).await["type"],"groupsChanged");
    diagnostic_stage(&progress,"legacy-groups-complete/ticket-begin");
    assert!(collector.ticket().is_none());
    diagnostic_stage(&progress,"legacy-ticket-complete/rich-connect-begin");
    let mut ws=socket(ports[1],true).await;
    diagnostic_stage(&progress,"rich-connect-complete/initial-frame-begin");
    let initial=frame(&mut ws).await;
    diagnostic_stage(&progress,"rich-initial-frame-complete");
    assert_eq!(initial["type"],"observations");assert_eq!(initial["cursor"],"0");assert_eq!(initial["events"],json!([]));
    let generation=initial["generation"].clone();let session=initial["sessionId"].clone();
    let mut events=Vec::new();let mut cursor=0;
    for ordinal in 0..2 {
        diagnostic_stage(&progress,&format!("http-{ordinal}-connect-begin"));
        let mut request=TcpStream::connect((std::net::Ipv4Addr::LOCALHOST,ports[0])).await.unwrap();
        diagnostic_stage(&progress,&format!("http-{ordinal}-connect-complete/write-begin"));
        request.write_all(format!("GET http://observation.test:{target_port}/ HTTP/1.1\r\nHost: observation.test\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
        diagnostic_stage(&progress,&format!("http-{ordinal}-write-complete/read-begin"));
        let mut response=String::new();
        let mut reading=ResponseProgress {inner:&mut request,bytes:0,prefix:Vec::new(),eof:false};
        let result=tokio::time::timeout(Duration::from_secs(5),reading.read_to_string(&mut response)).await;
        diagnostic_stage(&progress,&format!("http-{ordinal}-read-returned timeout={}",result.is_err()));
        if result.is_err() {
            let header_end=reading.prefix.windows(4).position(|v|v==b"\r\n\r\n").map(|p|p+4);
            eprintln!("[observation-http] ordinal={ordinal} bytes={} eof={} header_end={header_end:?} prefix={:?} fixture={progress:?}",
                reading.bytes,reading.eof,String::from_utf8_lossy(&reading.prefix));
            let _=std::io::Write::flush(&mut std::io::stderr());
        }
        result.unwrap().unwrap();assert!(response.ends_with("observed-ok"));
        diagnostic_stage(&progress,&format!("http-{ordinal}-events-wait-begin"));
        loop {
            let f=frame(&mut ws).await;if f["type"]!="observations"{continue;}
            assert_eq!(f["sessionId"],session);assert_eq!(f["generation"],generation);assert_eq!(f["dropped"],"0");
            for event in f["events"].as_array().unwrap(){let next=event["cursor"].as_str().unwrap().parse::<u64>().unwrap();assert!(next>cursor);cursor=next;events.push(event.clone());}
            if events.iter().filter(|e|e["kind"]=="requestClosed").count()>=ordinal+1 && events.iter().filter(|e|e["kind"]=="dns").count()>=ordinal+1 {break;}
        }
        diagnostic_stage(&progress,&format!("http-{ordinal}-events-complete"));
    }
    let requests:Vec<_>=events.iter().filter(|e|e["kind"]=="request").collect();assert_eq!(requests.len(),2);assert_ne!(requests[0]["data"]["id"],requests[1]["data"]["id"]);
    for r in requests {let d=&r["data"];assert!(uuid::Uuid::parse_str(d["id"].as_str().unwrap()).is_ok());assert!(d["metadata"]["sourceIP"].is_string());assert!(d["start"].is_string());assert!(events.iter().any(|e|e["kind"]=="requestClosed"&&e["data"]["id"]==d["id"]&&e["data"]["download"].as_str().unwrap().parse::<u64>().unwrap()>0));}
    let dns:Vec<_>=events.iter().filter(|e|e["kind"]=="dns").map(|e|&e["data"]).collect();assert!(dns.iter().any(|d|d["cached"]==false));assert!(dns.iter().any(|d|d["cached"]==true));
    for d in dns {assert_eq!(d["initiator"],"Direct");assert_eq!(d["domain"],"observation.test");assert_eq!(d["rcode"],"NOERROR");assert!(d["upstream"].as_str().unwrap().ends_with(&dns_addr.port().to_string()));assert!(d["answers"].as_array().unwrap().iter().any(|v|v=="127.0.0.1"));}
    diagnostic_stage(&progress,"io-events-assertions-complete/cache-flush-begin");
    // Existing cache action must invalidate the real answer and its source metadata.
    assert!(api(ports[1],"POST","/cache/dns/flush","").await.starts_with("HTTP/1.1 204"));
    diagnostic_stage(&progress,"cache-flush-complete/log-connect-begin");
    // Real config PATCH changes the source gate, not only GET readback/local filters.
    let mut request=format!("ws://127.0.0.1:{}/logs",ports[1]).into_client_request().unwrap();
    request.headers_mut().insert("authorization","Bearer 0123456789abcdef0123456789abcdef".parse().unwrap());
    let mut logs=connect_async(request).await.unwrap().0;
    diagnostic_stage(&progress,"log-connect-complete/context-begin");
    let context=context().unwrap();
    diagnostic_stage(&progress,"context-complete/log-receiver-wait-begin");
    tokio::time::timeout(Duration::from_secs(2),async{while context.logs.receiver_count()==0{tokio::task::yield_now().await;}}).await.unwrap();
    diagnostic_stage(&progress,"log-receiver-complete/silent-patch-begin");
    assert!(api(ports[1],"PATCH","/configs",r#"{"log-level":"silent"}"#).await.starts_with("HTTP/1.1 202"));
    diagnostic_stage(&progress,"silent-patch-complete/level-read-begin");
    assert_eq!(telemetry::source().unwrap().log_level(),Some(tracing::level_filters::LevelFilter::OFF));
    diagnostic_stage(&progress,"level-read-complete/silent-marker-begin");
    tracing::info!("observation-test-silent-marker");
    diagnostic_stage(&progress,"silent-marker-complete/log-silent-wait-begin");
    assert!(tokio::time::timeout(Duration::from_millis(40),logs.next()).await.is_err());
    diagnostic_stage(&progress,"log-silent-wait-complete/info-patch-begin");
    assert!(api(ports[1],"PATCH","/configs",r#"{"log-level":"info"}"#).await.starts_with("HTTP/1.1 202"));
    diagnostic_stage(&progress,"info-patch-complete/visible-marker-begin");
    tracing::info!("observation-test-visible-marker");
    diagnostic_stage(&progress,"visible-marker-complete/log-frame-begin");
    let log=frame(&mut logs).await;assert!(log["payload"].as_str().unwrap().contains("observation-test-visible-marker"));
    diagnostic_stage(&progress,"log-frame-complete/log-close-begin");
    logs.close(None).await.unwrap();drop(logs);
    diagnostic_stage(&progress,"log-close-complete/receiver-release-wait-begin");
    // No new event wakes this receiver: socket-close itself must release it.
    tokio::time::timeout(Duration::from_secs(2),async{while context.logs.receiver_count()!=0{tokio::task::yield_now().await;}}).await.unwrap();
    diagnostic_stage(&progress,"receiver-release-complete/ticket-begin");
    let old_ticket=collector.ticket().unwrap();
    diagnostic_stage(&progress,"ticket-complete/rich-close-begin");
    ws.close(None).await.unwrap();drop(ws);
    diagnostic_stage(&progress,"rich-close-complete/lease-release-wait-begin");
    tokio::time::timeout(Duration::from_secs(2),async{while collector.ticket().is_some(){tokio::task::yield_now().await;}}).await.unwrap();
    diagnostic_stage(&progress,"lease-release-complete/old-ticket-emit-begin");
    old_ticket.emit("dns",json!({"domain":"late-sensitive.test"}));
    diagnostic_stage(&progress,"old-ticket-emit-complete/reopen-begin");
    let mut next=socket(ports[1],true).await;
    diagnostic_stage(&progress,"reopen-complete/reopen-frame-begin");
    let empty=frame(&mut next).await;assert_ne!(empty["generation"],generation);assert_eq!(empty["events"],json!([]));assert_eq!(empty["sessionId"],session);
    diagnostic_stage(&progress,"reopen-frame-complete/cancel-begin");
    cancel.cancel();
    diagnostic_stage(&progress,"cancel-complete/owner-join-begin");
    tokio::time::timeout(Duration::from_secs(5),owner).await.unwrap().unwrap().unwrap();
    diagnostic_stage(&progress,"owner-join-complete/final-ticket-begin");
    assert!(collector.ticket().is_none());
    diagnostic_stage(&progress,"final-ticket-complete/fixture-stop-begin");
    dns_task.abort();target_task.abort();let _=dns_task.await;let _=target_task.await;
    diagnostic_stage(&progress,"io-complete");
}
async fn lease() {
    let cancel=CancellationToken::new();let collector=observations::Collector::new(cancel.clone());assert!(collector.ticket().is_none());
    let lease=collector.lease();let generation=lease.generation;let ticket=collector.ticket().unwrap();
    for n in 0..3100 {ticket.emit("dns",json!({"n":n}));}
    ticket.emit("request",json!({"id":"real-request-kept-separate"}));
    ticket.emit("dns",json!({"oversize":"x".repeat(64*1024)}));
    let mut cursor=0;let mut count=0;let mut request=false;
    loop {let w=collector.window(generation,cursor,false).unwrap();let v=serde_json::to_value(w).unwrap();assert_eq!(v["dropped"],"101");let next=v["cursor"].as_str().unwrap().parse().unwrap();if next==cursor{break;}cursor=next;for e in v["events"].as_array().unwrap(){count+=1;request|=e["kind"]=="request";}}
    assert_eq!(count,3001);assert!(request);drop(lease);assert!(collector.ticket().is_none());let second=collector.lease();assert!(second.generation>generation);ticket.emit("dns",json!({"private":"late"}));let v=serde_json::to_value(collector.window(second.generation,0,false).unwrap()).unwrap();assert_eq!(v["cursor"],"0");assert_eq!(v["events"],json!([]));
    let current=collector.ticket().unwrap();
    let info=crate::app::dispatcher::TrackerInfo { uuid:uuid::Uuid::new_v4(), ..Default::default() };
    info.upload_total.store(u64::MAX,std::sync::atomic::Ordering::Relaxed);
    current.request(&info).await;
    current.traffic(&info,None); // unchanged counters do not generate work
    info.download_total.store(u64::MAX,std::sync::atomic::Ordering::Relaxed);
    current.traffic(&info,None);current.traffic(&info,Some(chrono::Utc::now()));
    let precision=serde_json::to_value(collector.window(second.generation,0,false).unwrap()).unwrap();
    let entries=precision["events"].as_array().unwrap();assert_eq!(entries.len(),3);
    assert_eq!(entries[0]["kind"],"request");assert_eq!(entries[1]["kind"],"requestTraffic");assert_eq!(entries[2]["kind"],"requestClosed");
    assert_eq!(entries[0]["data"]["upload"],u64::MAX.to_string());assert_eq!(entries[2]["data"]["download"],u64::MAX.to_string());
    cancel.cancel();assert!(collector.ticket().is_none());
}
pub async fn run(case:&str) {match case {"observations-io"=>io().await,"observations-lease"=>lease().await,_=>panic!("unknown observation case")}}
