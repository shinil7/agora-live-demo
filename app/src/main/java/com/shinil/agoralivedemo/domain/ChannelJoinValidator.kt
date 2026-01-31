package com.shinil.agoralivedemo.domain

object ChannelJoinValidator {
    fun canJoin(
        connectionState: ChannelConnectionState,
        channelState: ChannelState,
        isOnline: Boolean
    ): Boolean {
        return connectionState == ChannelConnectionState.Connected &&
                channelState.canJoin && isOnline
    }
}
