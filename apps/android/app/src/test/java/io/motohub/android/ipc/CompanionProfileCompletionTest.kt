// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.TBoxConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * What Core adds to a connect request the companion app could not fill in itself.
 *
 * The case that produced this is adb68a95 (KOVE 450 Rally, 2026-08-31): the rider's ADVANCED
 * profile came from manual pairing and carried no model at all, so every connect over the bridge
 * resolved to the generic EasyConn profile and went looking for an advertisement a ThinkerRide
 * dash never makes - while Core's own garage entry for the very same SSID said THINKERRIDE.
 */
class CompanionProfileCompletionTest {

    private val coreEntry = MotorcycleProfile(
        ssid = "CQKY_c632fceef",
        password = "core-copy",
        modelId = "THINKERRIDE",
        connectionMode = TBoxConnectionMode.THINKERRIDE
    )

    private fun companionRequest(
        modelId: String? = null,
        connectionMode: TBoxConnectionMode = TBoxConnectionMode.AUTO,
        profileOverrideKey: String? = null
    ) = MotorcycleProfile(
        ssid = "CQKY_c632fceef",
        password = "typed-by-the-rider",
        modelId = modelId,
        connectionMode = connectionMode,
        profileOverrideKey = profileOverrideKey
    )

    @Test
    fun `a manually paired companion profile learns the dash from Core's garage`() {
        val completed = companionRequest().completedFrom(coreEntry)

        assertEquals("THINKERRIDE", completed.modelId)
        assertEquals(TBoxConnectionMode.THINKERRIDE, completed.connectionMode)
    }

    /** The credentials and the identity are the caller's; only the blanks are ours to fill. */
    @Test
    fun `the companion's own password and profile id survive the completion`() {
        val request = companionRequest()

        val completed = request.completedFrom(coreEntry)

        assertEquals("typed-by-the-rider", completed.password)
        assertEquals(request.id, completed.id)
    }

    @Test
    fun `a model the companion already knows is never overwritten`() {
        val completed = companionRequest(modelId = "66660732").completedFrom(coreEntry)

        assertEquals("66660732", completed.modelId)
    }

    @Test
    fun `a connection mode the companion chose is never overwritten`() {
        val completed = companionRequest(connectionMode = TBoxConnectionMode.PHONE_HOTSPOT)
            .completedFrom(coreEntry)

        assertEquals(TBoxConnectionMode.PHONE_HOTSPOT, completed.connectionMode)
    }

    /**
     * A pin is the rider saying something deliberate in the app they are actually looking at.
     * Core's guess must not creep in underneath it - not even into a field the pin leaves blank.
     */
    @Test
    fun `a companion that pinned a profile is left entirely alone`() {
        val request = companionRequest(profileOverrideKey = "kove_800x")

        assertSame(request, request.completedFrom(coreEntry))
    }

    @Test
    fun `nothing to complete from is not an error`() {
        val request = companionRequest()

        assertSame(request, request.completedFrom(null))
    }

    @Test
    fun `a garage entry with nothing to add leaves the request untouched`() {
        val request = companionRequest()

        val completed = request.completedFrom(
            MotorcycleProfile(ssid = "CQKY_c632fceef", password = "", modelId = null)
        )

        assertEquals(null, completed.modelId)
        assertEquals(TBoxConnectionMode.AUTO, completed.connectionMode)
    }

    @Test
    fun `a blank model in Core's garage counts as no model at all`() {
        val completed = companionRequest().completedFrom(coreEntry.copy(modelId = "  "))

        assertEquals(null, completed.modelId)
    }
}
