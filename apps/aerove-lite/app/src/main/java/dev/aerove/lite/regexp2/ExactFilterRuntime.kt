package dev.aerove.lite.regexp2

/** Runtime dependency supplied by the application. There is no approximate or silent fallback. */
fun interface ExactFilterExecutor {
    suspend fun indices(include: List<String>, exclude: List<String>, names: List<String>, validateOnly: Boolean): List<Int>
}

object ExactFilterRuntime {
    @Volatile private var executor: ExactFilterExecutor? = null
    fun install(executor: ExactFilterExecutor) { this.executor = executor }
    suspend fun indices(include: List<String>, exclude: List<String>, names: List<String>, validateOnly: Boolean = false): List<Int> =
        requireNotNull(executor) { "Exact filter service is not initialized" }.indices(include, exclude, names, validateOnly)
}
