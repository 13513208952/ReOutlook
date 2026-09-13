# Security policy

ReOutlook is currently an experimental client and has not received an independent security audit.
Do not treat it as a hardened enterprise mail product.

## Security boundaries

- The mail database remains in Android credential-protected app-private storage.
- The current database relies on Android file-based encryption and is not independently encrypted with SQLCipher.
- Android automatic backup is disabled; exports use an explicit, authenticated flow.
- The native bridge accepts structured mail data only from configured Outlook mail origins.
- Passwords, cookies, access tokens, refresh tokens, and Web Storage are excluded from native persistence and exports.
- Offline HTML is rendered with JavaScript, network loading, local file access, and navigation disabled.

## Administrator maintenance interfaces

Administrator components have no launcher or normal in-app entry point and require the platform `android.permission.DUMP` permission used by Android shell. A ReOutlook data export additionally requires a short-lived, single-use administrator signature bound to the installation, active account, operation, nonce, and expiry. The device owner must then approve the export through Android's system lock credential UI.

The bounded ReBrowser development-control bridge uses a separate one-use challenge and accepts either the authorized administrator signature or foreground device-credential confirmation. It can navigate and manage browser UI/state, but does not expose arbitrary JavaScript execution, Cookie, tokens, Web Storage, or ReOutlook mail data. Details are in [docs/REBROWSER_ADMIN_CONTROL.md](docs/REBROWSER_ADMIN_CONTROL.md).

The APK contains only administrator public keys. Private keys and raw device/workstation MAC addresses must never be committed to the public repository or packaged in an APK.

## Reporting a vulnerability

Open a minimal public issue without including real mail, credentials, database files, logs, or administrator keys. For sensitive reports, contact the repository owner through a private GitHub channel before sharing diagnostic material.