<div align="center">

<img src="media/logo.png" alt="MOTO-HUB logo" width="120">

# MOTO-HUB

**Android Auto, live dashboards and motorcycle navigation on your bike's TFT display — free.**

[![Latest release](https://img.shields.io/github/v/release/vincenzobpt/MOTO-HUB?label=release&color=44cc11)](https://github.com/vincenzobpt/MOTO-HUB/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/vincenzobpt/MOTO-HUB/total?color=44cc11)](https://github.com/vincenzobpt/MOTO-HUB/releases)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)
[![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![7 languages](https://img.shields.io/badge/languages-7-orange)](#what-moto-hub-does)
[![Discord](https://img.shields.io/badge/Discord-join%20the%20community-5865F2?logo=discord&logoColor=white)](https://discord.gg/jYv7Z2chtP)

<img src="media/hero-bike.jpg" alt="A motorcycle TFT dashboard running MOTO-HUB on the road" width="820">

Your motorcycle already has the screen. MOTO-HUB gives it the software.<br>
Project **Android Auto**, mirror **any app**, or run a full **GPS ride dashboard** on the TFT —
and control it all from the **handlebar buttons** you already have.

**CFMOTO · Voge · Zontes · Moto Morini · Benelli · QJ Motor · Morbidelli · KOVE** — one app for the EasyConn / Carbit dashboards many brands ship.

<br>

[![Download MOTO-HUB](https://img.shields.io/badge/Download%20MOTO--HUB-free%20·%20open%20source-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://github.com/vincenzobpt/MOTO-HUB/releases/latest)
&nbsp;
[![Add MOTO-HUB ADVANCED](https://img.shields.io/badge/Add%20MOTO--HUB%20ADVANCED-free%20companion%20app-e10600?style=for-the-badge&logo=android&logoColor=white)](https://github.com/vincenzobpt/MOTO-HUB-PRO-releases/releases/latest)

<sub>On the release page, expand **Assets** and download the file ending in `.apk`.</sub>

<br>

### 💬 Come and ride with us

**Every rider here is on Discord** — support when a dashboard misbehaves, help getting your bike working, early builds, and the place where the next features get decided.

[![Join the MOTO-HUB Discord](https://img.shields.io/badge/JOIN%20THE%20MOTO--HUB%20DISCORD-support%20·%20community%20·%20new%20builds-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/jYv7Z2chtP)

</div>

> [!WARNING]
> **MOTO-HUB is an experimental proof-of-concept, not a production-grade product.** Day-to-day development happens on a CFMOTO **700MT-ADV** with **OnePlus 13 / Galaxy Z Fold4** phones; behavior may differ or require a retry on other motorcycles, T-Box firmware versions, or phones. Do not depend on it as your only source of critical navigation information — plan your route before riding, configure everything while parked, and use the software at your own risk.

## Get riding in three steps

1. **Install** the MOTO-HUB APK from the [latest release](https://github.com/vincenzobpt/MOTO-HUB/releases/latest). Android will ask you to allow installs from this source — that is the normal prompt for apps outside Google Play.
2. **Pair** by scanning the QR code your dashboard shows (or import a photo of it, or enter the network manually). Your bike is saved to the garage.
3. **Ride** — start Android Auto or mirror your phone on the TFT. Add [MOTO-HUB ADVANCED](https://github.com/vincenzobpt/MOTO-HUB-PRO-releases/releases/latest) for the Ride Dashboard, navigation, trips and everything below.

## Two apps, one ride

MOTO-HUB is deliberately split in two:

- **MOTO-HUB** (this repository) is the **free, open-source core**. It owns the connection to the motorcycle — pairing, the T-Box transport, Android Auto, screen mirroring, handlebar buttons. Simple, focused, AGPL-3.0. It is the only app you *need*.
- **[MOTO-HUB ADVANCED](https://github.com/vincenzobpt/MOTO-HUB-PRO-releases)** is the **free companion app** that turns the same TFT into a full riding computer: a native GPS dashboard, motorcycle navigation, trip recording and replay, AI place discovery, group intercom and more. It installs alongside MOTO-HUB and talks to it over a documented IPC boundary.

**ADVANCED requires MOTO-HUB; MOTO-HUB does not require ADVANCED. Both are free — install both to get everything.** The two apps are released together and must be the **same version**.

| | MOTO-HUB (this repository) | [MOTO-HUB ADVANCED](https://github.com/vincenzobpt/MOTO-HUB-PRO-releases) |
| --- | :---: | :---: |
| T-Box pairing, garage, connection | ✅ | uses MOTO-HUB |
| Android Auto on the TFT | ✅ | delegated to MOTO-HUB |
| Screen mirroring (full screen or one app) | ✅ | ✅ |
| Handlebar button control | ✅ | ✅ |
| USB external display (AOA) | ✅ | — |
| Diagnostics, logs, in-app updates | ✅ | ✅ |
| **Ride Dashboard** — native GPS scene on the TFT | — | ✅ |
| **Navigation** — search, motorcycle routing, rich route preview | — | ✅ |
| **Route intelligence** — weather along the route, fuel prices, speed cameras | — | ✅ |
| **Trips** — full-telemetry recording, replay, analysis, GPX | — | ✅ |
| **Riding Coach** — post-ride AI evaluation | — | ✅ |
| **AI place discovery** | — | ✅ |
| **Group intercom** — rider-to-rider voice | — | ✅ |
| **Audio notes** pinned to your trips | — | ✅ |
| **OBD-II diagnostics suite** — hidden somewhere in the app 🤫 | — | 🥚 |
| Minimum Android version | 12+ | 14+ |

<div align="center">
  <img src="media/phone-core-home.png" alt="MOTO-HUB Core home screen" width="230">
  &nbsp;&nbsp;
  <img src="media/phone-adv-home.png" alt="MOTO-HUB ADVANCED home screen with Nav, Trips and AI tabs" width="230">
  <br>
  <sub>The same design language, two missions: <b>CORE</b> connects your bike — <b>ADVANCED</b> makes it fly.</sub>
</div>

## MOTO-HUB ADVANCED — everything your TFT was waiting for

*All of this is implemented, working, and free. It builds on the rider's own GPS — the motorcycle needs no extra hardware.*

### 🏍️ Ride Dashboard

A native, configurable riding scene rendered straight on the TFT: GPS speed, live map, trip stats, weather, phone status — every panel is a widget you choose, and panels can rotate through a carousel on the interval you set. The main panel is yours too: put the **live map** there, run **Android Auto embedded** inside it, or switch it to a full **OBD gauge cluster** with live engine data and gear estimation (ELM327 Bluetooth adapter required). Turn-by-turn guidance from Waze or Google Maps shows up in the Navigation widget.

<div align="center">
  <img src="media/tft-ride-dashboard.png" alt="Ride Dashboard on the TFT with GPS speed, live map and trip stats" width="410">
  <img src="media/tft-dashboard-aa.png" alt="Android Auto embedded inside the Ride Dashboard map panel" width="410">
  <br>
  <img src="media/tft-obd-dashboard.png" alt="Ride Dashboard with the OBD gauge cluster in the main panel" width="410">
  <img src="media/phone-widgets.png" alt="Widget customization screen" width="230">
  <br>
  <sub>The Ride Dashboard's main panel: live map, embedded Android Auto, or the OBD gauge cluster — your choice. Right: pick the widgets for every panel.</sub>
</div>

### 🗺️ Navigation, built for motorcycles

Search a destination, get **motorcycle routing** — including *curvy roads* when the fastest line is not the point — preview the full route, add waypoints, and send it to the TFT. The route preview is a briefing, not just a line on a map:

- **Where the curves are** — a strip showing which stretches actually bend, the best stretch called out, and what the twisty line costs you against the fast one.
- **The shape of the ride** — total ascent, number of turns, and a pinch-to-zoom elevation profile.
- **Weather along the route** — rain cells with the time you'll meet them, crosswind, ice risk.
- **Fuel prices on the route** — live official price data in 🇮🇹 🇪🇸 🇫🇷 🇵🇹, merged with the stations on your path.
- **Speed cameras** — an approaching-camera alert on the dashboard (off by default, and automatically disabled in countries where the law forbids it).
- **Street-level preview** — tap the route line to open Mapillary street imagery of that exact spot.

<div align="center">
  <img src="media/phone-nav-preview-1.png" alt="Route preview showing where the curves are and the Fast or Piega route choice" width="230">
  &nbsp;
  <img src="media/phone-nav-preview-2.png" alt="Route briefing with ascent, turns, elevation profile and petrol prices on the route" width="230">
  &nbsp;
  <img src="media/phone-nav-preview-3.png" alt="Weather along the route with temperature and crosswind at each stage" width="230">
  <br>
  <sub>Where the curves are and what they cost you in time &middot; ascent, turns, elevation and petrol on the way &middot; the weather you will actually meet.</sub>
</div>

### 📈 Trips — record, relive, improve

Every ride can be recorded with **full sensor telemetry**, not just a GPS trace. Browse your history by period, then relive it:

- **Replay** the ride on the map, in a **3D chase-cam POV**, or export a **Google Earth KMZ** flyover.
- **Post-ride analysis** — speed, altitude, lean angle and G-forces on a dedicated dashboard.
- **Riding Coach** — an AI evaluation of your riding style after each trip.
- **Audio notes** — record voice notes mid-ride, pinned to the exact point of the trip.
- **GPX export**, trip merging, and a period-based archive with multi-select.

<div align="center">
  <img src="media/phone-trips-archive.png" alt="Trips archive grouped by period" width="230">
  &nbsp;
  <img src="media/phone-trip-replay.png" alt="Trip replay in 3D POV view" width="230">
  &nbsp;
  <img src="media/phone-trip-analysis.png" alt="Post-ride telemetry analysis with lean angle" width="230">
</div>

### 🤖 AI place discovery

Ask for "a scenic pass with a café at the top" and let the AI tab rank real OpenStreetMap places for you — the map data is the source of truth, the model just picks well. Bring your own OpenAI-compatible API key; it is stored encrypted on the phone.

<div align="center">
  <img src="media/phone-ai.png" alt="AI assisted place discovery" width="230">
</div>

### 🎙️ Group intercom & voice

Riding with a friend? **Group intercom** carries voice between two phones over the rider's own hotspot — no accounts, no servers, no subscription.

<div align="center">
  <img src="media/phone-intercom.png" alt="Group intercom" width="230">
  &nbsp;
  <img src="media/phone-audio-notes.png" alt="Audio notes pinned to a trip" width="230">
</div>

### 🥚 …and one secret left to find

ADVANCED hides one more toy: a complete **OBD-II diagnostics suite**, tucked behind a door that appears on no menu. How to open it stays a secret — but riders who find it get:

- **Live engine data** — revs, speed, throttle, engine load and temperatures, streamed from a standard ELM327 Bluetooth adapter.
- **Fuel and air** — fuel trims, oxygen sensors, manifold and timing.
- **Trouble codes** — read the stored codes, explained by a built-in catalogue, with the distance and warm-ups since they were last cleared.
- **Full scan** — interrogate every PID your motorcycle supports and share the report as a file.

<div align="center">
  <img src="media/phone-obd-live-data-1.png" alt="Live data: engine speed, throttle, load and temperatures with one-minute traces" width="230">
  &nbsp;
  <img src="media/phone-obd-live-data-2.png" alt="Fuel and air: fuel trims, oxygen sensors, manifold pressure and timing" width="230">
  &nbsp;
  <img src="media/phone-obd-live-data-3.png" alt="Full scan: every PID the vehicle claims to support, answered and logged" width="230">
  <br>
  <sub>Live data &middot; fuel and air &middot; full scan. No, we won't tell you where the door is. Happy hunting. 🔎</sub>
</div>

<div align="center">

[![Add MOTO-HUB ADVANCED](https://img.shields.io/badge/Get%20all%20of%20this%20—%20MOTO--HUB%20ADVANCED-free-e10600?style=for-the-badge&logo=android&logoColor=white)](https://github.com/vincenzobpt/MOTO-HUB-PRO-releases/releases/latest)

</div>

## What MOTO-HUB does

The open-source core is a complete product on its own:

- **Android Auto on the TFT** — through an embedded local head-unit receiver, with per-bike `FIT` / `STRETCH` / `CROP` layout, TFT safe margins, and native-shape layout so the map fills the panel instead of sitting between black bars.
- **Screen mirroring** — the whole phone or a single app, H.264-encoded and streamed to the dashboard.
- **Handlebar buttons drive everything** — a one-time guided calibration learns what *your* bike actually sends, then maps press / double-press / hold to Android Auto actions, volume, assistant, or one-press navigation to saved destinations.

<div align="center">
  <img src="media/tft-android-auto.png" alt="Full Android Auto projected on the motorcycle TFT" width="560">
  <br>
  <sub>Full Android Auto on the motorcycle TFT — driven from the handlebar.</sub>
</div>

- **Motorcycle garage** — multiple bikes, each with its own photo, Wi-Fi credentials (encrypted), display format, safe margins and handlebar calibration.
- **Every QR dialect** — CFMOTO, MotoFun (Moto Morini), YUNMO and more; an unrecognized dashboard falls back to a generic profile instead of being rejected.
- **USB external display** — stream the phone to an AOA accessory head unit, fully independent of the T-Box.
- **Bulletproof sessions** — auto-connect on launch, a recovery watchdog that rebuilds a stalled stream, and seamless resume across longer dropouts.
- **Diagnostics that respect you** — network tests, a full local log you can share as a file, and a master switch that turns all logging off.
- **In-app updates** — the app checks GitHub releases and shows the notes before installing.
- **7 languages** — English, Italian, Spanish, French, Portuguese, Korean, German.

<a id="requirements"></a>**Requirements:** Android 12 or newer and a motorcycle with a compatible dashboard (see below). ADVANCED additionally requires Android 14+.

## Supported motorcycles

MOTO-HUB is **not a CFMOTO-only app**. It speaks to the EasyConn / Carbit dashboard stack that many manufacturers license:

| Brand | Notes |
| --- | --- |
| **CFMOTO** | The reference hardware this project is developed against (700MT-ADV) |
| **Voge** | |
| **Zontes** | |
| **Moto Morini** | Dashboards paired through the **MotoFun** companion app, whose QR code uses its own dialect |
| **Benelli** | TRK 702 / 702X |
| **QJ Motor** | Fort 4.0 |
| **Morbidelli / MBP** | T1002V |
| **KOVE** | 800X — ThinkerRide (BLE-provisioned) dashboards |

Nothing in the app filters on brand: the network name always comes from the rider, through the QR code or manual pairing. A dashboard MOTO-HUB has never seen is not rejected — an unknown QR dialect can be accepted after a warning, an unknown dashboard falls back to a generic profile, and the diagnostics are built so a rider on an unfamiliar motorcycle can send a log that explains what happened. Each motorcycle model and T-Box firmware still needs its own validation before it can be called *supported* — including CFMOTO ones.

## MOTO-HUB for iOS

Riding with an iPhone? **MOTO-HUB for iOS is available now** — the ride dashboard, navigation, trips and projection to the same motorcycle dashboards, on iOS 17 or later.

Install it through **AltStore Classic** (add the MOTO-HUB source) or sideload the IPA yourself with a tool such as Sideloadly. Everything you need is on its own page:

<div align="center">

[![MOTO-HUB for iOS](https://img.shields.io/badge/MOTO--HUB%20for%20iOS-download%20·%20AltStore%20source-000000?style=for-the-badge&logo=apple&logoColor=white)](https://github.com/vincenzobpt/MOTO-HUB-IOS-releases)

</div>

## Community

MOTO-HUB is built ride by ride, with testers on real motorcycles across many brands. Support, feature discussion and beta access all happen in one place:

<div align="center">

[![Discord](https://img.shields.io/badge/JOIN%20US%20ON%20DISCORD-support%20·%20community%20·%20development-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/jYv7Z2chtP)

</div>

---

## The fine print

<details>
<summary><b>🚗 Android Auto does not start?</b></summary>

<br>

Android Auto 17.4 removed the entry points an app could use to ask it to project: the activity it used is no longer exported and the receiver behind it ships disabled, so MOTO-HUB's request is refused or silently ignored. This affects every app of this kind, not only MOTO-HUB — the `headunit-revived` project reports the same in its issue #698. Android Auto 17.2.662634 is verified working; 17.4.663004 is not.

There is no need to install an older Android Auto. Android Auto can be asked to listen instead, using the head unit server its own Desktop Head Unit connects to, and MOTO-HUB connects to that:

1. Open the **Android Auto** app, scroll to the bottom and tap **Version** ten times to reveal Developer settings.
2. In Developer settings, enable **Add new cars to Android Auto** (older wording: *Unknown sources*).
3. Open the **⋮ menu at the top right** of Developer settings and choose **Start head unit server**. It lives in that menu, not in the list of settings below it. A notification confirms it is running, and it stays running until stopped or the phone restarts.
4. Start Android Auto from MOTO-HUB as usual. MOTO-HUB polls that server and connects on its own.

The same instructions are in the app under `Settings ▸ Android Auto does not start`, and the error shown when projection fails links to them.

</details>

<details>
<summary><b>📋 Full feature reference</b></summary>

<br>

### Everything MOTO-HUB does

- Pair with a motorcycle T-Box by scanning its QR code, importing a photo of it, or entering the network manually.
- Read the QR dialects other manufacturers use — such as Moto Morini's MotoFun code — and accept an unrecognized one after a warning rather than refusing it.
- Store multiple motorcycle profiles and select the active motorcycle.
- Store a private motorcycle photo and use it throughout the app UI.
- Connect to the T-Box Wi-Fi access point without requiring manual SSID entry, with a separate Wi-Fi Direct path for dashboards that advertise a `DIRECT-` network.
- Discover the EasyConn service and establish the T-Box session.
- Mirror the entire phone screen or a single Android app.
- Start Android Auto through an embedded local head-unit receiver, including on Android Auto versions that no longer accept a direct start request.
- Drive Android Auto from the motorcycle's handlebar buttons, after a short guided calibration, with per-motorcycle mappings for press, double press and hold.
- Control music volume, jump to a saved destination, or open the assistant from the handlebar without touching the phone.
- Stream the phone screen to a USB (AOA) external display, independently of the T-Box.
- Choose the Android Auto TFT layout per motorcycle: `FIT` (preserve the complete image, black bars when necessary), `STRETCH` (use the complete TFT area with geometric stretching), or `CROP` (use the complete TFT area without stretching, cropping edges when necessary).
- Calibrate per-motorcycle TFT safe margins so Android Auto video and touch stay inside the projection area not occupied by native motorcycle UI.
- Let Android Auto lay out at the dashboard's real shape instead of a letterboxed band inside it.
- Keep the phone preview available for Android Auto touch control, or disable the touchscreen entirely and ride with focus and handlebar controls.
- Select Smoother, Balanced or Sharper image detail, and a Smooth/Balanced/Saver/adaptive power behavior for the next stream.
- Override Android Auto with landscape or portrait SD/HD source resolutions, or keep automatic selection.
- Optionally connect to the saved motorcycle when MOTO-HUB opens.
- Optionally recover or seamlessly resume a stalled or dropped TFT stream when the T-Box returns.
- Show persistent diagnostics, run network tests, and share application logs as an exported file for troubleshooting.
- Check GitHub releases and pre-releases from inside the app, showing release notes before installing a newer APK.
- Run in English, Italian, Spanish, French, Portuguese, Korean or German, or follow the phone language.

### Motorcycle Garage

The garage stores multiple motorcycle profiles. Each profile can contain the T-Box SSID and encrypted Wi-Fi password, QR-provided metadata, a user-defined display name, a private motorcycle photo, the Android Auto display format (`FIT`, `STRETCH`, or `CROP`), TFT safe margins, its own handlebar button calibration and mapping, and observed T-Box capability snapshots. Existing single-profile data is migrated automatically when the app is upgraded.

### Projection Modes

`Mirror` uses Android `MediaProjection` and supports either the complete phone display or an app selected through Android's system picker.

`Auto` runs Android Auto through a local Android Auto Projection receiver. The decoded Android Auto video is composited, encoded as H.264, and sent to the T-Box through the ridedaemon transport. The compositor supports `FIT`, `STRETCH`, and `CROP` against the usable TFT projection area. When Android Auto declares internal letterbox margins, `STRETCH` uses the active Android Auto content rather than stretching black bars. By default MOTO-HUB asks Android Auto to lay out at the dashboard's real shape, so the map fills the panel instead of sitting between black bars; the per-motorcycle TFT safe margins can be advertised instead by switching content insets to `Manual`.

`External` appears only when a USB (AOA) accessory head unit is attached. It captures the phone screen and writes H.264 access units straight to the USB accessory endpoint, completely independently of the T-Box, EasyConn and ridedaemon path.

The Ride Dashboard, Navigation and Trips are not part of this app — they live in [MOTO-HUB ADVANCED](https://github.com/vincenzobpt/MOTO-HUB-PRO-releases), which connects through this app's IPC boundary.

### Handlebar Buttons

These dashboards forward their handlebar buttons to the phone as ordinary Bluetooth media commands — a volume change, a play/pause, a next/previous track — and which physical press produces which command differs per motorcycle, and per brand. MOTO-HUB therefore starts from a **calibration**: the rider performs each press once, and the app learns what that motorcycle actually sends. Nothing is assumed from the model name.

Each calibrated press (`Up`, `Down`, `Left`, `Right`, `Select`, each as press, double press or hold) can then be mapped to an Android Auto action: rotary forward/back, D-pad, Select, Back, Home, the assistant, or one-press navigation to one of three saved destinations. Mapping, calibration and the on/off switch are stored per motorcycle. The feature is on by default, and MOTO-HUB keeps the media session it needs alive so the dashboard keeps sending presses instead of handing them to another app.

Music volume is expressed in **presses**, not steps, because that is how the dashboard rocker behaves.

### Settings

`Video quality` sets image detail against the negotiated base bitrate: `Balanced` is the recommended default, `Smoother` uses 70% and `Sharper` 160%. `Power mode` selects `Auto` (adapt bitrate and frame rate to phone temperature and Wi-Fi quality), `Smooth` (30 FPS), `Balanced` (24 FPS) or `Saver` (20 FPS). `Disable touchscreen` lets the rider use focus and handlebar controls even on a dashboard that reports a touch display.

`Android Auto` selects the source resolution — `Auto` (dynamic orientation from the learned T-Box geometry), 800 x 480, 1280 x 720, 720 x 1280 or 1080 x 1920 — and how content insets are advertised. The T-Box output canvas is still negotiated at runtime and is not replaced by the Android Auto source resolution.

`Connection & automation` holds auto-connect, which requests the saved motorcycle network and discovers EasyConn on app launch and after deliberate projection stops, and the optional recovery watchdog, which monitors outgoing TFT frame progress and rebuilds the T-Box network, discovery, handshake, and encoder path after a post-start stall while keeping the local Android Auto receiver alive. Seamless resume can park a projection across a longer T-Box interruption and resume when the motorcycle network returns.

`Diagnostics` provides network tests (T-Box discovery, Wi-Fi binding, cellular routes), the application log with copy/share/clear, a master switch that stops all logging — and with it the error reports described under crash and error reporting — and verbose T-Box logging for protocol-level troubleshooting.

The general section holds the app language, launch-time update checks, and seamless resume.

### Network Behavior

The T-Box Wi-Fi network is a local display transport and may not provide Internet access. MOTO-HUB requests the T-Box network explicitly and keeps the T-Box transport separate from normal phone connectivity where Android allows it. OEM network behavior can vary, especially on OnePlus devices.

</details>

<details>
<summary><b>🔐 Permissions and privacy</b></summary>

<br>

MOTO-HUB is a local-first app. The permissions below are used to connect to the motorcycle, scan its pairing QR code, keep an active projection running, and give the rider controls. The app does not require an account and does not upload screen content.

### Permissions requested while using the app

| Permission | When it is requested | Why it is needed | What it does not mean |
| --- | --- | --- | --- |
| **Camera** | When you choose live QR scanning | Reads the T-Box QR code shown on the motorcycle TFT | The camera is not needed for normal streaming, and camera frames are not intentionally recorded or uploaded |
| **Nearby devices / Wi-Fi** | When you connect to a saved or newly paired motorcycle | Finds and requests the motorcycle's Wi-Fi access point, then communicates with the local T-Box | It is not Bluetooth tracking and does not grant access to unrelated nearby devices |
| **Location** | Alongside the Wi-Fi permission, when connecting to the T-Box | Android requires location permission for Wi-Fi discovery and network requests | MOTO-HUB does not track or record the rider's position: this app has no GPS features, requests no location updates, and sends no coordinates anywhere |
| **Bluetooth** | Only when you enable handlebar button control | Identifies the connected dashboard so its button presses can be told apart from a headset's | It does not scan for or connect to other Bluetooth devices |
| **Microphone** | Only when you use the Android Auto voice assistant | Carries your voice to Android Auto while it is projecting | Audio is passed to Android Auto for the active request and is not recorded to disk or uploaded by MOTO-HUB |
| **Notifications** | When starting projection on Android 13 and newer | Shows the required foreground-service status and gives you visible controls to stop or manage an active session | It is not remote telemetry; notifications stay on the phone |

### System confirmations and optional access

| Access | When it is used | Why it is needed |
| --- | --- | --- |
| **Screen sharing confirmation** | Every time you start phone mirroring, app-specific sharing, or the USB external display | Android requires the user to approve capture of the whole display or a selected app. MOTO-HUB cannot approve this silently |
| **Install unknown apps** *(optional)* | Only if you install an update offered by the in-app release check | Android requires this to hand a downloaded APK to the package installer. Declining it simply means updating manually from the releases page |
| **Display over other apps** *(optional)* | Phone-display dimming during projection, and starting navigation from a handlebar button while another app is in front | Places a non-touchable overlay over the phone display to reduce brightness while the TFT continues receiving the projection, and lets a background button press launch navigation |

### Technical permissions granted by Android

The app also declares network and foreground-service permissions required by Android for this workflow: Internet and network-state access, Wi-Fi state/change access, Wi-Fi multicast discovery, foreground services for media projection, connected devices and microphone, and a wake lock. These maintain the local T-Box connection and the projection; they are not separate user accounts or remote services.

The Android Auto receiver also declares package visibility for Android Auto and Google Play services so MOTO-HUB can detect and launch the installed Android Auto component. This does not give MOTO-HUB access to Google account data.

### If a permission is denied

The app should continue to open normally. Only the related feature is unavailable: without Camera, use QR import from a photo, manual pairing, or an already saved motorcycle; without Nearby Wi-Fi or Location, the T-Box connection cannot be discovered; without Notifications, projection cannot be kept as a managed foreground session; without screen-capture approval, mirroring cannot start; without Bluetooth, handlebar buttons cannot be identified; without Microphone, the Android Auto assistant has no voice input. Optional display dimming simply remains disabled unless overlay access is granted.

### Privacy notes

MOTO-HUB is designed to operate without an account or proprietary telemetry service. It handles screen content, T-Box credentials, and diagnostic data on the phone. Wi-Fi passwords are encrypted with Android Keystore. Screen frames are processed in memory for the active projection and are not intentionally recorded to disk.

This app contacts two Internet hosts on its own: GitHub, to check for a newer release when you ask it to or when launch-time update checks are enabled, and Sentry, for crash and error reporting (see below). It has no maps, geocoding, routing or weather features, requests no location updates, and sends no ride or position data anywhere. Anything Android Auto itself does over the network is Android Auto's own traffic, under your Google account, not MOTO-HUB's.

### Crash and error reporting

Official MOTO-HUB release APKs report crashes and connection failures to [Sentry](https://sentry.io/), in its EU region. This exists because the failures that matter here — a dashboard that will not associate, a stream that dies mid-ride — take a motorcycle and a rider to reproduce, and a single rider's report rarely says whether it is one bike or one model.

What is sent:

- Crashes, and why a previous process of the app ended.
- Errors already written to the in-app diagnostic log, **redacted** and capped at 50 per app run. They are sent as plain messages, never as raw exception objects, precisely because the local log can contain connection details that must not leave the phone.
- Coarse tags used to group reports across riders — for example whether the dashboard's Wi-Fi network was visible at all in the moment before a failed join. Values are deliberately kept low-cardinality, so they describe a situation rather than a rider.
- The app version and build number, so a report can be attributed to a release.

What is not sent: Sentry's "default PII" collection is switched off, so no account, contact, device identifier or IP-derived user data is attached. Screen content, T-Box passwords, trips and position are never sent — the app has no position data to begin with.

Turning off `Settings ▸ Diagnostics ▸ Enable logging` stops the diagnostic log entirely, and with it the error events described above. Crash reports are handled by the Sentry SDK itself and are not covered by that switch.

**Builds from this source send nothing.** The Sentry DSN is supplied at build time from a private properties file or CI secret, exactly like the Android Auto identity. A source build without it has telemetry disabled outright, not merely unconfigured.

Review [Security and Privacy](documentation/SECURITY_AND_PRIVACY.md) before distributing an APK outside personal use.

The public source does not include the Android Auto identity or APK-signing keystore. APKs attached to official MOTO-HUB releases are complete runtime builds and include Android Auto support.

</details>

<details>
<summary><b>🛠️ Build from source</b></summary>

<br>

### Repository layout

```text
MOTO-HUB/
├── apps/android/
│   ├── app/                Android application and projection pipelines
│   └── ipc-contract/       AIDL boundary MOTO-HUB ADVANCED connects through
├── packages/contracts/     Future platform-neutral contracts
├── tooling/                AAR build metadata and reproducibility helpers
├── translations/           Source strings and their translations
├── documentation/          Architecture, decisions, security, testing, and roadmap
└── README.md               Project overview and setup instructions
```

The public repository contains only the MOTO-HUB source, documentation, build metadata, and non-sensitive required artifacts. External projects are referenced by their public URLs and are not vendored into this repository.

### Build requirements

- **JDK 21.** Gradle 8.13 / AGP 8.12.3 do not run on newer JDKs, and the JDK bundled with current Android Studio versions is too new — point `JAVA_HOME` at a JDK 21 explicitly.
- Android SDK platform/API 36.
- A physical Android device. An emulator cannot reproduce the motorcycle Wi-Fi, camera, NSD, or Android Auto behavior.
- A generated `hudlib.aar` from the MOTO-HUB ridedaemon fork.

From `apps/android/`:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

./gradlew lintDebug testDebugUnitTest assembleDebug
```

The generated Android binding is expected at:

```text
apps/android/app/libs/hudlib.aar
```

To rebuild it, install Go and `gomobile`, then run these commands from the directory that contains the `MOTO-HUB` folder:

```bash
git clone https://github.com/vincenzobpt/ridedaemon-lib ridedaemon-lib
cd ridedaemon-lib
gomobile bind -target=android -androidapi 31 -o ../MOTO-HUB/apps/android/app/libs/hudlib.aar ./hud/api
```

The source commit and AAR checksum must be updated in [`tooling/ridedaemon.lock`](tooling/ridedaemon.lock) whenever the artifact changes.

### Android Auto release builds

The public source intentionally does **not** contain the static Android Auto head-unit identity (`aa_cert` and `aa_identity_data`) or the APK-signing keystore. Maintainer-built release APKs include Android Auto support and require no certificate setup or technical configuration from the user.

A normal source build without those inputs remains usable for pairing, T-Box streaming, mirroring, and diagnostics, but Android Auto reports that its identity is unavailable. This separation keeps private build inputs out of Git history; it does not make identity material embedded in a publicly downloadable APK confidential.

For a local Android Auto build, place the two identity files in `tooling/private/android-auto/` and run:

```bash
./gradlew -PincludeAndroidAutoIdentity=true assembleDebug
```

For a local build without Android Auto identity files, use the default build:

```bash
./gradlew assembleDebug
```

Maintainers can find the complete release process and required GitHub secret names in [`documentation/PUBLIC_RELEASE.md`](documentation/PUBLIC_RELEASE.md).

</details>

<details>
<summary><b>📚 Documentation</b></summary>

<br>

- [Architecture](documentation/ARCHITECTURE.md)
- [Android implementation](documentation/ANDROID_IMPLEMENTATION.md)
- [Reference analysis](documentation/REFERENCE_ANALYSIS.md)
- [T-Box streaming contract](documentation/TBOX_STREAMING_CONTRACT.md)
- [Dynamic Android Auto profile](documentation/DYNAMIC_ANDROID_AUTO_PROFILE.md)
- [Security, privacy, and licensing](documentation/SECURITY_AND_PRIVACY.md)
- [Test strategy](documentation/TEST_STRATEGY.md)
- [Roadmap](documentation/ROADMAP.md)
- [Risk register](documentation/RISK_REGISTER.md)
- [OpenCfMoto comparative audit](documentation/OPEN_CFMOTO_COMPARATIVE_AUDIT.md)
- [Projection settings](documentation/PROJECTION_SETTINGS.md)
- [Public release process](documentation/PUBLIC_RELEASE.md)
- [Architecture decisions](documentation/decisions/README.md)

The [Ride Dashboard](documentation/RIDE_DASHBOARD.md), [Navigation](documentation/NAVIGATION.md) and [Trip recording](documentation/TRIP_RECORDING.md) documents describe features that now ship in MOTO-HUB ADVANCED. They are kept here because they were written against this repository's history.

</details>

<details>
<summary><b>🙏 Technical sources and attribution</b></summary>

<br>

MOTO-HUB was developed using the following public projects as technical sources. The links below are references and attribution; they are not claims of endorsement.

### Ridedaemon library fork

- [vincenzobpt/ridedaemon-lib](https://github.com/vincenzobpt/ridedaemon-lib) - the fork used to generate the Android `hudlib.aar` binding.
- [charliecharlieO-o/ridedaemon-lib](https://github.com/charliecharlieO-o/ridedaemon-lib) - upstream project and protocol implementation.

The library implements EasyConn discovery, the T-Box handshake, control channels, media polling, H.264 framing, and the `gomobile` Android API.

### Reference Android integration

- [charliecharlieO-o/ridedaemon-android](https://github.com/charliecharlieO-o/ridedaemon-android) - reference Android integration used to study QR parsing, Wi-Fi provisioning, NSD discovery, MediaCodec configuration, and frame delivery.

### Android Auto and CFMOTO research

- [BojanJ/open-cfmoto](https://github.com/BojanJ/open-cfmoto) - independent Android Auto and CFMOTO T-Box research used to understand the local Android Auto receiver flow, self-mode startup, touch input, and video pipeline behavior.
- [zanderp/open-cfmoto](https://github.com/zanderp/open-cfmoto) - AGPL-licensed implementation studied for user-selectable bitrate, Android Auto source profiles, startup automation, and stream recovery behavior.

### Navigation and mapping services

These services are not contacted by this app. They are used by MOTO-HUB ADVANCED, which builds on this repository's transport, and are credited here because the documentation kept in this repository describes them.

- [OpenStreetMap](https://www.openstreetmap.org/copyright) - underlying map, address, and routing data, credited to OpenStreetMap contributors.
- [CARTO basemaps](https://carto.com/basemaps) - raster basemap tiles.
- [Photon](https://photon.komoot.io/) (Komoot) - the free geocoding API used to turn a searched address or place name into coordinates.
- [Valhalla](https://github.com/valhalla/valhalla) - the open-source routing engine used for turn-by-turn motorcycle routing.
- [Stadia Maps](https://stadiamaps.com/) - hosts the Valhalla routing API used by default, with the rider's own free API key.
- [FOSSGIS public Valhalla demo server](https://github.com/valhalla/valhalla/discussions/3373) - an optional, rate-limited, keyless routing fallback.
- [Open-Meteo](https://open-meteo.com/) - free weather API used for the route weather estimates.
- [Mapillary](https://www.mapillary.com/) - street-level imagery, with the rider's own token.

### Vendor and platform references

- [EasyConn](https://www.easyconn.net/) - vendor context for the T-Box ecosystem.
- [Sentry](https://sentry.io/) - crash and error reporting in official release builds; see the permissions and privacy section.
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection) - Android screen capture API.
- [Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) - hardware video encoding and decoding API.
- [Android Open Accessory](https://developer.android.com/develop/connectivity/usb/accessory) - USB accessory protocol used by the external display mode.
- [Android Wi-Fi network requests](https://developer.android.com/develop/connectivity/wifi/wifi-suggest) - Android Wi-Fi provisioning APIs.

</details>

<details>
<summary><b>⚖️ Licensing</b></summary>

<br>

This section is intentionally explicit because the project combines original MOTO-HUB code with external components and research.

- **MOTO-HUB (this repository) is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0)** — see [`LICENSE`](LICENSE). AGPL-3.0 was chosen because the repository combines GPL-3.0 material (`hudlib.aar`, the T-Box transport) with AGPL-3.0-derived material (`aa/`, the Android Auto receiver technique ported from `headunit-revived`); AGPL-3.0 satisfies both components' obligations for a combined work and additionally covers network-facing use.
- `ridedaemon-lib` and the reference Android project are distributed under AGPL-3.0 according to their repositories and license files; the generated `hudlib.aar` is derived from that fork. Redistributing it (including inside this repository) must comply with the applicable AGPL obligations — the corresponding source must remain available to anyone who interacts with it, including over a network.
- The `open-cfmoto` project used for research **is licensed under AGPL-3.0-or-later** (it carries both a `LICENSE` and a `NOTICE`, and an SPDX header on each source file). That statement replaces an earlier note here that it had no license file, which was true of the snapshot reviewed at the time and is no longer true. Code may therefore flow in either direction under the AGPL, provided the copyright notices and the modification notices required by section 5 travel with it. The influence recorded in the research references above is behavioural — bitrate, source profiles, startup automation, stream recovery — not source. In the other direction, `EcBtpProtocol.kt` was written here on 2026-08-13 and appears in OpenCfMoto the same day; see the note in that file.
- **MOTO-HUB ADVANCED** is a separate, closed-source companion application maintained in a private repository. It contains no GPL-3.0 or AGPL-3.0 code — it reaches this repository's T-Box transport and Android Auto receiver exclusively through a documented Binder IPC boundary (`apps/android/ipc-contract/`, `IpcBridgeService`), which is why it can be distributed under different terms. ADVANCED requires MOTO-HUB to be installed to function; MOTO-HUB does not require ADVANCED.
- CFMOTO, Voge, Zontes, Moto Morini, MotoFun, Benelli, QJ Motor, Morbidelli, MBP, KOVE, ThinkerRide, EasyConn, Carbit, MotoPlay, Android Auto, Google, and related names remain the property of their respective owners. MOTO-HUB is an independent project and must not imply official support from any of them.

This README documents the project's licensing rationale; it is not a substitute for legal advice.

</details>

---

<div align="center">
<sub>Use MOTO-HUB only while parked during setup and testing. The project is provided for experimentation with personally owned hardware and without any safety guarantee or vendor support.</sub>
</div>
