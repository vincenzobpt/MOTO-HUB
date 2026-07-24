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
Reverse TCP listeners `10920`, `10921`, and `10922` must be open before the EC
init probe is sent because a T-Box may connect back immediately.

If NSD supplies a valid service package and port but no IPv4 host, Android may
derive the peer from same-subnet route/DNS information. A `.1` Wi-Fi Direct
group-owner fallback is allowed when the local IPv4 prefix is known and the
derived network address is valid. MOTO-HUB does not invent a service port or
package when discovery itself fails.

### Wi-Fi Direct Group Owner dashes (`DIRECT-` SSIDs)

Some dashes (CL-C450 class, and units advertising SSIDs like `DIRECT-go-CFMOTO-xxxx`)
run the EasyConn head unit as a Wi-Fi Direct **Group Owner**, not as a normal WPA2
access point. `WifiNetworkSpecifier` cannot associate to a GO as a proper P2P client —
on some units the join even *appears* to succeed (DHCP hands out an address) while the
dash never treats the phone as a connected client, so every EasyConn port stays closed
and mDNS never answers. `TBoxLinkResolver` therefore routes `DIRECT-` SSIDs through
`TBoxWifiDirectConnector`, which joins the group by credentials via `WifiP2pManager`
(ported from OpenCfMoto's `BikeWifiP2p`). A P2P group has no
`ConnectivityManager.Network`; sockets bind to the phone's `192.168.49.x` source
address (`TBoxLink.WifiDirect`) and the dash is the group owner at `192.168.49.1`.
All other SSIDs keep the existing `WifiNetworkSpecifier` path (`TBoxLink.Infrastructure`).

### Wake probe on Wi-Fi Direct links (`DIRECT-` SSIDs)

Some T-Box units on a Wi-Fi Direct group-owner AP (SSID starts with `DIRECT-`)
never broadcast an `_EasyConn._tcp.` advertisement on their own; they only
respond once directly asked. `RideDaemonTransport.sendEasyConnWakeProbe()`
sends this request (`CMD_MDNS_RESPOND`, `0x70000010`, on the well-known port
`10930`) to the `.1`-derived peer as a last resort, after both regular NSD
discovery windows time out. This does not supply the EC host/port: an ack
only re-arms one more real NSD window on an infrastructure link, so the "no
invented port/package" rule above still holds there.

On a **Wi-Fi Direct group** there is no bindable `Network`, so NSD cannot
resolve the service even after the wake probe. In that case only, a completed
probe ACK (a full `CMD_MDNS_RESPOND` handshake) is treated as proof of the EC
endpoint, and the host is taken directly as the group owner
(`192.168.49.1:10930`, package `com.cfmoto.cfmotointernational`). This is a
*confirmed* endpoint, not an invented one, and matches what every reference
implementation does for P2P dashes. Frame layout and port were
reverse-engineered by OpenCfMoto/OpenMoto, not confirmed by an official spec.

## Heartbeat, ACK And Model-Specific Compatibility

The Android transport records PXC and MediaControl command IDs, sequence
numbers and payload sizes in the persistent diagnostic log. It also records
the age of the last control event and last offered video frame when the
session stops.

MOTO-HUB now carries model-aware compatibility behavior inspired by OpenCfMoto:

- known 800NK/CRCP identifiers and HUID/firmware patterns select an 800NK
  behavior profile, while modelId `66660732` selects the distinct MTX800
  portrait profile;
- affected 800NK and MTX800 sessions send the dual PXC heartbeat on both
  CAR_CTRL and CAR_DATA to avoid the firmware-side timeout seen on those
  displays;
- additional PXC configuration, OTA, or media setup frames are acknowledged
  with the expected command-plus-one ACK where supported by the profile;
- non-touch profiles and the global `Disable touchscreen` setting avoid
  advertising a touch surface to Android Auto when the real motorcycle UX is
  focus/handlebar based.

These behaviors must remain profile-gated or capability-gated. Do not apply a
firmware-specific heartbeat or ACK rule globally until it has been validated on
the generic and simulator profiles.

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
- When the TFT sends media command `112`, stale queued access units must be
  discarded and the encoder must provide a fresh SPS/PPS/IDR sequence.
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

The safe-area width and height are the encoder canvas available to MOTO-HUB;
they must not be treated as proof of the complete physical TFT resolution. A
motorcycle may render speed, RPM, gear and other native information outside
that rectangle. The observed safe-area payload does not currently establish
the physical display dimensions or the safe area's X/Y offset.

The macOS simulator therefore models two separate geometries:

- physical TFT width and height, used only for the full-display preview;
- projection X/Y/width/height, whose width and height are negotiated with the
  Android transport and used for touch coordinates.

The known `Auto` test profile is physical `800 x 480` with projection
`800 x 384` at `(0, 0)`. The size difference is measured; the top-left
placement remains an explicit simulator assumption until confirmed on the
motorcycle. Other motorcycle profiles must remain manually configurable until
their physical and projection geometry has been measured.

The Android app treats the negotiated projection width and height as the
encoder canvas available to MOTO-HUB. It does not assume that the simulator's
full preview window equals the real motorcycle safe area. Per-motorcycle safe
margins can further exclude native motorcycle UI from Android Auto video and
touch when a T-Box reports a canvas larger than the rider-usable projection
area.

Touch mapping must remain independent from the selected Android Auto source
resolution. MOTO-HUB first normalises raw T-Box coordinates from the runtime
capture area into the macroblock-aligned AVC canvas, then applies the inverse
of the actual visible Android Auto viewport. Manual AA presets affect only the
last source geometry and cannot replace the T-Box touch domain.

Touch coordinates outside the runtime capture area are dropped and recorded in
diagnostics instead of being clamped into an unrelated on-screen location. Such
an event indicates that the firmware may report physical-panel coordinates,
safe-area offsets or a rotated touch controller. The touch packet contains no
declared coordinate extents, so that case requires corner-touch measurements
from the affected motorcycle before a model-independent affine transform can be
derived safely.

For Android Auto, touch mapping is based on the actual compositor viewport:

- `FIT` maps only the visible non-bar region;
- `STRETCH` maps the full active Android Auto content across the full usable
  TFT area;
- `CROP` maps through the cropped source rectangle and rejects points outside
  the usable projection area.

`To validate`: event-type meaning, JSON schema stability and behavior of
firmware that does not send the safe area, plus whether any firmware reports
safe-area offsets or full physical TFT dimensions elsewhere.

## Live-Only Startup

MOTO-HUB creates `MobileConfig` without a static source. A fixed-resolution
fallback can be incompatible with the runtime TFT area and can replace an
otherwise healthy stream during a short encoder pause.

On media command `112`, RideDaemon clears stale frames and waits for a fresh
IDR before serving the TFT. Android requests that sync frame immediately,
caches codec configuration, and prepends missing SPS/PPS to the keyframe. A
surface-input repeat interval keeps static screens producing frames without
switching sources.

## Motorcycle Assumptions To Verify

- model, year and T-Box firmware version;
- IP/subnet and TXT record;
- effective safe area and orientation;
- physical TFT size and projection-area X/Y placement;
- raw touch coordinates at the projection area's four corners and orientation;
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
Physical TFT size:
Projection area X/Y/width/height:
Selected hardware codec:
Resolution/fps/bitrate/profile:
ridedaemon-lib commit:
Result and anomalies:
```

No model should be declared supported based only on protocol similarity.
