"""Focused regression tests for Issues #394 and #395.

Run: python3 -m unittest discover -s tools/compatibility/tests -p 'test_extract_features.py'
"""
import contextlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

EXTRACTOR_PATH = Path(__file__).resolve().parents[1] / "extract_features.py"
SPEC = importlib.util.spec_from_file_location("wa_x_feature_extractor", EXTRACTOR_PATH)
extractor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(extractor)


class PreferenceReadTests(unittest.TestCase):
    def test_typed_shared_preferences_and_settings_reads(self):
        files = {
            "Demo": """
                package com.wax.module.xposed.features.general
                const val PREF_COUNT = "count_pref"
                fun install() {
                    prefs.getBoolean("bool_key", false)
                    prefs.getString("string_key", null)
                    prefs.getInt(PREF_COUNT, 0)
                    prefs.getLong("long_key", 0)
                    prefs.getFloat("float_key", 0f)
                    prefs.getStringSet("set_key", null)
                    settingsStore.read("typed_key")
                    prefs.contains("contains_key")
                    prefs.edit().putBoolean("write_only", true)
                    // prefs.getString("comment_key", null)
                    /* prefs.getLong("block_comment_key", 0) */
                }
            """
        }
        keys = extractor.feature_preference_keys(files)["Demo"]
        self.assertEqual(
            keys,
            sorted([
                "bool_key", "string_key", "count_pref", "long_key",
                "float_key", "set_key", "typed_key", "contains_key",
            ]),
        )

    def test_no_read_does_not_claim_preference(self):
        files = {
            "WriteOnly": """
                const val PREF_UNUSED = "unused"
                fun install() {
                    prefs.edit().putString("written", "value")
                    // prefs.getBoolean("comment", false)
                }
            """
        }
        self.assertNotIn("WriteOnly", extractor.feature_preference_keys(files))

    def test_constant_reference_is_resolved_through_the_preference_contract(self):
        # `PINNED_LIMIT_PREF_KEY = "pinnedlimit"` is a preference key that no `PREF_*`
        # convention would find. `JSON_AUDIO_URL = "audio_url"` is a JSON field name and
        # must not be recorded as one: the preference XML is what separates the two.
        files = {
            "PinnedLimit": """
                package com.wax.module.xposed.features.general
                const val PINNED_LIMIT_PREF_KEY = "declared_key"
                const val JSON_AUDIO_URL = "undeclared_field"
                const val PREF_BY_CONVENTION = "convention_only"
                fun install() {
                    prefs.getBoolean(PINNED_LIMIT_PREF_KEY, false)
                    prefs.getString(JSON_AUDIO_URL, null)
                    prefs.getInt(PREF_BY_CONVENTION, 0)
                    prefs.getInt(UNRESOLVED, 0)
                }
            """
        }
        with self.preference_contract('app:key="declared_key"'):
            self.assertEqual(
                extractor.feature_preference_keys(files)["PinnedLimit"],
                ["convention_only", "declared_key"],
            )

    @contextlib.contextmanager
    def preference_contract(self, *rows: str):
        """Run a case against a preference contract holding exactly these rows."""
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "preference_demo.xml").write_text(
                "<PreferenceScreen>%s</PreferenceScreen>" % "".join(rows),
                encoding="utf-8",
            )
            with patch.object(extractor, "PREFERENCE_XML_DIR", tmp), patch.object(
                extractor, "_PREFERENCE_KEYS", None
            ):
                yield

    def test_missing_preference_contract_is_refused_rather_than_assumed(self):
        # An unreadable contract means no key can be proven from a constant name, so only
        # the naming convention survives. Guessing would turn a tooling fault into a claim.
        files = {
            "Feature": """
                const val PINNED_LIMIT_PREF_KEY = "pinnedlimit"
                fun install() { prefs.getBoolean(PINNED_LIMIT_PREF_KEY, false) }
            """
        }
        with patch.object(extractor, "PREFERENCE_XML_DIR", "/nonexistent/preference/xml"), patch.object(
            extractor, "_PREFERENCE_KEYS", None
        ):
            self.assertNotIn("Feature", extractor.feature_preference_keys(files))

    def test_qualified_store_receiver_is_still_a_preference_read(self):
        # `Utils.xprefs` is the target-scoped SharedPreferences. It is reached through a
        # qualifier, so a pattern anchored on a bare `prefs.` reported CustomPrivacy and
        # Tasker as reading no preference at all.
        files = {
            "CustomPrivacy": """
                package com.wax.module.xposed.features.privacy
                const val PREF_TASKER_ENABLED = "tasker"
                fun install(number: String) {
                    if (Utils.xprefs.getString("custom_privacy_type", "0") == "0") return
                    Utils.xprefs.getBoolean(PREF_TASKER_ENABLED, false)
                    prefs.getString("bare_receiver", null)
                }
            """
        }
        self.assertEqual(
            extractor.feature_preference_keys(files)["CustomPrivacy"],
            ["bare_receiver", "custom_privacy_type", "tasker"],
        )

    def test_unknown_receiver_name_is_refused_rather_than_guessed(self):
        # An unrecognised receiver is a missing-evidence error, not a licence to claim a
        # key: `payload` and `myprefs` are not known stores, and neither is `it`.
        files = {
            "Feature": """
                package com.wax.module.xposed.features.media
                fun install() {
                    payload.getString("payload_field", null)
                    myprefs.getBoolean("unqualified_field", false)
                    json?.let { Pair(it.getInt("widthPx"), it.getInt("heightPx")) }
                    bundle.getInt("bundle_field", 0)
                    value.contains("underline")
                }
            """
        }
        self.assertNotIn("Feature", extractor.feature_preference_keys(files))


class OwnershipTests(unittest.TestCase):
    def test_duplicate_file_stems_fail_loudly(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for subdir in ("general", "privacy"):
                folder = root / subdir
                folder.mkdir()
                (folder / "Helper.kt").write_text(
                    "package com.wax.module.xposed.features." + subdir + "\n",
                    encoding="utf-8",
                )
            with patch.object(extractor, "FEATURES_DIR", str(root)):
                with self.assertRaisesRegex(ValueError, "duplicate Kotlin file stem"):
                    extractor.feature_files()

    def test_same_package_helper_and_explicit_cross_package_import(self):
        files = {
            "Feature": """
                package com.wax.module.xposed.features.media
                import com.wax.module.xposed.features.general.CrossHelper as AliasHelper
                fun install() { LocalHelper(); AliasHelper(); Unrelated() }
            """,
            "LocalHelper": """
                package com.wax.module.xposed.features.media
                class LocalHelper { fun apply() { Unobfuscator.loadMedia() } }
            """,
            "CrossHelper": """
                package com.wax.module.xposed.features.general
                class CrossHelper { fun apply() { Unobfuscator.loadGeneral() } }
            """,
            "Unrelated": """
                package com.wax.module.xposed.features.others
                class Unrelated { fun apply() { Unobfuscator.loadUnrelated() } }
            """,
        }
        self.assertEqual(
            extractor.feature_closure("Feature", files),
            {"Feature", "LocalHelper", "CrossHelper"},
        )
        self.assertEqual(
            extractor.feature_resolver_usage(files)["Feature"],
            ["loadGeneral", "loadMedia"],
        )



class DefensiveExtractionTests(unittest.TestCase):
    def test_unrelated_typed_getters_and_dynamic_keys_are_not_certified(self):
        files = {
            "Feature": """
                package com.wax.module.xposed.features.media
                const val PREF_REAL = "real_pref"
                fun install() {
                    jsonObject.getString("json_field")
                    bundle.getInt("bundle_field", 0)
                    prefs.getString("real_literal", null)
                    prefs.getLong(PREF_REAL, 0L)
                    prefs.getInt(dynamicKey(), 0)
                    prefs.edit().putString("write_only", "value")
                }
            """
        }
        self.assertEqual(
            extractor.feature_preference_keys(files)["Feature"],
            ["real_literal", "real_pref"],
        )

    def test_class_declared_in_differently_named_file(self):
        files = {
            "Feature": """
                package com.wax.module.xposed.features.media
                import com.wax.module.xposed.features.general.CrossHelper as AliasHelper
                fun install() { LocalHelper(); AliasHelper(); AmbiguousHelper() }
            """,
            "Helpers": """
                package com.wax.module.xposed.features.media
                class LocalHelper { fun apply() { Unobfuscator.loadMedia() } }
            """,
            "DifferentFilename": """
                package com.wax.module.xposed.features.general
                class CrossHelper { fun apply() { Unobfuscator.loadGeneral() } }
            """,
            "Foreign": """
                package com.wax.module.xposed.features.privacy
                class AmbiguousHelper { fun apply() { Unobfuscator.loadPrivacy() } }
            """,
        }
        unresolved = set()
        self.assertEqual(
            extractor.feature_closure("Feature", files, unresolved),
            {"Feature", "Helpers", "DifferentFilename"},
        )
        self.assertIn("Feature: AmbiguousHelper", unresolved)
        self.assertEqual(
            extractor.feature_resolver_usage(files)["Feature"],
            ["loadGeneral", "loadMedia"],
        )

    def test_owner_index_reused_across_feature_scans(self):
        files = {
            "A": "package com.wax.module.xposed.features.general\nclass A {}",
            "B": "package com.wax.module.xposed.features.general\nclass B {}",
        }
        with patch.object(extractor, "declared_classes", wraps=extractor.declared_classes) as owners:
            extractor.feature_resolver_usage(files)
            self.assertEqual(1, owners.call_count)

    def test_nested_provider_interfaces_do_not_claim_top_level_ownership(self):
        files = {
            "ContextMenuActionProvider": """
                package com.wax.module.xposed.features.providers
                class ContextMenuActionProvider {
                    fun interface Provider { fun apply() }
                }
            """,
            "MenuStatusProvider": """
                package com.wax.module.xposed.features.providers
                class MenuStatusProvider {
                    interface Provider { fun addMenu() }
                }
            """,
        }
        owners = extractor.declared_classes(files)
        self.assertNotIn("com.wax.module.xposed.features.providers.Provider", owners)
        self.assertEqual(
            owners["com.wax.module.xposed.features.providers.MenuStatusProvider"],
            "MenuStatusProvider",
        )

    def test_string_literal_braces_cannot_change_top_level_class_depth(self):
        files = {
            "A": """package com.wax.module.xposed.features.general
                val sample = "{ class Fake {}"
                class Actual {}
            """,
        }
        owners = extractor.declared_classes(files)
        self.assertIn("com.wax.module.xposed.features.general.Actual", owners)
        self.assertNotIn("com.wax.module.xposed.features.general.Fake", owners)

    def test_ambiguous_qualified_class_definitions_fail(self):
        files = {
            "A": "package com.wax.module.xposed.features.media\nclass Helper {}",
            "B": "package com.wax.module.xposed.features.media\nclass Helper {}",
        }
        with self.assertRaisesRegex(ValueError, "ambiguous Kotlin class ownership"):
            extractor.feature_closure("A", files)


if __name__ == "__main__":
    unittest.main()
