package dev.aerove.lite.data

data class ProfileLinkSource(val inputIndex: Int, val line: Int = 1)

enum class ProfileLinkErrorCode {
    INVALID_INPUT, INVALID_URL, INVALID_ENCODING, INVALID_CONFIGURATION,
    DUPLICATE_PARAMETER, UNSUPPORTED_SCHEME, UNSUPPORTED_CAPABILITY, LIMIT_EXCEEDED,
}

data class ProfileLinkError(
    val source: ProfileLinkSource,
    val code: ProfileLinkErrorCode,
    val field: String,
    val reason: String,
)

sealed interface ProfileLinkEntry {
    val name: String
    val sources: List<ProfileLinkSource>

    data class Subscription(
        override val name: String,
        val url: String,
        override val sources: List<ProfileLinkSource>,
    ) : ProfileLinkEntry {
        override fun toString(): String = "Subscription(name=$name, sources=$sources)"
    }

    data class Inline(
        override val name: String,
        val config: String,
        val kind: Kind,
        val nodeCount: Int,
        override val sources: List<ProfileLinkSource>,
        val firstNodeName: String? = null,
    ) : ProfileLinkEntry {
        enum class Kind { CONFIGURATION, SHARE_LINKS }
        override fun toString(): String = "Inline(name=$name, kind=$kind, nodeCount=$nodeCount, sources=$sources)"
    }
}

data class ProfileLinkBatch(
    val entries: List<ProfileLinkEntry>,
    val errors: List<ProfileLinkError>,
    val rejectedShareSources: List<ProfileLinkSource>,
)

data class ProfileLinkLimits(
    val maxInputs: Int = 512,
    val maxInputBytes: Int = 4 * 1024 * 1024,
    val maxTotalBytes: Int = 8 * 1024 * 1024,
    val maxLinks: Int = 1024,
    val maxNodes: Int = 2048,
    val maxLinkBytes: Int = 64 * 1024,
    val maxParameters: Int = 64,
    val maxParameterBytes: Int = 16 * 1024,
) {
    init {
        require(maxInputs in 1..4096 && maxLinks in 1..4096 && maxNodes in 1..10000)
        require(maxInputBytes in 1..4 * 1024 * 1024 && maxTotalBytes in maxInputBytes..16 * 1024 * 1024)
        require(maxLinkBytes in 1..maxInputBytes && maxParameters in 1..256 && maxParameterBytes in 1..maxLinkBytes)
    }
}
