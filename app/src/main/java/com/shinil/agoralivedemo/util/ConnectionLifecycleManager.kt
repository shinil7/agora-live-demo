package com.shinil.agoralivedemo.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.shinil.agoralivedemo.di.ApplicationScope
import com.shinil.agoralivedemo.domain.ChannelConnectionState
import com.shinil.agoralivedemo.domain.repository.ChannelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionLifecycleManager @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    @param:ApplicationScope private val appScope: CoroutineScope
) : DefaultLifecycleObserver {

    fun initialize() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        appScope.launch {
            val isOnline = connectivityMonitor.isCurrentlyOnline()
            val state = channelRepository.connectionState.value
            val isLocalUserInCall = channelRepository.isLocalUserInCall()

            // Only start RTM if we're not already connected and local user is not in a call
            // This allows monitoring the channel even when not in a call
            if (isOnline && state == ChannelConnectionState.Idle && !isLocalUserInCall) {
                channelRepository.connectToChannel()
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        appScope.launch {
            val isLocalUserInCall = channelRepository.isLocalUserInCall()
            // Only stop RTM if local user is not in a call
            // If local user is in a call, keep RTM connected even when app goes to background
            if (!isLocalUserInCall) {
                channelRepository.disconnectFromChannel()
            }
        }
    }
}