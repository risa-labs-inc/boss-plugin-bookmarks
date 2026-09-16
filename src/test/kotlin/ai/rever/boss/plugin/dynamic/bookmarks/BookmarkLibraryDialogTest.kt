package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.bookmark.*
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class BookmarkLibraryDialogTest {
    @get:Rule val compose = createComposeRule()
    private val bookmark = Bookmark(id = "saved", tabConfig = TabConfig(type = "browser", title = "Original", url = "https://example.com"), workspaceName = "Work")
    private val collection = BookmarkCollection(id = "one", name = "One", bookmarks = listOf(bookmark))

    @Test fun `failed save retains typed values and concurrent changes require explicit reload`() {
        var state by mutableStateOf(BookmarkLibraryState(collections = listOf(collection), ready = true, revision = 1))
        var error by mutableStateOf<String?>(null)
        var submitted: String? = null
        compose.setContent { BossTheme {
            LibraryEditDialog(bookmark, "one", state, false, error, {}, { name, _, _, _, _ -> submitted = name; error = "Disk full" })
        } }
        compose.onNodeWithText("Original").performTextReplacement("Typed title")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals("Typed title", submitted) }
        compose.onNodeWithText("Typed title").assertExists()
        compose.onNodeWithText("Disk full").assertExists()
        compose.runOnIdle { state = state.copy(revision = 2, collections = listOf(collection.copy(bookmarks = listOf(bookmark.copy(tabConfig = bookmark.tabConfig.copy(title = "Another window")))))) }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Reload saved values").performClick()
        compose.onNodeWithText("Another window").assertExists()
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun `nonempty collection deletion requires an explicit move destination`() {
        var selected: String? = null
        compose.setContent { BossTheme {
            SafeDeleteCollectionDialog(collection, listOf(collection, BookmarkCollection(id = "two", name = "Other")), false, null, {}, { selected = it })
        } }
        compose.onNodeWithText("Delete folder").assertIsNotEnabled()
        compose.onNodeWithText("Folder: Choose…").performClick()
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithText("Delete folder").performClick()
        compose.runOnIdle { assertEquals("two", selected) }
    }
    @Test fun `internal default destination is displayed as no folder`() {
        val default = collection.copy(name = "Unsorted")
        compose.setContent { BossTheme {
            LibraryEditDialog(bookmark, default.id, BookmarkLibraryState(collections = listOf(default), ready = true, unfiledCollectionIds = setOf(default.id)), false, null, {}, { _, _, _, _, _ -> })
        } }
        compose.onNodeWithText("Folder: No folder").assertExists()
        compose.onNodeWithText("Unsorted").assertDoesNotExist()
    }

}
