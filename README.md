# WaterRower S4 Tracker (Android)

A lightweight, open-source, offline-first Android app built with **Kotlin** and **Jetpack Compose** for tracking workouts on a **WaterRower S4** equipped with the Bluetooth **ComModule** (or any Bluetooth LE rowing machine supporting the standard **FTMS `0x1826`** profile).

Designed for tablets and phones — including a **system-wide floating overlay** so you can keep an eye on your pace, stroke rate, distance, and time while watching videos or streaming apps in fullscreen.

## Features

- **Standard BLE FTMS Support (`0x1826` / `0x2AD1`)**
  - Streams live 500m pace, stroke rate (SPM), stroke count, distance, elapsed time, power (watts), and calories directly from the WaterRower S4 ComModule (`S4 Comms`).
  - Automatically merges split FTMS `More Data` notification frames.
  - Reads **Battery Service (`0x180F`)** for the ComModule battery level and **Heart Rate Service (`0x180D`)** when a heart rate strap is connected.
- **Floating Overlay (`SYSTEM_ALERT_WINDOW` + `connectedDevice` Foreground Service)**
  - Compact, semi-transparent pill floating cleanly above fullscreen video/streaming apps:
    `2:04 /500m  •  26 spm  •  1.420 m  •  06:12`
  - Drag & drop anywhere on screen (position is remembered).
  - Tap to expand additional metrics (watts, calories, strokes, heart rate) and quick controls.
  - Adjustable opacity, font scale, and optional watts in compact mode.
  - Non-focusable window flags ensure touch input outside the pill passes straight through to the underlying app.
- **Local-First Workout History (Room / SQLite)**
  - Records workouts automatically on activity or manually, including 1Hz telemetry samples for post-workout stroke-rate and power charts.
  - Built-in **JSON** and **CSV** export via the Android Share Sheet (structured for easy future sync or third-party integrations).
- **Adaptive Tablet & Phone Layout**
  - Dark-mode-first UI with `NavigationRail` on tablets/landscape screens and `NavigationBar` on compact/split-screen layouts.
- **Built-In Simulator & BLE Diagnostics**
  - Test the floating overlay and workout recording without a rower connected.
  - Inspect discovered GATT services, firmware revision, and raw `0x2AD1` hex packets directly in Settings.

## Building & Installing (No Local Android Studio Required)

This repository includes a GitHub Actions workflow (`.github/workflows/build-apk.yml`) that runs unit tests and builds a debug APK on every push to `main`:

1. Open the **Actions** tab on GitHub and select the latest **Build WaterRower Tracker APK** run.
2. Download the **`waterrower-tracker-debug-apk`** artifact and extract `app-debug.apk`.
3. Install the APK on your Android device (Android 9 / API 28 or newer).

To build locally with Gradle & JDK 17:

```bash
gradle testDebugUnitTest assembleDebug
```

## Quick Start

1. Open the app and enable **Simulator** to try the **Floating Overlay** immediately (grant the *"Display over other apps"* permission when prompted).
2. Switch to any fullscreen video app — the overlay remains visible, draggable, and unobtrusive.
3. To connect your rower: disable Simulator, wake up the S4 monitor & ComModule, and tap **S4 Comms suchen** to pair.

## Architecture Overview

- `ble/` — Bluetooth SIG FTMS (`0x2AD1`), Heart Rate (`0x2A37`), and Battery (`0x2A19`) parsers, sequential GATT client (`BleRowerClient`), and `SimulatedRowerClient`.
- `service/` & `overlay/` — `RowingForegroundService` (`FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`), shared `RowingSessionHub`, and lightweight native `FloatingOverlayController`.
- `data/` — Room database (`workouts` + `workout_samples`), `WorkoutRecorder`, `WorkoutExporter`, and `AppPreferences`.
- `ui/` — Adaptive Jetpack Compose Material 3 screens (`DashboardScreen`, `HistoryScreen`, `WorkoutDetailScreen`, `SettingsScreen`).
