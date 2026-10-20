package io.ofloo.pagerapp.data

import io.ofloo.pagerapp.shared.ActiveWindow
import io.ofloo.pagerapp.shared.NotifyAction
import io.ofloo.pagerapp.shared.PagerConfig
import io.ofloo.pagerapp.shared.StatusRule
import io.ofloo.pagerapp.shared.TimeBound
import io.ofloo.pagerapp.shared.VibrationPattern
import io.ofloo.pagerapp.shared.WatchMode
import io.ofloo.pagerapp.shared.defaultChatLevels
import io.ofloo.pagerapp.shared.sanitizeLevelOrder
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/** Serializes [PagerConfig] to/from a compact JSON string for SharedPreferences. */
object ConfigCodec {

    fun encode(config: PagerConfig): String {
        val rules = JSONObject()
        config.statusRules.forEach { (status, rule) ->
            rules.put(
                status.toString(),
                JSONObject()
                    .put("a", rule.action.name)
                    .put("t", rule.timeBound.name)
                    .put("v", rule.vibration.name)
                    .put("g", rule.followsGeneralSchedule)
                    .put("w", windowArray(rule.customWindows))
                    .put("s", rule.soundUri ?: "")
                    .put("r", rule.repeat),
            )
        }
        val windows = JSONArray()
        config.activeWindows.forEach { windows.put("${it.start}-${it.end}") }
        val levels = JSONObject()
        config.chatLevels.forEach { (name, rule) ->
            levels.put(
                name,
                JSONObject()
                    .put("a", rule.action.name)
                    .put("t", rule.timeBound.name)
                    .put("v", rule.vibration.name)
                    .put("g", rule.followsGeneralSchedule)
                    .put("w", windowArray(rule.customWindows))
                    .put("s", rule.soundUri ?: "")
                    .put("r", rule.repeat),
            )
        }
        val order = JSONArray()
        config.levelOrder.forEach { order.put(it) }
        return JSONObject()
            .put("rules", rules)
            .put("windows", windows)
            .put("order", order)
            .put("levels", levels)
            .put("bypass", config.bypassMode)
            .put("silence", config.silenceMode)
            .put("watch", config.watchMode.name)
            .toString()
    }

    fun decode(raw: String?): PagerConfig {
        if (raw.isNullOrBlank()) return PagerConfig()
        return try {
            val o = JSONObject(raw)
            val rules = mutableMapOf<Int, StatusRule>()
            o.optJSONObject("rules")?.let { obj ->
                obj.keys().forEach { key ->
                    val r = obj.getJSONObject(key)
                    val status = key.toIntOrNull() ?: return@forEach
                    rules[status] = StatusRule(
                        action = enumOr(r.optString("a"), NotifyAction.SILENT),
                        timeBound = enumOr(r.optString("t"), TimeBound.NEVER),
                        vibration = enumOr(r.optString("v"), VibrationPattern.SHORT),
                        followsGeneralSchedule = r.optBoolean("g", true),
                        customWindows = parseWindows(r.optJSONArray("w")),
                        soundUri = r.optString("s").takeIf { it.isNotBlank() },
                        repeat = r.optBoolean("r", false),
                    )
                }
            }
            val windows = mutableListOf<ActiveWindow>()
            o.optJSONArray("windows")?.let { arr ->
                for (i in 0 until arr.length()) {
                    parseWindow(arr.optString(i))?.let { windows.add(it) }
                }
            }
            val levels = mutableMapOf<String, StatusRule>()
            o.optJSONObject("levels")?.let { obj ->
                obj.keys().forEach { key ->
                    val r = obj.getJSONObject(key)
                    levels[key] = StatusRule(
                        action = enumOr(r.optString("a"), NotifyAction.SOUND_VIBRATE),
                        timeBound = enumOr(r.optString("t"), TimeBound.ACTIVE_HOURS),
                        vibration = enumOr(r.optString("v"), VibrationPattern.SHORT),
                        followsGeneralSchedule = r.optBoolean("g", true),
                        customWindows = parseWindows(r.optJSONArray("w")),
                        soundUri = r.optString("s").takeIf { it.isNotBlank() },
                        repeat = r.optBoolean("r", false),
                    )
                }
            }
            val order = mutableListOf<String>()
            o.optJSONArray("order")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { order.add(it) }
                }
            }
            PagerConfig(
                statusRules = rules,
                activeWindows = windows.ifEmpty { PagerConfig().activeWindows },
                levelOrder = sanitizeLevelOrder(order),
                bypassMode = o.optBoolean("bypass", false),
                silenceMode = o.optBoolean("silence", false),
                watchMode = enumOr(o.optString("watch"), WatchMode.OFF),
                chatLevels = levels.ifEmpty { defaultChatLevels() },
            )
        } catch (_: Exception) {
            PagerConfig()
        }
    }

    private fun windowArray(windows: List<ActiveWindow>): JSONArray {
        val arr = JSONArray()
        windows.forEach { arr.put("${it.start}-${it.end}") }
        return arr
    }

    private fun parseWindows(arr: JSONArray?): List<ActiveWindow> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { parseWindow(arr.optString(it)) }
    }

    private fun parseWindow(s: String): ActiveWindow? {
        val parts = s.split("-")
        if (parts.size != 2) return null
        return try {
            ActiveWindow(LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim()))
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(default)
}
