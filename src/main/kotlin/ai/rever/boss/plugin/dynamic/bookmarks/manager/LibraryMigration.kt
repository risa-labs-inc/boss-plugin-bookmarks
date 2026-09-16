package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.BookmarkCollection
import java.util.UUID

/** Preserve each legacy copy and its metadata; only colliding identifiers need aliases. */
internal fun importLegacyLibrary(
    current: LibraryDocument?,
    legacy: List<BookmarkCollection>,
    fingerprint: String?,
): LibraryDocument {
    val base = current ?: LibraryDocument()
    val unfiled = (base.unfiledCollectionIds ?: legacyUnfiledIds(base)).toMutableSet()
    val collections = base.collections.toMutableList()
    val collectionIds = collections.map { it.id }.toMutableSet()
    val bookmarkIds = collections.flatMap { it.bookmarks }.map { it.id }.toMutableSet()
    val favorites = base.favoriteBookmarkIds.toMutableSet()
    val aliases = base.legacyIdentityAliases.toMutableMap()
    legacy.forEachIndexed { index, collection ->
        val prior = base.legacyImportedCollections.getOrNull(index)?.takeIf { it.id == collection.id }
        val incoming = if (current == null) collection.bookmarks else collection.bookmarks.filterNot { it in prior?.bookmarks.orEmpty() }
        if (current != null && incoming.isEmpty()) return@forEachIndexed
        val collectionId = if (collectionIds.add(collection.id)) collection.id else "collection-${UUID.randomUUID()}".also {
            collectionIds.add(it); aliases["collection:$index:${collection.id}"] = it
        }
        if (collection.isFavorite) unfiled.add(collectionId)
        val bookmarks = incoming.mapIndexed { bookmarkIndex, bookmark ->
            val id = if (bookmarkIds.add(bookmark.id)) bookmark.id else "bookmark-${UUID.randomUUID()}".also {
                bookmarkIds.add(it); aliases["bookmark:$index:$bookmarkIndex:${bookmark.id}"] = it
            }
            favorites.add(id)
            bookmark.copy(id = id)
        }
        val name = if (collection.isFavorite) {
            var candidate = "Unsorted"
            var suffix = 2
            while (collections.any { it.name == candidate } || legacy.any { !it.isFavorite && it.name == candidate }) candidate = "Unsorted ${suffix++}"
            candidate
        } else collection.name
        collections += collection.copy(id = collectionId, name = name, bookmarks = bookmarks, isFavorite = false)
    }
    if (collections.isEmpty()) {
        val default = BookmarkCollection(id = "collection-${UUID.randomUUID()}", name = "Bookmarks")
        collections += default
        unfiled.add(default.id)
    }
    return base.copy(
        collections = collections,
        unfiledCollectionIds = unfiled,
        favoriteBookmarkIds = favorites,
        legacyFingerprint = fingerprint,
        legacyImportedCollections = legacy,
        legacyIdentityAliases = aliases,
    )
}

/** Only explicit legacy favorite provenance can identify an old default safely. */
internal fun legacyUnfiledIds(document: LibraryDocument): Set<String> = document.legacyImportedCollections
    .mapIndexedNotNull { index, collection ->
        if (!collection.isFavorite) null else document.legacyIdentityAliases["collection:$index:${collection.id}"] ?: collection.id
    }.filter { id -> document.collections.any { it.id == id } }.toSet()
