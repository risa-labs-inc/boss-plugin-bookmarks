package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [BookmarkManager]'s save scheduling — the half of the atomic-write
 * work that lives above the filesystem.
 *
 * Two properties matter and neither is visible from [BookmarkFileManagerTest]:
 * saves never overlap, and a burst of mutations collapses instead of writing
 * once per mutation.
 */
class BookmarkManagerSaveTest {
    private lateinit var tempDir: File

    /** Records overlap and call counts, and makes each save take a moment. */
    private class ProbeFileManager(
        directory: String,
    ) : BookmarkFileManager(directory) {
        val collectionSaves = AtomicInteger(0)
        val favoriteSaves = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        private val inFlight = AtomicInteger(0)

        override suspend fun saveCollections(collections: List<BookmarkCollection>): Boolean {
            val now = inFlight.incrementAndGet()
            maxConcurrent.updateAndGet { if (now > it) now else it }
            collectionSaves.incrementAndGet()
            // Widen the window an overlapping writer would land in.
            Thread.sleep(20)
            val result = super.saveCollections(collections)
            inFlight.decrementAndGet()
            return result
        }

        override suspend fun saveFavoriteWorkspaces(favorites: List<FavoriteWorkspace>): Boolean {
            favoriteSaves.incrementAndGet()
            return super.saveFavoriteWorkspaces(favorites)
        }
    }

    private lateinit var probe: ProbeFileManager
    private lateinit var manager: BookmarkManager

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("bookmark-manager-save-test").toFile()
        probe = ProbeFileManager(tempDir.absolutePath)
        manager = BookmarkManager(probe)
        settle()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Saves are asynchronous; give the workers time to drain. */
    private fun settle(millis: Long = 400) = Thread.sleep(millis)

    @Test
    fun `a burst of mutations never overlaps two saves`() {
        repeat(40) { manager.createCollection("Collection $it") }
        settle()

        assertEquals(1, probe.maxConcurrent.get(), "two saves ran at once")
    }

    @Test
    fun `a burst of mutations collapses into far fewer writes`() {
        val mutations = 40
        val before = probe.collectionSaves.get()

        repeat(mutations) { manager.createCollection("Collection $it") }
        settle()

        val writes = probe.collectionSaves.get() - before
        // Conflation means the exact count is timing-dependent, but writing
        // once per mutation — the behaviour this replaced — is what must not
        // happen. Generous bound so the test isn't itself a race.
        assertTrue(writes < mutations / 2, "expected coalescing, got $writes writes for $mutations mutations")
    }

    @Test
    fun `the last mutation always reaches disk`() {
        repeat(20) { manager.createCollection("Collection $it") }
        settle()

        // Coalescing must never drop the final state: the consumer reads the
        // snapshot at write time, so the file converges on the latest.
        val reloaded = runBlocking { BookmarkFileManager(tempDir.absolutePath).loadCollections() }
        assertTrue(
            reloaded.any { it.name == "Collection 19" },
            "the final mutation was conflated away instead of being written",
        )
    }

    @Test
    fun `favorite workspaces persist through their own channel`() {
        manager.addFavoriteWorkspace("workspace-1", "Workspace One")
        settle()

        val reloaded = runBlocking { BookmarkFileManager(tempDir.absolutePath).loadFavoriteWorkspaces() }
        assertEquals(listOf("workspace-1"), reloaded.map { it.workspaceId })
    }
}
