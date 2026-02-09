package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkManager
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.ContextMenuItemData
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

// Custom colors matching bundled plugin
private val GoldFavorite = Color(0xFFFBBF24)
private val DarkBackground = Color(0xFF1E1E1E)
private val SearchBackground = Color(0xFF1E1F22)
private val BorderColor = Color(0xFF555555)
private val DividerLineColor = Color(0xFF4B5563)
private val LightGrayText = Color(0xFFF2F2F2)
private val MutedGrayText = Color(0xFF9CA3AF)
private val LightGrayText2 = Color(0xFFD1D5DB)
private val AccentColor = Color(0xFF60A5FA)

@Composable
fun BookmarksContent(
    viewModel: BookmarksViewModel,
    bookmarkManager: BookmarkManager,
    workspaceDataProvider: WorkspaceDataProvider?,
    contextMenuProvider: ContextMenuProvider?,
    activeTabsProvider: ActiveTabsProvider?
) {
    // BookmarkManager is always available (internal to plugin)
    // WorkspaceDataProvider may be unavailable but bookmarks can still work
    BookmarksPanel(viewModel, contextMenuProvider, activeTabsProvider)
}

@Composable
private fun NoProvidersMessage() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DarkBackground
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
                tint = AccentColor.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Bookmarks",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = LightGrayText
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Bookmark providers not available",
                fontSize = 13.sp,
                color = MutedGrayText
            )
        }
    }
}

@Composable
private fun BookmarksPanel(
    viewModel: BookmarksViewModel,
    contextMenuProvider: ContextMenuProvider?,
    activeTabsProvider: ActiveTabsProvider?
) {
    val collections by viewModel.collections.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()
    val favoriteWorkspaces by viewModel.favoriteWorkspaces.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val currentWorkspace by viewModel.currentWorkspace.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    // Section expansion states
    var favoritesExpanded by remember { mutableStateOf(true) }
    var collectionsExpanded by remember { mutableStateOf(false) }
    var allWorkspacesExpanded by remember { mutableStateOf(false) }
    var favoriteWorkspacesExpanded by remember { mutableStateOf(true) }

    // Track expansion state for each collection and workspace
    var expandedCollections by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedWorkspaces by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Dialog states
    var showNewCollectionDialog by remember { mutableStateOf(false) }
    var showNewWorkspaceDialog by remember { mutableStateOf(false) }
    var collectionToDelete by remember { mutableStateOf<BookmarkCollection?>(null) }
    var collectionToRename by remember { mutableStateOf<BookmarkCollection?>(null) }
    var workspaceToDelete by remember { mutableStateOf<LayoutWorkspace?>(null) }
    var workspaceToRename by remember { mutableStateOf<LayoutWorkspace?>(null) }
    var showClearFavoritesDialog by remember { mutableStateOf(false) }
    var showUnfavoriteAllWorkspacesDialog by remember { mutableStateOf(false) }

    // Bookmark operation dialog states
    var bookmarkToRemove by remember { mutableStateOf<Pair<Bookmark, String>?>(null) }
    var bookmarkToCopy by remember { mutableStateOf<Pair<Bookmark, String>?>(null) }
    var bookmarkToMove by remember { mutableStateOf<Pair<Bookmark, String>?>(null) }

    // Filtered data based on search query
    val filteredCollections = remember(collections, searchQuery) {
        filterCollections(collections, searchQuery)
    }
    val filteredFavoriteWorkspaces = remember(favoriteWorkspaces, workspaces, searchQuery) {
        val favoriteWorkspacesList = favoriteWorkspaces.mapNotNull { fav ->
            workspaces.find { it.id == fav.workspaceId }
        }
        filterWorkspaces(favoriteWorkspacesList, searchQuery) { viewModel.buildTabStructure(it) }
    }
    val filteredAllWorkspaces = remember(workspaces, searchQuery) {
        filterWorkspaces(workspaces, searchQuery) { viewModel.buildTabStructure(it) }
    }

    // Filter favorites collection bookmarks
    val favoritesCollection = collections.find { it.isFavorite }
    val filteredFavorites = remember(favoritesCollection?.bookmarks, searchQuery) {
        favoritesCollection?.let { filterBookmarks(it.bookmarks, searchQuery) } ?: emptyList()
    }

    // Scrollbar state
    val listState = rememberLazyListState()

    fun toggleCollectionExpansion(collectionId: String) {
        expandedCollections = if (expandedCollections.contains(collectionId)) {
            expandedCollections - collectionId
        } else {
            expandedCollections + collectionId
        }
    }

    fun toggleWorkspaceExpansion(workspaceId: String) {
        expandedWorkspaces = if (expandedWorkspaces.contains(workspaceId)) {
            expandedWorkspaces - workspaceId
        } else {
            expandedWorkspaces + workspaceId
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Search bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookmarkSearchBar(
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.updateSearchQuery(it) }
            )
        }

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
            if (favoritesCollection != null) {
                item {
                    CollapsibleSection(
                        title = favoritesCollection.name,
                        isExpanded = favoritesExpanded,
                        onToggle = { favoritesExpanded = !favoritesExpanded },
                        icon = Icons.Outlined.Star,
                        contextMenuProvider = contextMenuProvider,
                        contextMenuItems = buildList {
                            if (favoritesCollection.bookmarks.isNotEmpty()) {
                                add(ContextMenuItemData("Clear All Favorites", Icons.Outlined.DeleteSweep) {
                                    showClearFavoritesDialog = true
                                })
                            }
                        }
                    )
                }

                if (favoritesExpanded) {
                    if (filteredFavorites.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Outlined.Star,
                                message = if (searchQuery.isBlank()) "No favorites yet" else "No matching favorites"
                            )
                        }
                    } else {
                        items(filteredFavorites, key = { "fav-${it.id}" }) { bookmark ->
                            BookmarkItem(
                                bookmark = bookmark,
                                onClick = { viewModel.onBookmarkClick(bookmark, coroutineScope) },
                                contextMenuProvider = contextMenuProvider,
                                activeTabsProvider = activeTabsProvider,
                                onRemove = { bookmarkToRemove = Pair(bookmark, favoritesCollection.id) },
                                onCopy = { bookmarkToCopy = Pair(bookmark, favoritesCollection.id) },
                                onMove = { bookmarkToMove = Pair(bookmark, favoritesCollection.id) }
                            )
                        }
                    }
                }
            }

            // Collections section (all non-favorite collections)
            val otherCollections = filteredCollections.filter { !it.isFavorite }
            item {
                Spacer(modifier = Modifier.height(8.dp))
                CollapsibleSection(
                    title = "Collections",
                    isExpanded = collectionsExpanded,
                    onToggle = { collectionsExpanded = !collectionsExpanded },
                    icon = Icons.Outlined.FolderOpen,
                    contextMenuProvider = contextMenuProvider,
                    trailingAction = {
                        IconButton(
                            onClick = { showNewCollectionDialog = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "New Collection",
                                tint = AccentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    contextMenuItems = listOf(
                        ContextMenuItemData("New Collection", Icons.Outlined.CreateNewFolder) {
                            showNewCollectionDialog = true
                        }
                    )
                )
            }

            if (collectionsExpanded) {
                if (otherCollections.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.FolderOpen,
                            message = if (searchQuery.isBlank()) "No collections yet" else "No matching collections"
                        )
                    }
                } else {
                    items(otherCollections, key = { "coll-${it.id}" }) { collection ->
                        CollectionItem(
                            collection = collection,
                            isExpanded = expandedCollections.contains(collection.id),
                            onToggleExpand = { toggleCollectionExpansion(collection.id) },
                            onBookmarkClick = { bookmark ->
                                viewModel.onBookmarkClick(bookmark, coroutineScope)
                            },
                            searchQuery = searchQuery,
                            contextMenuProvider = contextMenuProvider,
                            activeTabsProvider = activeTabsProvider,
                            onRename = { collectionToRename = collection },
                            onDelete = { collectionToDelete = collection },
                            onBookmarkRemove = { bookmark -> bookmarkToRemove = Pair(bookmark, collection.id) },
                            onBookmarkCopy = { bookmark -> bookmarkToCopy = Pair(bookmark, collection.id) },
                            onBookmarkMove = { bookmark -> bookmarkToMove = Pair(bookmark, collection.id) }
                        )
                    }
                }
            }

            // Favorite Workspaces section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                CollapsibleSection(
                    title = "Favorite Workspaces",
                    isExpanded = favoriteWorkspacesExpanded,
                    onToggle = { favoriteWorkspacesExpanded = !favoriteWorkspacesExpanded },
                    icon = Icons.Outlined.Star,
                    contextMenuProvider = contextMenuProvider,
                    contextMenuItems = buildList {
                        if (favoriteWorkspaces.isNotEmpty()) {
                            add(ContextMenuItemData("Unfavorite All", Icons.Outlined.DeleteSweep) {
                                showUnfavoriteAllWorkspacesDialog = true
                            })
                        }
                    }
                )
            }

            if (favoriteWorkspacesExpanded) {
                if (filteredFavoriteWorkspaces.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.Favorite,
                            message = if (searchQuery.isBlank()) "No favorite workspaces" else "No matching favorite workspaces"
                        )
                    }
                } else {
                    items(filteredFavoriteWorkspaces, key = { "fav-ws-${it.id}" }) { workspace ->
                        WorkspaceItem(
                            workspace = workspace,
                            isExpanded = expandedWorkspaces.contains(workspace.id),
                            onToggleExpand = { toggleWorkspaceExpansion(workspace.id) },
                            onWorkspaceClick = { viewModel.onWorkspaceClick(workspace, coroutineScope) },
                            onTabClick = { tabConfig -> viewModel.onWorkspaceTabClick(tabConfig) },
                            buildStructure = { viewModel.buildTabStructure(it) },
                            isFavorite = viewModel.isFavorite(workspace.id),
                            isCurrentWorkspace = currentWorkspace?.id == workspace.id,
                            contextMenuProvider = contextMenuProvider,
                            activeTabsProvider = activeTabsProvider,
                            onToggleFavorite = {
                                if (viewModel.isFavorite(workspace.id)) {
                                    viewModel.removeFavoriteWorkspace(workspace.id)
                                } else {
                                    viewModel.addFavoriteWorkspace(workspace.id, workspace.name)
                                }
                            },
                            onRename = { workspaceToRename = workspace },
                            onDelete = { workspaceToDelete = workspace },
                            onExport = { viewModel.exportWorkspace(workspace) },
                            viewModel = viewModel
                        )
                    }
                }
            }

            // All Workspaces section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                CollapsibleSection(
                    title = "All Workspaces",
                    isExpanded = allWorkspacesExpanded,
                    onToggle = { allWorkspacesExpanded = !allWorkspacesExpanded },
                    icon = Icons.Outlined.WorkOutline,
                    contextMenuProvider = contextMenuProvider,
                    trailingAction = {
                        IconButton(
                            onClick = { showNewWorkspaceDialog = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "New Workspace",
                                tint = AccentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    contextMenuItems = listOf(
                        ContextMenuItemData("New Workspace", Icons.Outlined.CreateNewFolder) {
                            showNewWorkspaceDialog = true
                        }
                    )
                )
            }

            if (allWorkspacesExpanded) {
                if (filteredAllWorkspaces.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.FolderOpen,
                            message = if (searchQuery.isBlank()) "No workspaces" else "No matching workspaces"
                        )
                    }
                } else {
                    items(filteredAllWorkspaces, key = { "ws-${it.id}" }) { workspace ->
                        WorkspaceItem(
                            workspace = workspace,
                            isExpanded = expandedWorkspaces.contains(workspace.id),
                            onToggleExpand = { toggleWorkspaceExpansion(workspace.id) },
                            onWorkspaceClick = { viewModel.onWorkspaceClick(workspace, coroutineScope) },
                            onTabClick = { tabConfig -> viewModel.onWorkspaceTabClick(tabConfig) },
                            buildStructure = { viewModel.buildTabStructure(it) },
                            isFavorite = viewModel.isFavorite(workspace.id),
                            isCurrentWorkspace = currentWorkspace?.id == workspace.id,
                            contextMenuProvider = contextMenuProvider,
                            activeTabsProvider = activeTabsProvider,
                            onToggleFavorite = {
                                if (viewModel.isFavorite(workspace.id)) {
                                    viewModel.removeFavoriteWorkspace(workspace.id)
                                } else {
                                    viewModel.addFavoriteWorkspace(workspace.id, workspace.name)
                                }
                            },
                            onRename = { workspaceToRename = workspace },
                            onDelete = { workspaceToDelete = workspace },
                            onExport = { viewModel.exportWorkspace(workspace) },
                            viewModel = viewModel
                        )
                    }
                }
            }

            // Bottom spacer
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Dialogs
    if (showNewCollectionDialog) {
        NewCollectionDialog(
            onDismiss = { showNewCollectionDialog = false },
            onCreate = { name ->
                viewModel.createCollection(name)
                showNewCollectionDialog = false
            }
        )
    }

    if (showNewWorkspaceDialog) {
        NewWorkspaceDialog(
            onDismiss = { showNewWorkspaceDialog = false },
            onCreate = { name ->
                viewModel.createNewWorkspace(name)
                showNewWorkspaceDialog = false
            }
        )
    }

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

    collectionToRename?.let { collection ->
        RenameCollectionDialog(
            collection = collection,
            onDismiss = { collectionToRename = null },
            onRename = { newName ->
                viewModel.renameCollection(collection.id, newName)
                collectionToRename = null
            }
        )
    }

    workspaceToDelete?.let { workspace ->
        DeleteWorkspaceDialog(
            workspace = workspace,
            onDismiss = { workspaceToDelete = null },
            onConfirm = {
                viewModel.deleteWorkspace(workspace.name)
                workspaceToDelete = null
            }
        )
    }

    workspaceToRename?.let { workspace ->
        RenameWorkspaceDialog(
            workspace = workspace,
            onDismiss = { workspaceToRename = null },
            onRename = { newName ->
                viewModel.renameWorkspace(workspace.name, newName)
                workspaceToRename = null
            }
        )
    }

    if (showClearFavoritesDialog) {
        ClearFavoritesDialog(
            onDismiss = { showClearFavoritesDialog = false },
            onConfirm = {
                favoritesCollection?.let { fav ->
                    fav.bookmarks.forEach { bookmark ->
                        viewModel.removeBookmark(fav.id, bookmark.id)
                    }
                }
                showClearFavoritesDialog = false
            }
        )
    }

    if (showUnfavoriteAllWorkspacesDialog) {
        UnfavoriteAllWorkspacesDialog(
            onDismiss = { showUnfavoriteAllWorkspacesDialog = false },
            onConfirm = {
                favoriteWorkspaces.forEach { fav ->
                    viewModel.removeFavoriteWorkspace(fav.workspaceId)
                }
                showUnfavoriteAllWorkspacesDialog = false
            }
        )
    }

    bookmarkToRemove?.let { (bookmark, collectionId) ->
        ConfirmRemoveBookmarkDialog(
            bookmark = bookmark,
            onDismiss = { bookmarkToRemove = null },
            onConfirm = {
                viewModel.removeBookmark(collectionId, bookmark.id)
                bookmarkToRemove = null
            }
        )
    }

    bookmarkToCopy?.let { (bookmark, fromCollectionId) ->
        CollectionSelectionDialog(
            title = "Copy Bookmark To",
            collections = collections.filter { !it.isFavorite && it.id != fromCollectionId },
            onDismiss = { bookmarkToCopy = null },
            onSelect = { targetCollection ->
                viewModel.addBookmark(targetCollection.name, bookmark.copy(id = java.util.UUID.randomUUID().toString()))
                bookmarkToCopy = null
            }
        )
    }

    bookmarkToMove?.let { (bookmark, fromCollectionId) ->
        CollectionSelectionDialog(
            title = "Move Bookmark To",
            collections = collections.filter { it.id != fromCollectionId },
            onDismiss = { bookmarkToMove = null },
            onSelect = { targetCollection ->
                viewModel.moveBookmark(bookmark.id, fromCollectionId, targetCollection.id)
                bookmarkToMove = null
            }
        )
    }
}

// ==================== Collapsible Section ====================

@Composable
private fun CollapsibleSection(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    icon: ImageVector? = null,
    contextMenuProvider: ContextMenuProvider?,
    trailingAction: (@Composable () -> Unit)? = null,
    contextMenuItems: List<ContextMenuItemData> = emptyList()
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left side: chevron + icon + title (clickable)
        val baseModifier = Modifier
            .weight(1f)
            .clickable(onClick = onToggle)

        val modifierWithContextMenu = if (contextMenuProvider != null && contextMenuItems.isNotEmpty()) {
            contextMenuProvider.applyContextMenu(baseModifier, contextMenuItems)
        } else {
            baseModifier
        }

        Row(
            modifier = modifierWithContextMenu,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(16.dp),
                tint = MutedGrayText
            )
            Spacer(modifier = Modifier.width(4.dp))

            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = AccentColor
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MutedGrayText
            )
        }

        if (trailingAction != null) {
            trailingAction()
        }
    }
}

// ==================== Bookmark Item ====================

@Composable
private fun BookmarkItem(
    bookmark: Bookmark,
    onClick: () -> Unit,
    contextMenuProvider: ContextMenuProvider?,
    activeTabsProvider: ActiveTabsProvider?,
    onRemove: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit
) {
    val contextMenuItems = listOf(
        ContextMenuItemData("Remove from Collection", Icons.Outlined.Delete) { onRemove() },
        ContextMenuItemData("", null, isDivider = true),
        ContextMenuItemData("Copy to Collection", Icons.Outlined.ContentCopy) { onCopy() },
        ContextMenuItemData("Move to Collection", Icons.AutoMirrored.Outlined.DriveFileMove) { onMove() }
    )

    val baseModifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 6.dp)

    val modifierWithContextMenu = if (contextMenuProvider != null) {
        contextMenuProvider.applyContextMenu(baseModifier, contextMenuItems)
    } else {
        baseModifier
    }

    Row(
        modifier = modifierWithContextMenu,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BookmarkIcon(
            tabType = bookmark.tabConfig.type,
            faviconCacheKey = bookmark.tabConfig.faviconCacheKey,
            activeTabsProvider = activeTabsProvider,
            size = 16.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = bookmark.tabConfig.title,
            fontSize = 12.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = "Remove bookmark",
            modifier = Modifier
                .size(16.dp)
                .clickable(onClick = onRemove),
            tint = GoldFavorite
        )
    }
}

/**
 * Displays a bookmark icon - favicon for browser tabs, or Material icon for other types.
 * Falls back to Material icon if favicon loading fails or is not available.
 */
@Composable
private fun BookmarkIcon(
    tabType: String,
    faviconCacheKey: String?,
    activeTabsProvider: ActiveTabsProvider?,
    size: Dp
) {
    // Try to load favicon for browser tabs
    val faviconPainter: Painter? = if (tabType == "browser" && faviconCacheKey != null && activeTabsProvider != null) {
        activeTabsProvider.loadFavicon(faviconCacheKey)
    } else {
        null
    }

    if (faviconPainter != null) {
        // Display the favicon
        androidx.compose.foundation.Image(
            painter = faviconPainter,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(2.dp))
        )
    } else {
        // Fallback to Material icon
        val icon = when (tabType) {
            "browser" -> Icons.Outlined.Language
            "editor" -> Icons.Outlined.Code
            "terminal" -> Icons.Outlined.Terminal
            else -> Icons.AutoMirrored.Outlined.InsertDriveFile
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(size),
            tint = MutedGrayText
        )
    }
}

// ==================== Collection Item ====================

@Composable
private fun CollectionItem(
    collection: BookmarkCollection,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onBookmarkClick: (Bookmark) -> Unit,
    searchQuery: String = "",
    contextMenuProvider: ContextMenuProvider?,
    activeTabsProvider: ActiveTabsProvider?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onBookmarkRemove: (Bookmark) -> Unit,
    onBookmarkCopy: (Bookmark) -> Unit,
    onBookmarkMove: (Bookmark) -> Unit
) {
    val filteredBookmarks = remember(collection.bookmarks, searchQuery) {
        filterBookmarks(collection.bookmarks, searchQuery)
    }

    val collectionMenuItems = buildList {
        if (!collection.isFavorite) {
            add(ContextMenuItemData("Rename Collection", Icons.Outlined.Edit) { onRename() })
        }
        add(ContextMenuItemData("Export Collection", Icons.Outlined.FileDownload) { })
        if (!collection.isFavorite) {
            add(ContextMenuItemData("", null, isDivider = true))
            add(ContextMenuItemData("Delete Collection", Icons.Outlined.Delete) { onDelete() })
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onToggleExpand() },
                tint = MutedGrayText
            )

            Spacer(modifier = Modifier.width(4.dp))

            val baseModifier = Modifier.weight(1f)
            val modifierWithContextMenu = if (contextMenuProvider != null) {
                contextMenuProvider.applyContextMenu(baseModifier, collectionMenuItems)
            } else {
                baseModifier
            }

            Row(
                modifier = modifierWithContextMenu,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MutedGrayText
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = collection.name,
                    fontSize = 13.sp,
                    color = LightGrayText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "(${filteredBookmarks.size})",
                    fontSize = 11.sp,
                    color = MutedGrayText
                )
            }
        }

        if (isExpanded) {
            if (filteredBookmarks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 44.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) "No bookmarks in this collection" else "No matching bookmarks",
                        fontSize = 12.sp,
                        color = MutedGrayText,
                        fontStyle = FontStyle.Italic
                    )
                }
            } else {
                Column(modifier = Modifier.padding(start = 20.dp)) {
                    filteredBookmarks.forEach { bookmark ->
                        BookmarkItem(
                            bookmark = bookmark,
                            onClick = { onBookmarkClick(bookmark) },
                            contextMenuProvider = contextMenuProvider,
                            activeTabsProvider = activeTabsProvider,
                            onRemove = { onBookmarkRemove(bookmark) },
                            onCopy = { onBookmarkCopy(bookmark) },
                            onMove = { onBookmarkMove(bookmark) }
                        )
                    }
                }
            }
        }
    }
}

// ==================== Workspace Item ====================

@Composable
private fun WorkspaceItem(
    workspace: LayoutWorkspace,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onWorkspaceClick: () -> Unit,
    onTabClick: (TabConfig) -> Unit,
    buildStructure: (SplitConfig) -> List<WorkspaceTabStructure>,
    isFavorite: Boolean = false,
    isCurrentWorkspace: Boolean = false,
    contextMenuProvider: ContextMenuProvider?,
    activeTabsProvider: ActiveTabsProvider?,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    viewModel: BookmarksViewModel
) {
    val workspaceMenuItems = buildList {
        add(ContextMenuItemData("Load Workspace", Icons.Outlined.FolderOpen) { onWorkspaceClick() })
        add(ContextMenuItemData("", null, isDivider = true))
        add(ContextMenuItemData(
            if (isFavorite) "Unfavorite Workspace" else "Favorite Workspace",
            if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder
        ) { onToggleFavorite() })
        add(ContextMenuItemData("", null, isDivider = true))
        if (workspace.name != "Last Session") {
            add(ContextMenuItemData("Rename Workspace", Icons.Outlined.Edit) { onRename() })
        }
        add(ContextMenuItemData("Export Workspace", Icons.Outlined.FileDownload) { onExport() })
        if (!isCurrentWorkspace && workspace.name != "Last Session") {
            add(ContextMenuItemData("", null, isDivider = true))
            add(ContextMenuItemData("Delete Workspace", Icons.Outlined.Delete) { onDelete() })
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onToggleExpand() },
                tint = MutedGrayText
            )

            Spacer(modifier = Modifier.width(4.dp))

            val baseModifier = Modifier
                .weight(1f)
                .clickable { onWorkspaceClick() }

            val modifierWithContextMenu = if (contextMenuProvider != null) {
                contextMenuProvider.applyContextMenu(baseModifier, workspaceMenuItems)
            } else {
                baseModifier
            }

            Row(
                modifier = modifierWithContextMenu,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isFavorite) GoldFavorite else MutedGrayText
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = workspace.name,
                    fontSize = 12.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = "Load workspace",
                    modifier = Modifier.size(14.dp),
                    tint = MutedGrayText
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onToggleFavorite() },
                tint = if (isFavorite) GoldFavorite else MutedGrayText
            )
        }

        if (isExpanded) {
            val tabStructure = buildStructure(workspace.layout)
            if (tabStructure.isEmpty()) {
                Text(
                    text = "No tabs",
                    fontSize = 11.sp,
                    color = MutedGrayText,
                    modifier = Modifier.padding(start = 44.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                RenderTabStructure(
                    structure = tabStructure,
                    workspaceName = workspace.name,
                    onTabClick = onTabClick,
                    contextMenuProvider = contextMenuProvider,
                    activeTabsProvider = activeTabsProvider,
                    viewModel = viewModel
                )
            }
        }
    }
}

// ==================== Tab Structure ====================

@Composable
private fun RenderTabStructure(
    structure: List<WorkspaceTabStructure>,
    workspaceName: String,
    onTabClick: (TabConfig) -> Unit,
    contextMenuProvider: ContextMenuProvider?,
    activeTabsProvider: ActiveTabsProvider?,
    viewModel: BookmarksViewModel,
    baseIndentation: Int = 44
) {
    structure.forEach { item ->
        when (item) {
            is WorkspaceTabStructure.TabItem -> {
                WorkspaceTabItem(
                    tabConfig = item.tabConfig,
                    workspaceName = workspaceName,
                    onClick = { onTabClick(item.tabConfig) },
                    contextMenuProvider = contextMenuProvider,
                    activeTabsProvider = activeTabsProvider,
                    viewModel = viewModel,
                    indentation = baseIndentation.dp
                )
            }

            is WorkspaceTabStructure.SplitSection -> {
                SplitSectionHeader(
                    sectionName = item.sectionName,
                    level = item.level
                )

                RenderTabStructure(
                    structure = item.children,
                    workspaceName = workspaceName,
                    onTabClick = onTabClick,
                    contextMenuProvider = contextMenuProvider,
                    activeTabsProvider = activeTabsProvider,
                    viewModel = viewModel,
                    baseIndentation = baseIndentation + (item.level * 16)
                )
            }
        }
    }
}

@Composable
private fun SplitSectionHeader(
    sectionName: String,
    level: Int
) {
    val indentation = (44 + (level * 16)).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentation, end = 24.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(1.dp)
                .background(DividerLineColor)
        )

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = sectionName,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MutedGrayText,
            letterSpacing = 0.5.sp
        )

        Spacer(modifier = Modifier.width(4.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(DividerLineColor)
        )
    }
}

@Composable
private fun WorkspaceTabItem(
    tabConfig: TabConfig,
    workspaceName: String,
    onClick: () -> Unit,
    contextMenuProvider: ContextMenuProvider?,
    activeTabsProvider: ActiveTabsProvider?,
    viewModel: BookmarksViewModel,
    indentation: Dp = 44.dp
) {
    val isBookmarked = remember(tabConfig) { viewModel.isTabBookmarked(tabConfig) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = indentation, end = 24.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BookmarkIcon(
            tabType = tabConfig.type,
            faviconCacheKey = tabConfig.faviconCacheKey,
            activeTabsProvider = activeTabsProvider,
            size = 14.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = tabConfig.title,
            fontSize = 11.sp,
            color = LightGrayText2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = if (isBookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = if (isBookmarked) "Remove bookmark" else "Add bookmark",
            modifier = Modifier.size(14.dp),
            tint = if (isBookmarked) GoldFavorite else MutedGrayText
        )
    }
}

// ==================== Search Bar ====================

@Composable
private fun BookmarkSearchBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.body2.copy(
            color = Color.White
        ),
        cursorBrush = SolidColor(GoldFavorite),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        SearchBackground,
                        RoundedCornerShape(4.dp)
                    )
                    .border(
                        1.dp,
                        BorderColor,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFF888888)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            "Search bookmarks, collections, workspaces...",
                            style = MaterialTheme.typography.body2,
                            color = Color(0xFF888888),
                            fontSize = 12.sp
                        )
                    }
                    innerTextField()
                }

                if (searchQuery.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Clear search",
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFF888888)
                        )
                    }
                }
            }
        }
    )
}

// ==================== Empty State ====================

@Composable
private fun EmptyState(
    icon: ImageVector,
    message: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MutedGrayText
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message,
            fontSize = 11.sp,
            color = MutedGrayText
        )
    }
}

// ==================== Toast Message ====================

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
            imageVector = if (isError) Icons.Filled.Error else Icons.Filled.CheckCircle,
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

// ==================== Dialogs ====================

@Composable
private fun NewCollectionDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Collection", color = LightGrayText) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Collection name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = SearchBackground,
                    textColor = Color.White
                )
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
        backgroundColor = DarkBackground,
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun NewWorkspaceDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Workspace", color = LightGrayText) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Workspace name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = SearchBackground,
                    textColor = Color.White
                )
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
        backgroundColor = DarkBackground,
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
        title = { Text("Delete Collection?", color = LightGrayText) },
        text = {
            Text(
                "Collection '${collection.name}' and all its bookmarks will be permanently deleted. " +
                "This action cannot be undone.",
                color = MutedGrayText
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
        backgroundColor = DarkBackground,
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun RenameCollectionDialog(
    collection: BookmarkCollection,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember { mutableStateOf(collection.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Collection", color = LightGrayText) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Collection name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = SearchBackground,
                    textColor = Color.White
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onRename(name) },
                enabled = name.isNotBlank() && name != collection.name
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        backgroundColor = DarkBackground,
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun DeleteWorkspaceDialog(
    workspace: LayoutWorkspace,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Workspace?", color = LightGrayText) },
        text = {
            Text(
                "Workspace '${workspace.name}' will be permanently deleted. " +
                "This action cannot be undone.",
                color = MutedGrayText
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
        backgroundColor = DarkBackground,
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun RenameWorkspaceDialog(
    workspace: LayoutWorkspace,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember { mutableStateOf(workspace.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Workspace", color = LightGrayText) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Workspace name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = SearchBackground,
                    textColor = Color.White
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onRename(name) },
                enabled = name.isNotBlank() && name != workspace.name
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        backgroundColor = DarkBackground,
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun ClearFavoritesDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear All Favorites?", color = LightGrayText) },
        text = {
            Text(
                "All favorited bookmarks will be removed. This action cannot be undone.",
                color = MutedGrayText
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))
            ) {
                Text("Clear All")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        backgroundColor = DarkBackground,
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun UnfavoriteAllWorkspacesDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unfavorite All Workspaces?", color = LightGrayText) },
        text = {
            Text(
                "All workspaces will be removed from favorites. The workspaces themselves will not be deleted.",
                color = MutedGrayText
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))
            ) {
                Text("Unfavorite All")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        backgroundColor = DarkBackground,
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun ConfirmRemoveBookmarkDialog(
    bookmark: Bookmark,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove Bookmark?", color = LightGrayText) },
        text = {
            Text(
                "Remove '${bookmark.tabConfig.title}' from this collection?",
                color = MutedGrayText
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))
            ) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        backgroundColor = DarkBackground,
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun CollectionSelectionDialog(
    title: String,
    collections: List<BookmarkCollection>,
    onDismiss: () -> Unit,
    onSelect: (BookmarkCollection) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = LightGrayText) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (collections.isEmpty()) {
                    Text(
                        "No other collections available",
                        color = MutedGrayText,
                        fontSize = 13.sp
                    )
                } else {
                    collections.forEach { collection ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(collection) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (collection.isFavorite) Icons.Filled.Star else Icons.Outlined.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (collection.isFavorite) GoldFavorite else MutedGrayText
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = collection.name,
                                fontSize = 13.sp,
                                color = LightGrayText
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        backgroundColor = DarkBackground,
        shape = RoundedCornerShape(8.dp)
    )
}

// ==================== Filtering Functions ====================

private fun filterBookmarks(bookmarks: List<Bookmark>, query: String): List<Bookmark> {
    if (query.isBlank()) return bookmarks
    val lowerQuery = query.lowercase()
    return bookmarks.filter { bookmark ->
        bookmark.tabConfig.title.lowercase().contains(lowerQuery) ||
        (bookmark.tabConfig.url?.lowercase()?.contains(lowerQuery) == true) ||
        bookmark.tags.any { it.lowercase().contains(lowerQuery) }
    }
}

private fun filterCollections(collections: List<BookmarkCollection>, query: String): List<BookmarkCollection> {
    if (query.isBlank()) return collections
    val lowerQuery = query.lowercase()
    return collections.filter { collection ->
        collection.name.lowercase().contains(lowerQuery)
    }
}

private fun filterWorkspaces(
    workspaces: List<LayoutWorkspace>,
    query: String,
    buildStructure: (SplitConfig) -> List<WorkspaceTabStructure>
): List<LayoutWorkspace> {
    if (query.isBlank()) return workspaces
    val lowerQuery = query.lowercase()
    return workspaces.filter { workspace ->
        workspace.name.lowercase().contains(lowerQuery) ||
        workspace.description.lowercase().contains(lowerQuery) ||
        extractTabTitles(buildStructure(workspace.layout)).any { tabTitle ->
            tabTitle.lowercase().contains(lowerQuery)
        }
    }
}

private fun extractTabTitles(structure: List<WorkspaceTabStructure>): List<String> {
    val titles = mutableListOf<String>()
    structure.forEach { item ->
        when (item) {
            is WorkspaceTabStructure.TabItem -> {
                titles.add(item.tabConfig.title)
            }
            is WorkspaceTabStructure.SplitSection -> {
                titles.addAll(extractTabTitles(item.children))
            }
        }
    }
    return titles
}
