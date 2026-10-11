package com.wax.module.ui.theme

import org.junit.Assert.*
import org.junit.Test

class ManagerAppearanceTest {
    @Test fun legacyModesRemainCompatible() {
        assertEquals(ManagerAppearance.DARK, ManagerAppearance.fromStored("1", false))
        assertEquals(ManagerAppearance.LIGHT, ManagerAppearance.fromStored("2", true))
    }

    @Test fun systemAndUnknownModesFollowThePlatform() {
        assertEquals(ManagerAppearance.LIGHT, ManagerAppearance.fromStored("0", false))
        assertEquals(ManagerAppearance.DARK, ManagerAppearance.fromStored(null, true))
        assertEquals(ManagerAppearance.DARK, ManagerAppearance.fromStored("99", true))
    }

    @Test fun amoledIsDarkAndIndependentOfSystem() {
        val selected = ManagerAppearance.fromStored("3", false)
        assertTrue(selected.dark)
        assertTrue(selected.amoled)
    }
}
