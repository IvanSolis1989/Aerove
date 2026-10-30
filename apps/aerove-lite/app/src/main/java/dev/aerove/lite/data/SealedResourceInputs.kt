package dev.aerove.lite.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.json.JSONObject

internal class SealedResourceInputs(val origins: List<Origin>) {
    class Origin(val lease: ResourceGenerationLease, val files: List<SealedFlutterResource>)

    fun resolve(path: String): SealedFlutterResource? = origins.asSequence().flatMap { it.files.asSequence() }
        .firstOrNull { it.relativePath.startsWith("assets/") && File(it.sourcePath).absoluteFile == File(path).absoluteFile }

    fun verify() = origins.forEach { origin ->
        require(readFiles(origin.lease.directory) == origin.files) { "Sealed input changed during preparation" }
    }

    fun close() = origins.asReversed().forEach { it.lease.close() }

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
