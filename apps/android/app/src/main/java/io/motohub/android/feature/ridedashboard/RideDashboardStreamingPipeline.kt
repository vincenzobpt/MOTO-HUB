package io.motohub.android.feature.ridedashboard

import android.content.Context
import io.motohub.android.encoding.AvcEncoder
import io.motohub.android.encoding.EncoderProfile
import io.motohub.android.encoding.VideoAccessUnitSink
import io.motohub.android.feature.ridedashboard.widget.DashboardWidget
import io.motohub.android.session.ProjectionEventLog

class RideDashboardStreamingPipeline(
    context: Context,
    private val encoderProfile: EncoderProfile,
    private val tBoxLabel: String,
    private val motorcyclePhotoPath: String?,
    private val layoutController: RideDashboardLayoutController,
    private val mapSource: RideDashboardMapSource,
    private val embeddedAndroidAuto: EmbeddedAndroidAutoSource?,
    private val cellularOnlyMaps: Boolean,
    private val leftWidget: DashboardWidget,
    private val rightWidget: DashboardWidget,
    private val sink: VideoAccessUnitSink,
    private val onFrameAccepted: (Long) -> Unit = {},
    private val onSinkRejected: () -> Unit = {},
    private val onFailure: (Throwable) -> Unit
) {
    private val applicationContext = context.applicationContext

    var telemetryProvider: RideTelemetryProvider? = null
        private set
    var encoder: AvcEncoder? = null
        private set
    var renderer: RideDashboardRenderer? = null
        private set

    fun start() {
        val activeTelemetry = RideTelemetryProvider(applicationContext)
        activeTelemetry.start().getOrThrow()
        telemetryProvider = activeTelemetry

        val activeEncoder = AvcEncoder(
            profile = encoderProfile,
            onAccessUnit = { accessUnit ->
                try {
                    if (!sink.offerAccessUnit(accessUnit)) {
                        onSinkRejected()
                        false
                    } else {
                        onFrameAccepted(1L)
                        true
                    }
                } catch (failure: Throwable) {
                    onFailure(failure)
                    false
                }
            },
            onFailure = onFailure
        )
        activeEncoder.start()
        val surface = activeEncoder.inputSurface ?: error("AVC encoder has no input surface")
        val activeRenderer = RideDashboardRenderer(
            context = applicationContext,
            surface = surface,
            fps = encoderProfile.frameRate,
            bitRate = encoderProfile.bitRate,
            tBoxLabel = tBoxLabel,
            motorcyclePhotoPath = motorcyclePhotoPath,
            telemetryProvider = activeTelemetry,
            layoutController = layoutController,
            mapSource = mapSource,
            embeddedAndroidAuto = embeddedAndroidAuto,
            cellularOnlyMaps = cellularOnlyMaps,
            onFailure = onFailure,
            leftWidget = leftWidget,
            rightWidget = rightWidget
        )
        activeEncoder.setFrameCapListener { activeRenderer.setFrameRateCap(it) }
        activeRenderer.start()
        encoder = activeEncoder
        renderer = activeRenderer
    }

    fun stop() {
        embeddedAndroidAuto?.stop()
        embeddedAndroidAuto?.let {
            ProjectionEventLog.record("RIDE_AA", "Embedded Android Auto source stopped.")
        }
        renderer?.stop()
        renderer = null
        telemetryProvider?.stop()
        telemetryProvider = null
        encoder?.stop()
        encoder = null
        sink.close()
    }
}
