package dev.aerove.lite.flutter

import dev.aerove.lite.data.NativeConfigValidator
import dev.aerove.lite.profilebuilder.RealGoFilterExecutor
import java.io.File
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking<Unit> {
    val home = File(args.single())
    val unused = NativeConfigValidator { error("This fixture only builds profiles and matches filters") }
    val config = MeowConfigProtocol(home, validator = unused, verifiedNativeFeatures = { setOf("ss") })
    val offline = MeowOfflineMethods(home, validator = unused, features = { setOf("ss") }, filters = RealGoFilterExecutor())
    val methods = setOf("getConfig", "getProfileBase", "buildProfile", "matchFilters")
    val dispatcher = MeowProtocolDispatcher(home, methods.associateWith { method -> MeowProtocolMethod { arguments ->
        if (method == "matchFilters") offline.invoke(method, arguments) else config.invoke(method, arguments)
    } })
    try {
        System.out.write(dispatcher.invoke(System.`in`.bufferedReader().readText()))
        System.out.flush()
    } finally {
        check(dispatcher.closeAndJoin())
    }
}
