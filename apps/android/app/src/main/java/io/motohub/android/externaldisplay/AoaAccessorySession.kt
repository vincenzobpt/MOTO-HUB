package io.motohub.android.externaldisplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import io.motohub.android.session.ProjectionEventLog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AoaAccessorySession private constructor(
    private val fileDescriptor: ParcelFileDescriptor,
    private val outputStream: FileOutputStream
) {
    // Same fd as outputStream: AOA's accessory fd is a bidirectional bulk pipe, so this is a
    // second independent stream object over the same underlying descriptor, not a dup().
    val inputStream: FileInputStream by lazy { FileInputStream(fileDescriptor.fileDescriptor) }

    fun write(accessUnit: ByteArray) {
        outputStream.write(accessUnit)
    }

    fun close() {
        try {
            outputStream.close()
        } catch (_: Exception) {
        }
        try {
            fileDescriptor.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val AUTOLINK_PACKAGE = "com.link.autolink"
        private const val ACTION_USB_PERMISSION =
            "io.motohub.android.action.AOA_USB_PERMISSION"

        suspend fun open(context: Context): Result<AoaAccessorySession> = runCatching {
            val applicationContext = context.applicationContext
            val usbManager = applicationContext.getSystemService(UsbManager::class.java)
            val accessory = usbManager.accessoryList?.firstOrNull()
                ?: error("AOA USB accessory not found. Make sure the head unit is connected and close Autolink first.")

            try {
                applicationContext.getSystemService(android.app.ActivityManager::class.java)
                    .killBackgroundProcesses(AUTOLINK_PACKAGE)
                ProjectionEventLog.record("AOA_SERVICE", "Requested background stop of $AUTOLINK_PACKAGE.")
            } catch (failure: Exception) {
                ProjectionEventLog.warning("AOA_SERVICE", "Unable to stop Autolink: ${failure.message}")
            }

            if (!usbManager.hasPermission(accessory)) {
                val granted = requestPermission(applicationContext, usbManager, accessory)
                check(granted) { "USB accessory permission was denied." }
            }

            val fd = usbManager.openAccessory(accessory)
                ?: error("Unable to open the USB AOA accessory.")
            ProjectionEventLog.record("AOA_SERVICE", "AOA USB accessory opened OK.")
            AoaAccessorySession(fd, FileOutputStream(fd.fileDescriptor))
        }

        fun isAccessoryConnected(context: Context): Boolean {
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            return usbManager.accessoryList?.isNotEmpty() == true
        }

        private suspend fun requestPermission(
            context: Context,
            usbManager: UsbManager,
            accessory: UsbAccessory
        ): Boolean = withContext(Dispatchers.Main) {
            ProjectionEventLog.record("AOA_SERVICE", "Requesting USB AOA permission.")
            val latch = CountDownLatch(1)
            val granted = BooleanArray(1)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    granted[0] = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    latch.countDown()
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            usbManager.requestPermission(
                accessory,
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            latch.await(30_000L, TimeUnit.MILLISECONDS)
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            granted[0]
        }
    }
}
