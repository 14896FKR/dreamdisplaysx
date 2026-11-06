package com.dreamdisplayx.media.player.cdn

import com.dreamdisplayx.api.media.stream.model.MediaStream
import com.dreamdisplayx.api.security.policy.MediaHosts
import com.dreamdisplayx.util.net.DreamHttpClient
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Ranks API-provided CDN candidates; signed mcdn URLs are never host-rewritten. */
object CdnSpeedProbe {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/CdnSpeedProbe")

    /** Chunk size for on-the-fly bandwidth probes (short budget during playback). */
    private const val PROBE_BANDWIDTH_BYTES = 256 * 1024

    /**
     * Chunk size for the startup pre-probe — same as PiliPlus's CDN speed test
     * (`maxSize = 8 * 1024 * 1024`).  A large download lets TCP leave slow-start and reflects the
     * real achievable throughput; a 256 KB chunk mostly measures connection setup.
     */
    private const val STARTUP_PROBE_BANDWIDTH_BYTES = 8 * 1024 * 1024

    /** Per-host budget for the startup pre-probe, matching PiliPlus's 15 s receive timeout. */
    private const val STARTUP_PROBE_TIMEOUT_MS = 15_000L

    /** Budget for the startup pre-probe; runs on a background thread so it can afford to be slow. */
    private const val WARMUP_BUDGET_MS = 60_000L

    /** Cache of hostname → measured bandwidth (MB/s × 1000, higher = faster); -1 = failed. */
    private val hostScoreCache = ConcurrentHashMap<String, Long>()

    /** Guards [startupProbe] so it only runs once per process. */
    private val startupProbeStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Regex for Bilibili upos-mirror URLs whose host can be replaced. */
    private val MIRROR_REGEX = Regex(
        """^https?://(?:upos-\w+-(?!302)\w+|(?:upos|proxy)-tf-[^/]+)\.(?:bilivideo|akamaized)\.(?:com|net)/upgcxcode""",
    )

    /** Reorders each stream's API candidates; explicit mirror settings rewrite only upos-* URLs. */
    fun reorderForPlayback(
        video: MediaStream?,
        audio: MediaStream?,
        preferredMirrorHost: String? = null,
        authReferer: String? = null,
    ): Pair<MediaStream?, MediaStream?> {
        if (video == null && audio == null) return video to audio

        val explicit = preferredMirrorHost?.trim()
        if (explicit.equals("BASE_URL", ignoreCase = true)) {
            logger.info("CDN mirror: BASE_URL selected, keeping the API's original URLs.")
            return video to audio
        }
        if (explicit.equals("BACKUP_URL", ignoreCase = true)) {
            logger.info("CDN mirror: BACKUP_URL selected, using first backup URL when available.")
            return promoteFirstBackup(video, audio)
        }
        val mirrorHost = if (explicit.isNullOrEmpty() || explicit.equals("auto", ignoreCase = true)) {
            null
        } else {
            resolveMirrorHost(explicit) ?: return video to audio
        }
        val allUrls = buildList {
            video?.let { add(it.url); addAll(it.backupUrls) }
            audio?.let { add(it.url); addAll(it.backupUrls) }
        }
        if (allUrls.none(::isBilibiliCdnUrl)) {
            return reorderByLatency(video, audio, allUrls, authReferer)
        }
        return video?.let { reorderStream(it, mirrorHost) } to audio?.let { reorderStream(it, mirrorHost) }
    }

    /** Resolves a friendly mirror name or a full hostname, rejecting malformed values. */
    private fun resolveMirrorHost(value: String): String? {
        val mirror = BilibiliCdnMirror.entries.firstOrNull {
            it.host != null && (it.name.equals(value, ignoreCase = true) ||
                it.host.equals(value, ignoreCase = true))
        }
        val candidate = mirror?.host ?: value
        val host = runCatching { URI("https://$candidate/").host }
            .getOrNull()?.removeSurrounding("[", "]")
        if (host == null || host.isBlank() || !host.contains('.') || host.contains(' ') ||
            host.any { !it.isLetterOrDigit() && it != '.' && it != '-' }
        ) {
            logger.warn("CDN mirror '{}' is not a valid hostname; ignoring.", value)
            return null
        }
        return host
    }

    /** True when [url] belongs to a Bilibili CDN family whose candidates can be ranked. */
    private fun isBilibiliCdnUrl(url: String): Boolean {
        val host = MediaHosts.hostOf(url)?.lowercase() ?: return false
        return host.contains("bilivideo") || host.contains("akamaized") ||
            host.contains("mcdn") || host.contains("mountaintoys")
    }

    /**
     * Reorders one stream's API candidates. Host rank wins before cached speed so a signed
     * `mcdn` edge can never outrank a usable `upos` backup; cached scores only break ties.
     */
    private fun reorderStream(stream: MediaStream, mirrorHost: String?): MediaStream {
        val raw = (listOf(stream.url) + stream.backupUrls).distinct()
        if (raw.isEmpty()) return stream
        val candidates = raw.mapIndexed { index, original ->
            val url = if (mirrorHost != null && MIRROR_REGEX.containsMatchIn(original)) {
                replaceHost(original, mirrorHost)
            } else {
                original
            }
            Candidate(url = url, host = MediaHosts.hostOf(url), index = index)
        }.distinctBy { it.url }.sortedWith(
            compareBy<Candidate> { if (it.host?.let(::isPenalized) == true) 1 else 0 }
                .thenBy { if (mirrorHost != null && MIRROR_REGEX.containsMatchIn(it.url)) 0 else 1 }
                .thenBy { hostRank(it.host) }
                .thenByDescending { it.host?.let { host -> hostScoreCache[host] } ?: -1L }
                .thenBy { it.index },
        )
        val first = candidates.first()
        val backups = candidates.drop(1).map { it.url }
        if (first.url == stream.url && backups == stream.backupUrls) return stream
        logger.info(
            "CDN candidate order: {} first ({} candidates).",
            first.host ?: "unknown",
            candidates.size,
        )
        return stream.copy(url = first.url, backupUrls = backups)
    }

    /** API host family priority: upos first, ordinary mirrors next, P2P mcdn last. */
    private fun hostRank(host: String?): Int {
        if (host == null) return 5
        val lower = host.lowercase()
        return when {
            lower.contains("mcdn") -> 4
            lower.startsWith("upos-") -> 0
            lower.contains("bilivideo") || lower.contains("akamaized") -> 1
            else -> 2
        }
    }

    private data class Candidate(val url: String, val host: String?, val index: Int)

    /** Replaces the host of a Bilibili `upos-*` URL with [newHost]. */
    private fun replaceHost(url: String, newHost: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        return runCatching { URI(uri.scheme, newHost, uri.path, uri.query, uri.fragment).toString() }
            .getOrNull() ?: url
    }

    /**
     * `BACKUP_URL` selector: promotes each stream's first backup URL to the primary position
     * (matching PiliPlus's "backup URL" CDN option). Streams without backups are unchanged.
     */
    private fun promoteFirstBackup(
        video: MediaStream?,
        audio: MediaStream?,
    ): Pair<MediaStream?, MediaStream?> {
        var changed = false
        fun promote(s: MediaStream): MediaStream {
            val first = s.backupUrls.firstOrNull() ?: return s
            if (first == s.url) return s
            changed = true
            return s.copy(
                url = first,
                backupUrls = listOf(s.url) + s.backupUrls.drop(1),
            )
        }
        val newVideo = video?.let { promote(it) }
        val newAudio = audio?.let { promote(it) }
        return if (changed) newVideo to newAudio else video to audio
    }

    // ── Bandwidth probe ─────────────────────────────────────────────────────

    /**
     * Aligns a secondary rendition URL (e.g. the scrub-preview stream) to the mirror host of the
     * currently playing stream. Secondary renditions never go through [reorderForPlayback], so
     * without this they keep the API's original host — frequently a slow edge (the log showed a
     * 360p scrub stream on a mirror that probed far below the selected mirror's throughput), which
     * makes every scrub-preview seek time out.
     *
     * Pure and synchronous: rewrites only when BOTH URLs are host-replaceable Bilibili mirrors, so
     * every selection mode is respected automatically — auto (playing host = probed best edge),
     * explicit mirror (playing host = the configured one), BASE_URL (original host, not a mirror
     * URL → no rewrite, scrub stays on its original host too), BACKUP_URL (same as playing).
     */
    fun alignSecondaryRenditionHost(url: String, playingUrl: String?): String {
        if (playingUrl.isNullOrEmpty()) return url
        if (!MIRROR_REGEX.containsMatchIn(url) || !MIRROR_REGEX.containsMatchIn(playingUrl)) return url
        val playingHost = MediaHosts.hostOf(playingUrl) ?: return url
        val currentHost = MediaHosts.hostOf(url) ?: return url
        if (playingHost == currentHost) return url
        return replaceHost(url, playingHost)
    }

    /** Picks a scrub-preview URL from the API backups so it does not start on a signed mcdn edge. */
    fun alignSecondaryRendition(url: String, backups: List<String>, playingUrl: String?): String {
        val candidates = (listOf(url) + backups).distinct()
        if (candidates.isEmpty()) return url
        val playingHost = playingUrl?.let { MediaHosts.hostOf(it) }
        val chosen = candidates.sortedWith(
            compareBy<String> { hostRank(MediaHosts.hostOf(it)) }
                .thenByDescending { hostScoreCache[MediaHosts.hostOf(it) ?: ""] ?: -1L },
        ).firstOrNull { candidate ->
            MediaHosts.hostOf(candidate)?.let { !isPenalized(it) } ?: true
        } ?: candidates.first()
        return if (playingUrl != null && playingHost != null && MIRROR_REGEX.containsMatchIn(chosen) &&
            MIRROR_REGEX.containsMatchIn(playingUrl)
        ) {
            replaceHost(chosen, playingHost)
        } else {
            chosen
        }
    }

    /**
     * Measures throughput to [host] by requesting a Range from [url].
     * @param chunkBytes  number of bytes to request (Range `bytes=0-{chunkBytes-1}`).
     *                    Startup probe uses 8 MB (matching PiliPlus); on-the-fly uses 256 KB.
     * @param timeoutMs  per-host timeout; startup uses 15 s (matching PiliPlus), on-the-fly uses 3 s.
     * Returns throughput in MB/s × 1000 (higher = better), or -1 on failure.
     */
    private fun probeBandwidth(
        host: String,
        url: String,
        deadlineNanos: Long,
        authReferer: String?,
        chunkBytes: Int = PROBE_BANDWIDTH_BYTES,
        timeoutMs: Long = STARTUP_PROBE_TIMEOUT_MS,
    ): Long {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            .coerceIn(500L, timeoutMs)
        val start = System.nanoTime()
        return try {
            val headers = mutableListOf("Range" to "bytes=0-${chunkBytes - 1}")
            authReferer?.let { headers.add("Referer" to it) }
            MediaHosts.refererFor(url)?.let { ref ->
                if (ref !in headers.map { it.second }) headers.add("Referer" to ref)
            }
            val response = DreamHttpClient.executeLimited(
                url,
                chunkBytes,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf(*headers.toTypedArray()),
                    connectTimeoutMs = remainingMs,
                    readTimeoutMs = remainingMs,
                    callTimeoutMs = remainingMs,
                ),
            )
            if (!response.isSuccessful) {
                logger.debug("CDN bandwidth probe for $host returned HTTP ${response.code}; skipping.")
                return -1
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            if (elapsedMs <= 0.0) return Long.MAX_VALUE // instant = loopback
            val bytes = response.body.size.coerceAtLeast(1)
            // bytes / elapsedMs = B/ms = MB/s × 1000 (since B/ms * 1000 = B/s, B/s / 1e6 = MB/s).
            // So the raw quotient is already MB/s × 1000 — no extra factor needed.
            (bytes / elapsedMs).toLong().coerceAtLeast(1)
        } catch (e: IOException) {
            logger.debug("CDN bandwidth probe for $host failed: ${e.message ?: e::class.java.simpleName}")
            -1
        } catch (e: Exception) {
            logger.debug("CDN bandwidth probe for $host aborted: ${e.message ?: e::class.java.simpleName}")
            -1
        }
    }

    // ── Fallback TTFB reorder (non-Bilibili) ────────────────────────────────

    /** Cache of hostname → latency (µs) for the current run. */
    private val hostLatencyMicros = ConcurrentHashMap<String, Long>()

    /** Reorders streams by latency (TTFB) for non-Bilibili candidates. */
    private fun reorderByLatency(
        video: MediaStream?,
        audio: MediaStream?,
        urls: List<String>,
        authReferer: String?,
    ): Pair<MediaStream?, MediaStream?> {
        if (urls.size <= 1) return video to audio

        val hostToUrls = LinkedHashMap<String, MutableList<String>>()
        for (url in urls) {
            val host = MediaHosts.hostOf(url) ?: continue
            hostToUrls.getOrPut(host) { mutableListOf() }.add(url)
        }
        if (hostToUrls.size <= 1) return video to audio

        val scores = LinkedHashMap<String, Long>()
        for (host in hostToUrls.keys) {
            val cached = hostLatencyMicros[host] ?: continue
            scores[host] = cached
        }
        if (scores.isEmpty()) return video to audio

        val order = scores.entries.sortedBy { it.value }.map { it.key }
        if (order.size < 2) return video to audio

        val detail = order.joinToString(", ") { host -> "$host=${scores[host]?.let { "${it / 1000.0}ms" } ?: "?"}" }
        logger.info("CDN latency (cached): $detail")

        fun reorderStream(s: MediaStream): MediaStream {
            val candidates = listOf(s.url) + s.backupUrls
            val rank: (String) -> Int = { url ->
                val h = MediaHosts.hostOf(url)
                if (h == null) Int.MAX_VALUE else order.indexOf(h).let { if (it < 0) Int.MAX_VALUE else it }
            }
            val sorted = candidates.sortedBy(rank)
            if (sorted == candidates) return s
            return s.copy(url = sorted.first(), backupUrls = sorted.drop(1))
        }

        val newVideo = video?.let { reorderStream(it) }
        val newAudio = audio?.let { reorderStream(it) }
        if (newVideo == video && newAudio == audio) return video to audio
        return newVideo to newAudio
    }

    // ── Startup pre-probe & public queries ─────────────────────────────────

    /**
     * Startup pre-probe: given sample Bilibili stream URLs fetched from a known public video
     * (e.g. PiliPlus's sample `BV1fK4y1t7hj`), this measures bandwidth to every known
     * [BilibiliCdnMirror] host and caches the scores.  Called at game startup on a background
     * thread so the first Bilibili playback already has a complete mirror ranking — subsequent
     * auto playbacks consume the cached scores and never probe again.
     *
     * Based on the PiliPlus CDNService approach — the same content is available from any mirror,
     * so we pick the fastest edge for the user's location.
     */
    fun startupProbe(templateUrls: List<String>, authReferer: String? = null) {
        if (!startupProbeStarted.compareAndSet(false, true)) return
        val templateUrl = templateUrls.firstOrNull { MIRROR_REGEX.containsMatchIn(it) }
            ?: templateUrls.firstOrNull { it.contains("/upgcxcode/") }
            ?: return
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WARMUP_BUDGET_MS)
        val ranked = LinkedHashMap<String, Long>()
        val visible = BilibiliCdnMirror.entries.filter { it.host != null }
        val total = visible.size
        for (mirror in visible) {
            val host = mirror.host!!
            if (isPenalized(host)) continue
            val cached = hostScoreCache[host]
            if (cached != null) {
                if (cached >= 0) ranked[host] = cached
                continue
            }
            if (System.nanoTime() > deadline) {
                logger.info("CDN startup probe: budget exhausted after {}/{} mirrors.", ranked.size, total)
                break
            }
            val probeUrl = replaceHost(templateUrl, host)
            if (probeUrl == templateUrl) continue // replaceHost failed
            val score = probeBandwidth(
                host, probeUrl, deadline, authReferer,
                chunkBytes = STARTUP_PROBE_BANDWIDTH_BYTES,  // 8 MB, same as PiliPlus
                timeoutMs = STARTUP_PROBE_TIMEOUT_MS,        // 15 s, same as PiliPlus
            )
            hostScoreCache[host] = score
            if (score >= 0) ranked[host] = score
        }
        if (ranked.isEmpty()) {
            logger.info("CDN startup probe: no mirrors reachable.")
            return
        }
        val ordered = ranked.entries.sortedByDescending { it.value }
        val detail = ordered.joinToString(", ") { (h, s) -> "$h=${s / 1000.0}MB/s" }
        logger.info("CDN startup probe (based on PiliPlus mirror list, {} mirrors): {}", ranked.size, detail)
        val best = ordered.first()
        logger.info(
            "CDN startup probe: fastest mirror = {} ({} MB/s)",
            best.key,
            "%.1f".format(best.value / 1000.0),
        )
    }

    /** Returns the hostname with the highest cached bandwidth score, or null if none is cached. */
    fun bestMirrorHost(): String? = hostScoreCache
        .filter { it.value >= 0 }
        .maxByOrNull { it.value }?.key

    /**
     * Returns all known mirrors with their cached bandwidth scores, sorted fastest-first.
     * Each pair is `(hostname, MB/s × 1000)`.
     */
    fun mirrorRanking(): List<Pair<String, Long>> = hostScoreCache
        .filter { it.value >= 0 }
        .entries.sortedByDescending { it.value }
        .map { it.key to it.value }

    /** Clears all cached scores so the next probe runs fresh. */
    fun clearScores() {
        hostScoreCache.clear()
        startupProbeStarted.set(false)
    }

    // ── Early-failure penalty ───────────────────────────────────────────────

    /**
     * Hosts that dropped a live stream mid-play (early EOS / repeated stalls) with the timestamp of
     * the failure. Short-burst bandwidth probes can badly overestimate a throttling edge, so a
     * host that starves during actual playback is excluded from ranking for a cooldown window.
     */
    private val penalizedHosts = ConcurrentHashMap<String, Long>()

    /** How long a penalized host stays out of the ranking. */
    private const val HOST_PENALTY_MS = 10 * 60_000L

    /**
     * Records that [url]'s host failed during playback, so the next resolution prefers a
     * different mirror. Score cache entry is dropped so a fresh probe may re-rank it later.
     */
    fun penalizeHost(url: String?) {
        val host = url?.let { MediaHosts.hostOf(it) } ?: return
        penalizedHosts[host] = System.currentTimeMillis()
        hostScoreCache.remove(host)
        logger.warn("CDN host '{}' penalized for {} s after a mid-play failure.", host, HOST_PENALTY_MS / 1000)
    }

    /** True while [host] is inside its post-failure cooldown. */
    private fun isPenalized(host: String): Boolean {
        val at = penalizedHosts[host] ?: return false
        if (System.currentTimeMillis() - at >= HOST_PENALTY_MS) {
            penalizedHosts.remove(host)
            return false
        }
        return true
    }

    /** True if the host of [url] is currently serving (not penalized). */
    fun isHostUsable(url: String?): Boolean {
        val host = url?.let { MediaHosts.hostOf(it) } ?: return true
        return !isPenalized(host)
    }
}