// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.pairing

import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.TBoxConnectionMode
import io.motohub.android.tbox.KOVE_625X_PROVISIONING_MODEL_ID
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxTransportFamily
import io.motohub.android.tbox.ThinkerRideProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The manual-pairing chip that says "KOVE / ThinkerRide (Bluetooth)" has to leave behind the same
 * thing the ThinkerRide QR does, or it selects a transport nothing downstream ever reads.
 */
class ManualPairingProfileTest {

    private fun profile(
        mode: TBoxConnectionMode,
        modelId: String? = null
    ) = MotorcycleProfile(
        ssid = "CQKY_c632fceef",
        password = "secret",
        modelId = modelId,
        connectionMode = mode
    )

    @Test
    fun `picking the ThinkerRide chip stamps the pseudo model id`() {
        val stamped = profile(TBoxConnectionMode.THINKERRIDE).withModelIdForConnectionMode()

        assertEquals(ThinkerRideProtocol.PROVISIONING_MODEL_ID, stamped.modelId)
    }

    /** The point of the stamp: without it the resolver answers GENERIC, i.e. EasyConn. */
    @Test
    fun `the stamped profile now resolves onto the ThinkerRide transport`() {
        val stamped = profile(TBoxConnectionMode.THINKERRIDE).withModelIdForConnectionMode()

        val resolved = TBoxModelProfile.resolve(stamped.modelId, null, ProfileOverride.AUTO)

        assertEquals(TBoxTransportFamily.THINKERRIDE, resolved.transportFamily)
    }

    @Test
    fun `the same profile without the stamp would have gone to EasyConn`() {
        val resolved = TBoxModelProfile.resolve(
            profile(TBoxConnectionMode.THINKERRIDE).modelId,
            null,
            ProfileOverride.AUTO
        )

        assertEquals(TBoxModelProfile.GENERIC, resolved)
        assertEquals(TBoxTransportFamily.EASYCONN, resolved.transportFamily)
    }

    @Test
    fun `moving the mode back off ThinkerRide takes the pseudo id away again`() {
        val corrected = profile(
            TBoxConnectionMode.ACCESS_POINT,
            modelId = ThinkerRideProtocol.PROVISIONING_MODEL_ID
        ).withModelIdForConnectionMode()

        assertNull(corrected.modelId)
    }

    /** A modelId that came from a dash or a QR is nobody's guess to revise. */
    @Test
    fun `a real model id survives a mode that is not ThinkerRide`() {
        val kept = profile(TBoxConnectionMode.WIFI_DIRECT, modelId = "66660732")
            .withModelIdForConnectionMode()

        assertEquals("66660732", kept.modelId)
    }

    @Test
    fun `a real model id is not replaced by the pseudo one on the ThinkerRide chip`() {
        val kept = profile(TBoxConnectionMode.THINKERRIDE, modelId = "66660732")
            .withModelIdForConnectionMode()

        assertEquals("66660732", kept.modelId)
    }

    @Test
    fun `a profile that needs nothing is returned untouched`() {
        val untouched = profile(TBoxConnectionMode.AUTO)

        assertSame(untouched, untouched.withModelIdForConnectionMode())
    }

    @Test
    fun `a blank model id counts as none at all`() {
        val stamped = profile(TBoxConnectionMode.THINKERRIDE, modelId = "   ")
            .withModelIdForConnectionMode()

        assertEquals(ThinkerRideProtocol.PROVISIONING_MODEL_ID, stamped.modelId)
    }

    @Test
    fun `a KOVE 625X network name earns its pseudo model id on the automatic mode`() {
        val stamped = MotorcycleProfile(ssid = "KY_ADV_90f6d3be4cc2", password = "secret")
            .withModelIdForConnectionMode()
        assertEquals(KOVE_625X_PROVISIONING_MODEL_ID, stamped.modelId)
        assertEquals(
            TBoxModelProfile.KOVE_625X,
            TBoxModelProfile.resolve(stamped.modelId, null, ProfileOverride.AUTO)
        )
    }

    @Test
    fun `a real model id is never replaced by the one the network name suggests`() {
        val kept = MotorcycleProfile(ssid = "KY_ADV_90f6d3be4cc2", password = "secret", modelId = "00297")
            .withModelIdForConnectionMode()
        assertEquals("00297", kept.modelId)
    }

    @Test
    fun `the ThinkerRide chip still wins over the network name because the rider chose it`() {
        val stamped = MotorcycleProfile(
            ssid = "KY_ADV_90f6d3be4cc2",
            password = "secret",
            connectionMode = TBoxConnectionMode.THINKERRIDE
        ).withModelIdForConnectionMode()
        assertEquals(ThinkerRideProtocol.PROVISIONING_MODEL_ID, stamped.modelId)
    }

    @Test
    fun `moving off the ThinkerRide chip falls back to what the network name says`() {
        val corrected = MotorcycleProfile(
            ssid = "KY_ADV_90f6d3be4cc2",
            password = "secret",
            modelId = ThinkerRideProtocol.PROVISIONING_MODEL_ID,
            connectionMode = TBoxConnectionMode.AUTO
        ).withModelIdForConnectionMode()
        assertEquals(KOVE_625X_PROVISIONING_MODEL_ID, corrected.modelId)
    }

    @Test
    fun `stamping twice changes nothing`() {
        val once = profile(TBoxConnectionMode.THINKERRIDE).withModelIdForConnectionMode()

        assertSame(once, once.withModelIdForConnectionMode())
    }
}
