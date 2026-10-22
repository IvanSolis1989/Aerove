package dev.aerove.lite.networksettings

import java.io.Closeable

internal interface BoundControlPort : Closeable { val port: Int }

/** Hold rejected binds until selection finishes, so the OS cannot offer the same port again. */
internal fun selectPrivateControlPort(settings: NetworkSettings, bind: () -> BoundControlPort): Int {
    settings.requireValid()
    val excluded = setOf(settings.ports.mixed, settings.ports.http, settings.ports.socks) - 0
    val held = mutableListOf<BoundControlPort>()
    var failure: Throwable? = null
    var selected: Int? = null
    try {
        for (attempt in 0..excluded.size) {
            val reservation = bind()
            held += reservation
            check(reservation.port in 1..65535) { "Invalid private control port reservation" }
            if (reservation.port !in excluded) { selected = reservation.port; break }
        }
        check(selected != null) { "Could not reserve a private controller port distinct from user proxy ports" }
    } catch (error: Throwable) {
        failure = error
    }
    for (reservation in held.asReversed()) {
        try { reservation.close() } catch (error: Exception) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
    }
    failure?.let { throw it }
    return checkNotNull(selected)
}
