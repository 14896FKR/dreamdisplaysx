package com.dreamdisplayx.platform.client.managers

/**
 * One-line status of the last favourites / media-list import, rendered above the queue rows.
 * Progress updates re-arm the deadline; both success and failure fade out on their own.
 */
object PlaylistImportStatus {
    /** Longest the strip stays up for a single progress update. */
    private const val DEFAULT_DURATION_MS = 4_000L

    @Volatile
    private var text: String = ""

    @Volatile
    private var untilNanos: Long = 0L

    /** Shows [line] for [durationMs], replacing whatever was there. */
    fun show(line: String, durationMs: Long = DEFAULT_DURATION_MS) {
        text = line
        untilNanos = System.nanoTime() + durationMs * 1_000_000L
    }

    /** The line to render right now, or empty when it expired. */
    fun current(): String = if (System.nanoTime() < untilNanos) text else ""

    /** Drops the line immediately. */
    fun clear() {
        text = ""
        untilNanos = 0L
    }
}
