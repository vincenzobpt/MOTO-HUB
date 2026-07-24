# MOTO-HUB - Documentation

Status: active project documentation
Last updated: 19 July 2026

MOTO-HUB is an Android app that connects to a motorcycle T-Box and can stream
screen mirroring or Android Auto to the motorcycle TFT. It also provides local
diagnostics and GitHub APK update checks.

This folder is the source of truth for the product to be built. The repositories
in `external upstream repositories` are technical references and are not the
final app.

## Index

| Document | Purpose |
|---|---|
| [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md) | Goals, scope, UX and acceptance criteria |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Architecture, components, flows and lifecycle |
| [REFERENCE_ANALYSIS.md](REFERENCE_ANALYSIS.md) | Repository analysis and reuse plan |
| [ANDROID_IMPLEMENTATION.md](ANDROID_IMPLEMENTATION.md) | Android APIs, permissions, modules and implementation sequence |
| [TBOX_STREAMING_CONTRACT.md](TBOX_STREAMING_CONTRACT.md) | Observed contract with the T-Box and `ridedaemon-lib` |
| [SECURITY_AND_PRIVACY.md](SECURITY_AND_PRIVACY.md) | Security model, privacy and GPL distribution |
| [TEST_STRATEGY.md](TEST_STRATEGY.md) | Software test strategy and hardware matrix |
| [ROADMAP.md](ROADMAP.md) | Spikes, MVP, later phases and decision gates |
| [RISK_REGISTER.md](RISK_REGISTER.md) | Open risks, impact and mitigations |
| [DYNAMIC_ANDROID_AUTO_PROFILE.md](DYNAMIC_ANDROID_AUTO_PROFILE.md) | Runtime Android Auto orientation profiles and fallback contract |
| [PUBLIC_RELEASE.md](PUBLIC_RELEASE.md) | Manual/public APK release process and private Android Auto identity handling |
| [OPEN_CFMOTO_COMPARATIVE_AUDIT.md](OPEN_CFMOTO_COMPARATIVE_AUDIT.md) | Functional audit against OpenCfMoto and remaining gaps |
| [decisions/README.md](decisions/README.md) | Architecture decision record index |

## Reference Repositories

- `https://github.com/vincenzobpt/ridedaemon-lib`: Go implementation of the
  EasyConn/T-Box protocol and Android-exportable APIs through `gomobile`.
- `https://github.com/charliecharlieO-o/ridedaemon-android`: Kotlin/Compose
  sample app that provisions Wi-Fi, discovers the T-Box, creates a
  `MobileSession`, encodes a synthetic scene and sends frames.

## Terminology

- **HUB**: the new Android app.
- **T-Box / HU / HUD**: EasyConn unit connected to the motorcycle TFT display.
- **Pairing**: in the product, provisioning and connection to the T-Box Wi-Fi
  network. BLE pairing is a separate channel and is not required for MVP video
  streaming.
- **Projection**: an Android `MediaProjection` session authorized by the user.
- **Source**: the complete display or a single app selected in the system picker.
- **Session**: the interval from consent and connection through complete stop.
- **Full Android Auto**: the standalone Android Auto mode. It owns the TFT
  stream and replaces the entire usable T-Box projection area.
- **TFT safe margins**: per-motorcycle pixels excluded because the motorcycle
  UI owns them. MOTO-HUB uses them for Android Auto video and touch mapping.

## Documentation Rules

- A feature not tested on a motorcycle must be marked `To validate`.
- A change at the app/library boundary requires an ADR in `decisions/`.
- Observable requirements belong in `PRODUCT_REQUIREMENTS.md`; code details
  belong in `ANDROID_IMPLEMENTATION.md`.
- Video values confirmed by tests must also be updated in
  `TBOX_STREAMING_CONTRACT.md`.
- GitHub publication is manual unless explicitly stated otherwise. Do not
  assume that source publication and APK publication happen together.
