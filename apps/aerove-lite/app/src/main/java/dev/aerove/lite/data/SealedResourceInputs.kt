package dev.aerove.lite.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.json.JSONObject

internal class SealedResourceInputs(val origins: List<Origin>, val shared: List<SharedInput> = emptyList()) {
    class SharedInput(val key: String, val file: SealedFlutterResource, val receiptFile: SealedFlutterResource?, val metadata: JSONObject?)

    fun cached(key: String, url: String, behavior: String, format: String, fetch: ProviderFetchPolicy?, policy: ProviderRequestPolicy?, now: Long): SharedInput? {
        val input=shared.singleOrNull {it.key==key} ?: return null
        val data=input.metadata ?: return null
        val source=data.optJSONObject("sourceDefinition") ?: return null
        if(source.optString("type")!="http" || source.optString("url")!=url || source.optString("behavior")!=behavior || source.optString("format",if(key in setOf("$.mmdb","$.asn-mmdb","$.geosite")) "" else "yaml")!=format) return null
        val stored=ProviderFetchPolicy.from(source.keys().asSequence().associateWith { unwrap(source.opt(it)) }) ?: ProviderFetchPolicy(null,emptyMap())
        if(stored != (fetch ?: ProviderFetchPolicy(null,emptyMap()))) return null
        val originalPolicy=data.optJSONObject("requestPolicy")
        if(originalPolicy!=null && policy!=null && (originalPolicy.optString("userAgent")!=policy.userAgent || originalPolicy.optBoolean("checkCertificate")!=policy.checkCertificate)) return null
        val at=data.getLong("updatedAt");val interval=data.getLong("interval")
        if(at<=0 || at>now || interval<0 || interval>Long.MAX_VALUE/1000 || interval>0 && now-at>interval*1000 || data.has("cacheMaxAgeMillis") && (data.getLong("cacheMaxAgeMillis")<0 || now-at>data.getLong("cacheMaxAgeMillis"))) return null
        checkedShared(input)
        return input
    }
    private fun unwrap(value: Any?): Any? = when(value) {
        null,JSONObject.NULL ->null
        is JSONObject ->value.keys().asSequence().associateWith {unwrap(value.opt(it))}
        is org.json.JSONArray ->(0 until value.length()).map {unwrap(value.opt(it))}
        else ->value
    }
    private fun checkedShared(input: SharedInput) {
        for(file in listOfNotNull(input.file,input.receiptFile)) {
            val source=File(file.sourcePath)
            require(source.toPath()==source.toPath().toRealPath() && Files.isRegularFile(source.toPath(),NOFOLLOW_LINKS) && sha256(source)==file.sha256) { "Shared resource changed during preparation" }
        }
    }
    class Origin(val lease: ResourceGenerationLease, val files: List<SealedFlutterResource>)

    fun resolve(path: String, key: String? = null): SealedFlutterResource? = shared.firstOrNull {
        (key==null || it.key==key) && (File(it.file.sourcePath).absoluteFile==File(path).absoluteFile || it.metadata?.optJSONObject("nativeRecord")?.optString("localPath")==path)
    }?.file ?: origins.asSequence().flatMap { it.files.asSequence() }
        .firstOrNull { it.relativePath.startsWith("assets/") && File(it.sourcePath).absoluteFile == File(path).absoluteFile }

    fun verify() {
        origins.forEach { origin ->require(readFiles(origin.lease.directory)==origin.files) { "Sealed input changed during preparation" } }
        shared.forEach(::checkedShared)
    }

    fun close() {
        var failure: Throwable?=null
        origins.asReversed().forEach { origin ->try {origin.lease.close()} catch(problem: Throwable) {if(failure==null) failure=problem else failure!!.addSuppressed(problem)} }
        failure?.let {throw it}
    }

    companion object {
        fun readFiles(directory: File): List<SealedFlutterResource> {
            val manifest = JSONObject(checkedResource(directory, "manifest.json").inputStream().use { it.readBounded(1024 * 1024).strictUtf8() })
            require(manifest.getInt("schema") == 1 &&
                manifest.getString("coreVersion") == "meow-rs/0.26.1/727ddcd06f592e2f12c5222c4de6cce328df808c" &&
                manifest.getString("profileId") == directory.parentFile.name && manifest.getString("generation") == directory.name) { "Sealed generation lineage mismatch" }
            for ((name, key) in listOf("input.yaml" to "compiledInputSha256", "config.yaml" to "configSha256")) {
                val file = checkedResource(directory, name)
                require(file.length() in 1..(4 * 1024 * 1024) && sha256(file) == manifest.getString(key)) { "Sealed configuration lineage changed" }
            }
            val records = manifest.getJSONArray("records")
            require(records.length() <= 4096) { "Sealed resource count exceeds budget" }
            val names = linkedSetOf("input.yaml", "config.yaml", "manifest.json")
            var total = 0L
            repeat(records.length()) {
                val row = records.getJSONObject(it); val name = row.getString("file")
                require(names.add(name)) { "Duplicate sealed resource path" }
                val file = checkedResource(directory, name)
                require(file.length() in 0..(64L * 1024 * 1024)) { "Sealed resource exceeds per-file budget" }
                total += file.length(); require(total <= 256L * 1024 * 1024) { "Sealed resource budget exceeded" }
                require(file.length() == row.getLong("bytes") && sha256(file) == row.getString("sha256")) { "Sealed resource content changed" }
            }
            return names.map { name -> checkedResource(directory, name).let { SealedFlutterResource(name, it.path, sha256(it)) } }
        }

        private fun checkedResource(directory: File, name: String): File {
            require(!name.startsWith('/') && !name.contains('\\') && !File(name).isAbsolute &&
                name.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
            val file = File(directory, name)
            require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.toPath() == file.toPath().toRealPath() &&
                file.toPath().startsWith(directory.toPath())) { "Unsafe sealed Flutter resource" }
            return file
        }
    }
}
