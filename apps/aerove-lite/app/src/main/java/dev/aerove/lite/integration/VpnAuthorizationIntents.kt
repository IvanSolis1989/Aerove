package dev.aerove.lite.integration

import dev.aerove.lite.LiteSettings

data class VpnAuthorizationRequest(val token: Long, val settings: LiteSettings?, val baseline: LiteSettings?)
data class VpnAuthorizationResult(val request: VpnAuthorizationRequest, val granted: Boolean)

/** Retained by the existing controller, with one platform launch and only the latest queued intent. */
class VpnAuthorizationIntents(private val isCurrent: (Long) -> Boolean) {
    private var active: VpnAuthorizationRequest? = null
    private var queued: VpnAuthorizationRequest? = null
    private var activeSettingsCancelled = false

    @Synchronized fun offer(token: Long, settings: LiteSettings?, baseline: LiteSettings? = settings) { queued = VpnAuthorizationRequest(token, settings, baseline) }
    @Synchronized fun next(): VpnAuthorizationRequest? {
        if (active != null) return null
        val request = queued.also { queued = null } ?: return null
        if (!isCurrent(request.token)) return null
        active = request
        activeSettingsCancelled = false
        return request
    }
    @Synchronized fun complete(granted: Boolean): VpnAuthorizationResult? {
        val request = active.also { active = null } ?: return null
        val cancelled = activeSettingsCancelled.also { activeSettingsCancelled = false }
        return if (!cancelled && isCurrent(request.token)) VpnAuthorizationResult(request, granted) else null
    }
    @Synchronized fun cancelSettings() {
        if (queued?.settings != null) queued = null
        if (active?.settings != null) activeSettingsCancelled = true
    }
}
