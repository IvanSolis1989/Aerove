use aerove_meow_core::api::CoreApi;
use axum::Router;
use meow_config::owned_load::{LoadContext, OwnedLoadScope, OwnedWork};
use meow_tunnel::Tunnel;
use std::sync::{Arc, Mutex, atomic::{AtomicU64, Ordering}};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};
use tokio_util::sync::CancellationToken;

struct Context { generation: AtomicU64, snapshots: Mutex<Vec<u64>> }
impl LoadContext for Context {
    fn wrap(&self,work:OwnedWork)->OwnedWork {
        self.snapshots.lock().unwrap().push(self.generation.load(Ordering::SeqCst));work
    }
    fn run_blocking(&self,work:Box<dyn FnOnce()+Send>){work();}
}
async fn version(socket:&mut TcpStream) {
    socket.write_all(b"GET /version HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer per-request-user-secret\r\n\r\n").await.unwrap();
    let mut header=Vec::new();while !header.ends_with(b"\r\n\r\n"){header.push(socket.read_u8().await.unwrap());assert!(header.len()<8192);}
    let header=String::from_utf8(header).unwrap();assert!(header.starts_with("HTTP/1.1 200"),"{header}");
    let size=header.lines().find_map(|line|line.to_ascii_lowercase().strip_prefix("content-length: ").map(|s|s.parse::<usize>().unwrap())).unwrap();
    let mut body=vec![0;size];socket.read_exact(&mut body).await.unwrap();assert!(serde_json::from_slice::<serde_json::Value>(&body).unwrap().is_object());
}

#[tokio::test(flavor="multi_thread",worker_threads=2)]
async fn user_keepalive_captures_context_for_each_real_http_request_and_joins() {
    let scope=OwnedLoadScope::new();let config=meow_config::load_config_from_str_owned("rules: ['MATCH,DIRECT']\n",&scope).await.unwrap();
    let tunnel=Tunnel::new_with_slot(config.dns.resolver_slot.clone());
    let api=Arc::new(CoreApi::assemble_with_epoch(&config,tunnel.clone(),"127.0.0.1:12345".parse().unwrap(),
        "per-request-private-secret-00000001",None,Router::new(),321,CancellationToken::new()).unwrap());
    tunnel.update_routing(config.proxies,config.rules,config.dialer_registry);
    let context=Arc::new(Context{generation:AtomicU64::new(1),snapshots:Mutex::new(Vec::new())});
    let endpoint=Arc::new(api.user_endpoint("per-request-user-secret",context.clone()).unwrap());
    let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();
    let cancel=CancellationToken::new();let serving={let endpoint=endpoint.clone();let cancel=cancel.clone();tokio::spawn(async move{endpoint.serve(listener,cancel).await.unwrap();})};
    let mut socket=TcpStream::connect(address).await.unwrap();version(&mut socket).await;
    assert_eq!(*context.snapshots.lock().unwrap(),vec![1,1]);
    context.generation.store(2,Ordering::SeqCst);version(&mut socket).await;
    assert_eq!(*context.snapshots.lock().unwrap(),vec![1,1,2]);
    socket.shutdown().await.unwrap();cancel.cancel();endpoint.cancel();serving.await.unwrap();endpoint.join().await;
    api.cancel();scope.close().await.unwrap();api.join().await;
}
