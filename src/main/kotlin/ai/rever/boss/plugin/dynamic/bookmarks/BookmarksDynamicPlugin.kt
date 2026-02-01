package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider

/**
 * Dynamic plugin for Bookmarks panel.
 *
 * This plugin displays and manages browser bookmarks in a sidebar panel.
 * Uses BookmarkDataProvider, WorkspaceDataProvider, and SplitViewOperations
 * from PluginContext for full functionality.
 */
class BookmarksDynamicPlugin : DynamicPlugin {
    override val pluginId = "ai.rever.boss.plugin.dynamic.bookmarks"
    override val displayName = "Bookmarks (Dynamic)"
    override val version = "1.0.2"
    override val description = "Displays and manages browser bookmarks in a sidebar panel"
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-bookmarks"

    private var bookmarkDataProvider: BookmarkDataProvider? = null
    private var workspaceDataProvider: WorkspaceDataProvider? = null
    private var splitViewOperations: SplitViewOperations? = null

    override fun register(context: PluginContext) {
        bookmarkDataProvider = context.bookmarkDataProvider
        workspaceDataProvider = context.workspaceDataProvider
        splitViewOperations = context.splitViewOperations

        context.panelRegistry.registerPanel(BookmarksInfo) { ctx, panelInfo ->
            BookmarksComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                bookmarkDataProvider = bookmarkDataProvider,
                workspaceDataProvider = workspaceDataProvider,
                splitViewOperations = splitViewOperations
            )
        }
    }
}
