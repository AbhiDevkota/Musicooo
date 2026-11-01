package com.music.bitchord.widget

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request from the recognition widget to start listening.
 */
object RecognitionDeepLink {

    const val EXTRA_START_RECOGNITION = "bitchord.startRecognition"

    private val _pending = MutableStateFlow(false)

    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun consume(intent: Intent?): Boolean {
        if (intent == null || !intent.getBooleanExtra(EXTRA_START_RECOGNITION, false)) return false
        intent.removeExtra(EXTRA_START_RECOGNITION)
        _pending.value = true
        return true
    }

    fun handled() {
        _pending.value = false
    }
}