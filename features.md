# MOTO-HUB Features

Current version: `0.9.0-beta.10-build.60-r1 (60)`, Android 14+.

This document describes implemented functionality. It must be updated whenever a feature is added,
removed, renamed, or materially changed.

## Motorcycle Pairing And Connection

- Pair with a CFMOTO T-Box by scanning its QR code.
- Import and decode a QR code from an existing photo.
- Automatically extract the Wi-Fi SSID, password, and available T-Box metadata.
- Store Wi-Fi credentials securely using Android Keystore.
- Automatically request and connect to the motorcycle Wi-Fi access point.
- Discover the EasyConn service on the T-Box network.
- Establish the encrypted EasyConn handshake and streaming session.
- Keep T-Box traffic bound to motorcycle Wi-Fi while preserving cellular Internet access where
  supported by Android and the phone manufacturer.
- Optionally connect to the saved motorcycle when MOTO-HUB launches.

## Motorcycle Garage

- Store and manage multiple motorcycles.
- Select the active motorcycle.
- Assign a custom display name.
- Take or select a motorcycle photo.
- Display the motorcycle photo throughout the application.
- Edit or delete saved motorcycle profiles.
- Store Android Auto display preferences separately for each motorcycle.
- Store per-motorcycle TFT safe margins for displays where motorcycle UI occupies part of the
  physical panel.
- Show `Profile saved` or `Unable to save profile` feedback after saving a motorcycle profile.
- Inspect hardware and software information actually reported by the T-Box, including:
  - EasyConn endpoint and discovery information.
  - TFT resolution and orientation.
  - Reported DPI and screen type.
  - Head-unit, vehicle brand, and vehicle model identifiers.
  - PXC, SDK, software, and protocol versions.
  - Transport and product types.
  - Supported T-Box feature flags.
- Avoid guessing the motorcycle model from its QR code or SSID.

## Screen Mirroring

- Mirror the entire Android display to the motorcycle TFT.
- Project a single application selected through Android's system picker.
- Hardware H.264 encoding optimized for the T-Box protocol.
- Persistent foreground notification with session controls.
- Dim or obscure the phone display while mirroring continues.
- Restore the phone display using the notification or phone interaction.
- Stable stop and cleanup of the projection session.

## Android Auto

- Run Android Auto directly on the motorcycle TFT through an embedded local head-unit receiver.
- Decode, composite, re-encode, and stream Android Auto video to the T-Box.
- Display Android Auto simultaneously on the TFT and phone.
- Use the phone preview as a touchscreen controller for a non-touch motorcycle TFT.
- Use an on-screen directional controller with Up, Down, Left, Right, Enter, Back, Home, and
  positive or negative scrolling.
- Select a per-motorcycle TFT display mode:
  - `FIT`: preserve the complete image with black bars when required.
  - `STRETCH`: stretch the active Android Auto content to use the complete available TFT area.
  - `CROP`: fill the complete available TFT area without stretching and crop edges when required.
- Select Android Auto resolution automatically from learned T-Box geometry.
- Override the Android Auto source with one of these manual profiles:
  - Landscape 800 x 480.
  - Landscape 1280 x 720.
  - Portrait 720 x 1280.
  - Portrait 1080 x 1920.
- Automatically recover supported stalled or disconnected TFT streams.
- Keep the local Android Auto receiver active during supported TFT recovery operations.
- Optionally disable TFT touchscreen advertisement so Android Auto uses focus/handlebar behavior.
- Preserve correct touch mapping through safe margins and the selected `FIT`, `STRETCH`, or
  `CROP` compositor mode.

## Video Quality

- Apply H.264 quality settings to mirroring and Android Auto.
- Select `Smoother` for reduced bitrate, heat, network load, and phone workload.
- Select `Balanced` for the recommended default quality.
- Select `Sharper` for clearer maps and text at a higher bitrate.
- Use automatic power behavior to reduce stream load during heat or weak-link conditions and
  recover quality when conditions improve.

## Reliability And Recovery

- Use persistent foreground services for projection.
- Monitor outgoing Android Auto TFT frames with an optional recovery watchdog.
- Detect supported stream stalls, encoder failures, and T-Box network losses.
- Reacquire the network, repeat EasyConn discovery and handshake, and rebuild the encoder when
  recovery is possible.
- Optionally use seamless resume to park and resume a projection after longer T-Box interruptions.
- Hold high-performance Wi-Fi resources while streaming where Android allows it.
- Auto-reconnect to the saved motorcycle after deliberate mode stops when auto-connect is enabled.
- Perform controlled cleanup after deliberate session stops.

## Diagnostics

- Maintain a built-in application event log.
- Record important connection, projection, recording, control, settings, and UI operations.
- Copy logs to the clipboard, share them, or clear them.
- Share logs as a generated diagnostic text file instead of only copying text.
- Include the app version, build number, phone model, and Android version in exported diagnostics.
- Run dedicated T-Box and cellular network routing tests.
- Detect likely Always-on VPN / kill-switch local-network blocking.
- Detect likely conflicts with the official CFMOTO/EasyConnect app and offer retry/settings actions.
- Inspect detected Android networks and bound routes.
- Review projection session events.
- Display structured passed, failed, skipped, and running diagnostic results.
- Inspect T-Box capabilities captured from EasyConn `CLIENT_INFO` without displaying sensitive
  fields.

## Additional Features

- Display the application version and build number.
- Check GitHub releases and pre-releases for newer APK builds.
- Show update release notes and pre-release status before installing.
- Provide an About page with a project description, safety disclaimer, and GitHub link.
- Use an English-only application interface.
- Guide the user through connection before presenting projection modes.
- Operate locally without requiring a MOTO-HUB account or proprietary cloud service.
