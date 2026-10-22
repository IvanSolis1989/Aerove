package dev.aerove.lite.data

import dev.aerove.lite.ProfileSummary
import java.io.IOException
import java.io.File

data class ConfigIssue(val path: String, val reason: String)

class ConfigValidationException(val issues: List<ConfigIssue>) :
    IOException(issues.joinToString("\n") { "${it.path}: ${it.reason}" })

fun interface NativeConfigValidator {
    // The bridge must run the pinned parser, conversion and strict unknown-field validation.
    fun validate(yaml: String): String?
}

fun interface ProfileValidator {
    fun validate(yaml: String)
}

fun interface NativeResourceValidator {
    fun validate(kind: String, path: String, behavior: String, format: String): String?
}

data class PreparedProfile internal constructor(
    val profileId: String,
    val source: String,
    val yaml: String,
    val revision: String,
    val generation: String?,
    val resourceDirectory: File?,
    val nextRefreshAt: Long?,
)

class ProfileLease internal constructor(internal val root: String, internal val token: String)

data class ProfileSnapshot(
    val profiles: List<ProfileSummary>,
    val selectedId: String?,
)

class ProfileConflictException(id: String) : IOException("Profile $id changed during update; retry")

class ControlEndpoint(val port: Int, val secret: String) {
    init {
        require(port in 1..65535) { "Invalid controller port" }
        require(secret.length >= 32 && secret.all { it.code in 33..126 }) {
            "Controller secret must contain at least 32 printable ASCII characters"
        }
    }

    override fun toString(): String = "ControlEndpoint(127.0.0.1:$port, secret=<redacted>)"
}

class CoreApiException(val statusCode: Int, val operation: String) :
    IOException("Core API $operation returned HTTP $statusCode")
