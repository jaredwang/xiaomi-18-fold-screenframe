package com.fold18.frame.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Mirrors src/frame/geometry.ts in the web version.

// Inner (1.414) and cover (1.466) ratios are only ~3.7% apart.
private const val ASPECT_TOLERANCE = 0.015f

/**
 * Exact native resolution first, then closest aspect ratio (for resized
 * screenshots); null when nothing is within tolerance.
 */
fun detectProfile(w: Int, h: Int): DeviceProfile? {
    val short = min(w, h)
    val long = max(w, h)
    Profiles.ALL.firstOrNull { it.screenW == short && it.screenH == long }?.let { return it }
    val aspect = long.toFloat() / short
    val best = Profiles.ALL.minBy { abs(aspect - it.aspect) / it.aspect }
    return if (abs(aspect - best.aspect) / best.aspect <= ASPECT_TOLERANCE) best else null
}

/** Clockwise device rotation in degrees; [flipped] picks the 180°-opposite pose. */
fun detectRotation(w: Int, h: Int, flipped: Boolean = false): Int {
    val base = if (w > h) 90 else 0
    return if (flipped) (base + 180) % 360 else base
}

data class Box(val x: Float, val y: Float, val w: Float, val h: Float)

data class Layout(
    val canvasW: Int,
    val canvasH: Int,
    /** Design units → pixels. */
    val scale: Float,
    val rotation: Int,
    val pad: Float,
    /** Natural (portrait) body size in pixels. */
    val bodyW: Float,
    val bodyH: Float,
    val band: Float,
    /** Screen corner radii in body space: topLeft, topRight, bottomRight, bottomLeft. */
    val screenRadius: List<Float>,
    /** Where the screenshot lands on the canvas (never rotated or resized). */
    val screen: Box,
)

fun computeLayout(
    imgW: Int,
    imgH: Int,
    profile: DeviceProfile,
    rotation: Int,
    padding: Float,
    radiusScale: Float,
): Layout {
    val short = min(imgW, imgH)
    val long = max(imgW, imgH)
    val scale = short.toFloat() / profile.screenW
    val band = profile.band * scale
    val bodyW = short + 2 * band
    val bodyH = long + 2 * band
    // Always leave room for buttons and the hinge spine, even with zero padding.
    val proud = max(profile.buttonDepth, profile.spine?.width ?: 0f)
    val pad = max(padding * short, proud * scale + 2)
    val landscape = rotation == 90 || rotation == 270
    val outW = if (landscape) bodyH else bodyW
    val outH = if (landscape) bodyW else bodyH
    return Layout(
        canvasW = (outW + 2 * pad).roundToInt(),
        canvasH = (outH + 2 * pad).roundToInt(),
        scale = scale,
        rotation = rotation,
        pad = pad,
        bodyW = bodyW,
        bodyH = bodyH,
        band = band,
        screenRadius = profile.radii.map { it * radiusScale * scale },
        screen = Box(pad + band, pad + band, imgW.toFloat(), imgH.toFloat()),
    )
}

/** Map a point in natural body space (pixels) to canvas pixels. */
fun Layout.toCanvas(x: Float, y: Float): Pair<Float, Float> = when (rotation) {
    90 -> pad + bodyH - y to pad + x
    180 -> pad + bodyW - x to pad + bodyH - y
    270 -> pad + y to pad + bodyW - x
    else -> pad + x to pad + y
}

/** Screen corner radii as they land on the canvas (body corners rotate with the device). */
fun Layout.screenCornersOnCanvas(): List<Float> {
    val k = rotation / 90
    val out = MutableList(4) { 0f }
    screenRadius.forEachIndexed { i, r -> out[(i + k) % 4] = r }
    return out
}

fun Layout.cameraOnCanvas(p: DeviceProfile): Pair<Float, Float> =
    toCanvas(band + p.camera.x * scale, band + p.camera.y * scale)
