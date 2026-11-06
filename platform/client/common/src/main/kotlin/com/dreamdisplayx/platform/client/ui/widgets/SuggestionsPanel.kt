package com.dreamdisplayx.platform.client.ui.widgets

import com.dreamdisplayx.platform.client.utils.buttonClickSound
import com.dreamdisplayx.platform.client.utils.translatableText
import com.dreamdisplayx.api.media.search.model.MediaSearchResult
import com.dreamdisplayx.platform.client.input.MouseButtons
import com.dreamdisplayx.platform.client.render.FirstFrameThumbnails
import com.dreamdisplayx.platform.client.mixins.EditBoxCaretHold
import com.dreamdisplayx.platform.client.render.Thumbnails
import com.dreamdisplayx.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplayx.platform.client.ui.editBoxHasFocus
import com.dreamdisplayx.platform.client.ui.editBoxSetFocused
import com.dreamdisplayx.platform.client.ui.drawText
//? if <1.21.11 {
import com.dreamdisplayx.platform.client.ui.enableScissorPoseAware
//?}
import com.dreamdisplayx.platform.client.ui.kit.*
import com.dreamdisplayx.platform.client.ui.widgets.SuggestionsPanel.Companion.THUMB_H
//? if >=1.21.11 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
//? if >=1.21.11 {
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
//?}
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import net.minecraft.sounds.SoundEvents
//? if >=26.3 {
import com.mojang.blaze3d.platform.InputConstants
//?} else
/*import org.lwjgl.glfw.GLFW*/
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Scrollable search / related-videos panel: a search row (edit box + clear + search buttons) above a strip of result cards. */
class SuggestionsPanel(
    private val onPick: (MediaSearchResult) -> Unit,
    private val controller: SuggestionsController,
    private val onOpenUploader: (MediaSearchResult) -> Unit,
) : UiWidget(translatableText("dreamdisplayx.button.suggestions")) {

    init {
        controller.onResults = { scrollOffset = 0 }
    }

    private val searchBox: EditBox
    private val clearButton: IconButton
    private val sortButton: IconButton
    private val sortDropdown: BilibiliFilterDropdown = BilibiliFilterDropdown(
        current = { controller.bilibiliFilter },
        onSelect = { controller.setBilibiliFilter(it) },
    )
    private val searchButton: IconButton
    private var scopeRect = UiRect(0, 0, 0, 0)

    /** When this returns false the panel is locked: it shows an "unavailable" notice and ignores input. */
    var available: () -> Boolean = { true }

    private var scrollOffset: Int = 0
    private var hoveredCard: Int = -1

    /** Scrollbar geometry captured in [drawScrollbar] so the mouse handlers can drag the thumb. */
    private var sbActive = false
    private var sbVertical = true
    private var sbStart = 0        // track origin along the scroll axis (stripTop / stripLeft)
    private var sbViewport = 0     // track length along the scroll axis
    private var sbThumbLen = 0
    private var sbMaxOff = 0
    private var sbCross = 0        // the bar's fixed cross-axis coordinate (barX / barY)

    /** True while the user is dragging the scrollbar thumb. */
    private var draggingScrollbar = false
    private var vertical: Boolean = false
    private var compactCards: Boolean = false
    private var lastStripH: Int = CARD_H

    override fun handlesWholeWidgetCursor(): Boolean = false

    init {
        val f = Minecraft.getInstance().font
        searchBox = EditBox(
            f, 0, 0, 100, SEARCH_H,
            translatableText("dreamdisplayx.suggestions.search"),
        )
        //? if >=1.19.4 {
        searchBox.setHint(translatableText("dreamdisplayx.suggestions.search"))
        //?} else
        /*searchBox.setSuggestion(translatableText("dreamdisplayx.suggestions.search").string)*/
        searchBox.setMaxLength(200)
        // The controller owns the text: Minecraft rebuilds this panel (and so this EditBox) whenever
        // DisplayMenu.init runs, e.g. on a window resize, and the user's typed/pasted link must survive.
        searchBox.setValue(controller.searchText)
        searchBox.setResponder { controller.onSearchTextChanged(it) }
        controller.onRewriteSearchText = { cleaned ->
            if (searchBox.value != cleaned) {
                // setValue keeps the caret at the end (normal paste behaviour); then rewind only the
                // visible window so a long link shows its host instead of its tail.
                searchBox.setValue(cleaned)
                (searchBox as? EditBoxCaretHold)?.dreamdisplayxSetDisplayPos(0)
            }
        }
        clearButton = IconButton(icon = { IconButton.modIcon("cross") }, margin = 4) {
            controller.clearSearch()
            searchBox.setValue("")
            editBoxSetFocused(searchBox, true)
        }
        sortButton = IconButton(icon = { IconButton.modIcon("filter") }, margin = 4) {
            sortDropdown.toggle()
        }
        searchButton = IconButton(icon = { IconButton.modIcon("search") }, margin = 4) {
            searchBox.isFocused = true
            controller.runSearch(searchBox.value)
        }
    }

    /** Switches between vertical sidebar and horizontal strip card layout. */
    fun setVertical(v: Boolean) {
        vertical = v
    }

    /** Toggles compact cards (thumbnail only, no title/meta text). */
    fun setCompactCards(c: Boolean) {
        compactCards = c
    }

    /** Shows videos related to [videoId]; clears the panel when null. */
    fun setRelatedTo(videoId: String?) = controller.setRelatedTo(videoId)

    private fun searchRowY(): Int = y + 10 + HEADER_H + 6
    private fun stripTop(): Int = searchRowY() + SEARCH_H + 8
    private fun stripBottom(): Int = y + height - 10
    private fun stripLeft(): Int = x + 10
    private fun stripRight(): Int = x + width - 10

    /** Positions the search box and its action buttons for the current panel rect. */
    private fun layoutChildren() {
        val rowY = searchRowY()
        var searchLeft = x + 10
        scopeRect = UiRect(searchLeft, rowY, SCOPE_W, SEARCH_H)
        searchLeft += SCOPE_W + ACTION_GAP
        val actions = 3
        val reserved = ACTION_W * actions + ACTION_GAP * (actions - 1)
        searchBox.x = searchLeft
        searchBox.y = rowY
        val newWidth = max(40, x + width - 10 - reserved - searchLeft)
        if (searchBox.width != newWidth) {
            searchBox.width = newWidth
            // setValue ran at width 100; scrollTo only guarantees caret visibility, not a start reset.
            val caret = searchBox.cursorPosition
            searchBox.setCursorPosition(0)
            searchBox.setCursorPosition(caret)
        }
        val clearX = x + width - 10 - reserved
        clearButton.place(UiRect(clearX, rowY, ACTION_W, SEARCH_H))
        sortButton.place(UiRect(clearX + ACTION_W + ACTION_GAP, rowY, ACTION_W, SEARCH_H))
        searchButton.place(UiRect(x + width - 10 - ACTION_W, rowY, ACTION_W, SEARCH_H))
    }

    /** Draws the compact scope toggle used at the left of the search row. */
    private fun drawScopeButton(g: GuiGraphicsCompat, f: Font) {
        if (scopeRect.w <= 0) return
        val uploader = controller.searchScope == SearchScope.UPLOADER
        g.fill(scopeRect.x, scopeRect.y, scopeRect.right, scopeRect.bottom, UiTheme.CARD_BG)
        g.drawOutline(scopeRect, if (uploader) UiTheme.CARD_BORDER_HOVER else UiTheme.PANEL_BORDER)
        val label = Component.translatableWithFallback(
            if (uploader) "dreamdisplayx.suggestions.scope.uploader" else "dreamdisplayx.suggestions.scope.video",
            if (uploader) "UP" else "Video",
        ).string
        g.drawText(
            f,
            label,
            scopeRect.x + (scopeRect.w - f.width(label)) / 2,
            scopeRect.y + (scopeRect.h - f.lineHeight) / 2,
            if (uploader) UiTheme.TEXT_PRIMARY else UiTheme.TEXT_DIM,
            false,
        )
    }

    /** Card width for the current orientation/viewport. */
    private fun cardW(viewportW: Int): Int =
        if (vertical) max(CARD_W, viewportW) else dynCardW()

    /** Thumbnail height for the current orientation/viewport. */
    private fun thumbH(viewportW: Int): Int =
        if (vertical) max(THUMB_H, (cardW(viewportW) * 180.0 / 320.0).toInt()) else dynThumbH()

    /**
     * Card height for the current orientation / viewport. Text is always one title line plus one
     * meta line, so every card is the same height and the thumbnail keeps the remaining space.
     */
    private fun cardH(viewportW: Int): Int = when {
        !vertical -> dynCardH()
        compactCards -> THUMB_H + 4
        else -> thumbH(viewportW) + textBlockH()
    }

    /** Worst-case card height for call sites without a particular card in hand. */
    private fun maxCardH(viewportW: Int): Int = when {
        !vertical -> dynCardH()
        compactCards -> THUMB_H + 4
        else -> thumbH(viewportW) + textBlockH()
    }

    /**
     * Height [drawCard]'s one title line + one meta line needs, plus [BOTTOM_PAD] below it.
     */
    private fun textBlockH(): Int {
        val lh = Minecraft.getInstance().font.lineHeight
        return 4 + (lh + 1) + 1 + lh + BOTTOM_PAD
    }

    private fun dynThumbH(): Int {
        val available = lastStripH - 2 - 3 - CARD_TEXT_H - 2
        return max(30, min(THUMB_H, available))
    }

    private fun dynCardH(): Int = dynThumbH() + 2 + 3 + CARD_TEXT_H + 2

    private fun dynCardW(): Int {
        val th = dynThumbH()
        if (th >= THUMB_H) return CARD_W
        return max(80, (th * CARD_W / THUMB_H.toDouble()).toInt())
    }

    /** Total scrollable content extent along the scroll axis; every card shares one height. */
    private fun contentExtent(viewportW: Int): Int {
        val cards = controller.visibleCards
        if (cards.isEmpty()) return 0
        if (!vertical) return cards.size * (cardW(viewportW) + CARD_GAP) - CARD_GAP
        return cards.size * (cardH(viewportW) + CARD_GAP) - CARD_GAP
    }

    /** Maximum scroll offset for the current viewport. */
    private fun maxScroll(viewportW: Int, viewportH: Int): Int =
        max(0, contentExtent(viewportW) - if (vertical) viewportH else viewportW)

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        val r = UiRect(x, y, width, height)
        g.drawPanelSprite(r)

        val f = Minecraft.getInstance().font
        g.drawText(f, message, x + 10, y + 10, UiTheme.TEXT_PRIMARY, false)
        val resultCount = controller.visibleCards.size
        if (resultCount > 0) {
            val countText = Component.translatable("dreamdisplayx.suggestions.results", resultCount).string
            g.drawText(f, countText, x + width - 10 - f.width(countText), y + 10, UiTheme.TEXT_DIM, false)
        }

        if (!available()) {
            val notice = translatableText("dreamdisplayx.suggestions.unavailable").string
            g.drawText(f, notice, x + 10, stripTop() + 6, UiTheme.TEXT_SECONDARY, false)
            return
        }

        layoutChildren()
        drawScopeButton(g, f)
        searchBox.renderChild(g, mouseX, mouseY, partialTick)
        clearButton.renderChild(g, mouseX, mouseY, partialTick)
        sortButton.renderChild(g, mouseX, mouseY, partialTick)
        searchButton.renderChild(g, mouseX, mouseY, partialTick)

        val stripTop = stripTop()
        val stripBottom = stripBottom()
        val stripH = stripBottom - stripTop
        if (stripH < 40) {
            drawSortDropdown(g, mouseX, mouseY)
            return
        }
        lastStripH = stripH

        controller.statusKey?.let { key ->
            val base = translatableText(key).string
            val msg = if (controller.isLoading) {
                val elapsed = maxOf(0L, (System.currentTimeMillis() - controller.loadStartedAtMs) / 1000L)
                base.replace(Regex("\\.+$"), "") + " • " + elapsed + "s"
            } else base
            g.drawText(f, msg, x + 10, stripTop + 6, UiTheme.TEXT_SECONDARY, false)
            drawSortDropdown(g, mouseX, mouseY)
            return
        }

        val stripLeft = stripLeft()
        val stripRight = stripRight()
        val viewportW = stripRight - stripLeft
        val viewportH = stripBottom - stripTop
        val cw = cardW(viewportW)
        val th = thumbH(viewportW)
        val refCh = maxCardH(viewportW)
        val maxOff = maxScroll(viewportW, viewportH)
        scrollOffset = scrollOffset.coerceIn(0, maxOff)

        val cards = controller.visibleCards
        //? if <1.21.11 {
        g.enableScissorPoseAware(stripLeft, stripTop, stripRight, stripBottom)
        //?} else
        /*g.enableScissor(stripLeft, stripTop, stripRight, stripBottom)*/
        hoveredCard = -1
        val rowY = if (vertical) 0 else stripTop + max(0, (viewportH - refCh) / 2)
        var pos = (if (vertical) stripTop else stripLeft) - scrollOffset
        for (i in cards.indices) {
            val info = cards[i]
            val ch = cardH(viewportW)
            val cardX = if (vertical) stripLeft else pos
            val cardY = if (vertical) pos else rowY
            val visibleOnAxis = if (vertical) {
                cardY + ch >= stripTop && cardY <= stripBottom
            } else {
                cardX + cw >= stripLeft && cardX <= stripRight
            }
            if (visibleOnAxis) {
                val hover = mouseX >= max(cardX, stripLeft) && mouseX < min(cardX + cw, stripRight) &&
                        mouseY >= max(cardY, stripTop) && mouseY < min(cardY + ch, stripBottom)
                if (hover) hoveredCard = i
                drawCard(g, f, info, cardX, cardY, cw, th, ch, hover)
                if (cardThumbnail(info) == null) requestCardThumbnail(info)
                info.channelAvatarUrl?.let { url -> if (Thumbnails.get(url) == null) Thumbnails.request(url, url) }
            }
            pos += (if (vertical) ch else cw) + CARD_GAP
        }
        if (controller.isLoadingMore) {
            val phantomX = if (vertical) stripLeft else pos
            val phantomY = if (vertical) pos else rowY
            val visibleOnAxis = if (vertical) phantomY <= stripBottom else phantomX <= stripRight
            if (visibleOnAxis) {
                g.drawShimmer(
                    phantomX, phantomY, phantomX + cw, phantomY + refCh,
                    UiTheme.PLACEHOLDER_BG, UiTheme.PLACEHOLDER_SHIMMER,
                )
            }
        }
        g.disableScissor()
        // Fires once the user has scrolled within one viewport of the end of the loaded cards; cheap
        // to call every frame since loadMoreIfNeeded() no-ops while a page is already in flight or the
        // list is exhausted. Horizontal strips scroll along the width axis, vertical ones along height.
        val reach = if (vertical) viewportH else viewportW
        if (cards.isNotEmpty() && scrollOffset >= maxOff - reach) {
            controller.loadMoreIfNeeded()
        }
        //? if >=1.21.11 {
        if (hoveredCard in cards.indices) g.requestCursor(CursorTypes.POINTING_HAND)
        //?}

        drawScrollbar(g, stripLeft, stripTop, stripRight, stripBottom, maxOff, viewportW, viewportH)
        //? if >=1.21.11 {
        if (draggingScrollbar || overScrollbar(mouseX.toDouble(), mouseY.toDouble())) {
            g.requestCursor(if (sbVertical) CursorTypes.RESIZE_NS else CursorTypes.RESIZE_EW)
        }
        //?}
        drawSortDropdown(g, mouseX, mouseY)
    }

    /** Draws the sort dropdown last so it layers on top of the card strip below the search row. */
    private fun drawSortDropdown(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int) {
        if (!sortButton.visible) return
        sortDropdown.draw(g, sortButton.x + sortButton.width / 2, sortButton.y + sortButton.height, mouseX, mouseY)
    }

    /**
     * Re-issues the sort dropdown's own draw call after the whole screen has finished its normal
     * widget pass.
     */
    fun redrawSortDropdownOnTop(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int) {
        if (!visible) return
        drawSortDropdown(g, mouseX, mouseY)
    }

    /** Draws the thin scrollbar along the scroll axis when content overflows. */
    private fun drawScrollbar(
        g: GuiGraphicsCompat,
        stripLeft: Int, stripTop: Int, stripRight: Int, stripBottom: Int,
        maxOff: Int, viewportW: Int, viewportH: Int,
    ) {
        if (maxOff <= 0) {
            sbActive = false
            return
        }
        sbActive = true
        sbVertical = vertical
        sbMaxOff = maxOff
        if (vertical) {
            val content = maxOff + viewportH
            val barX = stripRight + 1
            g.fill(barX, stripTop, barX + 2, stripBottom, UiTheme.SCROLLBAR_TRACK)
            val barH = max(20, (viewportH.toFloat() / content * viewportH).toInt())
            val barY = stripTop + (scrollOffset.toFloat() / maxOff * (viewportH - barH)).toInt()
            g.fill(barX, barY, barX + 2, barY + barH, UiTheme.SCROLLBAR_THUMB)
            sbStart = stripTop; sbViewport = viewportH; sbThumbLen = barH; sbCross = barX
        } else {
            val content = maxOff + viewportW
            val barY = stripBottom + 1
            g.fill(stripLeft, barY, stripRight, barY + 2, UiTheme.SCROLLBAR_TRACK)
            val barW = max(20, (viewportW.toFloat() / content * viewportW).toInt())
            val barX = stripLeft + (scrollOffset.toFloat() / maxOff * (viewportW - barW)).toInt()
            g.fill(barX, barY, barX + barW, barY + 2, UiTheme.SCROLLBAR_THUMB)
            sbStart = stripLeft; sbViewport = viewportW; sbThumbLen = barW; sbCross = barY
        }
    }

    /** True when ([mx], [my]) is over the (thin) scrollbar column / row, within a forgiving grab margin. */
    private fun overScrollbar(mx: Double, my: Double): Boolean {
        if (!sbActive) return false
        val along = if (sbVertical) my else mx
        val cross = if (sbVertical) mx else my
        return along >= sbStart && along <= sbStart + sbViewport &&
                cross >= sbCross - SB_GRAB && cross <= sbCross + 2 + SB_GRAB
    }

    /** Maps a cursor position along the scroll axis to [scrollOffset], centering the thumb on it. */
    private fun scrollFromPos(pos: Double) {
        val travel = sbViewport - sbThumbLen
        if (travel <= 0) {
            scrollOffset = 0
            return
        }
        val rel = (pos - sbStart - sbThumbLen / 2.0).coerceIn(0.0, travel.toDouble())
        scrollOffset = ((rel / travel) * sbMaxOff).roundToInt().coerceIn(0, sbMaxOff)
    }

    /**
     * The registered card thumbnail for [info]. Results carrying a [MediaSearchResult.thumbnailUrlOverride]
     * (e.g. Twitch) were registered under the raw id via the direct-URL path, so they're read from that
     * same slot ([Thumbnails.get] defaults to the raw-key HIGH tier); YouTube ids use the id-derived LOW tier.
     */
    private fun cardThumbnail(info: MediaSearchResult) = when {
        // An overridden thumbnail (Twitch / Vimeo / Kick) is registered under the raw id key
        info.thumbnailUrlOverride != null -> Thumbnails.get(info.id)
        // Only real YouTube results derive an image from the id; everything else has none to show
        info.isYouTubeResult -> Thumbnails.get(info.id, Thumbnails.Quality.LOW)
        // A direct link gets a locally extracted first frame, registered under the raw id key
        info.isCustom -> Thumbnails.get(info.id)
        else -> null
    }

    /** True when [info]'s thumbnail fetch (via whichever key [cardThumbnail] reads) has already failed. */
    private fun cardThumbnailFailed(info: MediaSearchResult): Boolean = when {
        info.thumbnailUrlOverride != null -> Thumbnails.isFailed(info.id)
        info.isYouTubeResult -> Thumbnails.isFailed(info.id, Thumbnails.Quality.LOW)
        info.isCustom -> Thumbnails.isFailed(info.id)
        else -> false
    }

    /** Requests [info]'s card thumbnail via the path matching [cardThumbnail]'s key, if not already loaded. */
    private fun requestCardThumbnail(info: MediaSearchResult) {
        val url = info.thumbnailUrlOverride
        val watchUrl = info.watchUrlOverride
        when {
            url != null -> Thumbnails.request(info.id, url)
            info.isYouTubeResult -> Thumbnails.request(info.id, Thumbnails.Quality.LOW)
            // A pasted direct link has no platform thumbnail: extract the video's first frame
            // instead. Guarded by READY/FAILED so finished extraction (success or failure) is never
            // re-attempted; coalescing inside FirstFrameThumbnails dedupes in-flight renders.
            info.isCustom && watchUrl != null ->
                if (Thumbnails.get(info.id) == null && !Thumbnails.isFailed(info.id)) {
                    FirstFrameThumbnails.request(info.id, watchUrl) { jpeg ->
                        if (jpeg != null) Thumbnails.registerBytes(info.id, jpeg)
                    }
                }
        }
    }

    /** Draws one result card: hover-pulsing background, thumbnail, platform/duration tags, title, and meta. */
    private fun drawCard(
        g: GuiGraphicsCompat, f: Font, info: MediaSearchResult,
        x: Int, y: Int, w: Int, thumbH: Int, cardH: Int, hover: Boolean,
    ) {
        if (info.isUploader) {
            drawUploaderCard(g, f, info, x, y, w, cardH, hover)
            return
        }
        // On hover, tint the card to the video's own dominant color (ambient), falling back to the
        // neutral grey highlight until the thumbnail (and thus its average color) has decoded.
        val ambient = if (hover) Thumbnails.averageColor(info.id) else null
        val hoverBg = ambient?.let { (0xC0 shl 24) or (darkenRgb(it, 0.45f) and 0x00FFFFFF) } ?: UiTheme.CARD_BG_HOVER
        val hoverBorder = ambient?.let { lightenRgb(it, 0.40f) } ?: UiTheme.CARD_BORDER_HOVER
        g.fill(x, y, x + w, y + cardH, if (hover) hoverBg else UiTheme.CARD_BG)

        // Full-bleed thumbnail across the whole card width (no side inset): it's the largest the
        // card can hold and the width/THUMB_H ratio matches 16:9, so the image stays crisp and
        // un-stretched instead of being shrunk into a bordered box.
        val thumbX = x
        val thumbY = y
        val thumbW = w
        val thumb = cardThumbnail(info)
        // A card still has an image on the way when it is a custom-art card, a YouTube result whose
        // thumbnail is downloading, or a platform result carrying a thumbnail URL. Anything else has
        // nothing to load, so a shimmer would promise an image that never arrives - draw the plate.
        // A URL that has already failed (e.g. a Kick CDN 403) counts the same way - it is never
        // coming, so keep showing an eternal shimmer would be a lie.
        val awaitingThumbnail =
            (info.isYouTubeResult || info.thumbnailUrlOverride != null) && !cardThumbnailFailed(info)
        if (thumb != null) {
            blitTexture(g, thumb, thumbX, thumbY, thumbW, thumbH)
        } else if (info.isCustom || !awaitingThumbnail) {
            drawCustomCardArt(g, f, info, thumbX, thumbY, thumbW, thumbH)
        } else {
            // Animated shimmer while the thumbnail is still downloading, instead of a dead black box.
            g.drawShimmer(
                thumbX, thumbY, thumbX + thumbW, thumbY + thumbH,
                UiTheme.PLACEHOLDER_BG, UiTheme.PLACEHOLDER_SHIMMER,
            )
        }

        // Keep the thumbnail readable: only a platform tag is shown, not a generic recency badge.
        val badge = PlatformBadge.forResult(info)
        if (badge != null) {
            drawCardTag(
                g,
                f,
                translatableText(badge.labelKey).string,
                badge.bgColor,
                badge.textColor,
                thumbX,
                thumbY
            )
        }
        val unionBadge = PlatformBadge.unionBadge(info)
        if (unionBadge != null) {
            drawRightCardTag(
                g,
                f,
                translatableText(unionBadge.labelKey).string,
                unionBadge.bgColor,
                unionBadge.textColor,
                thumbX,
                thumbY,
                thumbW,
            )
        }

        val dur = info.formatDuration()
        if (dur.isNotEmpty()) {
            // Soft bottom-up gradient across the thumbnail (YouTube-style) instead of a hard black
            // box, so the duration reads on any frame without an ugly rectangle.
            val scrimH = (thumbH * 2) / 5
            g.fillVGradient(
                thumbX, thumbY + thumbH - scrimH, thumbX + thumbW, thumbY + thumbH,
                0x00000000, 0xB0000000.toInt(),
            )
            val dx = thumbX + thumbW - f.width(dur) - 4
            val dy = thumbY + thumbH - f.lineHeight - 3
            g.drawText(f, dur, dx, dy, UiTheme.TEXT_PRIMARY, true)
        }

        // Compact cards have no text block below the thumbnail; keep the title visible on the
        // cover itself so a failed/absent thumbnail never leaves an unidentified black card.
        if (compactCards || cardH <= thumbH + 6) {
            val scrimH = 18
            g.fillVGradient(
                thumbX, thumbY + thumbH - scrimH, thumbX + thumbW, thumbY + thumbH,
                0x00000000, 0xCC000000.toInt(),
            )
            val titleW = thumbW - 8 - (if (dur.isNotEmpty()) f.width(dur) + 6 else 0)
            val title = UiText.trim(f, info.title, titleW.coerceAtLeast(24))
            g.drawText(f, title, thumbX + 4, thumbY + thumbH - f.lineHeight - 3, UiTheme.TEXT_PRIMARY, true)
        }

        // Draw the hover frame over the full card (including the full-bleed thumbnail's edges) so the
        // selection reads as one crisp outline instead of an animated flicker.
        if (hover) g.drawOutline(UiRect(x, y, w, cardH), hoverBorder)

        if (compactCards) return

        val textX = x + 4
        val textW = w - 8
        var textY = thumbY + thumbH + 4
        g.drawText(f, UiText.trim(f, info.title, textW), textX, textY, UiTheme.TEXT_PRIMARY, true)
        textY += f.lineHeight + 1
        textY += 1
        val meta = if (info.isUploader) {
            buildList {
                info.uploaderFans?.let {
                    add(Component.translatableWithFallback(
                        "dreamdisplayx.suggestions.fans", "%s fans", compactCount(it),
                    ).string)
                }
                info.uploaderVideoCount?.let {
                    add(Component.translatableWithFallback(
                        "dreamdisplayx.suggestions.videos", "%s videos", compactCount(it),
                    ).string)
                }
                if (isEmpty()) info.uploader?.let(::add)
            }.joinToString(" · ")
        } else {
            var value = info.uploader ?: ""
            val views = info.formatViews()
            if (views.isNotEmpty()) {
                value = if (value.isEmpty()) views
                else UiText.trim(f, value, max(20, textW - f.width(" • $views"))) + " • " + views
            }
            value
        }
        if (meta.isNotEmpty()) {
            var metaX = textX
            var metaW = textW
            val avatar = info.channelAvatarUrl?.let { Thumbnails.get(it) }
            if (avatar != null) {
                val iconSize = f.lineHeight
                blitTexture(g, avatar, metaX, textY - 1, iconSize, iconSize)
                metaX += iconSize + 3
                metaW -= iconSize + 3
            }
            if (info.isVerified) {
                val badgeSize = f.lineHeight - 1
                g.drawVerifiedBadge(metaX, textY - 1, badgeSize, UiTheme.ACCENT)
                metaX += badgeSize + 3
                metaW -= badgeSize + 3
            }
            g.drawText(f, UiText.trim(f, meta, metaW), metaX, textY, UiTheme.TEXT_META, true)
        }
    }

    /** Uploader results use a square avatar and bottom-anchored name/meta instead of a 16:9 video card. */
    private fun drawUploaderCard(
        g: GuiGraphicsCompat,
        f: Font,
        info: MediaSearchResult,
        x: Int,
        y: Int,
        w: Int,
        cardH: Int,
        hover: Boolean,
    ) {
        g.fill(x, y, x + w, y + cardH, if (hover) UiTheme.CARD_BG_HOVER else UiTheme.CARD_BG)
        val textW = w - 8
        val meta = buildList {
            info.uploaderFans?.let {
                add(Component.translatableWithFallback(
                    "dreamdisplayx.suggestions.fans", "%s fans", compactCount(it),
                ).string)
            }
            info.uploaderVideoCount?.let {
                add(Component.translatableWithFallback(
                    "dreamdisplayx.suggestions.videos", "%s videos", compactCount(it),
                ).string)
            }
            if (isEmpty()) info.uploader?.let(::add)
        }.joinToString(" · ")
        val metaY = y + cardH - f.lineHeight - 4
        val nameY = metaY - f.lineHeight - 2
        val avatarSize = min(w - 16, (cardH - 2 * f.lineHeight - 14).coerceAtLeast(24))
        val avatarX = x + (w - avatarSize) / 2
        val avatarY = y + 2
        val avatar = Thumbnails.get(info.id)
        if (avatar != null) {
            blitAvatar(g, avatar, info.id, avatarX, avatarY, avatarSize)
        } else {
            g.drawShimmer(
                avatarX, avatarY, avatarX + avatarSize, avatarY + avatarSize,
                UiTheme.PLACEHOLDER_BG, UiTheme.PLACEHOLDER_SHIMMER,
            )
            info.thumbnailUrlOverride?.let { Thumbnails.request(info.id, it) }
        }
        g.drawOutline(UiRect(avatarX - 1, avatarY - 1, avatarSize + 2, avatarSize + 2), UiTheme.PANEL_BORDER)
        val shownName = UiText.trim(f, info.title, textW)
        g.drawText(f, shownName, x + 4, nameY, UiTheme.TEXT_PRIMARY, true)
        if (info.isVerified) {
            g.drawVerifiedBadge(x + 4 + f.width(shownName) + 3, nameY, f.lineHeight - 1, UiTheme.ACCENT)
        }
        if (meta.isNotBlank()) {
            g.drawText(f, UiText.trim(f, meta, textW), x + 4, metaY, UiTheme.TEXT_META, true)
        }
        if (hover) g.drawOutline(UiRect(x, y, w, cardH), UiTheme.CARD_BORDER_HOVER)
    }

    /** Draws a small top-left tag ([label]) on a [bg] plate with [textColor] text. */
    private fun drawCardTag(
        g: GuiGraphicsCompat,
        f: Font,
        label: String,
        bg: Int,
        textColor: Int,
        thumbX: Int,
        thumbY: Int
    ) {
        val tw = f.width(label) + 6
        val tagH = f.lineHeight + 4
        g.fill(thumbX + 2, thumbY + 2, thumbX + 2 + tw, thumbY + 2 + tagH, bg)
        // No drop shadow: the tag already sits on its own solid plate, and a shadow only muddies
        // dark-on-bright tags (Vimeo / Kick) into a smeared double-stroke look. Matches PreviewSection's
        // badge, which never shadowed its text.
        g.drawText(f, label, thumbX + 5, thumbY + 4, textColor, false)
    }

    /** Draws a small top-right tag so it never covers the platform/access tag at the top-left. */
    private fun drawRightCardTag(
        g: GuiGraphicsCompat,
        f: Font,
        label: String,
        bg: Int,
        textColor: Int,
        thumbX: Int,
        thumbY: Int,
        thumbW: Int,
    ) {
        val tw = f.width(label) + 6
        val tagH = f.lineHeight + 4
        val tagX = thumbX + thumbW - tw - 2
        g.fill(tagX, thumbY + 2, tagX + tw, thumbY + 2 + tagH, bg)
        g.drawText(f, label, tagX + 3, thumbY + 4, textColor, false)
    }

    /**
     * Stand-in card art for a card with no thumbnail to load - a custom link, or a platform result
     * whose metadata carried none: a soft vertical plate with the source's host / uploader centered
     * on it, so the card still carries the one fact worth knowing at a glance instead of a dead box.
     */
    private fun drawCustomCardArt(
        g: GuiGraphicsCompat, f: Font, info: MediaSearchResult,
        x: Int, y: Int, w: Int, h: Int,
    ) {
        g.fillVGradient(x, y, x + w, y + h, UiTheme.CUSTOM_ART_TOP, UiTheme.CUSTOM_ART_BOTTOM)
        val host = info.uploader.orEmpty().ifEmpty { return }
        val label = UiText.trim(f, host, w - 8)
        g.drawText(f, label, x + (w - f.width(label)) / 2, y + (h - f.lineHeight) / 2, UiTheme.TEXT_SECONDARY, true)
    }

    private fun compactCount(value: Long): String = when {
        value >= 1_000_000_000L -> String.format("%.1fB", value / 1_000_000_000.0)
        value >= 1_000_000L -> String.format("%.1fM", value / 1_000_000.0)
        value >= 1_000L -> String.format("%.1fK", value / 1_000.0)
        else -> value.toString()
    }

    private fun blitTexture(g: GuiGraphicsCompat, id: Identifier, x: Int, y: Int, w: Int, h: Int) {
        //? if >=1.21.11 {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, w, h)
        //?} else
        /*g.blit(id, x, y, 0f, 0f, w, h, w, h)*/
    }

    /** Center-crops an uploader avatar so a non-square source never gets flattened into the card. */
    private fun blitAvatar(g: GuiGraphicsCompat, id: Identifier, key: String, x: Int, y: Int, size: Int) {
        val dims = Thumbnails.dimensions(key)
        val sourceW = dims?.first?.takeIf { it > 0 } ?: size
        val sourceH = dims?.second?.takeIf { it > 0 } ?: size
        val side = min(sourceW, sourceH)
        val u = (sourceW - side) / 2f
        val v = (sourceH - side) / 2f
        //? if >=1.21.11 {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, size, size, side, side, sourceW, sourceH)
        //?} else
        /*g.blit(id, x, y, size, size, u, v, side, side, sourceW, sourceH)*/
    }

    //? if >=1.20.2 {
    override fun mouseScrolled(mouseX: Double, mouseY: Double, dx: Double, dy: Double): Boolean {
        //?} else
        /*override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean =
            mouseScrolled(mouseX, mouseY, 0.0, delta)

        fun mouseScrolled(mouseX: Double, mouseY: Double, dx: Double, dy: Double): Boolean {*/
        if (!available()) return false
        if (!isMouseOver(mouseX, mouseY)) return false
        val stripTop = stripTop()
        val stripBottom = stripBottom()
        if (mouseY < stripTop || mouseY > stripBottom) return false
        val viewportW = stripRight() - stripLeft()
        val maxOff = maxScroll(viewportW, stripBottom - stripTop)
        val axis =
            if (vertical) dy
            else if (dx != 0.0)
                //? if >=26.3 {
                -dx
                //?} else
                /*dx*/
            else dy
        val delta = axis * 32
        scrollOffset = (scrollOffset - delta.toInt()).coerceIn(0, maxOff)
        return true
    }

    //? if >=1.21.11 {
    override fun mouseClicked(event: MouseButtonEvent, dbl: Boolean): Boolean {
        if (!available()) return false
        val mouseX = event.x()
        val mouseY = event.y()
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        val onSortButton = sortButton.isMouseOver(mouseX, mouseY)
        if (sortDropdown.visible && MouseButtons.isLeft(event.button()) && !onSortButton && sortDropdown.handleClick(
                mx,
                my
            )
        ) return true
        if (scopeRect.contains(mx, my)) {
            controller.setSearchScope(
                if (controller.searchScope == SearchScope.UPLOADER) SearchScope.VIDEO else SearchScope.UPLOADER,
            )
            return true
        }
        if (clearButton.isMouseOver(mouseX, mouseY)) return clearButton.mouseClicked(event, dbl)
        if (onSortButton) return sortButton.mouseClicked(event, dbl)
        if (searchButton.isMouseOver(mouseX, mouseY)) return searchButton.mouseClicked(event, dbl)
        if (searchBox.isMouseOver(mouseX, mouseY)) {
            val handled = searchBox.mouseClicked(event, dbl)
            editBoxSetFocused(searchBox, true)
            return handled
        }
        editBoxSetFocused(searchBox, false)
        if (MouseButtons.isLeft(event.button()) && overScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true
            scrollFromPos(if (sbVertical) mouseY else mouseX)
            return true
        }
        // Right-clicking a remembered link removes it from "My links"; a plain left-click plays it.
        if (MouseButtons.isRight(event.button()) && forgetCardAt(mx, my)) return true
        val card = if (MouseButtons.isLeft(event.button())) cardAt(mouseX, mouseY) else -1
        if (card in controller.visibleCards.indices) {
            val s = SimpleSoundInstance.forUI(buttonClickSound(), 1.0f)
            Minecraft.getInstance().soundManager.play(s)
            val result = controller.visibleCards[card]
            if (result.isUploader) onOpenUploader(result) else onPick(result)
            return true
        }
        return false
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (draggingScrollbar) {
            scrollFromPos(if (sbVertical) event.y() else event.x())
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (draggingScrollbar) {
            draggingScrollbar = false
            return true
        }
        return super.mouseReleased(event)
    }
    //?} else
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!available()) return false
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        val onSortButton = sortButton.isMouseOver(mouseX, mouseY)
        if (sortDropdown.visible && MouseButtons.isLeft(button) && !onSortButton && sortDropdown.handleClick(mx, my)) return true
        if (scopeRect.contains(mx, my)) {
            controller.setSearchScope(
                if (controller.searchScope == SearchScope.UPLOADER) SearchScope.VIDEO else SearchScope.UPLOADER,
            )
            return true
        }
        if (clearButton.isMouseOver(mouseX, mouseY)) return clearButton.mouseClicked(mouseX, mouseY, button)
        if (onSortButton) return sortButton.mouseClicked(mouseX, mouseY, button)
        if (searchButton.isMouseOver(mouseX, mouseY)) return searchButton.mouseClicked(mouseX, mouseY, button)
        if (searchBox.isMouseOver(mouseX, mouseY)) {
            val handled = searchBox.mouseClicked(mouseX, mouseY, button)
            editBoxSetFocused(searchBox, true)
            return handled
        }
        editBoxSetFocused(searchBox, false)
        if (MouseButtons.isLeft(button) && overScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true
            scrollFromPos(if (sbVertical) mouseY else mouseX)
            return true
        }
        // Right-clicking a remembered link removes it from "My links"; a plain left-click plays it.
        if (MouseButtons.isRight(button) && forgetCardAt(mx, my)) return true
        val card = if (MouseButtons.isLeft(button)) cardAt(mouseX, mouseY) else -1
        if (card in controller.visibleCards.indices) {
            val s = SimpleSoundInstance.forUI(buttonClickSound(), 1.0f)
            Minecraft.getInstance().soundManager.play(s)
            val result = controller.visibleCards[card]
            if (result.isUploader) onOpenUploader(result) else onPick(result)
            return true
        }
        return false
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (draggingScrollbar) {
            scrollFromPos(if (sbVertical) mouseY else mouseX)
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (draggingScrollbar) {
            draggingScrollbar = false
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }*/

    /**
     * Handles a right-click at ([mx], [my]): forgets the remembered custom link under the cursor,
     * with a soft click. Returns true only when a forgettable card was actually hit, so a
     * right-click that lands on a normal search result falls through untouched.
     */
    private fun forgetCardAt(mx: Int, my: Int): Boolean {
        val card = cardAt(mx.toDouble(), my.toDouble())
        val result = controller.visibleCards.getOrNull(card) ?: return false
        if (!controller.forgetCustom(result)) return false
        val s = SimpleSoundInstance.forUI(buttonClickSound(), 0.7f)
        Minecraft.getInstance().soundManager.play(s)
        return true
    }

    private fun cardAt(mouseX: Double, mouseY: Double): Int {
        if (controller.statusKey != null) return -1
        val stripTop = stripTop()
        val stripBottom = stripBottom()
        val stripLeft = stripLeft()
        val stripRight = stripRight()
        if (mouseX < stripLeft || mouseX >= stripRight || mouseY < stripTop || mouseY >= stripBottom) return -1

        val viewportW = stripRight - stripLeft
        val viewportH = stripBottom - stripTop
        if (viewportW <= 0 || viewportH <= 0) return -1

        val cw = cardW(viewportW)
        val rowY = if (vertical) 0 else stripTop + max(0, (viewportH - maxCardH(viewportW)) / 2)
        var pos = (if (vertical) stripTop else stripLeft) - scrollOffset
        for ((i, info) in controller.visibleCards.withIndex()) {
            val ch = cardH(viewportW)
            val cardX = if (vertical) stripLeft else pos
            val cardY = if (vertical) pos else rowY
            if (mouseX >= max(cardX, stripLeft) && mouseX < min(cardX + cw, stripRight) &&
                mouseY >= max(cardY, stripTop) && mouseY < min(cardY + ch, stripBottom)
            ) {
                return i
            }
            pos += (if (vertical) ch else cw) + CARD_GAP
        }
        return -1
    }

    /** True while the search box has keyboard focus, so screen-level hotkeys must stand aside. */
    val isTyping: Boolean get() = editBoxHasFocus(searchBox)

    //? if >=1.21.11 {
    override fun keyPressed(event: KeyEvent): Boolean {
        if (!available()) return super.keyPressed(event)
        if (editBoxHasFocus(searchBox)) {
            if (event.key() ==
                //? if >=26.3 {
                InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER
                //?} else
                /*GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER*/
            ) {
                controller.runSearch(searchBox.value)
                return true
            }
            return searchBox.keyPressed(event)
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (editBoxHasFocus(searchBox)) return searchBox.charTyped(event)
        return super.charTyped(event)
    }
    //?} else
    /*override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!available()) return super.keyPressed(keyCode, scanCode, modifiers)
        if (editBoxHasFocus(searchBox)) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                controller.runSearch(searchBox.value)
                return true
            }
            return searchBox.keyPressed(keyCode, scanCode, modifiers)
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(chr: Char, modifiers: Int): Boolean {
        if (editBoxHasFocus(searchBox)) return searchBox.charTyped(chr, modifiers)
        return super.charTyped(chr, modifiers)
    }*/

    companion object {
        private const val HEADER_H = 14

        /** Extra px around the thin scrollbar that still grabs it (a forgiving drag target). */
        private const val SB_GRAB = 4
        private const val CARD_GAP = 6
        private const val CARD_W = 152
        private const val CARD_TEXT_H = 24
        private const val THUMB_H = 86
        private const val BOTTOM_PAD = 4
        private const val CARD_H = THUMB_H + CARD_TEXT_H
        private const val SEARCH_H = 22
        private const val ACTION_W = SEARCH_H
        private const val ACTION_GAP = 4
        private const val SCOPE_W = 34

        /** Internal vertical paddings a card adds around its thumbnail + text (see [dynThumbH]/[dynCardH]). */
        private const val CARD_INNER_PAD = 2 + 3 + 2

        /**
         * Vertical space the panel spends on its title + search row before the card strip begins,
         * plus the bottom padding below it. Keep in sync with [stripTop]/[stripBottom].
         */
        const val STRIP_CHROME_H = 10 + HEADER_H + 6 + SEARCH_H + 8 + 10

        /** Strip viewport height at which horizontal cards reach their full [THUMB_H] thumbnails. */
        const val FULL_CARD_VIEWPORT_H = CARD_H + CARD_INNER_PAD

        /** Smallest strip viewport that still shows a card (min 30px thumbnail) without clipping it. */
        const val MIN_CARD_VIEWPORT_H = 30 + CARD_TEXT_H + CARD_INNER_PAD
    }
}
