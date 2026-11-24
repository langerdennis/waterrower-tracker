# WaterRower S4 Tracker (Android Tablet + Floating Overlay)

Eine schlanke, lokale Android-App (Kotlin + Jetpack Compose) für den **WaterRower S4** mit Bluetooth-ComModule (**`S4 Comms 61`** / standardisiertes BLE **FTMS `0x1826`**), optimiert für Tablets (z. B. **Pixel Tablet mit Android 17**) und das Serien-Schauen auf **Nuvio**.

## Features
- **Direkte BLE-FTMS-Anbindung (`0x1826` / `0x2AD1`):** Liest Pace (`/500m`), Schlagzahl (`SPM`), Distanz, Zeit, Watt, Kalorien und Schläge direkt vom `S4 Comms 61`.
- **Battery (`0x180F`) & Heart Rate (`0x180D`):** Zeigt den Akkustand des ComModules und (falls gekoppelt) die Herzfrequenz an.
- **Floating Overlay über Nuvio (`SYSTEM_ALERT_WINDOW` + `connectedDevice` Foreground Service):**
  - Kompakt-Modus: `2:04 /500m • 26 spm • 1.420 m • 06:12`
  - Per Drag & Drop frei auf dem Bildschirm positionierbar (Position wird gespeichert).
  - Ein Tipp auf die Leiste klappt Zusatzwerte (Watt, Kalorien, Puls) und Schnell-Buttons auf.
  - Deckkraft und Schriftgröße in den Einstellungen anpassbar.
- **Local-First Workout-Historie (Room SQLite):** Speichert alle Einheiten inkl. 1Hz-Verlaufsdaten lokal mit Verlaufskurven und JSON/CSV-Export (bereit für späteren Server-Sync).
- **Eingebauter Simulator-Modus:** Zum Testen des Overlays über Nuvio ohne verbundenes Rudergerät.
- **BLE-Diagnose-Ansicht:** Zeigt erkannte GATT-Services, Firmware und empfangene `0x2AD1`-Rohpakete.

## Kostenloser APK-Build via GitHub Actions (ohne lokales Android Studio)
1. Dieses Repository zu GitHub pushen (öffentlich oder privat).
2. In GitHub den Tab **Actions** öffnen → der Workflow **Build WaterRower Tracker APK** startet automatisch (oder manuell per *Run workflow*).
3. Nach ca. 2–3 Minuten unter **Artifacts** die Datei **`waterrower-tracker-debug-apk`** direkt auf dem Pixel Tablet herunterladen, entpacken und `app-debug.apk` installieren.

## Erster Test auf dem Pixel Tablet
1. App öffnen → **Simulator** aktivieren → **Floating Overlay starten** → Berechtigung *„Über anderen Apps einblenden“* erteilen.
2. Zu **Nuvio** wechseln und ein Video im Vollbild starten: Das Overlay bleibt sichtbar, lässt sich frei verschieben und blockiert keine Eingaben im Video.
3. Für echtes Rudern: Simulator ausschalten, WaterRower S4 + `S4 Comms 61` aktivieren und **S4 Comms suchen** tippen.
