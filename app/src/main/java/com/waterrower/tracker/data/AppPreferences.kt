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
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val overlayX: Int = 36,
    val overlayY: Int = 36,
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
        lastDeviceAddress = prefs.getString(KEY_LAST_ADDR, null),
        lastDeviceName = prefs.getString(KEY_LAST_NAME, null),
        overlayX = prefs.getInt(KEY_OVERLAY_X, 36),
        overlayY = prefs.getInt(KEY_OVERLAY_Y, 36),
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

    companion object {
        private const val KEY_OPACITY = "overlay_opacity"
        private const val KEY_SCALE = "overlay_scale"
        private const val KEY_SHOW_WATTS = "overlay_show_watts"
        private const val KEY_AUTO_RECORD = "auto_start_recording"
        private const val KEY_LAST_ADDR = "last_device_address"
        private const val KEY_LAST_NAME = "last_device_name"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
    }
}
