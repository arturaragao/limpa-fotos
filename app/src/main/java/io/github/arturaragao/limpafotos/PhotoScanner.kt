package io.github.arturaragao.limpafotos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.AtomicFile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.math.roundToInt

object MediaAccess {
    // Explicit partial access avoids Android 14's temporary permission compatibility mode.
    fun mode(context: Context): Int = when {
        Build.VERSION.SDK_INT < 33 -> if (granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) 2 else 0
        granted(context, Manifest.permission.READ_MEDIA_IMAGES) -> 2
        Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> 1
        else -> 0
    }
    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

fun photoUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

/** done/total = galeria inteira; batchDone/batchSize = só o lote desta rodada. */
data class ScanProgress(val done: Int, val total: Int, val batchDone: Int = 0, val batchSize: Int = 0)
data class ScanResult(val photos: List<Photo>, val unreadable: Int, val cacheWarning: Boolean,
    val progress: ScanProgress)

class PhotoScanner(private val context: Context) {
    /** Images only, including GIF/WebP. No bytes are read by the monthly worker. */
    fun metadata(): List<PhotoMeta> {
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.IS_FAVORITE)
        val result = mutableListOf<PhotoMeta>()
        val cursor = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns,
            "${MediaStore.Images.Media.IS_TRASHED} = 0 AND ${MediaStore.Images.Media.IS_PENDING} = 0",
            null, "${MediaStore.Images.Media.DATE_TAKEN} DESC") ?: error("A galeria não respondeu. Tente novamente.")
        cursor.use { c ->
            while (c.moveToNext()) {
                val taken = c.getLong(8)
                result.add(PhotoMeta(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(),
                    c.getString(3).orEmpty(), c.getString(4).orEmpty(), c.getInt(5), c.getInt(6),
                    c.getLong(7).coerceAtLeast(0), if (taken > 0) taken else 0L,
                    c.getInt(10) == 1, c.getLong(9) * 1000))
            }
        }
        return result
    }

    suspend fun scan(limit: Int = 200, progress: (ScanProgress) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        val all = metadata()
        val cache = AnalysisCache(context)
        val stored = cache.read()
        val validKeys = all.map(PhotoRules::analysisKey).toHashSet()
        val completed = stored.filterKeys { it in validKeys }.toMutableMap()
        // Partial access hides media without deleting it: retain its cached analyses.
        val hidden = if (MediaAccess.mode(context) == 1) stored.filterKeys { it !in validKeys } else emptyMap()
        val batch = PhotoRules.nextUnanalyzed(all, completed.keys, limit)
        var unreadable = 0
        var cacheWarning = false
        var labeler: ImageLabeler? = null
        var batchDone = 0
        progress(ScanProgress(completed.size, all.size, 0, batch.size))
        try {
            for (meta in batch) {
                currentCoroutineContext().ensureActive()
                try {
                    val activeLabeler = labeler ?: ImageLabeling.getClient(
                        ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build()).also { labeler = it }
                    completed[PhotoRules.analysisKey(meta)] = analyze(meta, activeLabeler)
                    // Each completed photo survives an error or interruption in the next one.
                    cacheWarning = !cache.write(hidden + completed) || cacheWarning
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Failed entries remain eligible for the next batch.
                    unreadable++
                }
                batchDone++
                progress(ScanProgress(completed.size, all.size, batchDone, batch.size))
            }
            currentCoroutineContext().ensureActive()
            val photos = all.mapNotNull { meta ->
                completed[PhotoRules.analysisKey(meta)]?.let { Photo(meta, it, emptySet()) }
            }
            ScanResult(PhotoRules.classify(photos), unreadable, cacheWarning, ScanProgress(completed.size, all.size))
        } finally {
            try { labeler?.close() } finally {
                cacheWarning = !cache.write(hidden + completed) || cacheWarning
            }
        }.let { it.copy(cacheWarning = cacheWarning) }
    }

    private suspend fun analyze(meta: PhotoMeta, labeler: ImageLabeler): Analysis {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, photoUri(meta.id))) { decoder, info, _ ->
            val scale = 256.0 / max(info.size.width, info.size.height).coerceAtLeast(1)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize((info.size.width * scale.coerceAtMost(1.0)).roundToInt().coerceAtLeast(1),
                (info.size.height * scale.coerceAtMost(1.0)).roundToInt().coerceAtLeast(1))
        }
        try {
            val variance = PhotoRules.laplacianVariance(gray(bitmap), bitmap.width, bitmap.height)
            val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
            val pixels = gray(small)
            if (small !== bitmap) small.recycle()
            var hash = 0L
            for (y in 0..7) for (x in 0..7) {
                if (pixels[y * 9 + x] > pixels[y * 9 + x + 1]) hash = hash or (1L shl (y * 8 + x))
            }
            // suspendCoroutine intentionally keeps the bitmap alive until the native task completes.
            val labels = suspendCoroutine<List<Pair<String, Float>>> { continuation ->
                labeler.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { found -> continuation.resume(found.map { it.text.lowercase() to it.confidence }) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
            val food = labels.any { it.first == "food" && it.second >= 0.7f }
            // Limiar baixo para gato: melhor deixar uma foto ruim do Koda do que sugerir apagar uma boa.
            val cat = labels.any { it.first == "cat" && it.second >= 0.5f }
            val plated = labels.any { it.first in PhotoRules.PLATED_LABELS && it.second >= 0.5f }
            return Analysis(hash, variance, food, cat, plated)
        } finally {
            bitmap.recycle()
        }
    }

    private fun gray(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (((p ushr 16) and 255) * 299 + ((p ushr 8) and 255) * 587 + (p and 255) * 114) / 1000
        }
        return pixels
    }
}

/** Versioned, atomic JSON in filesDir. Only id/date, hash, score and boolean; never image data. */
private class AnalysisCache(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "analysis-v2.json"))
    fun read(): Map<String, Analysis> = try {
        val json = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        if (json.optInt("version") != 2) emptyMap() else buildMap {
            val entries = json.getJSONArray("entries")
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                put(e.getString("key"), Analysis(e.getString("hash").toLong(),
                    if (e.isNull("sharpness")) Double.NaN else e.getDouble("sharpness"), e.getBoolean("food"),
                    e.optBoolean("cat"), e.optBoolean("plated")))
            }
        }
    } catch (_: Exception) { emptyMap() }

    fun write(entries: Map<String, Analysis>): Boolean {
        var stream: java.io.FileOutputStream? = null
        return try {
            val list = JSONArray()
            entries.forEach { (key, a) -> list.put(JSONObject().put("key", key).put("hash", a.hash.toString())
                .put("sharpness", if (a.sharpness.isFinite()) a.sharpness else JSONObject.NULL).put("food", a.food).put("cat", a.cat).put("plated", a.plated)) }
            val bytes = JSONObject().put("version", 2).put("entries", list).toString().toByteArray(Charsets.UTF_8)
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            file.failWrite(stream)
            false
        }
    }
}
