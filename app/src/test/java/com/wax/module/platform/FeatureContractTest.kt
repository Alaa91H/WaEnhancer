package com.wax.module.platform

import com.wax.module.resolver.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The declaration contract and the availability policy.
 *
 * Two things are being pinned down here. First, that the catalog cannot declare a feature the
 * registry would reject — an incomplete declaration should fail a test, not a user's start-up.
 * Second, that availability is decided by capability and compatibility alone: the tests below
 * vary one technical fact at a time and assert the answer moves, and vary connectivity and
 * assert it does not.
 */
class FeatureContractTest {
    @Before
    fun setUp() {
        FeatureRegistry.clear()
    }

    private fun validMetadata(id: String = "privacy.test"): FeatureMetadata =
        FeatureMetadata(
            id = id,
            displayName = "Test feature",
            category = FeatureCategory.PRIVACY,
            preferenceKeys = listOf("wae.test"),
            startupPolicy = StartupPolicy.LAZY,
            requiredResolvers = emptyList(),
            optionalResolvers = emptyList(),
            permissions = emptyList(),
            supportedWhatsAppVersions = listOf("2.26.40.xx"),
            supportedBusinessVersions = listOf("2.26.39.xx"),
            compatibilityConfidence = Confidence.EXACT,
            fallbackBehavior = FallbackBehavior.DISABLE_FEATURE,
            diagnostics = DiagnosticsMetadata(),
            tests = listOf("FeatureContractTest"),
        )

    private fun whatsAppFacts(): FeatureFacts = FeatureFacts(target = TargetApp.WHATSAPP, installedVersion = "2.26.40.21")

    // --- catalog ------------------------------------------------------------------------

    @Test
    fun everyCatalogDeclarationPassesRegistration() {
        val rejected = PlatformFeatureCatalog.registerInto().filterNot { it.isAccepted }
        assertTrue(
            "catalog declarations must all be valid: ${rejected.map { (it as RegistrationResult.Rejected).metadata.id to it.reasons }}",
            rejected.isEmpty(),
        )
    }

    @Test
    fun everyCatalogFeatureIsFree() {
        PlatformFeatureCatalog.metadata().forEach { metadata ->
            assertEquals("${metadata.id} must be free", FeatureAccessTier.FREE, metadata.accessTier)
        }
    }

    @Test
    fun theCatalogIntroducesOnlyOneAccessTier() {
        // If a second tier is ever added this fails, which is the point: the contract is
        // enforced by the type, and this test makes the edit deliberate rather than incidental.
        assertEquals(1, FeatureAccessTier.entries.size)
    }

    @Test
    fun aVisibleFeatureDeclaresAStockModeFallback() {
        PlatformFeatureCatalog
            .metadata()
            .filter { it.visualImpact.isVisibleInWhatsApp }
            .forEach { metadata ->
                assertNotEquals(
                    "${metadata.id} changes WhatsApp's interface, so it needs a fallback",
                    StockModeFallback.NONE_NEEDED,
                    metadata.stockModeFallback,
                )
            }
    }

    @Test
    fun aHighRiskFeatureIsCapabilityGated() {
        PlatformFeatureCatalog
            .metadata()
            .filter { it.riskLevel == RiskLevel.HIGH }
            .forEach { metadata ->
                // Either it names the native path it needs, or it says out loud that it has
                // not been wired. The combination this replaces — a HIGH-risk feature with no
                // required resolver, presented as available — is the one that lied: ten
                // declarations named resolvers that exist in no resolver file at all.
                assertTrue(
                    "${metadata.id} is HIGH risk, so it must declare a required resolver " +
                        "or be declared NOT_IMPLEMENTED",
                    metadata.requiredResolvers.isNotEmpty() ||
                        metadata.availability == FeatureAvailability.NOT_IMPLEMENTED,
                )
            }
    }

    @Test
    fun theRegistryEnforcesTheSameHighRiskRuleItDocuments() {
        // The registry is what actually rejects a declaration, so the rule lives there and the
        // catalog tests read it back. A test that asserted a different rule would have let a
        // HIGH-risk feature through the registry while looking green here.
        val ungated =
            validMetadata(id = "privacy.high_risk").copy(
                riskLevel = RiskLevel.HIGH,
                requiredResolvers = emptyList(),
                availability = FeatureAvailability.AVAILABLE,
            )
        val reasons = FeatureRegistry.validate(ungated)
        assertTrue(
            "a HIGH risk feature with no resolver and no NOT_IMPLEMENTED must be refused",
            reasons.any { it.contains("HIGH risk") },
        )

        val declaredUnwired = ungated.copy(availability = FeatureAvailability.NOT_IMPLEMENTED)
        assertFalse(
            "declaring itself NOT_IMPLEMENTED holds it back harder than any gate",
            FeatureRegistry.validate(declaredUnwired).any { it.contains("HIGH risk") },
        )

        val gated =
            ungated.copy(
                requiredResolvers = listOf("loadSomethingReal"),
                availability = FeatureAvailability.AVAILABLE,
            )
        assertFalse(
            FeatureRegistry.validate(gated).any { it.contains("HIGH risk") },
        )
    }

    @Test
    fun anUnwiredEngineIsNotPresentedAsAvailable() {
        // These four are tested engines that nothing constructs: no hook installs them, and the
        // manifest declares no listener to feed them. The enum member for "declared, not built"
        // exists precisely so that can be said out loud instead of implied.
        val unwired =
            setOf(
                PlatformFeatures.MEDIA_POLICY,
                PlatformFeatures.STATUS_AUDIO_STUDIO,
                PlatformFeatures.NOTIFICATION_COOLDOWN,
                PlatformFeatures.PRESENCE_ALERTS,
            )
        PlatformFeatureCatalog
            .metadata()
            .filter { it.id in unwired }
            .forEach { metadata ->
                assertEquals(
                    "${metadata.id} is a tested engine with no consumer, so it cannot be offered",
                    FeatureAvailability.NOT_IMPLEMENTED,
                    metadata.availability,
                )
                assertTrue(
                    "${metadata.id} claims no native path, so it must not name a resolver",
                    metadata.requiredResolvers.isEmpty() && metadata.optionalResolvers.isEmpty(),
                )
            }
    }

    @Test
    fun everyAvailabilityExplanationIsFreeOfPaymentWording() {
        FeatureAvailability.entries.forEach { availability ->
            assertFalse(
                "$availability must not present a paid tier",
                NoPaywallContract.containsPaymentWording(availability.explanation),
            )
        }
    }

    // --- registry rules -----------------------------------------------------------------

    @Test
    fun aVisibleImpactWithoutAFallbackIsRejected() {
        val metadata = validMetadata().copy(visualImpact = VisualImpact.INJECTS_UI)
        val result = FeatureRegistry.register(metadata)
        assertFalse(result.isAccepted)
        assertTrue((result as RegistrationResult.Rejected).reasons.any { it.contains("stockModeFallback") })
    }

    @Test
    fun aHighRiskFeatureWithoutARequiredResolverIsRejected() {
        val metadata = validMetadata().copy(riskLevel = RiskLevel.HIGH)
        val result = FeatureRegistry.register(metadata)
        assertFalse(result.isAccepted)
        assertTrue((result as RegistrationResult.Rejected).reasons.any { it.contains("required resolver") })
    }

    @Test
    fun aVisibleImpactWithAFallbackIsAccepted() {
        val metadata =
            validMetadata().copy(
                visualImpact = VisualImpact.INJECTS_UI,
                stockModeFallback = StockModeFallback.MANAGER_ONLY,
            )
        assertTrue(FeatureRegistry.register(metadata).isAccepted)
    }

    // --- availability -------------------------------------------------------------------

    @Test
    fun anAvailableFeatureReportsItselfUsable() {
        val report = FeatureAccessPolicy.evaluate(validMetadata(), whatsAppFacts())
        assertEquals(FeatureAvailability.AVAILABLE, report.availability)
        assertTrue(report.enabledByUser)
        assertTrue(report.isUsable)
    }

    @Test
    fun aDeclarationThatIsNotBuiltYetIsReportedAsSuch() {
        val metadata = validMetadata().copy(availability = FeatureAvailability.NOT_IMPLEMENTED)
        val report = FeatureAccessPolicy.evaluate(metadata, whatsAppFacts())
        assertEquals(FeatureAvailability.NOT_IMPLEMENTED, report.availability)
    }

    @Test
    fun anUnsupportedTargetIsReportedBeforeAnythingElseThatMightAlsoBeTrue() {
        val metadata = validMetadata().copy(availability = FeatureAvailability.NOT_IMPLEMENTED)
        val report =
            FeatureAccessPolicy.evaluate(
                metadata,
                whatsAppFacts().copy(targetSupported = false, versionSupported = false),
            )
        // NOT_IMPLEMENTED outranks the target check by design: it is the author's own statement.
        assertEquals(FeatureAvailability.NOT_IMPLEMENTED, report.availability)

        val supported = FeatureAccessPolicy.evaluate(validMetadata(), whatsAppFacts().copy(targetSupported = false))
        assertEquals(FeatureAvailability.UNSUPPORTED_TARGET, supported.availability)
    }

    @Test
    fun anUnsupportedVersionIsReported() {
        val report = FeatureAccessPolicy.evaluate(validMetadata(), whatsAppFacts().copy(versionSupported = false))
        assertEquals(FeatureAvailability.UNSUPPORTED_VERSION, report.availability)
    }

    @Test
    fun anIncompatibleVerdictFromTheCanaryIsReportedAsAnUnsupportedVersion() {
        val facts = whatsAppFacts().copy(killSwitchState = FeatureSwitchState.INCOMPATIBLE)
        val report = FeatureAccessPolicy.evaluate(validMetadata(), facts)
        assertEquals(FeatureAvailability.UNSUPPORTED_VERSION, report.availability)
        assertTrue(report.detail.contains("canary"))
    }

    @Test
    fun aBoundedDisableIsReportedAsTemporary() {
        FeatureSwitchState.entries
            .filter { it == FeatureSwitchState.TEMPORARILY_DISABLED || it == FeatureSwitchState.AUTOMATICALLY_DISABLED }
            .forEach { state ->
                val report = FeatureAccessPolicy.evaluate(validMetadata(), whatsAppFacts().copy(killSwitchState = state))
                assertEquals(state.name, FeatureAvailability.TEMPORARILY_DISABLED, report.availability)
            }
    }

    @Test
    fun aManualDisableKeepsTheAvailabilityAndOnlyTurnsTheSwitchOff() {
        val facts = whatsAppFacts().copy(killSwitchState = FeatureSwitchState.MANUALLY_DISABLED)
        val report = FeatureAccessPolicy.evaluate(validMetadata(), facts)
        assertEquals(FeatureAvailability.AVAILABLE, report.availability)
        assertFalse(report.enabledByUser)
        assertFalse(report.isUsable)
        assertEquals("Off — you turned this feature off.", FeatureAccessPolicy.describe(report))
    }

    @Test
    fun anExperimentalFeatureNeedsLabs() {
        val metadata = validMetadata().copy(availability = FeatureAvailability.EXPERIMENTAL)
        assertEquals(
            FeatureAvailability.EXPERIMENTAL,
            FeatureAccessPolicy.evaluate(metadata, whatsAppFacts()).availability,
        )
        assertEquals(
            FeatureAvailability.AVAILABLE,
            FeatureAccessPolicy.evaluate(metadata, whatsAppFacts().copy(labsEnabled = true)).availability,
        )
    }

    @Test
    fun aMissingResolverPermissionOrCapabilityIsReportedAsAMissingCapability() {
        listOf(
            whatsAppFacts().copy(resolversResolved = false),
            whatsAppFacts().copy(permissionsGranted = false),
            whatsAppFacts().copy(capabilityPresent = false),
        ).forEach { facts ->
            assertEquals(
                FeatureAvailability.MISSING_CAPABILITY,
                FeatureAccessPolicy.evaluate(validMetadata(), facts).availability,
            )
        }
    }

    @Test
    fun connectivityNeverChangesAvailability() {
        val offline = whatsAppFacts().copy(online = false)
        val online = whatsAppFacts()
        assertNotEquals(offline, online)
        assertEquals(
            FeatureAccessPolicy.evaluate(validMetadata(), online).availability,
            FeatureAccessPolicy.evaluate(validMetadata(), offline).availability,
        )
    }

    // --- declared versions --------------------------------------------------------------

    @Test
    fun evaluateAllHonoursTheDeclaredVersionRangePerTarget() {
        val features = PlatformFeatureCatalog.metadata()
        val whatsapp =
            FeatureAccessPolicy.evaluateAll(features, whatsAppFacts())
        assertTrue(
            "every catalog feature declares the current WhatsApp version",
            whatsapp.none { it.availability == FeatureAvailability.UNSUPPORTED_VERSION },
        )

        val businessFacts =
            FeatureFacts(target = TargetApp.WHATSAPP_BUSINESS, installedVersion = "2.26.39.4")
        val business = FeatureAccessPolicy.evaluateAll(features, businessFacts)
        assertTrue(business.none { it.availability == FeatureAvailability.UNSUPPORTED_VERSION })
    }

    @Test
    fun evaluateAllFailsClosedWhenTheVersionCannotBeRead() {
        // Fail-closed means "not available", and NOT_IMPLEMENTED is not available either: a
        // feature nobody wired stays unavailable whatever the version says. Restricting the
        // assertion to features that claim to be available is what keeps it testing the
        // version rule rather than the declaration's own honesty.
        val available = PlatformFeatureCatalog.metadata().filter { it.availability == FeatureAvailability.AVAILABLE }
        assertTrue("the catalog must still contain available features", available.isNotEmpty())
        val reports = FeatureAccessPolicy.evaluateAll(available, FeatureFacts(target = TargetApp.WHATSAPP))
        assertTrue(reports.all { it.availability == FeatureAvailability.UNSUPPORTED_VERSION })
    }

    @Test
    fun anUnwiredFeatureStaysUnavailableWhateverTheVersionSays() {
        val unwired =
            PlatformFeatureCatalog
                .metadata()
                .filter { it.availability == FeatureAvailability.NOT_IMPLEMENTED }
        assertTrue("the catalog must still declare unwired features", unwired.isNotEmpty())
        unwired.forEach { metadata ->
            listOf("2.26.40.21", "2.20.10.5", null).forEach { version ->
                val report =
                    FeatureAccessPolicy.evaluate(
                        metadata,
                        FeatureFacts(target = TargetApp.WHATSAPP, installedVersion = version),
                    )
                assertEquals(
                    "${metadata.id} has no consumer, so no version can make it available",
                    FeatureAvailability.NOT_IMPLEMENTED,
                    report.availability,
                )
            }
        }
    }

    @Test
    fun evaluateAllTreatsAVersionOutsideTheRangeAsUnsupported() {
        val features = listOf(validMetadata())
        val reports =
            FeatureAccessPolicy.evaluateAll(
                features,
                FeatureFacts(target = TargetApp.WHATSAPP, installedVersion = "2.20.10.5"),
            )
        assertEquals(FeatureAvailability.UNSUPPORTED_VERSION, reports.single().availability)
    }
}
