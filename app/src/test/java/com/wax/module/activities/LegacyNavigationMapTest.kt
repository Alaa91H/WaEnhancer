package com.wax.module.activities

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyNavigationMapTest {
    @Test fun allSixExistingRoutesPreserveTheirOwners() {
        assertEquals(0, LegacyNavigationMap.toPage(0))
        assertEquals(4, LegacyNavigationMap.toPage(1))
        assertEquals(5, LegacyNavigationMap.toPage(2))
        assertEquals(6, LegacyNavigationMap.toPage(3))
        assertEquals(8, LegacyNavigationMap.toPage(4))
        assertEquals(7, LegacyNavigationMap.toPage(5))
    }

    @Test fun unknownRouteOpensBrowserInsteadOfAnotherUsersSettings() {
        assertEquals(1, LegacyNavigationMap.toPage(-1))
        assertEquals(1, LegacyNavigationMap.toPage(99))
    }

    @org.junit.Test
    fun backFromLegacyPagesReturnsToOwningPrimaryTab() {
        for (page in 4..7) {
            org.junit.Assert.assertEquals(1, LegacyNavigationMap.primaryForPage(page))
        }
        org.junit.Assert.assertEquals(2, LegacyNavigationMap.primaryForPage(8))
        for (page in 0..3) {
            org.junit.Assert.assertNull(LegacyNavigationMap.primaryForPage(page))
        }
    }
}
