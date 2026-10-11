#!/usr/bin/env python3
"""Offline positive/negative fixtures for the catalog-resolver strict CI gate."""

from __future__ import annotations

import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().with_name("check_catalog_resolvers.py")
spec = importlib.util.spec_from_file_location("check_catalog_resolvers", SCRIPT)
assert spec is not None and spec.loader is not None
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class CatalogResolverGateTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="wax-catalog-resolvers-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.platform = self.root / checker.PLATFORM
        self.platform.mkdir(parents=True)
        self.resolver_path = self.root / checker.UNOBFUSCATOR
        self.resolver_path.parent.mkdir(parents=True)
        self.resolver_path.write_text(
            "object Unobfuscator {\n"
            "  // fun loadNotReal() {}\n"
            "  fun loadReceiptMethod(loader: ClassLoader): Any = TODO()\n"
            "  fun loadGhostModeMethod(loader: ClassLoader): Any = TODO()\n"
            "}\n",
            encoding="utf-8",
        )
        self.put_catalog(
            'feature(PlatformFeatures.RECEIPTS, "Read privacy", FeatureCategory.PRIVACY, '
            'requiredResolvers = listOf("loadReceiptMethod"), '
            'optionalResolvers = listOf("loadGhostModeMethod"))'
        )

    def put_catalog(self, content: str, file_name: str = "PlatformFeatureCatalog.kt") -> None:
        (self.platform / file_name).write_text(
            "package com.wax.module.platform\n"
            "internal fun feature(id: String, requiredResolvers: List<String> = emptyList()) {}\n"
            "object PlatformFeatureCatalog {\n"
            "  fun metadata() = listOf(\n"
            f"    {content},\n"
            "  )\n"
            "}\n",
            encoding="utf-8",
        )

    def run_gate(self) -> tuple[int, str]:
        output = io.StringIO()
        with contextlib.redirect_stderr(output), contextlib.redirect_stdout(output):
            status = checker.main(["--root", str(self.root)])
        return status, output.getvalue()

    def test_valid_required_and_optional_in_multiple_sources(self) -> None:
        self.put_catalog(
            'feature(PlatformFeatures.OTHER, "Other", FeatureCategory.MEDIA, '
            'requiredResolvers = listOf("loadReceiptMethod",),)'
        )
        (self.platform / "PlatformCatalogSupport.kt").write_text(
            'fun additionDeclarations() = listOf(\n'
            '  feature(PlatformFeatures.EXTRA, "extra", FeatureCategory.MEDIA,\n'
            '    optionalResolvers = listOf("loadGhostModeMethod")),\n'
            ')\n',
            encoding="utf-8",
        )
        status, output = self.run_gate()
        self.assertEqual(status, 0, output)
        self.assertIn("2 feature declarations, 2 declared resolver references", output)

    def test_missing_required_resolver_fails_with_feature_name(self) -> None:
        self.put_catalog(
            'feature(PlatformFeatures.SENSITIVE, "Secret", FeatureCategory.PRIVACY, '
            'requiredResolvers = listOf("loadUnsafeMissing"))'
        )
        status, output = self.run_gate()
        self.assertEqual(status, 1)
        self.assertIn("SENSITIVE requiredResolvers declares missing loadUnsafeMissing", output)

    def test_missing_optional_resolver_is_not_silently_accepted(self) -> None:
        self.put_catalog(
            'feature(PlatformFeatures.MEDIA, "Media", FeatureCategory.MEDIA, '
            'optionalResolvers = listOf("loadGhostModeMethod", "loadMissingNative"))'
        )
        status, output = self.run_gate()
        self.assertEqual(status, 1)
        self.assertIn("MEDIA optionalResolvers declares missing loadMissingNative", output)

    def test_comments_and_string_literals_cannot_fake_resolver_or_feature(self) -> None:
        self.resolver_path.write_text(
            'object Unobfuscator { /* fun loadFake() {} */\n'
            '  val text = "fun loadFake() {}"\n'
            '  fun loadReceiptMethod(loader: Any) = Unit\n}\n',
            encoding="utf-8",
        )
        self.put_catalog(
            '"feature(PlatformFeatures.FAKE, requiredResolvers = listOf(\\"loadFake\\"))"\n'
            '    /* feature(PlatformFeatures.NOPE, requiredResolvers = listOf("loadFake")) */\n'
            '    feature(PlatformFeatures.REAL, "real", FeatureCategory.PRIVACY,\n'
            '      requiredResolvers = listOf("loadFake"))'
        )
        status, output = self.run_gate()
        self.assertEqual(status, 1, output)
        self.assertIn("REAL requiredResolvers declares missing loadFake", output)
        self.assertNotIn("FAKE requiredResolvers", output)
        self.assertNotIn("NOPE requiredResolvers", output)

    def test_kotlin_nested_comment_does_not_hide_actual_resolver(self) -> None:
        self.resolver_path.write_text(
            "/* outer /* nested fun loadFake() */ end */\n"
            "fun loadReceiptMethod(loader: Any) = Unit\n",
            encoding="utf-8",
        )
        status, output = self.run_gate()
        self.assertEqual(status, 1, output)
        self.assertIn("optionalResolvers declares missing loadGhostModeMethod", output)

    def test_unknown_dynamic_resolver_expression_fails_closed(self) -> None:
        self.put_catalog(
            'feature(PlatformFeatures.DYNAMIC, "dyn", FeatureCategory.PRIVACY, '
            'requiredResolvers = resolverNames)'
        )
        status, output = self.run_gate()
        self.assertEqual(status, 2, output)
        self.assertIn("DYNAMIC: dynamic requiredResolvers cannot be verified", output)

    def test_nonliteral_list_element_is_a_parse_error(self) -> None:
        self.put_catalog(
            'feature(PlatformFeatures.DYNAMIC, "dyn", FeatureCategory.PRIVACY, '
            'requiredResolvers = listOf("loadReceiptMethod", dynamicName))'
        )
        status, output = self.run_gate()
        self.assertEqual(status, 2, output)
        self.assertIn("DYNAMIC: nonliteral requiredResolvers", output)

    def test_an_empty_or_unreadable_tree_never_passes(self) -> None:
        self.resolver_path.unlink()
        status, output = self.run_gate()
        self.assertEqual(status, 2, output)
        self.assertIn("cannot read", output)
        self.resolver_path.write_text("fun loadReceiptMethod() = Unit\n", encoding="utf-8")
        for source in self.platform.glob("*.kt"):
            source.unlink()
        status, output = self.run_gate()
        self.assertEqual(status, 2, output)
        self.assertIn("no Kotlin catalog sources", output)

    def test_catalog_helper_only_is_not_a_feature_registration(self) -> None:
        self.put_catalog('listOf("not a feature")')
        status, output = self.run_gate()
        self.assertEqual(status, 2, output)
        self.assertIn("no feature registrations", output)

    def test_triple_quotes_cannot_inject_false_declarations(self) -> None:
        self.put_catalog(
            '"""feature(PlatformFeatures.FAKE, requiredResolvers=listOf("loadFake"))"""\n'
            '    feature(PlatformFeatures.REAL, "real", FeatureCategory.PRIVACY,\n'
            '      optionalResolvers = listOf("loadGhostModeMethod"))'
        )
        status, output = self.run_gate()
        self.assertEqual(status, 0, output)
        self.assertIn("1 feature declarations, 1 declared resolver references", output)

    def test_current_repository_matches_after_catalog_cleanup(self) -> None:
        features, references, missing = checker.inspect(checker.ROOT)
        self.assertGreater(features, 40)
        self.assertGreaterEqual(references, 0)
        self.assertFalse(missing, missing)


if __name__ == "__main__":
    unittest.main()
