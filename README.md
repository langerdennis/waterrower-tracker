# WaterRower (Android)

A lightweight, open-source, offline-first Android app built with **Kotlin** and **Jetpack Compose (Material 3)** for tracking workouts on a **WaterRower S4** equipped with the Bluetooth **ComModule** (or any Bluetooth LE rowing machine supporting the standard **FTMS `0x1826`** profile).

Designed for tablets and phones — with a **system-wide floating overlay** so you can row intervals, hybrid kettlebell/mat circuits, or race your Ghost while watching series or movies in fullscreen.

## Features

- **Standard BLE FTMS Support (`0x1826` / `0x2AD1`)**
  - Streams live 500m pace, stroke rate (SPM), stroke count, distance, elapsed time, power (watts), and calories directly from the WaterRower S4 ComModule (`S4 Comms`).
  - Automatically merges split FTMS `More Data` notification frames, handles mid-session reconnects with exponential backoff, and supports optional **auto-reconnect on app start**.
  - Reads **Battery Service (`0x180F`)** (with `< 20%` low-battery alert directly in the overlay) and **Heart Rate Service (`0x180D`)** when a strap is present (auto-hiding HR cards when absent).
- **Floating Overlay (`SYSTEM_ALERT_WINDOW` + `connectedDevice` Foreground Service)**
  - **3 visual styles:** *Standard*, *Kino* (dimmed low-distraction look for dark scenes), and *Transparent* (subtitle-style floating text with shadow).
  - **Ultra-Compact mode & Position Lock:** Switch between full/compact text, cycle styles, or lock the pill position right inside the expanded overlay so you never accidentally drag it while controlling video playback.
  - **Gentle idle auto-dimming** after 15 s of stopped flywheel (wakes instantly on your next stroke or tap) + optional **auto-show overlay on connect**.
  - **Visual & audio cues:** Slim goal/program progress bar, peripheral target-pace border tint, live Ghost delta, and toggleable end-of-round/goal chime.
- **Scaled Interval & Hybrid (Row + 16 kg Kettlebell / Mat) Programs**
  - Curated presets (*500m Intervalle*, *1.000m Intervalle*, *Pyramide*, *Row & KB Swing*, *Row & Ganzkörper*) with per-program length scaling (`Kurz / Mittel / Lang`).
  - Hands-free transitions with a 6-second flywheel spin-down grace window and automatic resume on your next rowing stroke.
- **Goals & Live Ghost Racer**
  - Set free distance, duration, or target-pace goals, or race any previous recorded workout live (`+18 m` / `-12 m`) — completely optional and disableable.
- **Local-First History, 500m Splits, 8-Week Trend & Health Connect**
  - Room/SQLite storage with Real / Simulator / All filter, auto-start on rowing, idle auto-finish, 500m split tables, personal bests, optional weekly distance goal, 8-week bar chart, and weekly streak.
  - **Local Android Health Connect sync** (`EXERCISE_TYPE_ROWING_MACHINE` with distance, calories, title, and program notes — real workouts only).
  - Full **local JSON backup & UUID-deduplicated restore**, plus per-workout **JSON/CSV** export.
- **Built-In GitHub Release Updater & Diagnostics**
  - Check for and install signed GitHub Releases directly from **Settings → App-Updates (GitHub)** (stays completely offline until tapped).
  - Simulator mode and raw `0x2AD1` BLE packet diagnostics tucked cleanly under **Settings → Entwickler & BLE-Diagnose**.

## Installing

Download the latest signed release APK from **[GitHub Releases](https://github.com/langerdennis/waterrower-tracker/releases/latest)** (Android 9 / API 28 or newer). Subsequent updates can be installed in-place directly from **Settings → App-Updates (GitHub)** while preserving all local workout history.

## Building Locally

Requires JDK 17 and Gradle 8.11.1+:

```bash
gradle testDebugUnitTest assembleRelease
```

## Architecture Overview

- `ble/` — Bluetooth SIG FTMS (`0x2AD1`), Heart Rate (`0x2A37`), and Battery (`0x2A19`) parsers, sequential GATT client with backoff reconnect (`BleRowerClient`), and `SimulatedRowerClient`.
- `model/` — Telemetry formatting, `WorkoutGoal`, `GhostRacer`, `WorkoutAnalytics` (500m splits, personal bests, 8-week trend & streaks), `WorkoutProgram` & `ProgramRunner`.
- `service/` & `overlay/` — `RowingForegroundService` (`FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`), `RowingSessionHub`, and native `FloatingOverlayController`.
- `data/` — Room database (`workouts` + `workout_samples`, lossless migrations), `WorkoutRecorder`, `WorkoutExporter`, and `AppPreferences`.
- `health/` — Local Android Health Connect integration (`HealthConnectSyncManager`, `HealthConnectRationaleActivity`).
- `update/` — Zero-dependency GitHub Releases self-updater (`AppUpdater`).
- `ui/` — Adaptive Jetpack Compose Material 3 screens (`DashboardScreen`, `HistoryScreen`, `WorkoutDetailScreen`, `SettingsScreen`).
