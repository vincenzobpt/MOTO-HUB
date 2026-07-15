# Android Client

MOTO-HUB client for Android 14/API 34 and later.

## Local Build

Use the JDK bundled with Android Studio and an Android SDK with API 36:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The system JDK 26 is not compatible with the current Gradle/AGP combination.

The public source intentionally excludes the Android Auto identity files. A normal build supports mirroring and T-Box streaming; Android Auto is enabled only when the private files are supplied through the root `tooling/private/android-auto/` directory and the build is invoked with `-PincludeAndroidAutoIdentity=true`.

For a private Android Auto APK:

```bash
./gradlew -PincludeAndroidAutoIdentity=true exportPrivateAndroidAutoApk
```

The APK is exported as `artifacts/MOTO-HUB-0.8.0-37-android-auto-private.apk`. Do not publish that APK or the identity files. `./gradlew exportPublicApk` creates the installable public preview APK without Android Auto identity files.

## Transport Status

`hudlib.aar` is generated from the GPL-3.0 fork and included in the module.
After discovery, the UI enables the Android picker and the foreground service
starts the EasyConn handshake, AVC encoder and frame delivery. Local Wi-Fi and
transport remain separated from the service through `TBoxTransport` and its
session registry.

Primary pairing uses the EasyConn QR code: hosts `carbit.com` and
`carbit.com.cn` with an `ssid` parameter are accepted; passwords and metadata
are not written to logs. SSID and password are remembered between launches;
the password is encrypted with AES-GCM using a non-exportable Android Keystore
key. Manual entry remains available as a fallback and updates the same
persistent profile.

The network follows the reference app's model: a `WifiNetworkSuggestion` from
the QR code and a callback that confirms a local `192.168.0.x` address before
discovery. Host, port and package are passed to the Go session with
`setECHost()`, and the handshake runs on the primary T-Box Wi-Fi network.
TFT configuration events, fatal errors and network loss stop the projection
session through one idempotent cleanup path.

The optional `PHONE DISPLAY` mode darkens the physical panel five seconds after
startup while keeping it on so that `MediaProjection` is not terminated. It
requires Android's "draw over other apps" consent. The foreground notification
can restore the panel or darken it again; the Power button locks the phone and
still terminates projection according to Android policy.

`NETWORK DIAGNOSTICS` runs four local checks without enabling a VPN:
connection to the discovered T-Box, cellular TCP through `SocketFactory`,
cellular TCP through `Network.bindSocket()` and UDP DNS through `bindSocket()`.
The report is the technical gate before implementing a VPN bridge for other
apps; it does not change the default network, SSID or password.

For a QR photograph or a code shown on another display, use `IMPORT QR PHOTO`:
the Android Photo Picker passes the original file to ML Kit, avoiding autofocus
and moire caused by recapturing it with the camera.
