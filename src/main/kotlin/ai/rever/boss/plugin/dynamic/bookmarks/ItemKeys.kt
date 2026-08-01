package ai.rever.boss.plugin.dynamic.bookmarks

/** An element paired with the LazyColumn item key it is rendered under. */
internal data class Keyed<T>(val key: String, val value: T)

/**
 * Pair each element with an item key that is unique within the list, suffixing
 * repeats.
 *
 * ### Why uniqueness matters
 *
 * A LazyColumn key must be unique. Two items sharing one key share a single
 * subcomposition slot — and therefore a single `LayoutNode` — so the list
 * measures that one node twice in a pass and Compose throws
 * `IllegalStateException: layout state is not idle before measure starts`.
 * Collapsed rows are the same height and can hide it; expanding one of the pair
 * changes its size and triggers the second measure. That is the crash this
 * whole file exists to prevent (BossConsole-Releases#16).
 *
 * Ids are supposed to be unique and [manager.BookmarkManager.withDistinctIds]
 * repairs them on load, so in practice nothing is suffixed here. This is the
 * backstop that keeps *any* duplicate — a hand-edited file, a future host path
 * that inserts collections directly — from taking the whole panel down.
 *
 * ### Why the key is `<section>:<id length>:<id>`
 *
 * Two separate hazards, and it is worth keeping them straight — the obvious
 * reason is not the one the length solves.
 *
 * The `:` handles sections. Every section is an item in one shared LazyColumn,
 * so keys must not collide across sections either, and `-` concatenation was
 * ambiguous: section `fav` with a bookmark id of `ws-1` produced the same key as
 * section `fav-ws` with a workspace id of `1`. No section literal contains `:`,
 * so the section is always the text before the first colon and that ambiguity is
 * gone. The delimiter alone is enough for this part.
 *
 * The length handles the disambiguation suffix. Without it, a generated key can
 * alias a *literal* id: for `["a", "a", "a#2"]` the second element is keyed
 * `coll:a#2`, which is exactly what the third element's own id produces. The
 * `while` loop below still resolves it (`coll:a#2#2`), so this was never a live
 * crash — but the id and suffix namespaces overlapping is the thing the length
 * prefix removes at the source. Length-prefixing rather than escaping also means
 * an id that itself contains `:` cannot forge another key.
 *
 * Lives outside the Compose file so it can be unit-tested.
 */
internal fun <T> List<T>.keyedUniquely(section: String, id: (T) -> String): List<Keyed<T>> {
    // Sized past the 0.75 load factor: HashSet(size) resizes once on the way to
    // holding `size` elements.
    val seen = HashSet<String>((size / 0.75f).toInt() + 1)
    return map { item ->
        val rawId = id(item)
        val base = "$section:${rawId.length}:$rawId"
        var key = base
        var repeat = 2
        while (!seen.add(key)) {
            key = "$base#$repeat"
            repeat++
        }
        Keyed(key, item)
    }
}
