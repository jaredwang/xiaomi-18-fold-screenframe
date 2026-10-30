package com.fold18.frame

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Deletes the shared PNGs a minute after a share. A job rather than a
 * Handler timer: once the user is in the target app ours sits in the
 * background, where the cached-app freezer would stall an in-process timer.
 */
class ShareCacheJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Framer.clearShareCache(this)
        return false
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 1802

        /** (Re)starts the countdown; a newer share pushes the cleanup back. */
        fun schedule(context: Context, delayMs: Long) {
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, ShareCacheJobService::class.java))
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs * 5)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(info)
        }
    }
}
