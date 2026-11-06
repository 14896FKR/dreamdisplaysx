package com.dreamdisplayx.api.media.source.url

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaUrlCleanerTest {

    private fun clean(input: String): String? = MediaUrlCleaner.canonicalize(input)

    @Test
    fun `share text around a link is reduced to the link`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1WW4w6UErY",
            clean("【标题】 https://www.bilibili.com/video/BV1WW4w6UErY?spm_id_from=333.788，"),
        )
    }

    @Test
    fun `bilibili links keep only the parameters that matter`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD?p=2&t=30",
            clean("https://www.bilibili.com/video/BV1xx411c7mD?p=2&vd_source=abc&t=30&share_source=copy_web"),
        )
    }

    @Test
    fun `uploader space link loses its tracking query`() {
        assertEquals(
            "https://space.bilibili.com/45678",
            clean("https://space.bilibili.com/45678?spm_id_from=333.1&share_medium=android"),
        )
    }

    @Test
    fun `youtube share id is dropped while the start offset stays`() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ?t=30",
            clean("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf123&t=30"),
        )
    }

    @Test
    fun `signed stream parameters are never removed`() {
        assertEquals(
            "https://cdn.example.com/v.mp4?auth_key=abc&sign=xyz",
            clean("https://cdn.example.com/v.mp4?auth_key=abc&utm_source=feed&sign=xyz"),
        )
    }

    @Test
    fun `twitch tracking parameters are dropped`() {
        assertEquals(
            "https://www.twitch.tv/somechannel",
            clean("https://www.twitch.tv/somechannel?tt_content=channel&sr=a"),
        )
    }

    @Test
    fun `plain search phrase stays a search phrase`() {
        assertNull(clean("weekly funny moments"))
    }

    @Test
    fun `link without a query is returned unchanged`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD",
            clean("https://www.bilibili.com/video/BV1xx411c7mD"),
        )
    }

    @Test
    fun `fragment is preserved while the query is cleaned`() {
        assertEquals(
            "https://example.com/video#t=60",
            clean("https://example.com/video?spm_id_from=1#t=60"),
        )
    }
}
