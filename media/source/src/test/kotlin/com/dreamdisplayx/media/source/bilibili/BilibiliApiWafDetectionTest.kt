package com.dreamdisplayx.media.source.bilibili

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [BilibiliApi.isBannedResponse], which separates Bilibili's WAF rejection from an ordinary
 * transport failure so the caller can park the request instead of retrying into the ban.
 */
class BilibiliApiWafDetectionTest {

    private fun nested(message: String): Throwable =
        IOException("outer", IOException(message))

    @Test
    fun `the exact 412 line from the tester log is detected`() {
        val line = "java.io.IOException: HTTP 412 for " +
            "https://api.bilibili.com/x/web-interface/view?bvid=BV1bsxC6KEAo: " +
            "{\"code\":-412,\"message\":\"request was banned\",\"ttl\":1}."
        assertTrue(BilibiliApi.isBannedResponse(IOException(line)))
    }

    @Test
    fun `a bare HTTP 412 status is detected`() {
        assertTrue(BilibiliApi.isBannedResponse(IOException("HTTP 412 for https://api.bilibili.com/x/player/playurl")))
    }

    @Test
    fun `the ban is detected through a wrapped cause chain`() {
        assertTrue(BilibiliApi.isBannedResponse(nested("HTTP 412 for https://api.bilibili.com/x/web-interface/view")))
    }

    @Test
    fun `a timeout is not mistaken for a WAF ban`() {
        assertFalse(BilibiliApi.isBannedResponse(IOException("java.net.SocketTimeoutException: Read timed out")))
    }

    @Test
    fun `a missing video code is not mistaken for a WAF ban`() {
        assertFalse(BilibiliApi.isBannedResponse(IOException("{\"code\":-404,\"message\":\"not found\"}")))
    }

    @Test
    fun `an unrelated HTTP error is not a WAF ban`() {
        assertFalse(BilibiliApi.isBannedResponse(IOException("HTTP 500 for https://api.bilibili.com/x/web-interface/view")))
        assertFalse(BilibiliApi.isBannedResponse(IOException("Connection reset")))
    }
}
