package com.wax.module.modern

import android.content.SharedPreferences
import com.wax.module.platform.JsonValue
import com.wax.module.platform.MiniJson
import java.util.UUID

/**
 * One durable, Manager-owned profile repository shared with the authenticated
 * embedded WhatsApp panel. Profile snapshots include ONLY the Control Center
 * allowlisted setting values, never chat data, credentials or contacts.
 *
 * Current modern pilot preferences are globally scoped in Manager. Thus these
 * profiles are explicitly global, not per-WhatsApp-account/Android-user.
 */
object ControlCenterProfiles {
    const val KEY = "wax.control_center.profiles.v1"
    const val DEFAULT_ID = "default"
    private const val SCHEMA = 1
    private const val MAX_PROFILES = 16
    private const val MAX_NAME = 40

    private val modeKeys = setOf("typearchive", "antirevoke")
    private val favoriteKey = ModernTargetTelemetryProvider.CONTROL_CENTER_FAVORITES_KEY
    private val booleanKeys = ModernTargetTelemetryProvider.CONTROL_CENTER_PREFERENCE_KEYS.toSet()

    /** Shared key contract for Manager UI and cross-process preference signals. */
    fun affectsProfile(key: String?): Boolean =
        key == null || key == KEY || key == favoriteKey || key in modeKeys || key in booleanKeys

    data class Profile(
        val id: String,
        val name: String,
        val settings: Map<String, JsonValue>,
    )

    data class State(
        val activeId: String,
        val revision: Long,
        val profiles: List<Profile>,
        val corrupted: Boolean = false,
    )

    @JvmStatic
    @Synchronized
    fun read(prefs: SharedPreferences): State {
        // Do not mistake a wrong-type or oversized persisted profile document
        // for a fresh install; that would erase the user's saved profiles.
        val stored = prefs.all[KEY]
        if (stored != null && (stored !is String || stored.length > 64_000)) return corrupt()
        val raw = stored as? String
        if (raw == null) return State(DEFAULT_ID, 0, listOf(
            Profile(DEFAULT_ID, "Default", capture(prefs)),
        ))
        val root = (MiniJson.parse(raw) as? JsonValue.Obj)?.fields
            ?: return corrupt()
        if ((root["schema"] as? JsonValue.Num)?.value != SCHEMA.toDouble()) return corrupt()
        val active = (root["active"] as? JsonValue.Str)?.value ?: return corrupt()
        val rev = (root["revision"] as? JsonValue.Num)?.value?.toLong() ?: return corrupt()
        val entries = (root["profiles"] as? JsonValue.Arr)?.items ?: return corrupt()
        if (rev < 0 || entries.isEmpty() || entries.size > MAX_PROFILES) return corrupt()
        val profiles = entries.map { rawEntry ->
            val obj = (rawEntry as? JsonValue.Obj)?.fields ?: return corrupt()
            val id = (obj["id"] as? JsonValue.Str)?.value ?: return corrupt()
            val name = (obj["name"] as? JsonValue.Str)?.value ?: return corrupt()
            val settings = (obj["settings"] as? JsonValue.Obj)?.fields ?: return corrupt()
            if (!validId(id) || !validName(name) || !validSettings(settings)) return corrupt()
            Profile(id, name, settings)
        }
        if (profiles.distinctBy { it.id }.size != profiles.size ||
            profiles.none { it.id == DEFAULT_ID } ||
            profiles.none { it.id == active }) return corrupt()
        // The active profile's desired preferences are authoritative in the
        // Manager's existing SharedPreferences, including edits from either UI.
        return State(active, rev, profiles.map {
            if (it.id == active) it.copy(settings = capture(prefs)) else it
        })
    }

    @Synchronized
    fun create(prefs: SharedPreferences, name: String): Boolean {
        val state = read(prefs)
        if (state.corrupted || !validName(name) || state.profiles.size >= MAX_PROFILES ||
            state.profiles.any { it.name.equals(name.trim(), ignoreCase = true) }) return false
        val new = Profile("p_" + UUID.randomUUID().toString().replace("-", ""), name.trim(), capture(prefs))
        return commitState(prefs, state.copy(profiles = state.profiles + new))
    }

    @Synchronized
    fun rename(prefs: SharedPreferences, id: String, name: String): Boolean {
        val state = read(prefs)
        if (state.corrupted || id == DEFAULT_ID || !validName(name) ||
            state.profiles.none { it.id == id } ||
            state.profiles.any { it.id != id && it.name.equals(name.trim(), true) }) return false
        return commitState(prefs, state.copy(profiles =
            state.profiles.map { if (it.id == id) it.copy(name = name.trim()) else it }))
    }

    @Synchronized
    fun duplicate(prefs: SharedPreferences, id: String, name: String): Boolean {
        val state = read(prefs)
        val original = state.profiles.firstOrNull { it.id == id } ?: return false
        if (state.corrupted || state.profiles.size >= MAX_PROFILES ||
            !validName(name) || state.profiles.any { it.name.equals(name.trim(), true) }) return false
        return commitState(prefs, state.copy(profiles = state.profiles +
            original.copy(id = "p_" + UUID.randomUUID().toString().replace("-", ""), name = name.trim())))
    }

    @Synchronized
    fun delete(prefs: SharedPreferences, id: String): Boolean {
        val state = read(prefs)
        if (state.corrupted || id == DEFAULT_ID || id == state.activeId ||
            state.profiles.none { it.id == id }) return false
        return commitState(prefs, state.copy(profiles = state.profiles.filterNot { it.id == id }))
    }

    /** One commit applies all allowed desired values and the active profile. */
    @JvmStatic
    @Synchronized
    fun select(prefs: SharedPreferences, id: String): Boolean {
        val state = read(prefs)
        if (state.corrupted || !validId(id)) return false
        val selected = state.profiles.firstOrNull { it.id == id } ?: return false
        if (id == state.activeId) return true
        val editor = prefs.edit()
        for (key in booleanKeys) {
            val enabled = (selected.settings[key] as? JsonValue.Flag)?.value ?: false
            editor.putBoolean(key, enabled)
        }
        for (key in modeKeys) {
            val value = (selected.settings[key] as? JsonValue.Str)?.value ?: "0"
            editor.putString(key, value)
        }
        editor.putString(favoriteKey,
            (selected.settings[favoriteKey] as? JsonValue.Str)?.value.orEmpty())
        editor.putString(KEY, encode(state.copy(activeId = id, revision = state.revision + 1)))
        val saved = editor.commit()
        if (saved) return true
        // SharedPreferences can change its in-memory map even if the disk
        // commit fails. Best-effort compensation protects against a half
        // switched session; callers still receive a failure, never "applied".
        val rollback = prefs.edit()
        val previous = state.profiles.firstOrNull { it.id == state.activeId }?.settings
        if (previous != null) {
            for (key in booleanKeys) rollback.putBoolean(
                key, (previous[key] as? JsonValue.Flag)?.value ?: false,
            )
            for (key in modeKeys) rollback.putString(
                key, (previous[key] as? JsonValue.Str)?.value ?: "0",
            )
            rollback.putString(favoriteKey,
                (previous[favoriteKey] as? JsonValue.Str)?.value.orEmpty())
            rollback.putString(KEY, encode(state))
            rollback.commit()
        }
        return false
    }

    /** Manager modifications remain authoritative and are captured on next
     * profile operation, even if the process was killed since the last write. */
    private fun capture(prefs: SharedPreferences): Map<String, JsonValue> {
        val values = prefs.all
        val saved = linkedMapOf<String, JsonValue>()
        for (key in booleanKeys) saved[key] = JsonValue.Flag(values[key] == true)
        for (key in modeKeys) {
            val raw = values[key]
            saved[key] = JsonValue.Str(if (raw == "1" || raw == "2") raw as String
                else if (raw == true) "1" else "0")
        }
        val favorites = values[favoriteKey] as? String ?: ""
        saved[favoriteKey] = JsonValue.Str(
            if (ModernTargetTelemetryProvider.isValidFavorites(favorites)) favorites else "",
        )
        return saved
    }

    private fun commitState(prefs: SharedPreferences, state: State): Boolean =
        prefs.edit().putString(KEY, encode(state.copy(revision = state.revision + 1))).commit()

    private fun encode(state: State): String =
        MiniJson.write(JsonValue.Obj(mapOf(
            "schema" to JsonValue.Num(SCHEMA.toDouble()),
            "revision" to JsonValue.Num(state.revision.toDouble()),
            "active" to JsonValue.Str(state.activeId),
            "profiles" to JsonValue.Arr(state.profiles.map { profile ->
                JsonValue.Obj(mapOf(
                    "id" to JsonValue.Str(profile.id),
                    "name" to JsonValue.Str(profile.name),
                    "settings" to JsonValue.Obj(profile.settings),
                ))
            }),
        )))

    private fun validSettings(settings: Map<String, JsonValue>): Boolean {
        if (settings.keys.any { it !in booleanKeys && it !in modeKeys && it != favoriteKey }) return false
        return settings.all { (key, value) ->
            when {
                key in booleanKeys -> value is JsonValue.Flag
                key in modeKeys -> value is JsonValue.Str && value.value in setOf("0", "1", "2")
                key == favoriteKey -> value is JsonValue.Str &&
                    ModernTargetTelemetryProvider.isValidFavorites(value.value)
                else -> false
            }
        }
    }

    private fun validName(value: String): Boolean =
        value.isNotBlank() && value.trim().length <= MAX_NAME &&
            value.none { it.isISOControl() }

    private fun validId(value: String): Boolean =
        value == DEFAULT_ID || value.matches(Regex("p_[a-f0-9]{32}"))

    private fun corrupt(): State = State(DEFAULT_ID, 0, emptyList(), corrupted = true)
}
