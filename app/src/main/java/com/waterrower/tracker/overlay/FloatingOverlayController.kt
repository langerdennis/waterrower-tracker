package com.waterrower.tracker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.waterrower.tracker.data.AppPreferences
import com.waterrower.tracker.data.OverlaySettings
import com.waterrower.tracker.data.RecordingState
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.model.RowingTelemetry
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lightweight, crash-proof native WindowManager overlay designed to float over
 * fullscreen video apps like Nuvio on Pixel Tablet / Android 17.
 *
 * - Touch events outside the pill pass straight through to Nuvio.
 * - Drag anywhere on screen (position persisted).
 * - Tap to toggle between compact Mini-Pill and Expanded Controls.
 */
class FloatingOverlayController(
    private val context: Context,
    private val preferences: AppPreferences,
    private val onOpenApp: () -> Unit,
    private val onToggleRecording: () -> Unit,
    private val onCloseOverlay: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var statusDot: View? = null
    private var miniLineView: TextView? = null
    private var expandedRow: LinearLayout? = null
    private var extraMetricsView: TextView? = null
    private var recordButton: TextView? = null

    private var isExpanded = false
    private var lastTelemetry = RowingTelemetry()
    private var lastConnection: ConnectionState = ConnectionState.Disconnected
    private var lastRecording: RecordingState = RecordingState.Idle
    private var lastSettings: OverlaySettings = preferences.settings.value

    val isShowing: Boolean get() = rootView != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (rootView != null) return true

        val settings = preferences.settings.value
        lastSettings = settings

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.overlayX
            y = settings.overlayY
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = createPillBackground(settings.opacity)
            elevation = dp(8).toFloat()
        }

        // Top compact row (always visible)
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = View(context).apply {
            val size = dp(9)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(9)
            }
            background = createDotBackground(Color.parseColor("#64748B"))
        }

        val miniText = TextView(context).apply {
            setTextColor(Color.parseColor("#F8FAFC"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * settings.scale)
            text = "--:-- /500m  •  0 spm  •  0 m  •  00:00"
        }

        topRow.addView(dot)
        topRow.addView(miniText)
        container.addView(topRow)

        // Expandable bottom section (shown on tap)
        val expanded = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }

        val extraMetrics = TextView(context).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * settings.scale)
            setPadding(0, 0, 0, dp(8))
        }

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val recBtn = createActionChip("Start", "#0EA5E9") { onToggleRecording() }
        val appBtn = createActionChip("App", "#334155") { onOpenApp() }
        val closeBtn = createActionChip("Ausblenden", "#7F1D1D") { onCloseOverlay() }

        actionRow.addView(recBtn)
        actionRow.addView(appBtn)
        actionRow.addView(closeBtn)

        expanded.addView(extraMetrics)
        expanded.addView(actionRow)
        container.addView(expanded)

        // Drag & tap handling on the container
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).roundToInt()
                    val dy = (event.rawY - initialTouchY).roundToInt()
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) {
                        moved = true
                    }
                    if (moved) {
                        params.x = (initialX + dx).coerceAtLeast(0)
                        params.y = (initialY + dy).coerceAtLeast(0)
                        try {
                            windowManager.updateViewLayout(container, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        preferences.saveOverlayPosition(params.x, params.y)
                    } else {
                        isExpanded = !isExpanded
                        expanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
                    }
                    true
                }
                else -> false
            }
        }

        return try {
            windowManager.addView(container, params)
            rootView = container
            layoutParams = params
            statusDot = dot
            miniLineView = miniText
            expandedRow = expanded
            extraMetricsView = extraMetrics
            recordButton = recBtn
            render()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hide() {
        val view = rootView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        rootView = null
        layoutParams = null
        statusDot = null
        miniLineView = null
        expandedRow = null
        extraMetricsView = null
        recordButton = null
        isExpanded = false
    }

    fun update(
        telemetry: RowingTelemetry = lastTelemetry,
        connection: ConnectionState = lastConnection,
        recording: RecordingState = lastRecording,
        settings: OverlaySettings = lastSettings,
    ) {
        lastTelemetry = telemetry
        lastConnection = connection
        lastRecording = recording
        lastSettings = settings
        render()
    }

    private fun render() {
        val root = rootView ?: return
        root.background = createPillBackground(lastSettings.opacity)

        miniLineView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * lastSettings.scale)
        extraMetricsView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * lastSettings.scale)

        val dotColor = when {
            lastRecording is RecordingState.Active && !(lastRecording as RecordingState.Active).isPaused ->
                Color.parseColor("#22C55E")
            lastConnection is ConnectionState.Connected ->
                Color.parseColor("#38BDF8")
            lastConnection is ConnectionState.Connecting ->
                Color.parseColor("#F59E0B")
            else ->
                Color.parseColor("#EF4444")
        }
        statusDot?.background = createDotBackground(dotColor)

        val spmText = String.format(Locale.US, "%.0f", lastTelemetry.strokeRateSpm)
        val elapsedToShow = when (val rec = lastRecording) {
            is RecordingState.Active -> RowingTelemetry.formatDuration(rec.elapsedSeconds)
            RecordingState.Idle -> lastTelemetry.formattedElapsed
        }
        val distToShow = when (val rec = lastRecording) {
            is RecordingState.Active -> RowingTelemetry.formatDistance(rec.distanceMeters)
            RecordingState.Idle -> lastTelemetry.formattedDistance
        }

        val wattsSuffix = if (lastSettings.showWattsInMini && lastTelemetry.watts > 0) {
            "  •  ${lastTelemetry.watts} W"
        } else {
            ""
        }

        miniLineView?.text =
            "${lastTelemetry.formattedPace} /500m  •  $spmText spm  •  $distToShow  •  $elapsedToShow$wattsSuffix"

        val hrPart = lastTelemetry.heartRateBpm?.let { "  •  $it bpm" } ?: ""
        extraMetricsView?.text =
            "${lastTelemetry.watts} W  •  ${lastTelemetry.calories} kcal  •  ${lastTelemetry.strokeCount} Züge$hrPart"

        recordButton?.text = when (val rec = lastRecording) {
            is RecordingState.Active -> if (rec.isPaused) "Weiter" else "Stop & Save"
            RecordingState.Idle -> "Aufnahme"
        }
    }

    private fun createActionChip(label: String, hexColor: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(hexColor))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createPillBackground(opacity: Float): GradientDrawable {
        val alphaInt = (opacity.coerceIn(0.35f, 1.0f) * 255).roundToInt()
        val bgColor = Color.argb(alphaInt, 11, 18, 26)
        val borderColor = Color.argb((alphaInt * 0.9f).roundToInt().coerceAtMost(255), 56, 189, 248)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(bgColor)
            setStroke(dp(1), borderColor)
        }
    }

    private fun createDotBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).roundToInt()
}
