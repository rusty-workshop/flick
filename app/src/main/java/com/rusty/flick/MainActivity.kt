package com.rusty.flick

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        setContent { MaterialTheme { Home() } }
    }

    override fun onResume() {
        super.onResume()
        nfcOn.value = NfcAdapter.getDefaultAdapter(this)?.isEnabled == true
    }
}

/** Re-checked on every resume, so coming back from NFC settings clears the warning. */
val nfcOn = mutableStateOf(true)

val Accent = Color(0xFF9C7BFF)
val Muted = Color(0xFFAAAAAA)
val Bad = Color(0xFFE57373)

@Composable
fun Home() {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val state by Store.state.collectAsState()
    var syncing by remember { mutableStateOf(false) }
    val list = rememberScalingLazyListState()

    fun sync() {
        if (syncing) return
        syncing = true
        scope.launch { Store.sync(ctx); syncing = false }
    }

    LaunchedEffect(Unit) { sync() }

    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(list) }) {
        ScalingLazyColumn(Modifier.fillMaxSize(), state = list) {
            item { Status(state.active, state.beams) }

            if (!nfcOn.value) item {
                Chip(
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                    label = { Text("NFC is off") },
                    secondaryLabel = { Text("Tap to turn it on") },
                    colors = ChipDefaults.chipColors(backgroundColor = Bad.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.presets.isEmpty()) item {
                Text(
                    if (syncing) "Loading presets…" else "No presets yet.\nAdd some from your browser:\n${BuildConfig.SERVER_LAN.removePrefix("http://")}",
                    color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            items(state.presets, key = { it.id }) { p ->
                val on = p.id == state.activeId
                Chip(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        Store.select(ctx, if (on) "" else p.id)
                    },
                    icon = { Text(p.emoji.ifBlank { if (p.kind == "text") "💬" else "🔗" }, fontSize = 18.sp) },
                    label = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(pretty(p), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = if (on) ChipDefaults.chipColors(backgroundColor = Accent, contentColor = Color.Black, secondaryContentColor = Color.Black.copy(alpha = 0.7f), iconColor = Color.Black)
                    else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.active != null) item {
                Chip(
                    onClick = { Store.select(ctx, "") },
                    label = { Text("Stop beaming") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Chip(
                    onClick = { sync() },
                    label = { Text(if (syncing) "Syncing…" else "Sync presets") },
                    secondaryLabel = {
                        Text(
                            state.syncError ?: if (state.lastSync == 0L) "Never synced" else "Synced " + DateUtils.getRelativeTimeSpanString(state.lastSync),
                            color = if (state.syncError != null) Bad else Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Status(active: Preset?, beams: Int) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (active == null) {
            Text("Not beaming", color = Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Pick a preset", color = Muted, fontSize = 11.sp)
        } else {
            Text("⚡ Beaming", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(active.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (beams > 0) "Hold to a phone · $beams flicked" else "Hold the watch face to a phone",
                color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

private fun pretty(p: Preset) =
    if (p.kind == "text") p.value else p.value.removePrefix("https://").removePrefix("http://").removePrefix("www.")
