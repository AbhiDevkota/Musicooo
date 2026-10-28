package com.music.bitchord.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.music.bitchord.R
import java.util.Locale

/**
 * The microphone at the end of the search field, backed by whichever speech
 * recognizer the device already has.
 *
 * The listening sheet is the system's, so its own activity still performs the
 * capture; the first tap asks for `RECORD_AUDIO` because the microphone is
 * this app's control, and the in-app recognizer that replaces the sheet later
 * will record through that same grant. The recognizer is asked to answer in
 * the language the app is running in. Returning null when no recognizer is
 * installed — common on de-Googled builds — lets the field leave the icon out
 * rather than offer a button that can only fail.
 */
@Composable
fun rememberVoiceSearch(onResult: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    // The activity result lands on the launcher registered at first
    // composition, so the callback has to be read through a holder: otherwise
    // it keeps pointing at the search state that happened to be current then.
    val currentOnResult by rememberUpdatedState(onResult)
    val prompt = stringResource(R.string.voice_search_prompt)
    val unavailable = stringResource(R.string.voice_search_unavailable)
    val denied = stringResource(R.string.voice_search_permission_denied)
    // Asked of the same service that would handle the request, rather than
    // resolved as an intent: Android 11's package-visibility rules can hide
    // the recognizer activity, but not the service behind this call.
    val available = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isNotEmpty()) currentOnResult(spoken)
    }
    val startRecognizer: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        runCatching { launcher.launch(intent) }
            .onFailure {
                Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
            }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startRecognizer()
        } else {
            Toast.makeText(context, denied, Toast.LENGTH_SHORT).show()
        }
    }
    if (!available) return null
    // The first tap is where the microphone is asked for, the way every other
    // app that records does it; once the grant exists, every later tap goes
    // straight to the recognizer. Checking rather than holding a flag means a
    // grant revoked in system settings is asked for again on the next tap.
    return {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startRecognizer()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
