package io.github.arturaragao.limpafotos

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val message: String = "Permita acesso às fotos para começar."
)

class CleanViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("analysis_settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(CleanState(batchLimit = preferences.getInt("batch_limit", 200).coerceAtLeast(1)))
    val state = mutable.asStateFlow()
    private var scanJob: Job? = null

    fun setBatchLimit(limit: Int) {
        require(limit > 0)
        preferences.edit().putInt("batch_limit", limit).apply()
        mutable.update { it.copy(batchLimit = limit) }
    }

    fun scan(analyzeMore: Boolean = true) {
        if (scanJob?.isActive == true) return
        val limit = if (analyzeMore) mutable.value.batchLimit else 0
        scanJob = viewModelScope.launch {
            mutable.update { it.copy(scanning = true, progress = null, message = "Lendo a galeria…") }
            try {
                val result = PhotoScanner(getApplication()).scan(limit) { progress -> mutable.update { it.copy(progress = progress) } }
                val suggestions = result.photos.filter { it.categories.isNotEmpty() }
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
                        })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                clearAccess()
            } catch (error: Exception) {
                mutable.update { it.copy(scanning = false, message = error.message ?: "Não foi possível varrer a galeria.") }
            }
        }
    }

    fun clearAccess() {
        scanJob?.cancel()
        mutable.value = CleanState(batchLimit = mutable.value.batchLimit,
            message = "Abra Configurações para permitir o acesso às fotos.")
    }

    fun category(value: Category?) { mutable.update { it.copy(category = value) } }
    fun toggle(id: Long) { mutable.update { if (it.scanning) it else it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) } }
    fun message(value: String) { mutable.update { it.copy(message = value) } }
}
