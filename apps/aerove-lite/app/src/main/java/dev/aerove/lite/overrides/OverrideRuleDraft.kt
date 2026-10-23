package dev.aerove.lite.overrides

/** Same added-rule fields, parameter order and payload validation as Standard RuleForm/Rule.parse. */
data class OverrideRuleDraft(
    val action: String = "DOMAIN",
    val content: String = "",
    val target: String = "DIRECT",
    val src: Boolean = false,
    val noResolve: Boolean = false,
) {
    val hasParams: Boolean get() = action in parameterActions
    val issue: RuleDraftIssue? get() {
        if (content.isEmpty()) return RuleDraftIssue.ContentRequired
        if ('\n' in content || '\r' in content) return RuleDraftIssue.InvalidPayload
        val payload = content.trim()
        if (action == "NETWORK" && payload.lowercase() !in listOf("tcp", "udp")) return RuleDraftIssue.Network
        if (action in rangeActions) {
            val bounds = parseRanges(payload) ?: return RuleDraftIssue.NumberRange
            if (action == "DSCP" && bounds.any { it > 63 }) return RuleDraftIssue.DscpRange
        }
        if (target.isEmpty()) return RuleDraftIssue.TargetRequired
        return null
    }

    fun rawValue(): String {
        require(issue == null) { "Invalid rule draft: $issue" }
        return buildList {
            add(action); add(content); add(target)
            if (hasParams) { if (src) add("src"); if (noResolve) add("no-resolve") }
        }.joinToString(",")
    }

    companion object {
        val actions = listOf("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "DOMAIN-WILDCARD", "GEOSITE",
            "IP-CIDR", "IP-CIDR6", "IP-SUFFIX", "IP-ASN", "GEOIP", "SRC-GEOIP", "SRC-IP-ASN", "SRC-IP-CIDR", "SRC-IP-SUFFIX",
            "DST-PORT", "SRC-PORT", "IN-PORT", "IN-TYPE", "IN-USER", "IN-NAME", "REMATCH-NAME", "PROCESS-PATH",
            "PROCESS-PATH-REGEX", "PROCESS-PATH-WILDCARD", "PROCESS-NAME", "PROCESS-NAME-REGEX", "PROCESS-NAME-WILDCARD",
            "UID", "NETWORK", "DSCP", "AND", "OR", "NOT")
        val targets = listOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "PASS-RULE", "COMPATIBLE", "GLOBAL", "MATCH")
        internal val parameterActions = setOf("GEOIP", "IP-ASN", "IP-CIDR", "IP-CIDR6", "IP-SUFFIX", "RULE-SET")
        private val commaActions = setOf("AND", "OR", "NOT", "SUB-RULE", "DOMAIN-REGEX", "PROCESS-NAME-REGEX", "PROCESS-PATH-REGEX")
        private val rangeActions = setOf("DST-PORT", "SRC-PORT", "IN-PORT", "UID", "DSCP")

        fun parse(line: String): OverrideRuleDraft {
            val fields = line.split(',').map(String::trim)
            val action = fields.first().uppercase()
            val rest = fields.drop(1)
            return if (action in commaActions) OverrideRuleDraft(action, rest.dropLast(1).joinToString(","), rest.lastOrNull().orEmpty())
            else OverrideRuleDraft(action, rest.getOrNull(0).orEmpty(), rest.getOrNull(1).orEmpty(), "src" in rest.drop(2), "no-resolve" in rest.drop(2))
        }

        private fun parseRanges(payload: String): List<Long>? {
            if (payload == "*") return null
            val segments = payload.replace(',', '/').split('/')
            if (segments.size > 28) return null
            val bounds = mutableListOf<Long>()
            for (segment in segments) {
                if (segment.trim().isEmpty()) continue
                val parts = segment.trim().split('-')
                if (parts.size > 2) return null
                for (part in parts) {
                    val bound = part.replace(Regex("[\\[\\] ]"), "").toLongOrNull() ?: return null
                    if (bound < 0) return null
                    bounds += bound
                }
            }
            return bounds.takeIf { it.isNotEmpty() }
        }
    }
}

enum class RuleDraftIssue { ContentRequired, TargetRequired, Network, NumberRange, DscpRange, InvalidPayload }
