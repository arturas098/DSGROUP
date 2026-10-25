# TV Pultas v1

A separate Android Google TV / Android TV remote, inspired by the dark Google TV
remote layout shown in the screenshots. No Google TV mobile app is required.

## Features

- Open straight to the remote with the last television remembered.
- TV discovery on local Wi-Fi with manual IP fallback.
- One-time TV pairing with the code displayed by Android TV Remote Service.
- Reconnect using an app-private Android Keystore identity and pinned TV cert.
- D-pad / OK, Back, Home, Power, Mute, Volume.
- Keyboard mode with native phone keyboard; TV-side IME protocol for text input.
- Microphone opens Android speech-to-text and sends recognized words into the
  TV field (this is **not** the TV's own Google Assistant microphone session).
- No account, cloud, ads, or analytics.

## Install

Download **TV-Pultas-debug.apk** from
[releases](https://github.com/arturas098/DSGROUP/releases/tag/tv-pultas-v1).
Install on an Android phone and allow installation from your browser/files app.
Phone and television must be on the same local network.
For first use, tap the green device selector at the top, choose the TV or enter
its IP, then enter the 6-character pairing code shown on the TV.
Later app launches show the remote immediately and reconnect automatically.

This uses the reverse-engineered Android TV Remote v2 protocol
(TLS ports 6467 pairing and 6466 commands). Functionality depends on
the Android TV Remote Service being available and supporting remote IME.
Some TV models or firmware may not support text/voice exactly like Google TV.

## Build

With Android SDK 35 and Gradle 8.9:

```bash
gradle -p tv-remote :app:assembleDebug
```

APK: `tv-remote/app/build/outputs/apk/debug/app-debug.apk`.

The Wi-Fi Presence Scanner source in repository root remains independent.
