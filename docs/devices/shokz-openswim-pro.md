# Shokz OpenSwim Pro — control reference

| | |
|---|---|
| Name | `OpenSwim Pro by Shokz` |
| Model code | S180 |
| Company ID | `0x0cac` (Shenzhen Shokz Co., Ltd.) |
| Control UUID | SPP `00001101-0000-1000-8000-00805f9b34fb` (RFCOMM, PSM `0x0003`) |
| Framing | `shokz_v1` (custom binary TLV — **not** Soundcore `soundcore_v1`) |
| Manifest | [`manifests/shokz/shokz-openswim-pro.json`](https://github.com/thelok1s/orchestra-manifests/blob/main/manifests/shokz/shokz-openswim-pro.json) |
| Verified | ✅ live, 2026-07-06 HCI btsnoop (Pixel 8 Pro, phone MAC `c8:17:ec:7b:33:5c`) |

Dual-mode device: Bluetooth audio **and** an internal MP3 player (onboard storage). AVRCP
(PSM `0x0017`, CID `0x0076`) carries standard media transport and is handled natively by Android;
everything below is the proprietary `shokz_v1` control channel over RFCOMM/SPP.

## Framing (`shokz_v1`)

Two host→device packet formats share the one RFCOMM channel, distinguished by byte `[1]` + tail:

```
FORMAT A — persistent settings write (tail 2d):
  53 ff [seq] 01 a5 5a [ilen:2LE] 01000000 [dsz:2LE] [cksum:2] 01000000
  [data_size:4LE] 02000000 04000000 01000000(op=WRITE) 03000000 04000000
  [cmd_id:4LE] 04000000 [value_size:4LE] [value:value_size LE] 2d
    · cmd_id @ byte 44 · value @ byte 56 · total = 57 + value_size (61 for 4B, 65 for 8B)
    · ilen = 44+value_size · dsz = 40+value_size · data_size = 32+value_size

FORMAT B (immediate action, tail 31) — two sub-shapes:
  B-trigger (play/pause/skip, multipoint-enable):  … [cmd_id:4LE] 04000000 [value:4LE] 31
    · cmd_id @ 43 · value @ 51 · total 56 · NO value_size field
  B-typed  (volume, mode→Bluetooth):               … [cmd_id:4LE] 04000000 [value_size:4LE] [value:4LE] 31
    · cmd_id @ 43 · value @ 55 · total 60 · value_size TLV present

Device ACK (FORMAT A): 51 ff 01 02 2d
```

- `[seq]` (byte 2) is a rolling per-session counter; **any value is accepted**.
- The 2-byte field at `[14:16]` (A) / `[13:15]` (B) is a per-command checksum/timestamp the device
  **ignores** for acceptance (confirmed: identical commands accepted with differing values).
- All multi-byte fields are u32 LE.

Because the layout has several incompatible variants (A 4B/8B value, B-trigger, B-typed, plus the
MAC-splice below), the app does **not** reconstruct frames from `(format, cmd_id, value)`. The
manifest ships the exact captured frame per option/state under `set.frames` and `ShokzEngine` replays
it verbatim, patching only the seq byte, the host MAC (multipoint-disable), and the slider value.

## Control table

| Control | Fmt | cmd | value(s) | Inject | Verified | Notes |
|---|---|---|---|---|---|---|
| Equalizer | A | `0x0e` | standard `01`, vocal `02`, swimming `07` | multitoggle | ✅ | value_size 8 |
| Multipoint | B/A | on `0x11`=`00`, off `0x10`=`06`+MAC | — | switch | ✅ | disable splices host MAC (see below) |
| Playback mode (MP3/BT) | A/B | `0x25` on(MP3)=A`01`, off(BT)=B`00` | — | switch | ✅ | same cmd, format encodes direction |
| MP3 shuffle | A | `0x26` | order `00`, shuffle `01`, repeat `02` | list | ✅ | |
| Universal button | A | `0x24` | voice_asst `01`, switch_mode `03` | list | ✅ | see 0x24 note |
| Volume long-press | A | `0x24` | switch_mode `01`, voice_asst `04` | list | ✅ | see 0x24 note |
| MP3 play/pause/prev/next | B-trigger | `0x03`/`0x04`/`0x07`/`0x08` | `00` | none | ✅ | action triggers, catalog-only |
| MP3 volume | B-typed | `0x0a` | `00`..`10` (0–16, 16 steps) | in-app | ⚠️ | only min/max captured; steps 1–15 patched |

### `cmd_id 0x24` is context-dependent — resolved by keeping two functions
The universal button and the volume long-press **share `cmd_id 0x24`**, and the same value byte means
different actions depending on which setting is being written (`01` = voice-assistant for the button
but switch-mode for the long-press). There is no captured sub-field distinguishing them, so the
headphone disambiguates positionally by which setting the app was editing. We model this the same way:
**two separate functions**, each with its own captured frames. No runtime disambiguation is needed —
each control replays the exact frame for the chosen option, so the ambiguity never arises host-side.

### Multipoint disable splices the host MAC
The disable frame (`cmd_id 0x10`, value `0x06`) appends the 6-byte host Bluetooth MAC **before** the
`2d` tail (enable, `cmd_id 0x11`, does not). Capture: phone MAC `c8:17:ec:7b:33:5c` → bytes
`c8 17 ec 7b 33 5c` at offset 56. The manifest stores the frame with a zeroed placeholder and
`host_mac_offset_off: 56`; `ShokzEngine` writes `BluetoothAdapter.getAddress()` there at send time
(then zeroes the ignored checksum). If the local MAC is masked/unavailable, disable is sent as-is and
logged.

## Reads & status
**No device→host response layout was confirmed** for any command in the capture — the app never saw a
parseable read reply. State is therefore **write-only / optimistic**: `readMode`/`readToggle`/
`readLevel`/`readInfo` return `null` and the UI reflects the last write. If a response format is later
reverse-engineered, add the parser to `ShokzEngine` and populate each function's `read` block.

## Volume steps
Only `0` (min) and `16` (max) were captured (FORMAT B-typed, value u32-LE at byte 55). The slider
patches intermediate values 1–15 into the min-frame template; unit-tested to reproduce the captured
max frame byte-for-byte (modulo the ignored checksum). Marked ⚠️ until a mid-step is confirmed live.

## Other Shokz models
The `shokz_v1` framing is almost certainly shared across current Shokz models (OpenRun Pro 2,
OpenFit Air, …); typically only the `cmd_id`/value assignments differ. To add one: HCI-snoop the
official app, copy the captured frames into a new `manifests/shokz/<id>.json` under `set.frames`
(reusing this device's structure), and the existing engine drives it with **no app changes**. No such
captures exist yet.

## Verification method (live)
Root + HCI snoop on, drive the official Shokz app, pull `btsnoop_hci.log`, decode with tshark
(`btrfcomm` / `data.data`). Or probe from the app's debug path:
```
adb shell am broadcast -n io.github.thelok1s.orchestra/.DebugSendReceiver \
  -a io.github.thelok1s.orchestra.DEBUG_SEND --es mac <MAC> --es cmd <full-frame-hex>
```
→ `ShokzEngine.sendRaw` logs `TX …` + any reply to logcat tag `Orchestra`.
