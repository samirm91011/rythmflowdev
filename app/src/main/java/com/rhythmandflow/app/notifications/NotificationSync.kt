package com.rhythmandflow.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rhythmandflow.app.RhythmApplication
import com.rhythmandflow.app.data.Outcome
import java.util.concurrent.TimeUnit

private const val KEY_LAST_ID = "last_notified_id"
private const val WORK_NAME = "notification-sync"

/**
 * Runs about every 15 minutes (the shortest interval Android allows) while someone is signed in: asks the server for new
 * notifications and shows each one as a phone notification. Real push (Firebase) delivers instantly when it is set up;
 * this keeps notifications arriving even when push is not available.
 */
class NotificationSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as RhythmApplication).container
        val repo = container.repository
        if (!repo.hasToken) return Result.success()

        val prefs = container.localPrefs
        val last = prefs.getInt(KEY_LAST_ID, -1)
        return when (val r = repo.notifications(afterId = if (last >= 0) last else null, unreadOnly = true)) {
            is Outcome.Ok -> {
                val items = r.value.sortedBy { it.id }
                if (last >= 0) {
                    items.forEach { n ->
                        val channel = if (n.kind == "ADMIN_ERROR") Notifier.CH_ADMIN else Notifier.CH_GENERAL
                        Notifier.show(applicationContext, n.id, n.title, n.body, n.route, channel)
                    }
                }
                // First run on this phone: remember where we are instead of flooding with old notifications.
                prefs.putInt(KEY_LAST_ID, maxOf(last, items.maxOfOrNull { it.id } ?: 0))
                Result.success()
            }
            // No signal: try again later. A server answer (even an error) is not worth retrying hard.
            is Outcome.Fail -> if (r.code == null) Result.retry() else Result.success()
        }
    }
}

object NotificationSync {
    fun start(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<NotificationSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun stop(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }
}
