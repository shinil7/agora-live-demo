package com.shinil.agoralivedemo.ui.call

import android.view.SurfaceView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.shinil.agoralivedemo.domain.CallParticipant
import com.shinil.agoralivedemo.domain.LocalParticipant
import com.shinil.agoralivedemo.domain.RemoteParticipant
import com.shinil.agoralivedemo.domain.RemoteUserState
import com.shinil.agoralivedemo.ui.theme.AgoraLiveDemoTheme

/**
 * Returns the WiFi signal icon and color based on network latency.
 * Lower latency = better signal strength.
 */
@Composable
private fun getSignalStrength(latency: Int): Pair<ImageVector, Color> {
    return when {
        latency < 50 -> Pair(Icons.Default.SignalWifi4Bar, Color(0xFF4CAF50)) // Green - Excellent
        latency < 100 -> Pair(Icons.Default.SignalWifi4Bar, Color(0xFF8BC34A)) // Light Green - Good
        latency < 200 -> Pair(Icons.Default.SignalWifi4Bar, Color(0xFFFFC107)) // Yellow/Orange - Fair
        else -> Pair(Icons.Default.SignalWifiOff, Color(0xFFFF5722)) // Red - Poor
    }
}

@Composable
fun VideoGrid(
    participants: List<CallParticipant>,
    networkLatency: Int?,
    onLocalVideoReady: (SurfaceView) -> Unit,
    onRemoteVideoReady: (Int, SurfaceView) -> Unit
) {
    // Filter participants to show only those who are connected or connecting
    // For local participant, always show (connection state is implicit)
    // For remote participants, filter by connection state
    val visibleParticipants = participants.filter { participant ->
        when (participant) {
            is LocalParticipant -> true // Always show local participant
            is RemoteParticipant -> participant.connection == RemoteUserState.CONNECTED ||
                    participant.connection == RemoteUserState.RECONNECTING ||
                    participant.connection == RemoteUserState.CONNECTING
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = visibleParticipants,
            key = { participant ->
                when (participant) {
                    is LocalParticipant -> participant.userId
                    is RemoteParticipant -> participant.uid.toString()
                }
            }
        ) { participant ->
            when (participant) {
                is LocalParticipant -> {
                    VideoTile(
                        displayName = participant.displayName,
                        isVideoEnabled = participant.isVideoEnabled,
                        isMuted = participant.isMuted,
                        isSpeaking = participant.isSpeaking,
                        isLocal = true,
                        networkLatency = networkLatency,
                        modifier = Modifier.aspectRatio(0.75f)
                    ) { surfaceView ->
                        onLocalVideoReady(surfaceView)
                    }
                }
                is RemoteParticipant -> {
                    VideoTile(
                        displayName = participant.displayName,
                        isVideoEnabled = participant.isVideoEnabled,
                        isMuted = participant.isMuted,
                        isSpeaking = participant.isSpeaking,
                        isLocal = false,
                        connectionState = participant.connection,
                        networkLatency = networkLatency,
                        modifier = Modifier.aspectRatio(0.75f)
                    ) { surfaceView ->
                        onRemoteVideoReady(participant.uid, surfaceView)
                    }
                }
            }
        }
    }
}

@Composable
fun VideoTile(
    displayName: String,
    isVideoEnabled: Boolean,
    isMuted: Boolean,
    isSpeaking: Boolean,
    isLocal: Boolean,
    connectionState: RemoteUserState? = null,
    networkLatency: Int? = null,
    modifier: Modifier = Modifier,
    onSurfaceReady: (SurfaceView) -> Unit
) {
    val context = LocalContext.current
    val surfaceView = remember { SurfaceView(context) }

    DisposableEffect(Unit) {
        onSurfaceReady(surfaceView)
        onDispose {
            try {
                surfaceView.visibility = android.view.View.GONE
                surfaceView.holder.surface?.release()
            } catch (e: Exception) {
            }
        }
    }
    
    DisposableEffect(isVideoEnabled) {
        if (!isLocal) {
            surfaceView.visibility = if (isVideoEnabled) android.view.View.VISIBLE else android.view.View.GONE
        }
        onDispose { }
    }
    
    val isSpeakingAndNotMuted = isSpeaking && !isMuted
    val borderColor = if (isSpeakingAndNotMuted) MaterialTheme.colorScheme.primary else Color.Transparent
    val isConnecting = connectionState == RemoteUserState.CONNECTING
    val isReconnecting = connectionState == RemoteUserState.RECONNECTING

    Card(
        modifier = modifier
            .border(3.dp, borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (isVideoEnabled) {
                AndroidView(
                    factory = { surfaceView },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surfaceContainer,
                                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF374151)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
            
            if (!isLocal && (isConnecting || isReconnecting)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                )
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    ConnectingPill(isReconnecting = isReconnecting)
                }
            }
            
            if (!isLocal && !isReconnecting && networkLatency != null) {
                val (signalIcon, signalColor) = getSignalStrength(networkLatency)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(
                            Color.Black.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = signalIcon,
                        contentDescription = null,
                        tint = signalColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${networkLatency}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .height(25.dp)
                        .background(
                            Color.Black.copy(alpha = 0.4f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    if (isLocal) {
                        Text(
                            text = displayName,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = " (You)",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    } else {
                        Text(
                            text = displayName,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                if (isMuted) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color(0xFF374151), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MicOff,
                            contentDescription = "Muted",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    SpeakingIndicator(isActive = isSpeaking)
                }
            }
        }
    }
}

@Composable
fun SpeakingIndicator(isActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "speaking")
    
    val bar1Height by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    
    val bar2Height by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    
    val bar3Height by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(350, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    val backgroundColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val barColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val h1 = if (isActive) bar1Height else 0.3f
    val h2 = if (isActive) bar2Height else 0.3f
    val h3 = if (isActive) bar3Height else 0.3f

    val contentHeight = 21.dp // 25 - 2*2 (vertical padding)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = backgroundColor,
        modifier = Modifier
            .width(45.dp)
            .height(25.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 2.dp)
                .height(contentHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Bar 1
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(contentHeight),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height((contentHeight * h2).coerceAtMost(contentHeight))
                        .background(barColor, RoundedCornerShape(2.dp))
                )
            }
            // Bar 2
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(contentHeight),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height((contentHeight * h1).coerceAtMost(contentHeight))
                        .background(barColor, RoundedCornerShape(2.dp))
                )
            }
            // Bar 3
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(contentHeight),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height((contentHeight * h3).coerceAtMost(contentHeight))
                        .background(barColor, RoundedCornerShape(2.dp))
                )
            }
            // Bar 4
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(contentHeight),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height((contentHeight * (if (isActive) h1 * 0.85f else 0.3f)).coerceAtMost(contentHeight))
                        .background(barColor, RoundedCornerShape(2.dp))
                )
            }
            // Bar 5
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(contentHeight),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height((contentHeight * (if (isActive) h2 * 0.9f else 0.3f)).coerceAtMost(contentHeight))
                        .background(barColor, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
fun ConnectingPill(isReconnecting: Boolean = false) {
    val statusText = if (isReconnecting) "Reconnecting..." else "Connecting..."
    val iconColor = if (isReconnecting) Color(0xFFFFA726) else Color(0xFFFF6B6B)
    
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF374151).copy(alpha = 0.9f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SpeakingIndicatorActivePreview() {
    AgoraLiveDemoTheme {
        SpeakingIndicator(isActive = true)
    }
}

@Preview(showBackground = true)
@Composable
private fun SpeakingIndicatorInactivePreview() {
    AgoraLiveDemoTheme {
        SpeakingIndicator(isActive = false)
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectingPillPreview() {
    AgoraLiveDemoTheme {
        ConnectingPill(isReconnecting = false)
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectingPillReconnectingPreview() {
    AgoraLiveDemoTheme {
        ConnectingPill(isReconnecting = true)
    }
}
