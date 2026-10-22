package com.follow.clash.common

/** Only the listener in the slot can clear it, so an owner torn down late spares its successor. */
class ListenerSlot<T : Any>(private val install: (T?) -> Unit) {
    private var current: T? = null

    @Synchronized
    fun set(listener: T) {
        install(listener)
        current = listener
    }

    @Synchronized
    fun clear(listener: T): Boolean {
        if (current !== listener) {
            return false
        }
        install(null)
        current = null
        return true
    }
}
