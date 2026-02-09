package ai.rever.boss.plugin.dynamic.bookmarks.search

import ai.rever.boss.plugin.api.PluginSearchResult
import ai.rever.boss.plugin.api.SearchMatchRange
import ai.rever.boss.plugin.api.SearchProvider
import ai.rever.boss.plugin.api.SearchResultAction
import ai.rever.boss.plugin.api.SearchResultIcon
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkManager

/**
 * Search provider that enables bookmarks to appear in GlobalSearchService results.
 *
 * This provider searches through all bookmark collections and returns matching
 * results that can be opened by the global search UI.
 */
internal class BookmarkSearchProvider(
    private val bookmarkManager: BookmarkManager
) : SearchProvider {

    override val providerId = "bookmarks"
    override val displayName = "Bookmarks"

    override suspend fun search(query: String, limit: Int): List<PluginSearchResult> {
        if (query.isBlank()) {
            return emptyList()
        }

        val results = mutableListOf<PluginSearchResult>()
        val lowerQuery = query.lowercase()

        bookmarkManager.collections.value.forEach { collection ->
            collection.bookmarks.forEach { bookmark ->
                val title = bookmark.tabConfig.title
                val titleLower = title.lowercase()
                val url = bookmark.tabConfig.url
                val urlLower = url?.lowercase()
                val notes = bookmark.notes
                val notesLower = notes.lowercase()

                // Check for matches in title, URL, or notes
                val titleMatchIndex = titleLower.indexOf(lowerQuery)
                val urlMatchIndex = urlLower?.indexOf(lowerQuery) ?: -1
                val notesMatchIndex = notesLower.indexOf(lowerQuery)

                val hasMatch = titleMatchIndex >= 0 || urlMatchIndex >= 0 || notesMatchIndex >= 0

                if (hasMatch) {
                    // Calculate score based on match quality
                    val score = calculateScore(
                        titleMatchIndex = titleMatchIndex,
                        urlMatchIndex = urlMatchIndex,
                        notesMatchIndex = notesMatchIndex,
                        queryLength = lowerQuery.length,
                        titleLength = title.length
                    )

                    // Build match ranges for highlighting
                    val matchRanges = if (titleMatchIndex >= 0) {
                        listOf(SearchMatchRange(titleMatchIndex, titleMatchIndex + lowerQuery.length))
                    } else {
                        emptyList()
                    }

                    // Determine icon based on tab type
                    val icon = when {
                        bookmark.tabConfig.faviconCacheKey != null ->
                            SearchResultIcon.FaviconCache(bookmark.tabConfig.faviconCacheKey!!)
                        bookmark.tabConfig.type == "browser" ->
                            SearchResultIcon.MaterialIcon("Language")
                        bookmark.tabConfig.type == "editor" ->
                            SearchResultIcon.MaterialIcon("Code")
                        bookmark.tabConfig.type == "terminal" ->
                            SearchResultIcon.MaterialIcon("Terminal")
                        else ->
                            SearchResultIcon.MaterialIcon("Bookmark")
                    }

                    // Determine action based on tab type
                    val action = when (bookmark.tabConfig.type) {
                        "browser" -> SearchResultAction.OpenUrl(url ?: "about:blank")
                        "editor" -> {
                            val filePath = bookmark.tabConfig.filePath
                            if (filePath != null) {
                                SearchResultAction.OpenFile(filePath)
                            } else {
                                SearchResultAction.Custom("open-bookmark", mapOf(
                                    "bookmarkId" to bookmark.id,
                                    "collectionId" to collection.id
                                ))
                            }
                        }
                        else -> SearchResultAction.Custom("open-bookmark", mapOf(
                            "bookmarkId" to bookmark.id,
                            "collectionId" to collection.id
                        ))
                    }

                    results.add(
                        PluginSearchResult(
                            id = bookmark.id,
                            title = title,
                            subtitle = url ?: bookmark.tabConfig.filePath ?: collection.name,
                            icon = icon,
                            category = "Bookmarks",
                            providerId = providerId,
                            action = action,
                            score = score,
                            matchRanges = matchRanges,
                            metadata = mapOf(
                                "collectionId" to collection.id,
                                "collectionName" to collection.name,
                                "tabType" to bookmark.tabConfig.type
                            )
                        )
                    )
                }
            }
        }

        // Sort by score and limit
        return results
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * Calculate a relevance score for a match.
     *
     * Higher scores indicate better matches:
     * - Title matches are prioritized
     * - Matches at the start of the string score higher
     * - Shorter titles with matches score higher (more of the title matches)
     */
    private fun calculateScore(
        titleMatchIndex: Int,
        urlMatchIndex: Int,
        notesMatchIndex: Int,
        queryLength: Int,
        titleLength: Int
    ): Int {
        var score = 0

        // Title match is worth the most
        if (titleMatchIndex >= 0) {
            score += 100

            // Bonus for match at start
            if (titleMatchIndex == 0) {
                score += 50
            }

            // Bonus for matching a larger portion of the title
            val matchRatio = queryLength.toFloat() / titleLength
            score += (matchRatio * 30).toInt()
        }

        // URL match is worth less than title
        if (urlMatchIndex >= 0) {
            score += 50
            if (urlMatchIndex == 0) {
                score += 20
            }
        }

        // Notes match is worth the least
        if (notesMatchIndex >= 0) {
            score += 20
        }

        return score
    }
}
