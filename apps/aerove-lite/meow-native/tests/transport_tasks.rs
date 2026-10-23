#![cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
use aerove_meow_core::transport_tasks::TransportTasks;
use futures::poll;
use std::{future::pending, sync::{Arc, Condvar, Mutex, atomic::{AtomicUsize, Ordering}}};
use tokio::sync::oneshot;

struct HeldDrop { entered: Option<oneshot::Sender<()>>, release: Arc<(Mutex<bool>, Condvar)> }
impl Drop for HeldDrop {
    fn drop(&mut self) {
        let _ = self.entered.take().unwrap().send(());
        let (gate, changed) = &*self.release;
        let mut released = gate.lock().unwrap();
        while !*released { released = changed.wait(released).unwrap(); }
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn cancelled_close_waiter_keeps_handle_until_actual_destructor_finishes() {
    let owner = TransportTasks::new();
    let release = Arc::new((Mutex::new(false), Condvar::new()));
    let (entered, dropped) = oneshot::channel();
    let (began, started) = oneshot::channel();
    let guard = HeldDrop { entered: Some(entered), release: release.clone() };
    owner.spawn_with(async move { let _guard = guard; let _ = began.send(()); pending::<()>().await }, |work| Ok(tokio::spawn(work))).unwrap();
    started.await.unwrap(); owner.cancel(); dropped.await.unwrap();
    let mut first = Box::pin(owner.close());
    assert!(poll!(&mut first).is_pending()); drop(first);
    let mut second = Box::pin(owner.close());
    assert!(poll!(&mut second).is_pending());
    { let (gate, changed) = &*release; *gate.lock().unwrap() = true; changed.notify_all(); }
    second.await.unwrap(); owner.close().await.unwrap();
}

#[tokio::test]
async fn closed_owner_never_spawns_or_polls_work_and_reject_drop_is_outside_gate() {
    struct Reenter(Arc<TransportTasks>);
    impl Drop for Reenter { fn drop(&mut self) { self.0.cancel(); } }
    let owner = TransportTasks::new(); owner.close().await.unwrap();
    let polled = Arc::new(AtomicUsize::new(0)); let count = polled.clone();
    let guard = Reenter(owner.clone());
    let result = owner.spawn_with(async move { let _guard = guard; count.fetch_add(1, Ordering::Relaxed); }, |_| panic!("closed owner invoked spawn"));
    assert!(result.is_err()); assert_eq!(polled.load(Ordering::Relaxed), 0);
}

#[tokio::test]
async fn completed_child_panic_remains_sticky_after_reaping_and_repeat_close() {
    let owner = TransportTasks::new();
    let abort = owner.spawn_with(async { panic!("owned transport fixture panic") }, |work| Ok(tokio::spawn(work))).unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        while !abort.is_finished() { tokio::task::yield_now().await; }
    }).await.unwrap();
    owner.spawn_with(async {}, |work| Ok(tokio::spawn(work))).unwrap();
    assert_eq!(owner.close().await.unwrap_err().to_string(), "transportTaskPanicked");
    assert_eq!(owner.close().await.unwrap_err().to_string(), "transportTaskPanicked");
}
