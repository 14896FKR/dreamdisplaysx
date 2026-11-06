package com.dreamdisplayx.media.player.managers

/**
 * Decides which playback position is persisted when a display is saved (world exit, unregister) and
 * which one is resumed afterwards. Pure inputs, so the rules are covered by a headless unit test
 * instead of needing a running game to observe.
 */
object ResumePositionPolicy {
    /** A position this close to the end is a finished video, not a resume point. */
    const val TAIL_GUARD_NANOS: Long = 500_000_000L

    /** Inputs for the save decision. */
    data class SaveInput(
        /** True while the session is parked / paused (then [parkedNanos] is the visible position). */
        val paused: Boolean,
        /** Position frozen by the pause, or null when the session was never parked. */
        val parkedNanos: Long?,
        /** Live clock position (libvlc), always available. */
        val clockNanos: Long,
        /** Known media duration, 0 when unknown (live streams, unresolved media). */
        val durationNanos: Long,
    )

    /** Inputs for the resume decision. */
    data class RestoreInput(
        /** Position this client saved for the display, or 0 when it has none. */
        val localSavedNanos: Long,
        /** URL [localSavedNanos] belongs to, or null for legacy entries. */
        val localUrl: String?,
        /** Position the server persisted for the display, or 0 when it has none. */
        val serverNanos: Long,
        /** URL [serverNanos] belongs to. */
        val serverUrl: String?,
        /** URL the display is about to play. */
        val currentUrl: String?,
        /** Known media duration, 0 when unknown. */
        val durationNanos: Long,
    )

    /**
     * Position to persist: the paused position when the viewer is paused on a frame, otherwise the
     * live clock; never negative, and never a position inside the tail (that would resume at the end
     * and immediately finish again).
     */
    fun forSave(input: SaveInput): Long {
        val candidate = if (input.paused && (input.parkedNanos ?: 0L) > 0L) input.parkedNanos!! else input.clockNanos
        return sanitize(candidate, input.durationNanos)
    }

    /**
     * Position to resume from: a local save wins when it belongs to the same URL, otherwise the
     * server's stored position does; anything in the tail is treated as finished (0).
     */
    fun forRestore(input: RestoreInput): Long {
        if (matches(input.localUrl, input.currentUrl)) {
            val local = sanitize(input.localSavedNanos, input.durationNanos)
            if (local > 0L) return local
        }
        if (matches(input.serverUrl, input.currentUrl)) return sanitize(input.serverNanos, input.durationNanos)
        return 0L
    }

    /** Clamps to >= 0 and drops tail positions. An unknown duration leaves the position alone. */
    private fun sanitize(nanos: Long, durationNanos: Long): Long {
        if (nanos <= 0L) return 0L
        if (durationNanos <= 0L) return nanos
        return if (nanos >= (durationNanos - TAIL_GUARD_NANOS).coerceAtLeast(0L)) 0L else nanos
    }

    /** True when a stored URL may be applied to [currentUrl]; a legacy null URL counts as a match. */
    private fun matches(storedUrl: String?, currentUrl: String?): Boolean {
        if (storedUrl.isNullOrEmpty()) return true
        if (currentUrl.isNullOrEmpty()) return false
        return storedUrl == currentUrl
    }
}
