package com.fold18.frame.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

// A screenshot does not record which way up the device was held, but the
// status bar does: Android pads the status bar on the side of the punch-hole.
// Inner screen, camera at the top-left (portrait) or top-right (landscape)
// → that margin is wider. Camera at the bottom → both margins match.

data class Margins(val left: Float, val right: Float)

/** Width the status band is analysed at; keeps sampling cheap. */
const val SAMPLE_WIDTH = 400

// Status bar text sits roughly 1.5%–6% down the long side.
private const val BAND_TOP = 0.015f
private const val BAND_BOTTOM = 0.06f
private const val MIN_EDGE_ENERGY = 400.0

/** Top rows [y0, y1) to sample, for an image sampleW×sampleH (SAMPLE_WIDTH wide). */
fun statusBand(sampleW: Int, sampleH: Int): IntRange {
    val long = max(sampleW, sampleH)
    return floor(BAND_TOP * long).toInt() until ceil(BAND_BOTTOM * long).toInt()
}

/**
 * Left/right margins (fractions of width) of the status bar content in a
 * grayscale band (row-major, width × height), or null when the band shows no
 * clear status bar.
 */
fun statusBarMargins(width: Int, height: Int, gray: IntArray): Margins? {
    if (width < 3 || height < 3) return null
    val energy = DoubleArray(width)
    for (y in 1 until height) {
        for (x in 1 until width) {
            val v = gray[y * width + x]
            energy[x] += (abs(v - gray[y * width + x - 1]) + abs(v - gray[(y - 1) * width + x])).toDouble()
        }
    }
    val maxEnergy = energy.max()
    if (maxEnergy < MIN_EDGE_ENERGY) return null
    val threshold = maxEnergy * 0.25
    val first = energy.indexOfFirst { it > threshold }
    val last = energy.indexOfLast { it > threshold }
    val m = Margins(first.toFloat() / width, 1 - last.toFloat() / width)
    // Real status bars keep both ends near the edges; anything else is content.
    if (m.left > 0.16f || m.right > 0.16f) return null
    return m
}

/**
 * Whether the camera is at the bottom (true), top (false), or unknown (null),
 * for an inner-screen screenshot.
 */
fun cameraAtBottom(m: Margins?, landscape: Boolean): Boolean? {
    if (m == null) return null
    val cameraSide = if (landscape) m.right else m.left
    val otherSide = if (landscape) m.left else m.right
    return cameraSide - otherSide < 0.015f
}
