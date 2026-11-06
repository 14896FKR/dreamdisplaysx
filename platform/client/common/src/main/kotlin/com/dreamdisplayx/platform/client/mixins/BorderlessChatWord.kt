package com.dreamdisplayx.platform.client.mixins

import com.dreamdisplayx.platform.client.console.BorderlessConsoleParser
import com.dreamdisplayx.platform.client.console.BorderlessConsoleScreen
import com.dreamdisplayx.platform.client.console.BorderlessConsoleState
import com.dreamdisplayx.platform.client.ui.FullscreenOverlayManager
import com.dreamdisplayx.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Swallows the `Dev` magic word typed into vanilla chat while a Borderless video is up: it flips the
 * console feature instead of sending a chat message to the server.
 */
@Suppress("NonJavaMixin")
@Pseudo
@Mixin(targets = ["net.minecraft.client.gui.screens.ChatScreen"])
open class BorderlessChatWord {
    @Inject(method = ["handleChatInput"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun dreamdisplayxDevWord(message: String, addToRecent: Boolean, ci: CallbackInfo) {
        if (!FullscreenOverlayManager.isImmersiveActive) return
        if (!message.trim().equals(BorderlessConsoleParser.TOGGLE_WORD, ignoreCase = true)) return
        val enabled = BorderlessConsoleState.toggle()
        val mc = Minecraft.getInstance()
        if (enabled) {
            BorderlessConsoleScreen.showStatus("Console on. Type help.")
            BorderlessConsoleScreen.open()
        } else {
            MinecraftScreenUtil.setScreen(mc, null)
        }
        ci.cancel()
    }
}
