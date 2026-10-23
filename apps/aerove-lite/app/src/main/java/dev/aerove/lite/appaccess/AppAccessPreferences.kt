package dev.aerove.lite.appaccess

import org.json.JSONArray
import org.json.JSONObject

enum class AppAccessMode { Include, Exclude }
enum class AppAccessSort { None, Name, Time }

data class InstalledApplication(val packageName: String, val label: String, val system: Boolean,
    val internet: Boolean, val lastUpdateTime: Long)

data class AppAccessPreferences(
    val enabled: Boolean = false,
    val mode: AppAccessMode = AppAccessMode.Exclude,
    val includePackages: Set<String> = emptySet(),
    val excludePackages: Set<String> = emptySet(),
    val sort: AppAccessSort = AppAccessSort.None,
    val hideSystem: Boolean = true,
    val hideNoInternet: Boolean = true,
) {
    init {
        require(includePackages.size <= MAX_ACCESS_PACKAGES && excludePackages.size <= MAX_ACCESS_PACKAGES)
        require((includePackages + excludePackages).all(::validAccessPackage)) { "Invalid application selection" }
    }
    val selected: Set<String> get() = if (mode == AppAccessMode.Include) includePackages else excludePackages
    fun withSelected(value: Set<String>): AppAccessPreferences = if (mode == AppAccessMode.Include)
        copy(includePackages = value.toSet()) else copy(excludePackages = value.toSet())

    /** Standard only normalizes the active collection, using source filters rather than the search query. */
    fun forSave(applications: List<InstalledApplication>): AppAccessPreferences {
        if (applications.isEmpty()) return this
        val visible = applications.asSequence().filter { visibleApplication(it, this) }.map { it.packageName }.toSet()
        return withSelected(selected.intersect(visible).sorted().toSet())
    }
}

internal const val MAX_ACCESS_PACKAGES = 20_000
internal fun validAccessPackage(value: String): Boolean = value.length in 1..255 &&
    value.split('.').all { segment -> segment.isNotEmpty() && segment.first().let { it.isLetter() || it == '_' } &&
        segment.all { it.code in 32..126 && (it.isLetterOrDigit() || it == '_') } }

fun visibleApplication(application: InstalledApplication, preferences: AppAccessPreferences): Boolean =
    (!preferences.hideSystem || !application.system) && (!preferences.hideNoInternet || application.internet)

fun viewApplications(applications: List<InstalledApplication>, preferences: AppAccessPreferences,
    pinned: Set<String>, query: String): List<InstalledApplication> {
    val comparator = Comparator<InstalledApplication> { a, b ->
        val aPinned = a.packageName in pinned
        val bPinned = b.packageName in pinned
        if (aPinned != bPinned) if (aPinned) -1 else 1
        else when (preferences.sort) {
            AppAccessSort.None -> 0
            AppAccessSort.Name -> a.label.compareTo(b.label)
            AppAccessSort.Time -> b.lastUpdateTime.compareTo(a.lastUpdateTime)
        }
    }
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return applications.filter { application -> visibleApplication(application, preferences) &&
        terms.all { application.label.contains(it, true) || application.packageName.contains(it, true) } }.sortedWith(comparator)
}

fun importAccessClipboard(text: String): Set<String> {
    require(text.length <= 1024 * 1024) { "Application clipboard is too large" }
    val packages = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
    require(packages.size <= MAX_ACCESS_PACKAGES && packages.all(::validAccessPackage)) { "Invalid application clipboard" }
    return packages
}

object AppAccessPreferencesJson {
    private val keys = setOf("enabled", "mode", "includePackages", "excludePackages", "sort", "hideSystem", "hideNoInternet")
    fun encode(value: AppAccessPreferences): JSONObject = JSONObject().put("enabled", value.enabled)
        .put("mode", value.mode.name).put("includePackages", JSONArray(value.includePackages.sorted()))
        .put("excludePackages", JSONArray(value.excludePackages.sorted())).put("sort", value.sort.name)
        .put("hideSystem", value.hideSystem).put("hideNoInternet", value.hideNoInternet)

    fun decode(value: JSONObject?): AppAccessPreferences {
        if (value == null) return AppAccessPreferences()
        require(value.keys().asSequence().all { it in keys }) { "Unknown app access field" }
        fun boolean(key: String, fallback: Boolean): Boolean = if (!value.has(key)) fallback else {
            require(value.get(key) is Boolean) { "Invalid app access boolean" }; value.getBoolean(key)
        }
        fun packages(key: String): Set<String> {
            if (!value.has(key)) return emptySet()
            val array = value.get(key) as? JSONArray ?: throw IllegalArgumentException("Invalid app access collection")
            require(array.length() <= MAX_ACCESS_PACKAGES)
            return List(array.length()) { index -> array.get(index) as? String ?: throw IllegalArgumentException("Invalid package") }.toSet()
        }
        fun string(key: String, fallback: String): String = if (!value.has(key)) fallback else
            value.get(key) as? String ?: throw IllegalArgumentException("Invalid app access enum")
        return AppAccessPreferences(boolean("enabled", false), AppAccessMode.valueOf(string("mode", "Exclude")),
            packages("includePackages"), packages("excludePackages"), AppAccessSort.valueOf(string("sort", "None")),
            boolean("hideSystem", true), boolean("hideNoInternet", true))
    }
}
