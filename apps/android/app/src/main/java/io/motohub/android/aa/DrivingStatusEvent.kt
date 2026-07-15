// Ported from headunit-revived (AGPLv3): aap/protocol/messages/DrivingStatusEvent.kt
package io.motohub.android.aa

import com.google.protobuf.Message
import io.motohub.android.aa.proto.Sensors

class DrivingStatusEvent(status: Sensors.SensorBatch.DrivingStatusData.Status)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, makeProto(status)) {

    companion object {
        private fun makeProto(status: Sensors.SensorBatch.DrivingStatusData.Status): Message =
            Sensors.SensorBatch.newBuilder()
                .addDrivingStatus(Sensors.SensorBatch.DrivingStatusData.newBuilder().setStatus(status.number))
                .build()
    }
}
