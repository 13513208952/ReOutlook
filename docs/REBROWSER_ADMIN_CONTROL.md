# ReBrowser ADB administrator control

ReBrowser is a mode inside the same `io.github.reoutlook` application, not a second application.
Its administrator bridge exists for development, recovery, and controlled automation. It does not
weaken the stricter ReOutlook mail-export protocol.

## Boundary

Both exported ReBrowser administrator components require `android.permission.DUMP`, so only ADB
shell/system callers can reach them. Every command additionally requires **one** of:

1. a valid RSA-3072/SHA-256 signature from the currently authorized development administrator key; or
2. a successful foreground Android system-lock credential confirmation by the device owner.

These alternatives apply only to ReBrowser control. ReOutlook administrator mail export continues
to require both an administrator signature and owner confirmation.

Challenges bind the exact command and arguments, installation ID, random nonce, issue time, and
three-minute expiry. A challenge is single-use. A normal application launch clears an interrupted
challenge.

## Development key decision

The development bridge temporarily trusts the existing ReOutlook administrator signing public key.
Its private key remains outside the APK and public repository.

A hardware/network MAC address is deliberately not used as a secret key: MAC addresses are
identifiers, may be observable, can be spoofed, and are often unavailable or randomized on modern
Android. No raw workstation or device MAC is embedded in the APK or committed to the repository.
If workstation binding is needed later, a hash of a local machine identifier can be recorded as
non-secret key metadata while the RSA private key remains the actual proof of authorization.

## Supported commands

`tools/admin/rebrowser_admin.py` supports:

- `open URL`: navigate the current child Tab;
- `new-workspace`: create and activate a temporary outer workspace/Profile;
- `new-tab`: create a child Tab in the current workspace/Profile;
- `workspaces` / `tabs`: open the corresponding overview;
- `settings`: open browser-global settings;
- `set-home URL`: update the browser-global homepage;
- `state`: return bounded workspace/Tab metadata;
- `outlook`: switch back to the ReOutlook mode;
- `clear` and `result`: clear a challenge or inspect the last control result.

Examples:

```bash
export REOUTLOOK_MAINTENANCE_PRIVATE_KEY=/secure/path/reoutlook-maintenance-rsa-private.pem
python3 tools/admin/rebrowser_admin.py state --auth key
python3 tools/admin/rebrowser_admin.py open https://example.com/ --auth key
python3 tools/admin/rebrowser_admin.py new-tab --auth device
```

The state command exposes only workspace IDs, controlled Profile names, titles, and page origins.
It strips URL paths, queries, and fragments and never reads Cookie, passwords, tokens, Web Storage,
IndexedDB, or Service Worker data. The bridge intentionally does not provide arbitrary JavaScript
execution or authentication-data extraction.

Ordinary third-party apps cannot call this ADB-only bridge. A future plugin/automation API should
use a separate signature permission and explicit capability grants rather than opening this
administrator surface to all installed apps.
