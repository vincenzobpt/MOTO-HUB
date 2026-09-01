// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that stops one mode from ending a session another mode is still streaming on.
 *
 * Covers the bookkeeping only; matching the handle and dropping the link need a Context and stay
 * outside unit tests.
 */
class TBoxSessionRegistryClaimTest {
    private val consumers = SessionConsumers()

    @Test
    fun `the only holder releasing is the last one out`() {
        consumers.claim("ride-dashboard")

        assertTrue(consumers.releaseIsLast("ride-dashboard"))
    }

    @Test
    fun `a second holder keeps the session alive`() {
        consumers.claim("ride-dashboard")
        consumers.claim("android-auto")

        assertFalse(consumers.releaseIsLast("android-auto"))
        assertEquals("ride-dashboard", consumers.describe())
    }

    @Test
    fun `releasing a mode that never claimed does not free the session`() {
        consumers.claim("ride-dashboard")

        assertFalse(consumers.releaseIsLast("mirroring"))
    }

    @Test
    fun `claiming twice is idempotent so one release still frees the session`() {
        assertTrue(consumers.claim("android-auto"))
        assertFalse(consumers.claim("android-auto"))

        assertTrue(consumers.releaseIsLast("android-auto"))
    }

    @Test
    fun `a fresh session starts with no holders`() {
        consumers.claim("android-auto")
        consumers.clear()

        assertTrue(consumers.releaseIsLast("ride-dashboard"))
        assertEquals("", consumers.describe())
    }

    /**
     * What the AIDL connect guard asks before it takes the dash: a companion reconnecting on the
     * session it already holds is not a conflict, a mode inside Core is. Field log adb68a95
     * (2026-08-31) is the case where the question was never asked at all.
     */
    @Test
    fun `the companion's own claim does not count as somebody else holding the dash`() {
        consumers.claim("companion-app")

        assertEquals("", consumers.describeOthers("companion-app"))
    }

    @Test
    fun `a mode inside Core does count, even beside the companion's own claim`() {
        consumers.claim("companion-app")
        consumers.claim("android-auto")

        assertEquals("android-auto", consumers.describeOthers("companion-app"))
    }

    @Test
    fun `every other holder is named, in the order they claimed`() {
        consumers.claim("android-auto")
        consumers.claim("companion-app")
        consumers.claim("mirroring")

        assertEquals("android-auto, mirroring", consumers.describeOthers("companion-app"))
    }
}
