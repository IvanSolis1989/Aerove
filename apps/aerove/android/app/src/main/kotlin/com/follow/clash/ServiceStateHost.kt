package com.follow.clash

import com.follow.clash.models.SharedState
import com.follow.clash.service.models.NotificationParams
import com.follow.clash.service.models.VpnOptions
import kotlinx.coroutines.CoroutineScope

internal data class ServiceRuntime(
    val vpn: Boolean,
    val startedAtMillis: Long,
    val requested: VpnOptions,
    val parked: Boolean = false,
)

/**
 * The machine owns the arbitration and the transitions; this is only the part that cannot run on a
 * plain JVM, so unit tests can drive the state machine with an in-memory implementation.
 */
internal interface ServiceStateHost {
    val scope: CoroutineScope
    val runtime: ServiceRuntime?
    val homeDirPath: String
    val sdkInt: Int

    fun log(message: String)

    fun showToast(message: String)

    fun updateNotificationParams(params: NotificationParams)

    fun loadSharedState(): SharedState

    fun isVpnPermissionGranted(): Boolean

    fun isLocalNetworkPermissionGranted(): Boolean

    fun tile(): TileGateway?

    fun app(): AppGateway?

    fun notifyStopped()

    suspend fun quickSetup(initParams: String, setupParams: String): Result<String>

    suspend fun startService(options: VpnOptions, requested: VpnOptions): Boolean

    suspend fun parkService(options: VpnOptions, requested: VpnOptions): Boolean

    suspend fun settleServiceNotification()

    suspend fun stopService()
}

internal fun interface TileGateway {
    fun handleStart()
}

internal interface AppGateway {
    fun requestNotificationPermission(callback: (Boolean) -> Unit)

    fun requestLocalNetworkPermission(callback: (Boolean) -> Unit)

    fun prepareVpn(enable: Boolean, callback: (Boolean) -> Unit)

    fun cancelVpnPreparation(callback: (Boolean) -> Unit)
}
