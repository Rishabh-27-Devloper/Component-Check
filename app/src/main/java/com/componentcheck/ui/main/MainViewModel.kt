package com.componentcheck.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.componentcheck.data.local.ProfileEntity
import com.componentcheck.data.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProfileRepository.getInstance(application)

    val profiles: StateFlow<List<ProfileEntity>> = repository.allProfilesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _todayChangesCount = MutableStateFlow(0)
    val todayChangesCount: StateFlow<Int> = _todayChangesCount.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    init {
        loadTodayStats()
    }

    fun loadTodayStats() {
        viewModelScope.launch {
            _todayChangesCount.value = repository.getTodayChangesCount()
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val count = repository.refreshAllProfiles()
                loadTodayStats()
                _userMessage.emit("Updated $count profiles")
            } catch (e: Exception) {
                _userMessage.emit("Refresh failed: ${e.message}")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refreshSingle(profile: ProfileEntity) {
        viewModelScope.launch {
            val success = repository.refreshProfile(profile.username)
            if (success) {
                loadTodayStats()
                _userMessage.emit("Refreshed @${profile.username}")
            } else {
                _userMessage.emit("Failed to refresh @${profile.username}")
            }
        }
    }

    fun addProfile(
        handle: String,
        initialFollowers: Long? = null,
        onComplete: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val result = repository.addProfile(handle, initialFollowers)
            if (result.isSuccess) {
                loadTodayStats()
                onComplete(true, null)
            } else {
                onComplete(false, result.exceptionOrNull()?.message ?: "Failed to add profile")
            }
        }
    }

    fun updateBaselineCounters(username: String, followers: Long, following: Long, posts: Long) {
        viewModelScope.launch {
            repository.updateBaselineCounters(username, followers, following, posts)
            _userMessage.emit("Updated baseline for @$username")
        }
    }

    fun deleteProfile(profile: ProfileEntity) {
        viewModelScope.launch {
            repository.deleteProfile(profile.username)
            _userMessage.emit("Removed @${profile.username}")
        }
    }

    fun isInstagramConnected(): Boolean {
        return repository.sessionManager.isLoggedIn()
    }

    fun simulateChangeTest() {
        viewModelScope.launch {
            val username = repository.simulateChangeForTesting()
            if (username != null) {
                loadTodayStats()
                _userMessage.emit("Simulated change on @$username! Check your notifications.")
            } else {
                _userMessage.emit("Please add at least one profile first to test alerts.")
            }
        }
    }
}
