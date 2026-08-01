package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * Implementation of BookmarkDataProvider that wraps BookmarkManager.
 *
 * This is registered via registerPluginAPI() so BossConsole UI can access
 * bookmark functionality through the plugin system. When the plugin is
 * uninstalled, getPluginAPI() returns null and bookmark features gracefully degrade.
 */
internal class BookmarkDataProviderImpl(
    private val bookmarkManager: BookmarkManager
) : BookmarkDataProvider {

    override val collections: StateFlow<List<BookmarkCollection>>
        get() = bookmarkManager.collections

    override val favoriteWorkspaces: StateFlow<List<FavoriteWorkspace>>
        get() = bookmarkManager.favoriteWorkspaces

    // ==================== Bookmark Operations ====================

    /**
     * Note the id you supply is not guaranteed to be the id that gets stored.
     *
     * `Bookmark.generateId()` is millisecond-resolution, so a caller adding two
     * bookmarks in quick succession hands us the same id twice. Rather than let
     * that alias an existing bookmark — which would make `removeBookmark` and
     * `updateBookmark` act on whichever matched first — the manager re-ids the
     * newcomer. Since this returns Unit there is nothing to report it back
     * through, so a caller that mints an id and later calls
     * [updateBookmark]/[removeBookmark] with it may find it no longer resolves.
     * Read the id back from [collections] instead of assuming it survived.
     */
    override fun addBookmark(collectionName: String, bookmark: Bookmark) {
        bookmarkManager.addBookmark(collectionName, bookmark)
    }

    /**
     * Overrides the interface default, which loops [addBookmark] and therefore
     * persists once per bookmark.
     *
     * Note this differs in *behaviour*, not only cost: [addBookmark] silently
     * no-ops on a missing collection, so the default drops the whole batch for
     * a collection that does not exist, while this creates it. The host ensures
     * the collection before calling either way, so the two agree in practice —
     * but a direct caller should know which it is talking to.
     */
    override fun addBookmarks(collectionName: String, bookmarks: List<Bookmark>) {
        bookmarkManager.addBookmarks(collectionName, bookmarks)
    }

    /** Declares the override above, so callers need not infer it reflectively. */
    override val supportsBulkAdd: Boolean get() = true

    override fun removeBookmark(collectionId: String, bookmarkId: String) {
        bookmarkManager.removeBookmark(collectionId, bookmarkId)
    }

    override fun updateBookmark(collectionId: String, bookmark: Bookmark) {
        bookmarkManager.updateBookmark(collectionId, bookmark)
    }

    override fun moveBookmark(bookmarkId: String, fromCollectionId: String, toCollectionId: String) {
        bookmarkManager.moveBookmark(bookmarkId, fromCollectionId, toCollectionId)
    }

    override fun markBookmarkAsAccessed(collectionId: String, bookmarkId: String) {
        bookmarkManager.markBookmarkAsAccessed(collectionId, bookmarkId)
    }

    override fun isTabBookmarked(tabConfig: TabConfig): Boolean {
        return bookmarkManager.isTabBookmarked(tabConfig)
    }

    override fun findBookmarkForTab(tabConfig: TabConfig): Pair<String, String>? {
        return bookmarkManager.findBookmarkForTab(tabConfig)
    }

    // ==================== Collection Operations ====================

    override fun createCollection(name: String): BookmarkCollection {
        return bookmarkManager.createCollection(name)
    }

    override fun deleteCollection(collectionId: String) {
        bookmarkManager.deleteCollection(collectionId)
    }

    override fun renameCollection(collectionId: String, newName: String) {
        bookmarkManager.renameCollection(collectionId, newName)
    }

    // ==================== Favorite Workspace Operations ====================

    override fun addFavoriteWorkspace(workspaceId: String, workspaceName: String) {
        bookmarkManager.addFavoriteWorkspace(workspaceId, workspaceName)
    }

    override fun removeFavoriteWorkspace(workspaceId: String) {
        bookmarkManager.removeFavoriteWorkspace(workspaceId)
    }

    override fun isFavorite(workspaceId: String): Boolean {
        return bookmarkManager.isFavorite(workspaceId)
    }
}
