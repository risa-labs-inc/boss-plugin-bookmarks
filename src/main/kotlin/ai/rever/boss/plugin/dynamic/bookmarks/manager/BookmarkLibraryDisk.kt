package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class DeletedLibraryBookmark(
    val token: String,
    val collectionId: String,
    val bookmark: Bookmark,
    val favorite: Boolean,
    val collectionName: String = "Restored bookmarks",
)

@Serializable
internal data class LibraryDocument(
    val unfiledCollectionIds: Set<String>? = null,
    val schemaVersion: Int = 1,
    val revision: Long = 0,
    val collections: List<BookmarkCollection> = emptyList(),
    val favoriteBookmarkIds: Set<String> = emptySet(),
    val defaultFavorite: Boolean = true,
    val legacyFingerprint: String? = null,
    val deleted: List<DeletedLibraryBookmark> = emptyList(),
    val legacyIdentityAliases: Map<String, String> = emptyMap(),
    val legacyImportedCollections: List<BookmarkCollection> = emptyList(),
)

internal data class LibraryDiskSnapshot(val document: LibraryDocument?, val fingerprint: String?, val legacyFingerprint: String?)

/** One durable document is the commit boundary; legacy source files are never overwritten. */
internal open class BookmarkLibraryDisk(
    directory: String = BookmarkFileManager.defaultBookmarksDirectory(),
) {
    private val directory = File(directory)
    private val canonical = File(directory, "bookmark-library.json")
    private val legacy = File(directory, BookmarkFileManager.COLLECTIONS_FILE)
    private val files = BookmarkFileManager(directory)
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val processLock = locks.computeIfAbsent(canonical.absolutePath) { Mutex() }

    open suspend fun snapshot(): LibraryDiskSnapshot = withContext(Dispatchers.IO) {
        val bytes = if (canonical.exists()) canonical.readBytes() else null
        val document = bytes?.let {
            val text = it.decodeToString()
            val fields = json.parseToJsonElement(text).jsonObject
            require(fields.keys.containsAll(setOf("schemaVersion", "revision", "collections", "favoriteBookmarkIds"))) {
                "The bookmark library is incomplete. Restore its original file before saving."
            }
            json.decodeFromString<LibraryDocument>(text)
        }
        require(document == null || document.schemaVersion == 1) { "This bookmark library needs a newer BOSS version." }
        if (document != null) {
            val collectionIds = document.collections.map { it.id }
            val bookmarkIds = document.collections.flatMap { it.bookmarks }.map { it.id }
            require(collectionIds.isNotEmpty() && collectionIds.distinct().size == collectionIds.size && bookmarkIds.distinct().size == bookmarkIds.size && document.favoriteBookmarkIds.all { it in bookmarkIds } && document.revision >= 0) {
                "The bookmark library contains invalid identities. Restore its original file before saving."
            }
        }
        LibraryDiskSnapshot(document, bytes?.hash(), fingerprint(legacy))
    }

    open suspend fun legacyCollections(): List<BookmarkCollection> = withContext(Dispatchers.IO) {
        if (!legacy.exists()) emptyList() else BookmarkSerializer.deserializeCollections(legacy.readText())
    }

    open suspend fun commit(expectedFingerprint: String?, document: LibraryDocument): String = withContext(Dispatchers.IO) {
        processLock.withLock {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create the bookmark library folder." }
            FileChannel.open(File(directory, "bookmark-library.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    check(fingerprint(canonical) == expectedFingerprint) { "Bookmarks changed in another window or app. Reload before saving." }
                    check(fingerprint(legacy) == document.legacyFingerprint) { "Legacy bookmarks changed outside this library. Reload to import those changes." }
                    val content = json.encodeToString(LibraryDocument.serializer(), document)
                    files.writeAtomically(canonical.absolutePath, content)
                    content.toByteArray(Charsets.UTF_8).hash()
                }
            }
        }
    }

    private fun fingerprint(file: File): String? = if (file.exists()) file.readBytes().hash() else null
    private fun ByteArray.hash(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
