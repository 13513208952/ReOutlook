# ReBrowser design and current implementation

ReBrowser is a second mode of the same `io.github.reoutlook` application, not a separate APK.
Release builds expose dedicated ReOutlook and ReBrowser launcher entries that open the corresponding
mode directly, while in-app switching remains available. ReBrowser does not change ReOutlook's
existing WebView or its Default Profile. The capability probe remains debug-only; the browser
workspace implementation is part of the main application.

## Product intent

ReOutlook began as a way to retain the official Outlook Web experience while adding local
lifecycle, persistence, offline reading, and owner-controlled recovery. ReBrowser is the second
face that grew naturally from that Web container; it is not a separate APK and its purpose is not
to imitate a branded browser.

Its core principle is **persist by intent, not by visitation**. Most browsing is temporary and
should disappear naturally. A Web environment becomes durable only when the user locks it, and it
becomes primary only after a second explicit decision. URL favorites preserve resources; secondary
总标签页 bookmarks preserve complete working environments. This lifecycle model, rather than any
particular WebView or future browser engine, is the defining product idea.

## Current model

The user-facing name for every top-level workspace is **总标签页**. Temporary, secondary, and
primary are lifecycle classes of that same object, not three different tab systems.

- A 总标签页 owns one stable named WebView Profile and may contain multiple 子标签页.
- All 子标签页 inside one 总标签页 use that 总标签页 Profile.
- Every newly created 总标签页 is temporary and is discarded with its Profile when closed or when
  an interrupted temporary session is cleaned up.
- Locking a temporary 总标签页 promotes it to secondary and automatically registers the complete
  environment in the 书签栏.
- An open secondary survives process restarts. Explicitly closing it only shelves it; clicking its
  书签栏 entry restores its child-tab metadata and the same named Profile.
- Removing the lock drops it from the 书签栏 and turns it back into a temporary 总标签页, which is
  then cleared when closed.
- The overflow menu promotes a secondary to primary and demotes a primary only to secondary. By
  default the same promotion action is disabled for a temporary 总标签页. A browser-global setting
  may explicitly allow the sole shortcut `temporary → primary`, skipping secondary; it does not
  change the ordinary demotion chain `primary → secondary → temporary`.
- Primary 总标签页 are not unique: up to five may coexist, and promoting one workspace never
  demotes another primary. Primary workspaces cannot be closed, do not appear in the 书签栏, and
  are restored automatically at application startup.
- Active and shelved 总标签页 have a combined normal limit of 64, while each 总标签页 retains the
  existing limit of 50 子标签页.
- 收藏栏 is separate from 书签栏: 收藏栏 stores URL resources, while 书签栏 stores complete secondary
  browsing environments.
- Browser settings, favorites, download history, and lifecycle metadata are browser-global data;
  website state remains isolated per named Profile.
- Borrowed profiles, per-child-tab profile selection, and profile sharing across 总标签页 are
  intentionally deferred.
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

## Device capability result

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

- a Chrome-style ordinary card represents one complete 总标签页, not one 子标签页;
- pressing the top toolbar counter opens the first-level 总标签页 overview;
- tapping a 总标签页 card drills into that environment's second-level 子标签页 overview;
- the previous persistent bottom child-tab strip has been removed to preserve webpage space;
- pressing “new 总标签页” creates a temporary environment and a new named Profile;
- links requesting a new window create a 子标签页 in the current 总标签页 and retain its Profile;
- closing a 子标签页 never deletes the Profile;
- closing a temporary 总标签页 deletes it, closing a secondary shelves it in the 书签栏, and a
  primary has no close action.

The compact browser surface includes a Chrome-like omnibox, home action, a top `＋` that immediately
creates a 子标签页, a 总标签页 counter, and a Chrome-style overflow popup anchored at the upper
right. Its monochrome shortcut row uses five true circular buttons for back, forward, favorite,
application-only orientation switching, and refresh. Tapping orientation switches and locks between
portrait and sensor-landscape; long-pressing it returns orientation control to the system without
changing the system rotation setting. URL favorites and secondary-environment bookmarks each have a
dedicated manager opened from this menu instead of occupying persistent rows above the webpage. The
popup deliberately omits duplicate create/manage/close tab actions and the earlier page-info action.

The lifecycle lock is a high-contrast monochrome vector icon on 总标签页 cards instead of a hidden
gesture or a colored Emoji. Long-pressing a 总标签页 or 子标签页 enters multi-selection for bounded
batch actions. When a primary
总标签页 is among the selected cards, lock, unlock, and close actions are disabled. The ReBrowser
root relies on the shared display-cutout/system-bar inset handler rather than overriding it with a
portrait-only top/bottom inset listener. Profile and tab identifiers remain locally generated,
bounded, and validated when restored. Cookie, Web Storage, IndexedDB, service workers, and other
website data remain exclusively inside each named WebView Profile.

## Browser-global settings and back behavior

The settings screen is shared by all ReBrowser workspaces and currently controls the homepage,
default search engine, JavaScript, third-party Cookie policy, desktop-site mode, the explicit
permission for a temporary 总标签页 to skip secondary and promote directly to primary, and an
optional three-way video-orientation override. The initial
homepage and search defaults use `cn.bing.com`, with Baidu also offered before providers that may
not be reachable directly from mainland China. A changed homepage applies to the home action,
newly created tabs/workspaces, search/address fallback, and the top-level back policy. More
conventional browser settings will be added to this same screen. The earlier explanatory privacy
paragraph has been removed from the settings UI; privacy guarantees remain documented in the
project documentation instead of occupying an inert settings section.

WebView custom-view playback is hosted as native full-screen content. The persisted global browser
orientation defaults to normal portrait on a fresh installation and may be changed to
sensor-landscape or unlocked from the overflow shortcut. With the video override disabled, an
unlocked global mode maps video to ordinary sensor auto-rotation, while either global lock maps
video to sensor-landscape. Enabling the override selects sensor-landscape, ordinary sensor
auto-rotation, or normal portrait from a three-segment setting. Reverse portrait is deliberately
not requested. Leaving full-screen playback reapplies the persisted global browser orientation.

Hardware-button and edge-gesture back navigation use the same dispatcher. Transient overviews or
the focused omnibox close first; then the current child WebView walks backward through page history.
A restored page without history is sent to the configured homepage. Only at the homepage does the
first back show an anti-mistouch confirmation and a second back within two seconds exit the entire
application. ReOutlook applies the equivalent policy using the Outlook mailbox list as its homepage.

## Administrator development control

The ADB-only ReBrowser protocol-v2 bridge provides per-request structured results, object-ID
control, load waiting, lifecycle operations, diagnostics, bounded audit metadata, and invariant
repair. Either immutable root may authorize all three levels; foreground system-lock confirmation
alone is restricted to levels one and two. This deliberately does not weaken ReOutlook's stricter
mail-export rule. The protocol and bounded command set are documented in
[REBROWSER_ADMIN_CONTROL.md](REBROWSER_ADMIN_CONTROL.md).

Next stages include page thumbnails, a full new-tab page, favorite editing and folders, site
information, sharing, downloads, dark mode, broader settings, and bounded WebView navigation-state
restoration.
Borrowed-profile behavior remains deferred.
