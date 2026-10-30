#![cfg(any(target_os="linux",target_os="android",target_os="macos"))]
use aerove_meow_core::{network_context::NetworkContext, resource_fetch::{ResourceFetch,ResourceOptions,ResourceHttpPolicy,DownloadRequest,validate_prepared}};
use futures::FutureExt;
use std::{collections::BTreeMap, path::PathBuf, sync::{Arc,atomic::{AtomicU64,Ordering}}, time::Duration};
use tokio::{io::{AsyncReadExt,AsyncWriteExt},net::{TcpListener,TcpStream}};
use tokio_util::sync::CancellationToken;

struct Files(PathBuf);
impl Files {
    fn new()->Self {
        static NEXT:AtomicU64=AtomicU64::new(0);
        let root=std::env::temp_dir().join(format!("meow-resource-fetch-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));
        std::fs::create_dir(&root).unwrap();for dir in ["read","cache","stage"] {std::fs::create_dir(root.join(dir)).unwrap();}Self(root.canonicalize().unwrap())
    }
    fn path(&self,name:&str)->PathBuf {self.0.join(name)}
    fn owner(&self)->Arc<ResourceFetch> {
        let cancel=CancellationToken::new();ResourceFetch::create(NetworkContext::platform(cancel.clone()),vec!["127.0.0.1:9".parse().unwrap()],
            self.path("stage").to_str().unwrap(),Some(self.path("read").to_str().unwrap()),self.path("cache").to_str().unwrap(),cancel).unwrap()
    }
    fn owner_with(&self,options:ResourceOptions)->Arc<ResourceFetch> {
        let cancel=CancellationToken::new();ResourceFetch::create_with_options(NetworkContext::platform(cancel.clone()),vec!["127.0.0.1:9".parse().unwrap()],
            self.path("stage").to_str().unwrap(),Some(self.path("read").to_str().unwrap()),self.path("cache").to_str().unwrap(),cancel,options).unwrap()
    }
}
impl Drop for Files {fn drop(&mut self){std::fs::remove_dir_all(&self.0).unwrap();}}
async fn head(socket:&mut TcpStream)->String {
    let mut bytes=Vec::new();while !bytes.ends_with(b"\r\n\r\n"){bytes.push(socket.read_u8().await.unwrap());assert!(bytes.len()<65536);}String::from_utf8(bytes).unwrap()
}
async fn proxy(listener:TcpListener,count:usize) {
    for _ in 0..count {
        let(mut input,_)=listener.accept().await.unwrap();let request=head(&mut input).await;
        let target=request.lines().next().unwrap().strip_prefix("CONNECT ").unwrap().split_whitespace().next().unwrap();
        let mut output=TcpStream::connect(target).await.unwrap();input.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await.unwrap();
        if let Err(cause)=tokio::io::copy_bidirectional(&mut input,&mut output).await {
            assert!(matches!(cause.kind(),std::io::ErrorKind::ConnectionReset|std::io::ErrorKind::ConnectionAborted|std::io::ErrorKind::BrokenPipe),"{cause}");
        }
    }
}
fn yaml(proxy:std::net::SocketAddr,unused:std::net::SocketAddr)->String {
    format!("strict: true\nproxies: [{{name: NODE,type: http,server: '127.0.0.1',port: {}}}]\nproxy-groups: [{{name: PICK,type: select,proxies: [NODE,REJECT]}}]\nrule-providers:\n future: {{type: http,behavior: domain,format: mrs,url: 'http://{unused}/not-bootstrap',path: future.mrs,interval: 1,proxy: PICK}}\nrules: ['RULE-SET,future,DIRECT','MATCH,PICK']\n",proxy.port())
}
fn request(files:&Files,url:String,name:&str,max_bytes:usize)->DownloadRequest {
    DownloadRequest {url,proxy:Some("PICK".into()),headers:BTreeMap::from([("User-Agent".into(),vec!["AEROVE fixture".into()]),("X-Proof".into(),vec!["first".into(),"second".into()])]),
        destination:files.path(&format!("stage/{name}")).to_str().unwrap().into(),max_bytes}
}
fn load(owner:&Arc<ResourceFetch>,source:String)->std::pin::Pin<Box<dyn std::future::Future<Output=std::io::Result<()>>+Send+'_>> {
    Box::pin(owner.load(source))
}
#[tokio::test(flavor="multi_thread",worker_threads=2)]
async fn captured_policy_uses_selected_nonfirst_proxy_and_preserves_explicit_headers() {
    let files=Files::new();let ingress=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=ingress.local_addr().unwrap();
    let origin=TcpListener::bind("127.0.0.1:0").await.unwrap();let target=origin.local_addr().unwrap();
    let unused=TcpListener::bind("127.0.0.1:0").await.unwrap();
    let default=files.owner();load(&default,yaml(address,unused.local_addr().unwrap())).await.unwrap();
    let options=ResourceOptions {http:Some(ResourceHttpPolicy {user_agent:"AEROVE/captured".into(),check_certificate:true}),
        selection_cache:BTreeMap::from([("PICK".into(),"NODE".into())])};
    let captured=files.owner_with(options);
    load(&captured,yaml(address,unused.local_addr().unwrap()).replace("[NODE,REJECT]","[REJECT,NODE]")).await.unwrap();
    assert!(ingress.accept().now_or_never().is_none());
    let forwarding=tokio::spawn(proxy(ingress,3));
    let serving=tokio::spawn(async move {
        let mut headers=Vec::new();for _ in 0..3 {
            let(mut socket,_)=origin.accept().await.unwrap();headers.push(head(&mut socket).await);
            socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok").await.unwrap();
        }headers
    });
    for (owner,name,explicit) in [(&default,"default",false),(&captured,"captured",false),(&captured,"explicit",true)] {
        let mut input=request(&files,format!("http://{target}/body"),name,64);
        if !explicit {input.headers.remove("User-Agent");} else {input.headers.insert("user-agent".into(),vec!["Provider/explicit".into()]);input.headers.remove("User-Agent");}
        tokio::time::timeout(Duration::from_secs(5),owner.download(input)).await.unwrap().unwrap();
        assert_eq!(std::fs::read(files.path(&format!("stage/{name}"))).unwrap(),b"ok");
    }
    let headers=tokio::time::timeout(Duration::from_secs(5),serving).await.unwrap().unwrap();
    assert!(headers[0].lines().any(|line|line.starts_with("User-Agent: clash.meta/")));
    assert!(headers[1].contains("User-Agent: AEROVE/captured\r\n"));
    assert!(headers[2].contains("user-agent: Provider/explicit\r\n"));
    assert!(!headers[2].contains("AEROVE/captured"));
    for header in headers {assert!(header.contains("X-Proof: first\r\n"));assert!(header.contains("X-Proof: second\r\n"));}
    default.close_and_join().await.unwrap();captured.close_and_join().await.unwrap();
    tokio::time::timeout(Duration::from_secs(5),forwarding).await.unwrap().unwrap();assert!(unused.accept().now_or_never().is_none());
}

#[tokio::test]
async fn unsupported_cached_selection_is_diagnosed_before_any_download() {
    let files=Files::new();let ingress=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=ingress.local_addr().unwrap();
    for (name,member) in [("NODE","NODE")] {
        let owner=files.owner_with(ResourceOptions {http:None,selection_cache:BTreeMap::from([(name.into(),member.into())])});
        let failure=load(&owner,yaml(address,address)).await.unwrap_err().to_string();
        assert!(failure.contains(&format!("$.selectionCache.{name}:")),"{failure}");
        let input=request(&files,format!("http://{address}/forbidden"),"forbidden",64);
        assert!(owner.download(input).await.is_err());owner.close_and_join().await.unwrap();
    }
    assert!(ingress.accept().now_or_never().is_none());assert!(!files.path("stage/forbidden").exists());
}

async fn restored_cache_http(kind:&str,requested:&str,deleted:bool,second_selected:bool) {
    let files=Files::new();let first=TcpListener::bind("127.0.0.1:0").await.unwrap();let second=TcpListener::bind("127.0.0.1:0").await.unwrap();
    let mut cache=BTreeMap::from([("PICK".into(),requested.into())]);if deleted {cache.insert("DELETED".into(),"OLD".into());}
    let owner=files.owner_with(ResourceOptions {http:None,selection_cache:cache});
    let source=format!("proxies: [{{name: FIRST,type: http,server: '127.0.0.1',port: {}}},{{name: SECOND,type: http,server: '127.0.0.1',port: {}}}]\nproxy-groups: [{{name: PICK,type: {kind},proxies: [FIRST,SECOND],url: 'http://127.0.0.1:9',interval: 0}}]\nrules: ['MATCH,PICK']\n",first.local_addr().unwrap().port(),second.local_addr().unwrap().port());
    load(&owner,source).await.unwrap();assert!(first.accept().now_or_never().is_none());assert!(second.accept().now_or_never().is_none());
    let(selected,unused)=if second_selected {(second,first)} else {(first,second)};let forwarding=tokio::spawn(proxy(selected,1));
    let origin=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=origin.local_addr().unwrap();
    let serving=tokio::spawn(async move {let(mut socket,_)=origin.accept().await.unwrap();let _=head(&mut socket).await;
        socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nrestore").await.unwrap();});
    tokio::time::timeout(Duration::from_secs(5),owner.download(request(&files,format!("http://{address}/body"),"restored",64))).await.unwrap().unwrap();
    assert_eq!(std::fs::read(files.path("stage/restored")).unwrap(),b"restore");owner.close_and_join().await.unwrap();
    tokio::time::timeout(Duration::from_secs(5),serving).await.unwrap().unwrap();tokio::time::timeout(Duration::from_secs(5),forwarding).await.unwrap().unwrap();
    assert!(unused.accept().now_or_never().is_none());assert!(!files.path("cache/selector-cache.json").exists());
}
#[tokio::test(flavor="multi_thread",worker_threads=2)]
async fn deleted_cache_group_is_skipped_and_valid_nonfirst_selection_reaches_http() {
    restored_cache_http("select","SECOND",true,true).await;
}
#[tokio::test(flavor="multi_thread",worker_threads=2)]
async fn stale_or_empty_selector_cache_uses_the_actual_first_http_member() {
    for member in ["STALE",""] {restored_cache_http("select",member,false,false).await;}
}
#[tokio::test(flavor="multi_thread",worker_threads=2)]
async fn stale_or_empty_urltest_cache_resumes_automatic_real_http() {
    for member in ["STALE",""] {restored_cache_http("url-test",member,false,false).await;}
}
#[tokio::test(flavor="multi_thread",worker_threads=2)]
async fn stale_or_empty_fallback_cache_restores_fixed_standard_real_http() {
    for member in ["STALE",""] {restored_cache_http("fallback",member,false,false).await;}
}

#[tokio::test]
async fn automatic_candidate_pin_and_unfix_are_memory_only_and_invalid_policy_is_rejected() {
    let files=Files::new();
    for member in ["REJECT",""] {
        let owner=files.owner_with(ResourceOptions {http:None,selection_cache:BTreeMap::from([("AUTO".into(),member.into())])});
        load(&owner,"proxy-groups: [{name: AUTO,type: url-test,proxies: [DIRECT,REJECT],url: 'http://127.0.0.1:9',interval: 0}]\nrules: ['MATCH,AUTO']\n".into()).await.unwrap();
        owner.close_and_join().await.unwrap();assert!(!files.path("cache/selector-cache.json").exists());
    }
    for agent in [""," \t","injected\r\nHeader: true"] {
        let cancel=CancellationToken::new();let result=ResourceFetch::create_with_options(NetworkContext::platform(cancel.clone()),vec!["127.0.0.1:9".parse().unwrap()],
            files.path("stage").to_str().unwrap(),Some(files.path("read").to_str().unwrap()),files.path("cache").to_str().unwrap(),cancel,
            ResourceOptions {http:Some(ResourceHttpPolicy {user_agent:agent.into(),check_certificate:false}),selection_cache:BTreeMap::new()});
        let cause=match result {Ok(_)=>panic!("invalid policy accepted"),Err(cause)=>cause};assert!(cause.to_string().contains("$.http.userAgent:"));
    }
}

fn resource_tls_acceptor()->boring::ssl::SslAcceptor {
    use boring::{asn1::Asn1Time,bn::BigNum,hash::MessageDigest,pkey::PKey,rsa::Rsa,x509::{X509,X509NameBuilder}};
    let key=PKey::from_rsa(Rsa::generate(2048).unwrap()).unwrap();let mut name=X509NameBuilder::new().unwrap();
    name.append_entry_by_text("CN","localhost").unwrap();let name=name.build();let mut cert=X509::builder().unwrap();cert.set_version(2).unwrap();
    let serial=BigNum::from_u32(1).unwrap().to_asn1_integer().unwrap();cert.set_serial_number(&serial).unwrap();
    cert.set_subject_name(&name).unwrap();cert.set_issuer_name(&name).unwrap();cert.set_pubkey(&key).unwrap();
    let before=Asn1Time::days_from_now(0).unwrap();let after=Asn1Time::days_from_now(1).unwrap();
    cert.set_not_before(&before).unwrap();cert.set_not_after(&after).unwrap();cert.sign(&key,MessageDigest::sha256()).unwrap();
    let mut acceptor=boring::ssl::SslAcceptor::mozilla_intermediate_v5(boring::ssl::SslMethod::tls()).unwrap();
    acceptor.set_certificate(&cert.build()).unwrap();acceptor.set_private_key(&key).unwrap();acceptor.build()
}
#[tokio::test(flavor="multi_thread",worker_threads=2)]
async fn candidate_certificate_policy_reaches_real_tls_and_default_still_verifies() {
    let files=Files::new();let ingress=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=ingress.local_addr().unwrap();
    let origin=TcpListener::bind("127.0.0.1:0").await.unwrap();let target=origin.local_addr().unwrap();
    let acceptor=resource_tls_acceptor();let forwarding=tokio::spawn(proxy(ingress,3));
    let serving=tokio::spawn(async move {
        let mut completed=0;for _ in 0..3 {
            let(socket,_)=origin.accept().await.unwrap();if let Ok(mut socket)=tokio_boring::accept(&acceptor,socket).await {
                let mut header=Vec::new();while !header.ends_with(b"\r\n\r\n") {header.push(socket.read_u8().await.unwrap());assert!(header.len()<65536);}
                assert!(String::from_utf8(header).unwrap().contains("User-Agent: AEROVE/tls\r\n"));
                socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\ntls").await.unwrap();completed+=1;
            }
        }completed
    });
    for (index,check) in [None,Some(true),Some(false)].into_iter().enumerate() {
        let owner=files.owner_with(ResourceOptions {http:check.map(|check_certificate|ResourceHttpPolicy {user_agent:"AEROVE/tls".into(),check_certificate}),selection_cache:BTreeMap::new()});
        load(&owner,yaml(address,address)).await.unwrap();let name=format!("tls-{index}");let mut input=request(&files,format!("https://{target}/body"),&name,64);input.headers.remove("User-Agent");
        let result=tokio::time::timeout(Duration::from_secs(5),owner.download(input)).await.unwrap();
        if check==Some(false) {assert_eq!(result.unwrap().bytes,3);assert_eq!(std::fs::read(files.path(&format!("stage/{name}"))).unwrap(),b"tls");}
        else {assert!(result.is_err());assert!(!files.path(&format!("stage/{name}")).exists());}
        owner.close_and_join().await.unwrap();
    }
    assert_eq!(tokio::time::timeout(Duration::from_secs(5),serving).await.unwrap().unwrap(),1);
    tokio::time::timeout(Duration::from_secs(5),forwarding).await.unwrap().unwrap();
}

#[tokio::test(flavor="multi_thread",worker_threads=2)]
async fn materialized_file_provider_is_the_named_download_graph_and_never_reads_old_cache() {
    let files=Files::new();let owner=files.owner();
    let ingress=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=ingress.local_addr().unwrap();
    let old=TcpListener::bind("127.0.0.1:0").await.unwrap();
    let provider=files.path("read/provider.yaml");
    std::fs::write(&provider,format!("proxies: [{{name: NODE,type: http,server: '127.0.0.1',port: {}}}]\n",address.port())).unwrap();
    std::fs::write(files.path("cache/provider.yaml"),format!("proxies: [{{name: OLD,type: http,server: '127.0.0.1',port: {}}}]\n",old.local_addr().unwrap().port())).unwrap();
    let source=format!("dns: {{enable: false}}\nproxy-providers:\n P: {{type: file,path: {:?},interval: 0}}\nproxy-groups: [{{name: PICK,type: select,use: [P]}}]\nrules: ['MATCH,PICK']\n",provider.to_str().unwrap());
    load(&owner,source).await.unwrap();
    let origin=TcpListener::bind("127.0.0.1:0").await.unwrap();let origin_address=origin.local_addr().unwrap();
    let forwarding=tokio::spawn(proxy(ingress,1));
    let serving=tokio::spawn(async move {
        let(mut socket,_)=origin.accept().await.unwrap();let _=head(&mut socket).await;
        socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nfresh").await.unwrap();
    });
    let result=tokio::time::timeout(Duration::from_secs(5),owner.download(request(&files,format!("http://{origin_address}/body"),"provider-result",64))).await.unwrap().unwrap();
    assert_eq!(result.bytes,5);assert_eq!(std::fs::read(files.path("stage/provider-result")).unwrap(),b"fresh");
    owner.close_and_join().await.unwrap();serving.await.unwrap();forwarding.await.unwrap();
    assert!(old.accept().now_or_never().is_none());assert!(provider.is_file());
}

#[tokio::test]
async fn provider_transport_rejects_external_payload_and_unowned_dns_geodata_before_factory() {
    let files=Files::new();let owner=files.owner();let external=files.path("stage/provider.yaml");
    std::fs::write(&external,"proxies: [{name: NODE,type: direct}]\n").unwrap();
    let yaml=format!("proxy-providers: {{P: {{type: file,path: {:?}}}}}\nrules: ['MATCH,DIRECT']\n",external.to_str().unwrap());
    assert!(load(&owner,yaml).await.unwrap_err().to_string().contains("resourceReadRoot"));
    owner.close_and_join().await.unwrap();
    let owner=files.owner();
    assert!(load(&owner,"dns: {enable: true,fallback: ['udp://127.0.0.1:9']}\nrules: ['MATCH,DIRECT']\n".into()).await.unwrap_err().to_string().contains("mmdb-path"));
    owner.close_and_join().await.unwrap();
}
#[tokio::test(flavor="multi_thread",worker_threads=2)]
async fn named_candidate_graph_real_http_headers_staging_cap_and_no_bootstrap_rule_fetch() {
    let files=Files::new();let owner=files.owner();let ingress=TcpListener::bind("127.0.0.1:0").await.unwrap();let proxy_address=ingress.local_addr().unwrap();
    let origin=TcpListener::bind("127.0.0.1:0").await.unwrap();let origin_address=origin.local_addr().unwrap();let unused=TcpListener::bind("127.0.0.1:0").await.unwrap();
    load(&owner,yaml(proxy_address,unused.local_addr().unwrap())).await.unwrap();
    assert!(unused.accept().now_or_never().is_none());assert!(ingress.accept().now_or_never().is_none());
    let mut invalid=request(&files,format!("http://{origin_address}/body"),"missing",64);invalid.proxy=Some("MISSING".into());
    let failure=match owner.download(invalid).await {Err(failure)=>failure,Ok(_)=>panic!("unknown proxy unexpectedly downloaded successfully")};
    assert!(failure.to_string().contains("proxy"));assert!(origin.accept().now_or_never().is_none());
    let forwarding=tokio::spawn(proxy(ingress,2));
    let serving=tokio::spawn(async move {for _ in 0..2 {
        let(mut socket,_)=origin.accept().await.unwrap();let request=head(&mut socket).await;
        assert!(request.starts_with("GET /body HTTP/1.1\r\n"));assert!(request.contains("User-Agent: AEROVE fixture\r\n"));
        assert!(request.contains("X-Proof: first\r\n"));assert!(request.contains("X-Proof: second\r\n"));
        socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\nSubscription-Userinfo: upload=1; download=2; total=10\r\nConnection: close\r\n\r\nhello").await.unwrap();
    }});
    let result=tokio::time::timeout(Duration::from_secs(5),owner.download(request(&files,format!("http://{origin_address}/body"),"accepted",64))).await.unwrap().unwrap();
    assert_eq!(result.bytes,5);assert_eq!(result.subscription_user_info.as_deref(),Some("upload=1; download=2; total=10"));assert_eq!(std::fs::read(files.path("stage/accepted")).unwrap(),b"hello");
    assert!(tokio::time::timeout(Duration::from_secs(5),owner.download(request(&files,format!("http://{origin_address}/body"),"oversize",3))).await.unwrap().is_err());
    assert!(!files.path("stage/oversize").exists());assert!(unused.accept().now_or_never().is_none());
    tokio::time::timeout(Duration::from_secs(5),serving).await.unwrap().unwrap();tokio::time::timeout(Duration::from_secs(5),forwarding).await.unwrap().unwrap();
    tokio::time::timeout(Duration::from_secs(5),owner.close_and_join()).await.unwrap().unwrap();
}
#[tokio::test(flavor="multi_thread",worker_threads=2)]
async fn cancelled_named_fetch_joins_real_socket_and_never_returns_a_staged_success() {
    let files=Files::new();let owner=files.owner_with(ResourceOptions {http:Some(ResourceHttpPolicy {user_agent:"AEROVE/cancel".into(),check_certificate:false}),
        selection_cache:BTreeMap::from([("PICK".into(),"NODE".into())])});
    let ingress=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=ingress.local_addr().unwrap();
    let origin=TcpListener::bind("127.0.0.1:0").await.unwrap();let target=origin.local_addr().unwrap();let unused=TcpListener::bind("127.0.0.1:0").await.unwrap();
    load(&owner,yaml(address,unused.local_addr().unwrap())).await.unwrap();let forwarding=tokio::spawn(proxy(ingress,1));
    let request=request(&files,format!("http://{target}/held"),"cancelled",64);let running=owner.clone();let download=tokio::spawn(async move {running.download(request).await});
    let(mut socket,_)=tokio::time::timeout(Duration::from_secs(5),origin.accept()).await.unwrap().unwrap();head(&mut socket).await;
    tokio::time::timeout(Duration::from_secs(5),owner.close_and_join()).await.unwrap().unwrap();assert!(download.await.unwrap().is_err());
    let mut byte=[0];assert_eq!(tokio::time::timeout(Duration::from_secs(5),socket.read(&mut byte)).await.unwrap().unwrap(),0);drop(socket);
    tokio::time::timeout(Duration::from_secs(5),forwarding).await.unwrap().unwrap();assert!(!files.path("stage/cancelled").exists());
}
#[tokio::test]
async fn invalid_candidate_dialer_cycle_has_no_network_or_staged_commit() {
    let files=Files::new();let owner=files.owner();let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let port=listener.local_addr().unwrap().port();
    let source=format!("strict: true\nproxies: [{{name: A,type: http,server: '127.0.0.1',port: {port},dialer-proxy: B}},{{name: B,type: http,server: '127.0.0.1',port: {port},dialer-proxy: A}}]\nrules: ['MATCH,A']\n");
    assert!(load(&owner,source).await.unwrap_err().to_string().contains("cycle"));assert!(listener.accept().now_or_never().is_none());assert_eq!(std::fs::read_dir(files.path("stage")).unwrap().count(),0);
    tokio::time::timeout(Duration::from_secs(5),owner.close_and_join()).await.unwrap().unwrap();
}
#[tokio::test]
async fn prepared_geosite_uses_actual_owned_database_and_rejects_absent_or_external_resource() {
    let files=Files::new();let mut domain=vec![8,3,18,11];domain.extend_from_slice(b"example.com");
    let mut site=vec![10,2,b'c',b'n',18,domain.len() as u8];site.extend(domain);let mut dat=vec![10,site.len() as u8];dat.extend(site);
    let path=files.path("read/geosite.dat");std::fs::write(&path,&dat).unwrap();let quoted=serde_yaml::to_string(path.to_str().unwrap()).unwrap();
    let source=format!("geodata: {{geosite-path: {}}}\nrules: ['AND,((DST-PORT,443),(NETWORK,UDP),(NOT,((GEOSITE,cn)))),REJECT','MATCH,DIRECT']\n",quoted.trim());
    assert_eq!(aerove_meow_core::config_validation::validate_config(&source).await.unwrap_err(),"offline_geodata_resource_context_required");
    validate_prepared(&source,files.path("read").to_str().unwrap(),files.path("cache").to_str().unwrap()).await.unwrap();
    let absent="rules: ['GEOSITE,cn,DIRECT']\n";
    assert!(validate_prepared(absent,files.path("read").to_str().unwrap(),files.path("cache").to_str().unwrap()).await.unwrap_err().contains("$.geodata.geosite-path"));
    let outside=files.path("geosite.dat");std::fs::write(&outside,&dat).unwrap();let outside_yaml=serde_yaml::to_string(outside.to_str().unwrap()).unwrap();
    let invalid=source.replace(quoted.trim(),outside_yaml.trim());assert!(validate_prepared(&invalid,files.path("read").to_str().unwrap(),files.path("cache").to_str().unwrap()).await.unwrap_err().contains("outside prepared resource root"));
    assert_eq!(std::fs::read(path).unwrap(),dat);
}

#[tokio::test]
async fn resource_preflight_is_typed_pending_and_standalone_remains_strict() {
    use aerove_meow_core::config_validation::{preflight_rule_resource, validate_resource};
    let files=Files::new();let path=files.path("read/rules.yaml");
    std::fs::write(&path,"payload:\n  - GEOIP,CN\n  - IP-ASN,13335\n  - AND,((NETWORK,UDP),(GEOSITE,cn))\n  - DOMAIN,example.com\n").unwrap();
    let original=std::fs::read(&path).unwrap();
    let receipt:serde_json::Value=serde_json::from_str(&preflight_rule_resource(&path,"classical","yaml").unwrap()).unwrap();
    assert_eq!(receipt["state"],"requiresPreparedContext");assert_eq!(receipt["count"],4);
    assert_eq!(receipt["requires"],serde_json::json!({"geoip":true,"asn":true,"geosite":true}));
    assert!(validate_resource("ruleset",&path,"classical","yaml").await.is_err());
    assert_eq!(std::fs::read(&path).unwrap(),original);
    let read=files.path("read");let cache=files.path("cache");
    let yaml=format!("dns: {{enable: false}}\nrule-providers:\n  pending: {{type: file, behavior: classical, format: yaml, path: {:?}}}\nrules: [\"RULE-SET,pending,DIRECT\"]\n",path.to_str().unwrap());
    assert!(validate_prepared(&yaml,read.to_str().unwrap(),cache.to_str().unwrap()).await.is_err());
}

#[test]
fn resource_preflight_rejects_invalid_independent_rows_and_validates_no_geo() {
    use aerove_meow_core::config_validation::preflight_rule_resource;
    let files=Files::new();let path=files.path("read/rules.yaml");
    std::fs::write(&path,"payload:\n  - GEOIP,CN\n  - DEFINITELY-INVALID-RULE\n").unwrap();
    assert!(preflight_rule_resource(&path,"classical","yaml").is_err());
    std::fs::write(&path,"payload:\n  - DOMAIN,example.com\n  - IP-CIDR,192.0.2.0/24\n").unwrap();
    let receipt:serde_json::Value=serde_json::from_str(&preflight_rule_resource(&path,"classical","yaml").unwrap()).unwrap();
    assert_eq!(receipt["state"],"validated");assert_eq!(receipt["count"],2);
    assert_eq!(receipt["requires"],serde_json::json!({"geoip":false,"asn":false,"geosite":false}));
    std::fs::OpenOptions::new().write(true).open(&path).unwrap().set_len(aerove_meow_core::config_validation::MAX_RULE_TEXT_BYTES as u64+1).unwrap();
    assert_eq!(preflight_rule_resource(&path,"classical","yaml").unwrap_err(),"resource input exceeds 67108864 bytes");
}
