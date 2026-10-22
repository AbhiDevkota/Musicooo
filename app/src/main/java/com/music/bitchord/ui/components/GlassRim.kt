package com.music.bitchord.ui.components

import android.graphics.BlurMaskFilter
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp

/**
 * The edge of Apple's Liquid Glass, drawn over a surface of [shape].
 *
 * Three parts, outermost first:
 *  - a dark outline along the very edge, only at the two ends: a crescent
 *    round each, gone by the flat run between, where the rim draws the edge;
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
 * Neither Android nor Skia has plus-darker, which for black at alpha `a`
 * subtracts — `max(0, d - a)` — so the outline is black drawn normally, which
 * scales instead, `d * (1 - a)`: the same on a dark backdrop, where both come
 * out near black, and a shade lighter on a pale one. Working Apple's formula
 * per pixel takes a layer and a shader pass over every glass surface on every
 * frame it changes, and the bars change on every frame of a scroll.
 *
 * [alpha] scales all three, read at draw time, so a surface can fade its rim
 * in and out without recomposing.
 */
internal fun Modifier.glassRim(shape: Shape, alpha: () -> Float = FullRim): Modifier =
    drawWithCache {
        val body = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val rim = rims(body, (GLASS_OUTLINE_WIDTH + GLASS_RIM_DEPTH).toPx())
        val glow = rims(body, GLASS_GLOW_DEPTH.toPx())
        val rimGain = blurredPaint(BlendMode.ColorDodge, GLASS_RIM_BLUR.toPx())
        val rimLight = blurredPaint(BlendMode.Plus, GLASS_RIM_BLUR.toPx())
        val glowGain = blurredPaint(BlendMode.ColorDodge, GLASS_GLOW_BLUR.toPx())
        val glowLight = blurredPaint(BlendMode.Plus, GLASS_GLOW_BLUR.toPx())
        // Twice the width, centred on the edge and clipped to the inside half.
        val outline = Stroke(width = GLASS_OUTLINE_WIDTH.toPx() * 2f)
        val outlineBrush = Brush.horizontalGradient(
            *outlineStops(size).map { (at, opacity) -> at to Color.Black.copy(alpha = opacity) }.toTypedArray(),
            startX = 0f,
            endX = size.width,
        )
        onDrawWithContent {
            drawContent()
            val strength = alpha().coerceIn(0f, 1f)
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
                // Last, so it is the outermost band: over the rim's own edge.
                drawPath(body, outlineBrush, alpha = strength, style = outline)
            }
        }
    }

private val FullRim = { 1f }

/**
 * The outline's opacity across the surface: [GLASS_OUTLINE_ALPHA] at each end,
 * fading to nothing within an end cap's width of it, and none between — a
 * crescent round each end. On a circle the caps meet in the middle and it is
 * one ramp each side.
 */
private fun outlineStops(size: Size): List<Pair<Float, Float>> {
    val cap = (size.minDimension / 2f / size.width).coerceIn(0f, 0.5f)
    return listOf(
        0f to GLASS_OUTLINE_ALPHA,
        cap to 0f,
        1f - cap to 0f,
        1f to GLASS_OUTLINE_ALPHA,
    )
}

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

/** The dark outline: the outermost band of the shape (2.3px at 3x), at its darkest at the two ends. */
private val GLASS_OUTLINE_WIDTH = 0.77.dp
private const val GLASS_OUTLINE_ALPHA = 0.5f

/**
 * How deep the bright rim shows inside the outline, at the middle of a flat
 * edge; how far it multiplies the colour beneath; and the white added after.
 */
private val GLASS_RIM_DEPTH = 0.25.dp
private const val GLASS_RIM_GAIN = 2.4f
private const val GLASS_RIM_ALPHA = 0.07f

/** Just enough to take the hard edge off the rim — a pixel or two. */
private val GLASS_RIM_BLUR = 0.5.dp

/** The glow: a deeper copy of the rim, blurred, and a fraction as bright. */
private val GLASS_GLOW_DEPTH = 4.dp
private val GLASS_GLOW_BLUR = 10.dp
private const val GLASS_GLOW_GAIN = 1.6f
private const val GLASS_GLOW_ALPHA = 0.12f
