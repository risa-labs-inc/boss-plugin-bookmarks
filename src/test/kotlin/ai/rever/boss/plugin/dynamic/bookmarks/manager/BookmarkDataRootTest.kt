package ai.rever.boss.plugin.dynamic.bookmarks.manager

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `migration never overwrites managed data or imports unrelated files`() {
        val home = createTempDirectory("bookmark-migration-safe")
        val legacy = home.resolve("Documents/BOSS/bookmarks").createDirectories()
        val destination = home.resolve(".boss/plugin-data/ai.rever.boss.plugin.dynamic.bookmarks/bookmarks")
            .createDirectories()
        legacy.resolve(BookmarkFileManager.COLLECTIONS_FILE).writeText("legacy")
        destination.resolve(BookmarkFileManager.COLLECTIONS_FILE).writeText("managed")

        legacy.resolve("stale.tmp").writeText("not bookmark state")

        BookmarkFileManager.migrateLegacyBookmarks(legacy, destination)

        assertEquals("managed", destination.resolve(BookmarkFileManager.COLLECTIONS_FILE).readText())
        assertFalse(Files.exists(destination.resolve("stale.tmp")))
    }

    @Test
    fun `completed import is one shot and never resurrects deleted managed data`() {
        val home = createTempDirectory("bookmark-one-shot")
        val legacy = home.resolve("Documents/BOSS/bookmarks").createDirectories()
        val destination = home.resolve(".boss/plugin-data/ai.rever.boss.plugin.dynamic.bookmarks/bookmarks")
        legacy.resolve(BookmarkFileManager.COLLECTIONS_FILE).writeText("legacy")

        BookmarkFileManager.migrateLegacyBookmarks(legacy, destination)
        Files.delete(destination.resolve(BookmarkFileManager.COLLECTIONS_FILE))
        BookmarkFileManager.migrateLegacyBookmarks(legacy, destination)

        assertFalse(Files.exists(destination.resolve(BookmarkFileManager.COLLECTIONS_FILE)))
        assertTrue(Files.isRegularFile(destination.resolve(BookmarkFileManager.LEGACY_IMPORT_MARKER)))
    }

    @Test
    fun `interrupted import publishes neither a partial record nor completion marker`() {
        val home = createTempDirectory("bookmark-interrupted")
        val legacy = home.resolve("Documents/BOSS/bookmarks").createDirectories()
        val destination = home.resolve(".boss/plugin-data/ai.rever.boss.plugin.dynamic.bookmarks/bookmarks")
        legacy.resolve(BookmarkFileManager.COLLECTIONS_FILE).writeText("complete legacy data")

        assertFailsWith<IllegalStateException> {
            BookmarkFileManager.migrateLegacyBookmarks(legacy, destination) { source, target ->
                BookmarkFileManager.copyLegacyRecordSafely(source, target) { error("interrupted") }
            }
        }

        assertFalse(Files.exists(destination.resolve(BookmarkFileManager.COLLECTIONS_FILE)))
        assertFalse(Files.exists(destination.resolve(BookmarkFileManager.LEGACY_IMPORT_MARKER)))
    }

    @Test
    fun `concurrent managed write wins over legacy import`() {
        val home = createTempDirectory("bookmark-migration-race")
        val legacy = home.resolve("Documents/BOSS/bookmarks").createDirectories()
        val destination = home.resolve(".boss/plugin-data/ai.rever.boss.plugin.dynamic.bookmarks/bookmarks")
        legacy.resolve(BookmarkFileManager.COLLECTIONS_FILE).writeText("legacy")

        BookmarkFileManager.migrateLegacyBookmarks(legacy, destination) { source, target ->
            BookmarkFileManager.copyLegacyRecordSafely(source, target) {
                target.writeText("managed")
            }
        }

        assertEquals("managed", destination.resolve(BookmarkFileManager.COLLECTIONS_FILE).readText())
        assertTrue(Files.isRegularFile(destination.resolve(BookmarkFileManager.LEGACY_IMPORT_MARKER)))
    }

    @Test
    fun `filesystem failure cannot prevent the plugin from resolving its managed directory`() {
        val home = createTempDirectory("bookmark-unavailable")
        home.resolve("Documents/BOSS/bookmarks").createDirectories()
            .resolve(BookmarkFileManager.COLLECTIONS_FILE).writeText("legacy")
        home.resolve(".boss").writeText("not a directory")

        val resolved = BookmarkFileManager.defaultBookmarksDirectory(home.toString())

        assertEquals(
            home.resolve(".boss/plugin-data/ai.rever.boss.plugin.dynamic.bookmarks/bookmarks").toString(),
            resolved,
        )
    }
}
