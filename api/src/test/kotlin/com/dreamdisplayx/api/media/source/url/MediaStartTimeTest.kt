package com.dreamdisplayx.api.media.source.url

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaStartTimeTest {

    @Test
    fun `bilibili float seconds timestamp is read`() {
        assertEquals(299_200L, MediaStartTime.extractMillis("https://www.bilibili.com/video/BV1WW4w6UErY?t=299.2"))
    }

    @Test
    fun `timestamp is read next to other query parameters`() {
        assertEquals(30_000L, MediaStartTime.extractMillis("https://www.bilibili.com/video/BV1x?p=2&t=30"))
    }

    @Test
    fun `youtube second and clock forms are read`() {
        assertEquals(90_000L, MediaStartTime.extractMillis("https://www.youtube.com/watch?v=abc&t=90"))
        assertEquals(90_000L, MediaStartTime.extractMillis("https://www.youtube.com/watch?v=abc&t=1m30s"))
        assertEquals(3_723_000L, MediaStartTime.extractMillis("https://www.youtube.com/watch?v=abc&t=1h2m3s"))
    }

    @Test
    fun `start parameter and fragment forms are read`() {
        assertEquals(45_000L, MediaStartTime.extractMillis("https://example.com/video.mp4?start=45"))
        assertEquals(60_000L, MediaStartTime.extractMillis("https://example.com/video.mp4#t=60"))
    }

    @Test
    fun `urls without a timestamp return zero`() {
        assertEquals(0L, MediaStartTime.extractMillis("https://www.bilibili.com/video/BV1WW4w6UErY"))
        assertEquals(0L, MediaStartTime.extractMillis("https://www.bilibili.com/video/BV1x?p=2"))
        assertEquals(0L, MediaStartTime.extractMillis("not a url"))
        assertEquals(0L, MediaStartTime.extractMillis(""))
    }

    @Test
    fun `malformed timestamps are ignored`() {
        assertEquals(0L, MediaStartTime.extractMillis("https://example.com/v?t=abc"))
        assertEquals(0L, MediaStartTime.extractMillis("https://example.com/v?t=-5"))
        assertEquals(0L, MediaStartTime.extractMillis("https://example.com/v?t="))
    }
}
