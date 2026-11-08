// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.pairing

import io.motohub.android.i18n.motoHubText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.motohub.android.tbox.MySpinBikes
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubHeader
import io.motohub.android.session.TBoxConnectionMode

/**
 * Fallback pairing path for motorcycles that don't show an EasyConn QR code
 * (reported on some US-market bikes whose only official companion is
 * CFMOTO RideSync). The rider must already know the T-Box's Wi-Fi network
 * name and password from another source (the bike itself, its manual, or a
 * dealer) - this screen does not discover or guess credentials, it only
 * removes the QR scan as a hard requirement to enter them.
 */
@Composable
fun ManualPairingScreen(
    ssid: String,
    password: String,
    connectionMode: TBoxConnectionMode,
    formError: String?,
    /**
     * A name the phone has evidence for, which [ssid] differs from only by spacing or
     * punctuation. Null unless there is one - see [manualSsidVerdict].
     */
    ssidSuggestion: String?,
    onAcceptSsidSuggestion: () -> Unit,
    /** The phone's paired devices, mySPIN bikes first ([MySpinBikes.pairedForPicker]); offered on the mySPIN path. */
    pairedBluetoothNames: List<String> = emptyList(),
    onSsidChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onConnectionModeChanged: (TBoxConnectionMode) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    MotoHubBackground(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            MotoHubHeader(
                modifier = Modifier.fillMaxWidth(),
                trailing = { TextButton(onClick = onClose) { Text(motoHubText("Close")) } }
            )

            // The one mode whose dashboards have no Wi-Fi credentials to be told: the Save below
            // refuses an empty name, so asking for a password here would make it unusable by hand.
            val mySpin = connectionMode == TBoxConnectionMode.MYSPIN

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoLabel(motoHubText("MANUAL SETUP"))
                Text(motoHubText("Connect without a QR code"), style = MaterialTheme.typography.displaySmall)
                Text(
                    if (mySpin) {
                        motoHubText(
                            "A Ducati Multistrada V4, a Suzuki GSX-S1000GT/GX, a Kawasaki Ninja H2 SX or a BMW with the " +
                                "10.25-inch TFT (in testing) hands its Wi-Fi over on Bluetooth by " +
                                "itself. Pair the motorcycle in the phone's Bluetooth settings first, " +
                                "then type the Bluetooth name it has there. On a Ducati or a Suzuki, " +
                                "choose Ducati Connect or mySPIN on the dash to connect."
                        )
                    } else {
                        motoHubText("Some motorcycles don't show a pairing QR code on the dash. If you already know ") +
                            "the T-Box's Wi-Fi network name and password - from the bike itself, its manual, " +
                            "or a dealer - enter them here instead of scanning."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = ssid,
                onValueChange = onSsidChanged,
                label = {
                    Text(
                        if (mySpin) motoHubText("Bluetooth name of the paired motorcycle")
                        else motoHubText("Wi-Fi network name (SSID)")
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            // Picking the name the phone already has beats typing it: one wrong letter and the
            // link never finds the bike.
            if (mySpin && pairedBluetoothNames.isNotEmpty()) {
                var pickerOpen by rememberSaveable { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(motoHubText("Choose from paired devices"))
                    }
                    DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                        pairedBluetoothNames.forEach { name ->
                            val brand = MySpinBikes.brandOf(name)
                            DropdownMenuItem(
                                text = { Text(name) },
                                trailingIcon = if (brand == null) null else {
                                    { Text(brand, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                                },
                                onClick = {
                                    pickerOpen = false
                                    onSsidChanged(name)
                                }
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(motoHubText("T-Box Wi-Fi transport"), style = MaterialTheme.typography.titleSmall)
                Text(
                    motoHubText("Auto detects DIRECT- networks. Choose Wi-Fi Direct for a dashboard that is a P2P Group Owner."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TBoxConnectionMode.entries.forEach { mode ->
                    FilterChip(
                        selected = connectionMode == mode,
                        onClick = { onConnectionModeChanged(mode) },
                        label = { Text(motoHubText(mode.label())) }
                    )
                }
            }
            // Hidden rather than disabled: a greyed-out password box still tells the rider they
            // are missing something, which is the whole misunderstanding.
            if (!mySpin) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChanged,
                    label = { Text(motoHubText("Wi-Fi password")) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            formError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            // Deliberately not an error colour, and deliberately not blocking. The rider may well
            // be right; this only makes the other reading visible before a second motorcycle is
            // created that can never be joined.
            ssidSuggestion?.let { suggestion ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // One sentence with both names in it, not four fragments glued around them:
                    // a translator handed "No network called " and " has been seen, but this
                    // phone knows " separately cannot move them, and every language that puts
                    // its verb elsewhere would come out wrong. Same for the button.
                    Text(
                        motoHubText(
                            "No network called “%1\$s” has been seen, but this phone knows " +
                                "“%2\$s”, which differs only in spacing. Save again to keep " +
                                "what you typed.",
                            ssid,
                            suggestion
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onAcceptSsidSuggestion) {
                        Text(motoHubText("Use %1\$s", suggestion))
                    }
                }
            }

            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(motoHubText("Save"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun TBoxConnectionMode.label(): String = when (this) {
    TBoxConnectionMode.AUTO -> "Auto"
    TBoxConnectionMode.ACCESS_POINT -> "Access point"
    TBoxConnectionMode.WIFI_DIRECT -> "Wi-Fi Direct (P2P)"
    // Named from the rider's point of view: what they have to do, not what the dash is. They pick
    // this after their dash asks them to open a hotspot, so "phone hotspot" is the phrase they
    // just read on the screen.
    TBoxConnectionMode.PHONE_HOTSPOT -> "My phone hosts the hotspot"
    // For a dash that shows no credentials anywhere and no network in any scan: the app hosts a
    // hotspot itself and hands it over on Bluetooth. Named for what the rider observes - their
    // dash asked them to open an app and did nothing else.
    TBoxConnectionMode.BLE_PROVISIONED -> "Set up over Bluetooth (no network shown)"
    // Normally set by scanning the ThinkerRide QR; offered here for rebadged units whose code
    // points at an OEM host. "KOVE" is the brand a rider would look for, ThinkerRide the tech.
    TBoxConnectionMode.THINKERRIDE -> "KOVE / ThinkerRide (Bluetooth)"
    TBoxConnectionMode.MYSPIN -> "Ducati / Suzuki / Kawasaki / BMW mySPIN (Bluetooth)"
}
