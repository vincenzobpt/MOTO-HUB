# Tooling

## Ridedaemon AAR

The Android app consumes the generated `hudlib.aar` artifact from the
[MOTO-HUB ridedaemon fork](https://github.com/vincenzobpt/ridedaemon-lib).
The artifact is stored at:

```text
apps/android/app/libs/hudlib.aar
```

After installing Go and `gomobile`, clone the fork beside the MOTO-HUB
repository and rebuild the artifact with:

```bash
git clone https://github.com/vincenzobpt/ridedaemon-lib ../ridedaemon-lib
cd ../ridedaemon-lib
gomobile bind -target=android -androidapi 34 -o ../MOTO-HUB/apps/android/app/libs/hudlib.aar ./hud/api
```

The source commit, gomobile API level, checksum, and license are recorded in
[`ridedaemon.lock`](ridedaemon.lock). Update that file whenever the artifact
changes.

The Android build also packages the static H.264 fallback signal from
[`assets/static_signal.h264`](assets/static_signal.h264).
