package dev.aerove.lite.editor

internal class EditorSearchPublication(
    private val post: (Runnable) -> Boolean,
    private val remove: (Runnable) -> Unit,
) {
    private var pending: Runnable? = null
    private var closed = false

    fun schedule(current: () -> Boolean, publish: () -> Unit) {
        invalidate()
        if (closed) return
        lateinit var task: Runnable
        task = Runnable {
            if (!closed && pending === task) {
                pending = null
                if (current()) publish()
            }
        }
        pending = task
        if (!post(task) && pending === task) pending = null
    }

    fun invalidate() {
        val previous = pending
        pending = null
        previous?.let(remove)
    }

    fun close() { closed = true; invalidate() }
}
