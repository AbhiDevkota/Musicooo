package com.music.bitchord.data.shazam

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Stores what the microphone heard while a recognition was in progress.
 *
 * The clips live in the app's disposable cache directory, not in the song cache:
 * they are diagnostic recordings, not audio the listener chose to keep. The
 * folder is trimmed after every save so a long-lived install cannot accumulate
 * them without bound, and Settings offers a direct clear for the rest.
 */
object RecognitionCache {

    private const val DIRECTORY_NAME = "recognition"
    private const val MAX_FILES = 8
    private const val MAX_BYTES = 8L * 1024 * 1024

    fun directory(context: Context): File = File(context.cacheDir, DIRECTORY_NAME)

    /**
     * Writes [sampleCount] samples from [samples] as a mono 16-bit WAV.
     *
     * Blocking disk I/O, so callers run it on a background dispatcher. Returns
     * null when there is nothing to save or the cache cannot be written; a
     * recognition result must not fail because its diagnostic copy did.
     */
    fun save(
        context: Context,
        samples: ShortArray,
        sampleCount: Int,
        sampleRateHz: Int = ShazamSignatureGenerator.SAMPLE_RATE_HZ,
    ): File? {
        if (sampleCount <= 0) return null
        val directory = directory(context)
        if (!directory.exists() && !directory.mkdirs()) return null
        val file = File(directory, "recognition-${System.currentTimeMillis()}.wav")
        return runCatching {
            writeWav(file, samples, sampleCount, sampleRateHz)
            trim(context)
            file
        }.onFailure {
            runCatching { file.delete() }
        }.getOrNull()
    }

    /**
     * Keeps the newest [MAX_FILES] clips, and never more than [MAX_BYTES] in
     * total. The newest clip is kept even if a single recording exceeds the
     * byte budget, so the one just made for the current attempt survives.
     */
    fun trim(context: Context) {
        val files = directory(context).listFiles { file ->
            file.isFile && file.extension.equals("wav", ignoreCase = true)
        } ?: return
        var totalBytes = files.sumOf { it.length() }
        files.sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
            val overLimit = index >= MAX_FILES || (index > 0 && totalBytes > MAX_BYTES)
            if (overLimit) {
                val length = file.length()
                if (file.delete()) totalBytes -= length
            }
        }
    }

    /** Deletes every recognition clip, including anything left by an older build. */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        val directory = directory(context)
        directory.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        runCatching { directory.delete() }
        Unit
    }

    internal fun writeWav(
        file: File,
        samples: ShortArray,
        sampleCount: Int,
        sampleRateHz: Int,
    ) {
        require(sampleCount in 0..samples.size)
        val dataSize = sampleCount * 2
        BufferedOutputStream(FileOutputStream(file)).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeI32(output, 36 + dataSize)
            output.write("WAVE".toByteArray(Charsets.US_ASCII))
            output.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeI32(output, 16)
            writeI16(output, 1) // PCM
            writeI16(output, 1) // mono
            writeI32(output, sampleRateHz)
            writeI32(output, sampleRateHz * 2) // byte rate
            writeI16(output, 2) // block align
            writeI16(output, 16) // bits per sample
            output.write("data".toByteArray(Charsets.US_ASCII))
            writeI32(output, dataSize)
            for (index in 0 until sampleCount) {
                val sample = samples[index].toInt()
                output.write(sample and 0xff)
                output.write((sample ushr 8) and 0xff)
            }
        }
    }

    private fun writeI16(output: BufferedOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeI32(output: BufferedOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }
}
