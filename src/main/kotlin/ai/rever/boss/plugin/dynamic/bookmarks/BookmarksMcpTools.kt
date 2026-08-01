package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkManager
import ai.rever.boss.plugin.workspace.TabConfig

/**
 * MCP tools contributed by the Bookmarks plugin: list, add, and remove bookmarks
 * via the plugin's internal [BookmarkManager]. Registered in
 * [BookmarksDynamicPlugin.register]; removed automatically on disable/unload.
 */
internal class BookmarksMcpToolProvider(
    override val providerId: String,
    private val manager: BookmarkManager,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "bookmarks_list",
            description = "List saved bookmarks grouped by collection (collectionId/bookmarkId, title, url).",
            handler = McpToolHandler {
                val collections = manager.collections.value
                val lines = collections.flatMap { col ->
                    col.bookmarks.map { bm ->
                        val target = bm.tabConfig.url ?: bm.tabConfig.filePath ?: ""
                        "${col.id}/${bm.id}  [${col.name}]  ${bm.tabConfig.title}  $target"
                    }
                }
                if (lines.isEmpty()) McpToolResult("No bookmarks.")
                else McpToolResult(lines.joinToString("\n"))
            },
        ),
        McpToolDefinition(
            name = "bookmark_add",
            description = "Add a URL bookmark to a collection (default \"Favorites\").",
            inputSchema = ADD_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val title = args.string("title")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: title", isError = true)
                val url = args.string("url")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: url", isError = true)
                val collection = args.string("collection") ?: "Favorites"
                val bookmark = Bookmark(
                    // Not the default id: Bookmark.generateId() is
                    // "bookmark-<epochMillis>", so two adds in the same
                    // millisecond would alias each other.
                    id = manager.newBookmarkId(),
                    tabConfig = TabConfig(type = "browser", title = title, url = url),
                    workspaceName = "",
                )
                manager.addBookmark(collection, bookmark)
                McpToolResult("Added bookmark \"$title\" to $collection.")
            },
        ),
        McpToolDefinition(
            name = "bookmark_remove",
            description = "Remove a bookmark by collection id and bookmark id (from bookmarks_list).",
            inputSchema = REMOVE_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val collectionId = args.string("collection_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: collection_id", isError = true)
                val bookmarkId = args.string("bookmark_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: bookmark_id", isError = true)
                manager.removeBookmark(collectionId, bookmarkId)
                McpToolResult("Removed bookmark $bookmarkId.")
            },
        ),
    )

    private companion object {
        const val ADD_SCHEMA =
            """{"type":"object","properties":{"title":{"type":"string","description":"Bookmark title."},"url":{"type":"string","description":"URL to bookmark."},"collection":{"type":"string","description":"Collection name (default Favorites)."}},"required":["title","url"]}"""
        const val REMOVE_SCHEMA =
            """{"type":"object","properties":{"collection_id":{"type":"string","description":"Collection id."},"bookmark_id":{"type":"string","description":"Bookmark id."}},"required":["collection_id","bookmark_id"]}"""
    }
}
