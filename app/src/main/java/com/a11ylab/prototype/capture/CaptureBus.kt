package com.a11ylab.prototype.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val MAX_EVENTS = 50

/** In-memory bridge between [CaptureAccessibilityService] and the overlay UI. No persistence. */
object CaptureBus {
    private val _events = MutableStateFlow<List<CaptureEvent>>(emptyList())
    val events = _events.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _isAutoScrollReading = MutableStateFlow(false)
    val isAutoScrollReading = _isAutoScrollReading.asStateFlow()

    fun push(event: CaptureEvent) {
        if (_isPaused.value) return
        _events.update { current -> (listOf(event) + current).take(MAX_EVENTS) }
    }

    fun clear() {
        _events.value = emptyList()
    }

    fun togglePaused() {
        _isPaused.update { !it }
    }

    fun setAutoScrollReading(enabled: Boolean) {
        _isAutoScrollReading.value = enabled
    }
}
