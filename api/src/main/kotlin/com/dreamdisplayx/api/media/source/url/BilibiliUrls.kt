package com.dreamdisplayx.api.media.source.url

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.security.model.MediaHttpUrl
import java.util.*

/**
 * Recognizes and dissects Bilibili URLs (VODs, bangumi episodes / seasons, live rooms, `b23.tv` short links).
 *
 * @since 1.9.x
 */
@DreamDisplaysXUnstableApi
object BilibiliUrls {
    /** A `BVID`: `BV` followed by 10 alphanumeric characters. */
    private val BVID_RE = Regex("^BV[0-9A-Za-z]{10}$")

    /** A legacy `AVID`: `av` followed by digits. */
    private val AVID_RE = Regex("^[aA][vV](\\d+)$")

    /** A bangumi episode id: `ep` followed by digits. */
    private val EP_RE = Regex("^[eE][pP](\\d+)$")

    /** A bangumi season id: `ss` followed by digits. */
    private val SS_RE = Regex("^[sS][sS](\\d+)$")

    /** A media list (favourites / PiliPlus playlist): `ml<id>`. */
    private val ML_RE = Regex("^[mM][lL](\\d+)$")

    /** Route names a locale prefix may precede. */
    private val ROUTES = setOf("video", "bangumi")

    /** A locale path prefix such as `zh-Hans`, `zh-CN` or `en`. */
    private val LOCALE_PREFIX_RE = Regex("^[a-z]{2,3}(?:[-_][a-z]{2,4})?$", RegexOption.IGNORE_CASE)

    /** Drops a leading locale segment, but only when a known route follows it. */
    private fun routeSegments(segments: List<String>): List<String> {
        val first = segments.firstOrNull() ?: return segments
        if (!LOCALE_PREFIX_RE.matches(first)) return segments
        val rest = segments.drop(1)
        return if (rest.firstOrNull()?.lowercase(Locale.ROOT) in ROUTES) rest else segments
    }

    /** True when [url] points at a BIlibili VOD, live room, or short link. */
    fun isBilibili(url: String): Boolean = parse(url) != null

    /**
     * Parses a bare Bilibili id (`BV...`, `av...`, `ep...`, `ss...`) pasted without a URL, or
     * returns null when [id] is not one. The returned source carries a canonical `bilibili.com`
     * URL so downstream resolvers always have something to fetch.
     */
    fun parseBareId(id: String): MediaSource.Bilibili? {
        val trimmed = id.trim()
        if (BVID_RE.matches(trimmed)) {
            return MediaSource.Bilibili(
                url = "https://www.bilibili.com/video/$trimmed",
                bvid = trimmed,
            )
        }
        AVID_RE.matchEntire(trimmed)?.let { m ->
            val avid = m.groupValues[1].toLongOrNull() ?: return null
            return MediaSource.Bilibili(
                url = "https://www.bilibili.com/video/$trimmed",
                avid = avid,
            )
        }
        EP_RE.matchEntire(trimmed)?.let { m ->
            val epId = m.groupValues[1].toLongOrNull() ?: return null
            return MediaSource.Bilibili(
                url = "https://www.bilibili.com/bangumi/play/$trimmed",
                epId = epId,
            )
        }
        ML_RE.matchEntire(trimmed)?.let { m ->
            val mediaListId = m.groupValues[1].toLongOrNull() ?: return null
            return MediaSource.Bilibili(
                url = "https://www.bilibili.com/medialist/detail/ml$mediaListId",
                mediaListId = mediaListId,
            )
        }
        SS_RE.matchEntire(trimmed)?.let { m ->
            val seasonId = m.groupValues[1].toLongOrNull() ?: return null
            return MediaSource.Bilibili(
                url = "https://www.bilibili.com/bangumi/play/$trimmed",
                seasonId = seasonId,
            )
        }
        return null
    }

    /**
     * Parses [url] into a [com.dreamdisplayx.api.media.source.MediaSource.Bilibili], or null if not recognizable. A `b23.tv` short link
     * is recognized but left unresolved (no bvid / avid / roomId) — following its redirect needs a
     * network call, which belongs in [com.dreamdisplayx.media.source.bilibili.BilibiliApi], not here.
     */
    fun parse(url: String): MediaSource.Bilibili? {
        parseBareId(url)?.let { return it }
        val parsed = MediaHttpUrl.parse(url) ?: MediaHttpUrl.parse("https://${url.trim()}") ?: return null
        val host = parsed.uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.removePrefix("m.") ?: return null

        // Tracking noise (?spm_id_from=, vd_source=, share_*) is dropped; only the parameters that
        // change what is played survive.
        val normalized = keepQuery(parsed.value, KEPT_QUERY_KEYS)
        val segments = parsed.uri.path?.split('/')?.filter { it.isNotBlank() } ?: emptyList()

        if (host == "b23.tv") {
            if (segments.isEmpty()) return null
            return MediaSource.Bilibili(url = normalized)
        }

        // Uploader space: opens the creator page, never a playable source. A favourites folder
        // living under the space host (`/<mid>/favlist?fid=<id>`) is a playlist, not the page.
        if (host == "space.bilibili.com") {
            if (segments.getOrNull(1) == "favlist") {
                queryValue(parsed.uri.rawQuery, "fid")?.toLongOrNull()?.let { fid ->
                    return MediaSource.Bilibili(url = normalized, mediaListId = fid)
                }
            }
            val mid = segments.firstOrNull()?.toLongOrNull() ?: return null
            return MediaSource.Bilibili(url = normalized, spaceMid = mid)
        }

        // PiliPlus playlist share link: medialist/detail/ml<id>.
        if (segments.getOrNull(0) == "medialist" && segments.getOrNull(1) == "detail") {
            val mlId = ML_RE.matchEntire(segments.getOrNull(2) ?: "")?.groupValues?.get(1)?.toLongOrNull()
                ?: return null
            return MediaSource.Bilibili(url = normalized, mediaListId = mlId)
        }

        // Classic favourites folder: favlist?fid=<id>.
        if (segments.getOrNull(0) == "favlist") {
            val fid = queryValue(parsed.uri.rawQuery, "fid")?.toLongOrNull() ?: return null
            return MediaSource.Bilibili(url = normalized, mediaListId = fid)
        }

        if (host == "live.bilibili.com") {
            val roomId = segments.firstOrNull()?.toLongOrNull() ?: return null
            return MediaSource.Bilibili(url = normalized, roomId = roomId)
        }

        if (host != "bilibili.com") return null
        val route = routeSegments(segments)

        // Bangumi / movie: /bangumi/play/ep<id> or /bangumi/play/ss<id>.
        if (route.getOrNull(0) == "bangumi" && route.getOrNull(1) == "play") {
            val id = route.getOrNull(2) ?: return null
            EP_RE.matchEntire(id)?.let { m ->
                val epId = m.groupValues[1].toLongOrNull() ?: return null
                return MediaSource.Bilibili(url = normalized, epId = epId)
            }
            SS_RE.matchEntire(id)?.let { m ->
                val seasonId = m.groupValues[1].toLongOrNull() ?: return null
                return MediaSource.Bilibili(url = normalized, seasonId = seasonId)
            }
            return null
        }

        if (route.getOrNull(0) != "video") return null
        val id = route.getOrNull(1) ?: return null
        val part = partQueryParam(parsed.uri.rawQuery)?.toIntOrNull()

        if (BVID_RE.matches(id)) return MediaSource.Bilibili(url = normalized, bvid = id, part = part)
        AVID_RE.matchEntire(id)?.let { m ->
            val avid = m.groupValues[1].toLongOrNull() ?: return null
            return MediaSource.Bilibili(url = normalized, avid = avid, part = part)
        }
        return null
    }

    /** Query parameters worth keeping: `p` selects the part, `t` the start offset, `fid` a favourites list. */
    private val KEPT_QUERY_KEYS = setOf("p", "t", "fid")

    /** Rebuilds [raw] with only [allowed] query parameters, preserving order, base and fragment. */
    internal fun keepQuery(raw: String, allowed: Set<String>): String =
        MediaUrlCleaner.filterQuery(raw) { key -> key in allowed }

    /** Reads [name] from [rawQuery]; used for identifiers such as the favourites `fid`. */
    private fun queryValue(rawQuery: String?, name: String): String? =
        rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) null else pair.substring(0, idx).takeIf { it.equals(name, ignoreCase = true) }
                ?.let { pair.substring(idx + 1) }
        }

    /** Returns the value of the `p` (part index) query parameter in [rawQuery], or null when absent. */
    private fun partQueryParam(rawQuery: String?): String? =
        rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) null else pair.substring(0, idx).takeIf { it == "p" }?.let { pair.substring(idx + 1) }
        }
}
