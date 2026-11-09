// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import io.motohub.android.session.ProjectionEventLog

/**
 * Logs the external input devices Android sees while an Android Auto session runs.
 *
 * Some EasyConn dashes (the 1000 MT-X, productType 3) never send a touch over the media-control
 * socket, and the official EasyConn SDK drops socket touches for that product type anyway. The
 * remaining way such a dash could deliver touch is as a Bluetooth HID touchscreen of the phone,
 * which lands on the phone's own display. Nothing logged that until now, so it was never checked.
 * Reading the device list needs no permission.
 */
class ExternalInputDeviceLog(context: Context) {

    data class Device(
        val id: Int,
        val name: String,
        val external: Boolean,
        val virtual: Boolean,
        val sources: Int,
        val vendorId: Int,
        val productId: Int
    )

    private val inputManager = context.getSystemService(InputManager::class.java)
    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = logChange("added", deviceId)
        override fun onInputDeviceRemoved(deviceId: Int) =
            ProjectionEventLog.record(SOURCE, "Input device removed: id=$deviceId.")
        override fun onInputDeviceChanged(deviceId: Int) = logChange("changed", deviceId)
    }
    private var started = false

    fun start() {
        val manager = inputManager ?: return
        if (started) return
        started = true
        val devices = manager.inputDeviceIds.mapNotNull { read(InputDevice.getDevice(it)) }
        val snapshot = describeSnapshot(devices)
        if (devices.any(::isExternalTouch)) {
            ProjectionEventLog.warning(SOURCE, snapshot)
        } else {
            ProjectionEventLog.record(SOURCE, snapshot)
        }
        manager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        if (!started) return
        started = false
        inputManager?.unregisterInputDeviceListener(listener)
    }

    private fun logChange(event: String, deviceId: Int) {
        val device = read(InputDevice.getDevice(deviceId)) ?: return
        if (!isReportable(device)) return
        val line = "Input device $event: ${describe(device)}."
        if (isExternalTouch(device)) {
            ProjectionEventLog.warning(SOURCE, "$line $TOUCH_HINT")
        } else {
            ProjectionEventLog.record(SOURCE, line)
        }
    }

    private fun read(device: InputDevice?): Device? = device?.let {
        Device(
            id = it.id,
            name = it.name.orEmpty(),
            external = it.isExternal,
            virtual = it.isVirtual,
            sources = it.sources,
            vendorId = it.vendorId,
            productId = it.productId
        )
    }

    companion object {
        private const val SOURCE = "INPUT"
        private const val TOUCH_HINT =
            "An external touch device is attached to the phone: if it is the dashboard, its " +
                "touches go to the phone's own screen, not to Android Auto."

        private val SOURCE_NAMES = listOf(
            InputDevice.SOURCE_TOUCHSCREEN to "touchscreen",
            InputDevice.SOURCE_STYLUS to "stylus",
            InputDevice.SOURCE_TOUCHPAD to "touchpad",
            InputDevice.SOURCE_MOUSE to "mouse",
            InputDevice.SOURCE_KEYBOARD to "keyboard",
            InputDevice.SOURCE_DPAD to "dpad",
            InputDevice.SOURCE_GAMEPAD to "gamepad",
            InputDevice.SOURCE_JOYSTICK to "joystick"
        )

        /** Only real, external devices: the phone's own panel and buttons are not of interest. */
        fun isReportable(device: Device): Boolean = device.external && !device.virtual

        fun isExternalTouch(device: Device): Boolean =
            isReportable(device) && listOf(
                InputDevice.SOURCE_TOUCHSCREEN,
                InputDevice.SOURCE_STYLUS,
                InputDevice.SOURCE_TOUCHPAD
            ).any { device.sources and it == it }

        fun describe(device: Device): String {
            val kinds = SOURCE_NAMES.filter { (bit, _) -> device.sources and bit == bit }
                .joinToString("+") { it.second }
                .ifEmpty { "other" }
            return "id=${device.id}, name='${device.name}', kinds=$kinds, " +
                "sources=0x${Integer.toHexString(device.sources)}, " +
                "vendor=0x%04x, product=0x%04x".format(device.vendorId, device.productId)
        }

        fun describeSnapshot(devices: List<Device>): String {
            val external = devices.filter(::isReportable)
            if (external.isEmpty()) {
                return "No external input device is attached to the phone " +
                    "(so no dashboard reaches it as a Bluetooth/USB touchscreen)."
            }
            val touch = external.count(::isExternalTouch)
            val summary = if (touch > 0) {
                "$touch of ${external.size} external input device(s) can touch. $TOUCH_HINT"
            } else {
                "${external.size} external input device(s), none of them a touch device."
            }
            return "$summary Devices: " + external.joinToString("; ", transform = ::describe) + "."
        }
    }
}
