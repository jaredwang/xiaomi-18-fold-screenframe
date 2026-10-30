package com.veltrix.fold.frame

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import kotlin.concurrent.thread

/**
 * Wakes on MediaStore changes (no foreground service, no permanent
 * notification) and offers a "加壳" prompt for each new screenshot.
 * Content-trigger jobs fire once, so each run re-arms the next.
 */
class ScreenshotJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "screenshot-watch") {
            try {
                promptNewScreenshots(this)
            } finally {
                jobFinished(params, false)
                if (Settings(this).watchScreenshots) schedule(this)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val JOB_ID = 1801

        fun schedule(context: Context) {
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, ScreenshotJobService::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    ),
                )
                .setTriggerContentUpdateDelay(300)
                .setTriggerContentMaxDelay(1500)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(info)
        }

        fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)

        /** Start watching from "now": existing screenshots are never prompted. */
        fun enable(context: Context) {
            val settings = Settings(context)
            settings.watchScreenshots = true
            settings.lastPromptedId = Framer.latestScreenshot(context)?.first ?: 0
            schedule(context)
        }

        fun disable(context: Context) {
            Settings(context).watchScreenshots = false
            cancel(context)
        }

        fun promptNewScreenshots(context: Context) {
            if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) return
            val settings = Settings(context)
            val fresh = Framer.screenshotsAfter(context, settings.lastPromptedId, limit = 3)
            if (fresh.isEmpty()) return
            settings.lastPromptedId = fresh.first().first
            for ((_, uri) in fresh.reversed()) Notifications.showPrompt(context, uri)
        }
    }
}
