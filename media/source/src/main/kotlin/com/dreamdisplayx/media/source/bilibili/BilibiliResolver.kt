package com.dreamdisplayx.media.source.bilibili

import com.dreamdisplayx.api.media.model.DreamMediaException
import com.dreamdisplayx.api.media.source.service.MediaResolverService
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.media.source.model.ResolvedMedia
import com.dreamdisplayx.media.source.platform.LiveAwareResolvedMediaCache
import org.slf4j.LoggerFactory

/**
 * In-process Bilibili resolver: one site-API call (see [BilibiliApi]), mirroring the other
 * first-party resolvers.
 */
object BilibiliResolver : MediaResolverService {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/BilibiliResolver")

    /** Alongside the other first-party in-process resolvers. */
    override val priority: Int = 10

    /** Live playurl URLs are session-bound, so live entries expire fast; VODs are not cached
     * so that login-state changes (e.g. rejoin after a Bilibili logout) always pick up the current
     * credential and the highest available resolution — stale cached entries would otherwise keep
     * serving e.g. 480p long after the user logged back in. */
    private val cache = LiveAwareResolvedMediaCache(maxSize = 64, liveTtlSeconds = 25, staticTtlMinutes = 0)

    /** Shown when a video turns out to be a 360/VR panorama, which this mod cannot project. */
    private const val PANORAMIC_MESSAGE =
        "This is a 360/VR panorama video; Dream DisplaysX cannot project it onto a flat display yet."

    /** Resolutions warmed by [prefetch], reusable once by the resolve that follows (queue preload). */
    private val prefetchCache = java.util.concurrent.ConcurrentHashMap<String, PrefetchEntry>()

    /** One warmed resolution and the instant it stops being reusable. */
    private data class PrefetchEntry(val media: ResolvedMedia, val expiresAtNanos: Long)

    /** How long a warmed resolution may wait for its consumer; a playlist advance lands well inside. */
    private const val PREFETCH_TTL_NANOS = 90_000_000_000L

    override fun canResolve(source: MediaSource): Boolean = source is MediaSource.Bilibili

    override fun prefetch(source: MediaSource): Boolean {
        val bilibili = source as? MediaSource.Bilibili ?: return false
        val resolved = runCatching { resolveCached(bilibili) }.getOrNull() ?: return false
        // Prefetched queue entries may be reused exactly once by the resolve that follows, which
        // skips the playurl round trip; a quality switch resolves again and gets a fresh result.
        val key = BilibiliMetadataCache.cacheKey(bilibili) ?: return true
        prefetchCache[key] = PrefetchEntry(resolved, System.nanoTime() + PREFETCH_TTL_NANOS)
        return true
    }

    override fun resolve(source: MediaSource): ResolvedMedia {
        val bilibili = source as? MediaSource.Bilibili
            ?: throw UnsupportedOperationException("$source is not a Bilibili source.")
        return resolveCached(bilibili)
    }

    /** Drops [url]'s cached resolution so a dying live playurl is re-minted, not re-served. */
    fun invalidate(url: String) {
        (MediaSource.from(url) as? MediaSource.Bilibili)?.let { source ->
            BilibiliMetadataCache.cacheKey(source)?.let(cache::invalidate)
        }
    }

    private fun resolveCached(source: MediaSource.Bilibili): ResolvedMedia {
        val key = BilibiliMetadataCache.cacheKey(source)
            ?: throw DreamMediaException.NotFound("Unrecognized Bilibili URL: ${source.url}.")
        cache.get(key)?.let { return it }
        // A warmed entry is consumed here so it can serve only the next resolve.
        prefetchCache.remove(key)?.let { warmed ->
            if (System.nanoTime() < warmed.expiresAtNanos) {
                logger.debug("Resolved Bilibili {} from the prefetch entry.", key)
                return warmed.media
            }
        }

        val playback = BilibiliApi.resolve(source)
            ?: throw DreamMediaException.NotFound("Bilibili video/room could not be reached.")
        BilibiliMetadataCache.put(source, playback.metadata)

        // A 360/VR panorama is equirectangular: on a flat display it is a warped sphere map.
        if (playback.panoramic) throw DreamMediaException.NotFound(PANORAMIC_MESSAGE)

        if (playback.streams.isEmpty()) {
            throw DreamMediaException.NotFound(
                when {
                    source.roomId != null -> "This Bilibili live room is offline right now."
                    source.epId != null || source.seasonId != null ->
                        "This Bilibili anime/movie is unavailable or region-locked."
                    else -> "This Bilibili video is unavailable or private."
                },
            )
        }

        logger.debug(
            "Resolved Bilibili {}: {} streams, live={}.", key, playback.streams.size, playback.metadata.isLive,
        )
        val resolved = ResolvedMedia(
            streams = playback.streams,
            metadata = playback.metadata.toMediaMetadata(),
            isLive = playback.metadata.isLive,
            isSeekable = playback.isSeekable,
        )
        cache.put(key, resolved)
        return resolved
    }
}
