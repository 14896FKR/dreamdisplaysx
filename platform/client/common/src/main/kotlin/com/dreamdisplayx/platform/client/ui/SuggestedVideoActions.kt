package com.dreamdisplayx.platform.client.ui

import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.display.service.keys.DisplayServices
import com.dreamdisplayx.api.media.search.model.MediaSearchResult
import com.dreamdisplayx.api.playback.model.PlaylistCommandAction
import com.dreamdisplayx.api.runtime.registry.service.get
import com.dreamdisplayx.core.protocol.common.packets.PlaylistAddItem
import com.dreamdisplayx.media.source.PastedMediaCards
import com.dreamdisplayx.media.source.bilibili.BilibiliApi
import com.dreamdisplayx.media.source.bilibili.BilibiliSearchItem
import com.dreamdisplayx.media.source.bilibili.BilibiliUserItem
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.dreamdisplayx.platform.client.managers.PlaylistImportStatus
import com.dreamdisplayx.platform.client.managers.PlaylistStateStore
import com.dreamdisplayx.platform.client.storage.CustomVideoStore
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** Shared "play this selected result" action for the menu and the uploader screen. */
internal object SuggestedVideoActions {
    fun pick(displayScreen: DisplayScreen, info: MediaSearchResult) {
        // A pasted media list or uploader space is not a video: route it to its own page.
        if (info.id.startsWith(PastedMediaCards.SPACE_PREFIX)) {
            openUploaderPage(displayScreen, info)
            return
        }
        if (info.id.startsWith(PastedMediaCards.MEDIA_LIST_PREFIX)) {
            importMediaList(displayScreen, info)
            return
        }
        if (!displayScreen.canSetVideoHere) return
        val url = info.getWatchUrl()

        val playlist = PlaylistStateStore.stateOf(displayScreen.uuid)
        if (playlist?.enabled == true && playlist.currentIndex < 0) {
            PlaylistStateStore.send(
                displayScreen.uuid, PlaylistCommandAction.ADD.wire, url = url, title = info.title,
            )
            if (info.isCustom) CustomVideoStore.remember(url, info.title)
            return
        }

        DreamServices.registry.get(DisplayServices.DISPLAY).setUrl(
            DisplayId(displayScreen.uuid), url, displayScreen.lang,
        )
        if (info.isCustom) CustomVideoStore.remember(url, info.title)
    }

    /** Opens the creator page for a pasted `space.bilibili.com/<mid>` link. */
    private fun openUploaderPage(displayScreen: DisplayScreen, info: MediaSearchResult) {
        val mid = info.id.removePrefix(PastedMediaCards.SPACE_PREFIX).toLongOrNull() ?: return
        BilibiliUploaderScreen.open(
            displayScreen,
            BilibiliUserItem(mid = mid, name = info.title, avatarUrl = info.thumbnailUrlOverride),
            "",
            returnToPlaylist = DisplayMenu.lastPlaylistTab,
        )
    }

    /** Pulls a favourites / media list into the display's queue, then opens the playlist tab. */
    private fun importMediaList(displayScreen: DisplayScreen, info: MediaSearchResult) {
        val mediaListId = info.id.removePrefix(PastedMediaCards.MEDIA_LIST_PREFIX).toLongOrNull() ?: return
        if (!displayScreen.canSetVideoHere) return
        PlaylistImportStatus.show(
            text("dreamdisplayx.playlist.importing", "Importing %s/%s", 0, 0), IMPORT_STATUS_HOLD_MS,
        )
        Thread {
            val list = runCatching { BilibiliApi.fetchMediaList(mediaListId) }.getOrNull()
            Minecraft.getInstance().execute {
                val playable = list?.items?.filter { !it.bvid.isNullOrBlank() }.orEmpty()
                if (playable.isEmpty()) {
                    val failed = text(
                        "dreamdisplayx.playlist.import_failed",
                        "Could not import that playlist: no playable video in it.",
                    )
                    PlaylistImportStatus.show(failed)
                    notify(Component.literal(failed))
                    return@execute
                }
                val capped = playable.take(MAX_IMPORT)
                enqueueBatch(displayScreen, capped, playable.size - capped.size)
                DisplayMenu.openWithPlaylistTab(displayScreen)
            }
        }.apply {
            isDaemon = true
            name = "ddx-media-list-import"
            start()
        }
    }

    /**
     * Queues [items] in chunks of [BATCH_SIZE]: one packet per chunk instead of one per video, with the
     * running count in the status strip. Servers that predate the batch action ignore it, so the queue is
     * re-checked afterwards and the list is resent item by item when nothing arrived.
     */
    private fun enqueueBatch(displayScreen: DisplayScreen, items: List<BilibiliSearchItem>, skipped: Int) {
        val lang = displayScreen.lang.orEmpty()
        val entries = items.mapNotNull { item ->
            val bvid = item.bvid ?: return@mapNotNull null
            PlaylistAddItem(
                url = "https://www.bilibili.com/video/$bvid",
                title = item.title,
                lang = lang,
            )
        }
        val total = entries.size
        val before = PlaylistStateStore.stateOf(displayScreen.uuid)?.items?.size ?: 0
        entries.chunked(BATCH_SIZE).forEachIndexed { index, chunk ->
            PlaylistStateStore.sendBatch(
                displayScreen.uuid, PlaylistCommandAction.ADD_MANY.wire, chunk, lang,
            )
            val done = minOf((index + 1) * BATCH_SIZE, total)
            PlaylistImportStatus.show(
                text("dreamdisplayx.playlist.importing", "Importing %s/%s", done, total), IMPORT_STATUS_HOLD_MS,
            )
        }
        val summary = if (skipped > 0) {
            text(
                "dreamdisplayx.playlist.import_truncated",
                "Added %s video(s); %s entries beyond the limit were skipped.",
                total, skipped,
            )
        } else {
            text("dreamdisplayx.playlist.imported", "Added %s video(s) to the playlist.", total)
        }
        PlaylistImportStatus.show(summary)
        notify(Component.literal(summary))
        verifyOrResend(displayScreen, entries, before)
    }

    /** Waits one server round trip, then resends the list item by item when the queue never grew. */
    private fun verifyOrResend(displayScreen: DisplayScreen, entries: List<PlaylistAddItem>, before: Int) {
        Thread {
            runCatching { Thread.sleep(LEGACY_CHECK_DELAY_MS) }
            Minecraft.getInstance().execute {
                val after = PlaylistStateStore.stateOf(displayScreen.uuid)?.items?.size ?: 0
                if (after > before) return@execute
                for (entry in entries) {
                    PlaylistStateStore.send(
                        displayScreen.uuid, PlaylistCommandAction.ADD.wire,
                        url = entry.url, lang = entry.lang, title = entry.title,
                    )
                }
            }
        }.apply {
            isDaemon = true
            name = "ddx-media-list-import-fallback"
            start()
        }
    }

    /** Resolves a translatable line to plain text for the status strip and the chat fallback. */
    private fun text(key: String, fallback: String, vararg args: Any): String =
        Component.translatableWithFallback(key, fallback, *args).string

    /** Shows [message] in chat, the only place a background import can report back to the player. */
    private fun notify(message: Component) {
        Minecraft.getInstance().player?.displayClientMessage(message, false)
    }

    /** Longest a single import status line stays up; progress updates re-arm it. */
    private const val IMPORT_STATUS_HOLD_MS = 60_000L

    /** Videos queued per packet; 300 entries cost six packets instead of 300. */
    private const val BATCH_SIZE = 50

    /** Hard cap per import, matching the server's own ADD_MANY limit. */
    private const val MAX_IMPORT = 300

    /** Grace period before the batch result is treated as "server ignored ADD_MANY". */
    private const val LEGACY_CHECK_DELAY_MS = 5_000L
}
