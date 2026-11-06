package com.dreamdisplayx.media.source

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.search.model.MediaSearchResult
import com.dreamdisplayx.api.media.source.model.MediaPlatform
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.media.source.url.CustomMediaUrls
import com.dreamdisplayx.api.media.source.url.MediaUrlCleaner
import com.dreamdisplayx.api.media.source.url.YouTubeUrls
import com.dreamdisplayx.media.source.bilibili.BilibiliMetadataCache
import com.dreamdisplayx.media.source.platform.PlatformVideoMetadata
import com.dreamdisplayx.media.source.twitch.TwitchMetadata
import com.dreamdisplayx.media.source.twitch.TwitchMetadataCache

/**
 * Pure URL → single-card mapping for the search box's "paste a link, play it" flow.
 *
 * Given a pasted media link (or bare YouTube id), builds the single [MediaSearchResult] card shown
 * instead of text-searching the URL string; returns null when the input is a plain search phrase.
 * Anything [MediaUrlCleaner] rejects stays a search phrase. Matching is keyword-free so a URL
 * containing search terms is never half-search / half-card.
 *
 * Kept free of Minecraft / client types so the mapping is unit-testable on the JVM.
 */
@DreamDisplaysXUnstableApi
object PastedMediaCards {

    /**
     * Recognizes [query] as a pasted media link and builds its single-card result, or returns null
     * for a plain search phrase.
     *
     * A share sheet / chat message wraps the link in text ("【标题】 https://b23.tv/xxxx"), so the
     * first embedded URL is extracted first: treating the whole blob as a search phrase is why a
     * pasted link still listed Bilibili search results instead of resolving to that one video.
     */
    fun fromQuery(query: String): MediaSearchResult? {
        val candidate = MediaUrlCleaner.canonicalize(query) ?: return null
        val source = MediaSource.from(candidate)
        return when (source) {
            is MediaSource.YouTube -> fallbackResult(source.videoId)
            is MediaSource.Twitch -> twitchResult(source, null)
            is MediaSource.Vimeo -> platformResult(
                source.url, MediaPlatform.VIMEO, null, CustomMediaUrls.displayName(source.url),
            )
            is MediaSource.Kick -> platformResult(
                source.url, MediaPlatform.KICK, null, CustomMediaUrls.displayName(source.url),
            )
            // Bilibili URLs keep their platform badge; bangumi / episode / live URLs stay playable as-is.
            // Any metadata the cache already holds (a previously played / resolved link) fills in a real
            // title and thumbnail right away; the client enriches the card in the background otherwise.
            is MediaSource.Bilibili -> when {
                // A media list / favourites folder and an uploader space are not playable on their own:
                // their cards carry a prefixed id so the caller can route them to the right page.
                source.mediaListId != null -> mediaListCard(source)
                source.spaceMid != null -> spaceCard(source)
                else -> bilibiliResult(
                    source,
                    BilibiliMetadataCache.cacheKey(source)?.let(BilibiliMetadataCache::get),
                )
            }
            is MediaSource.DirectStream -> customResult(source.streamUrl)
            is MediaSource.Remote -> customResult(source.url)
            is MediaSource.Ingest -> customResult(source.url)
        }
    }

    /** Minimal result used when URL metadata could not be fetched. */
    private fun fallbackResult(videoId: String) =        MediaSearchResult(videoId, YouTubeUrls.watchUrl(videoId), null, null, null)

    /** Builds a single-card result for a pasted Twitch URL, using [meta] when the Helix lookup succeeded. */
    private fun twitchResult(source: MediaSource.Twitch, meta: TwitchMetadata?): MediaSearchResult {
        val id = TwitchMetadataCache.cacheKey(source) ?: source.url
        val fallbackTitle = source.channel ?: source.videoId ?: source.clipSlug ?: source.url
        return MediaSearchResult(
            id = id,
            title = meta?.title ?: fallbackTitle,
            uploader = meta?.channelName,
            durationSec = null,
            viewCount = meta?.viewCount,
            watchUrlOverride = source.url,
            thumbnailUrlOverride = meta?.thumbnailUrl,
            isTwitch = true,
            isLive = meta?.isLive ?: false,
            platform = MediaPlatform.TWITCH,
        )
    }

    /**
     * Builds a single-card result for a pasted Vimeo / Kick link. The card is keyed by the watch URL
     * (unlike Twitch, whose id is a cache key) so its thumbnail slot never collides with a YouTube id;
     * [meta] fills in the title / uploader / thumbnail when the metadata lookup succeeded.
     */
    private fun platformResult(
        url: String,
        platform: MediaPlatform,
        meta: PlatformVideoMetadata?,
        fallbackTitle: String,
    ): MediaSearchResult = MediaSearchResult(
        id = url,
        title = meta?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
        uploader = meta?.uploader,
        durationSec = meta?.durationSec,
        viewCount = meta?.viewCount,
        watchUrlOverride = url,
        thumbnailUrlOverride = meta?.thumbnailUrl,
        isLive = meta?.isLive ?: false,
        platform = platform,
    )

    /** Card id prefix for a Bilibili favourites / media list; the suffix is the media id. */
    const val MEDIA_LIST_PREFIX: String = "ml:"

    /** Card id prefix for an uploader space; the suffix is the `mid`. */
    const val SPACE_PREFIX: String = "space:"

    /**
     * Single card standing for a favourites / media list, routed to the playlist import on pick.
     * [title] / [coverUrl] come from the list itself once the background lookup lands.
     */
    fun mediaListCard(
        source: MediaSource.Bilibili,
        title: String? = null,
        coverUrl: String? = null,
    ): MediaSearchResult {
        val id = source.mediaListId ?: 0L
        return MediaSearchResult(
            id = MEDIA_LIST_PREFIX + id,
            title = title?.takeIf { it.isNotBlank() } ?: "media list ml$id",
            uploader = null,
            durationSec = null,
            viewCount = null,
            watchUrlOverride = source.url,
            thumbnailUrlOverride = coverUrl?.takeIf { it.isNotBlank() },
            platform = MediaPlatform.BILIBILI,
        )
    }

    /**
     * Single card standing for an uploader space, routed to that creator's page on pick. [name] /
     * [avatarUrl] / [fans] / [videos] come from the profile lookup once it lands, so the card reads
     * like a creator card (avatar, name, follower and video counts) instead of a bare URL.
     */
    fun spaceCard(
        source: MediaSource.Bilibili,
        name: String? = null,
        avatarUrl: String? = null,
        fans: Long? = null,
        videos: Long? = null,
    ): MediaSearchResult {
        val mid = source.spaceMid ?: 0L
        return MediaSearchResult(
            id = SPACE_PREFIX + mid,
            title = name?.takeIf { it.isNotBlank() } ?: "space.bilibili.com/$mid",
            uploader = null,
            durationSec = null,
            viewCount = null,
            watchUrlOverride = source.url,
            thumbnailUrlOverride = avatarUrl?.takeIf { it.isNotBlank() },
            channelAvatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
            isUploader = true,
            uploaderId = mid,
            uploaderFans = fans,
            uploaderVideoCount = videos,
            platform = MediaPlatform.BILIBILI,
        )
    }

    /**
     * Builds a single-card result for a pasted Bilibili link. [meta] fills in the title / uploader /
     * thumbnail when available (a cached or just-resolved entry); the card falls back to the URL's
     * display name so the link stays visible even before metadata lands.
     */
    fun bilibiliResult(source: MediaSource.Bilibili, meta: PlatformVideoMetadata?): MediaSearchResult =
        MediaSearchResult(
            id = source.url,
            title = meta?.title?.takeIf { it.isNotBlank() } ?: CustomMediaUrls.displayName(source.url),
            uploader = meta?.uploader,
            durationSec = meta?.durationSec,
            viewCount = meta?.viewCount,
            watchUrlOverride = source.url,
            thumbnailUrlOverride = meta?.thumbnailUrl,
            isLive = meta?.isLive ?: false,
            platform = MediaPlatform.BILIBILI,
        )

    /** The single card shown for a pasted link. Built purely from the URL: file name as the title, host as the uploader. */
    private fun customResult(url: String, platform: MediaPlatform = MediaPlatform.YOUTUBE): MediaSearchResult =
        MediaSearchResult(
            id = url,
            title = CustomMediaUrls.displayName(url),
            uploader = CustomMediaUrls.hostOf(url),
            durationSec = null,
            viewCount = null,
            watchUrlOverride = url,
            isCustom = true,
            platform = platform,
        )
}
