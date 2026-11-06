package com.dreamdisplayx.util.natives

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativesCacheStampTest {

    @Test
    fun tagOfReadsTheLastPathSegmentOfAReleaseBase() {
        val base = "https://github.com/Aruvelut-123/dreamdisplaysx/releases/download/natives-7f303c9-1787719531"
        assertEquals("natives-7f303c9-1787719531", NativesCacheStamp.tagOf(base))
    }

    @Test
    fun tagOfToleratesATrailingSlash() {
        val base = "https://github.com/o/r/releases/download/natives-abc-123/"
        assertEquals("natives-abc-123", NativesCacheStamp.tagOf(base))
    }

    @Test
    fun tagOfToleratesSurroundingWhitespace() {
        // A stamp read back from disk may carry padding.
        assertEquals(
            "natives-abc-123",
            NativesCacheStamp.tagOf("  https://github.com/o/r/releases/download/natives-abc-123\n"),
        )
    }

    @Test
    fun tagOfRejectsBlankInput() {
        assertNull(NativesCacheStamp.tagOf(""))
        assertNull(NativesCacheStamp.tagOf("   "))
        assertNull(NativesCacheStamp.tagOf("/"))
    }

    @Test
    fun tagOfRejectsBareReleasePages() {
        // Accepting these would make every cache look current.
        assertNull(NativesCacheStamp.tagOf("https://github.com/o/r/releases"))
        assertNull(NativesCacheStamp.tagOf("https://github.com/o/r/releases/download"))
        assertNull(NativesCacheStamp.tagOf("https://github.com/o/r/releases/latest"))
    }

    @Test
    fun isFreshMatchesWhenTheStampedTagIsTheExpectedOne() {
        assertTrue(NativesCacheStamp.isFresh("natives-abc-123", "natives-abc-123"))
    }

    @Test
    fun isFreshRejectsADifferentTag() {
        assertFalse(NativesCacheStamp.isFresh("natives-old-111", "natives-new-222"))
    }

    @Test
    fun isFreshRejectsAMissingStamp() {
        assertFalse(NativesCacheStamp.isFresh(null, "natives-new-222"))
    }

    @Test
    fun isFreshTrustsTheCacheWhenTheManifestHasNoUsableTag() {
        assertTrue(NativesCacheStamp.isFresh(null, null))
        assertTrue(NativesCacheStamp.isFresh("natives-abc-123", null))
    }

    @Test
    fun aLegacyCacheIsOnlyReusedWhenTheManifestCarriesNoTag() {
        assertFalse(NativesCacheStamp.legacyCacheIsFresh("natives-new-222"))
        assertTrue(NativesCacheStamp.legacyCacheIsFresh(null))
    }

    @Test
    fun theStampFileNameIsStable() {
        // Renaming this file would invalidate every existing cache on the next launch.
        assertEquals("natives-stamp.txt", NativesCacheStamp.FILE_NAME)
    }
}
