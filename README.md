# Presence Meter / Wi-Fi Presence Scanner (Android) — v0.2

An **experimental Android RSSI disturbance logger** for a stationary phone connected to a Wi-Fi router. It can sometimes flag signal changes when a person moves between the phone and router, but it **cannot reliably detect human presence**, determine whether a room is empty, count people, infer breathing, or produce RuView/DensePose body tracking. This is **not** based on the RuView CSI pipeline: public Android Wi-Fi APIs do not expose the needed CSI subcarrier amplitude/phase data.

## Features
- Live router-to-phone RSSI (dBm), frequency and link speed
- RSSI time series, updated with readings requested once per second
- 35-s baseline calibration and purely signal-based anomaly score (not probability)
- Accelerometer-based phone movement warning
- Optional Wi-Fi RTT/FTM range test **if both phone and router support** 802.11mc
- CSV export; no network upload, camera, microphone, or Internet permission
- Foreground-only; sampling stops when the app is not visible

## Build APK
Requirements: JDK 17, Android SDK platform 35, Gradle 8.9.

```sh
gradle assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

This is the standalone Presence Meter project, migrated from the WiFi scanner branch. Push to GitHub with Actions enabled. `.github/workflows/build-apk.yml` builds a debug APK and uploads it as a workflow artifact. In **Actions → Build Wi-Fi Presence Scanner APK → latest successful run → Artifacts**, download the APK ZIP. Enable permission to install unknown apps before installing the debug APK.

## Test protocol
1. Connect Android phone to router's Wi-Fi and give Nearby Wi-Fi/precise location permission; Android requires these for device Wi-Fi info.
2. Place phone stationary, ideally powered, with line between router and phone through test area. Keep app open.
3. With the area empty and still, tap **Calibrate (35 s)**.
4. Move someone across the router–phone line repeatedly. Compare score and signal graph against no-movement intervals.
5. Export CSV to see false positives and signal-reporting cadence; some drivers cache RSSI.
6. Move the phone or router to test a different geometry; re-calibrate each time.

Caveats: Most RSSI changes do not uniquely identify a human. A static person may produce no sustained change. Routers with multiple BSSIDs/mesh nodes may roam. Android may return repeated RSSI values. RSSI is **not** accurate distance; optional RTT needs compatible phone and AP. Android Wi-Fi scan throttling also affects RTT discovery.

## Permissions / privacy
- `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`: read connected Wi-Fi signal and network
- `ACCESS_FINE_LOCATION`: required by Android to access Wi-Fi scan/BSSID, not GPS tracking
- `NEARBY_WIFI_DEVICES`: required for Android 13+ Wi-Fi RTT operations
- `CHANGE_WIFI_STATE`: trigger an on-demand nearby access-point scan for optional RTT

The app does not ask for `INTERNET`, and does not run in the background.

## v0.2: Android Wi-Fi detection fix

- Fixes false NO WI-FI SIGNAL when Android redacts `WifiInfo.getNetworkId()` to -1 even with valid RSSI.
- Adds Wi-Fi network callback with `FLAG_INCLUDE_LOCATION_INFO` on Android 12+ for alternate `WifiInfo` retrieval, while keeping the existing RSSI poll fallback.
- Adds Wi-Fi and Location Settings buttons and live permission / Wi-Fi diagnostics, with clearer errors for restricted signal access.
- This fix improves recognition, **not human detection accuracy**. Android may still cache RSSI.
