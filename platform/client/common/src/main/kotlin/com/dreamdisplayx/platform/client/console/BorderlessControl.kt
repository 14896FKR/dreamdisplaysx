package com.dreamdisplayx.platform.client.console

import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.playback.model.FullscreenMode
import com.dreamdisplayx.api.playback.service.keys.PlaybackServices
import com.dreamdisplayx.api.runtime.registry.service.getOrNull
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.dreamdisplayx.platform.client.ui.FullscreenOverlayManager
import net.minecraft.client.Minecraft

/**
 * Applies console commands and watch keys to the display behind the active Borderless overlay.
 * Every entry point is a no-op when no overlay is open, so callers need no extra guard.
 */
object BorderlessControl {
    /** Fine seek step used by the watch keys; matches the display menu. */
    private const val SEEK_STEP_MS: Long = 5_000L

    /** Coarse seek step behind Shift; matches the display menu. */
    private const val SEEK_COARSE_STEP_MS: Long = 15_000L

    /** The display whose Borderless overlay is on screen, or null. */
    fun target(): DisplayScreen? = FullscreenOverlayManager.active?.displayScreen

    /** True while the console feature owns the input keys inside Borderless. */
    fun isConsoleActive(): Boolean =
        FullscreenOverlayManager.isImmersiveActive && BorderlessConsoleState.isEnabled

    /**
     * True while Borderless should freeze walking and camera turning. Forced broadcasts are excluded so a
     * server-driven fullscreen never takes the player's input away.
     */
    fun isCameraLocked(): Boolean {
        val overlay = FullscreenOverlayManager.active ?: return false
        return overlay.mode == FullscreenMode.IMMERSIVE && !overlay.forced
    }

    /** Runs [command] and returns the line to echo in the console. */
    fun applyCommand(command: ConsoleCommand): String = when (command) {
        is ConsoleCommand.Blank -> ""
        is ConsoleCommand.Help -> {
            BorderlessConsoleScreen.showHelp()
            ""
        }
        is ConsoleCommand.Disable -> {
            BorderlessConsoleState.set(false)
            "Console off. Press T for chat."
        }
        is ConsoleCommand.Exit -> {
            FullscreenOverlayManager.dismissActive(Minecraft.getInstance())
            "Closing Borderless."
        }
        is ConsoleCommand.Play -> setPlaying(true)
        is ConsoleCommand.Pause -> setPlaying(false)
        is ConsoleCommand.TogglePlay -> {
            val ds = target()
            if (ds == null || !ds.canControlPlayback) "No controllable video."
            else setPlaying(ds.isPaused)
        }
        is ConsoleCommand.MuteToggle -> {
            val ds = target()
            if (ds == null || !ds.isVideoStarted) "No video."
            else setMuted(!ds.muted)
        }
        is ConsoleCommand.Mute -> setMuted(command.muted)
        is ConsoleCommand.SeekTo -> seekTo(command.positionMs)
        is ConsoleCommand.SeekPercent -> {
            val ds = target()
            val duration = ds?.mediaPlayerDurationNanos ?: 0L
            if (ds == null || duration <= 0L) "No seekable video."
            else seekTo(duration / 100L * command.percent / 1_000_000L)
        }
        is ConsoleCommand.SeekRelative -> {
            val ds = target()
            if (ds == null) "No video." else seekTo(ds.currentTimeNanos / 1_000_000L + command.deltaMs)
        }
        is ConsoleCommand.VolumeSet -> setVolume(command.uiPercent)
        is ConsoleCommand.VolumeDelta -> {
            val ds = target()
            if (ds == null) "No video." else setVolume(VolumeMath.toUiPercent(ds.volume) + command.deltaUiPoints)
        }
        is ConsoleCommand.PartTo -> switchPart(command.page)
        is ConsoleCommand.Unknown -> "Unknown command: ${command.raw}. Type help."
    }

    /** Runs a watch key; returns a status line, or null when the action printed nothing. */
    fun applyWatch(action: WatchAction, coarse: Boolean): String? {
        val seekStep = if (coarse) SEEK_COARSE_STEP_MS else SEEK_STEP_MS
        return when (action) {
            WatchAction.EXIT -> {
                FullscreenOverlayManager.dismissActive(Minecraft.getInstance())
                null
            }
            WatchAction.TOGGLE_PLAY -> applyCommand(ConsoleCommand.TogglePlay).takeIf { it.isNotEmpty() }
            WatchAction.MUTE -> applyCommand(ConsoleCommand.MuteToggle).takeIf { it.isNotEmpty() }
            WatchAction.SEEK_FORWARD -> applyCommand(ConsoleCommand.SeekRelative(seekStep))
            WatchAction.SEEK_BACK -> applyCommand(ConsoleCommand.SeekRelative(-seekStep))
            WatchAction.VOLUME_UP -> applyCommand(ConsoleCommand.VolumeDelta(volumeStep(coarse)))
            WatchAction.VOLUME_DOWN -> applyCommand(ConsoleCommand.VolumeDelta(-volumeStep(coarse)))
        }
    }

    /** The volume step, in UI points, for a Shift-modified press. */
    private fun volumeStep(coarse: Boolean): Int =
        if (coarse) VolumeMath.COARSE_STEP_PERCENT else VolumeMath.FINE_STEP_PERCENT

    /** Starts or pauses the targeted display. */
    private fun setPlaying(playing: Boolean): String {
        val ds = target() ?: return "No video."
        if (!ds.canControlPlayback) return "Server does not allow playback control."
        val playback = DreamServices.registry.get(PlaybackServices.PLAYBACK)
        val id = DisplayId(ds.uuid)
        if (playing) playback.play(id) else playback.pause(id)
        return if (playing) "Playing." else "Paused."
    }

    /** Seeks the targeted display to [positionMs], clamped to the known duration. */
    private fun seekTo(positionMs: Long): String {
        val ds = target() ?: return "No video."
        if (!ds.isVideoStarted || ds.isLive || !ds.canSeek() || !ds.canSeekHere) return "Not seekable."
        val durationMs = ds.mediaPlayerDurationNanos / 1_000_000L
        val target = if (durationMs > 0L) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)
        DreamServices.registry.get(PlaybackServices.PLAYBACK).seek(DisplayId(ds.uuid), target)
        return "Seek ${PlaybackClock.format(target)}."
    }

    /** Sets the targeted display volume from a UI percentage. */
    private fun setVolume(uiPercent: Int): String {
        val ds = target() ?: return "No video."
        val volume = VolumeMath.fromUiPercent(uiPercent)
        ds.volume = volume
        DreamServices.registry.get(PlaybackServices.PLAYBACK).setVolume(DisplayId(ds.uuid), volume)
        return "Volume ${VolumeMath.toUiPercent(volume)}%."
    }

    /** Mutes or unmutes the targeted display. */
    private fun setMuted(muted: Boolean): String {
        val ds = target() ?: return "No video."
        DreamServices.registry.get(PlaybackServices.PLAYBACK).mute(DisplayId(ds.uuid), muted)
        return if (muted) "Muted." else "Unmuted."
    }

    /** Jumps the targeted multi-part video to [page] and names it in the status line. */
    private fun switchPart(page: Int): String {
        val ds = target() ?: return "No video."
        val parts = ds.parts
        if (parts.size <= 1) return "Not a multi-part video."
        val total = parts.maxOf { it.page }
        val part = parts.firstOrNull { it.page == page } ?: return "No part $page (1-$total)."
        if (page == ds.currentPart) return "Already on P$page/$total."
        if (!ds.canSetVideoHere) return "Server does not allow changing the video."
        if (!ds.switchToPart(page)) return "Could not switch to P$page."
        return "P$page/$total · ${part.title}"
    }

}
