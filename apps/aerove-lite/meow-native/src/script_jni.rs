// SPDX-License-Identifier: GPL-3.0-only
use jni::{EnvUnowned, errors::ThrowRuntimeExAndDefault, objects::{JObject, JString}};
use crate::script_engine;

#[derive(Debug)]
struct ScriptBridgeError(String);

impl std::fmt::Display for ScriptBridgeError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl std::error::Error for ScriptBridgeError {}

impl From<jni::errors::Error> for ScriptBridgeError {
    fn from(error: jni::errors::Error) -> Self { Self(error.to_string()) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_overrides_NativeScriptEngine_nativeBegin<'local>(mut env: EnvUnowned<'local>, _this: JObject<'local>) -> i64 {
    env.with_env(|_| -> Result<i64, ScriptBridgeError> { script_engine::begin().map_err(|code| ScriptBridgeError(code.into())) }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_overrides_NativeScriptEngine_nativeEvaluate<'local>(
    mut env: EnvUnowned<'local>, _this: JObject<'local>, script: JString<'local>, config: JString<'local>, operation: i64,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, ScriptBridgeError> {
        let script = script.try_to_string(env)?;
        let config = config.try_to_string(env)?;
        let result = std::panic::catch_unwind(|| script_engine::evaluate(&script, &config, operation));
        let result = match result {
            Ok(Ok(config)) => serde_json::json!({"ok": true, "config": config}),
            Ok(Err(code)) => serde_json::json!({"ok": false, "code": code}),
            Err(_) => serde_json::json!({"ok": false, "code": "engine"}),
        };
        Ok(env.new_string(result.to_string())?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_overrides_NativeScriptEngine_nativeCancel<'local>(mut env: EnvUnowned<'local>, _this: JObject<'local>, operation: i64) {
    env.with_env(|_| -> Result<(), ScriptBridgeError> { script_engine::cancel(operation); Ok(()) }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_overrides_NativeScriptEngine_nativeRelease<'local>(mut env: EnvUnowned<'local>, _this: JObject<'local>, operation: i64) {
    env.with_env(|_| -> Result<(), ScriptBridgeError> { script_engine::release(operation); Ok(()) }).resolve::<ThrowRuntimeExAndDefault>()
}
