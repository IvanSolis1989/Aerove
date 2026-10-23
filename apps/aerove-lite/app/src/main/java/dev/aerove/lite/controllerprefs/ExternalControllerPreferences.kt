package dev.aerove.lite.controllerprefs

import java.security.SecureRandom
import org.json.JSONObject

data class ExternalControllerPreferences(val enabled: Boolean = false, val secret: String = "") {
    fun validate(): ExternalControllerPreferences = apply {
        require(secret.length <= MAX_SECRET_LENGTH) { "Invalid external controller secret length" }
    }

    fun withEnabled(enabled: Boolean): ExternalControllerPreferences =
        if (!enabled) ExternalControllerPreferences()
        else copy(enabled = true, secret = secret.ifEmpty { randomSecret() }).validate()

    fun withSecret(input: String): ExternalControllerPreferences {
        val normalized = input.trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_SECRET_LENGTH) {
            "Invalid external controller secret length"
        }
        return copy(secret = normalized).validate()
    }

    fun effective(safeMode: Boolean): UserControllerPlan =
        UserControllerPlan(enabled && !safeMode && secret.isNotEmpty(), if (enabled && !safeMode) secret else "")

    override fun toString(): String = "ExternalControllerPreferences(enabled=$enabled, secret=<redacted>)"

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 9090
        const val ADDRESS = "$HOST:$PORT"
        const val MAX_SECRET_LENGTH = 512
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private fun randomSecret(): String {
            val random = SecureRandom()
            return buildString(32) { repeat(32) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
        }
    }
}

/** User listener only. The app's randomly allocated private control endpoint stays separate. */
class UserControllerPlan internal constructor(val enabled: Boolean, val secret: String) {
    val address: String get() = ExternalControllerPreferences.ADDRESS
    val reservedPort: Int? get() = ExternalControllerPreferences.PORT.takeIf { enabled }
    override fun toString(): String = "UserControllerPlan(enabled=$enabled, address=$address, secret=<redacted>)"
}

fun encodeExternalController(value: ExternalControllerPreferences): JSONObject = value.validate().let {
    JSONObject().put("enabled", it.enabled).put("secret", it.secret)
}

fun decodeExternalController(row: JSONObject?): ExternalControllerPreferences {
    if (row == null) return ExternalControllerPreferences()
    val enabled = if (!row.has("enabled")) false else row.get("enabled") as? Boolean
        ?: throw IllegalArgumentException("Invalid external controller enabled field")
    val secret = if (!row.has("secret")) "" else row.get("secret") as? String
        ?: throw IllegalArgumentException("Invalid external controller secret field")
    return ExternalControllerPreferences(enabled, secret).validate()
}
