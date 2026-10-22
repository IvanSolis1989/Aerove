// SPDX-License-Identifier: GPL-3.0-only
use jni::{EnvUnowned, errors::ThrowRuntimeExAndDefault, objects::{JObject, JString}};
use crate::{BridgeError, script_engine};

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_overrides_NativeScriptEngine_nativeBegin<'local>(mut env: EnvUnowned<'local>, _this: JObject<'local>) -> i64 {
    env.with_env(|_| -> Result<i64, BridgeError> { script_engine::begin().map_err(|code| BridgeError(code.into())) }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_overrides_NativeScriptEngine_nativeEvaluate<'local>(
    mut env: EnvUnowned<'local>, _this: JObject<'local>, script: JString<'local>, config: JString<'local>, operation: i64,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
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
    env.with_env(|_| -> Result<(), BridgeError> { script_engine::cancel(operation); Ok(()) }).resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_aerove_lite_overrides_NativeScriptEngine_nativeRelease<'local>(mut env: EnvUnowned<'local>, _this: JObject<'local>, operation: i64) {
    env.with_env(|_| -> Result<(), BridgeError> { script_engine::release(operation); Ok(()) }).resolve::<ThrowRuntimeExAndDefault>()
}
