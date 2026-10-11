package com.wax.module.ui.fragments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolActionTest {
    @Test fun sixToolsHaveStableUniqueActions() {
        assertEquals(6, ToolAction.entries.size)
        assertEquals(6, ToolAction.entries.toSet().size)
    }

    @Test fun compatibilityAndRecoveryRequireExplanation() {
        assertTrue(ToolAction.COMPATIBILITY.requiresExplanation)
        assertTrue(ToolAction.RECOVERY.requiresExplanation)
        for (action in ToolAction.entries - setOf(ToolAction.COMPATIBILITY, ToolAction.RECOVERY)) {
            assertFalse(action.requiresExplanation)
        }
    }
}
