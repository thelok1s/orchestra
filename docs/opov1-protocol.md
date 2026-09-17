# OPOv1 — BBK (OnePlus / OPPO / realme) headphone control protocol

Status: **frame format verified**, command catalogue extracted, set-path not yet captured.

Verified 2026-09-16 on **realme Buds Air8 Pro** (`28:04:c6:db:b6:9a`, Manufacturer 688) against
Pixel 8 Pro / Android 17, by live btsnoop capture of `com.realme.link` plus static RE of that app.

Sources, in decreasing authority:

1. `bbk_re/headset_jadx/` — decompiled `split_realmeHeadset.apk` (realme Link). The serializer in
   `tl/protocol/packet/Packet.java` is the ground truth for the frame layout.
2. Live btsnoop capture of a full app-connect session (≈90 exchanges, RFCOMM DLCI 25).
3. `re_refs/cracked-oneplus-buds/` (OnePlus Nord Buds 3 Pro, BLE GATT, macOS) — useful, but its
   README's packet table is **wrong**; see [Corrections](#corrections-to-the-public-write-up).

## Transport

On **Android the control channel is RFCOMM (SPP)**, not BLE GATT. The buds advertise the OPO
service in their BR/EDR SDP record:

| UUID | role |
|---|---|
| `0000079a-d102-11e1-9b23-00025b00a5a5` | OPO control service — open an insecure RFCOMM socket to this |
| `99999999-9999-9999-9999-999999999999` | vendor, unused by the control path |
| `66666666-6666-6666-6666-666666666666` | vendor, unused by the control path |

Observed on DLCI 25. The same `0000079a` UUID is exposed as a BLE GATT service on platforms with no
BR/EDR SPP (that is the path the macOS script uses, with write characteristic `0100079a-…` and
notify `0200079a-…`), but Orchestra should use RFCOMM.

## Frame format

```
 0    1    2    3    4    5    6    7    8    9 ...
+----+----+----+----+----+----+----+----+----+----------+
| AA |  LEN    | FL |  cmdId  | TR |  dataLen | payload |
+----+----+----+----+----+----+----+----+----+----------+
      u16 LE    u8    u16 LE   u8    u16 LE     dataLen bytes
```

| field | meaning |
|---|---|
| `AA` | start of frame, constant |
| `LEN` | u16 LE, `= total_frame_len - 2`, i.e. every byte after `LEN` itself |
| `FL` | flag/version byte. `0x00` in every frame observed |
| `cmdId` | u16 LE command id — see [Command ids](#command-ids) |
| `TR` | transfer id. Rolling per request; the reply echoes it |
| `dataLen` | u16 LE, length of `payload` |

There is **no checksum**. Bytes 4.. are exactly the `Packet` structure the app serializes:

```java
// tl/protocol/packet/Packet.java
ProtocolUtil.c(this.a, bArr, 0, 2, true);   // commandId  u16 LE
ProtocolUtil.c(this.b, bArr, 2, 1, true);   // transferId u8
ProtocolUtil.c(this.c, bArr, 3, 2, true);   // dataLength u16 LE
System.arraycopy(this.d, 0, bArr, 5, this.d.length);
```

(`ProtocolUtil.c(…, littleEndian=true)` writes LSB first.)

## Command ids

`cmdId` is **one little-endian u16**, so on the wire the low byte comes first:

```
cmdId = (opcode << 8) | feature
```

| mask | meaning | source |
|---|---|---|
| `0x00FF` | feature id (low byte, first on the wire) | `Protocol.a = 255` |
| `0x7F00` | opcode (high byte) | `Protocol.b = 32512`, `Protocol.a(int)` |
| `0x8000` | response flag | `Protocol.c = 32768`, `Packet.j()` |

A reply is the request with the response bit set — `Packet.c()` does `commandId | 0x8000`. So a
`GET` of feature `0x09` is cmdId `0x0109`, on the wire `09 01`, and its reply is `0x8109` → `09 81`.

Opcodes seen: `0x01` GET, `0x02` NOTIFY (device-initiated push), `0x04` SET, `0x05` REGISTER,
with `0x81` / `0x84` / `0x85` the matching responses.

The full id table is in `tl/protocol/packet/Protocol.java` (obfuscated field names, meaningful
values), and `Protocol.b2` maps feature index → the command ids that feature uses.

## Live-captured exchanges

From one app-connect session. `→` host to buds, `←` buds to host.

| cmdId | dir | dataLen | payload | reading |
|---|---|---|---|---|
| `0x0100` | → | 0 | — | hello / session open |
| `0x8100` | ← | 9 | `00bf4f5ae665dc4014` | hello ack |
| `0x0500` | → | 4 | `3f0b0000` | register |
| `0x8500` | ← | 5 | `000efdaa6a` | register ack |
| `0x0101` | → | 2 | `0002` | |
| `0x8101` | ← | 3 | `00c008` | |
| `0x0105` | → | 0 | — | firmware versions |
| `0x8105` | ← | 93 | ASCII `1,1,111,1,2,1.1.0.90,…` | per-component fw version list |
| `0x0106` | → | 0 | — | battery |
| `0x8106` | ← | 8 | `00030164026403e4` | `00` ok, `03` count, then `(01,0x64)` L=100%, `(02,0x64)` R=100%, `(03,0xe4)` case |
| `0x0107` | ← | 4 | `00010103` | |
| `0x8108` | ← | 42 | `000a` + 10×4B | gesture bindings, 10 entries `(bud, 01, gesture, action)` |
| **`0x0109`** | → | 0 | — | **noise control — GET** |
| **`0x8109`** | ← | 8 | `00 03 0100 0200 0304` | `00` ok, `03` count, `(type,value)` pairs; type `03` = current mode, value `04` = Off |
| `0x010b` | → | 0 | — | |
| `0x810b` | ← | 2 | `0002` | |
| `0x810c` | ← | 4 | `00010101`, `0002010a` | |
| **`0x810d`** | ← | 34 | `0010` + 16×2B | **supported-feature table**, see below |
| `0x0204` | ← | 57 | …ASCII `meowbookpro`, `Pixel 8 Pro Max Ultra+` | multipoint paired-host list (NOTIFY) |
| `0x8115` | ← | 54 | `00030c…` + ASCII `2026/09/13` | |
| `0x0120`/`0x8120`, `0x0122`/`0x8122`, `0x0124`, `0x0128`/`0x8128`, `0x012c`/`0x812c` | | | further features, payloads captured |

### Feature table (`0x810d`)

Payload is `00` status, `10` item count, then 16 × 2-byte items — exactly
`FeatureSwitchInfo`, whose `LENGTH_FEATURE_SWITCH_ITEM = 2` and fields are
`mFeatureType = b[i]`, `mStatus = b[i+1]`:

```
0600 0401 0501 0b01 0800 1101 1800 0900 1a01 0c00 1d00 1b00 2600 0000 0000 0000
```

→ supported features `{04,05,06,08,09,0b,0c,11,18,1a,1b,1d,26}` with on/off status, then null
padding. Those ids are all members of `CommandProtocol.SWITCH_FEATURE`
(`{1,2,3,4,5,6,7,8,9,11,12,17,19,24,26,27,29,38,54}`), and they match the feature ids actually
seen in traffic. **This is the device's own capability manifest** — query it to drive which
Orchestra functions to expose, instead of hard-coding per-model lists.

## Corrections to the public write-up

The [cracked-oneplus-buds](https://github.com/AasheeshLikePanner/cracked-oneplus-buds) README and
the accompanying blog post describe the frame as `SOF | LEN | PAD | PAD | CAT | SUB | SEQ | FLAG |
D0 | D1 | MODE`. Checked against the app's own serializer and against live traffic:

- There is no `CAT`/`SUB` pair. Those two bytes are **one u16 LE command id**; "CAT" is just its
  low byte. This is why the write-up reports ANC as "category `0x04`, sub `0x04`" — `0x0404` read
  the other way round is feature `0x04`, opcode `0x04` (SET).
- Bytes 7–8 are not `FLAG` + `D0`; they are the u16 LE `dataLen`. Every captured frame satisfies
  `dataLen == len(payload)`, and `LEN == total - 2`.
- `MODE` is not "byte 11" — it is the last byte of a variable-length payload.
- Their `HELLO` (`AA 07 00 00 00 01 23 00 00 12`) is 10 bytes but declares `LEN=7`, i.e. 9. The
  trailing `0x12` is a stray byte; every other `dataLen=0` query in both their script and our
  capture is exactly 9 bytes.
- The `B5 50 A0 69` "device token" is Nord Buds 3 Pro-specific. The Air8 Pro register exchange is
  `0x0500` payload `3f0b0000` → `0x8500` payload `000efdaa6a`, so the token is not a constant and
  should not be baked into a manifest.

The mode values the write-up gives (`01` ANC, `02` Transparency, `04` Off) **are** consistent with
what we see: the Air8 Pro reported mode `04` while the app UI showed Off.

## Open items

- **Set-path frames not yet captured.** realme Link refuses any noise-control change while the buds
  are in the case — it shows a `Please wear your earbuds` toast and sends nothing. Capturing
  `0x0409` (SET feature 09) requires the earbuds to be worn.
- Feature id → human name mapping. The app's constants are obfuscated; names have to come from
  correlating each feature id with the UI control that triggers it.
- Whether the `0x0100` hello / `0x0500` register handshake is mandatory before a SET on RFCOMM, or
  only for the BLE path.

## Reproducing the capture

```bash
btsnoop-adb --pcap-out opo.pcap          # needs adb root; HCI snoop log enabled
adb shell input tap <x> <y>              # drive realme Link; android-mcp is broken on A17
tshark -r opo.pcap -Y btl2cap -T fields \
  -e frame.time -e bthci_acl.chandle -e btl2cap.payload | python3 opodec.py
```

`opodec.py` strips the RFCOMM UIH header and decodes the OPO frame; a copy lives alongside this
document's working files. Note tshark will not dissect RFCOMM on a capture started mid-session (it
never saw the channel setup), so decode the L2CAP payload directly.
