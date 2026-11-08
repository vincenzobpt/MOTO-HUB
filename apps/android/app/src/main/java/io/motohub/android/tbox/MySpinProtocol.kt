// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.tbox.MySpinWire.Packet
import io.motohub.android.tbox.MySpinWire.Reader
import io.motohub.android.tbox.MySpinWire.Writer

/**
 * The phone side of one mySPIN TCP session: handshake, screen description, and the frame loop.
 * No sockets and no threads; the transport feeds [receive] with every packet read and writes
 * whatever [send] is handed.
 *
 * The dash drives the video. It asks for one frame at a time (type 25) and the phone answers each
 * request with one whole JPEG (type 26). Nothing may be sent without a request outstanding, which
 * is why [sendFrame] reports whether it consumed one.
 *
 * Trailing bytes after the fields this class knows are ignored rather than fatal: a newer dash
 * firmware may append fields, and losing the picture over them would be worse than not reading
 * them.
 */
class MySpinProtocol(
    private val send: (Packet) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {

    enum class State { HANDSHAKE, INITIALIZING, READY }

    enum class Generation { V1_2, V2_0 }

    /** The projection area the dash describes in its ClientInit. */
    data class Screen(
        val name: String,
        val width: Int,
        val height: Int,
        val physicalWidth: Int,
        val physicalHeight: Int,
        val pixelFormat: Int,
        val pixelEndianness: Int,
        val preferredCompression: Int,
        val compressionLevel: Int,
        val compressionCapabilities: Int
    )

    data class FrameRequest(
        val screenId: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val incremental: Boolean
    )

    enum class Key(val wire: Int) {
        OK(12), BACK(14), ROTATE_CLOCKWISE(20), ROTATE_COUNTERCLOCKWISE(21),
        UP(30), DOWN(31), LEFT(32), RIGHT(33);

        companion object {
            fun of(wire: Int): Key? = entries.firstOrNull { it.wire == wire }
        }
    }

    enum class KeyAction(val wire: Int) {
        PRESS(1), RELEASE(2), LONG_PRESS(3), CLICK(4), ABORT(6);

        companion object {
            fun of(wire: Int): KeyAction? = entries.firstOrNull { it.wire == wire }
        }
    }

    sealed interface Event {
        data class StateChanged(val state: State, val generation: Generation?) : Event
        data class ScreenChanged(val screen: Screen) : Event
        data class FrameRequested(val request: FrameRequest) : Event
        data class FrameSent(val bytes: Int) : Event

        /** [key] and [action] are null for wire values outside the known table; the raw ones say which. */
        data class KeyInput(val key: Key?, val action: KeyAction?, val rawKey: Int, val rawAction: Int) : Event
        data class VehicleData(val key: Long, val value: String, val extra: Long) : Event
        data class ClientState(val state: Int, val value: Int, val extra: Int) : Event
        data class Language(val code: String) : Event
        data class VoiceSupport(val first: Int, val second: Int) : Event

        /** A packet this class does not interpret; logged so a field capture shows what the dash sent. */
        data class Unhandled(val type: Int, val size: Int) : Event
    }

    var state = State.HANDSHAKE
        private set
    var generation: Generation? = null
        private set
    var screen: Screen? = null
        private set

    private var pendingRequest: FrameRequest? = null
    private var clientReady = false
    private var serverReady = false
    private var displayReadySent = false

    val hasPendingFrameRequest: Boolean get() = pendingRequest != null

    private val v2: Boolean get() = generation == Generation.V2_0

    /** Interprets one packet from the dash. Throws [MySpinProtocolException] when the session cannot go on. */
    fun receive(packet: Packet): List<Event> {
        val reader = Reader(packet.payload)
        if (state == State.HANDSHAKE) return receiveVersion(packet.type, reader)
        when (packet.type) {
            TYPE_VOICE_SUPPORT -> return listOf(Event.VoiceSupport(reader.u8(), reader.u8()))
            TYPE_LANGUAGE -> return listOf(Event.Language(String(reader.bytes(3), Charsets.US_ASCII)))
        }
        return if (state == State.INITIALIZING) receiveInitializing(packet, reader) else receiveReady(packet, reader)
    }

    private fun receiveVersion(type: Int, reader: Reader): List<Event> {
        if (type != TYPE_CLIENT_VERSION) {
            throw MySpinProtocolException("expected the mySPIN version (type $TYPE_CLIENT_VERSION), got type $type")
        }
        val major = reader.u16()
        val minor = reader.u16()
        val patch = reader.u16()
        generation = when {
            major == 1 && minor == 2 -> Generation.V1_2
            major == 2 && minor == 0 -> Generation.V2_0
            else -> throw MySpinProtocolException("mySPIN $major.$minor.$patch is not supported (only 1.2.x and 2.0.x)")
        }
        emit(TYPE_VERSION_ACCEPTED, Writer().u8(1))
        state = State.INITIALIZING
        return listOf(Event.StateChanged(state, generation))
    }

    private fun receiveInitializing(packet: Packet, reader: Reader): List<Event> = when (packet.type) {
        TYPE_CLIENT_INIT -> {
            if (screen != null) throw MySpinProtocolException("the dash sent its ClientInit twice")
            val described = readScreen(reader)
            requireJpeg(described)
            screen = described
            emit(TYPE_CLIENT_INIT_ACK, Writer().u8(1))
            emit(TYPE_SERVER_INIT, serverInit())
            emit(TYPE_SERVER_READY, Writer().u8(1))
            serverReady = true
            listOf(Event.ScreenChanged(described)) + enterReadyIfBothSides()
        }
        TYPE_CLIENT_READY -> {
            if (reader.u8() != 1) throw MySpinProtocolException("the dash rejected the mySPIN initialisation")
            clientReady = true
            enterReadyIfBothSides()
        }
        TYPE_V2_INIT_EXTRA -> {
            if (!v2) throw MySpinProtocolException("mySPIN 2.0 packet $TYPE_V2_INIT_EXTRA on a 1.2 session")
            emptyList()
        }
        else -> listOf(Event.Unhandled(packet.type, packet.payload.size))
    }

    private fun receiveReady(packet: Packet, reader: Reader): List<Event> = when (packet.type) {
        TYPE_SET_PIXEL_FORMAT -> {
            val pixelFormat = reader.u8()
            val endianness = reader.u8()
            val level = reader.u8()
            requirePixelFormat(pixelFormat, endianness)
            val changed = currentScreen().copy(pixelFormat = pixelFormat, pixelEndianness = endianness, compressionLevel = level)
            screen = changed
            emit(TYPE_SET_PIXEL_FORMAT_ACK, Writer().u8(1))
            listOf(Event.ScreenChanged(changed))
        }
        TYPE_SET_COMPRESSION -> {
            val changed = currentScreen().copy(preferredCompression = reader.i32())
            requireJpeg(changed)
            screen = changed
            emit(TYPE_SET_COMPRESSION_ACK, Writer().u8(1))
            listOf(Event.ScreenChanged(changed))
        }
        TYPE_FRAME_REQUEST -> {
            val screenId = if (v2) reader.i32() else 0
            val request = FrameRequest(screenId, reader.u16(), reader.u16(), reader.u16(), reader.u16(), reader.u8() != 0)
            if (screenId != 0) throw MySpinProtocolException("the dash asked for screen $screenId; only screen 0 exists")
            // A second request before the answer adds nothing: the answer is always the whole screen.
            if (pendingRequest == null) {
                pendingRequest = request
                listOf(Event.FrameRequested(request))
            } else {
                emptyList()
            }
        }
        TYPE_CLIENT_STATE -> listOf(Event.ClientState(reader.u8(), reader.u8(), if (v2) reader.i32() else 0))
        TYPE_VEHICLE_DATA -> listOf(Event.VehicleData(reader.u32(), reader.string(), reader.u32()))
        TYPE_CUSTOM_DATA -> readCustomData(reader)
        TYPE_PING -> {
            emit(TYPE_PONG, Writer().i32(reader.i32()))
            emptyList()
        }
        TYPE_PONG -> emptyList()
        else -> listOf(Event.Unhandled(packet.type, packet.payload.size))
    }

    /**
     * Tells the dash the phone has a picture to show. Sent once, after the first frame is ready;
     * the two state pairs are what the reference client sends, their meaning is not documented.
     */
    fun announceDisplayReady() {
        check(state == State.READY) { "mySPIN session is not ready" }
        if (displayReadySent) return
        for ((first, second) in listOf(2 to 0, 4 to 1)) {
            val writer = Writer().u8(first).u8(second)
            if (v2) writer.i32(0)
            emit(TYPE_SERVER_STATE, writer)
        }
        displayReadySent = true
    }

    /**
     * Answers the outstanding frame request with [jpeg], which must already be the full screen
     * size. Returns null, sending nothing, when the dash has not asked for a frame.
     */
    fun sendFrame(jpeg: ByteArray): Event.FrameSent? {
        check(state == State.READY) { "mySPIN session is not ready" }
        require(jpeg.isNotEmpty()) { "empty frame" }
        val request = pendingRequest ?: return null
        val current = currentScreen()
        val writer = Writer()
        if (v2) writer.i32(request.screenId)
        writer.u8(1)
            .u16(0).u16(0).u16(current.width).u16(current.height)
            .u8(current.pixelFormat).u8(current.pixelEndianness)
            .i32(COMPRESSION_JPEG)
            .u8(current.compressionLevel)
            .u8(100).u8(100)
            .i32(jpeg.size)
            .bytes(jpeg)
        emit(TYPE_FRAME, writer)
        pendingRequest = null
        return Event.FrameSent(jpeg.size)
    }

    fun ping(sequence: Int) {
        if (state == State.READY) emit(TYPE_PING, Writer().i32(sequence))
    }

    /** Asks the dash to publish these vehicle-data keys (unsigned 32-bit ids). */
    fun subscribeVehicleData(keys: Collection<Long>) {
        check(state == State.READY) { "mySPIN session is not ready" }
        require(keys.size <= 0xFFFF) { "too many vehicle-data keys" }
        require(keys.all { it in 0..0xFFFF_FFFFL }) { "vehicle-data keys are unsigned 32-bit" }
        val writer = Writer().u16(keys.size)
        keys.forEach { writer.i32(it.toInt()) }
        emit(TYPE_SUBSCRIBE_VEHICLE_DATA, writer)
    }

    private fun enterReadyIfBothSides(): List<Event> {
        if (!clientReady || !serverReady || state == State.READY) return emptyList()
        state = State.READY
        emit(TYPE_PHONE_TIME, Writer().u8(2).u16(4).u16(8).i64(clock()))
        return listOf(Event.StateChanged(state, generation))
    }

    private fun serverInit(): Writer {
        val writer = Writer()
        if (!v2) writer.string(SERVER_NAME).u8(1)
        return writer.u8(1).u8(1).i32(if (v2) 14 else 6).string("Android").string("")
    }

    private fun readScreen(reader: Reader): Screen {
        val name = reader.string()
        val width = reader.u16()
        val height = reader.u16()
        val physicalWidth = reader.u16()
        val physicalHeight = reader.u16()
        reader.i32()
        val pixelFormat = reader.u8()
        val endianness = reader.u8()
        val preferredCompression = reader.i32()
        val compressionLevel = reader.u8()
        val compressionCapabilities = reader.i32()
        repeat(reader.u8()) { reader.u16() }
        repeat(reader.u8()) { reader.u8() }
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE || width.toLong() * height > MAX_PIXELS) {
            throw MySpinProtocolException("mySPIN screen ${width}x$height is outside the supported size")
        }
        requirePixelFormat(pixelFormat, endianness)
        return Screen(
            name, width, height, physicalWidth, physicalHeight, pixelFormat, endianness,
            preferredCompression, compressionLevel, compressionCapabilities
        )
    }

    private fun requirePixelFormat(pixelFormat: Int, endianness: Int) {
        if (pixelFormat !in 2..4 || endianness !in 1..2) {
            throw MySpinProtocolException("mySPIN pixel format $pixelFormat/$endianness is not supported")
        }
    }

    /**
     * shortcut: JPEG is the only compression implemented. A dash that wants only ZLIB or RAW ends the
     * session with this message; add them once a field log shows a Multistrada asking for one.
     */
    private fun requireJpeg(described: Screen) {
        if (!acceptsJpeg(described)) {
            throw MySpinProtocolException(
                "the dash wants compression ${described.preferredCompression} " +
                    "(capabilities ${described.compressionCapabilities}); only JPEG is supported"
            )
        }
    }

    private fun readCustomData(reader: Reader): List<Event> {
        val kind = reader.u8()
        val action = reader.u16()
        val data = Reader(reader.bytes(reader.u16()))
        if (kind != CUSTOM_KIND_KEY || data.remaining == 0) return listOf(Event.Unhandled(TYPE_CUSTOM_DATA, data.remaining))
        val rawKey = data.u8()
        return listOf(Event.KeyInput(Key.of(rawKey), KeyAction.of(action), rawKey, action))
    }

    private fun currentScreen(): Screen = screen ?: throw MySpinProtocolException("the dash never described its screen")

    private fun emit(type: Int, writer: Writer) {
        send(Packet(type, writer.build()))
    }

    companion object {
        const val TYPE_CLIENT_VERSION = 1
        const val TYPE_VERSION_ACCEPTED = 2
        const val TYPE_CLIENT_INIT = 11
        const val TYPE_CLIENT_INIT_ACK = 12
        const val TYPE_SERVER_INIT = 14
        const val TYPE_CLIENT_READY = 17
        const val TYPE_SERVER_READY = 18
        const val TYPE_SET_PIXEL_FORMAT = 21
        const val TYPE_SET_PIXEL_FORMAT_ACK = 22
        const val TYPE_SET_COMPRESSION = 23
        const val TYPE_SET_COMPRESSION_ACK = 24
        const val TYPE_FRAME_REQUEST = 25
        const val TYPE_FRAME = 26
        const val TYPE_CLIENT_STATE = 35
        const val TYPE_SERVER_STATE = 36
        const val TYPE_SUBSCRIBE_VEHICLE_DATA = 54
        const val TYPE_VEHICLE_DATA = 55
        const val TYPE_VOICE_SUPPORT = 61
        const val TYPE_CUSTOM_DATA = 67
        const val TYPE_PHONE_TIME = 68
        const val TYPE_LANGUAGE = 69
        const val TYPE_PING = 128
        const val TYPE_PONG = 129
        const val TYPE_V2_INIT_EXTRA = 169

        const val COMPRESSION_JPEG = 1
        const val CUSTOM_KIND_KEY = 5
        const val SERVER_NAME = "mySPIN Android Server"

        private const val MAX_SIDE = 4096
        private const val MAX_PIXELS = 2_097_152L

        /** The dash prefers JPEG, lists it, or lists nothing (then JPEG is the default). */
        fun acceptsJpeg(screen: Screen): Boolean = when (screen.preferredCompression) {
            COMPRESSION_JPEG -> true
            0 -> screen.compressionCapabilities == 0 ||
                screen.compressionCapabilities and COMPRESSION_JPEG != 0
            else -> false
        }
    }
}
