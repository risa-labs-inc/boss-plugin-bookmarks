package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers id uniqueness, which the bookmarks panel depends on to render at all.
 *
 * The failure this guards against: `BookmarkCollection.generateId()` is
 * `"collection-<epochMillis>"`, so a browser import — which creates "Bookmarks
 * Bar" and "Other Bookmarks" back to back — gave both the same id. The panel
 * uses the id as a LazyColumn item key, two items under one key share a single
 * LayoutNode, and expanding one of them crashed Compose with
 * `IllegalStateException: layout state is not idle before measure starts`.
 *
 * Two properties are needed: newly minted ids never collide, and ids that older
 * versions already wrote to disk get repaired on load.
 */
class BookmarkManagerIdTest {
    private lateinit var tempDir: File
    private lateinit var fileManager: BookmarkFileManager
    private lateinit var manager: BookmarkManager

    @BeforeTest
    fun setUp() {
        // Never the real ~/Documents/BOSS/bookmarks — see the injectable ctor.
        tempDir = Files.createTempDirectory("bookmark-manager-id-test").toFile()
        fileManager = BookmarkFileManager(tempDir.absolutePath)
        manager = BookmarkManager(fileManager)
    }

    @AfterTest
    fun tearDown() {
        // An unclosed save worker sits on Dispatchers.Default for the rest of the
        // JVM and can recreate the directory this is about to delete.
        runBlocking { runCatching { manager.close() } }
        tempDir.deleteRecursively()
    }

    private fun bookmark(id: String, title: String = id): Bookmark =
        Bookmark(
            id = id,
            tabConfig = TabConfig(type = "browser", title = title, url = "https://example.com/$id"),
            workspaceName = "Test",
        )

    private fun collection(id: String, name: String, bookmarks: List<Bookmark> = emptyList()) =
        BookmarkCollection(id = id, name = name, bookmarks = bookmarks)

    @Test
    fun `collections created in the same millisecond get different ids`() {
        // The original bug in one line: this loop is what a bookmarks import
        // does, and it used to hand out one id for the whole burst.
        val created = (0 until 50).map { manager.createCollection("Collection $it") }

        val ids = created.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "createCollection minted a duplicate id")
    }

    @Test
    fun `bulk add into new collections gives each a different id`() {
        manager.addBookmarks("Bookmarks Bar", listOf(bookmark("b1")))
        manager.addBookmarks("Other Bookmarks", listOf(bookmark("b2")))

        val ids = manager.collections.value.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "addBookmarks minted a duplicate collection id")
    }

    @Test
    fun `duplicate collection ids on disk are renumbered, first one keeps its id`() {
        val repaired =
            manager.withDistinctIds(
                listOf(
                    collection("collection-1785194024699", "Bookmarks Bar"),
                    collection("collection-1785194024699", "Other Bookmarks"),
                ),
            )

        assertEquals(
            listOf("collection-1785194024699", "collection-1785194024699-2"),
            repaired.map { it.id },
        )
        // Renumbering must not reorder or rename: the user's two folders stay two
        // folders, in the order they were written.
        assertEquals(listOf("Bookmarks Bar", "Other Bookmarks"), repaired.map { it.name })
    }

    @Test
    fun `duplicate bookmark ids are renumbered across collections`() {
        val repaired =
            manager.withDistinctIds(
                listOf(
                    collection("c1", "One", listOf(bookmark("bookmark-1"), bookmark("bookmark-1"))),
                    collection("c2", "Two", listOf(bookmark("bookmark-1"))),
                ),
            )

        val bookmarkIds = repaired.flatMap { c -> c.bookmarks.map { it.id } }
        assertEquals(
            listOf("bookmark-1", "bookmark-1-2", "bookmark-1-3"),
            bookmarkIds,
            "bookmark ids must be unique across all collections, not just within one",
        )
    }

    @Test
    fun `a renumbered id never lands on one the file already uses`() {
        val repaired =
            manager.withDistinctIds(
                listOf(
                    collection("c", "First"),
                    // The naive "$id-2" would collide with this.
                    collection("c-2", "Taken"),
                    collection("c", "Needs a new id"),
                ),
            )

        assertEquals(listOf("c", "c-2", "c-3"), repaired.map { it.id })
    }

    @Test
    fun `a file with unique ids is returned untouched`() {
        val original =
            listOf(
                collection("c1", "One", listOf(bookmark("b1"))),
                collection("c2", "Two", listOf(bookmark("b2"))),
            )

        val repaired = manager.withDistinctIds(original)

        // Same instances, not merely equal ones: loadAllData compares the result
        // against what was read to decide whether to rewrite the file, so an
        // untouched load must not schedule a save.
        assertEquals(original.size, repaired.size)
        original.indices.forEach { assertSame(original[it], repaired[it]) }
    }

    @Test
    fun `repairing is idempotent`() {
        val once =
            manager.withDistinctIds(
                listOf(
                    collection("c", "First"),
                    collection("c", "Second"),
                ),
            )

        val twice = manager.withDistinctIds(once)

        assertEquals(once, twice, "a second load must not renumber again")
    }

    @Test
    fun `loading a file with colliding ids repairs it and writes the repair back`() {
        // Its own directory, untouched by setUp's manager: that one's initial
        // load also saves (it creates Favorites), and on a shared directory that
        // write races the seed file below and can clobber it.
        val seedDir = Files.createTempDirectory("bookmark-manager-id-test-seed").toFile()
        val seedFiles = BookmarkFileManager(seedDir.absolutePath)
        runBlocking {
            seedFiles.saveCollections(
                listOf(
                    collection("collection-dup", "Bookmarks Bar", listOf(bookmark("b1"))),
                    collection("collection-dup", "Other Bookmarks", listOf(bookmark("b2"))),
                ),
            )
        }

        // A fresh manager runs loadAllData against the file written above.
        val reloaded = BookmarkManager(seedFiles)
        try {
            awaitThat("the repaired collections to be persisted") {
                val onDisk = runBlocking { seedFiles.loadCollections() }
                val ids = onDisk.map { it.id }
                onDisk.size >= 2 && ids.size == ids.toSet().size
            }

            val onDisk = runBlocking { seedFiles.loadCollections() }
            val duplicates = onDisk.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }
            assertTrue(duplicates.isEmpty(), "collections.json still holds duplicate ids: $duplicates")

            // Both folders survive the repair — an id-keyed merge would have
            // silently dropped one of them.
            assertTrue(
                onDisk.any { it.name == "Bookmarks Bar" } && onDisk.any { it.name == "Other Bookmarks" },
                "repair lost a collection: ${onDisk.map { it.name }}",
            )
            assertNotEquals(
                onDisk.first { it.name == "Bookmarks Bar" }.id,
                onDisk.first { it.name == "Other Bookmarks" }.id,
            )
        } finally {
            runBlocking { runCatching { reloaded.close() } }
            seedDir.deleteRecursively()
        }
    }

    /**
     * Wait until [condition] holds.
     *
     * The load and its save are asynchronous; a fixed sleep would flake on a
     * loaded runner.
     */
    private fun awaitThat(
        what: String,
        timeoutMillis: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $what")
    }
}
