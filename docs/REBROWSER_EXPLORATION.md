# ReBrowser initial exploration

ReBrowser is a second mode of the same `io.github.reoutlook` application, not a separate app.
Release builds expose one launcher entry and switch from ReOutlook to ReBrowser inside the application.
ReBrowser does not change ReOutlook's existing WebView or its Default Profile. The capability probe
remains debug-only; the early browser workspace MVP is now part of the main application.

## Current model

- A top-level workspace owns one stable named WebView Profile.
- A workspace may contain multiple child tabs; all child tabs currently use that workspace Profile.
- Website state is isolated per workspace Profile.
- Bookmarks, settings, download history, and saved workspace metadata remain browser-global data.
- Temporary workspaces may be locked in place as secondary workspaces; promotion to a primary workspace is a separate settings action.
- Borrowed profiles, per-child-tab profile selection, and profile sharing across workspaces are intentionally deferred.
- ReOutlook continues to own the Default Profile. ReBrowser must never fall back to it.

## Debug probe

The debug APK includes an ADB-shell-only activity named `ReBrowser 探针`; it has no desktop
launcher entry and requires `android.permission.DUMP`. Launch it during development with
`adb shell am start -n io.github.reoutlook/.ReBrowserProbeActivity`. It checks:

1. the installed WebView provider and version;
2. support for `MULTI_PROFILE`, `DELETE_BROWSING_DATA`, and `SAVE_STATE`;
3. assignment of two child WebViews to one named workspace Profile;
4. assignment of another WebView to a different named Profile;
5. same-workspace cookie sharing and cross-workspace cookie isolation using a temporary cookie on `example.com`;
6. ProfileStore visibility of Default and the two named probe Profiles.

Temporary probe cookies are deleted after each run. The named probe Profiles are retained so persistence can be examined across process restarts. The probe does not read or copy ReOutlook cookies, tokens, Web Storage, or other authentication state.

If `MULTI_PROFILE` is unavailable, the probe reports ReBrowser as disabled. It does not create a browser WebView using the Default Profile.

## Initial device result

The first device probe used Google Android WebView 149.0.7827.48 and passed all checks:

- `MULTI_PROFILE`, `DELETE_BROWSING_DATA`, and experimental `SAVE_STATE` are supported;
- two child WebViews assigned to workspace A resolve to the same named Profile;
- workspace B resolves to a different named Profile;
- a temporary cookie is visible to both workspace-A child tabs and invisible to workspace B;
- workspace B's temporary cookie is invisible to workspace A;
- the two named Profiles remain discoverable after an application process restart;
- ReOutlook's private mail database remains intact after the probe;
- the release manifest and release APK do not contain `ReBrowserProbeActivity`.

This proves that the base workspace model is viable on the tested provider. It does not justify removing runtime feature checks: production ReBrowser must remain disabled on providers without `MULTI_PROFILE`.

## Two-level tab interaction

The visual hierarchy deliberately differs from a conventional browser internally:

- a Chrome-style ordinary tab card represents one complete ReBrowser workspace, not one child tab;
- pressing the top toolbar tab counter opens the workspace overview;
- pressing “new tab” at that level creates a new temporary workspace and a new named Profile;
- each workspace contains its own child-tab strip, modeled after Chrome Android's tab-group strip;
- the child-tab counter opens a second, workspace-scoped card overview;
- links requesting a new window create a child tab in the current workspace and therefore retain its Profile;
- closing a child tab does not delete the Profile, while closing the outer workspace closes all children and schedules its Profile for deletion.

The MVP includes a Chrome-like omnibox, home action, workspace counter, overflow menu, bottom child-tab strip, separate workspace and child-tab card overviews, and temporary/secondary/primary lifecycle actions. Secondary and primary workspaces persist their complete child-tab metadata. Temporary workspace Profile names are recorded for deletion immediately, so an interrupted process can clean them on a later cold start.

The current persistence layer stores only browser-global workspace metadata such as titles and URLs. Cookie, Web Storage, IndexedDB, service workers, and other website data remain inside the named WebView Profile. Profile and tab identifiers are generated locally, bounded, and validated when restored.

## Browser-global settings and back behavior

The settings screen is shared by all ReBrowser workspaces and currently controls the homepage,
default search engine, JavaScript, third-party Cookie policy, and desktop-site mode. The initial
homepage and search defaults use `cn.bing.com`, with Baidu also offered before providers that may
not be reachable directly from mainland China. A changed homepage applies to the home action,
newly created tabs/workspaces, search/address fallback, and the top-level back policy. More
conventional browser settings will be added to this same screen.

Hardware-button and edge-gesture back navigation use the same dispatcher. Transient overviews or
the focused omnibox close first; then the current child WebView walks backward through page history.
A restored page without history is sent to the configured homepage. Only at the homepage does the
first back show an anti-mistouch confirmation and a second back within two seconds exit the entire
application. ReOutlook applies the equivalent policy using the Outlook mailbox list as its homepage.

## Administrator development control

The ADB-only ReBrowser bridge accepts a single-use command after either an authorized administrator
signature or foreground system-lock confirmation. This deliberately does not weaken ReOutlook's
stricter mail-export rule. The protocol and bounded command set are documented in
[REBROWSER_ADMIN_CONTROL.md](REBROWSER_ADMIN_CONTROL.md).

Next stages include page thumbnails, a full new-tab page, bookmarks, site information, sharing,
downloads, dark mode, broader settings, and bounded WebView navigation-state restoration.
Borrowed-profile behavior remains deferred.
