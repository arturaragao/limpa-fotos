package io.github.arturaragao.limpafotos

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Restringe o APK aos assets HTTPS deste repositório. */
internal object UpdateSource {
    fun isTrustedApkUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val prefix = "/arturaragao/limpa-fotos/releases/download/"
        val path = uri.rawPath.orEmpty()
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            path.startsWith(prefix) && path.removePrefix(prefix).split('/').let { parts ->
                parts.size == 2 && parts.all { it.isNotBlank() && it != "." && it != ".." && '%' !in it } &&
                    parts.last().endsWith(".apk")
            }
    }.getOrDefault(false)
}

object Updater {
    private const val API = "https://api.github.com/repos/arturaragao/limpa-fotos/releases/latest"
    private const val PREFS = "update"
    private const val NOTIF_ID = 900
    const val EXTRA = "atualizar"
    internal const val STATUS_ACTION = "io.github.arturaragao.limpafotos.UPDATE_STATUS"
    private val executor = Executors.newSingleThreadExecutor()
    private val updateLock = Any()

    data class Release(val tag: String, val apkUrl: String)

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("check-update", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun latest(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) return null
            val json = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val tag = json.optString("tag_name")
            if (UpdateVersion.codeFromTag(tag) == null || json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val url = asset.optString("browser_download_url")
                if (asset.optString("name") == "limpa-fotos.apk" && UpdateSource.isTrustedApkUrl(url)) return Release(tag, url)
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Chamado no onResume; force = veio de uma notificação de atualização. */
    fun checkOnOpen(activity: Activity, force: Boolean) {
        onPackageReplaced(activity)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("confirmation", null)?.let { saved ->
            val id = prefs.getInt("sessionId", -1)
            if (id != -1 && activity.packageManager.packageInstaller.getSessionInfo(id) != null) {
                runCatching { activity.startActivity(Intent.parseUri(saved, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { reportError(activity, it.message ?: "Não consegui abrir a confirmação") }
                return
            }
            prefs.edit().remove("confirmation").apply()
        }
        val resumeInstall = prefs.getBoolean("installPending", false) && canInstall(activity)
        val now = System.currentTimeMillis()
        if (!force && !resumeInstall && now - prefs.getLong("lastCheck", 0) < TimeUnit.MINUTES.toMillis(1)) return
        prefs.edit().putLong("lastCheck", now).apply()
        val context = activity.applicationContext
        executor.execute {
            runCatching {
                synchronized(updateLock) {
                    if (hasActiveSession(context)) return@synchronized
                    val staged = prefs.getString("stagedTag", null)
                    if (resumeInstall && staged != null && UpdateVersion.isNewer(staged, BuildConfig.VERSION_CODE) && apkFile(context).isFile) {
                        install(context, staged)
                        return@synchronized
                    }
                    val release = latest() ?: return@synchronized
                    if (!UpdateVersion.isNewer(release.tag, BuildConfig.VERSION_CODE)) return@synchronized
                    if (canInstall(context)) downloadAndInstall(context, release)
                    else if (force) {
                        download(context, release)
                        prefs.edit().putBoolean("installPending", true).apply()
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            Toast.makeText(activity, "Permita instalar apps desconhecidos para o Limpa Fotos e volte.", Toast.LENGTH_LONG).show()
                            runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))) }
                                .onFailure { reportError(context, it.message ?: "Não consegui abrir a permissão de instalação") }
                        }
                    } else notifyAvailable(context, release)
                }
            }.onFailure {
                Log.w("LimpaFotos", "Falha ao atualizar", it)
                reportError(context, it.message ?: "Não consegui atualizar")
            }
        }
    }

    private fun apkFile(context: Context) = File(File(context.cacheDir, "update"), "limpa-fotos.apk")
    private fun canInstall(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    private fun download(context: Context, release: Release) {
        check(UpdateSource.isTrustedApkUrl(release.apkUrl)) { "URL do APK não permitida" }
        val file = apkFile(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "baixando.apk")
        val c = URL(release.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            check(c.url.protocol == "https") { "Download sem HTTPS" }
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.length() > 100_000) { "APK pequeno demais" }
            file.delete()
            check(tmp.renameTo(file)) { "Não consegui salvar o APK" }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("stagedTag", release.tag).apply()
        } finally {
            c.disconnect()
            tmp.delete()
        }
    }

    private fun downloadAndInstall(context: Context, release: Release) {
        download(context, release)
        if (canInstall(context)) install(context, release.tag) else notifyAvailable(context, release)
    }

    @Suppress("DEPRECATION")
    private fun install(context: Context, tag: String) {
        val file = apkFile(context)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: error("APK inválido")
        check(archive.packageName == context.packageName) { "APK não pertence ao Limpa Fotos" }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(code == UpdateVersion.codeFromTag(tag)?.toLong() && code > BuildConfig.VERSION_CODE) { "Versão do APK inválida" }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            check(prefs.edit().putInt("sessionId", id).putString("sessionTag", tag).putBoolean("installPending", false).commit()) {
                "Não consegui salvar a sessão"
            }
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val status = Intent(context, UpdateStatusReceiver::class.java).setAction(STATUS_ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            prefs.edit().remove("sessionId").remove("sessionTag").remove("confirmation").apply()
            throw e
        }
    }

    private fun hasActiveSession(context: Context): Boolean {
        onPackageReplaced(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getInt("sessionId", -1)
        if (id == -1) return false
        val session = context.packageManager.packageInstaller.getSessionInfo(id)
        if (session != null && session.isSealed) return true
        if (session != null) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        prefs.edit().remove("sessionId").remove("sessionTag").remove("confirmation").apply()
        return false
    }

    fun notifyIfNewer(context: Context) = synchronized(updateLock) {
        try {
            if (hasActiveSession(context)) return@synchronized
            val release = latest() ?: return@synchronized
            if (!UpdateVersion.isNewer(release.tag, BuildConfig.VERSION_CODE)) return@synchronized
            if (canInstall(context)) downloadAndInstall(context, release) else notifyAvailable(context, release)
        } catch (e: Exception) {
            reportError(context, e.message ?: "Não consegui atualizar")
            throw e
        }
    }

    private fun notifyAvailable(context: Context, release: Release) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("notified", null) == release.tag) return
        val open = Intent(context, MainActivity::class.java).putExtra(EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, NOTIF_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (notify(context, "Atualização do Limpa Fotos", "Versão ${release.tag.removePrefix("v")} disponível. Toque para atualizar.", pending)) {
            prefs.edit().putString("notified", release.tag).apply()
        }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, NOTIF_ID + 1, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notify(context: Context, title: String, text: String, pending: PendingIntent): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(android.app.NotificationChannel("avisos", "Atualizações", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(NOTIF_ID, Notification.Builder(context, "avisos")
            .setSmallIcon(R.drawable.ic_logo).setContentTitle(title).setContentText(text)
            .setContentIntent(pending).setAutoCancel(true).build())
        return true
    }

    private fun reportError(context: Context, reason: String) {
        Log.w("LimpaFotos", "Atualização: $reason")
        notify(context, "Falha ao atualizar Limpa Fotos", reason.take(180), openApp(context))
    }

    internal fun onPackageReplaced(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = prefs.getString("sessionTag", null) ?: return
        val code = UpdateVersion.codeFromTag(tag) ?: return
        if (BuildConfig.VERSION_CODE >= code) reportSuccess(context, tag)
    }

    private fun reportSuccess(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("sessionId").remove("sessionTag").remove("stagedTag").remove("notified").remove("confirmation")
            .putBoolean("installPending", false).commit()
        apkFile(context).delete()
        notify(context, "Limpa Fotos atualizado para ${tag.removePrefix("v")}", "Toque para abrir o app.", openApp(context))
    }

    @Suppress("DEPRECATION")
    internal fun onInstallStatus(context: Context, intent: Intent) {
        if (intent.action != STATUS_ACTION) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getInt("sessionId", -1)
        if (id == -1 || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                    prefs.edit().remove("sessionId").remove("sessionTag").remove("notified").remove("confirmation").apply()
                    reportError(context, "O instalador não enviou a confirmação")
                    return
                }
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                prefs.edit().putString("confirmation", confirmation.toUri(Intent.URI_INTENT_SCHEME)).apply()
                val pending = PendingIntent.getActivity(context, id, confirmation, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                notify(context, "Atualização do Limpa Fotos", "Toque para confirmar a instalação.", pending)
                runCatching { context.startActivity(confirmation) }.onFailure {
                    Log.w("LimpaFotos", "Confirme a instalação pela notificação", it)
                    notify(context, "Confirme a atualização do Limpa Fotos", (it.message ?: "Toque para abrir o instalador.").take(180), pending)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> reportSuccess(context, prefs.getString("sessionTag", null) ?: BuildConfig.VERSION_NAME)
            else -> {
                prefs.edit().remove("sessionId").remove("sessionTag").remove("notified").remove("confirmation").apply()
                reportError(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Instalação recusada pelo Android")
            }
        }
    }
}

class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.onPackageReplaced(context)
        else Updater.onInstallStatus(context, intent)
    }
}

class UpdateWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        Updater.notifyIfNewer(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
