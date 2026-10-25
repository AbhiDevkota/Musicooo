@file:OptIn(InternalResourceApi::class)

package bitchord.desktopapp.generated.resources

import kotlin.OptIn
import kotlin.String
import kotlin.collections.MutableMap
import org.jetbrains.compose.resources.FontResource
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.ResourceContentHash
import org.jetbrains.compose.resources.ResourceItem

private const val MD: String = "composeResources/bitchord.desktopapp.generated.resources/"

@delegate:ResourceContentHash(-1_083_748_600)
internal val Res.font.sf_pro_display_bold: FontResource by lazy {
      FontResource("font:sf_pro_display_bold", setOf(
        ResourceItem(setOf(), "${MD}font/sf_pro_display_bold.otf", -1, -1),
      ))
    }

@delegate:ResourceContentHash(-1_072_564_297)
internal val Res.font.sf_pro_display_heavy: FontResource by lazy {
      FontResource("font:sf_pro_display_heavy", setOf(
        ResourceItem(setOf(), "${MD}font/sf_pro_display_heavy.otf", -1, -1),
      ))
    }

@delegate:ResourceContentHash(-49_885_457)
internal val Res.font.sf_pro_display_medium: FontResource by lazy {
      FontResource("font:sf_pro_display_medium", setOf(
        ResourceItem(setOf(), "${MD}font/sf_pro_display_medium.otf", -1, -1),
      ))
    }

@delegate:ResourceContentHash(1_658_950_365)
internal val Res.font.sf_pro_display_regular: FontResource by lazy {
      FontResource("font:sf_pro_display_regular", setOf(
        ResourceItem(setOf(), "${MD}font/sf_pro_display_regular.otf", -1, -1),
      ))
    }

@delegate:ResourceContentHash(1_264_455_709)
internal val Res.font.sf_pro_display_semibold: FontResource by lazy {
      FontResource("font:sf_pro_display_semibold", setOf(
        ResourceItem(setOf(), "${MD}font/sf_pro_display_semibold.otf", -1, -1),
      ))
    }

@InternalResourceApi
internal fun _collectMainFont0Resources(map: MutableMap<String, FontResource>) {
  map.put("sf_pro_display_bold", Res.font.sf_pro_display_bold)
  map.put("sf_pro_display_heavy", Res.font.sf_pro_display_heavy)
  map.put("sf_pro_display_medium", Res.font.sf_pro_display_medium)
  map.put("sf_pro_display_regular", Res.font.sf_pro_display_regular)
  map.put("sf_pro_display_semibold", Res.font.sf_pro_display_semibold)
}
