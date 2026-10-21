package io.ofloo.pagerapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ofloo.pagerapp.shared.ActiveWindow
import java.time.LocalTime
import java.util.Locale

/** Edits a list of local-time windows; used for the general schedule and per-level overrides. */
@Composable
internal fun WindowListEditor(
    windows: List<ActiveWindow>,
    onChange: (List<ActiveWindow>) -> Unit,
    addLabel: String = "Add window",
    minWindows: Int = 0,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        windows.forEachIndexed { index, window ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = clock(window.start),
                    onValueChange = { value ->
                        parseTime(value)?.let { time ->
                            val updated = windows.toMutableList()
                            updated[index] = window.copy(start = time)
                            onChange(updated)
                        }
                    },
                    label = { Text(tr("start")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = clock(window.end),
                    onValueChange = { value ->
                        parseTime(value)?.let { time ->
                            val updated = windows.toMutableList()
                            updated[index] = window.copy(end = time)
                            onChange(updated)
                        }
                    },
                    label = { Text(tr("end")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = windows.size > minWindows,
                    onClick = { onChange(windows.filterIndexed { i, _ -> i != index }) },
                ) { Text(tr("Remove")) }
            }
        }
        if (windows.isEmpty()) {
            Text(
                "No custom hours yet: this level falls back to the general schedule.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        OutlinedButton(onClick = { onChange(windows + defaultWindow()) }) { Text(addLabel) }
        Text(
            "Format HH:MM, device local time. A window that ends before it starts wraps midnight.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun clock(time: LocalTime): String = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)

internal fun parseTime(value: String): LocalTime? =
    runCatching { LocalTime.parse(value.trim()) }.getOrNull()

internal fun defaultWindow() = ActiveWindow(LocalTime.of(7, 0), LocalTime.of(23, 0))
