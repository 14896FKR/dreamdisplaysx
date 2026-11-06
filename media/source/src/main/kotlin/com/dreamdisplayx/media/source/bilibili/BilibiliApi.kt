package com.dreamdisplayx.media.source.bilibili

import com.dreamdisplayx.api.media.source.url.BilibiliUrls
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.media.stream.model.MediaStream
import com.dreamdisplayx.api.media.stream.model.MediaStreamType
import com.dreamdisplayx.media.source.bilibili.danmaku.BiliDmSegParser
import com.dreamdisplayx.media.source.bilibili.danmaku.DanmakuEntry
import com.dreamdisplayx.media.source.platform.PlatformVideoMetadata
import com.dreamdisplayx.util.*
import com.dreamdisplayx.util.json.DreamJson
import com.dreamdisplayx.util.net.DreamHttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A resolved BIlibili VOD or live room: playable [streams] and [metadata]. */
data class BilibiliPlayback(
    val streams: List<MediaStream>,
    val metadata: PlatformVideoMetadata,
    val isSeekable: Boolean,
    /** True for a 360/VR panorama, which cannot be projected onto a flat display. */
    val panoramic: Boolean = false,
)

/** One search-result card from a Bilibili keyword search (video / bangumi / movie). */
data class BilibiliSearchItem(
    val bvid: String? = null,
    val title: String,
    val uploader: String? = null,
    val uploaderId: Long? = null,
    /** True when Bilibili marks this as a co-authored video. */
    val isUnionVideo: Boolean = false,
    val thumbnailUrl: String? = null,
    val durationSec: Long? = null,
    val viewCount: Long? = null,
    /** Upload epoch seconds when the API exposes it. */
    val publishedAtEpochSec: Long? = null,
    /** Bangumi / movie episode id (market `bangumi/play/ep<id>`), null for plain videos. */
    val epId: Long? = null,
    /** Bangumi / movie season id (market `bangumi/play/ss<id>`), null for plain videos. */
    val seasonId: Long? = null,
    /** Media category returned by the search API: `video` / `media_bangumi` / `media_ft` / `pgc`. */
    val mediaType: String = "video",
    /** Access mode returned by the API: 0=free, 1=VIP, 2=pay-per-view, etc. */
    val mediaMode: Int? = null,
    /** Display label for the access mode (e.g. "VIP", "付费", "独家"), or null. */
    val accessLabel: String? = null,
)

/** One Bilibili uploader returned by user search. */
data class BilibiliUserItem(
    val mid: Long,
    val name: String,
    val avatarUrl: String? = null,
    val signature: String? = null,
    val fans: Long? = null,
    val videoCount: Long? = null,
    val verified: Boolean = false,
    val recentVideos: List<BilibiliSearchItem> = emptyList(),
)

/** One page of an uploader's submissions, with the total count and the parent-zone index. */
data class BilibiliUploaderPage(
    val items: List<BilibiliSearchItem>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val zones: List<BilibiliZone> = emptyList(),
)

/** A favourites / media list: its title (when the API reports one) and its videos, in list order. */
data class BilibiliMediaList(
    val title: String?,
    val items: List<BilibiliSearchItem>,
)

/** A parent zone from `tlist`; [count] is this uploader's submissions in that zone. */
data class BilibiliZone(val tid: Int, val name: String, val count: Int)

/** A Bilibili season (UP-defined collection). */
data class BilibiliSeason(
    val seasonId: Long,
    val name: String,
    val total: Int,
    val cover: String? = null,
)

/** Minimal VOD identity needed to fetch Bilibili danmaku segments: bvid / aid / cid / part. */
data class BiliDanmakuVideo(
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val part: Int,
)

/**
 * Resolves BIlibili VODs and live rooms through BIlibili's public web API — the same JSON the
 * website uses, gated behind WBI-signed `playurl` requests (no API key or auth required).
 */
object BilibiliApi {
    /** Bilibili's Dolby / Dolby Atmos audio renditions. */
    private val BILIBILI_DOLBY_AUDIO_IDS = setOf(30080, 30250, 30255)

    /** Standard AAC fallbacks used when Dolby is absent or its input dies. */
    private val BILIBILI_AAC_AUDIO_IDS = setOf(30216, 30232, 30280)

    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/BilibiliApi")

    /** Lowercase hex digits, indexed by nibble — avoids `"%02x".format` per byte in [md5]. */
    private val HEX_CHARS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )

    /**
     * Bilibili login cookie string set by the client after a platform login (see `BilibiliAuth`),
     * e.g. `SESSDATA=...; bili_jct=...`. Enables VIP / higher-quality playback; empty when not logged in.
     */
    @Volatile
    var cookie: String = ""

    private const val SPI_URL = "https://api.bilibili.com/x/frontend/finger/spi"
    private const val BUVID_TTL_MS = 12L * 60L * 60L * 1000L
    @Volatile
    private var buvidPair: String? = null
    @Volatile
    private var buvidAt: Long = 0L

    /** BIlibili's API rejects requests without a browser-shaped `Referer` / `User-Agent`; the login cookie is added when present. */
    private fun headers(
        referer: String = "https://www.bilibili.com",
        cookieValue: String = spaceCookie(),
    ): Map<String, List<String>> =
        DreamHttpClient.headersOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Accept" to "application/json",
            "Referer" to referer,
            "Cookie" to cookieValue,
        )

    /** How long the WAF-prone endpoints are parked after a 412, so a throttle is not extended. */
    private val WAF_COOLDOWN = 60.seconds

    /** Bilibili's WAF rejection code, delivered as a 412 status or inside an HTTP-200 body. */
    private const val WAF_BAN_CODE = -412

    /** When the last WAF block was seen, so a burst of resolves cannot keep it alive. */
    @Volatile
    private var wafBlockedAt: Instant? = null

    /** Endpoints the WAF actually throttles; WBI-signed search kept working through the same ban. */
    private fun isWafProneUrl(url: String): Boolean =
        url.contains("/x/web-interface/view") ||
            url.contains("/x/player/") ||
            url.contains("/pgc/") ||
            url.contains("/xlive/")

    /** Milliseconds left in the post-412 cooldown, or 0 when the request may proceed. */
    private fun wafCooldownRemainingMs(url: String): Long {
        if (!isWafProneUrl(url)) return 0
        val since = wafBlockedAt ?: return 0
        val left = WAF_COOLDOWN - (Clock.System.now() - since)
        return if (left.isPositive()) left.inWholeMilliseconds else 0
    }

    /** True when [error] is Bilibili's WAF rejection rather than a transport problem. */
    internal fun isBannedResponse(error: Throwable): Boolean {
        val text = generateSequence(error as Throwable?) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
        return text.contains("HTTP 412") || text.contains("code\":-412") || text.contains("request was banned")
    }

    /** Records a WAF block; re-recording an active one would push the cooldown forward forever. */
    private fun noteWafBlock(url: String) {
        if (wafCooldownRemainingMs(url) > 0) return
        wafBlockedAt = Clock.System.now()
        logger.warn(
            "Bilibili blocked this client (HTTP 412) on {}; pausing those requests for {}.",
            url.substringBefore('?'), WAF_COOLDOWN,
        )
    }

    private fun anonCookie(): String {
        val now = System.currentTimeMillis()
        val cached = buvidPair
        if (cached != null && now - buvidAt < BUVID_TTL_MS) {
            return "$cached; b_nut=${now / 1000L}"
        }
        return runCatching {
            val body = DreamHttpClient.readText(
                SPI_URL,
                DreamHttpClient.RequestOptions(
                    headers = headers(cookieValue = ""),
                    connectTimeoutMs = 8_000,
                    readTimeoutMs = 8_000,
                ),
            )
            val data = DreamJson.compact.parseToJsonElement(body).asJsonObjectOrNull()?.obj("data")
                ?: return@runCatching ""
            val b3 = data.optString("b_3") ?: return@runCatching ""
            val b4 = data.optString("b_4") ?: return@runCatching ""
            val pair = "buvid3=$b3; buvid4=$b4"
            buvidPair = pair
            buvidAt = now
            "$pair; b_nut=${now / 1000L}"
        }.getOrDefault("")
    }

    /** Anonymous trio plus every configured cookie entry it does not already carry. */
    private fun spaceCookie(): String {
        val anon = anonCookie()
        if (cookie.isBlank()) return anon
        val names = anon.split(';').mapNotNullTo(mutableSetOf()) { part ->
            part.substringBefore('=').trim().takeIf { it.isNotEmpty() }
        }
        val sb = StringBuilder(anon)
        for (part in cookie.split(';')) {
            val kv = part.trim()
            if (kv.isEmpty() || !kv.contains('=')) continue
            val name = kv.substringBefore('=').trim()
            if (!names.add(name)) continue
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append(kv)
        }
        return sb.toString()
    }

    /** `b23.tv` short links get followed for at most this many hops before giving up. */
    private const val MAX_REDIRECT_HOPS = 5

    /** Uppermost `qn` (8K): asking for it makes the server answer with every entitled rendition. */
    private const val QN_MAX = "127"

    /** `fnval` bitfield for the full DASH ladder (DASH+HDR+4K+Dolby+DolbyVision+8K+AV1). */
    private const val FNVAL_DASH_ALL = 4048

    /** PGC (bangumi/movie) `fnval`: the ladder above plus the PGC-only bit 8192. */
    private const val FNVAL_DASH_ALL_PGC = 12240

    /** Fixed permutation table BIlibili uses to derive the 32-char WBI mixin key. Publicly documented, stable! */
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12,
        38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62,
        11, 36, 20, 34, 44, 52,
    )

    /** The `img_key` / `sub_key` WBI signing keys, cached since they rotate roughly once a day. */
    private data class WbiKeys(val imgKey: String, val subKey: String, val fetchedAt: Instant)

    private var wbiCache: WbiKeys? = null
    private val WBI_TTL = 20.hours

    /** Resolves [source] (a VOD, live room, bangumi episode/season, or unresolved short link), or null when nothing is playable. */
    fun resolve(source: MediaSource.Bilibili): BilibiliPlayback? = when {
        source.bvid != null || source.avid != null -> resolveVod(source.bvid, source.avid, source.part ?: 1)
        source.epId != null || source.seasonId != null -> resolveBangumi(source.epId, source.seasonId)
        source.roomId != null -> resolveLive(source.roomId!!)
        else -> resolveShortlink(source.url)?.let { resolve(it) }
    }

    /** Metadata-only lookup for the cache. */
    fun metadata(source: MediaSource.Bilibili): PlatformVideoMetadata? = resolve(source)?.metadata

    /**
     * Fetches a favourites / media list (PiliPlus `medialist/detail/ml<id>`, classic `favlist?fid=<id>`).
     * Both id spaces are served by the same endpoint; the legacy `medialist/gateway/base/detail` is gone.
     */
    fun fetchMediaList(mediaListId: Long, maxPages: Int = 10): BilibiliMediaList? {
        val items = ArrayList<BilibiliSearchItem>()
        var title: String? = null
        var page = 1
        while (page <= maxPages) {
            val params = mapOf(
                "media_id" to mediaListId.toString(),
                "pn" to page.toString(),
                "ps" to "20",
                "platform" to "web",
            )
            val data = getJson("https://api.bilibili.com/x/v3/fav/resource/list?${plainQuery(params)}")?.obj("data")
                ?: break
            if (title == null) title = data.obj("info")?.optString("title")?.takeIf { it.isNotBlank() }
            val medias = data.array("medias") ?: break
            if (medias.isEmpty()) break
            for (media in medias) {
                val obj = media.asJsonObjectOrNull() ?: continue
                val bvid = obj.optString("bvid")?.takeIf { it.isNotBlank() } ?: continue
                items += BilibiliSearchItem(
                    bvid = bvid,
                    title = obj.optString("title")?.takeIf { it.isNotBlank() } ?: bvid,
                    uploader = obj.obj("upper")?.optString("name"),
                    thumbnailUrl = normalizeThumbnailUrl(obj.optString("cover")),
                    durationSec = obj.optLong("duration")?.takeIf { it > 0L },
                )
            }
            if (data.optBoolean("has_more") != true) break
            page++
        }
        return if (items.isEmpty()) null else BilibiliMediaList(title, items)
    }

    /**
     * Resolves the VOD identity (bvid / aid / cid / part) needed to fetch danmaku for [source]
     * via a lightweight `/x/web-interface/view` call, or null when [source] is not a resolvable VOD.
     */
    fun danmakuVideo(source: MediaSource.Bilibili): BiliDanmakuVideo? {
        if (source.roomId != null || source.epId != null || source.seasonId != null) return null
        val viewParams = buildMap {
            source.bvid?.let { put("bvid", it) }
            source.avid?.let { put("aid", it.toString()) }
        }
        if (viewParams.isEmpty()) return null
        val data = fetchView("https://api.bilibili.com/x/web-interface/view?${plainQuery(viewParams)}")?.obj("data")
            ?: return null
        val pages = data.array("pages")?.mapNotNull { it.asJsonObjectOrNull() }
        val part = source.part ?: 1
        val page = pages?.firstOrNull { it.optInt("page") == part } ?: pages?.firstOrNull()
        val cid = page?.optLong("cid") ?: data.optLong("cid") ?: return null
        val aid = data.optLong("aid") ?: source.avid ?: return null
        return BiliDanmakuVideo(
            bvid = data.optString("bvid") ?: source.bvid ?: return null,
            aid = aid,
            cid = cid,
            part = page?.optInt("page") ?: part,
        )
    }

    /**
     * Fetches one 6-minute VOD danmaku segment (1-based) for [video]. Tries the WBI-signed protobuf
     * `seg.so` endpoint first, then the plain protobuf endpoint, then the legacy XML `list.so`.
     * Returns an empty list when nothing could be fetched or parsed.
     */
    fun fetchDanmakuSegment(video: BiliDanmakuVideo, segmentIndex: Int): List<DanmakuEntry> {
        val segment = maxOf(1, segmentIndex)
        val referer = "https://www.bilibili.com/video/${video.bvid}"
        val params = mapOf(
            "type" to "1",
            "oid" to video.cid.toString(),
            "pid" to video.aid.toString(),
            "segment_index" to segment.toString(),
        )

        val wbi = getBytes("https://api.bilibili.com/x/v2/dm/wbi/web/seg.so?${signedQuery(params)}", referer)
        if (wbi != null) {
            val entries = BiliDmSegParser.parseProtobuf(wbi)
            if (entries.isNotEmpty()) return entries
        }

        val plain = getBytes("https://api.bilibili.com/x/v2/dm/web/seg.so?${plainQuery(params)}", referer)
        if (plain != null) {
            val entries = BiliDmSegParser.parseProtobuf(plain)
            if (entries.isNotEmpty()) return entries
        }

        val xml = getBytes("https://api.bilibili.com/x/v1/dm/list.so?oid=${video.cid}", referer)
        if (xml != null) {
            val entries = BiliDmSegParser.parseXml(xml)
            if (entries.isNotEmpty()) return entries
        }
        return emptyList()
    }

    /** Keyword video search, WBI-signed like `playurl` — used to mix BIlibili results into the free-text suggestions list. */
    fun searchVideos(keyword: String, page: Int = 1): List<BilibiliSearchItem> {
        val params = mapOf("search_type" to "video", "keyword" to keyword, "page" to page.toString())
        val root = getJson("https://api.bilibili.com/x/web-interface/wbi/search/type?${signedQuery(params)}")
        val results = root?.obj("data")?.array("result")?.mapNotNull { it.asJsonObjectOrNull() } ?: return emptyList()
        return results.mapNotNull { item ->
            val bvid = item.optString("bvid") ?: return@mapNotNull null
            BilibiliSearchItem(
                bvid = bvid,
                title = stripHighlightTags(item.optString("title").orEmpty()),
                uploader = item.optString("author"), // real uploader name from search result
                uploaderId = item.optLong("mid"),
                isUnionVideo = unionFlag(item),
                thumbnailUrl = normalizeThumbnailUrl(item.optString("pic")),
                durationSec = parseSearchDuration(item.optString("duration")),
                viewCount = item.optLong("play"),
                publishedAtEpochSec = item.optLong("pubdate"),
                mediaType = "video",
            )
        }
    }

    /**
     * Keyword bangumi (anime / series) search; results are keyed by `ep_id` / `season_id` instead of
     * `bvid`, so they render as cards whose tap target is a `bangumi/play/...` URL.
     */
    /** Uploader (UP) search via Bilibili's `bili_user` search type. */
    fun searchUsers(keyword: String, page: Int = 1): List<BilibiliUserItem> {
        val params = mutableMapOf(
            "search_type" to "bili_user",
            "keyword" to keyword,
            "page" to page.toString(),
            "page_size" to "20",
            "platform" to "pc",
            "web_location" to "1430654",
        )
        val root = getJson("https://api.bilibili.com/x/web-interface/wbi/search/type?${signedQuery(params)}")
        val results = root?.obj("data")?.array("result")?.mapNotNull { it.asJsonObjectOrNull() } ?: return emptyList()
        return results.mapNotNull { item ->
            val mid = item.optLong("mid") ?: return@mapNotNull null
            val name = item.optString("uname") ?: item.optString("name") ?: return@mapNotNull null
            val recent = item.array("res")?.mapNotNull { it.asJsonObjectOrNull() }?.mapNotNull { video ->
                val bvid = video.optString("bvid") ?: return@mapNotNull null
                BilibiliSearchItem(
                    bvid = bvid,
                    title = video.optString("title").orEmpty(),
                    uploader = name,
                    uploaderId = mid,
                    isUnionVideo = unionFlag(video),
                    thumbnailUrl = normalizeThumbnailUrl(video.optString("pic")),
                    durationSec = parseSearchDuration(video.optString("duration")),
                    viewCount = video.optLong("play"),
                    publishedAtEpochSec = video.optLong("pubdate"),
                    mediaType = "video",
                )
            } ?: emptyList()
            BilibiliUserItem(
                mid = mid,
                name = name,
                avatarUrl = normalizeThumbnailUrl(item.optString("upic")),
                signature = item.optString("usign"),
                fans = item.optLong("fans"),
                videoCount = item.optLong("videos"),
                verified = (item.obj("official_verify")?.optInt("type") ?: -1) >= 0,
                recentVideos = recent,
            )
        }
    }

    /** Lists one uploader's videos, optionally filtered by a title keyword. */
    fun searchVideosByUploader(
        mid: Long,
        keyword: String = "",
        page: Int = 1,
        uploaderName: String? = null,
    ): List<BilibiliSearchItem> {
        val pageResult = fetchUploaderVideoPage(mid, keyword, page, 30, null)
        if (pageResult != null) return pageResult.items
        val byName = uploaderName?.takeIf { it.isNotBlank() }
            ?.let { name -> searchVideos(name, page).filter { it.uploaderId == mid } }
            .orEmpty()
        if (byName.isNotEmpty()) {
            return if (keyword.isBlank()) byName else byName.filter { matchesTitleKeyword(it.title, keyword) }
        }
        if (keyword.isBlank()) return emptyList()
        return searchVideos(keyword, page).filter { it.uploaderId == mid }
    }

    /**
     * One page of an uploader's submissions. [tid] filters one parent zone; null means all zones.
     * The returned [BilibiliUploaderPage.zones] is the `tlist` index from the same response.
     */
    fun fetchUploaderVideoPage(
        mid: Long,
        keyword: String = "",
        page: Int = 1,
        pageSize: Int = 30,
        tid: Int? = null,
    ): BilibiliUploaderPage? {
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 30)
        val params = mutableMapOf(
            "mid" to mid.toString(),
            "ps" to safeSize.toString(),
            "pn" to safePage.toString(),
            "order" to "pubdate",
            "platform" to "web",
            "web_location" to "1550101",
        )
        if (keyword.isNotBlank()) params["keyword"] = keyword
        if (tid != null) params["tid"] = tid.toString()
        val referer = "https://space.bilibili.com/$mid"
        val data = getJson(
            "https://api.bilibili.com/x/space/wbi/arc/search?${signedQuery(params)}", referer,
        )?.obj("data") ?: return null
        val total = data.obj("page")?.optInt("count") ?: 0
        val list = data.obj("list")
        val items = list?.array("vlist")
            ?.mapNotNull { it.asJsonObjectOrNull() }
            ?.mapNotNull { spaceVideoItem(it, mid, null, archive = false) }
            .orEmpty()
        val zones = list?.obj("tlist")?.entries?.mapNotNull { (_, value) ->
            val zone = value.asJsonObjectOrNull() ?: return@mapNotNull null
            val zoneId = zone.optInt("tid") ?: return@mapNotNull null
            val name = zone.optString("name") ?: return@mapNotNull null
            BilibiliZone(zoneId, name, zone.optInt("count") ?: 0)
        }?.sortedByDescending { it.count }.orEmpty()
        return BilibiliUploaderPage(items, total, safePage, safeSize, zones)
    }

    /** All UP-defined seasons (collections); the endpoint pages 20 at a time. */
    fun fetchUploaderSeasons(mid: Long): List<BilibiliSeason> {
        val out = ArrayList<BilibiliSeason>()
        var page = 1
        var total = Int.MAX_VALUE
        while (out.size < total && page <= 20) {
            val params = mapOf(
                "mid" to mid.toString(),
                "page_num" to page.toString(),
                "page_size" to "20",
            )
            val data = getJson(
                "https://api.bilibili.com/x/polymer/web-space/seasons_series_list?${plainQuery(params)}",
                "https://space.bilibili.com/$mid",
            )?.obj("data") ?: break
            val lists = data.obj("items_lists") ?: break
            total = lists.obj("page")?.optInt("total") ?: 0
            val seasons = lists.array("seasons_list") ?: break
            if (seasons.isEmpty()) break
            for (entry in seasons) {
                val meta = entry.asJsonObjectOrNull()?.obj("meta") ?: continue
                val seasonId = meta.optLong("season_id") ?: continue
                out.add(
                    BilibiliSeason(
                        seasonId = seasonId,
                        name = meta.optString("name").orEmpty(),
                        total = meta.optInt("total") ?: 0,
                        cover = normalizeThumbnailUrl(meta.optString("cover")),
                    ),
                )
            }
            page++
        }
        return out.distinctBy { it.seasonId }
    }

    /** One page of one season's videos, newest first. */
    fun fetchUploaderSeasonPage(
        mid: Long,
        seasonId: Long,
        page: Int = 1,
        pageSize: Int = 30,
    ): BilibiliUploaderPage? {
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 30)
        val params = mapOf(
            "mid" to mid.toString(),
            "season_id" to seasonId.toString(),
            "sort_reverse" to "true",
            "page_num" to safePage.toString(),
            "page_size" to safeSize.toString(),
        )
        val data = getJson(
            "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list?${plainQuery(params)}",
            "https://space.bilibili.com/$mid",
        )?.obj("data") ?: return null
        val total = data.obj("page")?.optInt("total") ?: 0
        val items = data.array("archives")
            ?.mapNotNull { it.asJsonObjectOrNull() }
            ?.mapNotNull { spaceVideoItem(it, mid, null, archive = true) }
            .orEmpty()
        return BilibiliUploaderPage(items, total, safePage, safeSize)
    }

    /** Maps either an `arc/search` row or a season-member archive row into the shared card model. */
    private fun spaceVideoItem(
        item: JsonObject,
        mid: Long,
        uploaderName: String?,
        archive: Boolean,
    ): BilibiliSearchItem? {
        val bvid = item.optString("bvid") ?: return null
        val stat = item.obj("stat")
        val duration = if (archive) {
            item.optLong("duration")?.takeIf { it > 0L } ?: parseSearchDuration(item.optString("length"))
        } else {
            parseSearchDuration(item.optString("length"))
        }
        return BilibiliSearchItem(
            bvid = bvid,
            title = item.optString("title").orEmpty(),
            uploader = item.optString("author") ?: uploaderName,
            uploaderId = mid,
            isUnionVideo = unionFlag(item),
            thumbnailUrl = normalizeThumbnailUrl(item.optString("pic")),
            durationSec = duration,
            viewCount = stat?.optLong("view") ?: item.optLong("play"),
            publishedAtEpochSec = item.optLong("ctime") ?: item.optLong("created"),
            mediaType = "video",
        )
    }

    /** True when every whitespace-separated keyword token occurs in [title]. */
    private fun matchesTitleKeyword(title: String, keyword: String): Boolean {
        val tokens = keyword.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.isEmpty() || tokens.all { title.contains(it, ignoreCase = true) }
    }

    /** Loads one uploader's public profile from Bilibili's card, relation, space and live endpoints. */
    fun fetchUpProfile(mid: Long): BilibiliUpProfile? {
        if (mid <= 0L) return null
        val referer = "https://space.bilibili.com/$mid"
        val card = tryJsonData(
            "https://api.bilibili.com/x/web-interface/card", referer, mapOf("mid" to mid.toString()),
        )?.obj("card") ?: return null
        val relation = tryJsonData(
            "https://api.bilibili.com/x/relation/stat", referer, mapOf("vmid" to mid.toString()),
        )
        val nav = tryJsonData(
            "https://api.bilibili.com/x/space/navnum", referer, mapOf("mid" to mid.toString()),
        )
        val live = tryJsonData(
            "https://api.live.bilibili.com/live_user/v1/Master/info", referer, mapOf("uid" to mid.toString()),
        )
        val roomId = live?.optLong("room_id") ?: 0L
        val roomInfo = if (roomId > 0L) {
            tryJsonData("https://api.live.bilibili.com/room/v1/Room/get_info", referer, mapOf("room_id" to roomId.toString()))
        } else {
            null
        }
        val guard = if (roomId > 0L) {
            tryJsonData(
                "https://api.live.bilibili.com/xlive/app-room/v2/guardTab/topListNew", referer,
                mapOf("roomid" to roomId.toString(), "ruid" to mid.toString(), "page" to "1", "page_size" to "1"),
            )
        } else {
            null
        }
        val elec = tryJsonData(
            "https://api.bilibili.com/x/ugcpay-rank/elec/month/up", referer,
            mapOf("up_mid" to mid.toString()),
        )
        val upstat = tryJsonData(
            "https://api.bilibili.com/x/space/upstat", referer, mapOf("mid" to mid.toString()),
        )
        val acc = trySignedJsonData(
            "https://api.bilibili.com/x/space/wbi/acc/info", referer, mapOf("mid" to mid.toString()),
        )

        val levelInfo = card.obj("level_info")
        var official = nestedString(card, "Official", "title")
        if (official.isBlank()) official = nestedString(card, "official_verify", "desc")
        if (official.isBlank()) official = nestedString(live?.obj("info"), "official_verify", "desc")
        var nameplate = oneLine(card.obj("nameplate")?.optString("name"))
        if (nameplate.isBlank()) nameplate = oneLine(live?.optString("medal_name"))
        val fans = card.optLong("fans")?.takeIf { it > 0L } ?: relation?.optLong("follower") ?: 0L
        val following = card.optLong("attention")?.takeIf { it > 0L } ?: relation?.optLong("following") ?: 0L

        return BilibiliUpProfile(
            mid = mid,
            name = oneLine(card.optString("name")),
            face = httpsUrl(card.optString("face")),
            sign = oneLine(card.optString("sign")),
            sex = oneLine(card.optString("sex")),
            level = levelInfo?.optInt("current_level") ?: 0,
            fans = fans,
            following = following,
            official = oneLine(official),
            vip = oneLine(nestedString(card, "vip", "label", "text")),
            nameplate = nameplate,
            roomId = roomId,
            roomNews = oneLine(live?.obj("room_news")?.optString("content")),
            liveStatus = roomInfo?.optInt("live_status") ?: 0,
            videos = nav?.optLong("video") ?: 0L,
            articles = nav?.optLong("article") ?: 0L,
            albums = nav?.optLong("album") ?: 0L,
            opus = nav?.optLong("opus") ?: 0L,
            elec = elec?.optLong("count") ?: 0L,
            guards = guard?.let { nestedLong(it, "info", "num") } ?: 0L,
            plays = upstat?.let { nestedLong(it, "archive", "view") } ?: 0L,
            likes = upstat?.optLong("likes") ?: 0L,
            birthday = oneLine(acc?.optString("birthday")),
            school = oneLine(nestedString(acc, "school", "name")),
            ipLocation = oneLine(acc?.optString("ip_location")),
        )
    }

    /** Returns the `data` object of a successful Bilibili JSON response. */
    private fun jsonData(root: JsonObject?): JsonObject? {
        if (root == null) return null
        val code = root.optInt("code")
        if (code != null && code != 0) return null
        return root.obj("data")
    }

    /** GETs an unsigned Bilibili endpoint and returns its `data` object, or null. */
    private fun tryJsonData(endpoint: String, referer: String, params: Map<String, String>): JsonObject? =
        runCatching { jsonData(getJson("$endpoint?${plainQuery(params)}", referer)) }.getOrNull()

    /** GETs a WBI-signed Bilibili endpoint and returns its `data` object, or null. */
    private fun trySignedJsonData(endpoint: String, referer: String, params: Map<String, String>): JsonObject? =
        runCatching { jsonData(getJson("$endpoint?${signedQuery(params)}", referer)) }.getOrNull()

    /** Reads a nested string path; missing links read as empty. */
    private fun nestedString(root: JsonObject?, vararg path: String): String {
        if (root == null || path.isEmpty()) return ""
        var node: JsonObject = root
        for (i in 0 until path.size - 1) node = node.obj(path[i]) ?: return ""
        return node.optString(path.last()).orEmpty()
    }

    /** Reads a nested numeric path; missing links read as zero. */
    private fun nestedLong(root: JsonObject?, vararg path: String): Long {
        if (root == null || path.isEmpty()) return 0L
        var node: JsonObject = root
        for (i in 0 until path.size - 1) node = node.obj(path[i]) ?: return 0L
        return node.optLong(path.last()) ?: 0L
    }

    /** Collapses whitespace so a multi-line signature stays on one UI row. */
    private fun oneLine(value: String?): String = value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    /** Prefixes protocol-relative Bilibili image URLs with https. */
    private fun httpsUrl(value: String?): String? {
        val url = value?.trim().orEmpty()
        return when {
            url.isBlank() -> null
            url.startsWith("//") -> "https:$url"
            else -> url
        }
    }

    fun searchBangumi(keyword: String, page: Int = 1): List<BilibiliSearchItem> {
        val params = mutableMapOf(
            "search_type" to "media_bangumi",
            "keyword" to keyword,
            "page" to page.toString(),
            "page_size" to "20",
            "platform" to "pc",
            "web_location" to "1430654",
        )
        val root = getJson("https://api.bilibili.com/x/web-interface/wbi/search/type?${signedQuery(params)}")
        val results = root?.obj("data")?.array("result")?.mapNotNull { it.asJsonObjectOrNull() } ?: return emptyList()
        return results.mapNotNull { item ->
            val seasonId = item.optLong("season_id") ?: return@mapNotNull null
            val mode = item.optInt("media_mode")
            val label = accessLabelFor(item)
            // Skip unreleased / trailer items that cannot be played
            if (label == "unreleased") return@mapNotNull null
            BilibiliSearchItem(
                title = seriesTitle(item),
                uploader = null,
                thumbnailUrl = normalizeThumbnailUrl(item.optString("cover")),
                durationSec = null,
                viewCount = item.optLong("play"),
                epId = item.optLong("ep_id"),
                seasonId = seasonId,
                mediaType = "media_bangumi",
                mediaMode = mode,
                accessLabel = label,
            )
        }
    }

    /** Keyword media (movie / TV / documentary) search via `media_ft`. */
    fun searchMedia(keyword: String, page: Int = 1): List<BilibiliSearchItem> {
        val params = mutableMapOf(
            "search_type" to "media_ft",
            "keyword" to keyword,
            "page" to page.toString(),
            "page_size" to "20",
            "platform" to "pc",
            "web_location" to "1430654",
        )
        val root = getJson("https://api.bilibili.com/x/web-interface/wbi/search/type?${signedQuery(params)}")
        val results = root?.obj("data")?.array("result")?.mapNotNull { it.asJsonObjectOrNull() } ?: return emptyList()
        return results.mapNotNull { item ->
            val seasonId = item.optLong("season_id") ?: return@mapNotNull null
            val mode = item.optInt("media_mode")
            val label = accessLabelFor(item)
            // Skip unreleased / trailer items that cannot be played
            if (label == "unreleased") return@mapNotNull null
            BilibiliSearchItem(
                title = seriesTitle(item),
                uploader = null,
                thumbnailUrl = normalizeThumbnailUrl(item.optString("cover")),
                durationSec = null,
                viewCount = item.optLong("play"),
                epId = item.optLong("ep_id"),
                seasonId = seasonId,
                mediaType = "pgc",
                mediaMode = mode,
                accessLabel = label,
            )
        }
    }

    /** Strips the `<em class="keyword">...</em>` highlight markup BIlibili's search API wraps matches in. */
    private val EM_TAG_RE = Regex("</?em[^>]*>")

    private fun stripHighlightTags(title: String): String = title.replace(EM_TAG_RE, "")

    /** Any Han (Chinese-script) character, used to pick the localised title below. */
    private val HAN_CHAR_RE = Regex("[一-鿿]")

    /**
     * Chooses the localised series/movie title for a bangumi or movie search result. Bilibili returns
     * two fields that may carry different languages — `title` (the search-highlighted name) and
     * `org_title` (the original/alternate name). The field holding the query match is often the
     * English name (e.g. searching "WALL-E" puts "WALL·E" in `title` while `org_title` is
     * "机器人总动员"), so prefer whichever candidate contains Chinese characters to keep titles in the
     * user's language.
     */
    private fun seriesTitle(item: JsonObject): String {
        val candidates = listOf(
            item.optString("title"),
            item.optString("org_title"),
            item.optString("media_name"),
        ).mapNotNull { it?.let(::stripHighlightTags)?.takeIf { s -> s.isNotBlank() } }
        return candidates.firstOrNull { HAN_CHAR_RE.containsMatchIn(it) }
            ?: candidates.firstOrNull()
            ?: ""
    }

    /**
     * Derives a user-facing access marker for a bangumi / movie search result from the API's `badges`
     * / `display_info` arrays (each entry carries a `text` like "大会员" / "会员特价"). Only returns
     * a marker for genuinely restricted content:
     *  - "vip"  for pink "大会员" / "VIP" badges (watchable with a Big Membership),
     *  - "paid" for yellow pay-per-view badges ("付费" / "会员特价" / "单片购买"),
     *  - "unreleased" for items that are not yet available ("预告" / "未上映" / "敬请期待"),
     *  - null   otherwise (free, or other badge kinds like "独家" which aren't paywalled here).
     * `media_mode` is deliberately not used — Bilibili sets it to 2 for both VIP and pay-per-view.
     */
    private fun accessLabelFor(item: JsonObject): String? {
        val badgeTexts = buildList {
            item.array("badges")?.forEach { e -> (e as? JsonObject)?.optString("text")?.let { add(it) } }
            item.array("display_info")?.forEach { e -> (e as? JsonObject)?.optString("text")?.let { add(it) } }
        }
        val joined = badgeTexts.joinToString(" ")
        return when {
            // Unreleased / trailer items cannot be played at all — filter out via accessLabel
            joined.contains("预告") || joined.contains("未上映") || joined.contains("敬请期待")
                || joined.contains("即将上映") || joined.contains("尚未上映") -> "unreleased"
            joined.contains("大会员") || joined.contains("VIP") -> "vip"
            joined.contains("付费") || joined.contains("特价") || joined.contains("购买") || joined.contains("单片") -> "paid"
            else -> null
        }
    }

    /** Bilibili reports co-authorship as `is_union_video` or `is_cooperation`, as a number or boolean. */
    internal fun unionFlag(item: JsonObject): Boolean =
        flag(item, "is_union_video") || flag(item, "is_cooperation") ||
            (item.obj("rights")?.let { flag(it, "is_cooperation") } == true)

    private fun flag(item: JsonObject, key: String): Boolean {
        val value = (item.get(key) as? JsonPrimitive)?.content ?: return false
        return value == "1" || value.equals("true", ignoreCase = true)
    }

    /** Prefer Dolby, keep AAC as the fallback, and use the original rows only when both are absent. */
    internal fun selectAudioRows(rows: List<JsonObject>): List<JsonObject> {
        val dolby = rows.filter(::isDolbyAudio).sortedByDescending { it.optInt("bandwidth") }
        val aac = rows.filter(::isAacAudio).sortedByDescending { it.optInt("bandwidth") }
        return when {
            dolby.isNotEmpty() -> (dolby + aac).distinctBy(::audioRowKey)
            aac.isNotEmpty() -> aac.distinctBy(::audioRowKey)
            else -> rows
        }
    }

    /** Reads the standard `audio` array plus the separate `dolby.audio` / `flac.audio` blocks. */
    internal fun dashAudioRows(dash: JsonObject): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        fun add(container: JsonObject?) {
            container?.array("audio")?.forEach { e -> e.asJsonObjectOrNull()?.let { rows += it } }
            container?.obj("audio")?.let { rows += it }
        }
        add(dash)
        add(dash.obj("dolby"))
        add(dash.obj("flac"))
        return rows.distinctBy(::audioRowKey)
    }

    private fun isDolbyAudio(row: JsonObject): Boolean {
        val codecs = row.optString("codecs").orEmpty().lowercase()
        return row.optInt("id") in BILIBILI_DOLBY_AUDIO_IDS ||
            "ec-3" in codecs || "eac3" in codecs || "ac-3" in codecs
    }

    private fun isAacAudio(row: JsonObject): Boolean {
        val codecs = row.optString("codecs").orEmpty().lowercase()
        return row.optInt("id") in BILIBILI_AAC_AUDIO_IDS || "mp4a" in codecs || "aac" in codecs
    }

    /** Stable identity for deduplicating the same rendition returned in multiple DASH blocks. */
    private fun audioRowKey(row: JsonObject): String =
        "${row.optInt("id")}|${row.optString("codecs")}|${row.optString("baseUrl")}|${row.optString("base_url")}"

    /** Short track label for the audio selector, including the Dolby preference. */
    private fun audioTrackName(row: JsonObject): String? = when {
        isDolbyAudio(row) && row.optInt("id") in setOf(30250, 30255) -> "Dolby Atmos"
        isDolbyAudio(row) -> "Dolby"
        row.optInt("id") == 30216 -> "AAC 64K"
        row.optInt("id") == 30232 -> "AAC 132K"
        row.optInt("id") == 30280 -> "AAC 192K"
        else -> row.optString("codecs")?.takeIf { it.isNotBlank() }
    }

    /** BIlibili's search API returns protocol-relative thumbnail URLs (`//i0.hdslb.com/...`). */
    private fun normalizeThumbnailUrl(pic: String?): String? = when {
        pic.isNullOrEmpty() -> null
        pic.startsWith("//") -> "https:$pic"
        else -> pic
    }

    /** Parses a search result's `"mm:ss"` / `"hh:mm:ss"` duration string into seconds. */
    private fun parseSearchDuration(text: String?): Long? {
        val parts = text?.split(":")?.map { it.toLongOrNull() ?: return null } ?: return null
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    /** Follows a `b23.tv` short link's redirect chain and reparses the final URL into a BIlibili source. */
    private fun resolveShortlink(url: String): MediaSource.Bilibili? {
        var current = url
        repeat(MAX_REDIRECT_HOPS) {
            val location = runCatching {
                DreamHttpClient.peekRedirectLocation(
                    current,
                    DreamHttpClient.RequestOptions(headers = headers(), connectTimeoutMs = 8_000, readTimeoutMs = 8_000),
                )
            }.getOrNull() ?: return BilibiliUrls.parse(current)?.takeIf { it.isResolved }

            val resolved = BilibiliUrls.parse(location)
            if (resolved != null && resolved.isResolved) return resolved
            current = location
        }
        return BilibiliUrls.parse(current)?.takeIf { it.isResolved }
    }

    /** True once a BIlibili source carries an actual id, i.e. is no longer a bare `b23.tv` short link. */
    private val MediaSource.Bilibili.isResolved: Boolean
        get() = bvid != null || avid != null || roomId != null || epId != null || seasonId != null

    /** Lists the parts of a multi-part VOD, or null when the video has no part list / the call fails. */
    fun fetchPageList(bvid: String?, avid: Long?): List<BilibiliPart>? {
        val query = when {
            !bvid.isNullOrBlank() -> "bvid=$bvid"
            avid != null && avid > 0L -> "aid=$avid"
            else -> return null
        }
        val root = getJson("https://api.bilibili.com/x/player/pagelist?$query") ?: return null
        val rows = root.array("data")?.mapNotNull { it.asJsonObjectOrNull() } ?: return null
        return rows.mapNotNull { row ->
            val page = row.optInt("page") ?: return@mapNotNull null
            BilibiliPart(
                page = page,
                title = oneLine(row.optString("part")),
                durationSec = row.optInt("duration") ?: 0,
            )
        }.takeIf { it.isNotEmpty() }
    }

    /** Resolves a VOD identified by [bvid] or legacy [avid], at part [part] (1-based). */
    private fun resolveVod(bvid: String?, avid: Long?, part: Int): BilibiliPlayback? {
        val viewParams = buildMap {
            bvid?.let { put("bvid", it) }
            avid?.let { put("aid", it.toString()) }
        }
        if (viewParams.isEmpty()) return null

        val viewRoot = fetchView("https://api.bilibili.com/x/web-interface/view?${plainQuery(viewParams)}")
        val data = viewRoot?.obj("data") ?: return null

        val pages = data.array("pages")?.mapNotNull { it.asJsonObjectOrNull() }
        val page = pages?.firstOrNull { it.optInt("page") == part } ?: pages?.firstOrNull()
        val cid = page?.optLong("cid") ?: data.optLong("cid") ?: return null
        val resolvedBvid = data.optString("bvid") ?: bvid

        val playurlParams = buildMap {
            resolvedBvid?.let { put("bvid", it) }
            put("cid", cid.toString())
            put("qn", QN_MAX)
            put("fnval", FNVAL_DASH_ALL.toString())
            put("fnver", "0")
            put("fourk", "1")
            put("try_look", "1")
        }
        val playurlRoot = getJson("https://api.bilibili.com/x/player/playurl?${signedQuery(playurlParams)}")
        val playurlData = playurlRoot?.obj("data")
        logger.debug(
            "Bilibili playurl: cookieSent={} acceptQuality={} dashVideos={}",
            cookie.isNotBlank(),
            playurlData?.array("accept_quality")?.mapNotNull { (it as? JsonPrimitive)?.content },
            playurlData?.obj("dash")?.array("video")?.size,
        )
        val streams = buildStreams(playurlData)
        if (streams.isEmpty()) return null

        // The view API keeps the original dimension; the stream heights above were canonicalised,
        // which would erase the 2:1 equirectangular shape this check depends on.
        val dimension = data.obj("dimension")
        val panoramic = resolvedBvid != null &&
            isEquirectangularFrame(dimension?.optInt("width"), dimension?.optInt("height")) &&
            hasPanoramicTag(fetchTagNames(resolvedBvid))

        val stat = data.obj("stat")
        val owner = data.obj("owner")
        val metadata = PlatformVideoMetadata(
            title = data.optString("title"),
            uploader = owner?.optString("name"),
            thumbnailUrl = data.optString("pic"),
            uploaderAvatarUrl = owner?.optString("face"),
            uploaderId = owner?.optLong("mid")?.takeIf { it > 0L },
            viewCount = stat?.optLong("view"),
            // `data.duration` covers every part, so a multi-part selection takes its own page's
            // length: the total made the player expect twice the part, so the real end read as an
            // early EOS (the part replayed) and the seek bar stretched past the last frame.
            durationSec = page?.optLong("duration") ?: data.optLong("duration"),
            isLive = false,
        )
        return BilibiliPlayback(streams = streams, metadata = metadata, isSeekable = true, panoramic = panoramic)
    }

    /** Resolves a bangumi/movie episode identified by [epId], or the first episode of season [seasonId]. */
    private fun resolveBangumi(epId: Long?, seasonId: Long?): BilibiliPlayback? {
        val seasonParams = buildMap {
            epId?.let { put("ep_id", it.toString()) }
            seasonId?.let { put("season_id", it.toString()) }
        }
        if (seasonParams.isEmpty()) return null

        val seasonRoot = getJson("https://api.bilibili.com/pgc/view/web/season?${plainQuery(seasonParams)}")
        val result = seasonRoot?.obj("result") ?: return null

        val episodes = result.array("episodes")?.mapNotNull { it.asJsonObjectOrNull() } ?: return null
        val sections = sectionEpisodes(result)
        val episode = selectBangumiEpisode(episodes, sections, epId)
        if (episode == null) {
            logger.warn(
                "Bilibili bangumi ep {} is not in the season payload ({} main episodes, {} section episodes); refusing to fall back to the first episode.",
                epId, episodes.size, sections.size,
            )
            return null
        }
        val resolvedEpId = episode.optLong("ep_id") ?: return null

        val playurlRoot =
            getJson("https://api.bilibili.com/pgc/player/web/v2/playurl?fnval=$FNVAL_DASH_ALL_PGC&fnver=0&fourk=1&qn=$QN_MAX&ep_id=$resolvedEpId")
        val streams = buildStreams(playurlRoot?.obj("result")?.obj("video_info"))
        if (streams.isEmpty()) return null

        val seasonTitle = result.optString("title")
        val episodeTitle = episode.optString("long_title")?.takeIf { it.isNotBlank() }
            ?: episode.optString("show_title")
        val title = listOfNotNull(seasonTitle, episodeTitle).joinToString(" · ").ifBlank { null }

        val metadata = PlatformVideoMetadata(
            title = title,
            uploader = null,
            thumbnailUrl = episode.optString("cover") ?: result.optString("cover"),
            uploaderAvatarUrl = null,
            viewCount = null,
            durationSec = episode.optLong("duration")?.let { it / 1000 },
            isLive = false,
        )
        return BilibiliPlayback(streams = streams, metadata = metadata, isSeekable = true)
    }

    /**
     * Flattens the `section` lists (specials / previews / trailers) that Bilibili returns beside the
     * main-run `episodes` of a season payload.
     */
    private fun sectionEpisodes(result: JsonObject): List<JsonObject> =
        result.array("section")
            ?.mapNotNull { it.asJsonObjectOrNull() }
            ?.flatMap { section -> section.array("episodes")?.mapNotNull { it.asJsonObjectOrNull() }.orEmpty() }
            .orEmpty()

    /**
     * Picks the episode to play from a season payload.
     *
     * An explicit [epId] must match a main-run or `section` episode exactly: falling back to
     * `episodes.first()` played the season's FIRST episode for a pasted `ep<id>` link, which is the
     * reported "the link resolved to a different video". A [seasonId]-only request legitimately
     * starts at the first episode. Returns null when an explicit [epId] is present nowhere, so the
     * caller reports a failure instead of playing the wrong episode.
     */
    internal fun selectBangumiEpisode(episodes: List<JsonObject>, sections: List<JsonObject>, epId: Long?): JsonObject? =
        if (epId != null) {
            episodes.firstOrNull { it.optLong("ep_id") == epId }
                ?: sections.firstOrNull { it.optLong("ep_id") == epId }
        } else {
            episodes.firstOrNull()
        }

    /** Resolves a live room identified by [roomId]. */
    private fun resolveLive(roomId: Long): BilibiliPlayback {
        val infoRoot = getJson("https://api.live.bilibili.com/room/v1/Room/get_info?room_id=$roomId")
        val info = infoRoot?.obj("data")
        val isLive = info?.optInt("live_status") == 1

        var metadata = PlatformVideoMetadata(
            title = info?.optString("title"),
            uploader = null,
            thumbnailUrl = info?.optString("user_cover") ?: info?.optString("keyframe"),
            uploaderAvatarUrl = null,
            // `Room/get_info`'s `online` is the popularity score, and `watched_show.num` is the
            // all-time watcher total; neither is "currently watching". The live online rank carries
            // the real concurrent count, filled in below once the anchor uid is known.
            viewCount = null,
            durationSec = null,
            isLive = isLive,
        )

        if (!isLive) return BilibiliPlayback(streams = emptyList(), metadata = metadata, isSeekable = false)

        val playParams = mapOf(
            "room_id" to roomId.toString(),
            "protocol" to "0,1",
            "format" to "0,1,2",
            "codec" to "0,1",
            "qn" to "10000",
        )
        val playData = getJson(
            "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?${signedQuery(playParams)}",
        )?.obj("data")
        // The anchor lives in the H5 room card, not in the play-info payload: `getRoomPlayInfo` only
        // carries room_id / uid / playurl_info, so reading `anchor_info` there always came back null.
        val h5 = getJson("https://api.live.bilibili.com/xlive/web-room/v1/index/getH5InfoByRoom?room_id=$roomId")
            ?.obj("data")
        val base = h5?.obj("anchor_info")?.obj("base_info")
        val anchorName = oneLine(base?.optString("uname")).takeIf { it.isNotBlank() }
        val anchorFace = httpsUrl(base?.optString("face"))
        val anchorUid = h5?.obj("room_info")?.optLong("uid")?.takeIf { it > 0L }
            ?: playData?.optLong("uid")?.takeIf { it > 0L }
            ?: info?.optLong("uid")?.takeIf { it > 0L }
        metadata = metadata.copy(
            title = metadata.title ?: h5?.obj("room_info")?.optString("title"),
            uploader = anchorName,
            uploaderAvatarUrl = anchorFace,
            uploaderId = anchorUid,
            viewCount = anchorUid?.let { onlineViewers(it, roomId) },
        )
        val streams = buildLiveStreams(playData)
        return BilibiliPlayback(streams = streams, metadata = metadata, isSeekable = false)
    }

    /**
     * Concurrent viewers of a live room, read from the room's online rank (`onlineNum`). Returns null
     * when the room has no online rank yet, so the preview shows no number instead of a fake one.
     */
    private fun onlineViewers(anchorUid: Long, roomId: Long): Long? = runCatching {
        getJson(
            "https://api.live.bilibili.com/xlive/general-interface/v1/rank/getOnlineGoldRank" +
                "?ruid=$anchorUid&roomId=$roomId&page=1&pageSize=1",
        )?.obj("data")?.optLong("onlineNum")?.takeIf { it > 0L }
    }.getOrNull()

    /** Builds video-only / audio-only [MediaStream]s from a VOD `playurl` DASH response, or muxed ones from `durl`. */
    private fun buildStreams(playurlData: JsonObject?): List<MediaStream> {
        if (playurlData == null) return emptyList()

        val dash = playurlData.obj("dash")
        if (dash != null) {
            val streams = ArrayList<MediaStream>()
            dash.array("video")?.mapNotNull { it.asJsonObjectOrNull() }?.forEach { v ->
                val urls = dashUrls(v) ?: return@forEach
                streams += MediaStream(
                    url = urls.first(), backupUrls = urls.drop(1),
                    type = MediaStreamType.VIDEO,
                    // `codecs` is e.g. "avc1.64001f" (H.264) / "av01.0.05M.08" (AV1) /
                    // "hev1.1.6.L120" (HEVC). Populated so stream consumers can prefer H.264
                    // renditions (e.g. scrub extraction, which software-decodes and stalls on
                    // AV1/HEVC long-GOP streams).
                    codec = v.optString("codecs")?.takeIf { it.isNotEmpty() },
                    width = canonicalWidth(v),
                    // Bilibili movie / bangumi DASH streams report the actual encoded height
                    // (e.g. 808 instead of 1080), which makes the quality selector show "808p"
                    // instead of "1080p". Map the qn (id) to the canonical height when available.
                    height = canonicalHeight(v),
                    fps = v.optString("frameRate")?.toDoubleOrNull(),
                    bitrate = v.optInt("bandwidth"),
                    audioTrackName = null,
                    audioTrackLang = null,
                )
            }
            val audioRows = dashAudioRows(dash)
            selectAudioRows(audioRows).forEach { a ->
                val urls = dashUrls(a) ?: return@forEach
                streams += MediaStream(
                    url = urls.first(), backupUrls = urls.drop(1),
                    type = MediaStreamType.AUDIO,
                    codec = a.optString("codecs")?.takeIf { it.isNotBlank() },
                    width = null,
                    height = null,
                    fps = null,
                    bitrate = a.optInt("bandwidth"),
                    audioTrackName = audioTrackName(a),
                    audioTrackLang = null,
                    isDefault = isDolbyAudio(a),
                )
            }
            if (streams.isNotEmpty()) return streams
        }

        // Very low quality / old videos have no DASH manifest, only progressive URLs.
        // Regular VODs key this "durl"; bangumi's `video_info` keys the same shape "durls".
        // Each durl entry may carry its own `url` and `backup_url` array.
        val durls = (playurlData.array("durl") ?: playurlData.array("durls"))
            ?.mapNotNull { it.asJsonObjectOrNull() } ?: return emptyList()
        val allUrls = durls.flatMap { d ->
            buildList {
                d.optString("url")?.let { add(it) }
                d.array("backup_url")?.forEach { e -> (e as? JsonPrimitive)?.content?.let { add(it) } }
                d.array("backupUrl")?.forEach { e -> (e as? JsonPrimitive)?.content?.let { add(it) } }
            }
        }.distinct()
        val primary = allUrls.firstOrNull() ?: return emptyList()
        return listOf(
            MediaStream(
                url = primary, backupUrls = allUrls.drop(1),
                type = MediaStreamType.VIDEO_AUDIO,
                codec = null, width = null, height = null, fps = null,
                bitrate = durls.firstOrNull()?.optInt("bandwidth"),
                audioTrackName = null, audioTrackLang = null,
            ),
        )
    }

    /** Builds muxed live streams (FLV / HLS) from a `getRoomPlayInfo` response. */
    private fun buildLiveStreams(playInfoData: JsonObject?): List<MediaStream> {
        val streams = ArrayList<MediaStream>()
        val codecs = playInfoData?.obj("playurl_info")?.obj("playurl")?.array("stream")
            ?.mapNotNull { it.asJsonObjectOrNull() } ?: return streams
        for (stream in codecs) {
            val formats = stream.array("format")?.mapNotNull { it.asJsonObjectOrNull() } ?: continue
            for (format in formats) {
                val codecList = format.array("codec")?.mapNotNull { it.asJsonObjectOrNull() } ?: continue
                for (codec in codecList) {
                    val baseUrl = codec.optString("base_url") ?: continue
                    val urlInfo = codec.array("url_info")?.mapNotNull { it.asJsonObjectOrNull() }.orEmpty()
                    if (urlInfo.isEmpty()) continue
                    // Collect all CDN hosts from url_info, first is primary, rest are backups
                    val urls = urlInfo.mapNotNull { ui ->
                        val host = ui.optString("host") ?: return@mapNotNull null
                        "$host$baseUrl${ui.optString("extra").orEmpty()}"
                    }.distinct()
                    val first = urls.firstOrNull() ?: continue
                    streams += MediaStream(
                        url = first, backupUrls = urls.drop(1),
                        type = MediaStreamType.VIDEO_AUDIO,
                        codec = codec.optString("codec_name"), width = null, height = null, fps = null,
                        bitrate = codec.optInt("current_qn"), audioTrackName = null, audioTrackLang = null,
                    )
                }
            }
        }
        return streams
    }

    /** Reads a DASH representation's playable URLs: primary + all backup CDNs. */
    private fun dashUrls(rep: JsonObject): List<String>? = buildList {
        rep.optString("baseUrl")?.let { add(it) }
        rep.optString("base_url")?.let { if (it !in this) add(it) }
        rep.array("backupUrl")?.forEach { e -> (e as? JsonPrimitive)?.content?.let { if (it !in this) add(it) } }
        rep.array("backup_url")?.forEach { e -> (e as? JsonPrimitive)?.content?.let { if (it !in this) add(it) } }
    }.takeIf { it.isNotEmpty() }

    /** Fetches (and caches) the WBI `img_key` / `sub_key` pair used to sign gated endpoints. */
    private fun wbiKeys(): WbiKeys? {
        wbiCache?.let { if (Clock.System.now() - it.fetchedAt < WBI_TTL) return it }

        val wbiImg = getJson("https://api.bilibili.com/x/web-interface/nav")?.obj("data")?.obj("wbi_img")
        val imgUrl = wbiImg?.optString("img_url")
        val subUrl = wbiImg?.optString("sub_url") ?: wbiImg?.optString("sub_key_url")
        if (imgUrl == null || subUrl == null) return wbiCache

        return WbiKeys(keySegment(imgUrl), keySegment(subUrl), Clock.System.now()).also { wbiCache = it }
    }

    /** Extracts the filename segment (stripped of extension) that WBI uses as a key from a signed asset URL. */
    private fun keySegment(url: String): String = url.substringAfterLast('/').substringBeforeLast('.')

    /** Builds the 32-char WBI mixin key by permuting `imgKey + subKey` through [MIXIN_KEY_ENC_TAB]. */
    private fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        val sb = StringBuilder(32)
        for (i in 0 until 32) {
            val idx = MIXIN_KEY_ENC_TAB[i]
            if (idx < raw.length) sb.append(raw[idx])
        }
        return sb.toString()
    }

    /** Builds a plain (unsigned) `key=value&...` query string, URL-encoding each value. */
    private fun plainQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }

    /**
     * Builds a WBI-signed query string for [params]: adds `wts` (unix seconds), sorts keys, strips
     * `!'()*` from values, appends the mixin key, and MD5-hashes the result into `w_rid`. Falls back
     * to a plain (unsigned) query when the WBI keys are unavailable — some endpoints still answer,
     * just at reduced quality.
     */
    private fun signedQuery(params: Map<String, String>): String {
        val wts = Clock.System.now().epochSeconds.toString()
        val all = (params + ("wts" to wts)).toSortedMap()
        val keys = wbiKeys() ?: return plainQuery(all)

        val filtered = all.mapValues { (_, v) -> v.filterNot { c -> c in "!'()*" } }
        val base = filtered.entries.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        val wRid = md5(base + mixinKey(keys.imgKey, keys.subKey))
        return "$base&w_rid=$wRid"
    }

    /** Bilibili qn (quality number) → canonical video height. The DASH stream's actual
     *  `height` may be the encoded pixel height (e.g. 808 for a movie) rather than the
     *  standard value (1080), which makes the quality selector show "808p" instead of
     *  "1080p". Mapping via the qn produces canonical labels. */
    private val QN_TO_HEIGHT = mapOf(
        6 to 240, 16 to 360, 32 to 480, 48 to 540,
        64 to 720, 74 to 720,
        80 to 1080, 112 to 1080, 116 to 1080,
        120 to 2160, 125 to 2160, 126 to 2160,
        127 to 4320,
    )

    /** Maps [qn] to the canonical height, or null when [qn] is not a known Bilibili quality number. */
    private fun qnToStandardHeight(qn: Int?): Int? = if (qn != null) QN_TO_HEIGHT[qn] else null

    /** True when [width]x[height] has the 2:1 equirectangular shape of a 360/VR panorama. */
    internal fun isEquirectangularFrame(width: Int?, height: Int?): Boolean {
        if (width == null || height == null || width <= 0 || height <= 0) return false
        val longEdge = maxOf(width, height).toDouble()
        val shortEdge = minOf(width, height).toDouble()
        return abs(longEdge / shortEdge - 2.0) <= 0.04
    }

    /** True when any of [tagNames] marks the upload as a 360 / VR panorama. */
    internal fun hasPanoramicTag(tagNames: Collection<String>): Boolean = tagNames.any { raw ->
        val tag = raw.trim().lowercase()
        tag == "vr" || tag.startsWith("vr ") ||
            tag.contains("全景") || tag.contains("panorama") ||
            tag.contains("360°") || tag.contains("360度")
    }

    /** Tag names for [bvid]; only fetched once a video already looks equirectangular. */
    private fun fetchTagNames(bvid: String): List<String> {
        val root = runCatching { getJson("https://api.bilibili.com/x/tag/archive/tags?bvid=$bvid") }.getOrNull()
            ?: return emptyList()
        return root.array("data")?.mapNotNull { element ->
            element.asJsonObjectOrNull()?.optString("tag_name")?.takeIf { it.isNotBlank() }
        } ?: emptyList()
    }

    /** Canonical rung height for one DASH video entry: the qn height when known, else the encoded one. */
    private fun canonicalHeight(video: JsonObject): Int? {
        val encoded = video.optInt("height")?.takeIf { it > 0 }
        return qnToStandardHeight(video.optInt("id")) ?: encoded
    }

    /**
     * Width rescaled to [canonicalHeight]. The qn mapping rewrites only the height, so a portrait
     * 1080x1920 upload (qn 80) would otherwise become 1080x1080 and get a square texture.
     */
    private fun canonicalWidth(video: JsonObject): Int? {
        val encodedW = video.optInt("width")?.takeIf { it > 0 }
        val encodedH = video.optInt("height")?.takeIf { it > 0 }
        val canonical = canonicalHeight(video) ?: return encodedW
        if (encodedW == null || encodedH == null) return encodedW
        val scaled = (encodedW.toDouble() / encodedH.toDouble() * canonical.toDouble()).roundToInt()
        return scaled.coerceAtLeast(2)
    }

    /** URL-encodes [value] for a query string, using `%20` (not `+`) for spaces. */
    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Returns the lowercase hex MD5 digest of [s]. */
    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(HEX_CHARS[v ushr 4])
                append(HEX_CHARS[v and 0x0F])
            }
        }
    }

    /**
     * Reads `x/web-interface/view`, falling back to the WBI-signed variant: the plain endpoint is
     * what the WAF throttles first, while the signed one kept working through the same ban.
     */
    private fun fetchView(plainViewUrl: String): JsonObject? {
        val signed = "https://api.bilibili.com/x/web-interface/wbi/view?${signedQuery(queryParamsOf(plainViewUrl.substringAfter('?', "")))}"
        if (wafBlockedAt != null) getJson(signed)?.let { if (it.obj("data") != null) return it }
        val plain = getJson(plainViewUrl)
        if (plain?.obj("data") != null) return plain
        return getJson(signed) ?: plain
    }

    /** Splits an already-encoded `a=1&b=2` query back into a map for re-signing. */
    private fun queryParamsOf(query: String): Map<String, String> =
        query.split('&')
            .filter { it.isNotBlank() }
            .associate { pair -> pair.substringBefore('=') to pair.substringAfter('=', "") }

    private fun getJson(url: String, referer: String = "https://www.bilibili.com"): JsonObject? {
        if (wafCooldownRemainingMs(url) > 0) return null
        return runCatching {
            val body = DreamHttpClient.readText(
                url,
                DreamHttpClient.RequestOptions(headers = headers(referer), connectTimeoutMs = 8_000, readTimeoutMs = 8_000),
            )
            val json = DreamJson.compact.parseToJsonElement(body).asJsonObjectOrNull()
            // Bilibili also delivers the WAF rejection as HTTP 200 + {"code":-412}.
            if (json?.optInt("code") == WAF_BAN_CODE) noteWafBlock(url)
            json
        }.onFailure { error ->
            logger.debug("Bilibili API fetch failed for {}: {}.", url, error.message)
            if (isBannedResponse(error)) noteWafBlock(url)
        }.getOrNull()
    }

    /** GETs the raw binary body at [url] with a Bilibili-shaped [referer], or null on any failure. */
    private fun getBytes(url: String, referer: String): ByteArray? = runCatching {
        DreamHttpClient.readBytes(
            url,
            DreamHttpClient.RequestOptions(
                headers = headers() + ("Referer" to listOf(referer)),
                connectTimeoutMs = 8_000,
                readTimeoutMs = 8_000,
            ),
        )
    }.onFailure { logger.debug("Bilibili binary fetch failed for {}: {}.", url, it.message) }.getOrNull()
}
