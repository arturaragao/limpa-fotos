package io.github.arturaragao.limpafotos

import android.app.Application
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
    val message: String = "Permita acesso às fotos para começar."
)

class CleanViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(CleanState())
    val state = mutable.asStateFlow()
    private var scanJob: Job? = null

    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            mutable.update { it.copy(scanning = true, progress = null, message = "Lendo a galeria…") }
            try {
                val result = PhotoScanner(getApplication()).scan { progress -> mutable.update { it.copy(progress = progress) } }
                val suggestions = result.photos.filter { it.categories.isNotEmpty() }
                mutable.update { previous -> previous.copy(photos = suggestions,
                    selected = suggestions.filter(PhotoRules::initiallySelected).map { it.meta.id }.toSet(),
                    scanning = false, scanned = true,
                    message = buildString {
                        append("${result.photos.size} imagens analisadas. Favoritos e melhores repetidas ficam desmarcados.")
                        if (result.unreadable > 0) append(" ${result.unreadable} sem análise visual; tente varrer novamente.")
                        if (result.cacheWarning) append(" Não foi possível salvar parte do cache.")
                    }) }
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
        mutable.value = CleanState(message = "Acesso às fotos indisponível. Permita o acesso para varrer.")
    }

    fun category(value: Category?) { mutable.update { it.copy(category = value) } }
    fun toggle(id: Long) { mutable.update { if (it.scanning) it else it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) } }
    fun message(value: String) { mutable.update { it.copy(message = value) } }
}
