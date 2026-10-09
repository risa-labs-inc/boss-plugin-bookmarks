package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.bookmark.BookmarkSaveRequest
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkFileManager
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkLibraryDisk
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkLibraryStore
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkManager
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Image
import java.io.File
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
            compose.setContent { BossTheme { Box(Modifier.size(220.dp, 600.dp).testTag("panel-capture")) { BookmarksContent(viewModel, spaces, null, null, null) } } }
            compose.onAllNodesWithText("Bookmarks").assertCountEquals(1)
            compose.onNodeWithText("Collections").assertDoesNotExist()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Workspaces", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            compose.onNodeWithContentDescription("Show in Favorites").performClick()
            compose.waitUntil(5000) { saved.bookmarkId in library.state.value.favoriteBookmarkIds }
            compose.onAllNodesWithText("My page").assertCountEquals(1)
            val screenshotPath = System.getenv("BOSS_BOOKMARK_SCREENSHOT")
            if (screenshotPath != null) {
                val bitmap = compose.onNodeWithTag("panel-capture").captureToImage().asSkiaBitmap()
                Image.makeFromBitmap(bitmap).encodeToData()?.let { File(screenshotPath).writeBytes(it.bytes) }
            }
            compose.onNodeWithText("Favorite Workspaces").assertDoesNotExist()
            compose.onNodeWithTag("favorites-filter").performClick()
            compose.onAllNodesWithText("My page").assertCountEquals(1)
            compose.onNodeWithContentDescription("Remove from Favorites").performClick()
            compose.waitUntil(5000) { library.state.value.favoriteBookmarkIds.isEmpty() }
            compose.onNodeWithText("My page").assertDoesNotExist()
            compose.onNodeWithTag("all-filter").performClick()
            compose.onAllNodesWithText("My page").assertCountEquals(1)
            compose.onNodeWithTag("workspaces-view").performClick()
            compose.onNodeWithText("Favorite Workspaces").assertExists()
            compose.onNodeWithText("My page").assertDoesNotExist()
            compose.onNodeWithTag("favorites-filter").assertDoesNotExist()
            compose.onNodeWithTag("bookmarks-view").performClick()
            compose.onNodeWithText("Favorite Workspaces").assertDoesNotExist()
            compose.onAllNodesWithText("My page").assertCountEquals(1)
            assertEquals(1, library.state.value.collections.flatMap { it.bookmarks }.size)
            assertTrue(library.createCollection("Research", library.state.value.revision).success)
            compose.waitForIdle()
            compose.onNodeWithText("Research").assertExists()
            compose.onAllNodesWithText("My page").assertCountEquals(1)
            assertTrue(library.createCollection("Unsorted", library.state.value.revision).success)
            compose.waitForIdle()
            compose.onNodeWithText("Unsorted").assertExists()
            compose.onNodeWithText("Collections").assertDoesNotExist()
            compose.onNodeWithTag("favorites-filter").performClick()
            compose.onAllNodesWithText("My page").assertCountEquals(0)
            compose.onNodeWithTag("all-filter").performClick()
            compose.onAllNodesWithText("My page").assertCountEquals(1)
            compose.onNodeWithContentDescription("Show in Favorites").performClick()
            compose.waitUntil(5000) { saved.bookmarkId in library.state.value.favoriteBookmarkIds }
            compose.onNodeWithTag("favorites-filter").performClick()
            viewModel.updateSearchQuery("My page")
            compose.waitForIdle()
            compose.onAllNodes(hasText("My page") and !hasSetTextAction()).assertCountEquals(1)
            compose.onNodeWithText("Unsorted").assertDoesNotExist()
            viewModel.close()
        } finally { library.close(); spaces.close(); directory.deleteRecursively() }
    }
}
