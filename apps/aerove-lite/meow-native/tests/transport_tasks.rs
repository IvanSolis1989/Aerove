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

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn shared_record_cancelled_waiter_retries_same_work_and_native_join_is_sticky() {
    let owner = TransportTasks::new();
    let release = Arc::new((Mutex::new(false), Condvar::new()));
    let (entered, dropped) = oneshot::channel();
    let (began, started) = oneshot::channel();
    let guard = HeldDrop { entered: Some(entered), release: release.clone() };
    let record = owner.spawn_join_with(async move {
        let _guard = guard; let _ = began.send(()); pending::<()>().await
    }, |work| Ok(tokio::spawn(work))).unwrap();
    started.await.unwrap(); record.abort(); dropped.await.unwrap();
    let mut protocol_wait = Box::pin(record.wait());
    assert!(poll!(&mut protocol_wait).is_pending()); drop(protocol_wait);
    let mut native_wait = Box::pin(owner.close_joined());
    assert!(poll!(&mut native_wait).is_pending());
    { let (gate, changed) = &*release; *gate.lock().unwrap() = true; changed.notify_all(); }
    assert!(!native_wait.await.unwrap().panicked);
    let outcome = record.wait().await;
    assert!(outcome.cancelled); assert!(!outcome.panicked); assert!(record.is_finished());
    assert_eq!(record.wait().await, outcome);
    assert!(!owner.close_joined().await.unwrap().panicked);
}

#[tokio::test]
async fn actual_join_confirms_cleanup_without_erasing_child_panic() {
    let owner = TransportTasks::new();
    let record = owner.spawn_join_with(async { panic!("shared owned task fixture panic") }, |work| Ok(tokio::spawn(work))).unwrap();
    assert!(record.wait().await.panicked);
    assert!(owner.close_joined().await.unwrap().panicked);
    assert!(owner.close_joined().await.unwrap().panicked);
    assert_eq!(owner.close().await.unwrap_err().to_string(), "transportTaskPanicked");
    let count = Arc::new(AtomicUsize::new(0)); let polled = count.clone();
    assert!(owner.spawn_join_with(async move { polled.fetch_add(1, Ordering::Relaxed); }, |_| panic!("closed owner spawned")).is_err());
    assert_eq!(count.load(Ordering::Relaxed), 0);
}

#[tokio::test]
async fn child_cleanup_reports_joined_failure_to_session_without_closing_successor_admission() {
    let session = TransportTasks::new(); let active = session.child();
    let record = active.spawn_join_with(async { panic!("active transport fixture panic") }, |work| Ok(tokio::spawn(work))).unwrap();
    assert!(record.wait().await.panicked);
    assert!(active.close_joined().await.unwrap().panicked);
    assert!(session.panicked());
    let successor = session.child();
    successor.spawn_with(async {}, |work| Ok(tokio::spawn(work))).unwrap();
    assert!(!successor.close_joined().await.unwrap().panicked);
    assert!(session.panicked());
    assert!(session.close_joined().await.is_ok());
    assert!(session.panicked());
}

#[tokio::test]
async fn scope_business_panic_still_has_real_native_join_and_preserved_failure() {
    let owner = TransportTasks::new(); let scope = meow_config::owned_load::OwnedLoadScope::new();
    let record = owner.spawn_join_with(async { panic!("scoped transport fixture panic") }, |work| scope.spawn_owned(work).map_err(std::io::Error::other)).unwrap();
    assert!(record.wait().await.panicked);
    assert!(owner.close_joined().await.unwrap().panicked);
    assert_eq!(scope.close().await, Err("providerThreadPanicked"));
    assert!(owner.panicked());
}
