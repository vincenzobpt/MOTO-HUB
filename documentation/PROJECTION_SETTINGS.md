# Projection Settings

Status: implemented, motorcycle validation pending

MOTO-HUB stores application-wide preferences in private Android shared
preferences and per-motorcycle display preferences in the Garage. New global
values are read when the next projection starts; an active encoder or Android
Auto contract is not mutated in place.

## General

General settings include optional seamless resume. When enabled, MOTO-HUB asks
for Android overlay permission because a long-running projection may need to
park and resume without losing the user-visible control surface. The preference
remains a normal on/off switch: granting permission enables the feature, and
turning the switch off disables it without revoking the Android permission.

The app also supports GitHub update checks. The updater considers both releases
and pre-releases, selects only the latest APK release newer than the installed
build, labels pre-releases as such, and shows release notes before installation.
It does not list every historical GitHub release.

## Video Quality

| Mode | Bitrate | Purpose |
|---|---:|---|
| Smoother | 70% of the negotiated base | Lower network load and heat |
| Balanced | 100% of the negotiated base | Preserves previous MOTO-HUB behavior |
| Sharper | 160% of the negotiated base | Crisper text and maps with higher load |

Only H.264 bitrate changes. T-Box output geometry, frame rate, Baseline profile,
keyframe behavior, and transport timing remain under the existing negotiated
pipeline. The setting applies to mirroring, Android Auto, and Ride Dashboard.

Power mode can also be automatic. In automatic mode, MOTO-HUB can lower frame
pressure/bitrate when thermal or link conditions degrade and recover upward when
the stream stabilizes. This is intentionally independent from the selected
visual layout mode.

`Disable touchscreen` forces Android Auto to avoid advertising TFT touch even
when the motorcycle reports touch capability. Use it for motorcycles whose TFT
firmware declares touch but whose real rider controls are handlebar/focus based.

## Android Auto Resolution And Orientation

`Auto` uses the learned T-Box geometry and per-motorcycle safe margins to pick
the closest standard Android Auto source. Portrait displays select a portrait
preset, landscape displays select a landscape preset, and missing/invalid
geometry falls back to the model default.

Manual Android Auto source overrides are:

- Landscape 800 x 480 at 160 dpi;
- Landscape 1280 x 720 at 160 dpi;
- Portrait 720 x 1280 at 240 dpi;
- Portrait 1080 x 1920 at 240 dpi.

These values define the Android Auto source advertised through AAP service
discovery. They do not replace the EasyConn/T-Box output canvas. MOTO-HUB still
learns, aligns, and encodes the TFT geometry negotiated at runtime. HD modes
increase decoder and compositor load and may be rejected by some Android Auto
or phone codec implementations.

## Per-Motorcycle Android Auto Display Mode

The Garage stores Android Auto display mode per motorcycle:

| Mode | Meaning |
|---|---|
| `FIT` | Preserve the complete active AA image. Bars are expected when aspect ratios differ. |
| `STRETCH` | Fill the available TFT area by stretching the active AA content. |
| `CROP` | Fill the available TFT area without stretching by cropping edges. |

The same setting is used by full Android Auto and by embedded Android Auto in
Ride Dashboard. Full Android Auto applies the mode in the GPU compositor before
encoding to the T-Box. Ride Dashboard applies it when placing the in-memory AA
frame inside the dashboard region.

Each Garage profile also stores TFT safe margins. These margins remove
motorcycle-owned UI areas from Android Auto video and touch mapping. They are
not a replacement for the runtime T-Box safe area; they are an additional
per-model/per-user calibration layer.

## Auto-Connect On Launch

When enabled, MOTO-HUB makes an automatic connection attempt on app launch if
an active motorcycle profile exists and the session is disconnected. Runtime
Wi-Fi and location permissions are still respected. After the user stops one of
the three projection modes, MOTO-HUB can automatically reconnect to the saved
motorcycle so the mode-selection screen is ready again.

## Auto-Recovery Watchdog

The watchdog starts only after Android Auto has streamed successfully. Every
five seconds it verifies that outgoing frames accepted by the T-Box continue to
advance. A ten-second stall, EasyConn termination, encoder failure, or T-Box
network loss starts recovery when the preference is enabled.

Recovery keeps the local Android Auto receiver and compositor alive, detaches
the failed encoder surface, reacquires the saved T-Box network if necessary,
repeats EasyConn discovery and handshake, creates a fresh encoder, and resumes
the stream. Initial startup failures and deliberate stops remain terminal so a
bad configuration cannot create an uncontrolled restart loop.

Diagnostics recognise common environmental blockers, including Always-on VPN
or VPN kill-switch behavior that prevents local T-Box sockets, and conflicts
with the official CFMOTO/EasyConnect app occupying the same local ports. The UI
surfaces targeted actions such as retrying or opening the official app's Android
settings page.
