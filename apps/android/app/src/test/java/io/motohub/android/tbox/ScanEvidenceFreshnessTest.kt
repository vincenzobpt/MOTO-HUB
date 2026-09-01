// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The age half of "is the dash on the air?" - see [scanEvidenceIsFresh].
 *
 * Rider 36a3fd37, 2026-09-01: he pressed start while still on his home Wi-Fi, then powered the
 * dash up and returned to MOTO-HUB at 13:32:46 and 13:33:18. Both retries were vetoed by a scan
 * list taken before the dash was switched on. The same dash joined in 5110ms at -40dBm ten
 * minutes later. A list that predates the dash coming up is not weak evidence, it is none.
 */
class ScanEvidenceFreshnessTest {
    private val now = 10_000_000L

    private fun microsAgo(ms: Long) = (now - ms) * 1_000L

    @Test
    fun aScanTakenSecondsAgoStillAnswers() {
        assertTrue(scanEvidenceIsFresh(microsAgo(0), now))
        assertTrue(scanEvidenceIsFresh(microsAgo(5_000), now))
        assertTrue(scanEvidenceIsFresh(microsAgo(SCAN_EVIDENCE_MAX_AGE_MS), now))
    }

    @Test
    fun aScanOlderThanTheDashBeingSwitchedOnAnswersNothing() {
        assertFalse(scanEvidenceIsFresh(microsAgo(SCAN_EVIDENCE_MAX_AGE_MS + 1), now))
        // The gap 36a3fd37 actually sat in.
        assertFalse(scanEvidenceIsFresh(microsAgo(10 * 60 * 1_000), now))
    }

    @Test
    fun aTimestampFromTheFutureIsNotEvidenceOfAgeEither() {
        // A stepped clock must not be read as a stale list; it is simply not an age.
        assertTrue(scanEvidenceIsFresh(microsAgo(-60_000), now))
    }
}
