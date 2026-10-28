/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.music.bitchord.playback.audio.bitperfect

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.BitPerfectMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a [BitPerfectAudioSink] asks of [BitPerfectOutput], kept narrow so a test can stand in for it. */
internal interface BitPerfectOutputs {
    fun canOpen(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any): Boolean

    fun open(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any): BitPerfectTransport

    fun release(requester: Any)

    fun markFailed(sampleRate: Int, channels: Int, sourceBits: Int, reason: String)

    fun publish(status: BitPerfectOutput.Status)

    fun publishIdle()
}

/**
 * Process-wide owner of the one exclusive output.
 *
 * Both crossfade players build a [BitPerfectAudioSink], but a DAC opened
 * bit-perfect has room for a single stream: Android's bit-perfect mixer
 * mutes everything else, and a claimed USB interface belongs to one handle.
 * Whichever sink opens first holds it until it releases; the other is told
 * no and stays on the ordinary path.
 *
 * A route that refused to open is remembered until the attached device or
 * the selected mode changes, so a track that keeps failing is not retried on
 * every buffer.
 */
internal object BitPerfectOutput {

    private const val TAG = "BitPerfectOutput"

    data class Status(
        val active: Boolean = false,
        val driver: String? = null,
        val deviceName: String? = null,
        val sampleRateHz: Int = 0,
        val outputBits: Int = 0,
        val sourceBits: Int = 0,
        /** False once any sample of the current track had to be rounded. */
        val exact: Boolean = true,
        /** Why the last attempt fell back to normal output, if it did. */
        val fallbackReason: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun forContext(context: Context): BitPerfectOutputs {
        val app = context.applicationContext
        return object : BitPerfectOutputs {
            override fun canOpen(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any) =
                canOpen(app, sampleRate, channels, sourceBits, requester)

            override fun open(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any) =
                open(app, sampleRate, channels, sourceBits, requester)

            override fun release(requester: Any) = this@BitPerfectOutput.release(requester)

            override fun markFailed(sampleRate: Int, channels: Int, sourceBits: Int, reason: String) =
                markFailed(app, sampleRate, channels, sourceBits, reason)

            override fun publish(status: Status) = this@BitPerfectOutput.publish(status)

            override fun publishIdle() = this@BitPerfectOutput.publishIdle()
        }
    }

    private var owner: Any? = null
    private val failed = HashSet<String>()
    private var failedFor: String? = null

    /** Whether a sink asking for this stream now could get the exclusive output. */
    @Synchronized
    fun canOpen(context: Context, sampleRate: Int, channels: Int, sourceBits: Int, requester: Any): Boolean {
        if (owner != null && owner !== requester) return false
        val mode = AppSettings.bitPerfectMode.value
        if (mode == BitPerfectMode.OFF) return false
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        return when (mode) {
            BitPerfectMode.OFF -> false
            BitPerfectMode.USB_ANDROID -> {
                if (Build.VERSION.SDK_INT < 34) return false
                val device = MixerTransport.usbDevice(manager) ?: return false
                if (key(mode, device.id, sampleRate, channels, sourceBits) in currentFailures(mode, device.id)) {
                    return false
                }
                BitPerfectPcm.outputBits(
                    sourceBits,
                    MixerTransport.supportedBits(manager, device, sampleRate, channels),
                ) != null
            }
        }
    }

    /** Opens the exclusive output for [requester], or throws with the reason. */
    @Synchronized
    fun open(
        context: Context,
        sampleRate: Int,
        channels: Int,
        sourceBits: Int,
        requester: Any,
    ): BitPerfectTransport {
        if (owner != null && owner !== requester) throw BitPerfectTransportException("busy")
        val manager = context.getSystemService(AudioManager::class.java)
            ?: throw BitPerfectTransportException("no_audio_manager")
        val mode = AppSettings.bitPerfectMode.value
        val transport = when (mode) {
            BitPerfectMode.OFF -> throw BitPerfectTransportException("off")
            BitPerfectMode.USB_ANDROID -> {
                if (Build.VERSION.SDK_INT < 34) throw BitPerfectTransportException("android_version")
                MixerTransport.open(manager, sampleRate, channels, sourceBits)
            }
        }
        owner = requester
        return transport
    }

    @Synchronized
    fun release(requester: Any) {
        if (owner === requester) owner = null
    }

    /** Records that this stream could not be opened on the attached device. */
    @Synchronized
    fun markFailed(context: Context, sampleRate: Int, channels: Int, sourceBits: Int, reason: String) {
        val mode = AppSettings.bitPerfectMode.value
        val manager = context.getSystemService(AudioManager::class.java)
        val deviceId = manager?.let(MixerTransport::usbDevice)?.id ?: -1
        currentFailures(mode, deviceId).add(key(mode, deviceId, sampleRate, channels, sourceBits))
        Log.i(TAG, "Bit-perfect output unavailable ($reason) for $sampleRate Hz/$channels ch/$sourceBits-bit")
        _status.value = Status(fallbackReason = reason)
    }

    fun publish(status: Status) {
        _status.value = status
    }

    /** Clears an active status, leaving a fallback reason in place for the settings row. */
    fun publishIdle() {
        if (_status.value.active) _status.value = Status()
    }

    private fun currentFailures(mode: BitPerfectMode, deviceId: Int): MutableSet<String> {
        val scope = "$mode/$deviceId"
        if (failedFor != scope) {
            failed.clear()
            failedFor = scope
        }
        return failed
    }

    private fun key(mode: BitPerfectMode, deviceId: Int, rate: Int, channels: Int, bits: Int) =
        "$mode/$deviceId/$rate/$channels/$bits"
}
