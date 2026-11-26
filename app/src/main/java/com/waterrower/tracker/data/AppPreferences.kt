package com.waterrower.tracker.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OverlayStyle(val id: String, val label: String) {
    STANDARD("standard", "Standard"),
    CINEMA("cinema", "Kino"),
    TRANSPARENT("transparent", "Transparent"),
    ;

    companion object {
        fun fromId(id: String?): OverlayStyle =
            entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

enum class OverlayContentMode(
    val id: String,
    val label: String,
    val shortLabel: String,
) {
    SMART("smart", "Smart (Mehrwert)", "Smart"),
    CINEMA_TOAST("cinema_toast", "Kino (Auto-Hide)", "Kino-Auto"),
    PROGRESS_BAR("progress_bar", "Balken / Ghost", "Balken"),
    TELEMETRY("telemetry", "Telemetrie (S4)", "Klassisch"),
    ;

    companion object {
        fun fromId(id: String?): OverlayContentMode =
            entries.firstOrNull { it.id == id } ?: SMART
    }
}

data class OverlaySettings(
    val opacity: Float = 0.85f,
    val scale: Float = 1.0f,
    val overlayStyle: OverlayStyle = OverlayStyle.STANDARD,
    val overlayContentMode: OverlayContentMode = OverlayContentMode.SMART,
    val overlayPositionLocked: Boolean = false,
    val showWattsInMini: Boolean = false,
    val autoStartRecording: Boolean = true,
    val autoFinishIdleMinutes: Int = AppPreferences.DEFAULT_AUTO_FINISH_MINUTES,
    val goalSoundEnabled: Boolean = true,
    val autoReconnectOnStart: Boolean = true,
    val autoShowOverlayOnConnect: Boolean = true,
    val weeklyGoalMeters: Int = 0,
    val healthConnectSyncEnabled: Boolean = false,
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val overlayX: Int = AppPreferences.DEFAULT_OVERLAY_X,
    val overlayY: Int = AppPreferences.DEFAULT_OVERLAY_Y,
)

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("waterrower_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<OverlaySettings> = _settings.asStateFlow()

    private fun load(): OverlaySettings {
        val storedMode = prefs.getString(KEY_OVERLAY_CONTENT_MODE, null)
        val contentMode = if (storedMode != null) {
            OverlayContentMode.fromId(storedMode)
        } else {
            OverlayContentMode.SMART
        }
        return OverlaySettings(
            opacity = prefs.getFloat(KEY_OPACITY, 0.85f).coerceIn(MIN_OPACITY, 1.0f),
            scale = prefs.getFloat(KEY_SCALE, 1.0f).coerceIn(0.8f, 1.4f),
            overlayStyle = OverlayStyle.fromId(prefs.getString(KEY_OVERLAY_STYLE, OverlayStyle.STANDARD.id)),
            overlayContentMode = contentMode,
            overlayPositionLocked = prefs.getBoolean(KEY_OVERLAY_LOCKED, false),
            showWattsInMini = prefs.getBoolean(KEY_SHOW_WATTS, false),
            autoStartRecording = prefs.getBoolean(KEY_AUTO_RECORD, true),
            autoFinishIdleMinutes = prefs.getInt(KEY_AUTO_FINISH_MIN, DEFAULT_AUTO_FINISH_MINUTES).coerceIn(0, 30),
            goalSoundEnabled = prefs.getBoolean(KEY_GOAL_SOUND, true),
            autoReconnectOnStart = prefs.getBoolean(KEY_AUTO_RECONNECT, true),
            autoShowOverlayOnConnect = prefs.getBoolean(KEY_AUTO_SHOW_OVERLAY, true),
            weeklyGoalMeters = prefs.getInt(KEY_WEEKLY_GOAL_METERS, 0).coerceIn(0, 200_000),
            healthConnectSyncEnabled = prefs.getBoolean(KEY_HEALTH_CONNECT_SYNC, false),
            lastDeviceAddress = prefs.getString(KEY_LAST_ADDR, null),
            lastDeviceName = prefs.getString(KEY_LAST_NAME, null),
            overlayX = prefs.getInt(KEY_OVERLAY_X, DEFAULT_OVERLAY_X),
            overlayY = prefs.getInt(KEY_OVERLAY_Y, DEFAULT_OVERLAY_Y),
        )
    }

    fun setOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(MIN_OPACITY, 1.0f)
        prefs.edit().putFloat(KEY_OPACITY, clamped).apply()
        _settings.value = _settings.value.copy(opacity = clamped)
    }

    fun setScale(scale: Float) {
        val clamped = scale.coerceIn(0.8f, 1.4f)
        prefs.edit().putFloat(KEY_SCALE, clamped).apply()
        _settings.value = _settings.value.copy(scale = clamped)
    }

    fun setOverlayStyle(style: OverlayStyle) {
        prefs.edit().putString(KEY_OVERLAY_STYLE, style.id).apply()
        _settings.value = _settings.value.copy(overlayStyle = style)
    }

    fun setOverlayContentMode(mode: OverlayContentMode) {
        prefs.edit().putString(KEY_OVERLAY_CONTENT_MODE, mode.id).apply()
        _settings.value = _settings.value.copy(overlayContentMode = mode)
    }

    fun setOverlayPositionLocked(locked: Boolean) {
        prefs.edit().putBoolean(KEY_OVERLAY_LOCKED, locked).apply()
        _settings.value = _settings.value.copy(overlayPositionLocked = locked)
    }

    fun setShowWattsInMini(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_WATTS, show).apply()
        _settings.value = _settings.value.copy(showWattsInMini = show)
    }

    fun setAutoStartRecording(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECORD, enabled).apply()
        _settings.value = _settings.value.copy(autoStartRecording = enabled)
    }

    fun setAutoFinishIdleMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(0, 30)
        prefs.edit().putInt(KEY_AUTO_FINISH_MIN, clamped).apply()
        _settings.value = _settings.value.copy(autoFinishIdleMinutes = clamped)
    }

    fun setGoalSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GOAL_SOUND, enabled).apply()
        _settings.value = _settings.value.copy(goalSoundEnabled = enabled)
    }

    fun setAutoReconnectOnStart(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
        _settings.value = _settings.value.copy(autoReconnectOnStart = enabled)
    }

    fun setAutoShowOverlayOnConnect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SHOW_OVERLAY, enabled).apply()
        _settings.value = _settings.value.copy(autoShowOverlayOnConnect = enabled)
    }

    fun setWeeklyGoalMeters(meters: Int) {
        val clamped = meters.coerceIn(0, 200_000)
        prefs.edit().putInt(KEY_WEEKLY_GOAL_METERS, clamped).apply()
        _settings.value = _settings.value.copy(weeklyGoalMeters = clamped)
    }

    fun setHealthConnectSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HEALTH_CONNECT_SYNC, enabled).apply()
        _settings.value = _settings.value.copy(healthConnectSyncEnabled = enabled)
    }

    fun saveLastDevice(address: String, name: String) {
        prefs.edit()
            .putString(KEY_LAST_ADDR, address)
            .putString(KEY_LAST_NAME, name)
            .apply()
        _settings.value = _settings.value.copy(
            lastDeviceAddress = address,
            lastDeviceName = name,
        )
    }

    fun saveOverlayPosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_OVERLAY_X, x)
            .putInt(KEY_OVERLAY_Y, y)
            .apply()
        _settings.value = _settings.value.copy(overlayX = x, overlayY = y)
    }

    fun resetOverlayPosition() {
        saveOverlayPosition(DEFAULT_OVERLAY_X, DEFAULT_OVERLAY_Y)
    }

    companion object {
        const val DEFAULT_OVERLAY_X = 36
        const val DEFAULT_OVERLAY_Y = 36
        const val DEFAULT_AUTO_FINISH_MINUTES = 3
        const val MIN_OPACITY = 0.15f

        private const val KEY_OPACITY = "overlay_opacity"
        private const val KEY_SCALE = "overlay_scale"
        private const val KEY_OVERLAY_STYLE = "overlay_style"
        private const val KEY_OVERLAY_CONTENT_MODE = "overlay_content_mode"
        private const val KEY_OVERLAY_LOCKED = "overlay_position_locked"
        private const val KEY_SHOW_WATTS = "overlay_show_watts"
        private const val KEY_AUTO_RECORD = "auto_start_recording"
        private const val KEY_AUTO_FINISH_MIN = "auto_finish_idle_minutes"
        private const val KEY_GOAL_SOUND = "goal_sound_enabled"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect_on_start"
        private const val KEY_AUTO_SHOW_OVERLAY = "auto_show_overlay_on_connect"
        private const val KEY_WEEKLY_GOAL_METERS = "weekly_goal_meters"
        private const val KEY_HEALTH_CONNECT_SYNC = "health_connect_sync_enabled"
        private const val KEY_LAST_ADDR = "last_device_address"
        private const val KEY_LAST_NAME = "last_device_name"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
    }
}
