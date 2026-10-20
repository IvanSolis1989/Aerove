package com.follow.clash.service.modules

import com.follow.clash.service.models.NotificationParams
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

internal data class NotificationUpdate(
    val params: NotificationParams,
    val showSpeed: Boolean,
    val session: Any,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun notificationUpdates(
    screenOn: Flow<Boolean>,
    uiActive: StateFlow<Boolean>,
    params: StateFlow<NotificationParams>,
): Flow<NotificationUpdate> = combine(screenOn, uiActive) { screen, active ->
    screen && active
}.flatMapLatest { showSpeed ->
    val session = Any()
    if (showSpeed) {
        combine(
            flow {
                while (true) {
                    emit(Unit)
                    delay(1_000)
                }
            },
            params,
        ) { _, current -> NotificationUpdate(current, true, session) }
    } else {
        params.map { NotificationUpdate(it, false, session) }
    }
}
