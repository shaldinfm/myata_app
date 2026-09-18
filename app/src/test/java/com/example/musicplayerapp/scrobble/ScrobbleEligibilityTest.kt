package com.example.musicplayerapp.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Last.fm's threshold, pinned at every boundary the rule has. */
class ScrobbleEligibilityTest {

    @Test
    fun `a track of 30 seconds or less never counts`() {
        assertNull(ScrobbleEligibility.thresholdMs(30))
        assertNull(ScrobbleEligibility.thresholdMs(1))
        assertNull(ScrobbleEligibility.thresholdMs(0))
        assertNull(ScrobbleEligibility.thresholdMs(-5))
    }

    @Test
    fun `31 seconds needs half, to the millisecond`() {
        assertEquals(15_500L, ScrobbleEligibility.thresholdMs(31))
    }

    @Test
    fun `below eight minutes the half-duration arm comes first`() {
        assertEquals(30_000L, ScrobbleEligibility.thresholdMs(60))
        assertEquals(90_000L, ScrobbleEligibility.thresholdMs(180))
        assertEquals(239_500L, ScrobbleEligibility.thresholdMs(479))
    }

    @Test
    fun `from eight minutes the four-minute arm comes first`() {
        assertEquals(240_000L, ScrobbleEligibility.thresholdMs(480))
        assertEquals(240_000L, ScrobbleEligibility.thresholdMs(600))
        assertEquals(240_000L, ScrobbleEligibility.thresholdMs(3_600))
    }

    @Test
    fun `tracking needs a configured build, a linked account, and not a TV`() {
        assertTrue(ScrobbleGate.isActive(isTv = false, isConfigured = true, isLinked = true))
        assertFalse("TV stays inert", ScrobbleGate.isActive(isTv = true, isConfigured = true, isLinked = true))
        assertFalse("no credentials", ScrobbleGate.isActive(isTv = false, isConfigured = false, isLinked = true))
        assertFalse("no session", ScrobbleGate.isActive(isTv = false, isConfigured = true, isLinked = false))
        assertFalse(ScrobbleGate.isActive(isTv = true, isConfigured = false, isLinked = false))
    }
}
