package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.bookmark.*
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Old integrations keep their API, but share the same durable records as the new UI. */
internal class LibraryCompatibilityProvider(
    private val library: BookmarkLibraryStore,
    private val spaces: BookmarkManager,
) : BookmarkDataProvider {
    override val collections: StateFlow<List<BookmarkCollection>> get() = library.collections
    override val favoriteWorkspaces: StateFlow<List<FavoriteWorkspace>> get() = spaces.favoriteWorkspaces
    override val supportsBulkAdd: Boolean get() = true

    private fun mutate(block: suspend () -> BookmarkMutationResult) = runBlocking(Dispatchers.IO) {
        if (!library.state.value.ready && library.state.value.error == null) withTimeout(10_000) {
            library.state.first { it.ready || it.error != null }
        }
        val result = block()
        check(result.success) { result.message ?: "Bookmark operation failed." }
        result
    }
    private fun update(transform: (List<BookmarkCollection>) -> List<BookmarkCollection>) =
        mutate { library.compatibilityUpdate(library.state.value.revision, transform) }

    override fun addBookmark(collectionName: String, bookmark: Bookmark) {
        update { current ->
            require(current.any { it.name == collectionName }) { "No collection named $collectionName." }
            val ids = current.flatMap { it.bookmarks }.map { it.id }.toSet()
            val stored = if (bookmark.id in ids) bookmark.copy(id = "bookmark-${UUID.randomUUID()}") else bookmark
            val index = current.indexOfFirst { it.name == collectionName }
            current.mapIndexed { i, collection -> if (i == index) collection.copy(bookmarks = collection.bookmarks + stored) else collection }
        }
    }
    override fun addBookmarks(collectionName: String, bookmarks: List<Bookmark>) {
        if (bookmarks.isEmpty()) return
        update { current ->
            val base = if (current.any { it.name == collectionName }) current else current + BookmarkCollection(id = "collection-${UUID.randomUUID()}", name = collectionName)
            val ids = base.flatMap { it.bookmarks }.map { it.id }.toMutableSet()
            val stored = bookmarks.map { b -> if (ids.add(b.id)) b else b.copy(id = "bookmark-${UUID.randomUUID()}").also { ids.add(it.id) } }
            val index = base.indexOfFirst { it.name == collectionName }
            base.mapIndexed { i, c -> if (i == index) c.copy(bookmarks = c.bookmarks + stored) else c }
        }
    }
    override fun removeBookmark(collectionId: String, bookmarkId: String) {
        require(collections.value.any { it.id == collectionId && it.bookmarks.any { b -> b.id == bookmarkId } }) { "Bookmark not found in this collection." }
        mutate { library.deleteBookmark(bookmarkId, library.state.value.revision) }
    }
    override fun updateBookmark(collectionId: String, bookmark: Bookmark) {
        update { current ->
            require(current.any { it.id == collectionId && it.bookmarks.any { b -> b.id == bookmark.id } }) { "Bookmark no longer exists." }
            current.map { c -> if (c.id == collectionId) c.copy(bookmarks = c.bookmarks.map { if (it.id == bookmark.id) bookmark else it }) else c }
        }
    }
    override fun moveBookmark(bookmarkId: String, fromCollectionId: String, toCollectionId: String) {
        require(collections.value.any { it.id == fromCollectionId && it.bookmarks.any { b -> b.id == bookmarkId } }) { "Bookmark not found in this collection." }
        mutate { library.moveBookmark(bookmarkId, toCollectionId, library.state.value.revision) }
    }
    override fun markBookmarkAsAccessed(collectionId: String, bookmarkId: String) {
        update { current -> current.map { c -> if (c.id == collectionId) c.copy(bookmarks = c.bookmarks.map { if (it.id == bookmarkId) it.markAsAccessed() else it }) else c } }
    }
    override fun isTabBookmarked(tabConfig: TabConfig) = findBookmarkForTab(tabConfig) != null
    override fun findBookmarkForTab(tabConfig: TabConfig): Pair<String, String>? {
        collections.value.forEach { c -> c.bookmarks.firstOrNull { sameTarget(it.tabConfig, tabConfig) }?.let { return c.id to it.id } }
        return null
    }
    override fun createCollection(name: String): BookmarkCollection {
        val result = mutate { library.createCollection(name, library.state.value.revision) }
        return collections.value.first { it.id == result.collectionId }
    }
    override fun deleteCollection(collectionId: String) { mutate { library.deleteCollection(collectionId, null, library.state.value.revision) } }
    override fun renameCollection(collectionId: String, newName: String) { mutate { library.renameCollection(collectionId, newName, library.state.value.revision) } }
    override fun addFavoriteWorkspace(workspaceId: String, workspaceName: String) = spaces.addFavoriteWorkspace(workspaceId, workspaceName)
    override fun removeFavoriteWorkspace(workspaceId: String) = spaces.removeFavoriteWorkspace(workspaceId)
    override fun isFavorite(workspaceId: String) = spaces.isFavorite(workspaceId)
}
