package com.wax.module.modern

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Pure JVM regression coverage for Manager-owned durable profile snapshots. */
class ControlCenterProfilesTest {
    private class MemoryPrefs : InvocationHandler {
        val values = linkedMapOf<String, Any>()
        var rejectCommit = false
        val prefs: SharedPreferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
                this,
            ) as SharedPreferences

        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<Any?>?,
        ): Any? {
            val arg = args.orEmpty()
            return when (method.name) {
                "getString" -> values[arg[0]] as? String ?: arg[1]

                "getBoolean" -> values[arg[0]] as? Boolean ?: arg[1]

                "getAll" -> values.toMap()

                "contains" -> values.containsKey(arg[0])

                "edit" -> editor()

                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener",
                -> null

                else -> throw UnsupportedOperationException(method.name)
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                val arg = args.orEmpty()
                when (method.name) {
                    "putBoolean", "putString", "putInt", "putLong" -> {
                        pending[arg[0] as String] = arg[1]
                        proxy
                    }

                    "remove" -> {
                        pending[arg[0] as String] = null
                        proxy
                    }

                    "clear" -> {
                        pending.clear()
                        pending.putAll(values.keys.associateWith { null })
                        proxy
                    }

                    "commit" -> {
                        if (rejectCommit) {
                            false
                        } else {
                            for ((key, value) in pending) {
                                if (value == null) values.remove(key) else values[key] = value
                            }
                            true
                        }
                    }

                    "apply" -> {
                        if (!rejectCommit) {
                            for ((key, value) in pending) {
                                if (value == null) values.remove(key) else values[key] = value
                            }
                        }
                        null
                    }

                    else -> {
                        throw UnsupportedOperationException(method.name)
                    }
                }
            } as SharedPreferences.Editor
        }
    }

    @Test fun upgradesExistingDesiredSettingsIntoDefaultProfileWithoutOverwritingThem() {
        val p = MemoryPrefs()
        p.values["hideread"] = true
        p.values["typearchive"] = "2"
        val state = ControlCenterProfiles.read(p.prefs)
        assertFalse(state.corrupted)
        assertEquals("default", state.activeId)
        assertEquals(true, p.values["hideread"])
        assertEquals("2", p.values["typearchive"])
        assertFalse(p.values.containsKey(ControlCenterProfiles.KEY))
    }

    @Test fun profileSwitchRestoresPersistedFlagsModesAndFavorites() {
        val p = MemoryPrefs()
        p.values["hideread"] = true
        p.values["antirevoke"] = "2"
        p.values[ModernTargetTelemetryProvider.CONTROL_CENTER_FAVORITES_KEY] = "anti_revoke"
        assertTrue(ControlCenterProfiles.create(p.prefs, "Work"))
        val work =
            ControlCenterProfiles
                .read(p.prefs)
                .profiles
                .last()
                .id
        p.values["hideread"] = false
        p.values["antirevoke"] = "0"
        p.values[ModernTargetTelemetryProvider.CONTROL_CENTER_FAVORITES_KEY] = "view_once"
        assertTrue(ControlCenterProfiles.select(p.prefs, work))
        assertEquals(true, p.values["hideread"])
        assertEquals("2", p.values["antirevoke"])
        assertEquals("anti_revoke", p.values[ModernTargetTelemetryProvider.CONTROL_CENTER_FAVORITES_KEY])
        assertTrue(ControlCenterProfiles.select(p.prefs, "default"))
        assertEquals(false, p.values["hideread"])
        assertEquals("0", p.values["antirevoke"])
        assertEquals("view_once", p.values[ModernTargetTelemetryProvider.CONTROL_CENTER_FAVORITES_KEY])
    }

    @Test fun invalidNamesAreRejectedAndBuiltInOrActiveCannotBeDeleted() {
        val p = MemoryPrefs()
        assertFalse(ControlCenterProfiles.create(p.prefs, " "))
        assertFalse(ControlCenterProfiles.create(p.prefs, "x".repeat(41)))
        assertTrue(ControlCenterProfiles.create(p.prefs, "Work"))
        assertFalse(ControlCenterProfiles.create(p.prefs, "WORK"))
        assertFalse(ControlCenterProfiles.rename(p.prefs, "default", "Renamed"))
        assertFalse(ControlCenterProfiles.delete(p.prefs, "default"))
        val work =
            ControlCenterProfiles
                .read(p.prefs)
                .profiles
                .last()
                .id
        assertTrue(ControlCenterProfiles.select(p.prefs, work))
        assertFalse(ControlCenterProfiles.delete(p.prefs, work))
    }

    @Test fun corruptSchemaFailsClosedWithoutDestroyingExistingSettings() {
        val p = MemoryPrefs()
        p.values["hideread"] = true
        p.values[ControlCenterProfiles.KEY] = "{\"schema\":999,\"profiles\":[]}"
        assertTrue(ControlCenterProfiles.read(p.prefs).corrupted)
        assertFalse(ControlCenterProfiles.create(p.prefs, "Replacement"))
        assertFalse(ControlCenterProfiles.select(p.prefs, "default"))
        assertTrue(p.values["hideread"] as Boolean)
    }

    @Test fun rejectedCommitDoesNotChangeActivePreferences() {
        val p = MemoryPrefs()
        assertTrue(ControlCenterProfiles.create(p.prefs, "Work"))
        val work =
            ControlCenterProfiles
                .read(p.prefs)
                .profiles
                .last()
                .id
        p.values["hideread"] = true
        p.rejectCommit = true
        assertFalse(ControlCenterProfiles.select(p.prefs, work))
        assertTrue(p.values["hideread"] as Boolean)
        assertEquals("default", ControlCenterProfiles.read(p.prefs).activeId)
    }

    @Test fun wrongTypeAndOversizeProfileDocumentsFailClosed() {
        val p = MemoryPrefs()
        p.values["hideread"] = true
        p.values[ControlCenterProfiles.KEY] = 42
        assertTrue(ControlCenterProfiles.read(p.prefs).corrupted)
        assertFalse(ControlCenterProfiles.select(p.prefs, "default"))
        p.values[ControlCenterProfiles.KEY] = "x".repeat(64_001)
        assertTrue(ControlCenterProfiles.read(p.prefs).corrupted)
        assertEquals(true, p.values["hideread"])
    }

    @Test fun duplicateRenameDeleteArePersistedAndGuarded() {
        val p = MemoryPrefs()
        assertTrue(ControlCenterProfiles.create(p.prefs, "Work"))
        val id =
            ControlCenterProfiles
                .read(p.prefs)
                .profiles
                .last()
                .id
        assertTrue(ControlCenterProfiles.duplicate(p.prefs, id, "Vacation"))
        val copied =
            ControlCenterProfiles
                .read(p.prefs)
                .profiles
                .last()
                .id
        assertTrue(ControlCenterProfiles.rename(p.prefs, copied, "Holiday"))
        assertFalse(ControlCenterProfiles.rename(p.prefs, copied, "Work"))
        assertTrue(ControlCenterProfiles.delete(p.prefs, copied))
        assertEquals(2, ControlCenterProfiles.read(p.prefs).profiles.size)
        assertFalse(ControlCenterProfiles.delete(p.prefs, copied))
    }
}
