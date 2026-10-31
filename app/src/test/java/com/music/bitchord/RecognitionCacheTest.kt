package com.music.bitchord

import com.music.bitchord.data.shazam.RecognitionCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class RecognitionCacheTest {

    @Test
    fun `writes a mono 16-bit WAV with the recorded samples`() {
        val file = File.createTempFile("recognition-", ".wav")
        try {
            val samples = shortArrayOf(0, 1, -1, 32767, -32768)
            RecognitionCache.writeWav(
                file = file,
                samples = samples,
                sampleCount = samples.size,
                sampleRateHz = 16_000,
            )

            val bytes = file.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
            assertEquals(1, readI16(bytes, 20))
            assertEquals(1, readI16(bytes, 22))
            assertEquals(16_000, readI32(bytes, 24))
            assertEquals(32_000, readI32(bytes, 28))
            assertEquals(2, readI16(bytes, 32))
            assertEquals(16, readI16(bytes, 34))
            assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
            assertEquals(samples.size * 2, readI32(bytes, 40))
            assertEquals(44 + samples.size * 2, bytes.size)

            val decoded = ShortArray(samples.size) { index ->
                readI16(bytes, 44 + index * 2).toShort()
            }
            assertArrayEquals(samples, decoded)
            assertEquals(36 + samples.size * 2, readI32(bytes, 4))
        } finally {
            file.delete()
        }
    }

    private fun readI16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readI32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
