package io.motohub.android.ipc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Deliberately excludes the Wi-Fi password and any other credential. */
@Parcelize
data class MotorcycleSummary(
    val id: String,
    val ssid: String,
    val modelId: String?,
    val displayName: String?
) : Parcelable
