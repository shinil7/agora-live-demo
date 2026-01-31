package com.shinil.agoralivedemo.domain.repository

import com.shinil.agoralivedemo.domain.ChannelConfig
import com.shinil.agoralivedemo.domain.ChannelConnectionState
import com.shinil.agoralivedemo.domain.ChannelState
import com.shinil.agoralivedemo.domain.ChannelMessage
import kotlinx.coroutines.flow.StateFlow

interface ChannelRepository {
    val channelConfig: StateFlow<ChannelConfig>
    val connectionState: StateFlow<ChannelConnectionState>
    val channelState: StateFlow<ChannelState>

    /**
     * Connects to the RTM channel, logs in, and subscribes to channel messages.
     * This establishes the connection for monitoring channel state and broadcasting events.
     */
    suspend fun connectToChannel()
    
    /**
     * Disconnects from the RTM channel and clears all state.
     * This unsubscribes, logs out, and resets all channel-related state to ensure
     * a clean state for the next connection attempt.
     */
    suspend fun disconnectFromChannel()
    
    fun getMessagingUserId(): String
    fun getDisplayNameForUid(callUid: Int): String?
    
    /**
     * Returns true if the local user is currently in a call.
     * This is different from checking if there are any users in the channel,
     * as the local user might be monitoring the channel without being in the call.
     */
    fun isLocalUserInCall(): Boolean
    
    /**
     * Publishes a call event message to the RTM channel.
     * This broadcasts the event to all subscribers in the channel.
     */
    fun publishCallEvent(message: ChannelMessage)
    
    suspend fun handleRemoteUserOffline(callUid: Int)
    
    /**
     * MUST be launched from ViewModel scope.
     * Processes RTM events (messages, state requests, etc.).
     * The event loop will be cancelled when the ViewModel is cleared.
     */
    suspend fun processChannelEvents()
}
