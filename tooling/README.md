# Tooling

## Ridedaemon AAR

The Android app consumes the generated `hudlib.aar` artifact from the
[MOTO-HUB ridedaemon fork](https://github.com/vincenzobpt/ridedaemon-lib).
The artifact is stored at:

```text
apps/android/app/libs/hudlib.aar
```

After installing Go and `gomobile`, use the MOTO-HUB-specific checkout at
`refs/ridedaemon-lib-motohub` and rebuild the artifact with:

```bash
git clone https://github.com/vincenzobpt/ridedaemon-lib refs/ridedaemon-lib-motohub
cd refs/ridedaemon-lib-motohub
gomobile bind -target=android -androidapi 34 -o ../../MOTO-HUB/apps/android/app/libs/hudlib.aar ./hud/api
```

The source commit, gomobile API level, checksum, and license are recorded in
[`ridedaemon.lock`](ridedaemon.lock). Update that file whenever the artifact
changes.

MOTO-HUB configures RideDaemon in live-only mode. The historical stream under
`assets/` is retained as a diagnostic fixture but is not packaged in the APK or
used as a runtime fallback because its fixed geometry is not portable across
T-Box displays.
