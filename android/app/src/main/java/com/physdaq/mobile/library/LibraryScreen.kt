package com.physdaq.mobile.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.physdaq.mobile.ui.theme.Mono
import com.physdaq.mobile.ui.theme.PhysDaqColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max

/** What is known about one file's contents. */
private sealed class FileState {
    object Loading : FileState()
    class Done(val analysis: RecordingAnalysis) : FileState()
    class Failed(val reason: String) : FileState()
}

/**
 * The session database: every recording on the phone, grouped into sessions,
 * with a detail view per node and a picker for CSVs from anywhere else.
 */
@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var files by remember { mutableStateOf<List<LibraryFile>?>(null) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<SessionGroup?>(null) }
    var toDelete by remember { mutableStateOf<SessionGroup?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val states = remember { mutableStateMapOf<String, FileState>() }

    val refresh: () -> Unit = {
        scope.launch {
            files = withContext(Dispatchers.IO) {
                try { LibraryRepository.list(context) } catch (e: Exception) { emptyList() }
            }
        }
    }

    fun load(file: LibraryFile) {
        if (states[file.uri] != null) return
        states[file.uri] = FileState.Loading
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    LibraryRepository.open(context, file).use { s ->
                        when (val r = CsvAnalyzer.analyze(InputStreamReader(s, Charsets.UTF_8))) {
                            is CsvAnalyzer.Result.Ok -> FileState.Done(r.analysis)
                            is CsvAnalyzer.Result.Unsupported -> FileState.Failed(r.reason)
                        }
                    }
                } catch (e: Exception) {
                    FileState.Failed("Could not read the file: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            states[file.uri] = result
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val f = LibraryRepository.describePicked(context, uri)
            val g = SessionGroups.group(listOf(f)).first()
            states.remove(f.uri)
            picked = g
            selectedKey = null
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val groups = remember(files) { SessionGroups.group(files ?: emptyList()) }
    // Read every file once so the list can show durations (each read runs in the background).
    LaunchedEffect(groups) { groups.forEach { g -> g.members.forEach { load(it.file) } } }

    val open: SessionGroup? = picked ?: groups.firstOrNull { it.key == selectedKey }
    BackHandler(enabled = open != null) { picked = null; selectedKey = null }

    toDelete?.let { g ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete session?") },
            text = {
                Text(
                    "${g.title}: ${g.members.size} file" + (if (g.members.size == 1) "" else "s") +
                        " will be removed from this phone. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        val failed = withContext(Dispatchers.IO) {
                            g.members.count { !LibraryRepository.delete(context, it.file) }
                        }
                        notice = if (failed == 0) "Deleted ${g.title}" else "$failed file(s) could not be deleted"
                        selectedKey = null
                        refresh()
                    }
                }) { Text("Delete", color = PhysDaqColors.Red) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (open == null) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label("Session database (${groups.count { it.isRecording }})")
                        Text(
                            "Recordings made with this app, newest first. Files from before a reinstall, " +
                                "or from the desktop app, can be opened with the button below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = { refresh() }) { Text("Refresh") }
                            OutlinedButton(
                                shape = RoundedCornerShape(10.dp),
                                onClick = { picker.launch(arrayOf("*/*")) }
                            ) { Text("Open CSV file...") }
                        }
                        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PhysDaqColors.Amber) }
                    }
                }
            }
            if (files != null && groups.isEmpty()) {
                item {
                    Text(
                        "No recordings yet. Record a session on the Live tab and it will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(groups, key = { it.key }) { g ->
                SessionRow(g, states, onOpen = { selectedKey = g.key }, onDelete = { toDelete = g })
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        shape = RoundedCornerShape(10.dp),
                        onClick = { picked = null; selectedKey = null }
                    ) { Text("Back") }
                    if (picked == null) {
                        OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = { toDelete = open }) {
                            Text("Delete", color = PhysDaqColors.Red)
                        }
                    }
                }
            }
            item {
                Column {
                    Text(open.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        dateText(open),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(open.members, key = { it.file.uri }) { m ->
                LaunchedEffect(m.file.uri) { load(m.file) }
                MemberCard(m, states[m.file.uri] ?: FileState.Loading)
            }
        }
    }
}

@Composable
private fun SessionRow(
    g: SessionGroup,
    states: Map<String, FileState>,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val done = g.members.mapNotNull { (states[it.file.uri] as? FileState.Done)?.analysis?.summary }
    val pending = g.members.any { states[it.file.uri] == null || states[it.file.uri] is FileState.Loading }
    val duration = when {
        done.isNotEmpty() -> ChartData.formatDuration(done.maxOf { it.durationSec })
        pending -> "..."
        else -> "--"
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(g.title, style = MaterialTheme.typography.titleMedium)
            Text(
                dateText(g) + "  |  " + duration + "  |  " + sizeText(g.sizeBytes),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                g.members.joinToString(", ") { it.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(shape = RoundedCornerShape(10.dp), onClick = onOpen) { Text("Open") }
                OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = onDelete) {
                    Text("Delete", color = PhysDaqColors.Red)
                }
            }
        }
    }
}

@Composable
private fun MemberCard(m: SessionGroup.Member, state: FileState) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(m.label, style = MaterialTheme.typography.titleMedium)
            when (state) {
                is FileState.Loading -> Text(
                    "Reading file...",
                    style = MaterialTheme.typography.bodySmall,
                    color = PhysDaqColors.Mauve
                )
                is FileState.Failed -> Text(
                    state.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PhysDaqColors.Amber
                )
                is FileState.Done -> AnalysisBody(state.analysis)
            }
        }
    }
}

@Composable
private fun ColumnScope.AnalysisBody(a: RecordingAnalysis) {
    val s = a.summary
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Text(
        String.format(Locale.ROOT, "%,d samples  |  %s  |  %.1f Hz", s.samples, ChartData.formatDuration(s.durationSec), s.rateHz),
        style = mono, color = dim
    )
    Text(
        String.format(Locale.ROOT, "Longest gap %.0f ms  |  worn %.0f%%", s.maxGapMs, s.wornPct),
        style = mono, color = dim
    )
    Text(
        if (s.bpmMean == null) "Heart rate: no reading (not worn, or under 8 s of contact)"
        else String.format(Locale.ROOT, "Heart rate: mean %.0f  |  min %.0f  |  max %.0f BPM", s.bpmMean, s.bpmMin, s.bpmMax),
        style = mono, color = dim
    )
    if (s.skippedRows > 0) {
        Text("${s.skippedRows} unreadable row(s) skipped", style = mono, color = PhysDaqColors.Amber)
    }

    if (s.bpmMean != null) {
        StaticChart("Heart rate over time (BPM)", ChartData.downsample(a.bpmTrace, 400), PhysDaqColors.Pink)
    }

    val roll = a.rollTrace
    val pitch = a.pitchTrace
    if (roll != null && pitch != null) {
        StaticChart("Tilt: roll (deg)", ChartData.downsample(roll, 400), PhysDaqColors.Mauve)
        StaticChart("Tilt: pitch (deg)", ChartData.downsample(pitch, 400), PhysDaqColors.Green)
    }

    val windowSec = 10.0
    val maxStart = max(0.0, s.durationSec - windowSec)
    var startSec by remember(a) { mutableStateOf(0f) }
    StaticChart(
        "PPG, filtered (${windowSec.toInt()} s from ${ChartData.formatDuration(startSec.toDouble())})",
        a.filteredWindow(startSec.toDouble(), windowSec),
        MaterialTheme.colorScheme.primary
    )
    if (maxStart > 1.0) {
        Slider(value = startSec, onValueChange = { startSec = it }, valueRange = 0f..maxStart.toFloat())
    }
}

/** A static line chart. NaN values break the line. */
@Composable
private fun StaticChart(title: String, values: FloatArray, color: Color) {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    for (v in values) {
        if (v.isNaN()) continue
        if (v < lo) lo = v
        if (v > hi) hi = v
    }
    val hasData = lo <= hi
    Column {
        Text(
            if (hasData) String.format(Locale.ROOT, "%s   %.0f to %.0f", title, lo, hi) else title,
            style = MaterialTheme.typography.labelMedium
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            if (!hasData || values.size < 2) return@Canvas
            val span = (hi - lo).coerceAtLeast(1f)
            val pad = size.height * 0.08f
            val drawHeight = size.height - 2 * pad
            val path = Path()
            var penDown = false
            for (i in values.indices) {
                val v = values[i]
                if (v.isNaN()) { penDown = false; continue }
                val x = size.width * i / (values.size - 1)
                val y = pad + drawHeight * (1f - (v - lo) / span)
                if (penDown) path.lineTo(x, y) else { path.moveTo(x, y); penDown = true }
            }
            drawPath(path, color, style = Stroke(width = 3f))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun dateText(g: SessionGroup): String =
    g.date?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(it) } ?: "date unknown"

private fun sizeText(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.ROOT, "%d KB", bytes / 1024)
    else -> "$bytes B"
}
