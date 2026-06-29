# Soundcore Space One Pro — control reference

| | |
|---|---|
| Model code | 3062 |
| `re_model` | A3062 (exact match in OpenSCQ30) |
| Control UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3062` (also exposes `66666666-…`; generic SPP `1101` connects but carries no control protocol) |
| MAC (RE unit) | `f4:9d:8a:63:e5:f7` |
| Firmware | `04.36` · serial `3062F7E5638A9DF4` |
| State packet | cmd `0101` → 105 bytes; sound-mode block at byte 74 |

Manifest: `framework/devices/soundcore-space-one-pro.json` (21 functions, 10 verified).

## Control table

| Control | cmd | payload (set) | values | Verified | Inject | Notes |
|---|---|---|---|---|---|---|
| Sound mode | `0681` | `{mode}5000000005` | anc=`00` · transparency=`01` · off/normal=`02` | ✅ | multitoggle (id 1001, also volume panel) | B0 of the composite packet (below) |
| ANC level | `0681` | `00{lvl}000000 05` (B1) | `10`/`20`/`30`/`40`/`50` = 1–5 | ✅ | in-app (slider) | composite; verified via DEBUG_SEND `003000000005` → readback B1=30 |
| Adaptive vs manual ANC | `0681` | B3 = `01`/`00` | adaptive=`01` · manual=`00` | ✅ | in-app | composite (B3) |
| Transparency level | `0681` | B5 = `01`…`05` | 1–5 (B2 flips to `01` in this mode) | ✅ | in-app (slider) | composite (B5) |
| Wind / "Air protection" | `0681` | B4 = `01`/`00` | on/off | ✅ | in-app | composite (B4) |
| Dolby Audio | `0286` | `{state}` | on=`01` off=`00` | ✅ | switch | standalone |
| Surrounding sounds | `01ae` | `{state}` | on=`01` off=`00` | ✅ | switch | app label "Surrounding sounds"; OpenSCQ30 calls it ambient_sound_mode_voice_prompt |
| Side tone | `0184` | `{state}` | on=`01` off=`00` | ✅ | switch | standalone |
| Low-battery prompt | `1082` | `{state}` | on=`01` off=`00` | ✅ | switch | standalone |
| Multipoint (dual conn) | `0b84` | `{state}` | on=`01` off=`00` | ✅ | switch | list read `0b01`; per-device connect/disconnect `0b81`/`0b82` |
| ANC-mode cycle members | `0682` | per-flag | on=`01` off=`00` | ⚠️ | switch | 3 toggles; likely one combined packet — verify |
| LDAC | `017f` (req) | `{state}` | on=`01` off=`00` | ⚠️ | none | set opcode unconfirmed (cf. Liberty uses `01ff`); may reboot |
| Auto power-off | `0186` | `{value}` | `00`=never, `01`=30m, … | ⚠️ | in-app (list) | durations TBD |
| Equalizer | `0387` | preset id LE u16 + 8 band gains + HearID block | — | ⚠️ | in-app | EQ+HearID; see OpenSCQ30 set_equalizer_and_custom_hear_id |
| Gesture / button | `0481` | `0000{action}` | `0f`=none | ⚠️ | in-app (list) | `0481` double / `0482` triple / `0483` hold / `0484` … |
| Limit high volume | `2082` | `{state}<dB>` | on=`01` off=`00`; `5a`=90 dB | ⚠️ | switch | refresh-rate `2081` |

Verified = ✅ live (app + btsnoop / DEBUG_SEND) · ⚠️ guessed from OpenSCQ30 A3062.

## Composite sound-mode packet (`0681`)
Six payload bytes — **all ANC-family settings live here**, so changing one means re-sending the whole
packet (read-modify-write):
```
B0  mode          00 = ANC · 01 = Transparency · 02 = Normal/Off
B1  ANC level     0x10..0x50  (levels 1..5; applies in manual ANC)
B2  transp flag   01 while adjusting transparency, else 00
B3  adaptive      01 = adaptive ANC · 00 = manual
B4  wind          01 = wind/"Air protection" on
B5  transp level  01..05
```
Our tile sends `<mode> 50 00 00 00 05` (level 5, transp 5, manual, wind off) — fine for plain mode
switching. **DEBUG_SEND `003000000005` set ANC level 3 and the device echoed B1=30 — composite
sub-fields are fully settable from Android; the only reason they aren't tiles is the Pixel About
page has no slider/sub-field control type.**

## Reads & status
| read cmd | meaning | location |
|---|---|---|
| `0101` | full state | mode B0 at byte 78; sound-mode block at 74 = `04 04 0f 03 <B0> <B1> 00 00 00 05` |
| `0103` | battery | max level 10, offset 1 (e.g. `…01030b000820`) |
| `0105` | serial + firmware | |
| `0102` | TWS status | |

## Unidentified opcodes
Seen host→device while navigating the app, not yet mapped: `2086`, `108d`, `1097` (`01`), `0501`,
`2003` (= real-time volume meter poll, confirmed on Liberty). `0b02` = dual-connection device list (read).
