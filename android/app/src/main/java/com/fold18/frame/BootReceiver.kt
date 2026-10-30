package com.fold18.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Scheduled jobs are dropped on reboot and app update; re-arm the screenshot watcher. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Settings(context).watchScreenshots) ScreenshotJobService.schedule(context)
    }
}
