package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BookmarkLibraryDiskTest {
    private fun record(id: String, title: String = id) = Bookmark(
        id = id, tabConfig = TabConfig(type = "browser", title = title, url = "https://example.com/$id"),
        workspaceName = "Legacy", notes = "retain notes", tags = listOf("retain tag"), createdAt = 123,
    )

    @Test fun `migration preserves distinct copies fields and original IDs with explicit collision aliases`() {
        val first = record("same", "First")
        val second = record("same", "Second")
        val legacy = listOf(BookmarkCollection(id = "one", name = "One", bookmarks = listOf(first, second)))
        val migrated = importLegacyLibrary(null, legacy, "old")
        val records = migrated.collections.single().bookmarks
        assertEquals(first, records.first())
        assertNotEquals("same", records.last().id)
        assertEquals(second.copy(id = records.last().id), records.last())
        assertEquals(records.map { it.id }.toSet(), migrated.favoriteBookmarkIds)
        assertTrue(migrated.legacyIdentityAliases.isNotEmpty())
        assertEquals(legacy, migrated.legacyImportedCollections)
    }

    @Test fun `external legacy import never overwrites edited canonical record`() {
        val original = record("one")
        val legacy = listOf(BookmarkCollection(id = "one", name = "One", bookmarks = listOf(original)))
        val first = importLegacyLibrary(null, legacy, "old")
        val edited = first.copy(collections = listOf(first.collections.single().copy(bookmarks = listOf(original.copy(notes = "new notes")))))
        val external = listOf(legacy.single().copy(bookmarks = listOf(original.copy(notes = "external notes"))))
        val imported = importLegacyLibrary(edited, external, "new")
        assertEquals(listOf("new notes", "external notes"), imported.collections.flatMap { it.bookmarks }.map { it.notes })
        assertEquals(2, imported.collections.flatMap { it.bookmarks }.map { it.id }.toSet().size)
    }

    @Test fun `atomic writes detect competing canonical and legacy edits and preserve source files`() = runBlocking {
        val directory = Files.createTempDirectory("library-disk-test").toFile()
        try {
            val legacyFile = File(directory, "collections.json")
            val legacy = listOf(BookmarkCollection(id = "one", name = "One", bookmarks = listOf(record("one"))))
            legacyFile.writeText(BookmarkSerializer.serializeCollections(legacy))
            val source = legacyFile.readBytes()
            val disk = BookmarkLibraryDisk(directory.absolutePath)
            val initial = disk.snapshot()
            val migrated = importLegacyLibrary(null, disk.legacyCollections(), initial.legacyFingerprint)
            val fingerprint = disk.commit(null, migrated)
            assertEquals(migrated, disk.snapshot().document)
            assertTrue(source.contentEquals(legacyFile.readBytes()))
            val changed = migrated.copy(revision = 1, favoriteBookmarkIds = emptySet())
            disk.commit(fingerprint, changed)
            assertFailsWith<IllegalStateException> { disk.commit(fingerprint, migrated) }
            assertEquals(changed, disk.snapshot().document)
            legacyFile.appendText(" ")
            assertFailsWith<IllegalStateException> { disk.commit(disk.snapshot().fingerprint, changed) }
            assertEquals(changed, disk.snapshot().document)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `corrupt library remains untouched and cannot look like an empty library`() = runBlocking {
        val directory = Files.createTempDirectory("library-corrupt-test").toFile()
        try {
            val file = File(directory, "bookmark-library.json")
            file.writeText("broken source")
            assertFailsWith<Exception> { BookmarkLibraryDisk(directory.absolutePath).snapshot() }
            assertEquals("broken source", file.readText())
            assertFalse(File(directory, "collections.json").exists())
        } finally { directory.deleteRecursively() }
    }
}
