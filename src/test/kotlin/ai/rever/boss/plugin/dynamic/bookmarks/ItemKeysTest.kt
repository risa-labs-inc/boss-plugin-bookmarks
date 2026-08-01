package ai.rever.boss.plugin.dynamic.bookmarks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [keyedUniquely], the panel's last line of defence against the crash
 * this plugin was fixed for.
 *
 * Duplicate ids are repaired in BookmarkManager, so these keys are what stands
 * between any duplicate that repair does not reach — a hand-edited file, a host
 * path that inserts collections directly — and
 * `IllegalStateException: layout state is not idle before measure starts`.
 */
class ItemKeysTest {
    private fun keysOf(section: String, ids: List<String>): List<String> =
        ids.keyedUniquely(section) { it }.map { it.key }

    @Test
    fun `distinct ids keep their values paired with their keys`() {
        val keyed = listOf("a", "b").keyedUniquely("coll") { it }

        assertEquals(listOf("a", "b"), keyed.map { it.value }, "values must not be reordered")
        assertEquals(2, keyed.map { it.key }.toSet().size)
    }

    @Test
    fun `duplicate ids get distinct keys`() {
        val keys = keysOf("coll", listOf("dup", "dup", "dup"))

        assertEquals(keys.size, keys.toSet().size, "duplicate ids produced a duplicate key")
        // The first occurrence keeps the unsuffixed key.
        assertEquals("coll:3:dup", keys.first())
    }

    @Test
    fun `every element is kept when ids repeat`() {
        val ids = List(50) { "same" }

        val keyed = ids.keyedUniquely("fav") { it }

        assertEquals(50, keyed.size, "an element was dropped")
        assertEquals(50, keyed.map { it.key }.toSet().size, "keys collided")
    }

    @Test
    fun `sections whose names prefix one another cannot collide`() {
        // The ambiguity plain "-" concatenation had: "fav" + "ws-1" and "fav-ws" +
        // "1" both concatenate to "fav-ws-1". Every section shares one LazyColumn,
        // so that would have been two items under one key. The ":" delimiter is
        // what fixes this one (no section literal contains a colon).
        val favBookmark = keysOf("fav", listOf("ws-1")).single()
        val favWorkspace = keysOf("fav-ws", listOf("1")).single()

        assertTrue(
            favBookmark != favWorkspace,
            "sections collided: both produced $favBookmark",
        )
    }

    @Test
    fun `a generated suffix cannot alias a literal id`() {
        // What the length prefix actually buys. Keyed as "<section>:<id>", the
        // duplicate "a" would be given "coll:a#2" — the very key the third
        // element's own id produces.
        val keys = keysOf("coll", listOf("a", "a", "a#2"))

        assertEquals(keys.size, keys.toSet().size, "a generated key aliased a literal id: $keys")
        assertEquals(listOf("coll:1:a", "coll:1:a#2", "coll:3:a#2"), keys)
    }

    @Test
    fun `an id containing the delimiter cannot forge another section's key`() {
        // Length-prefixing rather than escaping: an id that itself contains ':'
        // still cannot be read as a different (section, id) pair.
        val forged = keysOf("coll", listOf("3:abc")).single()
        val genuine = keysOf("coll", listOf("abc")).single()

        assertTrue(forged != genuine, "an id containing ':' forged another key")
    }

    @Test
    fun `an empty list produces no keys`() {
        assertEquals(emptyList(), emptyList<String>().keyedUniquely("coll") { it })
    }
}
