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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [BookmarkManager.renameBookmark] and the tab matching it has to keep
 * working.
 *
 * A bookmark's display name is `tabConfig.title`, which is also what
 * [BookmarkManager.isTabBookmarked] used to compare. So the interesting property
 * is not that the rename lands — it is that a renamed bookmark still resolves
 * back to the tab it was saved from, otherwise the panel's star reads
 * "not bookmarked" and bookmarking again silently duplicates it.
 */
class BookmarkManagerRenameTest {
    private lateinit var tempDir: File
    private lateinit var manager: BookmarkManager

    @BeforeTest
    fun setUp() {
        // Never the real ~/Documents/BOSS/bookmarks — see the injectable ctor.
        tempDir = Files.createTempDirectory("bookmark-rename-test").toFile()
        manager = BookmarkManager(BookmarkFileManager(tempDir.absolutePath))

        // The constructor loads asynchronously and that load adds Favorites.
        // Acting before it lands races every assertion below.
        awaitThat("the initial load to settle") {
            manager.collections.value.any { it.name == BookmarkCollection.FAVORITES_NAME }
        }
    }

    @AfterTest
    fun tearDown() {
        // An unclosed save worker sits on Dispatchers.Default for the rest of the
        // JVM and can recreate the directory this is about to delete.
        runBlocking { runCatching { manager.close() } }
        tempDir.deleteRecursively()
    }

    private fun awaitThat(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun favorites(): BookmarkCollection =
        manager.collections.value.first { it.name == BookmarkCollection.FAVORITES_NAME }

    /** Adds [tab] to Favorites and returns the id it was actually stored under. */
    private fun store(tab: TabConfig): Pair<String, String> {
        val id = manager.newBookmarkId()
        manager.addBookmark(
            BookmarkCollection.FAVORITES_NAME,
            Bookmark(id = id, tabConfig = tab, workspaceName = "Test"),
        )
        return favorites().id to id
    }

    private fun browserTab(title: String, url: String) =
        TabConfig(type = "browser", title = title, url = url)

    @Test
    fun `rename replaces the title and leaves the rest of the bookmark alone`() {
        val tab = browserTab("example.com", "https://example.com/")
        val (collectionId, bookmarkId) = store(tab)

        manager.renameBookmark(collectionId, bookmarkId, "Docs home")

        val renamed = assertNotNull(favorites().findBookmark(bookmarkId))
        assertEquals("Docs home", renamed.tabConfig.title)
        assertEquals("https://example.com/", renamed.tabConfig.url)
        assertEquals("Test", renamed.workspaceName)
    }

    @Test
    fun `rename trims surrounding whitespace`() {
        val (collectionId, bookmarkId) = store(browserTab("old", "https://example.com/"))

        manager.renameBookmark(collectionId, bookmarkId, "  Padded  ")

        assertEquals("Padded", favorites().findBookmark(bookmarkId)?.tabConfig?.title)
    }

    @Test
    fun `a blank title is ignored rather than blanking the bookmark`() {
        val (collectionId, bookmarkId) = store(browserTab("Keep me", "https://example.com/"))

        manager.renameBookmark(collectionId, bookmarkId, "   ")

        assertEquals("Keep me", favorites().findBookmark(bookmarkId)?.tabConfig?.title)
    }

    @Test
    fun `renaming an unknown bookmark or collection is a no-op`() {
        val (collectionId, bookmarkId) = store(browserTab("Keep me", "https://example.com/"))
        val before = manager.collections.value

        manager.renameBookmark(collectionId, "no-such-bookmark", "Nope")
        manager.renameBookmark("no-such-collection", bookmarkId, "Nope")

        assertEquals(before, manager.collections.value)
    }

    @Test
    fun `a renamed browser bookmark still matches the tab it was saved from`() {
        val tab = browserTab("example.com", "https://example.com/")
        val (collectionId, bookmarkId) = store(tab)

        manager.renameBookmark(collectionId, bookmarkId, "Something else entirely")

        assertTrue(
            manager.isTabBookmarked(tab),
            "renaming a bookmark detached it from its tab, so the star would read as unbookmarked",
        )
        assertEquals(collectionId to bookmarkId, manager.findBookmarkForTab(tab))
    }

    @Test
    fun `a renamed editor bookmark still matches by file path`() {
        val tab = TabConfig(type = "editor", title = "Main.kt", filePath = "/tmp/project/Main.kt")
        val (collectionId, bookmarkId) = store(tab)

        manager.renameBookmark(collectionId, bookmarkId, "entry point")

        assertEquals(collectionId to bookmarkId, manager.findBookmarkForTab(tab))
    }

    @Test
    fun `a different url is still a different bookmark`() {
        store(browserTab("Site", "https://example.com/"))

        assertTrue(manager.isTabBookmarked(browserTab("Site", "https://example.com/")))
        assertTrue(!manager.isTabBookmarked(browserTab("Site", "https://other.test/")))
    }

    @Test
    fun `terminal tabs, having no url or path, still match on title alone`() {
        // Nothing else distinguishes two terminal tabs, so dropping the title
        // here would make every terminal look bookmarked once one of them was.
        val build = TabConfig(type = "terminal", title = "build")
        store(build)

        assertTrue(manager.isTabBookmarked(build))
        assertNull(manager.findBookmarkForTab(TabConfig(type = "terminal", title = "deploy")))
    }
}
