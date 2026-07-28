package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkDataProviderImpl
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkManager
import ai.rever.boss.plugin.dynamic.bookmarks.search.BookmarkSearchProvider
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Dynamic plugin for Bookmarks panel.
 *
 * This plugin is self-contained with its own BookmarkManager for managing
 * bookmarks and persistence. It exposes BookmarkDataProvider via registerPluginAPI()
 * so BossConsole UI can access bookmark functionality through the plugin system.
 *
 * Features:
 * - Self-contained bookmark management (BookmarkManager)
 * - Persistence to ~/Documents/BOSS/bookmarks/
 * - Search provider for GlobalSearchService integration
 * - BookmarkDataProvider API for BossConsole UI (context menus, dialogs)
 * - UI panel for viewing and managing bookmarks
 *
 * When this plugin is uninstalled, getPluginAPI(BookmarkDataProvider::class.java)
 * returns null and bookmark features in BossConsole UI gracefully degrade.
 */
class BookmarksDynamicPlugin : DynamicPlugin {
    override val pluginId = "ai.rever.boss.plugin.dynamic.bookmarks"
    override val displayName = "Bookmarks (Dynamic)"
    override val version = "2.0.0"
    override val description = "Self-contained bookmark management with global search integration"
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-bookmarks"

    // Internal bookmark manager - self-contained within the plugin
    private var bookmarkManager: BookmarkManager? = null

    // Search provider for GlobalSearchService
    private var searchProvider: BookmarkSearchProvider? = null

    // BookmarkDataProvider exposed to BossConsole UI via plugin API
    private var bookmarkDataProvider: BookmarkDataProviderImpl? = null

    // Providers from PluginContext (still needed for workspace and tab operations)
    private var workspaceDataProvider: WorkspaceDataProvider? = null
    private var splitViewOperations: SplitViewOperations? = null
    private var contextMenuProvider: ContextMenuProvider? = null
    private var activeTabsProvider: ActiveTabsProvider? = null

    override fun register(context: PluginContext) {
        // Create internal bookmark manager
        bookmarkManager = BookmarkManager()

        // Create and register search provider for GlobalSearchService
        searchProvider = BookmarkSearchProvider(bookmarkManager!!)
        context.registerSearchProvider(searchProvider!!)

        // Contribute bookmarks_list/add/remove MCP tools; auto-removed on disable/unload.
        context.registerMcpToolProvider(BookmarksMcpToolProvider(pluginId, bookmarkManager!!))

        // Create and register BookmarkDataProvider for BossConsole UI
        // This allows context menus, bookmark dialogs, etc. to work
        bookmarkDataProvider = BookmarkDataProviderImpl(bookmarkManager!!)
        context.registerPluginAPI(bookmarkDataProvider!!)

        // Get providers from context (for workspace and tab operations)
        workspaceDataProvider = context.workspaceDataProvider
        splitViewOperations = context.splitViewOperations
        contextMenuProvider = context.contextMenuProvider
        activeTabsProvider = context.activeTabsProvider

        // Register panel UI
        context.panelRegistry.registerPanel(BookmarksInfo) { ctx, panelInfo ->
            BookmarksComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                bookmarkManager = bookmarkManager!!,
                workspaceDataProvider = workspaceDataProvider,
                splitViewOperations = splitViewOperations,
                contextMenuProvider = contextMenuProvider,
                activeTabsProvider = activeTabsProvider
            )
        }
    }

    private companion object {
        /** Shutdown must not hang the host if a save is wedged. */
        const val SAVE_DRAIN_TIMEOUT_MS = 3_000L
    }

    override fun dispose() {
        // Drain and stop the manager's save workers before dropping it. They run
        // on shared Dispatchers.Default threads, so merely nulling the reference
        // leaves them holding this plugin's classloader alive — and a reload
        // would then add a second pair of writers competing over the same file
        // from a stale snapshot. Closing also flushes a save that conflation
        // has left pending.
        bookmarkManager?.let { manager ->
            runCatching {
                runBlocking { withTimeout(SAVE_DRAIN_TIMEOUT_MS) { manager.close() } }
            }.onFailure { error ->
                BossLogger.forComponent("BookmarksDynamicPlugin").warn(
                    LogCategory.FILE,
                    "Bookmark save workers did not drain cleanly",
                    mapOf("reason" to (error.message ?: error::class.simpleName.orEmpty()))
                )
            }
        }

        // Note: searchProvider and bookmarkDataProvider will be unregistered by the host when the plugin is unloaded
        searchProvider = null
        bookmarkDataProvider = null
        bookmarkManager = null
        workspaceDataProvider = null
        splitViewOperations = null
        contextMenuProvider = null
        activeTabsProvider = null
    }
}
