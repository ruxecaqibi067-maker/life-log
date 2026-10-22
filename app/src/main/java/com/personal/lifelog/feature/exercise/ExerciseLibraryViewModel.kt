package com.personal.lifelog.feature.exercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.local.entity.ExerciseDefinition
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.ExerciseDraft
import com.personal.lifelog.domain.ExerciseType
import com.personal.lifelog.domain.RecordIds
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExerciseLibraryState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val query: String = "",
    val recentOnly: Boolean = false,
    val includeHidden: Boolean = false,
    val rows: List<ExerciseDefinition> = emptyList(),
)

data class ExerciseEditor(
    val id: String,
    val name: String = "",
    val type: ExerciseType? = null,
    val originalName: String? = null,
) {
    val isNew get() = originalName == null
    val dirty get() = if (isNew) name.isNotEmpty() || type != null else name != originalName
}

/** Room is the source of saved definitions. Only unfinished UI input is held in saved state. */
class ExerciseLibraryViewModel(
    private val repository: WorkoutRepository,
    private val saved: SavedStateHandle,
    private val selecting: Boolean = false,
) : ViewModel() {
    private data class Loaded(val rows: List<ExerciseDefinition> = emptyList(), val ready: Boolean = false, val failed: Boolean = false)
    private val loaded = MutableStateFlow(Loaded())
    private var loadJob: Job? = null
    private val query = saved.getStateFlow("query", "")
    private val recent = saved.getStateFlow("recent", false)
    private val hidden = saved.getStateFlow("hidden", false)
    val state = combine(loaded, query, recent, hidden) { data, search, recentOnly, showHidden ->
        val includeHidden = showHidden && !selecting
        val key = searchKey(search)
        ExerciseLibraryState(!data.ready, data.failed, search, recentOnly, includeHidden,
            data.rows.filter {
                (includeHidden || !it.isHidden) && (!recentOnly || it.lastUsedAtUtcMs != null) &&
                    searchKey(it.name).contains(key)
            }.sortedWith(compareByDescending<ExerciseDefinition> { it.lastUsedAtUtcMs }
                .thenBy { it.normalizedName }.thenBy { it.id }))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ExerciseLibraryState())

    private val _editor = MutableStateFlow(saved.get<ArrayList<String>>("editor")?.let {
        ExerciseEditor(it[0], it[1], it[2].takeIf(String::isNotEmpty)?.let(ExerciseType::valueOf),
            it[3].takeIf(String::isNotEmpty))
    })
    val editor = _editor.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<ErrorCode?>(null)
    val error = _error.asStateFlow()
    private val _typeRequired = MutableStateFlow(false)
    val typeRequired = _typeRequired.asStateFlow()

    init { retryLoad() }

    fun retryLoad() {
        loadJob?.cancel()
        loaded.value = Loaded()
        loadJob = viewModelScope.launch {
            repository.exercises(includeHidden = true)
                .catch { loaded.value = Loaded(ready = true, failed = true) }
                .collect { loaded.value = Loaded(it, ready = true) }
        }
    }

    fun search(value: String) { saved["query"] = value }
    fun showRecent(value: Boolean) { saved["recent"] = value }
    fun showHidden(value: Boolean) { saved["hidden"] = value }
    fun clearError() { _error.value = null; _typeRequired.value = false }

    fun add() { if (!_busy.value) { clearError(); setEditor(ExerciseEditor(RecordIds.newId())) } }
    fun edit(row: ExerciseDefinition) {
        if (!_busy.value) { clearError(); setEditor(ExerciseEditor(row.id, row.name, row.type, row.name)) }
    }
    fun changeName(name: String) {
        if (!_busy.value) { clearError(); setEditor(_editor.value?.copy(name = name)) }
    }
    fun chooseType(type: ExerciseType) {
        if (!_busy.value && _editor.value?.isNew == true) {
            clearError(); setEditor(_editor.value?.copy(type = type))
        }
    }
    fun dismissEditor() { if (!_busy.value) { setEditor(null); clearError() } }

    /** A creation in picker mode can be returned immediately to T08, without a second search. */
    fun save(onCreated: (ExerciseDefinition) -> Unit = {}) {
        if (_busy.value) return
        val input = _editor.value ?: return
        val type = input.type ?: run { _typeRequired.value = true; return }
        _busy.value = true
        clearError()
        viewModelScope.launch {
            try {
                when (val result = repository.saveExercise(ExerciseDraft(input.id, input.name, type))) {
                    is DataResult.Success -> {
                        setEditor(null)
                        // Ensure a new item is visible even when the previous search/recent filter excluded it.
                        if (input.isNew) { search(""); showRecent(false); onCreated(result.value) }
                    }
                    is DataResult.Failure -> _error.value = result.error.code
                }
            } finally { _busy.value = false }
        }
    }

    fun toggleHidden(row: ExerciseDefinition) {
        if (_busy.value) return
        _busy.value = true
        clearError()
        viewModelScope.launch {
            try {
                when (val result = repository.setExerciseHidden(row.id, !row.isHidden)) {
                    is DataResult.Success -> Unit
                    is DataResult.Failure -> _error.value = result.error.code
                }
            } finally { _busy.value = false }
        }
    }

    private fun setEditor(value: ExerciseEditor?) {
        saved["editor"] = value?.let { arrayListOf(it.id, it.name, it.type?.name.orEmpty(), it.originalName.orEmpty()) }
        _editor.value = value
    }
}

private fun searchKey(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
