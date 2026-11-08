// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.tbox.MySpinControl.Event
import io.motohub.android.tbox.MySpinControl.WirelessMode
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MySpinControlTest {

    private val sent = mutableListOf<JSONObject>()
    private var nextId = 0
    private val control = MySpinControl("IT", send = { sent += JSONObject(it) }, newId = { "id${++nextId}" })

    private fun sentTypes() = sent.map { it.getString("type") }

    private fun lastSent(type: String) = sent.last { it.getString("type") == type }

    /** The dash answering the last request of [requestType]. */
    private fun answer(requestType: String, responseType: String, payload: JSONObject = JSONObject()) =
        control.receive(
            JSONObject().put("id", lastSent(requestType).getString("id")).put("type", responseType).put("payload", payload).toString()
        )

    private fun dash(type: String, payload: JSONObject = JSONObject(), id: String = "dash") =
        control.receive(JSONObject().put("id", id).put("type", type).put("payload", payload).toString())

    private fun initAs(connectionType: String, authenticationType: String): List<Event> {
        control.begin()
        answer(MySpinControl.PHONE_DETECT_REQUEST, MySpinControl.IVI_DETECT_RESPONSE)
        return answer(
            MySpinControl.PHONE_INIT_REQUEST,
            MySpinControl.IVI_INIT_RESPONSE,
            JSONObject().put("version", 3).put("customerID", "ducati")
                .put("connectionType", connectionType).put("authenticationType", authenticationType)
        )
    }

    @Test
    fun theDetectRequestCarriesTheVersionAtTopLevelAndNoPayload() {
        control.begin()
        val detect = sent.single()
        assertEquals(MySpinControl.PHONE_DETECT_REQUEST, detect.getString("type"))
        assertEquals("3", detect.getString("version"))
        assertFalse(detect.has("payload"))
    }

    @Test
    fun theDetectResponseIsFollowedByTheInitWithRegionAndOs() {
        control.begin()
        answer(MySpinControl.PHONE_DETECT_REQUEST, MySpinControl.IVI_DETECT_RESPONSE)
        val init = lastSent(MySpinControl.PHONE_INIT_REQUEST).getJSONObject("payload")
        assertEquals("IT", init.getString("regionCode"))
        assertEquals(3, init.getInt("version"))
        assertEquals("Android", init.getString("phoneOS"))
    }

    @Test
    fun accessPointCredentialsAreAskedOnlyOnceTheDashWifiIsReady() {
        val events = initAs("AP", "PSK")
        assertEquals(Event.Capabilities("ducati", "AP", "PSK", WirelessMode.ACCESS_POINT), events.single())
        assertTrue(sentTypes().containsAll(listOf(MySpinControl.PHONE_WLAN_STATE_NOTIFICATION, MySpinControl.PHONE_WLAN_STATE_REQUEST)))
        assertFalse(sentTypes().contains(MySpinControl.PHONE_AP_REQUEST))

        dash(MySpinControl.IVI_WLAN_STATE_NOTIFICATION, JSONObject().put("iviWlanState", "BOOTING"))
        assertFalse(sentTypes().contains(MySpinControl.PHONE_AP_REQUEST))

        dash(MySpinControl.IVI_WLAN_STATE_NOTIFICATION, JSONObject().put("iviWlanState", "READY"))
        assertEquals(1, sentTypes().count { it == MySpinControl.PHONE_AP_REQUEST })

        val credentials = answer(
            MySpinControl.PHONE_AP_REQUEST,
            MySpinControl.IVI_AP_RESPONSE,
            JSONObject().put("ssid", "MTS-V4").put("password", "secret12").put("frequencyBand", "5GHz")
        )
        assertEquals(listOf(Event.AccessPoint("MTS-V4", "secret12", "5GHz")), credentials)
    }

    @Test
    fun theWlanStateResponseCanAlsoCarryReadiness() {
        initAs("AP", "PSK")
        answer(MySpinControl.PHONE_WLAN_STATE_REQUEST, MySpinControl.IVI_WLAN_STATE_RESPONSE, JSONObject().put("iviWlanState", "READY"))
        assertTrue(sentTypes().contains(MySpinControl.PHONE_AP_REQUEST))
    }

    @Test
    fun pushButtonWifiDirectWaitsForThePhoneDeviceName() {
        initAs("P2P", "PBC")
        dash(MySpinControl.IVI_WLAN_STATE_NOTIFICATION, JSONObject().put("iviWlanState", "READY"))
        assertFalse(sentTypes().contains(MySpinControl.PHONE_P2P_PBC_REQUEST))

        control.requestWifiDirectPushButton("Vincenzo OnePlus")
        assertEquals("Vincenzo OnePlus", lastSent(MySpinControl.PHONE_P2P_PBC_REQUEST).getJSONObject("payload").getString("deviceName"))

        val events = answer(MySpinControl.PHONE_P2P_PBC_REQUEST, MySpinControl.IVI_P2P_PBC_RESPONSE, JSONObject().put("deviceName", "MTS-TFT"))
        assertEquals(listOf(Event.WifiDirect("MTS-TFT", null)), events)
    }

    @Test
    fun pinWifiDirectReturnsThePin() {
        initAs("P2P", "PIN")
        dash(MySpinControl.IVI_WLAN_STATE_NOTIFICATION, JSONObject().put("iviWlanState", "READY"))
        val events = answer(
            MySpinControl.PHONE_P2P_PIN_REQUEST,
            MySpinControl.IVI_P2P_PIN_RESPONSE,
            JSONObject().put("deviceName", "MTS-TFT").put("pin", "12345670")
        )
        assertEquals(listOf(Event.WifiDirect("MTS-TFT", "12345670")), events)
    }

    @Test
    fun anUnsupportedWirelessModeFails() {
        assertTrue(initAs("AP", "OPEN").single() is Event.Failed)
    }

    @Test
    fun anotherControlVersionFails() {
        control.begin()
        answer(MySpinControl.PHONE_DETECT_REQUEST, MySpinControl.IVI_DETECT_RESPONSE)
        val events = answer(
            MySpinControl.PHONE_INIT_REQUEST,
            MySpinControl.IVI_INIT_RESPONSE,
            JSONObject().put("version", 2).put("connectionType", "AP").put("authenticationType", "PSK")
        )
        assertTrue(events.single() is Event.Failed)
    }

    @Test
    fun aResponseToNothingWeAskedFails() {
        control.begin()
        assertTrue(dash(MySpinControl.IVI_INIT_RESPONSE, id = "id99").single() is Event.Failed)
    }

    @Test
    fun theDashAskingOurWlanStateGetsAnAnswerWithItsOwnId() {
        initAs("AP", "PSK")
        control.reportPhoneWlan(MySpinControl.PhoneWlan.CONNECTED)
        dash(MySpinControl.IVI_WLAN_STATE_REQUEST, id = "dash-7")
        val reply = lastSent(MySpinControl.PHONE_WLAN_STATE_RESPONSE)
        assertEquals("dash-7", reply.getString("id"))
        assertEquals("CONNECTED", reply.getJSONObject("payload").getString("phoneWlanState"))
    }

    @Test
    fun anUnknownMessageIsReportedBackToTheDash() {
        assertTrue(dash("iviSomethingNewNotification").isEmpty())
        assertEquals(
            "iviSomethingNewNotification",
            lastSent(MySpinControl.PHONE_UNKNOWN_TYPE_NOTIFICATION).getJSONObject("payload").getString("unknownType")
        )
    }

    @Test
    fun aDashErrorFailsAndEverythingAfterIsIgnored() {
        initAs("AP", "PSK")
        val events = dash(MySpinControl.IVI_ERROR_NOTIFICATION, JSONObject().put("iviError", "WLAN_BUSY"))
        assertTrue((events.single() as Event.Failed).reason.contains("WLAN_BUSY"))
        assertTrue(dash(MySpinControl.IVI_WLAN_STATE_NOTIFICATION, JSONObject().put("iviWlanState", "READY")).isEmpty())
    }

    @Test
    fun anUnreadableLineFails() {
        assertTrue(control.receive("not json").single() is Event.Failed)
    }

    @Test
    fun aMissingCredentialFieldFails() {
        initAs("AP", "PSK")
        dash(MySpinControl.IVI_WLAN_STATE_NOTIFICATION, JSONObject().put("iviWlanState", "READY"))
        val events = answer(MySpinControl.PHONE_AP_REQUEST, MySpinControl.IVI_AP_RESPONSE, JSONObject().put("ssid", "MTS-V4"))
        assertTrue(events.single() is Event.Failed)
    }

    @Test
    fun controlLinesAreSplitOnNewlinesAndEmptyOnesSkipped() {
        val input = ByteArrayInputStream("{\"a\":1}\r\n\n{\"b\":2}\n".toByteArray())
        assertEquals("{\"a\":1}", MySpinControl.readLine(input))
        assertEquals("{\"b\":2}", MySpinControl.readLine(input))
        assertNull(MySpinControl.readLine(input))
    }

    @Test(expected = EOFException::class)
    fun aLineCutOffByTheStreamEndingIsAnError() {
        MySpinControl.readLine(ByteArrayInputStream("{\"a\":".toByteArray()))
    }

    @Test(expected = IOException::class)
    fun anOversizedLineIsAnError() {
        MySpinControl.readLine(ByteArrayInputStream(ByteArray(20) { 'x'.code.toByte() }), maxBytes = 10)
    }

    @Test
    fun thePhoneSaysItsWifiIsReadyUntilItHasJoined() {
        // Bosch and MTScreen both start at READY; DISABLED made a Suzuki dash drop its Wi-Fi Direct.
        assertEquals(MySpinControl.PhoneWlan.READY, MySpinControl("IT", send = {}).phoneWlan)
    }
}
