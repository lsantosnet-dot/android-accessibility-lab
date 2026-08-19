package com.a11ylab.prototype.capture

data class CaptureEvent(
    val timestampMillis: Long,
    val packageName: String,
    val className: String,
    val eventType: String,
    val text: String,
)
