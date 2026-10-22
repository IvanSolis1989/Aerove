use super::*;
use crate::common::{http::{HttpClient,new_http_client},tls::{DefaultTlsVerifier,build_tls_client_config,resolve_server_cert_and_key}};
use http_body_util::BodyExt;
use tokio::{net::{TcpListener,TcpStream},io::{AsyncRead,AsyncWrite}};

async fn serve<S:AsyncRead+AsyncWrite+Unpin>(mut stream:S,seen:Arc<std::sync::Mutex<Vec<String>>>){
    let mut header=Vec::new();let mut byte=[0;1];while !header.ends_with(b"\r\n\r\n"){if stream.read_exact(&mut byte).await.is_err(){return;}header.push(byte[0]);assert!(header.len()<8192);}
    seen.lock().unwrap().push(String::from_utf8(header).unwrap());
    stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok").await.unwrap();
}
async fn get(client:&HttpClient,url:String,agent:Option<&str>)->std::io::Result<Vec<u8>>{
    let mut request=http::Request::builder().uri(url);
    if let Some(agent)=agent{request=request.header(http::header::USER_AGENT,agent);}
    let response=client.request(request.body(http_body_util::Empty::<bytes::Bytes>::new()).unwrap()).await?;
    Ok(response.into_body().collect().await.map_err(std::io::Error::other)?.to_bytes().to_vec())
}
pub(crate) async fn io(){
    let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();let seen=Arc::new(std::sync::Mutex::new(Vec::new()));let requests=seen.clone();
    let plain=tokio::spawn(async move{loop{let (stream,_)=listener.accept().await.unwrap();serve(stream,requests.clone()).await;}});
    let bus=telemetry::source().unwrap();bus.set_http_policy(http_policy::Policy::new(Some("first-agent"),true).unwrap());
    let resolver=Arc::new(crate::app::dns::SystemResolver::new(false).unwrap());let client=new_http_client(resolver,None).unwrap();
    assert_eq!(get(&client,format!("http://{address}/one"),None).await.unwrap(),b"ok");
    assert_eq!(get(&client,format!("http://{address}/explicit"),Some("explicit-agent")).await.unwrap(),b"ok");
    bus.set_http_policy(http_policy::Policy::new(Some("second-agent"),true).unwrap());
    assert_eq!(get(&client,format!("http://{address}/changed"),None).await.unwrap(),b"ok");
    let rows=seen.lock().unwrap().clone();assert!(rows[0].to_ascii_lowercase().contains("user-agent: first-agent"));assert!(rows[1].to_ascii_lowercase().contains("user-agent: explicit-agent"));assert!(rows[2].to_ascii_lowercase().contains("user-agent: second-agent"));
    let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();
    let (cert,key)=resolve_server_cert_and_key(None,None,"policy-test").unwrap();let config=rustls::ServerConfig::builder().with_no_client_auth().with_single_cert(cert,key).unwrap();let acceptor=tokio_rustls::TlsAcceptor::from(Arc::new(config));let requests=seen.clone();
    let tls=tokio::spawn(async move{loop{let (stream,_)=listener.accept().await.unwrap();if let Ok(stream)=acceptor.accept(stream).await{serve(stream,requests.clone()).await;}}});
    assert!(get(&client,format!("https://{address}/secure"),None).await.is_err());
    bus.set_http_policy(http_policy::Policy::new(Some("tls-relaxed"),false).unwrap());
    assert_eq!(get(&client,format!("https://{address}/relaxed"),None).await.unwrap(),b"ok");
    assert!(seen.lock().unwrap().last().unwrap().to_ascii_lowercase().contains("user-agent: tls-relaxed"));
    // Node TLS builder remains secure even while application HTTP checking is off.
    let node=build_tls_client_config(Arc::new(DefaultTlsVerifier::new(None,false)),None,None).unwrap();
    let stream=TcpStream::connect(address).await.unwrap();let name=rustls::pki_types::ServerName::try_from("localhost".to_owned()).unwrap();assert!(tokio_rustls::TlsConnector::from(Arc::new(node)).connect(name,stream).await.is_err());
    bus.set_http_policy(http_policy::Policy::new(Some("secure-again"),true).unwrap());
    assert!(get(&client,format!("https://{address}/secure-again"),None).await.is_err());
    plain.abort();tls.abort();let _=plain.await;let _=tls.await;
}
pub(crate) async fn typed(){
    let config=parse("global-ua: AEROVE-test/1\ncheck-certificate: false\n").unwrap();assert_eq!(config.general.global_ua.as_deref(),Some("AEROVE-test/1"));assert!(!config.general.check_certificate);
    assert!(parse("check-certificate: broken").is_err());assert!(parse("global-ua: ''").is_err());assert!(parse("global-ua: \"bad\\r\\nInjected: yes\"").is_err());
    assert!(parse("{}").unwrap().general.check_certificate);
    let data=parse(&format!("global-ua: '{}'", "x".repeat(4097))).err().unwrap().to_string();assert!(data.contains("global-ua")&&!data.contains(&"x".repeat(32)));
}
