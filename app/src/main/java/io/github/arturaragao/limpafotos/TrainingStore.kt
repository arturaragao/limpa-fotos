package io.github.arturaragao.limpafotos

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal fun DoubleArray.toJson() = JSONArray(toList())
internal fun JSONArray.features(): DoubleArray {
    require(length() == Learner.FEATURE_COUNT)
    return DoubleArray(length()) { getDouble(it).also { value -> require(value.isFinite()) } }
}
private fun DoubleArray.sparseJson() = JSONObject().also { json ->
    for (i in indices) if (this[i] != 0.0) json.put(i.toString(), this[i])
}
private fun JSONObject.exampleFeatures(): DoubleArray {
    val dense = optJSONArray("features")
    if (dense != null) return dense.features()
    val sparse = getJSONObject("features")
    return DoubleArray(Learner.FEATURE_COUNT).also { x -> sparse.keys().forEach { key ->
        val index = key.toInt()
        require(index in x.indices)
        x[index] = sparse.getDouble(key).also { require(it.isFinite()) }
    } }
}

class TrainingStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "training.json"))
    fun read(): List<TrainingExample> = synchronized(lock) {
        val json = try { file.openRead().bufferedReader(Charsets.UTF_8).use { JSONArray(it.readText()) } }
        catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return@synchronized emptyList()
        }
        Learner.bounded((0 until json.length()).map { i -> json.getJSONObject(i).let {
            TrainingExample(it.getLong("id"), it.exampleFeatures(), it.getBoolean("apagar"), it.getLong("quando"))
        } })
    }
    fun put(examples: List<TrainingExample>) = synchronized(lock) {
        val updated = examples.associateBy { it.id }
        val all = Learner.bounded(read().filter { it.id !in updated } + updated.values)
        val json = JSONArray()
        all.forEach { json.put(JSONObject().put("id", it.id).put("features", it.features.sparseJson())
            .put("apagar", it.apagar).put("quando", it.quando)) }
        writeJson(file, json.toString())
    }
    companion object { private val lock = Any() }
}

/** Features survive process death while Android/Google Photos asks for confirmation. */
class DeletionTrainingStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "pending-deletion-training.json"))
    fun write(examples: List<TrainingExample>) {
        val json = JSONArray()
        examples.forEach { json.put(JSONObject().put("id", it.id).put("features", it.features.sparseJson())) }
        writeJson(file, json.toString())
    }
    fun read(): List<TrainingExample> {
        val json = JSONArray(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        return (0 until json.length()).map { i -> json.getJSONObject(i).let {
            TrainingExample(it.getLong("id"), it.exampleFeatures(), true, 0)
        } }
    }
}

/** Application lifetime: leaving the preview never cancels a recorded decision's training. */
class LearningEngine private constructor(context: Context) {
    private val store = TrainingStore(context.applicationContext)
    private val modelFile = AtomicFile(File(context.filesDir, "model.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val mutable = MutableStateFlow(0L)
    val revision = mutable.asStateFlow()
    @Volatile var model: Learner = readModel()
        private set
    @Volatile var error: String? = null
        private set

    init { schedule() }
    fun examples() = store.read()

    @Synchronized fun record(examples: List<TrainingExample>) {
        store.put(examples) // Decisions are durable before debounce starts.
        schedule()
    }

    @Synchronized private fun schedule() {
        job?.cancel()
        job = scope.launch {
            delay(1000)
            try {
                val trained = Learner.train(store.read()) { ensureActive() }
                ensureActive()
                synchronized(this@LearningEngine) {
                    ensureActive()
                    writeJson(modelFile, JSONObject().put("version", 1).put("weights", trained.weights.toJson())
                        .put("positives", trained.positives).put("negatives", trained.negatives).toString())
                    model = trained
                    error = null
                    mutable.value += 1
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                error = "Exemplos salvos; não foi possível atualizar o modelo. O app tentará ao reabrir ou ensinar de novo."
                mutable.value += 1
            }
        }
    }
    suspend fun awaitTraining() { job?.join() }
    private fun readModel(): Learner = try {
        val json = JSONObject(modelFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(json.getInt("version") == 1)
        Learner(json.getJSONArray("weights").features(), json.getInt("positives"), json.getInt("negatives"))
    } catch (_: Exception) { Learner() }

    companion object {
        @Volatile private var instance: LearningEngine? = null
        fun get(context: Context): LearningEngine = instance ?: synchronized(this) {
            instance ?: LearningEngine(context.applicationContext).also { instance = it }
        }
    }
}
