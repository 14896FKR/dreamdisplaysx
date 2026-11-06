package com.dreamdisplayx.platform.client.displays

import com.dreamdisplayx.api.media.source.url.BilibiliUrls
import com.dreamdisplayx.util.DreamCoroutines
import com.dreamdisplayx.util.net.DreamHttpClient
import com.google.gson.JsonParser
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Client-side Bilibili sponsor/self-promo skip using the bsbsb.top segment mirror. */
internal object BilibiliSponsorSkip {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/BilibiliSponsor")
    private val enabled = System.getProperty("dreamdisplayx.skipSponsorSegments", "true").toBoolean()
    private const val ENDPOINT = "https://bsbsb.top/api/skipSegments?videoID="
    private const val CATEGORIES = "[\"sponsor\",\"selfpromo\"]"

    internal data class Segment(val startMs: Long, val endMs: Long)

    private val cache = ConcurrentHashMap<String, List<Segment>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val applied = ConcurrentHashMap.newKeySet<String>()
    private val diagnosed = ConcurrentHashMap.newKeySet<String>()
    private val lastPosition = ConcurrentHashMap<String, Long>()

    fun tick(screen: DisplayScreen) {
        val url = screen.videoUrl ?: return
        if (diagnosed.add("probe:${screen.uuid}:$url")) {
            logger.info(
                "Bilibili sponsor probe: url={} paused={} live={} canSeek={} posMs={} durMs={}",
                url, screen.paused, screen.isLive, screen.canSeekHere,
                screen.currentTimeNanos / 1_000_000L, screen.mediaPlayerDurationNanos / 1_000_000L,
            )
        }
        if (!enabled || screen.paused || screen.isLive || !screen.canSeekHere) return
        val source = BilibiliUrls.parse(url) ?: return
        val bvid = source.bvid ?: return
        if (screen.mediaPlayerDurationNanos <= 0L) return
        val nowMs = screen.currentTimeNanos / 1_000_000L
        if (nowMs <= 0L) return
        val positionKey = "${screen.uuid}:$bvid"
        val previous = lastPosition.put(positionKey, nowMs)
        if (previous != null && nowMs + 5_000L < previous) {
            // A backward seek (or a fresh replay) must arm the same segments again.
            applied.removeIf { it.startsWith("$positionKey:") }
        }

        val segments = cache[bvid]
        if (segments == null) {
            ensureLoaded(bvid)
            return
        }
        val segment = segments.firstOrNull { nowMs >= it.startMs && nowMs < it.endMs } ?: return
        if (!applied.add("$positionKey:${segment.endMs}")) return
        logger.info(
            "Bilibili sponsor skip: bv={} posMs={} -> {}ms (segment {}..{})",
            bvid, nowMs, segment.endMs, segment.startMs, segment.endMs,
        )
        screen.seekToMillis(segment.endMs)
    }

    private fun ensureLoaded(bvid: String) {
        if (!inFlight.add(bvid)) return
        DreamCoroutines.clientIo.launch {
            try {
                val segments = fetch(bvid)
                cache[bvid] = segments
                val first = segments.firstOrNull()
                logger.info(
                    "Bilibili sponsor segments: bv={} count={} first={}..{}",
                    bvid, segments.size, first?.startMs, first?.endMs,
                )
            } catch (t: Throwable) {
                logger.warn("Bilibili sponsor fetch failed: bv={} error={}", bvid, t.message)
                cache[bvid] = emptyList()
            } finally {
                inFlight.remove(bvid)
            }
        }
    }

    private fun fetch(bvid: String): List<Segment> {
        val categories = URLEncoder.encode(CATEGORIES, StandardCharsets.UTF_8)
        val body = DreamHttpClient.readText(
            ENDPOINT + bvid + "&categories=" + categories,
            DreamHttpClient.RequestOptions(
                headers = DreamHttpClient.headersOf(
                    "User-Agent" to "DreamDisplaysX",
                    "origin" to "DreamDisplaysX",
                ),
                connectTimeoutMs = 8_000,
                readTimeoutMs = 8_000,
            ),
        )
        return parse(body)
    }

    /** Parses bsbsb.top segments (seconds) into sorted, overlapping segments merged in milliseconds. */
    internal fun parse(body: String): List<Segment> = runCatching {
        val array = JsonParser.parseString(body).asJsonArray
        val segments = array.mapNotNull { element ->
            val obj = element.asJsonObject
            val range = obj.getAsJsonArray("segment") ?: return@mapNotNull null
            if (range.size() < 2) return@mapNotNull null
            val start = Math.round(range[0].asDouble * 1000.0)
            val end = Math.round(range[1].asDouble * 1000.0)
            if (end <= start) null else Segment(start, end)
        }.sortedBy { it.startMs }
        if (segments.isEmpty()) return@runCatching emptyList()
        val merged = ArrayList<Segment>(segments.size)
        for (segment in segments) {
            val last = merged.lastOrNull()
            if (last != null && segment.startMs <= last.endMs) {
                merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, segment.endMs))
            } else {
                merged.add(segment)
            }
        }
        merged
    }.getOrDefault(emptyList())
}
