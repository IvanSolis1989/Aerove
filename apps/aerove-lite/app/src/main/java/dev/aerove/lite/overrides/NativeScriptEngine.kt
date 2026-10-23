package dev.aerove.lite.overrides

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Separate QuickJS runtime per invocation; no host filesystem/network bindings. */
class NativeScriptEngine : ScriptEngine {
    override suspend fun evaluate(script: String, configJson: String): String = withContext(Dispatchers.IO) {
        val operation = nativeBegin()
        val cancellation = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { nativeCancel(operation) }
        }
        try {
            currentCoroutineContext().ensureActive()
            val result = JSONObject(nativeEvaluate(script, configJson, operation))
            currentCoroutineContext().ensureActive()
            if (!result.getBoolean("ok")) throw ScriptExecutionException(result.getString("code"))
            result.getString("config")
        } finally {
            cancellation.cancel()
            nativeRelease(operation)
        }
    }

    private external fun nativeBegin(): Long
    private external fun nativeEvaluate(script: String, config: String, operation: Long): String
    private external fun nativeCancel(operation: Long)
    private external fun nativeRelease(operation: Long)

    companion object { init { System.loadLibrary("aerove_core") } }
}
