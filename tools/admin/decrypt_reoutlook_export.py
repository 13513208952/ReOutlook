#!/usr/bin/env python3
"""Decrypt an owner-approved ReOutlook administrator export."""
import argparse
import base64
import json
import os
import pathlib
import struct

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

MAGIC = b"REOUTLOOK-ADMIN-1\n"
HERE = pathlib.Path(__file__).resolve().parent
PRIVATE_KEY = pathlib.Path(os.environ.get(
    "REOUTLOOK_EXPORT_PRIVATE_KEY",
    str(HERE / "reoutlook-export-rsa-private.pem"))).expanduser()


def decrypt(source: pathlib.Path, destination: pathlib.Path) -> None:
    with source.open("rb") as stream:
        if stream.read(len(MAGIC)) != MAGIC:
            raise ValueError("Not a ReOutlook administrator export")
        raw_size = stream.read(4)
        if len(raw_size) != 4:
            raise ValueError("Truncated export header")
        header_size = struct.unpack(">I", raw_size)[0]
        if header_size <= 0 or header_size > 65536:
            raise ValueError("Invalid export header size")
        header = stream.read(header_size)
        if len(header) != header_size:
            raise ValueError("Truncated export header")
        metadata = json.loads(header)
        ciphertext_offset = stream.tell()
        stream.seek(0, 2)
        ciphertext_size = stream.tell() - ciphertext_offset
        if ciphertext_size < 16:
            raise ValueError("Truncated encrypted payload")
        stream.seek(-16, 2)
        tag = stream.read(16)
        stream.seek(ciphertext_offset)

        if not PRIVATE_KEY.is_file():
            raise FileNotFoundError(f"Missing administrator export key: {PRIVATE_KEY}")
        private_key = serialization.load_pem_private_key(PRIVATE_KEY.read_bytes(), password=None)
        content_key = private_key.decrypt(
            base64.b64decode(metadata["wrappedKey"]),
            padding.OAEP(mgf=padding.MGF1(algorithm=hashes.SHA256()),
                         algorithm=hashes.SHA256(), label=None),
        )
        decryptor = Cipher(
            algorithms.AES(content_key),
            modes.GCM(base64.b64decode(metadata["nonce"]), tag),
        ).decryptor()
        decryptor.authenticate_additional_data(header)

        remaining = ciphertext_size - 16
        with destination.open("wb") as output:
            while remaining:
                chunk = stream.read(min(1024 * 1024, remaining))
                if not chunk:
                    raise ValueError("Truncated encrypted payload")
                remaining -= len(chunk)
                output.write(decryptor.update(chunk))
            output.write(decryptor.finalize())
    print(json.dumps(metadata, ensure_ascii=False, indent=2))
    print(f"Decrypted ZIP: {destination}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=pathlib.Path)
    parser.add_argument("-o", "--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    decrypt(args.source, args.output)


if __name__ == "__main__":
    main()
