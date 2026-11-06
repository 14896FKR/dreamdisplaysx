package com.dreamdisplayx.platform.client.mixins

import com.dreamdisplayx.platform.client.ui.FullscreenOverlayManager
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/** Hides Jade's overlay while a borderless video surface owns the HUD. */
@Suppress("NonJavaMixin")
@Pseudo
@Mixin(targets = ["snownee.jade.overlay.OverlayRenderer"])
abstract class JadeOverlaySuppress {
    private companion object {
        @JvmStatic
        @Inject(method = ["shouldShow"], at = [At("HEAD")], cancellable = true, require = 0)
        private fun suppressShouldShow(ci: CallbackInfoReturnable<Boolean>) {
            if (FullscreenOverlayManager.isImmersiveActive) ci.setReturnValue(false)
        }
    }
}
