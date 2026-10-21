package io.ofloo.pagerapp.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.shared.ActiveWindow
import io.ofloo.pagerapp.shared.NotifyAction
import io.ofloo.pagerapp.shared.StatusRule
import io.ofloo.pagerapp.shared.STATUS_DEBUG
import io.ofloo.pagerapp.shared.STATUS_MESSAGE
import io.ofloo.pagerapp.shared.TimeBound
import io.ofloo.pagerapp.shared.VibrationPattern
import io.ofloo.pagerapp.shared.defaultRuleFor
import io.ofloo.pagerapp.shared.sanitizeLevelOrder
import io.ofloo.pagerapp.shared.statusForUrgency

/**
 * Per-level configuration: action, vibration and schedule. A level either follows the general
 * active hours from Settings or has its own windows; custom codes can be added too.
 */
@Composable
fun RulesScreen(store: Store) {
    var config by remember { mutableStateOf(store.config) }
    var customInput by remember { mutableStateOf("") }
    val levelOrder = remember(config.levelOrder) { sanitizeLevelOrder(config.levelOrder) }
    val otherStatuses = remember(config) {
        (config.statusRules.keys + (4..5) + STATUS_MESSAGE)
            .filterNot { it in setOf(0, 1, 2, 3, STATUS_DEBUG) }
            .toSortedSet()
    }

    fun update(status: Int, rule: StatusRule) {
        config = config.copy(statusRules = config.statusRules + (status to rule))
        store.config = config
    }

    fun moveLevel(from: Int, to: Int) {
        if (to !in levelOrder.indices) return
        val order = levelOrder.toMutableList()
        val level = order.removeAt(from)
        order.add(to, level)
        config = config.copy(levelOrder = order)
        store.config = config
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Section("Levels")
            Text(
                "Top is your highest rank. Move a level up or down; each card keeps its own " +
                    "action, vibration and schedule.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        itemsIndexed(levelOrder) { index, level ->
            val status = statusForUrgency(level) ?: return@itemsIndexed
            val rule = config.statusRules[status] ?: defaultRuleFor(status)
            RuleCard(
                title = "${index + 1} · ${levelTitle(level)}",
                subtitle = "status $status · urgency level",
                rule = rule,
                generalWindows = config.activeWindows,
                onChange = { update(status, it) },
                onMoveUp = if (index > 0) {
                    { moveLevel(index, index - 1) }
                } else {
                    null
                },
                onMoveDown = if (index < levelOrder.lastIndex) {
                    { moveLevel(index, index + 1) }
                } else {
                    null
                },
            )
        }

        item {
            Spacer(Modifier.height(16.dp))
            Section("Other statuses")
        }
        items(otherStatuses.toList()) { status ->
            val rule = config.statusRules[status] ?: defaultRuleFor(status)
            RuleCard(
                title = statusTitle(status),
                rule = rule,
                generalWindows = config.activeWindows,
                onChange = { update(status, it) },
            )
        }

        item {
            Spacer(Modifier.height(12.dp))
            Section("Add a custom status (6–99)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it.filter(Char::isDigit).take(2) },
                    label = { Text(tr("code")) },
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
                ) { Text(tr("Add")) }
            }
        }
    }
}

private fun levelTitle(level: String): String = level.replaceFirstChar { it.uppercase() }

private fun statusTitle(status: Int): String = when (status) {
    4 -> "4 · In Progress"
    5 -> "5 · Queued"
    6 -> "6 · Acknowledged"
    STATUS_MESSAGE -> "10 · Chat"
    else -> "status $status"
}

@Composable
private fun RuleCard(
    title: String,
    subtitle: String? = null,
    rule: StatusRule,
    generalWindows: List<ActiveWindow>,
    onChange: (StatusRule) -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked = result.data?.getParcelableExtra<Uri>(
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
            )
            val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val chosen = when {
                picked == null -> "none"
                default != null && picked.toString() == default.toString() -> null
                else -> picked.toString()
            }
            onChange(rule.copy(soundUri = chosen))
        }
    }

    fun chooseSound() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            )
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                rule.soundUri?.takeIf { it != "none" }?.let(Uri::parse),
            )
        }
        runCatching { ringtonePicker.launch(intent) }
    }

    val soundValue = when {
        rule.soundUri == null -> "default"
        rule.soundUri == "none" -> "none"
        else -> "custom"
    }
    val soundTitle = when (soundValue) {
        "default" -> "default"
        "none" -> "none"
        else -> runCatching {
            RingtoneManager.getRingtone(context, Uri.parse(rule.soundUri))?.getTitle(context)
        }.getOrNull() ?: "custom"
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onMoveUp != null || onMoveDown != null) {
                    Row {
                        TextButton(enabled = onMoveUp != null, onClick = { onMoveUp?.invoke() }) {
                            Text(tr("Up"))
                        }
                        TextButton(enabled = onMoveDown != null, onClick = { onMoveDown?.invoke() }) {
                            Text(tr("Down"))
                        }
                    }
                }
            }
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
                    label = "vibrate",
                    value = rule.vibration,
                    options = VibrationPattern.entries,
                    display = { it.name.lowercase() },
                    onSelect = { onChange(rule.copy(vibration = it)) },
                )
            }
            Spacer(Modifier.height(4.dp))
            Picker(
                label = "sound",
                value = soundValue,
                options = listOf("default", "none", "custom"),
                display = {
                    when (it) {
                        "default" -> "default"
                        "none" -> "none"
                        else -> if (soundValue == "custom") soundTitle else "choose…"
                    }
                },
                onSelect = {
                    when (it) {
                        "default" -> onChange(rule.copy(soundUri = null))
                        "none" -> onChange(rule.copy(soundUri = "none"))
                        else -> chooseSound()
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            Picker(
                label = "alert",
                value = rule.repeat,
                options = listOf(false, true),
                display = { if (it) "continue until ACK" else "once" },
                onSelect = { onChange(rule.copy(repeat = it)) },
            )
            Spacer(Modifier.height(4.dp))
            Picker(
                label = "schedule",
                value = scheduleOf(rule),
                options = ScheduleChoice.entries,
                display = {
                    when (it) {
                        ScheduleChoice.ALWAYS -> "always"
                        ScheduleChoice.GENERAL -> "general schedule"
                        ScheduleChoice.CUSTOM -> "custom for this level"
                        ScheduleChoice.NEVER -> "never"
                    }
                },
                onSelect = { onChange(rule.withSchedule(it, generalWindows)) },
            )
            if (scheduleOf(rule) == ScheduleChoice.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                Text(tr("Custom hours for $title"), fontWeight = FontWeight.Bold)
                WindowListEditor(
                    windows = rule.customWindows,
                    onChange = { onChange(rule.copy(customWindows = it)) },
                    addLabel = "Add custom window",
                )
            }
        }
    }
}

private enum class ScheduleChoice { ALWAYS, GENERAL, CUSTOM, NEVER }

private fun scheduleOf(rule: StatusRule): ScheduleChoice = when {
    rule.timeBound == TimeBound.ALWAYS -> ScheduleChoice.ALWAYS
    rule.timeBound == TimeBound.NEVER -> ScheduleChoice.NEVER
    rule.followsGeneralSchedule -> ScheduleChoice.GENERAL
    else -> ScheduleChoice.CUSTOM
}

private fun StatusRule.withSchedule(
    choice: ScheduleChoice,
    generalWindows: List<ActiveWindow>,
): StatusRule = when (choice) {
    ScheduleChoice.ALWAYS -> copy(timeBound = TimeBound.ALWAYS)
    ScheduleChoice.GENERAL -> copy(timeBound = TimeBound.ACTIVE_HOURS, followsGeneralSchedule = true)
    ScheduleChoice.CUSTOM -> copy(
        timeBound = TimeBound.ACTIVE_HOURS,
        followsGeneralSchedule = false,
        customWindows = customWindows.ifEmpty { generalWindows },
    )
    ScheduleChoice.NEVER -> copy(timeBound = TimeBound.NEVER)
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
