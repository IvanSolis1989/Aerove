package dev.aerove.lite.ui

/** Main-thread presentation clocks share one grid; the last stopped clock releases its epoch. */
internal object LiteFrameGrid {
    private val members = mutableMapOf<Any, Int>()
    private var epoch: Long? = null
    fun join(token: Any) { members[token] = 15 }
    fun leave(token: Any) { members.remove(token); if (members.isEmpty()) epoch = null }
    fun next(token: Any, now: Long, fps: Int): Long {
        require(token in members && fps in 1..60)
        if (epoch == null || (members.size == 1 && members[token] != fps)) epoch = now
        members[token] = fps
        val grid = epoch!!
        val frame = (now - grid).coerceAtLeast(0) * fps / 1_000_000_000L
        return grid + ((frame + 1) * 1_000_000_000L + fps - 1) / fps
    }
}
