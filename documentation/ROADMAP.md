# Roadmap

Status: incremental proposal

The roadmap prioritizes technical risks that could invalidate the product. The
complete UI comes after proving real streaming and Internet/T-Box routing.

## Phase 0 - Reproducible Baseline

Outputs:

- verified build of `ridedaemon-lib` and versioned `hudlib.aar`;
- build/install of the Android reference app;
- identification of motorcycle, T-Box and firmware;
- demo scene visible on the TFT for at least 15 minutes;
- redacted handshake and safe-area log.

Gate: do not proceed if the reference app cannot be reproduced on the target
hardware.

## Phase 1 - Feasibility Spike

Technical app without final UI:

- local-only connection to the T-Box;
- process binding and NSD binding test;
- full-screen `MediaProjection` -> `MediaCodec` -> `pushFrame()`;
- source app test with mobile Internet active;
- bitstream analysis and latency measurement;
- stop on lock, revocation and network loss.

Gate:

- stable video on the TFT;
- latency suitable for navigation;
- simultaneous Internet on at least the candidate devices;
- no unresolvable codec/T-Box limitation.

If routing fails, evaluate a library change for socket binding. If rendering or
aspect ratio fails, introduce a separate EGL spike.

## Phase 2 - Vertical MVP

- clean HUB Android project;
- QR onboarding plus manual fallback;
- home and connection status;
- Android app/screen picker;
- foreground service with notification and stop;
- state machine and idempotent cleanup;
- verified fixed video profile;
- redacted local diagnostics;
- unit tests and initial hardware matrix.

Gate: MVP criteria and the `TEST_STRATEGY.md` gates are satisfied.

## Phase 3 - Robustness and Compatibility

- retry/reconnect with a budget;
- multiple T-Box/firmware profiles;
- codec/fps/bitrate fallback;
- thermal and power tests;
- OEM background-restriction handling;
- diagnostic export;
- accessibility and localization;
- reproducible CI pipeline for the Go AAR and APK.

## Phase 4 - Video Quality

Only if justified by tests:

- EGL compositor;
- controlled fit/fill and bars;
- rotation and resize without a full restart;
- optional overlays;
- quality adaptation based on polling, drops and thermal state;
- profiles for each T-Box model.

## Phase 5 - Advanced Features

To be evaluated separately:

- BLE for media commands and motorcycle events;
- quick start for an already paired motorcycle;
- native dashboards and dedicated scenes;
- multiple saved T-Boxes;
- Android shortcut integration;
- public distribution and beta program.

## Initial Implementation Backlog

1. Freeze commits of the two reference repositories.
2. Automate `go test` and AAR generation.
3. Create the app skeleton and `ProjectionSessionService`.
4. Implement the state machine with fake dependencies.
5. Implement `TBoxNetworkManager` and routing tests.
6. Implement `TBoxDiscovery` and `RideDaemonAdapter`.
7. Implement `AvcEncoder` with debug-only diagnostic dump.
8. Implement `ProjectionCapture` and Activity consent.
9. Connect the end-to-end pipeline on hardware.
10. Add onboarding, home and streaming UI.
11. Run the test matrix and fix lifecycle issues.
12. Complete privacy, licensing and beta packaging.

## Do Not Bring Forward

- extensive Gradle modularization;
- proprietary app launcher;
- BLE before the video MVP;
- adaptive profiles without real metrics;
- overlays or 2D rendering inherited from the demo if direct projection works;
- support claims for untested models.
