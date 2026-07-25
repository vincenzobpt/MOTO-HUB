# Trip Recording

Status: implemented, hardware validation pending

## Product Behavior

MOTO-HUB can record a ride manually or start recording automatically when the rider launches
mirroring, Android Auto, Ride Dashboard, or native Navigation. Recording runs in a location
foreground service and continues while the phone is locked. A Navigation-owned recording remains
active if the Ride Dashboard or its embedded Android Auto projection stops; it closes only when
the rider stops navigation or reaches the destination.

The Trips screen provides:

- live speed, distance, moving time, maximum speed, and GPS accuracy;
- aggregate ride count, distance, and moving time;
- summary-only history loading for fast startup;
- an interactive OpenStreetMap track with pan, pinch zoom, zoom buttons, and route fitting;
- trip naming and deletion;
- standards-based GPX 1.1 export;
- local-only storage unless the rider explicitly exports a GPX file.

While Ride Dashboard is active, its phone session screen includes a persistent `Show recorded GPS
track` switch. When enabled, every point accepted by the trip recorder appears immediately on the
TFT map as a cyan route and individual GPS markers. Disabling the overlay does not stop recording.

## Storage And Recovery

Trips and points are stored in `moto_hub_trips.db`. SQLite write-ahead logging permits map/history
reads while the recorder commits progress. Points are buffered in small batches and committed with
their summary statistics in one transaction every eight points or eight seconds.

An active trip remains marked in the database. If Android recreates the service, recording resumes
from the last committed summary and point. At most one small in-memory batch can be lost after an
ungraceful process termination.

The list query does not load track points. Full coordinates are read only when a trip is opened or
exported, avoiding the reference implementation's memory growth as the library expands.

The live TFT overlay is bounded independently from storage. Once its display buffer grows beyond
4,096 points, older display points are progressively downsampled while preserving the beginning,
end, and overall route shape. The database and exported GPX retain every accepted point.

## GPS Filtering

The accumulator rejects:

- invalid coordinates;
- fixes less accurate than 60 metres;
- physically implausible segments above 80 metres per second;
- gaps longer than 20 seconds for distance and moving-time integration.

Track storage is spatially and temporally reduced: a point is retained after four metres of travel
or ten seconds. This preserves route shape while reducing database, rendering, and GPX overhead.
Recordings shorter than 100 metres, 15 seconds of movement, or two retained points are discarded.

## Map And Privacy

The trip map reuses MOTO-HUB's bounded OpenStreetMap tile cache and identifies the application with
a compliant user agent. Opening a map discloses the viewed tile area to the OpenStreetMap tile
service. GPS tracks themselves are not uploaded by MOTO-HUB.
