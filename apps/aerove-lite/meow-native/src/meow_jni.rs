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
        let ipv6 = meow_config::effective_ipv6(meow_config::parse_raw_yaml(&plan.engine_yaml).map_err(|_| BridgeError("invalidConfig".into()))?.ipv6);
        let disconnected = network_handle.is_none() && plan.physical_network.dns.default_interface.is_none()
            && plan.physical_network.dns.interfaces.is_empty() && (plan.runtime_mode == RuntimeMode::Proxy || plan.initially_parked);
        let network = if disconnected { PhysicalResolver::not_connected(network,ipv6)?.network() }
            else { PhysicalResolver::from_snapshot(&plan.physical_network.dns,network,ipv6)?.network() };
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
        let result = executor()?.block_on(session.stop());
        match session.owned_runtime() {
            Some(owner) if owner.cleanup_confirmed() => Ok(()),
            Some(_) => Err(failure(&session,"runtimeCleanupUnconfirmed")),
            None => result.map(|_| ()).map_err(|error| failure(&session,error.code)),
        }
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_configurePolicies<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64,
    request: JString<'local>) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let session = session(handle)?;
        let owner = session.owned_runtime().ok_or_else(|| BridgeError("runtimeNotReady".into()))?;
        let request = request.try_to_string(env)?;
        let receipt = owner.policies().configure_json(&request)?;
        Ok(env.new_string(serde_json::to_string(&receipt).map_err(|_| BridgeError("policy encoding failed".into()))?)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_policyStatus<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let session = session(handle)?;
        let owner = session.owned_runtime().ok_or_else(|| BridgeError("runtimeNotReady".into()))?;
        let status = owner.policies().status()?;
        Ok(env.new_string(serde_json::to_string(&status).map_err(|_| BridgeError("policy encoding failed".into()))?)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_hostPolicyCommand<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64,
    request: JString<'local>) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let session = session(handle)?;
        if session.snapshot().phase != Phase::Running { return Err(failure(&session, "runtimeNotReady")); }
        let owner = session.owned_runtime().ok_or_else(|| BridgeError("runtimeNotReady".into()))?;
        let request = request.try_to_string(env)?;
        let receipt = executor()?.block_on(owner.host_policy_command(request))?;
        Ok(env.new_string(receipt)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_capabilities<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let json = executor()?.block_on(crate::capabilities::json())?;
        Ok(env.new_string(json)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

fn validation_result<T>(work: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(work))
        .unwrap_or_else(|_| Err("native_validation_panicked".into()))
}
async fn validation_future<T>(work: impl std::future::Future<Output = Result<T, String>>) -> Result<T, String> {
    use futures::FutureExt;
    std::panic::AssertUnwindSafe(work).catch_unwind().await
        .unwrap_or_else(|_| Err("native_validation_panicked".into()))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_validate<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, source: JString<'local>) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let source = source.try_to_string(env)?;
        match executor()?.block_on(validation_future(crate::config_validation::validate_config(&source))) {
            Ok(()) => Ok(JString::null()),
            Err(error) => Ok(env.new_string(error)?),
        }
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_validateResource<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, kind: JString<'local>, path: JString<'local>, behavior: JString<'local>, format: JString<'local>) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let kind = kind.try_to_string(env)?; let path = path.try_to_string(env)?;
        let behavior = behavior.try_to_string(env)?; let format = format.try_to_string(env)?;
        match executor()?.block_on(validation_future(crate::config_validation::validate_resource(&kind,std::path::Path::new(&path),&behavior,&format))) {
            Ok(()) => Ok(JString::null()),
            Err(error) => Ok(env.new_string(error)?),
        }
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_inspectRuleSet<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, path: JString<'local>) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let path = path.try_to_string(env)?;
        let output = validation_result(|| crate::config_validation::inspect_rule_set(std::path::Path::new(&path))).map_err(BridgeError)?;
        Ok(env.new_string(output)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_MeowNativeCore_dumpRuleSet<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, path: JString<'local>, behavior: JString<'local>, format: JString<'local>) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let path = path.try_to_string(env)?; let behavior = behavior.try_to_string(env)?; let format = format.try_to_string(env)?;
        let output = validation_result(|| crate::config_validation::dump_rule_set(std::path::Path::new(&path),&behavior,&format)).map_err(BridgeError)?;
        Ok(env.new_string(output)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[cfg(test)]
mod validation_boundary_tests {
    use super::*;
    #[test]
    fn synchronous_validation_keeps_specific_errors_and_contains_panics() {
        assert_eq!(validation_result(|| Ok::<_,String>("payload\n".to_owned())).unwrap(),"payload\n");
        assert_eq!(validation_result::<()>(|| Err("rule_set_behavior_mismatch".into())).unwrap_err(),"rule_set_behavior_mismatch");
        assert_eq!(validation_result::<()>(|| panic!("fixture parser panic")).unwrap_err(),"native_validation_panicked");
    }
    #[tokio::test]
    async fn asynchronous_validation_keeps_specific_errors_and_contains_panics() {
        validation_future(async { Ok::<_,String>(()) }).await.unwrap();
        assert_eq!(validation_future::<()>(async { Err("$.unknown: unsupported_config_field".into()) }).await.unwrap_err(),"$.unknown: unsupported_config_field");
        assert_eq!(validation_future::<()>(async { panic!("fixture converter panic") }).await.unwrap_err(),"native_validation_panicked");
    }
}


static RESOURCE_FETCHES: OnceLock<Mutex<BTreeMap<i64,Arc<crate::resource_fetch::ResourceFetch>>>> = OnceLock::new();
static NEXT_RESOURCE_FETCH: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(1);
#[derive(serde::Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct ResourcePlan {
    schema_version:u8, physical_network:crate::runtime_plan::PhysicalNetwork,
    staging_root:String,resource_read_root:Option<String>,cache_root:String,
}
fn resource_fetch(handle:i64)->Result<Arc<crate::resource_fetch::ResourceFetch>,BridgeError> {
    RESOURCE_FETCHES.get_or_init(||Mutex::new(BTreeMap::new())).lock().get(&handle).cloned()
        .ok_or_else(||BridgeError("invalid resource scope handle".into()))
}
fn resource_owner_ended_exception(env: &mut jni::Env<'_>, exit: crate::resource_fetch::ResourceRequestExit) {
    use jni::{descriptors::Desc, objects::JThrowable};
    if env.exception_check() { return; }
    let primary = std::panic::catch_unwind(std::panic::AssertUnwindSafe(||
        Desc::<JThrowable>::lookup("Rust error: resourceRequestOwnerEnded", env)));
    let primary = match primary {
        Ok(Ok(primary)) => primary,
        // No primary exists yet: retain any pending VM exception for the original policy.
        Ok(Err(_)) => return,
        Err(payload) => { std::mem::forget(payload); return; },
    };
    let primary_throwable: &JThrowable = primary.as_ref();
    let diagnostic = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> jni::errors::Result<()> {
        let diagnostic = Desc::<JThrowable>::lookup(format!("resourceRequestOwnerEnded diagnostic {exit}"), env)?;
        let throwable: &JThrowable = diagnostic.as_ref();
        env.call_method(primary_throwable, jni_str!("addSuppressed"), jni_sig!("(Ljava/lang/Throwable;)V"), &[JValue::Object(throwable.as_ref())])?;
        Ok(())
    }));
    if let Err(payload) = diagnostic { std::mem::forget(payload); }
    if env.exception_check() { env.exception_clear(); }
    let _ = env.throw(primary_throwable);
}
fn resource_result<T>(env: &mut jni::Env<'_>, work:impl std::future::Future<Output=std::io::Result<T>>)->Result<T,BridgeError> {
    let result = executor()?.block_on(validation_future(async move { Ok(work.await) })).map_err(BridgeError)?;
    result.map_err(|cause| {
        if let Some(ended) = cause.get_ref().and_then(|inner| inner.downcast_ref::<crate::resource_fetch::ResourceRequestOwnerEnded>()) {
            resource_owner_ended_exception(env, ended.exit);
        }
        BridgeError(cause.to_string())
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_JniResourceFetch_nativeCreate<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,plan:JString<'local>)->i64 {
    env.with_env(|env|->Result<i64,BridgeError>{
        let text=plan.try_to_string(env)?;if text.len()>65536 {return Err(BridgeError("resource plan limit".into()));}
        let plan:ResourcePlan=serde_json::from_str(&text).map_err(|_|BridgeError("resource plan schema".into()))?;
        if plan.schema_version!=1 {return Err(BridgeError("resource schemaVersion".into()));}
        let raw=plan.physical_network.handle.as_deref().ok_or_else(||BridgeError("physicalNetwork.handle: unavailable".into()))?;
        let network=raw.parse::<u64>().ok().filter(|n|*n!=0&&n.to_string()==raw).ok_or_else(||BridgeError("physicalNetwork.handle".into()))?;
        let cancel=CancellationToken::new();let context=NetworkContext::platform_on_network(network,cancel.clone())?;
        let servers=crate::physical_dns::selected_servers(&plan.physical_network.dns)?;
        let scope=crate::resource_fetch::ResourceFetch::create(context,servers,&plan.staging_root,plan.resource_read_root.as_deref(),&plan.cache_root,cancel)?;
        let handle=NEXT_RESOURCE_FETCH.fetch_update(std::sync::atomic::Ordering::AcqRel,std::sync::atomic::Ordering::Acquire,|n|n.checked_add(1)).map_err(|_|BridgeError("resource handles exhausted".into()))?;
        RESOURCE_FETCHES.get_or_init(||Mutex::new(BTreeMap::new())).lock().insert(handle,scope);Ok(handle)
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_JniResourceFetch_nativeLoad<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,handle:i64,source:JString<'local>) {
    env.with_env(|env|->Result<(),BridgeError>{
        let source=source.try_to_string(env)?;if source.len()>crate::config_validation::MAX_TEXT_BYTES {return Err(BridgeError("candidate input limit".into()));}
        resource_result(env, resource_fetch(handle)?.load(source))
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_JniResourceFetch_nativeDownload<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,handle:i64,request:JString<'local>)->JString<'local> {
    env.with_env(|env|->Result<JString<'local>,BridgeError>{
        let text=request.try_to_string(env)?;if text.len()>262144 {return Err(BridgeError("resource request limit".into()));}
        let request=serde_json::from_str(&text).map_err(|_|BridgeError("resource request schema".into()))?;
        let result=resource_result(env, resource_fetch(handle)?.download(request))?;
        Ok(env.new_string(serde_json::to_string(&result).map_err(|_|BridgeError("resource response".into()))?)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_JniResourceFetch_nativeCancel<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,handle:i64) {
    env.with_env(|_|->Result<(),BridgeError>{resource_fetch(handle)?.cancel();Ok(())}).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_JniResourceFetch_nativeCloseJoin<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,handle:i64) {
    env.with_env(|env|->Result<(),BridgeError>{
        let result=resource_result(env, resource_fetch(handle)?.close_and_join());
        RESOURCE_FETCHES.get_or_init(||Mutex::new(BTreeMap::new())).lock().remove(&handle);result
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_JniResourceFetch_nativeValidatePrepared<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,source:JString<'local>,read_root:JString<'local>,cache:JString<'local>)->JString<'local> {
    env.with_env(|env|->Result<JString<'local>,BridgeError>{
        let source=source.try_to_string(env)?;let read_root=read_root.try_to_string(env)?;let cache=cache.try_to_string(env)?;
        if source.len()>crate::config_validation::MAX_TEXT_BYTES {return Err(BridgeError("prepared input limit".into()));}
        match executor()?.block_on(validation_future(crate::resource_fetch::validate_prepared(&source,&read_root,&cache))) {
            Ok(())=>Ok(JString::null()),Err(cause)=>Ok(env.new_string(cause)?)
        }
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_JniResourceFetch_nativePreflightRuleResource<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,path:JString<'local>,behavior:JString<'local>,format:JString<'local>)->JString<'local> {
    env.with_env(|env|->Result<JString<'local>,BridgeError>{
        let path=path.try_to_string(env)?;let behavior=behavior.try_to_string(env)?;let format=format.try_to_string(env)?;
        let receipt=validation_result(||crate::config_validation::preflight_rule_resource(std::path::Path::new(&path),&behavior,&format)).map_err(BridgeError)?;
        Ok(env.new_string(receipt)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}
