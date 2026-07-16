# Product Requirements

Status: MVP draft

## Vision

Allow the rider to view a single Android app or the entire display on the
CFMoto TFT, using the protocol already supported by the T-Box without
modifying the motorcycle hardware.

The product is a projection bridge, not an Android Auto implementation and not
a launcher running on the T-Box. The phone produces every frame; the T-Box
receives it as an H.264 stream and displays it.

## Product Principles

- Capture consent must be explicit and visible.
- The UI must be usable before riding; during a ride it must require as few
  interactions as possible.
- The displayed state must match the actual session state.
- If the network or capture is lost, the session must end predictably or attempt
  a limited, visible reconnection.
- Do not promise capture of DRM or protected content.

## Primary Personas

- CFMoto owner who wants to use a navigator other than the official one.
- User who wants to project a dashboard, weather app or personal UI.
- Developer/tester who needs to measure compatibility and video parameters.

## MVP Flow

1. The user opens HUB and starts motorcycle pairing.
2. HUB obtains T-Box network data through QR or manual entry.
3. Android displays consent for connecting to the local Wi-Fi network.
4. HUB verifies the network, EasyConn discovery and T-Box reachability.
5. The user selects `Share app or screen`.
6. Android displays its system sharing picker.
7. After consent, HUB starts the foreground service, T-Box session,
   `MediaProjection` and H.264 encoder.
8. HUB shows state, source, duration, quality and a `Stop` action.
9. A user, system or T-Box stop releases all resources.

## MVP Functional Requirements

### Onboarding and Network

- `FR-01`: read the T-Box QR using CameraX/ML Kit.
- `FR-02`: allow manual SSID and password entry as a fallback.
- `FR-03`: ask Android to connect to the local T-Box network without silently
  modifying networks.
- `FR-04`: detect and distinguish unavailable network, denied consent, failed
  discovery and failed handshake.
- `FR-05`: remember only non-sensitive metadata and, if the password is saved,
  protect it with Android Keystore.

### Source Selection

- `FR-06`: on Android 14 QPR2 or later, offer system-picker selection between a
  single app and the entire screen.
- `FR-07`: on earlier versions, offer full-screen capture.
- `FR-08`: require new consent for every new projection session.
- `FR-09`: do not present a proprietary app list as if HUB could select or
  capture an app without system involvement.

### Streaming

- `FR-10`: negotiate the session through `ridedaemon-lib`.
- `FR-11`: wait for the T-Box safe-area configuration before considering the
  stream ready.
- `FR-12`: capture the projection onto a `Surface` accepted by `MediaCodec`.
- `FR-13`: send every AVCC access unit to `MobileSession.pushFrame()`.
- `FR-14`: use a live-only video source and restart delivery from a fresh
  SPS/PPS/IDR sequence whenever the T-Box attaches its media consumer.
- `FR-15`: support manual stop from the app and persistent notification.
- `FR-16`: treat `MediaProjection.Callback.onStop()` as the definitive stop of
  the current capture.

### State and Diagnostics

- `FR-17`: expose the states `Disconnected`, `Connecting`, `Ready`,
  `Consent required`, `Starting`, `Streaming`, `Reconnecting`, `Error`.
- `FR-18`: show actionable messages, without exposing raw protocol errors as the
  user's only information.
- `FR-19`: maintain a local, exportable diagnostic log with sensitive data
  removed.

## Out Of Scope For MVP

- BLE control of media, alarms, notifications or motorcycle commands.
- Android Auto, CarPlay or emulation of their certifications.
- Touch control of the app projected from the TFT.
- Bypassing `FLAG_SECURE`, DRM or `MediaProjection` consent.
- Audio streaming to the T-Box.
- Graphic overlays, advanced crop and rotation through a GPU compositor.
- Automatic Play Store publication.

## Platform Constraints

- The user selects a single app in the Android picker. HUB cannot silently
  preselect and start an arbitrary app.
- Protected windows may appear black in the stream.
- Screen locking, user revocation, another projection or process death may end
  the session.
- A foreground service and notification are mandatory during capture.
- The T-Box uses a local network that may not provide Internet access.
- Background operation also depends on OEM power-management policies.

## Non-Functional Requirements

- `NFR-01 Latency`: glass-to-glass goal below 500 ms; desired target below 300
  ms, to be measured on hardware.
- `NFR-02 Startup`: from an already-associated network to first frame within 15
  seconds in 90% of attempts.
- `NFR-03 Stability`: 60-minute continuous session without crashes or critical
  leaks.
- `NFR-04 Resources`: no unbounded video queue; prefer recent frames.
- `NFR-05 Temperature`: monitor thermal throttling in 30- and 60-minute tests.
- `NFR-06 Privacy`: no frame saved to disk and no remote telemetry in the MVP.
- `NFR-07 Compatibility`: Android 14/API 34 minimum; app-window sharing only
  where available in the system.
- `NFR-08 Recovery`: all resources must be released within 3 seconds of stop.

## MVP Acceptance Criteria

- A supported phone connects to the T-Box through QR or manual data.
- The TFT displays the complete screen with unprotected content.
- On Android 14 QPR2+, the TFT displays only the app selected in the picker,
  without system-included notifications and system UI.
- The projected app can continue using Internet through an available network on
  at least the devices in the certification matrix.
- Screen lock, consent revocation and Wi-Fi disconnection do not leave an
  encoder, multicast lock or foreground service orphaned.
- A 60-minute test produces no continuous memory or latency growth.

## Open Product Questions

- Which CFMoto models and firmware versions will be declared supported?
- Should HUB remember multiple motorcycles/T-Boxes?
- What behavior is preferred after network loss: immediate stop or short
  automatic reconnection?
- Is distributing the entire app under GPL-3.0, as required by the embedded
  library, acceptable?
