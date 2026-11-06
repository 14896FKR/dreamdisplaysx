package com.dreamdisplayx.media.source.bilibili

import java.util.concurrent.ConcurrentHashMap

/** One part of a multi-part Bilibili VOD, as listed by `x/player/pagelist`. */
data class BilibiliPart(
    /** 1-based part number used by `?p=`. */
    val page: Int,
    val title: String,
    val durationSec: Int,
)

/**
 * Part lists for multi-part VODs, so the preview panel can offer part chips and the player can move
 * to the next part without another lookup. Keyed by `bv…` / `av…`, refreshed rarely (parts change).
 */
object BilibiliPartListCache {
    private const val TTL_MS = 60L * 60L * 1000L
    private const val FAIL_RETRY_MS = 60L * 1000L

    private data class Entry(val parts: List<BilibiliPart>?, val fetchedAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Cache key for [bvid] / [avid], or null when the link is neither. */
    fun keyOf(bvid: String?, avid: Long?): String? = when {
        !bvid.isNullOrBlank() -> "bv:$bvid"
        avid != null && avid > 0L -> "av:$avid"
        else -> null
    }

    /** Parts cached for [key], or null while the first lookup is still running. */
    fun known(key: String): List<BilibiliPart>? = cache[key]?.parts

    /** Fetches [key]'s part list in the background when it is missing or stale. */
    fun request(key: String, bvid: String?, avid: Long?) {
        val now = System.currentTimeMillis()
        val got = cache[key]
        if (got != null) {
            val age = now - got.fetchedAt
            if (got.parts != null && age < TTL_MS) return
            if (got.parts == null && age < FAIL_RETRY_MS) return
        }
        if (!inFlight.add(key)) return
        Thread {
            try {
                cache[key] = Entry(BilibiliApi.fetchPageList(bvid, avid), System.currentTimeMillis())
            } catch (e: Exception) {
                cache[key] = Entry(null, System.currentTimeMillis())
            } finally {
                inFlight.remove(key)
            }
        }.apply {
            name = "DDX-Bilibili-PartList"
            isDaemon = true
        }.start()
    }
}
