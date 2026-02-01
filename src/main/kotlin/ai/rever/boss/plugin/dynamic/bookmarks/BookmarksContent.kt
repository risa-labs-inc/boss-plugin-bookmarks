package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun BookmarksContent(
    viewModel: BookmarksViewModel,
    bookmarkDataProvider: BookmarkDataProvider?,
    workspaceDataProvider: WorkspaceDataProvider?
) {
    BossTheme {
        if (bookmarkDataProvider == null || workspaceDataProvider == null) {
            NoProvidersMessage()
        } else {
            BookmarksPanel(viewModel)
        }
    }
}

@Composable
private fun NoProvidersMessage() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Bookmarks,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colors.primary.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Bookmarks",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Bookmark providers not available",
                fontSize = 13.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun BookmarksPanel(viewModel: BookmarksViewModel) {
    val collections by viewModel.collections.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()
    val favoriteWorkspaces by viewModel.favoriteWorkspaces.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // Dialog states
    var showNewCollectionDialog by remember { mutableStateOf(false) }
    var collectionToDelete by remember { mutableStateOf<BookmarkCollection?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Toolbar
            BookmarksToolbar(
                onNewCollection = { showNewCollectionDialog = true }
            )

            Divider(color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f))

            // Search bar
            SearchBar(
                query = searchQuery,
                onQueryChange = { viewModel.updateSearchQuery(it) }
            )

            // Toast messages
            AnimatedVisibility(
                visible = statusMessage != null || errorMessage != null,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                ToastMessage(
                    statusMessage = statusMessage,
                    errorMessage = errorMessage,
                    onDismiss = { viewModel.clearMessages() }
                )
            }

            // Content
            BookmarksList(
                collections = collections,
                workspaces = workspaces,
                favoriteWorkspaces = favoriteWorkspaces.map { it.workspaceId }.toSet(),
                searchQuery = searchQuery,
                viewModel = viewModel,
                onDeleteCollection = { collectionToDelete = it }
            )
        }
    }

    // New Collection Dialog
    if (showNewCollectionDialog) {
        NewCollectionDialog(
            onDismiss = { showNewCollectionDialog = false },
            onCreate = { name ->
                viewModel.createCollection(name)
                showNewCollectionDialog = false
            }
        )
    }

    // Delete Collection Dialog
    collectionToDelete?.let { collection ->
        DeleteCollectionDialog(
            collection = collection,
            onDismiss = { collectionToDelete = null },
            onConfirm = {
                viewModel.deleteCollection(collection.id)
                collectionToDelete = null
            }
        )
    }
}

@Composable
private fun BookmarksToolbar(
    onNewCollection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Bookmarks",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface
        )

        Spacer(modifier = Modifier.weight(1f))

        IconButton(
            onClick = onNewCollection,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CreateNewFolder,
                contentDescription = "New Collection",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                "Search bookmarks...",
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
            )
        },
        colors = TextFieldDefaults.textFieldColors(
            backgroundColor = MaterialTheme.colors.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        shape = RoundedCornerShape(4.dp),
        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
    )
}

@Composable
private fun ToastMessage(
    statusMessage: String?,
    errorMessage: String?,
    onDismiss: () -> Unit
) {
    LaunchedEffect(statusMessage, errorMessage) {
        delay(3000)
        onDismiss()
    }

    val isError = errorMessage != null
    val message = errorMessage ?: statusMessage ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) Color(0xFF5D3A3A) else Color(0xFF3A5D3A))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = Color.White
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message,
            fontSize = 11.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BookmarksList(
    collections: List<BookmarkCollection>,
    workspaces: List<LayoutWorkspace>,
    favoriteWorkspaces: Set<String>,
    searchQuery: String,
    viewModel: BookmarksViewModel,
    onDeleteCollection: (BookmarkCollection) -> Unit
) {
    val listState = rememberLazyListState()

    // Filter bookmarks by search query
    val filteredCollections = if (searchQuery.isEmpty()) {
        collections
    } else {
        collections.map { collection ->
            collection.copy(
                bookmarks = collection.bookmarks.filter {
                    it.tabConfig.title.contains(searchQuery, ignoreCase = true) ||
                    (it.tabConfig.url?.contains(searchQuery, ignoreCase = true) ?: false)
                }
            )
        }.filter { it.bookmarks.isNotEmpty() || it.isFavorite }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .lazyListScrollbar(
                    listState = listState,
                    direction = Orientation.Vertical,
                    config = getPanelScrollbarConfig()
                )
        ) {
            // Favorites section
            val favorites = filteredCollections.find { it.isFavorite }
            if (favorites != null && favorites.bookmarks.isNotEmpty()) {
                item { CollectionHeader(favorites.name, favorites.bookmarks.size, true) }
                items(favorites.bookmarks, key = { "fav-${it.id}" }) { bookmark ->
                    BookmarkRow(
                        bookmark = bookmark,
                        onClick = { viewModel.onBookmarkClick(bookmark) },
                        onRemove = { viewModel.removeBookmark(favorites.id, bookmark.id) }
                    )
                }
            }

            // Favorite Workspaces section
            val favWs = workspaces.filter { it.id in favoriteWorkspaces }
            if (favWs.isNotEmpty()) {
                item { CollectionHeader("Favorite Workspaces", favWs.size, false) }
                items(favWs, key = { "fav-ws-${it.id}" }) { workspace ->
                    WorkspaceRow(
                        workspace = workspace,
                        isFavorite = true,
                        onClick = { viewModel.onWorkspaceClick(workspace) },
                        onToggleFavorite = { viewModel.removeFavoriteWorkspace(workspace.id) }
                    )
                }
            }

            // Other collections
            filteredCollections.filter { !it.isFavorite }.forEach { collection ->
                if (collection.bookmarks.isNotEmpty()) {
                    item {
                        CollectionHeader(
                            name = collection.name,
                            count = collection.bookmarks.size,
                            isFavorites = false,
                            onDelete = { onDeleteCollection(collection) }
                        )
                    }
                    items(collection.bookmarks, key = { "${collection.id}-${it.id}" }) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            onClick = { viewModel.onBookmarkClick(bookmark) },
                            onRemove = { viewModel.removeBookmark(collection.id, bookmark.id) }
                        )
                    }
                }
            }

            // All Workspaces section
            if (searchQuery.isEmpty() && workspaces.isNotEmpty()) {
                item { CollectionHeader("All Workspaces", workspaces.size, false) }
                items(workspaces, key = { "ws-${it.id}" }) { workspace ->
                    WorkspaceRow(
                        workspace = workspace,
                        isFavorite = workspace.id in favoriteWorkspaces,
                        onClick = { viewModel.onWorkspaceClick(workspace) },
                        onToggleFavorite = {
                            if (workspace.id in favoriteWorkspaces) {
                                viewModel.removeFavoriteWorkspace(workspace.id)
                            } else {
                                viewModel.addFavoriteWorkspace(workspace.id, workspace.name)
                            }
                        }
                    )
                }
            }

            // Empty state
            if (filteredCollections.all { it.bookmarks.isEmpty() } && workspaces.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isEmpty()) "No bookmarks yet" else "No results found",
                            fontSize = 13.sp,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionHeader(
    name: String,
    count: Int,
    isFavorites: Boolean,
    onDelete: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isFavorites) Icons.Default.Star else Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (isFavorites) Color(0xFFFFC107) else MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "($count)",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
        )
        if (onDelete != null && !isFavorites) {
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon based on tab type
        val icon = when (bookmark.tabConfig.type) {
            "browser" -> Icons.Default.Language
            "editor" -> Icons.Default.Code
            "terminal" -> Icons.Default.Terminal
            else -> Icons.Default.Tab
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bookmark.tabConfig.title,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            bookmark.tabConfig.url?.let { url ->
                Text(
                    text = url,
                    fontSize = 10.sp,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun WorkspaceRow(
    workspace: LayoutWorkspace,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Dashboard,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colors.primary
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = workspace.name,
            fontSize = 12.sp,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                modifier = Modifier.size(14.dp),
                tint = if (isFavorite) Color(0xFFFFC107) else MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun NewCollectionDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Collection") },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Collection name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name) },
                enabled = name.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        backgroundColor = MaterialTheme.colors.surface,
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun DeleteCollectionDialog(
    collection: BookmarkCollection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Collection?") },
        text = {
            Text(
                "Collection '${collection.name}' and all its bookmarks will be permanently deleted. " +
                "This action cannot be undone."
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        backgroundColor = MaterialTheme.colors.surface,
        shape = RoundedCornerShape(8.dp)
    )
}
