// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import android.view.InputDevice
import io.motohub.android.androidauto.ExternalInputDeviceLog.Companion.describeSnapshot
import io.motohub.android.androidauto.ExternalInputDeviceLog.Companion.isExternalTouch
import io.motohub.android.androidauto.ExternalInputDeviceLog.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalInputDeviceLogTest {

    private fun device(
        name: String,
        sources: Int,
        external: Boolean = true,
        virtual: Boolean = false
    ) = Device(
        id = 7,
        name = name,
        external = external,
        virtual = virtual,
        sources = sources,
        vendorId = 0x1234,
        productId = 0x00ab
    )

    @Test
    fun `a bluetooth touchscreen is flagged as an external touch device`() {
        val dash = device("CFMOTO-474862", InputDevice.SOURCE_TOUCHSCREEN)
        assertTrue(isExternalTouch(dash))
        val line = describeSnapshot(listOf(dash))
        assertTrue(line, line.startsWith("1 of 1 external input device(s) can touch."))
        assertTrue(line, line.contains("name='CFMOTO-474862', kinds=touchscreen"))
        assertTrue(line, line.contains("vendor=0x1234, product=0x00ab"))
    }

    @Test
    fun `the phone's own panel and virtual devices are not reported`() {
        val panel = device("sec_touchscreen", InputDevice.SOURCE_TOUCHSCREEN, external = false)
        val virtual = device("Virtual", InputDevice.SOURCE_KEYBOARD, virtual = true)
        assertFalse(isExternalTouch(panel))
        assertEquals(
            "No external input device is attached to the phone " +
                "(so no dashboard reaches it as a Bluetooth/USB touchscreen).",
            describeSnapshot(listOf(panel, virtual))
        )
    }

    @Test
    fun `a handlebar remote is listed but not counted as touch`() {
        val remote = device("BR80", InputDevice.SOURCE_KEYBOARD)
        assertFalse(isExternalTouch(remote))
        val line = describeSnapshot(listOf(remote))
        assertTrue(line, line.startsWith("1 external input device(s), none of them a touch device."))
        assertTrue(line, line.contains("kinds=keyboard"))
    }
}
