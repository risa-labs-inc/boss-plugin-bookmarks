package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkManager
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

/**
 * Bookmarks panel component (Dynamic Plugin)
 *
 * Full implementation using internal BookmarkManager for bookmark operations
 * and providers from PluginContext for workspace and tab operations.
 */
class BookmarksComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val bookmarkManager: BookmarkManager,
    private val workspaceDataProvider: WorkspaceDataProvider?,
    private val splitViewOperations: SplitViewOperations?,
    private val contextMenuProvider: ContextMenuProvider?,
    private val activeTabsProvider: ActiveTabsProvider?
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = BookmarksViewModel(
        bookmarkManager = bookmarkManager,
        workspaceDataProvider = workspaceDataProvider,
        splitViewOperations = splitViewOperations
    )

    @Composable
    override fun Content() {
        BookmarksContent(
            viewModel = viewModel,
            bookmarkManager = bookmarkManager,
            workspaceDataProvider = workspaceDataProvider,
            contextMenuProvider = contextMenuProvider,
            activeTabsProvider = activeTabsProvider
        )
    }
}
