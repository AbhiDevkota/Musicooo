package com.music.bitchord.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
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
 *  - a dark outline along the very edge, Apple's 60% black at plus-darker,
 *    at full strength at the two ends and fading across the middle, where
 *    the rim already draws the edge;
 *  - a bright rim along the top and, mirrored, along the bottom, plus-lighter
 *    white, at its thickest where the edge runs flat and thinning to nothing
 *    round the corners, where the edge turns vertical;
 *  - a soft glow under that rim: the same rim, deeper, blurred well out and
 *    much fainter.
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
 * Neither Android nor Skia has plus-darker. For opaque white-to-black it is
 * the backdrop less the outline's alpha; the outline is drawn as plain 60%
 * black over it, which agrees with that on a dark backdrop, where both come out
 * near black, and is a shade lighter on a pale one.
 *
 * [alpha] scales all three, read at draw time, so a surface can fade its rim
 * in and out without recomposing.
 */
internal fun Modifier.glassRim(shape: Shape, alpha: () -> Float = { 1f }): Modifier =
    drawWithCache {
        val body = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val rim = rims(body, (GLASS_OUTLINE_WIDTH + GLASS_RIM_DEPTH).toPx())
        val glow = rims(body, GLASS_GLOW_DEPTH.toPx())
        val glowPaint = Paint().apply {
            color = Color.White
            blendMode = BlendMode.Plus
            asFrameworkPaint().maskFilter = BlurMaskFilter(GLASS_GLOW_BLUR.toPx(), BlurMaskFilter.Blur.NORMAL)
        }
        // Twice the width, centred on the edge and clipped to the inside half.
        val outline = Stroke(width = GLASS_OUTLINE_WIDTH.toPx() * 2f)
        // Full strength at the two ends, easing to its faintest across the middle.
        val outlineBrush = Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = GLASS_OUTLINE_ALPHA),
            0.5f to Color.Black.copy(alpha = GLASS_OUTLINE_MIDDLE_ALPHA),
            1f to Color.Black.copy(alpha = GLASS_OUTLINE_ALPHA),
            startX = 0f,
            endX = size.width,
        )
        onDrawWithContent {
            drawContent()
            val strength = alpha().coerceIn(0f, 1f)
            if (strength <= 0f) return@onDrawWithContent
            clipPath(body) {
                glowPaint.alpha = GLASS_GLOW_ALPHA * strength
                drawIntoCanvas { it.drawPath(glow, glowPaint) }
                drawPath(rim, Color.White, alpha = GLASS_RIM_ALPHA * strength, blendMode = BlendMode.Plus)
                // Last, so it is the outermost band: over the rim's own edge.
                drawPath(body, outlineBrush, alpha = strength, style = outline)
            }
        }
    }

/** The top and bottom rims of [body], each [depth] deep where the edge runs flat. */
private fun rims(body: Path, depth: Float): Path {
    fun shifted(dy: Float) = Path().apply { addPath(body, Offset(0f, dy)) }
    val top = Path().apply { op(body, shifted(depth), PathOperation.Difference) }
    val bottom = Path().apply { op(body, shifted(-depth), PathOperation.Difference) }
    return Path().apply { op(top, bottom, PathOperation.Union) }
}

/** The dark outline: the outermost band of the shape, at the ends and at its faintest mid-way. */
private val GLASS_OUTLINE_WIDTH = 0.7.dp
private const val GLASS_OUTLINE_ALPHA = 0.6f
private const val GLASS_OUTLINE_MIDDLE_ALPHA = 0.2f

/** How deep the bright rim shows inside the outline, at the middle of a flat edge. */
private val GLASS_RIM_DEPTH = 0.75.dp
private const val GLASS_RIM_ALPHA = 0.19f

/** The glow: a deeper copy of the rim, blurred, and a fraction as bright. */
private val GLASS_GLOW_DEPTH = 4.dp
private val GLASS_GLOW_BLUR = 10.dp
private const val GLASS_GLOW_ALPHA = 0.25f
