package com.dreamdisplayx.platform.client.danmaku

import com.dreamdisplayx.platform.client.render.AwtOverlayFontProvider
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil

/** AWT-free pixel result consumed by the Minecraft texture wrapper. */
internal data class DanmakuRaster(val width: Int, val height: Int, val argb: IntArray)

/** Optional AWT backend for danmaku measurement and rasterization. */
internal object AwtDanmakuRasterizer {
    private const val BASE_FONT_PX = 12
    private const val SUPERSAMPLE = 2f
    private const val STROKE_WIDTH_PX = 1.5f

    fun measure(text: String, scale: Float, count: Int = 1): DanmakuMetrics {
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val graphics = probe.createGraphics()
        try {
            configure(graphics)
            val layout = layout(text, scale, count, graphics)
            return DanmakuMetrics(
                ceil((layout.advance + STROKE_WIDTH_PX).toDouble()).toFloat().coerceAtLeast(1f),
                (layout.lineHeight + STROKE_WIDTH_PX).coerceAtLeast(1f),
            )
        } finally {
            graphics.dispose()
        }
    }

    fun rasterize(
        text: String,
        argb: Int,
        scale: Float,
        count: Int = 1,
        colorful: Boolean = false,
    ): DanmakuRaster {
        val stroke = STROKE_WIDTH_PX * SUPERSAMPLE
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val probeGraphics = probe.createGraphics()
        val width: Int
        val height: Int
        try {
            configure(probeGraphics)
            val measured = layout(text, scale * SUPERSAMPLE, count, probeGraphics)
            width = ceil((measured.advance + stroke).toDouble()).toInt().coerceAtLeast(1)
            height = ceil((measured.lineHeight + stroke).toDouble()).toInt().coerceAtLeast(1)
        } finally {
            probeGraphics.dispose()
        }

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            configure(graphics)
            val drawn = layout(text, scale * SUPERSAMPLE, count, graphics)
            graphics.translate((stroke / 2f).toDouble(), (stroke / 2f + drawn.ascent).toDouble())
            val outline = drawn.outline()
            graphics.color = Color.BLACK
            graphics.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            graphics.draw(outline)
            graphics.paint = if (colorful) {
                GradientPaint(
                    0f,
                    0f,
                    Color(0xFFF2509E.toInt()),
                    width.toFloat(),
                    0f,
                    Color(0xFF308BCD.toInt()),
                )
            } else {
                Color(argb, true)
            }
            graphics.fill(outline)
        } finally {
            graphics.dispose()
        }

        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)
        return DanmakuRaster(width, height, pixels)
    }

    private fun configure(graphics: Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    private fun layout(
        text: String,
        scale: Float,
        count: Int,
        graphics: Graphics2D,
    ): AwtOverlayFontProvider.OverlayLayout =
        AwtOverlayFontProvider.layout(
            text,
            java.awt.Font.PLAIN,
            (BASE_FONT_PX * scale).toInt().coerceAtLeast(6),
            graphics.fontRenderContext,
            prefix = if (count > 1) "($count)" else null,
        )
}
