package io.motohub.android.feature.ridedashboard

/**
 * Triggers Google Android Auto's self-mode connect once Ride Dashboard's LOCAL embedded AA
 * receiver is ready (map source ANDROID_AUTO, running in the same process as this Activity).
 * Only meaningful in CORE, which owns the AGPL receiver — the factory resolves to a real
 * implementation there and a no-op in PRO (which always delegates the whole dashboard session to
 * Core instead — see ProRideDashboardEmbeddedAaBridge — so this is never reached in practice).
 */
interface RideDashboardLocalAndroidAutoTrigger {
    fun trigger()
}
