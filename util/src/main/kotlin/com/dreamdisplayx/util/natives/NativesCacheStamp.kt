package com.dreamdisplayx.util.natives

/**
 * Decides whether a cached native runtime still matches the release this jar was built against.
 * Pure logic only; [NativesDownloader] owns the file IO.
 */
object NativesCacheStamp {

    /** Stamp file written inside the platform's `libvlc/` directory. */
    const val FILE_NAME = "natives-stamp.txt"

    /** Release tag a manifest's `release_base` points at, or null when it carries no usable tag. */
    fun tagOf(releaseBase: String): String? {
        val trimmed = releaseBase.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val last = trimmed.substringAfterLast('/')
        // A bare ".../releases" or ".../download" page carries no tag segment.
        if (last.isEmpty() || last == "releases" || last == "download" || last == "latest") return null
        // Guard against a scheme-only or host-only string being accepted as a tag.
        if (last.contains(':')) return null
        return last
    }

    /** Whether a cache stamped with [stampedTag] can serve [expectedTag]; an unknown stamp never matches. */
    fun isFresh(stampedTag: String?, expectedTag: String?): Boolean {
        if (expectedTag == null) return true
        return stampedTag == expectedTag
    }

    /** Whether a cache with no stamp file at all may be reused. */
    fun legacyCacheIsFresh(expectedTag: String?): Boolean = expectedTag == null
}
