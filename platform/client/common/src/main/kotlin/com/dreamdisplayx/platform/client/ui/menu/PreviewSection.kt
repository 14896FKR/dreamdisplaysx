package com.dreamdisplayx.platform.client.ui.menu

import com.dreamdisplayx.platform.client.utils.translatableText
import com.dreamdisplayx.platform.client.utils.identifier
//? if >=1.21.11 {
import net.minecraft.client.renderer.RenderPipelines
//?}
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplayx.api.media.source.url.CustomMediaUrls
import com.dreamdisplayx.api.media.source.model.MediaPlatform
import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.display.service.keys.DisplayServices
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.runtime.registry.service.get
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.media.source.bilibili.BilibiliMetadataCache
import com.dreamdisplayx.media.source.bilibili.BilibiliUserItem
import com.dreamdisplayx.media.source.kick.KickMetadataCache
import com.dreamdisplayx.media.source.platform.PlatformVideoMetadata
import com.dreamdisplayx.media.source.twitch.TwitchMetadataCache
import com.dreamdisplayx.media.source.vimeo.VimeoMetadataCache
import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.console.PlaybackClock
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.dreamdisplayx.platform.client.render.*
import com.dreamdisplayx.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplayx.platform.client.ui.drawText
import com.dreamdisplayx.platform.client.ui.kit.*
import com.dreamdisplayx.platform.client.ui.widgets.IconButton
import com.dreamdisplayx.platform.client.ui.widgets.SeekBar
import com.dreamdisplayx.platform.client.ui.widgets.ValueSlider
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The preview panel of the display menu: live video (or thumbnail while loading), the title/metadata
 * overlay strip, and the playback controls row (mute, volume, progress bar, popout + its dropdown,
 * pause). Owns only drawing and per-frame placement; the widgets themselves live on the screen.
 */
class PreviewSection(
    private val ds: DisplayScreen,
    private val muteButton: IconButton,
    private val volume: ValueSlider,
    private val popoutButton: IconButton,
    private val audioTrackButton: IconButton,
    private val subtitleButton: IconButton,
    private val subtitleSecondaryButton: IconButton,
    private val danmakuButton: IconButton,
    private val pauseButton: IconButton,
    private val progress: SeekBar,
    private val dropdown: PopoutDropdown,
    private val audioTrackDropdown: AudioTrackDropdown,
    private val subtitleDropdown: SubtitleDropdown,
    private val subtitleSecondaryDropdown: SubtitleDropdown,
    /** Opens the uploader page for the video's creator; null leaves the uploader chip inert. */
    private val onOpenUploader: ((BilibiliUserItem) -> Unit)? = null,
) {
    // Owned by DisplayScreen (not this section) so the last decoded frame — and its GPU texture —
    // survive closing and reopening the menu instead of needing a fresh push before showing anything.
    private val yuvPreview: PreviewFrameTexture = ds.previewFrameTexture()
    private val ambientSampler = AmbientFrameSampler(ds)
    private var frameSinkAttached = false
    private var lastVideoUrl: String? = null

    /** Screen-space rect of the uploader chip (avatar + name), recorded while drawing. */
    private var uploaderHitRect: UiRect? = null

    /** Creator behind [uploaderHitRect]; null when the current source has no creator page. */
    private var uploaderHitUser: BilibiliUserItem? = null

    // Starts at the CURRENT state rather than always 0 — otherwise every menu reopen replays the
    // grow-in animation even when the track count was already known and settled from a previous
    // session, which reads as the menu "always refreshing" something that hasn't actually changed.
    private var audioPresence = if (ds.audioTrackList.size > 1) 1f else 0f
    private var subtitlePresence = if (ds.subtitleTrackList.isNotEmpty()) 1f else 0f
    private var subtitleSecondaryPresence = if (ds.subtitlesEnabled && ds.subtitleTrackList.size > 1) 1f else 0f
    private var lastPresenceFrameNanos = 0L

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger("DreamDisplaysX/PreviewSection")

        /** Chip sentinels: toggling the part list, and paging it, rather than a part number. */
        private const val PART_LIST_TOGGLE = -2
        private const val PART_PAGE_BACK = -1
        private const val PART_PAGE_FORWARD = 0

        /** Rows the part list shows at once before it pages. */
        private const val PART_LIST_ROWS = 8

        /** Widest the part list popup may get, so long titles do not stretch it across the menu. */
        private const val PART_LIST_MAX_W = 236

        /** Hover wash for picker rows; heavier than the theme-wide one so it reads on the dark popup. */
        private const val PART_HOVER_FILL = 0x50FFFFFF

        /** Enables the FPS debug label on the preview video (top-left) via -Ddreamdisplayx.debugFps=true. */
        private val DEBUG_FPS: Boolean =
            System.getProperty("dreamdisplayx.debugFps", "false").toBoolean()

        /** Aspect ratio of a YouTube thumbnail image, independent of the screen's own block shape. */
        private const val THUMBNAIL_RATIO = 16f / 9f

        /** Width of the volume slider in the controls row. */
        private const val VOLUME_W = 76
    }

    /** Draws the panel content into [panel] and lays out the controls row along its bottom edge. */
    fun render(g: GuiGraphicsCompat, panel: UiRect, mouseX: Int, mouseY: Int) {
        val font = Minecraft.getInstance().font
        uploaderHitRect = null
        uploaderHitUser = null
        lastMouseX = mouseX
        lastMouseY = mouseY
        val btn = UiTheme.CONTROL_BUTTON
        val innerX = panel.x + UiTheme.PANEL_PADDING_X
        val innerY = panel.y + UiTheme.PANEL_PADDING_Y + font.lineHeight + 6
        val innerW = panel.w - UiTheme.PANEL_PADDING_X * 2
        partListVideoTop = innerY

        val controlsRowY = panel.bottom - UiTheme.PANEL_PADDING_Y - btn
        val controlsRight = innerX + innerW
        val previewMaxH = controlsRowY - innerY - 6

        drawVideoArea(g, innerX, innerY, innerW, previewMaxH)
        drawTitleOverlay(g, innerX, innerY + previewMaxH, innerW)

        val now = System.nanoTime()
        val dt =
            if (lastPresenceFrameNanos == 0L) 0.016f else ((now - lastPresenceFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastPresenceFrameNanos = now
        val audioTarget = if (ds.audioTrackList.size > 1) 1f else 0f
        val audioDiff = audioTarget - audioPresence
        audioPresence += audioDiff * minOf(1f, dt * 10f)
        if (audioDiff in -0.002f..0.002f) audioPresence = audioTarget
        val subtitleTarget = if (ds.subtitleTrackList.isNotEmpty()) 1f else 0f
        val subtitleDiff = subtitleTarget - subtitlePresence
        subtitlePresence += subtitleDiff * minOf(1f, dt * 10f)
        if (subtitleDiff in -0.002f..0.002f) subtitlePresence = subtitleTarget
        val secondaryTarget = if (ds.subtitlesEnabled && ds.subtitleTrackList.size > 1) 1f else 0f
        val secondaryDiff = secondaryTarget - subtitleSecondaryPresence
        subtitleSecondaryPresence += secondaryDiff * minOf(1f, dt * 10f)
        if (secondaryDiff in -0.002f..0.002f) subtitleSecondaryPresence = secondaryTarget

        // Controls row: [mute][volume] [progress........] [danmaku][subtitles][2nd subtitle][audio][popout][pause]
        muteButton.place(UiRect(innerX, controlsRowY, btn, btn))
        val volumeX = innerX + btn + 4
        volume.place(UiRect(volumeX, controlsRowY, VOLUME_W, btn))
        pauseButton.place(UiRect(controlsRight - btn, controlsRowY, btn, btn))
        popoutButton.place(UiRect(controlsRight - btn * 2 - 4, controlsRowY, btn, btn))

        val audioSlotRight = controlsRight - btn * 2 - 8
        val audioBtnW = (btn * audioPresence).roundToInt()
        val audioBtnLeft = audioSlotRight - audioBtnW
        audioTrackButton.place(UiRect(audioBtnLeft, controlsRowY, audioBtnW, btn))
        audioTrackButton.setAlpha(audioPresence)

        val secondarySlotRight = audioBtnLeft - (4 * subtitleSecondaryPresence).roundToInt()
        val secondaryBtnW = (btn * subtitleSecondaryPresence).roundToInt()
        val secondaryBtnLeft = secondarySlotRight - secondaryBtnW
        subtitleSecondaryButton.place(UiRect(secondaryBtnLeft, controlsRowY, secondaryBtnW, btn))
        subtitleSecondaryButton.setAlpha(subtitleSecondaryPresence)

        val subtitleSlotRight = secondaryBtnLeft - (4 * subtitlePresence).roundToInt()
        val subtitleBtnW = (btn * subtitlePresence).roundToInt()
        val subtitleBtnLeft = subtitleSlotRight - subtitleBtnW
        subtitleButton.place(UiRect(subtitleBtnLeft, controlsRowY, subtitleBtnW, btn))
        subtitleButton.setAlpha(subtitlePresence)

        // Danmaku toggle sits beside the subtitle button; always present (Bilibili overlay control).
        val danmakuBtnLeft = subtitleBtnLeft - (4 * subtitlePresence).roundToInt() - btn
        danmakuButton.place(UiRect(danmakuBtnLeft, controlsRowY, btn, btn))

        val progX = volumeX + VOLUME_W + 4
        val progW = max(40, (danmakuBtnLeft - 4) - progX)
        progress.place(UiRect(progX, controlsRowY, progW, btn))

        dropdown.draw(g, popoutButton.x + btn / 2, popoutButton.y, mouseX, mouseY)
        val audioBtnFinalCenterX = audioSlotRight - btn / 2
        if (audioPresence > 0.01f) audioTrackDropdown.draw(g, audioBtnFinalCenterX, controlsRowY, mouseX, mouseY)
        val secondaryBtnFinalCenterX = secondarySlotRight - btn / 2
        if (subtitleSecondaryPresence > 0.01f) subtitleSecondaryDropdown.draw(g, secondaryBtnFinalCenterX, controlsRowY, mouseX, mouseY)
        val subtitleBtnFinalCenterX = subtitleSlotRight - btn / 2
        if (subtitlePresence > 0.01f) subtitleDropdown.draw(g, subtitleBtnFinalCenterX, controlsRowY, mouseX, mouseY)
        // Drawn last: the part picker covers the video area, never the card's own title / meta lines.
        if (partListOpen) drawPartList(g, font, mouseX, mouseY)
    }

    /** Draws the letterboxed video frame, or the dimmed thumbnail while loading. */
    private fun drawVideoArea(g: GuiGraphicsCompat, x: Int, y: Int, w: Int, h: Int) {
        if (ds.videoUrl != lastVideoUrl) {
            lastVideoUrl = ds.videoUrl
            ambientSampler.reset()
        }

        if (currentSource() != null) {
            drawAmbientBackdrop(g, x, y, w, h)
        } else {
            g.fill(x, y, x + w, y + h, UiTheme.VIDEO_BACKDROP)
        }

        val area = UiRect(x, y, w, h)
        val contentRatio = ds.videoContentAspect.toFloat().takeIf { it > 0f } ?: THUMBNAIL_RATIO
        val video = fitRatio(area, contentRatio)

        if (ds.isVideoStarted) {
            attachFrameSink()
            ambientSampler.uploadFrame()
        } else {
            detachFrameSink()
        }

        if (ds.isVideoStarted && ds.texture != null && ds.textureId != null) {
            ds.fitTexture()
            // fitTexture() may promote a staged quality-handoff texture, which releases and
            // unregisters the previous one. Re-read the id afterwards so we never blit a
            // just-freed texture (otherwise: "Missing resource" + GL_INVALID_OPERATION).
            val texId = ds.textureId
            if (texId != null) {
                blitVideoTexture(g, texId, video.x, video.y, video.w, video.h, ds.textureWidth, ds.textureHeight)
            }
        } else if (ds.isVideoStarted && ds.isYuvTexture) {
            yuvPreview.uploadFrame()
            val previewId = yuvPreview.textureId
            if (previewId != null) {
                blitVideoTexture(g, previewId, video.x, video.y, video.w, video.h, yuvPreview.texW, yuvPreview.texH)
            } else {
                drawWaiting(g, area)
            }
        } else {
            drawWaiting(g, area)
        }

        // Debug FPS label (rendered on top of the preview, top-left of the video area). Enable with
        // -Ddreamdisplayx.debugFps=true so it can be left in the build without showing by default.
        if (DEBUG_FPS) {
            val fps = ds.videoFps
            if (fps > 0.0) {
                g.drawText(Minecraft.getInstance().font, "%.1f fps".format(fps), video.x + 2, video.y + 2, 0xFFFFFF, true)
            }
        }
    }

    /**
     * Attaches the single preview-frame sink shared by the full YUV preview texture (only relevant
     * while [DisplayScreen.isYuvTexture]) and the ambient sampler (relevant for every playing video,
     * regardless of which texture path is actually rendered on screen).
     */
    private fun attachFrameSink() {
        if (frameSinkAttached) return
        frameSinkAttached = true
        ds.setPreviewFrameSink { buf, w, h, format ->
            if (ds.isYuvTexture) yuvPreview.updateFrame(buf, w, h, format)
            ambientSampler.onFrame(buf, w, h, format)
        }
    }

    private fun detachFrameSink() {
        if (!frameSinkAttached) return
        frameSinkAttached = false
        ds.setPreviewFrameSink(null)
    }

    /** Returns the largest box with aspect ratio [ratio] that fits inside [area], centered. */
    private fun fitRatio(area: UiRect, ratio: Float): UiRect {
        val w: Int
        val h: Int
        if (area.w / area.h.toFloat() > ratio) {
            h = area.h; w = (h * ratio).toInt()
        } else {
            w = area.w; h = (w / ratio).toInt()
        }
        return area.centered(w, h)
    }

    /**
     * The decode pipeline pads every frame to the display's own block aspect ratio (its GPU texture
     * is allocated at that shape), so a wide / narrow block display bakes black bars into the texture
     * pixels themselves.
     */
    private fun contentRect(frameW: Int, frameH: Int, contentAspect: Double): UiRect {
        if (frameW <= 0 || frameH <= 0 || contentAspect <= 0.0 || !contentAspect.isFinite()) {
            return UiRect(0, 0, frameW, frameH)
        }
        val frameAspect = frameW / frameH.toDouble()
        return if (contentAspect > frameAspect) {
            val contentH = (frameW / contentAspect).toInt().coerceIn(1, frameH)
            UiRect(0, (frameH - contentH) / 2, frameW, contentH)
        } else {
            val contentW = (frameH * contentAspect).toInt().coerceIn(1, frameW)
            UiRect((frameW - contentW) / 2, 0, contentW, frameH)
        }
    }

    /** Like [blitTexture], but crops the block-shape padding out of a [texW] x [texH] decode texture first. */
    private fun blitVideoTexture(
        g: GuiGraphicsCompat,
        id: Identifier,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        texW: Int,
        texH: Int
    ) {
        val content = contentRect(texW, texH, ds.videoContentAspect)
        //? if >=1.21.11 {
        g.blit(
            RenderPipelines.GUI_TEXTURED,
            id,
            x,
            y,
            content.x.toFloat(),
            content.y.toFloat(),
            w,
            h,
            content.w,
            content.h,
            texW,
            texH
        )
        //?} else
        /*g.blit(id, x, y, w, h, content.x.toFloat(), content.y.toFloat(), content.w, content.h, texW, texH)
        g.flush()*/
    }

    /** Draws the dimmed thumbnail (or shimmer, or plain backdrop) while no frame is ready yet. The
     *  "Waiting for video..." status text itself lives on the seek bar instead (see [SeekBar]). */
    private fun drawWaiting(g: GuiGraphicsCompat, area: UiRect) {
        // YouTube thumbnails are always 16:9, regardless of the screen's own block shape.
        val box = fitRatio(area, THUMBNAIL_RATIO)
        // While a saved resume is pending, prefer the target frame over the platform cover.
        val resumeFrame = waitingResumeFrame()
        val thumb = resumeFrame ?: if (ds.savedTimeNanos > 0L) null else currentThumbnail()
        when {
            thumb != null -> {
                blitTexture(g, thumb, box.x, box.y, box.w, box.h)
                g.fill(box.x, box.y, box.right, box.bottom, UiTheme.THUMB_DIM_SCRIM)
            }
            // A custom link has no thumbnail to wait for, so a shimmer would animate forever over
            // something that is loading fine. A flat plate with the host reads as settled instead.
            // A key whose fetch already failed (e.g. a Kick CDN 403) counts the same way — it is
            // never coming either.
            (currentThumbnailKey() == null || currentThumbnailKey()?.let(Thumbnails::isFailed) == true) &&
                    currentSource() != null ->
                drawCustomBackdrop(g, box)
            // Something is assigned and loading, just no thumbnail decoded yet: a neat shimmer
            currentSource() != null ->
                g.drawShimmer(box.x, box.y, box.right, box.bottom, UiTheme.PLACEHOLDER_BG, UiTheme.PLACEHOLDER_SHIMMER)
            // Nothing assigned to this display: leave the plain black backdrop from drawVideoArea
            else -> {}
        }
    }

    /** The custom-link stand-in for a thumbnail: a soft plate naming the host the video comes from. */
    private fun drawCustomBackdrop(g: GuiGraphicsCompat, box: UiRect) {
        val font = Minecraft.getInstance().font
        g.fillVGradient(box.x, box.y, box.right, box.bottom, UiTheme.CUSTOM_ART_TOP, UiTheme.CUSTOM_ART_BOTTOM)
        val host = ds.videoUrl?.let { CustomMediaUrls.hostOf(it) } ?: return
        val label = UiText.trim(font, host, box.w - 12)
        g.drawText(
            font, label,
            box.x + (box.w - font.width(label)) / 2, box.y + (box.h - font.lineHeight) / 2,
            UiTheme.TEXT_SECONDARY, true,
        )
    }

    /** Generic overlay text resolved for the current display URL, regardless of provider. */
    private data class OverlayInfo(
        val title: String?,
        val uploader: String?,
        val uploaderAvatarUrl: String?,
        val isVerified: Boolean,
        val views: String,
        val likes: String,
        val published: String?,
        val isNew: Boolean,
        val platform: MediaPlatform,
        /** Bilibili creator id, required to open the uploader page. */
        val uploaderId: Long? = null,
    )

    /**
     * Resolves [OverlayInfo] for the current display URL: Twitch
     * via [TwitchMetadataCache], Vimeo / Kick via their platform caches, and a bare file / long-tail
     * link from the URL alone.
     */
    private fun overlayInfo(): OverlayInfo {
        when (val source = currentSource()) {
            null -> return OverlayInfo(
                title = ds.videoUrl?.let { CustomMediaUrls.displayName(it) } ?: "—",
                uploader = null,
                uploaderAvatarUrl = null,
                isVerified = false,
                views = "",
                likes = "",
                published = null,
                isNew = false,
                platform = MediaPlatform.OTHER,
            )

            is MediaSource.Twitch -> {
                val key = TwitchMetadataCache.cacheKey(source)
                val meta = key?.let { TwitchMetadataCache.get(it) }
                if (meta == null) TwitchMetadataCache.requestAsync(source)
                return OverlayInfo(
                    title = meta?.title,
                    uploader = meta?.channelName,
                    uploaderAvatarUrl = meta?.channelAvatarUrl,
                    isVerified = false,
                    views = watchOrViewCount(meta?.viewCount, meta?.isLive == true),
                    likes = "",
                    published = meta?.gameName,
                    isNew = false,
                    platform = MediaPlatform.TWITCH,
                )
            }

            is MediaSource.Vimeo -> {
                val meta = VimeoMetadataCache.get(VimeoMetadataCache.cacheKey(source))
                if (meta == null) VimeoMetadataCache.requestAsync(source)
                return platformOverlayInfo(source.url, MediaPlatform.VIMEO, meta)
            }

            is MediaSource.Kick -> {
                val meta = KickMetadataCache.cacheKey(source)?.let { KickMetadataCache.get(it) }
                if (meta == null) KickMetadataCache.requestAsync(source)
                return platformOverlayInfo(source.url, MediaPlatform.KICK, meta)
            }

            is MediaSource.Bilibili -> {
                val meta = BilibiliMetadataCache.cacheKey(source)?.let { BilibiliMetadataCache.get(it) }
                if (meta == null) BilibiliMetadataCache.requestAsync(source)
                return platformOverlayInfo(source.url, MediaPlatform.BILIBILI, meta)
            }

            // A custom / long-tail link carries no metadata anywhere: its file name and host are all
            // there is to show, and both are derivable from the URL without a single request.
            is MediaSource.DirectStream -> return customOverlayInfo(source.streamUrl)
            is MediaSource.Remote -> return customOverlayInfo(source.url)

            is MediaSource.YouTube -> return OverlayInfo(
                title = source.videoId,
                uploader = null,
                uploaderAvatarUrl = null,
                isVerified = false,
                views = "",
                likes = "",
                published = null,
                isNew = false,
                platform = MediaPlatform.YOUTUBE,
            )

            is MediaSource.Ingest -> return OverlayInfo(
                title = "Live Ingest",
                uploader = null,
                uploaderAvatarUrl = null,
                isVerified = false,
                views = "",
                likes = "",
                published = null,
                isNew = false,
                platform = MediaPlatform.OTHER,
            )
        }
    }

    /** [OverlayInfo] for a Vimeo / Kick video from its [PlatformVideoMetadata]. */
    private fun platformOverlayInfo(url: String, platform: MediaPlatform, meta: PlatformVideoMetadata?): OverlayInfo =
        OverlayInfo(
            title = meta?.title ?: CustomMediaUrls.displayName(url),
            // Bilibili's host is always the same site name, so it would read as noise where the
            // uploader goes: leave that slot empty until the real creator name is known.
            uploader = meta?.uploader
                ?: if (platform == MediaPlatform.BILIBILI) null else CustomMediaUrls.hostOf(url),
            uploaderAvatarUrl = meta?.uploaderAvatarUrl,
            isVerified = false,
            views = watchOrViewCount(meta?.viewCount, meta?.isLive == true),
            likes = "",
            published = null,
            isNew = false,
            platform = platform,
            uploaderId = meta?.uploaderId,
        )

    /** [OverlayInfo] for a custom link: file name as the title, host in place of a channel. */
    private fun customOverlayInfo(url: String): OverlayInfo = OverlayInfo(
        title = CustomMediaUrls.displayName(url),
        uploader = CustomMediaUrls.hostOf(url),
        uploaderAvatarUrl = null,
        isVerified = false,
        views = "",
        likes = "",
        published = null,
        isNew = false,
        platform = MediaPlatform.DIRECT,
    )

    /** Formats a viewer / view count, labelling it "watching" while live and "views" otherwise. */
    private fun watchOrViewCount(count: Long?, isLive: Boolean): String = count?.let {
        val shown = if (isLive) formatLiveViewers(it) else formatCompactCount(it)
        shown + " " + translatableText(
            if (isLive) "dreamdisplayx.ui.watching" else "dreamdisplayx.ui.views_short",
        ).string
    } ?: ""

    /** Live audience: plain digits below 10k, one decimal plus a `w` suffix above it. */
    private fun formatLiveViewers(v: Long): String =
        if (v >= 10_000L) String.format(java.util.Locale.ROOT, "%.1fw", v / 10_000.0) else v.toString()

    /** Draws the dark strip with the video title (+NEW tag) and channel / views / likes / date metadata. */
    private fun drawTitleOverlay(g: GuiGraphicsCompat, x: Int, y: Int, w: Int) {
        val font = Minecraft.getInstance().font
        val info = overlayInfo()

        var title = info.title
        if (title.isNullOrEmpty()) title = ds.videoUrl
        if (title == null) title = "—"

        val padX = 4
        val padY = 3
        val textW = w - padX * 2
        var shown = UiText.trim(font, title, textW)

        val showParts = ds.parts.size > 1 && currentSource() is MediaSource.Bilibili
        val boxH = font.lineHeight * (if (showParts) 3 else 2) + padY * 3
        val boxY = y - boxH
        partPopupAnchorY = boxY
        partPopupX = x
        partPopupW = w
        g.fill(x, boxY, x + w, y, UiTheme.OVERLAY_SCRIM)
        drawPartChips(g, x, y, w, font)

        var titleX = x + padX
        val titleY = boxY + padY
        // The platform tag (Twitch / Vimeo / Kick / Link) takes precedence over the generic "New"
        val badge = PlatformBadge.forPlatform(info.platform)
        val tagText = when {
            badge != null -> translatableText(badge.labelKey).string
            info.isNew -> translatableText("dreamdisplayx.ui.new").string
            else -> null
        }
        if (tagText != null) {
            val bg = badge?.bgColor ?: UiTheme.ACCENT_NEW_TAG
            val fg = badge?.textColor ?: UiTheme.TEXT_PRIMARY
            val tw = font.width(tagText) + 6
            g.fill(titleX, titleY - 1, titleX + tw, titleY + font.lineHeight, bg)
            g.drawText(font, tagText, titleX + 3, titleY, fg, false)
            titleX += tw + 4
            shown = UiText.trim(font, title, textW - tw - 4)
        }
        g.drawText(font, shown, titleX, titleY, UiTheme.TEXT_PRIMARY, false)

        val parts = StringBuilder()
        if (!info.uploader.isNullOrEmpty()) parts.append(info.uploader)
        if (info.views.isNotEmpty()) {
            if (parts.isNotEmpty()) parts.append(" • ")
            parts.append(info.views)
        }
        if (info.likes.isNotEmpty()) {
            if (parts.isNotEmpty()) parts.append(" • ")
            parts.append(info.likes).append(" ").append(translatableText("dreamdisplayx.ui.likes").string)
        }
        if (!info.published.isNullOrEmpty()) {
            if (parts.isNotEmpty()) parts.append(" • ")
            parts.append(info.published)
        }

        val metaY = boxY + padY + font.lineHeight + padY
        val metaRowX = x + padX
        var metaX = metaRowX
        var metaW = textW
        val avatarUrl = info.uploaderAvatarUrl
        val avatar = avatarUrl?.let { Thumbnails.get(it) }
        if (avatar != null) {
            val iconSize = font.lineHeight
            blitTexture(g, avatar, metaX, metaY - 1, iconSize, iconSize)
            metaX += iconSize + 3
            metaW -= iconSize + 3
        } else if (avatarUrl != null) {
            Thumbnails.request(avatarUrl, avatarUrl)
        }
        if (info.isVerified) {
            val badgeSize = font.lineHeight - 1
            g.drawVerifiedBadge(metaX, metaY - 1, badgeSize, UiTheme.ACCENT)
            metaX += badgeSize + 3
            metaW -= badgeSize + 3
        }
        val uploaderName = info.uploader.orEmpty()
        if (uploaderName.isNotEmpty()) {
            val nameW = font.width(UiText.trim(font, uploaderName, metaW))
            val mid = info.uploaderId
            if (nameW > 0 && mid != null && onOpenUploader != null) {
                uploaderHitRect = UiRect(metaRowX, metaY - 2, metaX + nameW - metaRowX, font.lineHeight + 3)
                uploaderHitUser = BilibiliUserItem(
                    mid = mid,
                    name = uploaderName,
                    avatarUrl = info.uploaderAvatarUrl,
                )
            }
        }
        g.drawText(
            font, UiText.trim(font, parts.toString(), metaW),
            metaX, metaY, UiTheme.TEXT_SECONDARY, false,
        )
    }

    /** True while the part picker is open; a click anywhere else closes it. */
    private var partListOpen = false

    /** First part row the picker shows; paged with the ▲ / ▼ rows when there are more. */
    private var partListScroll = 0

    /** Part rows per picker page. */
    private var partListPageSize = PART_LIST_ROWS

    /** Top of the video area, so the picker never grows out of it. */
    private var partListVideoTop = 0

    /** Top edge of the preview info card; the picker hangs above it so it never covers the card. */
    private var partPopupAnchorY = 0

    /** Preview panel x / width, captured with the info card so the picker uses the same frame. */
    private var partPopupX = 0
    private var partPopupW = 0

    /** Last mouse position seen by [render], used to highlight the hovered part row. */
    private var lastMouseX = 0
    private var lastMouseY = 0

    /** Draws the current-part chip; the popup is added to the end of [render] so it sits on top. */
    private fun drawPartChips(g: GuiGraphicsCompat, x: Int, y: Int, w: Int, font: net.minecraft.client.gui.Font) {
        partHitRects.clear()
        val parts = ds.parts
        if (parts.size <= 1) {
            partListOpen = false
            return
        }
        val total = parts.size
        val current = ds.currentPart
        val currentPart = parts.firstOrNull { it.page == current } ?: parts[0]
        val rowY = y - font.lineHeight - 2
        // Fixed width, like the popup, so switching parts never resizes the chip.
        val chipW = min(w - 8, PART_LIST_MAX_W)
        val rect = UiRect(x + 4, rowY - 1, chipW, font.lineHeight + 1)
        val hovered = rect.contains(lastMouseX, lastMouseY)
        g.fill(
            rect.x, rect.y, rect.right, rect.bottom,
            when {
                partListOpen -> UiTheme.ACCENT_BILIBILI_TAG
                hovered -> 0xC03A3A46.toInt()
                else -> 0x8C000000.toInt()
            },
        )
        val label = UiText.trim(font, "P$current/$total · ${currentPart.title} ▾", chipW - 8)
        g.drawText(font, label, rect.x + 4, rowY, UiTheme.TEXT_PRIMARY, false)
        partHitRects += rect to PART_LIST_TOGGLE
    }

    /** Draws the part picker above the info card: fixed width, fixed rows, right-aligned lengths. */
    private fun drawPartList(g: GuiGraphicsCompat, font: net.minecraft.client.gui.Font, mouseX: Int, mouseY: Int) {
        val parts = ds.parts
        if (parts.size <= 1) {
            partListOpen = false
            return
        }
        val current = ds.currentPart
        val rowH = font.lineHeight + 4
        val fitRows = ((partPopupAnchorY - 8 - partListVideoTop) / rowH).coerceIn(3, PART_LIST_ROWS + 2)
        partListPageSize = (fitRows - 2).coerceIn(2, PART_LIST_ROWS)
        partListScroll = partListScroll.coerceIn(0, (parts.size - partListPageSize).coerceAtLeast(0))
        val rows = partListPageSize
        val hasUp = partListScroll > 0
        val hasDown = partListScroll + rows < parts.size
        val boxW = min(partPopupW - 8, PART_LIST_MAX_W)
        val boxX = partPopupX + 4
        // Two fixed paging rows keep the box the same height on every page, so nothing jumps.
        val boxH = (rows + 2) * rowH + 4
        val boxY = partPopupAnchorY - 3 - boxH
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, UiTheme.PLACEHOLDER_BG)
        g.drawOutline(UiRect(boxX, boxY, boxW, boxH), UiTheme.PANEL_BORDER)

        val numW = font.width("P99") + 6
        val durRight = boxX + boxW - 6
        var rowY = boxY + 2
        fun pager(label: String, page: Int, enabled: Boolean) {
            val rect = UiRect(boxX + 1, rowY, boxW - 2, rowH)
            if (enabled && rect.contains(mouseX, mouseY)) {
                g.fill(rect.x, rect.y, rect.right, rect.bottom, PART_HOVER_FILL)
            }
            g.drawText(
                font, label, rect.x + 4, rowY + 2,
                if (enabled) UiTheme.TEXT_SECONDARY else 0xFF6E6E6E.toInt(), false,
            )
            if (enabled) partHitRects += rect to page
            rowY += rowH
        }
        pager(translatableText("dreamdisplayx.ui.parts_earlier").string, PART_PAGE_BACK, hasUp)
        for (i in 0 until rows) {
            val part = parts.getOrNull(partListScroll + i)
            val rect = UiRect(boxX + 1, rowY, boxW - 2, rowH)
            if (part != null) {
                val selected = part.page == current
                if (selected) {
                    g.fill(rect.x, rect.y, rect.right, rect.bottom, UiTheme.ACCENT_BILIBILI_TAG)
                } else if (rect.contains(mouseX, mouseY)) {
                    g.fill(rect.x, rect.y, rect.right, rect.bottom, PART_HOVER_FILL)
                }
                val textColor = if (selected) UiTheme.TEXT_PRIMARY else UiTheme.TEXT_DIM
                g.drawText(font, "P${part.page}", boxX + 5, rowY + 2, textColor, false)
                val length = PlaybackClock.format(part.durationSec * 1000L)
                val lengthX = durRight - font.width(length)
                g.drawText(font, length, lengthX, rowY + 2, UiTheme.TEXT_META, false)
                val titleX = boxX + 5 + numW
                val titleW = lengthX - 4 - titleX
                if (titleW > font.width("WWW")) {
                    g.drawText(font, UiText.trim(font, part.title, titleW), titleX, rowY + 2, textColor, false)
                }
                partHitRects += rect to part.page
            }
            rowY += rowH
        }
        pager(translatableText("dreamdisplayx.ui.parts_later").string, PART_PAGE_FORWARD, hasDown)
    }

    /** Formats [v] compactly (e.g. "1.2M"), or its plain value below 1000. */
    private fun formatCompactCount(v: Long): String = when {
        v >= 1_000_000_000L -> String.format(java.util.Locale.ROOT, "%.1fB", v / 1_000_000_000.0)
        v >= 1_000_000L -> String.format(java.util.Locale.ROOT, "%.1fM", v / 1_000_000.0)
        v >= 1_000L -> String.format(java.util.Locale.ROOT, "%.1fK", v / 1_000.0)
        else -> v.toString()
    }

    // MediaSource.from parses the URL against every platform; currentSource is called several times
    // per frame, so its result is memoized until the display's URL actually changes
    private var sourceUrl: String? = null
    private var sourceCache: MediaSource? = null

    /** The [MediaSource] for the display's current URL, or null when there's none set. */
    private fun currentSource(): MediaSource? {
        val url = ds.videoUrl?.takeIf { it.isNotEmpty() } ?: return null
        if (url != sourceUrl) {
            sourceUrl = url
            sourceCache = MediaSource.from(url)
        }
        return sourceCache
    }

    /** Cache key for the current source's thumbnail/metadata: a YouTube id, or a platform composite key. */
    private fun currentThumbnailKey(): String? = when (val source = currentSource()) {
        is MediaSource.Twitch -> TwitchMetadataCache.cacheKey(source)
        is MediaSource.Vimeo -> VimeoMetadataCache.cacheKey(source)
        is MediaSource.Kick -> KickMetadataCache.cacheKey(source)
        is MediaSource.Bilibili -> BilibiliMetadataCache.cacheKey(source)
        is MediaSource.YouTube -> null
        else -> null
    }

    /** Requests the thumbnail download for the current source once its metadata (and thumbnail URL) is ready. */
    private fun requestCurrentThumbnail() {
        when (val source = currentSource()) {
            is MediaSource.Twitch -> {
                val key = TwitchMetadataCache.cacheKey(source) ?: return
                val meta = TwitchMetadataCache.get(key)
                if (meta == null) {
                    TwitchMetadataCache.requestAsync(source)
                    return
                }
                meta.thumbnailUrl?.let { Thumbnails.request(key, it) }
            }

            is MediaSource.Vimeo -> {
                val key = VimeoMetadataCache.cacheKey(source)
                val meta = VimeoMetadataCache.get(key)
                if (meta == null) VimeoMetadataCache.requestAsync(source)
                else meta.thumbnailUrl?.let { Thumbnails.request(key, it) }
            }

            is MediaSource.Kick -> {
                val key = KickMetadataCache.cacheKey(source) ?: return
                val meta = KickMetadataCache.get(key)
                if (meta == null) KickMetadataCache.requestAsync(source)
                else meta.thumbnailUrl?.let { Thumbnails.request(key, it) }
            }

            is MediaSource.Bilibili -> {
                val key = BilibiliMetadataCache.cacheKey(source) ?: return
                val meta = BilibiliMetadataCache.get(key)
                if (meta == null) BilibiliMetadataCache.requestAsync(source)
                else meta.thumbnailUrl?.let { Thumbnails.request(key, it) }
            }

            is MediaSource.YouTube -> {} // no thumbnail support
            else -> {}
        }
    }

    private fun drawAmbientBackdrop(g: GuiGraphicsCompat, x: Int, y: Int, w: Int, h: Int) {
        val live = ambientSampler.textureId
        val id = currentThumbnailKey()
        val ambient = live ?: id?.let { Thumbnails.ambientTexture(it) }
        if (ambient != null) {
            blitTexture(g, ambient, x, y, w, h)
            g.fill(x, y, x + w, y + h, UiTheme.AMBIENT_SCRIM)
        } else {
            // Warm the thumbnail even while the video plays, so the backdrop appears once it decodes
            // (request de-dups, so calling it per frame is cheap).
            requestCurrentThumbnail()
            g.fill(x, y, x + w, y + h, UiTheme.AMBIENT_DEFAULT)
        }
    }

    /** Returns the cached thumbnail for the current video, requesting it asynchronously if absent. */
    private fun currentThumbnail(): Identifier? {
        val id = currentThumbnailKey() ?: return null
        Thumbnails.get(id)?.let { return it }
        requestCurrentThumbnail()
        return null
    }

    /** Frame at the pending resume position, or null while extraction is still running. */
    private fun waitingResumeFrame(): Identifier? {
        if (ds.isVideoStarted || ds.savedTimeNanos <= 0L) return null
        val key = ds.videoUrl ?: return null
        val rawUrl = ds.scrubPreviewRawUrl ?: return null
        ScrubPreview.request(key, rawUrl, ds.mediaPlayerDurationNanos, ds.scrubPreviewSeeksByDecoding)
        return ScrubPreview.frameAt(key, ds.savedTimeNanos)
    }

    private fun blitTexture(g: GuiGraphicsCompat, id: Identifier, x: Int, y: Int, w: Int, h: Int) {
        //? if >=1.21.11 {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, w, h)
        //?} else
        /*g.blit(id, x, y, 0f, 0f, w, h, w, h)*/
    }

    fun close() {
        detachFrameSink()
        // yuvPreview is NOT closed here: it's owned by DisplayScreen and outlives this section so the
        // last frame (and its GPU texture) survives menu close/reopen; DisplayScreen.unregister() owns
        // its teardown.
        ambientSampler.close()
    }

    /** Drops the click target, e.g. while the preview is not being drawn. */
    fun clearHitTargets() {
        uploaderHitRect = null
        uploaderHitUser = null
    }

    /**
     * Opens the creator page when the click lands on the uploader chip (avatar + name) recorded while
     * drawing the overlay. Returns true when the click was consumed.
     */
    fun handleUploaderClick(mouseX: Double, mouseY: Double): Boolean {
        val rect = uploaderHitRect ?: return false
        val user = uploaderHitUser ?: return false
        val callback = onOpenUploader ?: return false
        if (!rect.contains(mouseX.toInt(), mouseY.toInt())) return false
        callback(user)
        return true
    }

    /** Part chips drawn in the title overlay this frame: hit box to 1-based part number. */
    private val partHitRects = mutableListOf<Pair<UiRect, Int>>()

    /** Switches the display to the clicked part, or opens / pages the picker. Returns true when consumed. */
    fun handlePartClick(mouseX: Double, mouseY: Double): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        val hit = partHitRects.firstOrNull { it.first.contains(mx, my) }
        if (hit == null) {
            // The open picker swallows the click that dismisses it, so it cannot also press a button.
            if (!partListOpen) return false
            partListOpen = false
            return true
        }
        when (hit.second) {
            PART_LIST_TOGGLE -> partListOpen = !partListOpen
            PART_PAGE_BACK -> partListScroll = (partListScroll - partListPageSize).coerceAtLeast(0)
            PART_PAGE_FORWARD -> partListScroll += partListPageSize
            else -> {
                partListOpen = false
                switchToPart(hit.second)
            }
        }
        return true
    }

    /** Steps the open picker to the previous / next part; false when the picker is closed. */
    fun stepPart(delta: Int): Boolean {
        if (!partListOpen) return false
        val parts = ds.parts
        if (parts.size <= 1) return false
        val index = parts.indexOfFirst { it.page == ds.currentPart }.coerceAtLeast(0)
        val target = (index + delta).coerceIn(0, parts.size - 1)
        if (target == index) return true
        // Keep the row just switched to inside the visible page.
        if (target < partListScroll) partListScroll = target
        if (target >= partListScroll + partListPageSize) partListScroll = target - partListPageSize + 1
        switchToPart(parts[target].page)
        return true
    }

    /** Switches the display to [page] through the server; shared by the picker clicks and arrow keys. */
    private fun switchToPart(page: Int) {
        val source = currentSource() as? MediaSource.Bilibili ?: return
        val bvid = source.bvid ?: return
        if (page == ds.currentPart) return
        DreamServices.registry.get(DisplayServices.DISPLAY).setUrl(
            DisplayId(ds.uuid), "https://www.bilibili.com/video/$bvid?p=$page", ds.lang,
        )
    }

    private class AmbientFrameSampler(private val ds: DisplayScreen) {
        @Volatile
        private var target: AmbientGrid.Grid? = null
        private var lastSampleNanos = 0L

        private var currentR: FloatArray? = null
        private var currentG: FloatArray? = null
        private var currentB: FloatArray? = null
        private var lastUploadNanos = 0L

        private var rgbaBuf: ByteBuffer = EMPTY_DIRECT
        private var dynamicTexture: DynamicTexture? = null
        var textureId: Identifier? = null
            private set
        private var uploader: AsyncTextureUploader? = null
        private var rgbaUploadBuffer: ByteBuffer? = null

        fun onFrame(buf: ByteBuffer, w: Int, h: Int, format: UploadPixelFormat) {
            val now = System.nanoTime()
            if (now - lastSampleNanos < SAMPLE_INTERVAL_NS) return
            lastSampleNanos = now
            target = AmbientGrid.fromFrameBuffer(buf, w, h, format.bytesPerPixel)
        }

        fun uploadFrame() {
            val t = target ?: return
            val gw = AmbientGrid.GRID_W
            val gh = AmbientGrid.GRID_H
            val n = gw * gh

            var cr = currentR
            var cg = currentG
            var cb = currentB
            val now = System.nanoTime()
            if (cr == null || cg == null || cb == null) {
                cr = FloatArray(n) { t.r[it].toFloat() }
                cg = FloatArray(n) { t.g[it].toFloat() }
                cb = FloatArray(n) { t.b[it].toFloat() }
                currentR = cr; currentG = cg; currentB = cb
            } else if (lastUploadNanos != 0L) {
                val dtSeconds = ((now - lastUploadNanos).coerceAtLeast(0)) / 1_000_000_000f
                val alpha = 1f - kotlin.math.exp(-dtSeconds / SMOOTH_TAU_SECONDS)
                for (i in 0 until n) {
                    cr[i] += (t.r[i] - cr[i]) * alpha
                    cg[i] += (t.g[i] - cg[i]) * alpha
                    cb[i] += (t.b[i] - cb[i]) * alpha
                }
            }
            lastUploadNanos = now

            val size = n * 4
            var out = rgbaBuf
            if (out.capacity() < size) out = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            out.clear()
            for (i in 0 until n) {
                out.put(cr[i].toInt().coerceIn(0, 255).toByte())
                out.put(cg[i].toInt().coerceIn(0, 255).toByte())
                out.put(cb[i].toInt().coerceIn(0, 255).toByte())
                out.put(0xFF.toByte())
            }
            out.flip()
            rgbaBuf = out

            val mc = Minecraft.getInstance()
            var tex = dynamicTexture
            if (tex == null) {
                val img = NativeImage(NativeImage.Format.RGBA, gw, gh, false)
                //? if >=1.21.11 {
                tex = DynamicTexture({ "dreamdisplayx:ambient" }, img)
                //?} else
                /*tex = DynamicTexture(img)*/
                textureId = identifier(
                    Initializer.MOD_ID,
                    "ambient/${ds.uuid}-${UUID.randomUUID()}",
                )
                mc.textureManager.register(textureId!!, tex)
                TextureUploadUtil.applyBilinearFilter(tex)
                dynamicTexture = tex
            }

            TextureUploadUtil.uploadDynamicTexture(
                texture = tex,
                src = rgbaBuf,
                w = gw,
                h = gh,
                format = UploadPixelFormat.RGBA32,
                glUploader = { uploader ?: AsyncTextureUploader(stateCache = true).also { uploader = it } },
                rgbaScratch = rgbaUploadBuffer,
                setRgbaScratch = { rgbaUploadBuffer = it },
            )
        }

        fun reset() {
            target = null
            lastSampleNanos = 0
            currentR = null; currentG = null; currentB = null
            lastUploadNanos = 0
            val mc = Minecraft.getInstance()
            dynamicTexture?.close()
            textureId?.let { mc.textureManager.release(it) }
            dynamicTexture = null
            textureId = null
        }

        fun close() {
            uploader?.close()
            uploader = null
            reset()
        }

        companion object {
            private val EMPTY_DIRECT: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
            private const val SAMPLE_INTERVAL_NS = 1_500_000_000L

            /** Time constant of the exponential ease toward each newly sampled target — bigger = slower, calmer drift. */
            private const val SMOOTH_TAU_SECONDS = 2.5f
        }
    }
}
