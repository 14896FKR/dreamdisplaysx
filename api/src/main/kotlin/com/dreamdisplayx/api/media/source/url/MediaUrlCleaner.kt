package com.dreamdisplayx.api.media.source.url

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.security.policy.MediaUrlPolicy
import java.util.*

/**
 * Turns a pasted media link into the URL the player actually needs: share-sheet text is reduced to
 * its first URL and share/referral bookkeeping parameters are dropped.
 *
 * @since 1.10.6.x
 */
@DreamDisplaysXUnstableApi
object MediaUrlCleaner {

    /**
     * Query keys that only carry referral / share bookkeeping. Anything not listed here keeps its
     * value and position, so signed and platform-specific parameters survive untouched.
     */
    private val TRACKING_KEYS = setOf(
        "spm", "spm_id_from", "from_source", "from_spmid", "vd_source", "buvid",
        "share_source", "share_medium", "share_plat", "share_session_id", "share_tag", "share_times",
        "share_from", "unique_k", "bbdown",
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
        "gclid", "fbclid", "msclkid", "yclid", "dclid", "twclid", "igshid",
        "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok",
        "si", "pp", "feature", "ab_channel",
        "tt_content", "tt_medium", "sr", "referrer",
    )

    /** Matches the first http(s) URL inside a pasted blob of text. */
    private val URL_IN_TEXT = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /** Punctuation a share sheet / chat message leaves stuck to the end of a URL. */
    private const val URL_TRAILING = ".,;:!?)]}>\"'，。；：！？）】》”’"

    /** Rebuilds [url] keeping only query pairs whose (lowercased) key passes [keep]; fragment preserved. */
    fun filterQuery(url: String, keep: (String) -> Boolean): String {
        val hashAt = url.indexOf('#')
        val beforeHash = if (hashAt >= 0) url.substring(0, hashAt) else url
        val fragment = if (hashAt >= 0) url.substring(hashAt) else ""
        val queryAt = beforeHash.indexOf('?')
        if (queryAt < 0) return url
        val base = beforeHash.substring(0, queryAt)
        val kept = beforeHash.substring(queryAt + 1)
            .split('&')
            .filter { pair -> pair.isNotEmpty() && keep(pair.substringBefore('=').lowercase(Locale.ROOT)) }
        return if (kept.isEmpty()) base + fragment else "$base?${kept.joinToString("&")}$fragment"
    }

    /** Drops tracking-only query parameters from [url]. */
    fun stripTracking(url: String): String = filterQuery(url) { it !in TRACKING_KEYS }

    /** Returns [text] with the trailing punctuation a chat message sticks to a URL removed. */
    fun trimTrailing(text: String): String = text.trim().trimEnd { it in URL_TRAILING }

    /**
     * The cleaned form of the link inside [text], or null when [text] is a plain search phrase.
     * Bilibili URLs are rebuilt from their known-meaningful parameters; other platforms only lose
     * [TRACKING_KEYS].
     */
    fun canonicalize(text: String): String? {
        if (text.isBlank()) return null
        val candidate = trimTrailing(URL_IN_TEXT.find(text)?.value ?: text)
        if (candidate.isEmpty() || !MediaUrlPolicy.isAllowed(candidate)) return null
        return when (val source = MediaSource.from(candidate)) {
            is MediaSource.Bilibili -> source.url
            is MediaSource.DirectStream -> stripTracking(source.streamUrl)
            is MediaSource.Remote -> stripTracking(source.url)
            is MediaSource.Twitch -> stripTracking(source.url)
            is MediaSource.Vimeo -> stripTracking(source.url)
            is MediaSource.Kick -> stripTracking(source.url)
            is MediaSource.Ingest -> source.url
            is MediaSource.YouTube -> stripTracking(candidate)
        }
    }
}
