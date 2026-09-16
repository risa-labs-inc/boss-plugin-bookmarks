package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.*
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal class BookmarkLibraryStore(
    private val disk: BookmarkLibraryDisk = BookmarkLibraryDisk(),
    watch: Boolean = true,
) : BookmarkLibraryProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(BookmarkLibraryState())
    override val state: StateFlow<BookmarkLibraryState> = mutableState
    private val mutableCollections = MutableStateFlow<List<BookmarkCollection>>(emptyList())
    val collections: StateFlow<List<BookmarkCollection>> = mutableCollections
    private var document = LibraryDocument()
    private var fingerprint: String? = null

    init {
        scope.launch {
            load(importExternal = false)
            if (watch) while (isActive) {
                delay(1000)
                mutex.withLock {
                    try {
                        val latest = disk.snapshot()
                        when {
                            latest.document == null || latest.legacyFingerprint != latest.document.legacyFingerprint -> {
                                mutableState.value = mutableState.value.copy(ready = false, error = "Bookmarks changed outside this library. Reload to review and import changes.")
                            }
                            latest.fingerprint != fingerprint -> {
                                // Another window committed successfully. Publish its complete snapshot;
                                // an already-open editor still holds its old optimistic revision.
                                fingerprint = latest.fingerprint
                                publish(latest.document)
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        mutableState.value = mutableState.value.copy(ready = false, error = e.message ?: "Cannot read bookmarks.")
                    }
                }
            }
        }
    }

    override suspend fun reload() = load(importExternal = true)

    private suspend fun load(importExternal: Boolean) = mutex.withLock {
        try {
            val snapshot = disk.snapshot()
            var loaded = snapshot.document
            check(loaded != null || fingerprint == null) { "The bookmark library file was removed. Restore it before reloading; your current bookmarks are retained." }
            if (loaded == null || loaded.legacyFingerprint != snapshot.legacyFingerprint) {
                if (loaded != null && !importExternal) {
                    publish(loaded, ready = false, error = "Legacy bookmarks changed. Reload to import changes without replacing saved records.")
                    fingerprint = snapshot.fingerprint
                    return@withLock
                }
                loaded = importLegacyLibrary(loaded, disk.legacyCollections(), snapshot.legacyFingerprint)
                    .copy(revision = (loaded?.revision ?: 0) + 1)
                fingerprint = disk.commit(snapshot.fingerprint, loaded)
            } else fingerprint = snapshot.fingerprint
            publish(loaded)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.value = mutableState.value.copy(ready = false, error = e.message ?: "Cannot load bookmarks.")
        }
    }

    private fun publish(value: LibraryDocument, ready: Boolean = true, error: String? = null) {
        document = value
        mutableCollections.value = value.collections
        mutableState.value = BookmarkLibraryState(value.collections, value.favoriteBookmarkIds, ready, error, value.revision, value.defaultFavorite)
    }

    private data class Change(val value: LibraryDocument, val result: BookmarkMutationResult = BookmarkMutationResult(true))

    private suspend fun change(revision: Long, transform: (LibraryDocument) -> Change): BookmarkMutationResult = mutex.withLock {
        if (!state.value.ready) return@withLock BookmarkMutationResult(false, message = state.value.error ?: "Bookmarks are still loading.")
        if (revision != document.revision) return@withLock BookmarkMutationResult(false, message = "Bookmarks changed. Review the latest values and try again.")
        try {
            val changed = transform(document)
            if (!changed.result.success) return@withLock changed.result
            if (changed.value == document) return@withLock changed.result
            val next = changed.value.copy(revision = document.revision + 1)
            fingerprint = disk.commit(fingerprint, next)
            publish(next)
            changed.result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val message = e.message ?: "Bookmarks could not be saved. Your previous library is unchanged."
            mutableState.value = mutableState.value.copy(error = message)
            BookmarkMutationResult(false, message = message)
        }
    }

    override suspend fun saveBookmark(request: BookmarkSaveRequest): BookmarkMutationResult = change(request.expectedRevision) { current ->
        require(request.name.isNotBlank()) { "Enter a bookmark name." }
        require(current.collections.any { it.id == request.collectionId }) { "Select an existing collection." }
        val target = request.tabConfig.copy(title = request.name.trim())
        require(meaningfulTarget(target)) { "This tab does not have a supported saved destination." }
        val records = current.collections.flatMap { it.bookmarks }
        val existing = request.bookmarkId?.let { id -> records.find { it.id == id } ?: error("This bookmark no longer exists.") }
        val duplicate = records.find { it.id != existing?.id && sameTarget(it.tabConfig, target) }
        if (duplicate != null && !request.allowCopy && (existing == null || !sameTarget(existing.tabConfig, target))) {
            Change(current, BookmarkMutationResult(false, duplicateBookmarkId = duplicate.id, message = "This destination is already saved. Edit it or deliberately save a copy."))
        } else {
            val template = existing ?: duplicate?.takeIf { request.allowCopy }
            val record = template?.copy(id = existing?.id ?: "bookmark-${UUID.randomUUID()}", tabConfig = target) ?: Bookmark(
                id = "bookmark-${UUID.randomUUID()}", tabConfig = target, workspaceName = request.workspaceName,
            )
            val updated = current.collections.map { collection ->
                val next = if (collection.id == request.collectionId) {
                    if (collection.bookmarks.any { it.id == record.id }) collection.bookmarks.map { if (it.id == record.id) record else it }
                    else collection.bookmarks + record
                } else collection.bookmarks.filterNot { it.id == record.id }
                collection.copy(bookmarks = next)
            }
            val favorites = if (request.favorite) current.favoriteBookmarkIds + record.id else current.favoriteBookmarkIds - record.id
            Change(current.copy(collections = updated, favoriteBookmarkIds = favorites, defaultFavorite = request.favorite), BookmarkMutationResult(true, bookmarkId = record.id, collectionId = request.collectionId))
        }
    }

    override suspend fun setFavorite(bookmarkId: String, favorite: Boolean, expectedRevision: Long) = change(expectedRevision) { current ->
        require(current.collections.any { c -> c.bookmarks.any { it.id == bookmarkId } }) { "This bookmark no longer exists." }
        Change(current.copy(favoriteBookmarkIds = if (favorite) current.favoriteBookmarkIds + bookmarkId else current.favoriteBookmarkIds - bookmarkId))
    }

    override suspend fun moveBookmark(bookmarkId: String, collectionId: String, expectedRevision: Long) = change(expectedRevision) { current ->
        val record = current.collections.flatMap { it.bookmarks }.find { it.id == bookmarkId } ?: error("This bookmark no longer exists.")
        require(current.collections.any { it.id == collectionId }) { "The destination collection no longer exists." }
        Change(current.copy(collections = current.collections.map { c -> c.copy(bookmarks = c.bookmarks.filterNot { it.id == bookmarkId } + if (c.id == collectionId) listOf(record) else emptyList()) }))
    }

    override suspend fun deleteBookmark(bookmarkId: String, expectedRevision: Long) = change(expectedRevision) { current ->
        val collection = current.collections.find { c -> c.bookmarks.any { it.id == bookmarkId } } ?: error("This bookmark no longer exists.")
        val record = collection.bookmarks.first { it.id == bookmarkId }
        val token = UUID.randomUUID().toString()
        Change(current.copy(
            collections = current.collections.map { it.copy(bookmarks = it.bookmarks.filterNot { b -> b.id == bookmarkId }) },
            favoriteBookmarkIds = current.favoriteBookmarkIds - bookmarkId,
            deleted = current.deleted + DeletedLibraryBookmark(token, collection.id, record, bookmarkId in current.favoriteBookmarkIds, collection.name),
        ), BookmarkMutationResult(true, undoToken = token))
    }

    override suspend fun undo(token: String, expectedRevision: Long) = change(expectedRevision) { current ->
        val deleted = current.deleted.find { it.token == token } ?: error("This undo is no longer available.")
        val restoredCollections = if (current.collections.any { it.id == deleted.collectionId }) current.collections else {
            var name = deleted.collectionName
            var suffix = 2
            while (current.collections.any { it.name == name }) name = "${deleted.collectionName} (restored ${suffix++})"
            current.collections + BookmarkCollection(id = deleted.collectionId, name = name)
        }
        require(current.collections.none { c -> c.bookmarks.any { it.id == deleted.bookmark.id } }) { "A bookmark already uses this identity." }
        Change(current.copy(
            collections = restoredCollections.map { if (it.id == deleted.collectionId) it.copy(bookmarks = it.bookmarks + deleted.bookmark) else it },
            favoriteBookmarkIds = if (deleted.favorite) current.favoriteBookmarkIds + deleted.bookmark.id else current.favoriteBookmarkIds,
            deleted = current.deleted.filterNot { it.token == token },
        ))
    }

    override suspend fun createCollection(name: String, expectedRevision: Long) = change(expectedRevision) { current ->
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Enter a collection name." }
        require(current.collections.none { it.name == normalized }) { "A collection with this name already exists." }
        val id = "collection-${UUID.randomUUID()}"
        Change(current.copy(collections = current.collections + BookmarkCollection(id = id, name = normalized)), BookmarkMutationResult(true, collectionId = id))
    }

    override suspend fun renameCollection(collectionId: String, name: String, expectedRevision: Long) = change(expectedRevision) { current ->
        require(name.isNotBlank()) { "Enter a collection name." }
        require(current.collections.any { it.id == collectionId }) { "This collection no longer exists." }
        require(current.collections.none { it.id != collectionId && it.name == name.trim() }) { "A collection with this name already exists." }
        Change(current.copy(collections = current.collections.map { if (it.id == collectionId) it.copy(name = name.trim()) else it }))
    }

    override suspend fun deleteCollection(collectionId: String, moveToCollectionId: String?, expectedRevision: Long) = change(expectedRevision) { current ->
        val collection = current.collections.find { it.id == collectionId } ?: error("This collection no longer exists.")
        require(current.collections.size > 1) { "Keep at least one collection." }
        require(collection.bookmarks.isEmpty() || moveToCollectionId != null) { "Move the bookmarks to another collection before deleting this collection." }
        require(moveToCollectionId == null || current.collections.any { it.id == moveToCollectionId && it.id != collectionId }) { "Choose a different destination collection." }
        Change(current.copy(collections = current.collections.filterNot { it.id == collectionId }.map {
            if (it.id == moveToCollectionId) it.copy(bookmarks = it.bookmarks + collection.bookmarks) else it
        }, deleted = current.deleted.map { if (it.collectionId == collectionId && moveToCollectionId != null) it.copy(collectionId = moveToCollectionId) else it }))
    }

    /** Legacy import/update APIs preserve the complete existing record, including notes and tags. */
    suspend fun compatibilityUpdate(expectedRevision: Long, transform: (List<BookmarkCollection>) -> List<BookmarkCollection>): BookmarkMutationResult = change(expectedRevision) { current ->
        val next = transform(current.collections)
        val ids = next.flatMap { it.bookmarks }.map { it.id }
        require(ids.toSet().size == ids.size) { "Bookmark IDs must remain unique." }
        Change(current.copy(collections = next, favoriteBookmarkIds = current.favoriteBookmarkIds.intersect(ids.toSet())))
    }

    fun close() { scope.cancel() }
}

internal fun meaningfulTarget(tab: TabConfig): Boolean = when (tab.type) {
    "browser" -> !tab.url.isNullOrBlank() && tab.url != "about:blank" && tab.url != "about:newtab"
    "editor", "jupyter" -> !tab.filePath.isNullOrBlank()
    "terminal" -> true
    else -> false
}

internal fun sameTarget(first: TabConfig, second: TabConfig): Boolean {
    if (first.type != second.type) return false
    return when (first.type) {
        "browser" -> first.url == second.url
        "editor", "jupyter" -> first.filePath == second.filePath
        "terminal" -> {
            val firstDirectory = first.workingDirectory?.takeIf { it.isNotBlank() }
            val directory = second.workingDirectory?.takeIf { it.isNotBlank() }
            val firstCommand = first.initialCommand?.takeIf { it.isNotBlank() }
            val command = second.initialCommand?.takeIf { it.isNotBlank() }
            firstDirectory == directory && firstCommand == command &&
                (directory != null || command != null || first.title == second.title)
        }
        else -> false
    }
}
