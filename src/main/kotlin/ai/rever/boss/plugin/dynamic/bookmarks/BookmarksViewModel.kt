package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.bookmark.*
import ai.rever.boss.plugin.dynamic.bookmarks.manager.sameTarget
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
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
import kotlinx.coroutines.cancel
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
    private val splitViewOperations: SplitViewOperations?,
    val library: BookmarkLibraryProvider? = null,
    private val opener: () -> BookmarkOpeningProvider? = { null },
    private var windowId: String = "",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Expose bookmark manager's data
    val collections: StateFlow<List<BookmarkCollection>> = library?.state?.map { it.collections }
        ?.stateIn(scope, SharingStarted.Eagerly, library.state.value.collections) ?: bookmarkManager.collections

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
    private val opening = java.util.concurrent.atomic.AtomicBoolean(false)
    fun openBookmark(bookmark: Bookmark, forceNewTab: Boolean = false) {
        if (!opening.compareAndSet(false, true)) return
        val originWindow = windowId
        scope.launch {
            try {
                val handler = opener()
                if (handler == null) _errorMessage.value = "Update BOSS to open this bookmark safely."
                else {
                    val result = handler.openBookmark(bookmark, originWindow, null, forceNewTab)
                    if (!result.success) _errorMessage.value = result.message ?: "This bookmark could not be opened."
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _errorMessage.value = e.message ?: "This bookmark could not be opened."
            } finally { opening.set(false) }
        }
    }

    fun onBookmarkClick(bookmark: Bookmark, coroutineScope: CoroutineScope) {
        if (library != null) { openBookmark(bookmark); return }
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
        if (library != null) { openBookmark(Bookmark(tabConfig = tabConfig, workspaceName = "")); return }
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
                    workingDirectory = tabConfig.workingDirectory,
                    initialCommand = tabConfig.initialCommand
                )
            }
        }
    }

    private val mutationBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutationBusy
    private val mutableUndoToken = MutableStateFlow<String?>(null)
    val undoToken: StateFlow<String?> = mutableUndoToken

    private fun mutate(message: String, onSuccess: () -> Unit = {}, action: suspend (BookmarkLibraryProvider) -> BookmarkMutationResult) {
        val provider = library ?: return
        if (!mutationBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                val result = action(provider)
                if (result.success) {
                    _errorMessage.value = null
                    _statusMessage.value = message
                    result.undoToken?.let { mutableUndoToken.value = it }
                    onSuccess()
                } else _errorMessage.value = result.message ?: "Bookmarks could not be saved."
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _errorMessage.value = e.message ?: "Bookmark operation failed."
            } finally { mutationBusy.value = false }
        }
    }

    fun setFavorite(bookmarkId: String, favorite: Boolean) = mutate(if (favorite) "Shown in Favorites" else "Removed from Favorites; bookmark kept") {
        it.setFavorite(bookmarkId, favorite, it.state.value.revision)
    }

    fun clearFavorites(onSuccess: () -> Unit) = mutate("Favorites cleared; bookmarks kept", onSuccess) { provider ->
        var result = BookmarkMutationResult(true)
        for (id in provider.state.value.favoriteBookmarkIds.toList()) {
            result = provider.setFavorite(id, false, provider.state.value.revision)
            if (!result.success) break
        }
        result
    }

    fun undoDelete() {
        val token = mutableUndoToken.value ?: return
        mutate("Bookmark restored", { mutableUndoToken.value = null }) { it.undo(token, it.state.value.revision) }
    }

    fun reloadLibrary() { library?.let { provider -> scope.launch { provider.reload() } } }

    fun saveEdit(bookmark: Bookmark, name: String, target: TabConfig, collectionId: String, favorite: Boolean, revision: Long, onSuccess: () -> Unit) =
        mutate("Bookmark saved", onSuccess) { it.saveBookmark(BookmarkSaveRequest(collectionId, target, name, favorite, revision, bookmark.id)) }

    fun addBookmark(collectionName: String, bookmark: Bookmark) {
        if (library == null) { bookmarkManager.addBookmark(collectionName, bookmark); return }
        val collection = library.state.value.collections.firstOrNull { it.name == collectionName } ?: return
        mutate("Bookmark saved") { it.saveBookmark(BookmarkSaveRequest(collection.id, bookmark.tabConfig, bookmark.tabConfig.title, it.state.value.defaultFavorite, it.state.value.revision)) }
    }

    fun copyBookmark(collectionName: String, bookmark: Bookmark, onSuccess: () -> Unit = {}) {
        if (library == null) { bookmarkManager.addBookmark(collectionName, bookmark.copy(id = bookmarkManager.newBookmarkId())); onSuccess(); return }
        val collection = library.state.value.collections.firstOrNull { it.name == collectionName } ?: return
        mutate("Bookmark copied", onSuccess) { it.saveBookmark(BookmarkSaveRequest(collection.id, bookmark.tabConfig, bookmark.tabConfig.title, false, it.state.value.revision, allowCopy = true)) }
    }

    fun removeBookmark(collectionId: String, bookmarkId: String, expectedRevision: Long? = null, onSuccess: () -> Unit = {}) {
        if (library == null) { bookmarkManager.removeBookmark(collectionId, bookmarkId); onSuccess(); return }
        mutate("Bookmark deleted", onSuccess) { it.deleteBookmark(bookmarkId, expectedRevision ?: it.state.value.revision) }
    }

    fun renameBookmark(collectionId: String, bookmarkId: String, newTitle: String) {
        if (library == null) { bookmarkManager.renameBookmark(collectionId, bookmarkId, newTitle); return }
        val snapshot = library.state.value
        val bookmark = snapshot.collections.flatMap { it.bookmarks }.find { it.id == bookmarkId } ?: return
        saveEdit(bookmark, newTitle, bookmark.tabConfig, collectionId, bookmarkId in snapshot.favoriteBookmarkIds, snapshot.revision) {}
    }

    fun moveBookmark(bookmarkId: String, fromCollectionId: String, toCollectionId: String, onSuccess: () -> Unit = {}) {
        if (library == null) { bookmarkManager.moveBookmark(bookmarkId, fromCollectionId, toCollectionId); onSuccess(); return }
        mutate("Bookmark moved", onSuccess) { it.moveBookmark(bookmarkId, toCollectionId, it.state.value.revision) }
    }

    fun isTabBookmarked(tabConfig: TabConfig): Boolean = findBookmarkForTab(tabConfig) != null
    fun findBookmarkForTab(tabConfig: TabConfig): Pair<String, String>? {
        if (library == null) return bookmarkManager.findBookmarkForTab(tabConfig)
        library.state.value.collections.forEach { c -> c.bookmarks.firstOrNull { sameTarget(it.tabConfig, tabConfig) }?.let { return c.id to it.id } }
        return null
    }

    fun createCollection(name: String, onSuccess: () -> Unit = {}) {
        if (library == null) { bookmarkManager.createCollection(name); onSuccess(); return }
        mutate("Folder created", onSuccess) { it.createCollection(name, it.state.value.revision) }
    }
    fun deleteCollection(collectionId: String, moveTo: String? = null, onSuccess: () -> Unit = {}) {
        if (library == null) { bookmarkManager.deleteCollection(collectionId); onSuccess(); return }
        mutate("Folder deleted", onSuccess) { it.deleteCollection(collectionId, moveTo, it.state.value.revision) }
    }
    fun renameCollection(collectionId: String, newName: String, expectedRevision: Long? = null, onSuccess: () -> Unit = {}) {
        if (library == null) { bookmarkManager.renameCollection(collectionId, newName); onSuccess(); return }
        mutate("Folder renamed", onSuccess) { it.renameCollection(collectionId, newName, expectedRevision ?: it.state.value.revision) }
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

    fun setOriginWindow(windowId: String) { this.windowId = windowId }

    fun close() { scope.cancel() }

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
