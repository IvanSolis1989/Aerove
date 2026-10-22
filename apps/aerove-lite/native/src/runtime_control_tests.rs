#[test]
fn parked_proxy_keeps_one_core_and_api_releases_tcp_udp_and_resumes_real_binds() {
    clash_lib::mobile::outbound::regression::run("control-io");
}
#[test]
fn failed_resume_bind_fails_and_joins_the_existing_owner() {
    clash_lib::mobile::outbound::regression::run("control-failure");
}

#[test]
fn registry_park_keeps_handle_and_rejects_wrong_mode_fd_without_closing_caller() {
    use super::*;
    use std::os::{fd::AsRawFd,unix::net::UnixStream};
    clash_lib::mobile::outbound::regression::with_runtime_lock(|| {
        let held:Vec<_>=(24000..49151).filter_map(|p|std::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST,p)).ok()).take(2).collect();
        let ports:Vec<_>=held.iter().map(|s|s.local_addr().unwrap().port()).collect();drop(held);
        let stamp=std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let directory=std::env::temp_dir().join(format!("aerove-control-{}-{stamp}",std::process::id()));std::fs::create_dir(&directory).unwrap();let path=directory.join("runtime.yaml");
        std::fs::write(&path,format!("mode: direct\nmmdb: null\nasn-mmdb: null\ngeosite: null\ntun: {{enable: false}}\ndns: {{enable: false}}\nmixed-port: {}\nallow-lan: false\nbind-address: 127.0.0.1\nexternal-controller: 127.0.0.1:{}\nsecret: '0123456789abcdef0123456789abcdef'\ncors-allow-origins: []\n",ports[0],ports[1])).unwrap();
        let handle=start_runtime(path,directory.clone(),RuntimeInput::Proxy).unwrap();
        struct Cleanup(i64);impl Drop for Cleanup{fn drop(&mut self){let _=stop(self.0);}}
        let _cleanup=Cleanup(handle);await_ready(handle,Duration::from_secs(5)).unwrap();let before=snapshot(handle).unwrap();assert!(before.listening);
        let parked=listener_command(handle,"park",-1,false).unwrap();assert!(!parked.listening);let paused=snapshot(handle).unwrap();assert_eq!(paused.state,State::Running);assert_eq!(paused.api_address,before.api_address);assert!(!paused.tun_ready&&!paused.user_listeners_ready&&!paused.listening);
        let (caller,_)=UnixStream::pair().unwrap();let fd=caller.as_raw_fd();assert!(listener_command(handle,"attachTun",fd,false).is_err());assert_ne!(unsafe{libc::fcntl(fd,libc::F_GETFD)},-1);
        listener_command(handle,"resumeProxy",-1,false).unwrap();let resumed=snapshot(handle).unwrap();assert!(resumed.listening&&resumed.user_listeners_ready&&!resumed.tun_ready);assert_eq!(resumed.api_address,before.api_address);
        stop(handle).unwrap();assert!(!snapshot(handle).unwrap().listening);let _=std::fs::remove_dir_all(directory);
    });
}
