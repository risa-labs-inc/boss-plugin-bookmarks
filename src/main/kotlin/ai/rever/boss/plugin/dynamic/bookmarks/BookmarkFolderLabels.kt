package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.bookmark.BookmarkCollection

internal fun BookmarkCollection.folderDisplayName(unfiledIds: Set<String> = emptySet()): String = if (id in unfiledIds) "No folder" else name
