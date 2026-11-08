// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

/**
 * Converts one AVCC access unit (4-byte big-endian NAL lengths, the shape AvcEncoder emits) to
 * the Annex-B byte stream the ThinkerRide and Yunmo dashes decode. A payload already in Annex-B
 * is returned untouched, and a unit that does not parse cleanly is shipped as-is rather than
 * corrupted.
 */
internal fun annexBFromAvcc(accessUnit: ByteArray): ByteArray {
    if (startsWithAnnexBCode(accessUnit)) return accessUnit
    val out = ArrayList<ByteArray>()
    var cursor = 0
    while (cursor + 4 <= accessUnit.size) {
        val nalLength = ((accessUnit[cursor].toInt() and 0xFF) shl 24) or
            ((accessUnit[cursor + 1].toInt() and 0xFF) shl 16) or
            ((accessUnit[cursor + 2].toInt() and 0xFF) shl 8) or
            (accessUnit[cursor + 3].toInt() and 0xFF)
        if (nalLength <= 0 || cursor + 4 + nalLength > accessUnit.size) return accessUnit
        out += accessUnit.copyOfRange(cursor + 4, cursor + 4 + nalLength)
        cursor += 4 + nalLength
    }
    if (cursor != accessUnit.size || out.isEmpty()) return accessUnit
    val total = out.sumOf { 4 + it.size }
    val result = ByteArray(total)
    var offset = 0
    for (nal in out) {
        result[offset + 3] = 1
        nal.copyInto(result, offset + 4)
        offset += 4 + nal.size
    }
    return result
}

private fun startsWithAnnexBCode(data: ByteArray): Boolean =
    (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
        data[2].toInt() == 0 && data[3].toInt() == 1) ||
        (data.size >= 3 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1)
