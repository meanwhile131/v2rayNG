package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoSelectManagerTest {

    // ---------- selectBestServer ----------

    @Test
    fun selectBestServer_returnsGuidWithLowestPositiveDelay() {
        val results = mapOf("guid-1" to 200L, "guid-2" to 100L, "guid-3" to 300L)
        assertEquals("guid-2", AutoSelectManager.selectBestServer(results))
    }

    @Test
    fun selectBestServer_ignoresFailedServers() {
        val results = mapOf("guid-1" to -1L, "guid-2" to 100L, "guid-3" to -1L)
        assertEquals("guid-2", AutoSelectManager.selectBestServer(results))
    }

    @Test
    fun selectBestServer_ignoresUnknownDelay() {
        val results = mapOf("guid-1" to 0L, "guid-2" to 100L)
        assertEquals("guid-2", AutoSelectManager.selectBestServer(results))
    }

    @Test
    fun selectBestServer_returnsNullWhenNothingWorks() {
        val results = mapOf("guid-1" to -1L, "guid-2" to 0L)
        assertNull(AutoSelectManager.selectBestServer(results))
    }

    @Test
    fun selectBestServer_returnsNullForEmptyResults() {
        assertNull(AutoSelectManager.selectBestServer(emptyMap()))
    }

    @Test
    fun selectBestServer_returnsOnlyWorkingCandidate() {
        assertEquals("guid-1", AutoSelectManager.selectBestServer(mapOf("guid-1" to 100L)))
    }

    // ---------- shouldSwitch ----------

    @Test
    fun shouldSwitch_switchesWhenCurrentFailed() {
        assertTrue(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = -1L,
                bestDelayMillis = 120L,
                bestGuid = "guid-2",
                currentGuid = "guid-1"
            )
        )
    }

    @Test
    fun shouldSwitch_switchesWhenCurrentUnmeasured() {
        assertTrue(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = null,
                bestDelayMillis = 120L,
                bestGuid = "guid-2",
                currentGuid = "guid-1"
            )
        )
    }

    @Test
    fun shouldSwitch_switchesWhenCurrentUntested() {
        assertTrue(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = 0L,
                bestDelayMillis = 120L,
                bestGuid = "guid-2",
                currentGuid = "guid-1"
            )
        )
    }

    @Test
    fun shouldSwitch_switchesWhenBestIsMuchFaster() {
        assertTrue(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = 800L,
                bestDelayMillis = 100L,
                bestGuid = "guid-2",
                currentGuid = "guid-1"
            )
        )
    }

    @Test
    fun shouldSwitch_doesNotSwitchOnMarginalDifference() {
        // 700ms -> 600ms is jitter, not a reason to drop the tunnel.
        assertFalse(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = 700L,
                bestDelayMillis = 600L,
                bestGuid = "guid-2",
                currentGuid = "guid-1"
            )
        )
    }

    @Test
    fun shouldSwitch_doesNotSwitchWhenBestIsSlower() {
        assertFalse(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = 100L,
                bestDelayMillis = 500L,
                bestGuid = "guid-2",
                currentGuid = "guid-1"
            )
        )
    }

    @Test
    fun shouldSwitch_doesNotSwitchToSameServer() {
        assertFalse(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = 500L,
                bestDelayMillis = 100L,
                bestGuid = "guid-1",
                currentGuid = "guid-1"
            )
        )
    }

    @Test
    fun shouldSwitch_doesNotSwitchWhenNoWorkingCandidate() {
        assertFalse(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = 500L,
                bestDelayMillis = null,
                bestGuid = null,
                currentGuid = "guid-1"
            )
        )
    }

    @Test
    fun shouldSwitch_doesNotSwitchWhenCandidateUnmeasured() {
        assertFalse(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = 500L,
                bestDelayMillis = 0L,
                bestGuid = "guid-2",
                currentGuid = "guid-1"
            )
        )
    }

    @Test
    fun shouldSwitch_switchesWhenCurrentFailedEvenIfCandidateSlower() {
        // A dead config must yield to any config that answers, however slow.
        assertTrue(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = -1L,
                bestDelayMillis = 2000L,
                bestGuid = "guid-2",
                currentGuid = "guid-1"
            )
        )
    }

    // ---------- shouldPreferFastestOnStart (loop guard) ----------

    @Test
    fun preferFastestOnStart_trueForFreshStart() {
        assertTrue(
            AutoSelectManager.shouldPreferFastestOnStart(
                currentGuid = "guid-1",
                lastAutoSelectedGuid = "guid-other",
                isFirstCheck = true
            )
        )
    }

    @Test
    fun preferFastestOnStart_falseAfterOwnSwitch() {
        // The switch restarted the tunnel; this worker must not start another one.
        assertFalse(
            AutoSelectManager.shouldPreferFastestOnStart(
                currentGuid = "guid-1",
                lastAutoSelectedGuid = "guid-1",
                isFirstCheck = true
            )
        )
    }

    @Test
    fun preferFastestOnStart_trueAfterManualSelection() {
        // The user picked a different config, so the next start should optimize again.
        assertFalse(
            AutoSelectManager.shouldPreferFastestOnStart(
                currentGuid = "guid-manual",
                lastAutoSelectedGuid = "guid-manual",
                isFirstCheck = true
            )
        )
        assertTrue(
            AutoSelectManager.shouldPreferFastestOnStart(
                currentGuid = "guid-manual",
                lastAutoSelectedGuid = "guid-1",
                isFirstCheck = true
            )
        )
    }

    @Test
    fun preferFastestOnStart_falseWhenNotFirstCheck() {
        assertFalse(
            AutoSelectManager.shouldPreferFastestOnStart(
                currentGuid = "guid-1",
                lastAutoSelectedGuid = null,
                isFirstCheck = false
            )
        )
    }

    @Test
    fun preferFastestOnStart_falseWhenNoConfigRunning() {
        assertFalse(
            AutoSelectManager.shouldPreferFastestOnStart(
                currentGuid = null,
                lastAutoSelectedGuid = null,
                isFirstCheck = true
            )
        )
    }

    // ---------- preferFastest (first check after a start) ----------

    @Test
    fun shouldSwitch_preferFastestTakesBestEvenOnMarginalGain() {
        // 700ms -> 600ms is refused in steady state but accepted on the first check.
        assertFalse(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = 700L,
                bestDelayMillis = 600L,
                bestGuid = "guid-2",
                currentGuid = "guid-1"
            )
        )
        assertTrue(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = 700L,
                bestDelayMillis = 600L,
                bestGuid = "guid-2",
                currentGuid = "guid-1",
                preferFastest = true,
            )
        )
    }

    @Test
    fun shouldSwitch_preferFastestStillNeedsAWorkingCandidate() {
        assertFalse(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = 100L,
                bestDelayMillis = null,
                bestGuid = null,
                currentGuid = "guid-1",
                preferFastest = true,
            )
        )
        assertFalse(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = 100L,
                bestDelayMillis = 0L,
                bestGuid = "guid-2",
                currentGuid = "guid-1",
                preferFastest = true,
            )
        )
    }

    @Test
    fun shouldSwitch_preferFastestDoesNotSwitchToSameServer() {
        assertFalse(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = 500L,
                bestDelayMillis = 100L,
                bestGuid = "guid-1",
                currentGuid = "guid-1",
                preferFastest = true,
            )
        )
    }

    @Test
    fun shouldSwitch_preferFastestTakesBestEvenWhenCurrentIsFaster() {
        // Only the fastest candidate is ever proposed, so this documents that a slower
        // "best" cannot arise; the guard is that best is what selectBestServer returned.
        assertTrue(
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = 50L,
                bestDelayMillis = 40L,
                bestGuid = "guid-2",
                currentGuid = "guid-1",
                preferFastest = true,
            )
        )
    }
}