package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * JSON serializer for bookmark-related data structures
 *
 * Handles serialization/deserialization of:
 * - BookmarkCollection lists
 * - FavoriteWorkspace lists
 *
 * Uses kotlinx.serialization with pretty printing and unknown key ignoring
 * for forward/backward compatibility.
 */
internal object BookmarkSerializer {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        // Allow default values for missing fields
        coerceInputValues = true
    }

    /**
     * Serialize a list of bookmark collections to JSON string
     *
     * @param collections List of bookmark collections to serialize
     * @return JSON string representation
     */
    fun serializeCollections(collections: List<BookmarkCollection>): String {
        return json.encodeToString(
            ListSerializer(BookmarkCollection.serializer()),
            collections
        )
    }

    /**
     * Deserialize JSON string to list of bookmark collections
     *
     * @param jsonString JSON string to deserialize
     * @return List of bookmark collections
     * @throws kotlinx.serialization.SerializationException if JSON is invalid
     */
    fun deserializeCollections(jsonString: String): List<BookmarkCollection> {
        return json.decodeFromString(
            ListSerializer(BookmarkCollection.serializer()),
            jsonString
        )
    }

    /**
     * Serialize a list of favorite workspaces to JSON string
     *
     * @param favorites List of favorite workspaces to serialize
     * @return JSON string representation
     */
    fun serializeFavoriteWorkspaces(favorites: List<FavoriteWorkspace>): String {
        return json.encodeToString(
            ListSerializer(FavoriteWorkspace.serializer()),
            favorites
        )
    }

    /**
     * Deserialize JSON string to list of favorite workspaces
     *
     * @param jsonString JSON string to deserialize
     * @return List of favorite workspaces
     * @throws kotlinx.serialization.SerializationException if JSON is invalid
     */
    fun deserializeFavoriteWorkspaces(jsonString: String): List<FavoriteWorkspace> {
        return json.decodeFromString(
            ListSerializer(FavoriteWorkspace.serializer()),
            jsonString
        )
    }
}
