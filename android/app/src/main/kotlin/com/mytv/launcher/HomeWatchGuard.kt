// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.mytv.launcher.data.SystemAccess

/**
 * Every 15 minutes (Android's shortest period): puts the Home-button helper back when the TV stopped MyTV and the
 * helper with it, so it never stays away until the next boot or the next time MyTV is opened.
 * Only scheduled when MyTV may switch the helper on itself (the extra right); a helper the owner switched off stays off.
 */
class HomeWatchGuard : JobService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        // A process the job itself just started: give Android the time to bind the helper by itself first.
        val upMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        main.postDelayed({
            runCatching { SystemAccess.repairHomeWatch(applicationContext) }.onFailure { Log.w("MyTV", "guard: $it") }
            jobFinished(params, false)
        }, (31_000L - upMs).coerceAtLeast(0L))
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { main.removeCallbacksAndMessages(null); return true }

    companion object {
        private const val ID = 4711

        /** Schedules the guard (once; it survives a reboot) when the right is there, removes it when not. */
        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            runCatching {
                if (!SystemAccess.canWriteSecure(c)) { js.cancel(ID); return }
                if (js.getPendingJob(ID) != null) return
                js.schedule(JobInfo.Builder(ID, ComponentName(c, HomeWatchGuard::class.java))
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build())
            }.onFailure { Log.w("MyTV", "guard schedule: $it") }
        }
    }
}
