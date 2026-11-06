package com.dreamdisplayx.platform.client.mixins

import com.dreamdisplayx.platform.client.console.BorderlessControl
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Freezes walking while a user-opened Borderless video is up. The impulse fields are zeroed as well as
 * the tick cancelled, so a key held before the overlay opened does not keep the player moving.
 */
@Suppress("NonJavaMixin")
@Pseudo
@Mixin(targets = ["net.minecraft.client.player.KeyboardInput"])
open class BorderlessMovementLock {
    @Inject(method = ["tick"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun dreamdisplayxLockMove(slow: Boolean, partialTick: Float, ci: CallbackInfo) {
        if (!BorderlessControl.isCameraLocked()) return
        val input = Minecraft.getInstance().player?.input ?: return
        input.leftImpulse = 0f
        input.forwardImpulse = 0f
        input.up = false
        input.down = false
        input.left = false
        input.right = false
        input.jumping = false
        input.shiftKeyDown = false
        ci.cancel()
    }
}
