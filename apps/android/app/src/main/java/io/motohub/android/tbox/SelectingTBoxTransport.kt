// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * The one [TBoxTransport] the app holds. Routes every call to the wire implementation the
 * configured [TBoxModelProfile] belongs to — EasyConn ([RideDaemonTransport]), ThinkerRide
 * ([ThinkerRideTransport]) or Yunmo ([YunmoTransport]) — so HubViewModel, CoreTBoxConnector and
 * the session services keep a
 * single transport field and a single event stream regardless of which protocol the motorcycle
 * speaks. [configureProtocolProfile] runs before [discover] on every connect path, which is what
 * makes the profile a safe routing key.
 */
class SelectingTBoxTransport(context: Context) : TBoxTransport {

    private val easyConn = RideDaemonTransport(context)
    private val thinkerRide = ThinkerRideTransport(context)
    private val yunmo = YunmoTransport(context)

    @Volatile
    private var active: TBoxTransport = easyConn

    /**
     * The profile a dash gets when it is discovered to speak Yunmo without the rider having pinned
     * anything. [TBoxModelProfile.MORINI_XCAPE_1200] is the only Yunmo profile there is, and its
     * settings come from the only Yunmo dash anyone has captured, so it is the best available
     * guess rather than a claim about which motorcycle this is.
     *
     * The session services pick this up through [activeProtocolProfile], so the encoder settings
     * follow the same switch the transport did. They used to re-resolve the profile from the saved
     * motorcycle instead, which left an auto-detected Yunmo session encoding at generic settings.
     */
    private val yunmoSessionProfile = TBoxModelProfile.MORINI_XCAPE_1200

    /**
     * What this session is actually routed to, for the session services to configure the encoder
     * from — and for the AIDL bridge to hand the companion app, which cannot see any of this.
     *
     * Without it the handover was only half a switch: the session spoke Yunmo but still encoded
     * with the settings of whatever the saved motorcycle resolved to (GENERIC, for a dash nothing
     * can identify from its QR). On the X-Cape that meant 30 fps all-intra instead of 10 - and
     * since every all-intra frame is a keyframe, and a keyframe is split into three wire frames,
     * three times the frames at three times the rate against a three-frame send window.
     *
     * It used to be set ONLY by the Yunmo fallback in [discover], which is the hole rider
     * 315e0af3 fell into twice more after that fallback was written. The fallback fires once:
     * from the second connect onwards [TBoxProtocolMemory] routes the session straight to Yunmo
     * at configure time, [discover] returns without ever reaching the switch, and this stayed
     * null — so both this app's own Android Auto and the companion app fell back to GENERIC on a
     * dash the log line right above them had just named. 132 rejected access units in five
     * seconds, on 1.1.96, with the rider watching a frozen TFT. See [routedProfileToPublish] for
     * why the answer is the configured profile for some families and null for others.
     */
    @Volatile
    override var activeProtocolProfile: TBoxModelProfile? = null
        private set

    /**
     * The profile the Yunmo fallback in [discover] switched to, and nothing else — the signal
     * [TBoxProtocolMemory] is taught from.
     *
     * Kept apart from [activeProtocolProfile] on purpose. That one now answers "what is this
     * session running", which a pinned override answers too; this one answers "what did the dash
     * itself tell us, that nobody had asked it", which is the only thing worth remembering across
     * rides. Teaching the memory from a pin would let one curious tap on a profile silently
     * redirect every later AUTO connect for that dash.
     */
    @Volatile
    var discoverySwitchedProfile: TBoxModelProfile? = null
        private set

    /** Remembered so the Yunmo fallback below can hand the ladder the same motorcycle. */
    @Volatile
    private var configuredMotorcycle: io.motohub.android.session.MotorcycleProfile? = null

    override val events: Flow<TBoxEvent> = merge(easyConn.events, thinkerRide.events, yunmo.events)

    override fun configureProtocolProfile(
        profile: TBoxModelProfile,
        motorcycle: io.motohub.android.session.MotorcycleProfile?
    ) {
        active = when (profile.transportFamily) {
            TBoxTransportFamily.EASYCONN -> easyConn
            TBoxTransportFamily.THINKERRIDE -> thinkerRide
            TBoxTransportFamily.YUNMO -> yunmo
        }
        activeProtocolProfile = routedProfileToPublish(profile)
        discoverySwitchedProfile = null
        configuredMotorcycle = motorcycle
        // The one line that says which wire this session will speak. A field log without it
        // cannot distinguish "the override never reached routing" from "the transport failed"
        // (X-Cape 1200 log, 2026-08-07: session ran EasyConn NSD with no way to tell why).
        ProjectionEventLog.record(
            "PROFILE",
            "Protocol profile: ${profile.key} (${profile.displayName}); " +
                "transport family=${profile.transportFamily}."
        )
        active.configureProtocolProfile(profile, motorcycle)
    }

    override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> {
        val first = active.discover(link, expectedModelId)
        if (first.isSuccess || active !== easyConn) return first

        // EasyConn found nothing. Before giving the rider "T-Box offline", ask whether this dash
        // speaks Yunmo instead — a question only the dash can answer, and one that used to require
        // the rider to find and pin a profile override by hand. Nothing else reaches this point:
        // a dash that answers EasyConn returned above, and a dash that answers neither costs one
        // extra short connect on a path that has already failed.
        val yunmoHost = yunmo.answersOnThisLink(link) ?: return first
        ProjectionEventLog.record(
            "PROFILE",
            "EasyConn discovery found nothing but the dash answered Yunmo on " +
                "$yunmoHost:${YunmoProtocol.DEFAULT_PORT}; switching this session to the Yunmo transport."
        )
        active = yunmo
        yunmo.configureProtocolProfile(yunmoSessionProfile, configuredMotorcycle)
        activeProtocolProfile = yunmoSessionProfile
        discoverySwitchedProfile = yunmoSessionProfile
        return yunmo.discover(link, expectedModelId)
    }

    override suspend fun start(host: TBoxHost): Result<Unit> = active.start(host)

    /**
     * Routes a JPEG still to whichever transport is carrying this session.
     *
     * Yunmo is named rather than reached through [TBoxTransport.offerStillFrame] because its own
     * still path takes the frame id for the acknowledgement window it keeps, which the interface
     * method does not express. Every other family answers through the interface, and the ones that
     * negotiated an encoded stream answer false there by default - which is what the caller wants,
     * since only a session configured for stills ever produces one.
     */
    fun offerJpegFrame(jpeg: ByteArray, frameId: Int): Boolean =
        if (active === yunmo) yunmo.offerJpegFrame(jpeg, frameId)
        else active.offerStillFrame(jpeg, frameId)

    /**
     * The [TBoxTransport] face of [offerJpegFrame], so a caller holding only the interface - the
     * companion app's frames arriving over the AIDL video pipe - reaches the same still path the
     * in-process session services reach directly.
     */
    override fun offerStillFrame(jpeg: ByteArray, frameId: Int): Boolean =
        offerJpegFrame(jpeg, frameId)

    override fun offerAccessUnit(avcc: ByteArray): Boolean = active.offerAccessUnit(avcc)

    override suspend fun stop() {
        // Stopping all three is deliberate: a routing change between sessions must never leave the
        // previous family's sockets or BLE link alive, and stop() is a no-op on an idle transport.
        easyConn.stop()
        thinkerRide.stop()
        yunmo.stop()
    }

    companion object {
        /**
         * What [configureProtocolProfile] may publish through [activeProtocolProfile]: the profile
         * itself off EasyConn, null on it.
         *
         * The asymmetry is the whole point, and it is about who holds the better answer rather
         * than about the wire. A profile outside [TBoxTransportFamily.EASYCONN] is here either
         * because the rider pinned it - in which case saying so costs nothing, the companion app
         * resolves the same pin from the same saved motorcycle - or because [TBoxProtocolMemory]
         * routed it from a family learned on an earlier ride, which nothing on the other side of
         * the bridge can arrive at: not from the QR, not from a model id, not from capabilities.
         * That second case is the one that has to be answered, and it is indistinguishable from
         * the first from in here.
         *
         * On EasyConn the better answer belongs to the caller, so this stays out of the way. The
         * profile handed in there is [TBoxModelProfile.resolve]'s, made without the CLIENT_INFO
         * capability scoring that identifies a dash whose model id matches nothing - and the
         * bridge has no capability store to redo it with. Publishing it would trade the
         * companion app's better resolution for a weaker one on every EasyConn dash there is, to
         * fix nothing: an EasyConn session is exactly what the other side already assumes.
         */
        internal fun routedProfileToPublish(profile: TBoxModelProfile): TBoxModelProfile? =
            profile.takeIf { it.transportFamily != TBoxTransportFamily.EASYCONN }
    }
}
