package com.music.bitchord.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import org.intellij.lang.annotations.Language

/**
 * The edge of Apple's Liquid Glass, drawn over a surface of [shape].
 *
 * Three parts, outermost first:
 *  - a dark outline along the very edge, black at plus-darker, gathered into
 *    two dark crescents at the ends and faint along the flat run between,
 *    where the rim already draws the edge;
 *  - a bright rim along the top and, mirrored, along the bottom, at its
 *    thickest where the edge runs flat and thinning to nothing round the
 *    corners, where the edge turns vertical;
 *  - a soft glow under that rim: the same rim, deeper, blurred well out and
 *    much fainter.
 *
 * Rim and glow are lit from what is beneath them. Each multiplies the colour
 * there up — color dodge against a grey, which is a gain on every channel —
 * and only then adds a little white. Added white alone, the obvious way,
 * raises every channel by the same amount: it keeps the hue but bleaches it,
 * so a red sleeve got a pale pink edge. A gain keeps the channels' ratios, so
 * red brightens as red, which is the edge Apple's glass has. The white is the
 * floor for what a gain cannot lift — over black there is nothing to multiply.
 *
 * The rim is cut the way Apple's is built — the shape less a copy of itself
 * shifted by the rim's depth, once down for the top and once up for the
 * bottom. Where the edge is horizontal that leaves the full depth; where it
 * turns, it leaves the depth times how far the edge still faces up or down,
 * which is the taper, from the shape's own geometry, at any size. Unlike the
 * backdrop library's [com.music.bitchord.ui.components.backdrop.highlight.Highlight],
 * which lights the edge from one diagonal, it is symmetrical top to bottom.
 *
 * Everything is drawn inside the shape. Every glass surface in the app sits
 * inside a clip of its own shape, which would cut away an outline drawn past
 * the edge, so the outline takes the shape's outermost band instead and the rim
 * starts just inside it.
 *
 * Plus-darker is not a blend mode Android or Skia has, and it is not one that
 * can be faked: for black at alpha `a` it subtracts — `max(0, d - a)` — where
 * black drawn normally scales, `d * (1 - a)`, and every chain of the blend
 * modes there are leaves a light fringe on the anti-aliased pixels of an edge
 * two pixels wide. So from Android 13 the surface renders into a layer of its
 * own and Apple's formula runs on it per pixel — see [PlusDarkerOutline].
 * Android 12 has no runtime shaders and draws the outline as plain black.
 *
 * [alpha] scales all three, read at draw time, so a surface can fade its rim
 * in and out without recomposing.
 */
@Composable
internal fun Modifier.glassRim(shape: Shape, alpha: () -> Float = FullRim): Modifier {
    val plusDarker = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { PlusDarkerOutline() }
    } else {
        null
    }
    val layoutDirection = LocalLayoutDirection.current
    // Named apart from GraphicsLayerScope's own alpha, which the layer block below would see.
    val rimAlpha = alpha
    return then(
        if (plusDarker != null) {
            Modifier.graphicsLayer {
                renderEffect = plusDarker.effect(shape, size, layoutDirection, this, rimAlpha().coerceIn(0f, 1f))
            }
        } else {
            Modifier
        },
    ).drawWithCache {
        val body = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val rim = rims(body, (GLASS_OUTLINE_WIDTH + GLASS_RIM_DEPTH).toPx())
        val glow = rims(body, GLASS_GLOW_DEPTH.toPx())
        val rimGain = blurredPaint(BlendMode.ColorDodge, GLASS_RIM_BLUR.toPx())
        val rimLight = blurredPaint(BlendMode.Plus, GLASS_RIM_BLUR.toPx())
        val glowGain = blurredPaint(BlendMode.ColorDodge, GLASS_GLOW_BLUR.toPx())
        val glowLight = blurredPaint(BlendMode.Plus, GLASS_GLOW_BLUR.toPx())
        // Only where the plus-darker layer is not: twice the width, centred on
        // the edge and clipped to the inside half.
        val outline = Stroke(width = GLASS_OUTLINE_WIDTH.toPx() * 2f)
        val outlineBrush = Brush.horizontalGradient(
            *outlineStops(size).map { (at, opacity) -> at to Color.Black.copy(alpha = opacity) }.toTypedArray(),
            startX = 0f,
            endX = size.width,
        )
        onDrawWithContent {
            drawContent()
            val strength = rimAlpha().coerceIn(0f, 1f)
            if (strength <= 0f) return@onDrawWithContent
            clipPath(body) {
                glowGain.color = gainGrey(GLASS_GLOW_GAIN).copy(alpha = strength)
                glowLight.color = Color.White.copy(alpha = GLASS_GLOW_ALPHA * strength)
                rimGain.color = gainGrey(GLASS_RIM_GAIN).copy(alpha = strength)
                rimLight.color = Color.White.copy(alpha = GLASS_RIM_ALPHA * strength)
                drawIntoCanvas {
                    it.drawPath(glow, glowGain)
                    it.drawPath(glow, glowLight)
                    it.drawPath(rim, rimGain)
                    it.drawPath(rim, rimLight)
                }
                if (plusDarker == null) {
                    // Last, so it is the outermost band: over the rim's own edge.
                    drawPath(body, outlineBrush, alpha = strength, style = outline)
                }
            }
        }
    }
}

private val FullRim = { 1f }

/**
 * The dark outline drawn at Apple's plus-darker, as a render effect on the
 * glass surface's own layer.
 *
 * The outline's shape and opacity are baked once per size into an alpha mask:
 * the band along the edge, clipped to the inside, its alpha the outline's
 * opacity at that point. [PLUS_DARKER_SHADER] then takes that alpha away from
 * whatever the layer holds there — the glass, its content, the rim — the way
 * Apple's formula does, which is also why it is drawn over all of it.
 *
 * Where the layer is transparent, plus-darker leaves plain black at the mask's
 * alpha, so a surface with nothing behind it in its own layer — a rim-only
 * button over the page — gets exactly the outline it had before.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class PlusDarkerOutline {
    private val shader = RuntimeShader(PLUS_DARKER_SHADER)
    private var maskKey: Any? = null
    private var strength = -1f
    private var effect: RenderEffect? = null

    fun effect(
        shape: Shape,
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
        strength: Float,
    ): RenderEffect? {
        if (size.width < 1f || size.height < 1f || strength <= 0f) return null
        val key = listOf(shape, size, layoutDirection, density.density)
        val maskChanged = key != maskKey
        if (maskChanged) {
            val mask = outlineMask(shape, size, layoutDirection, density)
            shader.setInputShader("mask", BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            maskKey = key
        }
        if (maskChanged || strength != this.strength) {
            shader.setFloatUniform("strength", strength)
            this.strength = strength
            effect = android.graphics.RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
        return effect
    }
}

private fun outlineMask(shape: Shape, size: Size, layoutDirection: LayoutDirection, density: Density): Bitmap {
    val mask = Bitmap.createBitmap(ceil(size.width).toInt(), ceil(size.height).toInt(), Bitmap.Config.ALPHA_8)
    val edge = Path().apply { addOutline(shape.createOutline(size, layoutDirection, density)) }.asAndroidPath()
    val stops = outlineStops(size)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        // Twice the width, centred on the edge and clipped to the inside half.
        strokeWidth = with(density) { GLASS_OUTLINE_WIDTH.toPx() } * 2f
        shader = LinearGradient(
            0f, 0f, size.width, 0f,
            stops.map { (_, opacity) -> android.graphics.Color.argb(opacity, 0f, 0f, 0f) }.toIntArray(),
            stops.map { (at, _) -> at }.toFloatArray(),
            Shader.TileMode.CLAMP,
        )
    }
    android.graphics.Canvas(mask).apply {
        clipPath(edge)
        drawPath(edge, paint)
    }
    return mask
}

/**
 * The outline's opacity across the surface: [GLASS_OUTLINE_ALPHA] at each end,
 * down to [GLASS_OUTLINE_MIDDLE_ALPHA] within an end cap's width of it, and
 * flat between — so the dark gathers into crescents round the two ends. On a
 * circle the caps meet in the middle and it is one ramp each side.
 */
private fun outlineStops(size: Size): List<Pair<Float, Float>> {
    val cap = (size.minDimension / 2f / size.width).coerceIn(0f, 0.5f)
    return listOf(
        0f to GLASS_OUTLINE_ALPHA,
        cap to GLASS_OUTLINE_MIDDLE_ALPHA,
        1f - cap to GLASS_OUTLINE_MIDDLE_ALPHA,
        1f to GLASS_OUTLINE_ALPHA,
    )
}

/**
 * Apple's plus-darker for a black source of alpha `m`, on premultiplied colour:
 * `max(0, a_out - (a_src - c_src) - (a_dst - c_dst))`, `a_out = min(1, m + a_dst)`.
 * Over an opaque pixel that is `max(0, c - m)`; over a transparent one, black at `m`.
 */
@Language("AGSL")
private const val PLUS_DARKER_SHADER = """
uniform shader content;
uniform shader mask;
uniform float strength;

half4 main(float2 coord) {
    half4 below = content.eval(coord);
    half m = half(mask.eval(coord).a * strength);
    half outA = min(1.0, m + below.a);
    half3 outC = max(half3(outA - m) - (half3(below.a) - below.rgb), half3(0.0));
    return half4(outC, outA);
}
"""

private fun blurredPaint(blendMode: BlendMode, blur: Float) = Paint().apply {
    this.blendMode = blendMode
    asFrameworkPaint().maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
}

/** The grey that color-dodges whatever is beneath it up by [gain]: `d / (1 - g)`. */
private fun gainGrey(gain: Float): Color = (1f - 1f / gain).let { Color(it, it, it) }

/** The top and bottom rims of [body], each [depth] deep where the edge runs flat. */
private fun rims(body: Path, depth: Float): Path {
    fun shifted(dy: Float) = Path().apply { addPath(body, Offset(0f, dy)) }
    val top = Path().apply { op(body, shifted(depth), PathOperation.Difference) }
    val bottom = Path().apply { op(body, shifted(-depth), PathOperation.Difference) }
    return Path().apply { op(top, bottom, PathOperation.Union) }
}

/**
 * The dark outline: the outermost band of the shape (2.3px at 3x), at its
 * darkest at the two ends and at its faintest along the run between.
 */
private val GLASS_OUTLINE_WIDTH = 0.77.dp
private const val GLASS_OUTLINE_ALPHA = 0.5f
private const val GLASS_OUTLINE_MIDDLE_ALPHA = 0.15f

/**
 * How deep the bright rim shows inside the outline, at the middle of a flat
 * edge; how far it multiplies the colour beneath; and the white added after.
 */
private val GLASS_RIM_DEPTH = 0.33.dp
private const val GLASS_RIM_GAIN = 2.4f
private const val GLASS_RIM_ALPHA = 0.07f

/** Just enough to take the hard edge off the rim — a pixel or two. */
private val GLASS_RIM_BLUR = 0.5.dp

/** The glow: a deeper copy of the rim, blurred, and a fraction as bright. */
private val GLASS_GLOW_DEPTH = 4.dp
private val GLASS_GLOW_BLUR = 10.dp
private const val GLASS_GLOW_GAIN = 1.6f
private const val GLASS_GLOW_ALPHA = 0.12f
