package com.shinil.agoralivedemo.ui.call

import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinil.agoralivedemo.domain.CallConnectionState
import com.shinil.agoralivedemo.domain.LocalParticipant

@Composable
fun CallScreen(
    modifier: Modifier = Modifier,
    viewModel: CallViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showExitDialog by remember { mutableStateOf(false) }

    BackHandler {
        showExitDialog = true
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (uiState.callState is CallConnectionState.Joined ||
                        uiState.callState is CallConnectionState.Reconnecting) {
                        viewModel.pauseVideoForBackground()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (uiState.callState is CallConnectionState.Joined ||
                        uiState.callState is CallConnectionState.Reconnecting) {
                        viewModel.resumeVideoFromBackground()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var hasBeenInCall by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        viewModel.joinChannel()
    }

    LaunchedEffect(uiState.callState) {
        if (uiState.callState is CallConnectionState.Joined || 
            uiState.callState is CallConnectionState.Joining ||
            uiState.callState is CallConnectionState.Reconnecting) {
            hasBeenInCall = true
        }
    }

    LaunchedEffect(uiState.callState, hasBeenInCall) {
        if (uiState.callState is CallConnectionState.Left && hasBeenInCall) {
            onNavigateBack()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState.callState) {
                is CallConnectionState.Idle,
                is CallConnectionState.Joining -> {
                    JoiningCallContent()
                }

                is CallConnectionState.Joined -> {
                    JoinedCallContent(
                        uiState = uiState,
                        viewModel = viewModel,
                        onEndCall = { showExitDialog = true }
                    )
                    
                    if (uiState.showReconnectingOverlay) {
                        ReconnectingOverlay()
                    }
                }

                is CallConnectionState.Reconnecting -> {
                    JoinedCallContent(
                        uiState = uiState,
                        viewModel = viewModel,
                        onEndCall = { showExitDialog = true }
                    )
                    ReconnectingOverlay()
                }

                is CallConnectionState.Failed -> {
                    FailedCallContent(
                        reason = state.reason,
                        onBack = onNavigateBack
                    )
                }

                is CallConnectionState.Left -> {
                    // Show nothing - navigation should happen immediately via LaunchedEffect
                }
            }
        }
    }
    
    if (showExitDialog) {
        ExitConfirmationDialog(
            channelName = uiState.channelName,
            onDismiss = { showExitDialog = false },
            onConfirm = {
                showExitDialog = false
                viewModel.leaveChannel()
            }
        )
    }
}

@Composable
private fun JoinedCallContent(
    uiState: CallUiState,
    viewModel: CallViewModel,
    onEndCall: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        CallHeader(
            channelName = uiState.channelName,
            elapsedTime = uiState.elapsedTimeText,
            userCount = uiState.currentUserCount,
            maxUsers = uiState.maxUsers
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            VideoGrid(
                participants = uiState.participants,
                networkLatency = uiState.networkLatency,
                onLocalVideoReady = { surfaceView: SurfaceView ->
                    viewModel.setupLocalVideo(surfaceView)
                },
                onRemoteVideoReady = { uid: Int, surfaceView: SurfaceView ->
                    viewModel.setupRemoteVideo(uid, surfaceView)
                }
            )
        }

        val localParticipant = uiState.participants.firstOrNull { it is LocalParticipant } as? LocalParticipant
        
        CallControls(
            isAudioMuted = localParticipant?.isMuted ?: false,
            isVideoEnabled = localParticipant?.isVideoEnabled ?: true,
            onToggleMute = viewModel::toggleAudioMute,
            onToggleVideo = viewModel::toggleVideo,
            onSwitchCamera = viewModel::switchCamera,
            onEndCall = onEndCall
        )
    }
}
