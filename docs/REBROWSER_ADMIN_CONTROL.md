# ReBrowser ADB administrator control

ReBrowser is a mode inside the same `io.github.reoutlook` application, not a second APK. Its administrator bridge exists for development, recovery, and controlled automation. It does not weaken the stricter ReOutlook mail-export protocol.

## Platform and authorization boundary

Both exported ReBrowser administrator components require `android.permission.DUMP`, so only ADB shell/system callers can reach them. Every command then uses a three-minute, single-use challenge bound to the exact request, installation ID, request ID, random nonce, issue time, and expiry.

Two immutable public roots are compiled into the application:

- `reoutlook-root-v1`: RSA-3072/SHA-256, fingerprint `0c045556f779ddc469cdf64c14b33f27904bf75fd48956111860a9f081be99fd`;
- `rebrowser-root-v1`: ECDSA P-256/SHA-256, fingerprint `38d674fb6eb66ed4efaf2360def9c023c81b18a594b3fcea127bc87338a0ef01`.

Both roots can independently authorize all three ReBrowser control levels and cannot be disabled by an application setting or administrator command. The ReBrowser root does **not** authorize ReOutlook mail export. ReOutlook export continues to require its RSA administrator signature and a separate foreground owner-credential confirmation.

The device owner may approve level-one and level-two ReBrowser operations using the Android system lock credential instead of a root signature. Level-three operations always require at least one administrator root signature and cannot be approved by the owner credential alone.

Other trusted keys, if introduced, must be added with explicit scopes in source code and a new APK. There is no command for enrolling a key. Non-root keys may be marked unavailable by a later build; the two development roots have no runtime revocation state.

## Authorization levels

- **Level 1:** bounded state, diagnostics, navigation, UI routing, and browser settings.
- **Level 2:** lifecycle and destructive object operations such as locking, shelving, closing, or deleting a specific workspace/tab. Delete commands bind an exact object ID and require `confirmDelete=true`.
- **Level 3:** invariant repair. The current `REPAIR_STATE` command only cancels deletion markers that conflict with still-persistent workspaces and repairs bounded active-tab metadata.

The owner-confirmation screen displays the operation, level, target IDs, and a destructive-operation warning. It does not offer an owner-only approval button for level three.

## Protocol v2

Every request contains a protocol version, unique request ID, operation, optional target IDs, and bounded arguments. Results are stored independently by request ID and move through structured states:

- `challenge-created`
- `authorized`
- `queued`
- `running`
- `completed`
- `failed`
- `cancelled`

Terminal results contain structured details or a bounded error string. The CLI polls `getResult` by request ID instead of guessing from the foreground UI. Navigation commands can use `--wait`; completion is then reported only after the targeted main frame finishes, fails, or reaches the bounded timeout.

A bounded audit ring records the request ID, operation, level, authorization method, key ID, target IDs, status, and time. It does not record complete URLs or page content.

## Supported command groups

`tools/admin/rebrowser_admin.py` currently supports:

- capability and state: `capabilities`, `state`, `diagnostics`, `audit`, `validate`;
- navigation: `open`, `reload`, `stop`, `back`, `forward`, `home`, and non-disclosing `assert-location`;
- object selection: `activate-workspace`, `activate-tab`;
- creation and UI: `new-workspace`, `new-tab`, `workspaces`, `tabs`, `settings`, `outlook`;
- lifecycle: `lock`, `unlock`, `promote`, `demote`, `shelve`, `restore`;
- destructive operations: `close-workspace`, `close-tab`, `delete-shelved` with `--confirm-delete`;
- settings: `set-home` and whitelisted `set-pref` values;
- level-three bounded repair: `repair`.

Examples:

```bash
export REBROWSER_ROOT_PRIVATE_KEY=/secure/path/rebrowser-root-v1-private.pem

python3 tools/admin/rebrowser_admin.py capabilities --key-id rebrowser-root-v1
python3 tools/admin/rebrowser_admin.py state --key-id rebrowser-root-v1 --compact
python3 tools/admin/rebrowser_admin.py new-workspace --key-id rebrowser-root-v1
python3 tools/admin/rebrowser_admin.py open https://www.baidu.com/ \
  --workspace WORKSPACE_ID --tab TAB_ID --wait --key-id rebrowser-root-v1
python3 tools/admin/rebrowser_admin.py lock WORKSPACE_ID --key-id rebrowser-root-v1
python3 tools/admin/rebrowser_admin.py close-workspace WORKSPACE_ID \
  --confirm-delete --key-id rebrowser-root-v1
python3 tools/admin/rebrowser_admin.py repair --key-id rebrowser-root-v1
```

The existing ReOutlook RSA root remains accepted:

```bash
export REOUTLOOK_MAINTENANCE_PRIVATE_KEY=/secure/path/reoutlook-maintenance-rsa-private.pem
python3 tools/admin/rebrowser_admin.py diagnostics --key-id reoutlook-root-v1
```

Use `--auth device` for an owner-approved level-one or level-two command. Run `--help` for target and wait options.

## Data boundary

State and diagnostics expose bounded workspace/tab IDs, controlled Profile names, titles, page origins, load progress, main-frame errors, lifecycle levels, feature support, and non-secret settings. URL paths, queries, and fragments are stripped.

The bridge never reads or returns passwords, Cookie, tokens, Web Storage, IndexedDB, Service Worker data, or ReOutlook mail. It does not provide arbitrary JavaScript execution, arbitrary database access, arbitrary private-file access, or arbitrary Intent execution.

Ordinary third-party applications cannot call this ADB-only bridge. A future plugin API must use a separate signature permission and explicit capability grants rather than exposing this administrator surface.
