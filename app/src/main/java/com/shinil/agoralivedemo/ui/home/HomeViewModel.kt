package com.shinil.agoralivedemo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinil.agoralivedemo.data.AppConfig
import com.shinil.agoralivedemo.domain.ChannelConnectionState
import com.shinil.agoralivedemo.domain.ChannelJoinValidator
import com.shinil.agoralivedemo.domain.repository.ChannelRepository
import com.shinil.agoralivedemo.util.ConnectivityMonitor
import com.shinil.agoralivedemo.util.TimeFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val channelName: String = "",
    val elapsedTimeText: String = "00:00:00",
    val maxUsers: Int = AppConfig.MAX_USERS,
    val currentUsers: Int = 0,
    val canJoin: Boolean = false,
    val channelConnectionState: ChannelConnectionState = ChannelConnectionState.Idle,
    val isOnline: Boolean = true,
    val isRetrying: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val connectivityMonitor: ConnectivityMonitor
) : ViewModel() {
    
    private val _isRetrying = MutableStateFlow(false)
    
    val uiState: StateFlow<HomeUiState> = combine(
        combine(
            channelRepository.channelConfig,
            channelRepository.connectionState,
            channelRepository.channelState,
            connectivityMonitor.isOnline
        ) { config, connectionState, channelState, isOnline ->
            ChannelData(config, connectionState, channelState, isOnline)
        },
        elapsedTimeFlow(),
        _isRetrying
    ) { channelData, elapsedText, isRetrying ->
        val (config, connectionState, channelState, isOnline) = channelData
        val errorMessage = (connectionState as? ChannelConnectionState.Failed)?.reason
        val canJoin = ChannelJoinValidator.canJoin(connectionState, channelState, isOnline)
        
        HomeUiState(
            channelName = config.channelName,
            elapsedTimeText = elapsedText,
            maxUsers = channelState.maxUsers,
            currentUsers = channelState.currentUserCount,
            canJoin = canJoin,
            channelConnectionState = connectionState,
            isOnline = isOnline,
            isRetrying = isRetrying,
            errorMessage = errorMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )
    
    private data class ChannelData(
        val config: com.shinil.agoralivedemo.domain.ChannelConfig,
        val connectionState: ChannelConnectionState,
        val channelState: com.shinil.agoralivedemo.domain.ChannelState,
        val isOnline: Boolean
    )

    init {
        viewModelScope.launch {
            channelRepository.processChannelEvents()
        }

        viewModelScope.launch {
            channelRepository.connectToChannel()
        }

        observeNetworkConnectivity()
    }

    /**
     * Observes network connectivity and manages channel connection lifecycle.
     * Automatically connects when network comes online and disconnects when offline.
     */
    private fun observeNetworkConnectivity() {
        viewModelScope.launch {
            var wasOffline = !connectivityMonitor.isCurrentlyOnline()
            connectivityMonitor.isOnline.collect { isOnline ->
                when {
                    // Network came back online
                    isOnline && wasOffline -> {
                        val connectionState = channelRepository.connectionState.value
                        val isInCall = channelRepository.channelState.value.currentUserCount > 0
                        
                        // Only connect if not already connected/connecting and not in a call
                        // (If in call, ConnectionLifecycleManager handles the connection)
                        if (connectionState !is ChannelConnectionState.Connected && 
                            connectionState !is ChannelConnectionState.Connecting && 
                            !isInCall) {
                            channelRepository.connectToChannel()
                        }
                    }
                    // Network went offline
                    !isOnline && !wasOffline -> {
                        channelRepository.disconnectFromChannel()
                    }
                }
                wasOffline = !isOnline
            }
        }
    }

    private fun elapsedTimeFlow() = combine(
                channelRepository.channelState,
                tickerFlow()
            ) { channelState, now ->
                channelState.channelStartTime?.let { now - it } ?: 0L
    }.map { elapsedMs -> TimeFormatter.formatElapsedTime(elapsedMs) }

    private fun tickerFlow(periodMs: Long = 1000L) = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(periodMs)
        }
    }

    fun retryConnection() {
        if (!uiState.value.isOnline) return
        if (_isRetrying.value) return
        
        _isRetrying.value = true
        
        viewModelScope.launch {
            try {
                channelRepository.disconnectFromChannel()
                delay(500) // Brief delay before reconnecting
                channelRepository.connectToChannel()
            } finally {
                _isRetrying.value = false
            }
        }
    }

    fun canJoinChannel(): Boolean {
        if (!uiState.value.isOnline) return false
        if (channelRepository.connectionState.value
            != ChannelConnectionState.Connected) return false
        return channelRepository.channelState.value.canJoin
    }
}