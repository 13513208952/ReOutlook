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
import uuid

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa

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
    "capabilities": "GET_CAPABILITIES",
    "diagnostics": "GET_DIAGNOSTICS",
    "audit": "GET_AUDIT",
    "validate": "VALIDATE_STATE",
    "repair": "REPAIR_STATE",
    "set-home": "SET_HOME",
    "set-pref": "SET_PREFERENCE",
    "activate-workspace": "ACTIVATE_WORKSPACE",
    "activate-tab": "ACTIVATE_TAB",
    "reload": "RELOAD",
    "stop": "STOP",
    "back": "GO_BACK",
    "forward": "GO_FORWARD",
    "home": "GO_HOME",
    "assert-location": "ASSERT_LOCATION",
    "lock": "LOCK_SECONDARY",
    "unlock": "UNLOCK_TEMPORARY",
    "promote": "PROMOTE_PRIMARY",
    "demote": "DEMOTE_SECONDARY",
    "shelve": "SHELVE_WORKSPACE",
    "restore": "RESTORE_WORKSPACE",
    "close-workspace": "CLOSE_WORKSPACE",
    "close-tab": "CLOSE_TAB",
    "delete-shelved": "DELETE_SHELVED",
    "show-downloads": "SHOW_DOWNLOADS",
    "download-policy": "GET_DOWNLOAD_POLICY",
    "set-downloads-enabled": "SET_DOWNLOAD_POLICY",
    "downloads": "GET_DOWNLOADS",
    "approve-download": "APPROVE_DOWNLOAD",
    "reject-download": "REJECT_DOWNLOAD",
    "cancel-download": "CANCEL_DOWNLOAD",
    "retry-download": "RETRY_DOWNLOAD",
    "delete-download": "DELETE_DOWNLOAD",
    "clear-downloads": "CLEAR_DOWNLOADS",
    "repair-downloads": "REPAIR_DOWNLOADS",
    "site-permissions": "GET_SITE_PERMISSIONS",
    "disable-site-permission": "DISABLE_SITE_PERMISSION",
    "clear-site-permissions": "CLEAR_SITE_PERMISSION_GRANTS",
}
WORKSPACE_ARGUMENT = {
    "activate-workspace", "lock", "unlock", "promote", "demote", "shelve",
    "restore", "close-workspace", "delete-shelved",
}
TAB_ARGUMENT = {"activate-tab", "close-tab"}
DOWNLOAD_ARGUMENT = {
    "approve-download", "reject-download", "cancel-download", "retry-download",
    "delete-download",
}
DESTRUCTIVE = {
    "close-workspace", "close-tab", "delete-shelved", "delete-download",
    "clear-downloads", "clear-site-permissions",
}
SITE_PERMISSIONS = {
    "camera", "microphone", "precise-location", "approximate-location",
    "clipboard", "background-runtime",
}
BOOLEAN_PREFERENCES = {
    "javascript", "thirdPartyCookies", "desktopMode", "forcePrimaryPromotion",
    "videoOrientationOverride",
}
REBROWSER_PRIVATE_KEY = pathlib.Path(os.environ.get(
    "REBROWSER_ROOT_PRIVATE_KEY",
    str(HERE / "rebrowser-root-v1-ecdsa-private.pem"))).expanduser()


def adb(*args: str) -> str:
    result = subprocess.run([ADB, *args], check=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)
    return result.stdout.strip()


def provider_call(method: str, argument: str | None = None) -> str:
    command = ["shell", "content", "call", "--uri", URI, "--method", method]
    if argument is not None:
        command += ["--arg", argument]
    return adb(*command)


def bundle_field(response: str, name: str) -> str | None:
    match = re.search(rf"(?:^|[{{, ]){re.escape(name)}=([^,}}\]]*)", response)
    return match.group(1).strip() if match else None


def decode_payload(response: str) -> dict | None:
    encoded = bundle_field(response, "payload")
    if encoded is None or not encoded:
        return None
    raw = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
    if not raw:
        return None
    return json.loads(raw.decode("utf-8"))


def clear_challenge() -> None:
    subprocess.run([ADB, "shell", "content", "call", "--uri", URI,
                    "--method", "clearChallenge"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def read_result(request_id: str | None = None) -> dict | None:
    return decode_payload(provider_call(
        "getResult" if request_id else "getLastResult", request_id))


def parse_bool(value: str) -> bool:
    lowered = value.lower()
    if lowered in ("true", "1", "yes", "on"):
        return True
    if lowered in ("false", "0", "no", "off"):
        return False
    raise SystemExit("Boolean preference values must be true or false")


def build_request(args: argparse.Namespace) -> dict:
    request = {
        "protocolVersion": 2,
        "requestId": str(uuid.uuid4()),
        "operation": OPERATIONS[args.command],
    }
    values = list(args.arguments)
    if args.command in ("open", "set-home", "assert-location"):
        if not values:
            raise SystemExit(f"{args.command} requires an HTTP(S) URL")
        request["url"] = values.pop(0)
    if args.command in WORKSPACE_ARGUMENT:
        if not values:
            raise SystemExit(f"{args.command} requires a workspace ID")
        request["workspaceId"] = values.pop(0)
    if args.command in TAB_ARGUMENT:
        if not values:
            raise SystemExit(f"{args.command} requires a tab ID")
        request["tabId"] = values.pop(0)
    if args.command in DOWNLOAD_ARGUMENT:
        if not values:
            raise SystemExit(f"{args.command} requires a download ID")
        request["downloadId"] = values.pop(0)
    if args.command == "set-downloads-enabled":
        if not values:
            raise SystemExit("set-downloads-enabled requires true or false")
        request["downloadsEnabled"] = parse_bool(values.pop(0))
    if args.command == "disable-site-permission":
        if not values or values[0] not in SITE_PERMISSIONS:
            raise SystemExit("disable-site-permission requires one of: "
                             + ", ".join(sorted(SITE_PERMISSIONS)))
        request["permission"] = values.pop(0)
    if args.command == "clear-site-permissions" and values:
        if values[0] not in SITE_PERMISSIONS - {"background-runtime"}:
            raise SystemExit("clear-site-permissions accepts an optional website permission")
        request["permission"] = values.pop(0)
    if args.command == "set-pref":
        if len(values) < 2:
            raise SystemExit("set-pref requires NAME VALUE")
        name, value = values.pop(0), values.pop(0)
        request["name"] = name
        request["value"] = parse_bool(value) if name in BOOLEAN_PREFERENCES else value
    if values:
        raise SystemExit(f"Unexpected arguments: {' '.join(values)}")
    if args.workspace:
        request["workspaceId"] = args.workspace
    if args.tab:
        request["tabId"] = args.tab
    if args.wait:
        request["waitForLoad"] = True
        request["timeoutSeconds"] = args.timeout
    if args.command in DESTRUCTIVE:
        if not args.confirm_delete:
            raise SystemExit("This command requires --confirm-delete")
        request["confirmDelete"] = True
    return request


def selected_key(args: argparse.Namespace) -> tuple[str, pathlib.Path]:
    key_id = args.key_id
    if key_id is None:
        key_id = ("rebrowser-root-v1" if os.environ.get("REBROWSER_ROOT_PRIVATE_KEY")
                  else "reoutlook-root-v1")
    if args.private_key:
        path = pathlib.Path(args.private_key).expanduser()
    else:
        path = REBROWSER_PRIVATE_KEY if key_id == "rebrowser-root-v1" else PRIVATE_KEY
    return key_id, path


def sign_challenge(challenge: str, path: pathlib.Path) -> bytes:
    if not path.is_file():
        raise SystemExit(f"Missing offline signing key: {path}")
    private_key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    if isinstance(private_key, rsa.RSAPrivateKey):
        return private_key.sign(
            challenge.encode("ascii"), padding.PKCS1v15(), hashes.SHA256())
    if isinstance(private_key, ec.EllipticCurvePrivateKey):
        return private_key.sign(challenge.encode("ascii"), ec.ECDSA(hashes.SHA256()))
    raise SystemExit("Unsupported administrator private key type")


def wait_for_result(request_id: str, timeout: int) -> dict:
    deadline = time.monotonic() + timeout
    latest = None
    while time.monotonic() < deadline:
        latest = read_result(request_id)
        if latest and latest.get("status") in ("completed", "failed", "cancelled"):
            return latest
        time.sleep(0.15)
    raise SystemExit(f"Timed out waiting for request {request_id}; last result: {latest}")


def run_request(args: argparse.Namespace) -> dict:
    request = build_request(args)
    encoded_request = base64.urlsafe_b64encode(json.dumps(
        request, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
    ).decode("ascii").rstrip("=")
    response = provider_call("createChallenge", encoded_request)
    challenge = bundle_field(response, "challenge")
    request_id = bundle_field(response, "requestId") or request["requestId"]
    if not challenge:
        clear_challenge()
        raise SystemExit(f"Could not read challenge: {response}")
    launch = ["shell", "am", "start", "-W", "-n", ACTIVITY,
              "--es", "challenge", challenge]
    if args.auth == "key":
        key_id, path = selected_key(args)
        signature = sign_challenge(challenge, path)
        launch += ["--es", "keyId", key_id,
                   "--es", "signature", base64.b64encode(signature).decode("ascii")]
    adb(*launch)
    if args.auth == "device":
        print("Approve the exact one-use request with the system lock credential on the phone.",
              file=sys.stderr)
    return wait_for_result(request_id, max(args.timeout + 10, 190 if args.auth == "device" else 20))


def output_result(result: dict | None, compact: bool) -> None:
    if result is None:
        print("null")
        return
    print(json.dumps(result, ensure_ascii=False,
                     separators=(",", ":") if compact else None,
                     indent=None if compact else 2))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=tuple(OPERATIONS) + ("clear", "result"))
    parser.add_argument("arguments", nargs="*")
    parser.add_argument("--workspace")
    parser.add_argument("--tab")
    parser.add_argument("--wait", action="store_true")
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--confirm-delete", action="store_true")
    parser.add_argument("--auth", choices=("key", "device"), default="key")
    parser.add_argument("--key-id", choices=("reoutlook-root-v1", "rebrowser-root-v1"))
    parser.add_argument("--private-key")
    parser.add_argument("--compact", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 60:
        raise SystemExit("--timeout must be between 1 and 60 seconds")
    try:
        if args.command == "clear":
            clear_challenge()
            return
        if args.command == "result":
            output_result(read_result(args.arguments[0] if args.arguments else None), args.compact)
            return
        result = run_request(args)
        output_result(result, args.compact)
        if result.get("status") != "completed":
            raise SystemExit(2)
    except subprocess.CalledProcessError as error:
        clear_challenge()
        sys.stderr.write(error.stdout or str(error))
        raise SystemExit(error.returncode)


if __name__ == "__main__":
    main()
