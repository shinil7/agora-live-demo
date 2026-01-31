package com.shinil.agoralivedemo.domain.repository

import com.shinil.agoralivedemo.domain.CallConnectionState
import com.shinil.agoralivedemo.domain.RemoteUser
import com.shinil.agoralivedemo.domain.VideoSurface
import kotlinx.coroutines.flow.StateFlow

interface VideoCallRepository {
    val callState: StateFlow<CallConnectionState>
    val remoteUsers: StateFlow<List<RemoteUser>>
    val localUid: StateFlow<Int?>
    val isLocalSpeaking: StateFlow<Boolean>
    val networkLatency: StateFlow<Int?>

    suspend fun joinChannel(displayName: String): Boolean
    suspend fun leaveChannel()
    
    /**
     * MUST be launched from ViewModel scope.
     * Processes RTC events in a coroutine-safe manner.
     * Cancellation of the coroutine will stop processing events.
     */
    suspend fun runRtcEventLoop()
    
    fun setupLocalVideo(videoSurface: VideoSurface)
    fun setupRemoteVideo(uid: Int, videoSurface: VideoSurface)
    fun muteLocalAudio(mute: Boolean)
    fun enableLocalVideo(enable: Boolean)
    fun switchCamera()
}
