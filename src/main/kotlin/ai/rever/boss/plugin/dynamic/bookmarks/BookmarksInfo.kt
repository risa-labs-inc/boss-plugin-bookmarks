package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark

/**
 * Bookmarks panel info for dynamic plugin.
 *
 * Displays and manages browser bookmarks in a sidebar panel.
 */
object BookmarksInfo : PanelInfo {
    override val id = PanelId("dynamic-bookmarks", 18)
    override val displayName = "Bookmarks (Dynamic)"
    override val icon = Icons.Outlined.Bookmark
    override val defaultSlotPosition = left.top.bottom
}
