# T-Box and Streaming Contract

Status: derived from reference repositories and the author's notes
Warning: this is not an official CFMoto/EasyConn specification.

## Interface Responsibilities

The T-Box exposes two separate areas:

- BLE: simple authentication, alerts, alarms and media commands.
- Wi-Fi AP: EasyConn discovery, control and video sent to the TFT.

MOTO-HUB uses only the second channel in the MVP. Calling this phase "pairing"
does not mean that BLE is a technical prerequisite for streaming.

## Discovery and Channels

Contract observed in `ridedaemon-lib`:

| Phase | Endpoint | Purpose |
|---|---|---|
| Discovery | mDNS `_EasyConn._tcp.` | EC host, port and TXT attributes |
| EC init | advertised host/port | initial client identification |
| PXC | TCP `10922` | handshake, config, encrypted HUID, heartbeat |
| Media control | TCP `10921` | screen config, commands and ping |
| Media stream | TCP `10920` | H.264 access units requested by T-Box |

Relevant TXT values observed:

- `packagename`, normally `com.cfmoto.cfmotointernational`;
- `ip`, with fallback to the address resolved by NSD;
- EC service port.

Do not assume that IP and subnet are identical across all models/firmware.

## Stream Clock

The T-Box polls for the next frame. The transport responds with the next
available access unit; without a poll, no frame is sent. The T-Box is therefore
the effective media-stream clock.

Implications:

- avoid growing buffers on Android;
- a recent frame is more valuable than a complete queue of old frames;
- encoder fps and session-configured fps must be consistent;
- heartbeat/control and frame delivery share a lifecycle but use separate
  channels.

## Required Video Format

Known baseline:

| Property | Initial value |
|---|---|
| Codec | H.264/AVC |
| Resolution | runtime T-Box capture area, aligned down to 16-pixel macroblocks |
| Frame rate | `15-30 fps` |
| Bitrate | `2-5 Mbps`, starting at `2.5 Mbps` |
| Structure | non-buffered, very short GOP, predictive but intra-tolerant |
| B-frame | unsupported/avoid |
| Wire access unit | Annex-B, delimited by AUD |
| Android API input | AVCC with 4-byte NAL lengths |

`MobileSession.pushFrame()` accepts the sample produced by `MediaCodec`. The
library detects AVCC/Annex-B, converts AVCC to Annex-B and prepends an AUD NAL.
The media-control capture request is the resolution source of truth. MOTO-HUB
must acknowledge arbitrary valid dimensions and configure the Android encoder
from that request rather than from a motorcycle model table.

### Encoder Invariants

- Each call must represent a complete access unit, not an arbitrary stream
  fragment.
- Keyframes must carry SPS/PPS if codec-config buffers are ignored.
- Long GOPs increase recovery time and may break the decoder.
- An encoder that introduces B-frames is incompatible with the low-latency,
  polling assumption.
- Timestamps are controlled externally by the session; do not implement a
  player with a jitter buffer in the app.

## Configuration and Safe Area

The Android reference waits for a library event, parses JSON and reads:

```text
viewAreaConfig.viewAreas[0].safeArea.width
viewAreaConfig.viewAreas[0].safeArea.height
```

Only positive values may override the default profile. Parsing must be isolated
and tested with missing, malformed and multiple view-area payloads.

`To validate`: event-type meaning, JSON schema stability and behavior of
firmware that does not send the safe area.

## Static Signal

`MobileConfig` requires bytes from a static stream. The library creates a
fallback source and alternates between static/live sources. The asset must be:

- valid H.264 Annex-B;
- compatible with the target resolution and decoder;
- separable into access units through AUD;
- short and legally distributable.

The build must include a test that verifies parsing and presence. Do not use an
empty placeholder file.

## Motorcycle Assumptions To Verify

- model, year and T-Box firmware version;
- IP/subnet and TXT record;
- effective safe area and orientation;
- accepted AVC profile (Baseline/Main), level and entropy mode;
- maximum tolerated GOP;
- stable bitrate and upper limit;
- behavior during frame drops, Wi-Fi loss and reconnect;
- any media-payload encryption.

The handshake uses RSA for specific data such as HUID; this does not prove that
video is encrypted end to end. Until the wire protocol is verified, treat video
as observable by an actor present on the local T-Box network.

## Compatibility and Versioning

Each hardware test must record:

```text
Motorcycle/model year:
TFT firmware:
T-Box hardware/firmware:
Phone and Android build:
Selected hardware codec:
Resolution/fps/bitrate/profile:
ridedaemon-lib commit:
Result and anomalies:
```

No model should be declared supported based only on protocol similarity.
