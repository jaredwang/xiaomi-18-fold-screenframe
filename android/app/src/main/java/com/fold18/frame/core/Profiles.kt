package com.fold18.frame.core

// Device geometry in "design units" = the panel's native pixels, always
// described in the device's natural portrait orientation (camera at the
// top-left for the inner screen). Every value scales with the screenshot's
// short side. Screen sizes, corner radii and cutouts come from the device
// (`dumpsys display` / `dumpsys window`); bezel and rim were measured from
// reference renders. Mirrors src/frame/profiles.ts in the web version.

enum class Edge { TOP, RIGHT, BOTTOM, LEFT }

/** A part along one edge, starting [start] units from the body's top/left corner. */
data class EdgePart(val edge: Edge, val start: Float, val length: Float)

/** Folded only: the hinge barrel standing proud of one edge. */
data class Spine(val edge: Edge, val width: Float, val overhang: Float)

data class Camera(val x: Float, val y: Float, val r: Float)

data class DeviceProfile(
    val id: String,
    val screenW: Int,
    val screenH: Int,
    /** Screen corner radii: topLeft, topRight, bottomRight, bottomLeft. */
    val radii: List<Float>,
    /** Total width of metal rim + black bezel around the screen. */
    val band: Float,
    /** Width of the outer metal rim (part of [band]). */
    val rim: Float,
    /** Punch-hole centre in screen coordinates. */
    val camera: Camera,
    val buttons: List<EdgePart>,
    /** How far buttons stick out of the body. */
    val buttonDepth: Float,
    /** Dark slots on the rim where the hinge meets the frame. */
    val hingeMarks: List<EdgePart>,
    val spine: Spine? = null,
) {
    val aspect: Float get() = screenH.toFloat() / screenW
}

object Profiles {
    val INNER = DeviceProfile(
        id = "inner",
        screenW = 1672,
        screenH = 2364,
        radii = listOf(142f, 142f, 142f, 142f),
        band = 50f,
        rim = 12f,
        camera = Camera(95f, 96f, 36f),
        buttons = listOf(EdgePart(Edge.TOP, 355f, 251f), EdgePart(Edge.TOP, 666f, 123f)),
        buttonDepth = 7f,
        hingeMarks = listOf(EdgePart(Edge.LEFT, 1232f - 28f, 56f), EdgePart(Edge.RIGHT, 1232f - 28f, 56f)),
    )

    // Cover screen sits on the back of the left half: the hinge side (left) has
    // tight corners, the free side carries the same buttons as the open device.
    val OUTER = DeviceProfile(
        id = "outer",
        screenW = 1168,
        screenH = 1712,
        radii = listOf(28f, 154f, 154f, 28f),
        band = 50f,
        rim = 12f,
        camera = Camera(584f, 63f, 26f),
        buttons = listOf(EdgePart(Edge.RIGHT, 355f, 251f), EdgePart(Edge.RIGHT, 666f, 123f)),
        buttonDepth = 7f,
        hingeMarks = emptyList(),
        // Traced from the official black-model cover shots.
        spine = Spine(Edge.LEFT, 26f, 9f),
    )

    val ALL = listOf(INNER, OUTER)
}
