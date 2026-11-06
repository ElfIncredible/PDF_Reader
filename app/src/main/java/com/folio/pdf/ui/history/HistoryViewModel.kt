package com.folio.pdf.ui.history

import android.text.format.DateUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.pdf.AppContainer
import com.folio.pdf.data.RecentsRepository
import com.folio.pdf.data.db.RecentDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

data class HistoryGroup(val title: String, val documents: List<RecentDocument>)

data class HistoryUiState(
    val groups: List<HistoryGroup> = emptyList(),
    val query: String = "",
    val total: Int = 0,
    val loading: Boolean = true,
)

class HistoryViewModel(private val recents: RecentsRepository) : ViewModel() {

    private val query = MutableStateFlow("")

    val uiState: StateFlow<HistoryUiState> = combine(recents.observeAll(), query) { all, q ->
        val filtered = all.filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }
        HistoryUiState(group(filtered), q, all.size, loading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun setQuery(value: String) { query.value = value }

    fun toggleFavorite(uri: String) = viewModelScope.launch { recents.toggleFavorite(uri) }

    fun remove(uri: String) = viewModelScope.launch { recents.remove(uri) }

    fun clear() = viewModelScope.launch { recents.clearHistory() }

    private fun group(docs: List<RecentDocument>): List<HistoryGroup> {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val startOfYesterday = startOfToday - DateUtils.DAY_IN_MILLIS
        val startOfWeek = startOfToday - 6 * DateUtils.DAY_IN_MILLIS
        val startOfMonth = startOfToday - 29 * DateUtils.DAY_IN_MILLIS

        return docs.groupBy {
            when {
                it.lastOpenedAt >= startOfToday -> "Today"
                it.lastOpenedAt >= startOfYesterday -> "Yesterday"
                it.lastOpenedAt >= startOfWeek -> "Earlier this week"
                it.lastOpenedAt >= startOfMonth -> "This month"
                else -> "Older"
            }
        }.map { (title, list) -> HistoryGroup(title, list) }
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory { initializer { HistoryViewModel(c.recents) } }
    }
}
