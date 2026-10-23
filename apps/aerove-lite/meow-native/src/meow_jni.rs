use crate::{flow_owner::{FlowIdentity, FlowOwnerLookup}, network_context::NetworkContext, physical_dns::PhysicalResolver, runtime::{Phase, Registry, Session}, runtime_plan::{RuntimeMode, RuntimePlan}};
use jni::{EnvUnowned, JavaVM, JValue, errors::ThrowRuntimeExAndDefault, jni_sig, jni_str, objects::{JClass, JObject, JString}, refs::Global};
use parking_lot::Mutex;
use std::{collections::BTreeMap, io, net::SocketAddr, os::fd::{FromRawFd, OwnedFd}, sync::{Arc, OnceLock}};
use tokio_util::sync::CancellationToken;

static EXECUTOR: OnceLock<Result<tokio::runtime::Runtime, String>> = OnceLock::new();
static HANDLES: OnceLock<Mutex<BTreeMap<i64, Arc<Session>>>> = OnceLock::new();
#[derive(Debug)]
struct BridgeError(String);
impl std::fmt::Display for BridgeError { fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { f.write_str(&self.0) } }
impl std::error::Error for BridgeError {}
impl From<jni::errors::Error> for BridgeError { fn from(_: jni::errors::Error) -> Self { Self("JNI operation failed".into()) } }
impl From<io::Error> for BridgeError { fn from(error: io::Error) -> Self { Self(error.to_string()) } }
fn executor() -> Result<&'static tokio::runtime::Runtime, BridgeError> {
    EXECUTOR.get_or_init(|| tokio::runtime::Builder::new_multi_thread().worker_threads(2)
        .thread_name("aerove-meow").enable_all().build().map_err(|_| "runtime unavailable".into()))
        .as_ref().map_err(|error| BridgeError(error.clone()))
}
fn session(handle: i64) -> Result<Arc<Session>, BridgeError> {
    HANDLES.get_or_init(|| Mutex::new(BTreeMap::new())).lock().get(&handle).cloned()
        .ok_or_else(|| BridgeError("invalid core handle".into()))
}
fn failure(session: &Session, fallback: &str) -> BridgeError {
    BridgeError(session.owned_runtime().and_then(|owner| owner.status().error).unwrap_or_else(|| fallback.to_owned()))
}
fn duplicate_fd(fd: i32) -> io::Result<Option<OwnedFd>> {
    if fd == -1 { return Ok(None); }
    if fd < 0 { return Err(io::Error::new(io::ErrorKind::InvalidInput, "tunFd")); }
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if flags < 0 { return Err(io::Error::last_os_error()); }
    if flags & libc::O_NONBLOCK == 0 { return Err(io::Error::new(io::ErrorKind::InvalidInput, "tunFd must already be nonblocking")); }
    let duplicated = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 0) };
    if duplicated < 0 { return Err(io::Error::last_os_error()); }
    Ok(Some(unsafe { OwnedFd::from_raw_fd(duplicated) }))
}
struct JavaProtector { vm: JavaVM, callback: Global<JObject<'static>>, cancel: CancellationToken }
impl meow_common::socket_protect::SocketProtector for JavaProtector {
    fn protect(&self, fd: i32) -> io::Result<()> {
        if self.cancel.is_cancelled() { return Err(io::Error::from(io::ErrorKind::Interrupted)); }
        let result: jni::errors::Result<bool> = self.vm.attach_current_thread(|env| {
            let result = env.call_method(&self.callback, jni_str!("protect"), jni_sig!("(I)Z"), &[JValue::Int(fd)]).and_then(|value| value.z());
            if env.exception_check() { env.exception_clear(); return Err(jni::errors::Error::JavaException); }
            result
        });
        if self.cancel.is_cancelled() { return Err(io::Error::from(io::ErrorKind::Interrupted)); }
        match result { Ok(true) => Ok(()), Ok(false) => Err(io::Error::new(io::ErrorKind::PermissionDenied, "socket protection rejected")),
            Err(_) => Err(io::Error::other("socket protection callback failed")) }
    }
}
struct JavaOwner { vm: JavaVM, callback: Global<JObject<'static>>, cancel: CancellationToken }
impl FlowOwnerLookup for JavaOwner {
    fn lookup(&self, protocol: i32, source: SocketAddr, target: SocketAddr) -> Option<FlowIdentity> {
        if self.cancel.is_cancelled() { return None; }
        let result: jni::errors::Result<Option<String>> = self.vm.attach_current_thread(|env| {
            let result = (|| {
                let source = env.new_string(source.to_string())?; let target = env.new_string(target.to_string())?;
                let value = env.call_method(&self.callback, jni_str!("resolveConnectionOwner"), jni_sig!("(ILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                    &[JValue::Int(protocol), JValue::Object(source.as_ref()), JValue::Object(target.as_ref())])?.l()?;
                if value.is_null() { Ok(None) } else { Ok(Some(env.cast_local::<JString>(value)?.try_to_string(env)?)) }
            })();
            if env.exception_check() { env.exception_clear(); return Err(jni::errors::Error::JavaException); }
            result
        });
        if self.cancel.is_cancelled() { return None; }
        decode_identity(&result.ok()??)
    }
}
fn decode_identity(json: &str) -> Option<FlowIdentity> {
    if json.len() > 4096 { return None; }
    let value: serde_json::Value = serde_json::from_str(json).ok()?;
    if value.as_object()?.len() != 3 { return None; }
    let uid = value.get("uid")?.as_u64().filter(|uid| *uid <= i32::MAX as u64)? as u32;
    let process = value.get("process")?.as_str()?; let path = value.get("processPath")?.as_str()?;
    if process.len() > 1024 || path.len() > 2048 || process.chars().chain(path.chars()).any(char::is_control) { return None; }
    Some(FlowIdentity { uid, process: process.into(), process_path: path.into() })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_startOwned<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>,
    json: JString<'local>, fd: i32, protector: JObject<'local>, owner: JObject<'local>) -> i64 {
    env.with_env(|env| -> Result<i64, BridgeError> {
        let json = json.try_to_string(env)?;
        let plan = RuntimePlan::parse(&json, fd, !protector.is_null(), !owner.is_null())?;
        let cancel = CancellationToken::new();
        let network_handle = plan.physical_network.handle.as_ref().map(|value| value.parse::<u64>().expect("validated network"));
        let network = match plan.runtime_mode {
            RuntimeMode::Proxy => match network_handle { Some(handle) => NetworkContext::platform_on_network(handle, cancel.clone())?, None => NetworkContext::platform(cancel.clone()) },
            RuntimeMode::Tun => {
                let callback = Arc::new(JavaProtector { vm: env.get_java_vm()?, callback: env.new_global_ref(protector)?, cancel: cancel.clone() });
                match network_handle { Some(handle) => NetworkContext::protected_on_network(callback, None, handle, cancel.clone())?, None => NetworkContext::protected(callback, None, cancel.clone()) }
            },
        };
        let has_servers = plan.physical_network.dns.default_interface.as_ref()
            .and_then(|name| plan.physical_network.dns.interfaces.get(name)).is_some_and(|servers| !servers.is_empty());
        let network = if has_servers { PhysicalResolver::from_snapshot(&plan.physical_network.dns, network,
            plan.tun.as_ref().is_some_and(|tun| tun.ipv6))?.network() }
            else { network.with_physical_dns(Vec::new()) };
        let lookup = if owner.is_null() { None } else { Some(Arc::new(JavaOwner { vm: env.get_java_vm()?, callback: env.new_global_ref(owner)?, cancel }) as Arc<dyn FlowOwnerLookup>) };
        let fd = duplicate_fd(fd)?;
        let runtime = executor()?;
        let session = { let _entered = runtime.enter(); Registry::start_owned(json, fd, network, lookup).map_err(|error| BridgeError(match error.field { Some(field) => format!("{}:{field}", error.code), None => error.code.into() }))? };
        let handle = match i64::try_from(session.generation()) { Ok(handle) => handle, Err(_) => {
            let _ = runtime.block_on(session.stop()); return Err(BridgeError("JNI handle exhausted".into()));
        } };
        let retired = {
            let mut handles = HANDLES.get_or_init(|| Mutex::new(BTreeMap::new())).lock();
            handles.insert(handle, session);
            let keys: Vec<_> = handles.keys().copied().filter(|old| *old != handle).collect();
            let count = handles.len().saturating_sub(16);
            keys.into_iter().take(count).filter_map(|key| handles.remove(&key)).collect::<Vec<_>>()
        };
        drop(retired);
        Ok(handle)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_status<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let session = session(handle)?; let snapshot = session.snapshot();
        let mut status = session.owned_runtime().map(|owner| owner.status()).unwrap_or(crate::owned_runtime::OwnedStatus {
            state: "Starting", error: None, api_address: None, runtime_mode: snapshot.runtime_mode,
            tun_ready: false, user_listeners_ready: false, listening: false, core_ready: false });
        match snapshot.phase {
            Phase::Failed => { status.state = "Failed"; if status.error.is_none() { status.error = snapshot.failure.map(|failure| failure.code.to_owned()); } },
            Phase::Stopped => status.state = "Exited",
            _ if matches!(status.state, "Exited" | "Failed") => { status.state = "Starting"; status.error = None; },
            _ => {},
        }
        Ok(env.new_string(serde_json::to_string(&status).map_err(|_| BridgeError("status encoding failed".into()))?)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_listenerStatus<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let session = session(handle)?;
        let status = session.owned_runtime().map(|owner| owner.listener_status()).unwrap_or(crate::owned_runtime::ListenerStatus {
            listening: false, tun_ready: false, user_listeners_ready: false, active_ports: Vec::new(),
            core_epoch: session.generation().to_string(), only_statistics_proxy: false, unified_delay: true, tcp_concurrent: true, find_process_mode: "off" });
        Ok(env.new_string(serde_json::to_string(&status).map_err(|_| BridgeError("listener status encoding failed".into()))?)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_awaitReady<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64) {
    env.with_env(|_| -> Result<(), BridgeError> {
        let session = session(handle)?;
        executor()?.block_on(session.ready()).map_err(|error| failure(&session, error.code))?;
        Ok(())
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_stop<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64) {
    env.with_env(|_| -> Result<(), BridgeError> {
        let session = session(handle)?;
        executor()?.block_on(session.stop()).map_err(|error| failure(&session, error.code))?;
        if session.owned_runtime().is_some_and(|owner| !owner.cleanup_confirmed()) {
            return Err(failure(&session, "runtimeCleanupUnconfirmed"));
        }
        Ok(())
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_awaitExit<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64) {
    env.with_env(|_| -> Result<(), BridgeError> {
        let session = session(handle)?;
        executor()?.block_on(session.exited()).map_err(|error| failure(&session, error.code))?;
        Ok(())
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_listenerCommand<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64,
    action: JString<'local>, fd: i32) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let session = session(handle)?;
        if session.snapshot().phase != Phase::Running { return Err(failure(&session, "runtimeNotReady")); }
        let owner = session.owned_runtime().ok_or_else(|| BridgeError("runtimeNotReady".into()))?;
        let action = action.try_to_string(env)?;
        let mode = owner.status().runtime_mode;
        let runtime = executor()?;
        let status = match (action.as_str(), mode, fd) {
            ("park", _, -1) => runtime.block_on(owner.park())?,
            ("resumeProxy", "proxy", -1) => runtime.block_on(owner.resume(None))?,
            ("attachTun", "tun", fd) if fd >= 0 => {
                let owned = duplicate_fd(fd)?.ok_or_else(|| BridgeError("attachedTunFdRequired".into()))?;
                runtime.block_on(owner.resume(Some(owned)))?
            },
            _ => return Err(BridgeError("invalidListenerCommandFdMode".into())),
        };
        Ok(env.new_string(serde_json::to_string(&status).map_err(|_| BridgeError("listener status encoding failed".into()))?)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}
