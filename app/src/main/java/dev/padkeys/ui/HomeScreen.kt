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
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Update
import androidx.activity.compose.LocalActivity
import dev.padkeys.Updates
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.KeyboardAlt
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
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
import dev.padkeys.Voice

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
            UpdateBanner()
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
                    "can see what you type: PadKeys keeps everything you type on the phone. Its only network use is checking " +
                    "GitHub for updates, from this screen.",
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
    PHONE("Phone", KeyboardOptions(keyboardType = KeyboardType.Phone)),
    URL("URL", KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go)),
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
        LayoutItem(0, 5, Icons.Rounded.Smartphone, "Upright", landscape = false)
        LayoutItem(1, 5, Icons.Rounded.StayCurrentLandscape, "Sideways", landscape = true)
        var height by remember { mutableFloatStateOf(Prefs.height) }
        SegmentedListItem(
            onClick = {},
            shapes = ListItemDefaults.segmentedShapes(2, 5),
            colors = segColors(),
            leadingContent = { Badge(Icons.Rounded.Height) },
            supportingContent = {
                Slider(value = height, onValueChange = { height = it }, onValueChangeFinished = { Prefs.height = height },
                    valueRange = 0.7f..1.4f, steps = 13)
            },
        ) { Text("Height · ${(height * 100).toInt()}%") }
        var hand by remember { mutableIntStateOf(if (!Prefs.oneHanded) 0 else if (Prefs.oneHandLeft) 1 else 2) }
        SegmentedListItem(
            onClick = {},
            shapes = ListItemDefaults.segmentedShapes(3, 5),
            colors = segColors(),
            leadingContent = { Badge(Icons.Rounded.PanTool) },
            supportingContent = {
                Column {
                    Text("Keys shrink to one side, with buttons to switch sides or go back to full width. Also in the toolbar")
                    Choice(listOf("Off", "Left", "Right"), hand) { i ->
                        hand = i
                        if (i != 0) Prefs.oneHandLeft = i == 1
                        Prefs.oneHanded = i != 0
                    }
                }
            },
        ) { Text("One-handed") }
        var pad by remember { mutableStateOf(Prefs.numberPad) }
        SwitchItem(4, 5, Icons.Rounded.Dialpad, "Number pad",
            "A number or phone pad in number, phone and date fields", pad) { pad = it; Prefs.numberPad = it }
    }

    Section("PC keys") {
        var fnRow by remember { mutableStateOf(Prefs.fnRow) }
        SwitchItem(0, 3, Icons.Rounded.KeyboardAlt, "Function row",
            "Esc and F1–F12 always above the keys, like a laptop's top row, so F-keys don't need Fn", fnRow) {
            fnRow = it; Prefs.fnRow = it
        }
        var hw by remember { mutableStateOf(Prefs.hardwareBar) }
        SwitchItem(1, 3, Icons.Rounded.Keyboard, "Stay on with a hardware keyboard",
            "When a keyboard is plugged in or paired, PadKeys stays as a slim bar of Esc, F1–F12, Del and (on wide " +
                "screens) Home, End, PgUp, PgDn, the keys tablet keyboards often lack", hw) { hw = it; Prefs.hardwareBar = it }
        var tp by remember { mutableStateOf(Prefs.trackPoint) }
        var speed by remember { mutableFloatStateOf(Prefs.trackPointSpeed) }
        SegmentedListItem(
            onClick = { tp = !tp; Prefs.trackPoint = tp },
            shapes = ListItemDefaults.segmentedShapes(2, 3),
            colors = segColors(),
            leadingContent = { Badge(Icons.Rounded.Mouse) },
            trailingContent = { CheckSwitch(tp) { tp = it; Prefs.trackPoint = it } },
            supportingContent = {
                Column {
                    Text("A red nub between G, H and B, like a ThinkPad's. Push it to move the cursor: further is " +
                        "faster, up and down by line. Turn Shift on to select. Not on Split")
                    if (tp) {
                        Text("Speed · ${(speed * 100).toInt()}%", style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 8.dp))
                        Slider(value = speed, onValueChange = { speed = it },
                            onValueChangeFinished = { Prefs.trackPointSpeed = speed }, valueRange = 0.4f..2.5f)
                    }
                }
            },
        ) { Text("TrackPoint") }
    }

    Section("Typing") {
        var flick by remember { mutableStateOf(Prefs.flick) }
        SwitchItem(0, 7, Icons.Rounded.SwipeUp, "Flick for symbols",
            "Flick a key up for the character in its corner (or hold keys without accents)", flick) { flick = it; Prefs.flick = it }
        var cursor by remember { mutableStateOf(Prefs.spaceCursor) }
        SwitchItem(1, 7, Icons.Rounded.SpaceBar, "Space-bar cursor",
            "Drag along the space bar to move the cursor; hold Shift to select", cursor) { cursor = it; Prefs.spaceCursor = it }
        var preview by remember { mutableStateOf(Prefs.keyPreview) }
        SwitchItem(2, 7, Icons.Rounded.ZoomIn, "Key previews", "A big copy of the key above your finger", preview) {
            preview = it; Prefs.keyPreview = it
        }
        var caps by remember { mutableStateOf(Prefs.autoCaps) }
        SwitchItem(3, 7, Icons.Rounded.TextFields, "Capitalize sentences",
            "Shift is on at the start of a sentence, where the app asks for it", caps) { caps = it; Prefs.autoCaps = it }
        var accents by remember { mutableStateOf(Prefs.accents) }
        SwitchItem(4, 7, Icons.Rounded.TouchApp, "Hold for accents",
            "Hold a letter for its accents (é, ñ, ü…) and slide to one; hold the period for punctuation, the comma " +
                "for emoji", accents) { accents = it; Prefs.accents = it }
        var delSwipe by remember { mutableStateOf(Prefs.deleteSwipe) }
        SwitchItem(5, 7, Icons.Rounded.Backspace, "Swipe to delete",
            "Drag left from Backspace to select words, lift to delete them", delSwipe) { delSwipe = it; Prefs.deleteSwipe = it }
        var dbl by remember { mutableStateOf(Prefs.doubleSpace) }
        SwitchItem(6, 7, Icons.Rounded.MoreHoriz, "Double-space period",
            "Two quick spaces after a word type a period and a space", dbl) { dbl = it; Prefs.doubleSpace = it }
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
        SwitchItem(0, 4, Icons.Rounded.AutoAwesome, "Suggestions",
            "Completions and spelling corrections above the keys. Never in password fields", suggest) {
            suggest = it; Prefs.suggestions = it
        }
        var autocorrect by remember { mutableStateOf(Prefs.autocorrect) }
        SwitchItem(1, 4, Icons.Rounded.Spellcheck, "Autocorrect",
            "Fix a misspelled word when you finish it with a space or punctuation; Backspace right after undoes it. " +
                "Never in password, email, web address or name fields", autocorrect) { autocorrect = it; Prefs.autocorrect = it }
        var learn by remember { mutableStateOf(Prefs.learn) }
        SwitchItem(2, 4, Icons.Rounded.Psychology, "Learn my words",
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
            shapes = ListItemDefaults.segmentedShapes(3, 4),
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

    Section("Toolbar") {
        val ctx = LocalContext.current
        var clip by remember { mutableStateOf(Prefs.clipboard) }
        SwitchItem(0, 2, Icons.Rounded.ContentPaste, "Clipboard history",
            "Text you copy while the keyboard is open, kept in memory until PadKeys stops; pin items to keep them. " +
                "Fresh copies are offered above the keys. Never passwords marked sensitive", clip) { clip = it; Prefs.clipboard = it }
        val voice = remember { Voice.find(ctx) != null }
        var mic by remember { mutableStateOf(Prefs.voiceKey) }
        SwitchItem(1, 2, Icons.Rounded.Mic, "Voice typing button",
            if (voice) "Switches to your phone's voice typing keyboard, which comes back here when done"
            else "Your phone has no voice typing keyboard turned on, so the button is hidden", mic) { mic = it; Prefs.voiceKey = it }
    }

    ThemeSettings()

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

// ---------------------------------------------------------------- theme

/** Theme: light / dark, the palette (PadKeys, wallpaper, custom, two-tone), its colors, pure black. Like PadBridge's. */
@Composable
private fun ThemeSettings() {
    var mode by remember { mutableIntStateOf(Prefs.themeMode) }
    var palette by remember { mutableIntStateOf(Prefs.palette) }
    var black by remember { mutableStateOf(Prefs.pureBlack) }
    var seed by remember { mutableIntStateOf(Prefs.seedColor) }
    var bg by remember { mutableIntStateOf(Prefs.twoToneBg) }
    var fg by remember { mutableIntStateOf(Prefs.twoToneFg) }
    var editing by remember { mutableIntStateOf(0) } // two-tone: 0 background, 1 foreground
    val canDynamic = android.os.Build.VERSION.SDK_INT >= 31
    fun changed() { ThemeState.version++ }
    val twoTone = palette == 3
    // Rows: theme (not two-tone), colors, seed / two-tone colors / presets, pure black (not two-tone).
    val count = 2 + (if (palette == 2) 1 else 0) + (if (twoTone) 2 else 0)
    var i = 0
    Section("Theme") {
        if (!twoTone) SegmentedListItem(
            onClick = {},
            shapes = ListItemDefaults.segmentedShapes(i++, count),
            colors = segColors(),
            leadingContent = { Badge(Icons.Rounded.DarkMode) },
            supportingContent = {
                Choice(listOf("System", "Light", "Dark"), mode) { mode = it; Prefs.themeMode = it; changed() }
            },
        ) { Text("Light or dark") }
        SegmentedListItem(
            onClick = {},
            shapes = ListItemDefaults.segmentedShapes(i++, count),
            colors = segColors(),
            leadingContent = { Badge(Icons.Rounded.Palette) },
            supportingContent = {
                Column {
                    Text(when (palette) {
                        1 -> "Colors from your wallpaper"
                        2 -> "Pick one color; the whole palette grows from it, light and dark"
                        3 -> "Just two colors: one behind the keys, one for labels and accents. Light or dark follows the background"
                        else -> "PadKeys' own blue palette"
                    })
                    // Two rows of two: four labels don't fit one row on a phone.
                    Choice(listOf("PadKeys", "Wallpaper"), if (palette < 2) palette else -1) { p ->
                        if (p == 1 && !canDynamic) return@Choice
                        palette = p; Prefs.palette = p; changed()
                    }
                    Choice(listOf("Custom", "Two-tone"), if (palette >= 2) palette - 2 else -1) { p ->
                        palette = p + 2; Prefs.palette = p + 2; changed()
                    }
                    if (!canDynamic) Text("Wallpaper colors need Android 12 or later",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            },
        ) { Text("Colors") }
        if (palette == 2) SegmentedListItem(
            onClick = {},
            shapes = ListItemDefaults.segmentedShapes(i++, count),
            colors = segColors(),
            supportingContent = { ColorPicker(seed, onChange = {}, onDone = { seed = it; Prefs.seedColor = it; changed() }) },
        ) { Text("Seed color") }
        if (twoTone) {
            SegmentedListItem(
                onClick = {},
                shapes = ListItemDefaults.segmentedShapes(i++, count),
                colors = segColors(),
                supportingContent = {
                    Column {
                        Choice(listOf("Background", "Foreground"), editing) { editing = it }
                        val ratio = contrast(bg, fg)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                "Contrast ${"%.1f".format(ratio)}:1" + if (ratio < 4.5f) " · low, labels may be hard to read" else "",
                                color = if (ratio < 4.5f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                val t = bg; bg = fg; fg = t; Prefs.twoToneBg = bg; Prefs.twoToneFg = fg; changed()
                            }) { Text("Swap") }
                        }
                        if (editing == 0) ColorPicker(bg, onChange = {}, onDone = { bg = it; Prefs.twoToneBg = it; changed() })
                        else ColorPicker(fg, onChange = {}, onDone = { fg = it; Prefs.twoToneFg = it; changed() })
                    }
                },
            ) { Text("Two-tone colors") }
            SegmentedListItem(
                onClick = {},
                shapes = ListItemDefaults.segmentedShapes(i++, count),
                colors = segColors(),
                supportingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        for ((b, f) in TwoTonePresets) {
                            OutlinedButton(
                                onClick = { bg = b; fg = f; Prefs.twoToneBg = b; Prefs.twoToneFg = f; changed() },
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(b), contentColor = Color(f)),
                            ) { Text("Aa") }
                        }
                    }
                },
            ) { Text("Presets") }
        } else SwitchItem(i++, count, Icons.Rounded.Contrast, "Pure black",
            "True black behind the keys in dark mode, easier on OLED screens and battery", black) {
            black = it; Prefs.pureBlack = it; changed()
        }
    }
}

private val TwoTonePresets = listOf(
    0xFF0B1F33 to 0xFFFFC857, 0xFF000000 to 0xFFFFFFFF, 0xFF000000 to 0xFF39FF14, 0xFFF4EFE6 to 0xFF1F3A5F,
    0xFF1B0B2E to 0xFFFF6AD5, 0xFFFFF4E0 to 0xFFC0392B, 0xFF0F2A1D to 0xFFB8F2C9, 0xFF101010 to 0xFFFF7A50,
).map { (a, b) -> a.toInt() to b.toInt() }

// ---------------------------------------------------------------- about

@Composable
private fun About() {
    var licenses by remember { mutableStateOf(false) }
    Section("About") {
        SegmentedListItem(
            onClick = {},
            shapes = ListItemDefaults.segmentedShapes(0, 5),
            colors = segColors(),
            leadingContent = { Badge(Icons.Rounded.Info) },
            supportingContent = {
                Text("PadBridge's keyboard as your phone's keyboard. What you type never leaves the phone: the only " +
                    "network use is checking GitHub for updates, from this screen.")
            },
        ) { Text("PadKeys ${BuildConfig.VERSION_NAME}") }
        val activity = LocalActivity.current
        SegmentedListItem(
            onClick = { activity?.let { onUpdateRowClick(it) } },
            shapes = ListItemDefaults.segmentedShapes(1, 5),
            colors = segColors(),
            leadingContent = { Badge(Icons.Rounded.SystemUpdate) },
            supportingContent = { Text(updateSubtitle(Updates.state)) },
        ) { Text("Check for updates") }
        var auto by remember { mutableStateOf(Prefs.autoUpdateCheck) }
        SwitchItem(2, 5, Icons.Rounded.Update, "Check automatically",
            "Look for a new version once a day when you open this screen", auto) { auto = it; Prefs.autoUpdateCheck = it }
        SegmentedListItem(
            onClick = {},
            shapes = ListItemDefaults.segmentedShapes(3, 5),
            colors = segColors(),
            leadingContent = { Badge(Icons.Rounded.MenuBook) },
            supportingContent = { Text("From wordfreq by Robyn Speer, CC BY-SA 4.0. Corrections come from your phone's spell checker.") },
        ) { Text("Word list") }
        SegmentedListItem(
            onClick = { licenses = true },
            shapes = ListItemDefaults.segmentedShapes(4, 5),
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
