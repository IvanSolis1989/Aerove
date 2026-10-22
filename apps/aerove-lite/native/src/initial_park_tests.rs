#[test]
fn initially_parked_proxy_has_only_private_api_then_first_resume_binds_real_listeners(){clash_lib::mobile::outbound::regression::run("initial-park-proxy");}
#[test]
fn initially_parked_tun_requires_no_descriptor_or_protect_and_stop_cannot_resurrect_it(){clash_lib::mobile::outbound::regression::run("initial-park-tun");}
#[test]
fn registry_initially_parked_tun_exposes_core_ready_without_tun_or_user_bind(){
    use super::*;
    clash_lib::mobile::outbound::regression::with_runtime_lock(||{
        assert!(admit_parked("tun",true).is_ok());assert!(admit_parked("proxy",false).is_ok());assert!(admit_parked("tun",false).is_err());assert!(admit_parked("proxy",true).is_err());assert!(admit_parked("unknown",false).is_err());
        let mut held:Vec<_>=(30000..49000).filter_map(|p|std::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST,p)).ok()).take(2).collect();let ports:Vec<_>=held.iter().map(|s|s.local_addr().unwrap().port()).collect();drop(held.pop());
        let directory=std::env::temp_dir().join(format!("aerove-initial-park-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));std::fs::create_dir(&directory).unwrap();let path=directory.join("runtime.yaml");std::fs::write(&path,format!("mode: direct\nmmdb: null\nasn-mmdb: null\ngeosite: null\ntun: {{enable: true, dns-hijack: false}}\ndns: {{enable: false}}\nmixed-port: {}\nallow-lan: false\nbind-address: 127.0.0.1\nexternal-controller: 127.0.0.1:{}\nsecret: '0123456789abcdef0123456789abcdef'\ncors-allow-origins: []\n",ports[0],ports[1])).unwrap();
        let handle=start_runtime(path,directory.clone(),RuntimeInput::ParkedTun {protector:Arc::new(|_|panic!("initial parked runtime unexpectedly protected a socket"))}).unwrap();struct Cleanup(i64);impl Drop for Cleanup{fn drop(&mut self){let _=stop(self.0);}}let _cleanup=Cleanup(handle);
        await_ready(handle,Duration::from_secs(5)).unwrap();let state=snapshot(handle).unwrap();assert_eq!(state.state,State::Running);assert!(state.core_ready);assert_eq!(state.runtime_mode,Some(RuntimeMode::Tun));assert!(!state.tun_ready&&!state.listening&&!state.user_listeners_ready);let read=listener_command(handle,"read",-1,false).unwrap();assert!(read.active_ports.is_empty());
        stop(handle).unwrap();let state=snapshot(handle).unwrap();assert_eq!(state.state,State::Exited);assert!(!state.core_ready&&state.runtime_mode.is_none());assert!(listener_command(handle,"resumeProxy",-1,false).is_err());let _=std::fs::remove_dir_all(directory);
    });
}

#[test]
fn cancelled_initial_park_start_never_binds_or_emits_ready(){clash_lib::mobile::outbound::regression::run("initial-park-cancel");}
