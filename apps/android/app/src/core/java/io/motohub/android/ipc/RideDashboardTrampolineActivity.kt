// CORE-only: exists purely to satisfy Android 14+'s background-start restriction on
// FOREGROUND_SERVICE_TYPE_LOCATION. RideDashboardSessionService needs that type (GPS telemetry,
// trip recording, track overlay) regardless of map source, and the OS only allows promoting a
// service to a location-typed foreground service when the calling process is itself currently
// visible/TOP — which Core's own MainActivity normally provides, but doesn't when a companion
// app (Pro) drives the dashboard remotely through IpcBridgeService (a background bound service,
// no visible Core UI in the loop).
//
// This is the standard, widely-used workaround: an invisible, momentary Activity briefly puts
// the calling process in the TOP state, which IS an accepted exemption, then starts the real
// service from that Activity's context and finishes immediately. Theme.Translucent.NoDisplay
// draws no window content, so in practice this is imperceptible — no visible frame, no icon in
// recents (noHistory + excludeFromRecents).
package io.motohub.android.ipc

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import io.motohub.android.feature.ridedashboard.RideDashboardMapSource
import io.motohub.android.feature.ridedashboard.RideDashboardSessionService

class RideDashboardTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RideDashboardSessionService.start(this, RideDashboardMapSource.ANDROID_AUTO)
        // Deferred, not immediate: gives the OS a beat to actually process the pending
        // startForegroundService() call — and RideDashboardSessionService's own
        // startForeground() promotion inside it — while Core is still in the TOP state this
        // Activity grants, rather than risking finishing before that happens.
        Handler(mainLooper).post { finish() }
    }
}
