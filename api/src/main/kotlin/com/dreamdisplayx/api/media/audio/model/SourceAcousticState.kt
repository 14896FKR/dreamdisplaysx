package com.dreamdisplayx.api.media.audio.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Latest known geometry and mix state for one registered source, published every game tick.
 *
 * @since 1.9.x
 */
@DreamDisplaysXUnstableApi
data class SourceAcousticState(
    /** Source plane */
    val plane: SourcePlane,

    /** User volume. */
    val userVolume: Float,

    /** Muted? */
    val muted: Boolean,

    /** True while played through a popout / fullscreen window, where distance no longer applies. */
    val bypassSpatial: Boolean,

    /**
     * True while the borderless overlay fills the viewport: the DSP still spatialises the source, but the
     * wall's distance must not attenuate or muffle it, because the player is looking at the picture as if
     * they stood at the screen.
     */
    val flatDistance: Boolean = false,

    /** Higher-priority sources keep the full DSP chain first when the render budget is exceeded. */
    val priority: Int = 0,

    /** Per-display opt-out; false forces the [AcousticQuality.OFF] legacy path for this source. */
    val acousticsEnabled: Boolean = true,

    /** Latest raytraced acoustic space (occlusion + reverb); defaults to OPEN_AIR. */
    val environment: AcousticEnvironment = AcousticEnvironment.OPEN_AIR,
)
