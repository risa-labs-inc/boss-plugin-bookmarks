package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

/**
 * Bookmarks panel component (Dynamic Plugin)
 *
 * Full implementation using providers from PluginContext.
 */
class BookmarksComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val bookmarkDataProvider: BookmarkDataProvider?,
    private val workspaceDataProvider: WorkspaceDataProvider?,
    private val splitViewOperations: SplitViewOperations?,
    private val contextMenuProvider: ContextMenuProvider?,
    private val activeTabsProvider: ActiveTabsProvider?
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = BookmarksViewModel(
        bookmarkDataProvider = bookmarkDataProvider,
        workspaceDataProvider = workspaceDataProvider,
        splitViewOperations = splitViewOperations
    )

    @Composable
    override fun Content() {
        BookmarksContent(
            viewModel = viewModel,
            bookmarkDataProvider = bookmarkDataProvider,
            workspaceDataProvider = workspaceDataProvider,
            contextMenuProvider = contextMenuProvider,
            activeTabsProvider = activeTabsProvider
        )
    }
}
