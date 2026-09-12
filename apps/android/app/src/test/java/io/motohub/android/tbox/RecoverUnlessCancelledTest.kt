// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A rider's Cancel is not a road worth retrying - see [recoverUnlessCancelled].
 *
 * Case 94e45e62 (Zontes 125X, Pixel 10 Pro, CORE 1.1.117, 2026-09-12) is the log this exists for.
 * The connect job was cancelled at 00:45:13.648; `recoverCatching` caught the resulting
 * CancellationException like any other failure and ran the access-point fallback inside the dead
 * job, which recorded that it was joining ZT663590 - measured at -46dBm on 5180MHz that instant -
 * and collapsed 9ms later as "T-Box AP connection failed: A20 was cancelled". The rider was told
 * their motorcycle's access point had failed, and the road that would have worked was spent.
 */
class RecoverUnlessCancelledTest {

    @Test
    fun aCancelIsRethrownAndTheOtherRoadIsNeverEntered() {
        var recoveryRan = false
        val cancel = CancellationException("A20 was cancelled")
        val thrown = try {
            Result.failure<String>(cancel).recoverUnlessCancelled {
                recoveryRan = true
                "joined ZT663590"
            }
            null
        } catch (caught: CancellationException) {
            caught
        }
        assertSame("the cancel must propagate, not be swallowed into a Result", cancel, thrown)
        assertTrue(
            "the fallback must not be burned on a job that cannot carry it",
            !recoveryRan
        )
    }

    @Test
    fun aJobCancellationSubclassIsACancelToo() {
        // kotlinx.coroutines throws JobCancellationException, a subclass: "A20 was cancelled;
        // job=A20{Cancelling}@ea8fec2" in the rider's log. The check is on the type, not the text.
        class JobCancellation(message: String) : CancellationException(message)
        try {
            Result.failure<String>(JobCancellation("A20 was cancelled")).recoverUnlessCancelled {
                fail("a cancelled job must not reach the recovery block")
                ""
            }
            fail("expected the cancel to propagate")
        } catch (expected: CancellationException) {
            assertEquals("A20 was cancelled", expected.message)
        }
    }

    @Test
    fun anOrdinaryFailureStillRecovers() {
        // The whole point of the recovery: "no hotspot is running" must still reach the road that
        // joins the dash's own access point.
        val recovered = Result.failure<String>(IOException("no hotspot is running"))
            .recoverUnlessCancelled { "joined ZT663590" }
        assertEquals("joined ZT663590", recovered.getOrNull())
    }

    @Test
    fun aSuccessIsPassedThroughUntouched() {
        var recoveryRan = false
        val success = Result.success("hosted network found").recoverUnlessCancelled {
            recoveryRan = true
            "joined ZT663590"
        }
        assertEquals("hosted network found", success.getOrNull())
        assertTrue("a success has nothing to recover from", !recoveryRan)
    }

    @Test
    fun aFailureInsideTheRecoveryIsCarriedNotThrown() {
        // Same contract as recoverCatching, which the PHONE_HOTSPOT branch relies on: the
        // access-point join may fail, and its failure has to arrive as a Result the caller can
        // replace with the hotspot message.
        val failed = Result.failure<String>(IOException("no hotspot is running"))
            .recoverUnlessCancelled { throw IOException("T-Box AP connection failed") }
        assertEquals("T-Box AP connection failed", failed.exceptionOrNull()?.message)
    }
}
