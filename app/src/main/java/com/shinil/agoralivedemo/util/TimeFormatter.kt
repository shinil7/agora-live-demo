package com.shinil.agoralivedemo.util

object TimeFormatter {
    fun formatElapsedTime(ms: Long): String {
        val totalSeconds = (ms + 999) / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }
}