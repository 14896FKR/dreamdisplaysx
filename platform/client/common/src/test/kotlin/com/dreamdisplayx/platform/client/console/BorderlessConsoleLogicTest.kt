package com.dreamdisplayx.platform.client.console

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BorderlessConsoleLogicTest {

    @Test
    fun `dev word toggles the feature off and is case insensitive`() {
        assertEquals(ConsoleCommand.Disable, BorderlessConsoleParser.parse("dev"))
        assertEquals(ConsoleCommand.Disable, BorderlessConsoleParser.parse("  Dev  "))
        assertEquals(ConsoleCommand.Disable, BorderlessConsoleParser.parse("DEV"))
    }

    @Test
    fun `playback verbs map to their commands`() {
        assertEquals(ConsoleCommand.Play, BorderlessConsoleParser.parse("play"))
        assertEquals(ConsoleCommand.Pause, BorderlessConsoleParser.parse("pause"))
        assertEquals(ConsoleCommand.TogglePlay, BorderlessConsoleParser.parse("p"))
        assertEquals(ConsoleCommand.TogglePlay, BorderlessConsoleParser.parse("toggle"))
        assertEquals(ConsoleCommand.Exit, BorderlessConsoleParser.parse("exit"))
        assertEquals(ConsoleCommand.Help, BorderlessConsoleParser.parse("help"))
        assertEquals(ConsoleCommand.Blank, BorderlessConsoleParser.parse("   "))
    }

    @Test
    fun `part jump accepts p prefixed and page forms`() {
        assertEquals(ConsoleCommand.PartTo(2), BorderlessConsoleParser.parse("P2"))
        assertEquals(ConsoleCommand.PartTo(12), BorderlessConsoleParser.parse("p12"))
        assertEquals(ConsoleCommand.PartTo(3), BorderlessConsoleParser.parse("p 3"))
        assertEquals(ConsoleCommand.PartTo(3), BorderlessConsoleParser.parse("page 3"))
        // A bare `p` keeps toggling playback, and part numbers start at 1.
        assertEquals(ConsoleCommand.TogglePlay, BorderlessConsoleParser.parse("p"))
        assertEquals(ConsoleCommand.Unknown("p0"), BorderlessConsoleParser.parse("p0"))
        assertEquals(ConsoleCommand.Unknown("page x"), BorderlessConsoleParser.parse("page x"))
    }

    @Test
    fun `seek accepts relative clock and percent forms`() {
        assertEquals(ConsoleCommand.SeekRelative(30_000), BorderlessConsoleParser.parse("seek +30"))
        assertEquals(ConsoleCommand.SeekRelative(-90_000), BorderlessConsoleParser.parse("seek -1:30"))
        assertEquals(ConsoleCommand.SeekTo(90_000), BorderlessConsoleParser.parse("seek 1:30"))
        assertEquals(ConsoleCommand.SeekTo(3_723_000), BorderlessConsoleParser.parse("seek 1:02:03"))
        assertEquals(ConsoleCommand.SeekPercent(40), BorderlessConsoleParser.parse("seek 40%"))
    }

    @Test
    fun `seek rejects malformed arguments`() {
        assertTrue(BorderlessConsoleParser.parse("seek") is ConsoleCommand.Unknown)
        assertTrue(BorderlessConsoleParser.parse("seek abc") is ConsoleCommand.Unknown)
    }

    @Test
    fun `volume accepts set and step forms`() {
        assertEquals(ConsoleCommand.VolumeSet(50), BorderlessConsoleParser.parse("vol 50"))
        assertEquals(ConsoleCommand.VolumeSet(200), BorderlessConsoleParser.parse("volume 500"))
        assertEquals(ConsoleCommand.VolumeDelta(10), BorderlessConsoleParser.parse("vol +10"))
        assertEquals(ConsoleCommand.VolumeDelta(-10), BorderlessConsoleParser.parse("vol -10"))
        assertTrue(BorderlessConsoleParser.parse("vol nope") is ConsoleCommand.Unknown)
    }

    @Test
    fun `mute accepts toggle and explicit states`() {
        assertEquals(ConsoleCommand.MuteToggle, BorderlessConsoleParser.parse("mute"))
        assertEquals(ConsoleCommand.Mute(true), BorderlessConsoleParser.parse("mute on"))
        assertEquals(ConsoleCommand.Mute(false), BorderlessConsoleParser.parse("mute off"))
    }

    @Test
    fun `unknown input is echoed back`() {
        assertEquals(ConsoleCommand.Unknown("dance"), BorderlessConsoleParser.parse("dance"))
    }

    @Test
    fun `volume converts between the ui scale and the internal range`() {
        assertEquals(50, VolumeMath.toUiPercent(0.25f))
        assertEquals(200, VolumeMath.toUiPercent(1f))
        assertEquals(0.25f, VolumeMath.fromUiPercent(50))
        assertEquals(0f, VolumeMath.fromUiPercent(-20))
        assertEquals(1f, VolumeMath.fromUiPercent(999))
    }

    @Test
    fun `volume steps snap to the slider grid and clamp`() {
        assertEquals(0.275f, VolumeMath.step(0.25f, 5))
        assertEquals(0.225f, VolumeMath.step(0.25f, -5))
        assertEquals(0f, VolumeMath.step(0.01f, -5))
        assertEquals(1f, VolumeMath.step(0.99f, 5))
    }

    @Test
    fun `clock formatting switches to hours above sixty minutes`() {
        assertEquals("0:05", PlaybackClock.format(5_000))
        assertEquals("1:23", PlaybackClock.format(83_000))
        assertEquals("1:02:03", PlaybackClock.format(3_723_000))
        assertEquals("0:00", PlaybackClock.format(-5_000))
    }

    @Test
    fun `state seeds once and toggles afterwards`() {
        BorderlessConsoleState.seedIfNeeded(true)
        assertTrue(BorderlessConsoleState.isEnabled)
        BorderlessConsoleState.seedIfNeeded(false)
        assertTrue(BorderlessConsoleState.isEnabled, "seeding twice must keep the runtime value")
        assertFalse(BorderlessConsoleState.toggle())
        assertTrue(BorderlessConsoleState.toggle())
    }
}
