package com.example.tanlishkeyboard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.tanlishkeyboard.accessibility.TanlishAccessibilityService
import com.example.tanlishkeyboard.data.AppServices
import com.example.tanlishkeyboard.data.Settings
import com.example.tanlishkeyboard.overlay.BubbleService
import com.example.tanlishkeyboard.translate.AndroidClipboard
import com.example.tanlishkeyboard.translate.ButtonOutcome
import com.example.tanlishkeyboard.translate.ButtonTranslator
import com.example.tanlishkeyboard.translate.EditableField
import com.example.tanlishkeyboard.translate.FieldProvider
import com.example.tanlishkeyboard.ui.theme.TanlishKeyboardTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TanlishKeyboardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ButtonScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    companion object {

        /** Whether the floating button is allowed to draw over other apps. */
        fun canDrawOverlays(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)

        /**
         * Whether the accessibility service is switched on.
         *
         * Asked through [AccessibilityManager], which any app may use, and backed up by the service's
         * own report: reading the setting itself is restricted on Android 14 and crashes apps that
         * try, which is a lesson this project already learned the hard way.
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            if (TanlishAccessibilityService.isConnected()) return true
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return runCatching {
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
            }.getOrDefault(false)
        }

        fun openOverlaySettings(context: Context) {
            val appSpecific = Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            val opened = runCatching { context.startActivity(appSpecific) }.isSuccess
            if (!opened) {
                runCatching { context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
            }
        }

        fun openAccessibilitySettings(context: Context) {
            runCatching { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
    }
}

@Composable
private fun ButtonScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val services = remember { AppServices.get(context) }
    val scope = rememberCoroutineScope()

    var overlayAllowed by remember { mutableStateOf(MainActivity.canDrawOverlays(context)) }
    var accessibilityOn by remember { mutableStateOf(MainActivity.isAccessibilityServiceEnabled(context)) }
    var buttonWanted by remember { mutableStateOf(services.settings.bubbleEnabled) }
    var buttonRunning by remember { mutableStateOf(BubbleService.isRunning) }

    var allowNetwork by remember { mutableStateOf(services.settings.allowNetwork) }
    var opacity by remember { mutableStateOf(services.settings.bubbleOpacity) }

    var playground by remember { mutableStateOf("") }
    var previous by remember { mutableStateOf<String?>(null) }
    var resultNote by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        overlayAllowed = MainActivity.canDrawOverlays(context)
        accessibilityOn = MainActivity.isAccessibilityServiceEnabled(context)
        buttonWanted = services.settings.bubbleEnabled
        buttonRunning = BubbleService.isRunning
        opacity = services.settings.bubbleOpacity
        // The service can be killed in the background; the user asked for a button, so put it back.
        if (buttonWanted && !BubbleService.isRunning) {
            BubbleService.start(context)
            buttonRunning = BubbleService.isRunning
        }
    }

    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val owner = activity ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The app's own icon, drawn from the same generated vector the launcher uses.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_app_mark),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Tanlish Translate", style = MaterialTheme.typography.headlineSmall)
                Text("த in, English out", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(
            "Type Tanglish in any app with your normal keyboard. The small floating button appears while " +
                "the keyboard is up: tap it and the text in that field is replaced with English, right " +
                "where you typed it.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Set up", style = MaterialTheme.typography.titleMedium)

                SetupStep(
                    done = overlayAllowed,
                    title = "1. Allow drawing over other apps",
                    body = "Android calls this \u201cDisplay over other apps\u201d. It is what lets the button float " +
                        "above WhatsApp, Messages and everything else.",
                    actionLabel = "Allow",
                    onAction = { MainActivity.openOverlaySettings(context) },
                )

                SetupStep(
                    done = accessibilityOn,
                    title = "2. Switch on the accessibility service",
                    body = "This is what lets the button read the field you are in when you tap it, and put the " +
                        "English back. Nothing is read while you type. Android 13 and newer grey the toggle " +
                        "out as a \u201cRestricted setting\u201d for apps that were not installed from a store: " +
                        "open App info, tap the \u22ee menu, choose Allow restricted settings, then come back.",
                    actionLabel = "Open accessibility settings",
                    onAction = { MainActivity.openAccessibilitySettings(context) },
                )

                SetupStep(
                    done = buttonWanted && buttonRunning,
                    title = "3. Show the button",
                    body = if (buttonWanted && buttonRunning) {
                        "Switched on. The circle appears while the keyboard is up — that is, while you are " +
                            "typing — and disappears when you close the keyboard. Drag it anywhere, tap it " +
                            "to translate your text, and long press it to hide it for good."
                    } else {
                        "Turn this on and a small circle showing த will appear whenever you start typing."
                    },
                    actionLabel = null,
                    onAction = {},
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Floating button", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = buttonWanted,
                        onCheckedChange = { wanted ->
                            buttonWanted = wanted
                            services.settings.bubbleEnabled = wanted
                            if (wanted) BubbleService.start(context) else BubbleService.stop(context)
                            buttonRunning = BubbleService.isRunning
                        },
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Button opacity", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${(opacity * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Slider(
                        value = opacity,
                        onValueChange = { value ->
                            opacity = value
                            services.settings.bubbleOpacity = value
                            BubbleService.refreshAppearance(context)
                        },
                        valueRange = Settings.MIN_BUBBLE_OPACITY..Settings.MAX_BUBBLE_OPACITY,
                    )
                    Text(
                        "How see-through the circle is while it floats over your apps.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Try it here", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Same code path as the floating button: it reads this field, translates it and writes " +
                        "the result back into it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = playground,
                    onValueChange = {
                        playground = it
                        previous = null
                        resultNote = null
                    },
                    label = { Text("Type Tanglish") },
                    placeholder = { Text("vanakkam nanba epdi irukka") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = !busy && playground.isNotBlank(),
                        onClick = {
                            val raw = playground
                            val field = object : EditableField {
                                override val isPassword = false
                                override fun read(): String = playground
                                override fun replace(text: String): Boolean {
                                    playground = text
                                    return true
                                }
                            }
                            busy = true
                            resultNote = null
                            scope.launch {
                                val translator = ButtonTranslator(
                                    fields = FieldProvider { field },
                                    clipboard = AndroidClipboard(context),
                                    translator = services.translator,
                                )
                                val outcome = translator.translate()
                                busy = false
                                when (outcome) {
                                    is ButtonOutcome.Replaced -> {
                                        previous = raw
                                        resultNote = outcome.message
                                    }

                                    is ButtonOutcome.Copied -> {
                                        previous = null
                                        resultNote = "${outcome.message}. Copied: ${outcome.text}"
                                    }

                                    is ButtonOutcome.Blocked -> {
                                        previous = null
                                        resultNote = outcome.message
                                    }
                                }
                            }
                        },
                    ) { Text(if (busy) "Translating…" else "Translate this field") }

                    previous?.let { original ->
                        TextButton(onClick = {
                            playground = original
                            previous = null
                            resultNote = "Undone"
                        }) { Text("Undo") }
                    }
                }

                resultNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Settings", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Allow AI translation (uses the internet)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = allowNetwork,
                        onCheckedChange = {
                            allowNetwork = it
                            services.settings.allowNetwork = it
                        },
                    )
                }
                Text(
                    "Leave this on and the button translates with AI. Turn it off and it sends nothing " +
                        "anywhere — it will simply tell you that AI translation is switched off.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SetupStep(
    done: Boolean,
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (done) "✓" else "○", style = MaterialTheme.typography.titleMedium)
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Text(body, style = MaterialTheme.typography.bodySmall)
        if (!done && actionLabel != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
