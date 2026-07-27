package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
        const val BIG = 3000
        const val WRITE_ROUNDS = 30
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
    fun `saves and reloads collections unchanged`() = runTest {
        val original = listOf(collection("Work", 3), collection("Personal", 2))

        assertTrue(fileManager.saveCollections(original))
        val reloaded = fileManager.loadCollections()

        // Compares identity and content rather than whole objects: BookmarkSerializer
        // leaves kotlinx encodeDefaults at false, so defaulted fields — createdAt
        // among them — are never written and are regenerated on load. A whole-object
        // assertion only passes when save and reload land in the same millisecond.
        assertEquals(original.map { it.id }, reloaded.map { it.id })
        assertEquals(original.map { it.name }, reloaded.map { it.name })
        assertEquals(
            original.map { c -> c.bookmarks.map { it.id to it.tabConfig.url } },
            reloaded.map { c -> c.bookmarks.map { it.id to it.tabConfig.url } },
        )
    }

    /** Identity of the file currently at [path], or null if it has none. */
    private fun fileKeyOf(path: java.nio.file.Path): Any? =
        Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()

    @Test
    fun `each save replaces the file rather than truncating it in place`() = runTest {
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

        assertNotNull(firstKey, "filesystem does not expose a file key; cannot verify")
        assertNotEquals(
            firstKey,
            secondKey,
            "collections.json kept its identity across a save, so it was truncated in place " +
                "rather than atomically replaced"
        )
    }

    @Test
    fun `concurrent saves converge on one complete document`() = runTest {
        // Bypasses BookmarkManager's mutex deliberately: even with unsynchronised
        // callers, every write must land whole. Sizes vary so a torn result would
        // show up as a parse failure (empty list) rather than a plausible one.
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
    fun `concurrent saves leave no temp files behind`() = runTest {
        val writes = (1..32).map { size ->
            async { fileManager.saveCollections(listOf(collection("Imported", size))) }
        }
        writes.awaitAll()

        val leftovers = tempDir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue(leftovers.isEmpty(), "left temp files behind: ${leftovers.map { it.name }}")
    }

    @Test
    fun `a large batch round-trips intact`() = runTest {
        // The real import workload: one collection holding hundreds of entries.
        val large = listOf(collection("Imported", 500))

        assertTrue(fileManager.saveCollections(large))
        val reloaded = fileManager.loadCollections()

        assertEquals(500, reloaded.single().bookmarks.size)
    }

    @Test
    fun `an existing file is replaced, not appended to`() = runTest {
        fileManager.saveCollections(listOf(collection("First", 10)))
        fileManager.saveCollections(listOf(collection("Second", 1)))

        val reloaded = fileManager.loadCollections()

        assertEquals(1, reloaded.size)
        assertEquals("Second", reloaded.single().name)
    }
}
