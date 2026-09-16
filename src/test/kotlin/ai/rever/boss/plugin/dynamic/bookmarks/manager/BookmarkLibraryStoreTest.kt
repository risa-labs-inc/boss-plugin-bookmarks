package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.*
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class BookmarkLibraryStoreTest {
    private suspend fun ready(store: BookmarkLibraryStore) { withTimeout(5000) { store.state.first { it.ready || it.error != null } }; assertTrue(store.state.value.ready, store.state.value.error) }
    private fun request(store: BookmarkLibraryStore, title: String = "Site", url: String = "https://example.com", favorite: Boolean = true) =
        BookmarkSaveRequest(store.state.value.collections.first().id, TabConfig(type = "browser", title = title, url = url), title, favorite, store.state.value.revision)

    @Test fun `unfavorite keeps one canonical saved record and preference survives restart`() = runBlocking {
        val dir = Files.createTempDirectory("library-store").toFile()
        val store = BookmarkLibraryStore(BookmarkLibraryDisk(dir.path), watch = false)
        try {
            ready(store)
            val saved = store.saveBookmark(request(store)); assertTrue(saved.success)
            assertTrue(store.setFavorite(saved.bookmarkId!!, false, store.state.value.revision).success)
            assertEquals(1, store.state.value.collections.flatMap { it.bookmarks }.size)
            assertTrue(store.state.value.favoriteBookmarkIds.isEmpty())
            val record = store.state.value.collections.first().bookmarks.first()
            assertTrue(store.saveBookmark(request(store, favorite = false).copy(bookmarkId = record.id)).success)
            store.close()
            val restarted = BookmarkLibraryStore(BookmarkLibraryDisk(dir.path), watch = false)
            try { ready(restarted); assertFalse(restarted.state.value.defaultFavorite); assertEquals(record.id, restarted.state.value.collections.first().bookmarks.single().id) } finally { restarted.close() }
        } finally { store.close(); dir.deleteRecursively() }
    }

    @Test fun `duplicate submit and stale editor cannot silently replace records`() = runBlocking {
        val dir = Files.createTempDirectory("library-duplicate").toFile(); val store = BookmarkLibraryStore(BookmarkLibraryDisk(dir.path), false)
        try {
            ready(store); val request = request(store)
            val first = store.saveBookmark(request); assertTrue(first.success)
            assertFalse(store.saveBookmark(request).success)
            val duplicate = store.saveBookmark(request(store, favorite = false)); assertFalse(duplicate.success); assertEquals(first.bookmarkId, duplicate.duplicateBookmarkId)
            assertTrue(store.saveBookmark(request(store).copy(allowCopy = true)).success)
            assertEquals(2, store.state.value.collections.flatMap { it.bookmarks }.size)
        } finally { store.close(); dir.deleteRecursively() }
    }

    @Test fun `move and collection delete retain favorite membership and deletion supports durable undo`() = runBlocking {
        val dir = Files.createTempDirectory("library-undo").toFile(); val store = BookmarkLibraryStore(BookmarkLibraryDisk(dir.path), false)
        try {
            ready(store); val id = store.saveBookmark(request(store)).bookmarkId!!
            val source = store.state.value.collections.first().id
            val destination = store.createCollection("Other", store.state.value.revision).collectionId!!
            assertFalse(store.deleteCollection(source, null, store.state.value.revision).success)
            assertTrue(store.deleteCollection(source, destination, store.state.value.revision).success)
            assertEquals(id, store.state.value.collections.single().bookmarks.single().id)
            assertTrue(id in store.state.value.favoriteBookmarkIds)
            val deletion = store.deleteBookmark(id, store.state.value.revision); assertTrue(deletion.success)
            assertTrue(store.state.value.collections.single().bookmarks.isEmpty()); assertTrue(store.state.value.favoriteBookmarkIds.isEmpty())
            store.close()
            val restarted = BookmarkLibraryStore(BookmarkLibraryDisk(dir.path), false)
            try { ready(restarted); assertTrue(restarted.undo(deletion.undoToken!!, restarted.state.value.revision).success); assertTrue(id in restarted.state.value.favoriteBookmarkIds) } finally { restarted.close() }
        } finally { store.close(); dir.deleteRecursively() }
    }

    @Test fun `disk failure never publishes success or changes current records`() = runBlocking {
        val dir = Files.createTempDirectory("library-failure").toFile()
        var fail = false
        val disk = object : BookmarkLibraryDisk(dir.path) {
            override suspend fun commit(expectedFingerprint: String?, document: LibraryDocument): String {
                if (fail) throw java.io.IOException("Disk full")
                return super.commit(expectedFingerprint, document)
            }
        }
        val store = BookmarkLibraryStore(disk, false)
        try {
            ready(store); val original = store.state.value; fail = true
            val result = store.saveBookmark(request(store)); assertFalse(result.success); assertEquals("Disk full", result.message)
            assertEquals(original.collections, store.state.value.collections); assertEquals(original.revision, store.state.value.revision)
            fail = false; assertTrue(store.saveBookmark(request(store)).success)
        } finally { store.close(); dir.deleteRecursively() }
    }

    @Test fun `two processes cannot overwrite each other and reload recovers`() = runBlocking {
        val dir = Files.createTempDirectory("library-concurrent").toFile()
        val first = BookmarkLibraryStore(BookmarkLibraryDisk(dir.path), false); ready(first)
        val second = BookmarkLibraryStore(BookmarkLibraryDisk(dir.path), false)
        try {
            ready(second); assertTrue(first.saveBookmark(request(first)).success)
            assertFalse(second.saveBookmark(request(second, url = "https://other.example")).success)
            second.reload(); assertTrue(second.saveBookmark(request(second, url = "https://other.example")).success)
            first.reload(); assertEquals(2, first.state.value.collections.flatMap { it.bookmarks }.size)
        } finally { first.close(); second.close(); dir.deleteRecursively() }
    }

    @Test fun `editing preserves notes tags and legacy placement metadata`() = runBlocking {
        val dir = Files.createTempDirectory("library-metadata").toFile()
        val record = Bookmark(id = "legacy", tabConfig = TabConfig("browser", "Old", url = "https://example.com"), workspaceName = "Work", createdAt = 123, notes = "Notes", tags = listOf("Tag"), targetWorkspaces = listOf(WorkspacePanelTarget("Work", "panel")))
        File(dir, "collections.json").writeText(BookmarkSerializer.serializeCollections(listOf(BookmarkCollection(id = "c", name = "Favorites", isFavorite = true, bookmarks = listOf(record)))))
        val store = BookmarkLibraryStore(BookmarkLibraryDisk(dir.path), false)
        try {
            ready(store); assertEquals("Unsorted", store.state.value.collections.single().name)
            assertTrue(store.saveBookmark(request(store, title = "Renamed").copy(bookmarkId = record.id)).success)
            val changed = store.state.value.collections.single().bookmarks.single()
            assertEquals(record.copy(tabConfig = record.tabConfig.copy(title = "Renamed")), changed)
            assertTrue(store.saveBookmark(request(store, title = "Copy").copy(allowCopy = true)).success)
            val copy = store.state.value.collections.single().bookmarks.last()
            assertEquals(record.notes, copy.notes); assertEquals(record.tags, copy.tags); assertEquals(record.targetWorkspaces, copy.targetWorkspaces)
            assertTrue(store.saveBookmark(request(store, title = "Original renamed again").copy(bookmarkId = changed.id)).success)
            assertTrue(store.saveBookmark(request(store, title = "Copy renamed").copy(bookmarkId = copy.id)).success)
            val renamed = store.state.value.collections.single().bookmarks
            assertEquals(setOf(changed.id, copy.id), renamed.map { it.id }.toSet())
            assertTrue(renamed.all { it.notes == record.notes && it.tags == record.tags && it.createdAt == record.createdAt })
            assertEquals(setOf(changed.id, copy.id), store.state.value.favoriteBookmarkIds)
        } finally { store.close(); dir.deleteRecursively() }
    }

    @Test fun `another window's favorite change is reflected without a manual reload`() = runBlocking {
        val dir = Files.createTempDirectory("library-watch").toFile()
        val first = BookmarkLibraryStore(BookmarkLibraryDisk(dir.path)); ready(first)
        val second = BookmarkLibraryStore(BookmarkLibraryDisk(dir.path))
        try {
            ready(second); val saved = first.saveBookmark(request(first)); assertTrue(saved.success)
            withTimeout(5000) { second.state.first { saved.bookmarkId in it.favoriteBookmarkIds } }
            assertTrue(second.setFavorite(saved.bookmarkId!!, false, second.state.value.revision).success)
            withTimeout(5000) { first.state.first { it.ready && it.favoriteBookmarkIds.isEmpty() && it.collections.first().bookmarks.isNotEmpty() } }
            File(dir, "bookmark-library.json").writeText("corrupt")
            withTimeout(5000) { first.state.first { !it.ready && it.error != null } }
            assertEquals(1, first.state.value.collections.first().bookmarks.size)
            first.reload(); assertEquals(1, first.state.value.collections.first().bookmarks.size)
        } finally { first.close(); second.close(); dir.deleteRecursively() }
    }
    @Test fun `reload does not replace current records when canonical file disappears`() = runBlocking {
        val dir = Files.createTempDirectory("library-removed").toFile()
        val store = BookmarkLibraryStore(BookmarkLibraryDisk(dir.path), false)
        try {
            ready(store); assertTrue(store.saveBookmark(request(store)).success)
            File(dir, "bookmark-library.json").delete()
            store.reload()
            assertFalse(store.state.value.ready)
            assertEquals(1, store.state.value.collections.single().bookmarks.size)
            assertFalse(File(dir, "bookmark-library.json").exists())
        } finally { store.close(); dir.deleteRecursively() }
    }

    @Test fun `adding existing destination to favorites preserves its record and is durable and idempotent`() = runBlocking {
        val dir = Files.createTempDirectory("library-add-favorite").toFile()
        val store = BookmarkLibraryStore(BookmarkLibraryDisk(dir.path), false)
        try {
            ready(store)
            val saved = store.saveBookmark(request(store, title = "My saved title", favorite = false))
            assertTrue(saved.success)
            assertTrue(store.compatibilityUpdate(store.state.value.revision) { collections ->
                collections.map { collection -> collection.copy(bookmarks = collection.bookmarks.map { it.copy(notes = "Important notes", tags = listOf("Work"), createdAt = 123) }) }
            }.success)
            val original = store.state.value.collections.single().bookmarks.single()
            val other = store.createCollection("Other", store.state.value.revision).collectionId!!
            val star = store.saveBookmark(request(store, title = "Different current tab title").copy(collectionId = other))
            assertTrue(star.success)
            assertEquals(original.id, star.bookmarkId)
            assertEquals(saved.collectionId, star.collectionId)
            assertEquals(original, store.state.value.collections.flatMap { it.bookmarks }.single())
            assertEquals(setOf(original.id), store.state.value.favoriteBookmarkIds)
            val revision = store.state.value.revision
            repeat(2) {
                assertTrue(store.saveBookmark(request(store, title = "Another title")).success)
                assertEquals(revision, store.state.value.revision)
            }
            store.close()
            val restarted = BookmarkLibraryStore(BookmarkLibraryDisk(dir.path), false)
            try {
                ready(restarted)
                assertEquals(original, restarted.state.value.collections.flatMap { it.bookmarks }.single())
                assertEquals(setOf(original.id), restarted.state.value.favoriteBookmarkIds)
                assertEquals(saved.collectionId, restarted.state.value.collections.first { it.bookmarks.isNotEmpty() }.id)
            } finally { restarted.close() }
        } finally { store.close(); dir.deleteRecursively() }
    }

}
