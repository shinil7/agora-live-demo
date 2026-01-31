package com.shinil.agoralivedemo.ui.call

import android.view.SurfaceView
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinil.agoralivedemo.data.AndroidVideoSurface
import com.shinil.agoralivedemo.data.AppConfig
import com.shinil.agoralivedemo.domain.CallConnectionState
import com.shinil.agoralivedemo.domain.CallParticipant
import com.shinil.agoralivedemo.domain.ChannelConfig
import com.shinil.agoralivedemo.domain.ChannelState
import com.shinil.agoralivedemo.domain.LocalParticipant
import com.shinil.agoralivedemo.domain.RemoteParticipant
import com.shinil.agoralivedemo.domain.RemoteUser
import com.shinil.agoralivedemo.domain.repository.ChannelRepository
import com.shinil.agoralivedemo.domain.repository.VideoCallRepository
import com.shinil.agoralivedemo.util.ConnectivityMonitor
import com.shinil.agoralivedemo.util.TimeFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CallUiState(
    val callState: CallConnectionState = CallConnectionState.Idle,
    val participants: List<CallParticipant> = emptyList(),
    val channelName: String = "",
    val currentUserCount: Int = 0,
    val maxUsers: Int = AppConfig.MAX_USERS,
    val elapsedTimeText: String = "00:00:00",
    val isNetworkAvailable: Boolean = true,
    val showReconnectingOverlay: Boolean = false,
    val networkLatency: Int? = null
)

// Intermediate data classes for better readability
private data class VideoCallData(
    val callState: CallConnectionState,
    val remoteUsers: List<RemoteUser>,
    val localUid: Int?,
    val isLocalSpeaking: Boolean,
    val isLocalMuted: Boolean,
    val isLocalVideoEnabled: Boolean,
    val networkLatency: Int?
)

private data class ChannelData(
    val channelState: ChannelState,
    val channelConfig: ChannelConfig,
    val isNetworkAvailable: Boolean,
    val elapsedText: String
)

@HiltViewModel
class CallViewModel @Inject constructor(
    private val videoCallRepository: VideoCallRepository,
    private val channelRepository: ChannelRepository,
    connectivityMonitor: ConnectivityMonitor,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val username: String = savedStateHandle.get<String>("username") ?: "User"

    private val _isAudioMuted = MutableStateFlow(false)
    private val _isVideoEnabled = MutableStateFlow(true)

    val uiState: StateFlow<CallUiState> = combine(
        combine(
            videoCallRepository.callState,
            videoCallRepository.remoteUsers,
            videoCallRepository.localUid,
            videoCallRepository.isLocalSpeaking,
            videoCallRepository.networkLatency
        ) { callState: CallConnectionState,
            remoteUsers: List<RemoteUser>,
            localUid: Int?,
            isLocalSpeaking: Boolean,
            networkLatency: Int? ->
            Triple(callState, remoteUsers, Triple(localUid,
                isLocalSpeaking, networkLatency))
        },
        _isAudioMuted,
        _isVideoEnabled,
        combine(
            channelRepository.channelState,
            channelRepository.channelConfig,
            connectivityMonitor.isOnline,
            elapsedTimeFlow()
        ) { channelState: ChannelState,
            channelConfig: ChannelConfig,
            isNetworkAvailable: Boolean,
            elapsedText: String ->
            ChannelData(channelState, channelConfig, isNetworkAvailable, elapsedText)
        }
    ) { firstGroup, isAudioMuted: Boolean, isVideoEnabled: Boolean, channelData: ChannelData ->
        val (callState, remoteUsers, innerTriple) = firstGroup
        val (localUid, isLocalSpeaking, networkLatency) = innerTriple
        val videoCallData = VideoCallData(callState, remoteUsers, localUid, isLocalSpeaking,
            isAudioMuted, isVideoEnabled, networkLatency)
        // Unified projection: join RTM identity with RTC transport state
        // RTM owns identity, RTC owns transport - UI sees merged result

        /**
         * INVARIANT: UI never renders RTC-only users.
         *
         * RTM is the authority for identity. An RTC user without a corresponding RTM identity
         * is filtered out and never shown in the UI. This ensures:
         * - All displayed users have valid identity information (userId, displayName)
         * - No orphaned RTC transport states appear in the UI
         * - Consistent data model where identity always comes from RTM
         *
         * This invariant is enforced by filtering out any RTC users that don't have
         * a matching RTM identity via getUserByCallUid().
         */
        val participants = buildParticipantList(videoCallData, channelData.channelState)

        val showReconnectingOverlay =
            videoCallData.callState is CallConnectionState.Reconnecting ||
                    (videoCallData.callState is CallConnectionState.Joined && !channelData.isNetworkAvailable)

        CallUiState(
            callState = videoCallData.callState,
            participants = participants,
            channelName = channelData.channelConfig.channelName,
            currentUserCount = channelData.channelState.currentUserCount,
            maxUsers = channelData.channelState.maxUsers,
            elapsedTimeText = channelData.elapsedText,
            isNetworkAvailable = channelData.isNetworkAvailable,
            showReconnectingOverlay = showReconnectingOverlay,
            networkLatency = videoCallData.networkLatency
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CallUiState()
    )

    private var rtcEventLoopJob: Job? = null

    private var wasVideoEnabledBeforeBackground = false

    init {
        // State-driven event loop lifecycle: tied strictly to call state transitions
        // No manual sync needed - state changes automatically start/stop the loop
        // drop(1) skips the initial state emission - we only react to actual transitions
        uiState
            .map { it.callState }
            .distinctUntilChanged()
            .drop(1) // Skip initial state - only react to transitions
            .onEach { state ->
                when (state) {
                    is CallConnectionState.Joining -> startRtcEventLoop()
                    is CallConnectionState.Left,
                    is CallConnectionState.Idle -> stopRtcEventLoop()
                    else -> { /* No action for other states */ }
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Start the RTC event loop. This processes all RTC SDK callbacks.
     * The loop is automatically cancelled when the ViewModel is cleared,
     * or can be manually cancelled via stopRtcEventLoop().
     */
    private fun startRtcEventLoop() {
        if (rtcEventLoopJob?.isActive == true) return

        rtcEventLoopJob = viewModelScope.launch {
            videoCallRepository.runRtcEventLoop()
        }
    }

    /**
     * Stop the RTC event loop. This prevents processing of any late SDK callbacks.
     * Once cancelled, no RTC events will be processed even if the SDK fires callbacks.
     */
    private fun stopRtcEventLoop() {
        rtcEventLoopJob?.cancel()
        rtcEventLoopJob = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun elapsedTimeFlow() = channelRepository.channelState
        .map { it.channelStartTime }
        .distinctUntilChanged()
        .flatMapLatest { start ->
            if (start == null) flowOf("00:00:00")
            else tickerFlow().map { TimeFormatter.formatElapsedTime(it - start) }
        }

    private fun tickerFlow(periodMs: Long = 1000L) = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(periodMs)
        }
    }

    /**
     * Builds the list of call participants by merging RTC transport state with RTM identity.
     *
     * Only users with both RTC transport and RTM identity are included.
     * Local participant is placed first in the list.
     */
    private fun buildParticipantList(
        videoCallData: VideoCallData,
        channelState: ChannelState
    ): List<CallParticipant> {
        // Create remote participants
        val remoteParticipants = videoCallData.remoteUsers.mapNotNull { rtcUser ->
            val rtmUser = channelState.getUserByCallUid(rtcUser.uid)
                ?: return@mapNotNull null // Filter out RTC-only users (no RTM identity)

            RemoteParticipant(
                userId = rtmUser.userId,
                uid = rtcUser.uid,
                displayName = rtmUser.displayName,
                connection = rtcUser.connectionState,
                isMuted = rtcUser.isMuted,
                isVideoEnabled = rtcUser.isVideoEnabled,
                isSpeaking = rtcUser.isSpeaking
            )
        }

        // Create local participant if we have local UID and RTM identity
        val localParticipant = videoCallData.localUid?.let { localUid ->
            val localRtmUser = channelState.getUserByCallUid(localUid)
                ?: return@let null // Filter out local RTC-only state (wait for RTM identity)

            LocalParticipant(
                userId = localRtmUser.userId,
                displayName = localRtmUser.displayName,
                isMuted = videoCallData.isLocalMuted,
                isVideoEnabled = videoCallData.isLocalVideoEnabled,
                isSpeaking = videoCallData.isLocalSpeaking
            )
        }

        // Combine local + remote participants (local first)
        return buildList {
            localParticipant?.let { add(it) }
            addAll(remoteParticipants)
        }
    }

    fun joinChannel() {
        if (!uiState.value.isNetworkAvailable) return

        val currentState = uiState.value.callState
        // State-derived guard: only allow joining from Idle or Left states
        if (currentState !is CallConnectionState.Idle &&
            currentState !is CallConnectionState.Left) {
            return
        }

        viewModelScope.launch {
            videoCallRepository.joinChannel(username)
        }
    }

    fun leaveChannel() {
        viewModelScope.launch {
            videoCallRepository.leaveChannel()
        }
    }

    fun setupLocalVideo(surfaceView: SurfaceView) {
        videoCallRepository.setupLocalVideo(AndroidVideoSurface(surfaceView))
    }

    fun setupRemoteVideo(uid: Int, surfaceView: SurfaceView) {
        videoCallRepository.setupRemoteVideo(uid, AndroidVideoSurface(surfaceView))
    }

    fun toggleAudioMute() {
        val newMuteState = !_isAudioMuted.value
        _isAudioMuted.value = newMuteState
        videoCallRepository.muteLocalAudio(newMuteState)
    }

    fun toggleVideo() {
        val newVideoState = !_isVideoEnabled.value
        _isVideoEnabled.value = newVideoState
        videoCallRepository.enableLocalVideo(newVideoState)
    }

    fun switchCamera() {
        videoCallRepository.switchCamera()
    }

    /**
     * Pauses video when app goes to background.
     * Saves the current video state to restore later.
     */
    fun pauseVideoForBackground() {
        wasVideoEnabledBeforeBackground = _isVideoEnabled.value
        if (wasVideoEnabledBeforeBackground) {
            videoCallRepository.enableLocalVideo(false)
        }
    }

    /**
     * Resumes video when app comes back to foreground.
     * Restores the video state if it was enabled before.
     */
    fun resumeVideoFromBackground() {
        if (wasVideoEnabledBeforeBackground) {
            videoCallRepository.enableLocalVideo(true)
        }
    }
}