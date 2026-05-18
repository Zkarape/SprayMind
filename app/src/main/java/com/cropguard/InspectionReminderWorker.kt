package com.cropguard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

// Fires at the WorkManager-scheduled time and posts the notification.
// WorkManager survives process death and device reboots — the reminder
// will fire even if the farmer force-stopped the app.
class InspectionReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val title   = inputData.getString(KEY_TITLE)   ?: return Result.failure()
        val body    = inputData.getString(KEY_BODY)    ?: return Result.failure()
        val notifId = inputData.getInt(KEY_NOTIF_ID, id.hashCode())
        NotificationHelper.post(applicationContext, title, body, notifId)
        return Result.success()
    }

    companion object {
        const val KEY_TITLE    = "title"
        const val KEY_BODY     = "body"
        const val KEY_NOTIF_ID = "notifId"
    }
}
