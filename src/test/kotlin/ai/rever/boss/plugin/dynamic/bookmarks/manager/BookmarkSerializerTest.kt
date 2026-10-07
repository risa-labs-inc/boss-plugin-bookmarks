package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlin.test.assertTrue

class BookmarkSerializerTest {
    @Test
    fun `legacy documents with omitted defaults remain readable`() {
        val legacy = """[{"id":"legacy","name":"Old","bookmarks":[{"id":"bookmark","tabConfig":{"type":"browser","title":"Old tab"},"workspaceName":"Work"}]}]"""
        val loaded = BookmarkSerializer.deserializeCollections(legacy)
        assertEquals("legacy", loaded.single().id)
        assertEquals("Work", loaded.single().bookmarks.single().workspaceName)
        assertTrue(loaded.single().bookmarks.single().tags.isEmpty())
        assertEquals(loaded, BookmarkSerializer.deserializeCollections(BookmarkSerializer.serializeCollections(loaded)))
    }

    @Test
    fun `legacy timestamp fallback preserves explicit collection timestamp and tab fields`() {
        val document = """[{"id":"old","name":"Old","createdAt":42,"bookmarks":[{"id":"bookmark","tabConfig":{"type":"browser","title":"Old","url":"https://example.com/createdAt"},"workspaceName":"Work"}]}]"""
        val loaded = BookmarkSerializer.deserializeCollections(document, legacyTimestamp = 100)
        assertEquals(42L, loaded.single().createdAt)
        assertEquals(100L, loaded.single().bookmarks.single().createdAt)
        assertEquals("https://example.com/createdAt", loaded.single().bookmarks.single().tabConfig.url)
    }

    @Test
    fun `timestamp fallback does not conceal malformed legacy documents`() {
        assertFailsWith<SerializationException> {
            BookmarkSerializer.deserializeCollections("""[{"id":"old","name":"Old","bookmarks":{}}]""", 100)
        }
        assertFailsWith<SerializationException> {
            BookmarkSerializer.deserializeFavoriteWorkspaces("""[{"workspaceId":"old","workspaceName":"Old","markedAt":"invalid"}]""", 100)
        }
    }

    @Test
    fun `shared serializer persists favorite workspace timestamp and reads legacy favorites`() {
        val favorite = FavoriteWorkspace(workspaceId = "work", workspaceName = "Work")
        val encoded = BookmarkSerializer.serializeFavoriteWorkspaces(listOf(favorite))
        val saved = Json.parseToJsonElement(encoded).jsonArray.single().jsonObject
        assertEquals(favorite.markedAt.toString(), saved.getValue("markedAt").jsonPrimitive.content)
        assertEquals(listOf(favorite), BookmarkSerializer.deserializeFavoriteWorkspaces(encoded))
        val legacy = BookmarkSerializer.deserializeFavoriteWorkspaces("""[{"workspaceId":"work","workspaceName":"Work"}]""")
        assertEquals("work", legacy.single().workspaceId)
        assertEquals(legacy, BookmarkSerializer.deserializeFavoriteWorkspaces(BookmarkSerializer.serializeFavoriteWorkspaces(legacy)))
    }
}
