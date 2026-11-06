package com.dreamdisplayx.media.player.managers

import kotlin.test.Test
import kotlin.test.assertEquals

class ResumePositionPolicyTest {

    private val duration = 600_000_000_000L

    private fun save(paused: Boolean, parked: Long?, clock: Long, dur: Long = duration) =
        ResumePositionPolicy.forSave(ResumePositionPolicy.SaveInput(paused, parked, clock, dur))

    private fun restore(local: Long, localUrl: String?, server: Long, url: String?, current: String? = url) =
        ResumePositionPolicy.forRestore(
            ResumePositionPolicy.RestoreInput(local, localUrl, server, url, current, duration),
        )

    @Test
    fun `saving while playing keeps the live clock`() {
        assertEquals(123_000_000_000L, save(paused = false, parked = null, clock = 123_000_000_000L))
    }

    @Test
    fun `saving while paused keeps the frozen position`() {
        assertEquals(77_000_000_000L, save(paused = true, parked = 77_000_000_000L, clock = 0L))
    }

    @Test
    fun `saving ignores a stale parked position while playing`() {
        assertEquals(200_000_000_000L, save(paused = false, parked = 10_000_000_000L, clock = 200_000_000_000L))
    }

    @Test
    fun `saving a tail position writes zero so the next join starts over`() {
        assertEquals(0L, save(paused = false, parked = null, clock = duration - 200_000_000L))
        assertEquals(0L, save(paused = false, parked = null, clock = duration))
    }

    @Test
    fun `saving keeps a position when the duration is unknown`() {
        assertEquals(42_000_000_000L, save(paused = false, parked = null, clock = 42_000_000_000L, dur = 0L))
    }

    @Test
    fun `saving never writes a negative position`() {
        assertEquals(0L, save(paused = true, parked = -1L, clock = -1L))
    }

    @Test
    fun `resuming prefers the local save for the same url`() {
        assertEquals(
            90_000_000_000L,
            restore(local = 90_000_000_000L, localUrl = "a", server = 30_000_000_000L, url = "a"),
        )
    }

    @Test
    fun `resuming falls back to the server position when the local save is for another url`() {
        assertEquals(
            30_000_000_000L,
            restore(local = 90_000_000_000L, localUrl = "old", server = 30_000_000_000L, url = "a"),
        )
    }

    @Test
    fun `resuming ignores a tail position on either side`() {
        // A local tail is finished, so the server's earlier position still wins when it has one.
        assertEquals(5_000_000_000L, restore(local = duration, localUrl = "a", server = 5_000_000_000L, url = "a"))
        assertEquals(0L, restore(local = 0L, localUrl = "a", server = duration, url = "a"))
    }

    @Test
    fun `resuming with nothing stored starts at zero`() {
        assertEquals(0L, restore(local = 0L, localUrl = null, server = 0L, url = "a"))
    }
}
