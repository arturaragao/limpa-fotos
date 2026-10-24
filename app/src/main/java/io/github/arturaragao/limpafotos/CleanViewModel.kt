package io.github.arturaragao.limpafotos

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CleanState(
    val photos: List<Photo> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val category: Category? = null,
    val scanning: Boolean = false,
    val progress: ScanProgress? = null,
    val scanned: Boolean = false,
    val batchLimit: Int = 200,
    val googleFlow: GooglePhotosFlow? = null,
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
    private var deletionSummary: String? = null

    fun setBatchLimit(limit: Int) {
        require(limit > 0)
        preferences.edit().putInt("batch_limit", limit).apply()
        mutable.update { it.copy(batchLimit = limit) }
    }

    fun scan(analyzeMore: Boolean = true) {
        if (scanJob?.isActive == true || mutable.value.googleFlow != null) return
        val limit = if (analyzeMore) mutable.value.batchLimit else 0
        scanJob = viewModelScope.launch {
            mutable.update { it.copy(scanning = true, progress = null, message = "Lendo a galeria…") }
            try {
                val result = PhotoScanner(getApplication()).scan(limit) { progress -> mutable.update { it.copy(progress = progress) } }
                // Só aparecem as fotos que o app sugere apagar; o Artur desmarca o que quer manter.
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
                            if (result.progress.done == result.progress.total) append("Análise concluída.")
                            else append("Continue com o próximo lote quando quiser.")
                            if (result.unreadable > 0) append(" ${result.unreadable} falharam; tente analisar mais novamente.")
                            if (result.cacheWarning) append(" Não foi possível salvar parte do cache.")
                            deletionSummary?.let { append(" $it") }
                        })
                }
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
        val job = scanJob ?: return
        viewModelScope.launch {
            job.cancelAndJoin()
            scan(analyzeMore = false)
        }
    }

    fun clearAccess() {
        scanJob?.cancel()
        mutable.value = CleanState(batchLimit = mutable.value.batchLimit, googleFlow = mutable.value.googleFlow,
            message = "Abra Configurações para permitir o acesso às fotos.")
    }

    fun category(value: Category?) { mutable.update { it.copy(category = value) } }
    fun toggle(id: Long) {
        val current = mutable.value
        if (current.scanning || current.googleFlow != null || current.photos.none { it.meta.id == id }) return
        val keep = id in current.selected
        try {
            KeptPhotoStore(getApplication()).setKept(id, keep)
            mutable.update { it.copy(photos = if (keep) it.photos.filter { p -> p.meta.id != id } else it.photos,
                selected = if (keep) it.selected - id else it.selected + id) }
        } catch (_: Exception) { message("Não foi possível salvar sua decisão. Tente novamente.") }
    }

    fun refreshReviewSelection() {
        try {
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
        if (mutable.value.scanning || mutable.value.googleFlow != null) return
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
        if (current.scanning || current.googleFlow != null) return
        val ids = current.photos.filter { it.meta.id in current.selected }.map { it.meta.id }
        if (ids.isNotEmpty()) googleFlow(PhotoRules.startGooglePhotos(ids))
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
        val presence = withContext(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val mode = MediaAccess.mode(context)
                if (mode == 0) MediaPresence.UNKNOWN else {
                    val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
                    context.contentResolver.query(photoUri(id), arrayOf(MediaStore.Images.Media.IS_TRASHED), args, null)
                        ?.use { cursor ->
                            when {
                                cursor.moveToFirst() -> if (cursor.getInt(0) == 1) MediaPresence.MISSING else MediaPresence.EXISTS
                                // Partial permission loss must never be counted as deletion.
                                mode == 2 -> MediaPresence.MISSING
                                else -> MediaPresence.UNKNOWN
                            }
                        } ?: MediaPresence.UNKNOWN
                }
            } catch (_: Exception) { MediaPresence.UNKNOWN }
        }
        if (mutable.value.googleFlow != flow) return
        googleFlow(PhotoRules.returnedFromGooglePhotos(flow, presence))
        if (presence == MediaPresence.MISSING) mutable.update { it.copy(
            photos = it.photos.filter { p -> p.meta.id != id }, selected = it.selected - id) }
        else message(if (presence == MediaPresence.UNKNOWN)
            "Não foi possível confirmar a exclusão. Confira as permissões, pule ou tente de novo."
            else "A foto continua no celular. No Google Fotos, toque na lixeira; depois volte aqui.")
    }
    fun message(value: String) { mutable.update { it.copy(message = value) } }
}
