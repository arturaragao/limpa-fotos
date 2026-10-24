package io.github.arturaragao.limpafotos

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
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
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Adapted from banco-cbr-android: only updater code may access the network. */
object Updater {
    private const val API = "https://api.github.com/repos/arturaragao/limpa-fotos/releases/latest"
    private const val PREFS = "update"
    private const val NOTIF_ID = 900
    const val EXTRA = "atualizar"
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    data class Release(val tag: String, val apkUrl: String)

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("check-update", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun latest(): Release? {
        val c = connection(API)
        try {
            if (c.responseCode != 200) return null
            val json = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val tag = json.optString("tag_name")
            if (UpdateVersion.codeFromTag(tag) == null || json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val url = asset.optString("browser_download_url")
                if (asset.optString("name") == "limpa-fotos.apk" &&
                    url.startsWith("https://github.com/arturaragao/limpa-fotos/releases/download/")) return Release(tag, url)
            }
            return null
        } finally { c.disconnect() }
    }

    /** On resume, at most once per minute. A notification tap can force a check. */
    fun checkOnOpen(activity: Activity, force: Boolean) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("installPending", false) && apkFile(activity).isFile && canInstall(activity)) {
            prefs.edit().putBoolean("installPending", false).apply()
            install(activity)
            return
        }
        if (busy) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("lastCheck", 0) in 0 until TimeUnit.MINUTES.toMillis(1)) return
        prefs.edit().putLong("lastCheck", now).apply()
        busy = true
        executor.execute {
            val release = runCatching { latest() }.onFailure { Log.w("LimpaFotos", "Falha ao procurar atualização", it) }.getOrNull()
            activity.runOnUiThread {
                if (release == null || !UpdateVersion.isNewer(release.tag, BuildConfig.VERSION_CODE) || activity.isFinishing || activity.isDestroyed) {
                    busy = false
                    return@runOnUiThread
                }
                AlertDialog.Builder(activity).setTitle("Atualização disponível")
                    .setMessage("Limpa Fotos ${release.tag.removePrefix("v")} (você tem ${BuildConfig.VERSION_NAME}). Atualizar agora?")
                    .setPositiveButton("Atualizar") { _, _ -> download(activity, release) }
                    .setNegativeButton("Depois", null).setOnDismissListener { busy = false }.show()
            }
        }
    }

    private fun connection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 60_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "LimpaFotos/${BuildConfig.VERSION_NAME}")
    }
    private fun apkFile(context: Context) = File(File(context.cacheDir, "update"), "limpa-fotos.apk")
    private fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    private fun download(activity: Activity, release: Release) {
        Toast.makeText(activity, "Baixando a atualização…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val ok = runCatching {
                val file = apkFile(activity)
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "baixando.apk")
                val c = connection(release.apkUrl)
                try {
                    check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
                    c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    check(tmp.length() > 100_000) { "APK incompleto" }
                    check(tmp.renameTo(file)) { "Não foi possível preparar o APK" }
                } finally {
                    c.disconnect()
                    tmp.delete()
                }
            }.onFailure { Log.w("LimpaFotos", "Falha ao baixar atualização", it) }.isSuccess
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (!ok) {
                    Toast.makeText(activity, "Não consegui baixar a atualização. Tente mais tarde.", Toast.LENGTH_LONG).show()
                } else if (!canInstall(activity)) {
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("installPending", true).apply()
                    Toast.makeText(activity, "Permita instalar apps desconhecidos para o Limpa Fotos e volte.", Toast.LENGTH_LONG).show()
                    runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }
                        .onFailure { Toast.makeText(activity, "Abra as configurações de instalação do Android.", Toast.LENGTH_LONG).show() }
                } else install(activity)
            }
        }
    }

    private fun install(activity: Activity) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apkFile(activity))
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { Toast.makeText(activity, "Não consegui abrir o instalador.", Toast.LENGTH_LONG).show() }
    }

    @SuppressLint("MissingPermission") // Notifications.manager checks runtime permission and system settings.
    fun notifyIfNewer(context: Context) {
        val release = latest() ?: return
        if (!UpdateVersion.isNewer(release.tag, BuildConfig.VERSION_CODE)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("notified", null) == release.tag) return
        val manager = Notifications.manager(context) ?: return
        manager.notify(NOTIF_ID, Notification.Builder(context, "avisos").setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("Atualização do Limpa Fotos")
            .setContentText("Versão ${release.tag.removePrefix("v")} disponível. Toque para atualizar.")
            .setContentIntent(Notifications.open(context, NOTIF_ID, update = true)).setAutoCancel(true).build())
        prefs.edit().putString("notified", release.tag).apply()
    }
}

class UpdateWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        Updater.notifyIfNewer(applicationContext)
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
