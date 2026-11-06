package com.dreamdisplayx.platform.client.mixins

import com.dreamdisplayx.platform.client.console.BorderlessControl
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Freezes camera rotation while a user-opened Borderless video is up, so the 3D acoustics listener pose
 * stops moving and the audio gain stops jumping.
 */
@Suppress("NonJavaMixin")
@Pseudo
@Mixin(targets = ["net.minecraft.client.MouseHandler"])
open class BorderlessMouseLock {
    @Inject(method = ["turnPlayer"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun dreamdisplayxLockTurn(delta: Double, ci: CallbackInfo) {
        if (BorderlessControl.isCameraLocked()) ci.cancel()
    }
}
