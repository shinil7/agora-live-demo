# Agora Live Video Call Demo

Android video calling app using **Agora RTC SDK** (interactive live streaming) + **Agora RTM SDK** (signaling) to show channel participants without joining the video call.

![Android](https://img.shields.io/badge/Platform-Android-green)
![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue)

---

## 🏗️ Architecture

**Requirements**: Public channel (max 4 users), join/leave, voice-video interaction, connection status, camera/mute controls, network interruption handling.

**Solution**: Use RTM + RTC together:
- **Home Screen**: Connects to RTM only → shows user count without joining video call
- **Call Screen**: Joins RTC channel → broadcasts join/leave events via RTM
- **State Sync**: RTC callbacks are source of truth → broadcasted via RTM messages

**Key Principle**: RTM owns identity (userId, displayName), RTC owns transport (uid, audio/video state)

### Assumptions

For this demo app, RTM+RTC approach was chosen because:
- **No backend required**: Pure client-side solution, perfect for demo
- **Real-time state sync**: RTM provides lightweight signaling without video overhead
- **Android-friendly**: Both SDKs are well-supported on Android
- **Alternative approaches** (webhooks, backend server) would require infrastructure → overkill for demo

---

## 🚀 Setup

1. **Get Agora credentials** from [Agora Console](https://console.agora.io/) (enable Signaling/RTM)
2. **Generate tokens** using [Agora Token Generator](https://agora-token-generator-demo.vercel.app):
   - RTM Token: Service = "Signaling (Pub-sub + Stream Channel)"
   - RTC Token: Service = "Interactive Live Streaming", Channel = `shinil_channel`
3. **Update `AppConfig.kt`** with your App ID, tokens, and channel name
4. **Build & Run**

⚠️ **Tokens expire after 24 hours** - regenerate regularly during development.

---

## ⚠️ Limitations

- Tokens expire after 24 hours (regenerate regularly)
- No automatic token refresh (production should use server-side generation)
- User presence loading delay: 1-2 second delay after RTM connection before user count is displayed. Join button may be enabled during this time even if channel is full. Can be solved by disabling the Join button while loading user presence.
- No unit tests (due to time constraints)
- UI not pixel-perfect (due to time constraints)
- Denied permission states are not handled

---

## 🛠️ Tech Stack

Kotlin • Jetpack Compose • MVVM • Hilt • Agora RTC SDK 4.6.0 • Agora RTM SDK 2.2.6
