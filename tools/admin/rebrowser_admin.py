#!/usr/bin/env python3
"""ADB control client for ReBrowser's key-or-device-credential authorization bridge."""
import argparse
import base64
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

PACKAGE = "io.github.reoutlook"
ACTIVITY = f"{PACKAGE}/.ReBrowserAdminActivity"
URI = "content://io.github.reoutlook.rebrowser.admin"
HERE = pathlib.Path(__file__).resolve().parent
PRIVATE_KEY = pathlib.Path(os.environ.get(
    "REOUTLOOK_MAINTENANCE_PRIVATE_KEY",
    str(HERE / "reoutlook-maintenance-rsa-private.pem"))).expanduser()
ANDROID_HOME = pathlib.Path(os.environ.get(
    "ANDROID_HOME", str(pathlib.Path.home() / "Android" / "Sdk"))).expanduser()
ADB = shutil.which("adb") or str(ANDROID_HOME / "platform-tools" / "adb")

OPERATIONS = {
    "open": "OPEN_URL",
    "new-workspace": "NEW_WORKSPACE",
    "new-tab": "NEW_CHILD_TAB",
    "workspaces": "SHOW_WORKSPACES",
    "tabs": "SHOW_CHILD_TABS",
    "settings": "OPEN_SETTINGS",
    "outlook": "SWITCH_TO_OUTLOOK",
    "state": "GET_STATE",
    "set-home": "SET_HOME",
}


def adb(*args: str) -> str:
    result = subprocess.run([ADB, *args], check=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)
    return result.stdout.strip()


def provider_call(method: str, argument: str | None = None) -> str:
    command = ["shell", "content", "call", "--uri", URI, "--method", method]
    if argument is not None:
        command += ["--arg", argument]
    return adb(*command)


def clear_challenge() -> None:
    subprocess.run([ADB, "shell", "content", "call", "--uri", URI,
                    "--method", "clearChallenge"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def last_result() -> str:
    return provider_call("getLastResult")


def run_request(command: str, url: str | None, authentication: str) -> None:
    request = {"operation": OPERATIONS[command]}
    if command in ("open", "set-home"):
        if not url:
            raise SystemExit(f"{command} requires an HTTP(S) URL")
        request["url"] = url
    request_json = json.dumps(request, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
    encoded_request = base64.urlsafe_b64encode(request_json).decode("ascii").rstrip("=")
    response = provider_call("createChallenge", encoded_request)
    match = re.search(r"challenge=([A-Za-z0-9_-]+)", response)
    if not match:
        clear_challenge()
        raise SystemExit(f"Could not read challenge: {response}")
    challenge = match.group(1)
    launch = ["shell", "am", "start", "-W", "-n", ACTIVITY,
              "--es", "challenge", challenge]
    if authentication == "key":
        if not PRIVATE_KEY.is_file():
            clear_challenge()
            raise SystemExit(f"Missing offline signing key: {PRIVATE_KEY}")
        private_key = serialization.load_pem_private_key(
            PRIVATE_KEY.read_bytes(), password=None)
        signature = private_key.sign(
            challenge.encode("ascii"), padding.PKCS1v15(), hashes.SHA256())
        launch += ["--es", "signature", base64.b64encode(signature).decode("ascii")]
    print(adb(*launch))
    if authentication == "device":
        print("Approve the one-use request with the system lock credential on the phone.")
    else:
        result = ""
        for _ in range(30):
            time.sleep(0.1)
            result = last_result()
            if "challenge-created" not in result and "authorized:" not in result:
                break
        print(result)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=tuple(OPERATIONS) + ("clear", "result"))
    parser.add_argument("url", nargs="?")
    parser.add_argument("--auth", choices=("key", "device"), default="key")
    args = parser.parse_args()
    try:
        if args.command == "clear":
            clear_challenge()
        elif args.command == "result":
            print(last_result())
        else:
            run_request(args.command, args.url, args.auth)
    except subprocess.CalledProcessError as error:
        clear_challenge()
        sys.stderr.write(error.stdout or str(error))
        raise SystemExit(error.returncode)


if __name__ == "__main__":
    main()
