package io.motohub.android

import android.app.Application
import io.motohub.android.i18n.MotoHubStrings
import io.motohub.android.session.CrashRecovery
import io.motohub.android.session.ProjectionEventLog

class MotoHubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MotoHubStrings.initialize(this)
        ProjectionEventLog.initialize(this)
        CrashRecovery.install(this)
        CrashRecovery.restorePreviousCrash(this)
    }
}
