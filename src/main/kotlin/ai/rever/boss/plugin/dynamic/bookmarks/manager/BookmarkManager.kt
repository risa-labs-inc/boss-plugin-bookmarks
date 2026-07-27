package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages bookmark collections and favorite workspaces.
 *
 * Provides reactive state flows for UI and handles persistence.
 * Automatically creates the "Favorites" collection on first run.
 *
 * This is the internal bookmark manager for the bookmarks plugin.
 * It is self-contained and does not depend on BossConsole's implementation.
 */
class BookmarkManager internal constructor(
    // Injectable for tests; production callers use the no-arg constructor and
    // get the real ~/Documents/BOSS/bookmarks location.
    private val fileManager: BookmarkFileManager
) {
    constructor() : this(BookmarkFileManager())

    // Getter (no backing field): see BookmarkFileManager.logger — avoids a
    // Compose-emitted $stable reference the host's ComponentLogger lacks.
    private val logger get() = BossLogger.forComponent("BookmarkManager")
    private val scope = CoroutineScope(Dispatchers.Default)

    // One lock per persisted file. Saves are fire-and-forget launches, so
    // without these two writers can interleave on the same path — see
    // saveCollectionsToFile.
    private val collectionsSaveMutex = Mutex()
    private val favoritesSaveMutex = Mutex()

    private val _collections = MutableStateFlow<List<BookmarkCollection>>(emptyList())
    val collections: StateFlow<List<BookmarkCollection>> = _collections.asStateFlow()

    private val _favoriteWorkspaces = MutableStateFlow<List<FavoriteWorkspace>>(emptyList())
    val favoriteWorkspaces: StateFlow<List<FavoriteWorkspace>> = _favoriteWorkspaces.asStateFlow()

    init {
        // Load bookmarks from disk
        loadAllData()
    }

    /**
     * Load all bookmark data from disk.
     */
    private fun loadAllData() {
        scope.launch {
            try {
                // Load collections
                val loaded = fileManager.loadCollections()

                // Ensure "Favorites" collection exists
                if (loaded.none { it.name == BookmarkCollection.FAVORITES_NAME }) {
                    val favorites = BookmarkCollection(
                        name = BookmarkCollection.FAVORITES_NAME,
                        isFavorite = true
                    )
                    _collections.value = listOf(favorites) + loaded
                    saveCollectionsToFile()
                } else {
                    _collections.value = loaded
                }

                // Load favorite workspaces
                _favoriteWorkspaces.value = fileManager.loadFavoriteWorkspaces()
            } catch (e: Exception) {
                logger.warn(LogCategory.UI, "Error loading bookmarks", error = e)
                // Initialize with default "Favorites" collection on error
                _collections.value = listOf(
                    BookmarkCollection(
                        name = BookmarkCollection.FAVORITES_NAME,
                        isFavorite = true
                    )
                )
            }
        }
    }

    // ==================== Bookmark Operations ====================

    /**
     * Add a bookmark to a collection.
     */
    fun addBookmark(collectionName: String, bookmark: Bookmark) {
        val collections = _collections.value.toMutableList()
        val index = collections.indexOfFirst { it.name == collectionName }

        if (index >= 0) {
            collections[index] = collections[index].addBookmark(bookmark)
            _collections.value = collections
            saveCollectionsToFile()
        }
    }

    /**
     * Remove a bookmark from a collection.
     */
    fun removeBookmark(collectionId: String, bookmarkId: String) {
        val collections = _collections.value.toMutableList()
        val index = collections.indexOfFirst { it.id == collectionId }

        if (index >= 0) {
            collections[index] = collections[index].removeBookmark(bookmarkId)
            _collections.value = collections
            saveCollectionsToFile()
        }
    }

    /**
     * Check if a tab is already bookmarked in any collection.
     */
    fun isTabBookmarked(tabConfig: TabConfig): Boolean {
        return _collections.value.any { collection ->
            collection.bookmarks.any { bookmark ->
                bookmark.tabConfig.type == tabConfig.type &&
                bookmark.tabConfig.title == tabConfig.title &&
                bookmark.tabConfig.url == tabConfig.url &&
                bookmark.tabConfig.filePath == tabConfig.filePath
            }
        }
    }

    /**
     * Find which collection and bookmark ID contain this tab.
     * Returns Pair(collectionId, bookmarkId) or null if not found.
     */
    fun findBookmarkForTab(tabConfig: TabConfig): Pair<String, String>? {
        _collections.value.forEach { collection ->
            collection.bookmarks.firstOrNull { bookmark ->
                bookmark.tabConfig.type == tabConfig.type &&
                bookmark.tabConfig.title == tabConfig.title &&
                bookmark.tabConfig.url == tabConfig.url &&
                bookmark.tabConfig.filePath == tabConfig.filePath
            }?.let { bookmark ->
                return Pair(collection.id, bookmark.id)
            }
        }
        return null
    }

    /**
     * Update a bookmark in a collection.
     */
    fun updateBookmark(collectionId: String, bookmark: Bookmark) {
        val collections = _collections.value.toMutableList()
        val index = collections.indexOfFirst { it.id == collectionId }

        if (index >= 0) {
            collections[index] = collections[index].updateBookmark(bookmark)
            _collections.value = collections
            saveCollectionsToFile()
        }
    }

    /**
     * Move a bookmark from one collection to another.
     */
    fun moveBookmark(bookmarkId: String, fromCollectionId: String, toCollectionId: String) {
        val collections = _collections.value.toMutableList()
        val fromIndex = collections.indexOfFirst { it.id == fromCollectionId }
        val toIndex = collections.indexOfFirst { it.id == toCollectionId }

        if (fromIndex >= 0 && toIndex >= 0) {
            val bookmark = collections[fromIndex].findBookmark(bookmarkId)
            if (bookmark != null) {
                collections[fromIndex] = collections[fromIndex].removeBookmark(bookmarkId)
                collections[toIndex] = collections[toIndex].addBookmark(bookmark)
                _collections.value = collections
                saveCollectionsToFile()
            }
        }
    }

    /**
     * Mark a bookmark as accessed (updates lastAccessedAt timestamp).
     */
    fun markBookmarkAsAccessed(collectionId: String, bookmarkId: String) {
        val collections = _collections.value.toMutableList()
        val index = collections.indexOfFirst { it.id == collectionId }

        if (index >= 0) {
            val bookmark = collections[index].findBookmark(bookmarkId)
            if (bookmark != null) {
                collections[index] = collections[index].updateBookmark(bookmark.markAsAccessed())
                _collections.value = collections
                saveCollectionsToFile()
            }
        }
    }

    // ==================== Collection Operations ====================

    /**
     * Create a new bookmark collection.
     */
    fun createCollection(name: String): BookmarkCollection {
        val collection = BookmarkCollection(name = name)
        _collections.value = _collections.value + collection
        saveCollectionsToFile()
        return collection
    }

    /**
     * Delete a bookmark collection.
     *
     * Cannot delete the special "Favorites" collection.
     */
    fun deleteCollection(collectionId: String) {
        val collection = _collections.value.find { it.id == collectionId }

        // Cannot delete "Favorites" collection
        if (collection != null && !collection.isFavorite) {
            _collections.value = _collections.value.filter { it.id != collectionId }
            saveCollectionsToFile()
        }
    }

    /**
     * Rename a bookmark collection.
     */
    fun renameCollection(collectionId: String, newName: String) {
        val collections = _collections.value.toMutableList()
        val index = collections.indexOfFirst { it.id == collectionId }

        if (index >= 0) {
            collections[index] = collections[index].copy(name = newName)
            _collections.value = collections
            saveCollectionsToFile()
        }
    }

    /**
     * Get the "Favorites" collection.
     *
     * Guaranteed to always exist.
     */
    fun getFavoritesCollection(): BookmarkCollection {
        return _collections.value.find { it.isFavorite }
            ?: BookmarkCollection(
                name = BookmarkCollection.FAVORITES_NAME,
                isFavorite = true
            )
    }

    // ==================== Favorite Workspace Operations ====================

    /**
     * Add a workspace to favorites.
     */
    fun addFavoriteWorkspace(workspaceId: String, workspaceName: String) {
        val current = _favoriteWorkspaces.value
        if (current.none { it.workspaceId == workspaceId }) {
            _favoriteWorkspaces.value = current + FavoriteWorkspace.create(workspaceId, workspaceName)
            saveFavoriteWorkspacesToFile()
        }
    }

    /**
     * Remove a workspace from favorites.
     */
    fun removeFavoriteWorkspace(workspaceId: String) {
        _favoriteWorkspaces.value = _favoriteWorkspaces.value.filter { it.workspaceId != workspaceId }
        saveFavoriteWorkspacesToFile()
    }

    /**
     * Check if a workspace is favorited.
     */
    fun isFavorite(workspaceId: String): Boolean {
        return _favoriteWorkspaces.value.any { it.workspaceId == workspaceId }
    }

    // ==================== Persistence ====================

    /**
     * Save collections to file.
     *
     * Every mutating operation calls this, so a burst of writes (a bulk import,
     * say) queues many concurrent launches. Without the mutex they would all
     * reach [BookmarkFileManager.saveCollections] at once and race on the same
     * path. The lock serialises them; the state is read *inside* the lock so a
     * queued save always persists the latest snapshot rather than a stale one.
     */
    private fun saveCollectionsToFile() {
        scope.launch {
            collectionsSaveMutex.withLock {
                fileManager.saveCollections(_collections.value)
            }
        }
    }

    /**
     * Save favorite workspaces to file.
     */
    private fun saveFavoriteWorkspacesToFile() {
        scope.launch {
            favoritesSaveMutex.withLock {
                fileManager.saveFavoriteWorkspaces(_favoriteWorkspaces.value)
            }
        }
    }
}
