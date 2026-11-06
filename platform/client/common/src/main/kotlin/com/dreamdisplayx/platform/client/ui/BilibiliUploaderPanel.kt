package com.dreamdisplayx.platform.client.ui

import com.dreamdisplayx.api.media.search.model.MediaSearchResult
import com.dreamdisplayx.api.media.source.model.MediaPlatform
import com.dreamdisplayx.media.source.bilibili.BilibiliApi
import com.dreamdisplayx.media.source.bilibili.BilibiliSearchItem
import com.dreamdisplayx.media.source.bilibili.BilibiliSeason
import com.dreamdisplayx.media.source.bilibili.BilibiliUpProfileCache
import com.dreamdisplayx.media.source.bilibili.BilibiliUserItem
import com.dreamdisplayx.media.source.bilibili.BilibiliZone
import com.dreamdisplayx.platform.client.input.MouseButtons
import com.dreamdisplayx.platform.client.render.Thumbnails
import com.dreamdisplayx.platform.client.ui.kit.*
import com.dreamdisplayx.util.DreamCoroutines
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-window uploader page with profile header, category tabs, a fixed 30-card page and an
 * explicit page input. Data loading stays here; the screen only owns navigation.
 */
class BilibiliUploaderPanel(
    private val uploader: BilibiliUserItem,
    private val keyword: String,
    private val onPick: (MediaSearchResult) -> Unit,
    /** Invoked with the room id when the live badge is clicked; null hides the click behaviour. */
    private val onPlayLive: ((Long) -> Unit)? = null,
) : UiWidget(Component.translatable("dreamdisplayx.uploader_profile.title")) {

    private enum class Tab { ALL, SEASONS, ZONES }

    private data class Chip(
        val label: String,
        val count: Int,
        val zone: BilibiliZone? = null,
        val season: BilibiliSeason? = null,
    )

    private data class Metrics(
        val bannerH: Int,
        val headerBottom: Int,
        val tabsY: Int,
        val tabsH: Int,
        val chipsY: Int,
        val chipsH: Int,
        val gridTop: Int,
        val gridBottom: Int,
        val pagerY: Int,
        val columns: Int,
        val cardW: Int,
        val thumbH: Int,
        val cardH: Int,
        val contentH: Int,
        val tabRects: List<UiRect>,
        val chipRects: List<UiRect>,
        val prevRect: UiRect,
        val nextRect: UiRect,
        val pageBoxRect: UiRect,
        val chipMaxScroll: Int,
    )

    private val logger = LoggerFactory.getLogger("DreamDisplaysX/UploaderPanel")
    private var tab = Tab.ALL
    private var page = 1
    private var total = 0
    private var allTotal = 0
    private var zones: List<BilibiliZone> = emptyList()
    private var seasons: List<BilibiliSeason> = emptyList()
    private var selectedZone: BilibiliZone? = null
    private var selectedSeason: BilibiliSeason? = null
    private var pageItems: List<MediaSearchResult> = emptyList()
    private var loading = true
    private var loadingSeasons = false
    private var failed = false
    private var pageGeneration = 0
    private var seasonsGeneration = 0
    private var pageJob: Job? = null
    private var seasonsJob: Job? = null

    private var scrollOffset = 0
    private var chipScroll = 0
    private var hoveredTab = -1
    private var hoveredChip = -1
    private var hoveredCard = -1
    private var hoveredPrev = false
    private var hoveredNext = false

    /** Hit box of the live badge, refreshed every frame while the creator is streaming. */
    private var liveChipRect: UiRect? = null
    private var hoveredLive = false

    private var draggingScrollbar = false
    private var sbActive = false
    private var sbStart = 0
    private var sbViewport = 0
    private var sbThumbLen = 0
    private var sbMaxOff = 0
    private var sbCross = 0

    init {
        reload()
    }

    fun close() {
        pageGeneration++
        seasonsGeneration++
        pageJob?.cancel()
        seasonsJob?.cancel()
    }

    override fun handlesWholeWidgetCursor(): Boolean = false

    /** The page-number edit box rect, or null while the page has no usable canvas. */
    fun pageInputRect(): UiRect? {
        if (width <= 0 || height <= 0) return null
        return metrics(Minecraft.getInstance().font).pageBoxRect
    }

    fun pageNumber(): Int = page

    fun totalPages(): Int = uploaderPageCount(total, PAGE_SIZE)

    /** Applies a page typed by the user; invalid text leaves the current page unchanged. */
    fun submitPageInput(value: String) {
        val target = value.trim().toIntOrNull() ?: return
        goToPage(target)
    }

    private fun reload() {
        pageJob?.cancel()
        seasonsJob?.cancel()
        pageGeneration++
        seasonsGeneration++
        tab = Tab.ALL
        page = 1
        total = 0
        allTotal = 0
        zones = emptyList()
        seasons = emptyList()
        selectedZone = null
        selectedSeason = null
        pageItems = emptyList()
        loading = true
        loadingSeasons = false
        failed = false
        scrollOffset = 0
        chipScroll = 0

        val pageGen = pageGeneration
        pageJob = DreamCoroutines.clientIo.launch {
            val result = withContext(Dispatchers.IO) {
                BilibiliApi.fetchUploaderVideoPage(uploader.mid, keyword, 1, PAGE_SIZE, null)
            }
            Minecraft.getInstance().execute {
                if (pageGen != pageGeneration) return@execute
                loading = false
                if (result == null) {
                    failed = true
                    return@execute
                }
                allTotal = result.total
                total = result.total
                zones = result.zones
                pageItems = result.items.mapNotNull(::toResult)
                page = 1
            }
        }

        val seasonsGen = seasonsGeneration
        seasonsJob = DreamCoroutines.clientIo.launch {
            loadingSeasons = true
            val list = withContext(Dispatchers.IO) {
                runCatching { BilibiliApi.fetchUploaderSeasons(uploader.mid) }.getOrDefault(emptyList())
            }
            Minecraft.getInstance().execute {
                if (seasonsGen != seasonsGeneration) return@execute
                loadingSeasons = false
                seasons = list
                if (tab == Tab.SEASONS && selectedSeason == null && list.isNotEmpty()) {
                    selectedSeason = list.first()
                    loadPage(1)
                }
            }
        }
    }

    private fun loadPage(target: Int) {
        val targetPage = target.coerceAtLeast(1)
        page = targetPage
        scrollOffset = 0
        loading = true
        failed = false
        pageItems = emptyList()
        pageJob?.cancel()
        val gen = ++pageGeneration
        pageJob = DreamCoroutines.clientIo.launch {
            val result = withContext(Dispatchers.IO) {
                when (tab) {
                    Tab.ALL -> BilibiliApi.fetchUploaderVideoPage(
                        uploader.mid, keyword, targetPage, PAGE_SIZE, null,
                    )
                    Tab.ZONES -> BilibiliApi.fetchUploaderVideoPage(
                        uploader.mid, keyword, targetPage, PAGE_SIZE, selectedZone?.tid,
                    )
                    Tab.SEASONS -> selectedSeason?.let {
                        BilibiliApi.fetchUploaderSeasonPage(uploader.mid, it.seasonId, targetPage, PAGE_SIZE)
                    }
                }
            }
            Minecraft.getInstance().execute {
                if (gen != pageGeneration) return@execute
                loading = false
                if (result == null) {
                    failed = true
                    return@execute
                }
                total = result.total
                page = result.page.coerceIn(1, totalPages())
                pageItems = result.items.mapNotNull(::toResult)
                if (result.zones.isNotEmpty()) zones = result.zones
            }
        }
    }

    private fun goToPage(target: Int) {
        val next = target.coerceIn(1, totalPages())
        if (next == page && !failed && !loading) return
        loadPage(next)
    }

    private fun selectTab(next: Tab) {
        if (tab == next) return
        tab = next
        page = 1
        scrollOffset = 0
        chipScroll = 0
        when (next) {
            Tab.ALL -> {
                selectedZone = null
                selectedSeason = null
                loadPage(1)
            }
            Tab.ZONES -> {
                selectedZone = null
                loadPage(1)
            }
            Tab.SEASONS -> {
                if (selectedSeason == null) selectedSeason = seasons.firstOrNull()
                if (selectedSeason == null) {
                    pageItems = emptyList()
                    total = 0
                    loading = false
                    failed = false
                } else {
                    loadPage(1)
                }
            }
        }
    }

    private fun selectChip(index: Int) {
        val chip = chipList().getOrNull(index) ?: return
        when (tab) {
            Tab.ZONES -> {
                selectedZone = chip.zone
                page = 1
                scrollOffset = 0
                loadPage(1)
            }
            Tab.SEASONS -> {
                selectedSeason = chip.season ?: return
                page = 1
                scrollOffset = 0
                loadPage(1)
            }
            Tab.ALL -> Unit
        }
    }

    private fun chipList(): List<Chip> = when (tab) {
        Tab.ALL -> emptyList()
        Tab.ZONES -> buildList {
            add(Chip(tr("chip_all", "All"), allTotal))
            for (zone in zones) add(Chip(zone.name, zone.count, zone = zone))
        }
        Tab.SEASONS -> seasons.map {
            Chip(it.name.removePrefix("合集·").trim(), it.total, season = it)
        }
    }

    private fun chipSelected(chip: Chip): Boolean = when (tab) {
        Tab.ZONES -> if (chip.zone == null) selectedZone == null else selectedZone?.tid == chip.zone.tid
        Tab.SEASONS -> chip.season != null && selectedSeason?.seasonId == chip.season.seasonId
        Tab.ALL -> false
    }

    private fun tabLabels(): List<String> {
        val all = if (allTotal > 0) "${tr("tab_all", "All")} ${formatCount(allTotal)}" else tr("tab_all", "All")
        return listOf(
            all,
            "${tr("tab_seasons", "Collections")} ${seasons.size}",
            "${tr("tab_zones", "Zones")} ${zones.size}",
        )
    }

    private fun metrics(f: Font): Metrics {
        val bannerH = 180
        val headerBottom = bannerH + 72
        val tabsY = headerBottom + 8
        val tabsH = 24
        val chipsH = if (tab == Tab.ALL) 0 else 28
        val chipsY = tabsY + tabsH + if (chipsH > 0) 4 else 0
        val gridTop = tabsY + tabsH + if (chipsH > 0) chipsH + 8 else 8
        val pagerY = max(gridTop + 72, height - 44)
        val gridBottom = max(gridTop + 60, pagerY - 8)

        val labels = tabLabels()
        var tabX = 32
        val tabRects = labels.map { label ->
            val w = max(76, f.width(label) + 24)
            val rect = UiRect(tabX, tabsY, w, tabsH)
            tabX += w + 8
            rect
        }

        val chips = chipList()
        val chipWidths = chips.map { max(74, min(210, f.width(chipLabel(it)) + 20)) }
        val chipTotalW = chipWidths.sum() + 8 * (chipWidths.size - 1).coerceAtLeast(0)
        val chipMaxScroll = max(0, chipTotalW - (width - 64))
        chipScroll = chipScroll.coerceIn(0, chipMaxScroll)
        var chipX = 32 - chipScroll
        val chipRects = chips.indices.map { i ->
            val rect = UiRect(chipX, chipsY, chipWidths[i], chipsH)
            chipX += chipWidths[i] + 8
            rect
        }

        val contentW = max(200, width - 64)
        val gap = 18
        val columns = when {
            contentW >= 900 -> 6
            contentW >= 700 -> 4
            else -> max(2, contentW / 220)
        }
        val cardW = max(100, (contentW - gap * (columns - 1)) / columns)
        val thumbH = max(50, cardW * 9 / 16)
        val cardH = thumbH + 8 + 2 * (f.lineHeight + 2) + 2 + f.lineHeight + 6
        val rows = if (pageItems.isEmpty()) 0 else (pageItems.size + columns - 1) / columns
        val contentH = if (rows == 0) f.lineHeight + 12 else rows * (cardH + gap) - gap

        val prevRect = UiRect(32, pagerY + 6, 64, 22)
        val nextRect = UiRect(prevRect.right + 8, pagerY + 6, 64, 22)
        val pageBoxRect = UiRect(nextRect.right + 22, pagerY + 6, 52, 22)
        return Metrics(
            bannerH = bannerH,
            headerBottom = headerBottom,
            tabsY = tabsY,
            tabsH = tabsH,
            chipsY = chipsY,
            chipsH = chipsH,
            gridTop = gridTop,
            gridBottom = gridBottom,
            pagerY = pagerY,
            columns = columns,
            cardW = cardW,
            thumbH = thumbH,
            cardH = cardH,
            contentH = contentH,
            tabRects = tabRects,
            chipRects = chipRects,
            prevRect = prevRect,
            nextRect = nextRect,
            pageBoxRect = pageBoxRect,
            chipMaxScroll = chipMaxScroll,
        )
    }

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        val f = Minecraft.getInstance().font
        val m = metrics(f)
        val maxOff = max(0, m.contentH - (m.gridBottom - m.gridTop))
        scrollOffset = scrollOffset.coerceIn(0, maxOff)
        g.fill(0, 0, width, height, 0xFF0B0F17.toInt())

        drawBanner(g, m)
        drawHeader(g, f, m, mouseX, mouseY)
        drawTabs(g, f, m, mouseX, mouseY)
        drawChips(g, f, m, mouseX, mouseY)
        drawGrid(g, f, m, mouseX, mouseY)
        drawPager(g, f, m, mouseX, mouseY)
    }

    private fun drawBanner(g: GuiGraphicsCompat, m: Metrics) {
        g.fillVGradient(0, 0, width, m.bannerH, 0xFF1E2A44.toInt(), 0xFF0D1420.toInt())
    }

    private fun drawHeader(g: GuiGraphicsCompat, f: Font, m: Metrics, mouseX: Int, mouseY: Int) {
        val profile = BilibiliUpProfileCache.let {
            it.request(uploader.mid)
            it.known(uploader.mid)
        }
        val avatar = 96
        val ax = 32
        val ay = m.bannerH - 48
        val avatarUrl = profile?.face ?: uploader.avatarUrl
        if (!avatarUrl.isNullOrBlank()) {
            if (Thumbnails.get(avatarUrl) == null) Thumbnails.request(avatarUrl, avatarUrl)
            val tex = Thumbnails.get(avatarUrl)
            if (tex != null) blitAvatar(g, tex, avatarUrl, ax, ay, avatar)
            else g.fill(ax, ay, ax + avatar, ay + avatar, UiTheme.PLACEHOLDER_BG)
        } else {
            g.fill(ax, ay, ax + avatar, ay + avatar, UiTheme.PLACEHOLDER_BG)
        }
        g.drawOutline(UiRect(ax - 2, ay - 2, avatar + 4, avatar + 4), UiTheme.PANEL_BORDER)

        val textX = ax + avatar + 24
        val textW = max(200, width - textX - 32)
        val nameScale = 22f / f.lineHeight
        val bodyScale = 13f / f.lineHeight
        val bodyLine = (f.lineHeight * bodyScale).roundToInt()
        val name = profile?.name?.takeIf { it.isNotBlank() } ?: uploader.name
        val level = profile?.level?.takeIf { it > 0 }
        val levelLabel = level?.let { "LV$it" }
        val levelW = levelLabel?.let { f.width(it) + 10 } ?: 0
        val nameMaxW = ((textW - levelW - 16) / nameScale).toInt().coerceAtLeast(40)
        val shownName = UiText.trim(f, name, nameMaxW)
        val nameY = ay + 4
        drawScaledText(g, f, shownName, textX, nameY, nameScale, UiTheme.TEXT_PRIMARY, true)
        var badgeX = textX + (f.width(shownName) * nameScale).roundToInt() + 8
        if (uploader.verified || !profile?.official.isNullOrBlank()) {
            g.drawVerifiedBadge(badgeX, nameY + 6, 14, UiTheme.ACCENT)
        }
        if (levelLabel != null) {
            val lx = width - 32 - levelW
            val ly = nameY + 2
            g.fill(lx, ly, lx + levelW, ly + 18, UiTheme.ACCENT_BILIBILI_TAG)
            g.drawText(f, levelLabel, lx + 5, ly + 4, UiTheme.TEXT_PRIMARY, false)
        }

        val sign = profile?.sign?.takeIf { it.isNotBlank() } ?: uploader.signature.orEmpty()
        val signText = sign.ifBlank {
            Component.translatableWithFallback(
                "dreamdisplayx.suggestions.profile.no_signature", "No signature",
            ).string
        }
        var sy = nameY + 28
        sy = drawWrappedScaled(g, f, signText, textX, sy, textW, 3, bodyScale, UiTheme.TEXT_SECONDARY) + 2

        val fans = profile?.fans ?: uploader.fans ?: 0L
        val following = profile?.following ?: 0L
        val videoCount = profile?.videos ?: uploader.videoCount ?: 0L
        val plays = profile?.plays ?: 0L
        val likes = profile?.likes ?: 0L
        val elec = profile?.elec ?: 0L
        val guards = profile?.guards ?: 0L
        val stat1 = Component.translatableWithFallback(
            "dreamdisplayx.suggestions.profile.stats", "%s fans · %s videos",
            compactCount(fans), compactCount(videoCount),
        ).string
        drawScaledText(
            g, f, UiText.trim(f, stat1, (textW / bodyScale).toInt()), textX, sy, bodyScale, UiTheme.TEXT_META, false,
        )
        sy += bodyLine

        val stat2 = buildList {
            if (following > 0L) add(
                Component.translatableWithFallback(
                    "dreamdisplayx.suggestions.profile.following", "%s following", compactCount(following),
                ).string,
            )
            if (plays > 0L) add(
                Component.translatableWithFallback(
                    "dreamdisplayx.suggestions.profile.plays", "%s plays", compactCount(plays),
                ).string,
            )
            if (likes > 0L) add(
                Component.translatableWithFallback(
                    "dreamdisplayx.suggestions.profile.likes", "%s likes", compactCount(likes),
                ).string,
            )
        }.joinToString(" · ")
        if (stat2.isNotBlank()) {
            drawScaledText(
                g, f, UiText.trim(f, stat2, (textW / bodyScale).toInt()), textX, sy, bodyScale, UiTheme.TEXT_META, false,
            )
            sy += bodyLine
        }

        // Charging / guard counts get their own line above the honour tags. Both are exact counts
        // people ask about, so they stay unabbreviated.
        val support = buildList {
            if (elec > 0L) add(
                Component.translatableWithFallback(
                    "dreamdisplayx.suggestions.profile.elec", "%s charging", formatExact(elec),
                ).string,
            )
            if (guards > 0L) add(
                Component.translatableWithFallback(
                    "dreamdisplayx.suggestions.profile.guards", "%s guards", formatExact(guards),
                ).string,
            )
        }.joinToString(" · ")
        if (support.isNotBlank()) {
            drawScaledText(
                g, f, UiText.trim(f, support, (textW / bodyScale).toInt()), textX, sy, bodyScale, UiTheme.TEXT_META, false,
            )
            sy += bodyLine
        }

        val tags = buildList {
            profile?.official?.takeIf { it.isNotBlank() }?.let(::add)
            profile?.vip?.takeIf { it.isNotBlank() }?.let(::add)
            profile?.ipLocation?.takeIf { it.isNotBlank() }?.let(::add)
            profile?.school?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" · ")
        if (tags.isNotBlank()) {
            drawScaledText(
                g, f, UiText.trim(f, tags, (textW / bodyScale).toInt()), textX, sy, bodyScale, UiTheme.TEXT_DIM, false,
            )
            sy += bodyLine
        }

        // Live badge closes the header block: a chip aligned with the stats / tags column, clickable to
        // switch the display to this room. It hugs the tags so the block leaves no empty strip above.
        val roomId = profile?.roomId ?: 0L
        if (profile?.liveStatus == 1 && roomId > 0L) {
            val label = Component.translatableWithFallback(
                "dreamdisplayx.uploader_profile.live_now", "Live Now",
            ).string
            val textW = (f.width(label) * bodyScale).roundToInt()
            val textH = (f.lineHeight * bodyScale).roundToInt()
            val chipW = textW + 24
            val chipH = LIVE_CHIP_H
            val chipX = textX
            val chipY = sy + 2
            val rect = UiRect(chipX, chipY, chipW, chipH)
            liveChipRect = rect
            hoveredLive = rect.contains(mouseX, mouseY)
            g.fill(chipX, chipY, chipX + chipW, chipY + chipH, if (hoveredLive) LIVE_CHIP_HOVER else LIVE_CHIP_BG)
            g.drawOutline(rect, LIVE_CHIP_BORDER)
            drawScaledText(
                g, f, label, chipX + (chipW - textW) / 2, chipY + (chipH - textH) / 2,
                bodyScale, UiTheme.TEXT_PRIMARY, false,
            )
        } else {
            liveChipRect = null
            hoveredLive = false
        }
    }

    private fun drawTabs(g: GuiGraphicsCompat, f: Font, m: Metrics, mouseX: Int, mouseY: Int) {
        val labels = tabLabels()
        hoveredTab = -1
        m.tabRects.forEachIndexed { i, rect ->
            val hover = rect.contains(mouseX, mouseY)
            if (hover) hoveredTab = i
            val selected = i == tab.ordinal
            if (selected) {
                g.fill(rect.x, rect.y, rect.right, rect.bottom, 0x3300A1D6)
            } else if (hover) {
                g.fill(rect.x, rect.y, rect.right, rect.bottom, UiTheme.HOVER_FILL)
            } else {
                g.fill(rect.x, rect.y, rect.right, rect.bottom, UiTheme.CARD_BG)
            }
            g.drawOutline(rect, if (selected) UiTheme.ACCENT_BILIBILI_TAG else UiTheme.PANEL_BORDER)
            val label = UiText.trim(f, labels[i], rect.w - 12)
            g.drawText(
                f, label, rect.x + (rect.w - f.width(label)) / 2, rect.y + (rect.h - f.lineHeight) / 2,
                if (selected || hover) UiTheme.TEXT_PRIMARY else UiTheme.TEXT_DIM, false,
            )
        }
    }

    private fun drawChips(g: GuiGraphicsCompat, f: Font, m: Metrics, mouseX: Int, mouseY: Int) {
        hoveredChip = -1
        if (tab == Tab.ALL || m.chipsH <= 0) return
        val chips = chipList()
        //? if <1.21.11 {
        g.enableScissorPoseAware(32, m.chipsY, width - 32, m.chipsY + m.chipsH)
        //?} else
        /*g.enableScissor(32, m.chipsY, width - 32, m.chipsY + m.chipsH)*/
        m.chipRects.forEachIndexed { i, rect ->
            val hover = rect.contains(mouseX, mouseY) && mouseX >= 32 && mouseX < width - 32
            if (hover) hoveredChip = i
            val selected = chipSelected(chips[i])
            if (selected) {
                g.fill(rect.x, rect.y, rect.right, rect.bottom, 0x3300A1D6)
            } else if (hover) {
                g.fill(rect.x, rect.y, rect.right, rect.bottom, UiTheme.HOVER_FILL)
            } else {
                g.fill(rect.x, rect.y, rect.right, rect.bottom, UiTheme.CARD_BG)
            }
            g.drawOutline(rect, if (selected) UiTheme.ACCENT_BILIBILI_TAG else UiTheme.PANEL_BORDER)
            val label = UiText.trim(f, chipLabel(chips[i]), rect.w - 12)
            g.drawText(
                f, label, rect.x + (rect.w - f.width(label)) / 2, rect.y + (rect.h - f.lineHeight) / 2,
                if (selected || hover) UiTheme.TEXT_PRIMARY else UiTheme.TEXT_DIM, false,
            )
        }
        g.disableScissor()
    }

    private fun drawGrid(g: GuiGraphicsCompat, f: Font, m: Metrics, mouseX: Int, mouseY: Int) {
        //? if <1.21.11 {
        g.enableScissorPoseAware(0, m.gridTop, width, m.gridBottom)
        //?} else
        /*g.enableScissor(0, m.gridTop, width, m.gridBottom)*/
        if (pageItems.isEmpty()) {
            val message = when {
                loading -> tr("dreamdisplayx.uploader_profile.loading", "Loading videos…")
                failed -> tr("dreamdisplayx.uploader_profile.failed", "Could not load uploader videos")
                tab == Tab.SEASONS && selectedSeason == null && loadingSeasons ->
                    tr("dreamdisplayx.uploader_profile.loading_seasons", "Loading collections…")
                tab == Tab.SEASONS && seasons.isEmpty() ->
                    tr("dreamdisplayx.uploader_profile.no_seasons", "No collections")
                tab == Tab.SEASONS -> tr("dreamdisplayx.uploader_profile.choose_season", "Choose a collection")
                else -> tr("dreamdisplayx.uploader_profile.empty", "This uploader has no videos to show")
            }
            drawCentered(g, f, message, m.gridTop + 24)
        } else {
            hoveredCard = -1
            val gap = 18
            val left = 32
            val top = m.gridTop - scrollOffset
            for (i in pageItems.indices) {
                val row = i / m.columns
                val col = i % m.columns
                val cardX = left + col * (m.cardW + gap)
                val cardY = top + row * (m.cardH + gap)
                if (cardY + m.cardH < m.gridTop || cardY > m.gridBottom) continue
                val hover = mouseX >= cardX && mouseX < cardX + m.cardW &&
                    mouseY >= cardY && mouseY < cardY + m.cardH &&
                    mouseY >= m.gridTop && mouseY < m.gridBottom
                if (hover) hoveredCard = i
                drawVideoCard(g, f, pageItems[i], cardX, cardY, m.cardW, m.thumbH, m.cardH, hover)
            }
        }
        g.disableScissor()
        drawScrollbar(g, m)
    }

    private fun drawPager(g: GuiGraphicsCompat, f: Font, m: Metrics, mouseX: Int, mouseY: Int) {
        g.fill(0, m.pagerY, width, height, 0xFF0B0F17.toInt())
        g.fill(0, m.pagerY, width, m.pagerY + 1, UiTheme.PANEL_BORDER)

        val canPrev = page > 1 && !loading
        val canNext = page < totalPages() && !loading
        hoveredPrev = m.prevRect.contains(mouseX, mouseY)
        hoveredNext = m.nextRect.contains(mouseX, mouseY)
        drawPagerButton(g, f, m.prevRect, tr("dreamdisplayx.uploader_profile.page_prev", "Prev"), canPrev, hoveredPrev)
        drawPagerButton(g, f, m.nextRect, tr("dreamdisplayx.uploader_profile.page_next", "Next"), canNext, hoveredNext)

        g.fill(
            m.pageBoxRect.x, m.pageBoxRect.y, m.pageBoxRect.right, m.pageBoxRect.bottom, UiTheme.CARD_BG,
        )
        g.drawOutline(m.pageBoxRect, UiTheme.PANEL_BORDER)
        val pageLabel = tr("dreamdisplayx.uploader_profile.page_label", "Page")
        val pageSuffix = "/ ${totalPages()} ${tr("dreamdisplayx.uploader_profile.page_suffix", "")}".trim()
        g.drawText(
            f, pageLabel, m.pageBoxRect.x - 8 - f.width(pageLabel),
            m.pageBoxRect.y + (m.pageBoxRect.h - f.lineHeight) / 2, UiTheme.TEXT_SECONDARY, false,
        )
        g.drawText(
            f, pageSuffix, m.pageBoxRect.right + 8,
            m.pageBoxRect.y + (m.pageBoxRect.h - f.lineHeight) / 2, UiTheme.TEXT_SECONDARY, false,
        )

        val totalText = if (loading) {
            tr("dreamdisplayx.uploader_profile.loading", "Loading videos…")
        } else {
            tr("dreamdisplayx.uploader_profile.total_videos", "%s videos", formatCount(total))
        }
        g.drawText(
            f, UiText.trim(f, totalText, max(80, width / 3)),
            width - 32 - f.width(UiText.trim(f, totalText, max(80, width / 3))),
            m.pageBoxRect.y + (m.pageBoxRect.h - f.lineHeight) / 2, UiTheme.TEXT_META, false,
        )
    }

    private fun drawPagerButton(
        g: GuiGraphicsCompat,
        f: Font,
        rect: UiRect,
        label: String,
        enabled: Boolean,
        hovered: Boolean,
    ) {
        val bg = if (enabled && hovered) UiTheme.CARD_BG_HOVER else UiTheme.CARD_BG
        g.fill(rect.x, rect.y, rect.right, rect.bottom, bg)
        g.drawOutline(rect, if (enabled && hovered) UiTheme.CARD_BORDER_HOVER else UiTheme.PANEL_BORDER)
        val text = UiText.trim(f, label, rect.w - 10)
        g.drawText(
            f, text, rect.x + (rect.w - f.width(text)) / 2, rect.y + (rect.h - f.lineHeight) / 2,
            if (enabled) UiTheme.TEXT_PRIMARY else UiTheme.TEXT_DIM, false,
        )
    }

    private fun drawVideoCard(
        g: GuiGraphicsCompat,
        f: Font,
        info: MediaSearchResult,
        x: Int,
        y: Int,
        w: Int,
        thumbH: Int,
        cardH: Int,
        hover: Boolean,
    ) {
        g.fill(x, y, x + w, y + cardH, if (hover) UiTheme.CARD_BG_HOVER else UiTheme.CARD_BG)
        val thumb = Thumbnails.get(info.id)
        if (thumb != null) {
            blitTexture(g, thumb, x, y, w, thumbH)
        } else {
            g.drawShimmer(x, y, x + w, y + thumbH, UiTheme.PLACEHOLDER_BG, UiTheme.PLACEHOLDER_SHIMMER)
            info.thumbnailUrlOverride?.let { Thumbnails.request(info.id, it) }
        }
        if (info.bilibiliUnionVideo) {
            val label = tr("dreamdisplayx.ui.bilibili_union", "Collab")
            val tagW = f.width(label) + 6
            val tagH = f.lineHeight + 4
            g.fill(x + w - tagW - 4, y + 4, x + w - 4, y + 4 + tagH, UiTheme.ACCENT_BILIBILI_VIP)
            g.drawText(f, label, x + w - tagW - 1, y + 6, UiTheme.TEXT_PRIMARY, false)
        }
        val views = info.viewCount?.takeIf { it > 0L }?.let(::compactCount)
        val dur = info.formatDuration()
        if (views != null || dur.isNotEmpty()) {
            g.fillVGradient(x, y + thumbH - max(18, thumbH / 3), x + w, y + thumbH, 0x00000000, 0xB0000000.toInt())
            if (views != null) g.drawText(f, views, x + 6, y + thumbH - f.lineHeight - 4, UiTheme.TEXT_PRIMARY, true)
            if (dur.isNotEmpty()) {
                g.drawText(
                    f, dur, x + w - f.width(dur) - 6, y + thumbH - f.lineHeight - 4, UiTheme.TEXT_PRIMARY, true,
                )
            }
        }
        var ty = y + thumbH + 8
        for (line in UiText.wrap(f, info.title, w - 12, 2)) {
            g.drawText(f, line, x + 6, ty, UiTheme.TEXT_PRIMARY, true)
            ty += f.lineHeight + 2
        }
        val meta = info.publishedText.orEmpty()
        if (meta.isNotBlank()) g.drawText(f, UiText.trim(f, meta, w - 12), x + 6, ty + 1, UiTheme.TEXT_META, false)
        if (hover) g.drawOutline(UiRect(x, y, w, cardH), UiTheme.CARD_BORDER_HOVER)
    }

    private fun drawCentered(g: GuiGraphicsCompat, f: Font, text: String, y: Int) {
        g.drawText(f, text, (width - f.width(text)) / 2, y, UiTheme.TEXT_SECONDARY, false)
    }

    private fun drawScrollbar(g: GuiGraphicsCompat, m: Metrics) {
        val viewportH = m.gridBottom - m.gridTop
        val maxOff = max(0, m.contentH - viewportH)
        if (maxOff <= 0) {
            sbActive = false
            return
        }
        sbActive = true
        val barX = width - 8
        g.fill(barX, m.gridTop, barX + 3, m.gridBottom, UiTheme.SCROLLBAR_TRACK)
        val barH = max(24, (viewportH.toFloat() / (m.contentH + 1) * viewportH).toInt())
        val barY = m.gridTop + (scrollOffset.toFloat() / maxOff * (viewportH - barH)).toInt()
        g.fill(barX, barY, barX + 3, barY + barH, UiTheme.SCROLLBAR_THUMB)
        sbStart = m.gridTop
        sbViewport = viewportH
        sbThumbLen = barH
        sbMaxOff = maxOff
        sbCross = barX
    }

    private fun overScrollbar(mouseX: Double, mouseY: Double): Boolean {
        if (!sbActive) return false
        return mouseY >= sbStart && mouseY <= sbStart + sbViewport &&
            mouseX >= sbCross - 5 && mouseX <= sbCross + 8
    }

    private fun scrollFromPos(mouseY: Double) {
        val travel = sbViewport - sbThumbLen
        if (travel <= 0) {
            scrollOffset = 0
            return
        }
        val rel = (mouseY - sbStart - sbThumbLen / 2.0).coerceIn(0.0, travel.toDouble())
        scrollOffset = ((rel / travel) * sbMaxOff).roundToInt().coerceIn(0, sbMaxOff)
    }

    private fun cardAt(mouseX: Double, mouseY: Double): Int {
        if (pageItems.isEmpty() || loading || failed) return -1
        val f = Minecraft.getInstance().font
        val m = metrics(f)
        val top = m.gridTop - scrollOffset
        val gap = 18
        for (i in pageItems.indices) {
            val row = i / m.columns
            val col = i % m.columns
            val x = 32 + col * (m.cardW + gap)
            val y = top + row * (m.cardH + gap)
            if (mouseX >= x && mouseX < x + m.cardW && mouseY >= y && mouseY < y + m.cardH &&
                mouseY >= m.gridTop && mouseY < m.gridBottom
            ) {
                return i
            }
        }
        return -1
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, dx: Double, dy: Double): Boolean {
        if (!isMouseOver(mouseX, mouseY)) return false
        val f = Minecraft.getInstance().font
        val m = metrics(f)
        if (tab != Tab.ALL && mouseY >= m.chipsY && mouseY <= m.chipsY + m.chipsH) {
            if (m.chipMaxScroll <= 0) return false
            chipScroll = (chipScroll - (dy * 48).toInt()).coerceIn(0, m.chipMaxScroll)
            return true
        }
        if (mouseY < m.gridTop || mouseY >= m.gridBottom) return false
        val maxOff = max(0, m.contentH - (m.gridBottom - m.gridTop))
        if (maxOff <= 0) return false
        scrollOffset = (scrollOffset - (dy * 48).toInt()).coerceIn(0, maxOff)
        return true
    }

    //? if >=1.21.11 {
    override fun mouseClicked(event: MouseButtonEvent, dbl: Boolean): Boolean {
        val mx = event.x()
        val my = event.y()
        if (MouseButtons.isLeft(event.button()) && overScrollbar(mx, my)) {
            draggingScrollbar = true
            scrollFromPos(my)
            return true
        }
        return handleClick(mx, my)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (draggingScrollbar) {
            scrollFromPos(event.y())
            return true
        }
        return false
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (draggingScrollbar) {
            draggingScrollbar = false
            return true
        }
        return false
    }
    //?} else
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (MouseButtons.isLeft(button) && overScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true
            scrollFromPos(mouseY)
            return true
        }
        return handleClick(mouseX, mouseY)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (draggingScrollbar) {
            scrollFromPos(mouseY)
            return true
        }
        return false
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (draggingScrollbar) {
            draggingScrollbar = false
            return true
        }
        return false
    }*/

    private fun handleClick(mouseX: Double, mouseY: Double): Boolean {
        val f = Minecraft.getInstance().font
        val m = metrics(f)
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        val liveRect = liveChipRect
        if (liveRect != null && liveRect.contains(mx, my)) {
            val roomId = BilibiliUpProfileCache.known(uploader.mid)?.roomId ?: 0L
            if (roomId > 0L) onPlayLive?.invoke(roomId)
            return true
        }
        if (m.prevRect.contains(mx, my) && page > 1 && !loading) {
            goToPage(page - 1)
            return true
        }
        if (m.nextRect.contains(mx, my) && page < totalPages() && !loading) {
            goToPage(page + 1)
            return true
        }
        val tabIndex = m.tabRects.indexOfFirst { it.contains(mx, my) }
        if (tabIndex >= 0) {
            selectTab(Tab.entries[tabIndex])
            return true
        }
        if (tab != Tab.ALL && mouseY >= m.chipsY && mouseY <= m.chipsY + m.chipsH &&
            mouseX >= 32 && mouseX < width - 32
        ) {
            val chipIndex = m.chipRects.indexOfFirst { it.contains(mx, my) }
            if (chipIndex >= 0) {
                selectChip(chipIndex)
                return true
            }
        }
        val index = cardAt(mouseX, mouseY)
        if (index in pageItems.indices) {
            onPick(pageItems[index])
            return true
        }
        return false
    }

    private fun toResult(item: BilibiliSearchItem): MediaSearchResult? {
        val bvid = item.bvid ?: return null
        val url = "https://www.bilibili.com/video/$bvid"
        val publishedEpoch = item.publishedAtEpochSec
        return MediaSearchResult(
            id = url,
            title = item.title,
            uploader = item.uploader ?: uploader.name,
            durationSec = item.durationSec,
            viewCount = item.viewCount,
            watchUrlOverride = url,
            thumbnailUrlOverride = item.thumbnailUrl,
            platform = MediaPlatform.BILIBILI,
            bilibiliMediaType = item.mediaType,
            bilibiliAccess = item.accessLabel,
            bilibiliUnionVideo = item.isUnionVideo,
            uploaderId = uploader.mid,
            publishedText = publishedEpoch?.let {
                java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            },
            publishedDaysAgo = publishedEpoch?.let {
                ((System.currentTimeMillis() / 1000L - it) / 86_400L).toInt().coerceAtLeast(0)
            },
        )
    }

    private fun drawScaledText(
        g: GuiGraphicsCompat,
        f: Font,
        text: String,
        x: Int,
        y: Int,
        scale: Float,
        color: Int,
        shadow: Boolean,
    ) {
        if (text.isEmpty()) return
        val pose = g.pose()
        //? if >=1.21.11 {
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(scale, scale)
        g.drawText(f, text, 0, 0, color, shadow)
        pose.popMatrix()
        //?} else
        /*pose.pushPose()
        pose.translate(x.toDouble(), y.toDouble(), 0.0)
        pose.scale(scale, scale, 1f)
        g.drawText(f, text, 0, 0, color, shadow)
        pose.popPose()*/
    }

    private fun drawWrappedScaled(
        g: GuiGraphicsCompat,
        f: Font,
        text: String,
        x: Int,
        y: Int,
        maxW: Int,
        maxLines: Int,
        scale: Float,
        color: Int,
    ): Int {
        if (text.isBlank()) return y
        val lines = UiText.wrap(f, text, (maxW / scale).toInt().coerceAtLeast(1), maxLines)
        var cursor = y
        val step = (f.lineHeight * scale).roundToInt()
        for (line in lines) {
            drawScaledText(g, f, line, x, cursor, scale, color, false)
            cursor += step
        }
        return cursor
    }

    private fun chipLabel(chip: Chip): String =
        "${chip.label} ${formatCount(chip.count)}"

    private fun tr(key: String, fallback: String, vararg args: Any): String {
        val fullKey = if (key.startsWith("dreamdisplayx.")) key else "dreamdisplayx.uploader_profile.$key"
        return Component.translatableWithFallback(fullKey, fallback, *args).string
    }

    private fun compactCount(value: Long): String = when {
        value >= 1_000_000_000L -> String.format("%.1fB", value / 1_000_000_000.0)
        value >= 1_000_000L -> String.format("%.1fM", value / 1_000_000.0)
        value >= 1_000L -> String.format("%.1fK", value / 1_000.0)
        else -> value.toString()
    }

    private fun formatCount(value: Int): String = String.format("%,d", value)

    /** Exact count with thousand separators, for figures that must not be rounded. */
    private fun formatExact(value: Long): String = String.format("%,d", value)

    private fun blitTexture(g: GuiGraphicsCompat, id: Identifier, x: Int, y: Int, w: Int, h: Int) {
        //? if >=1.21.11 {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, w, h)
        //?} else
        /*g.blit(id, x, y, 0f, 0f, w, h, w, h)*/
    }

    /** Center-crops the avatar to a square before drawing; Bilibili face URLs are not always square. */
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

    private companion object {
        const val PAGE_SIZE = 30

        /** Height of the live badge chip. */
        const val LIVE_CHIP_H = 18

        /** Bilibili pink badge fill, its hover state and its outline. */
        const val LIVE_CHIP_BG = 0xCCFB7299.toInt()
        const val LIVE_CHIP_HOVER = 0xFFFB7299.toInt()
        const val LIVE_CHIP_BORDER = 0x66FFFFFF
    }
}
