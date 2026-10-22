package dev.aerove.lite.localization

import java.util.Locale

// The complete supported list comes from Standard arb/ and its localization delegate.
internal enum class LocaleChoice(val tag: String, val platformTag: String) {
    English("en", "en"), Spanish("es", "es"), French("fr", "fr"), Japanese("ja", "ja"),
    Korean("ko", "ko"), Russian("ru", "ru"), SimplifiedChinese("zh_CN", "zh-CN"),
}

internal fun canonicalLocale(value: String?): String? {
    val normalized = value?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
    if (!normalized.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*"))) return null
    val language = normalized.substringBefore('-').lowercase(Locale.ROOT)
    return LocaleChoice.entries.firstOrNull { it.platformTag.substringBefore('-') == language }?.tag
}

internal fun localeChoice(value: String?): LocaleChoice? =
    canonicalLocale(value)?.let { canonical -> LocaleChoice.entries.first { it.tag == canonical } }

internal fun localePlatformTag(value: String?): String? = localeChoice(value)?.platformTag
