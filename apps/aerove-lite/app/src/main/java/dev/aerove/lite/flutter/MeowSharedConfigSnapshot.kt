package dev.aerove.lite.flutter

// Two complete disk observations; never consult preferences caches or backup files.
internal fun nativeLiteSharedConfigSnapshot(
    disk: () -> String?,
    pause: () -> Unit = { Thread.sleep(10) },
): String {
    repeat(3) { attempt ->
        val first = disk()
        val second = disk()
        if (first != null && first == second) return first
        if (attempt < 2) pause()
    }
    error("shared_config_unstable")
}
