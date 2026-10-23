package dev.aerove.lite.ui

import org.junit.Assert.*
import org.junit.Test

class AboutLicenseTest {
    private val root = "licenses/client-helper/"
    private fun assets(): MutableMap<String, String> = mutableMapOf(
        root + "dependencies.json" to """[{"Path":"example.org/module","Version":"v1.0.0","licenses":["licenses/module/LICENSE","licenses/module/PATENTS"]}]""",
        root + "LICENSE.AEROVE" to "AEROVE complete license\nCopyright AEROVE\n",
        root + "LICENSE.Go" to "Go complete license\nCopyright Go\n",
        root + "LICENSE.regexp2" to "regexp2 complete license\nCopyright regexp2\n",
        root + "ATTRIB.regexp2" to "regexp2 attribution\nOriginal authors\n",
        root + "module/LICENSE" to "Module complete license\nPermission and conditions\n",
        root + "module/PATENTS" to "Module patent terms\nGrant and restrictions\n",
    )

    @Test fun combinedPageIncludesFullRootAndDependencyLicenseTextsAndVersion() {
        val assets = assets()
        val reads = mutableListOf<String>()
        val document = readClientHelperLicenses { path -> reads += path; assets.getValue(path) }.joinToString("\n")
        assets.filterKeys { !it.endsWith("dependencies.json") }.values.forEach { assertTrue(document.contains(it.trimEnd())) }
        assertTrue(document.contains("example.org/module v1.0.0"))
        assertEquals(assets.keys, reads.toSet())
    }

    @Test fun absentOrEscapingLicenseCannotBeSilentlyOmitted() {
        val missing = assets().apply { remove(root + "module/PATENTS") }
        assertThrows(NoSuchElementException::class.java) { readClientHelperLicenses(missing::getValue) }
        val unsafe = assets().apply { put(root + "dependencies.json", """[{"Path":"module","Version":"v1","licenses":["licenses/../outside"]}]""") }
        assertThrows(IllegalArgumentException::class.java) { readClientHelperLicenses(unsafe::getValue) }
    }

    @Test fun unboundedMetadataOrLicenseTextIsRejected() {
        val assets = assets().apply { put(root + "LICENSE.Go", "x".repeat(256 * 1024 + 1)) }
        assertThrows(IllegalArgumentException::class.java) { readClientHelperLicenses(assets::getValue) }
    }
}
