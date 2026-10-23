package dev.aerove.lite.platform

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

sealed interface ExternalProfileIntent {
    data class Valid(val link: String) : ExternalProfileIntent
    data class Invalid(val reason: Reason) : ExternalProfileIntent

    enum class Reason { MALFORMED, UNSUPPORTED_SCHEME, INVALID_INSTALL_TARGET }

    companion object {
        private val schemes = setOf("clash", "clashmeta", "flclash")

        fun parse(link: String?): ExternalProfileIntent {
            if (link.isNullOrBlank()) return Invalid(Reason.MALFORMED)
            val uri = try {
                URI(link)
            } catch (_: URISyntaxException) {
                return Invalid(Reason.MALFORMED)
            }
            if (uri.scheme?.lowercase(Locale.ROOT) !in schemes) return Invalid(Reason.UNSUPPORTED_SCHEME)
            if (uri.rawAuthority != "install-config" || uri.rawPath !in setOf(null, "", "/") || uri.rawFragment != null) {
                return Invalid(Reason.INVALID_INSTALL_TARGET)
            }
            return Valid(link)
        }
    }
}
