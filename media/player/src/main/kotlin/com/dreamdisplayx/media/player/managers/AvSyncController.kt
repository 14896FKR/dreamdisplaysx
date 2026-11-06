package com.dreamdisplayx.media.player.managers

import kotlin.math.abs

/** Pulls the audible audio onto the video clock; pure inputs, so headless tests cover it. */
class AvSyncController(
    private val toleranceMs: Long = 350L,
    private val settleAfterActionMs: Long = 1_500L,
    private val maxActionMs: Long = 400L,
    private val repositionThresholdMs: Long = 3_000L,
) {
    enum class Action { NONE, SKIP, INSERT, REPOSITION }

    /**
     * One sample: audibleErrorMs is audible audio minus the video clock (negative = behind), while
     * videoAdvancing and audioReady gate every action and nowNanos drives the settle window.
     */
    data class Observation(
        val videoMs: Long,
        val audibleErrorMs: Long,
        val videoAdvancing: Boolean,
        val audioReady: Boolean,
        val nowNanos: Long,
    )

    data class Decision(val action: Action, val amountMs: Long, val reason: String)

    private var quietUntilNanos = 0L
    private var pendingMs: Long? = null

    fun reset() {
        quietUntilNanos = 0L
        pendingMs = null
    }

    fun onObservation(o: Observation): Decision {
        if (!o.audioReady) return Decision(Action.NONE, 0L, "audio clock not ready")
        if (o.videoMs < 0L) return Decision(Action.NONE, 0L, "video clock unset")
        if (!o.videoAdvancing) return Decision(Action.NONE, 0L, "video clock stalled")
        if (o.nowNanos < quietUntilNanos) return Decision(Action.NONE, 0L, "settling after the last action")

        val err = o.audibleErrorMs
        val magnitude = abs(err)
        if (magnitude <= toleranceMs) {
            pendingMs = null
            return Decision(Action.NONE, 0L, "within ${toleranceMs}ms tolerance")
        }
        val previous = pendingMs
        pendingMs = err
        if (previous == null || abs(previous) <= toleranceMs) {
            return Decision(Action.NONE, 0L, "first sample beyond tolerance, waiting for a second")
        }
        quietUntilNanos = o.nowNanos + settleAfterActionMs * 1_000_000L
        pendingMs = null
        if (magnitude > repositionThresholdMs && abs(previous) > repositionThresholdMs) {
            return Decision(Action.REPOSITION, err, "persistent ${err}ms error")
        }
        val amount = magnitude.coerceAtMost(maxActionMs)
        return if (err < 0L) {
            Decision(Action.SKIP, amount, "audio ${-err}ms behind")
        } else {
            Decision(Action.INSERT, amount, "audio ${err}ms ahead")
        }
    }
}
