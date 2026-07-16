# MOTO-HUB vs OpenCfMoto Comparative Audit

Date: 2026-07-16  
Status: evidence-based engineering audit  
Scope: Android application, Android Auto receiver, EasyConn/T-Box transport,
video pipeline, lifecycle, diagnostics, build and release engineering

## Executive Summary

MOTO-HUB is currently the stronger application in this comparison. It has a
more defensive network lifecycle, a modular transport boundary, persistent
multi-bike configuration, a substantially better user experience, richer
diagnostics, a tested AVC normalization layer, and a reproducible private
Android Auto release pipeline.

OpenCfMoto remains an important engineering reference. Its strongest ideas are:

- a smaller all-Kotlin implementation;
- Android 10+ support;
- explicit protocol profiles for known CFDL16 and CFDL26 head units;
- profile-driven Android Auto source resolutions, including portrait mode;
- a five-attempt EasyConn probe loop.

Those advantages should be adopted only through capability-based abstractions
and hardware-gated changes. Copying its model-ID assumptions or replacing the
known-good Android Auto identity and service profile would create regressions.

The current MOTO-HUB beta is hardware-validated for both mirroring and Android
Auto on the primary test combination. It cannot yet be described as universally
robust because the compatibility matrix, long-session evidence, reconnection
policy, instrumented tests, and automated T-Box protocol harness are incomplete.

## Audited Revisions

### MOTO-HUB

- Application commit: `9ca7eabdf1b10d786d83db4ea65e1df2a3b47eae`
- Branch: `fix/dynamic-tbox-subnet`
- Validation tag: `motohub-beta6-hardware-validated`
- Version: `0.8.2-beta.6` (`44`)
- RideDaemon commit: `2a5f6e4510f31361279c4d4c8b894128affc529a`
- RideDaemon branch: `motohub/transport-hardening`
- RideDaemon validation tag: `motohub-beta6-transport-hardware-validated`

### OpenCfMoto

- Main commit: `e5456c57ff80d3cfda376456b61c9fdb662f928c`
- Tag: `v0.1.0`
- Additional reviewed branch commit:
  `a52fad7cb8316c70b8c0182137ce92c153fdcbb0`
- The additional commit derives a `.1` peer address for Wi-Fi Direct networks.
  It is not merged into OpenCfMoto `main` at the time of this audit.

Source project: [BojanJ/open-cfmoto](https://github.com/BojanJ/open-cfmoto)

## Verification Results

| Check | MOTO-HUB | OpenCfMoto |
|---|---:|---:|
| Android main source files | 76 | 59 |
| Kotlin main source lines | 10,239 | 5,781 |
| Android test files | 12 | 2 templates |
| Android unit tests executed | 41 | 1 template test |
| RideDaemon Go tests | 17 | Not applicable |
| Real automated tests in the application ecosystem | 58 | 0 protocol/codec/network tests |
| Android lint | Pass, 0 errors, 35 warnings | Fail, 4 errors, 1 fatal, 60 warnings |
| Android debug assembly | Pass | Pass |
| Go race detector | Pass | Not applicable |
| Debug APK size | 95 MB | 51 MB |
| Minimum Android version | Android 14 / API 34 | Android 10 / API 29 |
| Release automation | Verified tag-to-release workflow | No equivalent workflow found |

The OpenCfMoto lint blockers are two invalid BLE constants, a location
permission declaration error, an RTL layout error, and a fatal packaged-private-
key finding. Its debug APK still assembles because lint is a separate task.

MOTO-HUB lint warnings are mostly dependency, KTX, obsolete-version-check, and
local-loopback TLS warnings. They are not release blockers, but they should be
triaged rather than ignored indefinitely.

## Weighted Assessment

The score is a prioritization aid, not a compatibility claim. Transport,
streaming correctness, Android Auto stability, and lifecycle are weighted more
heavily than APK size or broad Android-version reach.

| Area | Weight | MOTO-HUB | OpenCfMoto | Current leader |
|---|---:|---:|---:|---|
| T-Box network and transport | 20 | 18 | 14 | MOTO-HUB |
| AVC/video correctness | 15 | 14 | 12 | MOTO-HUB |
| Android Auto integration | 15 | 13 | 13 | Tie |
| Lifecycle and recovery | 10 | 9 | 6 | MOTO-HUB |
| User experience | 10 | 9 | 4 | MOTO-HUB |
| Diagnostics and supportability | 10 | 9 | 6 | MOTO-HUB |
| Automated quality controls | 10 | 9 | 2 | MOTO-HUB |
| Security and release hygiene | 5 | 4 | 1.5 | MOTO-HUB |
| Platform and bike reach | 5 | 3 | 4 | OpenCfMoto |
| **Total** | **100** | **88** | **62.5** | **MOTO-HUB** |

## Detailed Comparison

### 1. Wi-Fi and Android Network Handling

MOTO-HUB:

- requests the saved T-Box SSID with `WifiNetworkSpecifier`;
- waits for usable IPv4 `LinkProperties` instead of treating `onAvailable()` as
  proof that DHCP is complete;
- keeps incomplete OnePlus link-property updates distinct from a real
  `onLost()` event;
- detects an active VPN when process binding is rejected and reports an
  actionable error;
- explicitly releases and restores process binding around Android Auto startup;
- keeps the local-only T-Box network request alive while normal phone traffic
  can return to the default route;
- binds the EasyConn command socket to the selected Android `Network` before
  transferring its file descriptor to Go.

OpenCfMoto:

- requests the correct local-only Wi-Fi network;
- process-binds immediately in `onAvailable()`;
- does not wait for a usable IPv4 address;
- has no connection timeout in `BikeWifi`;
- has no VPN-interference diagnosis;
- has a simpler lifecycle with fewer OEM-specific safeguards.

Verdict: MOTO-HUB is materially stronger on Android/OEM networking.

### 2. T-Box Discovery and Peer Selection

MOTO-HUB treats Android NSD as authoritative. It validates service package and
port metadata, accepts advertised or resolved IPv4 addresses, and only derives a
peer from network routes, DNS, or local subnet evidence after a valid EasyConn
service has been resolved. A `.1` fallback is restricted to a compatible `/24`
local network.

OpenCfMoto `main` derives the peer from the default route or DNS. Its unmerged
Wi-Fi Direct branch adds a `.1` fallback from the local address. That fix is
useful, but it is less constrained than MOTO-HUB's NSD-first decision path.

Verdict: MOTO-HUB has the safer generalized discovery strategy.

### 3. EasyConn Handshake and Reverse Connections

Both applications now open reverse listeners on ports `10920`, `10921`, and
`10922` before initiating the EasyConn probe. This startup order is essential
because the T-Box connects back to the phone.

MOTO-HUB advantages:

- reverse-listener ordering is covered by Go tests;
- malformed and fragmented protocol input has tests;
- legacy and CFDL26 capture negotiation has tests;
- unknown even PXC commands are acknowledged defensively;
- the transport boundary is isolated behind `TBoxTransport`;
- network-bound socket ownership is explicit across Kotlin and Go.

OpenCfMoto advantage:

- its EasyConn probe retries up to five times with a two-second delay.

Verdict: MOTO-HUB has stronger correctness evidence, but should adopt a bounded,
cancellable probe retry policy.

### 4. Video Negotiation and H.264 Delivery

MOTO-HUB:

- uses only the live encoded source;
- consumes T-Box video dimensions at runtime;
- aligns encoder dimensions for AVC macroblock requirements;
- stores per-bike geometry only as a fallback;
- clears stale queued frames when command `112` starts a consumer;
- waits for a fresh IDR and requests encoder synchronization;
- parses Annex-B, AVCC, and `avcC` codec configuration;
- prepends missing SPS/PPS to key access units;
- has focused tests for access-unit normalization and encoder profiles;
- supports user-selected letterbox and stretch composition per bike.

OpenCfMoto:

- also waits for a keyframe and requests sync at consumer startup;
- has a compositor keepalive and repeat-previous-frame behavior;
- chooses known portrait or landscape Android Auto input profiles;
- uses a simpler codec-config concatenation path that can become fragile if an
  OEM returns codec config and frames in different byte-stream formats;
- does not support a live T-Box canvas resize after encoding starts.

Verdict: MOTO-HUB has the stronger output pipeline. OpenCfMoto's profile-driven
Android Auto input orientation remains a feature worth generalizing.

### 5. Android Auto Receiver

Shared foundation:

- both receivers originate from Headunit Reloaded / headunit-revived concepts;
- both use local loopback projection rather than a user-visible VPN;
- both decode Android Auto H.264 and re-encode it for the T-Box;
- both advertise a system-audio sink but discard incoming PCM;
- both advertise a microphone service but do not implement microphone capture;
- both depend on a proven-compatible Android Auto certificate/private-key
  identity in the distributed APK.

MOTO-HUB advantages:

- Android Auto is integrated into the same explicit session state model;
- a phone preview can be enabled for touch interaction on non-touch TFTs;
- phone and T-Box touches are mapped through tested geometry helpers;
- the known-good service-discovery identity was restored and hardware-validated;
- Android Auto private material is excluded from public source and provisioned
  through release secrets;
- the release workflow verifies that certificate and private key match before
  building.

OpenCfMoto advantage:

- its Android Auto source resolution is selected by a `BikeProfile`, including
  `720x1280@240` for a known portrait CFDL26 unit and `800x480@160` for known
  landscape units.

Important MOTO-HUB constraint:

Starting with the beta.8 candidate, MOTO-HUB selects `800x480@160` for a saved
landscape T-Box geometry and `720x1280@240` for a saved portrait geometry.
Missing or invalid geometry still selects the hardware-validated landscape
profile. The compatibility identity remains unchanged. Portrait support must
remain marked unvalidated until it passes on physical hardware.

Verdict: MOTO-HUB now has the stronger profile-selection design because it uses
runtime geometry rather than motorcycle identifiers. OpenCfMoto retains the
advantage of existing portrait hardware validation.

### 6. Mirroring and Phone Interaction

MOTO-HUB provides:

- full-screen and Android app-specific MediaProjection;
- an Android-required foreground service and stable notification controls;
- phone display dimming with notification-based restore and stop actions;
- Android Auto phone preview and touch input;
- explicit cleanup on projection revocation and network loss.

OpenCfMoto provides mirroring and Android Auto but uses a much smaller,
diagnostic-oriented activity flow. It does not offer MOTO-HUB's garage,
per-bike display preferences, guided connection state, or polished control
surface.

Verdict: MOTO-HUB is substantially more complete.

### 7. State, Lifecycle, and Concurrency

MOTO-HUB separates connection, projection, and Android Auto responsibilities
across foreground services and typed session states. Duplicate actions are
guarded, network loss is propagated, and cleanup is intended to be idempotent.
RideDaemon's concurrency-sensitive components pass `go test -race ./...`.

OpenCfMoto relies more heavily on process-global singleton holders and static
bridges. Android lint reports two static context-leak warnings. Its simpler
architecture is easier to inspect, but it has fewer lifecycle safeguards.

MOTO-HUB still lacks automated Activity/service recreation tests and repeated
hardware reconnect evidence. Therefore this area is strong, not complete.

### 8. Pairing, Garage, and Persistence

MOTO-HUB:

- scans a live QR code or imports one from a photo;
- saves multiple motorcycle profiles;
- supports profile names and app-private motorcycle photos;
- stores Android Auto display mode per bike;
- encrypts Wi-Fi passwords with AES-GCM and a key in Android Keystore;
- disables Android backup for the application.

OpenCfMoto:

- scans the motorcycle QR code for the active connection;
- uses the QR model ID as an early profile hint;
- has no equivalent multi-bike garage or encrypted persistence layer.

Verdict: MOTO-HUB is product-grade relative to OpenCfMoto's engineering UI.

### 9. Diagnostics

MOTO-HUB diagnostics are persistent and structured:

- timestamp, level, source, application version, device and Android version;
- stack traces for failures;
- up to 2,500 events and a 2 MB rewrite threshold;
- password/passphrase redaction;
- in-app viewer, clear and clipboard/export functions;
- dedicated network tests for T-Box, cellular TCP, UDP, and DNS routing.

OpenCfMoto has a useful unified log and share action, but the log is an
in-memory string buffer. It is lost with the process, has no structured level
model, and has no equivalent secret-redaction layer.

Verdict: MOTO-HUB is stronger for remote beta support.

### 10. Build, Release, and Supply Chain

MOTO-HUB's tag release workflow:

- uses pinned GitHub Action revisions;
- validates all required secrets;
- verifies the Android Auto certificate/private-key pair;
- runs unit tests and release lint;
- builds, aligns, signs, and verifies the APK;
- confirms version metadata and required Android Auto resources;
- publishes an APK and SHA-256 checksum to a GitHub Release;
- removes provisioned private material after the job.

OpenCfMoto has no comparable CI/release workflow in the reviewed revision and
tracks the Android Auto private key as an application resource, which produces a
fatal Android lint finding.

MOTO-HUB release gap: the hardware-validated beta and RideDaemon transport
commits are local feature branches. Public `main` still points to version
`0.8.1`, and the README still reports `0.8.1 (38)`. The strongest code is not yet
the default public code.

Verdict: MOTO-HUB has the superior release design, but must publish the validated
state before users benefit from it.

### 11. Maintainability

MOTO-HUB's interface boundaries and feature packages are stronger, but several
files have grown too large:

- `HubHomeScreen.kt`: 855 lines;
- `MainActivity.kt`: 613 lines;
- `RideDaemonTransport.kt`: 458 lines;
- `AaCompositor.kt`: 457 lines;
- `VideoDecoder.kt`: 457 lines;
- `AndroidAutoSessionService.kt`: 450 lines.

This is not currently a correctness failure. It increases review cost and the
probability of future regressions. Refactoring must happen behind tests and
without changing the validated protocol behavior.

OpenCfMoto is smaller and easier to read end-to-end, but its direct coupling and
global holders will become a liability if its product surface expands.

## What MOTO-HUB Should Adopt

| OpenCfMoto idea | Decision | Safe MOTO-HUB form |
|---|---|---|
| Five-attempt EasyConn probe | Adopt | Bounded exponential retry with cancellation, telemetry, and one session owner |
| Profile-driven AA orientation | Adopt carefully | Capability profile derived from runtime T-Box data, with the validated 800x480 profile as default |
| Explicit CFDL protocol profiles | Adopt selectively | Versioned capability flags from CLIENT_INFO and capture negotiation, not motorcycle-name hardcoding |
| All-Kotlin protocol stack | Do not migrate now | Keep RideDaemon boundary; migration has high regression cost and no current user benefit |
| Android 10+ minimum | Reconsider later | Lower only after lifecycle and MediaProjection compatibility tests exist |
| BLE wake-up prototype | Do not adopt now | It is inactive in OpenCfMoto's main flow, model-specific, lint-failing, and contains hardcoded secrets |
| Smaller APK | Adopt | ABI splits and release resource shrinking without altering codec behavior |

## Critical Gaps Before a Broad Stable Release

### P0 - Preserve and Publish the Known-Good Baseline

1. Merge the hardware-validated beta into `main` with a normal merge commit.
2. Push the matching RideDaemon transport commit and make its source revision
   reproducible from the AAR consumed by the app.
3. Update README version and compatibility statements.
4. Add release notes that distinguish physically validated combinations from
   community-reported combinations.
5. Select and document the final project/component licenses before expanding
   public distribution.
6. Keep the Android Auto identity and service profile unchanged in this phase.

Exit gate: a clean checkout of public `main` produces the same tested APK through
CI and its checksum is recorded.

### P1 - Compatibility and Recovery

1. Add bounded EasyConn connect/probe retry with explicit attempt logs.
2. Add an automatic reconnect policy for transient T-Box loss, with a strict
   attempt/time budget and a visible cancel action.
3. Introduce an `AndroidAutoCapabilityProfile` selected from runtime T-Box
   dimensions, orientation, protocol version, and proven compatibility data.
4. Preserve `800x480@160` and the current identity as the default profile.
5. Validate portrait Android Auto on a real portrait TFT before enabling it for
   users.
6. Run a minimum matrix across OnePlus, Samsung, and Pixel/AOSP-like phones and
   at least one landscape and one portrait T-Box.

Exit gate: ten consecutive start/stop cycles and a 60-minute session pass on
every declared supported combination.

### P2 - Automated Protocol and Lifecycle Testing

1. Build a fake T-Box loopback harness that exercises NSD metadata, reverse
   sockets, fragmented frames, delayed callbacks, command `112`, touch, and
   disconnects.
2. Add Android instrumented tests for foreground services, denied permissions,
   Activity recreation, notification actions, process backgrounding, and
   projection revocation.
3. Add fixture-based H.264 tests from multiple OEM codecs without storing user
   screen content.
4. Add CI for pull requests, not only release tags.
5. Build and test RideDaemon from source in CI, then verify the AAR hash consumed
   by Android.

Exit gate: transport regressions fail before an APK can be released.

### P3 - Runtime Observability and Adaptation

1. Record first-frame latency, produced/sent/dropped frames, effective FPS,
   reconnect count, encoder identity, and negotiated dimensions.
2. Add a user-exportable session summary separate from verbose logs.
3. Monitor Android thermal state and encoder stalls.
4. Add tested bitrate/FPS fallbacks only after metrics identify a need.
5. Detect a stalled stream and request a fresh IDR before tearing down the whole
   session.

Exit gate: a support log can distinguish network, handshake, decoder, encoder,
and T-Box-consumer failures without guessing.

### P4 - Maintainability and Distribution

1. Split oversized UI and service files along existing feature boundaries.
2. Move screen routing from Activity booleans to an explicit navigation model.
3. Produce ABI-specific APKs or an Android App Bundle to reduce download size.
4. Audit all permissions and make optional features degrade gracefully.
5. Decide whether unsupported microphone/audio behavior should be implemented
   or explicitly disclosed in UI and documentation.
6. Add accessibility and localization after the state flow is stable.

## Non-Negotiable Regression Rules

1. Never replace the hardware-validated Android Auto certificate, identity, or
   service profile in the main release without a separate experiment branch and
   motorcycle validation.
2. Never infer a marketing motorcycle model from QR `modelId`; use only
   protocol capabilities that can be proven programmatically.
3. Never merge transport changes without Android tests, Go tests, race tests,
   and a tagged rollback point.
4. Never claim support for a phone, Android version, T-Box, or firmware based
   only on a successful build or emulator test.
5. Never allow convenience fallback addresses to override valid NSD data.
6. Never collect screen recordings or unredacted credentials in production
   diagnostics.

## Recommended Next Increment

The next engineering increment should not add a visible feature. It should:

1. publish the exact beta.6 hardware-validated baseline;
2. add pull-request CI for Android and RideDaemon;
3. implement bounded EasyConn retry behind the existing `TBoxTransport` API;
4. add retry tests and failure telemetry;
5. validate unchanged mirroring and Android Auto behavior on the primary bike.

After that checkpoint, dynamic Android Auto orientation is the highest-value
compatibility feature to prototype on a separate branch.

## Final Verdict

MOTO-HUB is already the better application in this direct comparison. Its lead
comes from defensive Android networking, tested transport behavior, stronger
video normalization, lifecycle ownership, product UX, diagnostics, persistence,
and release engineering.

OpenCfMoto is currently better only in platform reach, implementation size, and
its explicit support strategy for a few known portrait/landscape head units.
Those are meaningful advantages and define MOTO-HUB's next compatibility work.

The path to the most solid application is not another large rewrite. It is to
freeze the working baseline, expand automated failure testing, collect objective
runtime metrics, and add new T-Box profiles only after physical validation.
