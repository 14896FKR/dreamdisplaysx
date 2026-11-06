package com.dreamdisplayx.platform.client.mixins

import com.dreamdisplayx.platform.client.ui.FullscreenOverlayManager
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
import net.minecraft.client.DeltaTracker
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Hides Sodium Extra's HUD text (FPS / coordinates / ...) while a borderless video surface owns the HUD.
 * It draws itself after our overlay, so its top-right FPS counter flashed through the video.
 */
@Suppress("NonJavaMixin")
@Pseudo
@Mixin(targets = ["me.flashyreese.mods.sodiumextra.client.gui.SodiumExtraHud"])
abstract class SodiumExtraHudSuppress {
    //? if >=26 {
    @Inject(method = ["onHudRender"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun suppressHud(g: GuiGraphicsExtractor, dt: DeltaTracker, ci: CallbackInfo) {
        if (FullscreenOverlayManager.shouldHideVanillaHud) ci.cancel()
    }
    //?} else
    /*
    @Inject(method = ["onHudRender"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun suppressHud(g: GuiGraphics, dt: DeltaTracker, ci: CallbackInfo) {
        if (FullscreenOverlayManager.shouldHideVanillaHud) ci.cancel()
    }
    */
}
