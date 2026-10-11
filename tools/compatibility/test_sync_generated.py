"""Regression tests for compatibility report status summaries (#392)."""

from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sync_generated  # noqa: E402
import validate_compatibility  # noqa: E402


def fixture() -> dict:
    return {
        "module": {
            "minSdk": 28,
            "targetSdk": 34,
            "compileSdk": 37,
            "abis": ["arm64-v8a"],
        },
        "packages": {
            "whatsapp": {
                "declaredVersions": ["w1", "w2", "w3"],
                "defaultStatus": "unknown",
            },
            "business": {
                "declaredVersions": ["b1", "b2"],
                "defaultStatus": "unknown",
            },
        },
        "statusVocabulary": {
            status: status
            for status in ("supported", "degraded", "unsupported", "unknown")
        },
        "resolutionTiers": {
            tier: {"meaning": tier}
            for tier in ("none", "indirect", "dexkit")
        },
        "derived": {
            "features": [{
                "id": "ExampleFeature",
                "category": "general",
                "resolutionTier": "none",
                "resolutionSources": [],
                "resolverDependencies": [],
                "preferenceKeys": [],
            }],
        },
        "matrix": {},
        "evidence": {},
    }


class CompatibilityReportTests(unittest.TestCase):
    def test_all_unknown_preserves_zero_evidence_message(self) -> None:
        report = sync_generated.render(fixture())
        self.assertIn("No cell in this matrix is resolver-verified yet.", report)
        self.assertIn("they are **not** evidence", report)
        self.assertIn("| Package | Declared versions | Resolver-verified |", report)
        self.assertIn("| WhatsApp | 3 | 0 / 3 cells |", report)
        self.assertIn("| WhatsApp Business | 2 | 0 / 2 cells |", report)

    def test_mixed_statuses_are_counted_without_certification(self) -> None:
        matrix = fixture()
        matrix["matrix"] = {
            "ExampleFeature": {
                "whatsapp": {"versions": {"w1": "supported", "w2": "degraded"}},
                "business": {"versions": {"b1": "unsupported"}},
            }
        }
        report = sync_generated.render(matrix)
        self.assertIn("Across 5 feature/version cells:", report)
        self.assertIn("1 `supported` status claim(s)", report)
        self.assertIn("1 `degraded`, 1 `unsupported`, and 2 `unknown`", report)
        self.assertIn("not independent resolver verification", report)
        self.assertNotIn("No cell in this matrix is resolver-verified yet.", report)
        self.assertIn("| WhatsApp | 3 | 1 / 3 cells | 1 | 0 | 1 |", report)
        self.assertIn("| WhatsApp Business | 2 | 0 / 2 cells | 0 | 1 | 1 |", report)

    def test_degraded_without_supported_is_not_reported_as_all_unknown(self) -> None:
        matrix = fixture()
        matrix["packages"]["whatsapp"]["defaultStatus"] = "degraded"
        report = sync_generated.render(matrix)
        self.assertIn("0 `supported` status claim(s)", report)
        self.assertIn("3 `degraded`, 0 `unsupported`, and 2 `unknown`", report)
        self.assertNotIn("No cell in this matrix is resolver-verified yet.", report)


class InheritedCertificationGateTests(unittest.TestCase):
    """The generator half of the #396 gate.

    The validator already refuses a package-wide ``supported`` default. These
    tests cover the other half: the generator must refuse to *emit* a cell it
    cannot justify, because it can be run on its own.
    """

    def test_a_supported_default_is_listed_for_both_packages(self) -> None:
        for package in ("whatsapp", "business"):
            with self.subTest(package=package):
                matrix = fixture()
                matrix["packages"][package]["defaultStatus"] = "supported"
                inherited = sync_generated.inherited_supported_cells(matrix)
                self.assertTrue(inherited, "%s inherited cells were not caught" % package)
                for cell in inherited:
                    self.assertIn(package, cell)

    def test_a_supported_default_lists_every_declared_version(self) -> None:
        matrix = fixture()
        matrix["packages"]["whatsapp"]["defaultStatus"] = "supported"
        inherited = sync_generated.inherited_supported_cells(matrix)
        for version in matrix["packages"]["whatsapp"]["declaredVersions"]:
            self.assertTrue(
                any(cell.endswith("/whatsapp/%s" % version) for cell in inherited),
                "version %s was not listed" % version,
            )

    def test_an_explicit_cell_is_never_listed(self) -> None:
        matrix = fixture()
        matrix["packages"]["whatsapp"]["defaultStatus"] = "supported"
        matrix["matrix"] = {
            "ExampleFeature": {
                "whatsapp": {"versions": {"w1": "supported"}},
            }
        }
        inherited = sync_generated.inherited_supported_cells(matrix)
        self.assertFalse(
            any(cell == "ExampleFeature/whatsapp/w1" for cell in inherited),
            "an evidence-backed cell must not be treated as inherited",
        )
        self.assertTrue(any(cell.endswith("/whatsapp/w2") for cell in inherited))

    def test_the_gate_raises_rather_than_emitting(self) -> None:
        matrix = fixture()
        matrix["packages"]["business"]["defaultStatus"] = "supported"
        with self.assertRaises(SystemExit) as raised:
            sync_generated.refuse_inherited_certification(matrix)
        self.assertIn("refusing to generate", str(raised.exception))

    def test_other_defaults_pass_the_gate(self) -> None:
        for status in ("unknown", "degraded", "unsupported"):
            with self.subTest(status=status):
                matrix = fixture()
                matrix["packages"]["whatsapp"]["defaultStatus"] = status
                matrix["packages"]["business"]["defaultStatus"] = status
                self.assertEqual(
                    [],
                    sync_generated.inherited_supported_cells(matrix),
                )
                sync_generated.refuse_inherited_certification(matrix)

    def test_the_real_generation_still_runs(self) -> None:
        # The gate must not break the zero-evidence matrix this project has.
        matrix = fixture()
        report = sync_generated.render(matrix)
        self.assertIn("No cell in this matrix is resolver-verified yet.", report)


VERSION = "2.26.32.123"
BUILD = "com.whatsapp/release/arm64:stable-build-123"
STAMP = "2026-10-08T12:00:00Z"


def certified_fixture() -> dict:
    """A matrix whose single declared cell can actually be earned."""
    matrix = fixture()
    for package, package_name in (("whatsapp", "com.whatsapp"), ("business", "com.whatsapp.w4b")):
        matrix["packages"][package] = {
            "packageName": package_name,
            "applicationId": "com.wax.module",
            "declaredVersions": [VERSION],
            "certifiedBuildFingerprints": {VERSION: BUILD},
            "defaultStatus": "unknown",
        }
    matrix["derived"]["features"][0]["resolutionTier"] = "dexkit"
    matrix["derived"]["features"][0]["resolverDependencies"] = ["resolveExample"]
    return matrix


def observation(account=None):
    record = {
        "package": "whatsapp",
        "packageName": "com.whatsapp",
        "version": VERSION,
        "buildFingerprint": BUILD,
        "sdk": 35,
        "abi": "arm64-v8a",
        "verifiedAt": STAMP,
        "result": "resolved",
        "resolvers": {"resolveExample": {"verifiedAt": STAMP, "result": "resolved"}},
    }
    if account is not None:
        record["account"] = account
    return record


def earned_cell(matrix, account=None):
    matrix["matrix"] = {"ExampleFeature": {"whatsapp": {"versions": {VERSION: "supported"}}}}
    matrix["evidence"] = {"ExampleFeature": {"targets": [observation(account)]}}
    return matrix


class SupportedClaimEmissionGateTests(unittest.TestCase):
    """The generator must not publish a cell the validator would reject (#391).

    A generated document is what a reader believes, so the two have to agree by
    construction. The generator asks the validator rather than re-implementing the
    evidence rule, and these tests fail if that pairing ever stops holding.
    """

    def test_an_earned_cell_is_published(self) -> None:
        matrix = earned_cell(certified_fixture())
        self.assertFalse(validate_compatibility.supported_cell_problems(matrix, matrix["derived"]))
        sync_generated.refuse_uncertifiable_supported_cells(matrix)

    def test_an_unearned_cell_is_refused(self) -> None:
        matrix = certified_fixture()
        matrix["matrix"] = {
            "ExampleFeature": {"whatsapp": {"versions": {VERSION: "supported"}}}
        }
        with self.assertRaises(SystemExit) as raised:
            sync_generated.refuse_uncertifiable_supported_cells(matrix)
        self.assertIn("refusing to generate", str(raised.exception))

    def test_an_instance_bound_observation_is_refused_for_a_package_wide_cell(self) -> None:
        # The document must not say "supported for every user" on the strength of one
        # account's observation, which is exactly the claim the cell reads as making.
        matrix = earned_cell(certified_fixture(), account="work-profile")
        with self.assertRaises(SystemExit) as raised:
            sync_generated.refuse_uncertifiable_supported_cells(matrix)
        self.assertIn("bound to one instance", str(raised.exception))

    def test_the_same_cell_is_published_once_its_scope_is_declared(self) -> None:
        matrix = earned_cell(certified_fixture(), account="work-profile")
        matrix["packages"]["whatsapp"]["certifiedAccountScopes"] = {VERSION: "work-profile"}
        sync_generated.refuse_uncertifiable_supported_cells(matrix)

    def test_the_gate_and_the_validator_reject_the_same_cells(self) -> None:
        candidates = {
            "no evidence": lambda m: m.update(
                {"evidence": {},
                 "matrix": {"ExampleFeature": {"whatsapp": {"versions": {VERSION: "supported"}}}}}
            ),
            "cross-version evidence": lambda m: m["evidence"]["ExampleFeature"]["targets"][0]
            .update({"version": "2.26.32.124"}),
            "changed build": lambda m: m["evidence"]["ExampleFeature"]["targets"][0]
            .update({"buildFingerprint": "com.whatsapp/beta/arm64:stable-build-999"}),
            "unpinned build": lambda m: m["packages"]["whatsapp"]
            .pop("certifiedBuildFingerprints"),
            "instance-bound evidence": lambda m: m["evidence"]["ExampleFeature"]["targets"][0]
            .update({"account": "clone-0"}),
            "unresolved resolver": lambda m: m["evidence"]["ExampleFeature"]["targets"][0]
            ["resolvers"]["resolveExample"].update({"result": "missing"}),
        }
        for name, mutate in candidates.items():
            with self.subTest(case=name):
                matrix = earned_cell(certified_fixture())
                mutate(matrix)
                rejected_by_validator = bool(
                    validate_compatibility.supported_cell_problems(matrix, matrix["derived"])
                )
                generator_refused = False
                try:
                    sync_generated.refuse_uncertifiable_supported_cells(matrix)
                except SystemExit:
                    generator_refused = True
                self.assertTrue(rejected_by_validator, "%s passed the validator" % name)
                self.assertTrue(generator_refused, "%s reached the generated document" % name)


if __name__ == "__main__":
    unittest.main()
