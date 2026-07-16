package io.motohub.android.tbox

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class EasyConnRetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMillis: Long = 750L,
    val maximumDelayMillis: Long = 2_000L,
    val backoffMultiplier: Int = 2
) {
    init {
        require(maxAttempts > 0)
        require(initialDelayMillis >= 0)
        require(maximumDelayMillis >= initialDelayMillis)
        require(backoffMultiplier >= 1)
    }
}

internal suspend fun <T> retryEasyConnStart(
    policy: EasyConnRetryPolicy,
    shouldRetry: (Throwable) -> Boolean,
    onRetry: (failedAttempt: Int, delayMillis: Long, failure: Throwable) -> Unit = { _, _, _ -> },
    sleeper: suspend (Long) -> Unit = { delay(it) },
    operation: suspend (attempt: Int) -> T
): T {
    var retryDelayMillis = policy.initialDelayMillis
    for (attempt in 1..policy.maxAttempts) {
        try {
            return operation(attempt)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            if (attempt == policy.maxAttempts || !shouldRetry(failure)) throw failure
            onRetry(attempt, retryDelayMillis, failure)
            sleeper(retryDelayMillis)
            retryDelayMillis = (retryDelayMillis * policy.backoffMultiplier)
                .coerceAtMost(policy.maximumDelayMillis)
        }
    }
    error("EasyConn retry loop completed without a result")
}

internal fun isTransientEasyConnFailure(failure: Throwable): Boolean {
    if (failure is CancellationException) return false

    val failureChain = generateSequence(failure) { it.cause }.toList()
    val detail = failureChain
        .mapNotNull(Throwable::message)
        .joinToString(" ")
        .lowercase()

    if (PERMANENT_FAILURE_MARKERS.any(detail::contains)) return false
    if (failureChain.any { it is IOException }) return true
    return TRANSIENT_FAILURE_MARKERS.any(detail::contains)
}

private val PERMANENT_FAILURE_MARKERS = listOf(
    "already running",
    "host is not set",
    "mux source is not set",
    "invalid ec init socket descriptor",
    "unable to adopt ec init socket",
    "unable to release ec init descriptor",
    "start reverse server"
)

private val TRANSIENT_FAILURE_MARKERS = listOf(
    "context deadline exceeded",
    "i/o timeout",
    "timed out",
    "timeout",
    "connection refused",
    "connection reset",
    "connection closed",
    "broken pipe",
    "no route to host",
    "network is unreachable",
    "failed to decode response",
    "no response",
    "initialize easyconn stream",
    "unsuccessful ec response"
)
