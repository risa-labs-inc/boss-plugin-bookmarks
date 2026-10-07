package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
        // Persist defaults explicitly, including timestamps. A computed default is
        // re-evaluated during encoding; if it matches, omitting it would cause the
        // timestamp to be regenerated on a later load.
        encodeDefaults = true
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
     * @param legacyTimestamp Stable file timestamp used only for absent creation timestamp keys
     * @return List of bookmark collections
     * @throws kotlinx.serialization.SerializationException if JSON is invalid
     */
    fun deserializeCollections(jsonString: String, legacyTimestamp: Long? = null): List<BookmarkCollection> {
        if (legacyTimestamp == null) {
            return json.decodeFromString(ListSerializer(BookmarkCollection.serializer()), jsonString)
        }
        val document = json.parseToJsonElement(jsonString)
        val normalized = mapArray(document) { collection ->
            val timestamped = withMissingTimestamp(collection, "createdAt", legacyTimestamp)
            if (timestamped is JsonObject && "bookmarks" in timestamped) {
                JsonObject(timestamped + ("bookmarks" to mapArray(timestamped.getValue("bookmarks")) {
                    withMissingTimestamp(it, "createdAt", legacyTimestamp)
                }))
            } else timestamped
        }
        return json.decodeFromJsonElement(ListSerializer(BookmarkCollection.serializer()), normalized)
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
     * @param legacyTimestamp Stable file timestamp used only for absent creation timestamp keys
     * @return List of favorite workspaces
     * @throws kotlinx.serialization.SerializationException if JSON is invalid
     */
    fun deserializeFavoriteWorkspaces(jsonString: String, legacyTimestamp: Long? = null): List<FavoriteWorkspace> {
        if (legacyTimestamp == null) {
            return json.decodeFromString(ListSerializer(FavoriteWorkspace.serializer()), jsonString)
        }
        val normalized = mapArray(json.parseToJsonElement(jsonString)) {
            withMissingTimestamp(it, "markedAt", legacyTimestamp)
        }
        return json.decodeFromJsonElement(ListSerializer(FavoriteWorkspace.serializer()), normalized)
    }

    // A legacy file's modification time is a stable approximation, not its true
    // creation time. Only fill absent keys; explicit timestamps remain authoritative.
    // Leave malformed structures intact so normal decoding still rejects them.
    private fun withMissingTimestamp(element: JsonElement, key: String, timestamp: Long): JsonElement =
        if (element is JsonObject && key !in element) {
            JsonObject(element + (key to JsonPrimitive(timestamp)))
        } else element

    private fun mapArray(element: JsonElement, transform: (JsonElement) -> JsonElement): JsonElement =
        if (element is JsonArray) JsonArray(element.map(transform)) else element
}
