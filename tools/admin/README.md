# ReOutlook administrator maintenance tool

This tool has no normal in-app entry point. Its maintenance components remain registered but are
callable only by Android shell permission. The tool submits an offline RSA-3072/SHA-256
administrator signature, and then requires the device owner to approve the exact export through Android's system
lock credential UI.

Private keys are deliberately not stored in this repository or APK.

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
- Signatures cover the installation ID, target account, operation, nonce, and expiry.
- The administrator signing key authorizes an operation; a separate RSA key encrypts exports.
- Administrator authorization alone cannot bypass the foreground system lock credential prompt.
