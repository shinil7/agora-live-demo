package com.shinil.agoralivedemo.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinil.agoralivedemo.domain.ChannelConnectionState

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    permissionsGranted: Boolean = true,
    onRequestPermissions: () -> Unit = {},
    onJoinCall: (username: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showUsernameDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!uiState.isOnline) {
                NetworkOfflineBanner(
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                DisconnectedScreen()
            } else {
                when (val state = uiState.channelConnectionState) {
                    ChannelConnectionState.Idle,
                    ChannelConnectionState.Connecting,
                    ChannelConnectionState.Reconnecting -> {
                        LoadingScreen(
                            text = when (state) {
                                ChannelConnectionState.Idle -> "Preparing channel…"
                                ChannelConnectionState.Connecting -> "Connecting…"
                                ChannelConnectionState.Reconnecting -> "Reconnecting…"
                                else -> ""
                            },
                            showRetryButton = state == ChannelConnectionState.Reconnecting || uiState.isRetrying,
                            onRetry = { viewModel.retryConnection() }
                        )
                    }

                    ChannelConnectionState.Disconnected -> {
                        DisconnectedScreen()
                    }

                    is ChannelConnectionState.Failed -> {
                        ErrorScreen(
                            message = state.reason ?: "Unable to connect",
                            onRetry = { viewModel.retryConnection() }
                        )
                    }

                    ChannelConnectionState.Connected -> {
                        if (!permissionsGranted) {
                            PermissionsRequiredScreen(
                                onRequestPermissions = onRequestPermissions
                            )
                        } else {
                            ConnectedHomeContent(
                                uiState = uiState,
                                onJoinClick = {
                                    if (viewModel.canJoinChannel()) {
                                        showUsernameDialog = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    if (showUsernameDialog) {
        UsernameDialog(
            channelName = uiState.channelName,
            onDismiss = { showUsernameDialog = false },
            onConfirm = { username ->
                showUsernameDialog = false
                onJoinCall(username)
            }
        )
    }
}