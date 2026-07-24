# Dynamic Android Auto Capability Profile

Status: implemented; additional motorcycle validation pending.

MOTO-HUB selects one standard Android Auto source profile before the local AAP
handshake:

| Learned T-Box geometry | Android Auto source | Density |
|---|---:|---:|
| Missing or invalid | model fallback, normally `800x480` | 160 dpi |
| Landscape safe area | closest landscape preset: `800x480` or `1280x720` | 160 dpi |
| Portrait safe area | closest portrait preset: `720x1280` or `1080x1920` | 240 dpi |

The selection uses the runtime `VideoArea` saved for the same T-Box SSID, then
applies per-motorcycle safe margins before choosing the closest Android Auto
source. Model profiles can provide safer defaults for known families such as
800NK variants, but exact T-Box dimensions still configure the encoder,
compositor and touch transform.

The user can override the automatic Android Auto source from Settings:

- Landscape 800 x 480 at 160 dpi;
- Landscape 1280 x 720 at 160 dpi;
- Portrait 720 x 1280 at 240 dpi;
- Portrait 1080 x 1920 at 240 dpi.

Manual source overrides affect only the Android Auto service-discovery
contract. They do not replace the EasyConn/T-Box output canvas negotiated at
runtime.

The profile is immutable for an active AAP session so service discovery,
decoder fallback, compositor input and touch coordinates always agree. If a
different orientation is learned during the first session, it is persisted and
used automatically at the next Android Auto start. Restarting AAP mid-session
is deliberately avoided.

TFT touch coordinates do not use this AA source profile. They are normalised
from the live T-Box capture area to the aligned output canvas and only then
mapped into the selected AA source. Consequently, switching between Auto,
portrait/landscape or SD/HD cannot alter where the same physical TFT point
lands.

Per-motorcycle safe margins exclude native motorcycle UI from the effective
Android Auto surface. MOTO-HUB advertises the remaining touch surface to Android
Auto so the phone and TFT use the same coordinate contract.

## Display Modes

Each motorcycle stores an Android Auto display mode:

| Mode | Behavior |
|---|---|
| `FIT` | Preserve the complete active Android Auto image. Bars are allowed when aspect ratios differ. |
| `STRETCH` | Fill the complete available TFT area with geometric stretching. If Android Auto declared internal margins, stretch uses the active content instead of stretching black bars. |
| `CROP` | Fill the complete available TFT area without distortion by cropping edges when aspect ratios differ. |

Full Android Auto applies this mode in `AaCompositor` before encoding to the
T-Box.

`800x480@160` remains the hardware-validated fallback. Android Auto identity,
EasyConn transport and the mirroring pipeline remain separate from source
profile selection.

## Validation Gate

- Ten Android Auto start/stop cycles on the validated landscape motorcycle.
- Stable projection and phone-preview touch mapping on a portrait TFT.
- Corner and centre TFT taps with Auto and every manual source override.
- Invalid or absent saved geometry selects `800x480@160`.
- Mirroring regression test on the validated landscape motorcycle.
