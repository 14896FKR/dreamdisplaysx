package com.dreamdisplayx.platform.client.console

import kotlin.math.roundToInt

/** One command typed into the Borderless console. Commands are bare words; there is no slash prefix. */
sealed interface ConsoleCommand {
    /** Turns the console feature off again: the magic word doubles as the off switch. */
    data object Disable : ConsoleCommand

    /** Starts or resumes playback. */
    data object Play : ConsoleCommand

    /** Pauses playback. */
    data object Pause : ConsoleCommand

    /** Flips between play and pause. */
    data object TogglePlay : ConsoleCommand

    /** Flips mute. */
    data object MuteToggle : ConsoleCommand

    /** Forces mute on or off. */
    data class Mute(val muted: Boolean) : ConsoleCommand

    /** Seeks by [deltaMs] relative to the current position. */
    data class SeekRelative(val deltaMs: Long) : ConsoleCommand

    /** Seeks to an absolute [positionMs]. */
    data class SeekTo(val positionMs: Long) : ConsoleCommand

    /** Seeks to [percent] of the video duration. */
    data class SeekPercent(val percent: Int) : ConsoleCommand

    /** Changes volume by [deltaUiPoints] on the 0..200 UI scale. */
    data class VolumeDelta(val deltaUiPoints: Int) : ConsoleCommand

    /** Sets volume to [uiPercent] on the 0..200 UI scale. */
    data class VolumeSet(val uiPercent: Int) : ConsoleCommand

    /** Switches a multi-part video to [page] (1-based). */
    data class PartTo(val page: Int) : ConsoleCommand

    /** Closes the Borderless overlay and returns to the world view. */
    data object Exit : ConsoleCommand

    /** Prints the command list. */
    data object Help : ConsoleCommand

    /** Nothing was typed. */
    data object Blank : ConsoleCommand

    /** Unrecognised input; [raw] is echoed back. */
    data class Unknown(val raw: String) : ConsoleCommand
}

/** Playback actions bound to keys while the console input line is closed. */
enum class WatchAction {
    /** Close the Borderless overlay. */
    EXIT,

    /** Toggle play / pause. */
    TOGGLE_PLAY,

    /** Seek forward by the coarse or fine step. */
    SEEK_FORWARD,

    /** Seek backward by the coarse or fine step. */
    SEEK_BACK,

    /** Raise the volume by one step. */
    VOLUME_UP,

    /** Lower the volume by one step. */
    VOLUME_DOWN,

    /** Toggle mute. */
    MUTE,
}

/** Parses console input into a [ConsoleCommand]. Pure: no Minecraft or playback dependency. */
object BorderlessConsoleParser {
    /** The magic word: toggles the whole Borderless console feature. */
    const val TOGGLE_WORD: String = "dev"

    /** Parses [input]; never throws and never returns null. */
    fun parse(input: String): ConsoleCommand {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ConsoleCommand.Blank
        val space = trimmed.indexOfFirst { it.isWhitespace() }
        val head = (if (space < 0) trimmed else trimmed.substring(0, space)).lowercase()
        val argument = if (space < 0) "" else trimmed.substring(space + 1).trim()
        return when (head) {
            TOGGLE_WORD -> ConsoleCommand.Disable
            "play", "resume" -> ConsoleCommand.Play
            "pause" -> ConsoleCommand.Pause
            // `p2` / `p 2` / `page 2` switch parts; a bare `p` still toggles playback.
            "toggle" -> ConsoleCommand.TogglePlay
            "p" -> parsePart(argument) ?: ConsoleCommand.TogglePlay
            "page" -> parsePart(argument) ?: ConsoleCommand.Unknown(trimmed)
            "seek" -> parseSeek(argument) ?: ConsoleCommand.Unknown(trimmed)
            "vol", "volume" -> parseVolume(argument) ?: ConsoleCommand.Unknown(trimmed)
            "mute" -> parseMute(argument)
            "exit", "close" -> ConsoleCommand.Exit
            "help", "?" -> ConsoleCommand.Help
            else -> (if (head.length > 1 && head[0] == 'p') parsePart(head.substring(1)) else null)
                ?: ConsoleCommand.Unknown(trimmed)
        }
    }

    /** `2` -> part 2; used by `p2`, `p 2` and `page 2`. */
    private fun parsePart(argument: String): ConsoleCommand? {
        val page = argument.toIntOrNull() ?: return null
        return if (page >= 1) ConsoleCommand.PartTo(page) else null
    }

    /** `+30` / `-1:00` seek relatively; `90` / `1:30` / `1:02:03` absolutely; `40%` by duration. */
    private fun parseSeek(argument: String): ConsoleCommand? {
        if (argument.isEmpty()) return null
        if (argument.endsWith("%")) {
            val percent = argument.dropLast(1).toIntOrNull() ?: return null
            return ConsoleCommand.SeekPercent(percent.coerceIn(0, 100))
        }
        val sign = when (argument.first()) {
            '+' -> 1L
            '-' -> -1L
            else -> 0L
        }
        val body = if (sign == 0L) argument else argument.substring(1)
        val millis = parseTimestampMillis(body) ?: return null
        return if (sign == 0L) ConsoleCommand.SeekTo(millis) else ConsoleCommand.SeekRelative(sign * millis)
    }

    /** `30` counts as seconds; `1:30` and `1:02:03` are clock forms. */
    private fun parseTimestampMillis(text: String): Long? {
        val parts = text.split(':')
        if (parts.size > 3) return null
        var total = 0.0
        for (part in parts) {
            val value = part.toDoubleOrNull() ?: return null
            if (value < 0.0) return null
            total = total * 60.0 + value
        }
        if (!total.isFinite()) return null
        return (total * 1000.0).toLong()
    }

    /** `vol 50` sets, `vol +5` / `vol -5` steps, all on the 0..200 UI scale. */
    private fun parseVolume(argument: String): ConsoleCommand? {
        if (argument.isEmpty()) return null
        val value = argument.toIntOrNull() ?: return null
        return when (argument.first()) {
            '+' -> ConsoleCommand.VolumeDelta(value.coerceIn(-200, 200))
            '-' -> ConsoleCommand.VolumeDelta(value.coerceIn(-200, 200))
            else -> ConsoleCommand.VolumeSet(value.coerceIn(0, VolumeMath.UI_MAX_PERCENT))
        }
    }

    /** `mute` flips; `mute on` / `mute off` force, with `1` / `0` accepted too. */
    private fun parseMute(argument: String): ConsoleCommand = when (argument.lowercase()) {
        "", "toggle" -> ConsoleCommand.MuteToggle
        "on", "1", "true" -> ConsoleCommand.Mute(true)
        "off", "0", "false" -> ConsoleCommand.Mute(false)
        else -> ConsoleCommand.MuteToggle
    }
}

/** Volume helpers shared by the console and the watch keys; the UI scale is 0..200 percent. */
object VolumeMath {
    /** Highest volume the UI shows; 200% maps to an internal volume of 1.0. */
    const val UI_MAX_PERCENT: Int = 200

    /** One fine step on the UI scale, matching the slider's 0.025 internal increment. */
    const val FINE_STEP_PERCENT: Int = 5

    /** One coarse step on the UI scale, matching the slider's 0.05 internal increment. */
    const val COARSE_STEP_PERCENT: Int = 10

    /** Converts the internal 0..1 [volume] to the UI percentage. */
    fun toUiPercent(volume: Float): Int = (volume.coerceIn(0f, 1f) * UI_MAX_PERCENT).roundToInt()

    /** Converts a UI percentage to the internal 0..1 volume. */
    fun fromUiPercent(percent: Int): Float =
        percent.coerceIn(0, UI_MAX_PERCENT).toFloat() / UI_MAX_PERCENT

    /** Applies [deltaUiPoints] to [volume] and snaps the result to a fine slider step. */
    fun step(volume: Float, deltaUiPoints: Int): Float {
        val stepInternal = FINE_STEP_PERCENT.toDouble() / UI_MAX_PERCENT
        val raw = volume.toDouble() + deltaUiPoints.toDouble() / UI_MAX_PERCENT
        return ((raw / stepInternal).roundToInt() * stepInternal).coerceIn(0.0, 1.0).toFloat()
    }
}

/** Clock formatting for console feedback: `12:34` under an hour, `1:02:03` above. */
object PlaybackClock {
    /** Formats [millis] as `m:ss` or `h:mm:ss`; negative values clamp to zero. */
    fun format(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Session-scoped on/off switch for the Borderless console. It is seeded once from the config default,
 * then flipped by the `Dev` magic word without touching the config file.
 */
object BorderlessConsoleState {
    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var seeded: Boolean = false

    /** True while the console owns the Borderless input keys. */
    val isEnabled: Boolean get() = enabled

    /** Seeds the switch from [defaultValue] once; later calls keep the runtime value. */
    fun seedIfNeeded(defaultValue: Boolean) {
        if (seeded) return
        seeded = true
        enabled = defaultValue
    }

    /** Overrides the switch directly. */
    fun set(value: Boolean) {
        seeded = true
        enabled = value
    }

    /** Flips the switch and returns the new state. */
    fun toggle(): Boolean {
        seeded = true
        enabled = !enabled
        return enabled
    }
}
