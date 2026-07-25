# MOTO-HUB T-Box Simulator

macOS-side T-Box emulator for testing MOTO-HUB without a motorcycle.

The simulator advertises `_EasyConn._tcp`, accepts the EasyConn init handshake,
connects back to the Android reverse ports (`10920`, `10921`, `10922`),
negotiates the TFT area, polls the H.264 stream and opens it in `ffplay`.

The simulator distinguishes the physical TFT from the rectangle available to
phone projection. Motorcycle-owned UI such as speed, RPM and gear may reserve
part of the physical display and is not part of the H.264 canvas.

## Prerequisites

- macOS and the Android phone on the same Wi-Fi network;
- Go 1.25 or newer;
- `ffplay` from FFmpeg for the video preview.

## Run

From `MOTO-HUB`:

```bash
cd tools/tbox-simulator
go run ./cmd/tbox-simulator \
  -profile motohub \
  -display-width 800 \
  -display-height 480 \
  -safe-x 0 \
  -safe-y 0 \
  -width 800 \
  -height 384 \
  -heartbeat 1s
```

`-profile` selects the T-Box compatibility profile advertised over Bonjour and
reported during the PXC handshake:

- `motohub`: development profile optimized for MOTO-HUB.
- `cfdl16`: legacy CFDL16 / EasyConn landscape profile, model ID `37416`.
- `cfdl26-portrait`: CFDL26 MotoPlay portrait profile, model ID `37426`.
- `cfdl26-landscape`: CFDL26 MotoPlay landscape profile, model ID `37426`.
- `800nk-crcp`: 800NK CRCP/sdk `0.9.23.x` non-touch profile.
- `800nk-touch`: 800NK touch profile with a measured `720 x 712` projection area.
- `66660742`: CFDL16-class MotoPlay landscape profile for model ID `66660742`.

The third-party profiles advertise realistic model IDs, package names, HUID/HU
names, touch capability, and support flags. They also open the second PXC
`CAR_DATA` channel and, for CFDL26/CRCP-style profiles, send the additional
configuration notify burst that compatible apps must ACK before media starts.

`-display-width` and `-display-height` describe the complete physical TFT.
`-safe-x`, `-safe-y`, `-width` and `-height` describe the projection rectangle
inside it. Only the projection width and height are negotiated with MOTO-HUB;
the offsets exist to make the desktop preview representative of the physical
layout. The default macOS `Auto` profile models the measured case of an
`800 x 480` TFT with an `800 x 384` projection area at `(0, 0)`.

To build the native macOS wrapper:

```bash
./build-macos-app.sh
open ./MOTO-HUB-TBox-Simulator.app
```

The SwiftUI window controls the simulator core, shows session logs and renders
a pairing QR compatible with the selected T-Box profile. The received TFT video opens in a
separate `ffplay` window. That window represents the full physical TFT: dark
regions outside the green projection outline stand for motorcycle-reserved UI.
The profile and manual physical/projection geometry are persisted between
launches. The `Handlebar controls` panel injects the same logical gestures used
by MOTO-HUB's handlebar mapping (`Up`, `Down`, `Select`, double taps, hold,
Backward and Forward). It is a simulator-only test channel and does not emulate
Bluetooth AVRCP itself.

The normal home Wi-Fi is sufficient: the Mac and Android phone can remain on
the network they already use. The app pre-fills the current Mac Wi-Fi SSID;
enter the Wi-Fi password, then scan the displayed QR from MOTO-HUB. A separate
macOS hotspot is only needed when the home router blocks device-to-device
Bonjour/TCP traffic.

Pairing flow:

1. Start the Mac hotspot or join the shared Wi-Fi network.
2. Open the simulator and make the displayed QR use that network's SSID/password.
3. In the target Android app, open pairing and scan the QR shown by the simulator.
4. Press Connect / Find T-Box in the Android app.
5. Start the projection mode from the Android app.

The simulator advertises the same EasyConn package metadata as the real CFMOTO
T-Box. Android NSD should discover it automatically. If the Mac has multiple
interfaces, pass the hotspot address explicitly with `-ip`.

When launched from the macOS app, the EasyConn and local control ports are
selected automatically, so an old simulator process cannot block startup. The
default Bonjour service name includes the selected EasyConn port to prevent
Android from reusing stale NSD records after a restart. The standalone CLI
keeps `http://127.0.0.1:8765` as its default control endpoint:

```bash
curl http://127.0.0.1:8765/status
curl -X POST http://127.0.0.1:8765/gesture/pinch
curl -X POST http://127.0.0.1:8765/gesture/rotate
curl -X POST http://127.0.0.1:8765/touch \
  -H 'Content-Type: application/json' \
  -d '{"action":"down","pointerId":0,"x":400,"y":192}'
curl -X POST http://127.0.0.1:8765/handlebar \
  -H 'Content-Type: application/json' \
  -d '{"gesture":"volumeUp"}'
```

The received stream can also be saved for inspection:

```bash
go run ./cmd/tbox-simulator -video-dump /tmp/motohub-dashboard.h264
```

Use `-no-heartbeat` to reproduce the 800NK-style phone-side heartbeat failure.
Use `-player ''` for headless protocol tests.

## Scope

This is a development simulator, not a replacement for final motorcycle
validation. It exercises EasyConn discovery, reverse sockets, PXC handshake,
heartbeat, media negotiation, H.264 delivery and injected touch events. It
also injects logical handlebar gestures into MOTO-HUB when the simulator
profile is active. It does not emulate Wi-Fi Direct firmware behavior, the
physical TFT decoder, Bluetooth AVRCP pairing, or the actual motorcycle gauges.
For an unmeasured motorcycle, physical TFT size, projection offset and
projection size are test hypotheses until verified on that motorcycle.
