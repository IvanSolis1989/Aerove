package dev.aerove.lite

import android.app.Application
import dev.aerove.lite.core.NativeCore
import dev.aerove.lite.data.CompatibilityValidator
import dev.aerove.lite.data.LiteSettingsStore
import dev.aerove.lite.data.NativeConfigValidator
import dev.aerove.lite.data.NativeResourceValidator
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.ResourceBundleManager
import dev.aerove.lite.vpn.VpnSnapshot
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

class LiteApplication : Application() {
    internal val nativeLifecycle = Mutex()
    val profiles by lazy {
        val runtime = File(filesDir, "runtime")
        val validator = CompatibilityValidator(NativeConfigValidator { NativeCore.validate(it) }, runtime)
        val resources = ResourceBundleManager(runtime, validator, NativeResourceValidator { kind, path, behavior, format ->
            NativeCore.validateResource(kind, path, behavior, format)
        })
        ProfileRepository(filesDir, validator, resources = resources)
    }
    val settings by lazy { LiteSettingsStore(filesDir) }
    private val mutableVpn = MutableStateFlow(VpnSnapshot())
    private var owner = 0L
    val vpn = mutableVpn.asStateFlow()

    @Synchronized
    internal fun registerVpnOwner(): Long = ++owner

    @Synchronized
    internal fun publishVpn(owner: Long, snapshot: VpnSnapshot) {
        if (this.owner == owner) mutableVpn.value = snapshot
    }
}
