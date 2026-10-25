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
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
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

/** done/total = galeria acessível sem mantidas; batchDone/batchSize = lote desta rodada. */
data class ScanProgress(val done: Int, val total: Int, val batchDone: Int = 0, val batchSize: Int = 0)
data class ScanResult(val photos: List<Photo>, val unreadable: Int, val cacheWarning: Boolean,
    val progress: ScanProgress, val report: String = "")

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
        val keptIds = KeptPhotoStore(context).read()
        val eligible = all.filter { it.id !in keptIds }
        val cache = AnalysisCache(context)
        val (version, raw) = cache.read()
        val stored = if (version == 5) raw else emptyMap()
        val reanalyzing = version in 3..4 || context.getSharedPreferences("analysis_settings", Context.MODE_PRIVATE)
            .getBoolean("learning_reanalysis", false)
        if (reanalyzing) context.getSharedPreferences("analysis_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("learning_reanalysis", true).apply()
        val validKeys = all.map(PhotoRules::analysisKey).toHashSet()
        val completed = stored.filterKeys { it in validKeys }.toMutableMap()
        // Partial access hides media without deleting it: retain its cached analyses.
        val hidden = if (MediaAccess.mode(context) == 1) stored.filterKeys { it !in validKeys } else emptyMap()
        val batch = PhotoRules.nextUnanalyzed(all, completed.keys, limit, keptIds)
        var unreadable = 0
        var cacheWarning = false
        var labeler: ImageLabeler? = null
        var detector: FaceDetector? = null
        var recognizer: TextRecognizer? = null
        var batchDone = 0
        fun completedCount() = eligible.count { PhotoRules.analysisKey(it) in completed }
        progress(ScanProgress(completedCount(), eligible.size, 0, batch.size))
        try {
            for (meta in batch) {
                currentCoroutineContext().ensureActive()
                try {
                    val activeLabeler = labeler ?: ImageLabeling.getClient(
                        ImageLabelerOptions.Builder().setConfidenceThreshold(0.1f).build()).also { labeler = it }
                    val activeDetector = detector ?: FaceDetection.getClient(FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setMinFaceSize(0.05f).build()).also { detector = it }
                    val activeRecognizer = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        .also { recognizer = it }
                    completed[PhotoRules.analysisKey(meta)] = analyze(meta, activeLabeler, activeDetector, activeRecognizer)
                    // Each completed photo survives an error or interruption in the next one.
                    cacheWarning = !cache.write(hidden + completed) || cacheWarning
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Failed entries remain eligible for the next batch.
                    unreadable++
                }
                batchDone++
                progress(ScanProgress(completedCount(), eligible.size, batchDone, batch.size))
            }
            currentCoroutineContext().ensureActive()
            val photos = all.mapNotNull { meta ->
                completed[PhotoRules.analysisKey(meta)]?.let { Photo(meta, it, emptySet()) }
            }
            val classified = withContext(Dispatchers.Default) {
                val engine = LearningEngine.get(context)
                PhotoRules.classify(photos, keptIds, engine.model, engine.examples().filter { it.apagar }.map { it.id }.toSet())
            }
            val notice = if (reanalyzing) " Análise refeita para aprender." else ""
            if (reanalyzing && limit > 0 && completedCount() == eligible.size)
                context.getSharedPreferences("analysis_settings", Context.MODE_PRIVATE)
                    .edit().putBoolean("learning_reanalysis", false).apply()
            ScanResult(classified, unreadable, cacheWarning,
                ScanProgress(completedCount(), eligible.size), PhotoRules.report(photos, classified, keptIds) + notice)
        } finally {
            try { try { try { labeler?.close() } finally { detector?.close() } } finally { recognizer?.close() } } finally {
                cacheWarning = !cache.write(hidden + completed) || cacheWarning
            }
        }.let { it.copy(cacheWarning = cacheWarning) }
    }

    private suspend fun analyze(meta: PhotoMeta, labeler: ImageLabeler, detector: FaceDetector,
        recognizer: TextRecognizer): Analysis {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, photoUri(meta.id))) { decoder, info, _ ->
            // Larger input protects small faces in group photos; blur retains its 256 px scale.
            val scale = 1600.0 / max(info.size.width, info.size.height).coerceAtLeast(1)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize((info.size.width * scale.coerceAtMost(1.0)).roundToInt().coerceAtLeast(1),
                (info.size.height * scale.coerceAtMost(1.0)).roundToInt().coerceAtLeast(1))
        }
        try {
            val faces = suspendCoroutine<List<com.google.mlkit.vision.face.Face>> { continuation ->
                detector.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
            val largestFace = faces.maxOfOrNull { it.boundingBox.width().toFloat() / bitmap.width } ?: 0f
            val scale = (256.0 / max(bitmap.width, bitmap.height)).coerceAtMost(1.0)
            val preview = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
            val variance = try { PhotoRules.laplacianVariance(gray(preview), preview.width, preview.height) }
                finally { if (preview !== bitmap) preview.recycle() }
            val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
            val pixels = gray(small)
            if (small !== bitmap) small.recycle()
            var hash = 0L
            for (y in 0..7) for (x in 0..7) {
                if (pixels[y * 9 + x] > pixels[y * 9 + x + 1]) hash = hash or (1L shl (y * 8 + x))
            }
            // suspendCoroutine intentionally keeps the bitmap alive until the native task completes.
            val foundLabels = suspendCoroutine<List<com.google.mlkit.vision.label.ImageLabel>> { continuation ->
                labeler.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
            val labels = foundLabels.map { it.text.lowercase(Locale.ROOT) to it.confidence }
            val text = suspendCoroutine<com.google.mlkit.vision.text.Text> { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
            // Limiar baixo para gato: melhor deixar uma foto ruim do Koda do que sugerir apagar uma boa.
            val cat = labels.any { it.first == "cat" && it.second >= 0.5f }
            val person = !PhotoRules.isScreenLike(meta) && PhotoRules.hasPersonLabel(labels)
            return Analysis(hash, variance, cat = cat, person = person,
                hasFace = PhotoRules.faceExcludes(meta, largestFace), faceCount = faces.size, largestFace = largestFace,
                labels = foundLabels.associate { it.index to it.confidence },
                textChars = text.text.count { it.isLetterOrDigit() },
                textLines = text.textBlocks.sumOf { it.lines.size }, idDocument = PhotoRules.isIdDocument(text.text),
                document = PhotoRules.hasDocumentLabel(labels), whatsappJunk = PhotoRules.hasJunkLabel(labels))
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
    // Keep the filename to detect v3/v4 upgrades; only v5 has complete learning features.
    private val file = AtomicFile(File(context.filesDir, "analysis-v3.json"))
    fun read(): Pair<Int, Map<String, Analysis>> = try {
        val json = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        val version = json.optInt("version")
        version to if (version != 5) emptyMap() else buildMap {
            val entries = json.getJSONArray("entries")
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                put(e.getString("key"), Analysis(e.getString("hash").toLong(),
                    if (e.isNull("sharpness")) Double.NaN else e.getDouble("sharpness"),
                    cat = e.getBoolean("cat"), hasFace = e.getBoolean("hasFace"), person = e.getBoolean("person"),
                    document = e.getBoolean("document"), whatsappJunk = e.getBoolean("whatsappJunk"),
                    faceCount = e.getInt("faceCount"), largestFace = e.getDouble("largestFace").toFloat(),
                    textChars = e.getInt("textChars"), textLines = e.getInt("textLines"), idDocument = e.getBoolean("idDocument"),
                    labels = e.getJSONObject("labels").let { labels -> labels.keys().asSequence().associate { index ->
                        index.toInt() to labels.getDouble(index).toFloat()
                    } }))
            }
        }
    } catch (_: Exception) { 0 to emptyMap() }

    fun write(entries: Map<String, Analysis>): Boolean {
        var stream: java.io.FileOutputStream? = null
        return try {
            val list = JSONArray()
            entries.forEach { (key, a) -> list.put(JSONObject().put("key", key).put("hash", a.hash.toString())
                .put("sharpness", if (a.sharpness.isFinite()) a.sharpness else JSONObject.NULL)
                .put("cat", a.cat).put("hasFace", a.hasFace).put("person", a.person)
                .put("document", a.document).put("whatsappJunk", a.whatsappJunk)
                .put("faceCount", a.faceCount).put("largestFace", a.largestFace.toDouble())
                .put("textChars", a.textChars).put("textLines", a.textLines).put("idDocument", a.idDocument)
                .put("labels", JSONObject().also { labels -> a.labels.forEach { (index, confidence) ->
                    labels.put(index.toString(), confidence.toDouble())
                } })) }
            val bytes = JSONObject().put("version", 5).put("entries", list).toString().toByteArray(Charsets.UTF_8)
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

/** Kept ids outlive cache invalidation and process death. A read failure must not reshow them. */
class KeptPhotoStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "kept-photos.json"))
    fun read(): Set<Long> = synchronized(lock) {
        val text = try { file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } }
            catch (error: FileNotFoundException) {
                if (file.baseFile.exists()) throw error
                return@synchronized emptySet<Long>()
            }
        val list = JSONArray(text)
        (0 until list.length()).map { list.getLong(it) }.toSet()
    }
    fun setKept(id: Long, kept: Boolean) = synchronized(lock) {
        val ids = read()
        writeJson(file, JSONArray((if (kept) ids + id else ids - id).toList()).toString())
    }
    fun clear() = synchronized(lock) { writeJson(file, "[]") }
    companion object { private val lock = Any() }
}

/** File-backed preview session avoids Binder limits for large category lists. No image data. */
data class ReviewPhoto(val id: Long, val name: String, val features: DoubleArray = DoubleArray(Learner.FEATURE_COUNT))
data class ReviewSession(val photos: List<ReviewPhoto>, val selected: Set<Long>, val index: Int, val teaching: Boolean = false)
class ReviewSessionStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "review-session.json"))
    fun write(session: ReviewSession) {
        val photos = JSONArray()
        session.photos.forEach { photos.put(JSONObject().put("id", it.id).put("name", it.name).put("features", it.features.toJson())) }
        writeJson(file, JSONObject().put("photos", photos).put("selected", JSONArray(session.selected.toList()))
            .put("index", session.index).put("teaching", session.teaching).toString())
    }
    fun read(): ReviewSession {
        val json = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        val photos = json.getJSONArray("photos")
        val selected = json.getJSONArray("selected")
        return ReviewSession((0 until photos.length()).map { photos.getJSONObject(it).let { p ->
            ReviewPhoto(p.getLong("id"), p.getString("name"), p.getJSONArray("features").features()) } },
            (0 until selected.length()).map { selected.getLong(it) }.toSet(), json.getInt("index"), json.optBoolean("teaching"))
    }
}

internal fun writeJson(file: AtomicFile, text: String) {
    var stream: java.io.FileOutputStream? = null
    try {
        stream = file.startWrite()
        stream.write(text.toByteArray(Charsets.UTF_8))
        file.finishWrite(stream)
    } catch (error: Exception) { file.failWrite(stream); throw error }
}
