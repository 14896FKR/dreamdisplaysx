package com.dreamdisplayx.media.source.bilibili

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** Uploader profile fields answered by Bilibili's public web endpoints. */
data class BilibiliUpProfile(
    val mid: Long,
    val name: String,
    val face: String? = null,
    val sign: String? = null,
    val sex: String? = null,
    val level: Int = 0,
    val fans: Long = 0L,
    val following: Long = 0L,
    val official: String? = null,
    val vip: String? = null,
    val nameplate: String? = null,
    val roomId: Long = 0L,
    val roomNews: String? = null,
    /** Bilibili room `live_status`: 1 while the creator is streaming, 0 otherwise or when unknown. */
    val liveStatus: Int = 0,
    val videos: Long = 0L,
    val articles: Long = 0L,
    val albums: Long = 0L,
    val opus: Long = 0L,
    val elec: Long = 0L,
    val guards: Long = 0L,
    val plays: Long = 0L,
    val likes: Long = 0L,
    val birthday: String? = null,
    val school: String? = null,
    val ipLocation: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
)

/** Ten-minute uploader-profile cache with in-flight de-duplication. */
object BilibiliUpProfileCache {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/BilibiliUpProfile")
    private const val TTL_MS = 10L * 60L * 1000L
    private const val FAIL_RETRY_MS = 30L * 1000L

    private data class Entry(val profile: BilibiliUpProfile?, val fetchedAt: Long)

    private val cache = ConcurrentHashMap<Long, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    fun known(mid: Long): BilibiliUpProfile? = cache[mid]?.profile

    fun isLoading(mid: Long): Boolean = inFlight.contains(mid)

    fun request(mid: Long) {
        if (mid <= 0L) return
        val now = System.currentTimeMillis()
        val got = cache[mid]
        if (got != null) {
            val age = now - got.fetchedAt
            if (got.profile != null && age < TTL_MS) return
            if (got.profile == null && age < FAIL_RETRY_MS) return
        }
        if (!inFlight.add(mid)) return
        Thread {
            try {
                cache[mid] = Entry(BilibiliApi.fetchUpProfile(mid), System.currentTimeMillis())
            } catch (e: Exception) {
                logger.debug("Bilibili uploader profile failed for {}: {}", mid, e.message)
                cache[mid] = Entry(null, System.currentTimeMillis())
            } finally {
                inFlight.remove(mid)
            }
        }.apply {
            name = "DDX-Bilibili-UpProfile"
            isDaemon = true
        }.start()
    }
}
