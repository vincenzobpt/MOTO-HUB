# Test Strategy

Status: initial plan

## Goals

Tests must demonstrate three independent properties:

1. Android lifecycle correctness;
2. bitstream/transport correctness;
3. real compatibility across phone + Android + T-Box + firmware.

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

- 800x400 profiles at 15, 20 and 30 fps;
- 2, 2.5, 3 and 5 Mbps bitrates;
- 10 consecutive starts/stops;
- 30- and 60-minute sessions;
- static frame before/after live source;
- packet loss or weak Wi-Fi signal in a controlled environment;
- T-Box restart during a session.

## Device Matrix

Table to be filled with real data:

| Phone | SoC/codec | Android | Concurrent STA | Motorcycle/T-Box | Full screen | Single app | 60 min | Notes |
|---|---|---:|---|---|---|---|---|---|
| To define | | | | | | | | |

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
