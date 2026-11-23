package dev.padkeys.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.StayCurrentLandscape
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.padkeys.BuildConfig
import dev.padkeys.Haptics
import dev.padkeys.KbLayout
import dev.padkeys.PadKeysService
import dev.padkeys.Predict
import dev.padkeys.Prefs

/** This app's keyboard, as Android names it ("dev.padkeys/.PadKeysService"). */
private fun Context.imeId() = ComponentName(this, PadKeysService::class.java).flattenToShortString()

private fun Context.imeEnabled(): Boolean =
    getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.any { it.id == imeId() } == true

private fun Context.imeSelected(): Boolean =
    Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == imeId()

private fun Context.openKeyboardSettings() =
    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private fun Context.showKeyboardPicker() =
    getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()

/**
 * The one screen, in Material 3 Expressive: a collapsing large top bar, setup as a hero card until PadKeys is the
 * keyboard, a field to try it in, and settings as grouped (segmented) list items with tonal icon badges, connected
 * toggle groups for choices. Setup status is re-read whenever [setupTick] changes (the activity bumps it on resume
 * and when the keyboard picker closes).
 */
@Composable
fun HomeScreen(setupTick: Int) {
    val ctx = LocalContext.current
    val enabled = remember(setupTick) { ctx.imeEnabled() }
    val selected = remember(setupTick) { ctx.imeSelected() }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("PadKeys") },
                subtitle = { Text(if (enabled && selected) "Your keyboard" else "Set up your keyboard") },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            if (enabled && selected) Ready() else SetupHero(enabled)
            TryIt()
            // Keyed on setupTick: the keyboard's own layout button changes the layout while this screen is open.
            key(setupTick) { KeyboardSettings() }
            About()
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

// ---------------------------------------------------------------- building blocks

/** A section title above a group of list items. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) { content() }
    }
}

@Composable
private fun segColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** The round tonal icon badge leading each setting. */
@Composable
private fun Badge(icon: ImageVector, container: Color = MaterialTheme.colorScheme.secondaryContainer,
                  content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
    }
}

/** M3E switch: a check in the thumb when on. */
@Composable
private fun CheckSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked = checked, onCheckedChange = onChange,
    thumbContent = if (checked) { { Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) } } else null,
)

@Composable
private fun SwitchItem(index: Int, count: Int, icon: ImageVector, title: String, sub: String?,
                       checked: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedListItem(
        onClick = { onChange(!checked) },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = segColors(),
        leadingContent = { Badge(icon) },
        supportingContent = sub?.let { { Text(it) } },
        trailingContent = { CheckSwitch(checked, onChange) },
    ) { Text(title) }
}

/** Unchecked toggle buttons need a visible container on the surfaceContainer cards they sit on. */
@Composable
private fun toggleColors() = ToggleButtonDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** A row of connected toggle buttons (M3E button group): one choice of several. */
@Composable
private fun Choice(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        labels.forEachIndexed { i, label ->
            ToggleButton(
                checked = selected == i,
                onCheckedChange = { onPick(i) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp),
                colors = toggleColors(),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    labels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

// ---------------------------------------------------------------- setup

@Composable
private fun SetupHero(enabled: Boolean) {
    val ctx = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(
                shape = MaterialShapes.Cookie9Sided.toShape(),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(96.dp),
            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Keyboard, null, Modifier.size(44.dp)) } }
            Text(if (enabled) "One more step" else "Make PadKeys your keyboard",
                style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                if (enabled) "PadKeys is on. Now pick it as the keyboard you type with."
                else "Turn on PadKeys in Settings › On-screen keyboards, then pick it. Android warns that a keyboard " +
                    "can see what you type: PadKeys keeps everything on the phone and has no internet access.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
            if (!enabled) Button(onClick = { ctx.openKeyboardSettings() }, modifier = Modifier.fillMaxWidth()) {
                Text("1 · Turn on PadKeys")
            }
            val pick: @Composable () -> Unit = { Text(if (enabled) "Choose PadKeys" else "2 · Choose PadKeys") }
            if (enabled) Button(onClick = { ctx.showKeyboardPicker() }, modifier = Modifier.fillMaxWidth()) { pick() }
            else FilledTonalButton(onClick = { ctx.showKeyboardPicker() }, enabled = false, modifier = Modifier.fillMaxWidth()) { pick() }
        }
    }
}

/** Set up: a compact card with a burst-shaped check, and the keyboard settings / picker at hand. */
@Composable
private fun Ready() {
    val ctx = LocalContext.current
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = MaterialShapes.SoftBurst.toShape(),
                color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(52.dp),
            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null) } }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text("PadKeys is your keyboard", style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = { ctx.showKeyboardPicker() }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Switch keyboard") }
                    Spacer(Modifier.size(16.dp))
                    TextButton(onClick = { ctx.openKeyboardSettings() }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("System settings") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- try it

/** Field types to try: each tells the keyboard something different (Enter's action, symbols first, no suggestions). */
private enum class TestField(val label: String, val options: KeyboardOptions, val singleLine: Boolean = true) {
    TEXT("Text", KeyboardOptions(imeAction = ImeAction.Done)),
    MESSAGE("Message", KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), singleLine = false),
    SEARCH("Search", KeyboardOptions(imeAction = ImeAction.Search)),
    NUMBER("Number", KeyboardOptions(keyboardType = KeyboardType.Number)),
    EMAIL("Email", KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)),
    PASSWORD("Password", KeyboardOptions(keyboardType = KeyboardType.Password)),
}

@Composable
private fun TryIt() {
    var kind by remember { mutableStateOf(TestField.TEXT) }
    var text by remember { mutableStateOf("") }
    var actions by remember { mutableIntStateOf(0) }
    Section("Try it") {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TestField.entries.forEach { f ->
                        ToggleButton(checked = kind == f, onCheckedChange = { kind = f }, colors = toggleColors()) { Text(f.label) }
                    }
                }
                // A new field per type, so the keyboard restarts with the new field's options.
                key(kind) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        placeholder = { Text("Type here with PadKeys") },
                        shape = RoundedCornerShape(20.dp),
                        singleLine = kind.singleLine,
                        minLines = if (kind.singleLine) 1 else 3,
                        keyboardOptions = kind.options,
                        keyboardActions = KeyboardActions { actions++ },
                        visualTransformation = if (kind == TestField.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
                    )
                }
                if (actions > 0) Text("Enter action performed ×$actions", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- settings

private val layoutLabels = KbLayout.entries.map { it.label }

@Composable
private fun LayoutItem(index: Int, count: Int, icon: ImageVector, title: String, landscape: Boolean) {
    var layout by remember { mutableStateOf(Prefs.layout(landscape)) }
    SegmentedListItem(
        onClick = {},
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = segColors(),
        leadingContent = { Badge(icon) },
        supportingContent = {
            Column {
                Text(layout.blurb)
                Choice(layoutLabels, KbLayout.entries.indexOf(layout)) { i ->
                    layout = KbLayout.entries[i]; Prefs.setLayout(landscape, layout)
                }
            }
        },
    ) { Text(title) }
}

@Composable
private fun KeyboardSettings() {
    Section("Layout") {
        LayoutItem(0, 3, Icons.Rounded.Smartphone, "Upright", landscape = false)
        LayoutItem(1, 3, Icons.Rounded.StayCurrentLandscape, "Sideways", landscape = true)
        var height by remember { mutableFloatStateOf(Prefs.height) }
        SegmentedListItem(
            onClick = {},
            shapes = ListItemDefaults.segmentedShapes(2, 3),
            colors = segColors(),
            leadingContent = { Badge(Icons.Rounded.Height) },
            supportingContent = {
                Slider(value = height, onValueChange = { height = it }, onValueChangeFinished = { Prefs.height = height },
                    valueRange = 0.7f..1.4f, steps = 13)
            },
        ) { Text("Height · ${(height * 100).toInt()}%") }
    }

    Section("Typing") {
        var flick by remember { mutableStateOf(Prefs.flick) }
        SwitchItem(0, 4, Icons.Rounded.SwipeUp, "Flick for symbols",
            "Flick a key up, or hold it, for the character in its corner", flick) { flick = it; Prefs.flick = it }
        var cursor by remember { mutableStateOf(Prefs.spaceCursor) }
        SwitchItem(1, 4, Icons.Rounded.SpaceBar, "Space-bar cursor",
            "Drag along the space bar to move the cursor; hold Shift to select", cursor) { cursor = it; Prefs.spaceCursor = it }
        var preview by remember { mutableStateOf(Prefs.keyPreview) }
        SwitchItem(2, 4, Icons.Rounded.ZoomIn, "Key previews", "A big copy of the key above your finger", preview) {
            preview = it; Prefs.keyPreview = it
        }
        var caps by remember { mutableStateOf(Prefs.autoCaps) }
        SwitchItem(3, 4, Icons.Rounded.TextFields, "Capitalize sentences",
            "Shift is on at the start of a sentence, where the app asks for it", caps) { caps = it; Prefs.autoCaps = it }
    }

    Section("Glide typing") {
        var glide by remember { mutableStateOf(Prefs.glide) }
        SwitchItem(0, 2, Icons.Rounded.Gesture, "Glide typing",
            "Slide through the letters of a word without lifting. Never in password fields", glide) {
            glide = it; Prefs.glide = it
        }
        var trail by remember { mutableStateOf(Prefs.glideTrail) }
        SwitchItem(1, 2, Icons.Rounded.Draw, "Show gesture trail", "A fading line follows your finger as you glide", trail) {
            trail = it; Prefs.glideTrail = it
        }
    }

    Section("Suggestions") {
        var suggest by remember { mutableStateOf(Prefs.suggestions) }
        SwitchItem(0, 3, Icons.Rounded.AutoAwesome, "Suggestions",
            "Completions and spelling corrections above the keys. Never in password fields", suggest) {
            suggest = it; Prefs.suggestions = it
        }
        var learn by remember { mutableStateOf(Prefs.learn) }
        SwitchItem(1, 3, Icons.Rounded.Psychology, "Learn my words",
            "Remember words you type to suggest them later. Stored only on this phone; never from passwords or " +
                "private (incognito) fields", learn) { learn = it; Prefs.learn = it }
        var learned by remember { mutableIntStateOf(Predict.learnedCount) }
        // The keyboard learns while this screen is open (the test field): keep the count current.
        DisposableEffect(Unit) {
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == "learnedWords") learned = Predict.learnedCount }
            Prefs.listen(l)
            onDispose { Prefs.unlisten(l) }
        }
        var confirm by remember { mutableStateOf(false) }
        SegmentedListItem(
            onClick = { if (learned > 0) confirm = true },
            shapes = ListItemDefaults.segmentedShapes(2, 3),
            colors = segColors(),
            leadingContent = {
                Badge(Icons.Rounded.DeleteSweep, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            },
            supportingContent = { Text(if (learned == 1) "1 word learned" else "$learned words learned") },
        ) { Text("Forget learned words") }
        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, null) },
            title = { Text("Forget learned words?") },
            text = { Text("PadKeys will stop suggesting the words it learned from your typing.") },
            confirmButton = { TextButton(onClick = { Predict.forget(); learned = 0; confirm = false }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }

    Section("Feel and look") {
        var haptics by remember { mutableStateOf(Prefs.haptics) }
        var style by remember { mutableIntStateOf(Prefs.hapticStyle) }
        SegmentedListItem(
            onClick = { haptics = !haptics; Prefs.haptics = haptics },
            shapes = ListItemDefaults.segmentedShapes(0, 2),
            colors = segColors(),
            leadingContent = { Badge(Icons.Rounded.Vibration) },
            trailingContent = { CheckSwitch(haptics) { haptics = it; Prefs.haptics = it } },
            supportingContent = {
                Column {
                    Text(when {
                        !Haptics.available -> "This device has no vibration motor"
                        Haptics.rich -> "Rich: crisp ticks (this phone's motor can play them)"
                        else -> "Basic: short pulses (this motor can't play ticks)"
                    })
                    if (haptics && Haptics.available) Choice(listOf("Auto", "Rich", "Basic"), style) { i ->
                        style = i; Prefs.hapticStyle = i; Haptics.click()
                    }
                }
            },
        ) { Text("Vibrate on key press") }
        var real by remember { mutableStateOf(Prefs.realistic) }
        SwitchItem(1, 2, Icons.Rounded.Layers, "Realistic look",
            "Keys drawn as hardware: silver in light mode, space black in dark", real) { real = it; Prefs.realistic = it }
    }
}

// ---------------------------------------------------------------- about

@Composable
private fun About() {
    var licenses by remember { mutableStateOf(false) }
    Section("About") {
        SegmentedListItem(
            onClick = {},
            shapes = ListItemDefaults.segmentedShapes(0, 3),
            colors = segColors(),
            leadingContent = { Badge(Icons.Rounded.Info) },
            supportingContent = {
                Text("PadBridge's keyboard as your phone's keyboard. Colors follow your wallpaper and light / dark " +
                    "mode. No internet access; nothing leaves the phone.")
            },
        ) { Text("PadKeys ${BuildConfig.VERSION_NAME}") }
        SegmentedListItem(
            onClick = {},
            shapes = ListItemDefaults.segmentedShapes(1, 3),
            colors = segColors(),
            leadingContent = { Badge(Icons.Rounded.MenuBook) },
            supportingContent = { Text("From wordfreq by Robyn Speer, CC BY-SA 4.0. Corrections come from your phone's spell checker.") },
        ) { Text("Word list") }
        SegmentedListItem(
            onClick = { licenses = true },
            shapes = ListItemDefaults.segmentedShapes(2, 3),
            colors = segColors(),
            leadingContent = { Badge(Icons.Rounded.Gavel) },
            supportingContent = { Text("Apache License 2.0, and the libraries PadKeys uses") },
        ) { Text("Open-source licenses") }
    }
    if (licenses) AlertDialog(
        onDismissRequest = { licenses = false },
        confirmButton = { TextButton(onClick = { licenses = false }) { Text("Close") } },
        title = { Text("Open-source licenses") },
        text = {
            Text(
                "PadKeys is licensed under the Apache License 2.0 and uses:\n\n" +
                    "• Jetpack Compose, Material 3, AndroidX Activity and Core (Google) — Apache License 2.0\n" +
                    "• Material Icons (Google) — Apache License 2.0\n" +
                    "• Kotlin standard library (JetBrains) — Apache License 2.0\n" +
                    "• English word list for suggestions (assets/words_en.txt): the 40,000 most common words from " +
                    "wordfreq by Robyn Speer, whose data is licensed CC BY-SA 4.0 " +
                    "(creativecommons.org/licenses/by-sa/4.0); the list in PadKeys is shared under the same license\n\n" +
                    "Full license texts: apache.org/licenses/LICENSE-2.0"
            )
        },
    )
}
