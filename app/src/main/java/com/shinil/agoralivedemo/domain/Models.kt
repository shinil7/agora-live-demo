package com.shinil.agoralivedemo.domain

import com.google.gson.annotations.SerializedName
import com.shinil.agoralivedemo.data.AppConfig

data class ChannelConfig(
    val channelName: String,
    val maxUsers: Int,
    val token: String? = null
)

data class CallUser(
    val userId: String,
    val displayName: String,
    val callUid: Int
)

data class ChannelState(
    val callUsers: Map<String, CallUser> = emptyMap(),
    val maxUsers: Int = AppConfig.MAX_USERS,
    val channelStartTime: Long? = null
) {
    val currentUserCount: Int get() = callUsers.size
    val canJoin: Boolean get() = callUsers.size < maxUsers

    fun getUserByCallUid(callUid: Int): CallUser? = callUsers.values.find { it.callUid == callUid }
}

sealed interface ChannelConnectionState {
    object Idle : ChannelConnectionState
    object Connecting : ChannelConnectionState
    object Connected : ChannelConnectionState
    object Reconnecting : ChannelConnectionState
    object Disconnected : ChannelConnectionState
    data class Failed(val reason: String? = null) : ChannelConnectionState
}

sealed interface CallConnectionState {
    object Idle : CallConnectionState
    object Joining : CallConnectionState
    object Joined : CallConnectionState
    object Reconnecting : CallConnectionState
    object Left : CallConnectionState
    data class Failed(val reason: String?) : CallConnectionState
}

sealed class ChannelMessage {
    data class CallJoined(
        @SerializedName("type") val type: String = "RTC_JOINED",
        @SerializedName("userId") val userId: String,
        @SerializedName("displayName") val displayName: String,
        @SerializedName("rtcUid") val callUid: Int,
        @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
    ) : ChannelMessage()

    data class CallLeft(
        @SerializedName("type") val type: String = "RTC_LEFT",
        @SerializedName("userId") val userId: String,
        @SerializedName("rtcUid") val callUid: Int? = null,
        @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
    ) : ChannelMessage()

    data class StateRequest(
        @SerializedName("type") val type: String = "STATE_REQUEST",
        @SerializedName("requesterId") val requesterId: String,
        @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
    ) : ChannelMessage()

    data class StateAnnounce(
        @SerializedName("type") val type: String = "STATE_ANNOUNCE",
        @SerializedName("userId") val userId: String,
        @SerializedName("displayName") val displayName: String,
        @SerializedName("rtcUid") val callUid: Int,
        @SerializedName("channelStartTime") val channelStartTime: Long?,
        @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
    ) : ChannelMessage()
}

enum class RemoteUserState {
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

data class RemoteUser(
    val uid: Int,
    val displayName: String = "",
    val isMuted: Boolean = false,
    val isVideoEnabled: Boolean = true,
    val isSpeaking: Boolean = false,
    val connectionState: RemoteUserState = RemoteUserState.CONNECTING
)

/**
 * Unified participant model that combines RTM identity with RTC transport state.
 * This is the single source of truth for UI - created by joining RTM and RTC flows.
 * 
 * Rule 1: RTM owns identity (userId, displayName, join/leave intent, max users)
 * Rule 2: RTC owns transport (uid, audio/video, speaking, latency, connection state)
 * Rule 3: UI reads this merged projection, not raw states
 * Rule 4: Local and remote participants are uniform - UI treats them the same
 */
sealed interface CallParticipant {
    val userId: String
    val displayName: String
    val isMuted: Boolean
    val isVideoEnabled: Boolean
    val isSpeaking: Boolean
}

/**
 * Local participant - represents the current user in the call.
 * Connection state is always CONNECTED (we know our own state).
 */
data class LocalParticipant(
    override val userId: String,
    override val displayName: String,
    override val isMuted: Boolean,
    override val isVideoEnabled: Boolean,
    override val isSpeaking: Boolean
) : CallParticipant

/**
 * Remote participant - represents other users in the call.
 * Includes connection state since we track remote users' connection status.
 */
data class RemoteParticipant(
    override val userId: String,
    val uid: Int,
    override val displayName: String,
    val connection: RemoteUserState,
    override val isMuted: Boolean,
    override val isVideoEnabled: Boolean,
    override val isSpeaking: Boolean
) : CallParticipant