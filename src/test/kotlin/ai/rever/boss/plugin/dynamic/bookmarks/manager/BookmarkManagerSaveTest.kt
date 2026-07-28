package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import kotlinx.coroutines.delay
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
        /** Held closed to keep the initial load in flight for as long as a test needs. */
        private val loadGate: java.util.concurrent.CountDownLatch? = null,
    ) : BookmarkFileManager(directory) {
        val collectionSaves = AtomicInteger(0)
        val favoriteSaves = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        private val inFlight = AtomicInteger(0)

        override suspend fun saveCollections(collections: List<BookmarkCollection>): Boolean {
            val now = inFlight.incrementAndGet()
            maxConcurrent.updateAndGet { if (now > it) now else it }
            collectionSaves.incrementAndGet()
            return try {
                // Widen the window an overlapping writer would land in. delay,
                // not Thread.sleep: this runs on a Dispatchers.Default worker.
                delay(20)
                super.saveCollections(collections)
            } finally {
                // finally, so a throwing save can't leave inFlight elevated and
                // fail every later test for the wrong reason.
                inFlight.decrementAndGet()
            }
        }

        override suspend fun saveFavoriteWorkspaces(favorites: List<FavoriteWorkspace>): Boolean {
            favoriteSaves.incrementAndGet()
            return super.saveFavoriteWorkspaces(favorites)
        }

        override suspend fun loadCollections(): List<BookmarkCollection> {
            loadGate?.await()
            return super.loadCollections()
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

    /**
     * Wait until [condition] holds.
     *
     * Polling with a deadline rather than a fixed sleep: a wall-clock budget
     * turns "a burst collapses" into a test that flakes on a loaded runner.
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

    /**
     * Wait until no save has happened for a short quiet period.
     *
     * A fixed sleep made count assertions flaky: the initial load's own save
     * could land after the baseline was read.
     */
    private fun settle(quietMillis: Long = 250) {
        val deadline = System.currentTimeMillis() + 5_000
        var last = probe.collectionSaves.get()
        var quietSince = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            val now = probe.collectionSaves.get()
            if (now != last) {
                last = now
                quietSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - quietSince >= quietMillis) {
                return
            }
        }
    }

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

        // Coalescing must never drop the final state: the consumer reads the
        // snapshot at write time, so the file converges on the latest.
        awaitThat("the final mutation to be written") {
            runBlocking { BookmarkFileManager(tempDir.absolutePath).loadCollections() }
                .any { it.name == "Collection 19" }
        }
    }

    @Test
    fun `close drains a pending save before the workers stop`() {
        // Conflation makes the tail write easy to lose: a request that arrives
        // while a save is in flight is only written by the next drain, and
        // nothing drains after shutdown unless close() does it.
        manager.createCollection("Written At Shutdown")
        runBlocking { manager.close() }

        val reloaded = runBlocking { BookmarkFileManager(tempDir.absolutePath).loadCollections() }
        assertTrue(
            reloaded.any { it.name == "Written At Shutdown" },
            "close() returned before the pending save was flushed",
        )
    }

    @Test
    fun `close stops the save workers`() {
        runBlocking { manager.close() }
        val after = probe.collectionSaves.get()

        // A mutation after close must not be picked up — the workers are gone,
        // which is what lets the plugin classloader be collected.
        manager.createCollection("After Close")
        settle()

        assertEquals(after, probe.collectionSaves.get(), "a worker survived close()")
    }

    @Test
    fun `a favourite added during the initial load is not clobbered`() {
        // Favourites take the same load-merge path as collections; assigning the
        // loaded list would drop this and then persist the post-load snapshot.
        val slow = ProbeFileManager(tempDir.absolutePath)
        val racing = BookmarkManager(slow)
        racing.addFavoriteWorkspace("workspace-race", "Racing Workspace")

        awaitThat("the favourite to survive the load") {
            racing.favoriteWorkspaces.value.any { it.workspaceId == "workspace-race" }
        }
        runBlocking { racing.close() }
    }

    @Test
    fun `bookmarks added during the load window survive it`() {
        // Deterministic, not a race: the loader is held until the mutations are
        // in. Merging by collection name alone filtered the pending copy out
        // wholesale, so its bookmarks went with it — and an existing collection
        // is precisely where an import lands.
        val seeded = BookmarkFileManager(tempDir.absolutePath)
        runBlocking {
            seeded.saveCollections(
                listOf(
                    BookmarkCollection(
                        id = "fav",
                        name = BookmarkCollection.FAVORITES_NAME,
                        isFavorite = true,
                        bookmarks = listOf(bookmark(1)),
                    ),
                ),
            )
        }

        val gate = java.util.concurrent.CountDownLatch(1)
        val racing = BookmarkManager(ProbeFileManager(tempDir.absolutePath, loadGate = gate))

        // Both land while the load is still blocked.
        racing.createCollection(BookmarkCollection.FAVORITES_NAME)
        racing.addBookmark(BookmarkCollection.FAVORITES_NAME, bookmark(2))
        gate.countDown()

        awaitThat("both the loaded and the in-flight bookmark to be present") {
            val favorites = racing.collections.value.filter { it.name == BookmarkCollection.FAVORITES_NAME }
            favorites.size == 1 &&
                favorites.single().bookmarks.map { it.id }.containsAll(listOf("bookmark-1", "bookmark-2"))
        }
        runBlocking { racing.close() }
    }

    private fun bookmark(index: Int) =
        ai.rever.boss.plugin.bookmark.Bookmark(
            id = "bookmark-$index",
            tabConfig =
                ai.rever.boss.plugin.workspace.TabConfig(
                    type = "browser",
                    title = "Site $index",
                    url = "https://site$index.test/",
                ),
            workspaceName = "",
        )

    @Test
    fun `favorite workspaces persist through their own channel`() {
        manager.addFavoriteWorkspace("workspace-1", "Workspace One")
        settle()

        val reloaded = runBlocking { BookmarkFileManager(tempDir.absolutePath).loadFavoriteWorkspaces() }
        assertEquals(listOf("workspace-1"), reloaded.map { it.workspaceId })
    }
}
