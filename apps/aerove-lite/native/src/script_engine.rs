// SPDX-License-Identifier: GPL-3.0-only
// Adapted from AEROVE Standard plugins/rust_api/rust/src/script/mod.rs.
// Kept runtime-independent: QuickJS owns no filesystem, network, timer or native core handles.
use rquickjs::{CatchResultExt, Context, Function, Runtime, Value};
use std::{collections::HashMap, sync::{Arc, Mutex, OnceLock, atomic::{AtomicBool, AtomicI64, Ordering}}, time::{Duration, Instant}};

const MEMORY_LIMIT: usize = 256 * 1024 * 1024;
const INPUT_LIMIT: usize = 16 * 1024 * 1024;
const SCRIPT_LIMIT: usize = 4 * 1024 * 1024;
const TIMEOUT: Duration = Duration::from_secs(10);
static NEXT: AtomicI64 = AtomicI64::new(1);
static OPERATIONS: OnceLock<Mutex<HashMap<i64, Arc<AtomicBool>>>> = OnceLock::new();

fn operations() -> &'static Mutex<HashMap<i64, Arc<AtomicBool>>> {
    OPERATIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn begin() -> Result<i64, &'static str> {
    let mut entries = operations().lock().map_err(|_| "internal")?;
    if entries.len() >= 8 { return Err("busy"); }
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    entries.insert(id, Arc::new(AtomicBool::new(false)));
    Ok(id)
}

pub fn cancel(id: i64) {
    if let Ok(entries) = operations().lock() {
        if let Some(flag) = entries.get(&id) { flag.store(true, Ordering::Release); }
    }
}

pub fn release(id: i64) {
    if let Ok(mut entries) = operations().lock() { entries.remove(&id); }
}

pub fn evaluate(script: &str, config: &str, id: i64) -> Result<String, &'static str> {
    let flag = operations().lock().map_err(|_| "internal")?.get(&id).cloned().ok_or("cancelled")?;
    evaluate_within(script, config, flag, TIMEOUT, MEMORY_LIMIT)
}

fn evaluate_within(script: &str, config: &str, cancelled: Arc<AtomicBool>, timeout: Duration, memory_limit: usize) -> Result<String, &'static str> {
    if script.len() > SCRIPT_LIMIT || config.len() > INPUT_LIMIT { return Err("size_limit"); }
    if cancelled.load(Ordering::Acquire) { return Err("cancelled"); }
    let runtime = Runtime::new().map_err(|_| "engine")?;
    runtime.set_memory_limit(memory_limit);
    runtime.set_max_stack_size(1024 * 1024);
    let deadline = Instant::now() + timeout;
    let interrupted = cancelled.clone();
    runtime.set_interrupt_handler(Some(Box::new(move || interrupted.load(Ordering::Acquire) || Instant::now() >= deadline)));
    let context = Context::full(&runtime).map_err(|_| "memory_limit")?;
    let result = context.with(|ctx| {
        // Console is intentionally a sink: configs and script logs can contain secrets.
        ctx.eval::<Value, _>(b"globalThis.console = Object.fromEntries(['log','info','warn','error','debug','trace'].map(k => [k,()=>{}]));".as_slice())
            .map_err(|_| "engine")?;
        ctx.eval::<Value, _>(script.as_bytes()).catch(&ctx).map_err(|_| "script_error")?;
        let entry: Function = ctx.eval(b"main".as_slice()).map_err(|_| "missing_main")?;
        let parsed: Value = ctx.json_parse(config).catch(&ctx).map_err(|_| "invalid_config")?;
        let output: Value = entry.call((parsed,)).catch(&ctx).map_err(|_| "script_error")?;
        let output = match output.as_promise() {
            Some(promise) => promise.finish::<Value>().catch(&ctx).map_err(|error| match error {
                rquickjs::CaughtError::Error(rquickjs::Error::WouldBlock) => "unsettled_promise",
                _ => "script_error",
            })?,
            None => output,
        };
        if output.is_null() || output.is_undefined() { return Ok(config.to_owned()); }
        let json = ctx.json_stringify(output).catch(&ctx).map_err(|_| "invalid_result")?
            .ok_or("invalid_result")?.to_string().map_err(|_| "invalid_result")?;
        if json.len() > INPUT_LIMIT { return Err("size_limit"); }
        let value: serde_json::Value = serde_json::from_str(&json).map_err(|_| "invalid_result")?;
        if !value.is_object() { return Err("invalid_result"); }
        Ok(json)
    });
    if cancelled.load(Ordering::Acquire) { return Err("cancelled"); }
    if Instant::now() >= deadline { return Err("timeout"); }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    fn run(script: &str) -> Result<String, &'static str> {
        evaluate_within(script, r#"{"mode":"rule","dns":{"nameserver":["1.1.1.1"]}}"#, Arc::new(AtomicBool::new(false)), Duration::from_secs(2), MEMORY_LIMIT)
    }
    #[test]
    fn real_javascript_transform_and_promise() {
        let value = run("const main = async c => ({...c, mode:'global', names:[...new Set(['a','a','b'])]});").unwrap();
        let value: serde_json::Value = serde_json::from_str(&value).unwrap();
        assert_eq!(value["mode"], "global");
        assert_eq!(value["names"], serde_json::json!(["a", "b"]));
        assert_eq!(value["dns"]["nameserver"][0], "1.1.1.1");
    }
    #[test]
    fn null_preserves_original_and_console_does_not_fail() {
        assert_eq!(run("function main(c){console.log(c);c.mode='global';return null}").unwrap(), r#"{"mode":"rule","dns":{"nameserver":["1.1.1.1"]}}"#);
    }
    #[test]
    fn errors_never_expose_configuration_or_script_text() {
        assert_eq!(run("function main(){throw new Error('secret-password')}").unwrap_err(), "script_error");
        assert_eq!(run("function main(){return []}").unwrap_err(), "invalid_result");
        assert_eq!(run("function main(){return new Promise(()=>{})}").unwrap_err(), "unsettled_promise");
        assert_eq!(run("const value=1").unwrap_err(), "missing_main");
        assert_eq!(run("function main( {").unwrap_err(), "script_error");
    }
    #[test]
    fn limits_infinite_loop_and_memory() {
        let started = Instant::now();
        assert_eq!(evaluate_within("function main(){while(true){}}", "{}", Arc::new(AtomicBool::new(false)), Duration::from_millis(30), MEMORY_LIMIT).unwrap_err(), "timeout");
        assert!(started.elapsed() < Duration::from_secs(3));
        let memory_error = evaluate_within("function main(){let a=[];while(true){a.push(new Array(10000).fill('data'))}}", "{}", Arc::new(AtomicBool::new(false)), Duration::from_secs(2), 2 * 1024 * 1024).unwrap_err();
        assert_ne!(memory_error, "timeout");
    }
    #[test]
    fn cancellation_interrupts_running_javascript() {
        let flag = Arc::new(AtomicBool::new(false));
        let interrupt = flag.clone();
        let worker = std::thread::spawn(move || evaluate_within("function main(){while(true){}}", "{}", flag, TIMEOUT, MEMORY_LIMIT));
        std::thread::sleep(Duration::from_millis(30));
        interrupt.store(true, Ordering::Release);
        assert_eq!(worker.join().unwrap().unwrap_err(), "cancelled");
    }
    #[test]
    fn cancellation_before_evaluation_is_retained() {
        let id = begin().unwrap(); cancel(id);
        assert_eq!(evaluate("function main(c){return c}", "{}", id).unwrap_err(), "cancelled");
        release(id);
    }
}
