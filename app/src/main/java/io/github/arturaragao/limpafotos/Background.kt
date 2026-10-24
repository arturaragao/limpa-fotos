package io.github.arturaragao.limpafotos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class LimpaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Updater.schedule(this)
        val reminder = PeriodicWorkRequestBuilder<MonthlyWorker>(30, TimeUnit.DAYS)
            .setInitialDelay(30, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("monthly-cleanup", ExistingPeriodicWorkPolicy.KEEP, reminder)
    }
}

object Notifications {
    fun manager(context: Context): NotificationManager? {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return null
        manager.createNotificationChannel(NotificationChannel("avisos", "Atualizações", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("limpeza", "Lembrete mensal", NotificationManager.IMPORTANCE_DEFAULT))
        return manager
    }

    fun open(context: Context, requestCode: Int, update: Boolean = false): PendingIntent = PendingIntent.getActivity(
        context, requestCode, Intent(context, MainActivity::class.java).putExtra(Updater.EXTRA, update)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class MonthlyWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    @SuppressLint("MissingPermission") // Notifications.manager checks runtime permission and system settings.
    override fun doWork(): Result {
        if (MediaAccess.mode(applicationContext) == 0) return Result.success()
        val manager = Notifications.manager(applicationContext) ?: return Result.success()
        return try {
            val candidates = PhotoScanner(applicationContext).metadata().filter { photo ->
                !photo.favorite && PhotoRules.metadataCategories(photo).any { it == Category.PRINTS || it == Category.STICKERS }
            }
            if (candidates.isNotEmpty()) {
                val text = "Você tem ${candidates.size} prints e figurinhas (${PhotoRules.formatMb(candidates.sumOf { it.bytes })}). Limpar?"
                manager.notify(901, Notification.Builder(applicationContext, "limpeza")
                    .setSmallIcon(R.drawable.ic_logo).setContentTitle("Limpa Fotos").setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(Notifications.open(applicationContext, 901)).setAutoCancel(true).build())
            }
            Result.success()
        } catch (_: SecurityException) {
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class CleanWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_clean)
            views.setOnClickPendingIntent(R.id.widget_root, Notifications.open(context, id + 10_000))
            manager.updateAppWidget(id, views)
        }
    }
}
