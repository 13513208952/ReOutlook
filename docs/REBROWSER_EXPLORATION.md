# ReBrowser initial exploration

ReBrowser is being explored without changing ReOutlook's existing WebView or its Default Profile.
The initial implementation is a debug-only capability probe and is not included in release builds.

## Current model

- A top-level workspace owns one stable named WebView Profile.
- A workspace may contain multiple child tabs; all child tabs currently use that workspace Profile.
- Website state is isolated per workspace Profile.
- Bookmarks, settings, download history, and saved workspace metadata remain browser-global data.
- Temporary workspaces may be locked in place as secondary workspaces; promotion to a primary workspace is a separate settings action.
- Borrowed profiles, per-child-tab profile selection, and profile sharing across workspaces are intentionally deferred.
- ReOutlook continues to own the Default Profile. ReBrowser must never fall back to it.

## Debug probe

The debug APK exposes a second launcher activity named `ReBrowser 探针`. It checks:

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

The next exploration stage is a small debug-only workspace shell with one named Profile, multiple ordinary child tabs, bounded state persistence, and no borrowed-profile behavior.
