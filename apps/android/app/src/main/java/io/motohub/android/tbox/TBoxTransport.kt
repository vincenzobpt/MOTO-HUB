// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class TBoxHost(
    val ipAddress: String,
    val port: Int,
    val packageName: String
)

sealed interface TBoxEvent {
    data class Capabilities(val value: TBoxCapabilities) : TBoxEvent
    data class VideoArea(
        val width: Int,
        val height: Int,
        /** True when the area came from a compatibility fallback rather than the T-Box. */
        val isFallback: Boolean = false
    ) : TBoxEvent
    data class Touch(val action: Int, val pointerId: Int, val x: Int, val y: Int) : TBoxEvent
    data object VideoStreamStart : TBoxEvent
    data class Warning(val message: String) : TBoxEvent
    data class FatalError(val message: String) : TBoxEvent
    data object Stopped : TBoxEvent
}

/**
 * Which wire protocol a [TBoxModelProfile] speaks, and therefore which [TBoxTransport]
 * implementation a session must be routed through (see SelectingTBoxTransport).
 */
enum class TBoxTransportFamily {
    /** Carbit/EasyConn dashes: the phone is the TCP client of the dash's services. */
    EASYCONN,

    /** ThinkerRide dashes (KOVE family): BLE handshake, then the dash connects to the phone. */
    THINKERRIDE,

    /** Yunmo SoftAP dashes (Moto Morini X-Cape 1200): one TCP socket to 192.168.4.1:8200. */
    YUNMO,

    /**
     * Bosch mySPIN dashes (Ducati Multistrada V4 "Ducati Connect", Suzuki, Kawasaki, BMW): Bluetooth
     * hands over the Wi-Fi, the dash announces itself over UDP and pulls JPEG frames over TCP.
     * Reached only through a profile the rider picked by hand.
     */
    MYSPIN,

    /**
     * EyLink (Erya) dashes (Kove 350 RR): the phone joins the dash's Wi-Fi Direct group, then
     * dials its control (11113) and video (11111) ports and pushes H.264. See [EyLinkProtocol].
     */
    EYLINK
}

sealed interface TBoxTransportStatus {
    data object Unavailable : TBoxTransportStatus
    data object Ready : TBoxTransportStatus
    data class Failure(val reason: String) : TBoxTransportStatus
}

interface TBoxTransport {
    /** Selects the profile whose wire-level capabilities will be advertised for the next session. */
    /**
     * [motorcycle] is optional and only [TBoxWireLadder] uses it: its memory of which wire format
     * a dashboard accepted is per motorcycle. A caller with none gets the profile's own settings.
     */
    fun configureProtocolProfile(
        profile: TBoxModelProfile,
        motorcycle: io.motohub.android.session.MotorcycleProfile? = null
    ) = Unit

    /**
     * The profile this transport is actually running, when that is not simply the one the caller
     * configured. Discovery can change it: a dash that answers Yunmo after EasyConn found nothing
     * is routed to a different family *and* a different profile than the saved motorcycle resolves
     * to, and the session's encoder settings have to follow the same switch. Null means "nothing
     * to correct, use what you resolved".
     */
    val activeProtocolProfile: TBoxModelProfile? get() = null
    suspend fun discover(link: TBoxLink, expectedModelId: String? = null): Result<TBoxHost>
    suspend fun start(host: TBoxHost): Result<Unit>
    fun offerAccessUnit(avcc: ByteArray): Boolean

    /**
     * Offers one JPEG still, for the dash families whose OEM app never streams H.264.
     *
     * Separate from [offerAccessUnit] because a still carries its own [frameId]: these dashes
     * acknowledge by id, so an id invented downstream would throw away the link's only liveness
     * signal. Returns false by default - a transport without a still path must refuse rather
     * than absorb, or a profile that asked for stills gets silently served something else.
     */
    fun offerStillFrame(jpeg: ByteArray, frameId: Int): Boolean = false
    suspend fun stop()
    val events: Flow<TBoxEvent>
}

/** Keeps UI and session code honest until the GPL transport AAR is packaged. */
class UnavailableTBoxTransport : TBoxTransport {
    override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> = Result.failure(
        IllegalStateException("hudlib.aar is not integrated")
    )

    override suspend fun start(host: TBoxHost): Result<Unit> = Result.failure(
        IllegalStateException("hudlib.aar is not integrated")
    )

    override fun offerAccessUnit(avcc: ByteArray): Boolean = false

    override suspend fun stop() = Unit

    override val events: Flow<TBoxEvent> = emptyFlow()
}
