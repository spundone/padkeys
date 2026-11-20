package dev.padkeys.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

/**
 * The one screen: setup (enable, then pick PadKeys), a field to try it in, settings and credits. Setup status is
 * re-read whenever [setupTick] changes (the activity bumps it on resume and when the keyboard picker closes).
 */
@Composable
fun HomeScreen(setupTick: Int) {
    val ctx = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Keyboard, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("PadKeys", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("PadBridge's keyboard, as your phone's keyboard", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val enabled = remember(setupTick) { ctx.imeEnabled() }
            val selected = remember(setupTick) { ctx.imeSelected() }
            Setup(enabled, selected)
            TryIt()
            // Keyed on setupTick: the keyboard's own layout button changes the layout while this screen is open.
            androidx.compose.runtime.key(setupTick) { SettingsCard() }
            About()
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Setup(enabled: Boolean, selected: Boolean) {
    val ctx = LocalContext.current
    SectionCard("Set up") {
        Step(1, "Turn on PadKeys in Settings › On-screen keyboards. Android warns that a keyboard can see what you " +
            "type: PadKeys keeps everything on the phone and has no internet access.", enabled) {
            Button(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text(if (enabled) "Open keyboard settings" else "Turn on PadKeys") }
        }
        Step(2, "Switch to PadKeys.", selected) {
            Button(enabled = enabled, onClick = {
                ctx.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            }) { Text("Choose keyboard") }
        }
        if (enabled && selected) Text("All set. Try it below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Step(n: Int, text: String, done: Boolean, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        if (done) Icon(Icons.Rounded.CheckCircle, "Done", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        else Surface(Modifier.size(28.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
            Text("$n", Modifier.padding(top = 3.dp), style = MaterialTheme.typography.titleSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action()
        }
    }
}

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
    SectionCard("Try it") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TestField.entries.forEach { f ->
                FilterChip(selected = kind == f, onClick = { kind = f }, label = { Text(f.label) })
            }
        }
        // A new field per type, so the keyboard restarts with the new field's options.
        androidx.compose.runtime.key(kind) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Type here") },
                singleLine = kind.singleLine,
                minLines = if (kind.singleLine) 1 else 3,
                keyboardOptions = kind.options,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions { actions++ },
                visualTransformation = if (kind == TestField.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
            )
        }
        if (actions > 0) Text("Enter action performed ×$actions", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LayoutChooser(title: String, landscape: Boolean) {
    var layout by remember { mutableStateOf(Prefs.layout(landscape)) }
    Text(title, style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KbLayout.entries.forEach { l ->
            FilterChip(selected = layout == l, onClick = { layout = l; Prefs.setLayout(landscape, l) }, label = { Text(l.label) })
        }
    }
    Text(layout.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsCard() {
    SectionCard("Keyboard") {
        LayoutChooser("Layout, upright", landscape = false)
        LayoutChooser("Layout, sideways", landscape = true)
        Text("Tap the keyboard button left of the suggestions to switch layouts; hold it to switch keyboards.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        var height by remember { mutableFloatStateOf(Prefs.height) }
        Text("Height  ${(height * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
        Slider(value = height, onValueChange = { height = it }, onValueChangeFinished = { Prefs.height = height },
            valueRange = 0.7f..1.4f, steps = 13)

        HorizontalDivider()
        var flick by remember { mutableStateOf(Prefs.flick) }
        SwitchRow("Flick for symbols", "Flick a key up, or hold it, to type its shifted character (the small one in the corner)", flick) {
            flick = it; Prefs.flick = it
        }
        var cursor by remember { mutableStateOf(Prefs.spaceCursor) }
        SwitchRow("Space-bar cursor", "Drag along the space bar to move the cursor; hold Shift to select", cursor) {
            cursor = it; Prefs.spaceCursor = it
        }
        var preview by remember { mutableStateOf(Prefs.keyPreview) }
        SwitchRow("Key previews", "A big copy of the key above your finger", preview) { preview = it; Prefs.keyPreview = it }
        var caps by remember { mutableStateOf(Prefs.autoCaps) }
        SwitchRow("Capitalize sentences", "Shift is on at the start of a sentence, where the app asks for it", caps) {
            caps = it; Prefs.autoCaps = it
        }

        HorizontalDivider()
        var suggest by remember { mutableStateOf(Prefs.suggestions) }
        SwitchRow("Suggestions", "Completions and spelling corrections above the keys. Never in password fields", suggest) {
            suggest = it; Prefs.suggestions = it
        }
        var learn by remember { mutableStateOf(Prefs.learn) }
        var learned by remember { mutableIntStateOf(Predict.learnedCount) }
        // The keyboard learns while this screen is open (the test field): keep the count current.
        androidx.compose.runtime.DisposableEffect(Unit) {
            val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "learnedWords") learned = Predict.learnedCount
            }
            Prefs.listen(l)
            onDispose { Prefs.unlisten(l) }
        }
        SwitchRow("Learn my words",
            "Remember words you type to suggest them later. Stored only on this phone; never from passwords " +
                "or private (incognito) fields", learn) { learn = it; Prefs.learn = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (learned == 1) "1 word learned" else "$learned words learned", Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var confirm by remember { mutableStateOf(false) }
            OutlinedButton(enabled = learned > 0, onClick = { confirm = true }) { Text("Forget learned words") }
            if (confirm) AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Forget learned words?") },
                text = { Text("PadKeys will stop suggesting the words it learned from your typing.") },
                confirmButton = { TextButton(onClick = { Predict.forget(); learned = 0; confirm = false }) { Text("Forget") } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            )
        }

        HorizontalDivider()
        var haptics by remember { mutableStateOf(Prefs.haptics) }
        SwitchRow("Vibrate on key press", if (Haptics.available) null else "This device has no vibration motor", haptics) {
            haptics = it; Prefs.haptics = it
        }
        if (haptics) {
            var style by remember { mutableIntStateOf(Prefs.hapticStyle) }
            val auto = if (Haptics.rich) "Auto (rich)" else "Auto (basic)"
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(auto, "Rich", "Basic").forEachIndexed { i, label ->
                    FilterChip(selected = style == i, onClick = { style = i; Prefs.hapticStyle = i; Haptics.tick() },
                        label = { Text(label) })
                }
            }
            Text("Rich: crisp ticks for phone-class motors. Basic: short pulses, for motors that can't play ticks.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        var real by remember { mutableStateOf(Prefs.realistic) }
        SwitchRow("Realistic look", "Keys drawn as hardware: silver in light mode, space black in dark", real) {
            real = it; Prefs.realistic = it
        }
    }
}

@Composable
private fun About() {
    var licenses by remember { mutableStateOf(false) }
    SectionCard("About") {
        Text("PadKeys ${BuildConfig.VERSION_NAME}. The keyboard from PadBridge, as a system keyboard. Colors follow " +
            "your wallpaper and light / dark mode. No internet access; nothing leaves the phone.",
            style = MaterialTheme.typography.bodyMedium)
        Text("Word suggestions use an English word list from wordfreq by Robyn Speer (CC BY-SA 4.0) and your " +
            "phone's spell checker.", style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = { licenses = true }) { Text("Open-source licenses") }
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
    Spacer(Modifier.height(8.dp))
}
