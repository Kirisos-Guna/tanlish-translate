package com.example.tanlishkeyboard.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.tanlishkeyboard.MainActivity
import com.example.tanlishkeyboard.accessibility.TanlishAccessibilityService
import com.example.tanlishkeyboard.data.AppServices
import com.example.tanlishkeyboard.data.Settings
import com.example.tanlishkeyboard.translate.AndroidClipboard
import com.example.tanlishkeyboard.translate.ButtonOutcome
import com.example.tanlishkeyboard.translate.ButtonTranslator
import com.example.tanlishkeyboard.translate.FieldProvider
import com.example.tanlishkeyboard.ui.theme.TanlishKeyboardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The floating translate button.
 *
 * It is one small overlay window plus, while a message is being shown, one pill window beside it.
 * Nothing is read from the app underneath until the button is tapped, and the translation goes back
 * through [TanlishAccessibilityService].
 */
class BubbleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var services: AppServices
    private lateinit var windowManager: WindowManager
    private lateinit var lifecycleOwner: WindowLifecycleOwner
    private lateinit var translator: ButtonTranslator

    private val state = mutableStateOf(BubbleState())

    /** The user's opacity setting, held as state so moving the slider updates a button already on screen. */
    private val opacity = mutableStateOf(Settings.DEFAULT_BUBBLE_OPACITY)

    private var bubble: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var hint: LinearLayout? = null
    private var hintMessage: TextView? = null
    private var hintUndo: TextView? = null
    private var hintParams: WindowManager.LayoutParams? = null
    private var hideHintJob: Job? = null

    private val callbacks = object : BubbleCallbacks {
        override fun onTap() = translateFocusedField()
        override fun onLongPress() = hideButton()
        override fun onDrag(dx: Float, dy: Float) = moveBy(dx, dy)
        override fun onDragEnd() = settle()
        override fun onUndo() = undo()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        services = AppServices.get(this)
        opacity.value = services.settings.bubbleOpacity
        windowManager = getSystemService(WindowManager::class.java)
        lifecycleOwner = WindowLifecycleOwner().also {
            it.onCreate()
            it.onStart()
            it.onResume()
        }
        translator = ButtonTranslator(
            fields = FieldProvider { TanlishAccessibilityService.focusedFieldOrNull() },
            clipboard = AndroidClipboard(this),
            translator = services.translator,
        )
        TanlishAccessibilityService.observeKeyboard { applyKeyboardVisibility() }
        applyKeyboardVisibility()
        settle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_APPEARANCE) {
            opacity.value = services.settings.bubbleOpacity
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation moves the screen edges, so put the button back where it belongs.
        settle()
    }

    override fun onDestroy() {
        isRunning = false
        TanlishAccessibilityService.observeKeyboard(null)
        hideHintJob?.cancel()
        bubble?.let { view -> runCatching { windowManager.removeView(view) } }
        hint?.let { view -> runCatching { windowManager.removeView(view) } }
        bubble = null
        bubbleParams = null
        hint = null
        hintParams = null
        lifecycleOwner.onDestroy()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- the button

    /**
     * The button is only on screen while a keyboard is up, so it never floats over an app the user is
     * just reading.
     *
     * When the accessibility service is switched off the keyboard cannot be seen at all, so the button
     * stays: that is how the user finds out step 2 of the setup is missing.
     */
    private fun applyKeyboardVisibility() {
        val typing = !TanlishAccessibilityService.isConnected() ||
            TanlishAccessibilityService.isKeyboardVisibleNow()
        if (typing) showBubble() else hideBubble()
    }

    private fun showBubble() {
        if (bubble != null) return
        if (state.value.phase != BubblePhase.Working) {
            state.value = BubbleState()
        }
        opacity.value = services.settings.bubbleOpacity

        val view = ComposeView(this)
        installOwners(view)
        view.setContent {
            TanlishKeyboardTheme {
                BubbleRoot(state = state.value, callbacks = callbacks, opacity = opacity.value)
            }
        }

        val size = dp(BUBBLE_SIZE_DP)
        val screen = screen()
        val savedX = services.settings.bubbleX
        val savedY = services.settings.bubbleY
        val start = if (savedX != null && savedY != null) {
            BubblePlacement.snap(savedX, savedY, size, screen, dp(MARGIN_DP))
        } else {
            BubblePlacement.defaultPosition(size, screen, dp(MARGIN_DP))
        }

        val layoutParams = windowParams(size, size).apply {
            x = start.x
            y = start.y
        }
        val added = runCatching { windowManager.addView(view, layoutParams) }.isSuccess
        if (!added) {
            // Almost always a missing "display over other apps" grant, which the setup screen covers.
            Toast.makeText(this, "Android blocked the floating button — check its permissions", Toast.LENGTH_LONG)
                .show()
            stopSelf()
            return
        }
        bubble = view
        bubbleParams = layoutParams
    }

    /** Takes the button off screen, and its message with it. */
    private fun hideBubble() {
        dismissHint()
        val view = bubble ?: return
        bubble = null
        bubbleParams = null
        runCatching { windowManager.removeView(view) }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val layoutParams = bubbleParams ?: return
        val screen = screen()
        val size = dp(BUBBLE_SIZE_DP)
        val moved = BubblePlacement.drag(
            layoutParams.x + dx.roundToInt(),
            layoutParams.y + dy.roundToInt(),
            size,
            screen,
            dp(MARGIN_DP),
        )
        layoutParams.x = moved.x
        layoutParams.y = moved.y
        bubble?.let { runCatching { windowManager.updateViewLayout(it, layoutParams) } }
        dismissHint()
    }

    /** Snaps the button to the nearest edge and remembers where it landed. */
    private fun settle() {
        val layoutParams = bubbleParams ?: return
        val size = dp(BUBBLE_SIZE_DP)
        val snapped = BubblePlacement.snap(
            layoutParams.x,
            layoutParams.y,
            size,
            screen(),
            dp(MARGIN_DP),
        )
        layoutParams.x = snapped.x
        layoutParams.y = snapped.y
        bubble?.let { runCatching { windowManager.updateViewLayout(it, layoutParams) } }
        services.settings.bubbleX = snapped.x
        services.settings.bubbleY = snapped.y
    }

    private fun hideButton() {
        services.settings.bubbleEnabled = false
        Toast.makeText(this, "Translate button hidden — turn it back on in the app", Toast.LENGTH_SHORT).show()
        stopSelf()
    }

    // ---------------------------------------------------------------- translating

    private fun translateFocusedField() {
        if (!TanlishAccessibilityService.isConnected()) {
            state.value = BubbleState(
                phase = BubblePhase.Failed,
                hint = "Switch on the Tanlish Translate accessibility service to read the field you are in",
            )
            showHint(HINT_MS)
            openApp()
            return
        }

        state.value = BubbleState(phase = BubblePhase.Working)
        dismissHint()

        scope.launch {
            val outcome = translator.translate()
            state.value = when (outcome) {
                is ButtonOutcome.Replaced -> BubbleState(
                    phase = BubblePhase.Done,
                    hint = outcome.message,
                    undoOffered = translator.canUndo,
                )

                is ButtonOutcome.Copied -> BubbleState(phase = BubblePhase.Done, hint = outcome.message)

                is ButtonOutcome.Blocked -> BubbleState(phase = BubblePhase.Failed, hint = outcome.message)
            }
            showHint(if (outcome is ButtonOutcome.Replaced && translator.canUndo) UNDO_MS else HINT_MS)
        }
    }

    private fun undo() {
        val restored = translator.undo()
        state.value = BubbleState(
            phase = if (restored) BubblePhase.Done else BubblePhase.Failed,
            hint = if (restored) "Put your Tanglish back" else "Could not undo that one",
        )
        showHint(SHORT_MS)
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    // ---------------------------------------------------------------- the hint pill

    /**
     * The pill is built from ordinary views rather than Compose.
     *
     * It has to be measured *before* it goes into the window manager, to know where to put it, and a
     * ComposeView cannot be measured until it is attached to a window: doing so throws
     * "Cannot locate windowRecomposer". A TextView has no such problem.
     */
    private fun showHint(dismissAfterMillis: Long) {
        if (bubble == null) return
        hideHintJob?.cancel()
        val view = hint ?: buildHintView().also { hint = it }
        hintMessage?.text = state.value.hint.orEmpty()
        hintUndo?.visibility = if (state.value.undoOffered) View.VISIBLE else View.GONE

        val screen = screen()
        val margin = dp(MARGIN_DP)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(screen.width - 2 * margin, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val bubblePoint = BubblePlacement.Point(bubbleParams?.x ?: 0, bubbleParams?.y ?: 0)
        val position = BubblePlacement.hintPosition(
            bubble = bubblePoint,
            bubbleSize = dp(BUBBLE_SIZE_DP),
            hint = BubblePlacement.Size(view.measuredWidth, view.measuredHeight),
            screen = screen,
            margin = margin,
        )

        val layoutParams = hintParams ?: windowParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).also { hintParams = it }
        layoutParams.x = position.x
        layoutParams.y = position.y

        runCatching {
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, layoutParams)
            } else {
                windowManager.addView(view, layoutParams)
            }
        }

        hideHintJob = scope.launch {
            delay(dismissAfterMillis)
            dismissHint()
        }
    }

    private fun dismissHint() {
        hideHintJob?.cancel()
        hideHintJob = null
        val view = hint ?: return
        hint = null
        hintMessage = null
        hintUndo = null
        hintParams = null
        runCatching { windowManager.removeView(view) }
    }

    private fun buildHintView(): LinearLayout {
        val message = TextView(this).apply {
            setTextColor(HINT_TEXT)
            textSize = 13f
            maxWidth = dp(260)
        }
        val undo = TextView(this).apply {
            text = "UNDO"
            setTextColor(HINT_UNDO)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
            setOnClickListener { undo() }
        }

        hintMessage = message
        hintUndo = undo

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(HINT_SURFACE)
                cornerRadius = dp(18).toFloat()
            }
            addView(message)
            addView(
                undo,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(8) },
            )
        }
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * Compose looks its recomposer up by walking to the root of the window it is attached to, so the
     * owners go on the view we hand to the window manager. An overlay window has no framework layout
     * wrapped around it, which is what makes this simpler than it was inside the keyboard.
     */
    private fun installOwners(view: View) {
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    private fun windowParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun screen(): BubblePlacement.Screen {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            return BubblePlacement.Screen(
                width = metrics.bounds.width(),
                height = metrics.bounds.height(),
                topInset = insets.top,
                bottomInset = insets.bottom,
            )
        }
        val size = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getSize(size)
        return BubblePlacement.Screen(size.x, size.y, topInset = dp(24), bottomInset = dp(24))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val BUBBLE_SIZE_DP = 56
        private const val MARGIN_DP = 8
        private const val ACTION_APPEARANCE = "com.example.tanlishkeyboard.action.APPEARANCE"

        private val HINT_SURFACE = 0xE6141A1F.toInt()
        private val HINT_TEXT = 0xFFF2F5F7.toInt()
        private val HINT_UNDO = 0xFF8AB4F8.toInt()
        private const val HINT_MS = 4_000L
        private const val UNDO_MS = 6_000L
        private const val SHORT_MS = 2_000L

        /** Whether the button is on screen right now, for the app screen to report. */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            runCatching { context.startService(Intent(context, BubbleService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BubbleService::class.java)) }
        }

        /** Re-reads the button's look after the opacity slider moved in the app. */
        fun refreshAppearance(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, BubbleService::class.java).setAction(ACTION_APPEARANCE),
                )
            }
        }
    }
}
