# Reference Repository Analysis

Status: initial snapshot of the checkouts in `external upstream repositories`

## Conclusion

The two repositories demonstrate that the T-Box can receive arbitrary video
produced by the phone, not only maps. The Go library is the transport base; the
Android app is an integration proof from which to extract patterns and
fixtures, not a production-ready base to rename and publish.

HUB should replace the scene renderer with `MediaProjection` while preserving
the `MediaCodec -> AVCC -> MobileSession.pushFrame()` path.

## `ridedaemon-lib`

### What It Provides

- EasyConn discovery through mDNS;
- EC handshake and PXC control;
- media control and poll-driven media stream;
- static/live source and access-unit framing;
- AVCC/Annex-B conversion with AUD;
- gomobile facade `hud/api.MobileSession`;
- error, event and stop callbacks.

### Relevant Mobile API

```kotlin
val config = Api.newMobileConfig(...)
val session = Api.newMobileSession(config, callback)
session.setECHost(host)
session.startSession()
session.pushFrame(avccAccessUnit)
session.stopSession()
```

The library can also perform discovery, but the Android reference uses
`NsdManager` and builds a `StreamHost`. For HUB this is initially preferable:
Android keeps control of the network and permissions.

### Invariants To Preserve

- `pushFrame()` receives a complete access unit.
- The sample must be valid AVCC or detectable Annex-B.
- SPS/PPS must accompany keyframes according to the codec contract.
- A media consumer starts on a fresh SPS/PPS/IDR sequence, never a stale
  predictive frame or a fixed-resolution fallback.
- `setECHost()` precedes `startSession()`.
- Fatal errors and `onStopped()` terminate the Android lifecycle.

### Fork Improvements Only If Needed

- propagate metrics/backpressure from `pushFrame()`;
- bind sockets to a specific Android `Network`;
- typed errors instead of strings only;
- video/firmware configuration if T-Box variants emerge;
- additional concurrency and malformed-input tests.

Do not modify the protocol preemptively: first capture network traffic and run a
hardware test that demonstrates the problem.

## `ridedaemon-android`

### Reusable Patterns

- CameraX + ML Kit for QR;
- parsing `ssid`, `pwd`, `auth`, `modelid`, `sn`, `mac`, `name` fields;
- local Wi-Fi network request;
- `NsdManager` for `_EasyConn._tcp.`;
- short-lived `WifiManager.MulticastLock`;
- `packagename` and `ip` TXT parsing;
- `MediaCodec` configuration with input `Surface`;
- draining AVCC samples and sending them to `pushFrame()`;
- reading `viewAreaConfig` from T-Box events.

These patterns should be rewritten behind lifecycle-aware, tested components,
not copied into one controller.

### Components To Replace

| Demo | HUB |
|---|---|
| `Hud2DRenderer` and synthetic scenes | `ProjectionCapture` |
| Renderer-drawn surface | `VirtualDisplay` on the codec input surface |
| Monolithic controller | `SessionOrchestrator` + isolated components |
| Callbacks and `isRunning/isDiscovering` booleans | serialized state machine |
| Logcat as feedback | typed state + user errors + redacted log |
| Hardcoded timeouts/behavior | configured and tested policy |
| Activity as operating lifecycle | dedicated foreground service |

### Technical Debt Not To Inherit

- duplicated manifest permissions;
- unrestricted `ACCESS_FINE_LOCATION` across versions;
- missing `mediaProjection` foreground service;
- no automated tests;
- encoder drain `join()` without timeout;
- `192.168.0.x` subnet treated as definitive proof;
- error handling primarily through `Log`;
- codec configuration not verified for profile, level and B-frames;
- coupling discovery, session, encoder and renderer;
- demo scenes and assets unnecessary to HUB.

## Gap Between Demo and Product

| Area | Current demo | Required for HUB |
|---|---|---|
| Source | generated 2D scene | screen/app through Android consent |
| Background | Activity/demo | robust foreground service |
| Network | basic connection and discovery | local-only routing + simultaneous Internet |
| Lifecycle | nominal start/stop | lock, revocation, process death, retry, cleanup |
| UX | technical flow | onboarding, state, error recovery, visible stop |
| Video | fixed profile | codec validation and hardware profiles |
| Privacy | not central | ephemeral token/frames, redacted logs, disclosure |
| Build | local AAR | pinned toolchain and reproducible artifact |
| Compatibility | single demo | phone/T-Box/firmware matrix |

## Recommended Migration Sequence

1. Build and run the library and sample unchanged on hardware.
2. Freeze the working commit SHA and video parameters.
3. Create HUB as a new project, not inside
   `https://github.com/charliecharlieO-o/ridedaemon-android`.
4. Import the AAR behind `RideDaemonAdapter`.
5. Move QR, network and NSD into separate components.
6. Move the encoder without the 2D renderer.
7. Connect `MediaProjection` to the input surface.
8. Add state machine, service, UI and diagnostics.
9. Modify the Go fork only based on failed gates.

## Main Local Evidence

- `https://github.com/vincenzobpt/ridedaemon-lib/README.md`: protocol, package and video format.
- `https://github.com/vincenzobpt/ridedaemon-lib/hud/api/mobile.go`: Android facade and frame conversion.
- `https://github.com/vincenzobpt/ridedaemon-lib/hud/core/cfmoto.go`: session orchestration.
- `https://github.com/vincenzobpt/ridedaemon-lib/hud/net/`: discovery and wire protocols.
- `https://github.com/vincenzobpt/ridedaemon-lib/hud/stream/`: sources and access units.
- `https://github.com/charliecharlieO-o/ridedaemon-android/.../HudStreamController.kt`: session integration,
  NSD, event parsing and demo pipeline.
- `https://github.com/charliecharlieO-o/ridedaemon-android/.../HudEncoder.kt`: MediaCodec configuration/drain.
- `https://github.com/charliecharlieO-o/ridedaemon-android/.../WifiApController.kt`: network provisioning.
- `https://github.com/charliecharlieO-o/ridedaemon-android/.../QrAnalyzer.kt`: observed QR schema.
