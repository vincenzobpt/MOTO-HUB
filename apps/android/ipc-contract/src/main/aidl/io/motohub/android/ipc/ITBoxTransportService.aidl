package io.motohub.android.ipc;

import android.os.ParcelFileDescriptor;

import io.motohub.android.ipc.MotorcycleSummary;
import io.motohub.android.ipc.EncoderProfileParcel;
import io.motohub.android.ipc.MotorcycleConnectRequest;
import io.motohub.android.ipc.ITBoxSessionListener;

/**
 * Bound-service contract exposing Core's already-established T-Box transport
 * (EasyConn session, H.264 delivery) to another app's process. Core owns the
 * GPL-3.0-derived connection; callers never touch it directly.
 */
interface ITBoxTransportService {
    boolean isSessionReady();

    /** Null when no T-Box session is currently active. Contains no credentials. */
    MotorcycleSummary getActiveMotorcycle();

    /** Null when no session is active or the video area has not yet been negotiated. */
    EncoderProfileParcel getNegotiatedEncoderProfile();

    /**
     * Starts the T-Box video session inside Core: EasyConn video start + live TFT area
     * negotiation (both need the GPL transport Core owns). Blocking; returns the negotiated
     * capture area (width/height are the raw TFT area — the caller applies its own quality and
     * bitrate), or null if negotiation failed. offerAccessUnit() only delivers frames after this
     * has returned successfully.
     */
    EncoderProfileParcel startVideoSession();

    /** Same call shape as the in-process VideoAccessUnitSink.offerAccessUnit(). */
    boolean offerAccessUnit(in byte[] accessUnit);

    /**
     * Whether the dash of the ACTIVE session wants JPEG stills rather than an H.264 stream.
     *
     * True only for the Moto Morini X-Cape 1200 family, whose OEM app never streams H.264 - the
     * dash acknowledges every access unit and paints none. A caller that gets true must render
     * into stills and write them with VideoPipeFraming.writeStill(); offerAccessUnit() and plain
     * access units on the pipe are refused for such a session rather than silently converted,
     * because there is no pixel source on Core's side of this boundary to convert them from.
     *
     * False when no session is active, which is also what a Core older than
     * AndroidAutoIpcState.CONTRACT_VERSION_VIDEO_STILLS effectively answers by not having this
     * call at all. Check getContractVersion() first.
     */
    boolean videoWantsStills();

    /**
     * Establishes the T-Box connection inside Core's process (Wi-Fi join + EasyConn discovery,
     * which need the GPL transport and the socket binding Core owns) and installs the session.
     * Blocking; returns true once the session is READY. Session ready/lost is also reported to
     * registered listeners.
     */
    boolean connect(in MotorcycleConnectRequest request);

    /**
     * Aborts an in-flight connect() as quickly as possible and cleans up any partial state it
     * left behind. Safe to call even if no connect() is in flight, or if it already finished.
     * A blocked connect() call returns false shortly after this is received.
     */
    void cancelConnect();

    /** Tears down the active T-Box session established via connect(). */
    void disconnect();

    void registerSessionListener(ITBoxSessionListener listener);
    void unregisterSessionListener(ITBoxSessionListener listener);

    /** Opens the one-way data plane for high-rate encoded video frames. */
    ParcelFileDescriptor openVideoStream();

    /** Closes the pipe returned by openVideoStream(). */
    void closeVideoStream();

    /**
     * Read-only snapshot of Core's diagnostic log, already formatted for sharing. A file
     * descriptor rather than a String: the export can exceed the 1 MB Binder transaction
     * buffer. Null when the log is empty or the snapshot cannot be produced. Declared last
     * so the preceding methods keep their transaction ids - a caller talking to an older
     * Core gets a dead transaction (surfaced as null by the client), not a misrouted one.
     */
    ParcelFileDescriptor openDiagnosticLogSnapshot();

    /**
     * Clears Core's diagnostic log. The companion app's own Clear calls this so "clear the
     * log" means the same thing it does everywhere else in the pair: both halves, one gesture.
     * Same tail-position rule as above for compatibility with older Cores.
     */
    void clearDiagnosticLog();

    /**
     * Revision of this contract that Core implements, so a caller can tell a Core that simply
     * does not know a call from one that answered it. A Core older than this method returns 0:
     * the dead transaction leaves the reply parcel empty and an empty parcel reads as 0.
     * Same tail-position rule as the two calls above.
     */
    int getContractVersion();

    /**
     * connect() for a Wi-Fi Direct group the CALLER has already formed and still owns.
     *
     * Core cannot resolve the phone's own 192.168.49.x address for a group another process
     * formed - field logs (samsung SM-S918B, Android 16, VOGE dash, 2026-08-06) show 35 of 44
     * handovers failing with "no usable 192.168.49.x address appeared", 10s apart, while the
     * companion app that formed the group read its address instantly. So the caller passes the
     * addresses it already resolved and Core skips its own interface lookup. Core verifies the
     * group is really formed and never releases it: the process that formed it still owns it.
     *
     * @param localIpv4 the phone's address inside the group, as the caller resolved it
     * @param groupOwnerIpv4 the dash's address (the P2P Group Owner)
     */
    boolean connectOverFormedGroup(
        in MotorcycleConnectRequest request,
        String localIpv4,
        String groupOwnerIpv4
    );

    /**
     * Why the last connect() or connectOverFormedGroup() returned false, in the caller's own
     * words to its rider. Null when the last connect succeeded, when none has run, or when Core
     * predates this call.
     *
     * connect() answers a bare boolean, and for a long time that was everything the companion app
     * knew. It could only say "Core failed to connect to the T-Box" and then offer the help it had
     * - which is help for a busy EasyConn session, so every network-layer failure came out looking
     * like the official CFMOTO app was holding the link. A rider chased that as far as
     * uninstalling an app that had nothing to do with it (2026-08-15), while the real reason - a
     * VPN holding the route to the dash - sat in Core's log where nothing showed it to him.
     *
     * Read it only after a false answer, and pair it with getLastConnectFailureStage().
     */
    String getLastConnectFailure();

    /**
     * Which half of the connect produced getLastConnectFailure(): one of
     * IpcBridgeContract.CONNECT_STAGE_*. Lets a caller offer network help for a network failure
     * and session help for a session failure, without matching on message text that is assembled
     * from exceptions and translated. Returns CONNECT_STAGE_UNKNOWN (0) on a Core that predates
     * this call, which is also what an empty reply parcel reads as.
     */
    int getLastConnectFailureStage();

    /**
     * The key of the TBoxModelProfile Core's transport settled on for the ACTIVE session, when
     * DISCOVERY changed it - null otherwise, including when no session is active.
     *
     * A caller resolves the profile from its own saved motorcycle, and that is usually right.
     * It cannot be right for a dash that answered Yunmo on :8200 after EasyConn discovery found
     * nothing: by modelId that dash is the generic profile, while Core's transport has already
     * switched to the real one. Everything the encoder needs hangs off the difference - frame
     * rate, bitrate, keyframe policy, touch policy, screen margins - so a caller encoding for the
     * generic profile sends such a dash three times the frames its send window holds.
     *
     * Null is therefore an answer, not a failure: it means "nothing to correct, keep what you
     * resolved". Core deliberately does not offer its own fallback resolution here - it has no
     * capability store, so it cannot see the CLIENT_INFO scoring that identifies a dash with no
     * matching model id, and a weaker answer would overwrite a better one.
     *
     * Answered by name rather than as a parcel of settings: both apps compile the same profile
     * table, so a key reaches the same enum entry, and a profile gaining a field needs no
     * contract change. A caller that does not recognise the key (an older companion against a
     * newer Core) resolves the profile itself, exactly as before.
     *
     * Also null on a Core older than IpcBridgeContract.CONTRACT_VERSION_ACTIVE_PROFILE, which is
     * what its dead transaction reads as. Same tail-position rule as the calls above.
     */
    String getActiveProfileKey();

    /**
     * Why the last startVideoSession() returned null, in Core's own words to a rider. Null when
     * the last call succeeded, when none has run, or when Core predates this call.
     *
     * startVideoSession() answers a bare parcel-or-null, and for a long time that was everything
     * the companion app knew: it printed its own summary, which is help for a busy EasyConn
     * session, for every cause there is. On a ThinkerRide dash that summary is not merely vague
     * but wrong - the KOVE 450 Rally firmware starts projection FROM THE DASH (long-press UP) and
     * nothing on the phone can trigger it, so Core's transport says exactly that and the rider
     * was shown "put the bike on its phone-connection screen and make sure no other app is
     * connected" instead. Two riders (32e132d0, 1013eadf) retried for days against a dash that
     * was waiting for them, while the sentence naming the gesture sat in Core's log.
     *
     * This is getLastConnectFailure()'s counterpart one call further on. Read it only after a
     * null answer; nothing here clears it except the next startVideoSession().
     *
     * Same tail-position rule as the calls above.
     */
    String getLastVideoSessionFailure();

    /**
     * Where the wire ladder stands for one motorcycle, as the JSON Core itself persists, or null
     * when Core has never walked it for that bike (or predates this call).
     *
     * Core owns the ladder because Core is where the wire is chosen, and this is the only way the
     * companion app can report the truth about it. Its diagnostics report used to read its OWN
     * copy of the same preferences - untouched in that process, and therefore rung 0 / TRYING /
     * no fingerprint for every rider alive. Field log 90438e1e (2026-08-25): the report said the
     * search had not started while Core's log, in the same file, had already recorded "rung 0 …
     * STREAMED. Staying on this rung (AWAITING_RIDER)" the day before.
     *
     * The raw JSON rather than a parcel of fields: both apps compile the same ladder, so the same
     * parser reads it on the other side, and a rung gaining a field needs no contract change.
     *
     * Same tail-position rule as the calls above.
     */
    String getWireLadderProgress(String motorcycleId);

    /**
     * One motorcycle's CLIENT_INFO capabilities, as the JSON TBoxCapabilityStore persists, or
     * null when Core has never seen CLIENT_INFO for that bike (or predates this call).
     *
     * CLIENT_INFO arrives on the EasyConn command socket, which lives in Core, so Core's store is
     * the only one a Core-owned session ever fills. The companion app reads the identically named
     * preferences file in ITS process, which nothing there writes: every ADVANCED installation in
     * the field reports `capabilities: null` (68 motorcycle rows out of 68 in the collector,
     * 2026-08-25) and therefore resolves the generic profile no matter what the dash said about
     * itself. Rider 36ee9d2c's Benelli ran the Ride Dashboard as generic while Core's Android
     * Auto, one process away, had scored the same dash and was applying another profile's screen
     * margins to it. Same failure as getWireLadderProgress(), one store over.
     *
     * By id rather than for the active session: the diagnostics report is written when nothing is
     * connected, and it is one of the readers this exists for.
     *
     * Same tail-position rule as the calls above.
     */
    String getCapabilitiesJson(String motorcycleId);

    /**
     * Probes the well-known EasyConn ports on the dash of the ACTIVE session, over the link Core
     * already holds, and answers the result as JSON: {"peerIp":"...","ports":[{"port":10930,
     * "status":"OPEN|REFUSED|NO_RESPONSE","detail":"..."}]}. Null when no session is installed,
     * when the peer address cannot be derived, or on a Core that predates this call.
     *
     * The scan exists for a dash that answers nothing on the documented port, and the moment a
     * rider wants it is the moment they are connected to that dash. In the companion app that is
     * exactly when it could not run: the scanner opens a Wi-Fi connection of its own, and with
     * Core owning the link the companion's connector has no network, no peer, and no way to tell
     * "the same motorcycle" from "another one" - so every scan during a session was refused
     * (field log 7efdfa33, 2026-08-25). Core scans over the session's own link and asks Android
     * for nothing, so nothing about the ride is disturbed.
     *
     * Blocking, like startVideoSession(): the probes run in parallel with a short connect timeout
     * each. Same tail-position rule as the calls above.
     */
    String scanTBoxPorts();

    /**
     * The screen margins Core holds for one motorcycle, by SSID, as "top,bottom,left,right" -
     * or null when the rider never taught them there.
     *
     * The teaching already travels the other way, in AndroidAutoSettingsParcel, and only when
     * this app has one: sending four zeros for a bike nobody calibrated here would erase a
     * calibration made over there. That leaves the case where only CORE was taught, which is the
     * common one - Core ships the same ruler and owns Android Auto - and nothing carried it back.
     * So one dash got two framings from one pair: Android Auto composited into a 680x408 viewport
     * while the Ride Dashboard used the full 800x480, minutes apart on the same EasyConn panel
     * (riders 7efdfa33 and 87bc5a7c, 2026-08-25).
     *
     * By SSID because that is the string both halves agree on; a profile id belongs to one garage.
     * Same tail-position rule as the calls above.
     */
    String getScreenMargins(String ssid);

    /**
     * What a session is doing to the dashboard, as
     * "ok/failing|rejected|accepted|profileKey|ssid" - or null while nothing has been concluded.
     *
     * Both verdicts travel, not only the bad one. "ok" is what lets the companion app ask a rider
     * to KEEP a profile they just picked, and that question needs evidence the picture is landing
     * rather than merely the absence of a complaint - which a session that never sent a frame
     * would also produce.
     *
     * The one thing a companion app cannot work out for itself, because the video pipe is
     * one-way: its own offerAccessUnit() reports whether the WRITE into the pipe succeeded, and
     * a write succeeds perfectly well while the dashboard at the other end refuses every frame.
     * Core sits where both facts meet, so Core is the only place that can tell "the rider is
     * looking at a frozen TFT" from "everything is fine".
     *
     * This exists because of what that costs a rider who cannot see it. 315e0af3 rode a Moto
     * Morini X-Cape 1200 for two days with the app reporting a healthy session while the
     * dashboard took 12 frames in every 100, found the profile picker by accident, pinned the
     * right profile and fixed it himself in ninety seconds. Nothing in the app had ever
     * suggested the profile was the problem - or that a picker existed.
     *
     * Deliberately latched and answered once per session: it earns the right to ask the rider
     * one question, and a question asked twice is a question riders learn to dismiss. Cleared
     * whenever a session is rebuilt, because a rebuild around a different profile is a new
     * question and deserves a new answer.
     *
     * A flat string rather than a parcel for the same reason getActiveProfileKey() is: no new
     * parcelable to keep in step across two independently-installed apps, and a field added
     * later needs no contract bump. The network name is last because an SSID may legally contain
     * the separator and the other fields cannot, so splitting with a fixed limit recovers it
     * whole. Same tail-position rule as the calls above.
     */
    String getDashboardDeliveryReport();

    /**
     * Whether CORE ITSELF holds BLUETOOTH_CONNECT right now.
     *
     * A runtime permission is granted to one package, and the two halves are two packages. The
     * handlebar of an Android Auto session is decoded by CORE's MediaButtonBridge, which refuses
     * to take the media volume and audio focus without this grant - so the permission the rider
     * has to give is CORE's, and every screen that asks for it lives in the companion app. The
     * companion could not even SEE the gap: it checked its own grant, found it, and reported the
     * handlebar as ready.
     *
     * Rider 315e0af3 (2026-08-24 to 08-26, Moto Morini X-Cape 1200): "AAP: [BTN] capture skipped:
     * Bluetooth is off or unavailable to this app" in every Android Auto session of every report,
     * while the companion's own Ride Dashboard logged "capture enabled; audio focus=granted" on
     * the same phone ten minutes later. He paired the motorcycle, reassigned every button and ran
     * the teaching wizard to the end; no press could ever have arrived.
     *
     * Only the permission, not the adapter: the adapter is one radio and the caller can read its
     * state for itself. This answers the half that is per-app and therefore invisible from over
     * there.
     *
     * A CORE that predates this call leaves the reply parcel empty, which reads as false - so the
     * caller must check getContractVersion() against
     * IpcBridgeContract.CONTRACT_VERSION_CORE_BLUETOOTH first and treat anything older as
     * "unknown", never as "missing". Same tail-position rule as the calls above.
     */
    boolean holdsHandlebarBluetoothPermission();

    /**
     * How THIS package's handlebar is configured right now, in HandlebarState's wire format
     * (io.motohub.android.ipc.HandlebarState). Null when it cannot be read at all.
     *
     * The permission above answers whether a press can arrive; this answers what happens to one
     * that does, and it is invisible from the companion app for the same reason: an Android Auto
     * session's handlebar is decoded here, and the screens that configure it are over there. A
     * diagnostics report that printed the companion's own copy would state the input protocol,
     * the mapping and the teaching status of the half that decodes nothing - which is how support
     * 0df154af could show a rider switching protocols and running the wizard while nothing in the
     * process that reads his presses changed at all.
     *
     * Deliberately not the radio: an adapter is one radio, and the caller reads that for itself.
     *
     * A CORE that predates this call leaves the reply parcel empty, which reads as null - the
     * caller must check getContractVersion() against
     * IpcBridgeContract.CONTRACT_VERSION_HANDLEBAR_STATE and treat anything older as "unknown".
     * Same tail-position rule as the calls above.
     */
    String getHandlebarState();

    /**
     * Forget this package's own garage entry for [ssid], because the companion app's rider just
     * deleted or re-paired that motorcycle over there.
     *
     * MOTO-HUB keeps two garages, and until this call only one of them could be emptied by the
     * rider. Core's copy answers connect() through
     * io.motohub.android.ipc.completedFrom, so a stale row here kept deciding for a profile the
     * rider had already thrown away: support f27f3825 (Benelli TRK 702X, bj5G2266, 2026-09-07)
     * deleted his motorcycle at 19:37:56, re-paired it by hand at 19:38:39, and was refused 12
     * seconds later by the connectionMode of the entry he had just deleted - with no screen in
     * either app able to reach it.
     *
     * Keyed on the network name for the reason getWireLadderProgress() already is: a profile id
     * is minted per garage, so the two entries for one physical dashboard never share one.
     *
     * Silent when there is nothing to forget - a rider who never paired in Core is not an error.
     * A CORE that predates this call ignores it, so the caller must check getContractVersion()
     * against IpcBridgeContract.CONTRACT_VERSION_FORGET_MOTORCYCLE first and, below that, say
     * that the other garage may still hold an entry. Same tail-position rule as the calls above.
     */
    void forgetMotorcycle(String ssid);

    /**
     * Write [connectionMode] onto this package's own garage entry for [ssid], because the
     * companion app's rider just chose it over there.
     *
     * The other half of forgetMotorcycle(), and the residual it deliberately left open. Core's
     * row is what completes an incoming connect (io.motohub.android.ipc.completedFrom), and a
     * bare AUTO arriving from a companion is read there as "the caller said nothing" rather than
     * as "the rider chose Auto" - so a rider who moved the mode back to Auto in the companion app
     * had Core's own older value put straight back on the next connect. Support f27f3825
     * (Benelli TRK 702X, bj5G2266) is that shape: PHONE_HOTSPOT on this side, "This motorcycle
     * expects your phone to host the network" on every attempt, and no screen in either app able
     * to reach the row that said it.
     *
     * ONE FIELD, and every row matching the name: the two garages mint their own ids, and this
     * entry carries a modelId the companion never mints (support adb68a95, a KOVE 450 Rally that
     * only Core knew was a ThinkerRide). Replacing the row would throw that away; writing the one
     * field the rider actually changed does not.
     *
     * [connectionMode] is a TBoxConnectionMode name. An unknown value is ignored rather than
     * guessed at - a newer companion naming a mode this build has never heard of must not be able
     * to blank the one that works.
     *
     * Silent when there is nothing here under that name. A Core that predates this call ignores
     * it, so the caller must check getContractVersion() against
     * IpcBridgeContract.CONTRACT_VERSION_SET_CONNECTION_MODE first and, below that, say that the
     * other garage may still hold a different mode. Same tail-position rule as the calls above.
     */
    void setMotorcycleConnectionMode(String ssid, String connectionMode);
}
