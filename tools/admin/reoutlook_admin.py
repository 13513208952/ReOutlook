#!/usr/bin/env python3
"""Offline administrator launcher for ReOutlook's owner-approved maintenance export."""
import argparse
import base64
import os
import pathlib
import re
import shutil
import subprocess
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

PACKAGE = "io.github.reoutlook"
ACTIVITY = f"{PACKAGE}/.MaintenanceActivity"
URI = "content://io.github.reoutlook.maintenance"
HERE = pathlib.Path(__file__).resolve().parent
PRIVATE_KEY = pathlib.Path(os.environ.get(
    "REOUTLOOK_MAINTENANCE_PRIVATE_KEY",
    str(HERE / "reoutlook-maintenance-rsa-private.pem"))).expanduser()
ANDROID_HOME = pathlib.Path(os.environ.get(
    "ANDROID_HOME", str(pathlib.Path.home() / "Android" / "Sdk"))).expanduser()
ADB = shutil.which("adb") or str(ANDROID_HOME / "platform-tools" / "adb")


def adb(*args: str) -> str:
    result = subprocess.run([ADB, *args], check=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)
    return result.stdout.strip()


def clear_challenge() -> None:
    subprocess.run(
        [ADB, "shell", "content", "call", "--uri", URI,
         "--method", "clearChallenge"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def request_export() -> None:
    if not PRIVATE_KEY.is_file():
        raise SystemExit(f"Missing offline signing key: {PRIVATE_KEY}")
    response = adb("shell", "content", "call", "--uri", URI,
                   "--method", "createChallenge", "--arg", "EXPORT_ACCOUNT")
    match = re.search(r"challenge=([A-Za-z0-9_-]+)", response)
    if not match:
        clear_challenge()
        raise SystemExit(f"Could not read challenge: {response}")
    challenge = match.group(1)
    private_key = serialization.load_pem_private_key(
        PRIVATE_KEY.read_bytes(), password=None)
    signature = private_key.sign(
        challenge.encode("ascii"), padding.PKCS1v15(), hashes.SHA256())
    signature_b64 = base64.b64encode(signature).decode("ascii")
    output = adb("shell", "am", "start", "-n", ACTIVITY,
                 "--es", "challenge", challenge, "--es", "signature", signature_b64)
    print(output)
    print("Review the request on the phone and confirm it with the system lock credential.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("export", "clear"))
    args = parser.parse_args()
    try:
        if args.command == "export":
            request_export()
        else:
            clear_challenge()
    except subprocess.CalledProcessError as error:
        clear_challenge()
        sys.stderr.write(error.stdout or str(error))
        raise SystemExit(error.returncode)


if __name__ == "__main__":
    main()
