package dev.padkeys.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.padkeys.UpdateState
import dev.padkeys.Updater
import dev.padkeys.Updates

private fun label(v: String) = "PadKeys $v"

/** Shown at the top of the screen while an update is available, downloading or ready. */
@Composable
fun UpdateBanner() {
    val s = Updates.state
    val activity = androidx.activity.compose.LocalActivity.current ?: return
    var notes by remember { mutableStateOf(false) }
    val visible = s is UpdateState.Available || s is UpdateState.Downloading || s is UpdateState.Ready
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SystemUpdate, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (s) {
                                is UpdateState.Available -> "${label(s.version)} is available"
                                is UpdateState.Downloading -> "Downloading the update…"
                                is UpdateState.Ready -> "Installing ${label(s.version)}"
                                else -> ""
                            },
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        )
                        if (s is UpdateState.Ready) Text(
                            "Confirm in Android's prompt.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (s is UpdateState.Downloading) {
                    Spacer(Modifier.size(12.dp))
                    if (s.progress >= 0f) LinearWavyProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                    else LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
                }
                if (s is UpdateState.Available) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        run {
                            // Text buttons in the banner's own color; the default primary is low-contrast on tertiary.
                            val onBanner = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
                            TextButton(onClick = { Updater.skip(s.version) }, colors = onBanner) { Text("Not now") }
                            if (s.notes.isNotBlank()) TextButton(onClick = { notes = true }, colors = onBanner) { Text("What's new") }
                        }
                        Button(
                            onClick = { Updater.start(activity) }, shapes = ButtonDefaults.shapes(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            ),
                        ) { Text("Update") }
                    }
                }
            }
        }
    }
    if (notes && s is UpdateState.Available) AlertDialog(
        onDismissRequest = { notes = false },
        title = { Text("What's new in ${s.version}") },
        text = {
            Text(
                s.notes.replace(Regex("(?m)^#+\\s*"), "").replace("**", ""),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { Button(onClick = { notes = false; Updater.start(activity) }) { Text("Update") } },
        dismissButton = { TextButton(onClick = { notes = false }) { Text("Later") } },
    )
}

/** Settings › About row: current status and a manual check. */
fun updateSubtitle(s: UpdateState): String = when (s) {
    UpdateState.Idle -> "Check ${Updater.SOURCE} for a newer version"
    UpdateState.Checking -> "Checking…"
    UpdateState.UpToDate -> "You have the latest version"
    is UpdateState.Available -> "${label(s.version)} is available. Tap to update"
    is UpdateState.Downloading -> if (s.progress >= 0) "Downloading… ${(s.progress * 100).toInt()}%" else "Downloading…"
    is UpdateState.Ready -> "Waiting for Android's install prompt"
    is UpdateState.Failed -> s.message
}

fun onUpdateRowClick(activity: Activity) {
    val s = Updates.state
    when {
        s is UpdateState.Failed && s.openAppInfo -> try {
            activity.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${activity.packageName}"))
            )
            Updates.state = UpdateState.Idle
        } catch (_: Exception) {}
        s is UpdateState.Available || s is UpdateState.Ready -> Updater.start(activity)
        s is UpdateState.Checking || s is UpdateState.Downloading -> Unit
        else -> Updater.check(activity, manual = true)
    }
}
