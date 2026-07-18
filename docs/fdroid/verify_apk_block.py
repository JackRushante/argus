#!/usr/bin/env python3
"""Verifica che l'APK di release sia F-Droid-clean: nel signing block NON deve
comparire il blocco "Dependency metadata" (id 0x504b4453). Uso:

    python docs/fdroid/verify_apk_block.py app-release.apk

Esce con codice 1 (e stampa KO) se il blocco è presente."""
import struct
import sys


def pair_ids(path: str) -> list[str]:
    data = open(path, "rb").read()
    magic = b"APK Sig Block 42"
    mp = data.find(magic)
    if mp < 0:
        raise SystemExit("nessun APK Signing Block v2/v3 trovato")
    block_size = struct.unpack("<Q", data[mp - 8 : mp])[0]
    block_start = mp + 16 - 8 - block_size
    off = block_start + 8
    end = mp - 8
    ids = []
    while off < end:
        pair_size = struct.unpack("<Q", data[off : off + 8])[0]
        pair_id = struct.unpack("<I", data[off + 8 : off + 12])[0]
        ids.append(hex(pair_id))
        off += 8 + pair_size
    return ids


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    ids = pair_ids(sys.argv[1])
    print("pair IDs:", ids)
    if "0x504b4453" in ids:
        print("KO: dependency-metadata block presente")
        return 1
    print("OK: nessun dependency-metadata block")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
