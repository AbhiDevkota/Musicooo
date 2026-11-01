package com.music.bitchord.widget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

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
 *    stands — the pill goes back to sleep and nothing opens, because the
 *    user just said no and the in-app mic button remains for later.
 * 2. Listening is [RecognitionSession] itself — the same staged
 *    2 → 4 → 8 → 12 second take the in-app sheet runs, with every look-up
 *    sharing the one accumulating clip. Its [RecognitionSession.level] is
 *    what the pill's bars are drawn from: the same five-bar waveform the
 *    sheet draws, phase-breathing on idle and rising with the room, shipped
 *    to the launcher as frame bitmaps at [FRAME_MS] cadence — [showWave].
 * 3. A find hands `"Title - Artist"` to [RecognitionDeepLink] and then opens
 *    [MainActivity]: the app comes up on Search, matches the song to a track
 *    and starts playing it with the player card over everything.
 * 4. Nothing found goes back to sleep: a toast from wherever the user is,
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
            RecognitionWidgetProvider.showSleep(this)
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
     * Runs the one take: the session drives itself through every progressive
     * search and its own recorder cleanup, and this coroutine drives the pill
     * while the session lives. The loop steps the sheet's waveform at the
     * sheet's own cadence — the only states that keep the session alive are
     * Listening and Searching — so falling out of the loop is the same
     * moment the session reaches a terminal state.
     */
    private fun startListening() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        this.scope = scope
        val session = RecognitionSession(scope)
        this.session = session
        listening = true
        val density = applicationContext.resources.displayMetrics.density
        scope.launch {
            session.start()
            var phase = 0f
            var state = session.state.value
            while (
                state == RecognitionSessionState.Listening ||
                state == RecognitionSessionState.Searching
            ) {
                val level = if (state == RecognitionSessionState.Listening) session.level.value else 0f
                RecognitionWidgetProvider.showWave(applicationContext, renderWave(density, phase, level))
                delay(FRAME_MS)
                phase = (phase + FRAME_MS * PHASE_PER_MS) % (2 * PI).toFloat()
                state = session.state.value
            }
            listening = false
            when (val settled = state) {
                is RecognitionSessionState.Found -> withContext(Dispatchers.Main) {
                    // The match is posted before the launch, not during it:
                    // the composition reads the flow the moment it builds,
                    // and the stream is what carries the query whether
                    // MainActivity comes up cold or arrives warm.
                    RecognitionDeepLink.postMatch(settled.track.searchQuery)
                    RecognitionWidgetProvider.showSleep(applicationContext)
                    startActivity(
                        Intent(applicationContext, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    finish()
                }

                else -> withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, R.string.widget_recognition_no_match, Toast.LENGTH_SHORT)
                        .show()
                    RecognitionWidgetProvider.showSleep(applicationContext)
                    finish()
                }
            }
        }
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
     * Leaves the take without an ending: the pill goes back to sleep before
     * anything else, the microphone stops, and the watch above is cancelled
     * whole. Idempotent by way of the flag, so the settled paths — which
     * unset [listening] first and close the window after — falling through
     * [onStop] on the way out touch none of this.
     */
    private fun abandon() {
        listening = false
        RecognitionWidgetProvider.showSleep(this)
        session?.stop()
        scope?.cancel()
        session = null
        scope = null
    }

    /**
     * One frame of the sheet's [LiveWaveform], drawn black on white so the
     * widget carries the sheet's own white-pill vocabulary. The arithmetic is
     * the composable's, bar for bar: an idle height that breathes on a
     * per-bar phase, scaled up by the current level. The sleeping face is
     * this drawing at phase zero frozen into [ic_widget_wave_sleep], so the
     * very first frame the pill shows is a continuation of its own sleep.
     */
    private fun renderWave(density: Float, phase: Float, level: Float): Bitmap {
        val width = (WAVE_WIDTH_DP * density).roundToInt()
        val height = (WAVE_HEIGHT_DP * density).roundToInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val gap = width * 0.10f
        val barWidth = (width - gap * 4f) / 5f
        val maxHeight = height * 0.94f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BAR_COLOR
        }
        val bar = RectF()
        val alphaScale = 0.62f + level * 0.38f
        repeat(5) { index ->
            val wave = 0.30f + 0.70f * abs(sin(phase + index * 0.85f))
            val idleHeight = height * (0.22f + 0.12f * wave)
            val barHeight = idleHeight + (maxHeight - idleHeight) * level * wave
            val left = index * (barWidth + gap)
            bar.set(left, (height - barHeight) / 2f, left + barWidth, (height + barHeight) / 2f)
            paint.alpha = (alphaScale * 0xFF).roundToInt()
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, paint)
        }
        return bitmap
    }

    private companion object {
        const val REQUEST_MICROPHONE = 41

        /** The waveform's cadence: the sheet runs its phase over 1.4 seconds. */
        const val FRAME_MS = 90L
        const val PHASE_PER_MS = ((2 * PI).toFloat()) / 1400f

        /** The ImageView the frames land in, from widget_recognition_wide.xml. */
        const val WAVE_WIDTH_DP = 56f
        const val WAVE_HEIGHT_DP = 34f

        /** The pill's bars, the white button's content turned all the way dark. */
        const val BAR_COLOR = 0xFF0E1013.toInt()
    }
}
