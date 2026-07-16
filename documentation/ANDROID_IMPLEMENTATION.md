# Android Implementation

Status: proposed technical guide

## Baseline

- Kotlin and Jetpack Compose.
- `minSdk 34` (Android 14), MOTO-HUB requirement.
- `compileSdk 36` and initial `targetSdk 36`; reassess at every release.
- Java/Kotlin target at least 11, preferably 17 if the toolchain and AAR allow
  it.
- One Activity; the session is owned by a non-exported foreground service.
- Coroutines/Flow for application state; callbacks adapted at the boundary.

Single-app sharing is available through the system picker from Android 14 QPR2.
`MediaProjection` continues to support full-display capture on earlier versions.
Official references: [Media projection](https://developer.android.com/media/grow/media-projection)
and [App screen sharing](https://developer.android.com/about/versions/14/features/app-screen-sharing).

## Initial Dependencies

- AndroidX Activity Compose, Lifecycle and Navigation Compose.
- Material 3.
- CameraX and ML Kit barcode scanning for QR onboarding.
- `hudlib.aar` generated from `ridedaemon-lib/hud/api` with `gomobile bind`.
- No additional video library in the MVP: use `MediaCodec`.
- No analytics or crash-reporting SDK until the privacy model is decided.

## Manifest

Expected permissions, to be applied with `maxSdkVersion` and correct runtime
requests:

| Permission/capability | Reason | Notes |
|---|---|---|
| `INTERNET` | local Go/T-Box sockets | does not imply remote Internet |
| `ACCESS_NETWORK_STATE` | observe network and AP loss | always |
| `ACCESS_WIFI_STATE` | Wi-Fi state | always |
| `CHANGE_NETWORK_STATE` | request/bind network | always |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS on older/background devices | keep lock duration minimal |
| `NEARBY_WIFI_DEVICES` | local Wi-Fi on recent Android | runtime, `neverForLocation` when applicable |
| `ACCESS_FINE_LOCATION` | Wi-Fi discovery/QR on earlier releases | limit to APIs that require it |
| `CAMERA` | QR scanning | runtime and scanner screen only |
| `FOREGROUND_SERVICE` | active service | required |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | capture on target 34+ | required |
| `POST_NOTIFICATIONS` | notification on Android 13+ | handle possible denial |

Service:

```xml
<service
    android:name=".session.ProjectionSessionService"
    android:exported="false"
    android:foregroundServiceType="mediaProjection" />
```

Android 16/API 36 introduces opt-in testing of LAN protections; Android
17/API 37 plans enforcement with `ACCESS_LOCAL_NETWORK`. Reassess the
permission plan before raising the target to 37. See the official guide
[Local network permission](https://developer.android.com/privacy-and-security/local-network-permission).

## Correct MediaProjection Sequence

For Android 14+ targets the order is mandatory:

1. The Activity launches `MediaProjectionManager.createScreenCaptureIntent()`.
2. The user chooses an app or screen and confirms.
3. With a positive result, the Activity starts `ProjectionSessionService`.
4. The service immediately calls `startForeground()` with type
   `mediaProjection`.
5. The service calls `getMediaProjection(resultCode, data)`.
6. It registers `MediaProjection.Callback` before creating the virtual display.
7. It creates the encoder, input surface and `VirtualDisplay`.

One grant permits one session. Do not reuse the `Intent`, create multiple
virtual displays from the same token or attempt to resume after `onStop()`.

### Activity -> Service Data

The consent result contains a parcelable `Intent`. Pass it to the service only
to initialize the current session. Do not save it in DataStore, a database or
`SavedStateHandle`. If the process dies, consider the session terminated and
require new consent in the UI.

## ProjectionCapture

Suggested interface:

```kotlin
interface ProjectionCapture {
    suspend fun start(grant: ProjectionGrant, target: CaptureTarget): CaptureInfo
    suspend fun stop(reason: StopReason)
    val events: Flow<CaptureEvent>
}
```

Required behavior:

- register and unregister callbacks;
- release `VirtualDisplay` and `MediaProjection` exactly once;
- handle `onCapturedContentResize()` without implicitly changing encoder
  resolution;
- handle `onCapturedContentVisibilityChanged()` for diagnostics and future
  intelligent frame suspension;
- treat screen lock and system stop as terminal.

### Darkened Phone Display

Android terminates a `MediaProjection` session when the device is locked, so the
Power button cannot be used to continue streaming with the display off. The MVP
instead offers an optional mode that keeps the display on and unlocked and sets
its physical brightness to minimum through a transparent
`TYPE_APPLICATION_OVERLAY` window.

The overlay:

- requires the `SYSTEM_ALERT_WINDOW` system consent once;
- uses `screenBrightness = BRIGHTNESS_OVERRIDE_OFF` and `FLAG_KEEP_SCREEN_ON`;
- does not intercept input or alter pixels sent to the input surface;
- is removed on stop, error and service destruction;
- can be removed immediately from the foreground notification.

Do not permanently modify `Settings.System.SCREEN_BRIGHTNESS`: a crash could
otherwise leave the phone unusable at minimum brightness.

The virtual display uses the runtime area advertised by the connected T-Box.
Both dimensions are aligned down to complete 16-pixel H.264 macroblocks; no
motorcycle model or fixed TFT resolution is assumed. A geometry saved for the
same SSID may recover a missing live event, but an unknown display without a
valid area fails explicitly instead of receiving a wrongly sized stream.

## H.264 Encoder

Starting configuration:

```text
MIME:                  video/avc
Resolution:            runtime T-Box area, aligned to 16-pixel macroblocks
Color format:          COLOR_FormatSurface
Frame rate:            30 fps, fallback 20/15
Bitrate:               2.5 Mbps, test range 2-5 Mbps
I-frame interval:      0 where it produces compatible frequent IDRs
Prepend SPS/PPS:       KEY_PREPEND_HEADER_TO_SYNC_FRAMES = 1
Static-frame repeat:   KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100000 us
B-frame:               disabled through a compatible profile/configuration
Rate control:          CBR if supported and stable
```

Do not assume that every codec interprets `KEY_I_FRAME_INTERVAL = 0` the same
way. During the spike, inspect the bitstream and verify IDR, SPS, PPS, library-
added AUD and absence of B-frames.

The drain loop must:

- honor `BufferInfo.offset` and `size`;
- cache `BUFFER_FLAG_CODEC_CONFIG` and prepend missing SPS/PPS to keyframes;
- request a sync frame when the T-Box media consumer attaches;
- always release the output buffer;
- avoid unnecessary sleeping when `dequeueOutputBuffer` already has a timeout;
- propagate errors to the service;
- terminate with a timeout, without an unbounded `join()` on the main thread.

## Ridedaemon Integration

Create a Kotlin adapter instead of exposing `api.MobileSession` to the rest of
the app:

```kotlin
interface TBoxTransport {
    suspend fun discover(network: Network): TBoxHost
    suspend fun start(host: TBoxHost, profile: StreamProfile)
    fun offerAvccAccessUnit(bytes: ByteArray): Boolean
    suspend fun stop()
    val events: Flow<TBoxEvent>
}
```

The adapter:

- creates a live-only `MobileConfig` without a fixed-resolution fallback;
- translates Go callbacks into typed events;
- guarantees one `MobileSession` only;
- calls `setECHost()` before `startSession()`;
- serializes start/stop;
- retains no Activity or ViewModel references;
- measures `pushFrame()` duration and blocking.

The reference app performs discovery with `NsdManager`, then builds
`Api.newStreamHost(ip, port, packageName)`. This is preferable to duplicating
discovery in the library because Android can manage permissions, multicast lock
and the selected network. Confirm the decision with routing tests.

## T-Box Network

Request the saved motorcycle network explicitly:

```text
WifiNetworkSpecifier
  + SSID and WPA2 passphrase from QR
  + local-only network request
  + Android NetworkCallback
```

The system may request authorization before connecting. Keep a
`NetworkCallback`, read addresses from `LinkProperties` and proceed after the
SSID-specific network has a usable IPv4 address. T-Box firmware is free to use
different DHCP subnets, including `192.168.0.0/24` and `192.168.43.0/24`.

When the network is available:

- rely on the SSID-specific Android network request rather than a subnet heuristic;
- perform the EasyConn TCP dial with the normal Go transport on the primary
  T-Box Wi-Fi;
- start NSD `_EasyConn._tcp.`;
- validate `packagename`, `ip` and port TXT values.

RideDaemon opens reverse ports `10920`, `10921`, and `10922` before sending the
EC init probe. If a resolved NSD service has package and port but no IPv4 host,
the adapter may use a same-subnet gateway/DNS address or, on an otherwise
unrouted `/24`, derive the Wi-Fi Direct group owner at `.1`. It must not invent
the service metadata when NSD itself fails.

The operational fallback is the T-Box AP as primary Wi-Fi and source-app
Internet through the mobile network. Reference:
[WifiNetworkSuggestion](https://developer.android.com/reference/android/net/wifi/WifiNetworkSuggestion).

## Storage

Prefer Proto DataStore for non-sensitive settings:

- selected video profile;
- logical T-Box identifier;
- completed onboarding;
- diagnostic preferences.

If the Wi-Fi password is stored, encrypt it with a non-exportable Android
Keystore key. Do not store projection tokens, frames, raw QR, HUID, MAC or
serial values in logs.

The MVP persists the T-Box profile in private `SharedPreferences`: cleartext
SSID and AES-GCM encrypted password. IV and ciphertext are stored separately;
the AES key is generated and held by `AndroidKeyStore` and needs no additional
interaction. The profile is updated by both QR scanning and manual fallback and
loaded while creating the `HubViewModel`.

## Minimal UI

Screens:

- `Welcome/Setup`: explain the local network and capture consent.
- `Pairing`: QR scanner, manual entry, connection state and T-Box test.
- `Home`: paired motorcycle, `Share app or screen` button, last error.
- `Streaming`: state, source when available, duration, quality and `Stop`.
- `Diagnostics`: session parameters, redacted log and manual export.
- `Settings`: automatic/manual quality, credentials and open-source licenses.

Do not build an app launcher for the MVP. The system picker is authoritative
for which content is shared.

## Idempotent Cleanup

Recommended order:

1. block new frames;
2. release `VirtualDisplay`;
3. stop `MediaProjection` if the app initiated the stop;
4. stop/drain `MediaCodec` with a timeout;
5. stop `MobileSession`;
6. stop discovery and release multicast lock;
7. remove network callback and process binding;
8. update state and remove notification/foreground service.

Every step must tolerate a missing resource, duplicate callback and exception
from a previous step.

## Library Build

From `https://github.com/vincenzobpt/ridedaemon-lib`, with Go and gomobile
configured:

```bash
gomobile bind -target=android -o hudlib.aar ./hud/api
```

For reproducible builds:

- pin the Go and `golang.org/x/mobile` versions;
- record the fork commit SHA;
- generate an AAR checksum;
- automate the task in CI or publish the AAR to an internal versioned Maven
  repository;
- run `go test ./...` before binding.

Do not manually copy unversioned AARs between machines as the final release
process.

## Definition Of Done For A Feature

- UI state and stop path implemented;
- state-machine unit tests;
- instrumented tests for relevant Android callbacks;
- no orphaned resources after stop/error;
- logs contain no sensitive data;
- behavior tested on at least one physical device and recorded in the matrix.
