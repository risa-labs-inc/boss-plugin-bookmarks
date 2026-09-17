package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.PluginContext
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull

class BookmarkHostCapabilityTest {
    @Test fun `older host is rejected before library initialization or provider registration`() {
        val calls = mutableListOf<String>()
        val context = Proxy.newProxyInstance(PluginContext::class.java.classLoader, arrayOf(PluginContext::class.java)) { _, method, _ ->
            calls += method.name
            check(method.name == "getPluginAPI") { "Unexpected access before capability verification: ${method.name}" }
            null
        } as PluginContext
        val plugin = BookmarksDynamicPlugin()
        val error = assertFailsWith<IllegalStateException> { plugin.register(context) }
        assertTrue(error.message.orEmpty().contains("saved bookmarks have not been changed"))
        assertEquals(listOf("getPluginAPI"), calls)
        listOf("library", "bookmarkManager", "bookmarkDataProvider").forEach { name ->
            val field = plugin.javaClass.getDeclaredField(name).apply { isAccessible = true }
            assertNull(field.get(plugin), "$name must remain uninitialized on unsupported hosts")
        }
    }
}
