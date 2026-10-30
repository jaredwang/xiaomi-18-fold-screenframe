package com.veltrix.fold.frame

/**
 * Frame colours of the four 18 Fold models, sampled from the official product
 * shots (specs page colour renders + front views). Only the metal changes:
 * the screen bezel is black on every model.
 *
 * Tones run [light] … [deep]; [bright] is the strongest highlight.
 */
enum class Finish(
    /** String resource with the chip label. */
    val label: Int,
    val swatch: Int,
    private val light: Int,
    private val bright: Int,
    private val mid: Int,
    private val midDark: Int,
    private val dark: Int,
    private val deep: Int,
    /** Thin edge line that keeps light metals from melting into light backgrounds. */
    val outline: Int?,
) {
    BLACK(R.string.finish_black, 0xFF2A2B2E.toInt(), 0xFF5D5F63.toInt(), 0xFF616367.toInt(), 0xFF505256.toInt(), 0xFF3E4043.toInt(), 0xFF2F3033.toInt(), 0xFF262729.toInt(), null),
    WHITE(R.string.finish_white, 0xFFE6DCCF.toInt(), 0xFFE4D9CC.toInt(), 0xFFEDE4D9.toInt(), 0xFFD5C8B8.toInt(), 0xFFC4B5A2.toInt(), 0xFFB9A994.toInt(), 0xFFA8967F.toInt(), 0x809E8C76.toInt()),
    PINK(R.string.finish_pink, 0xFFE3C2BA.toInt(), 0xFFE0C3BB.toInt(), 0xFFEACFC8.toInt(), 0xFFD0ADA4.toInt(), 0xFFC19C93.toInt(), 0xFFB58E85.toInt(), 0xFFA07B73.toInt(), 0x8096726A.toInt()),
    RED(R.string.finish_red, 0xFF9E2236.toInt(), 0xFFA3283A.toInt(), 0xFFB23246.toInt(), 0xFF8A1E2E.toInt(), 0xFF741726.toInt(), 0xFF5E0F1A.toInt(), 0xFF4A0A13.toInt(), null);

    /** Sweep gradient around the rim; pairs with [METAL_STOPS]. */
    val metal get() = intArrayOf(light, dark, mid, deep, bright, midDark, light)

    /** Across the hinge barrel, outer edge first. */
    val spine get() = intArrayOf(darken(deep), mid, bright, dark, darken(deep))

    /** Across a side button. */
    val button get() = intArrayOf(dark, light, dark)

    val hingeMark get() = dark

    companion object {
        val METAL_STOPS = floatArrayOf(0f, 0.18f, 0.35f, 0.55f, 0.72f, 0.88f, 1f)

        fun of(id: String?) = entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: BLACK

        private fun darken(c: Int): Int {
            val k = 0.6f
            return (c and 0xFF000000.toInt()) or
                (((c shr 16 and 255) * k).toInt() shl 16) or
                (((c shr 8 and 255) * k).toInt() shl 8) or
                ((c and 255) * k).toInt()
        }
    }
}
