package com.dreamdisplayx.platform.client.mixins

import com.dreamdisplayx.platform.client.console.BorderlessConsoleScreen
import com.dreamdisplayx.platform.client.console.BorderlessControl
import com.dreamdisplayx.platform.client.console.WatchAction
import com.dreamdisplayx.platform.client.ui.FullscreenOverlayManager
import com.dreamdisplayx.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
//? if <26.3 {
import org.lwjgl.glfw.GLFW
//?}

/**
 * Routes the raw key stream while a Borderless video owns the screen: the chat key opens the console,
 * and the playback keys drive the video while the console input line is closed.
 */
@Suppress("NonJavaMixin")
@Pseudo
@Mixin(targets = ["net.minecraft.client.KeyboardHandler"])
open class BorderlessKeyboardCapture {
    //? if <26.3 {
    @Inject(method = ["keyPress"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun dreamdisplayxCapture(
        window: Long,
        key: Int,
        scanCode: Int,
        action: Int,
        modifiers: Int,
        ci: CallbackInfo,
    ) {
        if (action != GLFW.GLFW_PRESS) return
        val mc = Minecraft.getInstance()
        if (MinecraftScreenUtil.currentScreen(mc) != null) return
        // Close the overlay here instead of letting vanilla open the pause screen and closing it a
        // tick later: that peek showed up as a flash of the pause menu.
        if (key == GLFW.GLFW_KEY_ESCAPE && FullscreenOverlayManager.active != null) {
            FullscreenOverlayManager.dismissActive(mc)
            ci.cancel()
            return
        }
        if (!BorderlessControl.isConsoleActive()) return
        if (mc.options.keyChat.matches(key, scanCode)) {
            BorderlessConsoleScreen.openDeferred()
            ci.cancel()
            return
        }
        val watch = watchAction(key) ?: return
        // Watch keys run with the console closed, so their status line has to be raised here; without
        // this the volume / seek / mute keys changed the video silently.
        val feedback = BorderlessControl.applyWatch(watch, modifiers and GLFW.GLFW_MOD_SHIFT != 0)
        if (!feedback.isNullOrEmpty()) BorderlessConsoleScreen.showStatus(feedback)
        ci.cancel()
    }
    //?}

    /** Maps a playback key to its action. */
    //? if <26.3 {
    private fun watchAction(key: Int): WatchAction? = when (key) {
        GLFW.GLFW_KEY_F -> WatchAction.EXIT
        GLFW.GLFW_KEY_SPACE -> WatchAction.TOGGLE_PLAY
        GLFW.GLFW_KEY_LEFT -> WatchAction.SEEK_BACK
        GLFW.GLFW_KEY_RIGHT -> WatchAction.SEEK_FORWARD
        GLFW.GLFW_KEY_UP -> WatchAction.VOLUME_UP
        GLFW.GLFW_KEY_DOWN -> WatchAction.VOLUME_DOWN
        GLFW.GLFW_KEY_M -> WatchAction.MUTE
        else -> null
    }
    //?}
}
