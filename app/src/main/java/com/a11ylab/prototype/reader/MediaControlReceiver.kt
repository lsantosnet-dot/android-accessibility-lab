package com.a11ylab.prototype.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.a11ylab.prototype.capture.CaptureAccessibilityService

const val ACTION_MEDIA_CONTROL = "com.a11ylab.prototype.MEDIA_CONTROL"
const val EXTRA_MEDIA_ACTION = "action"

const val MEDIA_ACTION_PLAY_PAUSE = "play_pause"
const val MEDIA_ACTION_SKIP_NEXT = "skip_next"
const val MEDIA_ACTION_SKIP_PREVIOUS = "skip_previous"
const val MEDIA_ACTION_STOP = "stop"

/** Forwards taps on the lock-screen/notification media controls to the running accessibility service. */
class MediaControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MEDIA_CONTROL) return
        val action = intent.getStringExtra(EXTRA_MEDIA_ACTION) ?: return
        CaptureAccessibilityService.handleMediaControl(action)
    }
}
