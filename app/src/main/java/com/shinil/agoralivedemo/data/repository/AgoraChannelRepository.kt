package com.shinil.agoralivedemo.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.shinil.agoralivedemo.data.AppConfig
import com.shinil.agoralivedemo.di.DefaultDispatcher
import com.shinil.agoralivedemo.di.IoDispatcher
import com.shinil.agoralivedemo.domain.CallUser
import com.shinil.agoralivedemo.domain.ChannelConfig
import com.shinil.agoralivedemo.domain.ChannelConnectionState
import com.shinil.agoralivedemo.domain.ChannelMessage
import com.shinil.agoralivedemo.domain.ChannelState
import com.shinil.agoralivedemo.domain.repository.ChannelRepository
import io.agora.rtm.ErrorInfo
import io.agora.rtm.LinkStateEvent
import io.agora.rtm.MessageEvent
import io.agora.rtm.PresenceEvent
import io.agora.rtm.PublishOptions
import io.agora.rtm.ResultCallback
import io.agora.rtm.RtmClient
import io.agora.rtm.RtmConfig
import io.agora.rtm.RtmConstants
import io.agora.rtm.RtmEventListener
import io.agora.rtm.SubscribeOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class AgoraChannelRepository @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ChannelRepository {
    private val gson = Gson()

    private val _channelConfig = MutableStateFlow(
        ChannelConfig(
            channelName = AppConfig.CHANNEL_NAME,
            maxUsers = AppConfig.MAX_USERS
        )
    )
    override val channelConfig = _channelConfig.asStateFlow()

    private val _connectionState =
        MutableStateFlow<ChannelConnectionState>(ChannelConnectionState.Idle)
    override val connectionState = _connectionState.asStateFlow()

    private val _channelState = MutableStateFlow(ChannelState())
    override val channelState = _channelState.asStateFlow()

    private var rtmClient: RtmClient? = null

    private val userId: String =
        "user_${System.currentTimeMillis()}_${(1000..9999).random()}"

    private var localCallUid: Int? = null
    private var localDisplayName: String? = null
    private var localJoinTime: Long? = null

    private val uidToUserIdMap = mutableMapOf<Int, String>()

    @Volatile private var eventLoopRunning = false
    @Volatile private var isConnecting = false

    private sealed interface InternalEvent {
        data class IncomingMessage(val json: String) : InternalEvent
        object StateRequest : InternalEvent
        data class RemoteOffline(val callUid: Int) : InternalEvent
        data class PublishFailed(val error: ErrorInfo) : InternalEvent
    }

    /**
     * Lightweight envelope for parsing message type without full deserialization.
     * This avoids unsafe reflection-heavy Map parsing.
     */
    private data class MessageEnvelope(
        @SerializedName("type") val type: String
    )

    private val events = MutableSharedFlow<InternalEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val eventListener = object : RtmEventListener {
        override fun onLinkStateEvent(event: LinkStateEvent) {
            val previousState = _connectionState.value
            val newState = when (event.currentState) {
                RtmConstants.RtmLinkState.CONNECTED ->
                    ChannelConnectionState.Connected
                RtmConstants.RtmLinkState.CONNECTING ->
                    ChannelConnectionState.Connecting
                RtmConstants.RtmLinkState.DISCONNECTED ->
                    ChannelConnectionState.Disconnected
                RtmConstants.RtmLinkState.FAILED ->
                    ChannelConnectionState.Failed(event.reason.toString())
                else -> ChannelConnectionState.Idle
            }
            _connectionState.value = newState

            // When RTM reconnects (DISCONNECTED -> CONNECTED), request state if we're in a call
            if (previousState is ChannelConnectionState.Disconnected &&
                newState is ChannelConnectionState.Connected &&
                localCallUid != null) {
                // Request state to restore channel state after reconnection
                publishAsync(ChannelMessage.StateRequest(requesterId = userId))
            }
        }

        override fun onMessageEvent(event: MessageEvent) {
            val json = event.message.data as? String ?: return
            events.tryEmit(InternalEvent.IncomingMessage(json))
        }

        override fun onPresenceEvent(event: PresenceEvent) {}
    }

    override suspend fun connectToChannel() = withContext(ioDispatcher) {
        // Prevent concurrent connection attempts
        if (isConnecting) return@withContext
        if (_connectionState.value != ChannelConnectionState.Idle) return@withContext

        isConnecting = true
        try {
            _connectionState.value = ChannelConnectionState.Connecting

            rtmClient = RtmClient.create(
                RtmConfig.Builder(AppConfig.AGORA_APP_ID, userId)
                    .eventListener(eventListener)
                    .build()
            )

            login()
            subscribe()

            // Request state from other users after subscribing
            delay(500)
            publishAsync(ChannelMessage.StateRequest(requesterId = userId))
        } finally {
            isConnecting = false
        }
    }

    override suspend fun disconnectFromChannel() {
        // Reset connecting flag in case disconnect is called during connection attempt
        isConnecting = false
        
        // Unsubscribe before logout to prevent receiving messages during cleanup
        rtmClient?.unsubscribe(channelConfig.value.channelName, null)
        rtmClient?.logout(null)
        rtmClient = null

        // Clear all local state
        uidToUserIdMap.clear()
        localCallUid = null
        localDisplayName = null
        localJoinTime = null

        // Always reset state when stopping - ensures clean state on app restart
        _channelState.value = ChannelState()
        _connectionState.value = ChannelConnectionState.Idle
    }

    /**
     * Processes RTM channel events in a long-running loop.
     */
    override suspend fun processChannelEvents() {
        if (eventLoopRunning) return

        eventLoopRunning = true

        try {
            events.collect { event ->
                when (event) {
                    is InternalEvent.IncomingMessage -> {
                        withContext(defaultDispatcher) {
                            handleMessage(event.json)
                        }
                    }

                    InternalEvent.StateRequest ->
                        handleStateRequestSuspend()

                    is InternalEvent.RemoteOffline ->
                        handleRemoteOfflineInternal(event.callUid)

                    is InternalEvent.PublishFailed -> {
                        // TODO: Handle publish failed
                        Log.w("AgoraChannelRepo", "Publish failed: ${event.error}")
                    }
                }
            }
        } finally {
            eventLoopRunning = false
        }
    }

    override fun publishCallEvent(message: ChannelMessage) {
        when (message) {
            is ChannelMessage.CallJoined -> onUserJoined(message)
            is ChannelMessage.CallLeft -> onUserLeft(message)
            else -> {}
        }
        publishAsync(message)
    }

    override suspend fun handleRemoteUserOffline(callUid: Int) {
        events.emit(InternalEvent.RemoteOffline(callUid))
    }

    override fun getMessagingUserId(): String = userId

    override fun getDisplayNameForUid(callUid: Int): String? =
        _channelState.value.getUserByCallUid(callUid)?.displayName

    override fun isLocalUserInCall(): Boolean = localCallUid != null

    private fun handleMessage(json: String) {
        when (val msg = parseMessage(json) ?: return) {
            is ChannelMessage.CallJoined -> onUserJoined(msg)
            is ChannelMessage.CallLeft -> onUserLeft(msg)
            is ChannelMessage.StateRequest ->
                events.tryEmit(InternalEvent.StateRequest)
            is ChannelMessage.StateAnnounce -> onStateAnnounce(msg)
        }
    }

    private fun parseMessage(json: String): ChannelMessage? =
        try {
            val type = gson.fromJson(json, MessageEnvelope::class.java).type
            when (type) {
                "RTC_JOINED" ->
                    gson.fromJson(json, ChannelMessage.CallJoined::class.java)
                "RTC_LEFT" ->
                    gson.fromJson(json, ChannelMessage.CallLeft::class.java)
                "STATE_REQUEST" ->
                    gson.fromJson(json, ChannelMessage.StateRequest::class.java)
                "STATE_ANNOUNCE" ->
                    gson.fromJson(json, ChannelMessage.StateAnnounce::class.java)
                else -> null
            }
        } catch (_: Exception) {
            null
        }

    private fun onUserJoined(msg: ChannelMessage.CallJoined) {
        uidToUserIdMap[msg.callUid] = msg.userId

        if (msg.userId == userId) {
            localCallUid = msg.callUid
            localDisplayName = msg.displayName
            localJoinTime = msg.timestamp
        }

        _channelState.update {
            it.copy(
                callUsers = it.callUsers + (msg.userId to
                        CallUser(msg.userId, msg.displayName, msg.callUid)),
                channelStartTime = it.channelStartTime ?: msg.timestamp
            )
        }
    }

    private fun onUserLeft(msg: ChannelMessage.CallLeft) {
        // Clear local call info if this is the local user leaving
        if (msg.userId == userId) {
            localCallUid = null
            localDisplayName = null
            localJoinTime = null
        }

        msg.callUid?.let { uidToUserIdMap.remove(it) }

        _channelState.update {
            val users = it.callUsers - msg.userId
            // If all users left, reset the state completely
            if (users.isEmpty()) {
                ChannelState()
            } else {
                it.copy(
                    callUsers = users,
                    channelStartTime = it.channelStartTime
                )
            }
        }
    }

    private fun onStateAnnounce(msg: ChannelMessage.StateAnnounce) {
        uidToUserIdMap[msg.callUid] = msg.userId

        _channelState.update {
            val start =
                listOfNotNull(it.channelStartTime, msg.channelStartTime).minOrNull()
            it.copy(
                callUsers = it.callUsers + (msg.userId to
                        CallUser(msg.userId, msg.displayName, msg.callUid)),
                channelStartTime = start
            )
        }
    }

    private fun handleRemoteOfflineInternal(callUid: Int) {
        val userIdentifier = uidToUserIdMap[callUid] ?: return
        onUserLeft(ChannelMessage.CallLeft(
            userId = userIdentifier,
            callUid = callUid
        ))
    }

    private suspend fun handleStateRequestSuspend() {
        val uid = localCallUid ?: return
        val name = localDisplayName ?: return

        // Small jitter to avoid state announce storms when multiple users reconnect simultaneously
        delay((50..200).random().toLong())

        publishAsync(
            ChannelMessage.StateAnnounce(
                userId = userId,
                displayName = name,
                callUid = uid,
                channelStartTime =
                    _channelState.value.channelStartTime ?: localJoinTime
            )
        )
    }

    private fun publishAsync(message: ChannelMessage) {
        val rtm = rtmClient ?: return

        rtm.publish(
            channelConfig.value.channelName,
            gson.toJson(message),
            PublishOptions(),
            object : ResultCallback<Void?> {
                override fun onSuccess(res: Void?) {}
                override fun onFailure(err: ErrorInfo) {
                    // Emit internal event to track publish failures
                    events.tryEmit(InternalEvent.PublishFailed(err))
                }
            }
        )
    }

    private suspend fun login() =
        suspendCancellableCoroutine { cont ->
            rtmClient?.login(AppConfig.RTM_TOKEN,
                object : ResultCallback<Void?> {
                    override fun onSuccess(res: Void?) = cont.resume(Unit)
                    override fun onFailure(err: ErrorInfo) =
                        cont.resumeWithException(Exception(err.toString()))
                })
        }

    private suspend fun subscribe() =
        suspendCancellableCoroutine { cont ->
            rtmClient?.subscribe(
                channelConfig.value.channelName,
                SubscribeOptions().apply { withMessage = true },
                object : ResultCallback<Void?> {
                    override fun onSuccess(res: Void?) = cont.resume(Unit)
                    override fun onFailure(err: ErrorInfo) =
                        cont.resumeWithException(Exception(err.toString()))
                }
            )
        }
}
