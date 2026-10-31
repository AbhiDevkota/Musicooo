package com.music.bitchord.data.shazam

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cleans up recognition clips left in the app's disposable cache directory.
 *
 * Recognition fingerprints the audio in memory and never writes a recording,
 * so the only clips this can find were saved as diagnostics by older builds.
 * The Settings action stays so an upgrade can reclaim that space.
 */
object RecognitionCache {

    private const val DIRECTORY_NAME = "recognition"

    private fun directory(context: Context): File = File(context.cacheDir, DIRECTORY_NAME)

    /** Deletes every recognition clip, including anything left by an older build. */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        val directory = directory(context)
        directory.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        runCatching { directory.delete() }
        Unit
    }
}
