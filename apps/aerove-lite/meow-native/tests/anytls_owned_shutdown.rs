#![cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
use aerove_meow_core::transport_tasks::TransportTasks;
use anytls_rs::{client::SessionPool, padding::PaddingFactory, session::Session};
use meow_common::{scoped_join::try_spawn, transport_tasks::{SharedUnitJoin, TransportScope, TransportTaskContext, Work}};
use std::{io, pin::Pin, sync::{Arc, Mutex, atomic::{AtomicBool, AtomicUsize, Ordering}}, task::{Context, Poll, Waker}};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite};

struct OwnedContext(Arc<TransportTasks>);
impl TransportTaskContext for OwnedContext {
    fn spawn(&self, work: Work) -> io::Result<tokio::task::AbortHandle> { self.spawn_join(work).map(|task| task.abort_handle()) }
    fn spawn_join(&self, work: Work) -> io::Result<Arc<SharedUnitJoin>> {
        self.0.spawn_join_with(work, |work| Ok(tokio::spawn(work)))
    }
}
#[derive(Default)]
struct Gate { allowed: AtomicBool, entered: tokio::sync::Notify, waiting: Mutex<Option<Waker>> }
impl Gate {
    fn release(&self) {
        self.allowed.store(true, Ordering::Release);
        if let Some(waker) = self.waiting.lock().unwrap().take() { waker.wake(); }
    }
}
struct Reader { inner: tokio::net::tcp::OwnedReadHalf, dropped: Arc<AtomicUsize> }
impl AsyncRead for Reader {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut tokio::io::ReadBuf<'_>) -> Poll<io::Result<()>> { Pin::new(&mut self.inner).poll_read(cx, buf) }
}
impl Drop for Reader { fn drop(&mut self) { self.dropped.fetch_add(1, Ordering::SeqCst); } }
struct Writer { inner: tokio::net::tcp::OwnedWriteHalf, gate: Arc<Gate>, dropped: Arc<AtomicUsize> }
impl AsyncWrite for Writer {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, bytes: &[u8]) -> Poll<io::Result<usize>> { Pin::new(&mut self.inner).poll_write(cx, bytes) }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> { Pin::new(&mut self.inner).poll_flush(cx) }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        if !self.gate.allowed.load(Ordering::Acquire) {
            *self.gate.waiting.lock().unwrap() = Some(cx.waker().clone());
            self.gate.entered.notify_one();
            if !self.gate.allowed.load(Ordering::Acquire) { return Poll::Pending; }
        }
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}
impl Drop for Writer { fn drop(&mut self) { self.dropped.fetch_add(1, Ordering::SeqCst); } }

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn actual_native_join_then_retry_closes_pooled_peer_and_final_drop_releases_transport() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let socket = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
    let (mut peer, _) = listener.accept().await.unwrap();
    let (read, write) = socket.into_split();
    let owner = TransportTasks::new();
    let scope = TransportScope::new(Arc::new(OwnedContext(owner.clone())));
    let gate = Arc::new(Gate::default());
    let dropped = Arc::new(AtomicUsize::new(0));
    let session = Arc::new(Session::new_client(Reader { inner: read, dropped: dropped.clone() },
        Writer { inner: write, gate: gate.clone(), dropped: dropped.clone() }, PaddingFactory::default().into_shared(), None));
    session.set_seq(1);
    let pool = scope.scope(async { Arc::new(SessionPool::new()) }).await;
    pool.stop_cleanup_task().await;
    pool.add_idle_session(session.clone()).await;
    scope.scope(session.clone().start_client()).await.unwrap();
    let closing = session.clone();
    let task = scope.scope(async { try_spawn(async move { closing.close().await }).unwrap() }).await;
    gate.entered.notified().await;
    assert!(session.is_closed()); assert_eq!(pool.idle_count().await, 1);
    owner.cancel();
    owner.close_joined().await.unwrap();
    assert!(task.await.unwrap_err().is_cancelled());
    assert_eq!(dropped.load(Ordering::SeqCst), 0);
    gate.release();
    session.close().await.unwrap();
    let mut settings = Vec::new();
    tokio::time::timeout(std::time::Duration::from_secs(1), peer.read_to_end(&mut settings)).await.unwrap().unwrap();
    let weak = Arc::downgrade(&session); drop(session);
    assert!(weak.upgrade().is_some()); drop(pool);
    assert!(weak.upgrade().is_none()); assert_eq!(dropped.load(Ordering::SeqCst), 2);
    owner.close_joined().await.unwrap();
}
