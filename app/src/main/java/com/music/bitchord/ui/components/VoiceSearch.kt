package com.music.bitchord.ui.components

import android.content.Intent
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
import com.music.bitchord.R
import java.util.Locale

/**
 * The microphone at the end of the search field, backed by whichever speech
 * recognizer the device already has.
 *
 * The listening is the system's, not the app's: launching its recognition
 * activity means no `RECORD_AUDIO` permission of our own, the listening sheet
 * is the one the user already knows, and the recognizer is asked for its
 * answer in the same language the app is running in. The returned null when
 * no recognizer is installed — common on de-Googled builds — lets the field
 * leave the icon out rather than offer a button that can only fail.
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
    if (!available) return null
    return remember(context, launcher, prompt, unavailable) {
        {
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
    }
}
