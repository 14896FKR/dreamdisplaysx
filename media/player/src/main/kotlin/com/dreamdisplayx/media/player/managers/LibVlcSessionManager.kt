package com.dreamdisplayx.media.player.managers

import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.model.StretchMode
import com.dreamdisplayx.api.media.player.FrameUploaderFactory
import com.dreamdisplayx.api.media.player.GpuTextureRef
import com.dreamdisplayx.api.media.player.RenderExecutor
import com.dreamdisplayx.api.media.stream.model.MediaStream
import com.dreamdisplayx.api.media.audio.service.AudioDspStage
import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.media.player.PausedFrame
import com.dreamdisplayx.media.player.events.PlayerEvents
import com.dreamdisplayx.media.player.pipeline.FrameSurface
import com.dreamdisplayx.media.player.pipeline.PlaybackClock
import com.dreamdisplayx.media.player.stream.ActiveStreams
import com.dreamdisplayx.media.player.stream.MediaStreamSelector
import com.dreamdisplayx.media.player.util.LibVlcMediaOptions
import com.dreamdisplayx.media.runtime.security.MediaHostGuard
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * LibVLC session manager rebuilt to mirror the VideoPlayer mod's low-level libvlc model:
 *
 *  - Desktop keeps one libvlc video player for the whole session and replaces its media
 *    synchronously (`set_media` + `play`), so no JNA callback trampoline is dropped while
 *    libvlc's async teardown could still touch it. Android retires media-bearing players and
 *    creates fresh ones because the VLC-Android TLS teardown path is unsafe to reuse.
 *  - Video is delivered through low-level lock/unlock/display/setup/cleanup callbacks into
 *    a triple-buffered pool (the VideoPlayer `TextureRenderCallback` model). Every callback
 *    is held by a strong field reference for the life of the manager.
 *  - All libvlc control operations run on a single control executor, serialised.
 *  - Playback events (playing/end-reached/error) are delivered through a low-level event
 *    listener, exactly like VideoPlayer.
 *  - Desktop audio is decoded through the Java Sound callback so display acoustics remain available;
 *    Android uses libvlc's OpenSL ES output because `java.desktop` is unavailable there. Separate
 *    audio renditions are replaced on the dedicated audio player without rebuilding desktop video.
 */
internal class LibVlcSessionManager(
    private val debugLabel: String,
    private val clock: PlaybackClock,
    private val events: PlayerEvents,
    private val terminated: AtomicBoolean,

    /** Returns the current GPU texture dimensions (width to height). */
    private val getTextureSize: () -> Pair<Int, Int>,
    private val getStretchMode: () -> StretchMode = { StretchMode.LETTERBOX },

    /** Invoked when the stream ends or errors. */
    private val onStreamEnd: (stderr: String, normalEos: Boolean) -> Unit,

    /** Invoked when quality switch fails before promotion. */
    private val onQualitySwitchAborted: (appliedAnyway: Boolean) -> Unit = {},

    /** Runs render-thread (GL) cleanup work. */
    private val renderExecutor: RenderExecutor,

    /** Creates per-channel GPU frame uploaders. */
    private val uploaderFactory: FrameUploaderFactory,

    /** Whether the GPU-side planar (I420) render path is active. */
    private val gpuYuvActive: Boolean,

    /** Whether hardware-accelerated decoding is enabled by config. */
    private val useHwAccel: Boolean,

    /** Per-display acoustics DSP stage for the desktop Java Sound callback path. */
    audioStage: AudioDspStage? = null,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/LibVlcSession")

    /**
     * Android mode: javax.sound does not exist on Android (no java.desktop), so the
     * Java Sound callback pipeline cannot run there — [LibVlcAudioOutput] is not even
     * instantiated because its method signatures reference `SourceDataLine` and would
     * fail to link. libvlc feeds OpenSL ES directly; muxed media keeps audio on the video
     * player while separate renditions use a dedicated audio player.
     */
    private val systemAudio: Boolean = com.dreamdisplayx.util.OsInfo.isAndroid

    /**
     * Owns the Java Sound audio line + 3D DSP feeding for this display. libvlc is configured with
     * audio callbacks so decoded PCM reaches us instead of libvlc's default output; this restores
     * directional 3D audio (panning), occlusion and our own audio pacing. Null on Android
     * ([systemAudio]): libvlc's own OpenSL ES output plays the sound there.
     */
    private val audioOutput: LibVlcAudioOutput? =
        if (systemAudio) null else LibVlcAudioOutput(debugLabel, audioStage)

    /** Rate-limits the A/V sync diagnostic (audio line clock vs libvlc clock) to once per few seconds. */
    private val lastSyncDiagNanos = java.util.concurrent.atomic.AtomicLong(0L)

    // ── Video FPS debug counter (renders the actual delivered frame rate, for the debug label) ──
    @Volatile
    private var fpsFrames = 0L
    @Volatile
    private var fpsWindowStartNanos = 0L
    @Volatile
    private var fpsValue = 0.0

    /** Delivered-video FPS (frames displayed per second), smoothed over a 1s sliding window. */
    val currentVideoFps: Double get() = fpsValue

    // ── Frame-interval diagnostics (min/avg/max gap between vout displays, in ms) ──
    // Reveals the vout's actual pacing pattern: if min ≈ source-frame interval (e.g. 33ms for 30fps)
    // but avg is ~2x that, libvlc is DROPPING half the frames (clock throttling); if min ≈ avg ≈ the
    // slow value, the decoder itself is only producing frames at that rate.
    @Volatile private var lastDisplayNanos = 0L
    @Volatile private var gapMinMs = 0L
    @Volatile private var gapMaxMs = 0L
    @Volatile private var gapSumMs = 0L
    @Volatile private var gapCount = 0L

    /** "min/avg/max ms" frame-interval summary of the last ~N vout displays; null when no frames yet. */
    fun frameIntervalInfo(): String? {
        if (gapCount <= 0) return null
        val avg = gapSumMs.toDouble() / gapCount
        return "${gapMinMs}/${"%.0f".format(avg)}/${gapMaxMs}ms"
    }

    private fun recordFrameGap() {
        val now = System.nanoTime()
        val prev = lastDisplayNanos
        lastDisplayNanos = now
        if (prev == 0L) return
        val gap = (now - prev) / 1_000_000L
        if (gap <= 0L) return
        if (gapMinMs == 0L || gap < gapMinMs) gapMinMs = gap
        if (gap > gapMaxMs) gapMaxMs = gap
        gapSumMs += gap
        gapCount++
    }

    /**
     * A/V drift threshold (~0.3 s). This is NOT the lip-sync gap (that is the audio buffer, ~0.045 s,
     * and is set in [LibVlcAudioOutput]); it is the point at which a real, sustained drift is declared
     * and the queued audio is flushed to snap the sound back to the video. The threshold must stay well
     * above the normal buffer lead so the auto-resync does not fire on every block of jitter — a 45 ms
     * trial threshold fired almost every diagnostic (the healthy lead already sits at the buffer), and
     * that constant cross-thread `line.flush()` from the render thread, racing the libvlc audio thread's
     * write/stop/resume on the same SourceDataLine, is what crashed the JVM with an access violation on
     * pause/resume. 0.3 s is a safe middle: high enough that healthy jitter never trips it, low enough
     * that a real stall still recovers within a few seconds.
     */
    private val AUTO_RESYNC_THRESHOLD_NANOS = 300_000_000L

    /** Video clock gap that indicates a resumed video restarted at 0 instead of the audio position. */
    private val AV_VIDEO_RESET_GAP_MS = 30_000L

    /** Audible offset allowed after subtracting the PCM line lead. */
    private val AV_SYNC_STARTUP_TOLERANCE_MS = 250L

    /** A correction makes the audio input re-buffer; no further correction inside this window. */
    private val AV_SYNC_SETTLE_AFTER_CORRECTION_MS = 1_200L

    /** Probe offsets after a correction: one inside the re-buffer stall, one at steady state. */
    private val AV_PROBE_STEPS_MS = longArrayOf(250L, 2_000L)

    /**
     * Rolling A/V decision maker (headless-tested), tunable per run for by-ear checks:
     * -Ddreamdisplayx.avToleranceMs=350 and -Ddreamdisplayx.avMaxActionMs=400.
     */
    private val avSync = AvSyncController(
        toleranceMs = System.getProperty("dreamdisplayx.avToleranceMs", "350").toLongOrNull()?.coerceIn(50L, 1_000L) ?: 350L,
        maxActionMs = System.getProperty("dreamdisplayx.avMaxActionMs", "400").toLongOrNull()?.coerceIn(50L, 1_000L) ?: 400L,
    )

    /**
     * Sample-level correction. On by default: nothing else pulls the audible audio back onto the video
     * clock, and the two inputs drift (the log shows offsets of 300-1200 ms otherwise). Pass
     * -Ddreamdisplayx.avServo=false to leave playback untouched.
     */
    private val avServoEnabled = System.getProperty("dreamdisplayx.avServo", "true").toBoolean()

    /** Video clock of the previous observation, used to detect a stalled picture clock. */
    @Volatile
    private var lastAvVideoMs = -1L

    /** Audible-clock sample cadence while playing; the measurement lines in the log come from here. */
    private val AV_SAMPLE_INTERVAL_MS = 5_000L

    /** Beyond this the offset needs no second sample; one sample cannot be a re-buffer artefact. */
    private val AV_SYNC_HARD_ERROR_FLOOR_MS = 800L

    /** A cold audio start beyond this offset is corrected once instead of being kept forever. */
    private val AV_STARTUP_ALIGN_MS = 700L

    /** How many one-shot startup alignments a session (or re-attached input) may use. */
    private val AV_STARTUP_ALIGN_MAX_ATTEMPTS = 2

    /** Quiet window after a startup alignment, so the re-buffered audio settles first. */
    private val AV_STARTUP_ALIGN_SETTLE_NANOS = 1_500_000_000L

    /** A startup alignment only makes sense while the session (or a re-attached audio input) is cold. */
    private val AV_STARTUP_ALIGN_WINDOW_NANOS = 20_000_000_000L

    /** Largest PCM skip the alignment may request when the audio is ahead. */
    private val AV_ALIGN_MAX_SKIP_MS = 5_000L

    /** How close to the media end a frozen picture counts as "stream ended", not "stalled". */
    private val AV_TAIL_END_GUARD_MS = 12_000L

    /** A freshly re-attached audio input reports 0ms for a while; ignore corrections that long. */
    private val AV_AUDIO_ATTACH_GRACE_NANOS = 2_000_000_000L

    /** A reset audio clock reads near 0 while the video is far in; not a real offset. */
    private val AV_AUDIO_RESET_MAX_MS = 1_000L

    /** Change from the initial offset that indicates genuine drift. */
    private val AV_SYNC_DRIFT_TOLERANCE_MS = 120L

    /** Sample-to-sample change below which the offset is stable enough to become the baseline. */
    private val AV_SYNC_STABLE_DELTA_MS = 150L

    /** Large audible error that must be corrected even while the offset is still changing. */
    private val AV_SYNC_HARD_ERROR_MS = 400L

    /** A pause longer than this discards the buffered video input and reopens it at the park point. */
    private val LONG_PAUSE_REOPEN_MS = 5_000L

    /** Audio watchdog: one clock check per second for the whole playback session. */
    private val AUDIO_WATCHDOG_INTERVAL_MS = 1_000L

    /** How often the last delivered frame is kept as a still for late-created surfaces. */
    private val FRAME_SNAPSHOT_INTERVAL_NANOS = 1_000_000_000L

    /** Bounded, rate-limited re-attach attempts across the audio track and CDN candidates. */
    private val AUDIO_REVIVE_MAX_ATTEMPTS = 6L
    private val AUDIO_REVIVE_COOLDOWN_NANOS = 5_000_000_000L
    private val AUDIO_REVIVE_GRACE_NANOS = 6_000_000_000L

    /** How far the audio input may sit from the video clock after a seek before it is rebuilt. */
    private val AUDIO_SEEK_TOLERANCE_MS = 1_500L

    /** Below this video clock a session is still starting, so a dead audio input is expected. */
    private val AUDIO_REVIVE_MIN_VIDEO_MS = 5_000L

    /** Grace after a session start during which a missing audio input is normal (starts after frame 1). */
    private val AUDIO_REVIVE_START_GRACE_NANOS = 3_000_000_000L

    /** Reopens allowed per seek; a source that cannot be positioned must not loop forever. */
    private val VIDEO_REOPEN_MAX_ATTEMPTS = 2L

    /** Full playback rebuilds allowed after the reopen budget is spent; then the session gives up. */
    private val VIDEO_RESTART_MAX_ATTEMPTS = 3L

    /** Delay before a rebuild, so a transient demuxer hiccup is not answered with a restart storm. */
    private val VIDEO_RESTART_BACKOFF_MS = 10_000L

    /** How far a reopened input may land from the seek target before the picture counts as wrong. */
    private val SEEK_LANDING_OFF_TARGET_MS = 5_000L

    /** Bounded set_time re-asserts before a wrong landing is escalated to a rebuild. */
    private val VIDEO_POSITION_FIX_MAX_ATTEMPTS = 3L

    /** Gap between position re-asserts; a fragmented input needs the demuxer running to honour them. */
    private val VIDEO_POSITION_FIX_GAP_MS = 600L

    /**
     * Grace window after an in-place seek during which the A/V snap logic must not drag the audio
     * player back to the video player's stale clock. A far seek flushes the video demuxer for
     * seconds; during that window `get_time` still reports the OLD position while the audio player
     * (audio-only, no vout) has already landed on the target and is playing there. Snapping audio
     * to the video clock then rewinds the whole playback to the pre-seek position — observed as
     * "audio plays at the target for a few seconds, then A/V continue from where it used to be".
     */
    private val SEEK_SETTLE_WINDOW_NANOS = 5_000_000_000L

    /** First landing check delay; fast seeks can settle before verification starts. */
    /**
     * Delay before the landing verifier samples the video clock. It is the floor on how long the
     * picture stays frozen, so it is tunable: `-Ddreamdisplayx.seekVerifyDelayMs=250`.
     */
    private val SEEK_VERIFY_DELAY_NANOS =
        (System.getProperty("dreamdisplayx.seekVerifyDelayMs")?.toLongOrNull() ?: 150L)
            .coerceIn(50L, 2_000L) * 1_000_000L

    /** Maximum distance from the target that still counts as landed. */
    private val SEEK_VERIFY_TOLERANCE_MS = 1_200L

    /** Gap between clock samples used to distinguish a dropped seek from an active flush. */
    private val SEEK_VERIFY_SAMPLE_GAP_NANOS = 200_000_000L

    /** Minimum clock advance across the sample gap that proves old content is still playing. */
    private val SEEK_VERIFY_DROP_ADVANCE_MS = 300L

    /** Bounded verification passes per seek. */
    private val SEEK_VERIFY_MAX_CHECKS = 12

    /** Delay after re-applying set_time before checking its result again. */
    private val SEEK_VERIFY_RECHECK_NANOS = 1_000_000_000L

    /** Fail-open bound for the picture freeze, so a broken seek cannot freeze the display forever. */
    private val SEEK_FREEZE_MAX_NANOS = 4_000_000_000L

    /** Largest forward/backward step handled with an in-place `set_time` instead of an input reopen. */
    private val SMALL_SEEK_MAX_DELTA_MS = 16_000L

    /** How long an in-place seek may take before the input is reopened instead. */
    private val IN_PLACE_SEEK_GRACE_MS = 1_500L

    /** When the newest seek was requested, used only for the `seek timing` line. */
    @Volatile
    private var seekRequestedNanos = 0L

    /** Which path the newest seek took, so the timing line names it. */
    @Volatile
    private var seekModeTag = "in-place"

    /** Generation of the newest seek that has already been settled, so the fallback never reopens it. */
    @Volatile
    private var seekSettledGeneration = -1L

    /** Wall deadline (System.nanoTime) until which A/V snapping is suppressed after a seek. */
    @Volatile
    private var seekSettleUntilNanos = 0L

    /**
     * Monotonic id of the newest seek. A verification task started for an older id is stale and
     * must not re-apply set_time (a newer seek superseded it). Read at task start, re-checked at
     * every pass.
     */
    private val seekGeneration = AtomicLong(0)

    /**
     * Picture freeze while a seek settles: while armed and the video clock is still far from the
     * target, [TextureRenderCallback.publishFrame] drops stale pre-seek frames so the user does
     * not watch old content "like another player" while the audio player is already at the target.
     * The last displayed frame simply stays on screen until the decoder resumes past the new
     * position (the intended hard-switch UX).
     */
    @Volatile
    private var seekFrozen = false

    @Volatile
    private var seekFrozenTargetMs = -1L

    @Volatile
    private var seekFrozenDeadlineNanos = 0L

    @Volatile
    private var avSyncQuietUntilNanos = 0L

    /** When the audio input was last (re-)attached; see [AV_AUDIO_ATTACH_GRACE_NANOS]. */
    @Volatile
    private var audioInputAttachedAtNanos = 0L

    /** One-shot startup alignment budget: a cold audio decoder can start seconds after the picture. */
    private val avStartupAlignAttempts = AtomicInteger(0)

    /** One-shot guard so a frozen picture clock is reported once, not once per sample. */
    private val avFrozenClockLogged = AtomicBoolean(false)

    /** True while the session plays a live stream, which has no timeline and must never be seeked. */
    private val liveSession = AtomicBoolean(false)

    /** One-shot guard so a live session reports its skipped A/V correction once, not every sample. */
    private val liveSkipLogged = AtomicBoolean(false)

    /** Guards the single per-session sampling thread. */
    private val avSamplerStarted = AtomicBoolean(false)

    /** True while a seek is flushing: audio stays silent until the new frame is on screen. */
    private val audioGatedBySeek = AtomicBoolean(false)

    /** Position a re-opened video input still has to land on; -1 when no reopen is pending. */
    private val pendingVideoRepositionMs = AtomicLong(-1L)

    /** Video-input reopens spent on the current seek. */
    private val videoReopenAttempts = AtomicLong(0L)

    /** Full playback rebuilds spent since the last delivered frame. */
    private val videoRestartAttempts = AtomicLong(0L)

    /** Where the picture is supposed to be; -1 when no seek is pending. */
    private val pendingSeekTargetMs = AtomicLong(-1L)

    /** Position re-asserts spent on a reopened input that landed at the wrong place. */
    private val videoPositionFixAttempts = AtomicLong(0L)

    /** When the video input was last repositioned (seek or reopen); 0 when it never was. */
    @Volatile
    private var lastRepositionNanos = 0L

    /** True once any frame reached the GPU surface; a parked session still needs its first picture. */
    @Volatile
    private var everPublishedFrame = false

    /** Last started stream set / quality, so a dead audio input can be re-attached without a restart. */
    @Volatile
    private var lastStreamSet: ActiveStreams? = null

    /** Quality of the running session, reused when playback has to be rebuilt from scratch. */
    @Volatile
    private var lastQualityStarted = 0

    /** When the current session started; guards the audio-heal grace window. */
    @Volatile
    private var sessionStartedAtNanos = 0L

    /** Bounded retries for [reviveDeadAudio], so a genuinely silent source cannot loop forever. */
    private val audioReviveAttempts = AtomicLong(0L)
    private val audioReviveCooldownNanos = AtomicLong(0L)

    /** True while inside the post-seek settle window during which audio must not be snapped to video. */
    private fun seekSettling(): Boolean = System.nanoTime() < seekSettleUntilNanos

    /** Arms the settle window and arms it for the seek-verification pass below. */
    private fun armSeekSettleWindow() {
        seekSettleUntilNanos = System.nanoTime() + SEEK_SETTLE_WINDOW_NANOS
    }

    init {
        // Mirror the config's hw-accel preference onto the shared libvlc instance before it is
        // created (the singleton instance is built on first use, so this must be set up front).
        LibVlc.useHwAccel = useHwAccel
    }

    /** Media-player events we attach to (VideoPlayer's set). */
    private val MEDIA_PLAYER_EVENTS = intArrayOf(
        LibVlc.LIBVLC_MEDIA_PLAYER_PLAYING,
        LibVlc.LIBVLC_MEDIA_PLAYER_PAUSED,
        LibVlc.LIBVLC_MEDIA_PLAYER_STOPPED,
        LibVlc.LIBVLC_MEDIA_PLAYER_END_REACHED,
        LibVlc.LIBVLC_MEDIA_PLAYER_ENCOUNTERED_ERROR,
        LibVlc.LIBVLC_MEDIA_PLAYER_TIME_CHANGED,
        LibVlc.LIBVLC_MEDIA_PLAYER_LENGTH_CHANGED,
    )

    // ── libvlc singleton state (VideoPlayer model) ──────────────────────────

    @Volatile
    private var mediaPlayer: Pointer? = null

    /**
     * Second libvlc media player dedicated to audio-only playback. Decoupled from the video player
     * so its audio-clock (Java Sound `write()` blocking) never throttles the video vout. Created
     * alongside [mediaPlayer] for desktop; Android rotates to a fresh player for each media session. Audio callbacks are registered on
     * this player only; the video player has `:no-audio` and runs on the system clock.
     */
    @Volatile
    private var audioPlayer: Pointer? = null

    /** Last volume handed to [setVolume], re-asserted after an attach (libvlc volume is per-player). */
    @Volatile
    private var lastVolume = 1.0

    /** Retired Android players stay strongly referenced and paused until process exit. */
    private val retiredAndroidPlayers = java.util.Collections.synchronizedList(mutableListOf<Pointer>())

    private val released = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val lifecycleGeneration = AtomicLong(0L)

    /** Serialises every libvlc control call, mirroring VideoPlayer's control executor. */
    private val controlExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> daemonThread(r, "MediaPlayer-vlc-ctrl") }

    // ── Frame surface ───────────────────────────────────────────────────────

    private val surface = FrameSurface(debugLabel, uploaderFactory, FramePixelFormat.BGRA32).also { s ->
        // Display-time frame selection: the render thread takes the queued frame that is DUE for the current
        // playback position (libvlc `get_time`) instead of always taking the newest published one, so a frame
        // delivered early waits for its slot instead of replacing the picture ahead of time.
        // libvlc's plane callbacks expose no per-frame PTS, so publishFrame() stamps each frame from the same
        // media clock the render thread reads (see FrameSurface.enableDisplaySync).
        s.enableDisplaySync({ currentPacingNanos() }, DISPLAY_SYNC_FRAME_NS)
    }

    @Volatile
    var expectedW = 0; private set

    @Volatile
    var expectedH = 0; private set

    private val noFrames = AtomicLong(0)

    var isPlaying = false; private set

    val lastFrameNanos: AtomicLong get() = noFrames

    // ── EOS / error signals ─────────────────────────────────────────────────

    @Volatile
    private var eosReached = false

    @Volatile
    private var errorMessage = ""

    /** Guards [onStreamEnd] so it fires exactly once per session. */
    private val eosFired = AtomicBoolean(false)

    // ── Park state ──────────────────────────────────────────────────────────

    private val parkFlag = AtomicBoolean(false)
    private var parkPositionNanos = 0L
    @Volatile
    private var parkedAtNanos = 0L

    /** User pause intent shared with start() so a press during first-frame wait is not overwritten. */
    private val userPauseRequested = AtomicBoolean(false)

    /** Records play/pause intent while the session is still opening its inputs. */
    fun setUserPauseRequested(paused: Boolean) {
        userPauseRequested.set(paused)
    }

    /** True when the viewer asked for the pause, as opposed to a start-paused seed from the display. */
    fun isUserPauseRequested(): Boolean = userPauseRequested.get()

    /** True once the current session has delivered a real decoded frame. */
    fun hasFirstFrame(): Boolean = firstFrameFired

    // ── Popout / preview sinks ──────────────────────────────────────────────

    private var popoutSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null
    private var previewSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    /** Coarse copy of the newest delivered frame, so a surface created later can still show a picture. */
    private var snapshotBuffer: ByteBuffer? = null
    private var snapshotWidth: Int = 0
    private var snapshotHeight: Int = 0
    private var snapshotAtNanos: Long = 0L

    /** Guards [snapshotBuffer] against the frame thread while the render thread copies it. */
    private val snapshotLock = Any()

    /** The newest frame kept for late-created surfaces, or null before the first snapshot. */
    fun lastFrameSnapshot(): PausedFrame? = synchronized(snapshotLock) {
        val buffer = snapshotBuffer ?: return@synchronized null
        if (snapshotWidth <= 0 || snapshotHeight <= 0 || buffer.remaining() <= 0) return@synchronized null
        // Hand out a copy: the frame thread keeps overwriting the kept buffer.
        val copy = ByteBuffer.allocateDirect(buffer.remaining()).order(ByteOrder.nativeOrder())
        copy.put(buffer.duplicate())
        copy.flip()
        PausedFrame(copy, snapshotWidth, snapshotHeight)
    }

    /** Latest sampled RGB color for optional world-light integrations. */
    @Volatile
    var ambientLightColor: Int = 0
        private set

    var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = popoutSink
        set(value) { popoutSink = value }

    var previewFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = previewSink
        set(value) { previewSink = value }

    /** Samples a decoded frame for optional client-side lighting integrations. */
    var ambientLightSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    // ── Low-level callbacks (held strongly for life) ───────────────────────

    /** Event callbacks are retained because Android keeps retired players alive until process exit. */
    private val eventCallbacks = java.util.Collections.synchronizedList(mutableListOf<LibVlc.EventCallback>())

    /**
     * Every native player gets its own callback object and direct-buffer pool. This is important on
     * Android: an old player's delayed vout callback must never use the replacement player's frame
     * pointers while libvlc is copying a picture.
     */
    private val videoCallbackSets = java.util.Collections.synchronizedList(mutableListOf<VideoCallbackSet>())
    private val videoCallbacksByPlayer = java.util.Collections.synchronizedMap(mutableMapOf<Long, VideoCallbackSet>())

    private inner class VideoCallbackSet {
        val frames = TextureRenderCallback()
        val format = LibVlc.VideoFormatCallback { opaque, chroma, width, height, pitches, lines ->
            frames.setup(opaque, chroma, width, height, pitches, lines)
        }
        val cleanup = LibVlc.VideoCleanupCallback { opaque -> frames.cleanup(opaque) }
        val lock = LibVlc.VideoLockCallback { opaque, planes -> frames.lock(opaque, planes) }
        val unlock = LibVlc.VideoUnlockCallback { opaque, picture, planes ->
            frames.unlock(opaque, picture, planes)
        }
        val display = LibVlc.VideoDisplayCallback { opaque, picture -> frames.display(opaque, picture) }

        init {
            // Keep JNA callback threads attached until VLC's native owner exits. The Android
            // loader also creates VLC's JNI TLS key before JNA, so this is a defensive second line.
            LibVlc.configureCallbackThread(format)
            LibVlc.configureCallbackThread(cleanup)
            LibVlc.configureCallbackThread(lock)
            LibVlc.configureCallbackThread(unlock)
            LibVlc.configureCallbackThread(display)
        }

        fun deactivate() = frames.deactivate()
    }

    private fun deactivateVideoCallbacks(player: Pointer?) {
        if (player == null) return
        videoCallbacksByPlayer[Pointer.nativeValue(player)]?.deactivate()
    }

    // ── Audio callbacks (held strongly; feed the 3D DSP + Java Sound line) ───

    private val audioFormatSetupCallback = LibVlc.AudioSetupCallback { data, format, rate, channels ->
        audioOutput?.onFormatSetup(data, format, rate, channels) ?: 0
    }

    private val audioFormatCleanupCallback = LibVlc.AudioCleanupCallback { data ->
        audioOutput?.onFormatCleanup(data)
    }

    private val audioPlayCallback = LibVlc.AudioPlayCallback { data, samples, count, pts ->
        audioOutput?.onPlay(data, samples, count, pts)
    }

    private val audioPauseCallback = LibVlc.AudioPauseCallback { data, pts ->
        audioOutput?.onPause(data, pts)
    }

    private val audioResumeCallback = LibVlc.AudioResumeCallback { data, pts ->
        audioOutput?.onResume(data, pts)
    }

    private val audioFlushCallback = LibVlc.AudioFlushCallback { data, pts ->
        audioOutput?.onFlush(data, pts)
    }

    private val audioDrainCallback = LibVlc.AudioDrainCallback { data ->
        audioOutput?.onDrain(data)
    }


    // ── Event handling ──────────────────────────────────────────────────────

    private fun handleEvent(event: Pointer?, sourcePlayer: Pointer) {
        // Android retires old players instead of stopping/releasing them. Their delayed events must
        // never mutate the state of the replacement player (especially END_REACHED / ERROR).
        if (event == null || released.get() || stopped.get() || mediaPlayer !== sourcePlayer) return
        val type = event.getInt(0)
        when (type) {
            LibVlc.LIBVLC_MEDIA_PLAYER_PLAYING -> {
                stopped.set(false)
                isPlaying = true
                // Audio diagnostics: log track count and current volume.
                val player = mediaPlayer
                if (player != null) {
                    val tracks = LibVlc.lib.libvlc_audio_get_track_count(player)
                    val vol = LibVlc.lib.libvlc_audio_get_volume(player)
                    logger.debug("$debugLabel libvlc playing: audioTracks={} volume={}.", tracks, vol)
                    // Update F3 decoder info — query immediately, and retry if the decoder isn't
                    // initialised yet (the info may not be available at the very first PLAYING event).
                    updateDecoderName(player, immediate = true)
                }
            }
            LibVlc.LIBVLC_MEDIA_PLAYER_PAUSED -> {
                if (MediaPlayer.DEBUG) logger.debug("$debugLabel libvlc paused.")
            }
            LibVlc.LIBVLC_MEDIA_PLAYER_END_REACHED -> {
                // DIAGNOSTIC: audio-vs-video length comparison. When a DASH audio slave is shorter
                // than the video master (common on Bilibili), libvlc stops calling onPlay once the
                // audio fragments run out and the audible audio ends early while video continues.
                val audioMs = runCatching { audioOutput?.audioFeedMs() }.getOrDefault(-1L) ?: -1L
                val videoMs = runCatching {
                    val p = mediaPlayer
                    if (p != null) LibVlc.lib.libvlc_media_player_get_length(p) else -1L
                }.getOrDefault(-1L)
                if (audioMs >= 0 && videoMs > 0) {
                    logger.debug(
                        "$debugLabel A/V END: audio fed {} ms vs video length {} ms ({} ms gap).",
                        audioMs, videoMs, videoMs - audioMs
                    )
                }
                logger.debug("$debugLabel libvlc end reached.")
                eosReached = true
                fireStreamEnd()
            }
            LibVlc.LIBVLC_MEDIA_PLAYER_TIME_CHANGED -> {
                // Time-changed log is useful for debugging but we never rebase the clock:
                // PlaybackClock owns the seek origin used by doRestart.
                if (MediaPlayer.DEBUG) {
                    logger.debug("$debugLabel TIME_CHANGED {} ms.", clock.currentTime() / 1_000_000L)
                }
            }
            LibVlc.LIBVLC_MEDIA_PLAYER_ENCOUNTERED_ERROR -> {
                // errmsg() is thread-local and almost always empty here (the failure happened on a
                // libvlc worker thread, not the event thread), so enrich the message with the real
                // reason from the libvlc log sink plus the player state at error time.
                val errmsg = LibVlc.errmsg()
                val stateName = runCatching {
                    val p = mediaPlayer
                    if (p != null) {
                        when (LibVlc.lib.libvlc_media_player_get_state(p)) {
                            LibVlc.LIBVLC_STATE_PLAYING -> "Playing"
                            LibVlc.LIBVLC_STATE_PAUSED -> "Paused"
                            LibVlc.LIBVLC_STATE_STOPPED -> "Stopped"
                            LibVlc.LIBVLC_STATE_ENDED -> "Ended"
                            else -> "Unknown"
                        }
                    } else "no-player"
                }.getOrDefault("Unknown")
                val recentLog = LibVlc.recentLogLines(12)
                // HW-decode backend failure (e.g. d3d11va surface-allocation crash on NVIDIA):
                // advance to the next candidate in the per-vendor chain (cuda → d3d11va →
                // software, vaapi → software, …) and let the MediaPlayer restart the SAME stream
                // with the new media-level :avcodec-hw. "none" means explicit software decode —
                // the chain's last resort, never a silent re-pick of the failed backend.
                val hwFallback = LibVlc.advanceHwBackendAfterHwFailure(recentLog)
                val detail = buildString {
                    append("libvlc error")
                    if (errmsg.isNotBlank()) append(": ").append(errmsg)
                    append(" [state=").append(stateName).append("]")
                    if (hwFallback != null) {
                        append(" [hw-decode fallback -> ").append(hwFallback).append("]")
                    }
                    if (recentLog.isNotEmpty()) {
                        append(" | libvlc log: ").append(recentLog.joinToString("; "))
                    }
                }
                logger.error("$debugLabel $detail")
                errorMessage = detail
                eosReached = true
                fireStreamEnd()
            }
            else -> {}
        }
    }

    /** Fires [onStreamEnd] exactly once per session. */
    private fun fireStreamEnd() {
        if (eosFired.compareAndSet(false, true) && !terminated.get()) {
            isPlaying = false
            onStreamEnd(errorMessage.ifEmpty { "End of stream" }, errorMessage.isEmpty())
        }
    }

    // ── Volume ──────────────────────────────────────────────────────────────

    /**
     * Applies the effective volume as a PCM gain on our own audio line. With audio callbacks set,
     * libvlc's software volume (libvlc_audio_set_volume) would be a no-op — the decoded samples are
     * delivered to us raw, so volume is applied here (and by the 3D DSP chain).
     */
    fun setVolume(volume: Double) {
        audioOutput?.setVolume(volume)
        // libvlc's own output needs the volume as well on Android (system audio), where the PCM never
        // reaches [LibVlcAudioOutput] and libvlc volume is per-player.
        val pct = (volume.coerceIn(0.0, 1.0) * 100).toInt()
        lastVolume = volume
        if (systemAudio) {
            val mp = mediaPlayer
            if (mp != null) {
                runCatching { LibVlc.lib.libvlc_audio_set_volume(mp, pct) }
            }
            val ap = audioPlayer
            if (ap != null) {
                runCatching { LibVlc.lib.libvlc_audio_set_volume(ap, pct) }
            }
        }
    }

    /** Opens (or reuses) the shared Java Sound audio line for this display. */
    private fun openAudioLine() {
        runCatching { audioOutput?.openLine() }
    }

    /**
     * Refreshes [MediaPlayer.currentDecoder] (F3 overlay) from libvlc's decoder info. The value is
     * only available once the decoder thread has started, so the first PLAYING event often reports
     * nothing yet; retry shortly afterwards on the control executor.
     */
    private fun updateDecoderName(player: Pointer, immediate: Boolean) {
        val update = {
            try {
                val name = LibVlc.videoDecoderName(player)
                if (!name.isNullOrBlank()) {
                    val old = MediaPlayer.currentDecoder.getAndSet(name)
                    if (old != name && MediaPlayer.DEBUG) logger.debug("$debugLabel video decoder: {}.", name)
                } else {
                    logger.debug("$debugLabel video decoder info not ready yet.")
                }
            } catch (t: Throwable) {
                logger.debug("$debugLabel video decoder query failed: ${t.message}")
            }
        }
        if (immediate) update()
        submit {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            update()
        }
    }

    // ── Frame pipe proxy ────────────────────────────────────────────────────

    fun textureFilled(): Boolean = surface.textureFilled()

    fun updateFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean {
        if (w != expectedW || h != expectedH) {
            // Render thread drives the authoritative texture size. Adopt it (the old JavaCPP
            // pipeline did the same via getTextureSize()) and drop the stale-size ready frame;
            // the next publish scales to the new size and uploads match from then on.
            if (w > 0 && h > 0) {
                expectedW = w
                expectedH = h
            }
            // Keep the uploaded picture on screen: clearing here blanked the display to grey for as
            // long as libvlc reported a jittering decoded size (live sources flip between e.g.
            // 1088 and 1090 lines), because no frame matched the new size until the next publish.
            return false
        }
        return surface.updateFrame(texture, w, h, expectedW, expectedH)
    }

    fun updateFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean {
        if (w != expectedW || h != expectedH) {
            if (w > 0 && h > 0) {
                expectedW = w
                expectedH = h
            }
            return false
        }
        return surface.updateFramePlanar(y, u, v, w, h, expectedW, expectedH)
    }

    fun hasIncoming(): Boolean = false // hard quality switch, no parallel channel

    fun updateIncomingFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean = false

    fun updateIncomingFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean = false

    fun clearFrame() = surface.clear()

    // ── Session lifecycle ───────────────────────────────────────────────────

    /**
     * Starts (or restarts) playback of [streamSet] on the current session players.
     * Video is delivered via the low-level callbacks; audio uses the dedicated player on desktop
     * and on Android only when the selected rendition is separate from the video URL.
     */
    fun start(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int, live: Boolean = false): Boolean {
        // A live stream has no timeline to seek on: `set_time` on its players only flushes the input
        // and re-buffers, which showed up as many seconds of silence right after a live link started.
        liveSession.set(live)
        liveSkipLogged.set(false)
        val (w, h) = targetDims(streamSet, lastQuality)
        val safeUrl = MediaHostGuard.resolveSafeUrl(streamSet.currentVideo.url)
        val audioUrl = streamSet.currentAudio.url
        // Compare against the resolver's logical video URL, not the redirect target. A muxed direct
        // link commonly resolves to a signed CDN URL; treating that redirect as a separate rendition
        // would silence the video and download the same media a second time on Android.
        val separateAudio = audioUrl.isNotBlank() &&
            !audioUrl.equals(streamSet.currentVideo.url, ignoreCase = true)
        val safeAudioUrl = try {
            if (separateAudio) MediaHostGuard.resolveSafeUrl(audioUrl) else safeUrl
        } catch (error: Throwable) {
            logger.warn("$debugLabel rejected unsafe audio rendition before native playback: ${error.message}")
            return false
        }
        val started = AtomicBoolean(false)
        val audioPlayerToStart = AtomicReference<Pointer?>(null)

        // Desktop replaces media on the same player; Android retires old players before creating fresh ones.
        val lifecycleToken = synchronized(lifecycleLock) {
            if (released.get()) return false
            stopped.set(false)
            lifecycleGeneration.incrementAndGet()
            lifecycleGeneration.get()
        }
        // Kept so a dead audio input can be re-attached without restarting the video session.
        lastStreamSet = streamSet
        lastQualityStarted = lastQuality
        videoRestartAttempts.set(0L)
        videoPositionFixAttempts.set(0L)
        everPublishedFrame = false
        sessionStartedAtNanos = System.nanoTime()
        audioReviveAttempts.set(0L)
        audioReviveCooldownNanos.set(0L)
        avSamplerStarted.set(false)
        audioInputAttachedAtNanos = 0L
        avStartupAlignAttempts.set(0)
        avFrozenClockLogged.set(false)
        avSync.reset()
        lastAvVideoMs = -1L
        videoReopenAttempts.set(0L)
        eosReached = false
        errorMessage = ""
        eosFired.set(false)
        parkFlag.set(false)
        // Fresh session: both players start together at the same offset, so the post-seek
        // snap-suppression window (armed by beginSeek / seek verification) must not leak in and
        // defer the initial A/V alignment chain.
        seekSettleUntilNanos = 0L
        seekFrozen = false
        seekGeneration.incrementAndGet()
        // A fresh session starts its own audio; drop any silence left over from the seek it replaces.
        audioGatedBySeek.set(false)
        expectedW = w
        expectedH = h
        firstFrameFired = false
        firstFrameLatch = CountDownLatch(1)
        parkedSeekFrameNeeded = false
        // Reset the playback clock to the requested offset so the progress bar starts at the
        // right second instead of inheriting a stale origin from the previous session.
        clock.reset(offsetNanos)
        // A resumed position is enforced too: a fresh input that plays from zero must be pulled back.
        pendingSeekTargetMs.set(if (!live && offsetNanos > 0L) offsetNanos / 1_000_000L else -1L)
        // Reset the audio output for this new session (re-prime the 3D DSP chain, flush the line).
        runCatching { audioOutput?.reset() }

        // Build the media options (UA + referer + optional hw decode). Desktop audio is played by a
        // SEPARATE libvlc player (see below) so its Java Sound `write()` blocking never throttles the
        // video vout. Android only does that for separate renditions; muxed media keeps its audio on
        // the video player. When the video media gets `:no-audio`, its master clock is the system
        // clock, so the vout delivers at the full source frame rate instead of being dragged to ~60%
        // by an audio clock that is pulsed by line writes. Hardware decode is enabled
        // BOTH here (media-level) and at instance-level (--avcodec-hw=any); VLC copies the GPU-decoded
        // frame back to system memory before handing it to the vmem lock callback, so vmem and hw
        // coexist and 4K H.264/HEVC decodes on the GPU instead of starving the CPU.
        // Only the audio player gets start-time: it is created late and must never run from zero
        // first. The video player's start-time is unreliable for Bilibili's fragmented m4s, so its
        // position is applied by set_time once the first frame proves the input is up (see below).
        val audioStartOption = if (offsetNanos > 0L) {
            ":start-time=" + String.format(java.util.Locale.ROOT, "%.3f", offsetNanos / 1_000_000_000.0)
        } else null
        val mediaOptions = mutableListOf(*LibVlcMediaOptions.forUrl(safeUrl, live = liveSession.get()))
        // Media-level hw decode is an avcodec-module concept (desktop backends only); Android
        // selects its decoder at instance level and defaults to explicit software avcodec when
        // the launcher cannot provide a real MediaCodec bridge.
        if (!systemAudio) {
            // currentHwBackend() may return "none" (explicit software decode after the hw chain
            // was exhausted, or an empty -Ddreamdisplayx.hwDecode= override): keep that option —
            // with no option libvlc defaults to `any` and could silently re-pick the broken hw
            // backend that just failed.
            LibVlc.currentHwBackend()?.let { mediaOptions.add(":avcodec-hw=$it") }
        }
        // Desktop always silences the video player and routes sound through the dedicated audio player —
        // including a single-file source (an mp4 direct link, or any stream whose audio URL equals the
        // video URL), which is fed the SAME url. Letting libvlc's own output play that track proved
        // unreliable on Linux: pulse reported "write index corrupt"/underflow, and the wedged aout kept
        // libvlc's decoder fifo from draining at end of stream, which froze the game. The Java Sound line
        // is the one audio path whose pacing we control. Cost: libvlc downloads that url a second time.
        if (systemAudio && separateAudio) {
            // A video-only rendition must stay silent while the dedicated Android audio player
            // feeds OpenSL ES. Muxed URLs keep their audio in the video player and do not need a
            // second native player.
            mediaOptions.add(":no-audio")
        } else if (!systemAudio) {
            mediaOptions.add(":no-audio")
        }
        if (separateAudio) {
            logger.info("$debugLabel audio will be played by the separate audio player.")
        } else if (audioUrl.isNotBlank()) {
            if (systemAudio) {
                logger.info("$debugLabel muxed audio will remain on the video player.")
            } else {
                logger.info("$debugLabel single-file audio: the dedicated audio player plays the same url.")
            }
        } else {
            logger.warn("$debugLabel no audio track resolved (audioUrl is blank); the display stays silent.")
        }

        submit {
            // A stop() / stopNow() that landed between this task's submission and its run must win:
            // running the attach would resurrect a player the stop already tore down (and stack it
            // under the replacement player the caller went on to create).
            if (!lifecycleIsCurrent(lifecycleToken)) return@submit
            // Android never reuses a player that has already carried media: even set_media() may
            // tear down its old input/vout/aout and trigger VLC-Android's unsafe TLS destructor.
            if (systemAudio) retireAndroidPlayers()
            if (!lifecycleIsCurrent(lifecycleToken)) return@submit
            val mp = player()
            if (mp == null) {
                errorMessage = "libvlc player unavailable"
                eosReached = true
                return@submit
            }
            try {
                // Reload safety: tear down the PREVIOUS media before attaching the new one. Setting
                // media on a player whose old vout/aout threads are still draining races the new
                // format setup and crashes natively (EXCEPTION_ACCESS_VIOLATION right after the new
                // "libvlc video setup" line, before the first frame). libvlc_media_player_stop is
                // synchronous on the control executor, so the old input fully releases first.
                // Android: NEVER call stop/release, and do not reuse a player after it has carried
                // media. Even set_media() can implicitly tear down the old input/vout/aout in this
                // VLC-Android AAR and reach the same TLS destructor UAF. The old player was retired
                // before this block; this player is fresh and may receive the new media.
                if (!systemAudio) {
                    runCatching { LibVlc.lib.libvlc_media_player_stop(mp) }
                }
                // Re-check after the (potentially long) old-media stop: a stop() that arrived while
                // this task was draining the previous input must still win over the new attach.
                if (!lifecycleIsCurrent(lifecycleToken)) return@submit
                val activeMp = mp
                val media = LibVlc.createMedia(safeUrl, mediaOptions.toTypedArray())
                val attachVideo = synchronized(lifecycleLock) {
                    if (lifecycleGeneration.get() == lifecycleToken && !stopped.get() && !released.get()) {
                        LibVlc.lib.libvlc_media_player_set_media(activeMp, media)
                        true
                    } else {
                        false
                    }
                }
                LibVlc.lib.libvlc_media_release(media)
                if (!attachVideo) return@submit // the player holds its own reference
                // Desktop always uses a dedicated audio player so Java Sound pacing cannot throttle
                // the vout. Android only creates one for a separate audio rendition; muxed media stays
                // on the video player's OpenSL ES output, avoiding duplicate sound.
                if (!lifecycleIsCurrent(lifecycleToken)) return@submit
                val ap = if (audioUrl.isNotBlank() && (!systemAudio || separateAudio)) audioPlayer() else null
                if (ap != null) {
                    // Same rule as above: never stop() on Android — set_media replaces the old
                    // input synchronously; an explicit stop would tear worker threads and hit the
                    // VLC-Android TLS destructor UAF (pthread_key_clean_all, libvlc.so+0xef7418).
                    if (!systemAudio) {
                        runCatching { LibVlc.lib.libvlc_media_player_stop(ap) }
                    }
                    val audioUrlToPlay = if (separateAudio) safeAudioUrl else safeUrl
                    val audioOptions = mutableListOf(*LibVlcMediaOptions.forUrl(audioUrlToPlay, live = liveSession.get()))
                    audioOptions.add(":no-video")
                    audioStartOption?.let { audioOptions.add(it) }
                    val audioMedia = LibVlc.createMedia(audioUrlToPlay, audioOptions.toTypedArray())
                    val attachAudio = synchronized(lifecycleLock) {
                        if (lifecycleGeneration.get() == lifecycleToken && !stopped.get() && !released.get()) {
                            LibVlc.lib.libvlc_media_player_set_media(ap, audioMedia)
                            true
                        } else {
                            false
                        }
                    }
                    LibVlc.lib.libvlc_media_release(audioMedia)
                    if (!attachAudio) return@submit
                    audioPlayerToStart.set(ap)
                } else if (audioUrl.isBlank()) {
                    logger.info("$debugLabel audio player skipped (no audio track resolved).")
                }
                synchronized(lifecycleLock) {
                    if (lifecycleGeneration.get() != lifecycleToken || stopped.get() || released.get()) return@submit
                    LibVlc.lib.libvlc_media_player_play(activeMp)
                }
                // Prestart desktop audio paused and PCM-gated (5 s decoder startup) - except live, where a
                // paused input sits at a 0 ms clock and costs two watchdog re-attaches (~7 s of silence).
                if (ap != null && !systemAudio) {
                    if (liveSession.get()) {
                        audioOutput?.setPaused(false)
                        LibVlc.lib.libvlc_media_player_play(ap)
                        logger.info("$debugLabel live audio started unpaused with the picture.")
                    } else {
                        // Gate the PCM only: natively pausing the input before it reaches PLAYING
                        // sticks in libvlc and a later set_pause(0)/play() never revives it.
                        audioOutput?.setPaused(true)
                        LibVlc.lib.libvlc_media_player_play(ap)
                        logger.info("$debugLabel audio prestarted with the line gated at {}ms.", offsetNanos / 1_000_000L)
                    }
                }
                // libvlc volume is per-player. Re-assert it after every fresh play so both the muxed
                // video path and a separate Android audio player honor the user's pre-start volume.
                if (systemAudio) {
                    val volumePercent = (lastVolume.coerceIn(0.0, 1.0) * 100).toInt()
                    runCatching { LibVlc.lib.libvlc_audio_set_volume(activeMp, volumePercent) }
                    ap?.let { runCatching { LibVlc.lib.libvlc_audio_set_volume(it, volumePercent) } }
                }
                if (!lifecycleIsCurrent(lifecycleToken)) return@submit
                isPlaying = true
                // Initial A/V sync: both players start independently, so the audio player's clock can
                // drift from the video's right from the start. Schedule an early correction (~1.5s)
                // rather than waiting for the 10s auto-resync.
                scheduleInitialAvSync()
                startAvClockSampler()
                started.set(true)
            } catch (t: Throwable) {
                logger.error("$debugLabel failed to start libvlc media: ${t.message}")
                errorMessage = t.message ?: "libvlc start failed"
                eosReached = true
            }
        }

        // Seek if needed (after media is set; performed on the control executor).
        // libvlc_media_player_set_time takes MILLISECONDS; convert ns -> ms.
        if (offsetNanos > 0) {
            submit {
                synchronized(lifecycleLock) {
                    if (!lifecycleIsCurrent(lifecycleToken)) return@submit
                    val mp = mediaPlayer ?: return@submit
                    runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, offsetNanos / 1_000_000L) }
                    // The audio player already carries :start-time; seeking it again on a fragmented
                    // m4s can fail and jump to EOF, which produced silent entries at large offsets.
                }
            }
        }

        // Wait for the first frame so the caller can rely on frames being delivered (with timeout).
        // The control-executor submit is asynchronous, so unconditionally await the latch.
        try {
            if (!firstFrameLatch.await(10, TimeUnit.SECONDS)) {
                logger.error("$debugLabel libvlc first frame timeout")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // The position must be applied AFTER the first frame: a set_time issued while the input is
        // still opening is dropped, which left the video on a 0-based clock while the audio player
        // (start-time) played the resume position — the reported "audio and picture do not match".
        if (offsetNanos > 0L && started.get() && lifecycleIsCurrent(lifecycleToken)) {
            submit {
                synchronized(lifecycleLock) {
                    if (!lifecycleIsCurrent(lifecycleToken)) return@submit
                    val mp = mediaPlayer ?: return@submit
                    runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, offsetNanos / 1_000_000L) }
                    val seekable = runCatching {
                        LibVlc.lib.libvlc_media_player_is_seekable(mp) == 1
                    }.getOrDefault(false)
                    val state = runCatching { LibVlc.lib.libvlc_media_player_get_state(mp) }.getOrDefault(-1)
                    val nowMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                    // The set_time return code is not actionable here: libvlc reports VLC_EINVAL even
                    // for the seeks that do land, so only the resulting clock position is meaningful.
                    logger.info(
                        "$debugLabel video positioned after first frame: seekable={} state={} time={}ms target={}ms.",
                        seekable, state, nowMs, offsetNanos / 1_000_000L,
                    )
                }
            }
        }
        val pendingAudio = audioPlayerToStart.get()
        if (pendingAudio != null && started.get() && lifecycleIsCurrent(lifecycleToken)) {
            submit {
                synchronized(lifecycleLock) {
                    if (!lifecycleIsCurrent(lifecycleToken)) return@submit
                    val mp = mediaPlayer
                    val videoMs = if (mp != null) {
                        runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                    } else -1L
                    val targetMs = if (videoMs >= 0L) videoMs else offsetNanos / 1_000_000L
                    // Live streams have no timeline: repositioning the warm input discards its buffered read.
                    if (targetMs > 0L && !liveSession.get()) {
                        runCatching {
                            LibVlc.lib.libvlc_media_player_set_time(pendingAudio, targetMs)
                        }
                    }
                    val keepPaused = parkFlag.get() || userPauseRequested.get()
                    if (!systemAudio) {
                        if (keepPaused) {
                            // PCM gate only: a native pause here is what left the input stuck in
                            // PAUSED so the next resume delivered no sound for seconds.
                            audioOutput?.setPaused(true)
                            logger.info("$debugLabel audio prepared with the line gated after first frame at {}ms.", targetMs)
                        } else if (liveSession.get()) {
                            // The live input is already running: dropping its PCM here is what made the
                            // clock restart from 0 ms, so only make sure it is unpaused.
                            runCatching { LibVlc.lib.libvlc_media_player_set_pause(pendingAudio, 0) }
                            audioOutput?.setPaused(false)
                            logger.info("$debugLabel live audio already running at {}ms.", targetMs)
                        } else {
                            audioOutput?.reset()
                            // Keep the gate closed until the paused libvlc input actually resumes.
                            audioOutput?.setPaused(true)
                            runCatching { LibVlc.lib.libvlc_media_player_set_pause(pendingAudio, 0) }
                            audioOutput?.setPaused(false)
                            logger.info("$debugLabel audio released after first frame at {}ms.", targetMs)
                        }
                    } else if (keepPaused) {
                        audioOutput?.setPaused(true)
                        LibVlc.lib.libvlc_media_player_play(pendingAudio)
                        logger.info("$debugLabel audio started with the line gated at {}ms.", targetMs)
                    } else {
                        logger.info("$debugLabel audio starts after first video frame at {}ms.", targetMs)
                        LibVlc.lib.libvlc_media_player_play(pendingAudio)
                        audioOutput?.setPaused(false)
                    }
                    if (systemAudio) {
                        val volumePercent = (lastVolume.coerceIn(0.0, 1.0) * 100).toInt()
                        runCatching { LibVlc.lib.libvlc_audio_set_volume(pendingAudio, volumePercent) }
                    }
                }
            }
        }
        // A stale frame can release the first-frame latch, so give the play task a short grace window.
        var graceMs = 0L
        while (!started.get() && graceMs < 2_000L && lifecycleIsCurrent(lifecycleToken)) {
            try {
                Thread.sleep(50L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            graceMs += 50L
        }
        if (started.get()) {
            startAudioWatchdog(lifecycleToken)
            startVideoWatchdog(lifecycleToken)
        }
        return started.get()
    }

    /**
     * Returns the current libvlc VIDEO player, creating it on first use. Desktop keeps and reuses
     * this player; Android replaces it after every media-bearing session. Desktop media gets
     * `:no-audio` because the separate audio player's Java Sound `write()` must not throttle the
     * vout; Android adds that flag only for video-only renditions.
     */
    private fun player(): Pointer? = synchronized(lifecycleLock) {
        if (stopped.get() || released.get()) return@synchronized null
        val existing = mediaPlayer
        if (existing != null) return@synchronized existing
        try {
            LibVlc.ensureLoaded()
            val lib = LibVlc.lib
            val mp = lib.libvlc_media_player_new(LibVlc.libvlcInstance)
            if (mp == null) {
                logger.error("$debugLabel libvlc_media_player_new returned null: ${LibVlc.errmsg()}")
                return null
            }
            // Each native player gets an independent callback set and direct-buffer pool. Skippable
            // via the diagnostic switch for bisection; callback sets are retained for delayed callbacks
            // from retired Android players.
            if (!LibVlcDiagnostics.noVideoCallback) {
                val callbacks = VideoCallbackSet()
                videoCallbackSets += callbacks
                videoCallbacksByPlayer[Pointer.nativeValue(mp)] = callbacks
                lib.libvlc_video_set_format_callbacks(mp, callbacks.format, callbacks.cleanup)
                lib.libvlc_video_set_callbacks(mp, callbacks.lock, callbacks.unlock, callbacks.display, null)
            }
            // Events are bound to this concrete player. The video player drives session state;
            // retired Android players may still emit delayed events that must be ignored.
            val em = lib.libvlc_media_player_event_manager(mp)
            if (em != null) {
                val callback = LibVlc.EventCallback { event, _ -> handleEvent(event, mp) }
                LibVlc.configureCallbackThread(callback)
                eventCallbacks += callback
                for (e in MEDIA_PLAYER_EVENTS) {
                    lib.libvlc_event_attach(em, e, callback, null)
                }
            }
            mediaPlayer = mp
            if (MediaPlayer.DEBUG) logger.debug("$debugLabel created libvlc video player.")
            mp
        } catch (t: Throwable) {
            logger.error("$debugLabel failed to create libvlc media player: ${t.message}")
            null
        }
    }

    /**
     * Returns the current dedicated AUDIO-only libvlc media player, creating it on first use.
     * Desktop reuses it; Android replaces it with the video player after each media-bearing session.
     * On desktop it only registers the audio callbacks that feed the 3D DSP + Java
     * Sound line ([LibVlcAudioOutput]); its media gets `:no-video`. No events are attached: its
     * END_REACHED (Bilibili DASH audio slaves run shorter than the video) must NOT trigger the
     * session EOS. Skipped entirely when [LibVlcDiagnostics.noAudioCallback] is set (bisection).
     * On Android no callback pipeline exists (no javax.sound): the player is created WITHOUT
     * audio callbacks so libvlc's own OpenSL ES output (instance-level `--aout=opensles`) plays
     * the DASH audio m4s directly — without it the separate audio URL on a video-only Bilibili
     * DASH m4s is never played and there is no sound at all.
     */
    private fun audioPlayer(): Pointer? = synchronized(lifecycleLock) {
        if (LibVlcDiagnostics.noAudioCallback || stopped.get() || released.get()) return@synchronized null
        val existing = audioPlayer
        if (existing != null) return@synchronized existing
        try {
            LibVlc.ensureLoaded()
            val lib = LibVlc.lib
            val ap = lib.libvlc_media_player_new(LibVlc.libvlcInstance)
            if (ap == null) {
                logger.error("$debugLabel libvlc audio player new returned null: ${LibVlc.errmsg()}")
                return null
            }
            if (!systemAudio) {
                // Desktop only: audio callbacks feed the 3D DSP + Java Sound line. The line is
                // pre-opened here so the first PCM block has a destination (also primes the default format).
                openAudioLine()
                LibVlc.configureCallbackThread(audioFormatSetupCallback)
                LibVlc.configureCallbackThread(audioFormatCleanupCallback)
                LibVlc.configureCallbackThread(audioPlayCallback)
                LibVlc.configureCallbackThread(audioPauseCallback)
                LibVlc.configureCallbackThread(audioResumeCallback)
                LibVlc.configureCallbackThread(audioFlushCallback)
                LibVlc.configureCallbackThread(audioDrainCallback)
                lib.libvlc_audio_set_format_callbacks(ap, audioFormatSetupCallback, audioFormatCleanupCallback)
                lib.libvlc_audio_set_callbacks(ap, audioPlayCallback, audioPauseCallback,
                    audioResumeCallback, audioFlushCallback, audioDrainCallback, null)
            }
            audioPlayer = ap
            if (MediaPlayer.DEBUG) logger.debug("$debugLabel created dedicated libvlc audio player.")
            ap
        } catch (t: Throwable) {
            logger.error("$debugLabel failed to create libvlc audio player: ${t.message}")
            null
        }
    }

    /**
     * Starts video-only replay (not supported by libvlc port; returns false).
     */
    fun startReplayVideoOnly(
        @Suppress("UNUSED_PARAMETER") snapshot: ByteArray?,
        @Suppress("UNUSED_PARAMETER") resume: Long,
        @Suppress("UNUSED_PARAMETER") positionNanos: Long,
        @Suppress("UNUSED_PARAMETER") audioPcm: ByteArray?,
    ): Boolean = false

    /**
     * Attaches a live stream after a video-only replay (not supported; returns false).
     */
    fun attachLiveAfterReplay(
        @Suppress("UNUSED_PARAMETER") streamSet: ActiveStreams,
        @Suppress("UNUSED_PARAMETER") liveOffsetNanos: Long,
        @Suppress("UNUSED_PARAMETER") lastQuality: Int,
    ): Boolean = false

    /** Silences the audio player for the duration of a seek (see [audioGatedBySeek]). */
    private fun gateAudioForSeek() {
        audioGatedBySeek.set(true)
        seekFrozen = true
        seekFrozenDeadlineNanos = System.nanoTime() + SEEK_FREEZE_MAX_NANOS
        // Silence through the PCM gate only. Natively pausing the audio player here left it stuck in
        // libvlc's PAUSED state during fast scrub chains (play() would not revive it and the watchdog
        // had to rebuild the input, losing sound for seconds). The clock is realigned on release.
        audioOutput?.setPaused(true)
    }

    /** Ends the seek silence: aligns the audio to the video clock, then unpauses (or stays parked). */
    private fun releaseAudioForSeek() {
        if (!audioGatedBySeek.compareAndSet(true, false)) return
        submit {
            synchronized(lifecycleLock) {
                if (stopped.get() || released.get()) return@submit
                val mp = mediaPlayer
                val ap = audioPlayer
                val videoMs = if (mp != null) {
                    runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                } else -1L
                // A reopened input can quietly play from the start: never drag the sound onto a
                // picture that sits far from the target, or the whole video restarts from zero.
                val targetMs = pendingSeekTargetMs.get()
                if (videoMs >= 0L && targetMs > 0L &&
                    kotlin.math.abs(videoMs - targetMs) > SEEK_LANDING_OFF_TARGET_MS
                ) {
                    logger.warn(
                        "$debugLabel video clock {}ms is off the seek target {}ms; keeping the audio gated.",
                        videoMs, targetMs,
                    )
                    audioGatedBySeek.set(true)
                    audioOutput?.setPaused(true)
                    if (ap != null) runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 1) }
                    enforceVideoPosition(targetMs)
                    return@submit
                }
                if (ap != null && videoMs >= 0L) {
                    runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, videoMs) }
                    // A fragmented m4s input can refuse set_time; the audio then keeps its old
                    // position and plays seconds behind the picture. Rebuild it at the target instead.
                    val audioAfter = runCatching { LibVlc.lib.libvlc_media_player_get_time(ap) }.getOrDefault(-1L)
                    if (audioAfter >= 0L && kotlin.math.abs(audioAfter - videoMs) > AUDIO_SEEK_TOLERANCE_MS) {
                        logger.warn(
                            "$debugLabel audio seek missed ({} ms vs video {} ms); re-attaching the audio input.",
                            audioAfter, videoMs,
                        )
                        reviveDeadAudio(videoMs)
                    }
                }
                audioOutput?.reset()
                val parked = parkFlag.get()
                if (ap != null) {
                    runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, if (parked) 1 else 0) }
                    // A long park can leave the audio input stopped rather than paused: set_pause(0)
                    // alone then reports progress without delivering PCM (sound arrives seconds late).
                    if (!parked) runCatching { LibVlc.lib.libvlc_media_player_play(ap) }
                }
                audioOutput?.setPaused(parked)
                logger.info("$debugLabel audio released after seek at {}ms (parked={}).", videoMs, parked)
            }
        }
    }

    /**
     * Re-creates the audio input when it died while the video kept playing (display reloaded after a
     * long flight, a start() whose audio attach lost the lifecycle race, ...). Bounded and
     * rate-limited; returns true when a play() was issued.
     */
    private fun reviveDeadAudio(videoMs: Long): Boolean {
        // A fresh session is still at 0 (or has not started its audio yet); only heal real playback.
        if (videoMs < AUDIO_REVIVE_MIN_VIDEO_MS) return false
        if (System.nanoTime() - sessionStartedAtNanos < AUDIO_REVIVE_START_GRACE_NANOS) return false
        val ap = audioPlayer ?: return false
        val ss = lastStreamSet ?: return false
        val candidates = audioCandidates(ss)
        if (candidates.isEmpty()) return false
        val now = System.nanoTime()
        if (now < audioReviveCooldownNanos.get() || audioReviveAttempts.get() >= AUDIO_REVIVE_MAX_ATTEMPTS) {
            return false
        }
        audioReviveCooldownNanos.set(now + AUDIO_REVIVE_COOLDOWN_NANOS)
        val attempt = audioReviveAttempts.getAndIncrement()
        val selected = candidates[(attempt % candidates.size).toInt()]
        val audioUrl = selected.url
        if (audioUrl.isBlank()) return false
        if (selected.url != ss.currentAudio.url) {
            lastStreamSet = ss.copy(currentAudio = selected)
            logger.warn(
                "$debugLabel audio fallback to {} (attempt {}).",
                selected.audioTrackName ?: selected.url.substringBefore('?'),
                attempt + 1,
            )
        }
        val separate = !audioUrl.equals(ss.currentVideo.url, ignoreCase = true)
        val urlToPlay = runCatching {
            if (separate) MediaHostGuard.resolveSafeUrl(audioUrl) else MediaHostGuard.resolveSafeUrl(ss.currentVideo.url)
        }.getOrNull() ?: return false
        val options = mutableListOf(*LibVlcMediaOptions.forUrl(urlToPlay, live = liveSession.get()))
        options.add(":no-video")
        // A live input has no timeline to position: :start-time only delays its first block.
        if (videoMs > 0L && !liveSession.get()) {
            options.add(":start-time=" + String.format(java.util.Locale.ROOT, "%.3f", videoMs / 1000.0))
        }
        return runCatching {
            LibVlc.lib.libvlc_media_player_stop(ap)
            val media = LibVlc.createMedia(urlToPlay, options.toTypedArray())
            LibVlc.lib.libvlc_media_player_set_media(ap, media)
            LibVlc.lib.libvlc_media_release(media)
            // :start-time above already positions the audio input; a second set_time can seek a
            // fragmented m4s past its index and end the input immediately.
            LibVlc.lib.libvlc_media_player_play(ap)
            audioOutput?.reset()
            if (parkFlag.get()) {
                LibVlc.lib.libvlc_media_player_set_pause(ap, 1)
                audioOutput?.setPaused(true)
            } else {
                audioOutput?.setPaused(false)
            }
            logger.warn("$debugLabel audio input re-attached at {}ms.", videoMs)
            audioInputAttachedAtNanos = System.nanoTime()
            // A re-attached input starts cold, so it gets the startup alignment budget again.
            avStartupAlignAttempts.set(0)
            true
        }.getOrDefault(false)
    }

    /** Current audio track first, then its CDN backups, then the other audio renditions. */
    private fun audioCandidates(ss: ActiveStreams): List<MediaStream> {
        val candidates = LinkedHashMap<String, MediaStream>()
        fun add(stream: MediaStream) {
            val urls = (listOf(stream.url) + stream.backupUrls).filter { it.isNotBlank() }.distinct()
            for (url in urls) {
                candidates.putIfAbsent(
                    url,
                    stream.copy(url = url, backupUrls = urls.filter { it != url }),
                )
            }
        }
        add(ss.currentAudio)
        ss.availableAudio.filter { !it.type.hasVideo }.forEach(::add)
        return candidates.values.toList()
    }

    /** Re-opens only the video input at [targetMs]; libvlc refuses a runtime seek on this source. */
    private fun reopenVideoAt(targetMs: Long): Boolean {
        if (videoReopenAttempts.get() >= VIDEO_REOPEN_MAX_ATTEMPTS) {
            logger.warn("$debugLabel video reopen refused at {}ms: attempt budget spent.", targetMs)
            return false
        }
        val mp = mediaPlayer ?: return false.also { logger.warn("$debugLabel video reopen refused: no player.") }
        val ss = lastStreamSet ?: return false.also { logger.warn("$debugLabel video reopen refused: no stream set.") }
        val safeUrl = runCatching { MediaHostGuard.resolveSafeUrl(ss.currentVideo.url) }.getOrNull()
            ?: return false.also { logger.warn("$debugLabel video reopen refused: url rejected by host guard.") }
        val options = mutableListOf(*LibVlcMediaOptions.forUrl(safeUrl, live = liveSession.get()))
        options.add(":no-audio")
        LibVlc.currentHwBackend()?.let { options.add(":avcodec-hw=$it") }
        // A live stream has no timeline: `:start-time` + `set_time` throw the freshly opened input
        // away, which showed up as "video input re-opened at Xms" followed by a permanent 0 ms clock.
        // Reopen it at the live edge instead and skip the seek freeze (nothing to wait for).
        val live = liveSession.get()
        if (targetMs > 0L && !live) {
            options.add(":start-time=" + String.format(java.util.Locale.ROOT, "%.3f", targetMs / 1000.0))
        }
        return runCatching {
            videoReopenAttempts.incrementAndGet()
            firstFrameFired = false
            surface.dropQueued()
            if (!live) {
                pendingVideoRepositionMs.set(targetMs)
                pendingSeekTargetMs.set(targetMs)
                lastRepositionNanos = System.nanoTime()
                seekFrozenTargetMs = targetMs
                seekFrozenDeadlineNanos = System.nanoTime() + SEEK_FREEZE_MAX_NANOS
                seekFrozen = true
            }
            LibVlc.lib.libvlc_media_player_stop(mp)
            val media = LibVlc.createMedia(safeUrl, options.toTypedArray())
            LibVlc.lib.libvlc_media_player_set_media(mp, media)
            LibVlc.lib.libvlc_media_release(media)
            LibVlc.lib.libvlc_media_player_play(mp)
            if (!live) runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, targetMs) }
            logger.warn("$debugLabel video input re-opened{}; waiting for its first frame.",
                if (live) " at the live edge" else " at ${targetMs}ms")
            true
        }.getOrElse { t ->
            logger.error("$debugLabel video reopen threw at {}ms: {}", targetMs, t.message)
            logger.warn("$debugLabel libvlc log: {}", LibVlc.recentLogLines(12).joinToString(" ; "))
            false
        }
    }

    /** Watches the audio clock for the whole playback, so a dead or frozen audio input heals itself. */
    private fun startAudioWatchdog(lifecycleToken: Long) {
        if (LibVlcDiagnostics.noAutoResync) return
        Thread {
            var lastVideoMs = -1L
            var lastAudioMs = -1L
            var lastFeedMs = -1L
            var stalledTicks = 0
            var feedStalledTicks = 0
            var pausedTicks = 0
            // A live audio input needs a moment to reach the live edge; reviving it inside that window
            // only restarts the demuxer (each revive cost ~3 s), so start with one grace period there.
            var reviveGraceUntilNanos = if (liveSession.get()) System.nanoTime() + AUDIO_REVIVE_GRACE_NANOS else 0L
            while (!stopped.get() && !released.get() && lifecycleIsCurrent(lifecycleToken)) {
                try {
                    Thread.sleep(AUDIO_WATCHDOG_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (stopped.get() || released.get() || !lifecycleIsCurrent(lifecycleToken) ||
                    parkFlag.get() || seekFrozen || audioGatedBySeek.get() ||
                    pendingVideoRepositionMs.get() >= 0L
                ) {
                    lastVideoMs = -1L
                    lastAudioMs = -1L
                    lastFeedMs = -1L
                    stalledTicks = 0
                    feedStalledTicks = 0
                    continue
                }
                val done = CountDownLatch(1)
                submit {
                    try {
                        if (stopped.get() || released.get() || !lifecycleIsCurrent(lifecycleToken) ||
                            parkFlag.get() || seekFrozen || audioGatedBySeek.get() ||
                            pendingVideoRepositionMs.get() >= 0L
                        ) return@submit
                        val mp = mediaPlayer ?: return@submit
                        val ap = audioPlayer ?: return@submit
                        val videoMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                        val audioMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(ap) }.getOrDefault(-1L)
                        val audioState = runCatching { LibVlc.lib.libvlc_media_player_get_state(ap) }.getOrDefault(-1)
                        val feedMs = runCatching { audioOutput?.audioFeedMs() ?: -1L }.getOrDefault(-1L)
                        val hasPcm = audioOutput?.leadNanos() != null
                        if (System.nanoTime() < reviveGraceUntilNanos) {
                            lastVideoMs = videoMs
                            lastAudioMs = audioMs
                            lastFeedMs = feedMs
                            stalledTicks = 0
                            feedStalledTicks = 0
                            return@submit
                        }
                        if (audioState == LibVlc.LIBVLC_STATE_PAUSED && !parkFlag.get() && videoMs >= 0L) {
                            // A resume that only set_pause(0) can leave the audio input paused: sound
                            // then arrives seconds late (or not at all). Unpause first, re-attach last.
                            pausedTicks++
                            if (pausedTicks <= 2) {
                                logger.warn(
                                    "$debugLabel audio input still paused at {}ms (tick {}); re-issuing play.",
                                    audioMs, pausedTicks,
                                )
                                runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 0) }
                                runCatching { LibVlc.lib.libvlc_media_player_play(ap) }
                                runCatching { audioOutput?.setPaused(false) }
                            } else {
                                if (reviveDeadAudio(videoMs)) {
                                    reviveGraceUntilNanos = System.nanoTime() + AUDIO_REVIVE_GRACE_NANOS
                                    scheduleInitialAvSync()
                                }
                                // A refused revive must not end the attempts: retry the unpause next tick.
                                pausedTicks = 0
                            }
                            lastVideoMs = videoMs
                            lastAudioMs = audioMs
                            lastFeedMs = feedMs
                            stalledTicks = 0
                            feedStalledTicks = 0
                            return@submit
                        }
                        pausedTicks = 0
                        if (videoMs >= 0L && audioMs < 0L) {
                            logger.warn(
                                "$debugLabel audio input missing while video plays ({}ms, state={}); re-attaching.",
                                videoMs, audioState,
                            )
                            if (reviveDeadAudio(videoMs)) {
                                reviveGraceUntilNanos = System.nanoTime() + AUDIO_REVIVE_GRACE_NANOS
                                scheduleInitialAvSync()
                            }
                            lastAudioMs = -1L
                            lastFeedMs = -1L
                            stalledTicks = 0
                            feedStalledTicks = 0
                            return@submit
                        }
                        if (videoMs < 0L || audioMs < 0L) {
                            lastVideoMs = videoMs
                            lastAudioMs = audioMs
                            lastFeedMs = feedMs
                            return@submit
                        }
                        val videoAdvanced = lastVideoMs >= 0L && videoMs > lastVideoMs + 100L
                        val audioAdvanced = lastAudioMs >= 0L && audioMs > lastAudioMs + 100L
                        if (videoAdvanced && !audioAdvanced) {
                            stalledTicks++
                            if (stalledTicks >= 3) {
                                logger.warn(
                                    "$debugLabel audio clock stalled at {}ms (state={}) while video={}ms; re-attaching.",
                                    audioMs, audioState, videoMs,
                                )
                                if (reviveDeadAudio(videoMs)) {
                                    reviveGraceUntilNanos = System.nanoTime() + AUDIO_REVIVE_GRACE_NANOS
                                    scheduleInitialAvSync()
                                }
                                lastAudioMs = -1L
                                lastFeedMs = -1L
                                stalledTicks = 0
                                feedStalledTicks = 0
                                return@submit
                            }
                        } else {
                            stalledTicks = 0
                        }
                        if (videoAdvanced && hasPcm && feedMs >= 0L) {
                            if (lastFeedMs < 0L || feedMs < lastFeedMs) {
                                feedStalledTicks = 0
                            } else if (feedMs <= lastFeedMs + 50L) {
                                feedStalledTicks++
                            } else {
                                feedStalledTicks = 0
                            }
                            if (feedStalledTicks >= 3) {
                                logger.warn(
                                    "$debugLabel audio PCM feed stalled at {}ms (state={}) while video={}ms; re-attaching.",
                                    feedMs, audioState, videoMs,
                                )
                                if (reviveDeadAudio(videoMs)) {
                                    reviveGraceUntilNanos = System.nanoTime() + AUDIO_REVIVE_GRACE_NANOS
                                    scheduleInitialAvSync()
                                }
                                lastAudioMs = -1L
                                lastFeedMs = -1L
                                stalledTicks = 0
                                feedStalledTicks = 0
                                return@submit
                            }
                        } else {
                            feedStalledTicks = 0
                        }
                        lastVideoMs = videoMs
                        lastAudioMs = audioMs
                        lastFeedMs = feedMs
                    } finally {
                        done.countDown()
                    }
                }
                runCatching { done.await(3, TimeUnit.SECONDS) }
            }
        }.also { it.isDaemon = true }.start()
    }

    /** Detects a video clock/frame stall and reopens the video input if a play retry is not enough. */
    private fun startVideoWatchdog(lifecycleToken: Long) {
        Thread {
            var lastVideoMs = -1L
            var lastFrameAt = 0L
            var stallTicks = 0
            while (!stopped.get() && !released.get() && lifecycleIsCurrent(lifecycleToken)) {
                try {
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!lifecycleIsCurrent(lifecycleToken) || parkFlag.get() || seekFrozen ||
                    audioGatedBySeek.get() || pendingVideoRepositionMs.get() >= 0L
                ) {
                    lastVideoMs = -1L
                    lastFrameAt = 0L
                    stallTicks = 0
                    continue
                }
                val done = CountDownLatch(1)
                submit {
                    try {
                        if (!lifecycleIsCurrent(lifecycleToken) || parkFlag.get() || seekFrozen ||
                            audioGatedBySeek.get() || pendingVideoRepositionMs.get() >= 0L
                        ) return@submit
                        val mp = mediaPlayer ?: return@submit
                        val state = runCatching { LibVlc.lib.libvlc_media_player_get_state(mp) }.getOrDefault(-1)
                        val videoMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                        val frameAt = lastFrameNanos.get()
                        if (videoMs < 0L) return@submit
                        if (frameAt == 0L) {
                            // No decoded frame yet: startup buffering is not a runtime stall.
                            lastVideoMs = videoMs
                            lastFrameAt = 0L
                            stallTicks = 0
                            return@submit
                        }
                        if (lastVideoMs < 0L) {
                            lastVideoMs = videoMs
                            lastFrameAt = frameAt
                            return@submit
                        }
                        val clockAdvanced = videoMs > lastVideoMs + 100L
                        val frameAdvanced = frameAt > lastFrameAt
                        // A frozen clock repeats the same frame, so frames alone must not clear a stall.
                        if (frameAdvanced && clockAdvanced) {
                            lastVideoMs = videoMs
                            lastFrameAt = frameAt
                            stallTicks = 0
                            return@submit
                        }
                        if (clockAdvanced) lastVideoMs = videoMs
                        if (state == LibVlc.LIBVLC_STATE_BUFFERING && stallTicks < 8) {
                            stallTicks++
                            return@submit
                        }
                        stallTicks++
                        if (stallTicks == 3) {
                            logger.warn(
                                "$debugLabel video stall detected (state={} time={}ms); re-issuing play.",
                                state, videoMs,
                            )
                            runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 0) }
                            runCatching { LibVlc.lib.libvlc_media_player_play(mp) }
                            runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 0) }
                            lastVideoMs = videoMs
                            lastFrameAt = frameAt
                        } else if (stallTicks >= 6) {
                            val ap = audioPlayer
                            val audioMs = if (ap != null) {
                                runCatching { LibVlc.lib.libvlc_media_player_get_time(ap) }.getOrDefault(-1L)
                            } else -1L
                            val targetMs = if (audioMs >= 0L) audioMs else videoMs
                            logger.warn(
                                "$debugLabel video stall persists (state={} time={}ms); reopening video input at {}ms.",
                                state, videoMs, targetMs,
                            )
                            // The input stalled silently otherwise: keep what libvlc last said.
                            logger.warn(
                                "$debugLabel libvlc log before the reopen: {}",
                                LibVlc.recentLogLines(12).joinToString(" ; "),
                            )
                            // Live audio keeps playing: gating it here silenced the stream for as long
                            // as the reopened input took to produce a frame.
                            if (!liveSession.get()) gateAudioForSeek()
                            if (!reopenVideoAt(targetMs)) {
                                pendingVideoRepositionMs.set(-1L)
                                seekFrozen = false
                                audioGatedBySeek.set(false)
                                audioOutput?.setPaused(false)
                                if (ap != null) runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 0) }
                                escalateDeadVideo(targetMs)
                                return@submit
                            } else {
                                releaseAudioIfReopenStalls()
                            }
                            stallTicks = 0
                            lastVideoMs = -1L
                            lastFrameAt = 0L
                        }
                    } finally {
                        done.countDown()
                    }
                }
                runCatching { done.await(2, TimeUnit.SECONDS) }
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Re-asserts the seek target while a reopened input keeps playing from the wrong place, then
     * escalates. A fragmented input ignores set_time until its demuxer runs, so this retries.
     */
    private fun enforceVideoPosition(targetMs: Long) {
        val attempt = videoPositionFixAttempts.incrementAndGet()
        if (attempt > VIDEO_POSITION_FIX_MAX_ATTEMPTS) {
            logger.error("$debugLabel video will not seek to {}ms after {} tries; rebuilding.", targetMs, attempt - 1)
            escalateDeadVideo(targetMs)
            return
        }
        Thread {
            try {
                Thread.sleep(VIDEO_POSITION_FIX_GAP_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (stopped.get() || released.get()) return@Thread
            submit {
                if (stopped.get() || released.get()) return@submit
                val mp = mediaPlayer ?: return@submit
                val nowMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                if (nowMs >= 0L && kotlin.math.abs(nowMs - targetMs) <= SEEK_LANDING_OFF_TARGET_MS) {
                    videoPositionFixAttempts.set(0L)
                    pendingSeekTargetMs.set(-1L)
                    seekFrozen = false
                    logger.info("$debugLabel video landed at {}ms after re-assert {}.", nowMs, attempt)
                    releaseAudioForSeek()
                    return@submit
                }
                val seekable = runCatching { LibVlc.lib.libvlc_media_player_is_seekable(mp) == 1 }.getOrDefault(false)
                logger.warn(
                    "$debugLabel video at {}ms instead of {}ms (seekable={}); re-applying set_time ({}).",
                    nowMs, targetMs, seekable, attempt,
                )
                runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, targetMs) }
                enforceVideoPosition(targetMs)
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Last resort once the reopen budget is spent: rebuild the whole session from the audio clock,
     * and if the picture still never returns stop the audio instead of playing on under a frozen frame.
     */
    private fun escalateDeadVideo(targetMs: Long) {
        val ss = lastStreamSet
        val token = synchronized(lifecycleLock) { lifecycleGeneration.get() }
        if (nearEndOfMedia(targetMs)) {
            // A picture that freezes at the tail is the end of the part, not a broken input.
            logger.warn("$debugLabel video frozen at {}ms near the end; ending the session.", targetMs)
            errorMessage = ""
            eosReached = true
            fireStreamEnd()
            return
        }
        val attempt = videoRestartAttempts.incrementAndGet()
        if (ss != null && attempt <= VIDEO_RESTART_MAX_ATTEMPTS) {
            logger.warn(
                "$debugLabel no video frames; rebuilding playback ({} of {}) at {}ms.",
                attempt, VIDEO_RESTART_MAX_ATTEMPTS, targetMs,
            )
            Thread {
                try {
                    Thread.sleep(VIDEO_RESTART_BACKOFF_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (stopped.get() || released.get() || !lifecycleIsCurrent(token)) return@Thread
                runCatching { start(ss, targetMs * 1_000_000L, lastQualityStarted, liveSession.get()) }
            }.also { it.isDaemon = true }.start()
            return
        }
        logger.error("$debugLabel video unrecoverable after {} rebuilds; stopping audio.", attempt - 1)
        audioOutput?.setPaused(true)
        audioPlayer?.let { ap -> runCatching { LibVlc.lib.libvlc_media_player_stop(ap) } }
        errorMessage = "video playback lost (no frames)"
        eosReached = true
        fireStreamEnd()
    }

    /** Fail-open the audio gate if a watchdog reopen never delivers a first frame. */
    private fun releaseAudioIfReopenStalls() {
        Thread {
            try {
                Thread.sleep(12_000L)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (stopped.get() || released.get() || !audioGatedBySeek.get()) return@Thread
            logger.warn("$debugLabel video reopen produced no frame; releasing the audio gate.")
            pendingVideoRepositionMs.set(-1L)
            seekFrozen = false
            audioGatedBySeek.set(false)
            audioOutput?.setPaused(false)
            val ap = audioPlayer ?: return@Thread
            runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 0) }
        }.also { it.isDaemon = true }.start()
    }

    /** Logs how long the verifier took to declare the newest seek landed (not the visible latency). */
    private fun logSeekVerifyTiming(outcome: String) {
        val started = seekRequestedNanos
        if (started == 0L) return
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        logger.info(
            "$debugLabel seek verify: {} mode={} target={} ms after={} ms.",
            outcome, seekModeTag, seekFrozenTargetMs, elapsedMs,
        )
    }

    /** Logs the visible cost of the newest seek: request to the first frame that reached the screen. */
    private fun logSeekFirstFrame() {
        val started = seekRequestedNanos
        if (started == 0L) return
        seekRequestedNanos = 0L
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        logger.info(
            "$debugLabel seek timing: first frame mode={} target={} ms elapsed={} ms.",
            seekModeTag, seekFrozenTargetMs, elapsedMs,
        )
    }

    /** Gives an in-place seek [IN_PLACE_SEEK_GRACE_MS] to land, then reopens the input the old way. */
    private fun armInPlaceSeekFallback(targetMs: Long) {
        val generation = seekGeneration.get()
        val startedNanos = seekRequestedNanos
        Thread {
            try {
                Thread.sleep(IN_PLACE_SEEK_GRACE_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (stopped.get() || released.get() || seekGeneration.get() != generation || !seekFrozen) return@Thread
            // The verifier may have declared this very seek landed while this thread was waking up;
            // reopening then costs a full input rebuild for nothing.
            if (seekSettledGeneration == generation) return@Thread
            seekSettledGeneration = generation
            val elapsedMs = if (startedNanos != 0L) (System.nanoTime() - startedNanos) / 1_000_000L else -1L
            logger.warn(
                "$debugLabel in-place seek to {} ms did not land within {} ms; reopening the input ({} ms so far).",
                targetMs, IN_PLACE_SEEK_GRACE_MS, elapsedMs,
            )
            seekModeTag = "in-place-fallback"
            submit {
                if (stopped.get() || released.get() || seekGeneration.get() != generation || !seekFrozen) return@submit
                if (!reopenVideoAt(targetMs)) {
                    pendingVideoRepositionMs.set(-1L)
                    seekFrozen = false
                    audioGatedBySeek.set(false)
                    audioOutput?.setPaused(false)
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    /** True while a seek waits for its frame: picture frozen, audio silent, input maybe reopening. */
    fun isSeekBuffering(): Boolean = audioGatedBySeek.get() || pendingVideoRepositionMs.get() >= 0L

    /**
     * True when the video input was repositioned within [windowMs]. An END_REACHED that soon is an
     * artifact of the reposition (the fresh input stops at the seek point), not a truncated stream.
     */
    fun repositionedWithin(windowMs: Long): Boolean {
        val at = lastRepositionNanos
        return at != 0L && System.nanoTime() - at < windowMs * 1_000_000L
    }

    /** Seeks both the video and audio players to [offsetNanos]. */
    fun beginSeek(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean {
        // Reset audio flags before the seek so a pause-then-resume immediately after seek starts clean.
        audioOutput?.onSeekReset()
        // Nothing may play while the picture is still the pre-seek frame.
        gateAudioForSeek()
        // A seek/loop reuses the native player, but it also resets the software clock. Let the first
        // frame at the new target mark that clock again; otherwise firstFrameFired stays true from
        // the previous position and sync following sees a permanently stopped clock.
        firstFrameFired = false
        // Retire verification from an older seek before publishing this seek's target.
        val seekId = seekGeneration.incrementAndGet()
        videoReopenAttempts.set(0L)
        val lifecycleToken = synchronized(lifecycleLock) { lifecycleGeneration.get() }
        val targetMs = offsetNanos / 1_000_000L
        pendingSeekTargetMs.set(targetMs)
        videoPositionFixAttempts.set(0L)
        lastRepositionNanos = System.nanoTime()
        // Hold the last displayed picture while the video demuxer flushes onto the target. This
        // prevents pre-seek frames from visibly continuing while the audio player has already moved.
        seekFrozenTargetMs = targetMs
        seekFrozenDeadlineNanos = System.nanoTime() + SEEK_FREEZE_MAX_NANOS
        seekFrozen = true
        parkedSeekFrameNeeded = parkFlag.get()
        surface.dropQueued()
        // Suppress the A/V snap chain for the settle window: the video demuxer takes seconds to
        // flush onto a far target while the audio player lands there immediately — snapping audio
        // to the video's stale clock would rewind the whole playback to the pre-seek position.
        armSeekSettleWindow()
        // Desktop runtime set_time can move libvlc's clock while the vout keeps the old timeline.
        // Reopening the video input at the target is the only reliable way to make the picture follow.
        if (!systemAudio) {
            lastStreamSet = streamSet
            val mp = mediaPlayer
            val currentMs = if (mp != null) {
                runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
            } else {
                -1L
            }
            val deltaMs = if (currentMs >= 0L) kotlin.math.abs(targetMs - currentMs) else Long.MAX_VALUE
            // Small steps land in place: a reopen costs ~1 s of HTTP open plus decoder startup, which is
            // what made every +/-5 s seek feel slow. Anything larger keeps the reopen path.
            if (mp != null && !liveSession.get() && deltaMs <= SMALL_SEEK_MAX_DELTA_MS) {
                seekModeTag = "in-place"
                seekRequestedNanos = System.nanoTime()
                logger.info("$debugLabel seek target={} ms; in-place set_time (delta={} ms).", targetMs, deltaMs)
                runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, targetMs) }
                scheduleSeekLandingVerification(targetMs)
                armInPlaceSeekFallback(targetMs)
                return true
            }
            seekModeTag = "reopen"
            seekRequestedNanos = System.nanoTime()
            logger.info("$debugLabel seek target={} ms; reopening video input at target.", targetMs)
            if (reopenVideoAt(targetMs)) return true
            pendingVideoRepositionMs.set(-1L)
            seekFrozen = false
            audioGatedBySeek.set(false)
            audioOutput?.setPaused(false)
            return false
        }
        submit {
            if (seekId != seekGeneration.get() || !lifecycleIsCurrent(lifecycleToken)) return@submit
            val mp = mediaPlayer ?: return@submit
            // ENDED state ignores set_time and a bare play() is not guaranteed to restart in libvlc
            // 3.0 — stop() first, then play() restarts from the beginning (loop/replay path).
            val state = try { LibVlc.lib.libvlc_media_player_get_state(mp) } catch (_: Throwable) { -1 }
            val seekable = runCatching { LibVlc.lib.libvlc_media_player_is_seekable(mp) == 1 }.getOrDefault(false)
            runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, targetMs) }
            logger.info(
                "$debugLabel seek target={} ms state={} seekable={}.",
                targetMs, state, seekable,
            )
            if (state == LibVlc.LIBVLC_STATE_ENDED) {
                try {
                    if (systemAudio) {
                        // Android: never stop() or set_media() on an ended player. Both operations can
                        // tear down VLC-Android workers and hit its unsafe TLS destructor. Validate
                        // the selected URLs before retiring the old players, then bind fresh players.
                        val vUrl = streamSet.currentVideo.url
                        val safeVUrl = MediaHostGuard.resolveSafeUrl(vUrl)
                        val aUrl = streamSet.currentAudio.url
                        val safeAUrl = if (aUrl.isNotBlank() && !aUrl.equals(vUrl, ignoreCase = true)) {
                            MediaHostGuard.resolveSafeUrl(aUrl)
                        } else {
                            null
                        }
                        retireAndroidPlayers()
                        if (!lifecycleIsCurrent(lifecycleToken) || seekId != seekGeneration.get()) return@submit
                        val freshMp = player()
                        if (freshMp == null) throw IllegalStateException("libvlc video player unavailable")
                        val vOpts = mutableListOf(*LibVlcMediaOptions.forUrl(safeVUrl, live = liveSession.get()))
                        val vMedia = runCatching { LibVlc.createMedia(safeVUrl, vOpts.toTypedArray()) }.getOrNull()
                        if (vMedia != null) {
                            val attach = synchronized(lifecycleLock) {
                                if (lifecycleGeneration.get() == lifecycleToken && !stopped.get() && !released.get() && seekId == seekGeneration.get()) {
                                    runCatching { LibVlc.lib.libvlc_media_player_set_media(freshMp, vMedia) }
                                    true
                                } else {
                                    false
                                }
                            }
                            runCatching { LibVlc.lib.libvlc_media_release(vMedia) }
                            if (!attach) return@submit
                            synchronized(lifecycleLock) {
                                if (lifecycleGeneration.get() != lifecycleToken || stopped.get() || released.get() || seekId != seekGeneration.get()) return@submit
                                runCatching { LibVlc.lib.libvlc_media_player_play(freshMp) }
                                runCatching { LibVlc.lib.libvlc_media_player_set_time(freshMp, offsetNanos / 1_000_000L) }
                            }
                        }
                        // Audio player: bind the fresh player too, never reuse the ended one.
                        val ap = audioPlayer()
                        if (ap != null && safeAUrl != null) {
                            val aOpts = mutableListOf(*LibVlcMediaOptions.forUrl(safeAUrl, live = liveSession.get()))
                            aOpts.add(":no-video")
                            val aMedia = runCatching { LibVlc.createMedia(safeAUrl, aOpts.toTypedArray()) }.getOrNull()
                            if (aMedia != null) {
                                val attach = synchronized(lifecycleLock) {
                                    if (lifecycleGeneration.get() == lifecycleToken && !stopped.get() && !released.get() && seekId == seekGeneration.get()) {
                                        runCatching { LibVlc.lib.libvlc_media_player_set_media(ap, aMedia) }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                runCatching { LibVlc.lib.libvlc_media_release(aMedia) }
                                if (!attach) return@submit
                                synchronized(lifecycleLock) {
                                    if (lifecycleGeneration.get() != lifecycleToken || stopped.get() || released.get() || seekId != seekGeneration.get()) return@submit
                                    runCatching { LibVlc.lib.libvlc_media_player_play(ap) }
                                    runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, offsetNanos / 1_000_000L) }
                                }
                            }
                        }
                    } else {
                        // Desktop loop/replay (there is no Android TLS destructor). For the normal
                        // loop target (0 ms), play() is enough to leave ENDED without stopping the
                        // input first. Stopping the dedicated audio player at every five-second EOF
                        // made libvlc wait for decoder FIFOs to drain; after several loops that was
                        // the last line in the native log and the display froze. User seeks to a
                        // non-zero target still use the conservative stop -> play -> set_time path.
                        val zeroTarget = targetMs == 0L
                        if (zeroTarget) {
                            runCatching { LibVlc.lib.libvlc_media_player_play(mp) }
                            runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, 0L) }
                        } else {
                            LibVlc.lib.libvlc_media_player_stop(mp)
                            LibVlc.lib.libvlc_media_player_play(mp)
                            runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, targetMs) }
                        }
                        // The audio player must be restarted too: its own stream also reached ENDED
                        // (Bilibili DASH audio is often shorter than the video). Avoid stopping it on
                        // the ordinary loop; a stopped audio input is what leaves the decoder FIFO
                        // teardown pending in the reported Linux trace.
                        val ap = audioPlayer
                        if (ap != null) {
                            if (zeroTarget) {
                                runCatching { LibVlc.lib.libvlc_media_player_play(ap) }
                                runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, 0L) }
                            } else {
                                runCatching { LibVlc.lib.libvlc_media_player_stop(ap) }
                                runCatching { LibVlc.lib.libvlc_media_player_play(ap) }
                                runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, targetMs) }
                            }
                        }
                        scheduleSeekLandingVerification(targetMs)
                    }
                    // Re-arm the EOS gate: the loop/replay restart path (beginSeek from ENDED) must
                    // let the NEXT end-of-stream fire handleStreamEnd again, otherwise the second
                    // replay ends frozen on the last frame (eosFired stays true after the first
                    // ENDED → fireStreamEnd, so the second END_REACHED event is swallowed).
                    eosFired.set(false)
                    // The two players restart independently, so their clocks drift apart right away;
                    // the 10s auto-resync would leave the audio audibly off-sync for the first loop.
                    // Schedule an early correction (~1.5s) once both have settled into playback.
                    scheduleInitialAvSync()
                } catch (_: Throwable) { }
            } else {
                // The video player was already seeked above (rc logged there); do not re-issue it,
                // a second set_time can restart the demuxer flush it just started.
                synchronized(lifecycleLock) {
                    if (lifecycleGeneration.get() != lifecycleToken || stopped.get() || released.get() || seekId != seekGeneration.get()) return@submit
                    // Seek the audio player to the same position (non-ENDED path).
                    val ap = audioPlayer
                    if (ap != null) runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, targetMs) }
                }
                scheduleSeekLandingVerification(targetMs)
                // Re-arm the EOS gate after every in-place seek, matching the ENDED restart path;
                // a prior end notification must not suppress the next genuine end event.
                eosFired.set(false)
                // The two players recover from a seek independently (separate buffers / decoders), so
                // their clocks can land apart; the 10s auto-resync would leave the audio audibly
                // off-sync for the first ~10s after every seek. Schedule the same early correction
                // (~1.5s) the cold-start path uses. Skipped while parked by scheduleInitialAvSync.
                scheduleInitialAvSync()
            }
            if (seekId != seekGeneration.get() || !lifecycleIsCurrent(lifecycleToken)) return@submit
            // If a seek left the player in a dead state (stopped/ended — e.g. a backwards seek
            // into an already-released region dropping it out of PLAYING), resume it. Buffering(2)
            // is a normal transient after a backwards seek and MUST NOT be play()ed — that would
            // interrupt the seek and freeze the picture (the "backwards seek sometimes sticks" bug).
            try {
                // Android may have retired the player in the ENDED branch above; never query or
                // resume that stale pointer after rotation. Use only the current active player, while
                // holding the lifecycle lock so cleanup cannot release it between the check and call.
                val after = synchronized(lifecycleLock) {
                    if (lifecycleGeneration.get() != lifecycleToken || stopped.get() || released.get() || seekId != seekGeneration.get()) return@submit
                    val currentMp = mediaPlayer ?: return@submit
                    val sampled = LibVlc.lib.libvlc_media_player_get_state(currentMp)
                    if (sampled == LibVlc.LIBVLC_STATE_STOPPED || sampled == LibVlc.LIBVLC_STATE_ENDED) {
                        // `play()` is asynchronous, so an immediate state sample can still read ENDED
                        // even when the input is already leaving EOF. Do not stop here: that would
                        // reintroduce the decoder-fifo teardown that froze repeated loops. A delayed,
                        // generation-aware fallback below handles a genuinely latched ENDED state.
                        LibVlc.lib.libvlc_media_player_play(currentMp)
                    }
                    sampled
                }
                // A successful loop is logically playing immediately; the asynchronous PLAYING
                // callback will confirm it later. Updating this flag here prevents playlist/sync
                // callbacks arriving in that short window from treating the session as dead and
                // queueing another cold start on top of the just-restarted inputs.
                if (after == LibVlc.LIBVLC_STATE_STOPPED || after == LibVlc.LIBVLC_STATE_ENDED ||
                    after == LibVlc.LIBVLC_STATE_PLAYING || after == LibVlc.LIBVLC_STATE_PAUSED
                ) {
                    if (!reviveIfCurrentSeek(seekId)) return@submit
                }
                if (!systemAudio && targetMs == 0L) {
                    scheduleEndedRestartFallback(seekId, lifecycleToken)
                }
            } catch (_: Throwable) { }
        }
        logger.debug("$debugLabel libvlc seek to ${offsetNanos / 1_000_000} ms.")
        return true
    }

    /** Re-checks an ended desktop replay after libvlc has had time to leave ENDED asynchronously. */
    private fun scheduleEndedRestartFallback(seekId: Long, lifecycleToken: Long) {
        Thread({
            // libvlc transitions out of ENDED asynchronously. Give the normal play()+set_time
            // path a short window before using the conservative desktop stop/play fallback.
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            submit {
                val restarted = synchronized(lifecycleLock) {
                    if (seekId != seekGeneration.get() || lifecycleGeneration.get() != lifecycleToken || stopped.get() || released.get()) return@submit
                    val mp = mediaPlayer ?: return@submit
                    val videoState = runCatching { LibVlc.lib.libvlc_media_player_get_state(mp) }.getOrDefault(-1)
                    val videoDead = videoState == LibVlc.LIBVLC_STATE_STOPPED || videoState == LibVlc.LIBVLC_STATE_ENDED
                    val ap = audioPlayer
                    val audioState = ap?.let {
                        runCatching { LibVlc.lib.libvlc_media_player_get_state(it) }.getOrDefault(-1)
                    }
                    val audioDead = audioState == LibVlc.LIBVLC_STATE_STOPPED || audioState == LibVlc.LIBVLC_STATE_ENDED
                    if (!videoDead && !audioDead) return@submit
                    logger.warn(
                        "$debugLabel ended replay remained in native state (video={}, audio={}); retrying dead channel(s) with stop/play.",
                        videoState, audioState,
                    )
                    if (videoDead) {
                        runCatching { LibVlc.lib.libvlc_media_player_stop(mp) }
                        runCatching { LibVlc.lib.libvlc_media_player_play(mp) }
                        runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, 0L) }
                    }
                    if (audioDead && ap != null) {
                        runCatching { LibVlc.lib.libvlc_media_player_stop(ap) }
                        runCatching { LibVlc.lib.libvlc_media_player_play(ap) }
                        runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, 0L) }
                    }
                    true
                }
                if (restarted) reviveIfCurrentSeek(seekId)
            }
        }, "$debugLabel-loop-recovery").also { it.isDaemon = true }.start()
    }

    private fun lifecycleIsCurrent(token: Long): Boolean = synchronized(lifecycleLock) {
        lifecycleGeneration.get() == token && !stopped.get() && !released.get()
    }

    /** Reopens a seek only if no newer seek or stop won the lifecycle race. */
    private fun reviveIfCurrentSeek(seekId: Long): Boolean = synchronized(lifecycleLock) {
        if (seekId != seekGeneration.get() || stopped.get() || released.get()) return@synchronized false
        isPlaying = true
        stopped.set(false)
        eosReached = false
        true
    }

    /** Publishes a stop and invalidates queued seek/recovery work as one lifecycle transition. */
    private fun markStopped(parked: Boolean, releasing: Boolean = false) {
        synchronized(lifecycleLock) {
            isPlaying = false
            parkFlag.set(parked)
            eosReached = true
            stopped.set(true)
            lifecycleGeneration.incrementAndGet()
            if (releasing) released.set(true)
            seekSettleUntilNanos = 0L
            seekFrozen = false
            seekGeneration.incrementAndGet()
            audioGatedBySeek.set(false)
        }
    }

    /**
     * Verifies an in-place seek without interrupting a legitimate slow demuxer flush.
     *
     * Two clock samples distinguish the failure modes: an advancing clock still playing the
     * pre-seek timeline means libvlc dropped set_time and warrants one re-assertion; a stalled
     * clock means the demuxer is flushing toward the target, so re-applying set_time would restart
     * that flush. The loop is bounded and generation-aware, so newer seeks retire stale checks.
     */
    private fun scheduleSeekLandingVerification(targetMs: Long) {
        val generation = seekGeneration.get()
        val issuedAtNanos = System.nanoTime()
        Thread {
            var checks = 0
            var reassertions = 0
            // A landed seek keeps playing, so the clock must be compared against target + elapsed
            // time; comparing against the bare target would call every successful seek "dropped".
            fun expectedMs(paused: Boolean): Long =
                if (paused) targetMs else targetMs + (System.nanoTime() - issuedAtNanos) / 1_000_000L
            try {
                Thread.sleep(SEEK_VERIFY_DELAY_NANOS / 1_000_000L)
            } catch (_: InterruptedException) {
                return@Thread
            }
            while (checks++ < SEEK_VERIFY_MAX_CHECKS) {
                if (stopped.get() || released.get()) return@Thread
                if (seekGeneration.get() != generation) return@Thread
                val mp = mediaPlayer ?: return@Thread
                val state = runCatching { LibVlc.lib.libvlc_media_player_get_state(mp) }.getOrDefault(-1)
                // PLAYING / BUFFERING / PAUSED can all accept set_time; a paused seek must still
                // release the frozen picture once it lands. Anything else (opening, stopped) is
                // transient right after a reopen - bail out of the loop and the freeze would survive
                // until the fail-open deadline, which is what made a seek look stuck.
                if (state != LibVlc.LIBVLC_STATE_PLAYING &&
                    state != 2 &&
                    state != LibVlc.LIBVLC_STATE_PAUSED
                ) {
                    armSeekSettleWindow()
                    try {
                        Thread.sleep(SEEK_VERIFY_SAMPLE_GAP_NANOS / 1_000_000L)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    continue
                }
                val seekable = runCatching { LibVlc.lib.libvlc_media_player_is_seekable(mp) == 1 }
                    .getOrDefault(false)
                if (!seekable) {
                    logger.debug("$debugLabel Seek to {} ms waiting for libvlc to become seekable.", targetMs)
                    armSeekSettleWindow()
                    try {
                        Thread.sleep(SEEK_VERIFY_SAMPLE_GAP_NANOS / 1_000_000L)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    continue
                }
                val pausedNow = state == LibVlc.LIBVLC_STATE_PAUSED
                val firstMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                if (firstMs < 0) return@Thread
                // A landed seek sits ON the target (still buffering) or has played on from it; both
                // are successes, so compare against either position.
                if (kotlin.math.abs(firstMs - targetMs) <= SEEK_VERIFY_TOLERANCE_MS ||
                    kotlin.math.abs(firstMs - expectedMs(pausedNow)) <= SEEK_VERIFY_TOLERANCE_MS
                ) {
                    seekFrozen = false
                    seekSettledGeneration = generation
                    pendingSeekTargetMs.set(-1L)
                    logSeekVerifyTiming("landed")
                    releaseAudioForSeek()
                    return@Thread
                }
                try {
                    Thread.sleep(SEEK_VERIFY_SAMPLE_GAP_NANOS / 1_000_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (stopped.get() || released.get()) return@Thread
                if (seekGeneration.get() != generation) return@Thread
                val secondMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                if (secondMs < 0) return@Thread

                // Keep audio from being snapped back to the stale video clock while this seek is
                // still settling, including flushes longer than the original five-second window.
                armSeekSettleWindow()
                val landing = when {
                    kotlin.math.abs(secondMs - targetMs) <= SEEK_VERIFY_TOLERANCE_MS ||
                        kotlin.math.abs(secondMs - expectedMs(pausedNow)) <= SEEK_VERIFY_TOLERANCE_MS -> SeekLanding.LANDED
                    secondMs - firstMs >= SEEK_VERIFY_DROP_ADVANCE_MS -> SeekLanding.DROPPED
                    else -> SeekLanding.FLUSHING
                }
                // A paused player cannot advance, so FLUSHING is indistinguishable from a dropped
                // set_time there; retry once instead of waiting for the fail-open deadline.
                val verdict = if (state == LibVlc.LIBVLC_STATE_PAUSED && landing == SeekLanding.FLUSHING) {
                    SeekLanding.DROPPED
                } else {
                    landing
                }
                when (verdict) {
                    SeekLanding.LANDED -> {
                        seekFrozen = false
                        seekSettledGeneration = generation
                        pendingSeekTargetMs.set(-1L)
                        logSeekVerifyTiming("landed")
                        // Fallback for a seek whose new frame never reached the vout.
                        releaseAudioForSeek()
                        // The video and audio players recovered independently; re-run the early
                        // correction now that the video clock is trustworthy.
                        seekSettleUntilNanos = 0L
                        scheduleInitialAvSync()
                        return@Thread
                    }
                    SeekLanding.FLUSHING -> {
                        logger.debug(
                            "$debugLabel Seek to {} ms still flushing (video clock at {} ms); waiting.",
                            targetMs, secondMs
                        )
                    }
                    SeekLanding.DROPPED -> {
                        if (reassertions >= 1) {
                            logger.warn(
                                "$debugLabel Seek to {} ms still not landed after re-assert (video clock advancing at {} ms); giving up.",
                                targetMs, secondMs
                            )
                            // Runtime set_time is refused on this input; re-open only the video and
                            // let the new first frame land on the target. Audio stays gated until
                            // that frame is up, so nothing plays against the frozen picture.
                            val reopened = seekGeneration.get() == generation && reopenVideoAt(targetMs)
                            seekSettledGeneration = generation
                            if (reopened) {
                                enforceVideoPosition(targetMs)
                            } else {
                                logger.error("$debugLabel seek to {}ms failed and the input could not be reopened.", targetMs)
                                escalateDeadVideo(targetMs)
                            }
                            return@Thread
                        }
                        reassertions++
                        logger.warn(
                            "$debugLabel Seek to {} ms was dropped (video clock advancing {} -> {} ms); re-applying set_time ({}).",
                            targetMs, firstMs, secondMs, reassertions
                        )
                        runCatching {
                            submit {
                                if (stopped.get() || released.get()) return@submit
                                if (seekGeneration.get() != generation) return@submit
                                val current = mediaPlayer ?: return@submit
                                runCatching { LibVlc.lib.libvlc_media_player_set_time(current, targetMs) }
                                audioPlayer?.let { ap ->
                                    runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, targetMs) }
                                }
                                armSeekSettleWindow()
                            }
                        }
                        try {
                            Thread.sleep(SEEK_VERIFY_RECHECK_NANOS / 1_000_000L)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                }
            }
            // Fail open: a permanently broken seek must not leave the display frozen forever.
            seekFrozen = false
            seekSettledGeneration = generation
        }.also { it.isDaemon = true }.start()
    }

    /** Flushes queued PCM then seeks the audio player; used by the one-shot startup sync. */
    private fun correctAudioSync(
        ap: Pointer,
        targetMs: Long,
        videoMs: Long,
        audioMs: Long,
        leadMs: Long?,
        offsetMs: Long,
        driftMs: Long?,
        note: String,
    ) {
        runCatching { audioOutput?.reset() }
        runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, targetMs) }
        if (!parkFlag.get()) {
            runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 0) }
        }
        logger.info(
            "$debugLabel A/V sync: video={}ms audio={}ms buffer={}ms target={}ms offset={}ms drift={}ms; {}.",
            videoMs, audioMs, leadMs, targetMs, offsetMs, driftMs ?: 0L, note,
        )
        avSyncQuietUntilNanos = System.nanoTime() + AV_SYNC_SETTLE_AFTER_CORRECTION_MS * 1_000_000L
        probeAudioClock(targetMs, audioMs, note)
    }

    /** Absorbs a sub-second offset by skipping audio samples; a seek here only causes a re-buffer. */
    private fun absorbOffsetByCatchUp(offsetMs: Long, note: String) {
        val out = audioOutput ?: return
        if (offsetMs >= 0L) {
            logger.info("$debugLabel A/V catch-up skipped ({}): audio ahead by {}ms; leaving it alone.", note, offsetMs)
            return
        }
        out.requestCatchUpMs(-offsetMs)
        logger.info("$debugLabel A/V catch-up ({}): skipping ~{}ms of PCM to meet the video clock.", note, -offsetMs)
        avSyncQuietUntilNanos = System.nanoTime() + AV_SYNC_SETTLE_AFTER_CORRECTION_MS * 1_000_000L
    }

    /** True when the picture clock sits in the last seconds of a VOD, where a freeze is the stream's end. */
    private fun nearEndOfMedia(videoMs: Long): Boolean {
        val player = mediaPlayer ?: return false
        val durationMs = runCatching { LibVlc.lib.libvlc_media_player_get_length(player) }.getOrDefault(-1L)
        return durationMs > 0L && videoMs >= durationMs - AV_TAIL_END_GUARD_MS
    }

    /** Feeds one sample to [avSync] and applies its decision; never acts on a stalled clock. */
    private fun runAvSync(videoMs: Long, audioMs: Long, leadMs: Long?, note: String) {
        val ready = !audioClockNotReady(videoMs, audioMs)
        val hadPrev = lastAvVideoMs >= 0L
        val advancing = hadPrev && videoMs > lastAvVideoMs
        lastAvVideoMs = videoMs
        val target = if (leadMs != null && leadMs >= 0L) videoMs + leadMs else videoMs
        // Dropped PCM does not move libvlc's audio clock, so the audible position is the clock plus
        // everything catch-up already skipped; without this the same error is "corrected" forever.
        val catchUpMs = audioOutput?.catchUpAppliedMs() ?: 0L
        val audibleAudioMs = if (audioMs >= 0L) audioMs + catchUpMs else audioMs
        val err = if (audibleAudioMs >= 0L) audibleAudioMs - target else 0L
        val decision = avSync.onObservation(
            AvSyncController.Observation(videoMs, err, advancing, ready, System.nanoTime()),
        )
        // Startup alignment only: a cold audio decoder can start a second late. Live has no timeline,
        // and a frozen picture clock makes the audio look seconds ahead every second it stays stuck,
        // so neither case may be corrected here (that threw away ~7 s of PCM in one shot).
        if (hadPrev && !advancing && ready && abs(err) >= AV_STARTUP_ALIGN_MS) {
            if (avFrozenClockLogged.compareAndSet(false, true)) {
                logger.warn(
                    "$debugLabel A/V align ({}): picture clock is frozen at {}ms (audio appears {}ms ahead); waiting for it to move.",
                    note, videoMs, err,
                )
            }
            return
        }
        if (advancing) avFrozenClockLogged.set(false)
        // A cold-start correction only. Mid-playback the servo owns small offsets and a reposition
        // owns large ones; acting here on a slow picture clock threw away seconds of PCM.
        val coldSession = System.nanoTime() - sessionStartedAtNanos < AV_STARTUP_ALIGN_WINDOW_NANOS ||
            (audioInputAttachedAtNanos != 0L &&
                System.nanoTime() - audioInputAttachedAtNanos < AV_STARTUP_ALIGN_WINDOW_NANOS)
        if (coldSession && !liveSession.get() && ready && abs(err) >= AV_STARTUP_ALIGN_MS &&
            avStartupAlignAttempts.getAndIncrement() < AV_STARTUP_ALIGN_MAX_ATTEMPTS
        ) {
            if (err > 0L) {
                // Audio ahead: `set_time` here seeks it BACKWARDS and replays what was just heard. Skip
                // PCM while the picture has runway, and leave it alone once the picture sits at the tail.
                avSyncQuietUntilNanos = System.nanoTime() + AV_STARTUP_ALIGN_SETTLE_NANOS
                if (nearEndOfMedia(videoMs)) {
                    logger.warn(
                        "$debugLabel A/V align ({}): audio {}ms ahead while the picture sits at the end; leaving audio alone.",
                        note, err,
                    )
                } else {
                    audioOutput?.requestCatchUpMs(err.coerceAtMost(AV_ALIGN_MAX_SKIP_MS))
                    logger.warn(
                        "$debugLabel A/V align ({}): audio {}ms ahead; skipped that much PCM instead of seeking it back.",
                        note, err,
                    )
                }
                return
            }
            val ap = audioPlayer ?: return
            runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, target) }
            audioOutput?.reset()
            avSyncQuietUntilNanos = System.nanoTime() + AV_STARTUP_ALIGN_SETTLE_NANOS
            logger.warn(
                "$debugLabel A/V startup align ({}): audio was {}ms off; moved audio to {}ms.",
                note, err, target,
            )
            return
        }
        if (liveSession.get()) {
            if (decision.action != AvSyncController.Action.NONE && liveSkipLogged.compareAndSet(false, true)) {
                logger.info(
                    "$debugLabel A/V correction skipped ({}): live stream has no timeline (err={}ms).",
                    note, err,
                )
            }
            return
        }
        if (!avServoEnabled) {
            if (decision.action != AvSyncController.Action.NONE) {
                logger.info("$debugLabel A/V servo off ({}): would have {}({}ms) on err={}ms.", note, decision.action, decision.amountMs, err)
            }
            return
        }
        when (decision.action) {
            AvSyncController.Action.NONE -> Unit
            AvSyncController.Action.SKIP -> {
                audioOutput?.requestCatchUpMs(decision.amountMs)
                logger.info(
                    "$debugLabel A/V action ({}): {}; skipping {}ms of PCM ({}ms skipped since the last reset, err now ≈{}ms).",
                    note, decision.reason, decision.amountMs, catchUpMs, err + decision.amountMs,
                )
            }
            AvSyncController.Action.INSERT -> {
                // Repeating PCM only makes sense while the picture still advances; at the tail the video
                // clock is the thing that stopped, so leave the audio running instead of stuttering it.
                if (nearEndOfMedia(videoMs)) {
                    logger.info("$debugLabel A/V action ({}): {}; picture sits at the end, leaving audio alone.", note, decision.reason)
                    return
                }
                audioOutput?.requestInsertMs(decision.amountMs)
                logger.info("$debugLabel A/V action ({}): {}; repeating {}ms of PCM.", note, decision.reason, decision.amountMs)
            }
            AvSyncController.Action.REPOSITION -> {
                val ap = audioPlayer ?: return
                runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, videoMs) }
                logger.warn("$debugLabel A/V action ({}): {}; repositioned audio to {}ms.", note, decision.reason, videoMs)
            }
        }
    }

    /** True while a re-attached or reset audio input has no trustworthy clock yet. */
    private fun audioClockNotReady(videoMs: Long, audioMs: Long): Boolean {
        val attachedAt = audioInputAttachedAtNanos
        if (attachedAt != 0L && System.nanoTime() - attachedAt < AV_AUDIO_ATTACH_GRACE_NANOS) return true
        return videoMs > 5_000L && audioMs in 0L..AV_AUDIO_RESET_MAX_MS
    }

    /** Logs one audible-clock sample: libvlc clocks plus the PCM counters diffed by the reader. */
    private fun logAvSample(label: String) {
        submit {
            val ap = audioPlayer ?: return@submit
            val mp = mediaPlayer ?: return@submit
            if (stopped.get() || released.get()) return@submit
            val audioMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(ap) }.getOrDefault(-1L)
            val videoMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
            val leadMs = audioOutput?.leadNanos()?.div(1_000_000L) ?: -1L
            val fedMs = audioOutput?.fedNanos()?.div(1_000_000L) ?: -1L
            val playedMs = audioOutput?.emittedNanos()?.div(1_000_000L) ?: -1L
            val skipMs = audioOutput?.catchUpAppliedMs() ?: 0L
            runAvSync(videoMs, audioMs, leadMs, label)
            logger.info(
                "$debugLabel A/V sample {}: audio={}ms video={}ms lead={}ms fed={}ms played={}ms skipped={}ms.",
                label, audioMs, videoMs, leadMs, fedMs, playedMs, skipMs,
            )
        }
    }

    /** Samples the audible clock every few seconds for the whole session; one thread per session. */
    private fun startAvClockSampler() {
        if (!avSamplerStarted.compareAndSet(false, true)) return
        Thread {
            while (!stopped.get() && !released.get()) {
                try {
                    Thread.sleep(AV_SAMPLE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (stopped.get() || released.get()) return@Thread
                if (parkFlag.get()) continue
                logAvSample("steady")
            }
        }.also { it.isDaemon = true }.start()
    }

    /** Samples the audio clock and the PCM counters after a correction, at 250 ms and 2.25 s. */
    private fun probeAudioClock(targetMs: Long, beforeMs: Long, note: String) {
        Thread {
            var elapsedMs = 0L
            for (stepMs in AV_PROBE_STEPS_MS) {
                try {
                    Thread.sleep(stepMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                elapsedMs += stepMs
                if (stopped.get() || released.get()) return@Thread
                submit {
                    val ap = audioPlayer ?: return@submit
                    if (stopped.get() || released.get()) return@submit
                    val audioMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(ap) }.getOrDefault(-1L)
                    val videoMs = mediaPlayer?.let { mp ->
                        runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                    } ?: -1L
                    val leadMs = audioOutput?.leadNanos()?.div(1_000_000L) ?: -1L
                    val fedMs = audioOutput?.fedNanos()?.div(1_000_000L) ?: -1L
                    val playedMs = audioOutput?.emittedNanos()?.div(1_000_000L) ?: -1L
                    logger.info(
                        "$debugLabel A/V probe +{}ms after {}: audio {}->{}ms target={}ms video={}ms lead={}ms fed={}ms played={}ms.",
                        elapsedMs, note, beforeMs, audioMs, targetMs, videoMs, leadMs, fedMs, playedMs,
                    )
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Schedules a short A/V correction chain right after playback starts. The video and audio
     * players start independently, so their clocks drift apart immediately; the 10s auto-resync in
     * [currentPacingNanos] would leave the audio audibly off-sync for the first ~10s. This snaps the
     * audio player to the video `get_time` as soon as both have settled, then re-checks a few times:
     * right after a video switch the audio player may still be opening its stream, so the first
     * check often fires before its clock exists. Best-effort: the 10s auto-resync still corrects
     * any residual drift.
     */
    private fun scheduleInitialAvSync() {
        if (LibVlcDiagnostics.noAutoResync) return
        Thread {
            // First check well before the old 1.5s mark, then a few spaced re-checks so a slow
            // audio start converges quickly instead of waiting for the 10s auto-resync.
            var delayMs = 400L
            var attempts = 0
            // Measure the normal pipeline offset once; only changes from it are real drift.
            var baselineOffsetMs: Long? = null
            var lastOffsetMs: Long? = null
            while (attempts < 6 && !stopped.get()) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                delayMs = 700L
                attempts++
                val aligned = CountDownLatch(1)
                var inSync = false
                submit {
                    try {
                        val mp = mediaPlayer
                        val ap = audioPlayer
                        if (mp == null || ap == null || stopped.get() || parkFlag.get()) {
                            inSync = true // nothing to sync any more; end the chain
                            return@submit
                        }
                        val videoMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                        val videoState = runCatching { LibVlc.lib.libvlc_media_player_get_state(mp) }.getOrDefault(-1)
                        val audioMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(ap) }.getOrDefault(-1L)
                        // Null means no PCM reached the line yet; zero would fake a large drift.
                        val rawLeadNanos = audioOutput?.leadNanos()
                        val leadMs = rawLeadNanos?.coerceAtLeast(0L)?.div(1_000_000L)
                        val targetAudioMs = leadMs?.let { (videoMs + it).coerceAtLeast(0L) }
                        if (attempts <= 3) {
                            logger.info(
                                "$debugLabel A/V check: video={}ms state={} audio={}ms lead={}ms target={}ms offset={}ms baseline={}ms.",
                                videoMs, videoState, audioMs, leadMs ?: -1L, targetAudioMs ?: -1L,
                                if (targetAudioMs != null && audioMs >= 0L) audioMs - targetAudioMs else -1L,
                                baselineOffsetMs ?: -1L,
                            )
                        }
                        when {
                            // Post-seek settle window: the video clock still reports the pre-seek
                            // position while its demuxer flushes toward the target. Snapping the
                            // audio (already at the target) to that stale clock rewinds playback.
                            seekSettling() -> Unit
                            // Audio is deliberately muted until the post-seek frame is on screen;
                            // snapping it now would undo the gate.
                            audioGatedBySeek.get() -> Unit
                            // A fresh correction re-buffers the audio input for a few hundred ms;
                            // correcting again inside that window only restarts the buffer.
                            System.nanoTime() < avSyncQuietUntilNanos -> Unit
                            // Video clock not up yet: keep waiting for a later attempt.
                            videoMs < 0 -> Unit
                            // A resumed video clock can restart at 0 while audio keeps the park
                            // position; rewinding audio to it would replay minutes of sound.
                            audioMs >= 0L && audioMs - videoMs > AV_VIDEO_RESET_GAP_MS -> {
                                runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, audioMs) }
                                logger.info(
                                    "$debugLabel A/V sync: video={}ms behind audio={}ms; re-seeking video.",
                                    videoMs, audioMs,
                                )
                                inSync = true
                            }
                            // No audio input at all (get_time = -1): seeking it would go nowhere, so
                            // re-create the input instead of poking a dead player.
                            audioMs < 0L -> {
                                if (reviveDeadAudio(videoMs)) inSync = true
                            }
                            // Wait for a real PCM sample before comparing clocks.
                            rawLeadNanos == null || rawLeadNanos < 0L -> Unit
                            else -> {
                                runAvSync(videoMs, audioMs, leadMs, "startup")
                                inSync = true
                            }
                        }
                    } finally {
                        aligned.countDown()
                    }
                }
                aligned.await(2, TimeUnit.SECONDS)
                if (inSync) return@Thread
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Stops the session. To the extent possible the libvlc players are NOT stopped on Android:
     * this libvlc-all AAR's `libvlc_media_player_stop` does not join every VLC worker thread, and
     * `pthread_key_clean_all` on an exiting thread runs VLC-Android's `jni_detach_thread` TLS
     * destructor which dereferences thread-local state — observed SIGSEGV at `libvlc.so+0xef7418`
     * on plain world exit AND on audio DASH stream end even with stop serialised and never released.
     * Pausing keeps every worker thread alive, so the TLS destructor never runs with stale state;
     * the players then stay allocated for the JVM lifetime and the OS reclaims them on exit.
     * Desktop keeps stop (its threads join synchronously and there is no Android TLS destructor).
     */
    fun stop() {
        markStopped(parked = false)
        // Wake a MediaPlayer.start() waiting for its first frame. Without this, a switch that
        // arrives while the old stream is buffering waits the full ten-second first-frame timeout
        // before its teardown can run.
        firstFrameLatch.countDown()
        submit {
            val mp = mediaPlayer
            if (mp != null) {
                stopOrPause(mp)
            }
            val ap = audioPlayer
            if (ap != null) {
                stopOrPause(ap)
            }
        }
        surface.clear()
    }

    /**
     * Stops playback from the CALLER's thread, bypassing the control-executor queue.
     *
     * `stop()` serialises through the single control executor, but that queue can be blocked by a
     * long-running task (a slow media attach whose native input thread is stuck reading a
     * throttled CDN edge). The stop then sits behind it for tens of seconds, `awaitStopped()`
     * times out, and the caller creates a replacement player while the old one is still alive —
     * two native players and two vouts on one display (observed 2026-09-06: two "libvlc video
     * setup" lines a second apart for two different URLs after a video switch). libvlc's player
     * API is thread-safe, so calling stop/pause directly here tears the media down immediately;
     * the queued [stop]/[cleanup] tasks that follow become near-instant no-ops.
     */
    fun stopNow() {
        markStopped(parked = false)
        firstFrameLatch.countDown()
        val mp = mediaPlayer
        if (mp != null) {
            runCatching {
                if (systemAudio) LibVlc.lib.libvlc_media_player_set_pause(mp, 1)
                else LibVlc.lib.libvlc_media_player_stop(mp)
            }
        }
        val ap = audioPlayer
        if (ap != null) {
            runCatching {
                if (systemAudio) LibVlc.lib.libvlc_media_player_set_pause(ap, 1)
                else LibVlc.lib.libvlc_media_player_stop(ap)
            }
        }
        surface.clear()
    }

    /**
     * Immediately PAUSES both native players from the caller's thread WITHOUT the blocking native
     * stop: `libvlc_media_player_set_pause` returns at once and does not join the input / vout / aout
     * worker threads, unlike `libvlc_media_player_stop` (which can sit for seconds behind a throttled
     * CDN edge). A caller about to release GPU resources can therefore be sure no further frames are
     * produced without freezing the render / client thread.
     */
    fun pauseNow() {
        markStopped(parked = true)
        // stopAsync() uses this path while a start task may be waiting for the first frame.
        firstFrameLatch.countDown()
        val mp = mediaPlayer
        if (mp != null) runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 1) }
        val ap = audioPlayer
        if (ap != null) runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 1) }
        audioOutput?.setPaused(true)
        surface.clear()
    }

    /**
     * Android: pause instead of stop (see [stop] KDoc) — worker threads stay alive, the TLS
     * destructor never observes freed state. Desktop: plain stop.
     */
    private fun stopOrPause(p: Pointer) {
        if (systemAudio) {
            runCatching { LibVlc.lib.libvlc_media_player_set_pause(p, 1) }
        } else {
            runCatching { LibVlc.lib.libvlc_media_player_stop(p) }
        }
    }

    /**
     * Retires Android players without invoking stop, release, or set_media on them.
     * The VLC-Android AAR may tear down native workers from set_media too, so retired players
     * remain paused and strongly referenced until process exit; the OS then reclaims them. We still
     * wait briefly for the paused state before creating a replacement, which prevents two vouts from
     * decoding at full speed during rapid URL/quality switches while preserving the safe no-stop rule.
     */
    private fun retireAndroidPlayers() {
        synchronized(lifecycleLock) {
            if (!systemAudio) return
            mediaPlayer?.let { player ->
                deactivateVideoCallbacks(player)
                pauseAndroidPlayerAndAwait(player)
                retiredAndroidPlayers += player
            }
            audioPlayer?.let { player ->
                pauseAndroidPlayerAndAwait(player)
                retiredAndroidPlayers += player
            }
            mediaPlayer = null
            audioPlayer = null
        }
    }

    /** Pauses a VLC-Android player and gives its input/vout threads a bounded chance to quiesce. */
    private fun pauseAndroidPlayerAndAwait(player: Pointer) {
        runCatching { LibVlc.lib.libvlc_media_player_set_pause(player, 1) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750)
        while (System.nanoTime() < deadline) {
            val state = runCatching { LibVlc.lib.libvlc_media_player_get_state(player) }.getOrDefault(-1)
            if (state != LibVlc.LIBVLC_STATE_PLAYING) return
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        logger.debug("$debugLabel Android player did not report PAUSED before replacement; keeping it retired.")
    }

    /**
     * Stops the session and releases resources. GPU teardown is deferred to the render thread.
     *
     * Teardown ordering is critical on Android: VLC-Android registers a `pthread_key` whose TLS
     * destructor (`jni_detach_thread`, `modules/video_output/android/utils.c`) runs when ANY VLC
     * native worker thread exits, and it dereferences thread-local state. This AAR's
     * `libvlc_media_player_stop` does NOT join every worker thread (OpenSL ES aout, MediaCodec
     * decoder, vout display threads can still be winding down when stop returns) — a worker
     * thread exiting at ANY later moment dereferences freed state and dies with SIGSEGV at
     * `libvlc.so+0xef7418` inside `pthread_key_clean_all` (observed on plain world exit AND on
     * DASH audio stream end, even with stop serialised and never released). On Android active teardown
     * must PAUSE the players instead of stopping them, but this cannot prevent workers from exiting at
     * natural EOF. The Android loader establishes VLC's JNI TLS key before JNA's callback key, and
     * callback threads are explicitly kept attached, so EOF teardown observes the JVM attachment in
     * the safe order; the OS reclaims paused players at process exit. Desktop has no such TLS
     * destructor and keeps serialised stop -> release.
     */
    fun cleanup() {
        markStopped(parked = false, releasing = true)
        val (mp, ap) = synchronized(lifecycleLock) {
            val oldVideo = mediaPlayer
            val oldAudio = audioPlayer
            deactivateVideoCallbacks(oldVideo)
            mediaPlayer = null
            audioPlayer = null
            oldVideo to oldAudio
        }
        // On desktop, stop must run serialised on the control executor and release MUST run only
        // after stop has completed. On Android the players are only paused (never stopped) and
        // kept alive. Add cleanup players to the same process-lifetime roots as URL-switch players;
        // otherwise DisplayMediaController can release the owning manager while libvlc still has
        // delayed callbacks into its direct buffers/JNA trampolines.
        if (systemAudio) {
            deactivateVideoCallbacks(mp)
            if (mp != null) retiredAndroidPlayers += mp
            if (ap != null) retiredAndroidPlayers += ap
            synchronized(ANDROID_KEEP_ALIVE) {
                if (!ANDROID_KEEP_ALIVE.contains(this)) ANDROID_KEEP_ALIVE += this
            }
        }
        val done = CountDownLatch(1)
        try {
            controlExecutor.execute {
                try {
                    if (mp != null) {
                        stopOrPause(mp)
                        if (!systemAudio) {
                            runCatching { LibVlc.lib.libvlc_media_player_release(mp) }
                        }
                    }
                    if (ap != null) {
                        stopOrPause(ap)
                        if (!systemAudio) {
                            runCatching { LibVlc.lib.libvlc_media_player_release(ap) }
                        }
                    }
                } finally {
                    done.countDown()
                }
            }
        } catch (_: RejectedExecutionException) {
            done.countDown()
        }
        runCatching { done.await(15, TimeUnit.SECONDS) }
        runCatching { controlExecutor.shutdownNow() }
        audioOutput?.close()
        surface.clear()
        renderExecutor.execute { surface.cleanup() }
    }

    // ── Quality switch ──────────────────────────────────────────────────────

    /** Hard quality switch: stop current media and start the new rendition. */
    fun beginQualitySwitch(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int) {
        stop()
        start(streamSet, offsetNanos, lastQuality)
    }

    fun promoteIncoming(): Boolean = true // hard switch already promoted

    // ── Audio track switch ──────────────────────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    fun setWarmAudioTracks(tracks: List<WarmTrack>) {
        // Not needed with libvlc.
    }

    // ── Park / suspend / resume ─────────────────────────────────────────────

    fun canPark(): Boolean = true

    private fun canHoldWarm(): Boolean = true

    fun suspend(allowExternalProcess: Boolean = false, retainBuffered: Boolean = false): Boolean {
        val mp = mediaPlayer ?: return false
        parkFlag.set(true)
        parkedAtNanos = System.nanoTime()
        audioGatedBySeek.set(false)
        // Parked: the last displayed frame stays on screen naturally; release any pending seek
        // freeze so a later resume is never gated on a seek that was superseded while parked.
        seekFrozen = false
        // Persist the authoritative libvlc position (get_time, ms) rather than the wall-clock
        // estimate, so the progress bar resumes exactly where the stream was paused.
        parkPositionNanos = currentPacingNanos().takeIf { it >= 0 } ?: clock.currentTime()
        // Freeze the software clock too. A long pause can outlive the warm session; resuming then
        // cold-starts from clock.originNanos, which otherwise may still be the previous seek origin.
        clock.reset(parkPositionNanos)
        logger.info("$debugLabel pause at {} ms.", parkPositionNanos / 1_000_000L)
        // Gate PCM immediately and pause both native players from this thread. Waiting for the
        // control queue left audio audible while a start() task was still holding it.
        audioOutput?.setPaused(true)
        if (firstFrameFired) {
            val ap = audioPlayer
            runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 1) }
            if (ap != null) runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 1) }
        }
        submit {
            runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 1) }
            val ap = audioPlayer
            if (ap != null) {
                runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 1) }
            }
            // Gate the PCM feed explicitly. libvlc's own aout pause callback must not control this:
            // it also fires while the input rebuffers, and a missing resume muted the session for
            // good (see LibVlcAudioOutput.onPause).
            audioOutput?.setPaused(true)
        }
        return true
    }

    /**
     * Pushes the kept still frame back through the surface queue, so the wall texture (or a new
     * overlay) shows the last picture while the session stays paused. Playback state is untouched:
     * un-pausing to fetch a frame moved the software clock and left the display running silently.
     */
    fun pushStillFrame() {
        val frame = lastFrameSnapshot() ?: return
        runCatching { surface.present(frame.buffer) }
    }

    fun resume() {
        val mp = mediaPlayer ?: return
        val pausedMs = if (parkedAtNanos > 0L) {
            (System.nanoTime() - parkedAtNanos) / 1_000_000L
        } else {
            0L
        }
        parkFlag.set(false)
        userPauseRequested.set(false)
        audioGatedBySeek.set(false)
        parkedSeekFrameNeeded = false
        clock.markFirstFrame()
        logger.info("$debugLabel resume from {} ms (paused {} ms).", clock.originNanos / 1_000_000L, pausedMs)
        if (!systemAudio && pausedMs >= LONG_PAUSE_REOPEN_MS && parkPositionNanos > 0L) {
            logger.warn(
                "$debugLabel long pause ({}ms); reopening video input at {}ms.",
                pausedMs, parkPositionNanos / 1_000_000L,
            )
            gateAudioForSeek()
            if (reopenVideoAt(parkPositionNanos / 1_000_000L)) {
                releaseAudioIfReopenStalls()
                return
            }
            pendingVideoRepositionMs.set(-1L)
            seekFrozen = false
            audioGatedBySeek.set(false)
            audioOutput?.setPaused(false)
        }
        submit {
            val ap = audioPlayer
            // Re-apply the frozen position while still paused; set_time after unpausing is
            // asynchronous and was ignored, so video resumed from 0 while audio stayed parked.
            val resumeMs = parkPositionNanos / 1_000_000L
            if (resumeMs > 0L) {
                runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, resumeMs) }
                // Audio is already parked at resumeMs; seeking it again here can EOF a fragmented
                // m4s input. The media-level :start-time handles the initial offset.
            }
            runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 0) }
            // A long pause can leave the video player parked even after set_pause(0); play()
            // resumes it in that state. Audio resumes through set_pause(0) and works already.
            runCatching { LibVlc.lib.libvlc_media_player_play(mp) }
            runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 0) }
            if (ap != null) {
                // The audio player keeps running through a pause (only the PCM line is gated), so
                // realign it to the parked position before the line opens again.
                if (resumeMs > 0L) runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, resumeMs) }
                runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 0) }
                // A long park can leave the audio player stopped rather than paused; set_pause alone
                // then advances get_time without delivering PCM. Re-issue play on the audio channel.
                runCatching { LibVlc.lib.libvlc_media_player_play(ap) }
                runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 0) }
            }
            audioOutput?.setPaused(false)
        }
        verifyResume(parkPositionNanos / 1_000_000L)
        scheduleInitialAvSync()
    }

    /** Re-issues video play when a long park leaves the video clock frozen after resume. */
    private fun verifyResume(targetMs: Long) {
        val lifecycleToken = synchronized(lifecycleLock) { lifecycleGeneration.get() }
        Thread {
            var prevMs = -1L
            var frozenTicks = 0
            var retries = 0
            repeat(10) {
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (stopped.get() || released.get() || parkFlag.get() || !lifecycleIsCurrent(lifecycleToken)) return@Thread
                // A seek or a reopen owns the clock for its whole settle window; judging "frozen"
                // inside it only produced false alarms and an unnecessary input reopen.
                if (seekFrozen || audioGatedBySeek.get() || pendingVideoRepositionMs.get() >= 0L) {
                    prevMs = -1L
                    frozenTicks = 0
                    return@repeat
                }
                val done = CountDownLatch(1)
                var retry = false
                var videoMs = -1L
                var state = -1
                submit {
                    try {
                        if (stopped.get() || released.get() || parkFlag.get() || !lifecycleIsCurrent(lifecycleToken)) return@submit
                        val mp = mediaPlayer ?: return@submit
                        state = runCatching { LibVlc.lib.libvlc_media_player_get_state(mp) }.getOrDefault(-1)
                        videoMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                        if (state == LibVlc.LIBVLC_STATE_PAUSED) {
                            retry = true
                        } else if (state == LibVlc.LIBVLC_STATE_PLAYING) {
                            // One still sample is normal (buffering); two in a row means the player
                            // really stayed parked, because a playing clock cannot hold for 500 ms.
                            if (prevMs >= 0L && videoMs <= prevMs + 50L) frozenTicks++ else frozenTicks = 0
                            retry = frozenTicks >= 2
                        }
                        if (retry) {
                            runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 0) }
                            runCatching { LibVlc.lib.libvlc_media_player_play(mp) }
                            runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 0) }
                            logger.warn(
                                "$debugLabel video still parked after resume (state={} time={}ms, {} still samples); re-issuing play.",
                                state, videoMs, frozenTicks + 1,
                            )
                        }
                    } finally {
                        done.countDown()
                    }
                }
                runCatching { done.await(2, TimeUnit.SECONDS) }
                if (videoMs >= 0L) {
                    // A moving clock is the only proof that the resume worked; anything else is
                    // judged by the two-still-samples rule inside the task above.
                    if (prevMs >= 0L && videoMs > prevMs + 50L) return@Thread
                    prevMs = videoMs
                }
                if (!retry) return@repeat
                retries++
                if (retries >= 2 && videoReopenAttempts.get() < VIDEO_REOPEN_MAX_ATTEMPTS) {
                    logger.warn("$debugLabel video still parked; reopening video input at {}ms.", targetMs)
                    gateAudioForSeek()
                    if (reopenVideoAt(targetMs)) {
                        releaseAudioIfReopenStalls()
                    } else {
                        pendingVideoRepositionMs.set(-1L)
                        seekFrozen = false
                        audioGatedBySeek.set(false)
                        audioOutput?.setPaused(false)
                    }
                    return@Thread
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun isParked(): Boolean = parkFlag.get()

    fun parkedPositionNanos(): Long? = parkPositionNanos.takeIf { parkFlag.get() && it >= 0 }

    /**
     * Repositions the frozen anchor of a parked session after an in-place seek. A warm-paused or
     * parked session reads its position back through [parkedPositionNanos] (UI progress, resume
     * position, [resume] pacing fallback), so the anchor must follow the seek or the UI would show
     * the pre-seek position until the session is resumed. No-op when not parked.
     */
    fun repositionParked(nanos: Long) {
        if (parkFlag.get() && nanos >= 0) parkPositionNanos = nanos
    }

    // ── Audio helpers ────────────────────────────────────────────────────────

    /**
     * Replaces the selected audio rendition without changing the video on desktop. Android needs a
     * full session restart because switching between muxed and separate renditions changes whether
     * the video player itself must carry audio; its native players are never reused after media.
     */
    fun restartAudio(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean {
        if (released.get()) return false
        if (streamSet.currentAudio.url.isBlank()) return false
        // A cold-paused or EOS session has no native channel to replace. Keeping the new stream in
        // MediaPlayer is still a successful selection; the next start() will attach it.
        if (stopped.get()) return true
        if (systemAudio) {
            val wasPlaying = isPlaying
            val wasParked = parkFlag.get()
            return try {
                if (!start(streamSet, offsetNanos, lastQuality)) {
                    false
                } else {
                    if (!wasPlaying || wasParked) {
                        suspend(allowExternalProcess = true, retainBuffered = true)
                    }
                    true
                }
            } catch (t: Throwable) {
                logger.warn("$debugLabel failed to switch Android audio rendition.", t)
                false
            }
        }

        val result = AtomicBoolean(false)
        val done = CountDownLatch(1)
        try {
            controlExecutor.execute {
                try {
                    if (!released.get() && !stopped.get()) {
                        result.set(restartDesktopAudioOnControl(streamSet, offsetNanos))
                    }
                } finally {
                    done.countDown()
                }
            }
        } catch (_: RejectedExecutionException) {
            return false
        }
        return try {
            if (done.await(15, TimeUnit.SECONDS)) result.get() else false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Performs the desktop audio replacement on the serialized libvlc control executor. */
    private fun restartDesktopAudioOnControl(streamSet: ActiveStreams, offsetNanos: Long): Boolean {
        val ap = audioPlayer ?: return false
        val safeUrl = runCatching { MediaHostGuard.resolveSafeUrl(streamSet.currentAudio.url) }.getOrNull()
            ?: return false
        val media = runCatching {
            val options = mutableListOf(*LibVlcMediaOptions.forUrl(safeUrl, live = liveSession.get()), ":no-video")
            LibVlc.createMedia(safeUrl, options.toTypedArray())
        }.getOrNull() ?: return false
        return try {
            runCatching { audioOutput?.reset() }
            runCatching { LibVlc.lib.libvlc_media_player_stop(ap) }
            LibVlc.lib.libvlc_media_player_set_media(ap, media)
            if (offsetNanos > 0L) {
                runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, offsetNanos / 1_000_000L) }
            }
            LibVlc.lib.libvlc_media_player_play(ap)
            if (!isPlaying || parkFlag.get()) {
                runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 1) }
                audioOutput?.setPaused(true)
            } else {
                audioOutput?.setPaused(false)
            }
            true
        } catch (t: Throwable) {
            logger.warn("$debugLabel failed to switch audio rendition.", t)
            false
        } finally {
            runCatching { LibVlc.lib.libvlc_media_release(media) }
            scheduleInitialAvSync()
        }
    }

    fun captureAudioPcm(maxNanos: Long): ByteArray? = null

    fun captureVideoCacheSnapshot(): ByteArray? = null

    // ── Pacing / clock ──────────────────────────────────────────────────────

    fun currentPacingNanos(): Long {
        // Keep this path free of libvlc calls: stop() can wait for a vout callback, and get_time()
        // from either the callback or the render thread can deadlock the native player.
        val current = clock.currentTime()
        val now = System.nanoTime()
        val last = lastSyncDiagNanos.get()
        if (now - last > 10_000_000_000L && lastSyncDiagNanos.compareAndSet(last, now)) {
            if (!LibVlcDiagnostics.noAutoResync) {
                val lead = audioOutput?.leadNanos()
                if (lead != null && lead > AUTO_RESYNC_THRESHOLD_NANOS) {
                    val leadMs = lead / 1_000_000L
                    if (MediaPlayer.DEBUG) {
                        logger.debug(
                            "{} A/V drift: audio buffer {}ms behind video (>{}ms) — flushing audio to re-sync.",
                            debugLabel, leadMs, AUTO_RESYNC_THRESHOLD_NANOS / 1_000_000L
                        )
                    }
                    audioOutput?.forceResync()
                }
            }
        }
        return current
    }

    fun activeBridgeEdgeNanos(): Long? = null

    // ── Control executor ─────────────────────────────────────────────────────

    private fun submit(r: () -> Unit) {
        if (released.get()) return
        try {
            controlExecutor.execute { if (!released.get()) r() }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun daemonThread(r: Runnable, name: String): Thread =
        Thread(r, name).also { it.isDaemon = true }

    // ── Target dimensions ────────────────────────────────────────────────────

    private fun targetDims(streamSet: ActiveStreams?, lastQuality: Int = 0): Pair<Int, Int> =
        MediaStreamSelector.targetDimensions(
            streamSet?.currentVideo,
            lastQuality,
            getTextureSize(),
        )

    /**
     * Triple-buffered video frame pool driven by the low-level libvlc video callbacks
     * (VideoPlayer's TextureRenderCallback model). libvlc writes into one of three direct
     * buffers; `display` marks the newest; the render thread copies it into the FrameSurface
     * and returns the buffer to the pool.
     */
    private inner class TextureRenderCallback {
        private val BUFFER_COUNT = 3
        private val buffers = arrayOfNulls<ByteBuffer>(BUFFER_COUNT)
        private val pointers = arrayOfNulls<Pointer>(BUFFER_COUNT)
        private val inUse = BooleanArray(BUFFER_COUNT)
        private val retiredBuffers = ArrayList<ByteBuffer>()

        private var dropBuffer: ByteBuffer? = null
        private var dropPointer: Pointer? = null
        private var frameWidth = 1
        private var frameHeight = 1
        private var framePitch = 32
        private var frameLines = 32
        private var bufferSize = 4
        @Volatile
        private var active = true
        private var formatReady = false
        private var formatGeneration = 0L
        private var nextWrite = 0
        private var writing = -1
        private var dropStreak = 0L
        private var lastDropLogNanos = 0L

        /** Collapsed counter for frames refused by lock(); a silently dead vout must be visible. */
        private fun noteDroppedFrame() {
            dropStreak++
            val now = System.nanoTime()
            if (dropStreak >= 60L && now - lastDropLogNanos >= 5_000_000_000L) {
                lastDropLogNanos = now
                logger.warn(
                    "$debugLabel dropped {} frames unrendered (formatReady={} size={}x{}).",
                    dropStreak, formatReady, frameWidth, frameHeight,
                )
            }
        }

        @Synchronized
        fun setup(opaque: com.sun.jna.ptr.PointerByReference?, chroma: Pointer?, width: Pointer?, height: Pointer?,
                  pitches: Pointer?, lines: Pointer?): Int {
            if (!active || width == null || height == null || chroma == null || pitches == null || lines == null) return 0
            val sourceW = width.getInt(0)
            val sourceH = height.getInt(0)
            if (sourceW <= 0 || sourceH <= 0 || sourceW > 16384 || sourceH > 16384) {
                logger.warn("$debugLabel rejected libvlc frame dimensions ${sourceW}x$sourceH")
                return 0
            }
            // Ask libvlc to scale to the display's required size. Doing this in the native vout
            // avoids a full-frame nearest-neighbour resample on the Java callback thread.
            val (w, h) = requestedFrameDimensions(
                sourceW, sourceH, expectedW, expectedH, getStretchMode(),
            )
            width.setInt(0, w)
            height.setInt(0, h)
            val pitch = align32(w * 4)
            val lineCount = align32(h)
            logger.info(
                "$debugLabel libvlc video setup: source={}x{} -> output={}x{} pitch={} lines={} (display={}x{})",
                sourceW, sourceH, w, h, pitch, lineCount, expectedW, expectedH,
            )
            // RV32 chroma (VideoPlayer model): single RGBA8888 plane, 32-byte aligned stride.
            chroma.write(0, RV32, 0, RV32.size)
            pitches.setInt(0, pitch)
            lines.setInt(0, lineCount)
            resize(w, h, pitch, lineCount)
            formatReady = true
            return 1
        }

        @Synchronized
        fun deactivate() {
            active = false
        }

        @Synchronized
        fun cleanup(opaque: Pointer?) {
            clear()
        }

        @Synchronized
        fun lock(opaque: Pointer?, planes: Pointer?): Pointer? {
            if (planes == null || !active) {
                ensureDropBuffer()
                if (planes != null) planes.setPointer(0, dropPointer!!)
                return DROP_TOKEN
            }
            // libvlc skips setup() when a reopened input keeps the same size, so formatReady can stay
            // false while the pools are still valid: re-arm instead of dropping every frame forever.
            if (!formatReady && buffers[0] != null && bufferSize > 0) formatReady = true
            if (!formatReady || buffers[0] == null || bufferSize <= 0) {
                noteDroppedFrame()
                ensureDropBuffer()
                planes.setPointer(0, dropPointer!!)
                return DROP_TOKEN
            }
            val index = acquireWriteBuffer()
            if (index < 0) {
                noteDroppedFrame()
                ensureDropBuffer()
                planes.setPointer(0, dropPointer!!)
                return DROP_TOKEN
            }
            inUse[index] = true
            writing = index
            // RV32: single RGBA8888 plane with the pitch supplied by setup().
            planes.setPointer(0, pointers[index]!!)
            return Pointer.createConstant(formatGeneration * BUFFER_COUNT + index + 1L)
        }

        @Synchronized
        fun unlock(opaque: Pointer?, picture: Pointer?, planes: Pointer?) {
        }

        @Synchronized
        fun display(opaque: Pointer?, picture: Pointer?) {
            if (picture == null) return
            val token = Pointer.nativeValue(picture)
            if (token == DROP_TOKEN_VALUE) return
            val encoded = token - 1L
            val generation = encoded / BUFFER_COUNT
            val index = (encoded % BUFFER_COUNT).toInt()
            if (generation != formatGeneration || index < 0 || index >= BUFFER_COUNT) return
            if (writing == index) writing = -1
            dropStreak = 0L
            try {
                if (!active || !formatReady || released.get() || stopped.get() || buffers[index] == null) return
                // Publish the newest frame into the surface for the render thread.
                publishFrame(index)
            } finally {
                // libvlc has finished reading the picture after display returns. Never leave a slot
                // leased: a missing release would fill the three-slot pool and force permanent drops.
                inUse[index] = false
            }
        }

        private fun copyVisibleRows(sourceBuffer: ByteBuffer, target: ByteBuffer) {
            val source = sourceBuffer.duplicate().order(ByteOrder.nativeOrder())
            val rowBytes = frameWidth * 4
            for (row in 0 until frameHeight) {
                val start = row * framePitch
                source.clear().position(start).limit(start + rowBytes)
                target.put(source)
            }
        }

        private fun publishFrame(index: Int) {
            val buf = buffers[index] ?: return
            val ew = expectedW
            val eh = expectedH
            if (ew <= 0 || eh <= 0) return
            // A re-opened input needs its first frame to apply the target position; drop that frame
            // and let the seek verifier release the freeze once the new position is confirmed.
            val repositionMs = pendingVideoRepositionMs.get()
            if (repositionMs >= 0L) {
                pendingVideoRepositionMs.set(-1L)
                submit {
                    if (stopped.get() || released.get()) return@submit
                    val player = mediaPlayer ?: return@submit
                    runCatching { LibVlc.lib.libvlc_media_player_set_time(player, repositionMs) }
                    logger.warn("$debugLabel re-opened video positioned at {}ms.", repositionMs)
                    scheduleSeekLandingVerification(repositionMs)
                }
                if (seekFrozen) return
            }
            // Seek picture-freeze: while a seek is settling, hold the last displayed picture
            // instead of visibly continuing the old timeline ("another player"). Drop frames until
            // the video clock reaches the target, the fail-open deadline expires, or a newer seek /
            // stop / park releases the gate.
            if (seekFrozen) {
                if (System.nanoTime() < seekFrozenDeadlineNanos) {
                    return // hold the last picture until the verifier proves the seek landed
                }
                seekFrozen = false // fail open so a broken seek cannot freeze the display forever
            }
            val parkedNow = parkFlag.get()
            // A session parked before its first upload would stay blank: no frame may be published
            // while parked, so the wall never leaves the placeholder. Always let the first one through.
            if (parkedNow && !parkedSeekFrameNeeded && everPublishedFrame) return
            // DEBUG: count delivered frames for the FPS label (1s sliding window).
            val now = System.nanoTime()
            if (fpsWindowStartNanos == 0L) fpsWindowStartNanos = now
            fpsFrames++
            recordFrameGap()
            val elapsedNs = now - fpsWindowStartNanos
            if (elapsedNs >= 1_000_000_000L) {
                val fps = fpsFrames.toDouble() * 1_000_000_000.0 / elapsedNs
                fpsValue = if (fpsValue <= 0.0) fps else fpsValue * 0.5 + fps * 0.5
                fpsFrames = 0L
                fpsWindowStartNanos = now
            }
            try {
                val packedSourceSize = frameWidth * frameHeight * 4
                val frameSize = ew * eh * 4
                val scratch = rgbScratch?.takeIf { it.capacity() >= packedSourceSize }?.also { it.clear() }
                    ?: ByteBuffer.allocateDirect(packedSourceSize).also { rgbScratch = it }
                copyVisibleRows(buf, scratch)
                scratch.flip()

                val spare = surface.takeOrAllocate(frameSize)
                var presented = false
                try {
                    spare.clear()
                    if (frameWidth == ew && frameHeight == eh) {
                        // libvlc already delivered the requested output size.
                        spare.put(scratch)
                    } else {
                        fitFrame(scratch, frameWidth, frameHeight, spare, ew, eh)
                    }
                    spare.flip()

                    // Keep a coarse last-frame copy: a popout opened after playback ended has no live
                    // frames to seed from, and would otherwise stay black.
                    if (now - snapshotAtNanos >= FRAME_SNAPSHOT_INTERVAL_NANOS) {
                        snapshotAtNanos = now
                        val needed = spare.remaining()
                        synchronized(snapshotLock) {
                            val copy = snapshotBuffer?.takeIf { it.capacity() >= needed }
                                ?: ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
                                    .also { snapshotBuffer = it }
                            copy.clear()
                            copy.put(spare.duplicate())
                            copy.flip()
                            snapshotWidth = ew
                            snapshotHeight = eh
                        }
                    }

                    // Popout / preview sinks (RV32 / BGRA8888 frames). Skippable via diagnostic switch.
                    if (!LibVlcDiagnostics.noFrameSink) {
                        val sink = popoutSink ?: previewSink
                        if (sink != null) sink(spare, ew, eh, FramePixelFormat.BGRA32)
                    }
                    // Keep lighting independent from optional popout/preview diagnostics.
                    ambientLightSink?.invoke(spare, ew, eh, FramePixelFormat.BGRA32)

                    // Publish to the GPU surface for the render thread. Skippable via diagnostic switch.
                    if (!LibVlcDiagnostics.noVideoPublish) {
                        // Stamp from the non-blocking playback clock; libvlc get_time is unsafe here.
                        val framePtsNanos = currentPacingNanos()
                        // present() queues the frame; publish() also returns a spare buffer that this
                        // single-producer path would otherwise drop on every frame.
                        surface.present(spare, framePtsNanos)
                        presented = true
                        everPublishedFrame = true
                        noFrames.set(System.nanoTime())
                        if (seekRequestedNanos != 0L) logSeekFirstFrame()
                        if (parkedNow) {
                            parkedSeekFrameNeeded = false
                            submit {
                                val player = mediaPlayer ?: return@submit
                                runCatching { LibVlc.lib.libvlc_media_player_set_pause(player, 1) }
                            }
                        }
                        // Let the audio join the new picture, unless a re-opened input must reposition first.
                        if (!parkedNow && audioGatedBySeek.get() && pendingVideoRepositionMs.get() < 0L) {
                            releaseAudioForSeek()
                        }
                    }

                    if (!firstFrameFired) {
                        firstFrameFired = true
                        firstFrameLatch.countDown()
                        // A picture is flowing again: hand the recovery ladder a fresh budget.
                        videoReopenAttempts.set(0L)
                        videoRestartAttempts.set(0L)
                        // A parked seek only needs this frame on screen; the clock starts on resume.
                        if (!parkedNow) clock.markFirstFrame()
                        logger.info("$debugLabel first frame delivered {}x{} (libvlc, yuv={}).", ew, eh, gpuYuvActive)
                        submit {
                            val p = mediaPlayer ?: return@submit
                            val decoder = runCatching { LibVlc.videoDecoderName(p) }.getOrNull() ?: "unknown"
                            logger.info(
                                "$debugLabel decoder active: {}; requestedBackend={}.",
                                decoder, LibVlc.currentHwBackend() ?: "disabled",
                            )
                        }
                        val repositionMs = pendingVideoRepositionMs.getAndSet(-1L)
                        if (repositionMs > 0L) {
                            submit {
                                if (stopped.get() || released.get()) return@submit
                                val player = mediaPlayer ?: return@submit
                                runCatching { LibVlc.lib.libvlc_media_player_set_time(player, repositionMs) }
                                logger.warn("$debugLabel re-opened video positioned at {}ms.", repositionMs)
                                scheduleSeekLandingVerification(repositionMs)
                            }
                        }
                    }
                } finally {
                    if (!presented) surface.recycleFrameBuffer(spare)
                }
            } catch (t: Throwable) {
                if (MediaPlayer.DEBUG) logger.warn("$debugLabel frame publish: ${t.message}")
            }
        }

        private fun resize(w: Int, h: Int, pitch: Int, lines: Int) {
            // A new libvlc format gets a fresh generation. Retain the previous direct slices so a
            // delayed native callback cannot write into freed memory or collide with the new pool.
            for (old in buffers) old?.let { retiredBuffers += it }
            dropBuffer?.let { retiredBuffers += it }
            val size = Math.addExact(Math.multiplyExact(pitch, lines), VIDEO_BUFFER_PADDING)
            frameWidth = w
            frameHeight = h
            framePitch = pitch
            frameLines = lines
            bufferSize = size
            formatGeneration = (formatGeneration + 1L).coerceAtLeast(1L)
            for (i in 0 until BUFFER_COUNT) {
                val (buffer, pointer) = alignedDirectBuffer(size)
                buffers[i] = buffer
                pointers[i] = pointer
                inUse[i] = false
            }
            val (drop, pointer) = alignedDirectBuffer(size.coerceAtLeast(4))
            dropBuffer = drop
            dropPointer = pointer
            nextWrite = 0
            writing = -1
        }

        @Synchronized
        private fun clear() {
            // Keep the direct buffers alive! This is called from libvlc's format-cleanup callback on
            // pause/seek. If we nulled buffers[]/bufferSize here, a resume (or a next seek with the
            // SAME dimensions) would NOT re-run setup()/resize() — libvlc skips setup when the size
            // is unchanged — so every subsequent lock() would take the DROP_TOKEN path and every
            // display() would early-return, freezing the video on the last frame while audio plays
            // on. The next setup() creates a fresh format generation and retains the old slices.
            formatReady = false
            nextWrite = 0
            writing = -1
            // libvlc guarantees no display() after cleanup, so leased slots must not stay leased.
            java.util.Arrays.fill(inUse, false)
            dropStreak = 0L
        }

        private fun acquireWriteBuffer(): Int {
            for (i in 0 until BUFFER_COUNT) {
                val index = (nextWrite + i) % BUFFER_COUNT
                if (!inUse[index] && index != writing) {
                    nextWrite = (index + 1) % BUFFER_COUNT
                    return index
                }
            }
            return -1
        }

        private fun alignedDirectBuffer(size: Int): Pair<ByteBuffer, Pointer> {
            val owner = ByteBuffer.allocateDirect(Math.addExact(size, 31)).order(ByteOrder.nativeOrder())
            val raw = Native.getDirectBufferPointer(owner)
            val address = Pointer.nativeValue(raw)
            require(address != 0L) { "libvlc vmem direct buffer has no native address" }
            val aligned = (address + 31L) and -32L
            val offset = (aligned - address).toInt()
            val view = owner.duplicate().order(ByteOrder.nativeOrder())
            view.position(offset).limit(offset + size)
            val slice = view.slice().order(ByteOrder.nativeOrder())
            val pointer = Native.getDirectBufferPointer(slice)
            require(Pointer.nativeValue(pointer) % 32L == 0L) { "libvlc vmem buffer is not 32-byte aligned" }
            return slice to pointer
        }

        private fun ensureDropBuffer() {
            // libvlc can call lock() after a cleanup (pause/seek/resume) has nulled the buffers; it
            // then hands the decoded frame to the pointer we return here. If we hand back a tiny
            // buffer (e.g. 4 bytes) libvlc writes a whole w*h*4 frame into it → massive heap
            // corruption (0xC0000374, surfacing on random threads) — the pause/resume crash that
            // persisted after the audio path was isolated. So the drop buffer must ALWAYS be at
            // least a full padded frame. frameWidth/frameHeight survive clear(), so they still hold
            // the last known dimensions here.
            // A decoder can race format setup with its first lock callback. Use the target texture
            // dimensions when the callback has not reported a real frame size yet; returning a tiny
            // drop buffer would let libvlc memcpy a full decoded frame past the direct allocation.
            val safeWidth = maxOf(frameWidth, expectedW, 1)
            val safeHeight = maxOf(frameHeight, expectedH, 1)
            val pitch = align32(safeWidth * 4)
            val lines = align32(safeHeight)
            val size = (pitch * lines + VIDEO_BUFFER_PADDING).coerceAtLeast(4)
            if (dropBuffer != null && dropPointer != null && dropBuffer!!.capacity() >= size) return
            dropBuffer?.let { retiredBuffers += it }
            val (drop, pointer) = alignedDirectBuffer(size)
            dropBuffer = drop
            dropPointer = pointer
        }
    }

    // ── First-frame latch ───────────────────────────────────────────────────

    private var firstFrameLatch = CountDownLatch(1)
    @Volatile
    private var firstFrameFired = false

    /** True once a parked seek still needs one target frame uploaded before re-pausing the input. */
    @Volatile
    private var parkedSeekFrameNeeded = false

    // ── Frame conversion helpers ────────────────────────────────────────────

    private var rgbScratch: ByteBuffer? = null


    /**
     * Fits a source frame into [dst] per [getStretchMode]. LETTERBOX/CROP map each destination pixel
     * back to a source pixel in one pass, so no intermediate buffer limit is narrowed per row.
     */
    private fun fitFrame(src: ByteBuffer, srcW: Int, srcH: Int, dst: ByteBuffer, dstW: Int, dstH: Int) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return
        if (srcW == dstW && srcH == dstH) {
            dst.clear()
            src.rewind()
            dst.put(src)
            dst.position(dstW * dstH * 4)
            src.rewind()
            return
        }
        val mode = getStretchMode()
        if (mode == StretchMode.STRETCH) {
            resizeRgba(src, srcW, srcH, dst, dstW, dstH)
            dst.position(dstW * dstH * 4)
            return
        }
        val win = fitWindow(mode, srcW, srcH, dstW, dstH)
        val srcRowBytes = srcW * 4
        val dstRowBytes = dstW * 4
        val line = ByteArray(srcRowBytes)
        val out = ByteArray(dstRowBytes)
        var loadedRow = -1
        src.rewind()
        for (dy in 0 until dstH) {
            // Clearing a buffer only resets its position, so every row starts zeroed for the bars.
            out.fill(0)
            val sy = dy - win.offY
            if (sy >= 0 && sy < win.fitH) {
                val srcSy = ((sy.toLong() * srcH) / win.fitH).toInt().coerceIn(0, srcH - 1)
                if (srcSy != loadedRow) {
                    src.limit(srcSy * srcRowBytes + srcRowBytes)
                    src.position(srcSy * srcRowBytes)
                    src.get(line, 0, srcRowBytes)
                    loadedRow = srcSy
                }
                var di = 0
                for (dx in 0 until dstW) {
                    val sx = dx - win.offX
                    if (sx >= 0 && sx < win.fitW) {
                        val srcSx = ((sx.toLong() * srcW) / win.fitW).toInt().coerceIn(0, srcW - 1)
                        val p = srcSx * 4
                        out[di] = line[p]
                        out[di + 1] = line[p + 1]
                        out[di + 2] = line[p + 2]
                        out[di + 3] = line[p + 3]
                    }
                    di += 4
                }
            }
            dst.put(out, 0, dstRowBytes)
        }
        // Leave the position at the full frame size so the caller's flip() exposes every byte.
        dst.position(dstW * dstH * 4)
        src.rewind()
    }


    private fun resizeRgba(src: ByteBuffer, srcW: Int, srcH: Int, dst: ByteBuffer, dstW: Int, dstH: Int) {
        src.rewind()
        val srcRow = srcW * 4
        if (srcW == dstW && srcH == dstH) {
            for (dy in 0 until dstH) {
                src.position(dy * srcRow).limit(dy * srcRow + srcRow)
                dst.put(src)
            }
            src.rewind()
            return
        }
        if (srcW == dstW) {
            // Same row width: bulk-copy whole rows (fast path, common when the texture only
            // differs in height from the decoded frame).
            for (dy in 0 until dstH) {
                val sy = (dy * srcH / dstH).coerceIn(0, srcH - 1)
                src.position(sy * srcRow).limit(sy * srcRow + srcRow)
                dst.put(src)
            }
            return
        }
        for (dy in 0 until dstH) {
            val sy = (dy * srcH / dstH).coerceIn(0, srcH - 1)
            for (dx in 0 until dstW) {
                val sx = (dx * srcW / dstW).coerceIn(0, srcW - 1)
                dst.putInt(src.getInt(sy * srcRow + sx * 4))
            }
        }
        src.rewind()
    }

    companion object {
        /**
         * Android libvlc players and their JNA callbacks cannot be released safely after media has
         * been attached. Keep the owning manager strongly reachable until process exit, including
         * when a display unloads and its MediaPlayer is otherwise eligible for GC.
         */
        private val ANDROID_KEEP_ALIVE = java.util.Collections.synchronizedList(
            mutableListOf<LibVlcSessionManager>(),
        )

        private const val LIBVLC_MEDIA_PLAYER_LENGTH_CHANGED = 0x111
        private const val REPLAY_FPS = 30.0

        /**
         * Nominal frame duration handed to [FrameSurface.enableDisplaySync]; the surface halves it (and caps the
         * result at 20 ms), so this is the widest tolerance the queue can use when picking a due frame.
         */
        private const val DISPLAY_SYNC_FRAME_NS = 40_000_000L

        /** Defensive tail (bytes) added beyond w*h*4 when allocating the RV32 video pool so a marginally
         * oversized libvlc write (alignment drift / transient seek frame) lands in slack instead of
         * corrupting adjacent heap memory. The reader only copies the exact w*h*4 span. */
        private const val VIDEO_BUFFER_PADDING = 4096
        private fun align32(value: Int): Int = (value + 31) and -32
        private val RV32 = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), '3'.code.toByte(), '2'.code.toByte())
        private val DROP_TOKEN = Pointer.createConstant(0x7FFFFFFFL)
        private val DROP_TOKEN_VALUE = 0x7FFFFFFFL
        private fun outputFps(sourceFps: Double?): Double =
            sourceFps?.takeIf { it.isFinite() && it > 1.0 && it <= 240.0 } ?: REPLAY_FPS
    }
}

/** Scaled window of a source frame inside a display texture; see [fitWindow]. */
internal data class FitWindow(val fitW: Int, val fitH: Int, val offX: Int, val offY: Int)

/**
 * Placement of a [srcW]x[srcH] source inside a [dstW]x[dstH] texture for [mode]: CROP overflows the
 * texture (negative offsets) and the caller crops that overflow away, LETTERBOX leaves bars.
 */
internal fun fitWindow(mode: StretchMode, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FitWindow {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return FitWindow(dstW, dstH, 0, 0)
    if (mode == StretchMode.STRETCH) return FitWindow(dstW, dstH, 0, 0)
    val (fitW, fitH) = requestedFrameDimensions(srcW, srcH, dstW, dstH, mode)
    return FitWindow(fitW, fitH, (dstW - fitW) / 2, (dstH - fitH) / 2)
}

/**
 * Returns the size libvlc should deliver for a source frame, given the display target and stretch mode.
 * The native vout performs this scale in C; the Java callback then only copies rows / applies bars.
 */
internal fun requestedFrameDimensions(
    srcW: Int,
    srcH: Int,
    dstW: Int,
    dstH: Int,
    mode: StretchMode,
): Pair<Int, Int> {
    if (srcW <= 0 || srcH <= 0) return 1 to 1
    if (dstW <= 0 || dstH <= 0) return srcW to srcH
    val maxDimension = 16384
    return when (mode) {
        StretchMode.STRETCH -> dstW.coerceIn(1, maxDimension) to dstH.coerceIn(1, maxDimension)
        StretchMode.LETTERBOX -> {
            val scale = minOf(dstW.toDouble() / srcW, dstH.toDouble() / srcH)
            (srcW * scale).toInt().coerceIn(1, maxDimension) to
                (srcH * scale).toInt().coerceIn(1, maxDimension)
        }
        StretchMode.CROP -> {
            val scale = maxOf(dstW.toDouble() / srcW, dstH.toDouble() / srcH)
            (srcW * scale).toInt().coerceIn(1, maxDimension) to
                (srcH * scale).toInt().coerceIn(1, maxDimension)
        }
    }
}

/** Verdict of a seek-landing clock-sample pair. */
internal enum class SeekLanding { LANDED, DROPPED, FLUSHING }

/**
 * Classifies two video-clock samples (ms) taken roughly [SEEK_VERIFY_SAMPLE_GAP_NANOS] apart
 * against a seek [targetMs].
 *
 * - [SeekLanding.LANDED]: either sample is within [toleranceMs] of the target — the seek reached
 *   its destination and playback can resume normally.
 * - [SeekLanding.DROPPED]: the clock advanced at least [advanceMs] while still far from the target
 *   — the pre-seek timeline is STILL PLAYING (set_time was silently ignored), so re-applying
 *   set_time is warranted.
 * - [SeekLanding.FLUSHING]: the clock barely moved while far from the target — the demuxer is
 *   legitimately flushing toward the target; re-applying set_time would restart that flush and
 *   should be avoided.
 *
 * Negative samples (failed libvlc reads) are treated as [SeekLanding.FLUSHING] so they never
 * trigger a spurious re-assertion; a restart of the flush is the most expensive error mode.
 */
internal fun classifySeekLanding(
    firstMs: Long,
    secondMs: Long,
    targetMs: Long,
    toleranceMs: Long,
    advanceMs: Long,
): SeekLanding {
    if (firstMs < 0L || secondMs < 0L || targetMs < 0L) return SeekLanding.FLUSHING
    if (kotlin.math.abs(firstMs - targetMs) <= toleranceMs ||
        kotlin.math.abs(secondMs - targetMs) <= toleranceMs
    ) {
        return SeekLanding.LANDED
    }
    return if (secondMs - firstMs >= advanceMs) SeekLanding.DROPPED else SeekLanding.FLUSHING
}
