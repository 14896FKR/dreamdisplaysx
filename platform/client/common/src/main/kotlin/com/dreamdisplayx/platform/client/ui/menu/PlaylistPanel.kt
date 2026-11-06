package com.dreamdisplayx.platform.client.ui.menu

import com.dreamdisplayx.platform.client.ui.editBoxHasFocus
import com.dreamdisplayx.platform.client.ui.editBoxSetFocused
import com.dreamdisplayx.platform.client.utils.translatableText
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.playback.model.PlaylistCommandAction
import com.dreamdisplayx.api.playback.model.PlaylistEndBehavior
import com.dreamdisplayx.api.playback.model.PlaylistEnqueuePolicy
import com.dreamdisplayx.core.protocol.common.packets.PlaylistItem
import com.dreamdisplayx.media.source.bilibili.BilibiliMetadataCache
import com.dreamdisplayx.media.source.bilibili.BilibiliUserItem
import com.dreamdisplayx.media.source.platform.PlatformVideoMetadata
import com.dreamdisplayx.platform.client.managers.PlaylistImportStatus
import com.dreamdisplayx.platform.client.managers.PlaylistStateStore
import com.dreamdisplayx.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplayx.platform.client.ui.kit.drawOutline
import com.dreamdisplayx.platform.client.ui.drawText
import com.dreamdisplayx.platform.client.ui.kit.UiRect
import com.dreamdisplayx.platform.client.ui.kit.UiTheme
import com.dreamdisplayx.platform.client.ui.kit.UiText
import com.dreamdisplayx.platform.client.ui.kit.UiWidget
import com.dreamdisplayx.platform.client.ui.widgets.IconButton
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * The playlist panel shown in the menu's "Playlist" tab. Renders the server-authoritative queue
 * ([PlaylistStateStore]), lets the player add a URL, reorder / remove / skip items, and the owner
 * change the two queue policies plus the end-of-queue behavior. Every mutation is a
 * [PlaylistCommand] to the server; the echo snapshot re-renders this panel.
 */
class PlaylistPanel(
    private val displayId: UUID,
    private val isOwnerOrAdmin: () -> Boolean,
) : UiWidget(translatableText("dreamdisplayx.ui.playlist")) {

    private val font = Minecraft.getInstance().font
    private val urlBox = EditBox(font, 0, 0, 100, 18, translatableText("dreamdisplayx.ui.playlist_add_hint"))
    private val titleBox = EditBox(font, 0, 0, 100, 18, translatableText("dreamdisplayx.ui.playlist_title_hint"))
    private val addButton = IconButton("add") { submit() }
    private val nextButton = IconButton("right") {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.NEXT.wire)
    }
    private val clearButton = IconButton("delete") { toggleBulkMode() }
    private val policyButton = IconButton("lock") { togglePolicy() }
    private val endBehaviorButton = IconButton("pause") { toggleEndBehavior() }
    private val modeButton = IconButton(
        icon = { IconButton.modIcon(if (modeEnabled()) "play" else "pause") },
    ) { toggleMode() }

    /** Scroll offset in pixels over the queue rows. */
    private var scroll = 0

    /** Bilibili metadata keys already requested for this panel; avoids a request on every frame. */
    private val metadataRequested = HashSet<String>()

    init {
        urlBox.setMaxLength(512)
        titleBox.setMaxLength(64)
    }

    /** Children that must receive vanilla input / render passes (URL box, buttons). */
    val children: List<net.minecraft.client.gui.components.AbstractWidget>
        get() = listOf(urlBox, titleBox, addButton, nextButton, clearButton, policyButton, endBehaviorButton, modeButton)

    private fun state() = PlaylistStateStore.stateOf(displayId)

    /** Whether playlist mode is on (defaults to on while no snapshot has arrived yet). */
    private fun modeEnabled(): Boolean = state()?.enabled != false

    private fun toggleMode() {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.SET_ENABLED.wire, enabled = !modeEnabled())
    }

    private fun togglePolicy() {
        val s = state() ?: return
        val next = when (PlaylistEnqueuePolicy.fromWire(s.enqueuePolicy)) {
            PlaylistEnqueuePolicy.EVERYONE -> PlaylistEnqueuePolicy.OWNER_APPROVAL
            PlaylistEnqueuePolicy.OWNER_APPROVAL -> PlaylistEnqueuePolicy.OWNER_ONLY
            else -> PlaylistEnqueuePolicy.EVERYONE
        }
        PlaylistStateStore.send(displayId, PlaylistCommandAction.SET_ENQUEUE_POLICY.wire, enqueuePolicy = next.wire)
    }

    private fun toggleEndBehavior() {
        val s = state() ?: return
        val next = when (PlaylistEndBehavior.fromWire(s.endBehavior)) {
            PlaylistEndBehavior.PAUSE -> PlaylistEndBehavior.CONTINUE
            PlaylistEndBehavior.CONTINUE -> PlaylistEndBehavior.LOOP_CURRENT
            else -> PlaylistEndBehavior.PAUSE
        }
        PlaylistStateStore.send(displayId, PlaylistCommandAction.SET_END_BEHAVIOR.wire, endBehavior = next.wire)
    }

    /** Adds [urlBox]'s content to the queue; the title box content is used as its display title. */
    private fun submit() {
        val url = urlBox.value.trim()
        if (url.isEmpty()) return
        PlaylistStateStore.send(
            displayId,
            PlaylistCommandAction.ADD.wire,
            url = url,
            title = titleBox.value.trim(),
        )
        urlBox.value = ""
        titleBox.value = ""
    }

    /** Cached Bilibili metadata for a queue row, or null while it is still unknown. */
    private fun itemMetadata(item: PlaylistItem): PlatformVideoMetadata? {
        val source = bilibiliSource(item) ?: return null
        val key = BilibiliMetadataCache.cacheKey(source) ?: return null
        return BilibiliMetadataCache.get(key)
    }

    /** Uploader / views / duration from the Bilibili metadata cache; platform names are redundant here. */
    private fun itemInfo(item: PlaylistItem): String = itemInfo(itemMetadata(item))

    /** Same as [itemInfo] for an already-resolved [meta]. */
    private fun itemInfo(meta: PlatformVideoMetadata?): String {
        if (meta == null) return ""
        val parts = buildList {
            meta.uploader?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it.take(18)) }
            meta.viewCount?.takeIf { it > 0 }?.let { add(formatViewCount(it) + "播放") }
            meta.durationSec?.takeIf { it > 0 }?.let { add(formatDuration(it)) }
        }
        return parts.joinToString(" · ")
    }

    /**
     * Uploader hover target of the last drawn frame: pressing V over the uploader part of a row opens
     * that creator's page, mirroring the preview overlay's uploader click.
     */
    private var hoverUploader: BilibiliUserItem? = null

    /** The uploader under the cursor, or null when the cursor is elsewhere. */
    fun uploaderUnderCursor(): BilibiliUserItem? = hoverUploader

    /** The parsed Bilibili source for a queue row, or null for non-Bilibili URLs. */
    private fun bilibiliSource(item: PlaylistItem): MediaSource.Bilibili? =
        runCatching { MediaSource.from(item.url) }.getOrNull() as? MediaSource.Bilibili

    /** Warms metadata once per item so the row can show uploader / views / duration on the next frame. */
    private fun requestItemMetadata(item: PlaylistItem) {
        val source = bilibiliSource(item) ?: return
        val key = BilibiliMetadataCache.cacheKey(source) ?: return
        if (BilibiliMetadataCache.get(key) != null) return
        if (metadataRequested.add(key)) BilibiliMetadataCache.requestAsync(source)
    }

    /** [BilibiliUserItem] for a queue row's creator, or null when the row has no Bilibili uploader. */
    private fun uploaderUser(item: PlaylistItem): BilibiliUserItem? {
        val meta = itemMetadata(item) ?: return null
        val mid = meta.uploaderId ?: return null
        return BilibiliUserItem(
            mid = mid,
            name = meta.uploader?.trim().orEmpty(),
            avatarUrl = meta.uploaderAvatarUrl,
        )
    }

    /** Compact Bilibili-style view count in Chinese units, without a trailing decimal. */
    private fun formatViewCount(views: Long): String = when {
        views >= 100_000_000 -> "${views / 100_000_000}亿"
        views >= 10_000 -> "${views / 10_000}万"
        else -> views.toString()
    }

    /** Formats seconds as m:ss or h:mm:ss for the queue row. */
    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
        else "%d:%02d".format(minutes, secs)
    }

    /** Plays the item at [index] immediately (owner / admin only, enforced server-side). */
    private fun skipTo(item: PlaylistItem) {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.SKIP_TO.wire, itemId = item.itemId)
    }

    private fun remove(item: PlaylistItem) {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.REMOVE.wire, itemId = item.itemId)
    }

    private fun approve(item: PlaylistItem) {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.APPROVE.wire, itemId = item.itemId)
    }

    private fun reject(item: PlaylistItem) {
        // Owners reject pending requests; requesters cancel their own pending rows. The server
        // intentionally exposes cancellation as REMOVE, not REJECT, to keep approval semantics owner-only.
        val action = if (isOwnerOrAdmin()) PlaylistCommandAction.REJECT else PlaylistCommandAction.REMOVE
        PlaylistStateStore.send(displayId, action.wire, itemId = item.itemId)
    }

    private fun move(item: PlaylistItem, to: Int) {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.MOVE.wire, itemId = item.itemId, position = to)
    }

    /** Places this panel into [panel] and draws it. Version-neutral, mirroring [SettingsSection.render]. */
    fun render(g: GuiGraphicsCompat, panel: UiRect, mouseX: Int, mouseY: Int) {
        place(panel)
        draw(g, mouseX, mouseY, 0f)
    }

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        hoverUploader = null
        hoverTitle = null
        bulkMouseX = mouseX
        bulkMouseY = mouseY
        // Vanilla child widgets are drawn through the screen's child list (DisplayMenu adds them);
        // this method paints the panel's own chrome: rows, labels, scrollbar.
        val s = state()

        val innerX = x + UiTheme.PANEL_PADDING_X
        val innerW = width - UiTheme.PANEL_PADDING_X * 2

        // Top bar: mode / next / clear / policy / end-behavior buttons on the right, labels on the left.
        val topY = y + 4
        val btnH = 16
        val btnGap = 2
        endBehaviorButton.place(UiRect(x + width - UiTheme.PANEL_PADDING_X - btnH, topY, btnH, btnH))
        policyButton.place(UiRect(x + width - UiTheme.PANEL_PADDING_X - (btnH + btnGap) * 2, topY, btnH, btnH))
        clearButton.place(UiRect(x + width - UiTheme.PANEL_PADDING_X - (btnH + btnGap) * 3, topY, btnH, btnH))
        nextButton.place(UiRect(x + width - UiTheme.PANEL_PADDING_X - (btnH + btnGap) * 4, topY, btnH, btnH))
        modeButton.place(UiRect(x + width - UiTheme.PANEL_PADDING_X - (btnH + btnGap) * 5, topY, btnH, btnH))

        // Playlist mode state always shows (even before the first snapshot arrives), so the owner can
        // flip it without waiting for a queue echo.
        val modeOn = modeEnabled()
        g.drawText(
            font,
            translatableText(
                if (modeOn) "dreamdisplayx.ui.playlist_mode_on" else "dreamdisplayx.ui.playlist_mode_off",
            ).string,
            innerX, topY - 2, if (modeOn) 0xFF7BD389.toInt() else UiTheme.TEXT_DIM, false,
        )

        // Add row: URL box + optional title box + add button, right under the top bar. Placed before
        // the state check so the input row stays put even while the first snapshot is still in flight.
        val addY = topY + btnH + font.lineHeight * 2 + 6
        val boxH = 16
        urlBox.x = innerX
        urlBox.y = addY
        urlBox.width = innerW - (boxH + 2) - 90
        titleBox.x = urlBox.x + urlBox.width + 2
        titleBox.y = addY
        titleBox.width = 88
        addButton.place(UiRect(titleBox.x + titleBox.width + 2, addY, boxH, boxH))

        drawImportStatus(g)
        if (bulkMode) {
            val barY = topY + btnH + font.lineHeight * 2 + 6 + 18
            drawBulkBar(g, innerX, barY, innerW)
        }

        if (s == null) {
            g.drawText(
                font, translatableText("dreamdisplayx.ui.playlist_empty").string,
                innerX, addY + boxH + 8, UiTheme.TEXT_DIM, false,
            )
            return
        }

        val policy = PlaylistEnqueuePolicy.fromWire(s.enqueuePolicy)
        val policyText = translatableText(
            when (policy) {
                PlaylistEnqueuePolicy.EVERYONE -> "dreamdisplayx.ui.playlist_policy_everyone"
                PlaylistEnqueuePolicy.OWNER_APPROVAL -> "dreamdisplayx.ui.playlist_policy_approval"
                else -> "dreamdisplayx.ui.playlist_policy_owner"
            },
        )
        val endBehavior = PlaylistEndBehavior.fromWire(s.endBehavior)
        val endText = translatableText(
            when (endBehavior) {
                PlaylistEndBehavior.PAUSE -> "dreamdisplayx.ui.playlist_end_pause"
                PlaylistEndBehavior.CONTINUE -> "dreamdisplayx.ui.playlist_end_continue"
                else -> "dreamdisplayx.ui.playlist_end_loop"
            },
        )
        // Two stacked mini-labels on the left under the mode line: policy (top) and end behavior (bottom).
        g.drawText(font, policyText.string, innerX, topY + font.lineHeight - 2, UiTheme.TEXT_DIM, false)
        g.drawText(font, endText.string, innerX, topY + font.lineHeight * 2 - 2, UiTheme.TEXT_DIM, false)

        // Queue rows.
        val rowsTop = rowsTopY()
        val rowH = 18
        val rowCount = s.items.size
        val visibleRows = max(0, (y + height - UiTheme.PANEL_PADDING_Y - rowsTop) / rowH)
        val maxScroll = max(0, rowCount - visibleRows)
        scroll = scroll.coerceIn(0, maxScroll)
        val playing = s.currentIndex

        for (vi in 0 until visibleRows) {
            val index = vi + scroll
            if (index >= rowCount) break
            val item = s.items[index]
            requestItemMetadata(item)
            val rowY = rowsTop + vi * rowH
            val rowRect = UiRect(innerX, rowY, innerW, rowH - 2)

            val isPlaying = index == playing
            val ticked = bulkMode && item.itemId in selected
            val bgColor = when {
                ticked -> 0x403A6EA5
                isPlaying -> UiTheme.ACTIVE_ROW_FILL
                item.pending -> 0x30A0A000.toInt()
                rowRect.contains(mouseX, mouseY) -> UiTheme.HOVER_FILL
                else -> 0
            }
            if (bgColor != 0) g.fill(rowRect.x, rowRect.y, rowRect.right, rowRect.bottom, bgColor)

            val label = when {
                isPlaying -> "▶ "
                item.pending -> "⏳ "
                else -> ""
            } + (item.title.ifBlank { item.url }).take(60)

            // Pending rows need the requester for approval; normal rows show platform + duration.
            val tagText = if (item.pending) {
                translatableText("dreamdisplayx.ui.playlist_by").string +
                    item.requesterName.ifBlank { item.requesterId.toString().take(8) }
            } else {
                itemInfo(item)
            }
            // Meta keeps its natural width (title yields to it); it is only trimmed in the extreme
            // case of being wider than the row itself, so the duration never disappears.
            val tagShown = if (font.width(tagText) <= innerW - 8) tagText
            else UiText.trim(font, tagText, innerW - 8)
            val tagW = font.width(tagShown)
            val tagX = rowRect.right - tagW - ROW_ACTION_RESERVE
            g.drawText(font, tagShown, tagX, rowRect.y + 4, UiTheme.TEXT_DIM, false)

            var titleX = rowRect.x + 2
            if (bulkMode) {
                g.drawText(
                    font, if (ticked) "☑" else "☐", titleX, rowRect.y + 4,
                    if (ticked) UiTheme.ACCENT else UiTheme.TEXT_SECONDARY, false,
                )
                titleX += font.width("☑ ") + 2
            }
            val availW = max(0, tagX - 4 - titleX)
            val hovered = rowRect.contains(mouseX, mouseY)
            g.drawText(
                font, UiText.trim(font, label, availW), titleX, rowRect.y + 4,
                if (item.pending) UiTheme.TEXT_DIM else UiTheme.TEXT_PRIMARY, false,
            )
            if (hovered && font.width(label) > availW) hoverTitle = label to rowRect
            // The uploader part of the meta column doubles as a "creator page" hotspot for the V key.
            val uploaderName = itemMetadata(item)?.uploader?.trim()
            if (hovered && !item.pending && !uploaderName.isNullOrEmpty()) {
                val uploaderW = min(font.width(uploaderName), tagW)
                if (uploaderW > 0 && mouseX in tagX until tagX + uploaderW && mouseY in rowRect.y until rowRect.bottom) {
                    hoverUploader = uploaderUser(item)
                }
            }

            // Per-row actions: approve / reject for pending rows (owner), move up / down + remove
            // for the rest. Left-to-right order: ▲ ▼ ✕ (remove stays rightmost as before).
            val canAct = isOwnerOrAdmin() || item.requesterId == clientPlayerId()
            if (canAct && hovered) {
                val ax = rowRect.right - 16
                if (item.pending) {
                    // Approve on the left of reject.
                    g.drawText(font, "✓", ax - 14, rowRect.y + 4, 0xFF55FF55.toInt(), false)
                    g.drawText(font, "✕", ax, rowRect.y + 4, 0xFFFF5555.toInt(), false)
                } else {
                    if (index > 0) g.drawText(font, "▲", ax - 28, rowRect.y + 4, UiTheme.TEXT_DIM, false)
                    if (index < s.items.size - 1) g.drawText(font, "▼", ax - 14, rowRect.y + 4, UiTheme.TEXT_DIM, false)
                    g.drawText(font, "✕", ax, rowRect.y + 4, 0xFFFF5555.toInt(), false)
                }
            }
        }

        hoverTitle?.let { (text, row) -> drawTitleTooltip(g, text, row) }
        hoverTitle = null

        // Pending rows also render drag arrows for the owner (simple move up / down).
        drawScrollbar(g, innerX + innerW + 2, rowsTop, (visibleRows * rowH).coerceAtMost(height), rowCount, maxScroll)

        // Keep vanilla children visible only when this panel is on screen.
        val visible = width > 0 && height > 0
        urlBox.visible = visible
        titleBox.visible = visible
        addButton.visible = visible
        nextButton.visible = visible
        clearButton.visible = visible
        policyButton.visible = visible
        endBehaviorButton.visible = visible
    }

    /** Row whose title did not fit; drawn as a hover tooltip after the queue rows. */
    private var hoverTitle: Pair<String, UiRect>? = null

    /** Draws [text] in a small plate under (or above) [row] so a clipped title stays readable. */
    private fun drawTitleTooltip(g: GuiGraphicsCompat, text: String, row: UiRect) {
        val padX = 4
        val w = min(font.width(text) + padX * 2, width - 8)
        val h = font.lineHeight + 6
        val tx = row.x.coerceIn(x + 4, max(x + 4, row.right - w))
        val below = row.bottom + 2
        val ty = if (below + h <= y + height - 2) below else (row.y - h - 2).coerceAtLeast(y + 2)
        g.fill(tx, ty, tx + w, ty + h, 0xF0101418.toInt())
        g.drawOutline(UiRect(tx, ty, w, h), UiTheme.PANEL_BORDER)
        g.drawText(font, UiText.trim(font, text, w - padX * 2), tx + padX, ty + 3, UiTheme.TEXT_PRIMARY, true)
    }

    private companion object {
        /** Room kept right of the meta tag for the row actions (they start at right-44). */
        private const val ROW_ACTION_RESERVE = 50

        /** Vertical space reserved for the import status strip while it is visible. */
        private const val STATUS_H = 12

        /** Height of the bulk-selection bar, which shares the import strip's slot. */
        private const val BULK_H = 15

        /** How long the delete link stays armed for its second, confirming click. */
        private const val BULK_CONFIRM_WINDOW_NANOS = 4_000_000_000L

        /** Bulk-bar link ids. */
        private const val BULK_ALL = "all"
        private const val BULK_DELETE = "delete"
        private const val BULK_CANCEL = "cancel"

        /** Hover wash for bulk-bar links. */
        private const val PART_HOVER = 0x50FFFFFF
    }

    /** The local player's id, or a nil UUID when no player is in a world. */
    private fun clientPlayerId(): UUID =
        Minecraft.getInstance().player?.uuid ?: UUID(0L, 0L)

    /** Simple track-only scrollbar. */
    private fun drawScrollbar(g: GuiGraphicsCompat, barX: Int, top: Int, viewH: Int, count: Int, maxScroll: Int) {
        if (maxScroll <= 0) return
        g.fill(barX, top, barX + 3, top + viewH, UiTheme.SCROLLBAR_TRACK)
        val len = max(14, (viewH.toFloat() * viewH / (count * 18)).toInt())
        val start = top + (scroll.toFloat() / maxScroll * (viewH - len)).toInt()
        g.fill(barX, start, barX + 3, start + len, UiTheme.SCROLLBAR_THUMB)
    }

    /** Click handling in virtual coordinates; returns true when consumed. */
    fun handleClick(mx: Int, my: Int): Boolean {
        if (bulkMode) {
            val link = bulkHitRects.firstOrNull { it.first.contains(mx, my) }?.second
            if (link != null) {
                when (link) {
                    BULK_ALL -> selectAllRows()
                    BULK_DELETE -> deleteSelectedRows()
                    BULK_CANCEL -> toggleBulkMode()
                }
                return true
            }
        }
        val s = state() ?: return false
        // Per-row action buttons (same header geometry as [draw]).
        val rowsTop = rowsTopY()
        val rowH = 18
        // Only the rows that are actually painted may swallow clicks: the row index below is derived
        // from the click y, so without this bound a click past the panel border landed on a row that
        // is scrolled out of sight and silently skipped the queue.
        val visibleRows = max(0, (y + height - UiTheme.PANEL_PADDING_Y - rowsTop) / rowH)
        val vi = (my - rowsTop) / rowH
        if (vi !in 0 until visibleRows) return false
        val idx = vi + scroll
        if (idx in s.items.indices) {
            val item = s.items[idx]
            val innerX = x + UiTheme.PANEL_PADDING_X
            val innerW = width - UiTheme.PANEL_PADDING_X * 2
            val rowRect = UiRect(innerX, rowsTop + vi * rowH, innerW, rowH - 2)
            if (rowRect.contains(mx, my)) {
                // Bulk mode: a click anywhere on the row ticks it instead of jumping playback.
                if (bulkMode) {
                    if (!selected.remove(item.itemId)) selected += item.itemId
                    bulkConfirmUntilNanos = 0L
                    return true
                }
                val ax = rowRect.right - 16
                if (item.pending && mx >= ax - 14 && mx <= ax - 4) {
                    if (isOwnerOrAdmin()) approve(item) else return false
                    return true
                }
                if (item.pending && mx >= ax && mx <= ax + 10) {
                    if (isOwnerOrAdmin() || item.requesterId == clientPlayerId()) reject(item)
                    return true
                }
                if (!item.pending) {
                    // ▲ (ax-28..ax-18) and ▼ (ax-14..ax-4) sit left of the ✕ glyph (ax..ax+10).
                    if (isOwnerOrAdmin() && mx >= ax - 28 && mx <= ax - 18 && idx > 0) {
                        move(item, idx - 1)
                        return true
                    }
                    if (isOwnerOrAdmin() && mx >= ax - 14 && mx <= ax - 4 && idx < s.items.size - 1) {
                        move(item, idx + 1)
                        return true
                    }
                    if (mx >= ax && mx <= ax + 10) {
                        if (isOwnerOrAdmin() || item.requesterId == clientPlayerId()) remove(item)
                        return true
                    }
                }
                // Plain click elsewhere on the row: owner / admin skips to it.
                if (isOwnerOrAdmin()) {
                    skipTo(item)
                    return true
                }
            }
        }
        return false
    }

    /** Ticked queue rows; the panel is in bulk mode while this is set up. */
    private val selected = LinkedHashSet<UUID>()

    /** True while the bulk bar is open (the trash button toggles it). */
    private var bulkMode = false

    /** [System.nanoTime] deadline for the "click again" delete confirmation. */
    private var bulkConfirmUntilNanos = 0L

    /** Text links drawn in the bulk bar this frame: rect to action id. */
    private val bulkHitRects = mutableListOf<Pair<UiRect, String>>()

    /** Cursor position from the last draw pass, for the bulk-bar link hover. */
    private var bulkMouseX = 0
    private var bulkMouseY = 0

    /** Opens / closes the bulk bar, dropping any selection and pending confirmation. */
    private fun toggleBulkMode() {
        bulkMode = !bulkMode
        selected.clear()
        bulkConfirmUntilNanos = 0L
    }

    /** Ticks every row, or clears the selection when everything is already ticked. */
    private fun selectAllRows() {
        val items = state()?.items ?: return
        if (selected.size >= items.size) selected.clear() else items.forEach { selected += it.itemId }
        bulkConfirmUntilNanos = 0L
    }

    /** Deletes the ticked rows; the first press only arms the confirmation. */
    private fun deleteSelectedRows() {
        if (selected.isEmpty()) return
        val now = System.nanoTime()
        if (now > bulkConfirmUntilNanos) {
            bulkConfirmUntilNanos = now + BULK_CONFIRM_WINDOW_NANOS
            return
        }
        selected.forEach { PlaylistStateStore.send(displayId, PlaylistCommandAction.REMOVE.wire, itemId = it) }
        selected.clear()
        bulkConfirmUntilNanos = 0L
    }

    /** Draws the bulk bar: selection count, select-all, delete (armed on the first press) and exit. */
    private fun drawBulkBar(g: GuiGraphicsCompat, left: Int, top: Int, rowW: Int) {
        bulkHitRects.clear()
        val items = state()?.items.orEmpty()
        val armed = System.nanoTime() <= bulkConfirmUntilNanos
        val head = translatableText("dreamdisplayx.ui.playlist_bulk_selected", selected.size, items.size).string
        g.drawText(font, head, left, top, UiTheme.TEXT_PRIMARY, false)
        var linkX = left + font.width(head) + 8
        fun link(label: String, action: String, color: Int) {
            val w = font.width(label) + 6
            if (linkX + w > left + rowW) return
            val rect = UiRect(linkX, top - 2, w, font.lineHeight + 4)
            val hovered = rect.contains(bulkMouseX, bulkMouseY)
            if (hovered) g.fill(rect.x, rect.y, rect.right, rect.bottom, PART_HOVER)
            g.drawText(font, label, linkX + 3, top, color, false)
            bulkHitRects += rect to action
            linkX += w + 4
        }
        val allLabel = translatableText(
            if (selected.size >= items.size && items.isNotEmpty()) "dreamdisplayx.ui.playlist_bulk_none"
            else "dreamdisplayx.ui.playlist_bulk_all",
        ).string
        link(allLabel, BULK_ALL, UiTheme.ACCENT)
        val deleteLabel = translatableText(
            if (armed) "dreamdisplayx.ui.playlist_bulk_confirm" else "dreamdisplayx.ui.playlist_bulk_delete",
            selected.size,
        ).string
        link(deleteLabel, BULK_DELETE, if (armed) 0xFFFF5555.toInt() else UiTheme.TEXT_PRIMARY)
        link(translatableText("dreamdisplayx.ui.playlist_bulk_cancel").string, BULK_CANCEL, UiTheme.TEXT_DIM)
    }

    /** Mouse-wheel scroll; returns true when consumed. */
    fun handleScroll(mouseX: Int, mouseY: Int, scrollY: Double): Boolean {
        val rowsTop = rowsTopY()
        // The wheel only scrolls this panel when the cursor is over it: without the x bound the queue
        // scrolled while the cursor sat on the video preview beside it.
        if (mouseX < x || mouseX > x + width) return false
        if (mouseY < rowsTop || mouseY > y + height) return false
        scroll = (scroll - scrollY.toInt()).coerceAtLeast(0)
        return true
    }

    /** Top of the queue rows, matching the header geometry in [draw]. */
    private fun rowsTopY(): Int {
        val topY = y + 4
        val btnH = 16
        val addY = topY + btnH + font.lineHeight * 2 + 6
        val status = if (PlaylistImportStatus.current().isEmpty()) 0 else STATUS_H
        val bulk = if (bulkMode) BULK_H else 0
        return addY + 16 + 6 + max(status, bulk)
    }

    /** Draws the one-line import status strip under the URL row; the rows move down while it shows. */
    private fun drawImportStatus(g: GuiGraphicsCompat) {
        val line = PlaylistImportStatus.current()
        if (line.isEmpty()) return
        val topY = y + 4
        val addY = topY + 16 + font.lineHeight * 2 + 6
        g.drawText(font, UiText.trim(font, line, width - UiTheme.PANEL_PADDING_X * 2), x + UiTheme.PANEL_PADDING_X, addY + 18, UiTheme.ACCENT_BILIBILI_TAG, false)
    }

    /** Focuses the URL box when the tab opens. */
    fun focusInput() {
        editBoxSetFocused(urlBox, true)
        urlBox.setValue("")
    }

    /** Drops keyboard focus from both boxes; called when this tab is hidden. */
    fun releaseFocus() {
        editBoxSetFocused(urlBox, false)
        editBoxSetFocused(titleBox, false)
    }

    /**
     * True when either text box currently holds keyboard focus. A hidden box cannot be typing: the
     * tab only toggles visibility, and a box focused before the switch kept swallowing every hotkey.
     */
    fun isTyping(): Boolean =
        (urlBox.visible && editBoxHasFocus(urlBox)) || (titleBox.visible && editBoxHasFocus(titleBox))
}
