package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkManager
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * ViewModel for Bookmarks panel (Dynamic Plugin)
 *
 * Uses internal BookmarkManager for bookmark operations instead of
 * BookmarkDataProvider from the host application.
 */
class BookmarksViewModel(
    private val bookmarkManager: BookmarkManager,
    private val workspaceDataProvider: WorkspaceDataProvider?,
    private val splitViewOperations: SplitViewOperations?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Expose bookmark manager's data
    val collections: StateFlow<List<BookmarkCollection>> = bookmarkManager.collections

    val favoriteWorkspaces = bookmarkManager.favoriteWorkspaces

    val workspaces: StateFlow<List<LayoutWorkspace>> = workspaceDataProvider?.workspaces
        ?: MutableStateFlow(emptyList())

    val currentWorkspace = workspaceDataProvider?.currentWorkspace
        ?: MutableStateFlow<LayoutWorkspace?>(null)

    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Status messages
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Handle bookmark click - opens tab in active panel
     */
    fun onBookmarkClick(bookmark: Bookmark, coroutineScope: CoroutineScope) {
        val splitView = splitViewOperations ?: return

        // Mark as accessed
        val collection = collections.value.find { coll ->
            coll.bookmarks.any { it.id == bookmark.id }
        }
        collection?.let {
            bookmarkManager.markBookmarkAsAccessed(it.id, bookmark.id)
        }

        // Open the tab
        openTab(bookmark.tabConfig, splitView)
    }

    /**
     * Handle workspace click - loads entire workspace
     */
    fun onWorkspaceClick(workspace: LayoutWorkspace, coroutineScope: CoroutineScope) {
        val splitView = splitViewOperations ?: return
        val wsProvider = workspaceDataProvider ?: return

        coroutineScope.launch {
            // Preserve current state
            val current = wsProvider.currentWorkspace.value
            if (current != null && current.id.isNotEmpty()) {
                splitView.preserveCurrentState(current.id, current.name)
            }

            // Load workspace
            wsProvider.loadWorkspace(workspace)
            splitView.applyWorkspace(workspace)
            _statusMessage.value = "Loaded workspace: ${workspace.name}"
        }
    }

    /**
     * Handle workspace tab click
     */
    fun onWorkspaceTabClick(tabConfig: TabConfig) {
        val splitView = splitViewOperations ?: return
        openTab(tabConfig, splitView)
    }

    private fun openTab(tabConfig: TabConfig, splitView: SplitViewOperations) {
        when (tabConfig.type) {
            "browser" -> {
                val url = tabConfig.url ?: "about:blank"
                splitView.openUrlInActivePanel(url, tabConfig.title, forceNewTab = true)
            }
            "editor" -> {
                val filePath = tabConfig.filePath ?: ""
                if (filePath.isNotEmpty()) {
                    val fileName = filePath.substringAfterLast('/')
                    splitView.openFileInActivePanel(filePath, fileName)
                }
            }
            "terminal" -> {
                splitView.getActiveTabsComponent()?.addTerminalTab(
                    id = "terminal-${Random.nextLong()}",
                    title = tabConfig.title,
                    workingDirectory = null
                )
            }
        }
    }

    // ==================== Bookmark Operations ====================

    fun addBookmark(collectionName: String, bookmark: Bookmark) {
        bookmarkManager.addBookmark(collectionName, bookmark)
        _statusMessage.value = "Bookmark added to $collectionName"
    }

    fun removeBookmark(collectionId: String, bookmarkId: String) {
        bookmarkManager.removeBookmark(collectionId, bookmarkId)
        _statusMessage.value = "Bookmark removed"
    }

    fun moveBookmark(bookmarkId: String, fromCollectionId: String, toCollectionId: String) {
        bookmarkManager.moveBookmark(bookmarkId, fromCollectionId, toCollectionId)
        _statusMessage.value = "Bookmark moved"
    }

    fun isTabBookmarked(tabConfig: TabConfig): Boolean {
        return bookmarkManager.isTabBookmarked(tabConfig)
    }

    fun findBookmarkForTab(tabConfig: TabConfig): Pair<String, String>? {
        return bookmarkManager.findBookmarkForTab(tabConfig)
    }

    // ==================== Collection Operations ====================

    fun createCollection(name: String): BookmarkCollection? {
        val collection = bookmarkManager.createCollection(name)
        _statusMessage.value = "Created collection: $name"
        return collection
    }

    fun deleteCollection(collectionId: String) {
        bookmarkManager.deleteCollection(collectionId)
        _statusMessage.value = "Collection deleted"
    }

    fun renameCollection(collectionId: String, newName: String) {
        bookmarkManager.renameCollection(collectionId, newName)
        _statusMessage.value = "Collection renamed to $newName"
    }

    // ==================== Workspace Operations ====================

    fun createNewWorkspace(name: String) {
        val wsProvider = workspaceDataProvider ?: return
        if (name.isNotEmpty()) {
            val newWorkspace = LayoutWorkspace(
                name = name,
                description = "",
                layout = SplitConfig.SinglePanel(
                    panel = PanelConfig(
                        id = "panel-1",
                        tabs = emptyList()
                    )
                )
            )
            wsProvider.updateCurrentWorkspace(newWorkspace)
            wsProvider.saveCurrentWorkspace(name)
            _statusMessage.value = "Created workspace: $name"
        }
    }

    fun deleteWorkspace(name: String) {
        workspaceDataProvider?.deleteWorkspace(name)
        _statusMessage.value = "Workspace deleted"
    }

    fun renameWorkspace(oldName: String, newName: String) {
        workspaceDataProvider?.renameWorkspace(oldName, newName)
        _statusMessage.value = "Workspace renamed to $newName"
    }

    fun exportWorkspace(workspace: LayoutWorkspace): String {
        val json = workspaceDataProvider?.exportWorkspace(workspace) ?: ""
        _statusMessage.value = "Workspace exported"
        return json
    }

    // ==================== Favorite Workspace Operations ====================

    fun addFavoriteWorkspace(workspaceId: String, workspaceName: String) {
        bookmarkManager.addFavoriteWorkspace(workspaceId, workspaceName)
        _statusMessage.value = "Added to favorites: $workspaceName"
    }

    fun removeFavoriteWorkspace(workspaceId: String) {
        bookmarkManager.removeFavoriteWorkspace(workspaceId)
        _statusMessage.value = "Removed from favorites"
    }

    fun isFavorite(workspaceId: String): Boolean {
        return bookmarkManager.isFavorite(workspaceId)
    }

    fun clearMessages() {
        _statusMessage.value = null
        _errorMessage.value = null
    }

    // ==================== Utility ====================

    /**
     * Build hierarchical tab structure from workspace layout
     */
    fun buildTabStructure(layout: SplitConfig, level: Int = 0): List<WorkspaceTabStructure> {
        return when (layout) {
            is SplitConfig.SinglePanel -> {
                layout.panel.tabs.map { WorkspaceTabStructure.TabItem(it) }
            }
            is SplitConfig.VerticalSplit -> {
                listOf(
                    WorkspaceTabStructure.SplitSection(
                        sectionName = "Left",
                        children = buildTabStructure(layout.left, level + 1),
                        level = level
                    ),
                    WorkspaceTabStructure.SplitSection(
                        sectionName = "Right",
                        children = buildTabStructure(layout.right, level + 1),
                        level = level
                    )
                )
            }
            is SplitConfig.HorizontalSplit -> {
                listOf(
                    WorkspaceTabStructure.SplitSection(
                        sectionName = "Top",
                        children = buildTabStructure(layout.top, level + 1),
                        level = level
                    ),
                    WorkspaceTabStructure.SplitSection(
                        sectionName = "Bottom",
                        children = buildTabStructure(layout.bottom, level + 1),
                        level = level
                    )
                )
            }
        }
    }
}

/**
 * Represents the hierarchical tab structure within a workspace
 */
sealed class WorkspaceTabStructure {
    data class TabItem(
        val tabConfig: TabConfig
    ) : WorkspaceTabStructure()

    data class SplitSection(
        val sectionName: String,
        val children: List<WorkspaceTabStructure>,
        val level: Int = 0
    ) : WorkspaceTabStructure()
}
