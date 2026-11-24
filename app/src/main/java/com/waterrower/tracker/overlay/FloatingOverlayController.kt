package com.waterrower.tracker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
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
 * fullscreen video and streaming apps on Android tablets and phones.
 *
 * - Touch events outside the pill pass straight through to the underlying app.
 * - Drag anywhere on screen (clamped to screen & display cutout bounds, position persisted).
 * - Tap to toggle between compact Mini-Pill and Expanded Controls (Pause/Weiter + Stop & Save).
 */
class FloatingOverlayController(
    private val context: Context,
    private val preferences: AppPreferences,
    private val onStartRecording: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onStopAndSave: () -> Unit,
    private val onOpenApp: () -> Unit,
    private val onCloseOverlay: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var statusDot: View? = null
    private var miniLineView: TextView? = null
    private var expandedRow: LinearLayout? = null
    private var extraMetricsView: TextView? = null
    private var pauseButton: TextView? = null
    private var recordButton: TextView? = null

    private var pillDrawable: GradientDrawable? = null
    private var dotDrawable: GradientDrawable? = null
    private var pauseBtnDrawable: GradientDrawable? = null
    private var recordBtnDrawable: GradientDrawable? = null

    private var appliedOpacity: Float = Float.NaN
    private var appliedScale: Float = Float.NaN
    private var appliedDotColor: Int = 0
    private var lastMiniText: String? = null
    private var lastExtraText: String? = null
    private var lastPauseVisible: Boolean? = null
    private var lastPauseText: String? = null
    private var lastPauseColor: Int = 0
    private var lastRecordText: String? = null
    private var lastRecordColor: Int = 0
    private var lastAppliedSettingsX: Int = Int.MIN_VALUE
    private var lastAppliedSettingsY: Int = Int.MIN_VALUE

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
        resetRenderCache()

        val pillBg = createPillBackground(settings.opacity)
        val dotBg = createDotBackground(COLOR_DISCONNECTED)
        val pauseBg = createChipDrawable(COLOR_AMBER)
        val recordBg = createChipDrawable(COLOR_SKY)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = pillBg
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
            background = dotBg
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

        val pauseBtn = createActionChip("Pause", pauseBg) {
            if (lastRecording is RecordingState.Active) {
                onTogglePause()
            }
        }.apply {
            visibility = View.GONE
        }
        val recBtn = createActionChip("Aufnahme", recordBg) {
            if (lastRecording is RecordingState.Active) {
                onStopAndSave()
            } else {
                onStartRecording()
            }
        }
        val appBtn = createActionChip("App", createChipDrawable(COLOR_SLATE_CHIP)) { onOpenApp() }
        val closeBtn = createActionChip("Ausblenden", createChipDrawable(COLOR_CLOSE_CHIP)) { onCloseOverlay() }

        actionRow.addView(pauseBtn)
        actionRow.addView(recBtn)
        actionRow.addView(appBtn)
        actionRow.addView(closeBtn)

        expanded.addView(extraMetrics)
        expanded.addView(actionRow)
        container.addView(expanded)

        // Pre-measure so initial position is clamped accurately before addView()
        container.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val (clampedX, clampedY) = clampPosition(settings.overlayX, settings.overlayY, container)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = clampedX
            y = clampedY
        }

        // Re-clamp automatically when layout size changes (initial layout, tap-to-expand, scale, longer text)
        container.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val lp = layoutParams ?: return@addOnLayoutChangeListener
            val (nx, ny) = clampPosition(lp.x, lp.y, view)
            if (nx != lp.x || ny != lp.y) {
                lp.x = nx
                lp.y = ny
                try {
                    windowManager.updateViewLayout(view, lp)
                } catch (_: Exception) {
                }
            }
        }

        // Drag & tap handling on the container
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        container.setOnTouchListener { view, event ->
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
                        val (nx, ny) = clampPosition(initialX + dx, initialY + dy, view)
                        if (nx != params.x || ny != params.y) {
                            params.x = nx
                            params.y = ny
                            try {
                                windowManager.updateViewLayout(container, params)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        lastAppliedSettingsX = params.x
                        lastAppliedSettingsY = params.y
                        preferences.saveOverlayPosition(params.x, params.y)
                    } else {
                        isExpanded = !isExpanded
                        expanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
                        render()
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
            pauseButton = pauseBtn
            recordButton = recBtn
            pillDrawable = pillBg
            dotDrawable = dotBg
            pauseBtnDrawable = pauseBg
            recordBtnDrawable = recordBg
            appliedOpacity = settings.opacity
            appliedScale = settings.scale
            appliedDotColor = COLOR_DISCONNECTED
            lastAppliedSettingsX = settings.overlayX
            lastAppliedSettingsY = settings.overlayY
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
        pauseButton = null
        recordButton = null
        pillDrawable = null
        dotDrawable = null
        pauseBtnDrawable = null
        recordBtnDrawable = null
        isExpanded = false
        resetRenderCache()
    }

    fun onConfigurationChanged() {
        val view = rootView ?: return
        val params = layoutParams ?: return
        val (nx, ny) = clampPosition(params.x, params.y, view)
        if (nx != params.x || ny != params.y) {
            params.x = nx
            params.y = ny
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
    }

    fun resetPosition() {
        val view = rootView ?: return
        val params = layoutParams ?: return
        val (nx, ny) = clampPosition(
            AppPreferences.DEFAULT_OVERLAY_X,
            AppPreferences.DEFAULT_OVERLAY_Y,
            view,
        )
        params.x = nx
        params.y = ny
        lastAppliedSettingsX = AppPreferences.DEFAULT_OVERLAY_X
        lastAppliedSettingsY = AppPreferences.DEFAULT_OVERLAY_Y
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
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
        val params = layoutParams

        // Sync external position preference updates (e.g. Reset Position from Settings)
        if (params != null &&
            (lastSettings.overlayX != lastAppliedSettingsX || lastSettings.overlayY != lastAppliedSettingsY)
        ) {
            lastAppliedSettingsX = lastSettings.overlayX
            lastAppliedSettingsY = lastSettings.overlayY
            val (nx, ny) = clampPosition(lastSettings.overlayX, lastSettings.overlayY, root)
            if (nx != params.x || ny != params.y) {
                params.x = nx
                params.y = ny
                try {
                    windowManager.updateViewLayout(root, params)
                } catch (_: Exception) {
                }
            }
        }

        if (lastSettings.opacity != appliedOpacity) {
            updatePillDrawable(pillDrawable, lastSettings.opacity)
            appliedOpacity = lastSettings.opacity
        }

        if (lastSettings.scale != appliedScale) {
            miniLineView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * lastSettings.scale)
            extraMetricsView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * lastSettings.scale)
            appliedScale = lastSettings.scale
        }

        val rec = lastRecording
        val dotColor = when {
            rec is RecordingState.Active && !rec.isPaused -> COLOR_ACTIVE_GREEN
            rec is RecordingState.Active && rec.isPaused -> COLOR_AMBER
            lastConnection is ConnectionState.Connected -> COLOR_CONNECTED_SKY
            lastConnection is ConnectionState.Connecting -> COLOR_AMBER
            else -> COLOR_DISCONNECTED
        }
        if (dotColor != appliedDotColor) {
            dotDrawable?.setColor(dotColor)
            appliedDotColor = dotColor
        }

        val spmText = String.format(Locale.US, "%.0f", lastTelemetry.strokeRateSpm)
        val elapsedToShow = when (rec) {
            is RecordingState.Active -> RowingTelemetry.formatDuration(rec.elapsedSeconds)
            RecordingState.Idle -> lastTelemetry.formattedElapsed
        }
        val distToShow = when (rec) {
            is RecordingState.Active -> RowingTelemetry.formatDistance(rec.distanceMeters)
            RecordingState.Idle -> lastTelemetry.formattedDistance
        }
        val wattsSuffix = if (lastSettings.showWattsInMini && lastTelemetry.watts > 0) {
            "  •  ${lastTelemetry.watts} W"
        } else {
            ""
        }
        val miniText =
            "${lastTelemetry.formattedPace} /500m  •  $spmText spm  •  $distToShow  •  $elapsedToShow$wattsSuffix"
        if (miniText != lastMiniText) {
            miniLineView?.text = miniText
            lastMiniText = miniText
        }

        if (isExpanded) {
            val caloriesToShow = when (rec) {
                is RecordingState.Active -> rec.calories
                RecordingState.Idle -> lastTelemetry.calories
            }
            val strokesToShow = when (rec) {
                is RecordingState.Active -> rec.strokeCount
                RecordingState.Idle -> lastTelemetry.strokeCount
            }
            val hrPart = lastTelemetry.heartRateBpm?.let { "  •  $it bpm" } ?: ""
            val extraText = "${lastTelemetry.watts} W  •  $caloriesToShow kcal  •  $strokesToShow Züge$hrPart"
            if (extraText != lastExtraText) {
                extraMetricsView?.text = extraText
                lastExtraText = extraText
            }
        }

        when (rec) {
            is RecordingState.Active -> {
                if (lastPauseVisible != true) {
                    pauseButton?.visibility = View.VISIBLE
                    lastPauseVisible = true
                }
                val pauseLabel = if (rec.isPaused) "Weiter" else "Pause"
                val pauseColor = if (rec.isPaused) COLOR_SKY else COLOR_AMBER
                if (pauseLabel != lastPauseText) {
                    pauseButton?.text = pauseLabel
                    lastPauseText = pauseLabel
                }
                if (pauseColor != lastPauseColor) {
                    pauseBtnDrawable?.setColor(pauseColor)
                    lastPauseColor = pauseColor
                }

                val recLabel = "Stop & Save"
                val recColor = COLOR_SAVE_GREEN
                if (recLabel != lastRecordText) {
                    recordButton?.text = recLabel
                    lastRecordText = recLabel
                }
                if (recColor != lastRecordColor) {
                    recordBtnDrawable?.setColor(recColor)
                    lastRecordColor = recColor
                }
            }
            RecordingState.Idle -> {
                if (lastPauseVisible != false) {
                    pauseButton?.visibility = View.GONE
                    lastPauseVisible = false
                }
                val recLabel = "Aufnahme"
                val recColor = COLOR_SKY
                if (recLabel != lastRecordText) {
                    recordButton?.text = recLabel
                    lastRecordText = recLabel
                }
                if (recColor != lastRecordColor) {
                    recordBtnDrawable?.setColor(recColor)
                    lastRecordColor = recColor
                }
            }
        }
    }

    private fun clampPosition(rawX: Int, rawY: Int, view: View): Pair<Int, Int> {
        val viewW = if (view.width > 0) view.width else view.measuredWidth
        val viewH = if (view.height > 0) view.height else view.measuredHeight
        val (minX, minY, maxX, maxY) = currentSafeDisplayBounds()
        val clampedX = rawX.coerceIn(minX, (maxX - viewW).coerceAtLeast(minX))
        val clampedY = rawY.coerceIn(minY, (maxY - viewH).coerceAtLeast(minY))
        return clampedX to clampedY
    }

    private data class DisplayBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private fun currentSafeDisplayBounds(): DisplayBounds {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val cutout = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.displayCutout(),
            )
            DisplayBounds(
                left = cutout.left,
                top = cutout.top,
                right = bounds.width() - cutout.right,
                bottom = bounds.height() - cutout.bottom,
            )
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            val cutout = rootView?.rootWindowInsets?.displayCutout
            val insetLeft = cutout?.safeInsetLeft ?: 0
            val insetTop = cutout?.safeInsetTop ?: 0
            val insetRight = cutout?.safeInsetRight ?: 0
            val insetBottom = cutout?.safeInsetBottom ?: 0
            DisplayBounds(
                left = insetLeft,
                top = insetTop,
                right = (dm.widthPixels - insetRight).coerceAtLeast(insetLeft),
                bottom = (dm.heightPixels - insetBottom).coerceAtLeast(insetTop),
            )
        }
    }

    private fun resetRenderCache() {
        appliedOpacity = Float.NaN
        appliedScale = Float.NaN
        appliedDotColor = 0
        lastMiniText = null
        lastExtraText = null
        lastPauseVisible = null
        lastPauseText = null
        lastPauseColor = 0
        lastRecordText = null
        lastRecordColor = 0
        lastAppliedSettingsX = Int.MIN_VALUE
        lastAppliedSettingsY = Int.MIN_VALUE
    }

    private fun createActionChip(
        label: String,
        bgDrawable: GradientDrawable,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = bgDrawable
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createChipDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(color)
        }

    private fun createPillBackground(opacity: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            updatePillDrawable(this, opacity)
        }

    private fun updatePillDrawable(drawable: GradientDrawable?, opacity: Float) {
        if (drawable == null) return
        val alphaInt = (opacity.coerceIn(0.35f, 1.0f) * 255).roundToInt()
        val bgColor = Color.argb(alphaInt, 11, 18, 26)
        val borderColor = Color.argb((alphaInt * 0.9f).roundToInt().coerceAtMost(255), 56, 189, 248)
        drawable.setColor(bgColor)
        drawable.setStroke(dp(1), borderColor)
    }

    private fun createDotBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).roundToInt()

    companion object {
        private val COLOR_ACTIVE_GREEN = Color.parseColor("#22C55E")
        private val COLOR_SAVE_GREEN = Color.parseColor("#16A34A")
        private val COLOR_CONNECTED_SKY = Color.parseColor("#38BDF8")
        private val COLOR_SKY = Color.parseColor("#0EA5E9")
        private val COLOR_AMBER = Color.parseColor("#F59E0B")
        private val COLOR_DISCONNECTED = Color.parseColor("#EF4444")
        private val COLOR_SLATE_CHIP = Color.parseColor("#334155")
        private val COLOR_CLOSE_CHIP = Color.parseColor("#7F1D1D")
    }
}
