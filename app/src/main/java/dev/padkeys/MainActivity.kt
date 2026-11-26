package dev.padkeys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import dev.padkeys.ui.HomeScreen
import dev.padkeys.ui.PadKeysTheme

/**
 * Setup, settings and a field to try the keyboard in. Also the "settings" entry Android shows for PadKeys in
 * Settings › On-screen keyboards (method.xml's settingsActivity).
 */
class MainActivity : ComponentActivity() {
    /** Bumped when the app comes back (from system settings, or the keyboard picker closes) to re-check setup. */
    private val setupTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Haptics.init(this)
        Predict.init(this)
        setContent { PadKeysTheme { HomeScreen(setupTick.intValue) } }
    }

    private var updateChecked = false

    override fun onResume() {
        super.onResume()
        setupTick.intValue++
        if (!updateChecked) { updateChecked = true; Updater.autoCheck(this) }
        if (Updates.state is UpdateState.Failed) Updates.state = UpdateState.Idle
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The input method picker is a dialog over this activity: focus comes back when it closes.
        if (hasFocus) setupTick.intValue++
    }
}
