package io.motohub.android.feature.ridedashboard

import android.content.Context
import io.motohub.android.androidauto.AndroidAutoCapabilityProfile
import io.motohub.android.androidauto.AndroidAutoDisplayMode

/** CORE flavor factory: builds the real AGPL-backed embedded AA source. */
fun createEmbeddedAndroidAutoSource(
    context: Context,
    capabilityProfile: AndroidAutoCapabilityProfile,
    displayMode: AndroidAutoDisplayMode
): EmbeddedAndroidAutoVideoSource =
    EmbeddedAndroidAutoSource(context, capabilityProfile, displayMode)
