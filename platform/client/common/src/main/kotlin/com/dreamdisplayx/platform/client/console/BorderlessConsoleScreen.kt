package com.dreamdisplayx.platform.client.console

import com.dreamdisplayx.platform.client.Config
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import com.dreamdisplayx.platform.client.mixins.EditBoxCaretHold
import com.dreamdisplayx.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplayx.platform.client.ui.drawText
import com.dreamdisplayx.platform.client.ui.editBoxHasFocus
import com.dreamdisplayx.platform.client.ui.kit.UiScreenBase
import com.dreamdisplayx.platform.client.ui.kit.UiTheme
import com.dreamdisplayx.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.Util
//? if >=1.21.11 {
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
//?}
import net.minecraft.network.chat.Component
//? if >=26.3 {
import com.mojang.blaze3d.platform.InputConstants
//?} else
/*import org.lwjgl.glfw.GLFW*/

//? if >=26.3 {
private fun isConsoleEnterKey(key: Int): Boolean =
    key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER

private fun isConsoleEscapeKey(key: Int): Boolean = key == InputConstants.KEY_ESCAPE
//?} else
/*private fun isConsoleEnterKey(key: Int): Boolean =
    key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER

private fun isConsoleEscapeKey(key: Int): Boolean = key == GLFW.GLFW_KEY_ESCAPE*/

/**
 * Borderless command line, drawn like the vanilla chat bar: one thin strip at the bottom of the screen and
 * a single transient status line above it. It owns the keyboard while open, so the player cannot move and
 * the camera cannot turn; Esc returns to the watch state where the playback keys take over.
 */
class BorderlessConsoleScreen : UiScreenBase(Component.translatable("dreamdisplayx.console.title")) {

    private lateinit var input: EditBox

    /** Last value written to `focusedTime`, used only for the caret diagnostic line. */
    private var lastPinnedMillis: Long = 0L

    /** Last logged caret state; the diagnostic line is emitted when this changes. */
    private var lastCaretLogState: String? = null

    override fun init() {
        super.init()
        input = EditBox(
            Minecraft.getInstance().font, 0, 0, width - 16, 12,
            Component.translatable("dreamdisplayx.console.input"),
        )
        input.setMaxLength(80)
        input.setBordered(false)
        input.setTextColor(UiTheme.TEXT_PRIMARY)
        addRenderableWidget(input)
        setFocused(input)
    }

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        // The console exists only to drive a Borderless video: if that overlay went away (EOS, Esc, a
        // forced session ending, minimised to PiP) the input line must not keep floating over the world.
        if (BorderlessControl.target() == null) {
            MinecraftScreenUtil.setScreen(Minecraft.getInstance(), null)
            return
        }
        val font = Minecraft.getInstance().font
        val barTop = height - 14
        g.fill(2, barTop, width - 2, height - 2, BAR_BACKGROUND)
        g.drawText(font, ">", 4, barTop + 2, UiTheme.TEXT_SECONDARY, false)
        input.x = 14
        input.y = barTop + 2
        input.width = width - 22
        // Any click outside the bar drops the box's focus, and an unfocused EditBox draws no caret at
        // all: the console owns the keyboard while it is open, so take the focus back every frame.
        if (!editBoxHasFocus(input)) setFocused(input)
        holdCaretIfConfigured()
        drawChildren(g, mouseX, mouseY, partialTick)
        // The status line and the help block share the space above the input bar; the block is anchored
        // to the bar so there is no empty strip between them. Both sit on one plate: bare text over
        // bright video is unreadable, and a pure black plate is harsh against the picture.
        val status = currentStatus()
        val help = helpLines()
        if (status.isEmpty() && help.isEmpty()) return
        val textX = 4
        val lines = help.size + if (status.isEmpty()) 0 else 1
        var textY = barTop - 3 - lines * HELP_LINE_H
        val blockW = (maxOf(font.width(status), help.maxOfOrNull { font.width(it) } ?: 0) + 10)
            .coerceAtMost(width - 4)
        drawBlockPlate(g, 2, textY - 2, 2 + blockW, barTop)
        if (status.isNotEmpty()) {
            g.drawText(font, status, textX, textY, UiTheme.TEXT_PRIMARY, true)
            textY += HELP_LINE_H
        }
        for (line in help) {
            g.drawText(font, line, textX, textY, UiTheme.TEXT_SECONDARY, true)
            textY += HELP_LINE_H
        }
    }

    //? if >=1.21.11 {
    override fun keyPressed(event: KeyEvent): Boolean {
        if (editBoxHasFocus(input) && isConsoleEnterKey(event.key())) {
            submit(input.value)
            return true
        }
        if (isConsoleEscapeKey(event.key())) {
            closeToWatch()
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (swallowOpeningChar()) return true
        return if (editBoxHasFocus(input)) input.charTyped(event) else super.charTyped(event)
    }
    //?} else
    /*override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (editBoxHasFocus(input) && isConsoleEnterKey(keyCode)) {
            submit(input.value)
            return true
        }
        if (isConsoleEscapeKey(keyCode)) {
            closeToWatch()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(chr: Char, modifiers: Int): Boolean {
        if (swallowOpeningChar()) return true
        return if (editBoxHasFocus(input)) input.charTyped(chr, modifiers) else super.charTyped(chr, modifiers)
    }*/

    /**
     * The key that opens the console also reaches GLFW's character callback, which would insert `t` into
     * the freshly focused box. Swallow that one character right after opening.
     */
    private fun swallowOpeningChar(): Boolean {
        if (System.nanoTime() >= suppressTypedCharUntilNanos) return false
        suppressTypedCharUntilNanos = 0L
        return true
    }

    /** Runs one console line, shows its result, and closes the screen when the command ends the session. */
    private fun submit(raw: String) {
        val command = BorderlessConsoleParser.parse(raw)
        if (command is ConsoleCommand.Blank) return
        val feedback = BorderlessControl.applyCommand(command)
        input.setValue("")
        if (feedback.isNotEmpty()) showStatus(feedback)
        if (command is ConsoleCommand.Disable || command is ConsoleCommand.Exit) {
            MinecraftScreenUtil.setScreen(Minecraft.getInstance(), null)
        }
    }

    /** Drops keyboard focus back to the game without closing the overlay. */
    private fun closeToWatch() {
        MinecraftScreenUtil.setScreen(Minecraft.getInstance(), null)
    }

    override fun isPauseScreen(): Boolean = false

    /**
     * Vanilla shows the caret while the box is focused and `(Util.getMillis() - focusedTime) / 300 % 2 == 0`;
     * pinning that stamp picks the mode: now = solid, now - 300 ms = hidden (odd), blink is left alone.
     */
    private fun holdCaretIfConfigured() {
        val hold = input as? EditBoxCaretHold ?: return
        val nowMs = Util.getMillis()
        val pinned = when (ClientStateManager.config.borderlessConsoleCaret) {
            Config.CONSOLE_CARET_OFF -> nowMs - CARET_HIDE_OFFSET_MS
            Config.CONSOLE_CARET_SOLID -> nowMs
            else -> null
        }
        if (pinned != null) {
            hold.dreamdisplayxSetFocusedTime(pinned)
            lastPinnedMillis = pinned
        }
        logCaretState(pinned != null)
    }

    /** Logs the caret decision when it changes, so a reported flicker can be traced from the log alone. */
    private fun logCaretState(pinned: Boolean) {
        val mode = ClientStateManager.config.borderlessConsoleCaret
        val focused = editBoxHasFocus(input)
        val state = "$mode/$focused"
        if (state == lastCaretLogState) return
        lastCaretLogState = state
        if (!pinned) {
            logger.info("console caret mode={} focused={} (blink left to vanilla)", mode, focused)
            return
        }
        val parity = (Util.getMillis() - lastPinnedMillis) / 300L % 2L
        logger.info("console caret mode={} focused={} parity={} (0=shown,1=hidden)", mode, focused, parity)
    }

    /** Opens the console for the active Borderless overlay. */
    companion object {
        /** Diagnostic logger for the caret state. */
        private val logger = org.slf4j.LoggerFactory.getLogger("DreamDisplaysX/Console")

        /** Last status line shown, or empty when nothing was reported yet. */
        private var statusText: String = ""

        /** Wall-clock deadline (nanoTime) after which [statusText] stops rendering. */
        private var statusUntilNanos: Long = 0L

        /** How long a status line stays visible after a command. */
        private const val STATUS_DURATION_NANOS: Long = 2_500_000_000L

        /** Offset that makes vanilla's blink quotient odd, which hides the caret for that frame. */
        private const val CARET_HIDE_OFFSET_MS: Long = 300L

        /** Chat-bar backdrop, matching the vanilla chat input strip. */
        private const val BAR_BACKGROUND = 0x80000000.toInt()

        /** Opacity of the block plate behind the status / help block and the watch-mode HUD line. */
        private const val BLOCK_ALPHA = 0x9E

        /** Brightness of the plate's brightest channel after the sampled colour is darkened. */
        private const val PLATE_BRIGHTNESS = 46f

        /** How far the sampled saturation is pushed so the hue still reads on the plate. */
        private const val PLATE_SATURATION = 1.7f

        /** Height of the soft top edge that fades the plate into the picture. */
        private const val BLOCK_FEATHER_PX = 6

        /** Average colour of the picture under the plate; the overlay refreshes it while the block shows. */
        @Volatile
        private var blockTintRgb: Int = 0x1C1C24

        /** Eases the plate tint toward [rgb], the average colour sampled under the block. */
        fun updateBlockTint(rgb: Int) {
            val old = blockTintRgb
            fun blend(shift: Int): Int = ((old shr shift and 0xFF) * 2 + (rgb shr shift and 0xFF)) / 3
            blockTintRgb = (blend(16) shl 16) or (blend(8) shl 8) or blend(0)
        }

        /** True while a plate is on screen, so the overlay only samples frames while it matters. */
        fun blockVisible(): Boolean = currentStatus().isNotEmpty() || helpLines().isNotEmpty()

        /** Draws the plate: a darkened, saturated copy of the picture under it, feathered at the edges. */
        fun drawBlockPlate(g: GuiGraphicsCompat, left: Int, top: Int, right: Int, bottom: Int) {
            if (right <= left || bottom <= top) return
            val base = plateColor(blockTintRgb)
            val feather = minOf(BLOCK_FEATHER_PX, (bottom - top) / 2)
            // Top edge: one row per step so the slab dissolves into the picture instead of ending flat.
            for (i in 0 until feather) {
                val alpha = BLOCK_ALPHA * (i + 1) / feather
                g.fill(left, top + i, right, top + i + 1, (alpha shl 24) or base)
            }
            // Right edge: a soft column so the slab reads as a gradient, not a rectangle.
            for (i in 0 until feather) {
                val alpha = BLOCK_ALPHA * (feather - i) / (feather + 1)
                g.fill(right - feather + i, top + feather, right - feather + i + 1, bottom, (alpha shl 24) or base)
            }
            g.fill(left, top + feather, right - feather, bottom, (BLOCK_ALPHA shl 24) or base)
        }

        /**
         * Keeps the sampled hue, pushes its saturation, then drops the brightness: the plate tints with
         * the picture instead of washing to grey, and stays dark enough for white text.
         */
        private fun plateColor(rgb: Int): Int {
            val r = (rgb shr 16 and 0xFF).toFloat()
            val gch = (rgb shr 8 and 0xFF).toFloat()
            val b = (rgb and 0xFF).toFloat()
            val average = (r + gch + b) / 3f
            val brightest = maxOf(r, gch, b)
            val scale = if (brightest > 1f) PLATE_BRIGHTNESS / brightest else 0f
            fun channel(c: Float): Int =
                ((average + (c - average) * PLATE_SATURATION) * scale).toInt().coerceIn(0, 255)
            return (channel(r) shl 16) or (channel(gch) shl 8) or channel(b)
        }

        /** Window after opening in which the opening keystroke's character is discarded. */
        private var suppressTypedCharUntilNanos: Long = 0L

        /** How long the opening keystroke may trail the screen open and still be swallowed. */
        private const val OPEN_CHAR_GUARD_NANOS: Long = 80_000_000L

        /** Shows [line] above the chat bar for a couple of seconds. */
        fun showStatus(line: String) {
            statusText = line
            statusUntilNanos = System.nanoTime() + STATUS_DURATION_NANOS
        }

        /** The status line to render right now, or empty when it expired. */
        private fun currentStatus(): String =
            if (System.nanoTime() < statusUntilNanos) statusText else ""

        /** Wall-clock deadline (nanoTime) after which the help block stops rendering. */
        private var helpUntilNanos: Long = 0L

        /** How long the help block stays up; long enough to read five lines. */
        private const val HELP_DURATION_NANOS: Long = 6_000_000_000L

        /** Line height of one help row. */
        private const val HELP_LINE_H = 10

        /** Grouped help rows: each line is one group, with the keys and the effect separated. */
        private val HELP_ROWS = listOf(
            "PLAY      space = play / pause        M = mute",
            "SEEK      left / right = 5 s          shift + left / right = 15 s",
            "SEEK      0 - 9 = jump to 10% .. 90%",
            "VOLUME    up / down = 5%              shift + up / down = 10%",
            "PART      p2 = jump to part 2         (multi-part videos only)",
            "COMMAND   seek +30 | vol 50 | mute on | play | pause | exit",
        )

        /** Shows the grouped help block above the input bar. */
        fun showHelp() {
            helpUntilNanos = System.nanoTime() + HELP_DURATION_NANOS
        }

        /** The help rows to render right now, or an empty list when the block expired. */
        private fun helpLines(): List<String> =
            if (System.nanoTime() < helpUntilNanos) HELP_ROWS else emptyList()

        /**
         * Status line for the in-world HUD while no screen is open: watch-mode keys run with the
         * console closed, so their feedback cannot rely on this screen being drawn.
         */
        fun hudStatus(): String = currentStatus()

        /** Opens the console screen. */
        fun open() = openInternal(guardTypedChar = false)

        /** Opens the console on the next client task, dropping the character that trailed the opening key. */
        fun openDeferred() = Minecraft.getInstance().execute { openInternal(guardTypedChar = true) }

        /** Closes the console when it is the open screen; call sites notify it when Borderless goes away. */
        fun closeIfOpen() {
            val mc = Minecraft.getInstance()
            if (MinecraftScreenUtil.currentScreen(mc) is BorderlessConsoleScreen) {
                MinecraftScreenUtil.setScreen(mc, null)
            }
        }

        /** Creates the screen; [guardTypedChar] arms the one-character guard for the opening keystroke. */
        private fun openInternal(guardTypedChar: Boolean) {
            if (guardTypedChar) suppressTypedCharUntilNanos = System.nanoTime() + OPEN_CHAR_GUARD_NANOS
            MinecraftScreenUtil.setScreen(Minecraft.getInstance(), BorderlessConsoleScreen())
        }
    }
}
