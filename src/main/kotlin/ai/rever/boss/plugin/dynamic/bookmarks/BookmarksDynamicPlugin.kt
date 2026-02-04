package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider

/**
 * Dynamic plugin for Bookmarks panel.
 *
 * This plugin displays and manages browser bookmarks in a sidebar panel.
 * Uses BookmarkDataProvider, WorkspaceDataProvider, SplitViewOperations,
 * ContextMenuProvider, and ActiveTabsProvider from PluginContext for full functionality.
 */
class BookmarksDynamicPlugin : DynamicPlugin {
    override val pluginId = "ai.rever.boss.plugin.dynamic.bookmarks"
    override val displayName = "Bookmarks (Dynamic)"
    override val version = "1.0.5"
    override val description = "Displays and manages browser bookmarks in a sidebar panel"
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-bookmarks"

    private var bookmarkDataProvider: BookmarkDataProvider? = null
    private var workspaceDataProvider: WorkspaceDataProvider? = null
    private var splitViewOperations: SplitViewOperations? = null
    private var contextMenuProvider: ContextMenuProvider? = null
    private var activeTabsProvider: ActiveTabsProvider? = null

    override fun register(context: PluginContext) {
        bookmarkDataProvider = context.bookmarkDataProvider
        workspaceDataProvider = context.workspaceDataProvider
        splitViewOperations = context.splitViewOperations
        contextMenuProvider = context.contextMenuProvider
        activeTabsProvider = context.activeTabsProvider

        context.panelRegistry.registerPanel(BookmarksInfo) { ctx, panelInfo ->
            BookmarksComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                bookmarkDataProvider = bookmarkDataProvider,
                workspaceDataProvider = workspaceDataProvider,
                splitViewOperations = splitViewOperations,
                contextMenuProvider = contextMenuProvider,
                activeTabsProvider = activeTabsProvider
            )
        }
    }
}
