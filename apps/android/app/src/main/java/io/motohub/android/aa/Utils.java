// Ported from headunit-revived (AGPLv3): app/.../aap/Utils.java
// Original project (c) Michael Reid / Andre Rinas — headunit-revived, AGPLv3.
// Adapted for MOTO-HUB: package + logging shim only.
package io.motohub.android.aa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class Utils {

    public static long ms_sleep(long ms) {
        try {
            Thread.sleep(ms);
            return (ms);
        } catch (InterruptedException e) {
            AaLog.INSTANCE.e("Exception e: " + e);
            return (0);
        }
    }

    public static long tmr_ms_get() {
        return (System.nanoTime() / 1000000);
    }

    public static byte[] toByteArray(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        int buffSize = 16384 * 1024; // 16M
        byte[] data = new byte[buffSize];

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    public static void put_time(int offset, byte[] arr, long time) {
        for (int ctr = 7; ctr >= 0; ctr--) {                           // Fill 8 bytes backwards
            arr[offset + ctr] = (byte) (time & 0xFF);
            time = time >> 8;
        }
    }

    public static void intToBytes(int value, int offset, byte[] buf) {
        buf[offset] = (byte) (value / 256);                            // big-endian 16-bit length
        buf[offset + 1] = (byte) (value % 256);
    }

    public static int bytesToInt(byte[] buf, int idx, boolean isShort) {
        if (isShort) {
            return ((buf[idx] & 0xFF) << 8) + (buf[idx + 1] & 0xFF);
        }
        return ((buf[idx] & 0xFF) << 24) + ((buf[idx + 1] & 0xFF) << 16) + ((buf[idx + 2] & 0xFF) << 8) + (buf[idx + 3] & 0xFF);
    }

    public static int getAccVersion(byte[] buffer) {
        return (buffer[1] << 8) | buffer[0];
    }
}
