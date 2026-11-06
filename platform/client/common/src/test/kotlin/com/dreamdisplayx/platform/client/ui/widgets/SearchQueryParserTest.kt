package com.dreamdisplayx.platform.client.ui.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchQueryParserTest {

    @Test
    fun `at prefix selects uploader and keeps keyword spaces`() {
        val parsed = SearchQueryParser.parse("  原神 攻略 @老番茄  ", SearchScope.VIDEO)

        assertEquals(SearchScope.UPLOADER, parsed.scope)
        assertEquals("原神 攻略", parsed.keyword)
        assertEquals("老番茄", parsed.uploaderName)
    }

    @Test
    fun `plain query keeps internal spaces`() {
        val parsed = SearchQueryParser.parse("  原神 攻略  ", SearchScope.VIDEO)

        assertEquals(SearchScope.VIDEO, parsed.scope)
        assertEquals("原神 攻略", parsed.keyword)
        assertNull(parsed.uploaderName)
    }

    @Test
    fun `uploader mode searches plain text as uploader name`() {
        val parsed = SearchQueryParser.parse("老番茄", SearchScope.UPLOADER)

        assertEquals(SearchScope.UPLOADER, parsed.scope)
        assertEquals("老番茄", parsed.keyword)
        assertNull(parsed.uploaderName)
    }

    @Test
    fun `at syntax keeps repeated spaces in the keyword`() {
        val parsed = SearchQueryParser.parse("  原神   攻略  @  老番茄  ", SearchScope.VIDEO)

        assertEquals(SearchScope.UPLOADER, parsed.scope)
        assertEquals("原神   攻略", parsed.keyword)
        assertEquals("老番茄", parsed.uploaderName)
    }

    @Test
    fun `plain query with repeated internal spaces stays intact`() {
        val parsed = SearchQueryParser.parse("  原神   攻略  ", SearchScope.VIDEO)

        assertEquals(SearchScope.VIDEO, parsed.scope)
        assertEquals("原神   攻略", parsed.keyword)
        assertNull(parsed.uploaderName)
    }

    @Test
    fun `plain uploader search does not become a video keyword`() {
        val parsed = SearchQueryParser.parse("老番茄", SearchScope.UPLOADER)

        assertEquals("", SearchQueryParser.uploaderVideoKeyword(parsed))
    }

    @Test
    fun `keyword before at is carried into the uploader page`() {
        val parsed = SearchQueryParser.parse("原神 攻略 @老番茄", SearchScope.VIDEO)

        assertEquals("原神 攻略", SearchQueryParser.uploaderVideoKeyword(parsed))
    }
}
