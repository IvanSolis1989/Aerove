#![cfg(any(target_os="linux",target_os="android",target_os="macos"))]
use aerove_meow_core::{network_context::NetworkContext, resource_fetch::{ResourceFetch,DownloadRequest,validate_prepared}};
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
    let files=Files::new();let owner=files.owner();let ingress=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=ingress.local_addr().unwrap();
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
