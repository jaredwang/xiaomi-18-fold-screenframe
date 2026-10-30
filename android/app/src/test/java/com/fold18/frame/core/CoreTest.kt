package com.fold18.frame.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CoreTest {
    @Test
    fun detectsScreens() {
        assertSame(Profiles.INNER, detectProfile(1672, 2364))
        assertSame(Profiles.INNER, detectProfile(2364, 1672))
        assertSame(Profiles.INNER, detectProfile(1415, 2000))
        assertSame(Profiles.OUTER, detectProfile(1168, 1712))
        assertSame(Profiles.OUTER, detectProfile(584, 856))
        assertNull(detectProfile(1000, 1000))
        assertNull(detectProfile(1080, 2520))
    }

    @Test
    fun rotations() {
        assertEquals(0, detectRotation(1000, 1400))
        assertEquals(90, detectRotation(1400, 1000))
        assertEquals(180, detectRotation(1000, 1400, flipped = true))
        assertEquals(270, detectRotation(1400, 1000, flipped = true))
    }

    @Test
    fun keepsScreenshotPixels() {
        val l = computeLayout(1672, 2364, Profiles.INNER, 0, 0.1f, 1f)
        assertEquals(1672f, l.screen.w)
        assertEquals(2364f, l.screen.h)
        assertEquals(Math.round(1672 + 100 + 2 * l.pad), l.canvasW)
    }

    @Test
    fun cameraCorners() {
        fun corner(rotation: Int): String {
            val portrait = rotation % 180 == 0
            val l = computeLayout(if (portrait) 1672 else 2364, if (portrait) 2364 else 1672, Profiles.INNER, rotation, 0.1f, 1f)
            val (x, y) = l.cameraOnCanvas(Profiles.INNER)
            return (if (x < l.canvasW / 2f) "left" else "right") + "-" + (if (y < l.canvasH / 2f) "top" else "bottom")
        }
        assertEquals("left-top", corner(0))
        assertEquals("right-top", corner(90))
        assertEquals("right-bottom", corner(180))
        assertEquals("left-bottom", corner(270))
    }

    @Test
    fun rotatesCornerRadii() {
        fun corners(w: Int, h: Int, rot: Int) = computeLayout(w, h, Profiles.OUTER, rot, 0f, 1f).screenCornersOnCanvas()
        assertEquals(listOf(28f, 154f, 154f, 28f), corners(1168, 1712, 0))
        assertEquals(listOf(28f, 28f, 154f, 154f), corners(1712, 1168, 90))
        assertEquals(listOf(154f, 28f, 28f, 154f), corners(1168, 1712, 180))
    }

    @Test
    fun statusBarMarginsFromGlyphs() {
        val w = 400
        val h = 18
        val gray = IntArray(w * h) { 20 }
        for ((a, b) in listOf(0.1 to 0.18, 0.85 to 0.945)) {
            for (y in 4 until h - 4) {
                var x = Math.round(a * w).toInt()
                while (x < Math.round(b * w)) {
                    gray[y * w + x] = 240
                    x += 3
                }
            }
        }
        val m = statusBarMargins(w, h, gray)!!
        assertEquals(0.1f, m.left, 0.01f)
        assertEquals(0.055f, m.right, 0.01f)
        assertNull(statusBarMargins(w, h, IntArray(w * h) { 20 }))
    }

    @Test
    fun cameraSideFromMargins() {
        assertEquals(false, cameraAtBottom(Margins(0.1f, 0.055f), landscape = false))
        assertEquals(true, cameraAtBottom(Margins(0.05f, 0.055f), landscape = false))
        assertEquals(false, cameraAtBottom(Margins(0.035f, 0.078f), landscape = true))
        assertEquals(true, cameraAtBottom(Margins(0.035f, 0.04f), landscape = true))
        assertNull(cameraAtBottom(null, landscape = false))
    }
}
