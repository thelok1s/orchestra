# Orchestra framework — extension & ergonomics plan

Status: **largely implemented (updated 2026-06-24).** Schema v2 + full capability catalog +
per-control enable/disable UI + native switches (Stage D) + persistent RFCOMM socket + live
btsnoop-verified command bytes + a redesigned app (animations, predictive back, status tiles,
battery, accordions) + **4-mode ANC** (NC/Off/Adaptive/Transparency, composite `0681` with
multi-byte readback `match`) + **schema v3 + manifest repo + OTA** (the `orchestra-manifests`
repo with CI index; named transport channels [rfcomm now, ble_gatt/aacp reserved]; embedded
per-ROM `platforms` bindings; in-app index-eligibility highlight, OTA download/update, and
sideload — see `docs/superpowers/specs/2026-06-24-manifest-repo-schema-v3-design.md`). Remaining:
**volume-panel independence (#2)** and an **in-app screen** for slider/list/composite controls.
Next phase: **first release** — see `docs/RELEASE-PREP.md`.

## Done 2026-06-07 (this is the source of truth for what landed)
- **Schema v2** (`framework/SCHEMA.md`): every capability has a `type` (multitoggle/toggle/list/
  slider/info), auto-derived `inject`ability + reason, and `conflicts_with`/`requires`.
- **Capability catalog** populated for both devices from `re_refs/` (OpenSCQ30 A3062 = Space One Pro;
  A3957 Liberty 5 = Liberty 4 Pro cousin). Real command bytes; only `sound_mode` is `_verified:true`.
- **`DeviceDef`** parses the full catalog; computes `injectable` (per schema) vs `implemented`
  (renderable now = multitoggle only). `injectedFuncs(addr)` = implemented + enabled + conflict-free.
- **`DeviceStore`** per-capability enable overrides (`caps_override`); default = `_verified` (so we
  never push guessed bytes until the user opts in). Public `capabilities(mac)` bridge for the UI.
- **Provider services** now iterate `injectedFuncs` — unchanged behavior for verified ANC.
- **Devices tab**: each hooked device expands to its capability list; toggleable controls have a
  switch, non-injectable (slider/list/info) and conflicting ones are greyed with the reason; an
  `unverified` flag shows on controls whose bytes aren't hardware-confirmed.

## Done 2026-06-13 (Stage D — native switch rendering)
- RE'd the `ActionSwitchPreference` + `ActionSwitchPreferenceState` + `DeviceSettingAction` wire
  format from `settings_re` and mirrored them as parcelables (type tag 1; switch always writes a
  DeviceSettingAction, EMPTY=actionType 0). `DeviceSettingState` CREATOR dispatches type 1.
- `RfcommEngine.applyToggle`/`readToggle` (state_values on/off + `{state}` template).
- `SettingProviderService` builds `ActionSwitchPreference` for `toggle` funcs, reads their state,
  and routes `ActionSwitchPreferenceState` updates (checked→0/1) through the same optimistic path.
- `Func.implemented` now includes `toggle`. **Verified on-device**: enabling "Wind noise reduction"
  in the app → it renders as a native switch on the Space One Pro About page beneath the ANC
  control; flipping it fires `update ... toggle wind_noise_reduction -> true` → optimistic push
  `{2213=1}` → `applyToggle` (RFCOMM frame built/sent; the one observed failure was a transient
  socket timeout while the device was mid-connection, not a wire-format bug).

## Done 2026-06-13 (persistent RFCOMM socket)
- `RfcommEngine` now pools one control socket per device (`Session` = socket+streams+lastUsed under a
  per-session lock). `withSession(...)` opens on first use, reuses across set/read ops AND across
  taps, validates `isConnected()`, retries once on `IOException` (reconnect for a stale socket), and
  a daemon reaper idle-closes after 8s. Reads `drain()` the stream first so a prior set's ACK can't
  pollute them. Serialized lock makes the two callers (provider executor + VolumeApplyReceiver thread)
  safe on one socket. **Verified**: one tap = one `session opened` covering both `TX set` + `readMode`;
  a second tap within 8s reused it (no new connect); idle-close at 8s; clean reconnect after.

## Next
1. Verify the catalog's guessed command bytes on-device (enable a toggle, watch logcat `TX toggle`)
   and set `_verified:true` per control as confirmed.
2. In-app screen for `slider`/`list` capabilities (EQ bands, manual ANC level, auto-power-off).
3. Unsolicited-state subscription (reflect physical-button / Soundcore-app changes without polling).

---
Original plan (control-type expansion, ergonomics, RFCOMM robustness):

## 1. Where we are

- One control type works end-to-end: **multitoggle** (`MultiTogglePreference` →
  `SegmentedButtonPreference`), rendered on both the About page and the volume-panel ANC tile.
- The framework already supports **multiple functions per device** (list, routed by `settingId`),
  **localization** (`*_i18n`), **optimistic UI**, and **per-device enable**.
- Surfaces: **About page** (rich — many preference types) vs **volume panel** (narrow — only the
  reserved ANC id 1001 + Spatial render as inline tiles).

## 2. Control-type expansion

The configurable About-page fragment (`BluetoothDetailsConfigurableFragment.refreshAppProvidedPreference`,
decompiled in `settings_re/`) accepts these app-provided model types:

| Model type | Renders as | Status | Work needed |
|---|---|---|---|
| `MultiTogglePreference` | SegmentedButton (≤4) | ✅ done | — |
| `SwitchPreference` (no action) | SwitchPreferenceCompat | ⏳ | RE the `DeviceSettingPreference` subclass wire format; add parcelable + `ToggleState` |
| `SwitchPreference` (+action) | PrimarySwitchPreference | ⏳ | same + an action/PendingIntent field |
| `PlainPreference` (+action) | Preference row / CardPreference | ⏳ | parcelable + `DeviceSettingActionModel` (PendingIntent or intent) |
| `BannerPreference` | BannerMessagePreference | ⏳ | parcelable + 2 buttons |
| `FooterPreference` | FooterPreference | ⏳ | trivial parcelable |
| `MoreSettingsPreference` | "More settings" → sub-fragment | ⏳ | opens `DeviceDetailsMoreSettingsFragment` |
| **Slider** | — | ❌ not supported | No slider in the configurable fragment. Use MoreSettings sub-page or our own in-app screen. |

**Next type to add: `SwitchPreference`** (Dolby on/off, wind reduction, multipoint, in-ear
detection…). Plan:
1. Capture a real Pixel-Buds switch over the binder to get the exact `DeviceSettingPreference`
   subclass + parcel layout (hook `Binders.listenerOnChanged` / the system's marshalling, or read
   `settingslib` source). Mirror it as a parcelable next to `MultiTogglePreference`.
2. Add `type: "toggle"` to the JSON schema: `set.on`/`set.off` commands (or one command with a
   `{state}` template), `read` with a boolean state byte.
3. `SettingProviderService`: emit `SwitchPreference`, route `updateDeviceSettings` (state = bool).
4. `RfcommEngine`: generalize `applyMode` to accept a target value (already per-`Func`).

**EQ**: there is no slider on the About page. Two viable paths:
- (a) An **EQ multitoggle** of presets (Bass/Balanced/Treble/Custom) — works today.
- (b) A dedicated **in-app EQ screen** (Compose sliders) that talks to `RfcommEngine` directly,
  reachable from a `MoreSettingsPreference` row or the Devices tab. Custom band gains live here.

## 3. Volume panel beyond ANC

The panel only inlines components with reserved ids. ANC = `getDeviceSetting → map.get(1001)`.
To add another inline tile, find its reserved id the same way: grep
`…ExternalSyntheticOutline0.m(<id>, map)` in `DeviceSettingServiceConnection$getDeviceSetting$
$inlined$map$1` (`sysui_re/jadx_bad`). Spatial Audio already renders natively (Pixel-gated, no
provider). Anything without a reserved id can only live on the About page / in-app — that's fine;
the panel is intentionally minimal.

## 4. Schema v2 (ergonomic device authoring)

Current `assets/devices/<id>.json` is verbose and ANC-shaped. Proposed v2:

- **Shared protocol profiles**: factor `protocol`/`framing`/`cmd_prefix`/`crc` into a named profile
  (`"protocol": "soundcore_v1"`) referenced by devices, so a new Soundcore model is ~15 lines.
- **Typed functions** with a `kind` discriminator (`multitoggle` | `toggle` | `eq_presets` |
  `action`) and per-kind fields; validate against `framework/SCHEMA.md`.
- **Declarative read parsing**: `read: { command, match_response, state: { byte_index | tlv_path } }`
  to cover devices whose state isn't a fixed offset.
- **i18n everywhere**: `title_i18n` / `label_i18n` already supported; extend to summaries/banners.
- **Surfaces per function**: keep `surfaces: [device_details, volume_panel]`; default device_details.
- Keep `framework/` as the single source of truth; a Gradle/CI step copies → `assets/devices/`
  (today they're hand-synced — automate to prevent drift).

## 5. Device authoring ergonomics

- **In-app "RFCOMM console"** (debug screen): connect to a hooked device, send raw frames, log
  responses, and a guided "capture sound-mode bytes" flow. Turns RE of a new device into a few taps.
- **Import/export** a device JSON from the Settings tab (share sheet) so contributors can submit defs.
- **Validation**: parse-time schema check with clear errors surfaced in the Settings/debug page.
- **Auto-match preview**: Devices tab already matches by `name_regex`; show *why* a bonded device
  did/didn't match (regex + UUID) to debug new entries.

## 6. RFCOMM robustness

Today each set/read opens a fresh socket. For multi-control pages this means several connects.
- **Pooled/persistent control socket** per device (open on first use, idle-close after N seconds),
  serialized through the existing single-thread executor.
- **Unsolicited state**: subscribe to device push frames so external changes (physical buttons, the
  Soundcore app) reflect in our UI without polling.
- **Reconnect/backoff** + surfacing connection errors in the control's `isAllowedChangingState`.

## 7. Suggested order

1. `SwitchPreference` type (unlocks the most real controls) + schema `toggle`.
2. Persistent RFCOMM socket + unsolicited-state subscription (quality-of-life for all controls).
3. In-app RFCOMM console + import/export (authoring ergonomics).
4. EQ: presets multitoggle now; dedicated in-app EQ screen later.
5. Schema v2 + `framework/`→assets automation.
