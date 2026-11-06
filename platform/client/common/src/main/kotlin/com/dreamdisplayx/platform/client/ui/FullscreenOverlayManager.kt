package com.dreamdisplayx.platform.client.ui

import com.dreamdisplayx.api.playback.model.FullscreenAckAction
import com.dreamdisplayx.api.playback.model.FullscreenMode
import com.dreamdisplayx.core.protocol.common.packets.FullscreenAck
import com.dreamdisplayx.platform.client.console.BorderlessConsoleScreen
import com.dreamdisplayx.platform.client.console.BorderlessConsoleState
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import com.dreamdisplayx.platform.client.net.ProtocolRouter
import com.dreamdisplayx.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor

//?} else
/*
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics
//?}
//? if <1.20 {
import com.dreamdisplayx.platform.client.ui.compat.GuiGraphics
//?}
*/

/**
 * Coordinator for the fullscreen video overlay. At most one overlay is live at a time (a newly
 * opened one fades the previous out); closing overlays keep rendering until their animation ends.
 * Rendered before [PipOverlayManager] in the HUD hooks so PiP stays on top.
 */
object FullscreenOverlayManager {
    /** Live plus still-fading-out overlays, in open order. */
    private val overlays = mutableListOf<FullscreenOverlay>()

    /** Tracks whether a GUI screen was open last tick, for Esc (pause-screen) edge detection. */
    private var screenWasOpen = false

    /** The single non-closing overlay, or null when none is active. */
    val active: FullscreenOverlay? get() = overlays.lastOrNull { !it.isClosing }

    /** True when any overlay (including a fading-out one) still needs rendering. */
    val isEmpty: Boolean get() = overlays.isEmpty()

    /**
     * True while the vanilla HUD (hotbar, health / hunger, chat, ...) should stay hidden behind a
     * fullscreen video.
     */
    val shouldHideVanillaHud: Boolean
        get() = !isEmpty && com.dreamdisplayx.platform.client.render.FlashbackCompat.shouldRenderHud

    /** True while an immersive-mode overlay is active; used to suppress the crosshair. */
    val isImmersiveActive: Boolean
        get() = active?.mode == FullscreenMode.IMMERSIVE

    /** Opens a fullscreen overlay for [screen], fading out any previous one. */
    fun open(
        screen: DisplayScreen,
        mode: FullscreenMode,
        forced: Boolean = false,
        sessionId: String? = null
    ): FullscreenOverlay {
        BorderlessConsoleState.seedIfNeeded(ClientStateManager.config.borderlessConsole)
        overlays.forEach { it.startClose() }
        val overlay = FullscreenOverlay(screen, mode, forced, sessionId)
        overlays.add(overlay)
        return overlay
    }

    /** Starts the close animation for the active overlay belonging to [ds], if any. */
    fun remove(ds: DisplayScreen) {
        overlays.filter { it.displayScreen === ds }.forEach { it.startClose() }
        // The console only exists to drive a Borderless video, so it must not outlive the overlay
        // (e.g. playback ended and the overlay auto-closed while the input line was open).
        BorderlessConsoleScreen.closeIfOpen()
    }

    /** Gracefully closes every overlay. */
    fun closeAll() {
        overlays.forEach { it.startClose() }
    }

    /**
     * Closes the active overlay the way Esc does: a forced session minimises to PiP, a user-opened one
     * is dismissed. Shared by the Esc edge detection and the Borderless console `exit` command.
     */
    fun dismissActive(mc: Minecraft) {
        val overlay = active ?: return
        MinecraftScreenUtil.setScreen(mc, null)
        // The console only drives a Borderless video, so closing the overlay closes the input line as
        // well. This path (Esc / `exit` / F) does not go through [remove], which used to be the only
        // place that closed it.
        BorderlessConsoleScreen.closeIfOpen()
        if (overlay.forced) {
            overlay.displayScreen.minimizeFullscreenToPip()
            overlay.sessionId?.let { ProtocolRouter.send(FullscreenAck(it, FullscreenAckAction.MINIMIZED.wire)) }
        } else {
            overlay.displayScreen.deactivateFullscreen()
            overlay.sessionId?.let { ProtocolRouter.send(FullscreenAck(it, FullscreenAckAction.DISMISSED.wire)) }
        }
    }

    /** Immediately destroys all overlays without animation. */
    fun clear() {
        val mc = Minecraft.getInstance()
        overlays.forEach { it.cleanup(mc) }
        overlays.clear()
    }

    /**
     * Per-tick Esc handling: pressing Esc during gameplay opens the vanilla pause screen, so a `null -> pause screen`
     * transition while an overlay is active is treated as the user's Esc-close intent.
     */
    fun onClientTick(mc: Minecraft) {
        val screen = MinecraftScreenUtil.currentScreen(mc)
        val overlay = active
        if (overlay != null && !screenWasOpen && screen is PauseScreen) {
            dismissActive(mc)
        }
        screenWasOpen = screen != null
    }

    /** Uploads and renders all overlays, dropping ones whose close animation has finished. */
    //? if >=26 {
    fun renderAll(mc: Minecraft, graphics: GuiGraphicsExtractor, partialTick: Float) {
        //?} else
        /*fun renderAll(mc: Minecraft, graphics: GuiGraphics, partialTick: Float) {*/
        val iter = overlays.iterator()
        while (iter.hasNext()) {
            val overlay = iter.next()
            overlay.uploadFrame()
            if (!overlay.render(mc, graphics, partialTick)) iter.remove()
        }
    }
}
