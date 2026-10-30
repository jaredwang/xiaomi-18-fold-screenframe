package com.fold18.frame

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The system share sheet calls this when a target is picked, which is how we
 * tell a finished share from a dismissed sheet. Some ROM share sheets skip the
 * callback; MainActivity also treats "we went to the background" as picked.
 */
class ShareChosenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        chosen = true
    }

    companion object {
        @Volatile
        var chosen = false

        /** Mutable so the system can attach the chosen component. */
        fun callback(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, ShareChosenReceiver::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
