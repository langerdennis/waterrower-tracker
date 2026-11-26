package com.waterrower.tracker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
import com.waterrower.tracker.data.OverlayContentMode
import com.waterrower.tracker.data.OverlaySettings
import com.waterrower.tracker.data.OverlayStyle
import com.waterrower.tracker.data.RecordingState
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.model.GhostDelta
import com.waterrower.tracker.model.PaceZone
import com.waterrower.tracker.model.ProgramProgressState
import com.waterrower.tracker.model.ProgramStep
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.model.WorkoutGoal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lightweight, crash-proof native WindowManager overlay designed to float over
 * fullscreen video and streaming apps on Android tablets and phones.
 *
 * Supports 4 content modes tailored around watching series while rowing:
 * - [OverlayContentMode.SMART]: S4-exclusive context only (ETA, projection, pace trend vs. avg, live weekly %, programs).
 * - [OverlayContentMode.CINEMA_TOAST]: Tiny unobtrusive status dot that pops open for 6s at 500m splits & program/goal events.
 * - [OverlayContentMode.PROGRESS_BAR]: Visual filling progress bar or live 2-marker Ghost Race track with micro-caption.
 * - [OverlayContentMode.TELEMETRY]: Classic full S4 telemetry (great in dark rooms without S4 backlight).
 */
class FloatingOverlayController(
    private val context: Context,
    private val preferences: AppPreferences,
    private val onStartRecording: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onStopAndSave: () -> Unit,
    private val onAdvanceProgramStep: () -> Unit,
    private val onOpenApp: () -> Unit,
    private val onCloseOverlay: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var statusDot: View? = null
    private var miniLineView: TextView? = null
    private var visualBarView: VisualBarView? = null
    private var barCaptionView: TextView? = null
    private var progressTrack: LinearLayout? = null
    private var progressFill: View? = null
    private var progressRemainder: View? = null
    private var expandedRow: LinearLayout? = null
    private var extraMetricsView: TextView? = null
    private var nextStepButton: TextView? = null
    private var pauseButton: TextView? = null
    private var recordButton: TextView? = null
    private var modeToggleButton: TextView? = null
    private var lockToggleButton: TextView? = null
    private var styleToggleButton: TextView? = null

    private var pillDrawable: GradientDrawable? = null
    private var dotDrawable: GradientDrawable? = null
    private var progressFillDrawable: GradientDrawable? = null
    private var pauseBtnDrawable: GradientDrawable? = null
    private var recordBtnDrawable: GradientDrawable? = null
    private var modeBtnDrawable: GradientDrawable? = null
    private var lockBtnDrawable: GradientDrawable? = null
    private var styleBtnDrawable: GradientDrawable? = null

    private var appliedOpacity: Float = Float.NaN
    private var appliedBorderColor: Int = 0
    private var appliedStyle: OverlayStyle? = null
    private var appliedExpandedForBg: Boolean? = null
    private var appliedScale: Float = Float.NaN
    private var appliedDotColor: Int = 0
    private var appliedDotMarginEnd: Int = -1
    private var appliedProgressVisible: Boolean? = null
    private var appliedProgressPermille: Int = -1
    private var appliedProgressColor: Int = 0
    private var lastMiniVisible: Boolean? = null
    private var lastMiniKey: String? = null
    private var lastBarModeVisible: Boolean? = null
    private var lastBarCaption: String? = null
    private var lastBarCaptionColor: Int = 0
    private var lastExtraText: String? = null
    private var lastNextStepVisible: Boolean? = null
    private var lastPauseVisible: Boolean? = null
    private var lastPauseText: String? = null
    private var lastPauseColor: Int = 0
    private var lastRecordText: String? = null
    private var lastRecordColor: Int = 0
    private var lastModeButtonMode: OverlayContentMode? = null
    private var lastLockedMode: Boolean? = null
    private var lastStyleButtonStyle: OverlayStyle? = null
    private var lastAppliedSettingsX: Int = Int.MIN_VALUE
    private var lastAppliedSettingsY: Int = Int.MIN_VALUE

    // Idle auto-dimming state
    private var idleSinceElapsedMs: Long? = null
    private var isAutoDimmed: Boolean = false
    private var autoDimScheduled: Boolean = false
    private val autoDimRunnable = Runnable {
        autoDimScheduled = false
        val root = rootView ?: return@Runnable
        val inActiveOffRowerOrRest = lastProgram != null && !lastProgram!!.isCompleted &&
            (lastProgram!!.currentStep is ProgramStep.TimedRest ||
                lastProgram!!.currentStep is ProgramStep.OffRowerStation)
        if (!isExpanded && lastTelemetry.strokeRateSpm <= 0f && !inActiveOffRowerOrRest) {
            setAutoDimmed(root, true)
        }
    }

    // Cinema Auto-Hide toast & 500m split milestone state
    private var trackedWorkoutId: String? = null
    private var lastSplitMilestoneMeters: Int = 0
    private var lastSplitElapsedSec: Int = 0
    private var lastProgramStepKey: String? = null
    private var activeToastText: String? = null
    private val clearToastRunnable = Runnable {
        activeToastText = null
        render()
    }

    private var isExpanded = false
    private var lastTelemetry = RowingTelemetry()
    private var lastConnection: ConnectionState = ConnectionState.Disconnected
    private var lastRecording: RecordingState = RecordingState.Idle
    private var lastSettings: OverlaySettings = preferences.settings.value
    private var lastGoal: WorkoutGoal = WorkoutGoal()
    private var lastGhostDelta: GhostDelta? = null
    private var lastGhostTotalMeters: Int? = null
    private var lastProgram: ProgramProgressState? = null
    private var lastBatteryPercent: Int? = null
    private var lastSavedWeeklyDistanceMeters: Int = 0

    val isShowing: Boolean get() = rootView != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (rootView != null) return true

        val settings = preferences.settings.value
        lastSettings = settings
        resetRenderCache()

        val pillBg = createPillBackground(settings.opacity, COLOR_CONNECTED_SKY, settings.overlayStyle, false)
        val dotBg = createDotBackground(COLOR_DISCONNECTED)
        val fillBg = createProgressBarDrawable(COLOR_CONNECTED_SKY)
        val trackBg = createProgressBarDrawable(Color.argb(70, 148, 163, 184))
        val pauseBg = createChipDrawable(COLOR_AMBER)
        val recordBg = createChipDrawable(COLOR_SKY)
        val modeBg = createChipDrawable(COLOR_SKY)
        val lockBg = createChipDrawable(COLOR_SLATE_CHIP)
        val styleBg = createChipDrawable(COLOR_SLATE_CHIP)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = pillBg
            elevation = if (settings.overlayStyle == OverlayStyle.STANDARD) dp(8).toFloat() else 0f
        }

        // Top compact row (dot + text OR visual bar + micro-caption)
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
            text = "Bereit zum Rudern"
        }

        val barView = VisualBarView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(190), dp(16)).apply {
                marginEnd = dp(8)
            }
            visibility = View.GONE
        }

        val barCaption = TextView(context).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * settings.scale)
            visibility = View.GONE
        }

        topRow.addView(dot)
        topRow.addView(miniText)
        topRow.addView(barView)
        topRow.addView(barCaption)
        container.addView(topRow)

        // Slim secondary progress bar (used in Smart / Telemetry / Cinema Toast modes)
        val fillView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f)
            background = fillBg
        }
        val remView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = trackBg
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3),
            ).apply {
                topMargin = dp(6)
            }
            addView(fillView)
            addView(remView)
        }
        container.addView(track)

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

        val nextBtn = createActionChip("Nächster", createChipDrawable(COLOR_VIOLET)) {
            onAdvanceProgramStep()
        }.apply {
            visibility = View.GONE
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

        actionRow.addView(nextBtn)
        actionRow.addView(pauseBtn)
        actionRow.addView(recBtn)
        actionRow.addView(appBtn)
        actionRow.addView(closeBtn)

        // Quick-toggle row for Mode, Style & Position Lock (usable mid-episode)
        val quickToggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val modeBtn = createActionChip("Modus: Smart", modeBg) {
            val all = OverlayContentMode.entries
            val next = all[(all.indexOf(lastSettings.overlayContentMode) + 1) % all.size]
            preferences.setOverlayContentMode(next)
        }
        val styleBtn = createActionChip("Stil: Standard", styleBg) {
            val all = OverlayStyle.entries
            val next = all[(all.indexOf(lastSettings.overlayStyle) + 1) % all.size]
            preferences.setOverlayStyle(next)
        }
        val lockBtn = createActionChip("Pos: Frei", lockBg) {
            preferences.setOverlayPositionLocked(!lastSettings.overlayPositionLocked)
        }
        quickToggleRow.addView(modeBtn)
        quickToggleRow.addView(styleBtn)
        quickToggleRow.addView(lockBtn)

        expanded.addView(extraMetrics)
        expanded.addView(actionRow)
        expanded.addView(quickToggleRow)
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

        // Re-clamp automatically when layout size changes
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
                    mainHandler.removeCallbacks(autoDimRunnable)
                    autoDimScheduled = false
                    idleSinceElapsedMs = null
                    setAutoDimmed(container, false)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!lastSettings.overlayPositionLocked) {
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
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved && !lastSettings.overlayPositionLocked) {
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
            visualBarView = barView
            barCaptionView = barCaption
            progressTrack = track
            progressFill = fillView
            progressRemainder = remView
            expandedRow = expanded
            extraMetricsView = extraMetrics
            nextStepButton = nextBtn
            pauseButton = pauseBtn
            recordButton = recBtn
            modeToggleButton = modeBtn
            lockToggleButton = lockBtn
            styleToggleButton = styleBtn
            pillDrawable = pillBg
            dotDrawable = dotBg
            progressFillDrawable = fillBg
            pauseBtnDrawable = pauseBg
            recordBtnDrawable = recordBg
            modeBtnDrawable = modeBg
            lockBtnDrawable = lockBg
            styleBtnDrawable = styleBg
            lastAppliedSettingsX = settings.overlayX
            lastAppliedSettingsY = settings.overlayY
            render()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hide() {
        mainHandler.removeCallbacks(autoDimRunnable)
        mainHandler.removeCallbacks(clearToastRunnable)
        autoDimScheduled = false
        activeToastText = null
        val view = rootView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        rootView = null
        layoutParams = null
        statusDot = null
        miniLineView = null
        visualBarView = null
        barCaptionView = null
        progressTrack = null
        progressFill = null
        progressRemainder = null
        expandedRow = null
        extraMetricsView = null
        nextStepButton = null
        pauseButton = null
        recordButton = null
        modeToggleButton = null
        lockToggleButton = null
        styleToggleButton = null
        pillDrawable = null
        dotDrawable = null
        progressFillDrawable = null
        pauseBtnDrawable = null
        recordBtnDrawable = null
        modeBtnDrawable = null
        lockBtnDrawable = null
        styleBtnDrawable = null
        isExpanded = false
        idleSinceElapsedMs = null
        isAutoDimmed = false
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
        goal: WorkoutGoal = lastGoal,
        ghostDelta: GhostDelta? = lastGhostDelta,
        ghostTotalMeters: Int? = lastGhostTotalMeters,
        program: ProgramProgressState? = lastProgram,
        batteryPercent: Int? = lastBatteryPercent,
        savedWeeklyDistanceMeters: Int = lastSavedWeeklyDistanceMeters,
    ) {
        lastTelemetry = telemetry
        lastConnection = connection
        lastRecording = recording
        lastSettings = settings
        lastGoal = goal
        lastGhostDelta = ghostDelta
        lastGhostTotalMeters = ghostTotalMeters
        lastProgram = program
        lastBatteryPercent = batteryPercent
        lastSavedWeeklyDistanceMeters = savedWeeklyDistanceMeters
        trackMilestonesAndToasts()
        render()
    }

    private fun trackMilestonesAndToasts() {
        val rec = lastRecording as? RecordingState.Active
        if (rec == null) {
            trackedWorkoutId = null
            lastSplitMilestoneMeters = 0
            lastSplitElapsedSec = 0
        } else {
            if (trackedWorkoutId != rec.workoutId) {
                trackedWorkoutId = rec.workoutId
                lastSplitMilestoneMeters = (rec.distanceMeters / SPLIT_MILESTONE_METERS) * SPLIT_MILESTONE_METERS
                lastSplitElapsedSec = rec.elapsedSeconds
            }
            val nextMilestone = lastSplitMilestoneMeters + SPLIT_MILESTONE_METERS
            if (rec.distanceMeters >= nextMilestone) {
                val achievedMilestone = (rec.distanceMeters / SPLIT_MILESTONE_METERS) * SPLIT_MILESTONE_METERS
                val splitDuration = (rec.elapsedSeconds - lastSplitElapsedSec).coerceAtLeast(1)
                val splitsCrossed = ((achievedMilestone - lastSplitMilestoneMeters) / SPLIT_MILESTONE_METERS).coerceAtLeast(1)
                val per500Sec = (splitDuration.toDouble() / splitsCrossed.toDouble()).roundToInt()
                val sessionAvg = OverlayContentFormatter.sessionAvgPaceSec(rec.distanceMeters, rec.elapsedSeconds)
                lastSplitMilestoneMeters = achievedMilestone
                lastSplitElapsedSec = rec.elapsedSeconds
                if (lastSettings.overlayContentMode == OverlayContentMode.CINEMA_TOAST) {
                    triggerToast(
                        OverlayContentFormatter.formatSplitMilestoneToast(
                            milestoneMeters = achievedMilestone,
                            splitDurationSec = per500Sec,
                            sessionAvgPaceSec = sessionAvg,
                        ),
                    )
                }
            }
        }

        val progKey = lastProgram?.let { "${it.plan.preset.id}:${it.stepIndex}:${it.isCompleted}" }
        if (progKey != null && progKey != lastProgramStepKey && lastProgramStepKey != null) {
            if (lastSettings.overlayContentMode == OverlayContentMode.CINEMA_TOAST) {
                val p = lastProgram!!
                val msg = when {
                    p.isCompleted -> "✓ ${p.plan.titleWithScale} geschafft!"
                    p.currentStep is ProgramStep.RowingDistance ->
                        "R${p.currentStep.roundIndex}/${p.currentStep.totalRounds}: ${RowingTelemetry.formatDistance(p.currentStep.targetMeters)} rudern"
                    else -> null
                }
                if (msg != null) triggerToast(msg)
            }
        }
        lastProgramStepKey = progKey
    }

    private fun triggerToast(message: String) {
        activeToastText = message
        mainHandler.removeCallbacks(clearToastRunnable)
        mainHandler.postDelayed(clearToastRunnable, CINEMA_TOAST_DURATION_MS)
        // Wake overlay immediately for the toast
        rootView?.let { setAutoDimmed(it, false) }
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

        val rec = lastRecording
        val isRecActive = rec is RecordingState.Active
        val elapsedSec = when (rec) {
            is RecordingState.Active -> rec.elapsedSeconds
            RecordingState.Idle -> lastTelemetry.elapsedSeconds
        }
        val distMeters = when (rec) {
            is RecordingState.Active -> rec.distanceMeters
            RecordingState.Idle -> lastTelemetry.distanceMeters
        }

        val style = lastSettings.overlayStyle
        val contentMode = lastSettings.overlayContentMode
        val program = lastProgram

        // Low-battery alert for the WaterRower S4 ComModule (< 20%)
        val lowBatteryPct = lastBatteryPercent?.takeIf {
            lastConnection is ConnectionState.Connected && it in 1 until LOW_BATTERY_THRESHOLD_PERCENT
        }
        val lowBatColor = if (lowBatteryPct != null && lowBatteryPct <= 10) COLOR_DISCONNECTED else COLOR_AMBER

        // Gentle auto-dimming when flywheel is idle > 15s (never dims during rest/KB, active toast, or expanded)
        val inActiveOffRowerOrRest = program != null && !program.isCompleted &&
            (program.currentStep is ProgramStep.TimedRest || program.currentStep is ProgramStep.OffRowerStation)
        val shouldStayBright = isExpanded ||
            lastTelemetry.strokeRateSpm > 0f ||
            inActiveOffRowerOrRest ||
            activeToastText != null
        val nowElapsed = SystemClock.elapsedRealtime()
        if (shouldStayBright) {
            if (autoDimScheduled) {
                mainHandler.removeCallbacks(autoDimRunnable)
                autoDimScheduled = false
            }
            idleSinceElapsedMs = null
            setAutoDimmed(root, false)
        } else {
            val startedIdle = idleSinceElapsedMs ?: nowElapsed.also { idleSinceElapsedMs = it }
            val elapsedIdle = nowElapsed - startedIdle
            if (elapsedIdle >= IDLE_AUTO_DIM_MS) {
                if (autoDimScheduled) {
                    mainHandler.removeCallbacks(autoDimRunnable)
                    autoDimScheduled = false
                }
                setAutoDimmed(root, true)
            } else if (!autoDimScheduled) {
                autoDimScheduled = true
                mainHandler.postDelayed(autoDimRunnable, IDLE_AUTO_DIM_MS - elapsedIdle)
            }
        }

        val paceZone = lastGoal.evaluatePaceZone(lastTelemetry.paceSecondsPer500m)
        val borderBaseColor = when {
            program?.isCompleted == true -> COLOR_ACTIVE_GREEN
            program?.currentStep is ProgramStep.OffRowerStation -> COLOR_VIOLET
            program?.currentStep is ProgramStep.TimedRest -> COLOR_AMBER
            lastGoal.isCompleted(distMeters, elapsedSec) -> COLOR_ACTIVE_GREEN
            paceZone == PaceZone.ON_TARGET -> COLOR_ACTIVE_GREEN
            paceZone == PaceZone.SLOWER -> COLOR_AMBER
            paceZone == PaceZone.FASTER -> COLOR_CONNECTED_SKY
            else -> COLOR_CONNECTED_SKY
        }

        if (lastSettings.opacity != appliedOpacity ||
            borderBaseColor != appliedBorderColor ||
            style != appliedStyle ||
            isExpanded != appliedExpandedForBg
        ) {
            updatePillDrawable(pillDrawable, lastSettings.opacity, borderBaseColor, style, isExpanded)
            applyTextStyle(style, lastSettings.opacity)
            root.elevation = when {
                style == OverlayStyle.TRANSPARENT && !isExpanded -> 0f
                style == OverlayStyle.CINEMA -> dp(2).toFloat()
                else -> dp(8).toFloat()
            }
            appliedOpacity = lastSettings.opacity
            appliedBorderColor = borderBaseColor
            appliedStyle = style
            appliedExpandedForBg = isExpanded
        }

        if (lastSettings.scale != appliedScale) {
            miniLineView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * lastSettings.scale)
            barCaptionView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * lastSettings.scale)
            extraMetricsView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * lastSettings.scale)
            appliedScale = lastSettings.scale
        }

        val rawDotColor = when {
            program?.currentStep is ProgramStep.OffRowerStation && !program.isCompleted -> COLOR_VIOLET
            program?.currentStep is ProgramStep.TimedRest && !program.isCompleted -> COLOR_AMBER
            rec is RecordingState.Active && !rec.isPaused -> COLOR_ACTIVE_GREEN
            rec is RecordingState.Active && rec.isPaused -> COLOR_AMBER
            lastConnection is ConnectionState.Connected -> COLOR_CONNECTED_SKY
            lastConnection is ConnectionState.Connecting -> COLOR_AMBER
            else -> COLOR_DISCONNECTED
        }
        val dotColor = if (style == OverlayStyle.STANDARD) {
            rawDotColor
        } else {
            val dotAlpha = (lastSettings.opacity.coerceIn(0.35f, 1.0f) * 185f).roundToInt().coerceIn(70, 210)
            Color.argb(dotAlpha, Color.red(rawDotColor), Color.green(rawDotColor), Color.blue(rawDotColor))
        }
        if (dotColor != appliedDotColor) {
            dotDrawable?.setColor(dotColor)
            appliedDotColor = dotColor
        }

        val isBarMode = contentMode == OverlayContentMode.PROGRESS_BAR
        if (lastBarModeVisible != isBarMode) {
            visualBarView?.visibility = if (isBarMode) View.VISIBLE else View.GONE
            barCaptionView?.visibility = if (isBarMode) View.VISIBLE else View.GONE
            lastBarModeVisible = isBarMode
        }

        val distToShow = RowingTelemetry.formatDistance(distMeters)
        val elapsedToShow = RowingTelemetry.formatDuration(elapsedSec)

        if (isBarMode) {
            // Visual Progress / Ghost Race Bar Mode
            if (lastMiniVisible != false) {
                miniLineView?.visibility = View.GONE
                lastMiniVisible = false
            }
            setDotMarginEnd(dp(9))

            val barModel = OverlayContentFormatter.computeBarModel(
                currentPaceSec = lastTelemetry.paceSecondsPer500m,
                distMeters = distMeters,
                elapsedSec = elapsedSec,
                goal = lastGoal,
                program = program,
                ghostDelta = lastGhostDelta,
                ghostTotalMeters = lastGhostTotalMeters,
                weeklyGoalMeters = lastSettings.weeklyGoalMeters,
                savedWeeklyDistanceMeters = lastSavedWeeklyDistanceMeters,
                isRecordingActive = isRecActive,
            )

            val fillColor = when {
                barModel.isCompleted -> COLOR_ACTIVE_GREEN
                program?.currentStep is ProgramStep.OffRowerStation -> COLOR_VIOLET
                program?.currentStep is ProgramStep.TimedRest -> COLOR_AMBER
                barModel.isGhostRace && (lastGhostDelta?.deltaMeters ?: 0) > 0 -> COLOR_ACTIVE_GREEN
                barModel.isGhostRace && (lastGhostDelta?.deltaMeters ?: 0) < 0 -> COLOR_AMBER
                else -> COLOR_CONNECTED_SKY
            }
            val effectiveAlpha = if (style == OverlayStyle.STANDARD) 255 else 195
            visualBarView?.updateBar(
                userFraction = barModel.userFraction,
                ghostFraction = barModel.ghostFraction,
                fillColor = Color.argb(effectiveAlpha, Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor)),
                ghostMarkerColor = Color.argb(effectiveAlpha, Color.red(COLOR_AMBER), Color.green(COLOR_AMBER), Color.blue(COLOR_AMBER)),
            )

            val caption = if (lowBatteryPct != null) {
                "${barModel.caption}  •  🔋 $lowBatteryPct%"
            } else {
                barModel.caption
            }
            val captionColor = when {
                lowBatteryPct != null -> lowBatColor
                barModel.isCompleted -> COLOR_ACTIVE_GREEN
                barModel.isGhostRace && (lastGhostDelta?.deltaMeters ?: 0) > 0 -> COLOR_ACTIVE_GREEN
                barModel.isGhostRace && (lastGhostDelta?.deltaMeters ?: 0) < 0 -> COLOR_AMBER
                style == OverlayStyle.CINEMA -> Color.parseColor("#CBD5E1")
                else -> Color.parseColor("#F8FAFC")
            }
            if (caption != lastBarCaption) {
                barCaptionView?.text = caption
                lastBarCaption = caption
            }
            if (captionColor != lastBarCaptionColor) {
                barCaptionView?.setTextColor(captionColor)
                lastBarCaptionColor = captionColor
            }
            if (appliedProgressVisible != false) {
                progressTrack?.visibility = View.GONE
                appliedProgressVisible = false
            }
        } else {
            // Text-based modes: SMART, CINEMA_TOAST, or TELEMETRY
            val cinemaText: String? = if (contentMode == OverlayContentMode.CINEMA_TOAST) {
                when {
                    inActiveOffRowerOrRest -> when (val step = program!!.currentStep) {
                        is ProgramStep.TimedRest ->
                            "Pause ${RowingTelemetry.formatDuration(program.restRemainingSeconds)}  •  danach ${step.nextDescription}"
                        is ProgramStep.OffRowerStation -> {
                            val nextHint = step.nextRoundLabel?.let { "Ziehen für $it" } ?: "Fertig → Stop & Save"
                            "R${step.roundIndex}/${step.totalRounds}: ${step.exerciseLabel}  •  $nextHint"
                        }
                        else -> null
                    }
                    activeToastText != null -> activeToastText
                    lowBatteryPct != null -> "🔋 ComModule $lowBatteryPct%"
                    lastConnection is ConnectionState.Error -> "BLE getrennt"
                    isExpanded -> OverlayContentFormatter.formatSmartLine(
                        currentPaceSec = lastTelemetry.paceSecondsPer500m,
                        distMeters = distMeters,
                        elapsedSec = elapsedSec,
                        goal = lastGoal,
                        program = program,
                        weeklyGoalMeters = lastSettings.weeklyGoalMeters,
                        savedWeeklyDistanceMeters = lastSavedWeeklyDistanceMeters,
                        isRecordingActive = isRecActive,
                    )
                    else -> null // Collapsed ultra-minimal dot!
                }
            } else {
                null
            }

            val showMiniText = contentMode != OverlayContentMode.CINEMA_TOAST || cinemaText != null
            if (lastMiniVisible != showMiniText) {
                miniLineView?.visibility = if (showMiniText) View.VISIBLE else View.GONE
                lastMiniVisible = showMiniText
            }
            setDotMarginEnd(if (showMiniText) dp(9) else 0)

            if (showMiniText) {
                val baseMini = when (contentMode) {
                    OverlayContentMode.CINEMA_TOAST -> cinemaText.orEmpty()
                    OverlayContentMode.SMART -> OverlayContentFormatter.formatSmartLine(
                        currentPaceSec = lastTelemetry.paceSecondsPer500m,
                        distMeters = distMeters,
                        elapsedSec = elapsedSec,
                        goal = lastGoal,
                        program = program,
                        weeklyGoalMeters = lastSettings.weeklyGoalMeters,
                        savedWeeklyDistanceMeters = lastSavedWeeklyDistanceMeters,
                        isRecordingActive = isRecActive,
                    )
                    OverlayContentMode.TELEMETRY -> formatTelemetryMiniLine(
                        program = program,
                        distToShow = distToShow,
                        elapsedToShow = elapsedToShow,
                    )
                    OverlayContentMode.PROGRESS_BAR -> ""
                }

                val showGhostInMini = lastGhostDelta != null &&
                    contentMode != OverlayContentMode.CINEMA_TOAST &&
                    (program == null || program.isCompleted || program.currentStep is ProgramStep.RowingDistance)
                val ghost = if (showGhostInMini) lastGhostDelta else null
                val ghostSuffix = ghost?.let { "  •  ${it.formattedCompact}" } ?: ""
                val rawGhostColor = when {
                    ghost == null -> 0
                    ghost.deltaMeters > 0 -> COLOR_ACTIVE_GREEN
                    ghost.deltaMeters < 0 -> COLOR_AMBER
                    else -> COLOR_CONNECTED_SKY
                }
                val ghostColor = if (ghost == null || style == OverlayStyle.STANDARD) {
                    rawGhostColor
                } else {
                    Color.argb(200, Color.red(rawGhostColor), Color.green(rawGhostColor), Color.blue(rawGhostColor))
                }

                val showLowBatSuffix = lowBatteryPct != null && contentMode != OverlayContentMode.CINEMA_TOAST
                val lowBatKey = if (showLowBatSuffix) lowBatteryPct.toString() else ""
                val miniKey = "$baseMini|$ghostSuffix|$ghostColor|$lowBatKey"

                if (miniKey != lastMiniKey) {
                    if (ghost == null && !showLowBatSuffix) {
                        miniLineView?.text = baseMini
                    } else {
                        val spannable = SpannableStringBuilder(baseMini)
                        if (ghost != null) {
                            spannable.append("  •  ")
                            val start = spannable.length
                            spannable.append(ghost.formattedCompact)
                            spannable.setSpan(
                                ForegroundColorSpan(ghostColor),
                                start,
                                spannable.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                        if (showLowBatSuffix && lowBatteryPct != null) {
                            spannable.append("  •  ")
                            val start = spannable.length
                            spannable.append("🔋 $lowBatteryPct%")
                            spannable.setSpan(
                                ForegroundColorSpan(lowBatColor),
                                start,
                                spannable.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                        miniLineView?.text = spannable
                    }
                    lastMiniKey = miniKey
                }
            }

            // Slim secondary progress bar (hidden when Cinema Auto-Hide is collapsed to a pure dot)
            val progress = when {
                !showMiniText -> null
                program != null -> program.stepProgressFraction
                else -> lastGoal.progressFraction(distMeters, elapsedSec)
            }
            val showProgress = progress != null
            if (appliedProgressVisible != showProgress) {
                progressTrack?.visibility = if (showProgress) View.VISIBLE else View.GONE
                appliedProgressVisible = showProgress
            }
            if (progress != null) {
                val permille = (progress * 1000f).roundToInt().coerceIn(0, 1000)
                if (permille != appliedProgressPermille) {
                    val fillLp = progressFill?.layoutParams as? LinearLayout.LayoutParams
                    val remLp = progressRemainder?.layoutParams as? LinearLayout.LayoutParams
                    if (fillLp != null && remLp != null) {
                        fillLp.weight = permille / 1000f
                        remLp.weight = (1000 - permille) / 1000f
                        progressFill?.layoutParams = fillLp
                        progressRemainder?.layoutParams = remLp
                    }
                    appliedProgressPermille = permille
                }
                val rawBarColor = when {
                    permille >= 1000 -> COLOR_ACTIVE_GREEN
                    program?.currentStep is ProgramStep.OffRowerStation -> COLOR_VIOLET
                    program?.currentStep is ProgramStep.TimedRest -> COLOR_AMBER
                    else -> COLOR_CONNECTED_SKY
                }
                val barColor = if (style == OverlayStyle.STANDARD) {
                    rawBarColor
                } else {
                    Color.argb(170, Color.red(rawBarColor), Color.green(rawBarColor), Color.blue(rawBarColor))
                }
                if (barColor != appliedProgressColor) {
                    progressFillDrawable?.setColor(barColor)
                    appliedProgressColor = barColor
                }
            }
        }

        val showNextStepBtn = program != null && !program.isCompleted
        if (lastNextStepVisible != showNextStepBtn) {
            nextStepButton?.visibility = if (showNextStepBtn) View.VISIBLE else View.GONE
            lastNextStepVisible = showNextStepBtn
        }

        if (lastModeButtonMode != contentMode) {
            modeToggleButton?.text = "Modus: ${contentMode.shortLabel}"
            modeBtnDrawable?.setColor(
                when (contentMode) {
                    OverlayContentMode.SMART -> COLOR_SKY
                    OverlayContentMode.CINEMA_TOAST -> COLOR_VIOLET
                    OverlayContentMode.PROGRESS_BAR -> COLOR_ACTIVE_GREEN
                    OverlayContentMode.TELEMETRY -> COLOR_SLATE_CHIP
                },
            )
            lastModeButtonMode = contentMode
        }
        if (lastStyleButtonStyle != style) {
            styleToggleButton?.text = "Stil: ${style.label}"
            styleBtnDrawable?.setColor(
                if (style == OverlayStyle.STANDARD) COLOR_SLATE_CHIP else COLOR_VIOLET,
            )
            lastStyleButtonStyle = style
        }
        if (lastLockedMode != lastSettings.overlayPositionLocked) {
            lockToggleButton?.text = if (lastSettings.overlayPositionLocked) "Gesperrt" else "Pos: Frei"
            lockBtnDrawable?.setColor(
                if (lastSettings.overlayPositionLocked) COLOR_AMBER else COLOR_SLATE_CHIP,
            )
            lastLockedMode = lastSettings.overlayPositionLocked
        }

        if (isExpanded) {
            val spmText = String.format(Locale.US, "%.0f", lastTelemetry.strokeRateSpm)
            val caloriesToShow = when (rec) {
                is RecordingState.Active -> rec.calories
                RecordingState.Idle -> lastTelemetry.calories
            }
            val hrPart = lastTelemetry.heartRateBpm?.let { "  •  $it bpm" } ?: ""
            val extraText = "${lastTelemetry.formattedPace} /500m  •  $spmText spm  •  $distToShow  •  $elapsedToShow  •  ${lastTelemetry.watts} W  •  $caloriesToShow kcal$hrPart"
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

    private fun formatTelemetryMiniLine(
        program: ProgramProgressState?,
        distToShow: String,
        elapsedToShow: String,
    ): String {
        val spmText = String.format(Locale.US, "%.0f", lastTelemetry.strokeRateSpm)
        val wattsSuffix = if (lastSettings.showWattsInMini && lastTelemetry.watts > 0) {
            "  •  ${lastTelemetry.watts} W"
        } else {
            ""
        }
        return when {
            program != null && program.isCompleted ->
                "✓ ${program.plan.titleWithScale}  •  $distToShow  •  $elapsedToShow"
            program != null && program.currentStep is ProgramStep.TimedRest -> {
                val step = program.currentStep
                "Pause ${RowingTelemetry.formatDuration(program.restRemainingSeconds)}  •  danach ${step.nextDescription}"
            }
            program != null && program.currentStep is ProgramStep.OffRowerStation -> {
                val step = program.currentStep
                val nextHint = step.nextRoundLabel?.let { "Ziehen für $it" } ?: "Fertig → Stop & Save"
                "R${step.roundIndex}/${step.totalRounds}: ${step.exerciseLabel}  •  $nextHint"
            }
            program != null && program.currentStep is ProgramStep.RowingDistance -> {
                val step = program.currentStep
                val remM = (step.targetMeters - program.stepDistanceMeters).coerceAtLeast(0)
                "R${step.roundIndex}/${step.totalRounds}  •  ${lastTelemetry.formattedPace} /500m  •  $spmText spm  •  noch ${RowingTelemetry.formatDistance(remM)}  •  $elapsedToShow$wattsSuffix"
            }
            else ->
                "${lastTelemetry.formattedPace} /500m  •  $spmText spm  •  $distToShow  •  $elapsedToShow$wattsSuffix"
        }
    }

    private fun setDotMarginEnd(marginPx: Int) {
        if (appliedDotMarginEnd == marginPx) return
        val lp = statusDot?.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.marginEnd = marginPx
        statusDot?.layoutParams = lp
        appliedDotMarginEnd = marginPx
    }

    private fun setAutoDimmed(view: View, dimmed: Boolean) {
        if (isAutoDimmed == dimmed) return
        isAutoDimmed = dimmed
        val targetAlpha = if (dimmed) IDLE_DIM_ALPHA else 1.0f
        view.animate()
            .alpha(targetAlpha)
            .setDuration(if (dimmed) 450L else 160L)
            .start()
    }

    private fun applyTextStyle(style: OverlayStyle, opacity: Float) {
        val textView = miniLineView ?: return
        val captionView = barCaptionView
        when (style) {
            OverlayStyle.STANDARD -> {
                textView.setTextColor(Color.parseColor("#F8FAFC"))
                textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                captionView?.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
            OverlayStyle.CINEMA -> {
                val textAlpha = (opacity.coerceIn(0.45f, 1.0f) * 215f).roundToInt().coerceIn(115, 220)
                textView.setTextColor(Color.argb(textAlpha, 203, 213, 225))
                textView.setShadowLayer(2f, 0f, 1f, Color.argb(140, 0, 0, 0))
                captionView?.setShadowLayer(2f, 0f, 1f, Color.argb(140, 0, 0, 0))
            }
            OverlayStyle.TRANSPARENT -> {
                val textAlpha = (opacity.coerceIn(0.4f, 1.0f) * 235f).roundToInt().coerceIn(120, 238)
                textView.setTextColor(Color.argb(textAlpha, 226, 232, 240))
                textView.setShadowLayer(5f, 0f, 1.5f, Color.argb(225, 0, 0, 0))
                captionView?.setShadowLayer(5f, 0f, 1.5f, Color.argb(225, 0, 0, 0))
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
        appliedBorderColor = 0
        appliedStyle = null
        appliedExpandedForBg = null
        appliedScale = Float.NaN
        appliedDotColor = 0
        appliedDotMarginEnd = -1
        appliedProgressVisible = null
        appliedProgressPermille = -1
        appliedProgressColor = 0
        lastMiniVisible = null
        lastMiniKey = null
        lastBarModeVisible = null
        lastBarCaption = null
        lastBarCaptionColor = 0
        lastExtraText = null
        lastNextStepVisible = null
        lastPauseVisible = null
        lastPauseText = null
        lastPauseColor = 0
        lastRecordText = null
        lastRecordColor = 0
        lastModeButtonMode = null
        lastLockedMode = null
        lastStyleButtonStyle = null
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

    private fun createProgressBarDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(3).toFloat()
            setColor(color)
        }

    private fun createPillBackground(
        opacity: Float,
        borderBaseColor: Int,
        style: OverlayStyle,
        expanded: Boolean,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            updatePillDrawable(this, opacity, borderBaseColor, style, expanded)
        }

    private fun updatePillDrawable(
        drawable: GradientDrawable?,
        opacity: Float,
        borderBaseColor: Int,
        style: OverlayStyle,
        expanded: Boolean,
    ) {
        if (drawable == null) return
        val clamped = opacity.coerceIn(AppPreferences.MIN_OPACITY, 1.0f)
        when {
            style == OverlayStyle.TRANSPARENT && !expanded -> {
                drawable.setColor(Color.TRANSPARENT)
                drawable.setStroke(0, Color.TRANSPARENT)
            }
            style == OverlayStyle.TRANSPARENT && expanded -> {
                drawable.setColor(Color.argb(190, 8, 12, 18))
                drawable.setStroke(dp(1), Color.argb(90, 148, 163, 184))
            }
            style == OverlayStyle.CINEMA -> {
                val bgAlpha = (clamped * 0.72f * 255f).roundToInt().coerceIn(28, 200)
                val borderAlpha = (clamped * 0.35f * 255f).roundToInt().coerceIn(18, 95)
                drawable.setColor(Color.argb(bgAlpha, 5, 7, 10))
                drawable.setStroke(
                    dp(1),
                    Color.argb(
                        borderAlpha,
                        Color.red(borderBaseColor),
                        Color.green(borderBaseColor),
                        Color.blue(borderBaseColor),
                    ),
                )
            }
            else -> {
                val alphaInt = (clamped * 255f).roundToInt()
                val bgColor = Color.argb(alphaInt, 11, 18, 26)
                val borderAlpha = (alphaInt * 0.9f).roundToInt().coerceAtMost(255)
                val borderColor = Color.argb(
                    borderAlpha,
                    Color.red(borderBaseColor),
                    Color.green(borderBaseColor),
                    Color.blue(borderBaseColor),
                )
                drawable.setColor(bgColor)
                drawable.setStroke(dp(1), borderColor)
            }
        }
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

    /**
     * Lightweight native Canvas bar rendering either a sleek filling progress bar
     * or a 2-marker live Ghost Race track (user dot vs. ghost marker).
     */
    private class VisualBarView(context: Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(85, 148, 163, 184)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CONNECTED_SKY
        }
        private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_AMBER
        }
        private val userDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private val trackRect = RectF()
        private val fillRect = RectF()

        private var userFrac: Float = 0f
        private var ghostFrac: Float? = null

        fun updateBar(
            userFraction: Float,
            ghostFraction: Float?,
            fillColor: Int,
            ghostMarkerColor: Int,
        ) {
            val u = userFraction.coerceIn(0f, 1f)
            val g = ghostFraction?.coerceIn(0f, 1f)
            if (u == userFrac && g == ghostFrac && fillPaint.color == fillColor && ghostPaint.color == ghostMarkerColor) {
                return
            }
            userFrac = u
            ghostFrac = g
            fillPaint.color = fillColor
            ghostPaint.color = ghostMarkerColor
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val padX = h * 0.35f
            val usableW = (w - padX * 2f).coerceAtLeast(1f)
            val barH = (h * 0.42f).coerceAtLeast(4f)
            val top = (h - barH) / 2f
            val radius = barH / 2f

            trackRect.set(padX, top, padX + usableW, top + barH)
            canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

            val fillRight = padX + usableW * userFrac
            if (userFrac > 0.005f) {
                fillRect.set(padX, top, fillRight.coerceAtLeast(padX + barH), top + barH)
                canvas.drawRoundRect(fillRect, radius, radius, fillPaint)
            }

            // Ghost marker & user head dot in Ghost Race mode
            ghostFrac?.let { gf ->
                val gx = padX + usableW * gf
                val cy = h / 2f
                canvas.drawCircle(gx, cy, h * 0.30f, ghostPaint)
                canvas.drawCircle(fillRight, cy, h * 0.34f, fillPaint)
                canvas.drawCircle(fillRight, cy, h * 0.16f, userDotPaint)
            }
        }
    }

    companion object {
        private const val IDLE_AUTO_DIM_MS = 15_000L
        private const val IDLE_DIM_ALPHA = 0.45f
        private const val LOW_BATTERY_THRESHOLD_PERCENT = 20
        private const val SPLIT_MILESTONE_METERS = 500
        private const val CINEMA_TOAST_DURATION_MS = 6_000L

        private val COLOR_ACTIVE_GREEN = Color.parseColor("#22C55E")
        private val COLOR_SAVE_GREEN = Color.parseColor("#16A34A")
        private val COLOR_CONNECTED_SKY = Color.parseColor("#38BDF8")
        private val COLOR_SKY = Color.parseColor("#0EA5E9")
        private val COLOR_AMBER = Color.parseColor("#F59E0B")
        private val COLOR_VIOLET = Color.parseColor("#A855F7")
        private val COLOR_DISCONNECTED = Color.parseColor("#EF4444")
        private val COLOR_SLATE_CHIP = Color.parseColor("#334155")
        private val COLOR_CLOSE_CHIP = Color.parseColor("#7F1D1D")
    }
}
