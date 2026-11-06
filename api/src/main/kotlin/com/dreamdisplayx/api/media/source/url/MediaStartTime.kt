package com.dreamdisplayx.api.media.source.url

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Reads a `?t=` / `?start=` / `#t=` offset from a pasted media URL: plain seconds, `1:30`, or `1h2m3s`.
 */
@DreamDisplaysXUnstableApi
object MediaStartTime {
    /** Query and fragment parameter names that carry a start offset. */
    private val PARAM_NAMES = listOf("t", "start", "start_time", "time_continue")

    /** Returns the offset in milliseconds, or 0 when [url] carries no usable timestamp. */
    fun extractMillis(url: String): Long {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return 0L
        for (name in PARAM_NAMES) {
            val raw = parameter(trimmed, name) ?: continue
            val millis = parseOffsetMillis(raw)
            if (millis > 0L) return millis
        }
        return 0L
    }

    /** Reads [name] from the URL query, then from the fragment. */
    private fun parameter(url: String, name: String): String? {
        val fragment = url.substringAfter('#', "")
        fragmentParameter(fragment, name)?.let { return it }
        val query = url.substringBefore('#').substringAfter('?', "")
        return queryParameter(query, name)
    }

    /** Reads [name] from `a=1&b=2`; case-insensitive, tolerant of a missing value. */
    private fun queryParameter(query: String, name: String): String? {
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            if (pair.substring(0, idx).equals(name, ignoreCase = true)) {
                return pair.substring(idx + 1).takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** Reads `t=90` style parameters out of a fragment such as `#t=90` or `#page=2&t=90`. */
    private fun fragmentParameter(fragment: String, name: String): String? {
        if (fragment.isEmpty()) return null
        return queryParameter(fragment, name)
    }

    /**
     * Parses an offset value: plain seconds (`299.2`), a clock form (`1:30`, `1:02:03`), or the
     * `1h2m3s` form. Returns 0 when the value cannot be understood.
     */
    private fun parseOffsetMillis(raw: String): Long {
        val value = raw.trim().lowercase()
        if (value.isEmpty()) return 0L
        value.toDoubleOrNull()?.let { seconds -> return secondsToMillis(seconds) }
        if (value.contains(':')) return clockToMillis(value)
        if (value.any { it == 'h' || it == 'm' || it == 's' }) return unitToMillis(value)
        return 0L
    }

    /** `299.2` and `90` are seconds; negative or non-finite values are rejected. */
    private fun secondsToMillis(seconds: Double): Long {
        if (!seconds.isFinite() || seconds <= 0.0) return 0L
        return (seconds * 1000.0).toLong()
    }

    /** `1:30` is 90 seconds, `1:02:03` is 3723 seconds. */
    private fun clockToMillis(value: String): Long {
        var total = 0.0
        for (part in value.split(':')) {
            val number = part.toDoubleOrNull() ?: return 0L
            if (number < 0.0) return 0L
            total = total * 60.0 + number
        }
        return secondsToMillis(total)
    }

    /** `1h2m3s`, `90s`, `1m30s` — any of h/m/s present and every piece numeric. */
    private fun unitToMillis(value: String): Long {
        val matcher = Regex("(\\d+(?:\\.\\d+)?)([hms])")
        var total = 0.0
        var consumed = 0
        for (match in matcher.findAll(value)) {
            val amount = match.groupValues[1].toDoubleOrNull() ?: return 0L
            total += when (match.groupValues[2]) {
                "h" -> amount * 3600.0
                "m" -> amount * 60.0
                else -> amount
            }
            consumed += match.value.length
        }
        if (consumed != value.length) return 0L
        return secondsToMillis(total)
    }
}
