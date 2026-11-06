package com.dreamdisplayx.media.source.bilibili

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BilibiliApiUnionFlagTest {

    @Test
    fun `numeric union flag is recognized`() {
        val item = buildJsonObject { put("is_union_video", 1) }
        assertTrue(BilibiliApi.unionFlag(item))
    }

    @Test
    fun `boolean cooperation flag is recognized`() {
        val item = buildJsonObject { put("is_cooperation", true) }
        assertTrue(BilibiliApi.unionFlag(item))
    }

    @Test
    fun `rights cooperation flag is recognized`() {
        val item = buildJsonObject {
            put("rights", buildJsonObject { put("is_cooperation", 1) })
        }
        assertTrue(BilibiliApi.unionFlag(item))
    }

    @Test
    fun `zero and absent flags stay false`() {
        assertFalse(BilibiliApi.unionFlag(buildJsonObject { put("is_union_video", 0) }))
        assertFalse(BilibiliApi.unionFlag(buildJsonObject { }))
    }
}
