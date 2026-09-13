# ReOutlook and ReBrowser administrator tools

These tools control two modes of the same `io.github.reoutlook` application; ReBrowser is not a
separate installed app. Neither administrator surface has a normal in-app entry point. The
maintenance components remain registered but are callable only by Android shell permission. The
ReOutlook export tool submits an offline RSA-3072/SHA-256 administrator signature and then requires
the device owner to approve the exact export through Android's system lock credential UI.

Private keys are deliberately not stored in this repository or APK.

ReOutlook mail export keeps the stricter rule: administrator signature **and** device-owner lock
confirmation are both mandatory. ReBrowser control uses its deliberately lower development rule:
ADB shell plus either the authorized administrator signature **or** device-owner lock confirmation.
See [the ReBrowser control protocol](../../docs/REBROWSER_ADMIN_CONTROL.md).

## Required private keys

Set the paths explicitly:

```bash
export REOUTLOOK_MAINTENANCE_PRIVATE_KEY=/secure/path/reoutlook-maintenance-rsa-private.pem
export REOUTLOOK_EXPORT_PRIVATE_KEY=/secure/path/reoutlook-export-rsa-private.pem
```

## Request an owner-approved export

```bash
python3 tools/admin/reoutlook_admin.py export
```

The owner must review the foreground warning, enter the system lock credential, and choose a file.
The resulting `.reoutlook-admin` file is encrypted for the offline administrator export key.

If a run is interrupted, invalidate the pending one-time challenge:

```bash
python3 tools/admin/reoutlook_admin.py clear
```

A normal app launch also clears stale maintenance challenges.

## Control ReBrowser

The key-authorized path is noninteractive:

```bash
python3 tools/admin/rebrowser_admin.py state --auth key
python3 tools/admin/rebrowser_admin.py open https://example.com/ --auth key
```

The owner-authorized alternative does not require a private key, but the owner must confirm the
one-use command on the phone:

```bash
python3 tools/admin/rebrowser_admin.py new-tab --auth device
```

Run `python3 tools/admin/rebrowser_admin.py --help` for the bounded command set. It does not expose
Cookie, tokens, Web Storage, arbitrary JavaScript execution, or ReOutlook mail data.

## Decrypt an approved export

The decryptor requires Python's `cryptography` package:

```bash
python3 tools/admin/decrypt_reoutlook_export.py \
  exported.reoutlook-admin -o recovered.zip
unzip -t recovered.zip
```

The ZIP contains a versioned manifest plus account-scoped JSON Lines files. It never includes
Outlook cookies, passwords, access tokens, refresh tokens, or Web Storage.

## Protocol boundary

- Entry components remain registered but require `android.permission.DUMP` (ADB shell).
- Challenges expire after three minutes and are single-use.
- Signatures cover the installation ID, exact operation and arguments, nonce, expiry, and—where relevant—the target account.
- The administrator signing key authorizes an operation; a separate RSA key encrypts exports.
- ReOutlook export authorization alone cannot bypass the foreground system lock credential prompt.
- ReBrowser commands instead accept either the administrator key or the foreground device credential.
