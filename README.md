# MOTO-HUB

> [!IMPORTANT]
> [**JOIN US ON DISCORD TO RECEIVE SUPPORT, HELP THE COMMUNITY AND FOLLOW THE APP DEVELOPMENT**](https://discord.gg/uCUK55nJ5v)

>[**You can also add the FREE ADVANCED complementary app**](https://github.com/vincenzobpt/MOTO-HUB-PRO-releases)

> [!WARNING]
> **MOTO-HUB is an experimental proof-of-concept, not a production-grade product.** It has been built and tested with a CFMOTO **700MT-ADV** dashboard and **OnePlus 13 / Galaxy Z Fold4** phones. Behavior may be unstable, require a retry, or differ on other motorcycles, T-Box firmware versions, or phones. Do not depend on it as your only source of critical navigation information. Plan your route before riding, and use the software at your own risk.

<table cellpadding="0" cellspacing="0" border="0">
  <tr>
    <td align="center" width="100%">
      <img src="8.png" alt="Full Android Auto (Waze) projected to the motorcycle TFT" width="560"><br>
      <sub>MOTO-HUB &mdash; full Android Auto on the motorcycle TFT</sub>
    </td>
  </tr>
</table>

MOTO-HUB is an Android 14+ application for connecting a phone to a compatible motorcycle T-Box and projecting content to the motorcycle TFT display.

MOTO-HUB projects **Android Auto** and **phone screen mirroring** (whole screen or a single app) to the TFT, drives Android Auto from the **motorcycle's own handlebar buttons**, and provides local diagnostics. It is designed as a personal, local-first project and is not affiliated with or endorsed by CFMOTO, EasyConn, MotoPlay, Google, or any other vehicle or software vendor.

## MOTO-HUB And MOTO-HUB ADVANCED

MOTO-HUB is split into two applications. **This repository is MOTO-HUB (Core)** — the app that owns the T-Box connection and the Android Auto receiver, and the only one you need to project.

| | MOTO-HUB (this repository) | [MOTO-HUB ADVANCED](https://github.com/vincenzobpt/MOTO-HUB-PRO-releases) |
| --- | --- | --- |
| T-Box pairing, garage, connection | ✅ | uses Core over IPC |
| Screen mirroring (full screen or one app) | ✅ | — |
| Android Auto on the TFT | ✅ | — |
| Handlebar button control | ✅ | — |
| USB external display (AOA) | ✅ | — |
| Diagnostics, logs, in-app updates | ✅ | ✅ |
| Ride Dashboard (native GPS scene on the TFT) | — | ✅ |
| Navigation (search, routing, route preview) | — | ✅ |
| Trip recording, history, GPX export | — | ✅ |

ADVANCED is a free companion app: it is installed alongside MOTO-HUB and reaches this repository's T-Box transport and Android Auto receiver through a documented Binder IPC boundary. **ADVANCED requires MOTO-HUB to be installed; MOTO-HUB does not require ADVANCED.** Install both to get everything.

<table cellpadding="0" cellspacing="0" border="0">
  <tr>
    <td align="center" width="33%">
      <img src="1.png" alt="Ride Dashboard with GPS speed, live map, and trip stats" width="190"><br>
      <sub>ADVANCED &mdash; Ride Dashboard</sub>
    </td>
    <td align="center" width="33%">
      <img src="5.png" alt="Navigation route preview with weather, curvy roads, and route type" width="190"><br>
      <sub>ADVANCED &mdash; Navigation preview</sub>
    </td>
    <td align="center" width="33%">
      <img src="6.png" alt="Android Auto embedded in the Ride Dashboard map panel" width="190"><br>
      <sub>ADVANCED &mdash; embedded Android Auto</sub>
    </td>
  </tr>
</table>

## Download The Latest APK

For the latest manually published Android package, visit the [latest MOTO-HUB release](https://github.com/vincenzobpt/MOTO-HUB/releases/latest).

On the release page, expand **Assets** and download the file ending in `.apk`. Do not download **Source code (zip)** or **Source code (tar.gz)**: those files contain the project source, not an installable application. Android may ask you to allow installation from this source the first time; this is a normal Android security prompt for APKs installed outside Google Play.

## Permissions And Privacy

MOTO-HUB is a local-first app. The permissions below are used to connect to the motorcycle, scan its pairing QR code, keep an active projection running, and give the rider controls. The app does not require an account and does not upload screen content.

### Permissions requested while using the app

| Permission | When it is requested | Why it is needed | What it does not mean |
| --- | --- | --- | --- |
| **Camera** | When you choose live QR scanning | Reads the T-Box QR code shown on the motorcycle TFT | The camera is not needed for normal streaming, and camera frames are not intentionally recorded or uploaded |
| **Nearby devices / Wi-Fi** | When you connect to a saved or newly paired motorcycle | Finds and requests the motorcycle's Wi-Fi access point, then communicates with the local T-Box | It is not Bluetooth tracking and does not grant access to unrelated nearby devices |
| **Location** | Alongside the Wi-Fi permission, when connecting to the T-Box | Android requires location permission for Wi-Fi discovery and network requests | MOTO-HUB does not track or record the rider's position: this app has no GPS features, requests no location updates, and sends no coordinates anywhere |
| **Bluetooth** | Only when you enable handlebar button control | Identifies the connected dashboard so its button presses can be told apart from a headset's | It does not scan for or connect to other Bluetooth devices |
| **Microphone** | Only when you use the Android Auto voice assistant | Carries your voice to Android Auto while it is projecting | Audio is passed to Android Auto for the active request and is not recorded to disk or uploaded by MOTO-HUB |
| **Notifications** | When starting projection on Android 13 and newer | Shows the required foreground-service status and gives you visible controls to stop or manage an active session | It is not remote telemetry; notifications stay on the phone |

### System confirmations and optional access

| Access | When it is used | Why it is needed |
| --- | --- | --- |
| **Screen sharing confirmation** | Every time you start phone mirroring, app-specific sharing, or the USB external display | Android requires the user to approve capture of the whole display or a selected app. MOTO-HUB cannot approve this silently |
| **Install unknown apps** *(optional)* | Only if you install an update offered by the in-app release check | Android requires this to hand a downloaded APK to the package installer. Declining it simply means updating manually from the releases page |
| **Display over other apps** *(optional)* | Phone-display dimming during projection, and starting navigation from a handlebar button while another app is in front | Places a non-touchable overlay over the phone display to reduce brightness while the TFT continues receiving the projection, and lets a background button press launch navigation |

### Technical permissions granted by Android

The app also declares network and foreground-service permissions required by Android for this workflow: Internet and network-state access, Wi-Fi state/change access, Wi-Fi multicast discovery, foreground services for media projection, connected devices and microphone, and a wake lock. These maintain the local T-Box connection and the projection; they are not separate user accounts or remote services.

The Android Auto receiver also declares package visibility for Android Auto and Google Play services so MOTO-HUB can detect and launch the installed Android Auto component. This does not give MOTO-HUB access to Google account data.

### If a permission is denied

The app should continue to open normally. Only the related feature is unavailable: without Camera, use QR import from a photo, manual pairing, or an already saved motorcycle; without Nearby Wi-Fi or Location, the T-Box connection cannot be discovered; without Notifications, projection cannot be kept as a managed foreground session; without screen-capture approval, mirroring cannot start; without Bluetooth, handlebar buttons cannot be identified; without Microphone, the Android Auto assistant has no voice input. Optional display dimming simply remains disabled unless overlay access is granted.

## What It Does

- Pair with a motorcycle T-Box by scanning its QR code, importing a photo of it, or entering the network manually.
- Read non-CFMOTO QR dialects where they have been observed, and warn before trusting an unverified one.
- Store multiple motorcycle profiles and select the active motorcycle.
- Store a private motorcycle photo and use it throughout the app UI.
- Connect to the T-Box Wi-Fi access point without requiring manual SSID entry, with a separate Wi-Fi Direct path for dashboards that advertise a `DIRECT-` network.
- Discover the EasyConn service and establish the T-Box session.
- Mirror the entire phone screen or a single Android app.
- Start Android Auto through an embedded local head-unit receiver, including on Android Auto versions that no longer accept a direct start request.
- Drive Android Auto from the motorcycle's handlebar buttons, after a short guided calibration, with per-motorcycle mappings for press, double press and hold.
- Control music volume, jump to a saved destination, or open the assistant from the handlebar without touching the phone.
- Stream the phone screen to a USB (AOA) external display, independently of the T-Box.
- Choose the Android Auto TFT layout per motorcycle:
  - `FIT`: preserve the complete image and use black bars when necessary.
  - `STRETCH`: use the complete available TFT area with geometric stretching.
  - `CROP`: use the complete available TFT area without stretching and crop edges when necessary.
- Calibrate per-motorcycle TFT safe margins so Android Auto video and touch stay inside the projection area not occupied by native motorcycle UI.
- Let Android Auto lay out at the dashboard's real shape instead of a letterboxed band inside it.
- Keep the phone preview available for Android Auto touch control, or disable the touchscreen entirely and ride with focus and handlebar controls.
- Select Smoother, Balanced or Sharper image detail, and a Smooth/Balanced/Saver/adaptive power behavior for the next stream.
- Override Android Auto with landscape or portrait SD/HD source resolutions, or keep automatic selection.
- Optionally connect to the saved motorcycle when MOTO-HUB opens.
- Optionally recover or seamlessly resume a stalled or dropped TFT stream when the T-Box returns.
- Show persistent diagnostics, run network tests, and share application logs as an exported file for troubleshooting.
- Check GitHub releases and pre-releases from inside the app, showing release notes before installing a newer APK.
- Run in English, Italian, Portuguese or Korean, or follow the phone language.

## Current Status

The current Android client is version `1.1.33` (`127`) and targets Android 14/API 34 and newer.

This build has been tested end-to-end for mirroring and Android Auto on a OnePlus 13 and a CFMOTO 700MT-ADV T-Box. Compatibility with other phones, motorcycle models, T-Box firmware versions, and Android Auto versions is not guaranteed and must be validated separately.

Mirroring, Android Auto, handlebar controls and diagnostics are implemented, but every motorcycle model and T-Box firmware still requires explicit validation before it can be considered supported. The USB external display path is newer and has had less validation than the T-Box path.

This is still an experimental project. Do not rely on it as the only navigation or safety system, and configure navigation while stationary.

## Repository Layout

```text
MOTO-HUB/
├── apps/android/
│   ├── app/                Android application and projection pipelines
│   └── ipc-contract/       AIDL boundary MOTO-HUB ADVANCED connects through
├── packages/contracts/     Future platform-neutral contracts
├── tooling/                AAR build metadata and reproducibility helpers
├── translations/           Source strings and their translations
├── documentation/          Architecture, decisions, security, testing, and roadmap
└── README.md               Project overview and setup instructions
```

The public repository contains only the MOTO-HUB source, documentation, build metadata, and non-sensitive required artifacts. External projects are referenced by their public URLs below and are not vendored into this repository.

## Build Requirements

- **JDK 21.** Gradle 8.13 / AGP 8.12.3 do not run on newer JDKs, and the JDK bundled with current Android Studio versions is too new — point `JAVA_HOME` at a JDK 21 explicitly.
- Android SDK platform/API 36.
- A physical Android device. An emulator cannot reproduce the motorcycle Wi-Fi, camera, NSD, or Android Auto behavior.
- A generated `hudlib.aar` from the MOTO-HUB ridedaemon fork.

From `apps/android/`:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

./gradlew lintDebug testDebugUnitTest assembleDebug
```

The generated Android binding is expected at:

```text
apps/android/app/libs/hudlib.aar
```

To rebuild it, install Go and `gomobile`, then run these commands from the directory that contains the `MOTO-HUB` folder:

```bash
git clone https://github.com/vincenzobpt/ridedaemon-lib ridedaemon-lib
cd ridedaemon-lib
gomobile bind -target=android -androidapi 34 -o ../MOTO-HUB/apps/android/app/libs/hudlib.aar ./hud/api
```

The source commit and AAR checksum must be updated in [`tooling/ridedaemon.lock`](tooling/ridedaemon.lock) whenever the artifact changes.

### Android Auto release builds

The public source intentionally does **not** contain the static Android Auto head-unit identity (`aa_cert` and `aa_identity_data`) or the APK-signing keystore. Maintainer-built release APKs include Android Auto support and require no certificate setup or technical configuration from the user.

A normal source build without those inputs remains usable for pairing, T-Box streaming, mirroring, and diagnostics, but Android Auto reports that its identity is unavailable. This separation keeps private build inputs out of Git history; it does not make identity material embedded in a publicly downloadable APK confidential.

For a local Android Auto build, place the two identity files in `tooling/private/android-auto/` and run:

```bash
./gradlew -PincludeAndroidAutoIdentity=true assembleDebug
```

For a local build without Android Auto identity files, use the default build:

```bash
./gradlew assembleDebug
```

Maintainers can find the complete release process and required GitHub secret names in [`documentation/PUBLIC_RELEASE.md`](documentation/PUBLIC_RELEASE.md).

## Android Features

### Motorcycle Garage

The garage stores multiple motorcycle profiles. Each profile can contain:

- T-Box SSID and encrypted Wi-Fi password.
- QR-provided metadata when available.
- A user-defined display name.
- A private motorcycle photo.
- Android Auto display format: `FIT`, `STRETCH`, or `CROP`.
- TFT safe margins used to exclude motorcycle-owned display regions from Android Auto video and touch.
- Its own handlebar button calibration and mapping.
- Observed T-Box capability snapshots, including model/profile hints where available.

Existing single-profile data is migrated automatically when the app is upgraded.

### Projection Modes

`Mirror` uses Android `MediaProjection` and supports either the complete phone display or an app selected through Android's system picker.

`Auto` runs Android Auto through a local Android Auto Projection receiver. The decoded Android Auto video is composited, encoded as H.264, and sent to the T-Box through the ridedaemon transport. The compositor supports `FIT`, `STRETCH`, and `CROP` against the usable TFT projection area. When Android Auto declares internal letterbox margins, `STRETCH` uses the active Android Auto content rather than stretching black bars. By default MOTO-HUB asks Android Auto to lay out at the dashboard's real shape, so the map fills the panel instead of sitting between black bars; the per-motorcycle TFT safe margins can be advertised instead by switching content insets to `Manual`.

`External` appears only when a USB (AOA) accessory head unit is attached. It captures the phone screen and writes H.264 access units straight to the USB accessory endpoint, completely independently of the T-Box, EasyConn and ridedaemon path.

The Ride Dashboard, Navigation and Trips are not part of this app — they live in [MOTO-HUB ADVANCED](https://github.com/vincenzobpt/MOTO-HUB-PRO-releases), which connects through this app's IPC boundary.

### If Android Auto does not start

Android Auto 17.4 removed the entry points an app could use to ask it to project: the activity it
used is no longer exported and the receiver behind it ships disabled, so MOTO-HUB's request is
refused or silently ignored. This affects every app of this kind, not only MOTO-HUB — the
`headunit-revived` project reports the same in its issue #698. Android Auto 17.2.662634 is verified
working; 17.4.663004 is not.

There is no need to install an older Android Auto. Android Auto can be asked to listen instead,
using the head unit server its own Desktop Head Unit connects to, and MOTO-HUB connects to that:

1. Open the **Android Auto** app, scroll to the bottom and tap **Version** ten times to reveal
   Developer settings.
2. In Developer settings, enable **Add new cars to Android Auto** (older wording: *Unknown
   sources*).
3. Open the **⋮ menu at the top right** of Developer settings and choose **Start head unit
   server**. It lives in that menu, not in the list of settings below it. A notification confirms
   it is running, and it stays running until stopped or the phone restarts.
4. Start Android Auto from MOTO-HUB as usual. MOTO-HUB polls that server and connects on its own.

The same instructions are in the app under `Settings ▸ Android Auto does not start`, and the error
shown when projection fails links to them.

### Handlebar Buttons

CFMOTO dashboards forward their handlebar buttons to the phone as ordinary Bluetooth media commands — a volume change, a play/pause, a next/previous track — and which physical press produces which command differs per motorcycle. MOTO-HUB therefore starts from a **calibration**: the rider performs each press once, and the app learns what that motorcycle actually sends. Nothing is assumed from the model name.

Each calibrated press (`Up`, `Down`, `Left`, `Right`, `Select`, each as press, double press or hold) can then be mapped to an Android Auto action: rotary forward/back, D-pad, Select, Back, Home, the assistant, or one-press navigation to one of three saved destinations. Mapping, calibration and the on/off switch are stored per motorcycle. The feature is on by default, and MOTO-HUB keeps the media session it needs alive so the dashboard keeps sending presses instead of handing them to another app.

Music volume is expressed in **presses**, not steps, because that is how the dashboard rocker behaves.

### Settings

`Video quality` sets image detail against the negotiated base bitrate: `Balanced` is the recommended default, `Smoother` uses 70% and `Sharper` 160%. `Power mode` selects `Auto` (adapt bitrate and frame rate to phone temperature and Wi-Fi quality), `Smooth` (30 FPS), `Balanced` (24 FPS) or `Saver` (20 FPS). `Disable touchscreen` lets the rider use focus and handlebar controls even on a dashboard that reports a touch display.

`Android Auto` selects the source resolution — `Auto` (dynamic orientation from the learned T-Box geometry), 800 x 480, 1280 x 720, 720 x 1280 or 1080 x 1920 — and how content insets are advertised. The T-Box output canvas is still negotiated at runtime and is not replaced by the Android Auto source resolution.

`Connection & automation` holds auto-connect, which requests the saved motorcycle network and discovers EasyConn on app launch and after deliberate projection stops, and the optional recovery watchdog, which monitors outgoing TFT frame progress and rebuilds the T-Box network, discovery, handshake, and encoder path after a post-start stall while keeping the local Android Auto receiver alive. Seamless resume can park a projection across a longer T-Box interruption and resume when the motorcycle network returns.

`Diagnostics` provides network tests (T-Box discovery, Wi-Fi binding, cellular routes), the application log with copy/share/clear, a master switch that stops all logging, and verbose T-Box logging for protocol-level troubleshooting.

The general section holds the app language, launch-time update checks, and seamless resume.

### Network Behavior

The T-Box Wi-Fi network is a local display transport and may not provide Internet access. MOTO-HUB requests the T-Box network explicitly and keeps the T-Box transport separate from normal phone connectivity where Android allows it. OEM network behavior can vary, especially on OnePlus devices.

## Documentation

- [Architecture](documentation/ARCHITECTURE.md)
- [Android implementation](documentation/ANDROID_IMPLEMENTATION.md)
- [Reference analysis](documentation/REFERENCE_ANALYSIS.md)
- [T-Box streaming contract](documentation/TBOX_STREAMING_CONTRACT.md)
- [Dynamic Android Auto profile](documentation/DYNAMIC_ANDROID_AUTO_PROFILE.md)
- [Security, privacy, and licensing](documentation/SECURITY_AND_PRIVACY.md)
- [Test strategy](documentation/TEST_STRATEGY.md)
- [Roadmap](documentation/ROADMAP.md)
- [Risk register](documentation/RISK_REGISTER.md)
- [OpenCfMoto comparative audit](documentation/OPEN_CFMOTO_COMPARATIVE_AUDIT.md)
- [Projection settings](documentation/PROJECTION_SETTINGS.md)
- [Public release process](documentation/PUBLIC_RELEASE.md)
- [Architecture decisions](documentation/decisions/README.md)

The [Ride Dashboard](documentation/RIDE_DASHBOARD.md), [Navigation](documentation/NAVIGATION.md) and [Trip recording](documentation/TRIP_RECORDING.md) documents describe features that now ship in MOTO-HUB ADVANCED. They are kept here because they were written against this repository's history.

## Technical Sources And Attribution

MOTO-HUB was developed using the following public projects as technical sources. The links below are references and attribution; they are not claims of endorsement.

### Ridedaemon library fork

- [vincenzobpt/ridedaemon-lib](https://github.com/vincenzobpt/ridedaemon-lib) - the fork used to generate the Android `hudlib.aar` binding.
- [charliecharlieO-o/ridedaemon-lib](https://github.com/charliecharlieO-o/ridedaemon-lib) - upstream project and protocol implementation.

The library implements EasyConn discovery, the T-Box handshake, control channels, media polling, H.264 framing, and the `gomobile` Android API.

### Reference Android integration

- [charliecharlieO-o/ridedaemon-android](https://github.com/charliecharlieO-o/ridedaemon-android) - reference Android integration used to study QR parsing, Wi-Fi provisioning, NSD discovery, MediaCodec configuration, and frame delivery.

### Android Auto and CFMOTO research

- [BojanJ/open-cfmoto](https://github.com/BojanJ/open-cfmoto) - independent Android Auto and CFMOTO T-Box research used to understand the local Android Auto receiver flow, self-mode startup, touch input, and video pipeline behavior.
- [zanderp/open-cfmoto](https://github.com/zanderp/open-cfmoto) - AGPL-licensed implementation studied for user-selectable bitrate, Android Auto source profiles, startup automation, and stream recovery behavior.

### Navigation and mapping services

These services are not contacted by this app. They are used by MOTO-HUB ADVANCED, which builds on this repository's transport, and are credited here because the documentation kept in this repository describes them.

- [OpenStreetMap](https://www.openstreetmap.org/copyright) - underlying map, address, and routing data, credited to OpenStreetMap contributors.
- [CARTO basemaps](https://carto.com/basemaps) - raster basemap tiles.
- [Photon](https://photon.komoot.io/) (Komoot) - the free geocoding API used to turn a searched address or place name into coordinates.
- [Valhalla](https://github.com/valhalla/valhalla) - the open-source routing engine used for turn-by-turn motorcycle routing.
- [Stadia Maps](https://stadiamaps.com/) - hosts the Valhalla routing API used by default, with the rider's own free API key.
- [FOSSGIS public Valhalla demo server](https://github.com/valhalla/valhalla/discussions/3373) - an optional, rate-limited, keyless routing fallback.
- [Open-Meteo](https://open-meteo.com/) - free weather API used for the route-preview weather-at-arrival estimate.

### Vendor and platform references

- [EasyConn](https://www.easyconn.net/) - vendor context for the T-Box ecosystem.
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection) - Android screen capture API.
- [Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) - hardware video encoding and decoding API.
- [Android Open Accessory](https://developer.android.com/develop/connectivity/usb/accessory) - USB accessory protocol used by the external display mode.
- [Android Wi-Fi network requests](https://developer.android.com/develop/connectivity/wifi/wifi-suggest) - Android Wi-Fi provisioning APIs.

## Licensing And Publication Gate

This section is intentionally explicit because the project combines original MOTO-HUB code with external components and research.

- **MOTO-HUB (this repository) is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0)** — see [`LICENSE`](LICENSE). AGPL-3.0 was chosen because the repository combines GPL-3.0 material (`hudlib.aar`, the T-Box transport) with AGPL-3.0-derived material (`aa/`, the Android Auto receiver technique ported from `headunit-revived`); AGPL-3.0 satisfies both components' obligations for a combined work and additionally covers network-facing use.
- `ridedaemon-lib` and the reference Android project are distributed under AGPL-3.0 according to their repositories and license files; the generated `hudlib.aar` is derived from that fork. Redistributing it (including inside this repository) must comply with the applicable AGPL obligations — the corresponding source must remain available to anyone who interacts with it, including over a network.
- The `open-cfmoto` project used for research does not contain a license file in the reviewed source snapshot. No code from that project should be published as part of MOTO-HUB until its redistribution terms and attribution requirements are verified.
- **MOTO-HUB ADVANCED** is a separate, closed-source companion application maintained in a private repository. It contains no GPL-3.0 or AGPL-3.0 code — it reaches this repository's T-Box transport and Android Auto receiver exclusively through a documented Binder IPC boundary (`apps/android/ipc-contract/`, `IpcBridgeService`), which is why it can be distributed under different terms. ADVANCED requires MOTO-HUB to be installed to function; MOTO-HUB does not require ADVANCED.
- CFMOTO, EasyConn, MotoPlay, Android Auto, Google, and related names remain the property of their respective owners. MOTO-HUB is an independent project and must not imply official support.

This README documents the project's licensing rationale; it is not a substitute for legal advice.

## Privacy Notes

MOTO-HUB is designed to operate without an account or proprietary telemetry service. It handles screen content, T-Box credentials, and diagnostic data on the phone. Wi-Fi passwords are encrypted with Android Keystore. Screen frames are processed in memory for the active projection and are not intentionally recorded to disk.

The only Internet host this app contacts on its own is `api.github.com`, to check for a newer release when you ask it to or when launch-time update checks are enabled. It has no maps, geocoding, routing or weather features, requests no location updates, and sends no ride or position data anywhere. Anything Android Auto itself does over the network is Android Auto's own traffic, under your Google account, not MOTO-HUB's.

Review [Security and Privacy](documentation/SECURITY_AND_PRIVACY.md) before distributing an APK outside personal use.

The public source does not include the Android Auto identity or APK-signing keystore. APKs attached to official MOTO-HUB releases are complete runtime builds and include Android Auto support.

## Disclaimer

Use MOTO-HUB only while parked during setup and testing. The project is provided for experimentation with personally owned hardware and without any safety guarantee or vendor support.
