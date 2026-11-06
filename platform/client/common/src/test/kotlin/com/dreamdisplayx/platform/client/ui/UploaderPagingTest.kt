package com.dreamdisplayx.platform.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class UploaderPagingTest {

    @Test
    fun `empty and exact page totals stay on one page`() {
        assertEquals(1, uploaderPageCount(0, 30))
        assertEquals(1, uploaderPageCount(30, 30))
    }

    @Test
    fun `partial last page rounds up`() {
        assertEquals(2, uploaderPageCount(31, 30))
        assertEquals(8, uploaderPageCount(213, 30))
    }
}
