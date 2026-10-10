package com.sleepguard.poc

import com.sleepguard.poc.ui.rawEventVisible
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the two-category raw-log filter (screen on/off vs lock/unlock).
 * The composable UI around it is not tested here; only the visibility predicate.
 */
class RawActivityLogFilterTest {

    private val screenTypes = listOf("SCREEN_INTERACTIVE", "SCREEN_NON_INTERACTIVE")
    private val lockTypes = listOf("KEYGUARD_HIDDEN", "KEYGUARD_SHOWN")

    @Test
    fun bothOn_showsEverything() {
        (screenTypes + lockTypes).forEach {
            assertTrue(it, rawEventVisible(it, showScreen = true, showLock = true))
        }
    }

    @Test
    fun bothOff_hidesEverything() {
        (screenTypes + lockTypes).forEach {
            assertFalse(it, rawEventVisible(it, showScreen = false, showLock = false))
        }
    }

    @Test
    fun screenOnly_showsScreenHidesLock() {
        screenTypes.forEach { assertTrue(it, rawEventVisible(it, showScreen = true, showLock = false)) }
        lockTypes.forEach { assertFalse(it, rawEventVisible(it, showScreen = true, showLock = false)) }
    }

    @Test
    fun lockOnly_showsLockHidesScreen() {
        lockTypes.forEach { assertTrue(it, rawEventVisible(it, showScreen = false, showLock = true)) }
        screenTypes.forEach { assertFalse(it, rawEventVisible(it, showScreen = false, showLock = true)) }
    }

    @Test
    fun unknownType_visibleUnlessAllFilteredOut() {
        assertTrue(rawEventVisible("UNKNOWN", showScreen = true, showLock = false))
        assertTrue(rawEventVisible("UNKNOWN", showScreen = false, showLock = true))
        assertFalse(rawEventVisible("UNKNOWN", showScreen = false, showLock = false))
    }
}
