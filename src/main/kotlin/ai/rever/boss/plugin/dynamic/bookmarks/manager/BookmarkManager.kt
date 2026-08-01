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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("BookmarkManager"))



    // One conflating channel per persisted file. Requests coalesce while a
    // save is running, and a single consumer keeps writes strictly ordered —
    // see saveCollectionsToFile.
    private val collectionSaveRequests = Channel<Unit>(Channel.CONFLATED)
    private val favoriteSaveRequests = Channel<Unit>(Channel.CONFLATED)

    /** Held so [close] can drain them before the scope goes away. */
    private val saveWorkers: List<Job> =
        listOf(
            scope.launch {
                for (unused in collectionSaveRequests) {
                    fileManager.saveCollections(_collections.value)
                }
            },
            scope.launch {
                for (unused in favoriteSaveRequests) {
                    fileManager.saveFavoriteWorkspaces(_favoriteWorkspaces.value)
                }
            },
        )

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
                val onDisk = fileManager.loadCollections()

                // Repair id collisions before anything keys off an id. Files
                // written by older versions can hold two collections under one
                // id — see withDistinctIds.
                val loaded = withDistinctIds(onDisk)

                // Ensure "Favorites" collection exists
                val withFavorites =
                    if (loaded.none { it.name == BookmarkCollection.FAVORITES_NAME }) {
                        listOf(
                            BookmarkCollection(
                                id = newCollectionId(),
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
                // would drop it from memory, and the mutation's own save would
                // then persist the post-load snapshot, losing it from disk too.
                //
                // Merging at collection level is not enough: an import goes into
                // a collection that usually already exists on disk (Favorites
                // always does), so a name-keyed filter would discard exactly the
                // bookmarks it is meant to protect. Union the bookmarks instead.
                val before =
                    _collections.getAndUpdate { pending ->
                        mergeLoadedWithPending(loaded = withFavorites, pending = pending)
                    }

                // Compare against what was on disk, not against the pre-merge
                // in-memory value: _collections starts empty, so `!= before` was
                // unconditionally true and every launch rewrote the file — an
                // iCloud sync upload per start for no state change.
                //
                // `onDisk` rather than the post-repair `loaded`, or an id
                // collision that withDistinctIds just fixed would live only in
                // memory and come back on the next launch.
                if (_collections.value != onDisk) {
                    saveCollectionsToFile()
                }

                // Load favorite workspaces
                // Same load-window race as collections: a workspace favourited
                // while this was in flight must survive the load.
                val loadedFavorites = fileManager.loadFavoriteWorkspaces()
                val favoritesBefore =
                    _favoriteWorkspaces.getAndUpdate { pending ->
                        val known = loadedFavorites.map { it.workspaceId }.toSet()
                        loadedFavorites + pending.filterNot { it.workspaceId in known }
                    }

                // Collections schedule a save after merging; favourites must too.
                // Otherwise a favourite added during the window survives in
                // memory, the load's stale read wins on disk, and it is gone on
                // next launch.
                if (_favoriteWorkspaces.value != loadedFavorites || favoritesBefore.isNotEmpty()) {
                    saveFavoriteWorkspacesToFile()
                }
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
                                id = newCollectionId(),
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
     * Mint a collection id that cannot collide with one minted a moment ago.
     *
     * [BookmarkCollection.generateId] is `"collection-<epochMillis>"`, so two
     * collections created inside the same millisecond get the *same* id — which
     * a browser import does routinely, creating "Bookmarks Bar" and "Other
     * Bookmarks" back to back through [createCollection].
     *
     * That is not a cosmetic clash. Collection ids are the identity every
     * operation keys off: [renameCollection] and [deleteCollection] hit
     * whichever collides first, [mergeLoadedWithPending] keys a map by id and so
     * silently drops one of the pair, and the panel uses the id as a LazyColumn
     * item key — where a duplicate makes two items share one subcomposition
     * slot and crashes Compose with "layout state is not idle before measure
     * starts" as soon as one of them is expanded.
     */
    private fun newCollectionId(): String =
        "collection-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}"

    /**
     * Return [collections] with every collection id, and every bookmark id,
     * distinct — repairing what older versions already wrote to disk.
     *
     * Colliding ids are not hypothetical: see [newCollectionId] for how
     * millisecond-resolution ids collide, and note that `Bookmark.generateId()`
     * has the same shape. This runs on load so a file written before the fix
     * stops crashing the panel, rather than only new files being safe.
     *
     * The first holder of an id keeps it and later ones are renumbered, so the
     * repair touches as little as possible and is idempotent: the ids it hands
     * out are themselves unused, so a second load is a no-op and does not
     * rewrite the file.
     *
     * Bookmark ids are made unique across *all* collections, not just within
     * one. The search provider publishes `bookmark.id` as the result id, and
     * [findBookmarkForTab] returns an id pair that callers resolve globally.
     */
    internal fun withDistinctIds(collections: List<BookmarkCollection>): List<BookmarkCollection> {
        val usedCollectionIds = HashSet<String>()
        val usedBookmarkIds = HashSet<String>()

        return collections.map { collection ->
            val collectionId = distinctId(collection.id, usedCollectionIds)

            var bookmarksChanged = false
            val bookmarks =
                collection.bookmarks.map { bookmark ->
                    val bookmarkId = distinctId(bookmark.id, usedBookmarkIds)
                    if (bookmarkId == bookmark.id) {
                        bookmark
                    } else {
                        bookmarksChanged = true
                        bookmark.copy(id = bookmarkId)
                    }
                }

            when {
                collectionId != collection.id ->
                    collection.copy(id = collectionId, bookmarks = bookmarks)
                bookmarksChanged -> collection.copy(bookmarks = bookmarks)
                // Untouched collections are returned as-is so an unaffected file
                // compares equal to what was loaded and no save is scheduled.
                else -> collection
            }
        }
    }

    /**
     * Claim [id] in [used], or the first `id-2`, `id-3`, … that is still free.
     *
     * The suffixed candidate is checked against [used] too: a file can already
     * contain the very id a naive `"$id-2"` would hand out.
     */
    private fun distinctId(id: String, used: MutableSet<String>): String {
        if (used.add(id)) return id
        var suffix = 2
        while (!used.add("$id-$suffix")) suffix++
        return "$id-$suffix"
    }

    /**
     * Apply [transform] to the favorite-workspace list atomically, saving only
     * if it actually changed. Favourites need the same treatment as
     * collections — see [mutateCollections].
     */
    private fun mutateFavorites(transform: (List<FavoriteWorkspace>) -> List<FavoriteWorkspace>) {
        val before = _favoriteWorkspaces.getAndUpdate(transform)
        if (_favoriteWorkspaces.value != before) saveFavoriteWorkspacesToFile()
    }

    /**
     * Apply [transform] to the collection list atomically, saving only if it
     * actually changed.
     *
     * `update` retries on conflict, so two mutations racing from different
     * threads can't lose one another the way a read-then-assign pair would.
     */
    private fun mutateCollections(transform: (List<BookmarkCollection>) -> List<BookmarkCollection>) {
        // getAndUpdate returns the exact pre-update value, so the comparison
        // can't be against a snapshot that another writer has since replaced.
        val before = _collections.getAndUpdate(transform)
        if (_collections.value != before) saveCollectionsToFile()
    }

    /**
     * Combine what was on disk with whatever landed while the load was running.
     *
     * Collections are matched by id, falling back to name for a pending
     * collection the loaded set knows under a different id. Bookmarks are
     * unioned and de-duplicated by id, so an import into an existing collection
     * survives instead of being replaced by the disk copy.
     *
     * Where both sides carry the same bookmark id, or disagree on a collection's
     * name, the *pending* value wins: it is the newer state, so preferring the
     * loaded copy would silently undo an edit, rename or delete made during the
     * window and then persist the reversion.
     */
    private fun mergeLoadedWithPending(
        loaded: List<BookmarkCollection>,
        pending: List<BookmarkCollection>,
    ): List<BookmarkCollection> {
        if (pending.isEmpty()) return loaded

        val merged = LinkedHashMap<String, BookmarkCollection>()
        loaded.forEach { merged[it.id] = it }

        pending.forEach { p ->
            val match = merged[p.id] ?: merged.values.firstOrNull { it.name == p.name }
            if (match == null) {
                merged[p.id] = p
            } else {
                // Pending wins on conflict. It is the newer state: an edit, a
                // rename or a removal made during the window would otherwise be
                // reverted by the disk copy — and then written back. Bookmarks
                // are unioned so nothing loaded is dropped, but where both sides
                // hold the same id the pending version is kept.
                merged[match.id] =
                    p.copy(
                        id = match.id,
                        bookmarks = (p.bookmarks + match.bookmarks).distinctBy { it.id },
                    )
            }
        }
        return merged.values.toList()
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
     * Add many bookmarks to a collection in one operation, creating the
     * collection if it does not already exist.
     *
     * The point of this over a loop of [addBookmark] is that it saves **once**.
     * Adding N bookmarks individually queues N full rewrites of
     * collections.json — for an import of a few hundred entries that is both
     * slow and, before the write became atomic, a way to lose the file.
     */
    fun addBookmarks(collectionName: String, bookmarks: List<Bookmark>) {
        if (bookmarks.isEmpty()) return

        mutateCollections { current ->
            // createCollection appends unconditionally rather than get-or-create,
            // so resolve by name first or an import into an existing folder would
            // leave a duplicate empty collection behind.
            val index = current.indexOfFirst { it.name == collectionName }

            if (index >= 0) {
                // One append of the whole batch, not a fold of per-item copies.
                // getAndUpdate re-runs this transform on CAS contention, and
                // BookmarkCollection.addBookmark copies the list each call — so
                // folding would be O(n²) copies, re-paid per retry, on whatever
                // thread the host called from. A browser import is 10-20k
                // entries, which is where that stops being theoretical.
                current.toMutableList().also {
                    it[index] = it[index].copy(bookmarks = it[index].bookmarks + bookmarks)
                }
            } else {
                current +
                    BookmarkCollection(
                        // Not the default millisecond id: a bulk import creates
                        // several collections in a row — see newCollectionId.
                        id = newCollectionId(),
                        name = collectionName,
                        bookmarks = bookmarks,
                        // Without this, importing into "Favorites" would create a
                        // collection named Favorites that getFavoritesCollection()
                        // — which matches on the flag — cannot find, and that
                        // deleteCollection would happily remove.
                        isFavorite = collectionName == BookmarkCollection.FAVORITES_NAME,
                    )
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
        val collection = BookmarkCollection(id = newCollectionId(), name = name)
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
                id = newCollectionId(),
                name = BookmarkCollection.FAVORITES_NAME,
                isFavorite = true
            )
    }

    // ==================== Favorite Workspace Operations ====================

    /**
     * Add a workspace to favorites.
     */
    fun addFavoriteWorkspace(workspaceId: String, workspaceName: String) {
        mutateFavorites { current ->
            if (current.any { it.workspaceId == workspaceId }) {
                current
            } else {
                current + FavoriteWorkspace.create(workspaceId, workspaceName)
            }
        }
    }

    /**
     * Remove a workspace from favorites.
     */
    fun removeFavoriteWorkspace(workspaceId: String) {
        mutateFavorites { current -> current.filter { it.workspaceId != workspaceId } }
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



    /**
     * Drain pending saves and stop the workers.
     *
     * Without this the two `for (request in channel)` loops run forever on
     * shared Dispatchers.Default threads: they keep this manager, its state
     * flows and the plugin classloader reachable, so a dynamic plugin can never
     * be unloaded, and each reload adds another pair of writers competing over
     * the same file from its own stale snapshot.
     *
     * Closing a channel lets its loop finish the requests already queued and
     * then exit, so joining here is also the flush-on-shutdown that conflation
     * would otherwise make easy to lose: a mutation whose trySend lands while a
     * save is in flight is only written by the *next* drain.
     */
    suspend fun close() {
        collectionSaveRequests.close()
        favoriteSaveRequests.close()
        try {
            saveWorkers.joinAll()
        } finally {
            // In a finally because dispose() calls this under withTimeout: when
            // the timeout fires — the wedged-save case the timeout exists for —
            // joinAll throws and a trailing cancel() would never run, leaking
            // exactly what close() was written to release. cancel() does not
            // suspend, so it is safe here, and it gives a stuck worker a
            // cancellation point to die at.
            scope.cancel()
        }
    }
}
