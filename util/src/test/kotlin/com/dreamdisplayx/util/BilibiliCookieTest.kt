package com.dreamdisplayx.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BilibiliCookieTest {
    @Test
    fun parsesNestedJsonCookiesAndDropsAttributes() {
        val json = """
            {"code":0,"data":{"cookies":{"SESSDATA":"abc","bili_jct":"def","DedeUserID":"123","Path":"/"}}}
        """.trimIndent()

        val cookie = BilibiliCookie.parse(json)

        assertEquals("SESSDATA=abc; bili_jct=def; DedeUserID=123", cookie)
        assertTrue(BilibiliCookie.hasSession(cookie))
    }

    @Test
    fun parsesHeaderListAndStripsQuotes() {
        val raw = "SESSDATA=\"abc\"; Path=/; Secure; bili_jct=def; HttpOnly"

        val cookie = BilibiliCookie.parse(raw)

        assertEquals("SESSDATA=abc; bili_jct=def", cookie)
    }

    @Test
    fun keepsBrowserCookiesNeededByBilibiliApis() {
        val raw = "SESSDATA=abc; buvid3=one; buvid4=two; b_nut=three; bili_jct=def"

        val cookie = BilibiliCookie.parse(raw)

        assertEquals("SESSDATA=abc; buvid3=one; buvid4=two; b_nut=three; bili_jct=def", cookie)
    }

    @Test
    fun mergeKeepsFirstValueAndDoesNotDuplicate() {
        val merged = BilibiliCookie.merge(
            "SESSDATA=first; bili_jct=one",
            "SESSDATA=second; buvid3=two",
        )

        assertEquals("SESSDATA=first; bili_jct=one; buvid3=two", merged)
    }

    @Test
    fun rejectsCookieWithoutSession() {
        assertFalse(BilibiliCookie.hasSession("bili_jct=def; buvid3=one"))
        assertFalse(BilibiliCookie.hasSession(""))
    }
}
