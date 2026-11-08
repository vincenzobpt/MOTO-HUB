# Tooling

## Ridedaemon AAR

The Android app consumes the generated `hudlib.aar` artifact from the
[MOTO-HUB ridedaemon fork](https://github.com/vincenzobpt/ridedaemon-lib).
The artifact is stored at:

```text
apps/android/app/libs/hudlib.aar
```

After installing Go and `gomobile`, clone the MOTO-HUB-specific ridedaemon-lib next to this
checkout and rebuild the artifact with:

```bash
git clone https://github.com/vincenzobpt/ridedaemon-lib ridedaemon-lib-motohub
cd ridedaemon-lib-motohub
gomobile bind -target=android -androidapi 34 -o ../MOTO-HUB/apps/android/app/libs/hudlib.aar ./hud/api
```

The source commit, gomobile API level, checksum, and license are recorded in
[`ridedaemon.lock`](ridedaemon.lock). Update that file whenever the artifact
changes.

MOTO-HUB configures RideDaemon in live-only mode. The historical stream under
`assets/` is retained as a diagnostic fixture but is not packaged in the APK or
used as a runtime fallback because its fixed geometry is not portable across
T-Box displays.

## Navigation Routing Key

Native turn-by-turn navigation calls the Stadia Maps hosted Valhalla routing
API. There is no bundled or shared key: every rider enters their own free
Stadia Maps API key in **Settings > Navigation** inside the app. It is
encrypted on-device with Android Keystore the same way the T-Box Wi-Fi
password is (`NavigationSettingsStore`), and is never part of the build or
the repository. Without a key configured, route requests fail explicitly
with a message pointing back to Settings.
