package com.dreamdisplayx.api.media.source.url

import kotlin.test.Test
import kotlin.test.assertEquals

class BilibiliUrlQueryTest {

    private fun parsedUrl(input: String): String? = BilibiliUrls.parse(input)?.url

    @Test
    fun `tracking parameters are dropped and the start offset survives`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1WW4w6UErY?t=299.2",
            parsedUrl("https://www.bilibili.com/video/BV1WW4w6UErY?spm_id_from=333.788&vd_source=abc123&t=299.2"),
        )
    }

    @Test
    fun `part parameter survives while share noise is removed`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD?p=2",
            parsedUrl("https://www.bilibili.com/video/BV1xx411c7mD?p=2&share_source=copy_web&share_medium=android&bbid=XYZ"),
        )
    }

    @Test
    fun `url without a query is unchanged`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD",
            parsedUrl("https://www.bilibili.com/video/BV1xx411c7mD"),
        )
    }

    @Test
    fun `live room keeps its id and loses tracking`() {
        assertEquals(
            "https://live.bilibili.com/12345",
            parsedUrl("https://live.bilibili.com/12345?spm_id_from=333.999&from=search"),
        )
    }

    @Test
    fun `query made only of tracking parameters is removed entirely`() {
        assertEquals(
            "https://www.bilibili.com/video/av12345",
            parsedUrl("https://www.bilibili.com/video/av12345?vd_source=deadbeef"),
        )
    }

    @Test
    fun `piliplus playlist link keeps its media id`() {
        val source = BilibiliUrls.parse("https://www.bilibili.com/medialist/detail/ml1234567890?spm_id_from=333.1")
        assertEquals(1234567890L, source?.mediaListId)
        assertEquals(null, source?.bvid)
    }

    @Test
    fun `classic favourites link keeps its fid`() {
        val source = BilibiliUrls.parse("https://www.bilibili.com/favlist?fid=987654&spm_id_from=333.1")
        assertEquals(987654L, source?.mediaListId)
    }

    @Test
    fun `uploader space link is recognised as a space`() {
        val source = BilibiliUrls.parse("https://space.bilibili.com/45678?spm_id_from=333.1")
        assertEquals(45678L, source?.spaceMid)
    }

    @Test
    fun `bare media list id is recognised`() {
        assertEquals(112233L, BilibiliUrls.parse("ml112233")?.mediaListId)
    }

    @Test
    fun `favourites folder under the space host is a playlist not a space`() {
        val source = BilibiliUrls.parse("https://space.bilibili.com/45678/favlist?fid=987654&spm_id_from=333.1")
        assertEquals(987654L, source?.mediaListId)
        assertEquals(null, source?.spaceMid)
        assertEquals("https://space.bilibili.com/45678/favlist?fid=987654", source?.url)
    }
}
