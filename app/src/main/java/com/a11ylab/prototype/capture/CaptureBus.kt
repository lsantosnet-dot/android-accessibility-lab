package com.a11ylab.prototype.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory bridge between [CaptureAccessibilityService] and the overlay UI. No persistence. */
object CaptureBus {
    private val _isAutoScrollReading = MutableStateFlow(false)
    val isAutoScrollReading = _isAutoScrollReading.asStateFlow()

    fun setAutoScrollReading(enabled: Boolean) {
        _isAutoScrollReading.value = enabled
    }
}
