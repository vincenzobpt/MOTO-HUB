# Security, Privacy and Licensing

Status: initial threat model, not legal advice

## Processed Data

| Data | Required for | Proposed persistence |
|---|---|---|
| Screen frames | essential to streaming | volatile memory only |
| Projection consent/token | essential to session | never persisted |
| T-Box SSID/password | connection | private preferences; password AES-GCM encrypted with Android Keystore key |
| IP/MAC/serial/HUID | protocol/diagnostics | redacted in logs |
| Session log | support | local, limited retention |
| Audio | not used in MVP | no capture |

## Trust Boundaries

- Android OS mediates network and projection consent.
- HUB sees all unprotected pixels of the selected source.
- `ridedaemon-lib` and embedded native/Go code run in the same process.
- The T-Box and local Wi-Fi network must not be considered a secure Internet
  channel by default.
- Other captured apps are outside HUB's control.

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
| Android Auto head-unit identity | keep certificate/private key out of public source and public APKs | private build input only |
| Overlay/tapjacking over consent | rely on the system dialog, no deceptive UI |

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

Proposed retention: limited local ring buffer, deletable by the user; export only
after a manual action.

## Licensing

`ridedaemon-lib` is distributed under GPL-3.0. Embedding its AAR in a
distributed app creates source-code distribution and compatible-license
obligations. The new app must therefore be designed as GPL-3.0 unless the
component is replaced or a different license agreement is reached with the
copyright holder.

Before distribution:

- confirm the fork license and every asset license;
- include license texts and notices in the app;
- publish the corresponding source for the distributed version;
- document the AAR build procedure;
- avoid marks or descriptions suggesting official CFMoto affiliation.
- never commit or publicly distribute the Android Auto private identity files; use a private sideload build when Android Auto support is required.

This section identifies a technical and organizational constraint and requires
legal review before commercial distribution.

## MVP Privacy Baseline

- fully local processing;
- no account;
- no remote telemetry;
- no audio recording;
- no frame persistence;
- opt-in diagnostics and manual export;
- privacy page accessible before the first projection starts.
