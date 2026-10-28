/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.music.bitchord.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.playback.audio.bitperfect.DacVolume
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * What the volume keys tell [DacVolumeHud]. Kept apart from [DacVolume] so a
 * level change from anywhere else — the player's bar, the settings slider —
 * moves the level without announcing itself on screen.
 */
object VolumeHud {
    /** [direction] of a key press, and whether the level was already at that end. */
    data class Pulse(val direction: Int, val atLimit: Boolean)

    private val _pulses = MutableSharedFlow<Pulse>(extraBufferCapacity = 8)
    val pulses: SharedFlow<Pulse> = _pulses.asSharedFlow()

    /**
     * True while the full player is open: its own volume bar already shows
     * the level, and two of them would be one too many.
     */
    @Volatile var suppressed = false

    fun pulse(direction: Int, atLimit: Boolean) {
        _pulses.tryEmit(Pulse(direction, atLimit))
    }
}

private const val HIDE_AFTER_MS = 1500L
private val HUD_HEIGHT = 156.dp
private val COMPACT_WIDTH = 14.dp
private val EXPANDED_WIDTH = 50.dp
private val EDGE_GAP = 10.dp

/**
 * The DAC's volume, the way iOS shows its own: a slim capsule that springs
 * out beside the volume keys, fills from the bottom, stretches like rubber
 * when pushed past either end, widens to a draggable slider under a finger,
 * and tucks itself away again a moment after the last touch.
 *
 * Shown only while direct USB output owns the volume ([DacVolume.level] is
 * not null), and never over the full player. A [Popup], so it sits above any
 * sheet the page has open.
 */
@Composable
fun DacVolumeHud() {
    val level by DacVolume.level.collectAsStateWithLifecycle()
    val current = level ?: return

    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    var present by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    // Off-screen to the right, as a share of the HUD's own width plus its gap.
    val slide = remember { Animatable(1f) }
    val stretch = remember { Animatable(1f) }
    var stretchFromTop by remember { mutableStateOf(false) }

    val fill by animateFloatAsState(
        targetValue = current,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
        label = "fill",
    )
    val width by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (dragging) EXPANDED_WIDTH else COMPACT_WIDTH,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "width",
    )

    fun scheduleHide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(HIDE_AFTER_MS)
            if (dragging) return@launch
            slide.animateTo(1f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow))
            present = false
        }
    }

    fun show() {
        if (VolumeHud.suppressed) return
        present = true
        scope.launch { slide.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 420f)) }
        scheduleHide()
    }

    /** Pulled past an end: stretch away from the bound, then snap back with a little bounce. */
    fun rubberBand(direction: Int) {
        stretchFromTop = direction < 0
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch {
            stretch.animateTo(1.07f, tween(durationMillis = 90))
            stretch.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 380f))
        }
    }

    LaunchedEffect(Unit) {
        VolumeHud.pulses.collect { pulse ->
            show()
            if (pulse.atLimit) rubberBand(pulse.direction)
        }
    }

    if (!present || VolumeHud.suppressed) return

    val offscreenPx = with(density) { (EXPANDED_WIDTH + EDGE_GAP * 2).roundToPx() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(
            x = -with(density) { EDGE_GAP.roundToPx() },
            // Level with a phone's volume keys, about a fifth of the way down.
            y = with(density) { (screenHeight * 0.2f).roundToPx() },
        ),
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        val heightPx = with(density) { HUD_HEIGHT.toPx() }
        Box(
            modifier = Modifier
                .height(HUD_HEIGHT)
                .width(EXPANDED_WIDTH),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = slide.value * offscreenPx
                        alpha = 1f - slide.value
                        scaleY = stretch.value
                        // Thinner as it stretches, as rubber does.
                        scaleX = 1f - (stretch.value - 1f) * 1.2f
                        transformOrigin = TransformOrigin(0.5f, if (stretchFromTop) 0f else 1f)
                    }
                    .width(width)
                    .fillMaxHeight()
                    .shadow(10.dp, RoundedCornerShape(percent = 50), clip = false)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color(0xFF1C1C1E).copy(alpha = 0.72f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(percent = 50))
                    .pointerInput(Unit) {
                        var start = 0f
                        var travelled = 0f
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragging = true
                                hideJob?.cancel()
                                start = DacVolume.level.value ?: 0f
                                travelled = 0f
                            },
                            onDragEnd = {
                                dragging = false
                                scope.launch { stretch.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 380f)) }
                                scheduleHide()
                            },
                            onDragCancel = {
                                dragging = false
                                scope.launch { stretch.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 380f)) }
                                scheduleHide()
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            travelled -= dragAmount
                            val wanted = start + travelled / heightPx
                            DacVolume.set(wanted.coerceIn(0f, 1f))
                            // Past either end the capsule follows the finger a little,
                            // with diminishing give, then springs back on release.
                            val over = when {
                                wanted > 1f -> wanted - 1f
                                wanted < 0f -> -wanted
                                else -> 0f
                            }
                            stretchFromTop = wanted < 0f
                            scope.launch { stretch.snapTo(1f + minOf(0.12f, over * 0.35f)) }
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(fill.coerceIn(0f, 1f))
                        .background(Color.White),
                )
                if (width > COMPACT_WIDTH + 18.dp) {
                    Icon(
                        imageVector = when {
                            current <= 0f -> Icons.AutoMirrored.Rounded.VolumeMute
                            current < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
                            else -> Icons.AutoMirrored.Rounded.VolumeUp
                        },
                        contentDescription = null,
                        // Dark where the white fill is behind it, as on iOS.
                        tint = if (fill > 0.12f) Color(0xFF3A3A3C) else Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .size(20.dp),
                    )
                }
            }
        }
    }
}
