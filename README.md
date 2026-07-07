<p align="center">
  <img src="icons/play_store_512.png" width="128" alt="Orchestra icon">
</p>

<h1 align="center">Orchestra</h1>

<p align="center">
  Native headphone controls inside Pixel / Android system UI — no vendor app, driven by
  per-device manifests over RFCOMM (Soundcore) or AAP/L2CAP (AirPods).
</p>

---

## What it is

**Orchestra** is a single APK that is *both* a launchable Compose app **and** an LSPosed module. It
makes the stock Android Bluetooth **"About device"** page render native controls — ANC / noise-control
modes, feature switches, and battery — for Soundcore and AirPods headphones, by impersonating the
device-settings integration that Pixel Buds use. There is no vendor app in the loop: Orchestra talks to
the headphones directly over their native control protocol (RFCOMM for Soundcore, Apple's Accessory
Protocol over L2CAP for AirPods), and every device is described by a small **JSON manifest** so adding
a model is data, not code.

The same controls that Pixel Buds get natively (segmented ANC control, feature toggles, per-bud
battery) appear for your headphones, in the system Settings UI, themed with Material You.

## Requirements

- A device where **root + LSPosed** (or a compatible Xposed framework) is available — developed on a
  **Pixel** running **Android 16 / 17**.
- The **LSPosed** (or "Vector"/modern Xposed) manager.
- A supported headphone (see below) — or write a manifest for your own.

> Orchestra needs LSPosed because it writes a privileged Bluetooth metadata tag and hooks the system
> UI. It does **not** require a separate KSU/Magisk module.

## Compatibility & support state

Orchestra impersonates the **Pixel** device-settings integration, so platform support is defined along
three axes — the OS, the ROM, and the Xposed framework. The **headphones themselves are not hardcoded**:
which devices are supported is defined entirely by **manifests** (see *Supported headphones* below).

| Axis | Supported | Notes |
|---|---|---|
| **Android version** | **Android 16–17** (API 36–37). `minSdk` 31 (Android 12). | About-device controls verified on **Android 17**. The **volume-panel ANC tile is currently broken on Android 17** (Google reworked that System-UI path) — the About-device page is unaffected. |
| **ROM** | **PixelOS** (Google's Pixel build) — the only verified target. | Injection bindings ship for `pixelos` only. The manifest schema has an embedded per-ROM `platforms` slot so other **root + LSPosed-capable** ROMs (e.g. LineageOS) can be added later — none are implemented/verified yet. Non-Pixel OEM skins (One UI, MIUI/HyperOS, ColorOS…) are **not** supported. |
| **Xposed framework** | **LSPosed** and the newer **"Vector"** framework (legacy Xposed bridge, verified at **API 101**). `xposedminversion` 82. | Uses the **legacy** Xposed API today. A dual legacy + **modern libxposed** engine (covering `<100` and `≥101` API levels) is on the roadmap. **Root required** (LSPosed needs it); no separate KSU/Magisk module. |

## Install & setup

1. Build or download the APK and install it:
   ```bash
   # build
   cd Orchestra && JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:assembleDebug
   # install
   adb install -r Orchestra/app/build/outputs/apk/debug/app-debug.apk
   ```
2. In **LSPosed**, enable **Orchestra** and set its **scope** to:
   **System UI** + **Settings** + **Orchestra** (itself), then **restart System UI** (or reboot).
3. Open the Orchestra app → **Devices** tab → toggle a paired Soundcore device **on** ("hook" it).
4. Open **Settings → Connected devices → \<your headphones\> → gear (About device)** once. The native
   control page now shows Orchestra's controls.

The app's **Status** tab tells you at a glance whether the module is active (and at what LSPosed API
level), whether Bluetooth is on, and how many devices are hooked.

## Supported headphones

**Which headphones are supported is defined by manifests, not by the app.** Each device is a JSON
manifest in the catalog repo — [**`orchestra-manifests`**](https://github.com/thelok1s/orchestra-manifests) —
and the app discovers them at runtime: it matches your paired devices against the catalog index,
highlights the eligible ones, and downloads their manifest when you hook one. Adding a model is writing
a manifest, not changing the app (see *Developing a manifest* below).

Currently shipped + hardware-verified:

| Device | Model | Controls verified |
|---|---|---|
| **Soundcore Space One Pro** | A3062 | 4-mode ANC (Noise Cancelling / Off / Adaptive / Transparency) + Dolby Audio, Surrounding sounds, Side tone, Multipoint, Low-battery prompt switches |
| **Soundcore Liberty 4 Pro** | A3957 | 3-mode ANC (Noise Cancelling / Off / Transparency) + native TWS battery (L / Case / R) |
| **AirPods Pro 2** | A3048 | 4-mode Noise Control (Off / ANC / Transparency / Adaptive), Conversational Awareness toggle, live per-bud + case battery on the native header, ear detection (in-app), background auto-pause on ear removal, CA volume-duck (gradual fade), Adaptive Audio strength (in-app 0–100 slider, active while Noise Control = Adaptive), Rename (in-app, sets the local Bluetooth alias) |

Both render correctly on the Android 17 **About-device** page. Each manifest also catalogues further
controls that ship **disabled** until their command bytes are hardware-confirmed (`_verified: false`).
A control shipped `_verified: false` must be opted in **per device** in the app's **Devices** tab
before its About-page toggle appears; verified controls inject automatically. The live, authoritative
device list is the catalog index in the manifests repo.

AirPods Pro 2 still needs **root + LSPosed** like every other device here — it is not a special case,
despite talking a different (Apple AAP) protocol under the hood. Its manifest ships in the app's
**bundled seed** at revision 6.

## How it works

Orchestra impersonates Android's **device-settings framework** (the same `settingslib` mechanism Pixel
Buds use):

- An **LSPosed hook in Settings** writes the privileged `BluetoothDevice` metadata **key 25**, pointing
  the system at Orchestra's **config provider service**.
- **Provider services** (`ConfigProviderService` / `SettingProviderService`) return the About-page
  layout and serve/handle each control, with optimistic UI and a persistent control socket.
- A transport-abstraction registry (`ControlEngine`) selects the per-device engine: **`RfcommEngine`**
  frames and exchanges the Soundcore protocol (`soundcore_v1`) over RFCOMM; **`AacpEngine`** speaks
  Apple's Accessory Protocol over an L2CAP socket (PSM 4097) for AirPods. `ble_gatt` is the remaining
  reserved slot.
- An **LSPosed hook in System UI** gates the volume-panel ANC tile (see *Known limitations*).

Deeper design lives in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Manifests

Every supported device is one **JSON manifest** (schema **v3**). A manifest declares:

- **`match`** — how to recognise the device (name regex / advertised UUIDs / model prefix).
- **`channels`** — named transport + protocol bundles (`rfcomm` and `aacp` are live; `ble_gatt` is a
  reserved slot) with a `default_channel`.
- **`functions[]`** — the full reverse-engineered capability catalogue. Each function has a UI `type`
  (`multitoggle` / `toggle` / `list` / `slider` / `info`), its set/read command bytes, conflicts /
  dependencies, an injectability verdict, and a `_verified` flag (only hardware-confirmed controls
  inject by default).
- **`platforms`** — optional per-ROM injection bindings (currently `pixelos`), so the same manifest can
  target other root+LSPosed-capable ROMs later.
- **Versioning** — `schema_version` (a hard compatibility gate) + a monotonic `revision` (bump on any
  edit, drives OTA updates).

The app **graceful-degrades**: a function whose transport, render type, or surface the running build
can't handle is skipped, never crashing the page. The full field reference is
[`orchestra-manifests/schema/SCHEMA.md`](https://github.com/thelok1s/orchestra-manifests/blob/main/schema/SCHEMA.md);
per-device protocol notes are in [`docs/devices/`](docs/devices/).

## Manifests repository & OTA

Device manifests live in a **dedicated repository — [`orchestra-manifests`](https://github.com/thelok1s/orchestra-manifests)** —
organised by manufacturer, with a **CI-generated `index.json`** (a freshness gate fails any PR whose
index is stale).

The app uses that repo at runtime:

- It **bundles a seed** of the manifests + index in `assets/` so it works offline on first run.
- On launch it **refreshes the index** (12 h TTL) and **highlights bonded devices** that are eligible
  for hooking.
- It **downloads / updates** a device's manifest over HTTPS from the repo's raw URL, **sha256-verified**
  against the index and gated by `revision`.
- You can **sideload your own manifest** from a file for testing — it takes highest precedence, is
  badged *local / testing*, and exposes all of its controls (bypassing the verified-default gate).

## Developing a manifest (add your device)

1. **Clone** [`orchestra-manifests`](https://github.com/thelok1s/orchestra-manifests) and `npm install`.
2. **Copy** an existing manifest under `manifests/<manufacturer-slug>/` to `<your-device-id>.json`.
3. Fill in `match`, the `channels`, and the `functions[]` capability list. Source command bytes from
   reverse-engineering references (e.g. **OpenSCQ30**) and **mark every function `_verified: false`**
   until you've confirmed it live on the hardware.
4. **Test on-device** without rebuilding the app: drop the JSON on the phone and load it via the app's
   **debug → "Load manifest from file"** (sideload). Confirmed controls → flip them to `_verified: true`.
5. **Bump `revision`** (and `revision_date`), run **`npm run build-index`**, and commit **both** the
   manifest and the regenerated `index.json` (CI enforces this).
6. Open a PR. See `schema/SCHEMA.md` for the full schema and `README.md` in that repo for the authoring
   workflow.

## Known limitations & roadmap

- **Volume-panel ANC tile is broken on Android 17** (the About-device page is unaffected). Google
  reworked that System-UI chain; the fix is tracked for the modern-libxposed engine rework.
- A per-device **in-app control screen** (tap an AirPods device card in the Devices tab) renders
  `slider`/`level` and `text` functions that can't be injected into the native About page — e.g.
  AirPods Pro 2's Adaptive Audio strength slider and Rename field, plus live battery/ear rows. A
  control shipped `_verified: false` still needs the Devices-tab opt-in before it appears here.
  `list` / composite controls (EQ bands…) aren't covered by this screen yet.
- **"Act as Apple device" (AirPods, Devices tab — default off) trades media buttons for handoff, by
  design.** When on, Orchestra spoofs the AirPods' Bluetooth Device-ID as Apple's, so the buds treat the
  Pixel as an Apple device — enabling Apple-style **stay-multipoint** and **seamless audio handoff** (e.g.
  Mac ↔ Pixel, the "Move to iPhone"-style switch). The catch is a **firmware tradeoff, not an Orchestra
  bug**: while spoofing, the buds route stem presses through Apple's protocol, so the Pixel's **play /
  pause / next / previous stop working**. Recovering them means telling the buds to hand their presses to
  the Pixel (a "takeover"), which makes the firmware treat the Pixel as its *sole* host and **drop
  multipoint entirely** — hardware-confirmed down to even a single-press takeover, and LibrePods hits the
  same wall (it, too, leaves default media presses to the standard path rather than taking them over). So
  it's one or the other: **on** → handoff + multipoint, no media buttons; **off** → media buttons +
  standard multipoint, no Apple handoff. (Long-press to cycle ANC keeps working either way — it's
  bud-native.)
- Only **2 devices** are fully verified; several per-device opcodes remain `_verified: false`.
- The module currently uses the **legacy Xposed API**; a dual legacy + **modern libxposed** engine
  (works across `<100` and `≥101` framework API levels) is on the roadmap.

## Disclaimer

Orchestra is an **unofficial** project, not affiliated with Google or Anker/Soundcore. It sends control
frames to **your own** headphones; some protocol bytes are reverse-engineered and unverified controls
are opt-in. Use at your own risk.

## Credits & license

Capability catalogue and protocol bytes were built from the excellent
[**OpenSCQ30**](https://github.com/Oppzippy/OpenSCQ30), SoundcoreManager, and SoundcoreDesktop projects.

Licensed under **GPL-3.0** — see [`LICENSE`](LICENSE).
