#!/usr/bin/env python3
"""Decode OPOv1 (BBK/OnePlus/OPPO/realme) frames out of tshark L2CAP payload hex.

Reads lines: <time> <chandle> <payload-hex>
Strips the RFCOMM header, then parses the OPO frame:
    AA | LEN u16LE (= total-2) | FLAG | CAT | SUB | SEQ | PLEN u16LE | PAYLOAD[PLEN]
"""
import sys


def rfcomm_strip(b):
    """Return (dlci, payload) for a RFCOMM UIH frame, or (None, None)."""
    if len(b) < 4:
        return None, None
    addr, ctrl = b[0], b[1]
    if ctrl & 0xEF != 0xEF:          # UIH only
        return None, None
    i = 2
    ln = b[i]
    if ln & 1:                        # EA set -> 1-byte length
        n = ln >> 1
        i += 1
    else:
        n = (b[i] >> 1) | (b[i + 1] << 7)
        i += 2
    return addr >> 2, b[i:i + n]


def opo(p):
    if len(p) < 9 or p[0] != 0xAA:
        return None
    ln = p[1] | (p[2] << 8)
    plen = p[7] | (p[8] << 8)
    return {
        "len": ln, "len_ok": ln == len(p) - 2,
        "flag": p[3], "cat": p[4], "sub": p[5], "seq": p[6],
        "plen": plen, "plen_ok": plen == len(p) - 9,
        "payload": p[9:9 + plen],
    }


for line in sys.stdin:
    f = line.rstrip("\n").split("\t")
    if len(f) < 3 or not f[2]:
        continue
    t, handle, hexs = f[0], f[1], f[2]
    try:
        b = bytes.fromhex(hexs)
    except ValueError:
        continue
    dlci, pay = rfcomm_strip(b)
    if not pay:
        continue
    o = opo(pay)
    if not o:
        continue
    flags = "" if (o["len_ok"] and o["plen_ok"]) else "  <-- LEN/PLEN MISMATCH"
    print(f'{t}  h={handle} dlci={dlci:<3} cmd={o["cat"]:02x}{o["sub"]:02x} '
          f'seq={o["seq"]:02x} plen={o["plen"]:<3} '
          f'payload={o["payload"].hex() or "-"}{flags}')
