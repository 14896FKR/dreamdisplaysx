package com.dreamdisplayx.platform.client.danmaku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DanmakuBlocklistImporterTest {

    @Test
    fun `json import appends enabled rules and skips disabled uid and duplicates`() {
        val json = """
            [
              {"type":0,"filter":"广告","opened":true},
              {"type":1,"filter":"(.)\\1{5,}","opened":true},
              {"type":1,"filter":"disabled","opened":false},
              {"type":2,"filter":"12345","opened":true}
            ]
        """.trimIndent()

        val result = DanmakuBlocklistImporter.importText(json, "rules.json", "广告")

        assertEquals(1, result.added)
        assertEquals(1, result.skippedDisabled)
        assertEquals(1, result.skippedUid)
        assertEquals(1, result.skippedDuplicate)
        assertTrue(result.updatedRaw.lines().contains("/(.)\\1{5,}/"))
    }

    @Test
    fun `xml import keeps enabled regex and skips headers and disabled items`() {
        val xml = """
            <filters>
              <item enabled="true">t=header</item>
              <item enabled="true">r=foo,bar</item>
              <item enabled="true">普通词</item>
              <item enabled="false">disabled</item>
            </filters>
        """.trimIndent()

        val result = DanmakuBlocklistImporter.importText(xml, "rules.xml", "")

        assertEquals(2, result.added)
        assertEquals(1, result.skippedDisabled)
        assertTrue(result.updatedRaw.lines().contains("/foo,bar/"))
        assertTrue(result.updatedRaw.lines().contains("普通词"))
    }

    @Test
    fun `text import supports comments r prefix and slash regexes`() {
        val text = """
            # comment
            广告
            r=foo,bar
            /baz,qux/
        """.trimIndent()

        val result = DanmakuBlocklistImporter.importText(text, "rules.txt", "广告")

        assertEquals(2, result.added)
        assertEquals(1, result.skippedDuplicate)
        assertTrue(result.updatedRaw.lines().contains("/foo,bar/"))
        assertTrue(result.updatedRaw.lines().contains("/baz,qux/"))
    }

    @Test
    fun `invalid regex is counted and not written`() {
        val result = DanmakuBlocklistImporter.importText("/(/", "rules.txt", "")

        assertEquals(0, result.added)
        assertEquals(1, result.skippedInvalid)
        assertEquals("", result.updatedRaw)
    }

    @Test
    fun `single line encoding keeps comma regexes intact`() {
        val line = DanmakuBlockWords.toSingleLine("普通词\n/foo,bar/\n/baz;qux/")

        assertFalse(line.contains('\n'))
        assertEquals(
            listOf("普通词", "/foo,bar/", "/baz;qux/"),
            DanmakuBlockWords.parse(line).map { it.canonical },
        )
    }
}
