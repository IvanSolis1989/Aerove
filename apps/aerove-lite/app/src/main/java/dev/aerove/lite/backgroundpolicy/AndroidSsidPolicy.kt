package dev.aerove.lite.backgroundpolicy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo

/** Consumed by the existing network callback, never registers a second network observer. */
object AndroidSsidPolicy {
    data class Access(val precise: Boolean, val background: Boolean, val locationEnabled: Boolean) {
        val granted: Boolean get() = precise && background && locationEnabled
    }

    fun access(context: Context): Access = Access(
        precise = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        background = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED,
        locationEnabled = context.getSystemService(LocationManager::class.java).isLocationEnabled,
    )

    fun read(capabilities: NetworkCapabilities?, preferences: BackgroundPolicyPreferences, access: Access): String? {
        if (preferences.excludeSSIDs.isEmpty() || !access.granted ||
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return null
        return BackgroundPolicyPreferences.ssidFromPlatform((capabilities.transportInfo as? WifiInfo)?.ssid)
    }
}
