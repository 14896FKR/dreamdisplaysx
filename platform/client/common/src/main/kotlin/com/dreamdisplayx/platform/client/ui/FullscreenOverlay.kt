package com.dreamdisplayx.platform.client.ui

import com.dreamdisplayx.platform.client.utils.identifier
//? if >=1.21.11 {
//?} else
/*import com.mojang.blaze3d.systems.RenderSystem*/
//? if >=1.21.11 {
import net.minecraft.client.renderer.RenderPipelines
//?}
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplayx.api.playback.model.FullscreenMode
import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.console.BorderlessConsoleScreen
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.dreamdisplayx.platform.client.render.AsyncTextureUploader
import com.dreamdisplayx.platform.client.render.TextureUploadUtil
import com.dreamdisplayx.platform.client.render.UploadPixelFormat
import com.dreamdisplayx.platform.client.ui.kit.UiRect
import com.dreamdisplayx.platform.client.ui.kit.UiTheme
import com.dreamdisplayx.platform.client.ui.kit.drawOutline
import com.dreamdisplayx.platform.client.ui.kit.scaleAlpha
import com.dreamdisplayx.platform.client.ui.drawText
import com.dreamdisplayx.platform.client.utils.MinecraftScreenUtil
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor

//?} else
/*
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics
//?}
//? if <1.20 {
import com.dreamdisplayx.platform.client.ui.compat.GuiGraphics
//?}
*/
import net.minecraft.client.renderer.texture.DynamicTexture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/** Full-screen overlay covering the entire viewport. No anchors or resizing like PiP. */
class FullscreenOverlay(
    val displayScreen: DisplayScreen,
    val mode: FullscreenMode,
    val forced: Boolean = false,
    val sessionId: String? = null,
) {
    @Volatile
    private var frontBuf: ByteBuffer = EMPTY_DIRECT

    private var backBuf: ByteBuffer = EMPTY_DIRECT

    @Volatile
    private var frameW = 0

    @Volatile
    private var frameH = 0

    @Volatile
    private var frameVersion = 0L

    @Volatile
    private var contentAspect = 0.0

    @Volatile
    private var frameFormat = UploadPixelFormat.RGB24

    private var uploadedVersion = 0L

    private var dynamicTexture: DynamicTexture? = null
    private var textureId: Identifier? = null
    private var texW = 0
    private var texH = 0
    private var uploader: AsyncTextureUploader? = null
    private var rgbaUploadBuffer: ByteBuffer? = null

    private var animProgress = 0f
    private var closing = false
    private var lastRenderNanos = 0L

    /** True once the close animation has fully played out; the manager discards the overlay. */
    val isFinished: Boolean get() = closing && animProgress < 0.01f

    /** True while the overlay is fading out. */
    val isClosing: Boolean get() = closing

    /** Stores the latest decoded frame; called off-thread by the popout frame sink. */
    fun updateFrame(
        buf: ByteBuffer,
        w: Int,
        h: Int,
        aspect: Double,
        format: UploadPixelFormat = UploadPixelFormat.RGB24,
    ) {
        val size = w * h * format.bytesPerPixel
        if (size <= 0 || buf.remaining() < size) return
        var back = backBuf
        if (back.capacity() < size) {
            back = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
        back.clear()
        val savedLimit = buf.limit()
        val savedPos = buf.position()
        buf.limit(savedPos + size)
        back.put(buf)
        buf.limit(savedLimit)
        buf.position(savedPos)
        back.flip()

        val prev = frontBuf
        frontBuf = back
        backBuf = if (prev.capacity() >= size) prev else ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        contentAspect = aspect
        frameFormat = format
        frameW = w; frameH = h
        frameVersion++
    }

    /** Uploads the latest stored frame to the GPU texture; render-thread only. */
    fun uploadFrame() {
        val fw = frameW
        val fh = frameH
        val v = frameVersion
        if (v == uploadedVersion) return
        val buf = frontBuf
        val format = frameFormat
        val size = fw * fh * format.bytesPerPixel
        if (fw <= 0 || fh <= 0 || buf.remaining() < size) return

        val mc = Minecraft.getInstance()
        var tex = dynamicTexture
        if (tex == null || texW != fw || texH != fh) {
            tex?.close()
            textureId?.let { mc.textureManager.release(it) }
            val img = NativeImage(NativeImage.Format.RGBA, fw, fh, false)
            //? if >=1.21.11 {
            tex = DynamicTexture({ "dreamdisplayx:fullscreen" }, img)
            //?} else
            /*tex = DynamicTexture(img)*/
            textureId = identifier(
                Initializer.MOD_ID, "fullscreen/${displayScreen.uuid}-${UUID.randomUUID()}"
            )
            mc.textureManager.register(textureId!!, tex)
            dynamicTexture = tex; texW = fw; texH = fh
        }

        TextureUploadUtil.uploadDynamicTexture(
            texture = tex,
            src = buf,
            w = fw,
            h = fh,
            format = format,
            glUploader = { uploader ?: AsyncTextureUploader(stateCache = true).also { uploader = it } },
            rgbaScratch = rgbaUploadBuffer,
            setRgbaScratch = { rgbaUploadBuffer = it },
        )
        uploadedVersion = v
    }

    /** Returns false when the close animation has finished; caller should discard. */
    fun render(
        mc: Minecraft,
        //? if >=26 {
        g: GuiGraphicsExtractor,
        //?} else
        /*g: GuiGraphics,*/
        partialTick: Float,
    ): Boolean {
        val now = System.nanoTime()
        val dt = if (lastRenderNanos == 0L) 0.016f
        else ((now - lastRenderNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastRenderNanos = now

        val target = if (closing) 0f else 1f
        animProgress += (target - animProgress) * minOf(1f, dt * 10f)

        if (isFinished) {
            cleanup(mc); return false
        }

        val sw = mc.window.guiScaledWidth
        val sh = mc.window.guiScaledHeight
        val alpha = animProgress

        // Backdrop: opaque black for immersive, translucent scrim for standard
        val scrimAlpha = if (mode == FullscreenMode.IMMERSIVE) alpha else SCRIM_ALPHA * alpha
        g.fill(0, 0, sw, sh, scaleAlpha(0xFF000000.toInt(), scrimAlpha))

        val id = textureId ?: return true
        val fw = texW
        val fh = texH
        val content = contentRect(fw, fh, contentAspect)
        val aspect = if (content.w > 0 && content.h > 0) content.w / content.h.toDouble() else 16.0 / 9.0

        // Letterbox the video into the available area (full screen or the inset standard area)
        val frac = if (mode == FullscreenMode.STANDARD) STANDARD_FRACTION else 1f
        val availW = (sw * frac).toInt().coerceAtLeast(1)
        val availH = (sh * frac).toInt().coerceAtLeast(1)
        var vw = availW
        var vh = (vw / aspect).toInt().coerceAtLeast(1)
        if (vh > availH) {
            vh = availH
            vw = (vh * aspect).toInt().coerceAtLeast(1)
        }
        val vx = (sw - vw) / 2
        val vy = (sh - vh) / 2

        // Same subtle scale-up-while-fading-in as PipOverlay, so opening/closing reads consistently.
        val scale = 0.94f + 0.06f * animProgress
        val matrices = g.pose()
        //? if >=1.21.11 {
        matrices.pushMatrix()
        matrices.translate(vx + vw / 2f, vy + vh / 2f)
        matrices.scale(scale, scale)
        matrices.translate(-vw / 2f, -vh / 2f)
        //?} else
        /*matrices.pushPose()
        matrices.translate((vx + vw / 2f).toDouble(), (vy + vh / 2f).toDouble(), 0.0)
        matrices.scale(scale, scale, 1f)
        matrices.translate((-vw / 2f).toDouble(), (-vh / 2f).toDouble(), 0.0)*/

        //? if >=1.21.11 {
        g.blit(
            RenderPipelines.GUI_TEXTURED,
            id,
            0,
            0,
            content.x.toFloat(),
            content.y.toFloat(),
            vw,
            vh,
            content.w,
            content.h,
            fw,
            fh,
            scaleAlpha(0xFFFFFFFF.toInt(), alpha),
        )
        //?} else
        /*RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); g.setColor(1f, 1f, 1f, alpha); g.blit(id, 0, 0, vw, vh, content.x.toFloat(), content.y.toFloat(), content.w, content.h, fw, fh); g.setColor(1f, 1f, 1f, 1f)*/

        if (mode == FullscreenMode.STANDARD) {
            g.drawOutline(UiRect(0, 0, vw, vh), scaleAlpha(UiTheme.PANEL_BORDER, alpha))
        }

        //? if >=1.21.11 {
        matrices.popMatrix()
        //?} else
        /*matrices.popPose()*/

        // Watch-mode feedback (volume / seek / part changes) has no screen to draw it, so it is painted
        // here while this overlay owns the frame. The console screen paints its own line when open;
        // both call the same plate helper, which tints itself from the picture behind it.
        val console = BorderlessConsoleScreen
        if (MinecraftScreenUtil.currentScreen(mc) == null) {
            val status = console.hudStatus()
            if (status.isNotEmpty()) {
                val f = mc.font
                console.drawBlockPlate(g, 4, sh - 26, 4 + f.width(status) + 8, sh - 12)
                g.drawText(f, status, 8, sh - 23, UiTheme.TEXT_PRIMARY, true)
            }
        }
        if (console.blockVisible()) sampleBlockTint(sw, sh, vx, vy, vw, vh)
        return true
    }

    /** [System.nanoTime] of the last plate tint sample; throttled, this runs off the frame buffer. */
    private var lastTintSampleNanos = 0L

    /**
     * Averages the picture under the bottom-left plate and hands it to the plate colour. Reads the CPU
     * frame we already hold for upload (no GPU readback), a 8x8 grid, at most every 250 ms.
     */
    private fun sampleBlockTint(sw: Int, sh: Int, vx: Int, vy: Int, vw: Int, vh: Int) {
        val now = System.nanoTime()
        if (now - lastTintSampleNanos < TINT_SAMPLE_INTERVAL_NANOS) return
        lastTintSampleNanos = now
        val buf = frontBuf
        val fw = frameW
        val fh = frameH
        val format = frameFormat
        if (fw <= 0 || fh <= 0 || vw <= 0 || vh <= 0 || format.bytesPerPixel < 3) return
        val stride = fw * format.bytesPerPixel
        if (buf.remaining() < stride * fh) return
        // Screen band the plate sits in (bottom-left), clipped to the letterboxed video rect.
        val x0 = vx.coerceAtLeast(0)
        val x1 = (vx + vw).coerceAtMost(sw / 2)
        val y0 = (sh - TINT_BAND_H).coerceAtLeast(vy)
        val y1 = (sh - 14).coerceAtMost(vy + vh)
        if (x1 <= x0 || y1 <= y0) {
            BorderlessConsoleScreen.updateBlockTint(0x101014)
            return
        }
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        for (gy in 0 until TINT_GRID) {
            for (gx in 0 until TINT_GRID) {
                val sx = x0 + (x1 - x0) * gx / TINT_GRID
                val sy = y0 + (y1 - y0) * gy / TINT_GRID
                val u = ((sx - vx).toLong() * fw / vw).toInt().coerceIn(0, fw - 1)
                val v = ((sy - vy).toLong() * fh / vh).toInt().coerceIn(0, fh - 1)
                val index = v * stride + u * format.bytesPerPixel
                if (index + 2 >= buf.limit()) continue
                val c0 = buf.get(index).toInt() and 0xFF
                val c1 = buf.get(index + 1).toInt() and 0xFF
                val c2 = buf.get(index + 2).toInt() and 0xFF
                if (format == UploadPixelFormat.BGRA32) {
                    sumR += c2; sumG += c1; sumB += c0
                } else {
                    sumR += c0; sumG += c1; sumB += c2
                }
                count++
            }
        }
        if (count == 0) return
        val r = (sumR / count).toInt()
        val g = (sumG / count).toInt()
        val b = (sumB / count).toInt()
        BorderlessConsoleScreen.updateBlockTint((r shl 16) or (g shl 8) or b)
    }

    /** Crops the padded frame texture down to the actual video content area. */
    private fun contentRect(frameW: Int, frameH: Int, contentAspect: Double): ContentRect {
        if (frameW <= 0 || frameH <= 0) return ContentRect(0, 0, frameW, frameH)
        if (contentAspect <= 0.0 || !contentAspect.isFinite()) return ContentRect(0, 0, frameW, frameH)

        val frameAspect = frameW / frameH.toDouble()
        return if (contentAspect > frameAspect) {
            val contentH = (frameW / contentAspect).toInt().coerceIn(1, frameH)
            ContentRect(0, (frameH - contentH) / 2, frameW, contentH)
        } else {
            val contentW = (frameH * contentAspect).toInt().coerceIn(1, frameW)
            ContentRect((frameW - contentW) / 2, 0, contentW, frameH)
        }
    }

    /** Begins the fade-out; the overlay is discarded once the animation finishes. */
    fun startClose() {
        closing = true
    }

    /** Releases the GPU texture and upload resources. */
    fun cleanup(mc: Minecraft) {
        try {
            uploader?.cleanup()
        } catch (_: Exception) {
        }
        uploader = null
        rgbaUploadBuffer = null
        val id = textureId ?: return
        textureId = null
        try {
            mc.textureManager.release(id)
        } catch (_: Exception) {
        }
        dynamicTexture = null
    }

    companion object {
        private val EMPTY_DIRECT: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

        /** Portion of the screen the video area occupies in [FullscreenMode.STANDARD]. */
        private const val STANDARD_FRACTION = 0.92f

        /** Scrim opacity behind the video in [FullscreenMode.STANDARD]. */
        private const val SCRIM_ALPHA = 0.72f

        /** Minimum gap between two plate tint samples. */
        private const val TINT_SAMPLE_INTERVAL_NANOS = 250_000_000L

        /** Height of the screen band used for the plate tint sample. */
        private const val TINT_BAND_H = 90

        /** Samples per axis of the plate tint grid. */
        private const val TINT_GRID = 8
    }

    private data class ContentRect(val x: Int, val y: Int, val w: Int, val h: Int)
}
