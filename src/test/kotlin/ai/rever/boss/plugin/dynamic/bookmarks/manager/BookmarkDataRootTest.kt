package ai.rever.boss.plugin.dynamic.bookmarks.manager

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookmarkDataRootTest {
    @Test
    fun `default directory is contained beneath boss`() {
        val home = createTempDirectory("bookmark-home")
        val actual = java.nio.file.Path.of(BookmarkFileManager.defaultBookmarksDirectory(home.toString()))

        assertTrue(actual.startsWith(home.resolve(".boss")))
        assertEquals(
            home.resolve(".boss/plugin-data/ai.rever.boss.plugin.dynamic.bookmarks/bookmarks"),
            actual,
        )
    }

    @Test
    fun `legacy files are copied without deleting the source`() {
        val home = createTempDirectory("bookmark-migration")
        val legacy = home.resolve("Documents/BOSS/bookmarks").createDirectories()
        legacy.resolve(BookmarkFileManager.COLLECTIONS_FILE).writeText("legacy")

        val destination = java.nio.file.Path.of(BookmarkFileManager.defaultBookmarksDirectory(home.toString()))

        assertEquals("legacy", destination.resolve(BookmarkFileManager.COLLECTIONS_FILE).readText())
        assertTrue(Files.exists(legacy.resolve(BookmarkFileManager.COLLECTIONS_FILE)))
    }

    @Test
    fun `migration never overwrites managed data or follows legacy symlinks`() {
        val home = createTempDirectory("bookmark-migration-safe")
        val legacy = home.resolve("Documents/BOSS/bookmarks").createDirectories()
        val destination = home.resolve(".boss/plugin-data/ai.rever.boss.plugin.dynamic.bookmarks/bookmarks")
            .createDirectories()
        legacy.resolve(BookmarkFileManager.COLLECTIONS_FILE).writeText("legacy")
        destination.resolve(BookmarkFileManager.COLLECTIONS_FILE).writeText("managed")

        val outside = home.resolve("outside.json").also { it.writeText("outside") }
        val link = legacy.resolve("linked.json")
        runCatching { Files.createSymbolicLink(link, outside) }

        BookmarkFileManager.migrateLegacyBookmarks(legacy, destination)

        assertEquals("managed", destination.resolve(BookmarkFileManager.COLLECTIONS_FILE).readText())
        assertFalse(Files.exists(destination.resolve("linked.json")))
    }
}
