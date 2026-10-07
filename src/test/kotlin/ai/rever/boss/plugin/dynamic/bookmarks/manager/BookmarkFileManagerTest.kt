package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers the durability of [BookmarkFileManager]'s writes.
 *
 * The failure this guards against: `saveCollections` used to be a bare
 * `File.writeText`, which truncates the target before writing. A bulk import
 * fires hundreds of overlapping saves, so a reader — or a second writer — could
 * observe a half-written `collections.json` and the user would lose every
 * bookmark they had.
 */
class BookmarkFileManagerTest {
    private companion object {
        /** Big enough that a truncate-then-write leaves a wide observable window. */
        const val BIG = 300
        const val WRITE_ROUNDS = 12
    }

    private lateinit var tempDir: File
    private lateinit var fileManager: BookmarkFileManager

    @BeforeTest
    fun setUp() {
        // Never the real ~/Documents/BOSS/bookmarks — see the injectable ctor.
        tempDir = Files.createTempDirectory("bookmark-file-manager-test").toFile()
        fileManager = BookmarkFileManager(tempDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun collection(name: String, bookmarkCount: Int): BookmarkCollection =
        BookmarkCollection(
            id = "collection-$name",
            name = name,
            bookmarks = (0 until bookmarkCount).map { index ->
                Bookmark(
                    id = "$name-bookmark-$index",
                    tabConfig = TabConfig(
                        type = "browser",
                        title = "Site $index",
                        url = "https://example.com/$index"
                    ),
                    workspaceName = ""
                )
            }
        )

    @Test
    fun `saves and reloads collections unchanged`() = runBlocking {
        val original = listOf(collection("Work", 3), collection("Personal", 2))

        assertTrue(fileManager.saveCollections(original))
        val reloaded = fileManager.loadCollections()

        // Whole-object equality, createdAt included: BookmarkSerializer now sets
        // encodeDefaults = true, so a defaulted field is written as of the moment
        // it was actually constructed rather than regenerated from the constructor
        // default on load.
        assertEquals(original, reloaded)
    }

    @Test
    fun `saved JSON explicitly contains every defaulted bookmark and collection field`() = runBlocking {
        val original = collection("Defaults", bookmarkCount = 1)
        assertTrue(fileManager.saveCollections(listOf(original)))

        // Inspect the persisted document: immediate reload equality can hide a
        // missing timestamp when construction and reload share a millisecond.
        val saved = Json.parseToJsonElement(
            tempDir.resolve(BookmarkFileManager.COLLECTIONS_FILE).readText()
        ).jsonArray.single().jsonObject
        assertEquals(setOf("id", "name", "bookmarks", "isFavorite", "createdAt"), saved.keys)
        assertEquals(original.createdAt.toString(), saved.getValue("createdAt").jsonPrimitive.content)
        val bookmark = saved.getValue("bookmarks").jsonArray.single().jsonObject
        assertEquals(
            setOf("id", "tabConfig", "workspaceName", "targetWorkspaceName", "targetPanelId",
                "targetWorkspaces", "notes", "tags", "createdAt", "lastAccessedAt"),
            bookmark.keys,
        )
        assertEquals(original.bookmarks.single().createdAt.toString(), bookmark.getValue("createdAt").jsonPrimitive.content)
        assertEquals(listOf(original), fileManager.loadCollections())
    }

    @Test
    fun `legacy collection timestamps use file modification time until saved`() = runBlocking {
        val file = tempDir.resolve(BookmarkFileManager.COLLECTIONS_FILE)
        val legacy = """[{"id":"old","name":"Old","bookmarks":[{"id":"missing","tabConfig":{"type":"browser","title":"Old"},"workspaceName":"Work"},{"id":"explicit","tabConfig":{"type":"browser","title":"Known"},"workspaceName":"Work","createdAt":123}],"isFavorite":true}]"""
        file.writeText(legacy)
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_600_000_000_000L))
        val modifiedAt = file.lastModified()

        val loaded = fileManager.loadCollections()
        assertEquals(modifiedAt, loaded.single().createdAt)
        assertEquals(modifiedAt, loaded.single().bookmarks.first().createdAt)
        assertEquals(123L, loaded.single().bookmarks.last().createdAt)
        assertEquals(loaded, fileManager.loadCollections())
        assertEquals(legacy, file.readText(), "loading must not rewrite legacy files")
        assertEquals(modifiedAt, file.lastModified())

        assertTrue(fileManager.saveCollections(loaded))
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(modifiedAt + 60_000))
        assertEquals(loaded, fileManager.loadCollections(), "saved timestamps must supersede file time")
    }

    @Test
    fun `legacy favorite timestamps use file modification time and preserve explicit values`() = runBlocking {
        val file = tempDir.resolve(BookmarkFileManager.FAVORITE_WORKSPACES_FILE)
        val legacy = """[{"workspaceId":"old","workspaceName":"Old"},{"workspaceId":"known","workspaceName":"Known","markedAt":123}]"""
        file.writeText(legacy)
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_600_000_000_000L))
        val modifiedAt = file.lastModified()

        val loaded = fileManager.loadFavoriteWorkspaces()
        assertEquals(modifiedAt, loaded.first().markedAt)
        assertEquals(123L, loaded.last().markedAt)
        assertEquals(loaded, fileManager.loadFavoriteWorkspaces())
        assertEquals(legacy, file.readText())
        assertEquals(modifiedAt, file.lastModified())

        assertTrue(fileManager.saveFavoriteWorkspaces(loaded))
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(modifiedAt + 60_000))
        assertEquals(loaded, fileManager.loadFavoriteWorkspaces())
    }

    @Test
    fun `new favorite workspace timestamps are written explicitly and round trip`() = runBlocking {
        val original = listOf(FavoriteWorkspace(workspaceId = "work", workspaceName = "Work"))
        assertTrue(fileManager.saveFavoriteWorkspaces(original))
        val saved = Json.parseToJsonElement(
            tempDir.resolve(BookmarkFileManager.FAVORITE_WORKSPACES_FILE).readText()
        ).jsonArray.single().jsonObject
        assertEquals(original.single().markedAt.toString(), saved.getValue("markedAt").jsonPrimitive.content)
        assertEquals(original, fileManager.loadFavoriteWorkspaces())
    }

    /** Identity of the file currently at [path], or null if it has none. */
    private fun fileKeyOf(path: java.nio.file.Path): Any? =
        Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()

    @Test
    fun `each save replaces the file rather than truncating it in place`() = runBlocking {
        // This is the whole point of the fix, stated as something exact rather
        // than as a race to lose. `File.writeText` opens the existing target and
        // truncates it, so the inode survives and there is a window where the
        // file on disk is short. An atomic move installs the temp file instead,
        // so the destination's identity changes on every write and no reader can
        // ever observe a partial document.
        //
        // Asserting on inode identity is deterministic; asserting that a
        // concurrent reader *catches* the truncation is not — that version of
        // this test passed against the bug roughly one run in three.
        val collectionsPath = tempDir.toPath().resolve(BookmarkFileManager.COLLECTIONS_FILE)

        fileManager.saveCollections(listOf(collection("First", 50)))
        val firstKey = fileKeyOf(collectionsPath)

        fileManager.saveCollections(listOf(collection("Second", 50)))
        val secondKey = fileKeyOf(collectionsPath)

        // Windows has no file key; the property is real there but unobservable,
        // so skip rather than fail a contributor's suite for it.
        assumeTrue(firstKey != null, "filesystem does not expose a file key")
        assertNotEquals(
            firstKey,
            secondKey,
            "collections.json kept its identity across a save, so it was truncated in place " +
                "rather than atomically replaced"
        )
    }

    @Test
    fun `concurrent saves converge on one complete document`() = runBlocking {
        // Bypasses BookmarkManager's save scheduling deliberately: even with
        // unsynchronised callers, every write must land whole. A torn result
        // shows up as a parse failure (empty list) rather than a plausible one.
        val writes = (1..WRITE_ROUNDS).map { round ->
            async(Dispatchers.IO) {
                fileManager.saveCollections(listOf(collection("Imported-$round", BIG)))
            }
        }
        writes.awaitAll()

        val reloaded = fileManager.loadCollections()

        // loadCollections swallows parse errors and returns emptyList().
        assertEquals(1, reloaded.size, "collections.json did not survive concurrent writes")
        assertEquals(BIG, reloaded.single().bookmarks.size, "the surviving document was partial")
    }

    @Test
    fun `concurrent saves leave no temp files behind`() = runBlocking {
        val writes = (1..32).map { size ->
            async { fileManager.saveCollections(listOf(collection("Imported", size))) }
        }
        writes.awaitAll()

        val leftovers = tempDir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue(leftovers.isEmpty(), "left temp files behind: ${leftovers.map { it.name }}")
    }

    @Test
    fun `a large batch round-trips intact`() = runBlocking {
        // The real import workload: one collection holding hundreds of entries.
        val large = listOf(collection("Imported", 500))

        assertTrue(fileManager.saveCollections(large))
        val reloaded = fileManager.loadCollections()

        assertEquals(500, reloaded.single().bookmarks.size)
    }

    @Test
    fun `an existing file is replaced, not appended to`() = runBlocking {
        fileManager.saveCollections(listOf(collection("First", 10)))
        fileManager.saveCollections(listOf(collection("Second", 1)))

        val reloaded = fileManager.loadCollections()

        assertEquals(1, reloaded.size)
        assertEquals("Second", reloaded.single().name)
    }
}
