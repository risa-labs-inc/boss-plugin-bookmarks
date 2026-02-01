package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.workspace.LayoutWorkspace
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
 */
class BookmarksViewModel(
    private val bookmarkDataProvider: BookmarkDataProvider?,
    private val workspaceDataProvider: WorkspaceDataProvider?,
    private val splitViewOperations: SplitViewOperations?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Expose providers' data
    val collections: StateFlow<List<BookmarkCollection>> = bookmarkDataProvider?.collections
        ?: MutableStateFlow(emptyList())

    val favoriteWorkspaces = bookmarkDataProvider?.favoriteWorkspaces
        ?: MutableStateFlow(emptyList())

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
    fun onBookmarkClick(bookmark: Bookmark) {
        val splitView = splitViewOperations ?: return
        val provider = bookmarkDataProvider ?: return

        // Mark as accessed
        val collection = collections.value.find { coll ->
            coll.bookmarks.any { it.id == bookmark.id }
        }
        collection?.let {
            provider.markBookmarkAsAccessed(it.id, bookmark.id)
        }

        // Open the tab
        openTab(bookmark.tabConfig, splitView)
    }

    /**
     * Handle workspace click - loads entire workspace
     */
    fun onWorkspaceClick(workspace: LayoutWorkspace) {
        val splitView = splitViewOperations ?: return
        val wsProvider = workspaceDataProvider ?: return

        scope.launch {
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
        bookmarkDataProvider?.addBookmark(collectionName, bookmark)
        _statusMessage.value = "Bookmark added to $collectionName"
    }

    fun removeBookmark(collectionId: String, bookmarkId: String) {
        bookmarkDataProvider?.removeBookmark(collectionId, bookmarkId)
        _statusMessage.value = "Bookmark removed"
    }

    fun isTabBookmarked(tabConfig: TabConfig): Boolean {
        return bookmarkDataProvider?.isTabBookmarked(tabConfig) ?: false
    }

    // ==================== Collection Operations ====================

    fun createCollection(name: String): BookmarkCollection? {
        val collection = bookmarkDataProvider?.createCollection(name)
        if (collection != null) {
            _statusMessage.value = "Created collection: $name"
        }
        return collection
    }

    fun deleteCollection(collectionId: String) {
        bookmarkDataProvider?.deleteCollection(collectionId)
        _statusMessage.value = "Collection deleted"
    }

    fun renameCollection(collectionId: String, newName: String) {
        bookmarkDataProvider?.renameCollection(collectionId, newName)
        _statusMessage.value = "Collection renamed to $newName"
    }

    // ==================== Favorite Workspace Operations ====================

    fun addFavoriteWorkspace(workspaceId: String, workspaceName: String) {
        bookmarkDataProvider?.addFavoriteWorkspace(workspaceId, workspaceName)
        _statusMessage.value = "Added to favorites: $workspaceName"
    }

    fun removeFavoriteWorkspace(workspaceId: String) {
        bookmarkDataProvider?.removeFavoriteWorkspace(workspaceId)
        _statusMessage.value = "Removed from favorites"
    }

    fun isFavorite(workspaceId: String): Boolean {
        return bookmarkDataProvider?.isFavorite(workspaceId) ?: false
    }

    fun clearMessages() {
        _statusMessage.value = null
        _errorMessage.value = null
    }
}
