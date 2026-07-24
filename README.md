# MOTO-HUB

> [!IMPORTANT]
> [**JOIN US ON DISCORD TO RECEIVE SUPPORT, HELP THE COMMUNITY AND FOLLOW THE APP DEVELOPMENT**](https://discord.gg/uCUK55nJ5v)

> [!WARNING]
> **MOTO-HUB is an experimental proof-of-concept, not a production-grade product.** It has been built and tested with a CFMOTO **700MT-ADV** dashboard and **OnePlus 13 / Galaxy Z Fold4** phones. Behavior may be unstable, require a retry, or differ on other motorcycles, T-Box firmware versions, or phones. Do not depend on it as your only source of critical navigation information. Plan your route before riding, and use the software at your own risk.

<p align="center">
  <img src="8.png" alt="Full Android Auto (Waze) projected to the motorcycle TFT" width="220"><br>
  <sub>Full Android Auto on the TFT</sub>
</p>

MOTO-HUB is an Android 14+ application for connecting a phone to a compatible motorcycle T-Box and projecting content to the motorcycle TFT display.

The app supports Android Auto, whole-screen mirroring, Android's app-specific screen sharing flow, and local diagnostics. It is designed as a personal, local-first project and is not affiliated with or endorsed by CFMOTO, EasyConn, MotoPlay, Google, or any other vehicle or software vendor.

## Download The Latest APK

For the latest manually published Android package, visit the [latest MOTO-HUB release](https://github.com/vincenzobpt/MOTO-HUB/releases/latest).

On the release page, expand **Assets** and download the file ending in `.apk`. Do not download **Source code (zip)** or **Source code (tar.gz)**: those files contain the project source, not an installable application. Android may ask you to allow installation from this source the first time; this is a normal Android security prompt for APKs installed outside Google Play.

## Permissions And Privacy

MOTO-HUB is a local-first app. The permissions below are used to connect to the motorcycle, scan its pairing QR code, keep an active projection running, and provide user controls. The app does not require an account and does not upload screen content.

### Permissions requested while using the app

| Permission | When it is requested | Why it is needed | What it does not mean |
| --- | --- | --- | --- |
| **Camera** | When you choose live QR scanning | Reads the T-Box QR code shown on the motorcycle TFT | The camera is not needed for normal streaming, and camera frames are not intentionally recorded or uploaded |
| **Nearby devices / Wi-Fi** | When you connect to a saved or newly paired motorcycle | Finds and requests the motorcycle's Wi-Fi access point, then communicates with the local T-Box | It is not Bluetooth tracking and does not grant access to unrelated nearby devices |
| **Location** | Requested while connecting to the T-Box | Supports Android Wi-Fi discovery | MOTO-HUB does not upload location data |
| **Notifications** | When starting projection on Android 13 and newer | Shows the required foreground-service status and gives you visible controls to stop or manage an active session | It is not remote telemetry; notifications stay on the phone |

### System confirmations and optional access

| Access | When it is used | Why it is needed |
| --- | --- | --- |
| **Screen sharing confirmation** | Every time you start phone mirroring or app-specific sharing | Android requires the user to approve capture of the whole display or a selected app. MOTO-HUB cannot approve this silently |
| **Display over other apps** *(optional)* | Only if you enable phone-display dimming during projection | Places a non-touchable overlay over the phone display to reduce brightness while the TFT continues receiving the projection. The overlay can be removed from MOTO-HUB or by stopping the session |

### Technical permissions granted by Android

The app also declares network and foreground-service permissions required by Android for this workflow: Internet and network-state access, Wi-Fi state/change access, Wi-Fi multicast discovery, foreground services for media projection, connected devices and location, and a wake lock. These maintain the local T-Box connection and projection; they are not separate user accounts or remote services.

The Android Auto receiver also declares package visibility for Android Auto and Google Play services so MOTO-HUB can detect and launch the installed Android Auto component. This does not give MOTO-HUB access to Google account data.

### If a permission is denied

The app should continue to open normally. Only the related feature is unavailable: without Camera, use QR import from a photo or an already saved motorcycle; without Nearby Wi-Fi or Location, the T-Box connection cannot be discovered; without Notifications, projection cannot be kept as a managed foreground session; without screen-capture approval, mirroring cannot start. Optional display dimming simply remains disabled unless overlay access is granted.

## What It Does

- Pair with a motorcycle T-Box by scanning its QR code.
- Store multiple motorcycle profiles and select the active motorcycle.
- Store a private motorcycle photo and use it throughout the app UI.
- Connect to the T-Box Wi-Fi access point without requiring manual SSID entry.
- Discover the EasyConn service and establish the T-Box session.
- Mirror the entire phone screen or a single Android app.
- Start Android Auto through an embedded local head-unit receiver.
- Choose the Android Auto TFT layout per motorcycle:
  - `FIT`: preserve the complete image and use black bars when necessary.
  - `STRETCH`: use the complete available TFT area with geometric stretching.
  - `CROP`: use the complete available TFT area without stretching and crop edges when necessary.
- Calibrate per-motorcycle TFT safe margins so Android Auto video and touch stay inside the projection area not occupied by native motorcycle UI.
- Keep the phone preview available for Android Auto touch control.
- Select Smoother, Balanced, Sharper, or adaptive power behavior for the next stream.
- Override Android Auto with landscape or portrait SD/HD source resolutions, or keep automatic selection.
- Optionally connect to the saved motorcycle when MOTO-HUB opens.
- Optionally recover or seamlessly resume a stalled or dropped Android Auto TFT stream when the T-Box returns.
- Show persistent diagnostics and share application logs as an exported file for troubleshooting.
- Check GitHub releases and pre-releases from inside the app, showing release notes before installing a newer APK.

## Current Status

The current Android client is version `0.9.0-beta.10-build.82-r1` (`82`) and targets Android 14/API 34 and newer.

This build has been tested end-to-end for mirroring and Android Auto on a OnePlus 13 and a CFMOTO 700MT-ADV T-Box. Compatibility with other phones, motorcycle models, T-Box firmware versions, and Android Auto versions is not guaranteed and must be validated separately.

Full Android Auto and diagnostics are implemented, but every motorcycle model and T-Box firmware still requires explicit validation before it can be considered supported.

This is still an experimental project. Do not rely on it as the only navigation or safety system, and configure navigation while stationary.

## Repository Layout

```text
MOTO-HUB/
├── apps/android/       Android application and projection pipelines
├── packages/contracts/ Future platform-neutral contracts
├── tooling/            AAR build metadata and reproducibility helpers
├── documentation/      Architecture, decisions, security, testing, and roadmap
└── README.md           Project overview and setup instructions
```

The public repository contains only the MOTO-HUB source, documentation, build metadata, and non-sensitive required artifacts. External projects are referenced by their public URLs below and are not vendored into this repository.

## Build Requirements

- Android Studio with its bundled JDK 17.
- Android SDK platform/API 36.
- A physical Android device. An emulator cannot reproduce the motorcycle Wi-Fi, camera, NSD, or Android Auto behavior.
- A generated `hudlib.aar` from the MOTO-HUB ridedaemon fork.

From `apps/android/`:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
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
- Observed T-Box capability snapshots, including model/profile hints where available.

Existing single-profile data is migrated automatically when the app is upgraded.

### Projection Modes

`Mirroring` uses Android `MediaProjection` and supports either the complete phone display or an app selected through Android's system picker.

`Android Auto` runs through a local Android Auto Projection receiver. The decoded Android Auto video is composited, encoded as H.264, and sent to the T-Box through the ridedaemon transport. The compositor supports `FIT`, `STRETCH`, and `CROP` against the usable TFT projection area. When Android Auto declares internal letterbox margins, `STRETCH` uses the active Android Auto content rather than stretching black bars.

### Projection Settings

The global Settings page controls the next stream. `Balanced` preserves the existing 2.5 Mbps base bitrate; `Smoother` uses 70% and `Sharper` uses 160% of the negotiated base. Adaptive power mode can lower output pressure when thermal or link conditions degrade. Android Auto `Auto` preserves dynamic orientation selection from learned T-Box geometry. Manual source overrides are 800 x 480, 1280 x 720, 720 x 1280, and 1080 x 1920. The T-Box output canvas is still negotiated at runtime and is not replaced by the Android Auto source resolution.

Auto-connect requests the saved motorcycle network and discovers EasyConn on app launch and after deliberate projection stops when enabled. The optional watchdog monitors outgoing TFT frame progress and rebuilds the T-Box network, discovery, handshake, and encoder path after a post-start stall while keeping the local Android Auto receiver alive. Seamless resume can park a projection across a longer T-Box interruption and resume when the motorcycle network returns.

### Network Behavior

The T-Box Wi-Fi network is a local display transport and may not provide Internet access. MOTO-HUB requests the T-Box network explicitly and keeps the T-Box transport separate from normal phone connectivity where Android allows it. OEM network behavior can vary, especially on OnePlus devices.

## Documentation

- [Architecture](documentation/ARCHITECTURE.md)
- [Android implementation](documentation/ANDROID_IMPLEMENTATION.md)
- [Reference analysis](documentation/REFERENCE_ANALYSIS.md)
- [T-Box streaming contract](documentation/TBOX_STREAMING_CONTRACT.md)
- [Security, privacy, and licensing](documentation/SECURITY_AND_PRIVACY.md)
- [Test strategy](documentation/TEST_STRATEGY.md)
- [Roadmap](documentation/ROADMAP.md)
- [Risk register](documentation/RISK_REGISTER.md)
- [OpenCfMoto comparative audit](documentation/OPEN_CFMOTO_COMPARATIVE_AUDIT.md)
- [Public release process](documentation/PUBLIC_RELEASE.md)
- [Architecture decisions](documentation/decisions/README.md)

## Technical Sources And Attribution

MOTO-HUB exists because of the reverse-engineering, prototyping, and documentation work of several independent open-source projects. **Thank you to everyone below — please go look at their repositories too.** Each one explores this space in its own way and is worth a look independently of MOTO-HUB.

The links are references and attribution, not claims of endorsement by those projects. License information was checked directly against each repository on **24 July 2026** and is accurate as of that date only — any of these projects can change its license at any time, so re-check the source repository yourself before relying on this table for anything license-sensitive.

| Project | License (checked 24 Jul 2026) | Why MOTO-HUB uses it | MOTO-HUB feature |
|---|---|---|---|
| [vincenzobpt/ridedaemon-lib](https://github.com/vincenzobpt/ridedaemon-lib) | GPL-3.0 | MOTO-HUB's own fork, rebuilt into the Android `hudlib.aar` binding used at runtime | Every T-Box connection: EasyConn discovery, handshake, and H.264 frame delivery |
| [charliecharlieO-o/ridedaemon-lib](https://github.com/charliecharlieO-o/ridedaemon-lib) | GPL-3.0 | Upstream Go protocol implementation the fork above is based on | Same underlying transport protocol |
| [charliecharlieO-o/ridedaemon-android](https://github.com/charliecharlieO-o/ridedaemon-android) | GPL-3.0 | Reference Android integration studied to understand QR pairing, Wi-Fi provisioning, network discovery, and encoder setup | Motorcycle QR pairing and the screen-mirroring pipeline |
| [andreknieriem/headunit-revived](https://github.com/andreknieriem/headunit-revived) | AGPL-3.0 | Technique directly ported for the loopback "self-mode" Android Auto trigger and the local receiver that decodes Android Auto's video | The Android Auto feature end to end |
| [BojanJ/open-cfmoto](https://github.com/BojanJ/open-cfmoto) | No license file (all rights reserved by default) | Original CFMoto T-Box research: how the Android Auto receiver flow, self-mode startup, and touch input behave on this hardware | Android Auto and general T-Box protocol understanding |
| [zanderp/open-cfmoto](https://github.com/zanderp/open-cfmoto) | AGPL-3.0 | Fork studied for bitrate selection, Android Auto source-resolution profiles, and stream-recovery behavior | Video quality settings and Android Auto reconnection |
| [ionutradu252/open-cflink](https://github.com/ionutradu252/open-cflink) | No license file (all rights reserved by default) | Fork of BojanJ's project, cited (with OpenMoto) as a reference for the T-Box's diagnostic EasyConn port | Network diagnostics (T-Box port scanning) |
| [NegligentNarwhal/openmoto](https://github.com/NegligentNarwhal/openmoto) | AGPL-3.0 | Fork of dcoletto/open-cfmoto, same diagnostic-port reference as OpenCfLink above | Network diagnostics (T-Box port scanning) |

Several files under the Android Auto receiver are directly adapted from headunit-revived's AGPL-3.0 source, credited by name in the file's own header comment. MOTO-HUB's own project license has not yet been finalized for public distribution — see [Licensing And Publication Gate](#licensing-and-publication-gate) below before treating anything in this README as a final legal statement.

### Vendor and platform references

- [EasyConn](https://www.easyconn.net/) - vendor context for the T-Box ecosystem.
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection) - Android screen capture API.
- [Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) - hardware video encoding and decoding API.
- [Android Wi-Fi network requests](https://developer.android.com/develop/connectivity/wifi/wifi-suggest) - Android Wi-Fi provisioning APIs.

## Licensing And Publication Gate

This section is intentionally explicit because the project combines original MOTO-HUB code with external components and research.

- `ridedaemon-lib` and the reference Android project are distributed under GPL-3.0 according to their repositories and license files.
- The generated `hudlib.aar` is derived from the GPL-3.0-only ridedaemon fork. A public distribution containing it must include the corresponding source and comply with the applicable GPL obligations.
- The `open-cfmoto` project used for research does not contain a license file in the reviewed source snapshot. No code from that project should be published as part of MOTO-HUB until its redistribution terms and attribution requirements are verified.
- The final MOTO-HUB license and repository notices must be selected before broader public distribution.
- CFMOTO, EasyConn, MotoPlay, Android Auto, Google, and related names remain the property of their respective owners. MOTO-HUB is an independent project and must not imply official support.

This README is a publication draft, not a legal opinion. The final repository should include the exact license texts and notices required by every distributed component.

## Privacy Notes

MOTO-HUB is designed to operate without an account or proprietary telemetry service. It handles screen content, T-Box credentials, and diagnostic data on the phone. Wi-Fi passwords are encrypted with Android Keystore. Screen frames are processed in memory for the active projection and are not intentionally recorded to disk.

Review [Security and Privacy](documentation/SECURITY_AND_PRIVACY.md) before distributing an APK outside personal use.

The public source does not include the Android Auto identity or APK-signing keystore. APKs attached to official MOTO-HUB releases are complete runtime builds and include Android Auto support.

## Disclaimer

Use MOTO-HUB only while parked during setup and testing. The project is provided for experimentation with personally owned hardware and without any safety guarantee or vendor support.
