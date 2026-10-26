package dev.aerove.lite.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

// Ports of Standard's getTimeText (datetime.dart) and dashboardTraffic (style.dart, num.dart); Dart ignores the locale.
internal fun formatUptime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    if (seconds / 3600 > 999) return "999:59:59"
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

internal fun dashboardTraffic(bytes: Long): Pair<String, String> {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
    return BigDecimal(size).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() to units[unit]
}
