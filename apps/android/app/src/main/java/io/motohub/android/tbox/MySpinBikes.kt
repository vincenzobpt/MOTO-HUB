// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.motohub.android.MainActivity
import io.motohub.android.R
import io.motohub.android.i18n.MotoHubStrings
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Recognises a mySPIN motorcycle by its Bluetooth name, so a rider adds it with one tap instead of
 * choosing the mode and typing the name (report 3194-5444-46CC typed "SZUZUKI_MYSPIN_1E4D7").
 * The rules are the names the official launchers accept (SUZUKI mySPIN and Ducati Connect
 * 2.26.0, Kawasaki SPIN 2.25.0): whole-name, case-sensitive matches.
 */
object MySpinBikes {
    private val SUZUKI = Regex("SUZUKI_MYSPIN.*")
    private val DUCATI = Regex("DUCATI.*")
    private val KAWASAKI = Regex("Kawasaki001.*")
    /** The names the BMW Motorrad app accepts: "BMW Motorrad" + VIN tail, or "ICC". */
    private val BMW = Regex("(BMW Motorrad|ICC).*")

    const val EXTRA_ADD = "io.motohub.android.extra.MYSPIN_ADD"
    private const val CHANNEL_ID = "myspin_found_v1"
    private const val NOTIFICATION_ID = 4403

    /** "Suzuki", "Ducati", "Kawasaki", "BMW", or null for any other Bluetooth name. */
    fun brandOf(name: String?): String? = when {
        name == null -> null
        SUZUKI.matches(name) -> "Suzuki"
        DUCATI.matches(name) -> "Ducati"
        KAWASAKI.matches(name) -> "Kawasaki"
        BMW.matches(name) -> "BMW"
        else -> null
    }

    /** Pure: the mySPIN names among [names] that no saved motorcycle already uses. */
    internal fun unsaved(names: List<String>, savedNames: Collection<String>): List<String> =
        names.filter { name -> brandOf(name) != null && savedNames.none { it.equals(name, ignoreCase = true) } }
            .distinct()
            .sorted()

    /**
     * Pure: [names] for the manual setup's picker, the recognised mySPIN bikes first. Every other
     * name stays in, below them: a model whose name no rule knows yet must still be pickable.
     */
    internal fun rankedForPicker(names: List<String>): List<String> =
        names.distinct().sortedWith(compareBy<String> { brandOf(it) == null }.thenBy(String.CASE_INSENSITIVE_ORDER) { it })

    /** Paired mySPIN motorcycles not saved yet; empty without the Bluetooth permission. */
    fun pairedUnsaved(context: Context, savedNames: Collection<String>): List<String> =
        unsaved(bondedNames(context), savedNames)

    /** Every device this phone is paired with, mySPIN bikes first; empty without the Bluetooth permission. */
    fun pairedForPicker(context: Context): List<String> = rankedForPicker(bondedNames(context))

    @SuppressLint("MissingPermission")
    private fun bondedNames(context: Context): List<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices.orEmpty().mapNotNull { it.name } }.getOrDefault(emptyList())
    }

    /** "Suzuki found": tapping it opens MOTO-HUB, which adds [name] (see [MySpinFoundInbox]). */
    fun notifyFound(context: Context, name: String) {
        val brand = brandOf(name) ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, MotoHubStrings.get("mySPIN motorcycles"), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_ADD, name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(MotoHubStrings.get("%1\$s found: %2\$s", brand, name))
            .setContentText(MotoHubStrings.get("Tap to add it to MOTO-HUB."))
            .setContentIntent(open)
            .setAutoCancel(true)
            // The dash's link comes and goes (phone, media, mySPIN): one alert, not one per link.
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        ProjectionEventLog.record(MySpinBluetoothLink.TAG, "A $brand mySPIN dash \"$name\" connected and is not saved yet; offered to add it.")
    }
}

/** The name a "found" notification asked to add, held until the activity takes it. */
object MySpinFoundInbox {
    private val mutablePending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = mutablePending.asStateFlow()

    fun offer(intent: Intent?) {
        intent?.getStringExtra(MySpinBikes.EXTRA_ADD)?.takeIf { MySpinBikes.brandOf(it) != null }?.let { mutablePending.value = it }
    }

    fun consume() {
        mutablePending.value = null
    }
}
