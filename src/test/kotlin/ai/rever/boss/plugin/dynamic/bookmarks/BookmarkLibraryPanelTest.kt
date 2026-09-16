package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.bookmark.BookmarkSaveRequest
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkFileManager
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkLibraryDisk
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkLibraryStore
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkManager
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookmarkLibraryPanelTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `star toggles sidebar membership without deleting saved bookmark`() = runBlocking {
        val directory = Files.createTempDirectory("library-panel").toFile()
        val library = BookmarkLibraryStore(BookmarkLibraryDisk(directory.path), false)
        val spaces = BookmarkManager(BookmarkFileManager(directory.path), loadCollections = false)
        try {
            withTimeout(5000) { library.state.first { it.ready } }
            val snapshot = library.state.value
            val saved = library.saveBookmark(BookmarkSaveRequest(snapshot.collections.first().id, TabConfig(type = "browser", title = "My page", url = "https://example.com"), "My page", false, snapshot.revision))
            assertTrue(saved.success)
            val viewModel = BookmarksViewModel(spaces, null, null, library)
            compose.setContent { BossTheme { BookmarksContent(viewModel, spaces, null, null, null) } }
            compose.onNodeWithText("Bookmarks").performClick()
            compose.onNodeWithContentDescription("Show in Favorites").performClick()
            compose.waitUntil(5000) { saved.bookmarkId in library.state.value.favoriteBookmarkIds }
            compose.onAllNodesWithContentDescription("Remove from Favorites").onFirst().performClick()
            compose.waitUntil(5000) { library.state.value.favoriteBookmarkIds.isEmpty() }
            assertEquals(1, library.state.value.collections.flatMap { it.bookmarks }.size)
        } finally { library.close(); spaces.close(); directory.deleteRecursively() }
    }
}
