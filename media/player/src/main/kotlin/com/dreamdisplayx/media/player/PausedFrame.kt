package com.dreamdisplayx.media.player

import java.nio.ByteBuffer

/**
 * A copy of the newest delivered video frame, in the same pixel format the popout sinks receive.
 * Surfaces created after playback paused or ended use it to show a picture instead of black.
 */
data class PausedFrame(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
)
