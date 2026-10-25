package io.github.arturaragao.limpafotos

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ScanWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result = try {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Análise de fotos", NotificationManager.IMPORTANCE_LOW))
        try {
            val notification = notification("Analisando 0 de 0", ongoing = true)
            setForeground(if (Build.VERSION.SDK_INT >= 29)
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(NOTIFICATION_ID, notification))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // WorkManager can wrap the platform exception in an ExecutionException.
            if (Build.VERSION.SDK_INT < 31 || generateSequence<Throwable>(error) { it.cause }
                    .none { it is ForegroundServiceStartNotAllowedException }) throw error
        }
        val result = scanMutex.withLock {
            coroutineScope {
                val updates = Channel<ScanProgress>(Channel.CONFLATED)
                val reporter = launch {
                    for (p in updates) {
                        setProgress(workDataOf("done" to p.done, "total" to p.total,
                            "batchDone" to p.batchDone, "batchSize" to p.batchSize))
                        notify(NOTIFICATION_ID, notification("Analisando ${p.done} de ${p.total}", ongoing = true))
                    }
                }
                try {
                    PhotoScanner(applicationContext).scan(inputData.getInt("limit", 200).coerceAtLeast(1)) {
                        updates.trySend(it)
                    }
                } finally {
                    updates.close()
                    reporter.join()
                }
            }
        }
        notify(RESULT_NOTIFICATION_ID, notification("Análise concluída", ongoing = false))
        Result.success(workDataOf("unreadable" to result.unreadable,
            "cacheWarning" to result.cacheWarning, "report" to result.report))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        notify(RESULT_NOTIFICATION_ID, notification("Falha na análise", ongoing = false))
        Result.failure(workDataOf("error" to "Não foi possível analisar a galeria. Tente novamente."))
    } finally {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun notification(text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(applicationContext, NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_logo).setContentTitle("Limpar Fotos").setContentText(text)
            .setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing).apply { if (!ongoing) setTimeoutAfter(5_000) }.build()
    }

    @SuppressLint("MissingPermission") // Denial hides notifications but must not prevent analysis.
    private fun notify(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        try { manager.notify(id, notification) } catch (_: SecurityException) { }
    }

    companion object {
        const val NAME = "scan"
        private const val CHANNEL = "scan"
        private const val NOTIFICATION_ID = 903
        private const val RESULT_NOTIFICATION_ID = 904
        // Cache-only scans also write the cache; cancellation cleanup must finish first.
        internal val scanMutex = Mutex()
    }
}
