package io.motohub.android.tbox

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EasyConnRetryTest {
    @Test
    fun `returns immediately when the first attempt succeeds`() = runBlocking {
        val attempts = mutableListOf<Int>()

        val result = retryEasyConnStart(
            policy = policy(),
            shouldRetry = { true },
            sleeper = { fail("A successful first attempt must not sleep") }
        ) { attempt ->
            attempts += attempt
            "ready"
        }

        assertEquals("ready", result)
        assertEquals(listOf(1), attempts)
    }

    @Test
    fun `retries transient failures with bounded exponential delays`() = runBlocking {
        val attempts = mutableListOf<Int>()
        val delays = mutableListOf<Long>()
        val retryAttempts = mutableListOf<Int>()

        val result = retryEasyConnStart(
            policy = policy(),
            shouldRetry = ::isTransientEasyConnFailure,
            onRetry = { failedAttempt, _, _ -> retryAttempts += failedAttempt },
            sleeper = delays::add
        ) { attempt ->
            attempts += attempt
            if (attempt < 3) throw SocketTimeoutException("T-Box did not answer")
            "ready"
        }

        assertEquals("ready", result)
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(listOf(1, 2), retryAttempts)
        assertEquals(listOf(100L, 200L), delays)
    }

    @Test
    fun `stops after the configured attempt budget`() = runBlocking {
        val expected = IOException("connection reset")
        var attempts = 0

        try {
            retryEasyConnStart(
                policy = policy(),
                shouldRetry = ::isTransientEasyConnFailure,
                sleeper = { }
            ) {
                attempts++
                throw expected
            }
            fail("The final failure must be returned")
        } catch (failure: Throwable) {
            assertSame(expected, failure)
        }

        assertEquals(3, attempts)
    }

    @Test
    fun `does not retry permanent configuration failures`() = runBlocking {
        val expected = IllegalStateException("host is not set or has not been found")
        var attempts = 0

        try {
            retryEasyConnStart(
                policy = policy(),
                shouldRetry = ::isTransientEasyConnFailure,
                sleeper = { fail("A permanent failure must not sleep") }
            ) {
                attempts++
                throw expected
            }
            fail("The permanent failure must be returned")
        } catch (failure: Throwable) {
            assertSame(expected, failure)
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `propagates cancellation without retrying`() = runBlocking {
        val expected = CancellationException("cancelled by user")
        var attempts = 0

        try {
            retryEasyConnStart(
                policy = policy(),
                shouldRetry = { true },
                sleeper = { fail("Cancellation must not sleep") }
            ) {
                attempts++
                throw expected
            }
            fail("Cancellation must be propagated")
        } catch (failure: Throwable) {
            assertSame(expected, failure)
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `classifies Go timeout messages but rejects permanent startup errors`() {
        assertTrue(isTransientEasyConnFailure(Exception("context deadline exceeded")))
        assertTrue(isTransientEasyConnFailure(Exception("initialize EasyConn stream: no response")))
        assertTrue(isTransientEasyConnFailure(IOException("network unreachable")))
        assertFalse(isTransientEasyConnFailure(Exception("already running")))
        assertFalse(isTransientEasyConnFailure(Exception("start reverse server 1: bind failed")))
        assertFalse(isTransientEasyConnFailure(IOException("start reverse server 1: bind failed")))
        assertFalse(isTransientEasyConnFailure(CancellationException("cancelled")))
    }

    private fun policy() = EasyConnRetryPolicy(
        maxAttempts = 3,
        initialDelayMillis = 100,
        maximumDelayMillis = 250,
        backoffMultiplier = 2
    )
}
