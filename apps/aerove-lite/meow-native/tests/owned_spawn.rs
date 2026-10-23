use meow_config::owned_load::{LoadContext, OwnedLoadScope, OwnedWork};
use std::{sync::{Arc, atomic::{AtomicUsize, Ordering}}, time::Duration};

tokio::task_local! { static CAPTURED: usize; }
struct Context(usize);
impl LoadContext for Context {
    fn wrap(&self, work: OwnedWork) -> OwnedWork {
        let value = self.0;
        Box::pin(CAPTURED.scope(value, work))
    }
    fn run_blocking(&self, work: Box<dyn FnOnce() + Send>) { work(); }
}
struct Finished(Arc<AtomicUsize>);
impl Drop for Finished { fn drop(&mut self) { self.0.fetch_add(1, Ordering::SeqCst); } }

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn explicit_spawn_captures_its_owner_even_from_other_scope_and_close_joins_drop() {
    let first = OwnedLoadScope::with_context(Arc::new(Context(11)));
    let other = OwnedLoadScope::with_context(Arc::new(Context(22)));
    let ended = Arc::new(AtomicUsize::new(0));
    let (started, began) = tokio::sync::oneshot::channel();
    let owned = first.clone(); let expected = first.clone(); let ended_task = ended.clone();
    let task = other.run(async move {
        owned.spawn_owned(async move {
            let _finished = Finished(ended_task);
            assert_eq!(CAPTURED.with(|value| *value), 11);
            assert!(Arc::ptr_eq(&OwnedLoadScope::current().unwrap(), &expected));
            started.send(()).unwrap();
            std::future::pending::<()>().await;
        })
    }).await.unwrap();
    tokio::time::timeout(Duration::from_secs(5), began).await.unwrap().unwrap();
    first.close().await.unwrap();
    assert_eq!(ended.load(Ordering::SeqCst), 1);
    assert!(task.await.unwrap_err().is_cancelled());
    assert!(!other.is_cancelled()); other.close().await.unwrap();
}

#[tokio::test]
async fn rejected_spawn_never_polls_and_drops_input_before_returning_error() {
    let scope = OwnedLoadScope::new(); scope.close().await.unwrap();
    let ended = Arc::new(AtomicUsize::new(0)); let guard = Finished(ended.clone());
    let result = scope.spawn_owned(async move { let _guard = guard; panic!("closed work polled"); });
    assert_eq!(result.unwrap_err(), "ownedLoadScopeClosed");
    assert_eq!(ended.load(Ordering::SeqCst), 1);
}
