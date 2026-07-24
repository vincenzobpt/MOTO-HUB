# Test Strategy

Status: active strategy

## Goals

Tests must demonstrate five independent properties:

1. Android lifecycle correctness;
2. bitstream/transport correctness;
3. real compatibility across phone + Android + T-Box + firmware.
4. Android Auto compositor correctness across `FIT`, `STRETCH`, and `CROP`;
5. touch-coordinate correctness across safe areas, safe margins, and motorcycle
   model profiles.

Emulators and unit tests do not replace testing on the motorcycle.

## Levels

### JVM Unit Tests

- state machine and illegal transitions;
- timeout and retry budget;
- QR parser and input validation;
- NSD TXT and safe-area JSON parser;
- Go callback to typed-event mapping;
- log redaction;
- encoder profile selection;
- Android Auto profile selection, display placement, safe margins and touch
  viewport mapping;
- adaptive power-mode controller behavior;
- GitHub update version selection, including pre-release handling;
- idempotent cleanup with fake dependencies.

### Go Tests

In the `ridedaemon-lib` fork:

- `go test ./...`;
- AVCC -> Annex-B with multiple NALs and malformed input;
- exactly one AUD per access unit;
- static/live source, mux and backpressure;
- handshake and protocol parser with fixtures where possible;
- race tests on concurrent components: `go test -race ./...` on a supported
  host.

### Instrumented Android Tests

- service start/stop and notification;
- denied/revoked permissions;
- Activity recreation during a session;
- UI process in background with service alive;
- duplicate network callback or `onLost()` during start;
- `MediaProjection.onStop()` and cleanup;
- codec initialization failure and stop during drain;
- rotation and resize of captured content.

`MediaProjection` consent must not be bypassed in end-to-end tests; use manual
tests or controlled harnesses for the system-mediated part.

### Bitstream Analysis

Save streams only in explicitly diagnostic lab builds and without personal
content. Verify with tools such as `ffprobe`:

- AVC profile/level;
- resolution and frame rate;
- SPS/PPS in keyframes;
- IDR interval;
- absence of B-frames;
- AVCC format at codec output and Annex-B at transport input;
- monotonicity and effective frequency.

Video dumps must be excluded from user logs and deleted after testing.

## Essential Hardware Tests

### Connection

- valid QR, malformed QR and wrong password;
- denied Wi-Fi consent;
- T-Box powered off or mDNS service absent;
- AP without Internet;
- Wi-Fi loss and return during streaming;
- mobile network available and unavailable;
- cellular socket diagnostics: `SocketFactory`, TCP `bindSocket()` and
  UDP/DNS `bindSocket()`;
- T-Box TCP diagnostics with streaming inactive;
- phone with and without local-only STA concurrency.

### Capture

- full screen on Android 14+;
- single app on Android 14 QPR2+;
- orientation change;
- covered/minimized source app;
- lock and unlock;
- revocation from the system chip;
- second app starting `MediaProjection`;
- app with `FLAG_SECURE` and DRM content.

### Streaming

- landscape, portrait and near-square runtime areas, including dimensions that
  require 16-pixel macroblock alignment;
- touch at the four projection corners and centre using Auto and manual AA
  source presets; raw T-Box coordinates must map through the aligned AVC canvas
  before the visible AA viewport;
- `FIT`, `STRETCH`, and `CROP` in full Android Auto, including `AUTO 800x480`
  where the usable projection area is `800x384`;
- motorcycles that report touch accurately, motorcycles that report touch but
  require focus/handlebar control, and `Disable touchscreen` enabled;
- immediate `VideoArea` delivery during handshake and delayed delivery;
- missing live area with and without a geometry saved for the same SSID;
- 2, 2.5, 3 and 5 Mbps bitrates;
- 10 consecutive starts/stops;
- 30- and 60-minute sessions;
- static frame before/after live source;
- packet loss or weak Wi-Fi signal in a controlled environment;
- T-Box restart during a session.

### Settings And Updates

- save a Garage profile and confirm visible success/failure feedback;
- change Android Auto display mode and confirm it applies after restarting the
  affected projection mode;
- enable and disable seamless resume after overlay permission has been granted;
- stop mirroring and full Android Auto with auto-connect enabled
  and confirm MOTO-HUB reconnects to the saved motorcycle;
- share application logs and confirm Android receives a diagnostic text file;
- check GitHub update flow with no newer release, newer pre-release, newer
  release and invalid/no-APK release fixtures.

## Device Matrix

Table to be filled with real data:

| Phone | SoC/codec | Android | Concurrent STA | Motorcycle/T-Box | Full screen | Single app | 60 min | Notes |
|---|---|---:|---|---|---|---|---|---|
| To define | | | | | | | | |

Known physical entries should include:

- CFMOTO 700MT-ADV with physical `800x480` and measured projection `800x384`;
- CFMOTO 800NK/CRCP variants, including portrait touch displays when available.

Minimum before beta:

- one Pixel/AOSP-like device;
- one recent Samsung;
- at least one other OEM with aggressive power management;
- two major Android versions, including one with app-only sharing;
- every declared supported T-Box model/firmware.

## Measurements

- **First frame**: consent completed -> first frame visible on TFT.
- **Glass-to-glass latency**: high-frequency timer on the filmed phone and TFT;
  compare frame by frame.
- **Effective FPS**: encoded and polled frames served per interval.
- **Drop rate**: produced, accepted and discarded frames.
- **Stability**: PSS memory, threads, temperature, battery and latency over
  time.
- **Cleanup**: no service, codec, virtual display, network callback or
  multicast lock after stop.

## MVP Release Gates

- all product acceptance criteria verified;
- no P0/P1 crash in 10 start/stop cycles;
- no unbounded memory growth over 60 minutes;
- complete stop on lock, revocation and network loss;
- compliant bitstream on every codec in the matrix;
- source-app Internet verified during T-Box connection;
- licensing and privacy reviews completed;
- release logs contain no sensitive data.

## Minimum Bug Report

```text
HUB version and commit:
Phone / Android build:
Motorcycle / T-Box / firmware:
Source: screen or single app:
Video profile and codec:
Reproduction steps:
Expected/observed result:
Stop reason:
Redacted log attached:
```
