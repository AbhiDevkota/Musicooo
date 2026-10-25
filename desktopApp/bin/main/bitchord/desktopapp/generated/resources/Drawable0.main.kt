@file:OptIn(InternalResourceApi::class)

package bitchord.desktopapp.generated.resources

import kotlin.OptIn
import kotlin.String
import kotlin.collections.MutableMap
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.ResourceContentHash
import org.jetbrains.compose.resources.ResourceItem

private const val MD: String = "composeResources/bitchord.desktopapp.generated.resources/"

@delegate:ResourceContentHash(-2_126_339_263)
internal val Res.drawable.logo: DrawableResource by lazy {
      DrawableResource("drawable:logo", setOf(
        ResourceItem(setOf(), "${MD}drawable/logo.svg", -1, -1),
      ))
    }

@delegate:ResourceContentHash(-1_957_829_640)
internal val Res.drawable.logo_mark: DrawableResource by lazy {
      DrawableResource("drawable:logo_mark", setOf(
        ResourceItem(setOf(), "${MD}drawable/logo_mark.svg", -1, -1),
      ))
    }

@InternalResourceApi
internal fun _collectMainDrawable0Resources(map: MutableMap<String, DrawableResource>) {
  map.put("logo", Res.drawable.logo)
  map.put("logo_mark", Res.drawable.logo_mark)
}
