# ADR-005 - Native OSM Navigation Instead Of Embedding Google Maps

Status: Accepted
Date: 2026-07-19

## Context

Ride Dashboard needs turn-by-turn navigation. Google Maps was evaluated
through three integration paths: a WebView against the Maps JavaScript API
(already tested by the maintainer and confirmed non-viable, Google actively
prevents turn-by-turn use inside a WebView), the Maps SDK for Android
(view-only, no turn-by-turn, no supported offscreen capture), and the
Navigation SDK for Android (has turn-by-turn, but is a gated commercial
product with terms that do not cover re-projection onto a non-certified
vehicle display, and no supported API to render into an arbitrary `Surface`).

Ride Dashboard already draws OpenStreetMap tiles, a live GPS track and
telemetry directly into the `Canvas` feeding the H.264 encoder. Any
navigation source must fit that same model: produce data the render thread
can draw, not a view to embed.

## Decision

Build native turn-by-turn navigation as a new data source into the existing
Ride Dashboard renderer, using Valhalla (Stadia Maps hosted) for routing and
Photon for geocoding, both OSM-based. Route, maneuvers and progress are drawn
by `RideDashboardRenderer` alongside the existing map panel. Google Maps
navigation remains available separately and unchanged through the existing
embedded Android Auto receiver.

## Consequences

- No dependency on Google Maps Platform billing, terms, or certified-display
  requirements for this feature.
- Full control over rendering, consistent with the app's existing dark/lime
  visual language.
- Valhalla and Photon are both viable to run offline later without changing
  the architecture, unlike a Google-based path.
- Requires building and maintaining routing/geocoding clients, map-matching,
  off-route detection and maneuver rendering in-house.
- Adds a new outbound network dependency, bound to the cellular network the
  same way `OpenStreetMapTileProvider` already is (the T-Box Wi-Fi network
  has no Internet).

## Alternatives Considered

- WebView + Maps JavaScript API: confirmed blocked by Google.
- Maps SDK for Android: no turn-by-turn; `snapshot()` is not a continuous
  capture API.
- Navigation SDK for Android: real turn-by-turn, but gated, commercial, and
  its terms do not permit projecting its rendering onto a non-certified
  display; no supported render-to-`Surface` API regardless.
- Mapbox Directions/Navigation: richer instruction payload (SSML, lane
  guidance, shields) but terms favor using the Mapbox basemap, which
  conflicts with staying OSM-based and offline-ready.
