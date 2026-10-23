use super::*;
use std::os::{fd::AsRawFd, unix::net::UnixStream};

fn ready(mode: RuntimeMode, tun_ready: bool, user_listeners_ready: bool) -> ReadyInfo {
    ReadyInfo { address: "127.0.0.1:19000".parse().unwrap(), runtime_mode: mode, tun_ready, user_listeners_ready }
}

#[test]
fn explicit_mode_admission_rejects_mismatched_descriptors_and_callbacks_without_closing_caller() {
    let (original, _) = UnixStream::pair().unwrap();
    let fd = original.as_raw_fd();
    assert_eq!(admit_runtime("tun", fd, true).unwrap(), RuntimeMode::Tun);
    assert_eq!(admit_runtime("proxy", -1, false).unwrap(), RuntimeMode::Proxy);
    for (mode, fd, callback, field) in [("TUN", fd, true, "runtimeMode"), ("", -1, false, "runtimeMode"),
        ("proxy", fd, false, "tunFd"), ("proxy", -2, false, "tunFd"), ("proxy", -1, true, "protector"),
        ("tun", -1, true, "tunFd"), ("tun", fd, false, "protector")] {
        assert!(admit_runtime(mode, fd, callback).unwrap_err().to_string().contains(field));
    }
    assert_ne!(unsafe { libc::fcntl(fd, libc::F_GETFD) }, -1);
}

#[test]
fn session_requires_actual_mode_specific_readiness_and_clears_metadata_on_exit() {
    let proxy = Session::new(RuntimeMode::Proxy, false);
    proxy.ready(ready(RuntimeMode::Tun, true, true));
    proxy.ready(ready(RuntimeMode::Proxy, false, false));
    proxy.ready(ready(RuntimeMode::Proxy, true, true));
    assert_eq!(proxy.snapshot.lock().unwrap().state, State::Starting);
    proxy.ready(ready(RuntimeMode::Proxy, false, true));
    assert_eq!(proxy.snapshot.lock().unwrap().state, State::Running);
    proxy.finished(Err(anyhow!("fixture failure")));
    let snapshot = proxy.snapshot.lock().unwrap();
    assert_eq!(snapshot.state, State::Failed);
    assert!(snapshot.api_address.is_none());
    assert!(snapshot.runtime_mode.is_none());
    assert!(!snapshot.tun_ready && !snapshot.user_listeners_ready);
    drop(snapshot);
    let tun = Session::new(RuntimeMode::Tun, false);
    tun.ready(ready(RuntimeMode::Tun, false, true));
    assert_eq!(tun.snapshot.lock().unwrap().state, State::Starting);
    tun.cancel.cancel();
    tun.ready(ready(RuntimeMode::Tun, true, true));
    assert_eq!(tun.snapshot.lock().unwrap().state, State::Starting);
    let exited = serde_json::to_value(Snapshot::exited()).unwrap();
    assert!(exited["runtimeMode"].is_null());
    assert_eq!(exited["tunReady"], false);
    assert_eq!(exited["userListenersReady"], false);
}

#[test]
fn proxy_full_runtime_binds_api_and_forwards_http_dns_and_udp_without_tun() {
    clash_lib::mobile::outbound::regression::run("runtime-proxy-io");
}

#[test]
fn runtime_mode_yaml_mismatch_and_real_bind_failure_never_report_ready() {
    clash_lib::mobile::outbound::regression::run("runtime-mode-failure");
}

#[test]
fn proxy_full_runtime_cancel_and_successor_release_actual_sockets_and_context() {
    clash_lib::mobile::outbound::regression::run("runtime-proxy-cancel");
}

#[test]
fn proxy_registry_requires_join_before_successor_and_stale_ready_cannot_revive_old_handle() {
    clash_lib::mobile::outbound::regression::with_runtime_lock(|| {
        let sockets: Vec<_> = (20000..49151).filter_map(|port| std::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, port)).ok())
            .take(2).collect();
        assert_eq!(sockets.len(), 2);
        let ports: Vec<_> = sockets.iter().map(|socket| socket.local_addr().unwrap().port()).collect();
        drop(sockets);
        let unique = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let directory = std::env::temp_dir().join(format!("aerove-proxy-registry-{}-{unique}", std::process::id()));
        std::fs::create_dir(&directory).unwrap();
        let path = directory.join("runtime.yaml");
        std::fs::write(&path, format!("mode: direct\nmmdb: null\nasn-mmdb: null\ngeosite: null\ntun: {{enable: false}}\ndns: {{enable: false}}\nmixed-port: {}\nallow-lan: false\nbind-address: 127.0.0.1\nexternal-controller: 127.0.0.1:{}\nsecret: '0123456789abcdef0123456789abcdef'\ncors-allow-origins: []\n", ports[0], ports[1])).unwrap();
        struct Owned(i64);
        impl Drop for Owned { fn drop(&mut self) { let _ = stop(self.0); } }
        let first = Owned(start_runtime(path.clone(), directory.clone(), RuntimeInput::Proxy).unwrap());
        await_ready(first.0, Duration::from_secs(5)).unwrap();
        let running = snapshot(first.0).unwrap();
        assert_eq!(running.state, State::Running);
        assert_eq!(running.runtime_mode, Some(RuntimeMode::Proxy));
        assert!(!running.tun_ready && running.user_listeners_ready);
        assert!(start_runtime(path.clone(), directory.clone(), RuntimeInput::Proxy).is_err());
        let old = session(first.0).unwrap().unwrap();
        stop(first.0).unwrap();
        old.ready(ready(RuntimeMode::Proxy, false, true));
        assert_eq!(old.snapshot.lock().unwrap().state, State::Exited);
        assert!(snapshot(first.0).unwrap().runtime_mode.is_none());
        let next = Owned(start_runtime(path, directory, RuntimeInput::Proxy).unwrap());
        assert!(next.0 > first.0);
        await_ready(next.0, Duration::from_secs(5)).unwrap();
        stop(first.0).unwrap();
        assert_eq!(snapshot(next.0).unwrap().state, State::Running);
        stop(next.0).unwrap();
        for port in ports { assert!(std::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, port)).is_ok()); }
    });
}
