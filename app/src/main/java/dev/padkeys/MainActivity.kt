package dev.padkeys

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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

    /** Asked by the keyboard's mic: once answered, go back to the app being typed in. */
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Denied for good: Android won't ask again, so open App info to allow it there.
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
        if (fromKeyboard) { fromKeyboard = false; moveTaskToBack(true) }
    }
    private var fromKeyboard = false

    private fun handle(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_ASK_MIC, false) != true) return
        i.removeExtra(EXTRA_ASK_MIC)
        fromKeyboard = i.getBooleanExtra(EXTRA_RETURN, false)
        askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Haptics.init(this)
        Predict.init(this)
        setContent { PadKeysTheme { HomeScreen(setupTick.intValue) } }
        if (savedInstanceState == null) handle(intent)
    }

    private var updateChecked = false

    override fun onResume() {
        super.onResume()
        setupTick.intValue++
        if (!updateChecked) { updateChecked = true; Updater.autoCheck(this) }
        if (Updates.state is UpdateState.Failed) Updates.state = UpdateState.Idle
    }

    companion object {
        /** Started by the keyboard's mic to ask for the microphone permission. */
        const val EXTRA_ASK_MIC = "askMic"
        /** Go back to the app being typed in once answered. */
        const val EXTRA_RETURN = "returnAfter"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The input method picker is a dialog over this activity: focus comes back when it closes.
        if (hasFocus) setupTick.intValue++
    }
}
