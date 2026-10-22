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
}

impl Snapshot {
    fn exited() -> Self {
        Self { state: State::Exited, error: None, api_address: None }
    }
}

struct Session {
    snapshot: Mutex<Snapshot>,
    changed: Condvar,
    cancel: CancellationToken,
    worker: Mutex<Option<JoinHandle<()>>>,
}

impl Session {
    fn new() -> Self {
        Self {
            snapshot: Mutex::new(Snapshot {
                state: State::Starting, error: None, api_address: None,
            }),
            changed: Condvar::new(),
            cancel: CancellationToken::new(),
            worker: Mutex::new(None),
        }
    }

    fn ready(&self, address: std::net::SocketAddr) {
        let mut state = self.snapshot.lock().unwrap();
        if state.state == State::Starting && !self.cancel.is_cancelled() {
            state.state = State::Running;
            state.api_address = Some(address.to_string());
            self.changed.notify_all();
        }
    }

    fn finished(&self, result: Result<()>) {
        let mut state = self.snapshot.lock().unwrap();
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

fn start(
    config_path: PathBuf,
    working_directory: PathBuf,
    tun: OwnedFd,
    protector: clash_lib::mobile::Protector,
) -> Result<i64> {
    let mut registry = registry().lock().unwrap();
    if registry.active.is_some() {
        bail!("previous core must complete stop and join before start");
    }
    let handle = registry.next_handle;
    let next = handle.checked_add(1).ok_or_else(|| anyhow!("core handles exhausted"))?;
    let session = Arc::new(Session::new());
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
                let result = runtime.block_on(clash_lib::mobile::run(
                    source, working_directory, tun, protector,
                    worker_session.cancel.clone(),
                    |address| worker_session.ready(address),
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
        Some(session) => session.snapshot.lock().unwrap().clone(),
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_core_NativeCore_start<'local>(
    mut env: EnvUnowned<'local>, _class: JClass<'local>, config_path: JString<'local>,
    working_directory: JString<'local>, tun_fd: i32, protector: JObject<'local>,
) -> i64 {
    env.with_env(|env| -> std::result::Result<i64, BridgeError> {
        let config_path = PathBuf::from(config_path.try_to_string(env)?);
        let working_directory = PathBuf::from(working_directory.try_to_string(env)?);
        let tun = duplicate_tun(tun_fd).map_err(bridge_error)?;
        if protector.is_null() {
            return Err(BridgeError("VPN protector must not be null".into()));
        }
        let protector = JavaProtector { vm: env.get_java_vm()?, callback: env.new_global_ref(protector)? };
        start(config_path, working_directory, tun, Arc::new(move |fd| protector.protect(fd)))
            .map_err(bridge_error)
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
        match clash_lib::mobile::validate(&source) {
            Ok(()) => Ok(JString::null()),
            Err(error) => Ok(env.new_string(error.to_string())?),
        }
    }).resolve::<ThrowRuntimeExAndDefault>()
}

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
    }

    #[test]
    fn late_ready_cannot_revive_a_cancelled_session() {
        let session = Session::new();
        session.cancel.cancel();
        session.ready("127.0.0.1:1234".parse().unwrap());
        assert_ne!(session.snapshot.lock().unwrap().state, State::Running);
    }
}
