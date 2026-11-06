package com.dreamdisplayx.media.player.util

import com.dreamdisplayx.api.security.policy.MediaHosts

/**
 * Builds the per-media libvlc options for a stream URL.
 *
 * These are passed to `media().play(mrl, options...)` as media-level options
 * (libvlc `libvlc_media_add_option`, `:option=value` syntax) rather than
 * instance-level `--option=value` arguments. Media-level options are what
 * actually get applied to the HTTP request for that specific media item.
 *
 * Streaming sites with hotlink protection (Bilibili, Kick, YouTube edge hosts)
 * reject requests without a browser-shaped `User-Agent` and a matching
 * `Referer`, so both are always added when the platform requires one.
 */
object LibVlcMediaOptions {

    /** Browser-shaped UA so CDN hotlink protection doesn't 403 the request. */
    const val BROWSER_USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /**
     * Media-level libvlc options for [url]. The `User-Agent` is always set (the
     * libvlc default is not browser-shaped and is rejected by several CDNs); the
     * `Referer` is added only when [MediaHosts] knows a platform referer for it.
     */
    fun forUrl(url: String, live: Boolean = false): Array<String> {
        val options = mutableListOf(":http-user-agent=$BROWSER_USER_AGENT")
        if (url.startsWith("http://") || url.startsWith("https://")) {
            // Re-open the HTTP input after an unexpected CDN disconnect instead of ending the stream.
            options.add(":http-reconnect")
        }
        if (live) {
            // Live has nothing to sync against, so the clock machinery only adds latency: no jitter
            // compensation and no clock synchronisation is what VLC's own low-latency preset does.
            options.add(":clock-jitter=0")
            options.add(":clock-synchro=0")
        }
        MediaHosts.refererFor(url)?.let { referer ->
            // VLC 3.0 option name: http-referrer (double r).
            options.add(":http-referrer=$referer")
        }
        return options.toTypedArray()
    }
}