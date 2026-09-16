package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.bookmark.BookmarkLibraryProvider
import ai.rever.boss.plugin.bookmark.BookmarkOpeningProvider
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkManager
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.api.LocalWindowIdProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy

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
    private val activeTabsProvider: ActiveTabsProvider?,
    private val library: BookmarkLibraryProvider,
    private val opener: () -> BookmarkOpeningProvider?,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = BookmarksViewModel(
        bookmarkManager = bookmarkManager,
        workspaceDataProvider = workspaceDataProvider,
        splitViewOperations = splitViewOperations,
        library = library,
        opener = opener,
        windowId = "",
    )

    init { lifecycle.doOnDestroy { viewModel.close() } }

    @Composable
    override fun Content() {
        val originWindow = LocalWindowIdProvider.current?.getWindowId().orEmpty()
        SideEffect { viewModel.setOriginWindow(originWindow) }
        BossTheme {
            BookmarksContent(
                viewModel = viewModel,
                bookmarkManager = bookmarkManager,
                workspaceDataProvider = workspaceDataProvider,
                contextMenuProvider = contextMenuProvider,
                activeTabsProvider = activeTabsProvider
            )
        }
    }
}
