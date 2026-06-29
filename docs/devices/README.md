# Device control documentation — standard format

Each `docs/devices/<id>.md` is the human-readable reference for one device: every control, its exact
RFCOMM sequence, and verification status. The machine-readable manifest lives in
`framework/devices/<id>.json` (consumed by the app); these MDs are the **source of truth for the
protocol** and the place to record controls we understand but can't (yet) inject natively.

## Why both a JSON and an MD?
- **JSON** = what the app injects/renders right now (typed, validated, per-control inject flags).
- **MD** = the full reverse-engineered picture, including sequences for controls that have no native
  Android surface (sliders, composite sub-fields, reboot-triggering codecs). Nothing is lost: a
  control that can't be a tile still has its bytes documented here.

## Framing (Soundcore `soundcore_v1`)
All commands share one framing:
```
host→device:  08 ee 00 00 00 <cmd:2> <len:2 LE total> <payload…> <crc>
device→host:  09 ff 00 00 01 <cmd:2> <len:2 LE> <payload…> <crc>
len = total packet byte count (LE) · crc = sum(all preceding bytes) & 0xFF
```
`<cmd>` equals OpenSCQ30's `Command([hi,lo])`. Same bytes whether carried over RFCOMM (vendor SPP
UUID) or inside BLE GATT writes/notifications.

## Required sections per device doc
1. **Identity** — name, model code, `re_model`, control UUID, captured firmware/MAC.
2. **Control table** — one row per control:
   | Control | cmd | payload (set) | values | read | Verified | Inject | Notes |
   - `payload` uses `{state}`/`{mode}`/`{value}` placeholders; give the literal hex for each value.
   - `Verified`: ✅ live / ⚠️ guessed (from OpenSCQ30 cousin) / 🍎 capture-only (iOS/Mac).
   - `Inject`: how it appears in Android — `multitoggle` / `switch` / `in-app` (slider/list/composite) / `none` (read-only/reboot).
3. **Composite packets** — when several controls share one command (e.g. `0681` sound-mode), document
   the full payload byte map.
4. **Reads & status** — read commands and where values sit in the response.
5. **Unidentified** — opcodes observed but not mapped.

## Verification method (live)
Root + HCI snoop on, drive the official Soundcore app, pull `/data/misc/bluetooth/logs/btsnoop_hci.log`,
decode with tshark (`data.data` for RFCOMM). Or send a probe from the app's debug path:
```
adb shell am broadcast -n io.github.thelok1s.orchestra/.DebugSendReceiver \
  -a io.github.thelok1s.orchestra.DEBUG_SEND --es mac <MAC> --es cmd <hhll> [--es payload <hex>]
```
→ logs `TX raw …` + `RX raw …` to logcat tag `Orchestra`.
