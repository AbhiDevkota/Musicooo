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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.music.bitchord.R

private val RecognitionTop = Color(0xFF1D1D20)
private val RecognitionBottom = Color(0xFF0A0A0B)

/** The live microphone has not been tapped yet, so the permission prompt owns the screen. */
private enum class MicrophoneAccess { Waiting, Granted, Denied }

/**
 * YouTube Music's "search a song" surface, opened by the microphone in the
 * search bar.
 *
 * The sheet owns the microphone grant for the same reason the search field
 * does: the first tap is the moment that permission makes sense, and the
 * listening state is this app's UI rather than the system speech recognizer's.
 * Capture and the Shazam lookup land in the [MicrophoneAccess.Granted] branch
 * without changing how the field opens this screen; if the grant is refused
 * the sheet stays useful by explaining what it needs and offering the way back
 * to the system toggle instead of dropping a toast and closing.
 */
@Composable
fun SongRecognitionDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
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
    // Coming back from the system prompt, or from Settings after the user
    // flipped the switch there, is the only way the grant can change without
    // this sheet doing anything. A revoked grant falls back to the explanation
    // rather than leaving a listening animation running with no microphone.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = context.hasMicrophone()
        if (granted) {
            access = MicrophoneAccess.Granted
        } else if (access == MicrophoneAccess.Granted) {
            access = MicrophoneAccess.Denied
        }
        canAskAgain = activity?.canAskForMicrophone() == true
    }

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
            // A soft halo under the control, so the white disc does not sit on
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
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = Color.White.copy(alpha = 0.75f),
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
                        MicrophoneAccess.Waiting, MicrophoneAccess.Granted -> ListeningContent()
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
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

/** The pulsing disc YouTube Music shows while it is listening. */
@Composable
private fun ListeningContent() {
    val transition = rememberInfiniteTransition(label = "song-recognition")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
        ),
        label = "pulse",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(280.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inner = size.minDimension * 0.20f
                val outer = size.minDimension * 0.48f
                repeat(3) { index ->
                    val progress = (pulse + index / 3f) % 1f
                    drawCircle(
                        color = Color.White.copy(alpha = 0.18f * (1f - progress)),
                        radius = inner + (outer - inner) * progress,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .graphicsLayer {
                        scaleX = breathe
                        scaleY = breathe
                    }
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = stringResource(R.string.recognition_microphone),
                    tint = Color.Black,
                    modifier = Modifier.size(40.dp),
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
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MicOff,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
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
