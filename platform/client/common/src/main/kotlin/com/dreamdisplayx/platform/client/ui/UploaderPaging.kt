package com.dreamdisplayx.platform.client.ui

/** Page count for a fixed-size uploader grid; an empty result still occupies one page. */
internal fun uploaderPageCount(total: Int, pageSize: Int): Int {
    if (pageSize <= 0) return 1
    return if (total <= 0) 1 else (total + pageSize - 1) / pageSize
}
