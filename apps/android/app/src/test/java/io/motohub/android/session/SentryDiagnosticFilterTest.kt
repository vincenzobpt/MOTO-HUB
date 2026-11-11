// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryDiagnosticFilterTest {
    @Test
    fun connectionFailuresWithoutAnExceptionStayOffSentry() {
        assertFalse(isUnexpectedDiagnostic("TBOX", "T-Box AP connection failed.", hasThrowable = false))
        assertFalse(isUnexpectedDiagnostic("WIFI", "Wi-Fi setup timed out after 30000ms", hasThrowable = false))
    }

    @Test
    fun exceptionsCrashesAndLibraryFailuresGoOut() {
        assertTrue(isUnexpectedDiagnostic("RIDE_DASHBOARD", "Unhandled dashboard startup failure", hasThrowable = true))
        assertTrue(isUnexpectedDiagnostic("CRASH", "Previous process ended unexpectedly.", hasThrowable = false))
        assertTrue(isUnexpectedDiagnostic("TBOX", "RideDaemon fatal callback: 3 (EOF)", hasThrowable = false))
    }

    @Test
    fun eachReportOncePerDayUnderTheCap() {
        assertTrue(admitsToday("a", emptySet(), sentToday = 0, cap = 5))
        assertFalse(admitsToday("a", setOf("a"), sentToday = 1, cap = 5))
        assertTrue(admitsToday("b", setOf("a"), sentToday = 1, cap = 5))
        assertFalse(admitsToday("f", setOf("a", "b", "c", "d", "e"), sentToday = 5, cap = 5))
    }
}
