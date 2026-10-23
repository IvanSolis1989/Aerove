mod script_engine;
mod script_jni;
use std::{
    io,
    os::fd::{BorrowedFd, OwnedFd},
    path::PathBuf,
    sync::{Arc, Condvar, Mutex, OnceLock},
    thread::JoinHandle,
    time::{Duration, Instant},
};

use anyhow::{Context, Result, anyhow, bail};
use jni::{
    EnvUnowned, JavaVM, JValue, errors::ThrowRuntimeExAndDefault,
    jni_sig, jni_str,
    objects::{JClass, JObject, JString},
    refs::Global,
};
use serde::Serialize;
use tokio_util::sync::CancellationToken;
use clash_lib::mobile::{ReadyInfo, RuntimeInput, RuntimeMode};

#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
enum State {
    Starting,
    Running,
    Failed,
    Exited,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Snapshot {
    state: State,
    error: Option<String>,
    api_address: Option<String>,
    runtime_mode: Option<RuntimeMode>,
    tun_ready: bool,
    user_listeners_ready: bool,
    listening: bool,
    core_ready:bool,
}

impl Snapshot {
    fn exited() -> Self {
        Self { state: State::Exited, error: None, api_address: None, runtime_mode: None,
            tun_ready: false, user_listeners_ready: false, listening:false,core_ready:false }
    }
}

struct Session {
    snapshot: Mutex<Snapshot>,
    control: Arc<clash_lib::mobile::runtime_control::RuntimeControl>,
    initially_parked:bool,
    changed: Condvar,
    cancel: CancellationToken,
    worker: Mutex<Option<JoinHandle<()>>>,
}

impl Session {
    fn new(mode: RuntimeMode,initially_parked:bool) -> Self {
        Self {
            control: clash_lib::mobile::runtime_control::RuntimeControl::new(),
            snapshot: Mutex::new(Snapshot {
                state: State::Starting, error: None, api_address: None,
                runtime_mode: Some(mode), tun_ready: false, user_listeners_ready: false, listening:false,core_ready:false,
            }),
            initially_parked,
            changed: Condvar::new(),
            cancel: CancellationToken::new(),
            worker: Mutex::new(None),
        }
    }

    fn ready(&self, info: ReadyInfo) {
        let mut state = self.snapshot.lock().unwrap();
        if state.state == State::Starting && !self.cancel.is_cancelled()
            && state.runtime_mode == Some(info.runtime_mode)
            && ((self.initially_parked&&!info.user_listeners_ready&&!info.tun_ready)
                ||(!self.initially_parked&&info.user_listeners_ready&&info.tun_ready==(info.runtime_mode==RuntimeMode::Tun))) {
            state.state = State::Running;
            state.api_address = Some(info.address.to_string());
            state.core_ready=true;
            state.tun_ready = info.tun_ready;
            state.user_listeners_ready = info.user_listeners_ready;
            state.listening = !self.initially_parked;
            self.changed.notify_all();
        }
    }

    fn finished(&self, result: Result<()>) {
        let mut state = self.snapshot.lock().unwrap();
        state.api_address = None;
        state.core_ready=false;
        state.runtime_mode = None;
        state.tun_ready = false;
        state.user_listeners_ready = false;
        state.listening = false;
        match result {
            Err(error) => {
                state.state = State::Failed;
                state.error = Some(format!("{error:#}"));
            }
            Ok(()) if self.cancel.is_cancelled() => state.state = State::Exited,
            Ok(()) => {
                state.state = State::Failed;
                state.error = Some("core exited without a stop request".into());
            }
        }
        self.changed.notify_all();
    }
}

struct Registry {
    next_handle: i64,
    active: Option<(i64, Arc<Session>)>,
}

static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();

fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry { next_handle: 1, active: None }))
}

fn session(handle: i64) -> Result<Option<Arc<Session>>> {
    let registry = registry().lock().unwrap();
    if handle <= 0 || handle >= registry.next_handle {
        bail!("invalid core handle {handle}");
    }
    Ok(registry.active.as_ref().filter(|(id, _)| *id == handle)
        .map(|(_, session)| session.clone()))
}

fn duplicate_tun(fd: i32) -> Result<OwnedFd> {
    if fd < 0 {
        bail!("invalid TUN descriptor");
    }
    // Borrowing never transfers or closes the caller's descriptor, including error paths.
    unsafe { BorrowedFd::borrow_raw(fd) }.try_clone_to_owned()
        .context("duplicate TUN descriptor")
}

fn admit_runtime(mode: &str, fd: i32, has_protector: bool) -> Result<RuntimeMode> {
    let mode = RuntimeMode::parse(mode)?;
    match mode {
        RuntimeMode::Tun if fd < 0 => bail!("tunFd: tun runtime requires a descriptor"),
        RuntimeMode::Tun if !has_protector => bail!("protector: tun runtime requires a VPN socket protector"),
        RuntimeMode::Proxy if fd != -1 => bail!("tunFd: proxy runtime requires -1 and owns no descriptor"),
        RuntimeMode::Proxy if has_protector => bail!("protector: proxy runtime requires null"),
        _ => Ok(mode),
    }
}

fn start(
    config_path: PathBuf,
    working_directory: PathBuf,
    tun: OwnedFd,
    protector: clash_lib::mobile::Protector,
) -> Result<i64> {
    start_runtime(config_path, working_directory, RuntimeInput::Tun { fd: tun, protector })
}

fn start_runtime(config_path: PathBuf, working_directory: PathBuf, input: RuntimeInput) -> Result<i64> {
    start_runtime_owned(config_path,working_directory,input,None)
}
fn start_runtime_owned(config_path:PathBuf,working_directory:PathBuf,input:RuntimeInput,owner:Option<clash_lib::mobile::process_identity::Resolver>)->Result<i64> {
    let mut registry = registry().lock().unwrap();
    if registry.active.is_some() {
        bail!("previous core must complete stop and join before start");
    }
    let handle = registry.next_handle;
    let next = handle.checked_add(1).ok_or_else(|| anyhow!("core handles exhausted"))?;
    let session = Arc::new(Session::new(input.mode(),input.initially_parked()));
    let worker_session = session.clone();
    let worker = std::thread::Builder::new().name(format!("aerove-core-{handle}"))
        .spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<()> {
                let source = std::fs::read_to_string(config_path).context("read runtime configuration")?;
                if !working_directory.is_dir() {
                    bail!("core working directory does not exist");
                }
                let runtime = tokio::runtime::Builder::new_multi_thread()
                    .worker_threads(2).enable_all().build().context("create core runtime")?;
                let result = runtime.block_on(clash_lib::mobile::run_runtime_identity(
                    source, working_directory, input,
                    worker_session.cancel.clone(), worker_session.control.clone(),owner,
                    |info| worker_session.ready(info),
                ));
                // Dropping the instance runtime joins its workers and closes detached connection tasks.
                drop(runtime);
                result.map_err(Into::into)
            }));
            let result = match result {
                Ok(result) => result,
                Err(payload) => {
                    let message = payload.downcast_ref::<String>().cloned()
                        .or_else(|| payload.downcast_ref::<&str>().map(|value| (*value).to_owned()))
                        .unwrap_or_else(|| "unknown Rust panic".into());
                    Err(anyhow!("core panic: {message}"))
                }
            };
            worker_session.finished(result);
        }).context("spawn core worker")?;
    *session.worker.lock().unwrap() = Some(worker);
    registry.next_handle = next;
    registry.active = Some((handle, session));
    Ok(handle)
}

fn snapshot(handle: i64) -> Result<Snapshot> {
    Ok(match session(handle)? {
        Some(session) => {
            let mut state=session.snapshot.lock().unwrap().clone();
            if state.state==State::Running && let Some(listeners)=session.control.cached() {
                state.tun_ready=listeners.tun_ready;state.user_listeners_ready=listeners.user_listeners_ready;state.listening=listeners.listening;
            }
            state
        },
        None => Snapshot::exited(),
    })
}

fn await_ready(handle: i64, timeout: Duration) -> Result<()> {
    let session = session(handle)?.ok_or_else(|| anyhow!("core handle has exited"))?;
    let deadline = Instant::now().checked_add(timeout).ok_or_else(|| anyhow!("invalid ready timeout"))?;
    let mut state = session.snapshot.lock().unwrap();
    loop {
        match state.state {
            State::Running => return Ok(()),
            State::Failed | State::Exited => bail!("{}", state.error.as_deref().unwrap_or("core has exited")),
            State::Starting => {}
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            bail!("core readiness timed out");
        }
        state = session.changed.wait_timeout(state, remaining).unwrap().0;
    }
}

fn stop(handle: i64) -> Result<()> {
    let Some(session) = session(handle)? else { return Ok(()) };
    session.cancel.cancel();
    let mut worker = session.worker.lock().unwrap();
    let Some(join) = worker.take() else {
        if session.snapshot.lock().unwrap().state == State::Exited {
            return Ok(());
        }
        bail!("core join was not confirmed; new starts remain blocked");
    };
    if join.join().is_err() {
        bail!("core worker join failed; new starts remain blocked");
    }
    {
        let mut registry = registry().lock().unwrap();
        if registry.active.as_ref().is_some_and(|(id, _)| *id == handle) {
            registry.active = None;
        }
    }
    session.snapshot.lock().unwrap().state = State::Exited;
    session.changed.notify_all();
    Ok(())
}

struct JavaProtector {
    vm: JavaVM,
    callback: Global<JObject<'static>>,
}

impl JavaProtector {
    fn protect(&self, fd: i32) -> io::Result<()> {
        let result: jni::errors::Result<bool> = self.vm.attach_current_thread(|env| {
            let result = env.call_method(&self.callback, jni_str!("protect"), jni_sig!("(I)Z"), &[JValue::Int(fd)])
                .and_then(|value| value.z());
            if env.exception_check() {
                env.exception_clear();
                return Err(jni::errors::Error::JavaException);
            }
            result
        });
        match result {
            Ok(true) => Ok(()),
            Ok(false) => Err(io::Error::new(io::ErrorKind::PermissionDenied, "VpnService.protect returned false")),
            Err(error) => Err(io::Error::other(format!("VpnService.protect JNI failure: {error}"))),
        }
    }
}

struct JavaConnectionOwner {vm:JavaVM,callback:Global<JObject<'static>>}
impl JavaConnectionOwner {
    fn resolve(&self,protocol:i32,source:std::net::SocketAddr,target:std::net::SocketAddr)->io::Result<clash_lib::mobile::process_identity::Identity> {
        let result:jni::errors::Result<Option<String>>=self.vm.attach_current_thread(|env| {
            let result=(|| {let source=env.new_string(source.to_string())?;let target=env.new_string(target.to_string())?;
                let object=env.call_method(&self.callback,jni_str!("resolveConnectionOwner"),jni_sig!("(ILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),&[JValue::Int(protocol),JValue::Object(source.as_ref()),JValue::Object(target.as_ref())])?.l()?;
                if object.is_null(){Ok(None)}else{Ok(Some(env.cast_local::<JString>(object)?.try_to_string(env)?))}
            })();
            if env.exception_check(){env.exception_clear();return Err(jni::errors::Error::JavaException);}result
        });
        let json=result.map_err(|_|io::Error::other("connection owner callback failed"))?.ok_or_else(||io::Error::new(io::ErrorKind::NotFound,"connection owner unknown"))?;
        decode_connection_owner(&json)
    }
}
fn decode_connection_owner(json:&str)->io::Result<clash_lib::mobile::process_identity::Identity> {
    let invalid=||io::Error::new(io::ErrorKind::InvalidData,"invalid connection owner receipt");
    if json.len()>4096{return Err(invalid());}
    let value:serde_json::Value=serde_json::from_str(json).map_err(|_|invalid())?;let object=value.as_object().ok_or_else(invalid)?;
    if object.len()!=3{return Err(invalid());}
    let uid=value.get("uid").and_then(serde_json::Value::as_u64).filter(|uid|*uid<=i32::MAX as u64).ok_or_else(invalid)?;
    let process=value.get("process").and_then(serde_json::Value::as_str).ok_or_else(invalid)?;
    let path=value.get("processPath").and_then(serde_json::Value::as_str).ok_or_else(invalid)?;
    if process.len()>1024||path.len()>1024||process.chars().chain(path.chars()).any(char::is_control){return Err(invalid());}
    Ok(clash_lib::mobile::process_identity::Identity {uid:uid as u32,process:process.into(),process_path:path.into()})
}

#[derive(Debug)]
struct BridgeError(String);

impl std::fmt::Display for BridgeError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl std::error::Error for BridgeError {}

impl From<jni::errors::Error> for BridgeError {
    fn from(error: jni::errors::Error) -> Self { Self(error.to_string()) }
}

fn bridge_error(error: anyhow::Error) -> BridgeError { BridgeError(format!("{error:#}")) }

fn validate_catching_panic(check: impl FnOnce() -> anyhow::Result<()>) -> std::result::Result<(), String> {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(check)) {
        Ok(Ok(())) => Ok(()),
        Ok(Err(error)) => Err(format!("{error:#}")),
        Err(payload) => {
            let message = payload.downcast_ref::<String>().cloned()
                .or_else(|| payload.downcast_ref::<&str>().map(|value| (*value).to_owned()))
                .unwrap_or_else(|| "unknown parser panic".to_owned());
            Err(format!("validation parser panicked: {message}"))
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_start<'local>(
    mut env: EnvUnowned<'local>, _class: JClass<'local>, config_path: JString<'local>,
    working_directory: JString<'local>, tun_fd: i32, protector: JObject<'local>,
) -> i64 {
    env.with_env(|env| -> std::result::Result<i64, BridgeError> {
        admit_runtime("tun", tun_fd, !protector.is_null()).map_err(bridge_error)?;
        let config_path = PathBuf::from(config_path.try_to_string(env)?);
        let working_directory = PathBuf::from(working_directory.try_to_string(env)?);
        let tun = duplicate_tun(tun_fd).map_err(bridge_error)?;
        let protector = JavaProtector { vm: env.get_java_vm()?, callback: env.new_global_ref(protector)? };
        start(config_path, working_directory, tun, Arc::new(move |fd| protector.protect(fd)))
            .map_err(bridge_error)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

fn admit_parked(mode:&str,has_protector:bool)->Result<RuntimeMode>{
    let mode=RuntimeMode::parse(mode)?;
    if (mode==RuntimeMode::Tun)!=has_protector {bail!("protector: parked tun requires a VPN protector and parked proxy requires null");}Ok(mode)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_startRuntimeParked<'local>(
    mut env:EnvUnowned<'local>,_class:JClass<'local>,config_path:JString<'local>,working_directory:JString<'local>,runtime_mode:JString<'local>,protector:JObject<'local>,
)->i64 {
    env.with_env(|env|->std::result::Result<i64,BridgeError>{
        let mode=admit_parked(&runtime_mode.try_to_string(env)?,!protector.is_null()).map_err(bridge_error)?;
        let config_path=PathBuf::from(config_path.try_to_string(env)?);let directory=PathBuf::from(working_directory.try_to_string(env)?);
        let input=match mode {RuntimeMode::Proxy=>RuntimeInput::ParkedProxy,RuntimeMode::Tun=>{
            let protector=JavaProtector {vm:env.get_java_vm()?,callback:env.new_global_ref(protector)?};
            RuntimeInput::ParkedTun {protector:Arc::new(move|fd|protector.protect(fd))}
        }};
        start_runtime(config_path,directory,input).map_err(bridge_error)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_startRuntime<'local>(
    mut env: EnvUnowned<'local>, _class: JClass<'local>, config_path: JString<'local>,
    working_directory: JString<'local>, runtime_mode: JString<'local>, tun_fd: i32, protector: JObject<'local>,
) -> i64 {
    env.with_env(|env| -> std::result::Result<i64, BridgeError> {
        let mode = admit_runtime(&runtime_mode.try_to_string(env)?, tun_fd, !protector.is_null()).map_err(bridge_error)?;
        let config_path = PathBuf::from(config_path.try_to_string(env)?);
        let working_directory = PathBuf::from(working_directory.try_to_string(env)?);
        let input = match mode {
            RuntimeMode::Tun => {
                let fd = duplicate_tun(tun_fd).map_err(bridge_error)?;
                let protector = JavaProtector { vm: env.get_java_vm()?, callback: env.new_global_ref(protector)? };
                RuntimeInput::Tun { fd, protector: Arc::new(move |fd| protector.protect(fd)) }
            },
            RuntimeMode::Proxy => RuntimeInput::Proxy,
        };
        start_runtime(config_path, working_directory, input).map_err(bridge_error)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_startRuntimeOwned<'local>(
    mut env:EnvUnowned<'local>,_class:JClass<'local>,config_path:JString<'local>,working_directory:JString<'local>,runtime_mode:JString<'local>,tun_fd:i32,protector:JObject<'local>,owner:JObject<'local>,initially_parked:bool,
)->i64 {
    env.with_env(|env|->std::result::Result<i64,BridgeError>{
        let mode_text=runtime_mode.try_to_string(env)?;
        let mode=if initially_parked {if tun_fd!=-1{return Err(BridgeError("tunFd: initially parked runtime requires -1".into()));}admit_parked(&mode_text,!protector.is_null()).map_err(bridge_error)?}else{admit_runtime(&mode_text,tun_fd,!protector.is_null()).map_err(bridge_error)?};
        if mode==RuntimeMode::Proxy&&!owner.is_null(){return Err(BridgeError("connectionOwner: proxy runtime requires null".into()));}
        let resolver=if owner.is_null(){None}else{let callback=JavaConnectionOwner {vm:env.get_java_vm()?,callback:env.new_global_ref(owner)?};Some(Arc::new(move|protocol,source,target|callback.resolve(protocol,source,target)) as clash_lib::mobile::process_identity::Resolver)};
        let input=match mode {
            RuntimeMode::Proxy=>if initially_parked{RuntimeInput::ParkedProxy}else{RuntimeInput::Proxy},
            RuntimeMode::Tun=>{let callback=JavaProtector {vm:env.get_java_vm()?,callback:env.new_global_ref(protector)?};let protect:clash_lib::mobile::Protector=Arc::new(move|fd|callback.protect(fd));if initially_parked{RuntimeInput::ParkedTun {protector:protect}}else{RuntimeInput::Tun {fd:duplicate_tun(tun_fd).map_err(bridge_error)?,protector:protect}}},
        };
        start_runtime_owned(PathBuf::from(config_path.try_to_string(env)?),PathBuf::from(working_directory.try_to_string(env)?),input,resolver).map_err(bridge_error)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_configureFindProcessMode<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,handle:i64,mode:JString<'local>){
    env.with_env(|env|->std::result::Result<(),BridgeError>{let active=session(handle).map_err(bridge_error)?.ok_or_else(||BridgeError("core handle has exited".into()))?;if active.snapshot.lock().unwrap().state!=State::Running{return Err(BridgeError("core must be running".into()));}active.control.find_process_mode(&mode.try_to_string(env)?).map_err(|error|bridge_error(error.into()))?;Ok(())}).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_invalidateProcessIdentity<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,handle:i64){
    env.with_env(|_|->std::result::Result<(),BridgeError>{let active=session(handle).map_err(bridge_error)?.ok_or_else(||BridgeError("core handle has exited".into()))?;if active.snapshot.lock().unwrap().state!=State::Running{return Err(BridgeError("core must be running".into()));}active.control.invalidate_process_identity().map_err(|error|bridge_error(error.into()))?;Ok(())}).resolve::<ThrowRuntimeExAndDefault>()
}

fn listener_command(handle:i64,action:&str,tun_fd:i32,only:bool)->Result<clash_lib::mobile::runtime_control::ListenerStatus> {
    let session=session(handle)?.ok_or_else(||anyhow!("core handle has exited"))?;
    let status=session.snapshot.lock().unwrap().clone();
    if status.state!=State::Running {bail!("core must be running for listener control");}
    if action!="attachTun" && tun_fd != -1 {bail!("tunFd: only attachTun accepts a descriptor");}
    match action {
        "park"=>session.control.park().map_err(Into::into),
        "resumeProxy" if status.runtime_mode==Some(RuntimeMode::Proxy)=>session.control.resume_proxy().map_err(Into::into),
        "attachTun" if status.runtime_mode==Some(RuntimeMode::Tun)=>{
            if session.control.cached().is_none_or(|s|s.listening) {bail!("listening: attachTun requires a parked runtime");}
            session.control.attach_tun(duplicate_tun(tun_fd)?).map_err(Into::into)
        },
        "read"=>session.control.status().map_err(Into::into),
        "statistics"=>session.control.statistics(only).map_err(Into::into),
        _=>bail!("runtimeMode: invalid listener command or resume input"),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_configureProbeDial<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,handle:i64,unified:jni::sys::jboolean,concurrent:jni::sys::jboolean){
    env.with_env(|_env|->std::result::Result<(),BridgeError>{
        let session=session(handle).map_err(bridge_error)?.ok_or_else(||BridgeError("core handle has exited".into()))?;
        if session.snapshot.lock().unwrap().state!=State::Running{return Err(BridgeError("core must be running for probe and dial policy".into()));}
        session.control.probe_dial(unified,concurrent).map_err(|error|bridge_error(error.into()))?;Ok(())
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_userControllerCommand<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,handle:i64,read:jni::sys::jboolean,enabled:jni::sys::jboolean,secret:JString<'local>)->JString<'local>{
    env.with_env(|env|->std::result::Result<JString<'local>,BridgeError>{
        let session=session(handle).map_err(bridge_error)?.ok_or_else(||BridgeError("core handle has exited".into()))?;
        if session.snapshot.lock().unwrap().state!=State::Running{return Err(BridgeError("core must be running for user controller".into()));}
        let plan=if read {None}else{Some((enabled,secret.try_to_string(env)?))};let receipt=session.control.controller(plan).map_err(|error|bridge_error(error.into()))?;
        let json=serde_json::to_string(&receipt).map_err(|_|BridgeError("user controller receipt could not be encoded".into()))?;Ok(env.new_string(json)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_updateNetworkDns<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,handle:i64,snapshot:JString<'local>){
    env.with_env(|env|->std::result::Result<(),BridgeError>{
        let session=session(handle).map_err(bridge_error)?.ok_or_else(||BridgeError("core handle has exited".into()))?;
        if session.snapshot.lock().unwrap().state!=State::Running{return Err(BridgeError("core must be running for network DNS".into()));}
        session.control.network_dns(snapshot.try_to_string(env)?).map_err(|error|bridge_error(error.into()))?;Ok(())
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_configureHttp<'local>(
    mut env:EnvUnowned<'local>,_class:JClass<'local>,handle:i64,user_agent:JString<'local>,check_certificate:jni::sys::jboolean,
){
    env.with_env(|env|->std::result::Result<(),BridgeError>{
        let session=session(handle).map_err(bridge_error)?.ok_or_else(||BridgeError("core handle has exited".into()))?;
        if session.snapshot.lock().unwrap().state!=State::Running {return Err(BridgeError("core must be running for HTTP policy".into()));}
        session.control.http(user_agent.try_to_string(env)?,check_certificate).map_err(|error|bridge_error(error.into()))?;
        Ok(())
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_listenerCommand<'local>(
    mut env:EnvUnowned<'local>,_class:JClass<'local>,handle:i64,action:JString<'local>,tun_fd:i32,only_statistics_proxy:jni::sys::jboolean,
)->JString<'local>{
    env.with_env(|env|->std::result::Result<JString<'local>,BridgeError>{
        let status=listener_command(handle,&action.try_to_string(env)?,tun_fd,only_statistics_proxy).map_err(bridge_error)?;
        let json=serde_json::to_string(&status).map_err(|e|BridgeError(e.to_string()))?;
        Ok(env.new_string(json)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_snapshot<'local>(
    mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64,
) -> JString<'local> {
    env.with_env(|env| -> std::result::Result<JString<'local>, BridgeError> {
        let state = snapshot(handle).map_err(bridge_error)?;
        let json = serde_json::to_string(&state).map_err(|error| BridgeError(error.to_string()))?;
        Ok(env.new_string(json)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_awaitReady<'local>(
    mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64, timeout_millis: i64,
) {
    env.with_env(|_| -> std::result::Result<(), BridgeError> {
        if timeout_millis < 0 { return Err(BridgeError("negative ready timeout".into())); }
        await_ready(handle, Duration::from_millis(timeout_millis as u64)).map_err(bridge_error)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_stop<'local>(
    mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64,
) {
    env.with_env(|_| -> std::result::Result<(), BridgeError> {
        stop(handle).map_err(bridge_error)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_validate<'local>(
    mut env: EnvUnowned<'local>, _class: JClass<'local>, source: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> std::result::Result<JString<'local>, BridgeError> {
        let source = source.try_to_string(env)?;
        match validate_catching_panic(|| clash_lib::mobile::validate(&source).map_err(Into::into)) {
            Ok(()) => Ok(JString::null()),
            Err(error) => Ok(env.new_string(error.to_string())?),
        }
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_validateResource<'local>(
    mut env: EnvUnowned<'local>, _class: JClass<'local>, kind: JString<'local>,
    path: JString<'local>, behavior: JString<'local>, format: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> std::result::Result<JString<'local>, BridgeError> {
        let kind = kind.try_to_string(env)?;
        let path = path.try_to_string(env)?;
        let behavior = behavior.try_to_string(env)?;
        let format = format.try_to_string(env)?;
        match validate_catching_panic(|| clash_lib::mobile::resources::validate_resource(&kind, std::path::Path::new(&path), &behavior, &format)) {
            Ok(()) => Ok(JString::null()),
            Err(error) => Ok(env.new_string(format!("{error:#}"))?),
        }
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_inspectRuleSet<'local>(
    mut env: EnvUnowned<'local>, _class: JClass<'local>, path: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> std::result::Result<JString<'local>, BridgeError> {
        let path = path.try_to_string(env)?; let mut output = String::new();
        validate_catching_panic(|| { output = clash_lib::mobile::resources::inspect_rule_set(std::path::Path::new(&path))?; Ok(()) })
            .map_err(|_| BridgeError("rule_set_invalid: offline inspection failed".into()))?;
        Ok(env.new_string(output)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_dumpRuleSet<'local>(
    mut env: EnvUnowned<'local>, _class: JClass<'local>, path: JString<'local>, behavior: JString<'local>, format: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> std::result::Result<JString<'local>, BridgeError> {
        let path = path.try_to_string(env)?; let behavior = behavior.try_to_string(env)?; let format = format.try_to_string(env)?;
        let mut output = String::new();
        validate_catching_panic(|| { output = clash_lib::mobile::resources::dump_rule_set(std::path::Path::new(&path), &behavior, &format)?; Ok(()) })
            .map_err(|_| BridgeError("rule_set_invalid: offline dump failed".into()))?;
        Ok(env.new_string(output)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[cfg(test)]
mod resource_tests;

#[cfg(test)]
mod outbound_tests;

#[cfg(test)]
mod telemetry_tests;

#[cfg(test)]
mod service_check_tests;

#[cfg(test)]
mod inbound_tests;

#[cfg(test)]
mod runtime_mode_tests;

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::{fd::AsRawFd, unix::net::UnixStream};

    #[test]
    fn duplicate_has_independent_ownership_and_closes_once() {
        let (original, _) = UnixStream::pair().unwrap();
        let copy = duplicate_tun(original.as_raw_fd()).unwrap();
        assert_ne!(original.as_raw_fd(), copy.as_raw_fd());
        let fd = copy.as_raw_fd();
        drop(copy);
        assert_eq!(unsafe { libc::fcntl(fd, libc::F_GETFD) }, -1);
        assert_ne!(unsafe { libc::fcntl(original.as_raw_fd(), libc::F_GETFD) }, -1);
        assert!(duplicate_tun(-1).is_err());
    }

    #[test]
    fn failed_start_is_not_ready_and_requires_join_before_replacement() {
        clash_lib::mobile::outbound::regression::with_runtime_lock(|| {
        let (fd, _) = UnixStream::pair().unwrap();
        let directory = std::env::temp_dir();
        let path = directory.join(format!("aerove-missing-{}.yaml", std::process::id()));
        let handle = start(path.clone(), directory.clone(), duplicate_tun(fd.as_raw_fd()).unwrap(),
            Arc::new(|_| Ok(()))).unwrap();
        assert!(await_ready(handle, Duration::from_secs(2)).is_err());
        assert_eq!(snapshot(handle).unwrap().state, State::Failed);
        assert!(start(path.clone(), directory.clone(), duplicate_tun(fd.as_raw_fd()).unwrap(),
            Arc::new(|_| Ok(()))).is_err());
        stop(handle).unwrap();
        assert_eq!(snapshot(handle).unwrap().state, State::Exited);
        let next = start(path, directory, duplicate_tun(fd.as_raw_fd()).unwrap(), Arc::new(|_| Ok(()))).unwrap();
        assert!(next > handle);
        stop(handle).unwrap();
        assert!(session(next).unwrap().is_some());
        stop(next).unwrap();
        stop(next).unwrap();
        assert!(snapshot(0).is_err());
        });
    }

    #[test]
    fn connection_owner_receipts_reject_negative_fractional_overflow_and_unknown_fields() {
        assert_eq!(decode_connection_owner(r#"{"uid":10042,"process":"dev.fixture","processPath":""}"#).unwrap().uid,10042);
        for json in [r#"{"uid":-1,"process":"","processPath":""}"#,r#"{"uid":1.5,"process":"","processPath":""}"#,r#"{"uid":4294967295,"process":"","processPath":""}"#,r#"{"uid":1,"process":"x","processPath":"","extra":true}"#] {assert!(decode_connection_owner(json).is_err());}
    }

    #[test]
    fn late_ready_cannot_revive_a_cancelled_session() {
        let session = Session::new(RuntimeMode::Tun,false);
        session.cancel.cancel();
        session.ready(ReadyInfo { address: "127.0.0.1:1234".parse().unwrap(), runtime_mode: RuntimeMode::Tun,
            tun_ready: true, user_listeners_ready: true });
        assert_ne!(session.snapshot.lock().unwrap().state, State::Running);
    }
}

#[cfg(test)]
mod observation_tests;

#[cfg(test)]
mod runtime_control_tests;

#[cfg(test)]
mod http_policy_tests;

#[cfg(test)]
mod dns_network_tests;

#[cfg(test)]
mod initial_park_tests;

#[cfg(test)]
mod mtu_tests;

#[cfg(test)]
mod outbound_direct_tests;

#[cfg(test)]
mod user_controller_tests;
#[cfg(test)]
mod probe_dial_tests;

#[cfg(test)]
mod reset_connections_tests;

#[cfg(test)]
mod process_identity_tests;

#[cfg(test)]
mod protocol_tests;

#[cfg(test)]
mod ssh_peer_tests;

#[cfg(test)]
mod wireguard_peer_tests;

#[cfg(test)]
mod shadowquic_peer_tests;

#[cfg(test)]
mod provider_loaded_tests;
