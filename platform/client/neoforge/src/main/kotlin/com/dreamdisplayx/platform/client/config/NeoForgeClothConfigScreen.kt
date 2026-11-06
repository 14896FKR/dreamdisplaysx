package com.dreamdisplayx.platform.client.config

import com.dreamdisplayx.api.media.audio.model.AcousticQuality
import com.dreamdisplayx.media.player.cdn.BilibiliCdnMirror
import com.dreamdisplayx.platform.client.Config
import com.dreamdisplayx.platform.client.danmaku.DanmakuBlocklistImporter
import com.dreamdisplayx.platform.client.login.BilibiliLoginManager
import com.dreamdisplayx.platform.client.managers.ClientStartupManager
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import com.dreamdisplayx.util.BilibiliCookie
import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.api.ConfigCategory
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder
import me.shedaniel.clothconfig2.gui.entries.StringListEntry
import me.shedaniel.clothconfig2.gui.entries.TooltipListEntry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Optional
import java.util.function.Supplier

/**
 * Cloth Config screen for NeoForge — mirrors the Fabric [ClothConfigScreenProvider] exactly
 * so the same localizable keys work on both platforms. Registered via [IConfigScreenFactory].
 *
 * Requires Cloth Config to be installed; otherwise no in-game config editor is available
 * (the shared `Config.toml` remains the source of truth for manual editing).
 */
internal object NeoForgeClothConfigScreen {

    fun create(parent: Screen?): Screen {
        val config = ClientStateManager.config
        val entryBuilder = ConfigEntryBuilder.create()
        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.translatable(ConfigScreenText.Keys.TITLE))
            .setSavingRunnable { config.save() }

        // Re-run the CDN bandwidth ranking whenever the config page is opened, so the
        // dropdown / tooltips reflect fresh measurements (based on PiliPlus's speed test).
        ClientStartupManager.refreshCdnRanking()

        val general: ConfigCategory = builder.getOrCreateCategory(
            Component.translatable(ConfigScreenText.Keys.CATEGORY_GENERAL),
        )

        // Displays enabled
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.translatable(ConfigScreenText.Keys.DISPLAYS_ENABLED),
                config.displaysEnabled,
            )
                .setDefaultValue(true)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.DISPLAYS_ENABLED_TOOLTIP))
                .setSaveConsumer { v: Boolean -> config.displaysEnabled = v }
                .build(),
        )

        val autoPlayEntry = config.configEntries().single { it.key == "auto-play-on-join" }
        general.addEntry(
            entryBuilder.startBooleanToggle(
                ConfigScreenText.entryLabel(autoPlayEntry),
                config.autoPlayOnJoin,
            )
                .setDefaultValue(false)
                .setTooltip(ConfigScreenText.entryTooltip(autoPlayEntry))
                .setSaveConsumer { v: Boolean -> config.autoPlayOnJoin = v }
                .build(),
        )

        val animatedLoadingEntry = config.configEntries().single { it.key == "animated-loading-screen" }
        general.addEntry(
            entryBuilder.startBooleanToggle(
                ConfigScreenText.entryLabel(animatedLoadingEntry),
                config.animatedLoadingScreen,
            )
                .setDefaultValue(false)
                .setTooltip(ConfigScreenText.entryTooltip(animatedLoadingEntry))
                .setSaveConsumer { v: Boolean -> config.animatedLoadingScreen = v }
                .build(),
        )

        general.addEntry(
            entryBuilder.startBooleanToggle(Component.translatable(ConfigScreenText.Keys.FLASHBACK_RENDER_HUD), config.flashbackRenderHud)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.FLASHBACK_RENDER_HUD_TOOLTIP))
                .setSaveConsumer { v: Boolean -> config.flashbackRenderHud = v }
                .build(),
        )
        general.addEntry(
            entryBuilder.startBooleanToggle(Component.translatable(ConfigScreenText.Keys.FLASHBACK_RENDER_DISPLAYS), config.flashbackRenderDisplays)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.FLASHBACK_RENDER_DISPLAYS_TOOLTIP))
                .setSaveConsumer { v: Boolean -> config.flashbackRenderDisplays = v }
                .build(),
        )
        // Prefer 60fps
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.translatable(ConfigScreenText.Keys.PREFER_FPS60),
                config.preferFps60,
            )
                .setDefaultValue(true)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.PREFER_FPS60_TOOLTIP))
                .setSaveConsumer { v: Boolean -> config.preferFps60 = v }
                .build(),
        )

        // Hardware acceleration (toggle)
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.translatable(ConfigScreenText.Keys.USE_HW_ACCEL),
                config.useHwAccel,
            )
                .setDefaultValue(true)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.USE_HW_ACCEL_TOOLTIP))
                .setSaveConsumer { v: Boolean -> config.useHwAccel = v }
                .build(),
        )

        // Mute on alt-tab
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.translatable(ConfigScreenText.Keys.MUTE_ON_ALT_TAB),
                config.muteOnAltTab,
            )
                .setDefaultValue(false)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.MUTE_ON_ALT_TAB_TOOLTIP))
                .setSaveConsumer { v: Boolean -> config.muteOnAltTab = v }
                .build(),
        )

        // Borderless console (enable + caret mode), same rows as the Fabric screen
        val borderlessConsoleEntry = config.configEntries().single { it.key == "borderless-console" }
        general.addEntry(
            entryBuilder.startBooleanToggle(
                ConfigScreenText.entryLabel(borderlessConsoleEntry),
                config.borderlessConsole,
            )
                .setDefaultValue(false)
                .setTooltip(ConfigScreenText.entryTooltip(borderlessConsoleEntry))
                .setSaveConsumer { v: Boolean -> config.borderlessConsole = v }
                .build(),
        )

        val consoleCaretEntry = config.configEntries().single { it.key == "borderless-console-caret" }
        val caretValues = Config.CONSOLE_CARET_MODES.toTypedArray()
        general.addEntry(
            entryBuilder.startSelector(
                ConfigScreenText.entryLabel(consoleCaretEntry),
                caretValues,
                config.borderlessConsoleCaret.takeIf { it in caretValues } ?: Config.CONSOLE_CARET_SOLID,
            )
                .setDefaultValue(Config.CONSOLE_CARET_SOLID)
                .setNameProvider { v -> ConfigScreenText.caretLabel(v) }
                .setTooltip(ConfigScreenText.entryTooltip(consoleCaretEntry))
                .setSaveConsumer { v: String -> config.borderlessConsoleCaret = v }
                .build(),
        )

        // Binaural audio
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.translatable(ConfigScreenText.Keys.AUDIO_BINAURAL),
                config.audioBinauralOutput,
            )
                .setDefaultValue(true)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.AUDIO_BINAURAL_TOOLTIP))
                .setSaveConsumer { v: Boolean -> config.audioBinauralOutput = v }
                .build(),
        )

        // Default render distance
        general.addEntry(
            entryBuilder.startIntField(
                Component.translatable(ConfigScreenText.Keys.DEFAULT_RENDER_DISTANCE),
                config.defaultDistance,
            )
                .setDefaultValue(96)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.DEFAULT_RENDER_DISTANCE_TOOLTIP))
                .setSaveConsumer { v: Int -> config.defaultDistance = v }
                .build(),
        )

        // Default volume
        general.addEntry(
            entryBuilder.startDoubleField(
                Component.translatable(ConfigScreenText.Keys.DEFAULT_VOLUME),
                config.defaultDisplayVolume,
            )
                .setDefaultValue(0.5)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.DEFAULT_VOLUME_TOOLTIP))
                .setSaveConsumer { v: Double -> config.defaultDisplayVolume = v }
                .build(),
        )

        // Audio acoustics
        general.addEntry(
            entryBuilder.startEnumSelector(
                Component.translatable(ConfigScreenText.Keys.AUDIO_ACOUSTICS),
                AcousticQuality::class.java,
                config.audioAcoustics,
            )
                .setDefaultValue(AcousticQuality.ADVANCED)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.AUDIO_ACOUSTICS_TOOLTIP))
                .setSaveConsumer { v: AcousticQuality -> config.audioAcoustics = v }
                .build(),
        )

        // Bilibili CDN mirror selection (dropdown with all known mirrors + auto/BASE_URL/BACKUP_URL)
        val cdnValues = BilibiliCdnMirror.CONFIG_VALUES.toTypedArray()
        val currentCdn = config.bilibiliCdnMirror.ifBlank { "auto" }
            .let { v -> if (v in BilibiliCdnMirror.CONFIG_VALUES) v else v }
        general.addEntry(
            entryBuilder.startSelector(
                Component.translatable(ConfigScreenText.Keys.CDN_MIRROR),
                cdnValues,
                if (currentCdn in cdnValues) currentCdn else "auto",
            )
                .setDefaultValue("auto")
                .setNameProvider { v -> ConfigScreenText.cdnLabel(v) }
                .setTooltip(Component.translatable(ConfigScreenText.Keys.CDN_MIRROR_TOOLTIP))
                .setSaveConsumer { v: String -> config.bilibiliCdnMirror = v }
                .build(),
        )

        val mergeDanmakuEntry = config.configEntries().single { it.key == "danmaku-merge-duplicates" }
        general.addEntry(
            entryBuilder.startBooleanToggle(
                ConfigScreenText.entryLabel(mergeDanmakuEntry),
                config.danmakuMergeDuplicates,
            )
                .setDefaultValue(true)
                .setTooltip(ConfigScreenText.entryTooltip(mergeDanmakuEntry))
                .setSaveConsumer { v: Boolean -> config.danmakuMergeDuplicates = v }
                .build(),
        )

        val colorfulDanmakuEntry = config.configEntries().single { it.key == "danmaku-show-colorful" }
        general.addEntry(
            entryBuilder.startBooleanToggle(
                ConfigScreenText.entryLabel(colorfulDanmakuEntry),
                config.danmakuShowColorful,
            )
                .setDefaultValue(true)
                .setTooltip(ConfigScreenText.entryTooltip(colorfulDanmakuEntry))
                .setSaveConsumer { v: Boolean -> config.danmakuShowColorful = v }
                .build(),
        )

        val blockWordsEntry = config.configEntries().single { it.key == "danmaku-block-words" }
        val blockWordsField = entryBuilder.startStrField(
            ConfigScreenText.entryLabel(blockWordsEntry),
            DanmakuBlocklistImporter.toSingleLine(config.danmakuBlockWords),
        )
            .setDefaultValue("")
            .setTooltip(ConfigScreenText.entryTooltip(blockWordsEntry))
            .setSaveConsumer { v: String -> config.danmakuBlockWords = v }
            .build()
        general.addEntry(blockWordsField)
        general.addEntry(DanmakuImportButtonEntry { importDanmakuBlocklist(config, blockWordsField) })

        // Manual Bilibili cookie; sent to the server on Save and never persisted client-side.
        general.addEntry(
            entryBuilder.startStrField(
                Component.translatableWithFallback(ConfigScreenText.Keys.BILIBILI_COOKIE, "Bilibili Cookie"),
                "",
            )
                .setDefaultValue("")
                .setTooltip(
                    Component.translatableWithFallback(
                        ConfigScreenText.Keys.BILIBILI_COOKIE_TOOLTIP,
                        "Paste a Bilibili cookie header or JSON, then Save. Requires OP; the value is not stored in the client config.",
                    ),
                )
                .setErrorSupplier { raw: String ->
                    if (raw.isBlank() || BilibiliCookie.hasSession(raw)) Optional.empty<Component>()
                    else Optional.of(
                        Component.translatableWithFallback(
                            ConfigScreenText.Keys.BILIBILI_COOKIE_INVALID,
                            "Cookie has no SESSDATA.",
                        ),
                    )
                }
                .setSaveConsumer { raw: String ->
                    if (raw.isNotBlank()) BilibiliLoginManager.loginWithCookie(raw)
                }
                .build(),
        )

        return builder.build()
    }
}

private fun importDanmakuBlocklist(config: Config, field: StringListEntry) {
    val dialog = FileDialog(null as Frame?, Component.translatable(ConfigScreenText.Keys.DANMAKU_IMPORT).string, FileDialog.LOAD)
    dialog.isVisible = true
    val directory = dialog.directory ?: return
    val selectedName = dialog.file ?: return
    runCatching {
        DanmakuBlocklistImporter.importFile(File(directory, selectedName), config.danmakuBlockWords)
    }.onSuccess { result ->
        if (result.added > 0) {
            val singleLine = DanmakuBlocklistImporter.toSingleLine(result.updatedRaw)
            config.danmakuBlockWords = singleLine
            config.save()
            field.setValue(singleLine)
        }
        showImportMessage(
            Component.translatableWithFallback(
                ConfigScreenText.Keys.DANMAKU_IMPORT_RESULT,
                "Imported %s rules; skipped %s disabled, %s UID, %s duplicate, %s invalid.",
                result.added,
                result.skippedDisabled,
                result.skippedUid,
                result.skippedDuplicate,
                result.skippedInvalid,
            ),
        )
    }.onFailure { error ->
        showImportMessage(
            Component.translatableWithFallback(
                ConfigScreenText.Keys.DANMAKU_IMPORT_ERROR,
                "Import failed: %s",
                error.message ?: "unknown error",
            ),
        )
    }
}

private fun showImportMessage(message: Component) {
    Minecraft.getInstance().player?.displayClientMessage(message, false)
}

private class DanmakuImportButtonEntry(private val onPress: () -> Unit) : TooltipListEntry<Boolean>(
    Component.empty(),
    Supplier {
        Optional.of(
            arrayOf<Component>(
                Component.translatable(ConfigScreenText.Keys.DANMAKU_IMPORT_TOOLTIP),
            ),
        )
    },
) {
    private val button = Button.builder(
        Component.translatable(ConfigScreenText.Keys.DANMAKU_IMPORT),
        { _: Button -> onPress() },
    ).bounds(0, 0, 160, 20).build()

    override fun getValue(): Boolean = false

    override fun getDefaultValue(): Optional<Boolean> = Optional.empty()

    override fun children(): MutableList<GuiEventListener> = mutableListOf(button)

    override fun getItemHeight(): Int = 24

    override fun render(
        guiGraphics: GuiGraphics,
        index: Int,
        y: Int,
        x: Int,
        entryWidth: Int,
        entryHeight: Int,
        mouseX: Int,
        mouseY: Int,
        isHovered: Boolean,
        delta: Float,
    ) {
        button.x = x
        button.y = y + 2
        button.width = entryWidth
        button.height = 20
        button.render(guiGraphics, mouseX, mouseY, delta)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean =
        this.button.mouseClicked(mouseX, mouseY, button)

    override fun narratables(): List<NarratableEntry> = listOf(this.button)
}