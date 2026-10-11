package com.wax.module.ui.features

import com.wax.module.settings.SettingKeyRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureTogglePolicyTest {
    @Test fun enablingUnknownRuntimeRequiresExplicitConfirmation() {
        assertEquals(
            FeatureTogglePolicy.Decision.EXPLICIT_ENABLE_CONFIRMATION,
            FeatureTogglePolicy.decision(SettingKeyRegistry.Kind.BOOLEAN, false, true),
        )
    }

    @Test fun disablingSelectedFeatureNeverNeedsAccountRiskConsent() {
        assertEquals(
            FeatureTogglePolicy.Decision.DISABLE_IMMEDIATELY,
            FeatureTogglePolicy.decision(SettingKeyRegistry.Kind.BOOLEAN, true, false),
        )
    }

    @Test fun editorOnlyAndUndeclaredSettingsCannotBeToggled() {
        for (kind in listOf(
            null,
            SettingKeyRegistry.Kind.TEXT,
            SettingKeyRegistry.Kind.INT,
            SettingKeyRegistry.Kind.FLOAT,
            SettingKeyRegistry.Kind.SET,
        )) {
            assertEquals(
                FeatureTogglePolicy.Decision.NOT_EDITABLE,
                FeatureTogglePolicy.decision(kind, false, true),
            )
        }
    }

    @Test fun redundantToggleDoesNotModifyPreferences() {
        for (value in listOf(true, false)) {
            assertEquals(
                FeatureTogglePolicy.Decision.NO_CHANGE,
                FeatureTogglePolicy.decision(SettingKeyRegistry.Kind.BOOLEAN, value, value),
            )
        }
    }
}
