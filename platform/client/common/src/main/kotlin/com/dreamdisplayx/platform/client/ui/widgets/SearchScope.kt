package com.dreamdisplayx.platform.client.ui.widgets

/** Search target selected by the suggestions panel toggle. */
enum class SearchScope { VIDEO, UPLOADER }

/**
 * Parsed search input. [keyword] keeps its internal spaces; only the outer whitespace is trimmed.
 * A leading or embedded `@` selects uploader search, with the text before it used as a video keyword.
 */
data class ParsedSearchQuery(
    val scope: SearchScope,
    val keyword: String,
    val uploaderName: String?,
)

object SearchQueryParser {
    fun parse(raw: String, currentScope: SearchScope): ParsedSearchQuery {
        val query = raw.trim()
        val at = query.indexOf('@')
        if (at >= 0) {
            return ParsedSearchQuery(
                scope = SearchScope.UPLOADER,
                keyword = query.substring(0, at).trim(),
                uploaderName = query.substring(at + 1).trim().takeIf { it.isNotEmpty() },
            )
        }
        return ParsedSearchQuery(currentScope, query, null)
    }

    /** Only text before an explicit '@' is a video filter; a plain uploader query is just the name. */
    fun uploaderVideoKeyword(parsed: ParsedSearchQuery): String =
        if (parsed.uploaderName != null) parsed.keyword else ""
}
