package com.dreamdisplayx.media.player.managers

import com.dreamdisplayx.api.media.model.StretchMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the letterbox/crop window geometry: CROP must cover the texture (negative offsets, cropped by
 * fitFrame) and LETTERBOX must fit inside it with non-negative bars.
 */
class LibVlcFitWindowTest {

    private fun crop(srcW: Int, srcH: Int, dstW: Int, dstH: Int) =
        fitWindow(StretchMode.CROP, srcW, srcH, dstW, dstH)

    private fun letterbox(srcW: Int, srcH: Int, dstW: Int, dstH: Int) =
        fitWindow(StretchMode.LETTERBOX, srcW, srcH, dstW, dstH)

    @Test
    fun `crop always covers the destination and overflows symmetrically`() {
        val win = crop(2560, 1442, 1080, 1080)
        assertTrue(win.fitW >= 1080, "crop window narrower than the texture: $win")
        assertTrue(win.fitH >= 1080, "crop window shorter than the texture: $win")
        assertEquals(win.offX, (1080 - win.fitW) / 2)
        assertEquals(win.offY, (1080 - win.fitH) / 2)
        assertTrue(win.offX <= 0 && win.offY <= 0, "crop window does not overflow: $win")
    }

    @Test
    fun `crop of a portrait source on a landscape texture crops the height`() {
        val win = crop(1088, 1922, 1920, 1080)
        assertTrue(win.fitW >= 1920, "crop window narrower than the texture: $win")
        assertTrue(win.fitH >= 1080, "crop window shorter than the texture: $win")
        assertTrue(win.offY < 0, "portrait crop should overflow vertically: $win")
    }

    @Test
    fun `letterbox always fits inside the destination with non-negative bars`() {
        for ((srcW, srcH) in listOf(2560 to 1442, 1088 to 1922, 480 to 866, 368 to 642)) {
            val win = letterbox(srcW, srcH, 1080, 1080)
            assertTrue(win.fitW in 1..1080, "letterbox window wider than the texture: $win")
            assertTrue(win.fitH in 1..1080, "letterbox window taller than the texture: $win")
            assertTrue(win.offX >= 0 && win.offY >= 0, "letterbox bars must not be negative: $win")
        }
    }

    @Test
    fun `the window is never empty for any mode and any sane size`() {
        for (mode in listOf(StretchMode.LETTERBOX, StretchMode.CROP)) {
            for (srcW in listOf(1, 2, 3, 360, 2560)) {
                for (srcH in listOf(1, 2, 3, 202, 1442)) {
                    for ((dstW, dstH) in listOf(1 to 1, 2 to 2, 360 to 360, 1080 to 1080)) {
                        val win = fitWindow(mode, srcW, srcH, dstW, dstH)
                        assertTrue(win.fitW >= 1, "$mode ${srcW}x$srcH -> ${dstW}x$dstH gave $win")
                        assertTrue(win.fitH >= 1, "$mode ${srcW}x$srcH -> ${dstW}x$dstH gave $win")
                    }
                }
            }
        }
    }

    @Test
    fun `degenerate sizes fall back to the destination without throwing`() {
        for (mode in listOf(StretchMode.LETTERBOX, StretchMode.CROP, StretchMode.STRETCH)) {
            // No source pixels: the destination is handed back unchanged.
            assertEquals(FitWindow(1080, 1080, 0, 0), fitWindow(mode, 0, 1442, 1080, 1080))
            // No destination: mirror the inputs instead of dividing by zero.
            assertEquals(FitWindow(0, 1080, 0, 0), fitWindow(mode, 2560, 1442, 0, 1080))
        }
    }
}
