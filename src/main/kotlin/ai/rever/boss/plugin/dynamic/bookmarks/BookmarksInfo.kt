package ai.rever.boss.plugin.dynamic.bookmarks

import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star

/**
 * Bookmarks panel info.
 *
 * Displays and manages browser bookmarks in a sidebar panel.
 * Priority 1 = First position in left.top.top panel
 */
object BookmarksInfo : PanelInfo {
    override val id = PanelId("bookmarks", 1)
    override val displayName = "Bookmarks"
    override val icon = Icons.Outlined.Star
    override val defaultSlotPosition = left.top.top
}
