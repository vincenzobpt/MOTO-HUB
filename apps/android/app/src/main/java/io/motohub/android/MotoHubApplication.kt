package io.motohub.android

import android.app.Application
import io.motohub.android.session.ProjectionEventLog

class MotoHubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProjectionEventLog.initialize(this)
    }
}
