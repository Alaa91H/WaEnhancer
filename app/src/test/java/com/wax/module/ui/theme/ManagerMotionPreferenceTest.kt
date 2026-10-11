package com.wax.module.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagerMotionPreferenceTest {
    @Test fun physicalThumbIsOnRightWhenEnabledRegardlessOfLocale() {
        assertEquals(39f, ManagerMotionPreference.physicalThumbX(true, 52f, 13f))
    }

    @Test fun physicalThumbIsOnLeftWhenDisabledRegardlessOfLocale() {
        assertEquals(13f, ManagerMotionPreference.physicalThumbX(false, 52f, 13f))
    }

    @Test fun managerOnlyPreferenceHasIsolatedStorageKey() {
        assertEquals("wax.manager.reduce_motion", ManagerMotionPreference.KEY)
    }
}
