package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Paths

/**
 * Manages file-based bookmark storage.
 *
 * Stores bookmark data in ~/Documents/BOSS/bookmarks/:
 * - collections.json: All bookmark collections
 * - favorite-workspaces.json: Favorite workspace IDs
 *
 * This is a JVM-only implementation for the bookmarks plugin.
 */
internal class BookmarkFileManager {
    private val logger = BossLogger.forComponent("BookmarkFileManager")

    companion object {
        /** Bookmark collections file name */
        const val COLLECTIONS_FILE = "collections.json"

        /** Favorite workspaces file name */
        const val FAVORITE_WORKSPACES_FILE = "favorite-workspaces.json"

        /** Default bookmarks directory name under Documents */
        private const val BOOKMARKS_DIR = "BOSS/bookmarks"
    }

    /**
     * Get the bookmarks directory path.
     *
     * @return Full path to bookmarks directory (e.g., ~/Documents/BOSS/bookmarks/)
     */
    fun getBookmarksDirectory(): String {
        val userHome = System.getProperty("user.home")
        return Paths.get(userHome, "Documents", BOOKMARKS_DIR).toString()
    }

    /**
     * Ensure the bookmarks directory exists.
     *
     * @return true if directory exists or was created successfully
     */
    suspend fun ensureBookmarksDirectory(): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(getBookmarksDirectory())
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir.exists() && dir.isDirectory
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Error ensuring bookmarks directory", error = e)
            false
        }
    }

    /**
     * Save bookmark collections to file.
     *
     * Saves all collections to collections.json.
     *
     * @param collections List of bookmark collections to save
     * @return true if saved successfully
     */
    suspend fun saveCollections(collections: List<BookmarkCollection>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ensureBookmarksDirectory()

                val filePath = Paths.get(getBookmarksDirectory(), COLLECTIONS_FILE).toString()
                val file = File(filePath)

                // Serialize collections
                val json = BookmarkSerializer.serializeCollections(collections)

                // Write to file
                file.writeText(json)

                true
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Error saving collections", error = e)
                false
            }
        }

    /**
     * Load bookmark collections from file.
     *
     * Loads from collections.json.
     *
     * @return List of bookmark collections, empty list if file doesn't exist
     */
    suspend fun loadCollections(): List<BookmarkCollection> =
        withContext(Dispatchers.IO) {
            try {
                val filePath = Paths.get(getBookmarksDirectory(), COLLECTIONS_FILE).toString()
                val file = File(filePath)

                if (!file.exists()) {
                    return@withContext emptyList()
                }

                val json = file.readText()
                BookmarkSerializer.deserializeCollections(json)
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Error loading collections", error = e)
                emptyList()
            }
        }

    /**
     * Save favorite workspaces to file.
     *
     * Saves to favorite-workspaces.json.
     *
     * @param favorites List of favorite workspaces to save
     * @return true if saved successfully
     */
    suspend fun saveFavoriteWorkspaces(favorites: List<FavoriteWorkspace>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ensureBookmarksDirectory()

                val filePath = Paths.get(getBookmarksDirectory(), FAVORITE_WORKSPACES_FILE).toString()
                val file = File(filePath)

                // Serialize favorite workspaces
                val json = BookmarkSerializer.serializeFavoriteWorkspaces(favorites)

                // Write to file
                file.writeText(json)

                true
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Error saving favorite workspaces", error = e)
                false
            }
        }

    /**
     * Load favorite workspaces from file.
     *
     * Loads from favorite-workspaces.json.
     *
     * @return List of favorite workspaces, empty list if file doesn't exist
     */
    suspend fun loadFavoriteWorkspaces(): List<FavoriteWorkspace> =
        withContext(Dispatchers.IO) {
            try {
                val filePath = Paths.get(getBookmarksDirectory(), FAVORITE_WORKSPACES_FILE).toString()
                val file = File(filePath)

                if (!file.exists()) {
                    return@withContext emptyList()
                }

                val json = file.readText()
                BookmarkSerializer.deserializeFavoriteWorkspaces(json)
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Error loading favorite workspaces", error = e)
                emptyList()
            }
        }
}
