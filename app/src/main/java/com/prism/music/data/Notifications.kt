package com.prism.music.data

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.prism.music.PrismActivity
import com.prism.music.R

/**
 * Prism's own notifications (besides playback and downloads, which have theirs): each kind has its channel, so the
 * listener can also silence one in Android's settings, and each is off when its switch in Prism's Settings is.
 */
object Notifications {
    const val NEW_RELEASES = "new_releases"
    const val REWIND = "rewind"
    const val RECOGNITION = "recognition"
    const val CONNECT = "connect"

    fun createChannels(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        // Pairing codes are wanted on screen at once (the other device is waiting for them).
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CONNECT, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Vinz Connect").setDescription("Pairing codes when another device wants to play here").build()
        )
        listOf(
            Triple(NEW_RELEASES, "New releases", "New music from artists you follow"),
            Triple(REWIND, "Rewind", "When your year in music is ready"),
            Triple(RECOGNITION, "Song recognition", "Songs recognized after you were offline"),
        ).forEach { (id, name, description) ->
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(id, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(name).setDescription(description).build()
            )
        }
    }

    /** Whether Android lets Prism post at all (the permission on 13+, and notifications not blocked for the app). */
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Opens Prism at [link] (a deep link MainActivity understands, e.g. "prism://releases"), or just Prism. */
    fun openIntent(context: Context, link: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, PrismActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (link != null) intent.data = Uri.parse(link)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Posts a notification on [channel]; quietly does nothing when notifications aren't allowed. */
    fun post(context: Context, channel: String, id: Int, title: String, text: String, link: String?, build: (NotificationCompat.Builder) -> Unit = {}) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, link, id))
            .also(build)
            .build()
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(channel, id, n) }
    }
}
