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

    override fun addBookmark(collectionName: String, bookmark: Bookmark) {
        bookmarkManager.addBookmark(collectionName, bookmark)
    }

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
