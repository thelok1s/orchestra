# Soundcore Liberty 4 Pro — control reference

| | |
|---|---|
| Model code | 3954 |
| `re_model` | A3957 (Liberty 5 — closest OpenSCQ30 cousin; no exact entry) |
| Control UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3954` |
| MAC (RE unit) | `f4:9d:8a:5c:c3:14` · ACL handle seen `0x000d` |
| Firmware | `04.29` · model+MAC `395414C35C8A9DF4` |
| State packet | cmd `0101` → 175 bytes (0x00af); mode byte at idx 134 (after marker `44 44 33`) |

Manifest: `framework/devices/soundcore-liberty-4-pro.json` (18 functions, 6 verified). TWS earbuds —
several opcodes differ from the over-ear; **verify per-control, don't assume from A3062.**

## Control table

| Control | cmd | payload (set) | values | Verified | Inject | Notes |
|---|---|---|---|---|---|---|
| In-ear detection | `0181` | `{state}` | on=`01` off=`00` | ✅ | switch | "Обнаружение наушника в ухе" |
| Sound-leak compensation | `109a` | `{state}` | on=`01` off=`00` | ✅ | switch | **NOT `1086`** (OpenSCQ30 guess wrong for this unit) |
| Low-battery prompt | `1082` | `{state}` | on=`01` off=`00` | ✅ | switch | under the touch-tone submenu |
| Multipoint (dual conn) | `0b84` | `{state}` | on=`01` off=`00` | ✅ | switch | list `0b01`/`0b02` |
| LDAC / codec | `01ff` | `{state}` | LDAC=`01` default=`00` | ✅ | none | **NOT `017f`** (that's the request id). **Switching reboots the earbuds ~10s and drops audio+control; app then asks to reselect device in BT settings.** |
| Sound mode | `0681` | adaptive, see below | — | ⚠️ | multitoggle/in-app | different (adaptive) packet vs over-ear; discrete mapping unconfirmed |
| Wind / "Air protection" | `0681` | B3 = `01`/`00` | on/off | ✅ | in-app | composite (B3 of the adaptive packet) |
| Touch tone | `0183` | `{state}` | on=`01` off=`00` | ⚠️ | switch | submenu |
| Wearing tone | `018c` | `{state}` | on=`01` off=`00` | ⚠️ | switch | |
| ANC-cycle members | `0682` | per-flag | on/off | ⚠️ | switch | |
| Gaming mode | `0187` | `{state}` | on=`01` off=`00` | ⚠️ | switch | conflicts with LDAC |
| Limit high volume | `2082` | `{state}<dB>` | on/off | ⚠️ | switch | refresh rate radios present |
| Auto power-off | `0186` | `{value}` | durations | ⚠️ | in-app (list) | default 30 min |
| Equalizer | `0281` | preset id / 8 bands | — | ⚠️ | in-app | |
| Gesture / button | `0481` | `0000{action}` | — | ⚠️ | in-app (list) | |

Verified = ✅ live 2026-06-14 (app + btsnoop, handle 0x000d) · ⚠️ guessed from A3957/A3947.

## Composite sound-mode packet (`0681`) — adaptive
The Liberty uses a **5-byte** payload (vs the over-ear's 6), and its UI is an ANC↔Transparency
*slider* (1–10) plus Airplane mode, not three buttons:
```
B0  mode      00 = ambient/adaptive-ANC · 01 = manual-ANC · 03 = airplane    (02 transparency? — UNCONFIRMED)
B1  level     01..0a = 1..10 · 0b = adaptive/auto
B2  (00)
B3  wind      01 = "Air protection" on
B4  (ff)
```
Captured: airplane `03 0b 00 00 ff` · ambient-ANC `00 0b 00 00 ff` · manual level-1 `01 01 00 00 ff` ·
manual level-1 + wind `01 01 00 01 ff`. **The discrete anc/off/transparency mapping in the JSON is
still the over-ear-style guess (anc=00/off=01/transp=02) and needs a clean re-capture** — its real
model is the adaptive slider above.

## Reads & status
`0101` full state (mode byte idx 134) · `0103` battery · `0105` serial+fw · `2003` real-time volume meter.

## Notes
Generic SPP `1101` connects but does NOT carry the control protocol — use the vendor UUID. The codec
(`01ff`) reboot can also drop the phone's USB/adb link momentarily.
