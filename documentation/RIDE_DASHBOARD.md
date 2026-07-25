# Ride Dashboard

Status: implemented, motorcycle validation pending

The Ride Dashboard is a native scene rendered at the negotiated TFT geometry
directly into its AVC encoder surface. It remains a separate operating mode
from screen mirroring and full Android Auto. Its map region can show either the
native OpenStreetMap view or an Android Auto source composited inside the
dashboard while the surrounding dashboard panels remain native.

## Implemented Scope

- GPS ground speed and course from the phone GNSS receiver;
- GNSS fix age, accuracy, visible satellites and satellites used in the fix;
- trip distance, elapsed time, average speed and maximum speed;
- altitude when reported by Android Location;
- an OpenStreetMap view centred on the current position with the accepted GNSS
  track overlaid;
- a persistent OpenStreetMap/Android Auto selector for the dashboard map region;
- Android Auto composition with the per-motorcycle `FIT`, `STRETCH`, or `CROP`
  display mode and TFT touch forwarding inside the actual visible,
  unobstructed Android Auto viewport;
- live phone preview and phone touch/D-pad control for the same embedded
  Android Auto session, including motorcycles without a touch TFT;
- phone battery, cellular-network availability, encoder bitrate, target FPS and
  T-Box identity;
- dedicated foreground-service notification and stop action.

## Physical Layout Controls

When handlebar control is enabled and the Ride Dashboard is streaming, the
motorcycle's Bluetooth buttons change the rendered layout without affecting the
T-Box transport:

- each Up press cycles through both panels, right panel hidden, both panels
  hidden, left panel restored, then both panels again;
- Down toggles a map-only fullscreen view for the selected map source and
  restores the exact panel layout that was active before fullscreen;
- Up does not alter the remembered panel layout while fullscreen is active;
- panels slide beyond the display edges while the map expands continuously;
  fullscreen also retracts the header and technical rail.

Both the regular and larger absolute-volume gestures are treated as one layout
step because Bluetooth implementations report handlebar presses with different
volume deltas. Android Auto keeps its configurable handlebar mapping unchanged.
After dashboard streaming starts, MOTO-HUB briefly re-asserts its media session
and audio focus so an already connected motorcycle refreshes its AVRCP target.

Handlebar gesture timing is configurable in Settings -> Handlebar Controls:
double-tap windows are 200, 300, or 450 ms, and Select hold thresholds are 500,
600, or 800 ms. Single presses wait for the selected double-tap window. Select
hold requires a real Bluetooth key-up from the Enter / star button; volume-based
buttons do not expose release events. Select double tap and discrete previous /
next-track double taps are also remappable in the same screen.

When OpenStreetMap is selected, tile requests are opened explicitly on Android's
cellular network while the EasyConn stream remains on the T-Box Wi-Fi. Only
tiles visible in the current dashboard viewport are requested. They are cached
locally for at least seven days; MOTO-HUB does not expose bulk download or
offline-region features. The dashboard displays the required `OpenStreetMap
contributors` attribution.

When Android Auto is selected, the Ride Dashboard service still owns the only
T-Box transport and final encoder. The existing local Android Auto receiver and
decoder render into an in-memory surface. The decoded AA source replaces only
the map region; native dashboard panels, header and technical rail remain
native unless fullscreen is active.

The Android Auto map region uses the same per-motorcycle display mode as full
Android Auto:

- `FIT` preserves the complete active AA image and may leave bars when aspect
  ratios differ;
- `STRETCH` fills the released dashboard space by stretching the active AA
  content;
- `CROP` fills the released dashboard space without distortion and crops edges
  when required.

Cycling panels progressively expands the Android Auto region into the space
freed by the side panels. Fullscreen removes the dashboard chrome and uses the
complete negotiated projection area. Covered regions reject TFT touches, and
touch mapping follows the actual destination rectangle after fit/stretch/crop
placement. This composition is encoded as one dashboard frame and never starts
a second T-Box session.

The embedded compositor exposes two simultaneous outputs: the dashboard frame
reader and the phone preview. Opening `Preview & touch control` from the active
Ride Dashboard therefore shows and controls the same Android Auto session
already visible in the map region. Touches are mapped from the phone preview to
the Android Auto source coordinates; opening or closing the phone preview does
not restart Android Auto or the T-Box stream.

For motorcycles without a touch TFT, the phone preview and on-screen D-pad are
the expected control surface for embedded Android Auto. For motorcycles with a
touch TFT, raw T-Box touches are mapped through the negotiated projection area,
safe margins, and visible AA viewport before being sent to Android Auto.

## Data Provenance

| Value | Source |
|---|---|
| Speed, course, altitude, accuracy and satellites | Android GNSS/location APIs |
| Trip, average, maximum and breadcrumb track | Calculated locally by MOTO-HUB |
| Native street map | OpenStreetMap standard raster tiles over the cellular network |
| Embedded navigation surface | Local Android Auto Projection receiver |
| Battery and cellular availability | Android system APIs |
| FPS, bitrate and video geometry | Active MOTO-HUB encoder configuration |
| T-Box identity | EasyConn discovery and CLIENT_INFO capability snapshot |

The current T-Box protocol does not provide verified road speed, RPM, selected
gear, fuel level, engine temperature or odometer values. The dashboard must not
invent or label phone-derived values as motorcycle telemetry.

## Filtering And Safety

- samples with accuracy worse than 60 metres are visible as diagnostic fixes
  but excluded from distance and track calculations;
- small movements inside the GNSS accuracy radius are treated as jitter;
- physically unreasonable jumps are excluded from trip distance;
- a fix older than fifteen seconds is shown as stale and speed returns to zero;
- the phone display may be locked because this mode does not use
  `MediaProjection`.

The feature is informational only. It must not replace the motorcycle's
homologated instrumentation or be the rider's only navigation source.

## Later Increments

1. Per-motorcycle dashboard layout and map-source selection.
2. Configurable OSM-derived tile providers and zoom behaviour.
3. Route planning, manoeuvre guidance and ETA for the native map source.
4. Optional calibrated phone-sensor widgets that are clearly identified as
   estimates rather than T-Box telemetry.
