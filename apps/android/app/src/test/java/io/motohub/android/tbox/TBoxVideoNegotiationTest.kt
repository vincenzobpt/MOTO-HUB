package io.motohub.android.tbox

import android.net.Network
import io.motohub.android.encoding.EncoderProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxVideoNegotiationTest {
    @Test
    fun `captures an area emitted synchronously while transport starts`() = runBlocking {
        val transport = FakeTransport(TBoxEvent.VideoArea(720, 712))

        val result = transport.negotiateVideoConfiguration(HOST, null, 100)

        assertEquals(
            TBoxVideoConfiguration(
                rawArea = TBoxEvent.VideoArea(720, 712),
                encoderProfile = EncoderProfile(720, 704),
                source = TBoxVideoAreaSource.LIVE
            ),
            result.getOrThrow()
        )
    }

    @Test
    fun `uses saved geometry only when the live area times out`() = runBlocking {
        val transport = FakeTransport(null)

        val result = transport.negotiateVideoConfiguration(
            HOST,
            savedArea = TBoxEvent.VideoArea(1024, 601),
            timeoutMillis = 10
        )

        assertEquals(TBoxVideoAreaSource.SAVED, result.getOrThrow().source)
        assertEquals(EncoderProfile(1024, 592), result.getOrThrow().encoderProfile)
    }

    @Test
    fun `fails instead of inventing dimensions when no geometry exists`() = runBlocking {
        val transport = FakeTransport(null)

        val result = transport.negotiateVideoConfiguration(HOST, null, 10)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("no saved geometry"))
    }

    private class FakeTransport(
        private val areaOnStart: TBoxEvent.VideoArea?
    ) : TBoxTransport {
        private val mutableEvents = MutableSharedFlow<TBoxEvent>()
        override val events: Flow<TBoxEvent> = mutableEvents.asSharedFlow()

        override suspend fun discover(network: Network): Result<TBoxHost> = Result.success(HOST)

        override suspend fun start(host: TBoxHost): Result<Unit> {
            areaOnStart?.let { mutableEvents.emit(it) }
            return Result.success(Unit)
        }

        override fun offerAccessUnit(avcc: ByteArray): Boolean = true

        override suspend fun stop() = Unit
    }

    private companion object {
        val HOST = TBoxHost("192.168.43.1", 10930, "ECARX")
    }
}
