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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel

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

    // One conflating channel per persisted file. Requests coalesce while a
    // save is running, and a single consumer keeps writes strictly ordered —
    // see saveCollectionsToFile.
    private val collectionSaveRequests = Channel<Unit>(Channel.CONFLATED)
    private val favoriteSaveRequests = Channel<Unit>(Channel.CONFLATED)

    private val _collections = MutableStateFlow<List<BookmarkCollection>>(emptyList())
    val collections: StateFlow<List<BookmarkCollection>> = _collections.asStateFlow()

    private val _favoriteWorkspaces = MutableStateFlow<List<FavoriteWorkspace>>(emptyList())
    val favoriteWorkspaces: StateFlow<List<FavoriteWorkspace>> = _favoriteWorkspaces.asStateFlow()

    init {
        startSaveWorkers()
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
                val withFavorites =
                    if (loaded.none { it.name == BookmarkCollection.FAVORITES_NAME }) {
                        listOf(
                            BookmarkCollection(
                                name = BookmarkCollection.FAVORITES_NAME,
                                isFavorite = true
                            )
                        ) + loaded
                    } else {
                        loaded
                    }

                // Merge rather than replace. This load is asynchronous, so a
                // mutation — a bulk import fired right after registration, say
                // — can land in _collections before it finishes. Assigning
                // would drop that collection from memory, and the mutation's
                // own save would then persist the post-load snapshot, losing it
                // from disk too.
                var mergedAnything = false
                _collections.update { pending ->
                    val loadedNames = withFavorites.map { it.name }.toSet()
                    val addedDuringLoad = pending.filterNot { it.name in loadedNames }
                    mergedAnything = addedDuringLoad.isNotEmpty()
                    withFavorites + addedDuringLoad
                }

                if (mergedAnything || loaded.size != withFavorites.size) {
                    saveCollectionsToFile()
                }

                // Load favorite workspaces
                _favoriteWorkspaces.value = fileManager.loadFavoriteWorkspaces()
            } catch (e: Exception) {
                logger.warn(LogCategory.UI, "Error loading bookmarks", error = e)
                // Same merge rule as the success path: never clobber a mutation
                // that arrived while the load was in flight.
                _collections.update { pending ->
                    if (pending.any { it.name == BookmarkCollection.FAVORITES_NAME }) {
                        pending
                    } else {
                        listOf(
                            BookmarkCollection(
                                name = BookmarkCollection.FAVORITES_NAME,
                                isFavorite = true
                            )
                        ) + pending
                    }
                }
            }
        }
    }

    /**
     * Apply [transform] to the collection list atomically, saving only if it
     * actually changed.
     *
     * `update` retries on conflict, so two mutations racing from different
     * threads can't lose one another the way a read-then-assign pair would.
     */
    private fun mutateCollections(transform: (List<BookmarkCollection>) -> List<BookmarkCollection>) {
        val before = _collections.value
        val after = _collections.updateAndGet(transform)
        if (after != before) saveCollectionsToFile()
    }

    // ==================== Bookmark Operations ====================

    /**
     * Add a bookmark to a collection.
     */
    fun addBookmark(collectionName: String, bookmark: Bookmark) {
        mutateCollections { current ->
            val index = current.indexOfFirst { it.name == collectionName }
            if (index < 0) {
                current
            } else {
                current.toMutableList().also { it[index] = it[index].addBookmark(bookmark) }
            }
        }
    }

    /**
     * Remove a bookmark from a collection.
     */
    fun removeBookmark(collectionId: String, bookmarkId: String) {
        mutateCollections { current ->
            val index = current.indexOfFirst { it.id == collectionId }
            if (index < 0) {
                current
            } else {
                current.toMutableList().also { it[index] = it[index].removeBookmark(bookmarkId) }
            }
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
        mutateCollections { current ->
            val index = current.indexOfFirst { it.id == collectionId }
            if (index < 0) {
                current
            } else {
                current.toMutableList().also { it[index] = it[index].updateBookmark(bookmark) }
            }
        }
    }

    /**
     * Move a bookmark from one collection to another.
     */
    fun moveBookmark(bookmarkId: String, fromCollectionId: String, toCollectionId: String) {
        mutateCollections { current ->
            val fromIndex = current.indexOfFirst { it.id == fromCollectionId }
            val toIndex = current.indexOfFirst { it.id == toCollectionId }
            val bookmark = current.getOrNull(fromIndex)?.findBookmark(bookmarkId)

            if (fromIndex < 0 || toIndex < 0 || bookmark == null) {
                current
            } else {
                current.toMutableList().also {
                    it[fromIndex] = it[fromIndex].removeBookmark(bookmarkId)
                    it[toIndex] = it[toIndex].addBookmark(bookmark)
                }
            }
        }
    }

    /**
     * Mark a bookmark as accessed (updates lastAccessedAt timestamp).
     */
    fun markBookmarkAsAccessed(collectionId: String, bookmarkId: String) {
        mutateCollections { current ->
            val index = current.indexOfFirst { it.id == collectionId }
            val bookmark = current.getOrNull(index)?.findBookmark(bookmarkId)

            if (index < 0 || bookmark == null) {
                current
            } else {
                current.toMutableList().also {
                    it[index] = it[index].updateBookmark(bookmark.markAsAccessed())
                }
            }
        }
    }

    // ==================== Collection Operations ====================

    /**
     * Create a new bookmark collection.
     */
    fun createCollection(name: String): BookmarkCollection {
        val collection = BookmarkCollection(name = name)
        mutateCollections { current -> current + collection }
        return collection
    }

    /**
     * Delete a bookmark collection.
     *
     * Cannot delete the special "Favorites" collection.
     */
    fun deleteCollection(collectionId: String) {
        mutateCollections { current ->
            // Cannot delete "Favorites" collection
            val collection = current.find { it.id == collectionId }
            if (collection == null || collection.isFavorite) {
                current
            } else {
                current.filter { it.id != collectionId }
            }
        }
    }

    /**
     * Rename a bookmark collection.
     */
    fun renameCollection(collectionId: String, newName: String) {
        mutateCollections { current ->
            val index = current.indexOfFirst { it.id == collectionId }
            if (index < 0) {
                current
            } else {
                current.toMutableList().also { it[index] = it[index].copy(name = newName) }
            }
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
     * Ask for collections to be written.
     *
     * Every mutating operation calls this, so a burst — a bulk import, say —
     * would otherwise queue one full serialise-and-write of an ever-growing
     * document per mutation: O(n²) bytes for a linear import, where only the
     * last write's content actually matters.
     *
     * A conflating channel gives ordering and coalescing together. Requests
     * that arrive while a save is in flight collapse into one, and the single
     * consumer reads state at write time, so the file always converges on the
     * latest snapshot. Five hundred mutations cost one or two writes.
     *
     * Note this only orders writers *inside this instance*. Two host processes,
     * or a plugin reload, are made safe by the atomic move in
     * [BookmarkFileManager] — not by this. Don't remove the rename on the
     * grounds that the writes look serialised here.
     */
    private fun saveCollectionsToFile() {
        collectionSaveRequests.trySend(Unit)
    }

    /** Ask for favorite workspaces to be written. See [saveCollectionsToFile]. */
    private fun saveFavoriteWorkspacesToFile() {
        favoriteSaveRequests.trySend(Unit)
    }

    private fun startSaveWorkers() {
        scope.launch {
            for (request in collectionSaveRequests) {
                fileManager.saveCollections(_collections.value)
            }
        }
        scope.launch {
            for (request in favoriteSaveRequests) {
                fileManager.saveFavoriteWorkspaces(_favoriteWorkspaces.value)
            }
        }
    }
}
