package com.dreamdisplayx.media.source.bilibili

import com.dreamdisplayx.util.optInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class BilibiliAudioSelectionTest {

    private fun row(id: Int, bandwidth: Int = 0): JsonObject = buildJsonObject {
        put("id", id)
        put("bandwidth", bandwidth)
    }

    @Test
    fun `Dolby rows win while AAC rows stay as fallback`() {
        val selected = BilibiliApi.selectAudioRows(
            listOf(row(30232, 132_000), row(30080, 448_000), row(30280, 192_000), row(30255, 512_000)),
        )

        assertEquals(listOf(30255, 30080, 30280, 30232), selected.map { it.optInt("id") })
    }

    @Test
    fun `Dolby block is merged with standard audio rows`() {
        val dash = buildJsonObject {
            putJsonArray("audio") { add(row(30280, 192_000)) }
            putJsonObject("dolby") { putJsonArray("audio") { add(row(30080, 448_000)) } }
        }

        val selected = BilibiliApi.selectAudioRows(BilibiliApi.dashAudioRows(dash))

        assertEquals(listOf(30080, 30280), selected.map { it.optInt("id") })
    }

    @Test
    fun `AAC rows are used when Dolby is absent`() {
        val selected = BilibiliApi.selectAudioRows(listOf(row(30232, 132_000), row(30280, 192_000)))

        assertEquals(listOf(30280, 30232), selected.map { it.optInt("id") })
    }

    @Test
    fun `unknown rows are kept when neither Dolby nor AAC exists`() {
        val selected = BilibiliApi.selectAudioRows(listOf(row(12345), row(67890)))

        assertEquals(listOf(12345, 67890), selected.map { it.optInt("id") })
    }
}
