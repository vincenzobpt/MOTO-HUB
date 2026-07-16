# Dynamic Android Auto Capability Profile

Status: landscape hardware validation passed in `0.8.2-beta.8`; physical
portrait TFT validation pending.

MOTO-HUB selects one standard Android Auto source profile before the local AAP
handshake:

| Learned T-Box geometry | Android Auto source | Density |
|---|---:|---:|
| Missing, invalid or landscape | `800x480` | 160 dpi |
| Portrait | `720x1280` | 240 dpi |

The selection uses only the runtime `VideoArea` saved for the same T-Box SSID.
It never infers a motorcycle model from QR metadata, SSID naming or marketing
names. Exact T-Box dimensions still configure the encoder and compositor.

The profile is immutable for an active AAP session so service discovery,
decoder fallback, compositor input and touch coordinates always agree. If a
different orientation is learned during the first session, it is persisted and
used automatically at the next Android Auto start. Restarting AAP mid-session
is deliberately avoided.

`800x480@160` remains the hardware-validated fallback and keeps the beta.7
landscape behavior byte-compatible. Android Auto identity, EasyConn transport
and the mirroring pipeline are unchanged.

## Validation Gate

- Ten Android Auto start/stop cycles on the validated landscape motorcycle.
- Stable projection and phone-preview touch mapping on a portrait TFT.
- Invalid or absent saved geometry selects `800x480@160`.
- Mirroring regression test on the validated landscape motorcycle.
