package io.motohub.android.ipc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class EncoderProfileParcel(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitRate: Int
) : Parcelable
