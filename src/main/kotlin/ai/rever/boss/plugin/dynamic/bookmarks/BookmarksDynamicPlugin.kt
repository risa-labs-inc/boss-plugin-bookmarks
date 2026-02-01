package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Dynamic plugin for Bookmarks panel.
 *
 * This plugin displays and manages browser bookmarks in a sidebar panel.
 * It requires the BookmarkDataProvider and WorkspaceDataProvider services from the host application.
 *
 * NOTE: Full implementation is deferred to Phase 2. This plugin requires window-scoped services
 * (BookmarkDataProvider, WorkspaceDataProvider) which are currently provided via CompositionLocals
 * in BossApp.kt. The PluginContext needs to be extended to support window-scoped service injection
 * before this plugin can be fully functional.
 *
 * @see ai.rever.boss.plugin.api.BookmarkDataProvider
 * @see ai.rever.boss.plugin.api.WorkspaceDataProvider
 */
class BookmarksDynamicPlugin : DynamicPlugin {
    override val pluginId = "ai.rever.boss.plugin.dynamic.bookmarks"
    override val displayName = "Bookmarks (Dynamic)"
    override val version = "1.0.0"
    override val description = "Displays and manages browser bookmarks in a sidebar panel"
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-bookmarks"

    override fun register(context: PluginContext) {
        val bookmarkProvider = context.bookmarkDataProvider
        val workspaceProvider = context.workspaceDataProvider
        val splitViewOps = context.splitViewOperations

        if (bookmarkProvider == null || workspaceProvider == null || splitViewOps == null) {
            throw IllegalStateException(
                "Bookmarks plugin requires BookmarkDataProvider, WorkspaceDataProvider, and SplitViewOperations. " +
                "Ensure the host provides these services in PluginContext. " +
                "NOTE: This plugin is not yet fully implemented - window-scoped service injection is pending."
            )
        }

        // TODO: Phase 2 - Register panel once window-scoped services are properly injected
        // context.panelRegistry.registerPanel(BookmarksInfo) { ctx, panelInfo ->
        //     BookmarksComponent(
        //         ctx = ctx,
        //         panelInfo = panelInfo,
        //         bookmarkDataProvider = bookmarkProvider,
        //         workspaceDataProvider = workspaceProvider,
        //         splitViewOperations = splitViewOps
        //     )
        // }

        throw IllegalStateException(
            "Bookmarks dynamic plugin is not yet fully implemented. " +
            "Phase 2 is required to support window-scoped service injection."
        )
    }
}
