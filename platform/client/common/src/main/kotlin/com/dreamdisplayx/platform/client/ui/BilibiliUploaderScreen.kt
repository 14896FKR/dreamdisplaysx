package com.dreamdisplayx.platform.client.ui

import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.display.service.keys.DisplayServices
import com.dreamdisplayx.api.runtime.registry.service.get
import com.dreamdisplayx.media.source.bilibili.BilibiliUserItem
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.dreamdisplayx.platform.client.input.MouseButtons
import com.dreamdisplayx.platform.client.ui.kit.UiRect
import com.dreamdisplayx.platform.client.ui.kit.UiScreenBase
import com.dreamdisplayx.platform.client.ui.kit.UiTheme
import com.dreamdisplayx.platform.client.ui.widgets.IconButton
import com.dreamdisplayx.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
//? if >=1.21.11 {
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
//?}
import net.minecraft.network.chat.Component
//? if >=26.3 {
import com.mojang.blaze3d.platform.InputConstants
//?} else
/*import org.lwjgl.glfw.GLFW*/

//? if >=26.3 {
private fun isPageEnterKey(key: Int): Boolean =
    key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER

private fun isPageEscapeKey(key: Int): Boolean = key == InputConstants.KEY_ESCAPE
//?} else
/*private fun isPageEnterKey(key: Int): Boolean =
    key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER

private fun isPageEscapeKey(key: Int): Boolean = key == GLFW.GLFW_KEY_ESCAPE*/

/** Full-window uploader page. The search panel stays in DisplayMenu; this screen owns profile browsing. */
class BilibiliUploaderScreen(
    private val displayScreen: DisplayScreen,
    private val uploader: BilibiliUserItem,
    private val keyword: String,
    /** True when the page was opened from the playlist tab, so closing it returns to that tab. */
    private val returnToPlaylist: Boolean = false,
) : UiScreenBase(Component.translatable("dreamdisplayx.uploader_profile.title")) {

    private lateinit var panel: BilibiliUploaderPanel
    private lateinit var backButton: IconButton
    private lateinit var pageBox: EditBox

    override fun init() {
        super.init()
        panel = addUi(
            BilibiliUploaderPanel(uploader, keyword, onPick = { info ->
                SuggestedVideoActions.pick(displayScreen, info)
                DisplayMenu.open(displayScreen, playlistTab = returnToPlaylist)
            }, onPlayLive = ::playLiveRoom),
        )
        panel.place(UiRect(0, 0, width, height))
        backButton = addUi(IconButton("left") { goBack() })
        backButton.place(UiRect(12, 12, 22, 22))

        pageBox = EditBox(
            Minecraft.getInstance().font, 0, 0, 44, 18,
            Component.translatable("dreamdisplayx.uploader_profile.page_input"),
        )
        pageBox.setMaxLength(4)
        pageBox.setFilter { value -> value.all { it.isDigit() } }
        pageBox.setBordered(false)
        pageBox.setTextColor(UiTheme.TEXT_PRIMARY)
        pageBox.setValue("1")
        pageBox.visible = false
        addRenderableWidget(pageBox)
    }

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        panel.place(UiRect(0, 0, width, height))
        val pageRect = panel.pageInputRect()
        if (pageRect == null) {
            pageBox.visible = false
            if (editBoxHasFocus(pageBox)) setFocused(null)
        } else {
            pageBox.visible = true
            if (!editBoxHasFocus(pageBox)) pageBox.setValue(panel.pageNumber().toString())
            val textW = Minecraft.getInstance().font.width(pageBox.value)
            pageBox.width = (textW + 8).coerceAtLeast(12)
            pageBox.x = pageRect.centerX - textW / 2
            // Borderless EditBox draws its text at getY(), not centered by height.
            pageBox.y = pageRect.y + (pageRect.h - 8) / 2
            pageBox.height = pageRect.h
        }
        drawScreenBackground(g)
        drawChildren(g, mouseX, mouseY, partialTick)
    }

    //? if >=1.21.11 {
    override fun onMouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val rect = panel.pageInputRect()
        if (rect != null && MouseButtons.isLeft(event.button()) &&
            rect.contains(event.x().toInt(), event.y().toInt())
        ) {
            setFocused(pageBox)
            pageBox.mouseClicked(event, doubleClick)
            return true
        }
        return false
    }
    //?} else
    /*override fun onMouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val rect = panel.pageInputRect()
        if (rect != null && MouseButtons.isLeft(button) &&
            rect.contains(mouseX.toInt(), mouseY.toInt())
        ) {
            setFocused(pageBox)
            pageBox.mouseClicked(mouseX, mouseY, button)
            return true
        }
        return false
    }*/

    override fun onClose() {
        goBack()
    }

    override fun removed() {
        if (::panel.isInitialized) panel.close()
        super.removed()
    }

    override fun minContentSize(): Pair<Int, Int> = 1000 to 640

    private fun goBack() {
        // Return to the tab the page was opened from: a creator page reached from a playlist row,
        // the preview card or a search result must not dump the player on the display-settings tab.
        DisplayMenu.open(displayScreen, playlistTab = returnToPlaylist)
    }

    /** Switches the display to [roomId]'s live room; the page stays open so the player can compare. */
    private fun playLiveRoom(roomId: Long) {
        val url = "https://live.bilibili.com/$roomId"
        if (currentLiveRoomId() == roomId) return
        if (!displayScreen.canSetVideoHere) return
        DreamServices.registry.get(DisplayServices.DISPLAY).setUrl(
            DisplayId(displayScreen.uuid), url, displayScreen.lang,
        )
    }

    /** Room id of the live stream this display already plays, or 0 when it plays something else. */
    private fun currentLiveRoomId(): Long {
        val url = displayScreen.videoUrl ?: return 0L
        if (!url.contains("live.bilibili.com/")) return 0L
        return url.substringAfter("live.bilibili.com/").takeWhile { it.isDigit() }.toLongOrNull() ?: 0L
    }

    //? if >=1.21.11 {
    override fun keyPressed(event: KeyEvent): Boolean {
        if (editBoxHasFocus(pageBox)) {
            if (isPageEnterKey(event.key())) {
                panel.submitPageInput(pageBox.value)
                pageBox.setValue(panel.pageNumber().toString())
                return true
            }
            if (isPageEscapeKey(event.key())) {
                setFocused(null)
                return true
            }
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean =
        if (editBoxHasFocus(pageBox)) pageBox.charTyped(event) else super.charTyped(event)
    //?} else
    /*override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (editBoxHasFocus(pageBox)) {
            if (isPageEnterKey(keyCode)) {
                panel.submitPageInput(pageBox.value)
                pageBox.setValue(panel.pageNumber().toString())
                return true
            }
            if (isPageEscapeKey(keyCode)) {
                setFocused(null)
                return true
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(chr: Char, modifiers: Int): Boolean =
        if (editBoxHasFocus(pageBox)) pageBox.charTyped(chr, modifiers) else super.charTyped(chr, modifiers)*/

    companion object {
        fun open(
            displayScreen: DisplayScreen,
            uploader: BilibiliUserItem,
            keyword: String,
            returnToPlaylist: Boolean = false,
        ) {
            MinecraftScreenUtil.setScreen(
                Minecraft.getInstance(),
                BilibiliUploaderScreen(displayScreen, uploader, keyword, returnToPlaylist),
            )
        }
    }
}
