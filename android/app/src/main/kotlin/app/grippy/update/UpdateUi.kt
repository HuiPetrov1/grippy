// SPDX-License-Identifier: MPL-2.0
// Grippy's update UI. Not part of upstream Get a Grip.

package app.grippy.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/** A bar across the top of the app while an update is ready, downloading or failed. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val state by UpdateManager.state.collectAsState()
    var dismissedCode by remember { mutableStateOf<Long?>(null) }
    val update = when (val s = state) {
        is UpdateState.Available -> s.update
        is UpdateState.Downloading -> s.update
        is UpdateState.Installing -> s.update
        is UpdateState.Failed -> s.update
        else -> null
    } ?: return
    if (state is UpdateState.Available && dismissedCode == update.versionCode) return

    val palette = LocalGripPalette.current
    val context = LocalContext.current
    InstrumentSurface(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = Metrics.maxContentWidth)
            .fillMaxWidth(),
        color = palette.card,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when (val s = state) {
                            is UpdateState.Downloading -> "Downloading Grippy ${update.versionName}"
                            is UpdateState.Installing -> "Installing Grippy ${update.versionName}"
                            is UpdateState.Failed -> s.message
                            else -> "Grippy ${update.versionName} is ready"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkPrimary,
                    )
                    if (state is UpdateState.Available && update.notes.isNotBlank()) {
                        Text(update.notes, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                    }
                }
                when (state) {
                    is UpdateState.Available -> {
                        TextButton(onClick = { dismissedCode = update.versionCode }) { Text("Later") }
                        TextButton(onClick = { UpdateManager.install(context) }) { Text("Update") }
                    }
                    is UpdateState.Failed -> TextButton(onClick = { UpdateManager.install(context) }) { Text("Retry") }
                    else -> Unit
                }
            }
            val s = state
            if (s is UpdateState.Downloading) {
                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                )
            }
        }
    }
}

/** The status line and action for Settings' Updates card. */
@Composable
fun rememberUpdateStatus(): Pair<String, Pair<String, () -> Unit>?> {
    val state by UpdateManager.state.collectAsState()
    val context = LocalContext.current
    return when (val s = state) {
        UpdateState.Idle -> "Not checked yet" to ("Check for updates" to { UpdateManager.check(context, force = true) })
        UpdateState.Checking -> "Checking…" to null
        UpdateState.UpToDate -> "Up to date" to ("Check again" to { UpdateManager.check(context, force = true) })
        is UpdateState.Available -> "${s.update.versionName} is ready" to ("Update now" to { UpdateManager.install(context) })
        is UpdateState.Downloading -> "Downloading… ${(s.progress * 100).toInt()}%" to null
        is UpdateState.Installing -> "Installing ${s.update.versionName}…" to null
        is UpdateState.Failed -> s.message to ("Try again" to {
            if (s.update != null) UpdateManager.install(context) else UpdateManager.check(context, force = true)
        })
    }
}
