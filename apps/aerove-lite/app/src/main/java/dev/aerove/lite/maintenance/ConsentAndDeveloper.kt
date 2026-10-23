package dev.aerove.lite.maintenance

import kotlin.random.Random

const val CURRENT_DISCLAIMER_VERSION = 1

class DeveloperUnlock {
    private var previous: Long? = null
    private var taps = 0
    fun tap(monotonicMillis: Long): Boolean {
        val elapsed = previous?.let { monotonicMillis - it }
        taps = if (elapsed == null || elapsed < 0 || elapsed >= 1000) 1 else taps + 1
        previous = monotonicMillis
        return (taps >= 5).also { if (it) reset() }
    }
    fun reset() { previous = null; taps = 0 }
}

enum class Restatement { Partial, Diverged, Complete }

fun restatement(text: String, statement: String, composingStart: Int = -1, composingEnd: Int = -1): Restatement {
    if (text.trim() == statement) return Restatement.Complete
    val committed = if (composingStart in 0..text.length && composingEnd in composingStart..text.length)
        text.removeRange(composingStart, composingEnd) else text
    return if (statement.startsWith(committed.trimStart())) Restatement.Partial else Restatement.Diverged
}

/** Feed the existing bounded app-log owner on its worker dispatcher, only after a user test action. */
fun developerLogSamples(random: Random = Random.Default): List<String> {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return List(1000) { index ->
        "[$index]" + buildString { repeat(random.nextInt(20, 201)) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }
}
