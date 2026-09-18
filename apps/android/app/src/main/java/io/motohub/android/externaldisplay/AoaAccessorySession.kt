// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
        suspend fun open(context: Context): Result<AoaAccessorySession> = runCatching {
            val applicationContext = context.applicationContext
            val usbManager = applicationContext.getSystemService(UsbManager::class.java)
            val accessory = usbManager.accessoryList?.firstOrNull()
                ?: error("AOA USB accessory not found. Make sure the head unit is connected and close Autolink first.")

            requestAutolinkStop(applicationContext)

            if (!usbManager.hasPermission(accessory)) {
                val granted = awaitAccessoryPermission(applicationContext, usbManager, accessory, 30_000L)
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
    }
}

private const val ACTION_USB_PERMISSION = "io.motohub.android.action.AOA_USB_PERMISSION"

/**
 * Shows Android's "allow this accessory" dialog and waits for the rider's answer.
 *
 * Suspends, never blocks: the answer is a broadcast delivered on the main looper, so the old
 * `CountDownLatch.await()` inside `withContext(Main)` held the very thread the answer had to
 * arrive on - the app froze for the whole timeout and then reported "denied" even after the
 * rider tapped Allow. The verdict is read from [UsbManager.hasPermission] as well as from the
 * extra, because the extra is only filled in when the PendingIntent is mutable.
 */
internal suspend fun awaitAccessoryPermission(
    context: Context,
    usbManager: UsbManager,
    accessory: UsbAccessory,
    timeoutMs: Long
): Boolean {
    ProjectionEventLog.record("AOA_SERVICE", "Requesting USB AOA permission.")
    val answered = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        runCatching { context.unregisterReceiver(this) }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (continuation.isActive) continuation.resume(granted)
                    }
                }
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(ACTION_USB_PERMISSION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                continuation.invokeOnCancellation {
                    runCatching { context.unregisterReceiver(receiver) }
                }
                usbManager.requestPermission(
                    accessory,
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        // Explicit package: Android 14 refuses a mutable PendingIntent around an
                        // implicit intent, and it has to be mutable for the system to add the
                        // EXTRA_PERMISSION_GRANTED verdict.
                        Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }
        }
    }
    val granted = answered == true || usbManager.hasPermission(accessory)
    when {
        granted -> ProjectionEventLog.record("AOA_SERVICE", "USB AOA permission granted.")
        answered == null -> ProjectionEventLog.warning(
            "AOA_SERVICE",
            "USB AOA permission: no answer from the rider within ${timeoutMs / 1000}s."
        )
        else -> ProjectionEventLog.warning("AOA_SERVICE", "USB AOA permission denied by the rider.")
    }
    return granted
}
