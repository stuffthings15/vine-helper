package com.example.vinehelper

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshRateSafetyTest {

    @Test
    fun recommendedForYouCannotGoBelowFiveSeconds() {
        assertEquals(5, clampRefreshSeconds("potluck", 1))
        assertEquals(5, clampRefreshSeconds("potluck", 5))
    }

    @Test
    fun availableForAllAndAdditionalItemsCannotGoBelowThreeSeconds() {
        assertEquals(3, clampRefreshSeconds("last_chance", 1))
        assertEquals(3, clampRefreshSeconds("encore", 2))
    }

    @Test
    fun refreshRatesAreCappedAtSixtySeconds() {
        assertEquals(60, clampRefreshSeconds("potluck", 120))
        assertEquals(60, clampRefreshSeconds("last_chance", 120))
        assertEquals(60, clampRefreshSeconds("encore", 120))
    }

    @Test
    fun allServerRequestsKeepTheThreeSecondSafetyGap() {
        assertEquals(3_000L, MIN_GLOBAL_REQUEST_GAP_MS)
    }
}