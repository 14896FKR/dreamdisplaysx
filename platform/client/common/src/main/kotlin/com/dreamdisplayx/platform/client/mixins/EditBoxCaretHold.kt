package com.dreamdisplayx.platform.client.mixins

import net.minecraft.client.gui.components.EditBox
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

/**
 * Access to the vanilla caret blink timestamp. The Borderless console moves it forward every frame
 * to keep the caret solid, because the blink is decided inside `EditBox#renderWidget`.
 */
@Suppress("NonJavaMixin")
@Mixin(EditBox::class)
interface EditBoxCaretHold {
    /** Sets the blink reference time; [millis] must come from the clock the box compares against. */
    @Accessor("focusedTime")
    fun dreamdisplayxSetFocusedTime(millis: Long)

    /** Moves the visible text window without moving the caret, so a pasted link shows its head. */
    @Accessor("displayPos")
    fun dreamdisplayxSetDisplayPos(pos: Int)
}
