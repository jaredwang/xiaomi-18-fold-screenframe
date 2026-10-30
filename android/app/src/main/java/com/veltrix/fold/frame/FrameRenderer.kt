package com.veltrix.fold.frame

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import com.veltrix.fold.frame.core.DeviceProfile
import com.veltrix.fold.frame.core.Edge
import com.veltrix.fold.frame.core.EdgePart
import com.veltrix.fold.frame.core.Layout
import com.veltrix.fold.frame.core.Spine
import com.veltrix.fold.frame.core.computeLayout
import com.veltrix.fold.frame.core.screenCornersOnCanvas
import com.veltrix.fold.frame.core.toCanvas
import kotlin.math.max
import kotlin.math.min

/** [background] null = transparent. */
data class FrameOptions(
    val background: Int?,
    val padding: Float,
    val shadow: Boolean,
    val radiusScale: Float,
    val finish: Finish,
)

/** Draws the device frame around a screenshot on an Android Canvas. */
object FrameRenderer {
    /** Body corners (topLeft, topRight, bottomRight, bottomLeft indices) touching each edge. */
    private val SPINE_CORNERS = mapOf(
        Edge.TOP to listOf(0, 1), Edge.RIGHT to listOf(1, 2), Edge.BOTTOM to listOf(2, 3), Edge.LEFT to listOf(3, 0),
    )

    /** [src] must be a software bitmap; it is drawn at its original pixel size. */
    fun render(src: Bitmap, profile: DeviceProfile, rotation: Int, opts: FrameOptions): Bitmap {
        val l = computeLayout(src.width, src.height, profile, rotation, opts.padding, opts.radiusScale)
        val out = Bitmap.createBitmap(l.canvasW, l.canvasH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        opts.background?.let { c.drawColor(it) }

        val s = l.scale
        val outerR = l.screenRadius.map { it + l.band }.toMutableList()
        profile.spine?.let { spine ->
            // The body butts squarely against the hinge barrel.
            val tight = spine.width * 0.6f * s
            for (i in SPINE_CORNERS.getValue(spine.edge)) outerR[i] = min(outerR[i], tight)
        }
        val body = bodyMatrix(l)

        if (opts.shadow) {
            // Built in canvas space so the shadow always falls downwards.
            val path = roundRect(0f, 0f, l.bodyW, l.bodyH, outerR).apply { transform(body) }
            // Canvas2D blur 70 ≈ sigma 35; Skia radius→sigma is ×0.577.
            val shadow = paint(Color.BLACK).apply { setShadowLayer(60 * s, 0f, 22 * s, 0x38000000) }
            c.drawPath(path, shadow)
        }

        c.save()
        c.concat(body)
        val finish = opts.finish
        profile.spine?.let { drawSpine(c, l, it, finish) }
        for (b in profile.buttons) drawButton(c, l, b, profile.buttonDepth * s, finish)
        drawRim(c, l, profile, outerR, finish)
        for (m in profile.hingeMarks) drawHingeMark(c, l, m, profile.rim * s, finish)
        c.restore()

        // Screenshot, unrotated, through an anti-aliased rounded path.
        val sc = l.screen
        val shot = paint().apply {
            isFilterBitmap = true
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(sc.x, sc.y) })
            }
        }
        c.drawPath(roundRect(sc.x, sc.y, sc.w, sc.h, l.screenCornersOnCanvas()), shot)

        c.save()
        c.concat(body)
        drawCamera(c, l.band + profile.camera.x * s, l.band + profile.camera.y * s, profile.camera.r * s)
        c.restore()
        return out
    }

    /** Natural body space → canvas: rotate about the body origin, then move it into place. */
    private fun bodyMatrix(l: Layout) = Matrix().apply {
        val (ox, oy) = l.toCanvas(0f, 0f)
        setRotate(l.rotation.toFloat())
        postTranslate(ox, oy)
    }

    private fun paint(color: Int? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color?.let { this.color = it } }

    private fun stroke(color: Int, width: Float) = paint(color).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, r: List<Float>): Path {
        val cap = min(w / 2, h / 2)
        val (tl, tr, br, bl) = r.map { max(0f, min(it, cap)) }
        return Path().apply {
            addRoundRect(RectF(x, y, x + w, y + h), floatArrayOf(tl, tl, tr, tr, br, br, bl, bl), Path.Direction.CW)
        }
    }

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) = roundRect(x, y, w, h, List(4) { r })

    /** Concentric layers measured from the reference: metal rim → dark lips → glass edge → bezel. */
    private fun drawRim(c: Canvas, l: Layout, p: DeviceProfile, outerR: List<Float>, finish: Finish) {
        val s = l.scale
        fun inset(d: Float): Path {
            val v = d * s
            return roundRect(v, v, l.bodyW - 2 * v, l.bodyH - 2 * v, outerR.map { it - v })
        }

        // Brushed metal rim with light falling unevenly around the perimeter.
        val cx = l.bodyW / 2
        val cy = l.bodyH / 2
        val metal = SweepGradient(cx, cy, finish.metal, Finish.METAL_STOPS).apply {
            setLocalMatrix(Matrix().apply { setRotate(-45f, cx, cy) })
        }
        c.drawPath(inset(0f), paint().apply { shader = metal })
        finish.outline?.let { c.drawPath(inset(0.5f), stroke(it, 1f * s)) }
        c.drawPath(inset(4.5f), stroke(Color.argb(66, 255, 255, 255), 2.5f * s))
        c.drawPath(inset(p.rim), paint(0xFF141516.toInt()))
        c.drawPath(inset(p.rim + 5), stroke(0xFF4F5053.toInt(), 2 * s))
        c.drawPath(inset(p.rim + 13), paint(0xFF070707.toInt()))
        // Thin grey glint where the cover glass ends.
        c.drawPath(inset(p.rim + 19), stroke(0xFF262729.toInt(), 3 * s))
    }

    /** Hinge barrel: a cylinder half-tucked under the body, slightly longer than it. */
    private fun drawSpine(c: Canvas, l: Layout, spine: Spine, finish: Finish) {
        val s = l.scale
        val width = spine.width * s
        val tuck = 8 * s
        val over = spine.overhang * s
        val r = (width - tuck) * 0.9f
        val vertical = spine.edge == Edge.LEFT || spine.edge == Edge.RIGHT
        val rect = if (vertical) {
            val x = if (spine.edge == Edge.LEFT) -(width - tuck) else l.bodyW - tuck
            RectF(x, -over, x + width, l.bodyH + over)
        } else {
            val y = if (spine.edge == Edge.TOP) -(width - tuck) else l.bodyH - tuck
            RectF(-over, y, l.bodyW + over, y + width)
        }
        val outerFirst = spine.edge == Edge.LEFT || spine.edge == Edge.TOP
        var stops = floatArrayOf(0f, 0.18f, 0.32f, 0.5f, 1f)
        var colors = finish.spine
        if (!outerFirst) {
            stops = stops.map { 1 - it }.reversed().toFloatArray()
            colors = colors.reversedArray()
        }
        val g = if (vertical) {
            LinearGradient(rect.left, 0f, rect.right, 0f, colors, stops, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, rect.top, 0f, rect.bottom, colors, stops, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(rect, r, r, paint().apply { shader = g })
    }

    private fun edgeRect(l: Layout, part: EdgePart, outward: Float, inward: Float): RectF {
        val s = l.scale
        val start = part.start * s
        val len = part.length * s
        return when (part.edge) {
            Edge.TOP -> RectF(start, -outward, start + len, inward)
            Edge.BOTTOM -> RectF(start, l.bodyH - inward, start + len, l.bodyH + outward)
            Edge.LEFT -> RectF(-outward, start, inward, start + len)
            Edge.RIGHT -> RectF(l.bodyW - inward, start, l.bodyW + outward, start + len)
        }
    }

    private fun drawButton(c: Canvas, l: Layout, b: EdgePart, depth: Float, finish: Finish) {
        val r = edgeRect(l, b, depth, depth)
        val colors = finish.button
        val g = if (b.edge == Edge.TOP || b.edge == Edge.BOTTOM) {
            LinearGradient(0f, r.top, 0f, r.bottom, colors, null, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(r.left, 0f, r.right, 0f, colors, null, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(r, depth * 0.6f, depth * 0.6f, paint().apply { shader = g })
    }

    private fun drawHingeMark(c: Canvas, l: Layout, m: EdgePart, rimWidth: Float, finish: Finish) {
        val s = l.scale
        val r = edgeRect(l, m, 2 * s, rimWidth * 0.9f)
        c.drawRoundRect(r, rimWidth * 0.45f, rimWidth * 0.45f, paint(finish.hingeMark))
        val b = 2.5f * s
        r.inset(b, b)
        c.drawRoundRect(r, rimWidth * 0.3f, rimWidth * 0.3f, paint(0xFF0B0C0D.toInt()))
    }

    private fun drawCamera(c: Canvas, x: Float, y: Float, r: Float) {
        c.drawCircle(x, y, r, paint(0xFF18191C.toInt()))
        c.drawCircle(x, y, r, stroke(0xFF3A3C41.toInt(), r * 0.07f))
        val lens = RadialGradient(
            x, y, r * 0.66f,
            intArrayOf(0xFF3D4B60.toInt(), 0xFF1C2433.toInt(), 0xFF0B0D12.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawCircle(x, y, r * 0.66f, paint().apply { shader = lens })
        c.drawCircle(x - r * 0.2f, y - r * 0.2f, r * 0.08f, paint(Color.argb(46, 255, 255, 255)))
    }
}
