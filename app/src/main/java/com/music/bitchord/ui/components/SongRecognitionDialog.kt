package com.music.bitchord.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.shazam.RecognitionSession
import com.music.bitchord.data.shazam.RecognitionSessionState
import com.music.bitchord.data.shazam.ShazamTrack
import com.music.bitchord.ui.components.backdrop.drawBackdrop
import com.music.bitchord.ui.components.backdrop.effects.blur
import com.music.bitchord.ui.components.backdrop.effects.colorControls
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private val RecognitionTop = Color(0xFF1D1D20)
private val RecognitionBottom = Color(0xFF0A0A0B)
// The sheet is always dark, so the orb uses the same faint white lift as the
// dark-theme nav bar. A black tint would read as a hole rather than glass.
private val RecognitionGlassTint = Color.White.copy(alpha = 0.10f)
private val RecognitionGlassFallback = Color.White.copy(alpha = 0.09f)
private val RecognitionControlFallback = Color.White.copy(alpha = 0.07f)

/** Critically damped, like the player sheet: brisk out, no bounce at either end. */
private const val SHEET_STIFFNESS = 195f
private const val SHEET_SETTLED_PX = 0.5f
private const val SHEET_CLOSE_FRACTION = 0.24f
private const val SHEET_FALLBACK_HEIGHT_DP = 800f

/** How fast a downward flick has to be, in dp per second, to close on its own. */
private const val SHEET_FLING_DP = 720f

/** The live microphone has not been tapped yet, so the permission prompt owns the screen. */
private enum class MicrophoneAccess { Waiting, Granted, Denied }

/**
 * YouTube Music's "search a song" surface, opened by the microphone in the
 * search bar.
 *
 * The sheet owns the microphone grant for the same reason the search field
 * does: the first tap is the moment that permission makes sense, and the
 * listening state is this app's UI rather than the system speech recognizer's.
 * While the grant is live, [RecognitionSession] streams the mic level into the
 * orb, so the rings and bars answer the sound in the room instead of running on
 * a fixed loop, and searches the same recording at 4, 8 and 12 seconds. If the
 * grant is refused the sheet stays useful by explaining what it needs and
 * offering the way back to the system toggle instead of dropping a toast and
 * closing.
 *
 * A match is handed to [onSearch] with "title artist", so the existing Search
 * tab owns the final choice rather than the recognizer guessing which upload is
 * the right one.
 */
@Composable
fun SongRecognitionDialog(
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(scope) { RecognitionSession(scope) }
    val density = LocalDensity.current
    val reduceMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    // The sheet follows the finger down, then either springs back or carries on
    // out of the window — the same shape as the player's dismiss.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var sheetHeight by remember { mutableFloatStateOf(0f) }
    var dismissing by remember { mutableStateOf(false) }
    val flingVelocity = with(density) { SHEET_FLING_DP.dp.toPx() }
    val fallbackHeight = with(density) { SHEET_FALLBACK_HEIGHT_DP.dp.toPx() }

    fun animateSheetTo(target: Float, velocity: Float, after: (() -> Unit)? = null) {
        scope.launch {
            animate(
                initialValue = dragOffset,
                targetValue = target,
                initialVelocity = velocity,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = SHEET_STIFFNESS,
                    visibilityThreshold = SHEET_SETTLED_PX,
                ),
            ) { value, _ -> dragOffset = value }
            after?.invoke()
        }
    }

    val dismiss: () -> Unit = {
        if (!dismissing) {
            dismissing = true
            if (reduceMotion) {
                onDismiss()
            } else {
                animateSheetTo(sheetHeight.takeIf { it > 0f } ?: fallbackHeight, 0f, onDismiss)
            }
        }
    }
    // The rationale question can only be put to an Activity; the Compose
    // context normally is one, but wrapping it keeps this safe in previews and
    // inside other context wrappers.
    val activity = remember(context) { context.findActivity() }
    var access by remember {
        mutableStateOf(
            if (context.hasMicrophone()) MicrophoneAccess.Granted else MicrophoneAccess.Waiting,
        )
    }
    // False when Android will no longer show the request itself — the second
    // refusal, or "don't ask again" — which is when Settings is the only door
    // left. The callback and the resume check both refresh it.
    var canAskAgain by remember { mutableStateOf(true) }
    val requestMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        access = if (granted) MicrophoneAccess.Granted else MicrophoneAccess.Denied
        canAskAgain = !granted && activity?.canAskForMicrophone() == true
    }
    LaunchedEffect(Unit) {
        if (!context.hasMicrophone()) requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Only a granted sheet owns the microphone. This also closes the recorder
    // when the permission prompt pushes the activity down mid-request.
    LaunchedEffect(access) {
        if (access == MicrophoneAccess.Granted && context.hasMicrophone()) {
            session.start()
        } else {
            session.stop()
        }
    }
    DisposableEffect(session) {
        onDispose { session.stop() }
    }
    val level by session.level.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        session.stop()
    }
    // Coming back from the system prompt, or from Settings after the user
    // flipped the switch there, is the only way the grant can change without
    // this sheet doing anything. A revoked grant falls back to the explanation
    // rather than leaving a listening animation running with no microphone.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = context.hasMicrophone()
        if (granted) {
            access = MicrophoneAccess.Granted
            // A result or a settled "no match" is not interrupted listening;
            // only restart a take that the pause cut off.
            if (state == RecognitionSessionState.Listening || state == RecognitionSessionState.Searching) {
                session.start()
            }
        } else if (access == MicrophoneAccess.Granted) {
            access = MicrophoneAccess.Denied
        }
        canAskAgain = activity?.canAskForMicrophone() == true
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            // The sheet paints its own glass under the status bar like the
            // player does; only the content below stays inside safe drawing.
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { sheetHeight = it.height.toFloat() }
                .graphicsLayer { translationY = dragOffset }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        if (!dismissing) {
                            dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                        }
                    },
                    onDragStopped = { velocity ->
                        if (!dismissing) {
                            val travel = sheetHeight.takeIf { it > 0f } ?: fallbackHeight
                            val flick = abs(velocity) >= flingVelocity
                            val close = if (flick) {
                                velocity > 0f
                            } else {
                                dragOffset > travel * SHEET_CLOSE_FRACTION
                            }
                            when {
                                close && reduceMotion -> onDismiss()
                                close -> {
                                    dismissing = true
                                    animateSheetTo(travel, velocity.coerceAtLeast(0f), onDismiss)
                                }
                                reduceMotion -> dragOffset = 0f
                                else -> animateSheetTo(0f, velocity)
                            }
                        }
                    },
                )
                .recognitionSheetSurface(),
        ) {
            // A soft halo under the control, so the glass orb does not sit on
            // a flat black field while the rings travel out from it.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(360.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.recognition_title),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = dismiss,
                        modifier = Modifier
                            .size(40.dp)
                            .recognitionGlassControl(CircleShape, RecognitionControlFallback),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = Color.White.copy(alpha = 0.78f),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (access) {
                        MicrophoneAccess.Waiting, MicrophoneAccess.Granted -> when (val current = state) {
                            RecognitionSessionState.Listening ->
                                ListeningContent(level = level, searching = false)
                            RecognitionSessionState.Searching ->
                                ListeningContent(level = 0f, searching = true)
                            is RecognitionSessionState.Found -> FoundContent(
                                track = current.track,
                                onSearch = { onSearch(current.track.searchQuery) },
                                onRetry = { session.start() },
                            )
                            RecognitionSessionState.NoMatch -> RecognitionMessageContent(
                                icon = Icons.Rounded.MusicOff,
                                title = stringResource(R.string.no_results),
                                body = stringResource(R.string.recognition_no_match_hint),
                                actionLabel = stringResource(R.string.try_again),
                                onAction = { session.start() },
                            )
                            RecognitionSessionState.Failed -> RecognitionMessageContent(
                                icon = Icons.Rounded.CloudOff,
                                title = stringResource(R.string.failed),
                                body = stringResource(R.string.recognition_failed_hint),
                                actionLabel = stringResource(R.string.try_again),
                                onAction = { session.start() },
                            )
                        }
                        MicrophoneAccess.Denied -> MicrophoneDeniedContent(
                            canAskAgain = canAskAgain,
                            onEnable = {
                                if (canAskAgain) {
                                    requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    context.openAppSettings()
                                }
                            },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 12.dp)
                        .recognitionGlassControl(
                            shape = RoundedCornerShape(percent = 50),
                            fallback = RecognitionControlFallback,
                        )
                        .clickable(onClick = dismiss)
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = Color.White.copy(alpha = 0.76f),
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

/**
 * The listening orb. The pulse keeps time, but [level] decides how bright and
 * wide the rings travel, how much the orb swells, and how high the live bars
 * inside it jump. [searching] swaps the bars for a spinner once the microphone
 * has been released and the last fingerprint is on its way to Shazam.
 */
@Composable
private fun ListeningContent(level: Float, searching: Boolean) {
    // The level itself is feedback, so it keeps moving even under "reduce
    // animation" — but it snaps rather than eases, and the decorative pulse,
    // breathing and idle bar wobble stop.
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val animatedLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = if (reduceAnimation) {
            snap()
        } else {
            spring(
                stiffness = Spring.StiffnessMediumLow,
                dampingRatio = 0.78f,
            )
        },
        label = "recognition-level",
    )
    val transition = rememberInfiniteTransition(label = "song-recognition")
    val pulseState = if (reduceAnimation) {
        null
    } else {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = LinearEasing),
            ),
            label = "pulse",
        )
    }
    val breatheState = if (reduceAnimation) {
        null
    } else {
        transition.animateFloat(
            initialValue = 0.99f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breathe",
        )
    }
    val pulse = pulseState?.value ?: 0f
    val breathe = breatheState?.value ?: 1f
    val microphoneDescription = stringResource(R.string.recognition_microphone)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(340.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inner = size.minDimension * 0.22f
                val outer = size.minDimension * 0.49f
                // The glow behind the orb rises with the room, so even the
                // quietest reading still leaves a soft footprint.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.04f + animatedLevel * 0.12f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = size.minDimension * 0.36f,
                    ),
                    radius = size.minDimension * 0.36f,
                )
                repeat(3) { index ->
                    val progress = (pulse + index / 3f) % 1f
                    val radius = inner + (outer - inner) * progress
                    val alpha = (0.10f + animatedLevel * 0.30f) * (1f - progress)
                    drawCircle(
                        color = Color.White.copy(alpha = alpha),
                        radius = radius,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(168.dp)
                    .graphicsLayer {
                        val scale = breathe + animatedLevel * 0.06f
                        scaleX = scale
                        scaleY = scale
                    }
                    .recognitionGlass(CircleShape)
                    .semantics { contentDescription = microphoneDescription },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.08f + animatedLevel * 0.22f),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = size.minDimension * 0.50f,
                        ),
                        radius = size.minDimension * 0.50f,
                    )
                }
                if (searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(52.dp),
                        color = Color.White.copy(alpha = 0.82f),
                        strokeWidth = 3.dp,
                    )
                } else {
                    LiveWaveform(
                        level = animatedLevel,
                        animate = !reduceAnimation,
                        modifier = Modifier.size(width = 84.dp, height = 58.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(
                if (searching) R.string.recognition_searching else R.string.recognition_listening,
            ),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (!searching) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.recognition_hint),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Five rounded bars that move with the live level; never completely still. */
@Composable
private fun LiveWaveform(level: Float, animate: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "recognition-waveform")
    val phaseState = if (animate) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = LinearEasing),
            ),
            label = "waveform-phase",
        )
    } else {
        null
    }
    val phase = phaseState?.value ?: 0f
    Canvas(modifier) {
        val bars = 5
        val gap = size.width * 0.10f
        val barWidth = (size.width - gap * (bars - 1)) / bars
        val maxHeight = size.height * 0.94f
        val alpha = 0.62f + level * 0.38f
        repeat(bars) { index ->
            val wave = 0.30f + 0.70f * abs(sin(phase + index * 0.85f))
            val idleHeight = size.height * (0.22f + 0.12f * wave)
            val height = idleHeight + (maxHeight - idleHeight) * level * wave
            val left = index * (barWidth + gap)
            drawRoundRect(
                color = Color.White.copy(alpha = alpha),
                topLeft = Offset(left, (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/**
 * The matched song. Recognition never auto-plays: the button hands the title
 * and artist to the normal Search tab, where the listener picks the upload
 * they actually want.
 */
@Composable
private fun FoundContent(
    track: ShazamTrack,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
) {
    val artwork = track.artworkUrl
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(190.dp)
                .recognitionGlass(RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (!artwork.isNullOrBlank()) {
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(28.dp)),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = track.title,
            color = Color.White,
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (track.artist.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = track.artist,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onSearch,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.search),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
        TextButton(onClick = onRetry) {
            Text(
                text = stringResource(R.string.try_again),
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 14.sp,
            )
        }
    }
}

/** A settled answer or a failed request, with one clear way to start over. */
@Composable
private fun RecognitionMessageContent(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .recognitionGlass(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = body,
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 15.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onAction,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Text(
                text = actionLabel,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
    }
}

/** Shown after a refusal, with the route to the system toggle instead of a toast. */
@Composable
private fun MicrophoneDeniedContent(canAskAgain: Boolean, onEnable: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .recognitionGlass(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MicOff,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.recognition_permission_title),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.recognition_permission_body),
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 15.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onEnable,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Text(
                text = stringResource(
                    if (canAskAgain) {
                        R.string.recognition_enable_microphone
                    } else {
                        R.string.recognition_open_settings
                    },
                ),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
    }
}

/**
 * A pale frosted layer across the whole sheet, not just the orb. It samples the
 * page the dialog covers at the same low resolution the other glass surfaces
 * use, then keeps most of the sheet's dark weight on top so the blur reads as
 * texture rather than as another picture behind the text.
 */
@Composable
private fun Modifier.recognitionSheetSurface(): Modifier {
    val glass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    if (!glass || reduceDynamicBlur) {
        return background(Brush.verticalGradient(listOf(RecognitionTop, RecognitionBottom)))
    }
    val density = LocalDensity.current
    val blurPx = with(density) { 18.dp.toPx() * 0.33f }
    return drawBackdrop(
        backdrop = LocalAppBackdrop.current,
        shape = { RoundedCornerShape(0.dp) },
        effects = {
            colorControls(saturation = 1.12f)
            blur(blurPx)
        },
        highlight = null,
        shadow = null,
        onDrawSurface = {
            drawRect(Color.Black.copy(alpha = 0.42f))
        },
        backdropScale = 0.33f,
    ).background(
        Brush.verticalGradient(
            listOf(
                RecognitionTop.copy(alpha = 0.70f),
                RecognitionBottom.copy(alpha = 0.86f),
            ),
        ),
    )
}

/**
 * The main orb is a real glass surface: when Liquid Glass is on it samples the
 * app behind the dialog, so the room-facing control refracts the page rather
 * than sitting as a flat white disc. With the effect off, reduced, or on a
 * device without RenderEffect support, the same shape falls back to a smoked
 * fill and keeps the hairline the rest of the app uses on plain surfaces.
 */
@Composable
private fun Modifier.recognitionGlass(shape: CornerBasedShape): Modifier {
    val glass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    return clip(shape).then(
        if (glass && !reduceDynamicBlur) {
            Modifier.liquidGlass(shape = shape, surfaceTint = RecognitionGlassTint)
        } else {
            Modifier
                .background(RecognitionGlassFallback)
                .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
        },
    )
}

/**
 * Smaller controls only need the glass rim — the highlight that catches the
 * light along the edge. Skipping the backdrop capture here keeps the close and
 * cancel buttons from adding two more full blur passes to a screen that already
 * has the orb's.
 */
@Composable
private fun Modifier.recognitionGlassControl(shape: CornerBasedShape, fallback: Color): Modifier =
    clip(shape)
        .background(fallback)
        .liquidGlassRim(shape)

private fun Context.hasMicrophone(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun Activity.canAskForMicrophone(): Boolean =
    shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null)),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
