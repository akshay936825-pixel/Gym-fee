package com.gym.hisab.work

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gym.hisab.HisabApp
import com.gym.hisab.MainActivity
import com.gym.hisab.R
import com.gym.hisab.core.Dates
import com.gym.hisab.core.MemberRows
import com.gym.hisab.core.MemberState
import com.gym.hisab.core.Money
import com.gym.hisab.data.SyncUi
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object Notifications {
    const val CHANNEL = "reminders"

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, id: Int, title: String, text: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(id, n)
    }
}

object Scheduler {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Upload pending changes as soon as there is internet. Safe to call after every local change. */
    fun kickSync(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync-now", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun schedulePeriodic(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(
            "sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(online).build(),
        )
        // Daily reminder around 9:00 India time.
        val now = ZonedDateTime.now(Dates.IST)
        var next = now.with(LocalTime.of(9, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        wm.enqueueUniquePeriodicWork(
            "daily-reminder", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES).build(),
        )
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as HisabApp).container.repo
        val ok = try { repo.syncNow() } catch (e: Exception) { false }
        return when {
            ok -> Result.success()
            repo.syncState.value is SyncUi.Offline -> Result.retry()
            else -> Result.success() // permission/validation problems are shown in the app, not retried forever
        }
    }
}

/** Works fully offline: reads the phone's own database. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as HisabApp).container
        if (c.prefs.gymId.value == null) return Result.success()
        val repo = c.repo
        val rows = MemberRows.build(repo.members.first(), repo.memberships.first(), repo.payments.first(), Dates.today())
        val expiring = rows.count { it.state == MemberState.EXPIRING }
        val owing = rows.filter { it.balancePaise > 0 }
        if (expiring == 0 && owing.isEmpty()) return Result.success()
        val showMoney = c.prefs.role.value != "staff"
        val parts = ArrayList<String>()
        if (expiring > 0) parts += applicationContext.getString(R.string.notif_expiring, expiring)
        if (owing.isNotEmpty()) {
            parts += if (showMoney) applicationContext.getString(R.string.notif_pending_amt, owing.size, Money.format(owing.sumOf { it.balancePaise }))
            else applicationContext.getString(R.string.notif_pending, owing.size)
        }
        Notifications.show(applicationContext, 1001, applicationContext.getString(R.string.notif_title), parts.joinToString(" • "))
        return Result.success()
    }
}
