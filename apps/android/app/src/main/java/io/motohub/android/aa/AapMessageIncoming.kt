// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Ported from headunit-revived (AGPLv3): aap/AapMessageIncoming.kt
package io.motohub.android.aa

internal class AapMessageIncoming(header: EncryptedHeader, ba: ByteArrayWithLimit)
    // A continuation fragment carries raw bytes and no message type; one of a single byte has
    // nothing to read a 2-byte type from, so it gets -1 (unknown) instead of an out-of-bounds read.
    : AapMessage(header.chan, header.flags.toByte(), if (ba.data.size >= 2) Utils.bytesToInt(ba.data, 0, true) else -1, calcOffset(header), ba.limit, ba.data) {

    internal class EncryptedHeader {
        var chan: Int = 0
        var flags: Int = 0
        var enc_len: Int = 0
        var msg_type: Int = 0
        var buf = ByteArray(SIZE)

        fun decode() {
            this.chan = buf[0].toInt()
            this.flags = buf[1].toInt()
            // Encoded length of bytes to be decrypted (minus 4/8 byte headers)
            this.enc_len = Utils.bytesToInt(buf, 2, true)
        }

        companion object {
            const val SIZE = 4
        }
    }

    companion object {
        fun decrypt(header: EncryptedHeader, offset: Int, buf: ByteArray, ssl: AapSsl): AapMessage? {
            if (header.flags and 0x08 != 0x08) {
                AaLog.e(
                    "WRONG FLAG: enc_len: %d  chan: %d %s flags: 0x%02x  msg_type: 0x%02x %s",
                    header.enc_len, header.chan, Channel.name(header.chan), header.flags, header.msg_type,
                    MsgType.name(header.msg_type, header.chan)
                )
                return null
            }

            val ba = ssl.decrypt(offset, header.enc_len, buf) ?: return null

            // Only a first (or single) fragment starts with the 2-byte type. A last fragment of
            // one byte is valid: rejecting it lost that video frame (WB-48).
            if (ba.data.size < 2 && header.flags and 0x01 == 0x01) {
                AaLog.e(
                    "Decrypted payload too short: %d (chan: %d flags: 0x%02x enc_len: %d)",
                    ba.data.size, header.chan, header.flags, header.enc_len
                )
                return null
            }

            val msg = AapMessageIncoming(header, ba)
            if (AaLog.LOG_VERBOSE) AaLog.d("RECV: %s", msg.toString())
            return msg
        }

        fun calcOffset(header: EncryptedHeader): Int = 2
    }
}
