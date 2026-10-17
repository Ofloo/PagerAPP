package io.ofloo.pagerapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.shared.ActiveWindow
import io.ofloo.pagerapp.shared.NotifyAction
import io.ofloo.pagerapp.shared.StatusRule
import io.ofloo.pagerapp.shared.STATUS_MESSAGE
import io.ofloo.pagerapp.shared.TimeBound
import io.ofloo.pagerapp.shared.VibrationPattern
import io.ofloo.pagerapp.shared.defaultRuleFor
import java.time.LocalTime

/**
 * Per-status configuration. Every status (0-5 defaults, plus any custom code that has been
 * seen) can have its action and time bound changed; the user can also add custom codes.
 */
@Composable
fun RulesScreen(store: Store) {
    var config by remember { mutableStateOf(store.config) }
    var customInput by remember { mutableStateOf("") }
    val defined = remember(config) {
        (config.statusRules.keys + (0..5) + STATUS_MESSAGE).toSortedSet()
    }

    fun update(status: Int, rule: StatusRule) {
        config = config.copy(statusRules = config.statusRules + (status to rule))
        store.config = config
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Text(
                "Per status: what it does, and when it may notify.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        items(defined.toList()) { status ->
            val rule = config.statusRules[status] ?: defaultRuleFor(status)
            RuleCard(
                status = status,
                rule = rule,
                isCustom = config.statusRules.containsKey(status),
                onChange = { update(status, it) },
            )
        }

        item {
            Spacer(Modifier.height(12.dp))
            Section("Add a custom status (6–99)")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it.filter(Char::isDigit).take(2) },
                    label = { Text("code") },
                    singleLine = true,
                    modifier = Modifier.width(120.dp),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    enabled = customInput.toIntOrNull()?.let { it in 6..99 } == true,
                    onClick = {
                        val code = customInput.toInt()
                        config = config.copy(statusRules = config.statusRules + (code to defaultRuleFor(code)))
                        store.config = config
                        customInput = ""
                    },
                ) { Text("Add") }
            }
        }
    }
}

@Composable
private fun RuleCard(
    status: Int,
    rule: StatusRule,
    isCustom: Boolean,
    onChange: (StatusRule) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("status $status", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(
                    label = "action",
                    value = rule.action,
                    options = NotifyAction.entries,
                    display = {
                        when (it) {
                            NotifyAction.SILENT -> "silent"
                            NotifyAction.SOUND_VIBRATE -> "sound+vib"
                            NotifyAction.URGENT -> "urgent"
                        }
                    },
                    onSelect = { onChange(rule.copy(action = it)) },
                )
                Picker(
                    label = "time",
                    value = rule.timeBound,
                    options = TimeBound.entries,
                    display = {
                        when (it) {
                            TimeBound.ALWAYS -> "always"
                            TimeBound.ACTIVE_HOURS -> "active hours"
                            TimeBound.NEVER -> "never"
                        }
                    },
                    onSelect = { onChange(rule.copy(timeBound = it)) },
                )
            }
        }
    }
}

@Composable
internal fun <T> Picker(
    label: String,
    value: T,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { open = true }) { Text(display(value)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = { onSelect(option); open = false },
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
}

/** Shared default window helper for the Settings screen. */
internal fun defaultWindow() = ActiveWindow(LocalTime.of(7, 0), LocalTime.of(23, 0))
