package com.dreamdisplayx.media.player.cdn

import com.dreamdisplayx.api.media.stream.model.MediaStream
import com.dreamdisplayx.api.media.stream.model.MediaStreamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for CDN candidate ordering and the early-failure penalty. No network or libvlc
 * involved.
 */
class CdnSpeedProbeTest {

    private fun audio(url: String, backups: List<String> = emptyList()): MediaStream = MediaStream(
        url = url,
        backupUrls = backups,
        type = MediaStreamType.AUDIO,
        codec = "mp4a.40.2",
        width = null,
        height = null,
        fps = null,
        bitrate = 185_000,
        audioTrackName = "Dolby",
        audioTrackLang = null,
        isDefault = true,
    )

    @Test
    fun `api upos backup is promoted over signed mcdn primary`() {
        val mcdn = "https://xy171x43x247x44xy.mcdn.bilivideo.cn:8082/v1/resource/upgcxcode/44/15/1-30080.m4s?os=mcdn"
        val mountain = "https://h2i438c.edge.mountaintoys.cn:4483/upgcxcode/44/15/1-30080.m4s"
        val upos = "https://upos-sz-estgoss.bilivideo.com/upgcxcode/44/15/1-30080.m4s"

        val (_, probed) = CdnSpeedProbe.reorderForPlayback(null, audio(mcdn, listOf(mountain, upos)))

        assertEquals(upos, probed?.url)
        assertTrue(probed?.backupUrls?.contains(mcdn) == true)
    }

    @Test
    fun `base url mode keeps the signed primary untouched`() {
        val mcdn = "https://xy171x43x247x44xy.mcdn.bilivideo.cn:8082/v1/resource/upgcxcode/44/15/1-30080.m4s?os=mcdn"
        val upos = "https://upos-sz-estgoss.bilivideo.com/upgcxcode/44/15/1-30080.m4s"
        val stream = audio(mcdn, listOf(upos))

        val (_, probed) = CdnSpeedProbe.reorderForPlayback(null, stream, preferredMirrorHost = "BASE_URL")

        assertEquals(stream, probed)
    }

    @Test
    fun `explicit mirror rewrites upos but leaves mcdn untouched`() {
        val mcdn = "https://xy171x43x247x44xy.mcdn.bilivideo.cn:8082/v1/resource/upgcxcode/44/15/1-30080.m4s?os=mcdn"
        val upos = "https://upos-sz-estgoss.bilivideo.com/upgcxcode/44/15/1-30080.m4s"

        val (_, probed) = CdnSpeedProbe.reorderForPlayback(null, audio(mcdn, listOf(upos)), "ali")

        assertEquals("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/44/15/1-30080.m4s", probed?.url)
        assertTrue(probed?.backupUrls?.contains(mcdn) == true)
    }

    @Test
    fun `scrub preview prefers an api upos backup over signed mcdn`() {
        val mcdn = "https://xy171x43x247x44xy.mcdn.bilivideo.cn:8082/v1/resource/upgcxcode/44/15/1-30080.m4s?os=mcdn"
        val upos = "https://upos-sz-estgoss.bilivideo.com/upgcxcode/44/15/1-30080.m4s"
        val playing = "https://upos-sz-mirrorcoso1.bilivideo.com/upgcxcode/44/15/1-30080.m4s"

        val chosen = CdnSpeedProbe.alignSecondaryRendition(mcdn, listOf(upos), playing)

        assertEquals(
            "https://upos-sz-mirrorcoso1.bilivideo.com/upgcxcode/44/15/1-30080.m4s",
            chosen,
        )
    }

    @Test
    fun `penalized host is not usable until cooldown elapses`() {
        val url = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1.m4s"
        assertTrue(CdnSpeedProbe.isHostUsable(url), "host should start usable")

        CdnSpeedProbe.penalizeHost(url)
        assertFalse(CdnSpeedProbe.isHostUsable(url), "penalized host must be excluded")
    }

    @Test
    fun `non-bilibili or unknown hosts are never penalized`() {
        CdnSpeedProbe.penalizeHost(null)
        CdnSpeedProbe.penalizeHost("not a url")
        assertTrue(CdnSpeedProbe.isHostUsable(null))
        assertTrue(CdnSpeedProbe.isHostUsable("not a url"))
        assertTrue(CdnSpeedProbe.isHostUsable("https://example.com/video.mp4"))
    }

    @Test
    fun `penalizeHost is idempotent`() {
        val url = "https://upos-tf-all-hw.bilivideo.com/upgcxcode/2.m4s"
        CdnSpeedProbe.penalizeHost(url)
        CdnSpeedProbe.penalizeHost(url)
        assertFalse(CdnSpeedProbe.isHostUsable(url))
    }
}
