package com.shinil.agoralivedemo.data.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shinil.agoralivedemo.data.AndroidVideoSurface
import com.shinil.agoralivedemo.data.AppConfig
import com.shinil.agoralivedemo.domain.CallConnectionState
import com.shinil.agoralivedemo.domain.ChannelMessage
import com.shinil.agoralivedemo.domain.RemoteUser
import com.shinil.agoralivedemo.domain.RemoteUserState
import com.shinil.agoralivedemo.domain.VideoSurface
import com.shinil.agoralivedemo.domain.repository.ChannelRepository
import com.shinil.agoralivedemo.domain.repository.VideoCallRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val RECONNECT_TIMEOUT_MS = 20_000L
private const val SPEAKING_HOLD_MS = 500L
private const val SPEAKING_VOLUME_THRESHOLD = 40
private const val STATE_RESET_DELAY_MS = 500L

@Singleton
class AgoraVideoCallRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val channelRepository: ChannelRepository
) : VideoCallRepository {

    private val _callState =
        MutableStateFlow<CallConnectionState>(CallConnectionState.Idle)
    override val callState = _callState.asStateFlow()

    private val _remoteUsers =
        MutableStateFlow<List<RemoteUser>>(emptyList())
    override val remoteUsers = _remoteUsers.asStateFlow()

    private val _localUid =
        MutableStateFlow<Int?>(null)
    override val localUid = _localUid.asStateFlow()

    private val _isLocalSpeaking =
        MutableStateFlow(false)
    override val isLocalSpeaking = _isLocalSpeaking.asStateFlow()

    private val _networkLatency =
        MutableStateFlow<Int?>(null)
    override val networkLatency = _networkLatency.asStateFlow()

    private var rtcEngine: RtcEngine? = null
    private var displayName: String? = null
    private val lastSpeakingTime = mutableMapOf<Int, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    private sealed interface RtcEvent {
        data class JoinSuccess(val uid: Int) : RtcEvent
        data class UserJoined(val uid: Int) : RtcEvent
        data class UserOffline(val uid: Int) : RtcEvent
        data class ConnectionState(val state: Int, val reason: Int) : RtcEvent
        data class Error(val code: Int) : RtcEvent
        data class RemoteVideoState(val uid: Int, val state: Int, val reason: Int) : RtcEvent
        data class FirstFrame(val uid: Int) : RtcEvent
        data class MuteVideo(val uid: Int, val muted: Boolean) : RtcEvent
        data class MuteAudio(val uid: Int, val muted: Boolean) : RtcEvent
        data class Volume(val speakers: List<IRtcEngineEventHandler.AudioVolumeInfo>?)
            : RtcEvent
        data class Stats(val stats: IRtcEngineEventHandler.RtcStats) : RtcEvent
    }

    private val rtcEvents = MutableSharedFlow<RtcEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val rtcHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            rtcEvents.tryEmit(RtcEvent.JoinSuccess(uid))
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            rtcEvents.tryEmit(RtcEvent.UserJoined(uid))
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            rtcEvents.tryEmit(RtcEvent.UserOffline(uid))
        }

        override fun onLeaveChannel(stats: RtcStats?) {
            rtcEvents.tryEmit(RtcEvent.ConnectionState(
                Constants.CONNECTION_STATE_DISCONNECTED, 0)
            )
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            rtcEvents.tryEmit(RtcEvent.ConnectionState(state, reason))
        }

        override fun onError(err: Int) {
            rtcEvents.tryEmit(RtcEvent.Error(err))
        }

        override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
            rtcEvents.tryEmit(RtcEvent.RemoteVideoState(uid, state, reason))
        }

        override fun onFirstRemoteVideoFrame(uid: Int, width: Int, height: Int, elapsed: Int) {
            rtcEvents.tryEmit(RtcEvent.FirstFrame(uid))
        }

        override fun onUserMuteVideo(uid: Int, muted: Boolean) {
            rtcEvents.tryEmit(RtcEvent.MuteVideo(uid, muted))
        }

        override fun onUserMuteAudio(uid: Int, muted: Boolean) {
            rtcEvents.tryEmit(RtcEvent.MuteAudio(uid, muted))
        }

        override fun onAudioVolumeIndication(
            speakers: Array<out AudioVolumeInfo>?,
            totalVolume: Int
        ) {
            rtcEvents.tryEmit(RtcEvent.Volume(speakers?.toList()))
        }

        override fun onRtcStats(stats: RtcStats?) {
            if (stats == null) return
            rtcEvents.tryEmit(RtcEvent.Stats(stats))
        }
    }

    override suspend fun runRtcEventLoop() {
        rtcEvents.collect { event ->
            when (event) {

                is RtcEvent.JoinSuccess -> {
                    _localUid.value = event.uid
                    _callState.value = CallConnectionState.Joined

                    channelRepository.publishCallEvent(
                        ChannelMessage.CallJoined(
                            userId = channelRepository.getMessagingUserId(),
                            displayName = displayName ?: "User",
                            callUid = event.uid
                        )
                    )
                }

                is RtcEvent.UserJoined -> {
                    // RTC only manages transport state - identity comes from RTM
                    // Display name will be provided by RTM in the unified projection
                    _remoteUsers.update { users ->
                        if (users.none { it.uid == event.uid }) {
                            users + RemoteUser(
                                uid = event.uid,
                                displayName = "", // RTM owns identity, not RTC
                                connectionState = RemoteUserState.CONNECTING
                            )
                        } else users
                    }
                }

                is RtcEvent.UserOffline -> {
                    _remoteUsers.update { it.filter { u -> u.uid != event.uid } }
                    channelRepository.handleRemoteUserOffline(event.uid)
                }

                is RtcEvent.ConnectionState -> handleConnectionState(event)

                is RtcEvent.Error -> {
                    _callState.value = CallConnectionState.Failed(
                        when (event.code) {
                            109, 111 -> "Token expired"
                            110 -> "Invalid token"
                            else -> "Error ${event.code}"
                        }
                    )
                }

                is RtcEvent.RemoteVideoState -> handleRemoteVideo(event)

                is RtcEvent.FirstFrame -> {
                    updateRemoteUser(event.uid) {
                        it.copy(connectionState = RemoteUserState.CONNECTED)
                    }
                }

                is RtcEvent.MuteVideo -> {
                    updateRemoteUser(event.uid) { it.copy(isVideoEnabled = !event.muted) }
                }

                is RtcEvent.MuteAudio -> {
                    updateRemoteUser(event.uid) { it.copy(isMuted = event.muted) }
                }

                is RtcEvent.Volume -> handleVolume(event)

                is RtcEvent.Stats -> {
                    val latency = event.stats.lastmileDelay
                    if (latency > 0) _networkLatency.value = latency
                }
            }
        }
    }

    private fun handleConnectionState(event: RtcEvent.ConnectionState) {
        when (event.state) {

            Constants.CONNECTION_STATE_CONNECTING ->
                _callState.value = CallConnectionState.Joining

            Constants.CONNECTION_STATE_CONNECTED -> {
                cancelReconnectTimeout()
                val wasReconnecting = _callState.value is CallConnectionState.Reconnecting
                _callState.value = CallConnectionState.Joined
                
                // If we were reconnecting, re-broadcast our presence to restore RTM state
                if (wasReconnecting && _localUid.value != null) {
                    channelRepository.publishCallEvent(
                        ChannelMessage.CallJoined(
                            userId = channelRepository.getMessagingUserId(),
                            displayName = displayName ?: "User",
                            callUid = _localUid.value!!
                        )
                    )
                }
            }

            Constants.CONNECTION_STATE_RECONNECTING,
            Constants.CONNECTION_STATE_DISCONNECTED -> {
                _callState.value = CallConnectionState.Reconnecting
                scheduleReconnectTimeout()
            }

            Constants.CONNECTION_STATE_FAILED ->
                _callState.value = CallConnectionState.Failed("Connection failed")
        }
    }

    private fun scheduleReconnectTimeout() {
        cancelReconnectTimeout()

        reconnectRunnable = Runnable {
            if (_callState.value is CallConnectionState.Reconnecting) {
                disconnectFromCall()
            }
        }

        mainHandler.postDelayed(reconnectRunnable!!, RECONNECT_TIMEOUT_MS)
    }

    private fun cancelReconnectTimeout() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun handleRemoteVideo(event: RtcEvent.RemoteVideoState) {
        val newState = when (event.state) {
            Constants.REMOTE_VIDEO_STATE_DECODING -> RemoteUserState.CONNECTED
            Constants.REMOTE_VIDEO_STATE_FROZEN,
            Constants.REMOTE_VIDEO_STATE_FAILED -> RemoteUserState.RECONNECTING
            else -> null // Keep current state
        }
        
        if (newState != null) {
            updateRemoteUser(event.uid) { it.copy(connectionState = newState) }
        }
    }

    private fun handleVolume(event: RtcEvent.Volume) {
        val now = System.currentTimeMillis()

        event.speakers?.forEach {
            val active =
                if (it.uid == 0) it.vad == 1
                else it.volume > SPEAKING_VOLUME_THRESHOLD

            if (active) lastSpeakingTime[it.uid] = now
        }

        _isLocalSpeaking.value =
            (now - (lastSpeakingTime[0] ?: 0L)) < SPEAKING_HOLD_MS

        _remoteUsers.update {
            it.map { u ->
                val speaking =
                    (now - (lastSpeakingTime[u.uid] ?: 0L)) < SPEAKING_HOLD_MS
                if (u.isSpeaking != speaking)
                    u.copy(isSpeaking = speaking)
                else u
            }
        }
    }

    private fun initializeRtcEngine(): Boolean {
        if (rtcEngine != null) return true

        return try {
            rtcEngine = RtcEngine.create(
                RtcEngineConfig().apply {
                    mContext = this@AgoraVideoCallRepository.context.applicationContext
                    mAppId = AppConfig.AGORA_APP_ID
                    mEventHandler = rtcHandler
                }
            )
            rtcEngine?.apply {
                enableVideo()
                setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION)
                setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
                enableAudioVolumeIndication(150, 5, true)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Joins the RTC channel with the given display name.
     * Returns true if join was initiated successfully, false otherwise.
     * 
     * Can only be called from Idle or Left state. If called from Left state,
     * the state is reset to Idle first to ensure clean state.
     */
    override suspend fun joinChannel(displayName: String): Boolean {
        // Only allow joining from Idle or Left state
        if (_callState.value !is CallConnectionState.Idle && 
            _callState.value !is CallConnectionState.Left) return false
        if (!initializeRtcEngine()) return false

        // If we're joining from Left state, reset to Idle first to clear any stale state
        if (_callState.value is CallConnectionState.Left) {
            _callState.value = CallConnectionState.Idle
        }

        this.displayName = displayName
        _callState.value = CallConnectionState.Joining

        val channelName = channelRepository.channelConfig.value.channelName

        val result = rtcEngine?.joinChannel(
            AppConfig.RTC_TOKEN.takeIf { it.isNotBlank() },
            channelName,
            0,
            ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                autoSubscribeAudio = true
                autoSubscribeVideo = true
            }
        ) ?: -1
        return result == 0
    }

    override suspend fun leaveChannel() {
        disconnectFromCall()
    }

    private fun disconnectFromCall() {
        cancelReconnectTimeout()

        _localUid.value?.let {
            channelRepository.publishCallEvent(
                ChannelMessage.CallLeft(
                    userId = channelRepository.getMessagingUserId(),
                    callUid = it
                )
            )
        }

        rtcEngine?.leaveChannel()
        RtcEngine.destroy()
        rtcEngine = null

        // Set to Left first to trigger navigation, then reset to Idle after a delay
        // This allows the UI to react to Left state, but ensures we can join again
        _callState.value = CallConnectionState.Left
        
        // Reset to Idle after a delay to allow navigation to complete
        // This ensures the state is ready for the next join attempt
        mainHandler.postDelayed({
            if (_callState.value is CallConnectionState.Left) {
                _callState.value = CallConnectionState.Idle
            }
        }, STATE_RESET_DELAY_MS)
        
        clearCallState()
    }

    override fun setupLocalVideo(videoSurface: VideoSurface) {
        if (!initializeRtcEngine()) return

        val surfaceView = extractSurfaceView(videoSurface)
        rtcEngine?.setupLocalVideo(
            VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, 0)
        )
        rtcEngine?.startPreview()
    }

    override fun setupRemoteVideo(uid: Int, videoSurface: VideoSurface) {
        val surfaceView = extractSurfaceView(videoSurface)
        rtcEngine?.setupRemoteVideo(
            VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, uid)
        )
    }
    
    /**
     * Extracts the Android SurfaceView from the platform-agnostic VideoSurface.
     */
    private fun extractSurfaceView(videoSurface: VideoSurface): android.view.SurfaceView {
        return (videoSurface as? AndroidVideoSurface)?.getSurfaceView()
            ?: throw IllegalArgumentException("Expected AndroidVideoSurface")
    }

    override fun muteLocalAudio(mute: Boolean) {
        rtcEngine?.muteLocalAudioStream(mute)
    }

    override fun enableLocalVideo(enable: Boolean) {
        rtcEngine?.enableLocalVideo(enable)
    }

    override fun switchCamera() {
        rtcEngine?.switchCamera()
    }

    private fun updateRemoteUser(uid: Int, update: (RemoteUser) -> RemoteUser) {
        _remoteUsers.update { users ->
            users.map { user ->
                if (user.uid == uid) update(user) else user
            }
        }
    }

    private fun clearCallState() {
        _remoteUsers.value = emptyList()
        _localUid.value = null
        _isLocalSpeaking.value = false
        _networkLatency.value = null
        lastSpeakingTime.clear()
        displayName = null
    }
}