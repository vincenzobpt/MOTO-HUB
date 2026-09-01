// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.pairing

import io.motohub.android.session.TBoxConnectionMode
import io.motohub.android.tbox.ThinkerRideProtocol
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * How far the decoded QR corroborates itself.
 *
 * The pairing QR is a Carbit artefact, and Carbit licenses the same dash stack to manufacturers
 * well beyond CFMOTO. A rebadged unit can serve the identical query string from its own OEM host,
 * so treating the host as an entry requirement would turn a cosmetic difference into a hard
 * rejection. The host is therefore corroboration, not a gate: an unfamiliar source still produces
 * a payload, marked so the caller can put the decision in front of the rider instead of guessing.
 */
enum class TBoxQrOrigin {
    /**
     * Shape and source both check out — a known provisioning host, or a dialect specific enough
     * to identify itself (see [TBoxQrParser.parse]). Not a vendor name: several manufacturers
     * reach this level, which is why it is not called `CARBIT`.
     */
    RECOGNISED,

    /** Usable credentials from a source MOTO-HUB cannot vouch for. Confirm before saving. */
    UNVERIFIED
}

/**
 * What the Carbit `action` parameter says about how the dash expects to be reached. It is a
 * bitmask, not an enum: a dash can advertise more than one of these, and several do.
 *
 * This matters most for the bit nothing else can tell you. A dash that wants the phone to host the
 * hotspot advertises no network of its own, so its QR has no SSID and no password — the shape that
 * used to be rejected outright as unusable. Everything else about that dash looks like any other
 * Carbit code.
 *
 * The other bits pay for themselves by ruling things out: a dash that does not claim Wi-Fi Direct
 * should not be made to sit through a P2P join attempt that can only time out.
 */
@JvmInline
value class TBoxQrTopology(val bits: Int) {
    val accessPoint: Boolean get() = bits and (BIT_AP or BIT_AP_INTERNET) != 0
    val wifiDirect: Boolean get() = bits and BIT_P2P != 0
    val phoneHostsHotspot: Boolean get() = bits and BIT_PHONE_HOTSPOT != 0

    /**
     * The claim in words, for a log a rider will mail in. `bits=0` is itself an answer - a code
     * that made no claim at all - and is worth saying rather than printing an empty list.
     */
    fun describe(): String {
        if (bits == 0) return "nothing (no action bitmask in the code)"
        val claims = buildList {
            if (bits and BIT_AP != 0) add("access point")
            if (bits and BIT_AP_INTERNET != 0) add("access point with internet")
            if (wifiDirect) add("Wi-Fi Direct")
            if (phoneHostsHotspot) add("phone hosts the hotspot")
        }
        return if (claims.isEmpty()) "unknown (action=$bits)" else claims.joinToString() + " (action=$bits)"
    }

    /** True only when the dash said something and none of it was an access point of its own. */
    val neverOffersAccessPoint: Boolean get() = bits != 0 && !accessPoint

    /**
     * The transport this code implies, or null to leave the rider's saved choice alone. A code
     * that claims exactly one topology is decisive; one that claims several is not, because only
     * the dash knows which it will actually be on, and [TBoxConnectionMode.AUTO] picks between
     * them from the SSID at connect time.
     *
     * The Wi-Fi Direct case is not the same as an access point and AUTO cannot stand in for it.
     * AUTO infers P2P from a `DIRECT-` SSID prefix ([io.motohub.android.tbox.TBoxLinkResolver]),
     * but a P2P code does not carry the group name: `ssid=` is the dash's P2P *device* name, and
     * the group Android will see is `DIRECT-xy-<that name>`. A QJ SRK921 RR (field log
     * 6b345de4, 2026-08-28) scanned `ssid=qj5inch-0758 action=8` - Wi-Fi Direct and nothing else
     * - fell to AUTO, failed the prefix test, and spent every attempt asking
     * `WifiNetworkSpecifier` for an access point that does not exist and never appeared in a
     * single scan. Three joins, three 30s timeouts, and the rider was then offered the phone
     * hotspot - the one topology the code had explicitly ruled out.
     */
    fun suggestedConnectionMode(): TBoxConnectionMode? = when {
        phoneHostsHotspot && !accessPoint && !wifiDirect -> TBoxConnectionMode.PHONE_HOTSPOT
        wifiDirect && !accessPoint && !phoneHostsHotspot -> TBoxConnectionMode.WIFI_DIRECT
        else -> null
    }

    companion object {
        const val BIT_AP = 1
        const val BIT_AP_INTERNET = 1 shl 1
        const val BIT_P2P = 1 shl 3
        const val BIT_PHONE_HOTSPOT = 1 shl 7

        /** Unset, unparseable or absent all mean "the code said nothing", which is not a claim. */
        val UNSPECIFIED = TBoxQrTopology(0)

        fun of(raw: String?): TBoxQrTopology =
            TBoxQrTopology(raw?.trim()?.toIntOrNull() ?: 0)
    }
}

data class TBoxQrPayload(
    val ssid: String,
    val password: String,
    val encryption: String?,
    // Opaque T-Box provisioning identifier. It is never interpreted as a motorcycle model.
    val modelId: String?,
    val displayName: String?,
    val origin: TBoxQrOrigin,
    /**
     * Set only by a dialect that identifies the *transport*, not just the network — the
     * ThinkerRide code means "pair over BLE, the dash connects to you", which no SSID shape or
     * modelId could re-derive later. Null leaves the saved profile's mode untouched.
     */
    val suggestedConnectionMode: TBoxConnectionMode? = null,
    /** What the `action` bitmask claimed, or [TBoxQrTopology.UNSPECIFIED] when it said nothing. */
    val topology: TBoxQrTopology = TBoxQrTopology.UNSPECIFIED,
    /**
     * The dash's own MAC, from `mac=` or Carbit's `bm=`, lowercase colon form. On a phone-hotspot
     * code this is the only thing identifying the dash at all.
     */
    val dashMacAddress: String? = null
)

object TBoxQrParser {
    private const val WIFI_SCHEME = "WIFI:"

    /**
     * Decodes any of the three pairing codes seen in the field. Failure is reserved for content
     * that carries no network name at all — anything with usable credentials comes back with an
     * [TBoxQrOrigin] describing how much it can be trusted.
     *
     * The MotoFun dialect is tried before the query-string one because it is recognised by shape
     * (`Wifi=<ssid>#<password>`) rather than by a parameter name, and returns null the moment that
     * shape is absent — so a Carbit code carrying an unrelated `wifi=` parameter still falls
     * through to [parseProvisioningUrl]. That one throws instead of returning null, so it is last.
     */
    fun parse(rawValue: String): Result<TBoxQrPayload> = runCatching {
        val trimmed = rawValue.trim()
        parseWifiNetworkCode(trimmed)
            ?: parseCarbitToken(trimmed)
            ?: parseMotoFunUrl(trimmed)
            ?: parseThinkerRideUrl(trimmed)
            ?: parseProvisioningUrl(trimmed)
    }

    /**
     * The bare `CARBIT` + 12 hex code some dashes print instead of a URL:
     *
     *     CARBITDC0D301738D4
     *
     * It carries no network, no password and no `action` bitmask, because the dash it comes from
     * has none of those to offer. Confirmed on a Zontes S350 (Brazil/JTZ, 2026): no access point,
     * nothing in a Wi-Fi Direct scan, and a dash screen that says only "open the app and scan
     * this". What it does carry is the dash's identity, and that dash is reachable over Bluetooth
     * - so this code selects [TBoxConnectionMode.BLE_PROVISIONED], the one transport that can do
     * anything with it.
     *
     * The twelve digits are the dash's Wi-Fi MAC, not its Bluetooth one: on the S350 the QR reads
     * `DC:0D:30:17:38:D4` while the BLE peripheral answers on `DD:0D:30:17:38:D4`. They are kept
     * for identification, never used to address the radio - [io.motohub.android.tbox.EcBtpNetLink]
     * finds the dash by the service it advertises.
     */
    private fun parseCarbitToken(rawValue: String): TBoxQrPayload? {
        val digits = CARBIT_TOKEN.matchEntire(rawValue)?.groupValues?.get(1)?.uppercase() ?: return null
        // What the dash calls itself over BLE: "EC" followed by the last four bytes of the MAC
        // (`CARBITDC0D301738D4` -> `EC301738D4`). Used as the profile's name because a profile is
        // keyed by SSID and this dash has none - and because it is the string the rider can see
        // for themselves in any Bluetooth scanner.
        val advertisedName = "EC" + digits.takeLast(8)
        return TBoxQrPayload(
            ssid = advertisedName,
            password = "",
            encryption = null,
            modelId = null,
            displayName = advertisedName,
            // No other dialect prints this prefix followed by exactly twelve hex digits, which is
            // the same standard the MotoFun shape is recognised by.
            origin = TBoxQrOrigin.RECOGNISED,
            suggestedConnectionMode = TBoxConnectionMode.BLE_PROVISIONED,
            dashMacAddress = normaliseMac(digits)
        )
    }

    /**
     * The ThinkerRide (KOVE) pairing code:
     *
     *     http://g.thinkerride.com/?<SSID>&<PASSWORD>&ap=1
     *
     * The credentials are *positional* — two bare query components with no `key=` at all — which
     * no other dialect produces, so the shape is recognisable on its own. The host corroborates
     * it; a rebadged unit serving the same shape from an OEM host needs the `ap=` marker as a
     * second witness and still goes to the rider as [TBoxQrOrigin.UNVERIFIED]. Like MotoFun,
     * this one returns null the moment the shape is absent, so Carbit query strings
     * (`ssid=...&pwd=...`) fall through untouched to [parseProvisioningUrl].
     *
     * Beyond the network, this dialect decides the *transport*: a ThinkerRide dash pairs over
     * BLE and then connects to the phone, so the payload carries
     * [TBoxConnectionMode.THINKERRIDE] and the pseudo modelId that routes later sessions to the
     * ThinkerRide profile family.
     */
    private fun parseThinkerRideUrl(rawValue: String): TBoxQrPayload? {
        val uri = runCatching { URI(rawValue) }.getOrNull()
        val query = (uri?.rawQuery ?: rawValue.substringAfter('?', "").substringBefore('#'))
        val components = query.split('&').filter(String::isNotBlank)
        if (components.size < 2) return null
        val positional = components.take(2)
        if (positional.any { it.contains('=') }) return null

        val host = (uri?.host ?: hostOf(rawValue))?.lowercase()
        val thinkerRideHost = host != null && THINKER_RIDE_DOMAINS.any { host == it || host.endsWith(".$it") }
        val accessPointMarker = components.drop(2).any { it.equals("ap=1", ignoreCase = true) }
        if (!thinkerRideHost && !accessPointMarker) return null

        val ssid = decode(positional[0]).trim()
        if (ssid.isEmpty()) return null

        return TBoxQrPayload(
            ssid = ssid,
            password = decode(positional[1]),
            // Every ThinkerRide dash seen runs a WPA2 access point; a passphrase was just read
            // out of the code, so an open network is not a possibility.
            encryption = "wpa2-psk",
            modelId = ThinkerRideProtocol.PROVISIONING_MODEL_ID,
            displayName = null,
            origin = if (thinkerRideHost) TBoxQrOrigin.RECOGNISED else TBoxQrOrigin.UNVERIFIED,
            suggestedConnectionMode = TBoxConnectionMode.THINKERRIDE
        )
    }

    private fun parseProvisioningUrl(rawValue: String): TBoxQrPayload {
        // URI rejects the whole string over one unescaped character - a `%` in a passphrase is
        // enough - so a dash whose QR is slightly off spec would be unpairable. Fall back to
        // reading the query and host by hand; content that carries no SSID is still rejected
        // below, which is the only rejection this parser owes the caller.
        val uri = runCatching { URI(rawValue) }.getOrNull()
        val parameters = (uri?.rawQuery ?: rawValue.substringAfter('?', "").substringBefore('#'))
            .split('&')
            .filter(String::isNotBlank)
            .associate { item ->
                val keyAndValue = item.split('=', limit = 2)
                // Parameter names are folded: OEM firmware is not consistent about their case,
                // and an `SSID=` that reads as absent costs a pairing for a cosmetic difference.
                decode(keyAndValue[0]).lowercase() to decode(keyAndValue.getOrElse(1) { "" })
            }
        val host = (uri?.host ?: hostOf(rawValue))?.lowercase()
        val origin = if (host != null && isKnownProvisioningHost(host)) {
            TBoxQrOrigin.RECOGNISED
        } else {
            TBoxQrOrigin.UNVERIFIED
        }
        val topology = TBoxQrTopology.of(parameters["action"])
        val dashMac = normaliseMac(parameters["mac"] ?: parameters["bm"])
        val ssid = parameters["ssid"].orEmpty().trim()

        // A dash that wants the phone to host carries no network of its own to name, so this code
        // is complete without an SSID and rejecting it for the missing field would be wrong. The
        // rider still has to type the credentials the dash prints on its own screen (Android does
        // not let an app dictate them), but the profile, the transport and the MAC all come from
        // here instead of from a guess.
        if (ssid.isEmpty() && topology.phoneHostsHotspot && dashMac != null) {
            val tail = dashMac.filter { it != ':' }.takeLast(6).uppercase()
            return TBoxQrPayload(
                ssid = "",
                password = "",
                encryption = null,
                modelId = parameters["modelid"],
                displayName = "Phone hotspot ($tail)",
                origin = origin,
                suggestedConnectionMode = TBoxConnectionMode.PHONE_HOTSPOT,
                topology = topology,
                dashMacAddress = dashMac
            )
        }

        check(ssid.isNotEmpty()) { describeUnusableCode(rawValue) }

        return TBoxQrPayload(
            ssid = ssid,
            password = parameters["pwd"].orEmpty(),
            encryption = parameters["auth"],
            modelId = parameters["modelid"],
            displayName = parameters["name"],
            origin = origin,
            suggestedConnectionMode = topology.suggestedConnectionMode(),
            topology = topology,
            dashMacAddress = dashMac
        )
    }

    /**
     * Accepts a MAC in either shape Carbit prints it — colon-separated, or twelve bare hex digits —
     * and returns the lowercase colon form. Anything else is not a MAC and comes back null rather
     * than being passed on as one.
     */
    private fun normaliseMac(raw: String?): String? {
        val digits = raw?.trim()?.replace(":", "")?.replace("-", "") ?: return null
        if (digits.length != 12 || !digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return digits.lowercase().chunked(2).joinToString(":")
    }

    /**
     * The Moto Morini / MotoFun dash code, confirmed on the X-Cape 649 / 700 and the Seiemmezzo:
     *
     *     http://admin.motomorini.com/app.html?Wifi=ML174167#12345678#dc0d30da1b6c
     *       &MachineID=dc0d30da1b6c&ProductID=00297
     *
     * `#` is a field separator here, not the start of a URI fragment, so neither [URI] nor the
     * hand-rolled query split can be used: both stop at the first `#` and hand back a query of
     * `Wifi=ML174167`, silently dropping the password. The raw string is scanned instead.
     *
     * `ProductID` takes the place of `modelid` — like it, an opaque provisioning identifier that is
     * never read as a motorcycle model. There is no `action` bitmask to honour: the dash is a plain
     * access point, and the transport is decided from the SSID shape further down.
     */
    private fun parseMotoFunUrl(rawValue: String): TBoxQrPayload? {
        val wifiField = MOTO_FUN_WIFI.find(rawValue) ?: return null
        val ssid = wifiField.groupValues[1].trim()
        if (ssid.isEmpty()) return null

        // The match stops at the `#` that ends the SSID, so the remainder starts on the separator.
        // Its absence means this is some other `wifi=` parameter, not a MotoFun pairing code.
        val remainder = rawValue.substring(wifiField.range.last + 1)
        if (!remainder.startsWith('#')) return null
        val password = remainder.drop(1).substringBefore('#').substringBefore('&').trim()
        if (password.isEmpty()) return null

        val machineId = MOTO_FUN_MACHINE_ID.find(rawValue)?.groupValues?.get(1)
        val productId = MOTO_FUN_PRODUCT_ID.find(rawValue)?.groupValues?.get(1)
        val host = (runCatching { URI(rawValue) }.getOrNull()?.host ?: hostOf(rawValue))?.lowercase()

        // This dialect identifies itself: no other code puts a password behind `Wifi=<ssid>#`, and
        // the MotoFun identifiers alongside it are a second witness. A rebadged unit serving the
        // same shape from an unfamiliar host with neither identifier still goes to the rider.
        val corroborated = (host != null && isKnownProvisioningHost(host)) ||
            machineId != null || productId != null

        return TBoxQrPayload(
            ssid = ssid,
            password = password,
            // Not carried by this dialect. Every dash seen with it runs a WPA2 access point, and a
            // passphrase was just read out of the code, so an open network is not a possibility.
            encryption = "wpa2-psk",
            modelId = productId,
            displayName = null,
            origin = if (corroborated) TBoxQrOrigin.RECOGNISED else TBoxQrOrigin.UNVERIFIED
        )
    }

    /**
     * The standard `WIFI:S:name;T:WPA;P:secret;;` code some dashes print instead of a provisioning
     * URL. It carries no model id, so the dash is identified from CLIENT_INFO on first contact —
     * the same route an unrecognised provisioning URL takes.
     */
    private fun parseWifiNetworkCode(rawValue: String): TBoxQrPayload? {
        if (!rawValue.startsWith(WIFI_SCHEME, ignoreCase = true)) return null
        val fields = splitWifiFields(rawValue.substring(WIFI_SCHEME.length))
        val ssid = fields["S"].orEmpty()
        check(ssid.isNotEmpty()) {
            "The Wi-Fi QR code does not carry a network name." + TRY_THE_IOS_CODE
        }

        return TBoxQrPayload(
            ssid = ssid,
            password = fields["P"].orEmpty(),
            encryption = fields["T"],
            modelId = null,
            displayName = null,
            origin = TBoxQrOrigin.UNVERIFIED
        )
    }

    /**
     * Splits the `key:value;` pairs of a Wi-Fi network code. The format escapes its own delimiters
     * with a backslash, so the key/value split has to happen while scanning: an SSID containing an
     * escaped colon would otherwise be cut in half by a later search.
     */
    private fun splitWifiFields(body: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        val buffer = StringBuilder()
        var key: String? = null
        var escaped = false

        fun commit() {
            key?.takeIf(String::isNotEmpty)?.let { name ->
                fields.putIfAbsent(name.uppercase(), buffer.toString())
            }
            key = null
            buffer.setLength(0)
        }

        for (character in body) {
            when {
                escaped -> {
                    buffer.append(character)
                    escaped = false
                }
                character == '\\' -> escaped = true
                character == ':' && key == null -> {
                    key = buffer.toString()
                    buffer.setLength(0)
                }
                character == ';' -> commit()
                else -> buffer.append(character)
            }
        }
        commit()
        return fields
    }

    /**
     * Percent-decodes one query component, leaving `+` and a stray `%` exactly as they were.
     *
     * `URLDecoder` implements `application/x-www-form-urlencoded`, where `+` stands for a space.
     * A Carbit provisioning QR is a plain query string, not a submitted form: a passphrase
     * containing a literal `+` was saved with a space in its place, and every join then failed
     * association with nothing in the log to say why. An unescaped `%` made `URLDecoder` throw,
     * which rejected the whole QR - passing the byte through beats refusing to pair at all.
     * Percent-escapes are still decoded, so `%2B` remains a `+` and `%20` remains a space.
     */
    private fun decode(value: String): String {
        if (!value.contains('%')) return value
        val decoded = StringBuilder(value.length)
        val escaped = ByteArrayOutputStream()

        // Consecutive escapes are one UTF-8 sequence: they have to be decoded together, so the
        // bytes are only turned into text once a literal character (or the end) interrupts them.
        fun flushEscaped() {
            if (escaped.size() == 0) return
            decoded.append(String(escaped.toByteArray(), StandardCharsets.UTF_8))
            escaped.reset()
        }

        var index = 0
        while (index < value.length) {
            val byte = if (value[index] == '%') hexByteAt(value, index + 1) else null
            if (byte == null) {
                flushEscaped()
                decoded.append(value[index])
                index++
            } else {
                escaped.write(byte)
                index += 3
            }
        }
        flushEscaped()
        return decoded.toString()
    }

    /** The byte spelled by the two hex digits at [start], or null if they are not two hex digits. */
    private fun hexByteAt(value: String, start: Int): Int? {
        if (start + 1 >= value.length) return null
        val high = Character.digit(value[start], 16)
        val low = Character.digit(value[start + 1], 16)
        if (high < 0 || low < 0) return null
        return (high shl 4) or low
    }

    /** Authority host of a URL [URI] refused to parse, so an off-spec QR can still be vouched for. */
    private fun hostOf(rawValue: String): String? {
        val authority = rawValue.substringAfter("://", missingDelimiterValue = "")
            .takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@').substringBefore(':').takeIf(String::isNotEmpty)
    }

    /**
     * Provisioning domains MOTO-HUB has seen serve a real pairing code. Corroboration only — an
     * absent match costs a confirmation dialog, never the pairing (see [TBoxQrOrigin]), so this
     * list never needs to be complete.
     */
    private val KNOWN_PROVISIONING_DOMAINS = listOf(
        "carbit.com",
        "carbit.com.cn",
        // Moto Morini / MotoFun serves the dialect below from its own domain.
        "motomorini.com"
    )

    /** Hosts the ThinkerRide (KOVE) pairing code has been seen served from. */
    private val THINKER_RIDE_DOMAINS = listOf("thinkerride.com")

    private fun isKnownProvisioningHost(host: String): Boolean =
        KNOWN_PROVISIONING_DOMAINS.any { host == it || host.endsWith(".$it") }

    /** `CARBIT` followed by exactly twelve hex digits, and nothing else in the code. */
    private val CARBIT_TOKEN = Regex("""CARBIT([0-9A-Fa-f]{12})""", RegexOption.IGNORE_CASE)

    /** `Wifi=<ssid>`, where the SSID runs up to the `#` that introduces the password. */
    private val MOTO_FUN_WIFI = Regex("""(?:^|[?&])wifi=([^&#\s]+)""", RegexOption.IGNORE_CASE)
    private val MOTO_FUN_MACHINE_ID = Regex("""machineid=([^&#\s]+)""", RegexOption.IGNORE_CASE)
    private val MOTO_FUN_PRODUCT_ID = Regex("""productid=([^&#\s]+)""", RegexOption.IGNORE_CASE)

    /**
     * What to try when the remedy is "scan the other code on the dash".
     *
     * Dashes that print two codes label one for Android and one for iPhone/CarPlay, and on the
     * Carbit/EasyConn family it is repeatedly the *iPhone* one that carries the credentials. What
     * the Android-labelled code holds instead has never been captured - riders scan it, get
     * nothing usable, and the thread ends once someone tells them to try the other one. Confirmed
     * that way on Benelli, CFMOTO, QJ-Motor and Voge dashboards through August 2026.
     *
     * Said explicitly because nobody guesses it: an Android rider has no reason to scan the code
     * marked for iPhone, and every rider who got there was told by someone in the community.
     */
    private const val TRY_THE_IOS_CODE =
        " If the dash shows two codes, use the one marked for iPhone / CarPlay - despite the " +
            "label, it pairs Android too."

    /**
     * What to add when the dash asked the phone to host, but may still host an AP of its own.
     *
     * The wording deliberately differs from [TRY_THE_IOS_CODE]: there the remedy is to scan the
     * other code, here it is to change what the dash is doing. Scanning the iPhone code would give
     * the same credentials again - what the rider needs is that screen's radio.
     */
    private const val OR_THE_IOS_ACCESS_POINT =
        " If the dash also offers iPhone / CarPlay, picking that turns on an access point of its " +
            "own - joining it is easier, and it pairs Android too."

    /**
     * The QR decoded cleanly but carries no credentials. Naming the actual content is what lets a
     * rider recover on their own: the dash prints several codes and only one of them pairs, so
     * "unreadable" sends them polishing the screen instead of changing screens.
     */
    private fun describeUnusableCode(rawValue: String): String {
        val vin = rawValue.contains("vin:", ignoreCase = true)
        return when {
            vin && (
                rawValue.contains("color:", ignoreCase = true) ||
                    rawValue.contains("engine:", ignoreCase = true) ||
                    rawValue.startsWith("code:", ignoreCase = true)
                ) ->
                "That is the vehicle information code (VIN, engine, colour), not the Wi-Fi " +
                    "pairing code. Open the phone-connection screen on the dash and scan the " +
                    "code shown there." + TRY_THE_IOS_CODE

            // No iPhone hint here: this dash serves the MotoFun dialect from one screen, and the
            // pairing code is the only code on it. Sending a Moto Morini rider hunting for a
            // second code would replace one wrong screen with a search for a screen that has none.
            rawValue.contains("motomorini", ignoreCase = true) ||
                rawValue.contains("motofun", ignoreCase = true) ->
                "This Moto Morini code carries no Wifi= field, so it is not the pairing code. " +
                    "Open the phone-link / MotoFun screen on the dash and scan the code there."

            // A provisioning-domain URL that carries no credentials is not a rider mistake: some
            // dashes pair the other way round. They join a hotspot the PHONE hosts, under an SSID
            // and password the dash itself prints, so their QR has nothing to hand over and the
            // generic "scan the pairing code instead" advice sends the rider hunting for a code
            // that does not exist. Confirmed on a tester's dash 2026-08-02, whose screen reads
            // "Please open Android hotspot and set the following parameters".
            //
            // The iPhone hint used to be withheld here, on the grounds that this code is complete
            // as it is. That holds only for a dash that can ONLY be a client: support case
            // FD79-4FFB is a Benelli TRK 702X that does both, and the rider spent nine days on the
            // hotspot before someone told him to pick iPhone/CarPlay on the dash, which raised an
            // access point of its own. Hosting the hotspot stays the first instruction, because it
            // is what the scanned code actually asks for; the access point is the easier road out
            // where the dash offers one, so it is named second rather than not at all.
            hostOf(rawValue)?.lowercase()?.let(::isKnownProvisioningHost) == true ->
                "This dash connects the other way round: it joins a hotspot your phone creates, " +
                    "so its code carries no network to join. On the dash, read the Ssid and " +
                    "Password it shows, set your Android hotspot to exactly those values, turn it " +
                    "on, and the dash will connect by itself." + OR_THE_IOS_ACCESS_POINT

            rawValue.startsWith("http", ignoreCase = true) ->
                "That is a web address with no network credentials in it. Scan the dash pairing " +
                    "code instead (MotoPlay / EasyConnect / MotoFun)." + TRY_THE_IOS_CODE

            else -> "The QR code does not carry a T-Box network name." + TRY_THE_IOS_CODE
        }
    }
}
