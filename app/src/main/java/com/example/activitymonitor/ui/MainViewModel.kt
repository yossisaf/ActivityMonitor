package com.example.activitymonitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.activitymonitor.db.ActivityEventEntity
import com.example.activitymonitor.db.BatterySampleEntity
import com.example.activitymonitor.repository.MonitorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(private val repository: MonitorRepository) : ViewModel() {
    private val _events = MutableStateFlow<List<ActivityEventEntity>>(emptyList())
    val events: StateFlow<List<ActivityEventEntity>> = _events.asStateFlow()
    private val _searchResults = MutableStateFlow<List<ActivityEventEntity>>(emptyList())
    val searchResults: StateFlow<List<ActivityEventEntity>> = _searchResults.asStateFlow()
    private val _battery = MutableStateFlow<List<BatterySampleEntity>>(emptyList())
    val battery: StateFlow<List<BatterySampleEntity>> = _battery.asStateFlow()

    init {
        viewModelScope.launch { repository.observeRecentEvents().collect { _events.value = it } }
        viewModelScope.launch { repository.observeBattery().collect { _battery.value = it } }
    }
    fun search(query: String, start: Long, end: Long, packageName: String? = null, eventType: Int? = null) = viewModelScope.launch { _searchResults.value = runCatching { repository.search(query, start, end, packageName, eventType) }.getOrDefault(emptyList()) }
    suspend fun event(id: Long) = repository.event(id)
    suspend fun packageEvents(packageName: String, start: Long, end: Long) = repository.packageEvents(packageName, start, end)
    class Factory(private val repository: MonitorRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repository) as T
    }
}
