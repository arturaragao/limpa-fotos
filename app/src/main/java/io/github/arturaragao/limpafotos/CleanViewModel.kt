package io.github.arturaragao.limpafotos

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

data class CleanState(
    val photos: List<Photo> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val category: Category? = null,
    val scanning: Boolean = false,
    val progress: ScanProgress? = null,
    val scanned: Boolean = false,
    val batchLimit: Int = 200,
    val googleFlow: GooglePhotosFlow? = null,
    val teaching: Boolean = false,
    val learning: Boolean = false,
    val exampleCount: Int = 0,
    val taughtBatch: Int = 0,
    val message: String = "Permita acesso às fotos para começar."
)

class CleanViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("analysis_settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(CleanState(batchLimit = preferences.getInt("batch_limit", 200).coerceAtLeast(1),
        googleFlow = savedState.get<Bundle>("googleFlow")?.let { GooglePhotosFlow(
            it.getLongArray("ids")?.toList().orEmpty(), it.getInt("index"), it.getInt("deleted"),
            it.getInt("skipped"), it.getBoolean("awaiting"), it.getBoolean("decision")) }))
    val state = mutable.asStateFlow()
    private var scanJob: Job? = null
    private var enqueueJob: Job? = null
    private var deletionSummary: String? = null
    private val workManager = WorkManager.getInstance(application)
    private var workObserved = false
    private var workerActive = false
    private var pendingCacheRefresh = false
    private var workerOutput = Data.EMPTY
    private var workerFailure: String? = null
    private val learning = LearningEngine.get(application)
    private var analyzedPhotos: List<Photo> = emptyList()

    init {
        viewModelScope.launch {
            learning.revision.collect {
                try {
                    val count = withContext(Dispatchers.IO) { learning.examples().size }
                    mutable.update { it.copy(exampleCount = count) }
                    reevaluate()
                    learning.error?.let(::message)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message("Não foi possível ler o aprendizado. Tente novamente.") }
            }
        }
        viewModelScope.launch {
            var handledTerminal: java.util.UUID? = null
            workManager.getWorkInfosForUniqueWorkFlow(ScanWorker.NAME).collect { infos ->
                val work = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
                workObserved = true
                workerActive = work != null && !work.state.isFinished
                if (workerActive && work != null) {
                    scanJob?.cancelAndJoin()
                    val p = work.progress
                    mutable.update { it.copy(scanning = true,
                        progress = if (p.keyValueMap.containsKey("done")) ScanProgress(
                            p.getInt("done", 0), p.getInt("total", 0),
                            p.getInt("batchDone", 0), p.getInt("batchSize", 0)) else null,
                        message = "Lendo a galeria…") }
                } else if (work != null && work.state.isFinished && handledTerminal != work.id) {
                    handledTerminal = work.id
                    workerOutput = work.outputData
                    workerFailure = when (work.state) {
                        WorkInfo.State.FAILED -> workerOutput.getString("error") ?: "Falha na análise."
                        WorkInfo.State.CANCELLED -> "Análise interrompida; o que já foi analisado ficou salvo."
                        else -> null
                    }
                    scanJob?.cancelAndJoin()
                    mutable.update { it.copy(scanning = false) }
                    if (MediaAccess.mode(application) > 0) scan(analyzeMore = false)
                } else if (!workerActive && pendingCacheRefresh) {
                    scan(analyzeMore = false)
                }
                pendingCacheRefresh = false
            }
        }
    }

    fun setBatchLimit(limit: Int) {
        require(limit > 0)
        preferences.edit().putInt("batch_limit", limit).apply()
        mutable.update { it.copy(batchLimit = limit) }
    }

    fun scan(analyzeMore: Boolean = true) {
        if (mutable.value.googleFlow != null || mutable.value.teaching || mutable.value.learning || workerActive) return
        if (analyzeMore) {
            workerOutput = Data.EMPTY
            workerFailure = null
            val request = OneTimeWorkRequestBuilder<ScanWorker>()
                .setInputData(workDataOf("limit" to mutable.value.batchLimit)).build()
            workerActive = true
            mutable.update { it.copy(scanning = true, progress = null, message = "Lendo a galeria…") }
            enqueueJob = viewModelScope.launch {
                scanJob?.cancelAndJoin()
                mutable.update { it.copy(scanning = true, progress = null, message = "Lendo a galeria…") }
                try {
                    workManager.enqueueUniqueWork(ScanWorker.NAME, ExistingWorkPolicy.KEEP, request).await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    workerActive = false
                    mutable.update { it.copy(scanning = false, message = "Não foi possível iniciar a análise. Tente novamente.") }
                }
            }
            return
        }
        if (scanJob?.isActive == true) return
        // onResume can precede the first WorkManager emission; avoid writing beside a running worker.
        if (!workObserved) { pendingCacheRefresh = true; return }
        scanJob = viewModelScope.launch {
            mutable.update { it.copy(scanning = true, progress = null, message = "Lendo a galeria…") }
            try {
                val result = ScanWorker.scanMutex.withLock {
                    PhotoScanner(getApplication()).scan(0) { progress -> mutable.update { it.copy(progress = progress) } }
                }.let { it.copy(unreadable = maxOf(it.unreadable, workerOutput.getInt("unreadable", 0)),
                    cacheWarning = it.cacheWarning || workerOutput.getBoolean("cacheWarning", false),
                    report = it.report.ifBlank { workerOutput.getString("report").orEmpty() }) }
                analyzedPhotos = result.photos
                val suggestions = PhotoRules.filterKept(result.photos, KeptPhotoStore(getApplication()).read())
                    .filter(PhotoRules::initiallySelected)
                mutable.update { previous ->
                    val known = previous.photos.map { it.meta.id }.toHashSet()
                    previous.copy(photos = suggestions,
                        selected = suggestions.filter { photo ->
                            if (photo.meta.id in known) photo.meta.id in previous.selected && !photo.keeper && !photo.meta.favorite
                            else PhotoRules.initiallySelected(photo)
                        }.map { it.meta.id }.toSet(),
                        scanning = false, scanned = true, progress = result.progress,
                        message = buildString {
                            if (workerFailure != null) append(workerFailure)
                            else if (result.progress.done == result.progress.total) append("Análise concluída.")
                            else append("Continue com o próximo lote quando quiser.")
                            if (result.unreadable > 0) append(" ${result.unreadable} falharam; tente analisar mais novamente.")
                            if (result.cacheWarning) append(" Não foi possível salvar parte do cache.")
                            if (result.report.isNotBlank()) append(" ${result.report}")
                            deletionSummary?.let { append(" $it") }
                        })
                }
                deletionSummary = null
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(scanning = false) }
                throw cancelled
            } catch (_: SecurityException) {
                clearAccess()
            } catch (error: Exception) {
                mutable.update { it.copy(scanning = false, message = error.message ?: "Não foi possível varrer a galeria.") }
            }
        }
    }

    /** Para no meio do lote: o que já foi analisado fica no cache e aparece na tela. */
    fun stop() {
        viewModelScope.launch {
            enqueueJob?.cancelAndJoin()
            workManager.cancelUniqueWork(ScanWorker.NAME).await()
            scanJob?.cancelAndJoin()
            workerActive = false
            mutable.update { it.copy(scanning = false) }
            scan(analyzeMore = false)
        }
    }

    fun clearAccess() {
        scanJob?.cancel()
        analyzedPhotos = emptyList()
        mutable.value = CleanState(batchLimit = mutable.value.batchLimit, googleFlow = mutable.value.googleFlow,
            exampleCount = mutable.value.exampleCount,
            message = "Abra Configurações para permitir o acesso às fotos.")
    }

    fun category(value: Category?) { mutable.update { it.copy(category = value) } }
    fun toggle(id: Long) {
        val current = mutable.value
        if (current.scanning || current.learning || current.googleFlow != null || current.photos.none { it.meta.id == id }) return
        val keep = id in current.selected
        try {
            val photo = current.photos.first { it.meta.id == id }
            record(photo, !keep)
            KeptPhotoStore(getApplication()).setKept(id, keep)
            mutable.update { it.copy(photos = if (keep && !current.teaching) it.photos.filter { p -> p.meta.id != id } else it.photos,
                selected = if (keep) it.selected - id else it.selected + id) }
        } catch (_: Exception) { message("Não foi possível salvar sua decisão. Tente novamente.") }
    }

    fun refreshReviewSelection() {
        try {
            if (mutable.value.teaching) {
                val review = ReviewSessionStore(getApplication()).read()
                if (review.teaching) mutable.update { it.copy(selected = review.selected) }
                return
            }
            val kept = KeptPhotoStore(getApplication()).read()
            mutable.update { current ->
                val photos = PhotoRules.filterKept(current.photos, kept)
                current.copy(photos = photos, selected = current.selected - kept)
            }
            scan(analyzeMore = false)
        } catch (_: Exception) {
            mutable.update { it.copy(photos = emptyList(), selected = emptySet(),
                message = "Não foi possível ler as fotos mantidas. Tente novamente.") }
        }
    }

    fun showKeptAgain() {
        if (mutable.value.scanning || mutable.value.teaching || mutable.value.googleFlow != null) return
        try { KeptPhotoStore(getApplication()).clear(); scan(analyzeMore = false) }
        catch (_: Exception) { message("Não foi possível limpar a lista de mantidas.") }
    }

    private fun googleFlow(value: GooglePhotosFlow?) {
        savedState["googleFlow"] = value?.let { flow -> Bundle().apply {
            putLongArray("ids", flow.ids.toLongArray()); putInt("index", flow.index)
            putInt("deleted", flow.deleted); putInt("skipped", flow.skipped)
            putBoolean("awaiting", flow.awaitingReturn); putBoolean("decision", flow.needsDecision)
        } }
        mutable.update { it.copy(googleFlow = value) }
    }
    fun beginGooglePhotos() {
        val current = mutable.value
        if (current.scanning || current.teaching || current.googleFlow != null) return
        val ids = current.photos.filter { it.meta.id in current.selected }.map { it.meta.id }
        if (ids.isNotEmpty()) {
            try {
                stageDeletion(ids)
                googleFlow(PhotoRules.startGooglePhotos(ids))
            } catch (_: Exception) { message("Não foi possível preparar a revisão. Tente novamente.") }
        }
    }
    fun googleOpened() { mutable.value.googleFlow?.let { googleFlow(PhotoRules.openGooglePhoto(it)) } }
    fun googleFailed() { mutable.value.googleFlow?.let {
        googleFlow(it.copy(awaitingReturn = false, needsDecision = true))
        message("Não foi possível abrir esta foto no Google Fotos. Pule ou tente de novo.")
    } }
    fun skipGooglePhoto() { mutable.value.googleFlow?.let { googleFlow(PhotoRules.skipGooglePhoto(it)) } }
    fun retryGooglePhoto() { mutable.value.googleFlow?.let { googleFlow(PhotoRules.retryGooglePhoto(it)) } }
    fun finishGooglePhotos() {
        val flow = mutable.value.googleFlow ?: return
        googleFlow(null)
        deletionSummary = "Google Fotos: ${flow.deleted} apagadas confirmadas; ${flow.skipped} puladas."
        scan(analyzeMore = false)
        message(deletionSummary!!)
    }
    fun cancelGoogleForLocal(): List<Long> {
        val remaining = mutable.value.googleFlow?.let { it.ids.drop(it.index) }.orEmpty()
        googleFlow(null)
        return remaining
    }

    suspend fun checkGoogleReturn() {
        val flow = mutable.value.googleFlow ?: return
        val id = flow.currentId ?: return
        if (!flow.awaitingReturn) return
        val presence = withContext(Dispatchers.IO) { mediaPresence(id) }
        if (mutable.value.googleFlow != flow) return
        googleFlow(PhotoRules.returnedFromGooglePhotos(flow, presence))
        if (presence == MediaPresence.MISSING) {
            confirmedDeletion(listOf(id))
            mutable.update { it.copy(photos = it.photos.filter { p -> p.meta.id != id }, selected = it.selected - id) }
        }
        else message(if (presence == MediaPresence.UNKNOWN)
            "Não foi possível confirmar a exclusão. Confira as permissões, pule ou tente de novo."
            else "A foto continua no celular. No Google Fotos, toque na lixeira; depois volte aqui.")
    }
    fun message(value: String) { mutable.update { it.copy(message = value) } }

    private fun record(photo: Photo, apagar: Boolean) {
        learning.record(listOf(TrainingExample(photo.meta.id, Learner.features(photo.meta, photo.analysis!!),
            apagar, System.currentTimeMillis())))
    }

    private suspend fun reevaluate() {
        if (mutable.value.teaching || mutable.value.scanning || mutable.value.googleFlow != null) return
        val source = analyzedPhotos
        val suggestions = withContext(Dispatchers.Default) {
            PhotoRules.classify(source, KeptPhotoStore(getApplication()).read(), learning.model,
                learning.examples().filter { it.apagar }.map { it.id }.toSet())
                .filter(PhotoRules::initiallySelected)
        }
        if (mutable.value.teaching || mutable.value.scanning || mutable.value.googleFlow != null || source !== analyzedPhotos) return
        mutable.update { it.copy(photos = suggestions, selected = suggestions.map { p -> p.meta.id }.toSet()) }
    }

    fun teach() {
        val current = mutable.value
        if (current.scanning || current.learning || current.googleFlow != null || current.teaching) return
        mutable.update { it.copy(learning = true) }
        viewModelScope.launch {
            try {
                val batch = withContext(Dispatchers.Default) {
                    val kept = KeptPhotoStore(getApplication()).read()
                    Learner.teachingBatch(PhotoRules.filterKept(analyzedPhotos, kept),
                        learning.examples().map { it.id }.toSet(), learning.model)
                }
                if (batch.isEmpty()) message("Não há fotos novas para ensinar. Analise mais fotos.")
                else mutable.update { it.copy(photos = batch, selected = emptySet(), category = null,
                    teaching = true, message = "Toque nas fotos que quer apagar. As outras serão mantidas.") }
            } catch (_: Exception) { message("Não foi possível abrir o modo Ensinar. Tente novamente.") }
            finally { mutable.update { it.copy(learning = false) } }
        }
    }

    fun finishTeaching() {
        val current = mutable.value
        if (!current.teaching || current.learning) return
        mutable.update { it.copy(learning = true, message = "Aprendendo com suas escolhas…") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    learning.record(current.photos.map { TrainingExample(it.meta.id, Learner.features(it.meta, it.analysis!!),
                        it.meta.id in current.selected, now) })
                    val kept = KeptPhotoStore(getApplication())
                    current.photos.forEach { kept.setKept(it.meta.id, it.meta.id !in current.selected) }
                }
                learning.awaitTraining()
                mutable.update { it.copy(teaching = false, taughtBatch = current.photos.size,
                    exampleCount = learning.examples().size,
                    message = learning.error ?: "Aprendi com ${current.photos.size} exemplos. Mais 60 quando quiser.") }
                reevaluate()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("Não foi possível salvar todos os exemplos. Toque em Pronto novamente.") }
            finally { mutable.update { it.copy(learning = false) } }
        }
    }

    fun leaveTeaching() {
        if (!mutable.value.teaching || mutable.value.learning) return
        mutable.update { it.copy(teaching = false, category = null) }
        viewModelScope.launch { reevaluate() }
    }

    fun stageDeletion(ids: List<Long>) {
        val previous = try { DeletionTrainingStore(getApplication()).read().associateBy { it.id } }
            catch (_: java.io.FileNotFoundException) { emptyMap() }
        DeletionTrainingStore(getApplication()).write(ids.mapNotNull { id ->
            analyzedPhotos.firstOrNull { it.meta.id == id }?.let {
                TrainingExample(id, Learner.features(it.meta, it.analysis!!), true, System.currentTimeMillis())
            } ?: previous[id]
        })
    }

    private fun confirmedDeletion(ids: List<Long>) {
        try {
            val staged = DeletionTrainingStore(getApplication()).read().filter { it.id in ids }
            if (staged.isNotEmpty()) learning.record(staged.map { it.copy(quando = System.currentTimeMillis()) })
            analyzedPhotos = analyzedPhotos.filter { it.meta.id !in ids }
        } catch (_: Exception) { message("Foto apagada; não foi possível salvar este exemplo de aprendizado.") }
    }

    fun localTrashResult(accepted: Boolean) {
        if (!accepted) { message("Movimentação cancelada. Suas fotos continuam na galeria."); return }
        mutable.update { it.copy(learning = true) }
        viewModelScope.launch {
            try {
                val confirmed = withContext(Dispatchers.IO) {
                    DeletionTrainingStore(getApplication()).read().filter { mediaPresence(it.id) == MediaPresence.MISSING }.map { it.id }
                }
                confirmedDeletion(confirmed)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                message("Não foi possível conferir a lixeira e salvar os exemplos. Tente novamente.")
            } finally {
                mutable.update { it.copy(learning = false) }
            }
            scan(analyzeMore = false)
        }
    }

    private fun mediaPresence(id: Long): MediaPresence = try {
        val context = getApplication<Application>()
        val mode = MediaAccess.mode(context)
        if (mode == 0) MediaPresence.UNKNOWN else {
            val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            context.contentResolver.query(photoUri(id), arrayOf(MediaStore.Images.Media.IS_TRASHED), args, null)
                ?.use { cursor ->
                    val found = cursor.moveToFirst()
                    PhotoRules.mediaPresence(mode == 2, found, found && cursor.getInt(0) == 1)
                } ?: MediaPresence.UNKNOWN
        }
    } catch (_: Exception) { MediaPresence.UNKNOWN }
}
