package com.dreamdisplayx.media.player.managers

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-JVM tests for the A/V decision path. No libvlc, no audio device: every branch that once
 * caused an audible loop (acting on a stalled clock, or chasing clock noise) is reproduced here.
 */
class AvSyncControllerTest {
    private fun obs(
        videoMs: Long = 10_000L,
        errMs: Long,
        advancing: Boolean = true,
        ready: Boolean = true,
        nowNanos: Long = 0L,
    ) = AvSyncController.Observation(videoMs, errMs, advancing, ready, nowNanos)

    @Test
    fun `never acts without a usable audio clock`() {
        val c = AvSyncController()
        assertEquals(AvSyncController.Action.NONE, c.onObservation(obs(errMs = -5_000, ready = false)).action)
    }

    @Test
    fun `never acts on an unset video clock`() {
        val c = AvSyncController()
        assertEquals(AvSyncController.Action.NONE, c.onObservation(obs(videoMs = -1, errMs = -5_000)).action)
    }

    @Test
    fun `never acts on a stalled video clock`() {
        val c = AvSyncController()
        repeat(200) { i ->
            val d = c.onObservation(obs(videoMs = 548_851, errMs = 5_000, advancing = false, nowNanos = i * 5_000_000_000L))
            assertEquals(AvSyncController.Action.NONE, d.action, "stalled clock must not trigger action #$i")
        }
    }

    @Test
    fun `clock noise up to the tolerance never triggers an action`() {
        val c = AvSyncController()
        listOf(-122L, 125L, 130L, 250L, -250L, 340L).forEachIndexed { i, err ->
            val d = c.onObservation(obs(errMs = err, nowNanos = i * 5_000_000_000L))
            assertEquals(AvSyncController.Action.NONE, d.action, "noise $err must not act")
        }
    }

    @Test
    fun `a single sample beyond tolerance still waits`() {
        val c = AvSyncController()
        assertEquals(AvSyncController.Action.NONE, c.onObservation(obs(errMs = -5_000, nowNanos = 1_000_000_000L)).action)
    }

    @Test
    fun `two consecutive samples act, and then it settles`() {
        val c = AvSyncController()
        assertEquals(AvSyncController.Action.NONE, c.onObservation(obs(errMs = -600, nowNanos = 1_000_000_000L)).action)
        val second = c.onObservation(obs(errMs = -600, nowNanos = 2_000_000_000L))
        assertEquals(AvSyncController.Action.SKIP, second.action)
        assertEquals(400L, second.amountMs)
        assertEquals(
            AvSyncController.Action.NONE,
            c.onObservation(obs(errMs = -600, nowNanos = 2_500_000_000L)).action,
        )
    }

    @Test
    fun `ahead repeats samples instead of skipping`() {
        val c = AvSyncController()
        c.onObservation(obs(errMs = 500, nowNanos = 1_000_000_000L))
        val d = c.onObservation(obs(errMs = 500, nowNanos = 2_000_000_000L))
        assertEquals(AvSyncController.Action.INSERT, d.action)
        assertEquals(400L, d.amountMs)
    }

    @Test
    fun `persistent large error repositions`() {
        val c = AvSyncController()
        assertEquals(AvSyncController.Action.NONE, c.onObservation(obs(errMs = -5_000, nowNanos = 1_000_000_000L)).action)
        assertEquals(
            AvSyncController.Action.REPOSITION,
            c.onObservation(obs(errMs = -4_900, nowNanos = 2_000_000_000L)).action,
        )
    }

    @Test
    fun `oscillating large samples never reposition`() {
        val c = AvSyncController()
        assertEquals(AvSyncController.Action.NONE, c.onObservation(obs(errMs = 4_000, nowNanos = 1_000_000_000L)).action)
        assertEquals(AvSyncController.Action.NONE, c.onObservation(obs(errMs = -200, nowNanos = 2_000_000_000L)).action)
    }
}
