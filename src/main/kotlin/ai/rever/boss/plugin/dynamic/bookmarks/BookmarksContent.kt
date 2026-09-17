package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkLibraryState
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.dynamic.bookmarks.manager.BookmarkManager
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossThemeColors
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

// Theme-aware chrome colors sourced from the host's reactive BOSS theme tokens.
// These re-skin automatically when the host theme changes.
private val DarkBackground get() = BossThemeColors.BackgroundColor
private val SearchBackground get() = BossThemeColors.SurfaceColor
private val BorderColor get() = BossThemeColors.BorderColor
private val DividerLineColor get() = BossThemeColors.BorderColor
private val LightGrayText get() = BossThemeColors.TextPrimary
private val MutedGrayText get() = BossThemeColors.TextSecondary
private val LightGrayText2 get() = BossThemeColors.TextSecondary
private val AccentColor get() = BossThemeColors.AccentColor

// Brand color: the gold favorite/star accent is intentionally fixed (not generic chrome).
private val GoldFavorite = Color(0xFFFBBF24)

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
    val libraryState = viewModel.library?.state?.collectAsState()?.value
    val undoToken by viewModel.undoToken.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()
    val favoriteWorkspaces by viewModel.favoriteWorkspaces.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val currentWorkspace by viewModel.currentWorkspace.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    // Section expansion states
    var workspaceView by remember { mutableStateOf(false) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var allWorkspacesExpanded by remember { mutableStateOf(false) }
    var favoriteWorkspacesExpanded by remember { mutableStateOf(true) }

    // Track expansion state for each collection and workspace.
    //
    // Collections are tracked by LazyColumn item key, workspaces by workspace id,
    // and the asymmetry is deliberate. A collection appears once, so the item key
    // is the better identity — should two ever share an id, keying on the id would
    // expand and collapse them together. A workspace appears in *both* the
    // "Favorite Workspaces" and "All Workspaces" sections, where its item keys
    // differ (`fav-ws:` vs `ws:`); keying on the id is what keeps the two rows for
    // one workspace expanding in sync.
    //
    // One axis where the item key is *not* strictly better: its disambiguation
    // suffix is positional within the filtered list, so if a duplicate id ever did
    // reach the panel, typing in the search box could move which row owns the
    // "expanded" key. That only bites in the case ids are repaired to prevent, and
    // the cost is a wrongly-expanded row rather than a crash.
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
    var bookmarkToRename by remember { mutableStateOf<Pair<Bookmark, String>?>(null) }
    var bookmarkToRemove by remember { mutableStateOf<Pair<Bookmark, String>?>(null) }
    var bookmarkToCopy by remember { mutableStateOf<Pair<Bookmark, String>?>(null) }
    var bookmarkToMove by remember { mutableStateOf<Pair<Bookmark, String>?>(null) }

    // Filtered data based on search query.
    //
    // Each list is paired with LazyColumn item keys up front — see [keyedUniquely]
    // for why the keys cannot be `it.id` on its own.
    //
    val favoriteIds = libraryState?.favoriteBookmarkIds
        ?: collections.filter { it.isFavorite }.flatMap { it.bookmarks }.map { it.id }.toSet()
    val filteredCollections = remember(collections, searchQuery, favoriteIds, favoritesOnly) {
        val visible = if (favoritesOnly) collections.map { collection ->
            collection.copy(bookmarks = collection.bookmarks.filter { it.id in favoriteIds })
        }.filter { it.bookmarks.isNotEmpty() } else collections
        filterCollections(visible, searchQuery).keyedUniquely("coll") { it.id }
    }
    val filteredFavoriteWorkspaces = remember(favoriteWorkspaces, workspaces, searchQuery) {
        val favoriteWorkspacesList = favoriteWorkspaces.mapNotNull { fav ->
            workspaces.find { it.id == fav.workspaceId }
        }
        filterWorkspaces(favoriteWorkspacesList, searchQuery) { viewModel.buildTabStructure(it) }
            .keyedUniquely("fav-ws") { it.id }
    }
    val filteredAllWorkspaces = remember(workspaces, searchQuery) {
        filterWorkspaces(workspaces, searchQuery) { viewModel.buildTabStructure(it) }
            .keyedUniquely("ws") { it.id }
    }

    val unfiledIds = libraryState?.unfiledCollectionIds.orEmpty()
    val rootBookmarks = filteredCollections.filter { it.value.id in unfiledIds }
        .flatMap { entry -> filterBookmarks(entry.value.bookmarks, searchQuery).map { entry.value.id to it } }
    val visibleFolders = filteredCollections.filterNot { it.value.id in unfiledIds }

    // Scrollbar state
    val listState = rememberLazyListState()

    // Takes a LazyColumn item key, not a collection id — see expandedCollections.
    fun toggleCollectionExpansion(itemKey: String) {
        expandedCollections = if (expandedCollections.contains(itemKey)) {
            expandedCollections - itemKey
        } else {
            expandedCollections + itemKey
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
        TabRow(selectedTabIndex = if (workspaceView) 1 else 0, backgroundColor = DarkBackground, contentColor = AccentColor) {
            Tab(selected = !workspaceView, selectedContentColor = AccentColor, unselectedContentColor = MutedGrayText, onClick = { workspaceView = false; viewModel.updateSearchQuery("") }, modifier = Modifier.height(40.dp).testTag("bookmarks-view")) { Text("Bookmarks", modifier = Modifier.padding(horizontal = 4.dp), fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            Tab(selected = workspaceView, selectedContentColor = AccentColor, unselectedContentColor = MutedGrayText, onClick = { workspaceView = true; viewModel.updateSearchQuery("") }, modifier = Modifier.height(40.dp).testTag("workspaces-view")) { Text("Workspaces", modifier = Modifier.padding(horizontal = 4.dp), fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
        }
        if (!workspaceView) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { favoritesOnly = false }, modifier = Modifier.testTag("all-filter")) { Text("All (${collections.sumOf { it.bookmarks.size }})", color = if (!favoritesOnly) AccentColor else MutedGrayText) }
            TextButton(onClick = { favoritesOnly = true }, modifier = Modifier.testTag("favorites-filter")) { Text("Favorites (${favoriteIds.size})", color = if (favoritesOnly) AccentColor else MutedGrayText) }
        }
        // Search bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookmarkSearchBar(
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                placeholder = if (workspaceView) "Search workspaces…" else "Search bookmarks…"
            )
        }

        if (libraryState != null && !libraryState.ready && libraryState.error == null) {
            Text("Loading bookmarks…", modifier = Modifier.padding(12.dp))
        }
        if (libraryState?.error != null) {
            Text(libraryState.error.orEmpty(), color = MaterialTheme.colors.error, modifier = Modifier.padding(12.dp))
            TextButton(onClick = viewModel::reloadLibrary) { Text("Reload library") }
        }
        if (undoToken != null) TextButton(onClick = viewModel::undoDelete, enabled = !busy) { Text("Undo delete") }
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
            if (!workspaceView) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showNewCollectionDialog = true }) { Text("New folder", maxLines = 1, softWrap = false) }
                }
            }
            if (rootBookmarks.isEmpty() && visibleFolders.isEmpty()) item {
                EmptyState(Icons.Outlined.Bookmarks, when {
                    searchQuery.isNotBlank() -> "No matching bookmarks"
                    favoritesOnly -> "No favorites yet. Star a saved bookmark to add it here."
                    else -> "No bookmarks yet. Save a tab to keep it here."
                })
            }
                items(rootBookmarks, key = { "flat:${it.second.id}" }) { (sourceId, bookmark) ->
                    BookmarkItem(
                        bookmark = bookmark,
                        onClick = { viewModel.onBookmarkClick(bookmark, coroutineScope) },
                        contextMenuProvider = contextMenuProvider,
                        activeTabsProvider = activeTabsProvider,
                        onRename = { bookmarkToRename = bookmark to sourceId },
                        onRemove = { bookmarkToRemove = bookmark to sourceId },
                        onCopy = { bookmarkToCopy = bookmark to sourceId },
                        onMove = { bookmarkToMove = bookmark to sourceId },
                        favorite = bookmark.id in favoriteIds,
                        onToggleFavorite = { viewModel.setFavorite(bookmark.id, bookmark.id !in favoriteIds) },
                        onOpenNew = { viewModel.openBookmark(bookmark, true) },
                    )
                }
                    items(visibleFolders, key = { it.key }) { (itemKey, collection) ->
                        CollectionItem(
                            collection = collection,
                            sourceCollection = collections.firstOrNull { it.id == collection.id } ?: collection,
                            // Tracked by item key, not collection id: should two
                            // collections ever share an id, keying on it would
                            // expand and collapse them together.
                            isExpanded = expandedCollections.contains(itemKey) || searchQuery.isNotBlank() || favoritesOnly,
                            onToggleExpand = { toggleCollectionExpansion(itemKey) },
                            onBookmarkClick = { bookmark ->
                                viewModel.onBookmarkClick(bookmark, coroutineScope)
                            },
                            searchQuery = searchQuery,
                            contextMenuProvider = contextMenuProvider,
                            activeTabsProvider = activeTabsProvider,
                            onRename = { collectionToRename = collections.firstOrNull { it.id == collection.id } },
                            onDelete = { collectionToDelete = collections.firstOrNull { it.id == collection.id } },
                            onBookmarkRename = { bookmark -> bookmarkToRename = Pair(bookmark, collection.id) },
                            onBookmarkRemove = { bookmark -> bookmarkToRemove = Pair(bookmark, collection.id) },
                            onBookmarkCopy = { bookmark -> bookmarkToCopy = Pair(bookmark, collection.id) },
                            onBookmarkMove = { bookmark -> bookmarkToMove = Pair(bookmark, collection.id) },
                            favoriteIds = favoriteIds,
                            onToggleFavorite = { viewModel.setFavorite(it.id, it.id !in favoriteIds) },
                            onOpenNew = { viewModel.openBookmark(it, true) },
                        )
                    }

            } else {
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
                            add(ContextMenuItemData("Unfavorite All", Icons.Outlined.DeleteSweep, onClick = {
                                showUnfavoriteAllWorkspacesDialog = true
                            }))
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
                    items(filteredFavoriteWorkspaces, key = { it.key }) { (_, workspace) ->
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
                        ContextMenuItemData("New Workspace", Icons.Outlined.CreateNewFolder, onClick = {
                            showNewWorkspaceDialog = true
                        })
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
                    items(filteredAllWorkspaces, key = { it.key }) { (_, workspace) ->
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

            }
            // Bottom spacer
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Dialogs
    if (showNewCollectionDialog) {
        LibraryNameDialog("New Folder", "", busy, errorMessage,
            onDismiss = { showNewCollectionDialog = false },
            onConfirm = { name -> viewModel.createCollection(name) { showNewCollectionDialog = false } },
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
        SafeDeleteCollectionDialog(
            collection, collections, busy, errorMessage,
            unfiledIds = unfiledIds,
            onDismiss = { collectionToDelete = null },
            onConfirm = { destination -> viewModel.deleteCollection(collection.id, destination) { collectionToDelete = null } },
        )
    }

    collectionToRename?.let { collection ->
        val revision = remember(collection.id) { libraryState?.revision }
        LibraryNameDialog("Rename Folder", collection.name, busy, errorMessage,
            onDismiss = { collectionToRename = null },
            onConfirm = { name -> viewModel.renameCollection(collection.id, name, revision) { collectionToRename = null } },
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
                viewModel.clearFavorites { showClearFavoritesDialog = false }
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

    bookmarkToRename?.let { (bookmark, collectionId) ->
        LibraryEditDialog(
            bookmark = bookmark,
            collectionId = collectionId,
            state = libraryState ?: BookmarkLibraryState(collections = collections),
            busy = busy,
            error = errorMessage,
            onDismiss = { bookmarkToRename = null },
            onSave = { name, target, collection, favorite, revision ->
                viewModel.saveEdit(bookmark, name, target, collection, favorite, revision) { bookmarkToRename = null }
            },
        )
    }

    bookmarkToRemove?.let { (bookmark, collectionId) ->
        val revision = remember(bookmark.id) { libraryState?.revision }
        ConfirmRemoveBookmarkDialog(
            bookmark = bookmark,
            onDismiss = { bookmarkToRemove = null },
            onConfirm = {
                viewModel.removeBookmark(collectionId, bookmark.id, revision) { bookmarkToRemove = null }
            }
        )
    }

    bookmarkToCopy?.let { (bookmark, fromCollectionId) ->
        CollectionSelectionDialog(
            title = "Copy Bookmark To",
            unfiledIds = unfiledIds,
            collections = collections.filter { !it.isFavorite && it.id != fromCollectionId },
            onDismiss = { bookmarkToCopy = null },
            onSelect = { targetCollection ->
                viewModel.copyBookmark(targetCollection.name, bookmark) { bookmarkToCopy = null }
            }
        )
    }

    bookmarkToMove?.let { (bookmark, fromCollectionId) ->
        CollectionSelectionDialog(
            title = "Move Bookmark To",
            unfiledIds = unfiledIds,
            collections = collections.filter { it.id != fromCollectionId },
            onDismiss = { bookmarkToMove = null },
            onSelect = { targetCollection ->
                viewModel.moveBookmark(bookmark.id, fromCollectionId, targetCollection.id) { bookmarkToMove = null }
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
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenNew: () -> Unit,
) {
    val contextMenuItems = listOf(
        ContextMenuItemData("Open", Icons.AutoMirrored.Outlined.OpenInNew, onClick = onClick),
        ContextMenuItemData("Open in New Tab", Icons.Outlined.Add, onClick = onOpenNew),
        ContextMenuItemData("Edit Bookmark", Icons.Outlined.Edit, onClick = onRename),
        ContextMenuItemData(if (favorite) "Remove from Favorites" else "Show in Favorites", Icons.Outlined.Star, onClick = onToggleFavorite),
        ContextMenuItemData("", null, isDivider = true),
        ContextMenuItemData("Delete Bookmark", Icons.Outlined.Delete, onClick = { onRemove() }),
        ContextMenuItemData("", null, isDivider = true),
        ContextMenuItemData("Copy to Folder", Icons.Outlined.ContentCopy, onClick = { onCopy() }),
        ContextMenuItemData("Move to Folder", Icons.AutoMirrored.Outlined.DriveFileMove, onClick = { onMove() })
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
            color = BossThemeColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = if (favorite) "Remove from Favorites" else "Show in Favorites",
            modifier = Modifier
                .size(16.dp)
                .clickable(onClick = onToggleFavorite),
            tint = if (favorite) AccentColor else MutedGrayText
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
            "diff" -> Icons.Outlined.Difference
            "composer" -> Icons.Outlined.SmartToy
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
    sourceCollection: BookmarkCollection = collection,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onBookmarkClick: (Bookmark) -> Unit,
    searchQuery: String = "",
    contextMenuProvider: ContextMenuProvider?,
    activeTabsProvider: ActiveTabsProvider?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onBookmarkRename: (Bookmark) -> Unit,
    onBookmarkRemove: (Bookmark) -> Unit,
    onBookmarkCopy: (Bookmark) -> Unit,
    onBookmarkMove: (Bookmark) -> Unit,
    favoriteIds: Set<String>,
    onToggleFavorite: (Bookmark) -> Unit,
    onOpenNew: (Bookmark) -> Unit,
) {
    val filteredBookmarks = remember(collection.bookmarks, searchQuery) {
        filterBookmarks(collection.bookmarks, searchQuery)
    }

    val clipboard = LocalClipboardManager.current
    val collectionMenuItems = buildList {
        if (!collection.isFavorite) {
            add(ContextMenuItemData("Rename Folder", Icons.Outlined.Edit, onClick = { onRename() }))
        }
        add(ContextMenuItemData("Copy Folder JSON", Icons.Outlined.ContentCopy, onClick = {
            val json = Json { prettyPrint = true; encodeDefaults = true }
            clipboard.setText(AnnotatedString(json.encodeToString(ListSerializer(BookmarkCollection.serializer()), listOf(sourceCollection))))
        }))
        if (!collection.isFavorite) {
            add(ContextMenuItemData("", null, isDivider = true))
            add(ContextMenuItemData("Delete Folder", Icons.Outlined.Delete, onClick = { onDelete() }))
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The whole row toggles, not just the chevron. A 16.dp icon is a
                // small target, and clicking anywhere else — the folder icon, the
                // name, the count — used to do nothing at all, which reads as the
                // panel being broken rather than as "aim for the arrow".
                //
                // `clickable` before `padding` so the padded area is part of the
                // target, matching BookmarkItem. The context-menu modifier on the
                // inner row only consumes secondary presses, so a left click still
                // reaches this handler.
                .clickable { onToggleExpand() }
                .padding(horizontal = 24.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                // No clickable of its own: the row handles the gesture now, and a
                // nested one would put a second ripple over the same tap.
                modifier = Modifier.size(16.dp),
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
                    text = collection.folderDisplayName(),
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
                        text = if (searchQuery.isBlank()) "No bookmarks in this folder" else "No matching bookmarks",
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
                            onRename = { onBookmarkRename(bookmark) },
                            onRemove = { onBookmarkRemove(bookmark) },
                            onCopy = { onBookmarkCopy(bookmark) },
                            onMove = { onBookmarkMove(bookmark) },
                            favorite = bookmark.id in favoriteIds,
                            onToggleFavorite = { onToggleFavorite(bookmark) },
                            onOpenNew = { onOpenNew(bookmark) },
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
        add(ContextMenuItemData("Load Workspace", Icons.Outlined.FolderOpen, onClick = { onWorkspaceClick() }))
        add(ContextMenuItemData("", null, isDivider = true))
        add(ContextMenuItemData(
            if (isFavorite) "Unfavorite Workspace" else "Favorite Workspace",
            if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            onClick = { onToggleFavorite() }
        ))
        add(ContextMenuItemData("", null, isDivider = true))
        if (workspace.name != "Last Session") {
            add(ContextMenuItemData("Rename Workspace", Icons.Outlined.Edit, onClick = { onRename() }))
        }
        add(ContextMenuItemData("Export Workspace", Icons.Outlined.FileDownload, onClick = { onExport() }))
        if (!isCurrentWorkspace && workspace.name != "Last Session") {
            add(ContextMenuItemData("", null, isDivider = true))
            add(ContextMenuItemData("Delete Workspace", Icons.Outlined.Delete, onClick = { onDelete() }))
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
                    color = BossThemeColors.TextPrimary,
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
    modifier: Modifier = Modifier,
    placeholder: String = "Search bookmarks…"
) {
    BasicTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.body2.copy(
            color = BossThemeColors.TextPrimary
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
                    tint = BossThemeColors.TextSecondary
                )

                Spacer(modifier = Modifier.width(6.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.body2,
                            color = BossThemeColors.TextSecondary,
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
                            tint = BossThemeColors.TextSecondary
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
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

    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Folder", color = LightGrayText) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Folder name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = SearchBackground,
                    textColor = BossThemeColors.TextPrimary
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

    BossAlertDialog(
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
                    textColor = BossThemeColors.TextPrimary
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
    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Folder?", color = LightGrayText) },
        text = {
            Text(
                "Folder '${collection.folderDisplayName()}' and all its bookmarks will be permanently deleted. " +
                "This action cannot be undone.",
                color = MutedGrayText
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = BossThemeColors.ErrorColor)
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

    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Folder", color = LightGrayText) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Folder name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = SearchBackground,
                    textColor = BossThemeColors.TextPrimary
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
    BossAlertDialog(
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
                colors = ButtonDefaults.textButtonColors(contentColor = BossThemeColors.ErrorColor)
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

    BossAlertDialog(
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
                    textColor = BossThemeColors.TextPrimary
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
    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear All Favorites?", color = LightGrayText) },
        text = {
            Text(
                "Bookmarks will be removed from the Favorites shelf. They remain saved in All Bookmarks.",
                color = MutedGrayText
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = BossThemeColors.ErrorColor)
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
    BossAlertDialog(
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
                colors = ButtonDefaults.textButtonColors(contentColor = BossThemeColors.ErrorColor)
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
private fun RenameBookmarkDialog(
    bookmark: Bookmark,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    // Keyed by id: if the panel ever swaps the target bookmark without the
    // dialog leaving composition, an unkeyed remember would keep the previous
    // bookmark's title in the field while renaming the new one.
    var title by remember(bookmark.id) { mutableStateOf(bookmark.tabConfig.title) }
    val trimmed = title.trim()

    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Bookmark", color = LightGrayText) },
        text = {
            TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Bookmark name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = SearchBackground,
                    textColor = BossThemeColors.TextPrimary
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (trimmed.isNotEmpty()) onRename(trimmed) },
                // Compared against the trimmed value so adding trailing
                // whitespace does not look like a rename that then no-ops.
                enabled = trimmed.isNotEmpty() && trimmed != bookmark.tabConfig.title
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
private fun ConfirmRemoveBookmarkDialog(
    bookmark: Bookmark,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Bookmark?", color = LightGrayText) },
        text = {
            Text(
                "Delete '${bookmark.tabConfig.title}'? Open tabs stay open. You can undo this deletion.",
                color = MutedGrayText
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = BossThemeColors.ErrorColor)
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
private fun CollectionSelectionDialog(
    title: String,
    unfiledIds: Set<String>,
    collections: List<BookmarkCollection>,
    onDismiss: () -> Unit,
    onSelect: (BookmarkCollection) -> Unit
) {
    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = LightGrayText) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (collections.isEmpty()) {
                    Text(
                        "No other folders available",
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
                                text = collection.folderDisplayName(unfiledIds),
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
        bookmark.tags.any { it.lowercase().contains(lowerQuery) } ||
        bookmark.notes.lowercase().contains(lowerQuery) ||
        bookmark.tabConfig.filePath?.lowercase()?.contains(lowerQuery) == true ||
        bookmark.tabConfig.workingDirectory?.lowercase()?.contains(lowerQuery) == true
    }
}

private fun filterCollections(collections: List<BookmarkCollection>, query: String): List<BookmarkCollection> {
    if (query.isBlank()) return collections
    val lowerQuery = query.lowercase()
    return collections.filter { collection ->
        collection.name.lowercase().contains(lowerQuery) || filterBookmarks(collection.bookmarks, query).isNotEmpty()
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
