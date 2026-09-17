package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.BookmarkLibraryState
import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp

@Composable
internal fun LibraryEditDialog(
    bookmark: Bookmark,
    collectionId: String,
    state: BookmarkLibraryState,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String, TabConfig, String, Boolean, Long) -> Unit,
) {
    var name by remember(bookmark.id) { mutableStateOf(bookmark.tabConfig.title) }
    var target by remember(bookmark.id) { mutableStateOf(bookmark.tabConfig.url ?: bookmark.tabConfig.filePath ?: bookmark.tabConfig.workingDirectory.orEmpty()) }
    var command by remember(bookmark.id) { mutableStateOf(bookmark.tabConfig.initialCommand.orEmpty()) }
    var destination by remember(bookmark.id) { mutableStateOf(collectionId) }
    var favorite by remember(bookmark.id) { mutableStateOf(bookmark.id in state.favoriteBookmarkIds) }
    var revision by remember(bookmark.id) { mutableStateOf(state.revision) }
    var configBase by remember(bookmark.id) { mutableStateOf(bookmark.tabConfig) }
    val type = configBase.type
    val valid = !busy && state.ready && revision == state.revision && name.isNotBlank() && (type == "terminal" || target.isNotBlank())
    val submit = {
        val config = when (type) {
            "browser" -> configBase.copy(url = target.trim())
            "terminal" -> configBase.copy(workingDirectory = target.takeIf { it.isNotBlank() }, initialCommand = command.takeIf { it.isNotBlank() })
            else -> configBase.copy(filePath = target)
        }
        onSave(name, config, destination, favorite, revision)
    }
    BossAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Edit Bookmark") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key == Key.Enter && valid) { submit(); true } else false
            }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, enabled = !busy)
                Text(when (type) {
                    "terminal" -> "Opens a new terminal with this startup folder and command. Running sessions are never reused."
                    "browser" -> "Saves this address, not browsing history or sign-ins."
                    "diff" -> "Opens the working-tree diff in its saved project: ${configBase.workingDirectory.orEmpty()}"
                    "composer" -> "Reopens this Composer session. Its content is managed by the Composer tool."
                    else -> "Opens this saved file in the current pane."
                })
                OutlinedTextField(target, { target = it }, label = { Text(when(type) { "browser" -> "Address"; "terminal" -> "Startup folder"; "composer" -> "Session ID"; "diff" -> "File path within saved project"; else -> "File path" }) }, singleLine = true, enabled = !busy)
                if (type == "terminal") OutlinedTextField(command, { command = it }, label = { Text("Initial command (optional)") }, singleLine = true, enabled = !busy)
                CollectionPicker(state.collections, destination, { destination = it }, !busy, state.unfiledCollectionIds)
                Row { Checkbox(favorite, { favorite = it }, enabled = !busy); Text("Show in Favorites", modifier = Modifier.padding(top = 12.dp)) }
                if (revision != state.revision) {
                    Text("The library changed while this editor was open. Reload saved values before saving.")
                    TextButton(onClick = {
                        val latest = state.collections.flatMap { it.bookmarks }.find { it.id == bookmark.id }
                        if (latest != null) {
                            configBase = latest.tabConfig
                            name = latest.tabConfig.title
                            target = latest.tabConfig.url ?: latest.tabConfig.filePath ?: latest.tabConfig.workingDirectory.orEmpty()
                            command = latest.tabConfig.initialCommand.orEmpty()
                            destination = state.collections.first { c -> c.bookmarks.any { it.id == bookmark.id } }.id
                            favorite = bookmark.id in state.favoriteBookmarkIds
                            revision = state.revision
                        }
                    }, enabled = !busy) { Text("Reload saved values") }
                }
                if (error != null) Text(error, color = MaterialTheme.colors.error)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = submit) { Text(if (busy) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun CollectionPicker(collections: List<BookmarkCollection>, selected: String?, onSelect: (String) -> Unit, enabled: Boolean, unfiledIds: Set<String>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text("Folder: " + (collections.find { it.id == selected }?.folderDisplayName(unfiledIds) ?: "Choose…")) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            collections.forEach { collection -> DropdownMenuItem(onClick = { onSelect(collection.id); expanded = false }) { Text(collection.folderDisplayName(unfiledIds)) } }
        }
    }
}

@Composable
internal fun SafeDeleteCollectionDialog(
    collection: BookmarkCollection,
    collections: List<BookmarkCollection>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
    unfiledIds: Set<String> = emptySet(),
) {
    var destination by remember(collection.id) { mutableStateOf<String?>(null) }
    val choices = collections.filterNot { it.id == collection.id }
    BossAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Delete folder ${collection.folderDisplayName(unfiledIds)}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${collection.bookmarks.size} saved bookmarks. Deleting this folder never deletes its bookmarks.")
                if (collection.bookmarks.isNotEmpty()) {
                    Text("Choose where to move them first.")
                    CollectionPicker(choices, destination, { destination = it }, !busy, unfiledIds)
                }
                if (choices.isEmpty()) Text("Keep at least one folder.")
                if (error != null) Text(error, color = MaterialTheme.colors.error)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(destination) }, enabled = !busy && choices.isNotEmpty() && (collection.bookmarks.isEmpty() || destination != null)) { Text("Delete folder") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
internal fun LibraryNameDialog(title: String, initial: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    BossAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = { Column { OutlinedTextField(name, { name = it }, label = { Text("Folder name") }, singleLine = true, enabled = !busy); if (error != null) Text(error, color = MaterialTheme.colors.error) } },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = !busy && name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
