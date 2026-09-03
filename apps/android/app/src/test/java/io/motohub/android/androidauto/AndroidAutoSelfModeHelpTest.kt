// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoSelfModeHelpTest {
    @Test
    fun `versions verified working are not flagged`() {
        // 17.2.662634 is the build Android Auto projection was confirmed working on - and a rider
        // log of 2026-07-31 had that same build refuse WirelessStartupActivity as not exported, so
        // this stays a warning about the odds and never a verdict. What decides it at runtime is
        // whether an entry point accepted the intent, not the number.
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion("17.2.662634-release"))
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion("17.1.6624"))
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion("16.9.999999-release"))
    }

    @Test
    fun `versions that removed self-mode are flagged`() {
        // 17.4.663004 is the build where every entry point stopped working.
        assertTrue(AndroidAutoSelfModeHelp.isKnownBrokenVersion("17.4.663004-release"))
        assertTrue(AndroidAutoSelfModeHelp.isKnownBrokenVersion("17.3.0"))
        assertTrue(AndroidAutoSelfModeHelp.isKnownBrokenVersion("18.0.1-release"))
    }

    @Test
    fun `every self-mode failure opens the setup help`() {
        // The failures have different remedies but the same help screen, and the screen is what
        // carries the full sequence - a message that stopped matching here would take the rider's
        // only route to it away.
        assertTrue(AndroidAutoSelfModeHelp.isMessageAboutSelfMode(AndroidAutoSelfModeHelp.NEVER_CONNECTED_MESSAGE))
        assertTrue(
            AndroidAutoSelfModeHelp.isMessageAboutSelfMode(AndroidAutoSelfModeHelp.ACCEPTED_BUT_SILENT_MESSAGE)
        )
        assertTrue(
            AndroidAutoSelfModeHelp.isMessageAboutSelfMode(
                AndroidAutoSelfModeHelp.ACCEPTED_BUT_SILENT_ON_CLOSED_RELEASE_MESSAGE
            )
        )
        assertFalse(AndroidAutoSelfModeHelp.isMessageAboutSelfMode("Android Auto connected without delivering video."))
        assertFalse(AndroidAutoSelfModeHelp.isMessageAboutSelfMode(null))
    }

    @Test
    fun `a release that closed self-mode is sent to the head unit server, not the switch`() {
        // Field case FF3D-A418: 17.4.663054, "Add new cars" already on, ten failures in an hour,
        // :5277 never started. Naming the switch first is what cost that rider the hour.
        assertEquals(
            AndroidAutoSelfModeHelp.ACCEPTED_BUT_SILENT_ON_CLOSED_RELEASE_MESSAGE,
            AndroidAutoSelfModeHelp.acceptedButSilentMessage("17.4.663054-release")
        )
        assertTrue(
            AndroidAutoSelfModeHelp.ACCEPTED_BUT_SILENT_ON_CLOSED_RELEASE_MESSAGE
                .substringBefore("Add new cars")
                .contains("Start head unit server")
        )
    }

    @Test
    fun `a release that still has self-mode keeps the switch as its remedy`() {
        // On 17.2 the acceptance really is a trust decision, and the head unit server does not
        // fix that one - so this must not become "head unit server for everybody".
        assertEquals(
            AndroidAutoSelfModeHelp.ACCEPTED_BUT_SILENT_MESSAGE,
            AndroidAutoSelfModeHelp.acceptedButSilentMessage("17.2.662634-release")
        )
        // An unreadable version is not evidence the release closed anything, so it keeps the
        // older remedy rather than being promoted into the newer one.
        assertEquals(
            AndroidAutoSelfModeHelp.ACCEPTED_BUT_SILENT_MESSAGE,
            AndroidAutoSelfModeHelp.acceptedButSilentMessage(null)
        )
    }

    @Test
    fun `a rider step is told apart from the narration around it`() {
        // The home screen decides between a caption and a card on this, so narration must never
        // match: promoting "Asking Android Auto to project…" to an ACTION NEEDED card would tell
        // the rider to go do something while the app is still trying.
        assertEquals(
            AndroidAutoSelfModeHelp.HEAD_UNIT_SERVER_STEP,
            AndroidAutoSelfModeHelp.riderStepOf(AndroidAutoSelfModeHelp.HEAD_UNIT_SERVER_STEP.flat)
        )
        assertEquals(
            AndroidAutoSelfModeHelp.ADD_NEW_CARS_STEP,
            AndroidAutoSelfModeHelp.riderStepOf(AndroidAutoSelfModeHelp.ADD_NEW_CARS_STEP.flat)
        )
        assertNull(AndroidAutoSelfModeHelp.riderStepOf("Asking Android Auto to project…"))
        assertNull(AndroidAutoSelfModeHelp.riderStepOf("Android Auto is starting up…"))
        assertNull(AndroidAutoSelfModeHelp.riderStepOf(null))
    }

    @Test
    fun `the flattened step is the exact line AaSelfMode publishes`() {
        // riderStepOf matches on this text, and a companion app forwards it over AIDL as a plain
        // string with no way back to the object - so rewording either half here silently drops
        // the card and leaves the rider staring at the grey caption again.
        assertEquals(
            "Start \"head unit server\" in the three-dot menu at the top right of Android Auto's " +
                "own settings…",
            AndroidAutoSelfModeHelp.HEAD_UNIT_SERVER_STEP.flat
        )
        assertEquals(
            "Enable \"Add new cars to Android Auto\" in Android Auto, then Developer settings…",
            AndroidAutoSelfModeHelp.ADD_NEW_CARS_STEP.flat
        )
    }

    @Test
    fun `the wording this step used to publish still resolves`() {
        // Core and ADVANCED are two installs, and the flat line crosses between them over AIDL as
        // a plain string. A phone carrying one release of each would otherwise publish the old
        // arrow-and-overflow text, match nothing, and lose the card the reword was for.
        assertEquals(
            AndroidAutoSelfModeHelp.HEAD_UNIT_SERVER_STEP,
            AndroidAutoSelfModeHelp.riderStepOf(
                "Start \"head unit server\" in Android Auto ▸ Developer settings ▸ ⋮ menu…"
            )
        )
        // The wording in between, which put the server inside Developer settings.
        assertEquals(
            AndroidAutoSelfModeHelp.HEAD_UNIT_SERVER_STEP,
            AndroidAutoSelfModeHelp.riderStepOf(
                "Start \"head unit server\" in Android Auto, then Developer settings, then the " +
                    "three-dot menu at the top right…"
            )
        )
        assertEquals(
            AndroidAutoSelfModeHelp.ADD_NEW_CARS_STEP,
            AndroidAutoSelfModeHelp.riderStepOf(
                "Enable \"Add new cars to Android Auto\" in Android Auto ▸ Developer settings…"
            )
        )
    }

    @Test
    fun `the head unit server is never placed inside Developer settings`() {
        // It is in the three-dot menu on Android Auto's ordinary settings screen; Developer
        // settings only has to be unlocked for that menu to appear. A rider sent into the
        // Developer settings list scrolls it forever - which is what these texts used to do.
        val texts = listOf(
            AndroidAutoSelfModeHelp.NEVER_CONNECTED_MESSAGE,
            AndroidAutoSelfModeHelp.ACCEPTED_BUT_SILENT_MESSAGE,
            AndroidAutoSelfModeHelp.ACCEPTED_BUT_SILENT_ON_CLOSED_RELEASE_MESSAGE,
            AndroidAutoSelfModeHelp.HEAD_UNIT_SERVER_STEP.flat
        )
        texts.filter { it.contains("head unit server") }.forEach { text ->
            assertTrue(text, text.contains("three-dot menu"))
        }
        assertTrue(
            AndroidAutoSelfModeHelp.HEAD_UNIT_SERVER_STEP.where.contains("three-dot menu")
        )
        assertFalse(
            AndroidAutoSelfModeHelp.HEAD_UNIT_SERVER_STEP.where.contains("Developer settings")
        )
        // And the unlock that reveals it is still named, or the menu is empty for a new rider.
        assertTrue(
            AndroidAutoSelfModeHelp.HEAD_UNIT_SERVER_STEP.prerequisite
                .orEmpty().contains("ten times")
        )
    }

    @Test
    fun `the rider-facing steps carry no arrow or overflow glyphs`() {
        // Rendered small on a bike, "▸" is a speck and "⋮" reads as a colon. Only the legacy
        // map may still contain them, and it is private.
        val texts = listOf(
            AndroidAutoSelfModeHelp.NEVER_CONNECTED_MESSAGE,
            AndroidAutoSelfModeHelp.ACCEPTED_BUT_SILENT_MESSAGE,
            AndroidAutoSelfModeHelp.ACCEPTED_BUT_SILENT_ON_CLOSED_RELEASE_MESSAGE,
            AndroidAutoSelfModeHelp.HEAD_UNIT_SERVER_STEP.flat,
            AndroidAutoSelfModeHelp.ADD_NEW_CARS_STEP.flat,
            AndroidAutoSelfModeHelp.HEAD_UNIT_SERVER_STEP.prerequisite.orEmpty(),
            AndroidAutoSelfModeHelp.ADD_NEW_CARS_STEP.prerequisite.orEmpty()
        )
        texts.forEach { text ->
            assertFalse(text, text.contains("▸"))
            assertFalse(text, text.contains("⋮"))
        }
    }

    @Test
    fun `an unreadable version is never flagged`() {
        // Guessing "broken" from a version we cannot parse would scare users off a working setup.
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion(null))
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion(""))
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion("not-a-version"))
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion("17"))
    }
}
