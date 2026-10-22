package com.personal.lifelog.feature.meal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.data.local.entity.MealRecord
import com.personal.lifelog.data.media.PhotoResult
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.RecordRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Read-only detail with an explicit delete action. The record and photo are always
 * re-read from Room and PhotoStorage, so returning from the editor shows committed
 * data rather than a stale in-memory copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MealDetailViewModel(
    private val mealId: String,
    private val records: RecordRepository,
    private val photos: PhotoStorage,
) : ViewModel() {

    private data class Loaded(
        val meal: MealRecord?,
        val loadFailed: Boolean = false,
        val photoUri: android.net.Uri? = null,
        val photoMissing: Boolean = false,
    )

    private data class DeleteState(
        val deleting: Boolean = false,
        val error: MealUiError? = null,
        val deleted: Boolean = false,
    )

    private val deleteState = MutableStateFlow(DeleteState())
    private val refresh = MutableStateFlow(0L)

    private val loaded: Flow<Loaded> = refresh.flatMapLatest {
        if (runCatching { Rules.id(mealId) }.isSuccess) {
            records.observeMeal(mealId)
                .mapLatest { meal ->
                    if (meal == null) {
                        Loaded(meal = null)
                    } else {
                        val photo = meal.photoId?.let { photos.photoUri(it) }
                        Loaded(
                            meal = meal,
                            photoUri = (photo as? PhotoResult.Success)?.value,
                            photoMissing = photo is PhotoResult.Failure,
                        )
                    }
                }
                .catch { emit(Loaded(meal = null, loadFailed = true)) }
        } else {
            flowOf(Loaded(meal = null))
        }
    }

    val state: StateFlow<MealDetailUiState> = combine(loaded, deleteState) { load, deletion ->
        MealDetailUiState(
            loading = false,
            missing = load.meal == null,
            loadError = if (load.loadFailed) MealUiError.LOAD_FAILED else null,
            meal = load.meal?.toDetail(),
            photoUri = load.photoUri,
            photoMissing = load.photoMissing,
            deleting = deletion.deleting,
            deleteError = deletion.error,
            deleted = deletion.deleted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MealDetailUiState(loading = true))

    /** Re-subscribe to Room after a transient read/photo failure. */
    fun retryLoad() {
        refresh.update { it + 1 }
    }

    fun delete() {
        if (deleteState.value.deleting || deleteState.value.deleted) return
        deleteState.update { it.copy(deleting = true, error = null) }
        viewModelScope.launch {
            when (val result = photos.deleteMeal(mealId)) {
                is PhotoResult.Success -> deleteState.update { it.copy(deleting = false, deleted = true) }
                is PhotoResult.Failure -> deleteState.update {
                    it.copy(
                        deleting = false,
                        error = result.dataError?.toMealUiError() ?: result.error.toMealUiError(),
                    )
                }
                PhotoResult.Cancelled -> deleteState.update { it.copy(deleting = false) }
            }
        }
    }
}
