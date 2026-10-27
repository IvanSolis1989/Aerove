package dev.aerove.lite.ui

internal data class MarkerProfileMoment(
    val centroid: Double?, val contrast: Double, val touchesStart: Boolean, val touchesEnd: Boolean,
)

/** The original LineProfile 20%-above-min moment, shared by both measurement directions. */
internal fun markerProfileMoment(positions: DoubleArray, values: DoubleArray): MarkerProfileMoment {
    require(values.isNotEmpty() && positions.size == values.size)
    val baseline = values.min()
    val contrast = values.max() - baseline
    val cutoff = contrast * 0.20
    var weight = 0.0
    var moment = 0.0
    values.indices.forEach { i ->
        val amount = (values[i] - baseline - cutoff).coerceAtLeast(0.0)
        weight += amount
        moment += positions[i] * amount
    }
    return MarkerProfileMoment(if (weight > 0) moment / weight else null, contrast,
        values.first() > baseline + cutoff, values.last() > baseline + cutoff)
}

internal data class DirectionalChromaticRow(
    val row: Int, val centroidsPx: List<Double?>, val contrasts: List<Double>,
    val touchesStart: List<Boolean>, val touchesEnd: List<Boolean>, val signedRedBlueDp: Double?,
)

internal data class DirectionalChromaticResult(
    val width: Int, val height: Int, val rows: List<DirectionalChromaticRow>,
) {
    val missingSignalRows get() = rows.filter { it.signedRedBlueDp == null }.map { it.row }
    val boundaryRows get() = rows.filter { it.touchesStart.any { hit -> hit } || it.touchesEnd.any { hit -> hit } }.map { it.row }
    // Every original depth row has one vote, including the inner rows where dispersion is zero.
    val meanSignedRedBlueDp: Double? get() = if (missingSignalRows.isEmpty()) rows.sumOf { requireNotNull(it.signedRedBlueDp) } / height else null
}

/** Packed RGB pixels only; no pose, style, lens control, source identity or scene labels. */
internal fun directionalChromaticDiagnostic(
    pixels: IntArray, width: Int, height: Int, density: Double, bottom: Boolean,
): DirectionalChromaticResult {
    require(width > 4 && height > 0 && width.toLong() * height == pixels.size.toLong())
    require(density.isFinite() && density > 0)
    val positions = DoubleArray(width) { it + 0.5 }
    val rgb = List(3) { DoubleArray(width) }
    val rows = ArrayList<DirectionalChromaticRow>(height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            rgb[0][x] = ((pixel ushr 16) and 255).toDouble()
            rgb[1][x] = ((pixel ushr 8) and 255).toDouble()
            rgb[2][x] = (pixel and 255).toDouble()
        }
        val moments = rgb.map { markerProfileMoment(positions, it) }
        val red = moments[0].centroid
        val blue = moments[2].centroid
        val allChannelsPresent = moments.all { it.centroid != null }
        val span = if (allChannelsPresent) (requireNotNull(red) - requireNotNull(blue)) * (if (bottom) -1 else 1) / density else null
        rows += DirectionalChromaticRow(y, moments.map { it.centroid }, moments.map { it.contrast },
            moments.map { it.touchesStart }, moments.map { it.touchesEnd }, span)
    }
    return DirectionalChromaticResult(width, height, rows)
}
