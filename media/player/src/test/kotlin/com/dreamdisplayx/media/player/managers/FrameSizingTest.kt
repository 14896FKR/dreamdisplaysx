package com.dreamdisplayx.media.player.managers

import com.dreamdisplayx.api.media.model.StretchMode
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameSizingTest {
    private val source = 864 to 480
    private val display = 1916 to 1080

    @Test
    fun `stretch requests the exact display size`() {
        assertEquals(display, requestedFrameDimensions(source.first, source.second, display.first, display.second, StretchMode.STRETCH))
    }

    @Test
    fun `letterbox preserves the source aspect and fits inside the display`() {
        assertEquals(1916 to 1064, requestedFrameDimensions(source.first, source.second, display.first, display.second, StretchMode.LETTERBOX))
    }

    @Test
    fun `crop preserves the source aspect and covers the display`() {
        assertEquals(1944 to 1080, requestedFrameDimensions(source.first, source.second, display.first, display.second, StretchMode.CROP))
    }

    @Test
    fun `invalid source or display sizes fall back without throwing`() {
        assertEquals(1 to 1, requestedFrameDimensions(0, 480, 1916, 1080, StretchMode.LETTERBOX))
        assertEquals(source, requestedFrameDimensions(source.first, source.second, 0, 0, StretchMode.CROP))
    }
}
