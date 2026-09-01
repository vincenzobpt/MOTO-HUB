// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.app.ActivityManager.RunningAppProcessInfo
import org.junit.Test

class TBoxWifiDirectConnectorTest {
    @Test
    fun `matches the profile group name ignoring case and quotes`() {
        assertTrue(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-CL-C450-1234", null, "DIRECT-CL-C450-1234")
        )
        assertTrue(
            TBoxWifiDirectConnector.groupBelongsToProfile("direct-cl-c450-1234", null, "\"DIRECT-CL-C450-1234\"")
        )
        assertTrue(
            TBoxWifiDirectConnector.groupBelongsToProfile(" DIRECT-AB12 ", null, "DIRECT-AB12")
        )
    }

    @Test
    fun `rejects a formed group that belongs to another device`() {
        assertFalse(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-tv-LivingRoom", null, "DIRECT-CL-C450-1234")
        )
        assertFalse(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-XY99-otherbike", null, "DIRECT-CL-C450-1234")
        )
    }

    @Test
    fun `accepts an unverifiable group name rather than breaking working joins`() {
        assertTrue(TBoxWifiDirectConnector.groupBelongsToProfile(null, null, "DIRECT-CL-C450-1234"))
        assertTrue(TBoxWifiDirectConnector.groupBelongsToProfile("", null, "DIRECT-CL-C450-1234"))
        assertTrue(TBoxWifiDirectConnector.groupBelongsToProfile("  ", null, "DIRECT-CL-C450-1234"))
    }

    /**
     * Field log 94b0a3da: the rider's dash raises a group called `DIRECT-iY` and his profile is
     * saved under the dash's P2P device name. Those two strings can never be equal, so the old
     * name-only check removed a link he had established by hand.
     */
    @Test
    fun `accepts the dash group when the profile holds its device name`() {
        assertTrue(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-iY", "VOGE-5G-9fab", "VOGE-5G-9fab")
        )
        // Owner name unreadable: a mismatch here proves nothing, because it could not have matched.
        assertTrue(TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-iY", null, "VOGE-5G-9fab"))
        assertTrue(TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-iY", "  ", "VOGE-5G-9fab"))
        // Android's own naming, when the framework does put the peer inside the group name.
        assertTrue(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-xy-VOGE-5G-9fab", null, "VOGE-5G-9fab")
        )
    }

    @Test
    fun `rejects a group whose owner is provably another device`() {
        // The only rejection a peer-name profile can make: the owner is readable and is someone else.
        assertFalse(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-iY", "LivingRoom TV", "VOGE-5G-9fab")
        )
        assertFalse(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-tv-LivingRoom", "LivingRoom", "VOGE-5G-9fab")
        )
    }

    @Test
    fun `the group owner identifies the dash for a group-name profile too`() {
        assertTrue(
            TBoxWifiDirectConnector.groupBelongsToProfile(
                "DIRECT-zz-renamed",
                "CFMOTO-EF7198",
                "DIRECT-go-CFMOTO-EF7198"
            )
        )
        // A DIRECT- profile keeps its strict rejection: the owner is readable and is not the dash.
        assertFalse(
            TBoxWifiDirectConnector.groupBelongsToProfile(
                "DIRECT-tv-LivingRoom",
                "LivingRoom",
                "DIRECT-go-CFMOTO-EF7198"
            )
        )
    }

    @Test
    fun `recovers the dash peer name from the group ssid`() {
        // The SSID riders actually reported from the field.
        assertEquals(
            "CFMOTO-EF7198",
            TBoxWifiDirectConnector.peerNameFromGroupSsid("DIRECT-go-CFMOTO-EF7198")
        )
        assertEquals(
            "CL-C450-1234",
            TBoxWifiDirectConnector.peerNameFromGroupSsid("\"DIRECT-XY-CL-C450-1234\" ")
        )
        assertEquals(
            "LivingRoom",
            TBoxWifiDirectConnector.peerNameFromGroupSsid("direct-tv-LivingRoom")
        )
    }

    @Test
    fun `falls back to a credential join when the ssid is not a DIRECT group name`() {
        assertNull(TBoxWifiDirectConnector.peerNameFromGroupSsid("MotoHubAP"))
        assertNull(TBoxWifiDirectConnector.peerNameFromGroupSsid("DIRECT-AB12"))
        assertNull(TBoxWifiDirectConnector.peerNameFromGroupSsid("DIRECT--"))
        assertNull(TBoxWifiDirectConnector.peerNameFromGroupSsid("DIRECT-go-"))
    }

    @Test
    fun `looks for the peer named inside a group ssid`() {
        assertEquals(
            "CFMOTO-EF7198",
            TBoxWifiDirectConnector.expectedPeerName("DIRECT-go-CFMOTO-EF7198")
        )
    }

    @Test
    fun `treats a non-group ssid as the peer name itself`() {
        // A rider's Voge: Android's own Wi-Fi Direct screen lists the dash as the device
        // "VOGE-5G-4474", and that same string is all the rider ever gets to enter. Deriving
        // nothing from it used to abandon discovery, which left no way in at all - the
        // credentials join cannot express a name without the DIRECT- prefix.
        assertEquals("VOGE-5G-4474", TBoxWifiDirectConnector.expectedPeerName("VOGE-5G-4474"))
        assertEquals("VOGE-5G-4474", TBoxWifiDirectConnector.expectedPeerName(" \"VOGE-5G-4474\" "))
        // A DIRECT- name that carries no device part is still all we have to search for.
        assertEquals("DIRECT-ee", TBoxWifiDirectConnector.expectedPeerName("DIRECT-ee"))
    }

    @Test
    fun `retries a refused join for as long as the budget can hold another round`() {
        // The shape the field log shows: a refused round comes back in about 2.5s, and the
        // whole-join budget is 35s. Four rounds is what that buys, and the rider gets one real
        // 30s attempt instead of a 2.5s one repeated by the watchdog.
        val budget = 35_000L
        assertTrue(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(2_500L, budget))
        assertTrue(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(11_000L, budget))
        assertTrue(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(19_500L, budget))
        assertTrue(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(26_000L, budget))
    }

    @Test
    fun `stops retrying while there is still time to report why`() {
        // The retry that would not fit is the one that matters: started anyway, it is cut off by
        // the 35s timeout and the rider is told the dash never formed a group - a statement about
        // the motorcycle, when the phone is the one refusing.
        val budget = 35_000L
        assertFalse(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(26_001L, budget))
        assertFalse(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(34_000L, budget))
        assertFalse(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(60_000L, budget))
    }

    @Test
    fun `a budget too small for one settled round refuses the very first retry`() {
        assertFalse(
            TBoxWifiDirectConnector.shouldSettleAndRetryJoin(
                elapsedMillis = 0L,
                budgetMillis = 5_000L,
                settleMillis = 6_000L,
                roundCostMillis = 3_000L
            )
        )
    }

    @Test
    fun `a foreground service is not a window on screen`() {
        // The regression this whole change exists for. TBoxNetworkConnector's specifier gate
        // accepts IMPORTANCE_FOREGROUND_SERVICE, so reusing that threshold here would have
        // called support case f014ce61's refusals "foreground" and changed nothing.
        assertFalse(
            TBoxWifiDirectConnector.hasNoVisibleWindow(RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        )
        assertTrue(
            TBoxWifiDirectConnector.hasNoVisibleWindow(
                RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            )
        )
        assertTrue(
            TBoxWifiDirectConnector.hasNoVisibleWindow(RunningAppProcessInfo.IMPORTANCE_VISIBLE)
        )
        assertTrue(
            TBoxWifiDirectConnector.hasNoVisibleWindow(RunningAppProcessInfo.IMPORTANCE_CACHED)
        )
    }

    @Test
    fun `a join that never had a window tells the rider to open the app`() {
        val advice = TBoxWifiDirectConnector.joinRefusalAdvice(
            ssid = "DIRECT-VOGE-057543",
            appName = "MOTO-HUB",
            peerSeen = false,
            peerListClearedOnStop = false,
            everHadAWindow = false
        )
        assertTrue(advice.contains("Open MOTO-HUB"))
        // The advice that cannot apply must be absent, not merely outranked: a rider who reads
        // "turn Wi-Fi off and on" does it, and then investigates the wrong thing.
        assertFalse(advice.contains("Turn Wi-Fi off"))
    }

    @Test
    fun `a stack that scanned and found the dash keeps its own advice`() {
        // peerSeen means the framework worked, whatever the window was doing, so the window
        // branch must not steal a case it cannot explain.
        val advice = TBoxWifiDirectConnector.joinRefusalAdvice(
            ssid = "DIRECT-VOGE-057543",
            appName = "MOTO-HUB",
            peerSeen = true,
            peerListClearedOnStop = false,
            everHadAWindow = false
        )
        assertTrue(advice.contains("connection page"))
        assertFalse(advice.contains("Open MOTO-HUB"))

        val cleared = TBoxWifiDirectConnector.joinRefusalAdvice(
            ssid = "DIRECT-VOGE-057543",
            appName = "MOTO-HUB",
            peerSeen = true,
            peerListClearedOnStop = true,
            everHadAWindow = false
        )
        assertTrue(cleared.contains("peer list"))
    }

    @Test
    fun `a join refused with the app on screen still blames the stack`() {
        val advice = TBoxWifiDirectConnector.joinRefusalAdvice(
            ssid = "DIRECT-VOGE-057543",
            appName = "MOTO-HUB",
            peerSeen = false,
            peerListClearedOnStop = false,
            everHadAWindow = true
        )
        assertTrue(advice.contains("Turn Wi-Fi off"))
    }

    @Test
    fun `the importance reading is named, not left as a number`() {
        assertEquals(
            "foreground service only",
            TBoxWifiDirectConnector.importanceName(
                RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            )
        )
        assertEquals(
            "on screen",
            TBoxWifiDirectConnector.importanceName(RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        )
        assertEquals("importance 7", TBoxWifiDirectConnector.importanceName(7))
    }
}
