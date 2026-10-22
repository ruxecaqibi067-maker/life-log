package com.personal.lifelog.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Theme preference state backed by the Room AppSettings row via SettingsRepository.
 * The initial value SYSTEM covers the brief window before the database flow emits;
 * the row is seeded on first database creation, so the real value follows immediately.
 */
class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settings.settings()
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /** Non-null while the last theme write failed; the screen shows a generic retry message. */
    private val _themeError = MutableStateFlow<String?>(null)
    val themeError: StateFlow<String?> = _themeError.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch {
            when (val result = settings.setTheme(mode)) {
                is DataResult.Success -> _themeError.value = null
                is DataResult.Failure -> _themeError.value = result.error.code.name
            }
        }
    }
}
