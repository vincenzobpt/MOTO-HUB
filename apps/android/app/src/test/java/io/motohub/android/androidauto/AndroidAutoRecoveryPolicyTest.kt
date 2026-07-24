package io.motohub.android.androidauto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoRecoveryPolicyTest {
    @Test
    fun `recovery requires both an enabled preference and prior streaming`() {
        assertFalse(shouldAutoRecoverAndroidAuto(hasReachedStreaming = false, enabled = false))
        assertFalse(shouldAutoRecoverAndroidAuto(hasReachedStreaming = false, enabled = true))
        assertFalse(shouldAutoRecoverAndroidAuto(hasReachedStreaming = true, enabled = false))
        assertTrue(shouldAutoRecoverAndroidAuto(hasReachedStreaming = true, enabled = true))
    }

    @Test
    fun `watchdog waits until the complete stall threshold`() {
        assertFalse(
            isAndroidAutoWatchdogStalled(
                nowElapsed = 19_999L,
                lastProgressElapsed = 10_000L,
                thresholdMillis = 10_000L
            )
        )
        assertTrue(
            isAndroidAutoWatchdogStalled(
                nowElapsed = 20_000L,
                lastProgressElapsed = 10_000L,
                thresholdMillis = 10_000L
            )
        )
    }

    @Test
    fun `watchdog ignores an uninitialized progress clock`() {
        assertFalse(
            isAndroidAutoWatchdogStalled(
                nowElapsed = 30_000L,
                lastProgressElapsed = 0L,
                thresholdMillis = 10_000L
            )
        )
    }
}
