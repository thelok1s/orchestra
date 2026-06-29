# Maestro Proxy — Native Android controls for non‑Pixel earbuds

> ⚠️ **Partially superseded (2026-06).** This document describes the earlier **KSU priv-app + slice
> + separate Xposed module** design under the old name "Maestro Proxy" (`com.maestro.proxy`). The
> project has since been **rebranded to Orchestra** (`io.github.thelok1s.orchestra`) and consolidated
> into a **single app + LSPosed module with NO KSU/root** (metadata key 25 is written from a
> privileged Settings-process hook). For the current design start at **`../INDEX.md`**; the goal,
> the device-settings mechanism, the Pixel-UUID gate, and the RFCOMM protocol below remain accurate.

**Goal.** Make non‑Pixel Bluetooth headphones (Soundcore) controllable from the **native Pixel system UI** —
both the Bluetooth **"About device"** page in Settings *and* the **system volume panel** — exactly as if
they were Pixel Buds ("Maestro"). No vendor app, no companion. Data‑driven: adding a headphone = one JSON file.

This document describes the complete system, **bottom to top**: from the RF bytes on the wire to the pixels
SystemUI draws, every layer that the controls pass through, why each layer exists, what we inject at each
layer, and where Google put hard gates that forced an LSPosed escalation.

> Platform: Pixel 8 Pro, Android 16 (Baklava). Root: KernelSU ("Wild KSU" 3.1.2) + SuSFS 2.0.0. LSPosed (zygisk) installed.
> Devices proven: **Soundcore Space One Pro** (model 3062, over‑ear) and **Soundcore Liberty 4 Pro** (model 3954, buds).

---

## 0. The two surfaces we target

| Surface | Owner | Framework it consumes | Pixel‑only gate? |
|---|---|---|---|
| **"About device" / device details** (Settings ➝ the gear next to a BT device) | `com.android.settings` + `settingslib` | `com.android.settingslib.bluetooth.devicesettings` (binder config + per‑setting providers) | **No** — works with just metadata |
| **Volume panel** Noise‑Control & Spatial‑Audio tiles | `com.android.systemui` (volume panel) | androidx **Slice** (ANC) + the same device‑settings framework (`device_settings` component) | **Yes** — hard‑coded Maestro/Pixel‑Buds UUID check |

Both surfaces are discovered/driven from one place: **`BluetoothDevice` metadata key 25**
(`METADATA_FAST_PAIR_CUSTOMIZED_FIELDS`). That single key is the entire injection point for the "About device"
path and the slice URI for the volume panel. The volume panel adds an *extra* gate on top (see §6).

---

## 1. Layer 0 — The headphones (RFCOMM vendor protocol)

The earbuds expose a **vendor RFCOMM channel** advertised by a model‑specific service UUID:

```
0cf12d31-fac3-4553-bd80-d6832e7b<MODELCODE>
                                 ^^^^^^^^  e.g. 3954 = Liberty 4 Pro, 3062 = Space One Pro
```

> A generic SPP channel (`00001101-…`) also accepts a connection but does **not** carry the control protocol.
> You must connect on the **vendor UUID**.

### Framing — `soundcore_v1`

```
host → device:  08 ee 00 00 00 | <cmd:2> | <len:2 LE, total frame> | <payload…> | <crc>
device → host:  09 ff 00 00 01 | <cmd:2> | <len:2 LE>              | <payload…> | <crc>
crc = sum(all previous bytes) & 0xff      ("sum8")
```

### Noise‑control commands (verified live)

| Action | Command | Payload | Notes |
|---|---|---|---|
| **Read state** | `0101` | — | Response `0101` is a long status packet (Liberty: 175 B / `0x00af`). The ANC **mode byte** sits at full‑packet **index 134** (right after the marker `44 44 33`). Space One Pro uses a different index. |
| **Set mode** | `0681` | `{mode}5000000005` | `mode` = `00` ANC / `01` Off / `02` Transparency. ACK `09ff00000106810a00…`. |

Everything device‑specific (vendor UUID, mode‑byte index, model code, option labels/icons, command bytes) lives
in a **device‑definition JSON** (see §8) — the code is generic.

---

## 2. Layer 1 — Bluetooth stack: metadata key 25 and SDP UUIDs

Two distinct pieces of stack state matter.

### 2a. Metadata key 25 — `METADATA_FAST_PAIR_CUSTOMIZED_FIELDS`

A per‑device blob, conventionally an **XML‑tagged string**: `<TAG>value</TAG><TAG2>value2</TAG2>…`.
Google's Fast Pair normally writes it for Pixel Buds. We write it ourselves (see `Metadata.java`). The tags
that matter:

```
<DEVICE_SETTINGS_CONFIG_PACKAGE_NAME>com.maestro.proxy</…>
<DEVICE_SETTINGS_CONFIG_CLASS>com.maestro.proxy.ConfigProviderService</…>
<DEVICE_SETTINGS_CONFIG_ACTION>com.maestro.proxy.BIND_DEVICE_SETTINGS_CONFIG_PROVIDER</…>
<HEARABLE_CONTROL_SLICE_WITH_WIDTH>content://com.maestro.slice/anc?mac=<MAC>&dev=<id>&width=</…>
```

* The three `DEVICE_SETTINGS_CONFIG_*` tags tell `settingslib` **which app/service to bind** for the device‑settings
  config provider → that's how the About page and the volume‑panel `device_settings` component reach **our** binder.
* `HEARABLE_CONTROL_SLICE_WITH_WIDTH` is the **slice URI prefix** the volume panel ANC tile binds. SystemUI appends
  `<width>&version=2&is_collapsed=<bool>&hide_label=<bool>`, so the stored value **must end with `width=`**.

Read/write use hidden SystemApi (`BluetoothDevice.getMetadata(int)` / `setMetadata(int, byte[])`) requiring
`BLUETOOTH_PRIVILEGED`. We call them via reflection from a **priv‑app** that's granted that permission.

> `BluetoothUtils.getFastPairCustomizedField(device, TAG)` (in `settingslib`, also bundled in SystemUI) is the
> reader: it does `getMetadata(25)` then regex `"<TAG>(.*?)</TAG>"`. We confirmed our values match exactly.

### 2b. SDP service UUIDs — `BluetoothDevice.getUuids()`

The list of services discovered over SDP, **persisted** in `/data/misc/bluedroid/bt_config.conf` under each device's
`[MAC]` section as a space‑separated `Service = …` line. This is **separate** from metadata and is what the volume‑panel
Pixel gate inspects (§6). We can inject a UUID here (see §6, bypass 1).

---

## 3. Layer 2 — the `settingslib` device‑settings framework

`com.android.settingslib.bluetooth.devicesettings` is the AOSP framework that renders "rich" per‑device controls.
It is a set of **AIDL binder interfaces** + **Parcelables**. We hand‑wrote byte‑compatible stubs (the real txn
codes don't match aidl‑codegen — the config interface's real `getDeviceSettingsConfigWithOptions` is **txn 2**).

```
                     reads key‑25 DEVICE_SETTINGS_CONFIG_* tags
Settings / SystemUI ───────────────────────────────────────────►  binds our app
        │
        │  IDeviceSettingsConfigProviderService  (action BIND_DEVICE_SETTINGS_CONFIG_PROVIDER)
        ▼
  ConfigProviderService  ──returns──►  DeviceSettingsConfig { List<DeviceSettingItem> }
        │                               each item: id + (preferenceKey EMPTY → AppProvidedItem
        │                                                preferenceKey SET  → BuiltinItem)
        │
        │  for each AppProvidedItem the consumer binds the per‑setting provider:
        │  IDeviceSettingsProviderService  (action BIND_DEVICE_SETTINGS_PROVIDER)
        ▼
  SettingProviderService
        ├─ getDeviceSettingsConfig / getServiceStatus
        ├─ registerDeviceSettingsListener(deviceInfo, listener)
        │        → opens RFCOMM, reads mode (cmd 0101), pushes a DeviceSetting (MultiTogglePreference)
        │          back to the listener so the UI renders the current state
        └─ updateDeviceSettings(state)
                 → opens RFCOMM, sets mode (cmd 0681), re‑reads, pushes updated state
```

Key Parcelables (all byte‑verified against decompiled AOSP): `DeviceInfo`, `DeviceSettingsConfig`,
`DeviceSettingItem`, `DeviceSetting`, `DeviceSettingPreference`, `MultiTogglePreference`,
`MultiTogglePreferenceState`, `ToggleInfo`, `DeviceSettingState`, plus the two service‑status types and
`DeviceSettingConfigOptions`.

**The one bug that mattered:** if a `DeviceSettingItem`'s `preferenceKey` is **non‑empty**, the consumer treats it
as a *BuiltinItem* and looks up a built‑in preference by that key → not found → **blank page**. Leaving
`preferenceKey = null/empty` makes it an *AppProvidedItem* rendered from the `DeviceSetting` we push (our
3‑way Noise Control multitoggle). This is why the About page initially rendered blank.

---

## 4. Layer 3a — "About device" page (Settings)

Path is entirely §3: Settings reads key‑25 → binds `ConfigProviderService` (txn 2) → gets our config with one
`AppProvidedItem` (`preferenceKey` empty) → binds `SettingProviderService` → `register…` opens RFCOMM, reads the
current ANC mode, pushes a `MultiTogglePreference` (Noise Cancelling / Off / Transparency with custom icons) →
tap → `updateDeviceSettings` → RFCOMM set → re‑read → push. **Works on both devices, live, no gate, no hook.**

---

## 5. Layer 3b — the volume panel (SystemUI)

The redesigned volume panel (`com.android.systemui.volume.panel`) is a set of **components** chosen by
**availability criteria**. Decompiled from `/system_ext/priv-app/SystemUIGoogle/SystemUIGoogle.apk`
(jadx; this build is **not** obfuscated). Open it over adb with:

```
adb shell su -c "am broadcast -a com.android.systemui.action.LAUNCH_VOLUME_PANEL_DIALOG com.android.systemui"
```

Relevant components & their Google availability criteria:

```
GoogleVolumePanelComponentImpl.criteriaByKey:
  "anc"            → AncAvailabilityGoogleCriteria
  "spatial_audio"  → SpatialAudioAvailabilityGoogleCriteria
  "device_settings"→ DeviceSettingsAvailabilityCriteria   (binds OUR proxy — the §3 framework, in the panel)
  "media_output", "volume_sliders", "captioning", "clear_calling", …
```

`ComponentsInteractorImpl.components = filterNotNull(stateIn(combine(perComponentAvailabilityFlows), Eagerly))`
— reactive: when any criterion flips, the visible component set re‑emits.

### The ANC tile data path

```
AncSliceInteractor (per‑panel scope, ancSlices = stateIn(…, Eagerly, Unavailable))
   ▼ for the active AudioOutputDevice.Bluetooth:
   BluetoothUtils.getFastPairCustomizedField(device, "HEARABLE_CONTROL_SLICE_WITH_WIDTH")
   ▼ URI = <tag> + width + "&version=2&is_collapsed=" + z + "&hide_label=" + z2
   SliceViewManagerExtKt.sliceForUri(sliceViewManager, uri)   ← androidx Slice pin/bind (reactive callbackFlow)
   ▼ filter: accept iff (no "error" hint) AND (≥1 item of format "slice")
   combine(buttonSlice, popupSlice) → both non‑null ⇒ AncSlices.Ready
AncAvailabilityCriteria.isAvailable() = (ancSlices is Ready)
AncViewModel.buttonSlice/popupSlice  = filterIsInstance(Ready).map { it.slice }   ← renders the tile
```

So the ANC tile needs: **active device is BT** + **our slice binds & is structurally valid** + (the Google gate, §6).
Our `com.maestro.slice` provider builds exactly such a slice (header + 3‑cell grid, primary action) — verified
`onBindSlice OK (3 cells)`, valid structure (`slice[list_item,horizontal]`, `slice[]`, no `error` hint).

---

## 6. The Pixel gate (why metadata alone is not enough for the volume panel)

`AncAvailabilityGoogleCriteria` (and `SpatialAudioAvailabilityGoogleCriteria`) combine the AOSP availability with
a **Pixel‑device check**:

```java
// AncAvailabilityGoogleCriteria$availability$1   (z = ancSliceReady, z2 = true, bt = activePixelBluetoothMediaDevice)
return z && !(z2 && bt == null);     //  ==  ancSliceReady && (activePixelBluetoothMediaDevice != null)
```

```java
// PixelDeviceInteractor.isPixelDevice(AudioOutputDevice.Bluetooth)
for (ParcelUuid u : device.mDevice.getUuids())
    if (MAESTRO_UUIDS.contains(u.getUuid())) return true;   // else false
// MAESTRO_UUIDS = { 3a046f6d-24d2-7655-6534-0d7ecb759709,
//                   099775cb-7e0d-3465-5576-d2246d6f043a,
//                   25e97ff7-24ce-4c4c-8951-f764a708f7b5,
//                   b5f708a7-64f7-5189-4c4c-ce24f77fe925 }
```

**The active device's `getUuids()` must contain a hard‑coded Maestro/Pixel‑Buds UUID.** Metadata (key 25) cannot
add SDP UUIDs, so the proxy alone can never satisfy this. This is exactly why the About page works but the volume
tile did not. Two ways to clear it:

### Bypass 1 — data‑level UUID injection (no code hook, fits the KSU model)
Add a Maestro UUID to the device's `Service = …` line in `/data/misc/bluedroid/bt_config.conf` while **Bluetooth is
off** (the BT daemon rewrites the file on exit). On re‑enable, `getUuids()` reports it.
* **Verified:** after injection the **Spatial Audio tile appears** (it shares the same gate) and the UUID
  **survived an earbud reconnect** (no SDP wipe).
* Procedure: `svc bluetooth disable` → wait off → `sed` append UUID to the `Service` line → `svc bluetooth enable`.
* Caveat: re‑applying on boot is timing‑sensitive (must edit while the daemon is down); GMS *may* try Pixel‑Buds
  behaviours when it sees the UUID.

### Bypass 2 — LSPosed hook (durable, surgical) — **chosen**
Module `com.maestro.xphook` (`MaestroXposed/`), scope `com.android.systemui`:
* `PixelDeviceInteractor.access$isPixelDevice → true`
* `AncAvailabilityGoogleCriteria$availability$1.invokeSuspend → true`

The second hook also fixes a **second, ANC‑specific problem**: even with the gate open and the slice binding,
the ANC component was being sampled as *unavailable* at panel‑open (the slice binds ~40 ms later, async via
`flowOn(mainCoroutineContext)`), then pruned and never re‑observed (a `notifyChange` while the panel was open
triggered **no** re‑bind → no live subscription). Forcing the availability combine to `true` keeps the ANC
component permanently in the layout, which keeps the slice subscription alive → `Ready` is reached → content renders.
Hook load confirmed: `[MaestroXposed] hooked … -> true` for both.

---

## 7. Our components (the whole stack we ship)

| Component | Package / path | Role | Deploy |
|---|---|---|---|
| **MaestroProxyApp** | `com.maestro.proxy` → `MaestroProxyModule/system/priv-app/MaestroProxy/` | Writes key‑25 metadata; serves the device‑settings **config** + **per‑setting** binders; does the RFCOMM I/O for the About page | **priv‑app** in the KSU module (needs `BLUETOOTH_PRIVILEGED`) |
| **MaestroSlice** | `com.maestro.slice` (`MaestroSlice/`) | androidx `SliceProvider` serving the volume‑panel ANC slice; `ApplyReceiver` does RFCOMM on tap | normal APK (granted `BLUETOOTH_CONNECT`) |
| **MaestroXposed** | `com.maestro.xphook` (`MaestroXposed/`) | LSPosed hooks that clear the Pixel gate + force ANC availability | normal APK, enabled in LSPosed manager, scope = System UI |
| **bt_config injection** | `/data/misc/bluedroid/bt_config.conf` | (Optional, alt to the hook) adds a Maestro UUID to the device's SDP services | root edit while BT off |
| **Device definitions** | `framework/devices/*.json`, mirrored to `MaestroProxyModule/devices/` and `MaestroSlice/app/src/main/assets/devices/` | Per‑headphone protocol + UI data | data |
| **WebUI / config** | `MaestroProxyModule/webroot/`, `/data/adb/maestroproxy/config.json` | Enable devices `{mac, device}` | KSU WebUI |

**Proxy app source map** (`MaestroProxyApp/src/com/maestro/proxy/`):
* `ConfigProviderService.java` — hand‑written binder; txn 1 (legacy) + **txn 2** `getDeviceSettingsConfigWithOptions`. Emits one `AppProvidedItem` (`preferenceKey` empty).
* `SettingProviderService.java` — txn 1 status, 2 register (RFCOMM read→push), 3 unregister, 4 update (RFCOMM set→re‑read→push).
* `Metadata.java` — key‑25 upsert of the 4 tags (preserves others), via reflection.
* `DeviceDef.java` — loads `/data/adb/maestroproxy/devices/<id>.json` + `config.json` (reads via `su -c cat` because `/data/adb` is `0700`).
* `ConnectReceiver.java` — BOOT/APPLY/ACL_CONNECTED re‑assert key‑25 (clobber‑guard vs GMS Fast Pair).
* `MainActivity.java` — launcher (cold‑starts the process so it's eligible for broadcasts) + applies config.
* `build.sh` — offline CLI build: `javac → d8 → aapt2 link → zip dex → zipalign → apksigner` (no Gradle).

**Slice app** (`MaestroSlice/app/src/main/java/com/maestro/slice/`): `AncSliceProvider` (builds the slice; header
primary action is **mandatory** or `SliceProviderWrapper` rejects it with `am_wtf "A slice requires a primary
action"`), `Sc` (RFCOMM + device‑def loader from assets), `ApplyReceiver` (apply + `notifyChange`), `TestActivity`
(debug bind harness — note: the *platform* `SliceManager.bindSlice` throws `No valid specs found` regardless of
spec names; only SystemUI's androidx pin path supplies specs correctly).

---

## 8. Device‑definition JSON (the extensibility contract)

One file per headphone, e.g. `soundcore-liberty-4-pro.json`:

```json
{
  "id": "soundcore-liberty-4-pro",
  "name": "Soundcore Liberty 4 Pro",
  "model_code": "3954",
  "match":     { "name_regex": "(?i)soundcore Liberty 4 Pro",
                 "service_uuids_any": ["0cf12d31-fac3-4553-bd80-d6832e7b3954"] },
  "transport": { "type": "rfcomm", "uuid": "0cf12d31-fac3-4553-bd80-d6832e7b3954" },
  "protocol":  { "framing": "soundcore_v1", "cmd_prefix": "08ee000000",
                 "resp_prefix": "09ff000001", "checksum": "sum8" },
  "functions": [{
    "id": "sound_mode", "type": "multitoggle", "title": "Noise control",
    "options": [ {"id":"anc","label":"Noise Cancelling","icon":"anc"},
                 {"id":"off","label":"Off","icon":"off"},
                 {"id":"transparency","label":"Transparency","icon":"transparency"} ],
    "set":  { "command":"0681", "payload_template":"{mode}5000000005",
              "option_values": {"anc":"00","off":"01","transparency":"02"} },
    "read": { "command":"0101", "state_byte_index":134,
              "value_map": {"00":"anc","01":"off","02":"transparency"} },
    "ui":   { "setting_id":2201, "surfaces":["device_details","volume_panel"] }
  }]
}
```

**Adding a new Soundcore device** = create this file (set `model_code`, the vendor UUID, the `state_byte_index`,
the option values), drop it in the three `devices/` locations, add the device to `config.json`, re‑assert. Only the
vendor UUID and `state_byte_index` differ between the proven models.

---

## 9. Deployment & gotchas (hard‑won)

* **KSU + SuSFS hides module mounts from app processes** → the priv‑app APK looked corrupt (ENOENT/NPE). Fix: add
  `com.maestro.proxy` to the **KSU root allowlist** (`/data/adb/ksu/.allowlist`). Do **not** touch the global
  "Unmount modules" toggles. App stays in `priv_app:s0` so `baseband_guard` doesn't kill BT.
* `/data/adb` is `0700` → the app can't read its own config dir; it shells out `su -c cat` (allowlisted, silent).
* `setMetadata` needs `BLUETOOTH_CONNECT` at runtime too: `pm grant com.maestro.proxy android.permission.BLUETOOTH_CONNECT` (and `…SCAN`). Same for `com.maestro.slice`.
* Android 16 won't cold‑start the app from a background broadcast → the launcher `MainActivity` exists to start the process.
* GMS Fast Pair may rewrite key‑25 around connect → `ConnectReceiver` re‑asserts on `ACL_CONNECTED`.
* **Stale slice process**: after reinstalling the slice app, SystemUI keeps binding the old cached process →
  `am force-stop com.maestro.slice` + restart SystemUI to clear it.
* **Restart SystemUI**: `adb shell su -c "killall com.android.systemui"`.
* **The device must be the active audio output** for the volume‑panel paths (play audio to it). After a BT‑stack
  restart the active device resets to the phone speaker.

---

## 10. Can LSPosed do more? (settings + arbitrary controls, both surfaces)

**Yes — substantially.** Once we hook SystemUI (and, if wanted, `com.android.settings`), we are no longer limited
to "what metadata can express." We can read, modify, reorder, relabel, re‑icon, add, or remove controls on **both**
surfaces, per‑headphone, because both ultimately render data we can intercept:

1. **Fix / extend the About‑device settings page.** Hook the `settingslib` device‑settings rendering (or our own
   `ConfigProviderService`/`SettingProviderService`, which we fully own anyway) to: add controls beyond Noise
   Control (EQ, touch‑gesture mapping, in‑ear detection, find‑my, LDAC/codec toggles — anything the RFCOMM
   protocol supports), change layout/labels/icons, bypass any future gate, and inject `BuiltinItem`s (battery,
   header image) so it reads like a real Pixel Buds page. We already drive this path with zero hooks; LSPosed only
   adds the ability to also touch the *consumer* side (Settings) for things the provider API can't express.

2. **Put/modify controls in the volume panel.** We can:
   * force component availability (done for ANC),
   * **rewrite the slice content on the fly** — the slice is just an androidx `Slice`; we can add cells, change
     icons/labels, add a second row (e.g. an EQ or a transparency‑level slider), intercept the tap `PendingIntent`,
   * register **new components** or repurpose `device_settings` to surface extra toggles next to Noise Control and
     Spatial Audio,
   * because the apply action routes through our `ApplyReceiver`/`SettingProviderService`, any control maps to an
     arbitrary RFCOMM command.

**Constraints / costs.** Rendering is still bounded by the **templates** each surface supports — volume‑panel
tiles are slice templates (header/grid/row, toggles, range); the device‑details page supports the
device‑settings item types (toggle, **multi‑toggle**, slider/preference, footer). Within those we have full
freedom; for genuinely custom widgets we'd hook the composables/views directly (more fragile). LSPosed hooks bind
to SystemUI/Settings **class names**; this build is unobfuscated so they resolve cleanly, but an OTA can rename
internals → the hooks need re‑validation after updates (keep them name‑tolerant: match by method shape, log the
candidates, fail soft — as the current module does).

**Bottom line:** the proxy/metadata path is enough for the About page and is the clean, update‑safe core. LSPosed
is the lever for (a) the Pixel‑only gates and (b) anything richer than the stock templates — and yes, it lets us
place and tweak arbitrary controls on **both** the sound panel and the settings page, tailored per headphone.

---

## 11. Status (2026‑06‑03)

* ✅ About‑device page: full Noise Control with live RFCOMM read/apply — **both** devices.
* ✅ Framework generalises across devices (one JSON per headphone).
* ✅ Volume‑panel **Pixel gate identified and cleared** — Spatial Audio tile now renders (proof the gate is open);
  bt_config UUID injection works and is reconnect‑durable.
* ✅ ANC slice binds and is structurally valid (`onBindSlice OK (3 cells)`, both compact + grid variants); LSPosed
  module loads and fires all hooks (`isPixelDevice`→true, ANC availability combine→true, `isAvailable()`→flowOf(true)).
* ❌ **Volume‑panel Noise‑Control tile still does not render.** With the Pixel gate cleared, ANC availability fully
  forced (no crash), and the slice binding OK for both `is_collapsed` variants, the tile never appears — only Spatial
  Audio. The crash that briefly occurred when forcing availability (`ClassCastException: Object[] cannot be cast to
  Boolean` in `ComponentsInteractorImpl`, my malformed `flowOf` — now fixed) proved the ANC component **is** pulled
  into the pipeline when available. So the remaining blocker is the **UI layer**: SystemUI's ANC Compose component
  renders `AncViewModel.buttonSlice` via a SliceView and produces nothing visible for our third‑party slice (Spatial,
  a native Compose tile with no slice dependency, renders fine). Next: capture a **real Pixel Buds ANC slice** to
  replicate its exact structure, or hook the ANC Compose render path directly.
* Testing friction: each SystemUI restart (needed to clear its slice‑bind cache for a fresh bind) re‑engages the
  device PIN lock, which blocks scripted UI capture until a manual unlock.

## 12. SHIPPED solution (2026‑06‑03) — clean, no hooks

After confirming the inline volume‑panel ANC tile is blocked deep in SystemUI's Compose layer (the "anc" component's
`Content` never composes even with availability forced and the device‑setting fed — verified the component never
binds our provider during a panel open), we took the **clean, fragility‑free route**:

* **No LSPosed module, no `bt_config` UUID injection** — the device stays **non‑Pixel**.
* Volume panel therefore shows the native **"Device settings" button** (the `device_settings` component, gated on
  `activeNonPixelBluetoothMediaDevice`). Tapping it fires `Intent("com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS")`
  → our **"About device" control page**.
* That page renders our **3‑way Noise Control** (Noise Cancelling / Off / Transparency, custom icons, live state) via
  the device‑settings framework, plus the standard footer. **Verified live on the Space One Pro**: tap Transparency →
  `update → TX set transparency 08ee…02… → readMode value=02 -> transparency` (headphones switched).
* Works for **both** headphones via the per‑device JSON; **zero hooks, OTA‑safe**.

Net surfaces delivered: **About‑device page = full inline Noise Control** (primary surface); **volume panel = one‑tap
"Device settings" button → that page**. The Pixel‑style inline volume tile + Spatial Audio remain available *only*
via the Pixel‑spoof (UUID/LSPosed) path documented above, which is a fragile, deep‑RE option, not the shipped default.

See also the memory notes: `volume-panel-anc-pixel-uuid-gate`, `maestro-devicesettings-arch`, `soundcore-proxy-project`.
