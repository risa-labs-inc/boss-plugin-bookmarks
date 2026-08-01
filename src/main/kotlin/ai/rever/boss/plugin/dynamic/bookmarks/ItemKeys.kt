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
 * section `fav-ws` with a workspace id of `1`. Splitting on a delimiter no
 * section literal contains removes that.
 *
 * The length does two further things the delimiter cannot.
 *
 * It stops a generated suffix aliasing a *literal* id. Keyed `<section>:<id>`,
 * `["a", "a", "a#2"]` gives the duplicate `coll:a#2` — exactly what the third
 * element's own id produces. The `while` loop below still resolves that
 * (`coll:a#2#2`), so it was never a live crash, but the length removes the
 * overlap between the id and suffix namespaces at the source.
 *
 * It also makes the "no section contains `:`" rule a convenience rather than a
 * requirement. A section named `fav:ws` would break a bare `<section>:<id>`
 * scheme — `("fav:ws", "1")` and `("fav", "ws:1")` both give `fav:ws:1` — but
 * cannot break this one, because the middle field is a number and so can never
 * be read as part of a section name. For the same reason an id that itself
 * contains `:` cannot forge another key, which escaping a delimiter would not
 * guarantee.
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
