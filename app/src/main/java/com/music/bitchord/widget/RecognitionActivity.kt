package com.music.bitchord.widget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
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
 *    to the launcher as frame bitmaps — [showWave].
 * 3. A find hands `"Title - Artist"` to [RecognitionDeepLink] and then opens
 *    [MainActivity]: the app comes up on Search, matches the song to a track
 *    and starts playing it with the player card over everything.
 * 4. Nothing found goes back to sleep: a toast from wherever the user is,
 *    and the window closes without ever having shown itself.
 *
 * While a take is live the window — every touch of it — is the stop button.
 * The second tap on the widget lands on this glass first, and stops there;
 * a tap anywhere else stops just the same, like any tap-to-cancel overlay.
 * Nothing below the glass can be reached while a take runs, and nothing has
 * to be: there is no second meaning a touch could have.
 *
 * Being backgrounded takes the microphone with it on Android's own rules, so
 * [onStop] mid-listen is treated as the user abandoning the request rather
 * than as something to finish in the background.
 */
class RecognitionActivity : Activity() {

    private var session: RecognitionSession? = null

    /**
     * The scope that runs the frame watch, kept so [abandon] can cancel the
     * whole thing at once. Alone it is a leak — cancelling it is the only way
     * a leaving take stops.
     */
    private var scope: CoroutineScope? = null
    private var listening = false

    /**
     * The one bitmap every listening frame is drawn into. Reused across the
     * take so the render loop never allocates — a fresh bitmap per frame at
     * the taking cadence GC-pauses itself into the stutter the partial
     * updates just removed.
     */
    private var frameBitmap: Bitmap? = null

    /**
     * The glass itself is the stop button: while a take is live, any touch
     * on the window ends it — the second tap on the widget plainly lands
     * here first, because this window sits over the launcher.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (listening) {
            abandon()
            finish()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A paint-less content view, so the touches have a full pipeline to
        // run down and [dispatchTouchEvent] receives them nowhere but here.
        // The window keeps painting nothing: the view is transparent ink,
        // the theme beneath it unchanged.
        setContentView(View(this))
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

    // A second tap while a take is live is the stop button: the pill goes
    // back to sleep and the microphone lets go. A tap into a live window
    // whose take is already over — abandoned from the background, or already
    // settling its ending — restarts the listen, exactly what a first tap
    // would have done on a clean launcher.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (listening) {
            abandon()
            finish()
        } else {
            startListening()
        }
    }

    /**
     * Runs the one take: the session drives itself through every progressive
     * search and its own recorder cleanup — recording on IO, fingerprinting
     * and lookups on workers, none of it here — while the frame watch that
     * drives the widget rides this same thread. The loop steps the sheet's
     * waveform on real time — 2π over 1.4 seconds, the sheet's own period —
     * and the frames convene with the music: tight while a beat is in the
     * room, easy in the silences between. Falling out of the loop is the
     * same moment the session reaches a terminal state.
     */
    private fun startListening() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        this.scope = scope
        val session = RecognitionSession(scope)
        this.session = session
        listening = true
        val density = applicationContext.resources.displayMetrics.density
        scope.launch {
            session.start()
            var phase = 0f
            var lastFrame = SystemClock.elapsedRealtime()
            var state = session.state.value
            while (
                state == RecognitionSessionState.Listening ||
                state == RecognitionSessionState.Searching
            ) {
                val level = session.level.value
                delay(if (level >= BEAT_LEVEL) BEAT_FRAME_MS else IDLE_FRAME_MS)
                val now = SystemClock.elapsedRealtime()
                phase = (phase + (now - lastFrame) * PHASE_PER_MS) % (2 * PI).toFloat()
                lastFrame = now
                RecognitionWidgetProvider.showWave(applicationContext, nextFrame(density, phase, level))
                state = session.state.value
            }
            listening = false
            when (val settled = state) {
                is RecognitionSessionState.Found -> {
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
                    // The app arrives on a fade rather than the launcher's
                    // window-slam: the pill just finished breathing on the
                    // home screen, so the transition into playing the song
                    // is the two joining, not one replacing the other.
                    overridePendingTransition(
                        android.R.anim.fade_in,
                        android.R.anim.fade_out,
                    )
                    finish()
                }

                else -> {
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
     * Leaves the take without an ending: the watch is cancelled, the pill
     * goes back to sleep — right now, in this same turn of the looper — and
     * the microphone lets go. Idempotent by way of the flag, so the settled
     * paths — which unset [listening] first and close the window after —
     * falling through [onStop] on the way out touch none of this.
     *
     * The sleep can't be raced here, and that is the point of frames riding
     * the main thread: a cancelled watch's next frame is never given a turn,
     * and no frame of the last one exists anymore by the time this runs. The
     * sleep face is therefore the last picture the widget is handed — a stop
     * always reads as sleep, the state the second tap promised.
     */
    private fun abandon() {
        listening = false
        scope?.cancel()
        RecognitionWidgetProvider.showSleep(this)
        session?.stop()
        session = null
        scope = null
    }

    /**
     * One frame of the sheet's [LiveWaveform], drawn white on the black
     * capsule exactly as the sheet wears its own bars. The arithmetic is the
     * composable's, bar for bar: an idle height that breathes on a per-bar
     * phase, scaled up by the current level. The sleeping face is this
     * drawing at phase zero frozen into [ic_widget_wave_sleep], so the very
     * first frame the widget shows is a continuation of its own sleep.
     *
     * The frame is reused across the whole take — only the beat in it
     * changes from one to the next — because allocating a fresh bitmap per
     * frame at this cadence is a GC pause away from the stutter the partial
     * updates just removed.
     */
    private fun nextFrame(density: Float, phase: Float, level: Float): Bitmap {
        val width = (WAVE_WIDTH_DP * density).roundToInt()
        val height = (WAVE_HEIGHT_DP * density).roundToInt()
        val bitmap = frameBitmap
            ?.takeIf { it.width == width && it.height == height && it.isMutable }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .also { frameBitmap = it }
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        val gap = width * 0.10f
        val barWidth = (width - gap * 4f) / 5f
        val maxHeight = height * 0.94f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BAR_COLOR }
        val bar = RectF()
        repeat(5) { index ->
            val wave = 0.30f + 0.70f * abs(sin(phase + index * 0.85f))
            val idleHeight = height * (0.22f + 0.12f * wave)
            val barHeight = idleHeight + (maxHeight - idleHeight) * level * wave
            val left = index * (barWidth + gap)
            bar.set(left, (height - barHeight) / 2f, left + barWidth, (height + barHeight) / 2f)
            paint.alpha = ((0.62f + level * 0.38f) * 0xFF + 0.5f).toInt()
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, paint)
        }
        return bitmap
    }

    private companion object {
        const val REQUEST_MICROPHONE = 41

        /**
         * The frames' cadence in a room with music against a room resting.
         * Faster than the sheet's own cycle when the beat is coming through,
         * easy on the long silences a recognition listen is full of.
         */
        const val BEAT_FRAME_MS = 55L
        const val IDLE_FRAME_MS = 110L
        const val BEAT_LEVEL = 0.22f

        /** The waveform itself: the sheet runs its phase over 1.4 seconds. */
        const val PHASE_PER_MS = ((2 * PI).toFloat()) / 1400f

        /** The ImageView the frames land in, from widget_recognition_wide.xml. */
        const val WAVE_WIDTH_DP = 88f
        const val WAVE_HEIGHT_DP = 54f

        /** The bars, white on the black capsule exactly as the sheet wears them. */
        const val BAR_COLOR = 0xFFFFFFFF.toInt()
    }
}
