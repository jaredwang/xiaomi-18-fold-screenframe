package com.veltrix.fold.frame

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Size

object Notifications {
    private const val PROMPT = "screenshot_prompt"

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(PROMPT, context.getString(R.string.channel_prompt), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_prompt_description)
                setSound(null, null)
            },
        )
    }

    /** "New screenshot — 加壳": opens the editor with it. */
    fun showPrompt(context: Context, screenshot: Uri) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val thumb = runCatching { context.contentResolver.loadThumbnail(screenshot, Size(512, 512), null) }.getOrNull()
        val open = PendingIntent.getActivity(
            context, screenshot.hashCode(), MainActivity.openIntent(context, screenshot),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, PROMPT)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.prompt_title))
            .setContentText(context.getString(R.string.prompt_text))
            .setLargeIcon(thumb)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.prompt_action), open).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(screenshot.hashCode(), n)
    }
}
