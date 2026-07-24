# Security, Privacy and Licensing

Status: active threat model, not legal advice

## Processed Data

| Data | Required for | Proposed persistence |
|---|---|---|
| Screen frames | essential to streaming | volatile memory only |
| Projection consent/token | essential to session | never persisted |
| T-Box SSID/password | connection | private preferences; password AES-GCM encrypted with Android Keystore key |
| IP/MAC/serial/HUID | protocol/diagnostics | redacted in logs |
| Session log | support | local, limited retention |
| Android Auto microphone audio | Android Auto Assistant / voice channel | live only; not recorded by MOTO-HUB |

## Trust Boundaries

- Android OS mediates network and projection consent.
- HUB sees all unprotected pixels of the selected source.
- `ridedaemon-lib` and embedded native/Go code run in the same process.
- The T-Box and local Wi-Fi network must not be considered a secure Internet
  channel by default.
- Other captured apps are outside HUB's control.
- The AAP (Android Auto Projection) session is strictly loopback
  (`127.0.0.1`, self-mode, no VPN, no Wi-Fi Direct pairing) - the only
  process that can ever be the TLS peer is Google's own Android Auto app on
  the same device; see `ANDROID_IMPLEMENTATION.md` Android Auto Self-Mode.

## Threats and Controls

| Threat | Required control |
|---|---|
| Unintended notification capture | encourage app-only sharing on Android 14 QPR2+ |
| Forgotten session | persistent notification, stop action and visible timer |
| Reused projection token | volatile token, one session, callback cleanup |
| Frames written to disk | no recorder, cache or release buffer dump |
| Credentials in logs | centralized, tested redaction |
| Malicious/incorrect T-Box AP | validate record, package and known profile |
| Local video interception | disclose risk; verify protocol before promising encryption |
| Android components invoked by third parties | non-exported service; explicit intents |
| AAR supply chain | reproducible build, commit SHA and artifact checksum |
| Android Auto head-unit identity | keep source files out of Git history; include only in maintainer-built APKs; disclose that packaged APK material is extractable | manual or controlled release workflow |
| Overlay/tapjacking over consent | rely on the system dialog, no deceptive UI |
| AAP TLS peer certificate not validated (`NoCheckTrustManager`) | acceptable only because the session is loopback-only (`127.0.0.1`) with no real network path for a MITM; never bind the AAP listener to a non-loopback interface, and treat that change as re-opening this threat |

## Protected Content

An insecure virtual display obscures `FLAG_SECURE` windows; creating a secure
display requires privileges unavailable to ordinary third-party apps. HUB must
not bypass this limitation or describe black output as a codec bug. Android
reference:
[DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE](https://developer.android.com/reference/android/hardware/display/DisplayManager#VIRTUAL_DISPLAY_FLAG_SECURE).

## Consent UX

- Before the picker, show what will be transmitted and to which motorcycle.
- Do not imitate the Android dialog.
- Make it clear whether one app or the entire screen is shared when the
  information is available.
- Keep a stop action in the notification and app.
- Explain separately the overlay consent used for minimum brightness; the
  overlay must be transparent, non-interactive and active only during streaming.
- After lock/revocation, show that the session ended and request new consent.

## Logging

Allowed events:

- phase state and duration;
- normalized error type;
- encoder profile and codec name;
- frame and performance counters;
- phone model/Android build, with consent during export.

Forbidden events:

- frames, screenshots or thumbnails;
- password and complete QR;
- projection token or serialized Intent;
- unredacted protocol payload;
- captured app names/content without explicit need.

Retention: limited local event history, deletable by the user; export only after
a manual action. Sharing logs creates a diagnostic text file and sends that file
through Android's share sheet.

## Licensing

`ridedaemon-lib` is distributed under GPL-3.0. Embedding its AAR in a
distributed app creates source-code distribution and compatible-license
obligations. The new app must therefore be designed as GPL-3.0 unless the
component is replaced or a different license agreement is reached with the
copyright holder.

Before distribution:

- confirm the fork license and every asset license;
- include license texts and notices in the app;
- decide the source publication policy for each release before distribution;
- document the AAR build procedure;
- avoid marks or descriptions suggesting official CFMoto affiliation.
- never commit Android Auto identity source files or signing credentials;
  provision them only for maintainer builds and document that a public APK
  contains its runtime identity.

This section identifies a technical and organizational constraint and requires
legal review before commercial distribution.

## MVP Privacy Baseline

- fully local processing;
- no account;
- no remote telemetry;
- no MOTO-HUB audio recording; Android Auto microphone transport is live only;
- no frame persistence;
- opt-in diagnostics and manual export;
- privacy page accessible before the first projection starts.
