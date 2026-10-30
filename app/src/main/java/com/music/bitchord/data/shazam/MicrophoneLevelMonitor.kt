package com.music.bitchord.data.shazam

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Streams the microphone as a smoothed loudness reading for the recognition
 * animation.
 *
 * The recorder already runs at [ShazamSignatureGenerator.SAMPLE_RATE_HZ] mono
 * PCM16, so the same read loop can hand its samples to the signature generator
 * in the next stage without reopening the microphone. Nothing is buffered or
 * uploaded here.
 *
 * The reading is deliberately not the raw RMS: [LEVEL_GAIN] and the square root
 * put the quiet end of a room and a phone held near a speaker on the same
 * readable 0..1 scale, which is what the orb's rings and bars animate against.
 */
class MicrophoneLevelMonitor(private val scope: CoroutineScope) {

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var job: Job? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (job?.isActive == true) return
        val minBuffer = AudioRecord.getMinBufferSize(
            ShazamSignatureGenerator.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return
        val bufferSize = max(minBuffer, LEVEL_WINDOW_SAMPLES * 2)
        val recorder = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(ShazamSignatureGenerator.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        }.getOrNull() ?: return
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return
        }
        record = recorder
        job = scope.launch(Dispatchers.IO) {
            val samples = ShortArray(LEVEL_WINDOW_SAMPLES)
            try {
                recorder.startRecording()
                while (isActive) {
                    val read = recorder.read(samples, 0, samples.size)
                    if (read < 0) break
                    if (read == 0) continue
                    var sum = 0.0
                    for (index in 0 until read) {
                        val sample = samples[index] / 32768.0
                        sum += sample * sample
                    }
                    val rms = sqrt(sum / read).toFloat()
                    val next = sqrt((rms * LEVEL_GAIN).coerceIn(0f, 1f))
                    val previous = _level.value
                    // Jump towards a new sound, fall away gently, so the rings
                    // answer a beat instead of flickering at the read rate.
                    val weight = if (next > previous) ATTACK else RELEASE
                    _level.value = previous + (next - previous) * weight
                }
            } catch (_: Exception) {
                // A call or another recorder can take the microphone away
                // mid-listen. Let the orb settle rather than close the sheet.
            } finally {
                runCatching { recorder.stop() }
                if (record === recorder) {
                    record = null
                    runCatching { recorder.release() }
                }
                _level.value = 0f
            }
        }
    }

    fun stop() {
        val recorder = record
        record = null
        job?.cancel()
        job = null
        recorder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        _level.value = 0f
    }

    private companion object {
        /** ~32 ms at 16 kHz: enough to animate, short enough to feel live. */
        const val LEVEL_WINDOW_SAMPLES = 512
        const val LEVEL_GAIN = 8f
        const val ATTACK = 0.6f
        const val RELEASE = 0.12f
    }
}
