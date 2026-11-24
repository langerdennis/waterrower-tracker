package com.waterrower.tracker.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OverlaySettings(
    val opacity: Float = 0.85f,
    val scale: Float = 1.0f,
    val showWattsInMini: Boolean = false,
    val autoStartRecording: Boolean = true,
    val autoFinishIdleMinutes: Int = AppPreferences.DEFAULT_AUTO_FINISH_MINUTES,
    val goalSoundEnabled: Boolean = true,
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val overlayX: Int = AppPreferences.DEFAULT_OVERLAY_X,
    val overlayY: Int = AppPreferences.DEFAULT_OVERLAY_Y,
)

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("waterrower_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<OverlaySettings> = _settings.asStateFlow()

    private fun load(): OverlaySettings = OverlaySettings(
        opacity = prefs.getFloat(KEY_OPACITY, 0.85f).coerceIn(0.35f, 1.0f),
        scale = prefs.getFloat(KEY_SCALE, 1.0f).coerceIn(0.8f, 1.4f),
        showWattsInMini = prefs.getBoolean(KEY_SHOW_WATTS, false),
        autoStartRecording = prefs.getBoolean(KEY_AUTO_RECORD, true),
        autoFinishIdleMinutes = prefs.getInt(KEY_AUTO_FINISH_MIN, DEFAULT_AUTO_FINISH_MINUTES).coerceIn(0, 30),
        goalSoundEnabled = prefs.getBoolean(KEY_GOAL_SOUND, true),
        lastDeviceAddress = prefs.getString(KEY_LAST_ADDR, null),
        lastDeviceName = prefs.getString(KEY_LAST_NAME, null),
        overlayX = prefs.getInt(KEY_OVERLAY_X, DEFAULT_OVERLAY_X),
        overlayY = prefs.getInt(KEY_OVERLAY_Y, DEFAULT_OVERLAY_Y),
    )

    fun setOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(0.35f, 1.0f)
        prefs.edit().putFloat(KEY_OPACITY, clamped).apply()
        _settings.value = _settings.value.copy(opacity = clamped)
    }

    fun setScale(scale: Float) {
        val clamped = scale.coerceIn(0.8f, 1.4f)
        prefs.edit().putFloat(KEY_SCALE, clamped).apply()
        _settings.value = _settings.value.copy(scale = clamped)
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

        private const val KEY_OPACITY = "overlay_opacity"
        private const val KEY_SCALE = "overlay_scale"
        private const val KEY_SHOW_WATTS = "overlay_show_watts"
        private const val KEY_AUTO_RECORD = "auto_start_recording"
        private const val KEY_AUTO_FINISH_MIN = "auto_finish_idle_minutes"
        private const val KEY_GOAL_SOUND = "goal_sound_enabled"
        private const val KEY_LAST_ADDR = "last_device_address"
        private const val KEY_LAST_NAME = "last_device_name"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
    }
}
