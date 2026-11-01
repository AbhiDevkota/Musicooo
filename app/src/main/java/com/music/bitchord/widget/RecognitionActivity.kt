package com.music.bitchord.widget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.data.shazam.RecognitionSession
import com.music.bitchord.data.shazam.RecognitionSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The recognition widget's microphone, in window form.
 *
 * The widget opens this instead of MainActivity, and the window paints
 * nothing — the launcher, or whatever the user was looking at, stays exactly
 * where it was for the seconds it takes to listen and name the song. The
 * sequence the widget promises is the whole of what happens here:
 *
 * 1. The mic permission is asked only when it is missing; a granted one goes
 *    straight past to listening, and a refusal ends the request where it
 *    stands — the pill goes back to resting and nothing opens, because the
 *    user just said no and the in-app mic button remains for later.
 * 2. Listening is [RecognitionSession] itself — the same staged
 *    2 → 4 → 8 → 12 second take the in-app sheet runs, with every look-up
 *    sharing the one accumulating clip — so the only thing this activity
 *    keeps is one watch on the state.
 * 3. A find hands `"Title - Artist"` to [RecognitionDeepLink] and then opens
 *    [MainActivity]: the app comes up on Search, matches the song to a track
 *    and starts playing it with the player card over everything.
 * 4. Nothing found goes back to silence: a toast from wherever the user is,
 *    and the window closes without ever having shown itself.
 *
 * Being backgrounded takes the microphone with it on Android's own rules, so
 * [onStop] mid-listen is treated as the user abandoning the request rather
 * than as something to finish in the background.
 */
class RecognitionActivity : Activity() {

    private var session: RecognitionSession? = null

    /**
     * The scope that runs both the session and the watch on it, kept so
     * [abandon] can cancel the whole thing at once. Alone it is a leak —
     * cancelling it is the only way a leaving take stops.
     */
    private var scope: CoroutineScope? = null
    private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MICROPHONE) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            // A refusal stops here rather than route somewhere that will ask
            // again — the answer was taken, not lost.
            RecognitionWidgetProvider.showListening(this, false)
            finish()
        }
    }

    // A second tap while a take is live is the stop button: the pill goes back
    // to sleep and the microphone lets go. With nothing running — the window
    // is already settling its ending — the tap changes nothing on purpose.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (listening) {
            abandon()
            finish()
        }
    }

    /**
     * Runs the one take. The session drives itself through every progressive
     * search and its own recorder cleanup; the coroutine below reads nothing
     * but the state stream, and is guaranteed one terminal state by the
     * filter it waits on.
     */
    private fun startListening() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        this.scope = scope
        val session = RecognitionSession(scope)
        this.session = session
        listening = true
        RecognitionWidgetProvider.showListening(this, true)
        scope.launch {
            val state = session.state.first {
                it is RecognitionSessionState.Found ||
                    it is RecognitionSessionState.NoMatch ||
                    it is RecognitionSessionState.Failed
            }
            listening = false
            when (state) {
                is RecognitionSessionState.Found -> withContext(Dispatchers.Main) {
                    // The match is posted before the launch, not during it:
                    // the composition reads the flow the moment it builds,
                    // and the stream is what carries the query whether
                    // MainActivity comes up cold or arrives warm.
                    RecognitionDeepLink.postMatch(state.track.searchQuery)
                    RecognitionWidgetProvider.showListening(applicationContext, false)
                    startActivity(
                        Intent(applicationContext, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    finish()
                }

                else -> withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, R.string.widget_recognition_no_match, Toast.LENGTH_SHORT)
                        .show()
                    RecognitionWidgetProvider.showListening(applicationContext, false)
                    finish()
                }
            }
        }
        session.start()
    }

    override fun onStop() {
        super.onStop()
        if (listening) abandon()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (listening) abandon()
    }

    /**
     * Leaves the take without an ending: the pill reverts before anything
     * else, the microphone stops, and the watch above is cancelled whole.
     * Idempotent by way of the flag, so the success path — which unsets
     * [listening] first and finishes the activity after — falling through
     * [onStop] on the way out touches none of this.
     */
    private fun abandon() {
        listening = false
        RecognitionWidgetProvider.showListening(this, false)
        session?.stop()
        scope?.cancel()
        session = null
        scope = null
    }

    private companion object {
        const val REQUEST_MICROPHONE = 41
    }
}
