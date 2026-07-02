# Device definition schema (v2)

A device definition is a JSON file describing how to (a) recognize a Bluetooth device,
(b) talk to it, and (c) map each of its capabilities to a native Android device-settings
control — **and, crucially, whether that capability can be injected into the Pixel native UI
at all.** The headless provider app reads these at runtime; the WebUI / in-app Devices tab
browses `catalog.json` and lets the user enable/disable each injectable capability per device.

> **v2 vs v1.** v1 only modelled the one working control (`sound_mode`). v2 models the *full*
> capability catalog reverse-engineered from OpenSCQ30 / SoundcoreManager / SoundcoreDesktop,
> tags every function with a UI `type`, and records whether it is **injectable** on the Pixel
> About-device page (and why not, when it isn't), plus inter-capability **conflicts**.
> The app shows non-injectable / conflicting capabilities greyed-out with the reason.

## Top level
| field | meaning |
|---|---|
| `schema` | schema version (`2`) |
| `id` | stable slug, also the definition filename |
| `name` / `vendor` / `model_code` | display + identification |
| `re_model` | matching OpenSCQ30 model code (e.g. `A3062`) for provenance; may differ from `model_code` |
| `match` | how to recognize the device |
| `transport` | how to connect |
| `protocol` | framing/checksum used to build & parse frames |
| `functions[]` | every capability + its UI mapping + injectability |

## match
First matching definition wins. Any provided sub-rule must hold:
- `name_regex` — Java regex against the BT name.
- `service_uuids_any` — device must advertise at least one of these SDP UUIDs.
- `model_name_prefix` (optional) — prefix of metadata key 3 (MODEL_NAME).

## transport
- `type`: `rfcomm` (only one implemented). Future: `ble_gatt`, `spp`.
- `rfcomm`: `uuid` (connect via `createInsecure/RfcommSocketToServiceRecord`), `secure` bool.

## protocol
- `framing`: `soundcore_v1` = `08 ee 00 00 00 <cmd:2> <len:2 LE total> <payload> <crc>`;
  responses `09 ff 00 00 01 ...`. (Command bytes here equal OpenSCQ30's `Command([hi,lo])`.)
- `checksum`: `sum8` = sum(all preceding bytes) & 0xFF.
- `cmd_prefix` / `resp_prefix`: hex prefixes for the codec.

## functions[]
Each function is one capability / user-facing control.

| field | meaning |
|---|---|
| `id` | stable slug |
| `type` | UI control kind — see table below |
| `title` / `title_i18n` | label + `{lang: text}` overrides |
| `icon` | logical glyph name for switch/row controls (resolved by the app's `Icons`: anc, adaptive, transparency, off, dolby, surround, multipoint, ldac, ear, wind, mic, battery, volume, gaming, touch, tune). `multitoggle` uses per-option `icon` instead. |
| `summary` / `summary_i18n` | subtitle shown under the title on switch/row controls (Pixel-Buds-style icon+title+subtitle) |
| `capability` | OpenSCQ30 module name this came from (provenance) |
| `options[]` | for `multitoggle`/`list`: `{id,label,icon,label_i18n?}` |
| `range` | for `slider`: `{min,max,step,unit?}` |
| `feature` | for `level` (AAP only): hex feature byte (e.g. `"2E"`) written/read via the generic single-byte AAP feature frame |
| `min` / `max` / `step` | for `level`: flat bounds/step (distinct from `slider`'s nested `range`) |
| `local` | `true` when the control has no `set`/`read` protocol frame at all — handled entirely on-device (e.g. `rename` via `BluetoothDevice.setAlias`); app-surface only |
| `set` | how to write the value (see below) |
| `read` | how to read current value (see below) |
| `inject` | `"auto"` (default) \| `true` \| `false` — see injectability |
| `inject_reason` | text shown in UI when not injectable (auto-filled if omitted) |
| `conflicts_with` | `[function_id,…]` capabilities that cannot be active simultaneously |
| `requires` | `{function_id: value}` — this control is only meaningful when another holds a value |
| `ui.setting_id` | the `DeviceSettingItem` id exposed. `1001` = reserved ANC (volume panel). Framework range ≥ `2200`. |
| `ui.surfaces` | `device_details`, `volume_panel` |
| `_verified` | `true` only when the set/read bytes were confirmed live on hardware |

### type → render → injectability
| `type` | Android preference | Injectable on About page? |
|---|---|---|
| `multitoggle` | `MultiTogglePreference` (SegmentedButton) | ✅ **iff ≤ 4 options**, else auto-false (`too-many-options`) |
| `toggle` | `ActionSwitchPreference` (Switch) | ✅ |
| `list` | — (no native list pref in the configurable fragment) | ❌ auto-false (`no-native-list`) → in-app screen |
| `slider` | — (no slider in the configurable fragment) | ❌ auto-false (`no-native-slider`) → in-app screen |
| `level` | — (no native slider in the configurable fragment) | ❌ auto-false (`no-native-slider`), app-surface only — a 0–max scalar control (e.g. AirPods Adaptive Audio strength) driven by a generic AAP feature byte instead of a full protocol command |
| `text` | — (no native text input in the configurable fragment) | ❌ auto-false, app-surface only — a local free-text control (e.g. rename); typically `local:true` (no `set`/`read` protocol frame) |
| `info` | `FooterPreference` / read-only row | display-only (`inject:false`, not a control) |

**`inject: "auto"`** applies the rules above. Set `inject:false` (with `inject_reason`) to force
a capability off the native page even when it could render (e.g. unverified bytes you don't want
to expose yet). Set `inject:true` only to override a heuristic you know is wrong.

### set
- `command`: hex command id (2 bytes, = OpenSCQ30 `Command`).
- `payload_template`: hex with `{mode}` / `{value}` / `{state}` placeholders.
- `option_values`: maps option id → substituted hex (for `multitoggle`/`list`).
- `state_values`: maps `on`/`off` → hex (for `toggle`).

### read
- `command` / `response_command`: hex command ids to request and to match in the reply.
- `state_byte_index`: byte offset in the full response packet holding the value (null if unknown).
- `value_map`: maps the hex byte → option id / `on`/`off`.

## conflicts & dependencies
- `conflicts_with`: when the user enables (or the device reports active) two mutually-exclusive
  capabilities, the app surfaces the clash on the device card and keeps one, disabling the other.
  Well-known Soundcore exclusions: **LDAC ⟷ Multipoint**, **LDAC ⟷ Gaming mode**.
- `requires`: a control whose `requires` isn't met is shown but inert (e.g. manual ANC level
  requires `sound_mode = anc`). Used to grey-out dependent controls.

## per-device user enablement
Injectable capabilities default to **on**. The Devices tab persists an enabled-set per device MAC;
`ConfigProviderService` only emits app-items for capabilities that are `injectable` **and** enabled
**and** whose conflicts are resolved. Non-injectable capabilities are listed but not toggleable
(shown with their `inject_reason`).

## icons
Logical icon names (`anc`, `off`, `transparency`, …) map to drawables in the app so definitions
stay device-agnostic.

## Adding a device
1. Find the OpenSCQ30 model (`re_model`) and copy its capability list + command bytes.
2. Mark every function `_verified:false` until confirmed live (see in-app RFCOMM console / test tool).
3. Write `devices/<id>.json`; add an entry to `catalog.json` (capability + verified counts).
