/*
 * The glass rendering itself is Kyant0/backdrop (Apache-2.0), vendored at
 * [com.music.bitchord.ui.components.backdrop] — see that package for the
 * upstream attribution. This file is the integration glue, adapted from
 * EchoMusicApp/Echo-Music's GlassEffectConfig/Modifier.liquidGlass
 * (GPL-3.0), cut down from Echo's full per-component/vibrancy-slider config
 * to the single on/off switch BitChord exposes in Settings. It is used by the
 * floating nav bar and, while enabled, the app-wide floating top controls.
 */
package com.music.bitchord.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.backdrop.Backdrop
import com.music.bitchord.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.bitchord.ui.components.backdrop.drawBackdrop
import com.music.bitchord.ui.components.backdrop.effects.blur
import com.music.bitchord.ui.components.backdrop.effects.colorControls
import com.music.bitchord.ui.components.backdrop.effects.lens
import com.music.bitchord.ui.components.backdrop.shadow.Shadow
import androidx.compose.ui.unit.dp

/** Whether the liquid glass nav bar is turned on — see [AppSettings.liquidGlass]. */
val LocalLiquidGlassEnabled = staticCompositionLocalOf { false }

/** The backdrop content (app UI) that a liquid glass surface samples from. */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop> { error("No AppBackdrop provided") }

/**
 * Where a [liquidGlass] surface composed under it also records the glass it
 * draws — the sampled, blurred and tinted backdrop, without its rim or shadow —
 * so a lens above it can refract that surface rather than the page behind it.
 * The tab bar's travelling selection pill is the one reader; see
 * [com.music.bitchord.ui.components.floatingtabbar.GlassSelectionPill].
 */
internal val LocalGlassExport = compositionLocalOf<LayerBackdrop?> { null }

/**
 * The backdrop blur pipeline requires [android.graphics.RenderEffect] on a
 * [android.graphics.RenderNode], available from Android 12 (API 31).
 */
fun isGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

/** Apple-matched defaults (Echo's GlassEffectConfig()), fixed rather than user sliders. */
private const val BACKDROP_SATURATION = 1.8f
private const val BLUR_RADIUS_DP = 8f
private const val LENS_HEIGHT = 0.5f
private const val LENS_AMOUNT = 0.5f
private const val LENS_MAX_DP = 48f
private const val SURFACE_OPACITY = 0.4f

/**
 * The glass's own fill over what it samples.
 *
 * Dark glass lifts the backdrop with a faint white added on, plus-lighter, the
 * way Apple's does: it reads a shade lighter than the page around it and every
 * colour under it comes through whole. It used to veil it instead — #121212 at
 * 40% — which took 40% of that colour away and greyed what was left. Light
 * glass keeps its milky white veil: over a pale page there is nothing for
 * plus-lighter to add.
 */
private val LIGHT_GLASS_TINT = Color(0xFFFAFAFA)
private const val DARK_GLASS_LIFT = 0.08f

private fun DrawScope.drawGlassFill(light: Boolean, shape: Shape) {
    val outline = shape.createOutline(size, layoutDirection, this)
    if (light) {
        drawOutline(outline, LIGHT_GLASS_TINT.copy(alpha = SURFACE_OPACITY))
    } else {
        drawOutline(outline, Color.White.copy(alpha = DARK_GLASS_LIFT), blendMode = BlendMode.Plus)
    }
}

/**
 * Resolution fraction the glass surface records and processes its backdrop at.
 *
 * A third, which is what Echo's `glassResolutionScale` comes to at this blur
 * radius. It was briefly raised to a half while the sharp-text bug was being
 * chased — wrongly, as it turned out: the cause was a transparent backdrop, not
 * the resample (see MainActivity's `paintBackdrop`). A third is nine times fewer
 * pixels than full resolution through the colour matrix, the blur and the lens
 * shader, on as many as six surfaces at once in the middle of a fold, and the
 * blur is what hides the upscale.
 */
private const val GLASS_RESOLUTION_SCALE = 0.33f

/**
 * The hairline along a bar's edge, and what stands in for the glass rim
 * wherever the glass itself is not drawn.
 *
 * A surface filled with the theme's own `surface` colour has no edge of its own
 * against a dark page — it is the same near-black the page is. The glass gets
 * its edge from [glassRim], and this is that edge for everything that does not.
 * Never both: a light hairline over the rim's dark outline is a second edge
 * Apple's glass does not have.
 */
internal val GLASS_EDGE_WIDTH = 0.5.dp
internal val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.10f)

/**
 * Icon and label colour for content sitting on a glass surface.
 *
 * Glass shows whatever is behind it rather than the theme's surface colour, so
 * the usual onSurface greys have nothing dependable to sit against. Pure black
 * or white off the theme's luminance is the only tint that holds against
 * arbitrary artwork, and it is what Echo's own glass nav bar uses.
 */
@Composable
fun glassContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.Black else Color.White

/**
 * Selected-tab indicator colour for a glass surface: white in both themes.
 *
 * In light theme that is the inverse of [glassContentColor], a bright lift
 * behind a dark glyph. In dark theme it is a faint white lift over the bar's
 * dark glass rather than a black scrim pressed into it — the selected tab is
 * raised out of the bar, as on iOS, not sunk.
 */
@Composable
fun glassIndicatorColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color.White.copy(alpha = 0.5f)
    } else {
        Color.White.copy(alpha = DARK_INDICATOR_OPACITY)
    }

/** The selected tab's pill in dark theme, a step whiter than the bar. */
private const val DARK_INDICATOR_OPACITY = 0.14f

/** Whether liquid glass is drawn here: switched on, and supported by the device. */
@Composable
fun isLiquidGlassActive(): Boolean = LocalLiquidGlassEnabled.current && isGlassSupported()

/**
 * A lightweight visual match for liquid glass over a stable background.
 *
 * This keeps the same continuous shape, translucent tint and [glassRim] as
 * [liquidGlass], but intentionally performs no backdrop capture, blur, lens
 * refraction or shadow rendering. When Liquid Glass is disabled or unsupported,
 * [fallbackColor] and the hairline preserve the control's existing filled
 * appearance.
 */
@Composable
fun Modifier.lightweightLiquidGlass(
    shape: CornerBasedShape,
    fallbackColor: Color,
): Modifier {
    if (!isLiquidGlassActive()) {
        return clip(shape)
            .background(fallbackColor, shape)
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
    }
    val glassShape = remember(shape) { shape.continuous() }
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    return clip(glassShape)
        .drawBehind { drawGlassFill(light, glassShape) }
        .glassRim(glassShape)
}

/**
 * Just the rim of liquid glass — [glassRim]'s outline, rim and glow — for a
 * control that keeps its own fill.
 *
 * Over a flat, stable page the blur and lens of [liquidGlass] would sample a
 * single colour and change nothing a reader could see; the rim is the part
 * that reads as glass. No backdrop capture, so it costs a few path fills.
 * Nothing at all while Liquid Glass is off or unsupported — a caller with an
 * edge of its own for that case keeps it behind [isLiquidGlassActive].
 */
@Composable
fun Modifier.liquidGlassRim(shape: CornerBasedShape): Modifier {
    if (!isLiquidGlassActive()) return this
    val glassShape = remember(shape) { shape.continuous() }
    return glassRim(glassShape)
}

/**
 * Renders this composable as a liquid glass surface sampling [LocalAppBackdrop]:
 * saturation, blur and lens refraction, then the glass's own fill — a faint
 * plus-lighter lift on dark theme, a milky veil on light (see [DARK_GLASS_LIFT]). Returns the receiver unchanged on devices
 * without RenderEffect support — callers should still gate on [isGlassSupported]
 * to fall back to the regular Haze treatment there.
 *
 * Under "reduce dynamic blur" the surface is filled solid instead, which is what
 * that setting promises everywhere else in the app. It is checked here rather
 * than at each call site so there is one answer to it: [MainActivity] also stops
 * recording the backdrop layer when it is on, and a surface that still tried to
 * sample would be sampling a layer nothing is drawing into.
 *
 * [shape] is restricted to [CornerBasedShape] because the backdrop's lens effect
 * throws for any other shape type. Its corners are drawn continuous — see
 * [ContinuousCornerShape] — which never reaches past the plain shape, so a
 * caller's own clip to [shape] is still safe. The edge is [glassRim], not the
 * backdrop library's diagonal highlight.
 */
@Composable
fun Modifier.liquidGlass(
    shape: CornerBasedShape,
    /** Drawn over the glass in place of the theme's default tint, when set. */
    surfaceTint: Color? = null,
): Modifier {
    if (!isGlassSupported()) return this
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    if (reduceDynamicBlur) {
        return background(MaterialTheme.colorScheme.surface, shape)
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
    }
    val glassShape = remember(shape) { shape.continuous() }
    val backdrop = LocalAppBackdrop.current
    val exportedBackdrop = LocalGlassExport.current
    val density = LocalDensity.current
    val blurPx = with(density) { BLUR_RADIUS_DP.dp.toPx() } * GLASS_RESOLUTION_SCALE
    val lensHeightPx = with(density) { (LENS_HEIGHT * LENS_MAX_DP).dp.toPx() } * GLASS_RESOLUTION_SCALE
    val lensAmountPx = with(density) { (LENS_AMOUNT * LENS_MAX_DP).dp.toPx() } * GLASS_RESOLUTION_SCALE
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f

    // The rim ahead of the backdrop, so it draws over the glass it edges. The
    // clip between them is what cuts the glass to the continuous shape: the
    // backdrop node draws its sample and fill unclipped, and callers clip to
    // the plain shape, which left a sliver of glass outside the rim's outline.
    return glassRim(glassShape).clip(glassShape).drawBackdrop(
        backdrop = backdrop,
        shape = { glassShape },
        effects = {
            colorControls(saturation = BACKDROP_SATURATION)
            blur(blurPx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(
                    refractionHeight = lensHeightPx,
                    refractionAmount = lensAmountPx,
                    depthEffect = true,
                    chromaticAberration = true,
                )
            }
        },
        highlight = null,
        shadow = { Shadow.Default },
        onDrawSurface = {
            if (surfaceTint != null) drawRect(color = surfaceTint, size = size) else drawGlassFill(light, glassShape)
        },
        exportedBackdrop = exportedBackdrop,
        backdropScale = GLASS_RESOLUTION_SCALE,
    )
}
