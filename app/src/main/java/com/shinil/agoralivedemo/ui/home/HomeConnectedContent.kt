package com.shinil.agoralivedemo.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shinil.agoralivedemo.data.AppConfig
import com.shinil.agoralivedemo.ui.theme.AgoraLiveDemoTheme
import com.shinil.agoralivedemo.ui.theme.CardBackground
import com.shinil.agoralivedemo.ui.theme.CardBorder

@Composable
fun ConnectedHomeContent(
    uiState: HomeUiState,
    onJoinClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ChannelCard(
            uiState = uiState,
            onJoinClick = onJoinClick
        )
    }
}

@Composable
private fun ChannelCard(
    uiState: HomeUiState,
    onJoinClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = CardBackground
        ),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ChannelAvatar()

            Spacer(modifier = Modifier.height(16.dp))

            ChannelTitle()

            Spacer(modifier = Modifier.height(16.dp))

            ChannelInfoRow(
                elapsedTime = uiState.elapsedTimeText,
                userCount = "${uiState.currentUsers}/${uiState.maxUsers} users"
            )

            Spacer(modifier = Modifier.height(24.dp))

            ChannelSubtitle()

            Spacer(modifier = Modifier.height(16.dp))

            JoinChannelButton(
                canJoin = uiState.canJoin,
                onClick = onJoinClick
            )
        }
    }
}

@Composable
private fun ChannelAvatar() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color(0xFF2D3748)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(40.dp)
        )
    }
}

@Composable
private fun ChannelTitle() {
    Text(
        text = "Public Chat Group",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ChannelInfoRow(
    elapsedTime: String,
    userCount: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        InfoBadge(
            icon = Icons.Default.Timer,
            text = elapsedTime,
            iconTint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(12.dp))

        InfoBadge(
            icon = Icons.Default.Headphones,
            text = userCount,
            iconTint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ChannelSubtitle() {
    Text(
        text = "Enjoy the Interactive Chat!",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary
    )
}

@Composable
private fun JoinChannelButton(
    canJoin: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = canJoin,
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = if (canJoin) "JOIN CHANNEL" else "CHANNEL FULL",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (canJoin) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectedHomeContentPreview() {
    AgoraLiveDemoTheme {
        ConnectedHomeContent(
            uiState = HomeUiState(
                channelName = "Public Chat",
                elapsedTimeText = "00:15:30",
                maxUsers = AppConfig.MAX_USERS,
                currentUsers = 2,
                canJoin = true,
                channelConnectionState = com.shinil.agoralivedemo.domain.ChannelConnectionState.Connected
            ),
            onJoinClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChannelCardPreview() {
    AgoraLiveDemoTheme {
        ChannelCard(
            uiState = HomeUiState(
                channelName = "Public Chat",
                elapsedTimeText = "00:15:30",
                maxUsers = AppConfig.MAX_USERS,
                currentUsers = 2,
                canJoin = true
            ),
            onJoinClick = {}
        )
    }
}
