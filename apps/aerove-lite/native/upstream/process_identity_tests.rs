use super::process_identity::*;
use std::sync::atomic::{AtomicUsize, Ordering};
use tokio_util::sync::CancellationToken;
fn fixture(mode: FindProcessMode) -> (std::sync::Arc<Owner>, std::sync::Arc<AtomicUsize>, CancellationToken) {
    let calls = std::sync::Arc::new(AtomicUsize::new(0)); let count = calls.clone();
    let cancel = CancellationToken::new();
    let owner = Owner::new(Some(std::sync::Arc::new(move |protocol, source, dest| {
        assert!(protocol == 6 || protocol == 17); assert_eq!(source.port(), 12000); assert_eq!(dest.port(), 443);
        count.fetch_add(1, Ordering::SeqCst);
        Ok(Identity { uid: 10042, process: "dev.fixture".into(), process_path: String::new() })
    })), cancel.clone(), mode); (owner, calls, cancel)
}
pub fn strict_once_and_original_endpoints() {
    let (owner,calls,_) = fixture(FindProcessMode::Strict);
    let flow = owner.bind(6,"10.0.0.2:12000".parse().unwrap(),"1.1.1.1:443".parse().unwrap());
    flow.prefetch(); assert_eq!(calls.load(Ordering::SeqCst),0);
    assert_eq!(flow.identity().unwrap().uid,10042); flow.identity(); assert_eq!(calls.load(Ordering::SeqCst),1);
}
pub fn udp_reuse_generation_and_off() {
    let (owner,calls,cancel) = fixture(FindProcessMode::Always);
    let bind = || owner.bind(17,"10.0.0.2:12000".parse().unwrap(),"1.1.1.1:443".parse().unwrap());
    let first=bind(); first.prefetch(); bind().prefetch(); assert_eq!(calls.load(Ordering::SeqCst),1);
    owner.set_mode(FindProcessMode::Off); assert!(first.identity().is_none());
    owner.set_mode(FindProcessMode::Always);
    assert!(first.identity().is_some()); // Existing flow retains its original attribution.
    bind().prefetch(); assert_eq!(calls.load(Ordering::SeqCst),2);
    cancel.cancel(); assert!(bind().identity().is_none()); assert_eq!(calls.load(Ordering::SeqCst),2);
}
pub fn actual_mode_change_invalidates_pending_and_cached_udp() {
    let calls=std::sync::Arc::new(AtomicUsize::new(0)); let count=calls.clone();
    let owner=Owner::new(Some(std::sync::Arc::new(move |_,_,_| {
        let uid=10000+count.fetch_add(1,Ordering::SeqCst) as u32;
        Ok(Identity{uid,process:format!("uid.{uid}"),process_path:String::new()})
    })),CancellationToken::new(),FindProcessMode::Always);
    let bind=|| owner.bind(17,"10.0.0.2:12000".parse().unwrap(),"1.1.1.1:443".parse().unwrap());
    let historical=bind(); assert_eq!(historical.identity().unwrap().uid,10000);
    owner.set_mode(FindProcessMode::Off);
    let pending_off=bind(); assert!(pending_off.identity().is_none());
    assert_eq!(historical.known().unwrap().uid,10000);
    assert_eq!(calls.load(Ordering::SeqCst),1);
    owner.set_mode(FindProcessMode::Always);
    assert!(pending_off.identity().is_none());
    let current=bind(); assert!(!std::sync::Arc::ptr_eq(&current,&historical));
    assert_eq!(current.identity().unwrap().uid,10001);
    assert_eq!(historical.known().unwrap().uid,10000);
    assert_eq!(historical.identity().unwrap().uid,10000);
    assert_eq!(calls.load(Ordering::SeqCst),2);
}
pub fn mode_round_trip_rejects_inflight_receipt() {
    let slot=std::sync::Arc::new(std::sync::Mutex::new(std::sync::Weak::<Owner>::new()));
    let callback_slot=slot.clone(); let calls=std::sync::Arc::new(AtomicUsize::new(0)); let count=calls.clone();
    let owner=Owner::new(Some(std::sync::Arc::new(move |_,_,_| {
        let n=count.fetch_add(1,Ordering::SeqCst);
        if n==0 { let owner=callback_slot.lock().unwrap().upgrade().unwrap(); owner.set_mode(FindProcessMode::Off); owner.set_mode(FindProcessMode::Always); }
        Ok(Identity{uid:10000+n as u32,process:"fixture".into(),process_path:String::new()})
    })),CancellationToken::new(),FindProcessMode::Always);
    *slot.lock().unwrap()=std::sync::Arc::downgrade(&owner);
    let bind=|| owner.bind(17,"10.0.0.2:12000".parse().unwrap(),"1.1.1.1:443".parse().unwrap());
    let old=bind(); assert!(old.identity().is_none()); assert!(old.known().is_none());
    assert_eq!(bind().identity().unwrap().uid,10001); assert_eq!(calls.load(Ordering::SeqCst),2);
}
pub fn same_mode_preserves_udp_and_pending_lookup() {
    let (owner,calls,_)=fixture(FindProcessMode::Strict);
    let first=owner.bind(17,"10.0.0.2:12000".parse().unwrap(),"1.1.1.1:443".parse().unwrap());
    owner.set_mode(FindProcessMode::Strict);
    let second=owner.bind(17,"10.0.0.2:12000".parse().unwrap(),"1.1.1.1:443".parse().unwrap());
    assert!(std::sync::Arc::ptr_eq(&first,&second));
    assert_eq!(second.identity().unwrap().uid,10042); owner.set_mode(FindProcessMode::Strict);
    assert_eq!(first.identity().unwrap().uid,10042); assert_eq!(calls.load(Ordering::SeqCst),1);
}
pub fn unknown_is_not_root_and_parse_is_strict() {
    let owner=Owner::new(None,CancellationToken::new(),FindProcessMode::Always);
    assert!(owner.bind(6,"10.0.0.2:12000".parse().unwrap(),"1.1.1.1:443".parse().unwrap()).identity().is_none());
    assert!(FindProcessMode::parse("default").is_err()); assert_eq!(FindProcessMode::parse("STRICT").unwrap(),FindProcessMode::Strict);
}
pub fn late_cancelled_receipt_is_discarded_and_owner_is_not_retained() {
    let cancel=CancellationToken::new(); let during=cancel.clone();
    let owner=Owner::new(Some(std::sync::Arc::new(move |_,_,_| {
        during.cancel(); Ok(Identity {uid:10042,process:"stale".into(),process_path:String::new()})
    })),cancel,FindProcessMode::Strict);
    let flow=owner.bind(6,"10.0.0.2:12000".parse().unwrap(),"1.1.1.1:443".parse().unwrap());
    assert!(flow.identity().is_none());
    let weak=std::sync::Arc::downgrade(&owner); drop(owner); assert!(weak.upgrade().is_none());
    assert!(flow.identity().is_none());
}
pub fn saturated_udp_cache_does_not_call_java_per_packet() {
    let (owner,calls,_)=fixture(FindProcessMode::Always);
    for port in 1..=4096 { owner.bind(17,"10.0.0.2:12000".parse().unwrap(),format!("1.1.1.1:{port}").parse().unwrap()); }
    for _ in 0..100 { assert!(owner.bind(17,"10.0.0.2:12000".parse().unwrap(),"2.2.2.2:443".parse().unwrap()).identity().is_none()); }
    assert_eq!(calls.load(Ordering::SeqCst),0);
}
