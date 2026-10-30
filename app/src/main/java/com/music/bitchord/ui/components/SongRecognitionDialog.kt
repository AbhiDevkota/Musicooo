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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.music.bitchord.data.shazam.MicrophoneLevelMonitor
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

/** The live microphone has not been tapped yet, so the permission prompt owns the screen. */
private enum class MicrophoneAccess { Waiting, Granted, Denied }

/**
 * YouTube Music's "search a song" surface, opened by the microphone in the
 * search bar.
 *
 * The sheet owns the microphone grant for the same reason the search field
 * does: the first tap is the moment that permission makes sense, and the
 * listening state is this app's UI rather than the system speech recognizer's.
 * While the grant is live, [MicrophoneLevelMonitor] streams the mic level into
 * the orb, so the rings and bars answer the sound in the room instead of
 * running on a fixed loop. If the grant is refused the sheet stays useful by
 * explaining what it needs and offering the way back to the system toggle
 * instead of dropping a toast and closing.
 */
@Composable
fun SongRecognitionDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val monitor = remember(scope) { MicrophoneLevelMonitor(scope) }
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
            monitor.start()
        } else {
            monitor.stop()
        }
    }
    DisposableEffect(monitor) {
        onDispose { monitor.stop() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        monitor.stop()
    }
    // Coming back from the system prompt, or from Settings after the user
    // flipped the switch there, is the only way the grant can change without
    // this sheet doing anything. A revoked grant falls back to the explanation
    // rather than leaving a listening animation running with no microphone.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = context.hasMicrophone()
        if (granted) {
            access = MicrophoneAccess.Granted
            monitor.start()
        } else if (access == MicrophoneAccess.Granted) {
            access = MicrophoneAccess.Denied
        }
        canAskAgain = activity?.canAskForMicrophone() == true
    }
    val level by monitor.level.collectAsStateWithLifecycle()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(RecognitionTop, RecognitionBottom))),
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
                        onClick = onDismiss,
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
                        MicrophoneAccess.Waiting, MicrophoneAccess.Granted ->
                            ListeningContent(level = level)
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
                        .clickable(onClick = onDismiss)
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
 * inside it jump.
 */
@Composable
private fun ListeningContent(level: Float) {
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
            modifier = Modifier.size(300.dp),
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
                    .size(140.dp)
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
                LiveWaveform(
                    level = animatedLevel,
                    animate = !reduceAnimation,
                    modifier = Modifier.size(width = 64.dp, height = 44.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.recognition_listening),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.recognition_hint),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
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
        val alpha = 0.55f + level * 0.45f
        repeat(bars) { index ->
            val wave = 0.30f + 0.70f * abs(sin(phase + index * 0.85f))
            val idleHeight = size.height * (0.10f + 0.08f * wave)
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
