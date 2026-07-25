package io.motohub.android.ipc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Sent by a companion app (PRO) to ask Core to establish the T-Box connection on its behalf —
 * Core owns the GPL-licensed transport (hudlib.aar) and the Wi-Fi/socket binding, so the actual
 * connect must run in Core's process. PRO passes the credentials it holds in its own store;
 * Core joins the network, runs EasyConn discovery, and installs the session.
 */
@Parcelize
data class MotorcycleConnectRequest(
    val id: String,
    val ssid: String,
    val password: String,
    val modelId: String?,
    val displayName: String?,
    val profileOverrideKey: String?,
    /** Matches io.motohub.android.session.TBoxConnectionMode.name (AUTO/…). */
    val connectionMode: String
) : Parcelable
