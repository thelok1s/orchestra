# AirPods Pro 2 (USB-C) — control reference

| | |
|---|---|
| Name match | `(?i)airpods pro` |
| Model code | A3048 (Pro 2, USB-C / Lightning share the manifest) |
| Service UUID (match) | `74ec2172-0bad-4d01-8f77-997b2be0722a` (Apple AAP) |
| Transport | **AAP (Apple Accessory Protocol) over L2CAP, PSM `0x1001` (4097)** |
| MAC (RE unit) | `38:C4:3A:34:9E:5E` |
| Firmware | 7Axxx-class (AAP defs cross-checked against LibrePods' 7A305 capture) |

Manifest: `manifests/apple/airpods-pro-2.json` (schema v4, **rev 9**, 8 functions, all `_verified:true`).
Unlike Soundcore, AirPods are **code-first**: the `aap_v1` framing (handshake → feature-enable →
notification-request, opcode routing, push reader) lives in `AacpEngine`; the manifest only names
the framing and the per-function opcode/option bytes. *Engine knows the protocol; manifest knows the
device.*

## Framing (`aap_v1`)

AAP is a **push** protocol over a single persistent L2CAP socket, not request/response polling.
Control frames share an 11-byte shape; battery/ear frames are variable-length:

```
noise-control / toggle (set + notify):
  04 00 04 00 09 00 <feature> <value> 00 00 00
    feature 0D = listening mode · 28 = conversational awareness
    listening-mode value: 01 Off · 02 ANC · 03 Transparency · 04 Adaptive
    CA value:             01 on · 02 off

battery (notify):  04 00 04 00 04 00 <count> (<comp> 01 <level> <status> 01)*
    comp: 02 = single/L · 04 = case · 08 = R (bud enum) · level 0..64 (0x64=100%)

ear-detection (notify):  04 00 04 00 06 00 <primary> <secondary>
    0 = in-ear · 1 = out · 2 = in-case
```

Header `04 00 04 00` is the AAP "control command" container; byte 4 is the opcode, byte 6 the
feature/sub-op. The engine caches every inbound notification into `AapState`; `readMode`/`readInfo`
return the cache instantly (never blocks the binder thread) and the About page fills as frames land.

> Hidden-API note: reflecting the `BluetoothSocket` L2CAP constructor on `targetSdk 37` needs
> `HiddenApiBypass` (no `settings put global hidden_api_policy`). The reflected connect to PSM
> `0x1001` succeeds **rootless** on Pixel 8 / Android 16–17 — no `l2c_fcr` BT-stack hook required
> (Phase 0 PASS branch).

## Control table

| Control | opcode / feature | set payload | values | Verified | Inject | Notes |
|---|---|---|---|---|---|---|
| Noise control (ANC) | `0900` / `0D` | 11-byte frame, `{mode}` at byte 7 | Off=`01` ANC=`02` Transparency=`03` Adaptive=`04` | ✅ | multitoggle | native About-page tile; long-press ANC-cycle also reports here |
| Conversational Awareness | `0900` / `28` | 11-byte frame, `{state}` at byte 7 | on=`01` off=`02` | ✅ | switch | native toggle |
| Battery (L / R / case) | `0400` notify | — (read-only push) | level 0..100, +charging | ✅ | none | rendered on the Fast-Pair **battery header** (untethered-battery metadata keys) + in-app; `inject:false` keeps it off the settings page (settingslib won't render an action-less row) |
| Ear detection | `0600` notify | — (read-only push) | in-ear / out / case | ✅ | in-app | drives background auto-pause; `inject:false` |
| Auto-pause on removal | behavior | — | on/off | ✅ | switch (behavior) | `AapBehaviorController`; holds the session open so ear frames keep streaming |
| CA volume-duck | behavior | — | on/off | ✅ | switch (behavior) | gradual fade on detected speech |
| Adaptive Audio strength | `0900` / `2E` | level 0..100 (`AUTO_ANC_STRENGTH`) | 0..100 | ✅ | in-app (slider) | active only while Noise control = Adaptive |
| Rename | (BT alias) | — | free text | ✅ | in-app (text) | sets the local Bluetooth alias; re-pair for the buds to echo the new name |

Verified = ✅ live on the RE unit (AAP reader capture via the SystemUI broker), 2026-06 → 07.

## Ownership / handoff + Apple-identity spoof (opt-in tier)

- **Apple-identity DID spoof** ("Act as Apple device", Devices tab, **default-off**): a self-installing
  inline hook (`BTA_DmSetLocalDiRecord` via ShadowHook, from the Bluetooth process) reports the local
  adapter's DID vendor as Apple `0x004C`. Unlocks Apple **stay-multipoint** + seamless audio handoff
  (Mac ↔ Pixel, "Move to iPhone"-style). System-wide while active; OTA-fragile; use at your own risk.
- **Ownership frames** (`0x0e`, parsed into `AapState`): `owns` / `ownedByOther` toggling identifies
  which host currently holds the buds; surfaced as the app's ownership/handoff status row.
- **Buttons ⊥ multipoint tradeoff (firmware-level, do not revisit).** Under the spoof, stem media
  presses (single/double/triple) reach the host **only** via a `STEM_CONFIG` (`0900`/`39`) takeover,
  which makes the firmware treat the Pixel as sole host and **drops multipoint**. Hardware-confirmed
  down to a single-press takeover; LibrePods hits the identical wall (its live `STEM_CONFIG` stays
  `00`, media buttons equally dead under the spoof). So: spoof **on** → handoff + multipoint, no media
  buttons; **off** → media buttons (stock AVRCP) + standard multipoint, no handoff. Long-press
  ANC-cycle works either way (bud-native). See the README "Known limitations" bullet.

## Unidentified / not mapped

- `0x19` STEM_PRESS — only emitted **after** a `STEM_CONFIG` takeover (single `…19 00 05`, double
  `…06`, triple `…07`, long `…08`); unusable without breaking multipoint (above).
- `0x55` (`04 00 04 00 55 00 01 01 <level>`) — volume-swipe candidate; **absolute level**, not a clean
  up/down delta → not injectable as a media key. Deferred.
- `0x52` (`03 00 02 01 00`) — appeared sparsely on stem activity; too rare to be a reliable per-press
  signal; unidentified.

## Verification method (live)

AAP has no btsnoop-friendly request/response; capture from the engine instead. The SystemUI-resident
broker (`AapBroker` + `AacpEngine`) owns the single AAP socket and logs inbound frames; a temporary
RAWCAP hexdump in the reader loop prints every frame for opcode discovery. Drive controls from the
native page or the in-app screens and watch logcat tag `Orchestra` / `AACP` for the echoed
notification. Cross-reference LibrePods' `AACPManager` (which logs its full control-command state) and
its `docs/AAP Definitions.md` / `control_commands.md` for opcode names.
