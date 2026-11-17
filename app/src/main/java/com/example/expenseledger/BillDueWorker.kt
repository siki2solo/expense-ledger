package com.example.expenseledger

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class BillDueWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val db = LedgerDbHelper(applicationContext)
        val snapshot = db.loadSnapshot()
        if (!snapshot.dueRemindersEnabled) return Result.success()

        val alerts = LedgerMath.collectDueAlerts(snapshot.expenses)
        if (alerts.items.isEmpty()) return Result.success()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bill Due Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts for overdue and upcoming Hold/Need bills"
        }
        manager.createNotificationChannel(channel)

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            104,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val top = alerts.items.first()
        val body = "${alerts.overdueCount} overdue, ${alerts.dueTodayCount} today. Top: ${top.expense.quote} (₹${LedgerMath.formatAmt(top.remaining)})"

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Expense Ledger — Bill Reminder")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(1401, notif)
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "expense_ledger_due_bills"
    }
}

object BillDueScheduler {
    private const val WORK_NAME = "expense_ledger_daily_due_bills"

    fun sync(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (enabled) {
            val req = PeriodicWorkRequestBuilder<BillDueWorker>(12, TimeUnit.HOURS).build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(WORK_NAME)
        }
    }
}
