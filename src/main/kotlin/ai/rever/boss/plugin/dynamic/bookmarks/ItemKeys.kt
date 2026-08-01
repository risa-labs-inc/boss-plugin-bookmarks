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
 * Every section is an item in one shared LazyColumn, so keys must not collide
 * across sections either. Delimiting keeps sections apart; length-prefixing the
 * id makes the encoding unambiguous whatever an id or a section name contains,
 * so no `(section, id)` pair can spell another pair's key — nor can a generated
 * `#n` suffix spell a literal id. `ItemKeysTest` enumerates these cases.
 *
 * Lives outside the Compose file so it can be unit-tested.
 */
internal fun <T> List<T>.keyedUniquely(section: String, id: (T) -> String): List<Keyed<T>> {
    // Sized past the 0.75 load factor: HashSet(size) resizes once on the way to
    // holding `size` elements.
    val seen = HashSet<String>((size / 0.75f).toInt() + 1)

    // Where to resume probing per base key. Without it every duplicate restarts
    // at 2 and re-walks the suffixes already handed out, making a group of n
    // identical ids cost O(n²). This function only does any work at all when
    // repair did *not* reach the data, so it is precisely the place that should
    // assume pathological input rather than assume ids are nearly unique.
    val nextRepeat = HashMap<String, Int>()

    return map { item ->
        val rawId = id(item)
        val base = "$section:${rawId.length}:$rawId"
        var key = base
        if (!seen.add(key)) {
            var repeat = nextRepeat[base] ?: 2
            key = "$base#$repeat"
            while (!seen.add(key)) {
                repeat++
                key = "$base#$repeat"
            }
            nextRepeat[base] = repeat + 1
        }
        Keyed(key, item)
    }
}
