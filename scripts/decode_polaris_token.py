#!/usr/bin/env python3
"""
Decode Polaris static unlock token from an Android btsnoop_hci.log capture.

Scans the capture for GATT Write Request / Write Command packets targeting
the Onewheel UART Serial Write characteristic (e659f3ff-ea98-11e3-ac10-0800200c9a66).
The first 20-byte write after a notification-subscribe on the UART Read char
is the FM app's static unlock token.

Usage:
    python3 decode_polaris_token.py <path-to-btsnoop.log> [--board-name ow452500]
"""

from __future__ import annotations
import argparse
import struct
import sys
from pathlib import Path

BTSNOOP_MAGIC = b"btsnoop\0"

# Onewheel UUIDs — stored in Android HCI packets as little-endian byte sequences
OW_UART_WRITE_UUID_BYTES = bytes.fromhex("66 9a 0c 20 00 08 10 ac e3 11 98 ea ff f3 59 e6".replace(" ", ""))
OW_UART_READ_UUID_BYTES  = bytes.fromhex("66 9a 0c 20 00 08 10 ac e3 11 98 ea fe f3 59 e6".replace(" ", ""))

# GATT opcodes
OP_WRITE_REQ  = 0x12
OP_WRITE_CMD  = 0x52
OP_WRITE_RSP  = 0x13


def iter_btsnoop_records(path: Path):
    """Yield (timestamp_us, direction, payload) tuples from a btsnoop v1 log."""
    with path.open("rb") as f:
        header = f.read(16)
        if not header.startswith(BTSNOOP_MAGIC):
            raise ValueError(f"not a btsnoop file (magic={header[:8]!r})")
        while True:
            rec_hdr = f.read(24)
            if len(rec_hdr) < 24:
                return
            orig_len, incl_len, flags, drops, ts_hi, ts_lo = struct.unpack(">IIIIII", rec_hdr)
            payload = f.read(incl_len)
            if len(payload) < incl_len:
                return
            ts_us = (ts_hi << 32) | ts_lo
            direction = "RX" if flags & 0x01 else "TX"
            yield ts_us, direction, payload


def scan_for_token(path: Path, verbose: bool = False) -> str | None:
    """Return the 20-byte unlock token as hex, or None if not found."""
    candidate_writes: list[tuple[int, bytes]] = []
    saw_read_subscribe = False
    saw_service = False

    for ts, direction, payload in iter_btsnoop_records(path):
        # Scan raw packet bytes for the UART UUIDs (crude but robust across
        # HCI framing / L2CAP / ATT layering variations)
        if OW_UART_READ_UUID_BYTES in payload:
            saw_read_subscribe = True
        if OW_UART_WRITE_UUID_BYTES in payload:
            saw_service = True

        # Look for ATT write-request / write-command packets
        # ATT PDU lives inside L2CAP; we scan heuristically for opcode + handle + 20-byte value
        # The value itself is all we care about — if it's exactly 20 bytes
        # and it appears near an OW_UART_WRITE_UUID reference, it's the token.
        if saw_read_subscribe and OW_UART_WRITE_UUID_BYTES in payload:
            # Find opcode offsets
            for op in (OP_WRITE_REQ, OP_WRITE_CMD):
                idx = payload.find(bytes([op]))
                while idx != -1:
                    # Handle is 2 bytes after opcode; value is after handle
                    if idx + 3 + 20 <= len(payload):
                        candidate = payload[idx + 3 : idx + 3 + 20]
                        if all(b == 0 for b in candidate):
                            pass  # skip all-zero padding
                        else:
                            candidate_writes.append((ts, candidate))
                    idx = payload.find(bytes([op]), idx + 1)

        # Separately: scan for any 20-byte sequence preceded by a characteristic
        # value write indicator. This is a fallback for non-standard framing.
        if OW_UART_WRITE_UUID_BYTES in payload and direction == "TX":
            uuid_idx = payload.find(OW_UART_WRITE_UUID_BYTES)
            tail = payload[uuid_idx + len(OW_UART_WRITE_UUID_BYTES):]
            # Skip ahead looking for a 20-byte non-zero block
            for start in range(min(8, len(tail) - 20)):
                block = tail[start:start + 20]
                if len(block) == 20 and any(b != 0 for b in block):
                    candidate_writes.append((ts, block))
                    break

    if verbose:
        print(f"[debug] service seen: {saw_service}")
        print(f"[debug] read subscribe seen: {saw_read_subscribe}")
        print(f"[debug] candidate writes: {len(candidate_writes)}")

    if not candidate_writes:
        return None

    # Deduplicate while preserving first-seen order
    seen: set[bytes] = set()
    unique: list[bytes] = []
    for _, block in candidate_writes:
        if block not in seen:
            seen.add(block)
            unique.append(block)

    if verbose:
        for i, blk in enumerate(unique):
            print(f"[debug] candidate {i}: {blk.hex()}")

    # The real token is the one that appears MOST often (FM app rewrites it every 15s
    # as keep-alive during connection). If there's only one, return it.
    counts = {blk: sum(1 for _, b in candidate_writes if b == blk) for blk in unique}
    best = max(counts, key=counts.get)
    return best.hex()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("log", type=Path, help="path to btsnoop_hci.log")
    ap.add_argument("--board-name", default="ow452500", help="used for secret filename")
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--out", type=Path, default=None, help="override output path")
    args = ap.parse_args()

    if not args.log.exists():
        print(f"[x] file not found: {args.log}", file=sys.stderr)
        return 1

    print(f"[*] parsing btsnoop: {args.log}")
    try:
        token = scan_for_token(args.log, verbose=args.verbose)
    except ValueError as e:
        print(f"[x] {e}", file=sys.stderr)
        return 2

    if not token:
        print("[x] no token candidates found")
        print("    check: (1) btsnoop capture was recording during an FM-app unlock")
        print("           (2) capture was not truncated before unlock completed")
        print("           (3) --verbose for diagnostic info")
        return 3

    print(f"[✓] TOKEN (hex, 40 chars): {token}")

    out_path = args.out or Path(f"/root/.claude/.secrets/ow_token_{args.board_name}")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(token + "\n")
    out_path.chmod(0o600)
    print(f"[✓] saved to: {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
