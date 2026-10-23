package dev.aerove.lite.images

import org.junit.Assert.*
import org.junit.Test

class IconSvgDocumentTest {
    @Test fun gradientsClipsPathsAndInternalReferencesRemainInTheDocument() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><defs>
            <linearGradient id="g"><stop offset="0" stop-color="red"/></linearGradient>
            <clipPath id="c"><circle cx="5" cy="5" r="4"/></clipPath></defs>
            <path d="M0 0H10V10H0Z" fill="url(#g)" clip-path="url(#c)"/></svg>"""
        assertEquals(svg, IconSvgDocument.parse(svg.toByteArray()).text)
    }
    @Test fun boundedEmbeddedImagesAndUtf16AreAcceptedWithoutExternalLoading() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><image href="data:image/png;base64,AQID"/></svg>"""
        assertArrayEquals(byteArrayOf(1, 2, 3), IconSvgDocument.parse(svg.toByteArray()).embeddedImages.single())
        val utf16 = ("\uFEFF<?xml version=\"1.0\" encoding=\"UTF-16\"?>" + svg).toByteArray(Charsets.UTF_16LE)
        assertEquals(svg, IconSvgDocument.parse(utf16).text)
    }
    @Test fun externalEntitiesScriptsImagesCssAndProcessingInstructionsAreRejected() {
        val bad = listOf(
            "<!DOCTYPE svg [<!ENTITY x SYSTEM 'https://example.test/entity'>]><svg>&x;</svg>",
            "<svg><script>alert(1)</script></svg>",
            "<svg><image href='https://example.test/a.png'/></svg>",
            "<svg><path fill='url(file:///private)'/></svg>",
            "<?xml-stylesheet href='https://example.test/style.css'?><svg/>",
            "<svg><style>@import 'https://example.test/style.css';</style></svg>",
            "<svg onload='run()'/>",
            "<svg><use id='loop' href='#loop'/></svg>",
        )
        bad.forEach { value ->
            try { IconSvgDocument.parse(value.toByteArray()); fail("Unsafe SVG admitted: $value") }
            catch (_: Exception) { }
        }
    }
    @Test fun structureAndTextBudgetsStopBeforeCreatingTheRendererDocument() {
        for (value in listOf("<svg>" + "<g/>".repeat(2049) + "</svg>", "<svg><text>" + "A".repeat(65537) + "</text></svg>")) {
            try { IconSvgDocument.parse(value.toByteArray()); fail("Unbounded SVG accepted") }
            catch (_: Exception) { }
        }
        var checkpoints = 0
        try {
            IconSvgDocument.parse("<svg><g/></svg>".toByteArray()) { if (++checkpoints > 1) throw kotlinx.coroutines.CancellationException("left page") }
            fail("Cancelled SVG continued")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }
}
