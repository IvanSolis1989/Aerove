package com.follow.clash.service.modules

import android.app.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal interface ServiceModule {
    fun start()

    fun stop()
}

// Active TUN uses the system binding; parked and proxy runs retain foreground keepalive.
internal class ServiceModules(
    private val service: Service,
    private var foreground: Boolean,
) {
    private var scope: CoroutineScope? = null
    private var modules = emptyList<ServiceModule>()

    @Synchronized
    fun start() {
        if (scope != null) return

        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nextModules = listOfNotNull(
            if (foreground) NotificationModule(service, nextScope) else null,
        )
        val startedModules = mutableListOf<ServiceModule>()

        try {
            nextModules.forEach { module ->
                module.start()
                startedModules.add(module)
            }
            scope = nextScope
            modules = nextModules
        } catch (error: Throwable) {
            nextScope.cancel()
            startedModules.asReversed().forEach { module ->
                runCatching { module.stop() }
            }
            throw error
        }
    }

    @Synchronized
    fun setForeground(enabled: Boolean) {
        foreground = enabled
        val currentScope = scope
        if (currentScope == null) { start(); return }
        if (enabled && modules.isEmpty()) {
            val notification = NotificationModule(service, currentScope)
            notification.start()
            modules = listOf(notification)
        } else if (!enabled && modules.isNotEmpty()) {
            modules.asReversed().forEach { it.stop() }
            modules = emptyList()
        }
    }

    @Synchronized
    fun stop() {
        val currentScope = scope ?: return
        val currentModules = modules
        scope = null
        modules = emptyList()

        currentScope.cancel()
        currentModules.asReversed().forEach { module ->
            runCatching { module.stop() }
        }
    }
}
