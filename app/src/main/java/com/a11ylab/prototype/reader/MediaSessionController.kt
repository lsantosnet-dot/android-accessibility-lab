package com.a11ylab.prototype.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.a11ylab.prototype.R
import com.a11ylab.prototype.capture.CaptureAccessibilityService

private const val CHANNEL_ID = "screen_reading_playback"
private const val NOTIFICATION_ID = 1001

/**
 * Mirrors [ScreenReader]'s state into a [MediaSessionCompat] + `MediaStyle` notification, the
 * mechanism the system uses to draw the media card on the lock screen. Regular overlay windows
 * (see [com.a11ylab.prototype.overlay.OverlayManager]) never draw over the secure lock screen,
 * so this is a separate, parallel surface — it doesn't replace or touch the floating panel.
 */
class MediaSessionController(private val service: Service) {

    private val context: Context = service.applicationContext
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val mediaSession = MediaSessionCompat(context, "A11yLabScreenReader")

    init {
        ensureChannel()
        // The lock screen's dedicated media widget (SystemUI's "media carousel", since Android 11)
        // drives its buttons through MediaController.getTransportControls() — i.e. this callback —
        // not through the notification actions' PendingIntents below. Both are wired to the same
        // handleMediaControl() so ordinary notification taps keep working too.
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = CaptureAccessibilityService.handleMediaControl(MEDIA_ACTION_PLAY_PAUSE)
            override fun onPause() = CaptureAccessibilityService.handleMediaControl(MEDIA_ACTION_PLAY_PAUSE)
            override fun onSkipToNext() = CaptureAccessibilityService.handleMediaControl(MEDIA_ACTION_SKIP_NEXT)
            override fun onSkipToPrevious() = CaptureAccessibilityService.handleMediaControl(MEDIA_ACTION_SKIP_PREVIOUS)
            override fun onStop() = CaptureAccessibilityService.handleMediaControl(MEDIA_ACTION_STOP)
        })
    }

    /** Pushes a new reading state: updates the session, and shows/updates the notification. */
    fun updateState(state: ReaderState, currentSegment: Int, totalSegments: Int) {
        if (state == ReaderState.IDLE) {
            hide()
            return
        }

        val isPlaying = state == ReaderState.READING
        mediaSession.isActive = true
        mediaSession.setPlaybackState(buildPlaybackState(isPlaying))
        val notification = buildNotification(isPlaying, currentSegment, totalSegments)

        if (isPlaying) {
            ServiceCompat.startForeground(
                service,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            // Paused: keep the card visible (so it can be resumed from the lock screen) but stop
            // pinning the service in the foreground state.
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_DETACH)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    /** Tears down the visible card without releasing the session — used when reading stops. */
    fun hide() {
        mediaSession.isActive = false
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /** Final teardown — releases the [MediaSessionCompat] itself. */
    fun release() {
        hide()
        mediaSession.release()
    }

    private fun buildPlaybackState(isPlaying: Boolean): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP,
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1f,
            )
            .build()

    private fun buildNotification(isPlaying: Boolean, currentSegment: Int, totalSegments: Int): Notification {
        val contentText = if (totalSegments > 0) "Trecho $currentSegment de $totalSegments" else "Lendo a tela"

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(R.drawable.ic_media_pause, "Pausar", controlPendingIntent(MEDIA_ACTION_PLAY_PAUSE))
        } else {
            NotificationCompat.Action(R.drawable.ic_media_play, "Reproduzir", controlPendingIntent(MEDIA_ACTION_PLAY_PAUSE))
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Accessibility Lab")
            .setContentText(contentText)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(controlPendingIntent(MEDIA_ACTION_STOP))
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_media_skip_previous,
                    "Voltar",
                    controlPendingIntent(MEDIA_ACTION_SKIP_PREVIOUS),
                ),
            )
            .addAction(playPauseAction)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_media_skip_next,
                    "Avançar",
                    controlPendingIntent(MEDIA_ACTION_SKIP_NEXT),
                ),
            )
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun controlPendingIntent(action: String): PendingIntent {
        val intent = Intent(context, MediaControlReceiver::class.java).apply {
            setAction(ACTION_MEDIA_CONTROL)
            putExtra(EXTRA_MEDIA_ACTION, action)
        }
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Leitura de tela",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Controles de reprodução da leitura em voz alta, incluindo a tela de bloqueio."
        }
        notificationManager.createNotificationChannel(channel)
    }
}
