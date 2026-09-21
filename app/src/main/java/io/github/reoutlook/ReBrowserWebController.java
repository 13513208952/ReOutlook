package io.github.reoutlook;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns every ReBrowser WebView, callback installation, and page execution boundary. */
@SuppressLint("RequiresFeature") // Activity constructs this only after the Multi-Profile gate.
final class ReBrowserWebController implements ReBrowserPageDownloadBridge {
    interface Listener {
        void onPageStarted(ReBrowserStore.Tab tab, String url, boolean visible);
        void onPageFinished(ReBrowserStore.Tab tab, String url, String title, boolean visible);
        void onMainFrameError(
                ReBrowserStore.Tab tab,
                int errorCode,
                String description,
                boolean visible);
        void onProgressChanged(ReBrowserStore.Tab tab, int progress, boolean visible);
        void onTitleReceived(ReBrowserStore.Tab tab, String title, boolean visible);
        void onExternalNavigationBlocked(String message);
        void onExternalNavigationRequested(Uri uri, String scheme);
        PopupTarget onCreatePopup(ReBrowserStore.Tab sourceTab);
        void onPopupCreated(ReBrowserStore.Tab tab);
        void onPopupCreationFailed(ReBrowserStore.Tab tab, Throwable error);
        void onCloseRequested(ReBrowserStore.Tab tab);
        void onDownloadRequested(DownloadRequest request);
        void onFileChooserUnavailable();
        void onSitePermissionRequested(ReBrowserPermissionRequest request);
    }

    static final class PopupTarget {
        final String workspaceId;
        final String profileName;
        final ReBrowserStore.Tab tab;

        PopupTarget(String workspaceId, String profileName, ReBrowserStore.Tab tab) {
            this.workspaceId = workspaceId;
            this.profileName = profileName;
            this.tab = tab;
        }
    }

    static final class DownloadRequest {
        final String workspaceId;
        final String tabId;
        final String profileName;
        final String sourceUrl;
        final String sourceOrigin;
        final String url;
        final String requestMethod;
        final String userAgent;
        final String contentDisposition;
        final String mimeType;
        final long contentLength;

        DownloadRequest(
                String workspaceId,
                String tabId,
                String profileName,
                String sourceUrl,
                String sourceOrigin,
                String url,
                String requestMethod,
                String userAgent,
                String contentDisposition,
                String mimeType,
                long contentLength
        ) {
            this.workspaceId = workspaceId;
            this.tabId = tabId;
            this.profileName = profileName;
            this.sourceUrl = sourceUrl;
            this.sourceOrigin = sourceOrigin;
            this.url = url;
            this.requestMethod = requestMethod;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
            this.contentLength = contentLength;
        }
    }

    static final class PageState {
        final ReBrowserStore.Tab tab;
        final String url;
        final String title;

        PageState(ReBrowserStore.Tab tab, String url, String title) {
            this.tab = tab;
            this.url = url;
            this.title = title;
        }
    }

    private static final class PageBinding {
        final String workspaceId;
        final String profileName;
        final ReBrowserStore.Tab tab;
        final WebView webView;
        final long generation;
        long navigationGeneration;
        final java.util.LinkedHashMap<String, RequestMethodObservation> requestMethods =
                new java.util.LinkedHashMap<>();
        final Map<String, Long> permissionPromptBlockedUntil = new HashMap<>();
        boolean loading;
        int loadProgress;
        boolean sensitiveMediaActive;
        String lastMainFrameError = "";

        PageBinding(
                String workspaceId,
                String profileName,
                ReBrowserStore.Tab tab,
                WebView webView,
                long generation
        ) {
            this.workspaceId = workspaceId;
            this.profileName = profileName;
            this.tab = tab;
            this.webView = webView;
            this.generation = generation;
        }
    }

    private static final class RequestMethodObservation {
        final String method;
        final long observedAt;

        RequestMethodObservation(String method, long observedAt) {
            this.method = method;
            this.observedAt = observedAt;
        }
    }

    private final Activity activity;
    private final ReBrowserPreferences preferences;
    private final ReBrowserSitePermissions sitePermissions;
    private final ReBrowserFullscreenController fullscreenController;
    private final Listener listener;
    private final int fileChooserRequestCode;
    private final Map<String, PageBinding> pagesByTabId = new HashMap<>();
    private final Map<WebView, PageBinding> pagesByView = new IdentityHashMap<>();
    private final Map<PermissionRequest, ReBrowserPermissionRequest> mediaRequests =
            new IdentityHashMap<>();
    private final List<ReBrowserPermissionRequest> pendingPermissionRequests = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout container;
    private ValueCallback<Uri[]> pendingFileChooser;
    private String pendingFileChooserTabId;
    private long pendingFileChooserGeneration;
    private boolean foreground = true;
    private long nextGeneration = 1L;

    ReBrowserWebController(
            Activity activity,
            ReBrowserPreferences preferences,
            ReBrowserSitePermissions sitePermissions,
            ReBrowserFullscreenController fullscreenController,
            Listener listener,
            int fileChooserRequestCode
    ) {
        this.activity = activity;
        this.preferences = preferences;
        this.sitePermissions = sitePermissions;
        this.fullscreenController = fullscreenController;
        this.listener = listener;
        this.fileChooserRequestCode = fileChooserRequestCode;
    }

    void attachContainer(FrameLayout container) {
        this.container = container;
    }

    static android.content.pm.PackageInfo currentProvider() {
        return WebView.getCurrentWebViewPackage();
    }

    boolean hasPages() {
        return !pagesByTabId.isEmpty();
    }

    void showTab(String workspaceId, String profileName, ReBrowserStore.Tab tab) {
        PageBinding page = pagesByTabId.get(tab.id);
        if (page == null) page = createPage(workspaceId, profileName, tab);
        showPage(page, true);
    }

    private void showPage(PageBinding page, boolean loadInitialUrl) {
        if (container == null) throw new IllegalStateException("web-container-unavailable");
        android.view.View previous = container.getChildCount() == 0
                ? null : container.getChildAt(0);
        if (previous instanceof WebView && previous != page.webView) {
            PageBinding previousPage = pagesByView.get(previous);
            if (previousPage != null) {
                cancelPermissionRequestsForTab(previousPage.tab.id, "tab-hidden");
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
                    WebViewCompat.setAudioMuted(previousPage.webView, true);
                }
                previousPage.webView.onPause();
                if (previousPage.sensitiveMediaActive) {
                    pagesByTabId.remove(previousPage.tab.id);
                    pagesByView.remove(previousPage.webView);
                    destroyPage(previousPage);
                }
            }
        }
        container.removeAllViews();
        if (page.webView.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) page.webView.getParent()).removeView(page.webView);
        }
        container.addView(page.webView, ReBrowserUi.matchMatch());
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
            WebViewCompat.setAudioMuted(page.webView, false);
        }
        if (loadInitialUrl && page.webView.getUrl() == null) page.webView.loadUrl(page.tab.url);
        page.webView.onResume();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private PageBinding createPage(
            String workspaceId,
            String profileName,
            ReBrowserStore.Tab tab
    ) {
        if (!ReBrowserStore.isOwnedProfile(profileName)) {
            throw new IllegalArgumentException("拒绝使用非 ReBrowser Profile");
        }
        WebView webView = new WebView(activity);
        try {
            // No WebView operation is permitted before the owned named Profile is assigned.
            WebViewCompat.setProfile(webView, profileName);
        } catch (Throwable error) {
            webView.destroy();
            throw error;
        }
        PageBinding page = new PageBinding(
                workspaceId, profileName, tab, webView, nextGeneration++);
        pagesByTabId.put(tab.id, page);
        pagesByView.put(webView, page);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(preferences.javascriptEnabled());
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        installPermissionPolicy(page);
        webView.setWebViewClient(new BrowserClient(page));
        webView.setWebChromeClient(new ChromeClient(page));
        webView.setDownloadListener((url, userAgent, disposition, mimeType, contentLength) ->
                dispatchDownload(page, url, userAgent, disposition, mimeType, contentLength));
        applyPreferences(page);
        return page;
    }

    void applyPreferencesToAll() {
        for (PageBinding page : pagesByTabId.values()) applyPreferences(page);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void applyPreferences(PageBinding page) {
        WebSettings settings = page.webView.getSettings();
        settings.setJavaScriptEnabled(preferences.javascriptEnabled());
        settings.setUseWideViewPort(preferences.desktopModeEnabled());
        settings.setLoadWithOverviewMode(preferences.desktopModeEnabled());
        settings.setUserAgentString(preferences.desktopModeEnabled()
                ? desktopUserAgent() : null);
        WebViewCompat.getProfile(page.webView).getCookieManager().setAcceptThirdPartyCookies(
                page.webView, preferences.thirdPartyCookiesEnabled());
    }

    private void installPermissionPolicy(PageBinding page) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return;
        }
        WebViewCompat.addWebMessageListener(
                page.webView,
                "reBrowserPermissionBridge",
                Set.of("*"),
                (view, message, sourceOrigin, isMainFrame, replyProxy) ->
                        handlePermissionMessage(page, view, message, sourceOrigin,
                                isMainFrame, replyProxy));
        WebViewCompat.addDocumentStartJavaScript(
                page.webView, ReBrowserPermissionScript.DOCUMENT_START, Set.of("*"));
    }

    private void handlePermissionMessage(
            PageBinding page,
            WebView view,
            WebMessageCompat message,
            Uri sourceOrigin,
            boolean isMainFrame,
            JavaScriptReplyProxy reply
    ) {
        String payload = message == null ? null : message.getData();
        if (payload == null || payload.length() > 220_000) {
            replyError(reply, "", "invalid-request");
            return;
        }
        String id = "";
        try {
            JSONObject request = new JSONObject(payload);
            id = request.optString("id", "");
            if (!id.matches("[0-9]{1,12}")) {
                replyError(reply, "", "invalid-request-id");
                return;
            }
            String origin = ReBrowserSitePermissions.normalizeOrigin(sourceOrigin.toString());
            if (!isMainFrame || !foreground || !isCurrentVisiblePage(page)
                    || origin.isEmpty() || !origin.equals(safeOrigin(view.getUrl()))) {
                replyError(reply, id, "page-not-eligible");
                return;
            }
            String operation = request.optString("operation");
            if ("clipboard-read".equals(operation) || "clipboard-write".equals(operation)) {
                handleClipboardRequest(page, origin, id, operation, request, reply);
                return;
            }
            if ("location".equals(operation)) {
                final String requestId = id;
                boolean precise = request.optBoolean("highAccuracy", false)
                        && sitePermissions.capabilityEnabled(
                                ReBrowserSitePermissions.PRECISE_LOCATION);
                String permission = precise ? ReBrowserSitePermissions.PRECISE_LOCATION
                        : ReBrowserSitePermissions.APPROXIMATE_LOCATION;
                int timeout = Math.max(1_000, Math.min(30_000,
                        request.optInt("timeout", 15_000)));
                requestSemanticPermission(page, origin, permission,
                        precise
                                ? Arrays.asList(Manifest.permission.ACCESS_COARSE_LOCATION,
                                        Manifest.permission.ACCESS_FINE_LOCATION)
                                : List.of(Manifest.permission.ACCESS_COARSE_LOCATION),
                        () -> readLocation(page, origin, requestId, precise, timeout, reply),
                        () -> replyError(reply, requestId, "location-denied"));
                return;
            }
            replyError(reply, id, "unsupported-operation");
        } catch (Exception error) {
            replyError(reply, id, "invalid-request");
        }
    }

    private void handleClipboardRequest(
            PageBinding page,
            String origin,
            String id,
            String operation,
            JSONObject request,
            JavaScriptReplyProxy reply
    ) {
        requestSemanticPermission(page, origin, ReBrowserSitePermissions.CLIPBOARD, List.of(), () -> {
            if (!isCurrentVisiblePage(page) || !foreground) {
                replyError(reply, id, "page-not-eligible");
                return;
            }
            ClipboardManager clipboard = (ClipboardManager)
                    activity.getSystemService(Activity.CLIPBOARD_SERVICE);
            if ("clipboard-write".equals(operation)) {
                String text = request.optString("text", "");
                if (text.length() > 200_000) {
                    replyError(reply, id, "clipboard-text-too-large");
                    return;
                }
                clipboard.setPrimaryClip(ClipData.newPlainText("网页剪贴板", text));
                replyValue(reply, id, JSONObject.NULL);
                return;
            }
            ClipData clip = clipboard.getPrimaryClip();
            CharSequence text = clip == null || clip.getItemCount() == 0 ? ""
                    : clip.getItemAt(0).coerceToText(activity);
            String value = text == null ? "" : text.toString();
            if (value.length() > 200_000) value = value.substring(0, 200_000);
            replyValue(reply, id, value);
        }, () -> replyError(reply, id, "clipboard-denied"));
    }

    private void requestSemanticPermission(
            PageBinding page,
            String origin,
            String permission,
            List<String> androidPermissions,
            Runnable approved,
            Runnable denied
    ) {
        if (!sitePermissions.capabilityEnabled(permission)
                || page.permissionPromptBlockedUntil.getOrDefault(permission, 0L)
                        > SystemClock.elapsedRealtime()) {
            denied.run();
            return;
        }
        long now = System.currentTimeMillis();
        if (sitePermissions.hasGrant(page.profileName, origin, permission, now)
                && androidPermissionsGranted(androidPermissions)) {
            approved.run();
            return;
        }
        boolean sensitive = ReBrowserSitePermissions.CAMERA.equals(permission)
                || ReBrowserSitePermissions.MICROPHONE.equals(permission)
                || ReBrowserSitePermissions.PRECISE_LOCATION.equals(permission);
        ReBrowserPermissionRequest request = createPermissionRequest(
                page, origin, List.of(permission),
                sensitive ? 0L : ReBrowserSitePermissions.FIVE_MINUTES_MS,
                sensitive ? ReBrowserSitePermissions.THREE_HOURS_MS
                        : ReBrowserSitePermissions.FIFTEEN_DAYS_MS,
                sensitive ? "仅本次" : "允许 5 分钟",
                sensitive ? "允许 3 小时" : "允许 15 天",
                androidPermissions, approved, denied);
        listener.onSitePermissionRequested(request);
    }

    private ReBrowserPermissionRequest createPermissionRequest(
            PageBinding page,
            String origin,
            List<String> permissions,
            long shortDuration,
            long longDuration,
            String shortLabel,
            String longLabel,
            List<String> androidPermissions,
            Runnable approved,
            Runnable denied
    ) {
        ReBrowserPermissionRequest[] holder = new ReBrowserPermissionRequest[1];
        ReBrowserPermissionRequest request = new ReBrowserPermissionRequest(
                page.profileName, page.tab.id, origin, permissions,
                shortDuration, longDuration, shortLabel, longLabel, androidPermissions,
                new ReBrowserPermissionRequest.Responder() {
                    @Override
                    public void approve(long durationMs) {
                        ReBrowserPermissionRequest current = holder[0];
                        pendingPermissionRequests.remove(current);
                        if (!foreground || !isCurrentVisiblePage(page)
                                || !androidPermissionsGranted(androidPermissions)) {
                            denied.run();
                            return;
                        }
                        for (String permission : permissions) {
                            if (!sitePermissions.capabilityEnabled(permission)) {
                                denied.run();
                                return;
                            }
                        }
                        if (durationMs > 0L) {
                            long now = System.currentTimeMillis();
                            for (String permission : permissions) {
                                if (!sitePermissions.grant(page.profileName, origin,
                                        permission, durationMs, now)) {
                                    denied.run();
                                    return;
                                }
                            }
                        }
                        approved.run();
                    }

                    @Override
                    public void deny(String reason) {
                        pendingPermissionRequests.remove(holder[0]);
                        if ("user-denied".equals(reason) || "user-dismissed".equals(reason)) {
                            long blockedUntil = SystemClock.elapsedRealtime() + 30_000L;
                            for (String permission : permissions) {
                                page.permissionPromptBlockedUntil.put(permission, blockedUntil);
                            }
                        }
                        denied.run();
                    }
                });
        holder[0] = request;
        pendingPermissionRequests.add(request);
        // The browser card enforces a 30-second idle timeout. Keep additional time for the
        // Android runtime-permission sheet after the user has already made that choice.
        handler.postDelayed(() -> request.deny("permission-timeout"), 120_000L);
        return request;
    }

    private boolean androidPermissionsGranted(List<String> permissions) {
        for (String permission : permissions) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean isCurrentVisiblePage(PageBinding page) {
        return pagesByTabId.get(page.tab.id) == page && page.webView.getParent() == container;
    }

    private String desktopUserAgent() {
        String defaultAgent = WebSettings.getDefaultUserAgent(activity);
        return defaultAgent.replaceFirst("\\(Linux; Android[^)]*\\)", "(X11; Linux x86_64)")
                .replace("; wv", "")
                .replace(" Mobile", "");
    }

    void load(String tabId, String url) {
        requirePage(tabId).webView.loadUrl(url);
    }

    void stop(String tabId) {
        PageBinding page = requirePage(tabId);
        page.webView.stopLoading();
        page.loading = false;
    }

    void reload(String tabId) {
        requirePage(tabId).webView.reload();
    }

    boolean canGoBack(String tabId) {
        PageBinding page = pagesByTabId.get(tabId);
        return page != null && page.webView.canGoBack();
    }

    boolean canGoForward(String tabId) {
        PageBinding page = pagesByTabId.get(tabId);
        return page != null && page.webView.canGoForward();
    }

    void goBack(String tabId) {
        requirePage(tabId).webView.goBack();
    }

    void goForward(String tabId) {
        requirePage(tabId).webView.goForward();
    }

    String currentUrl(String tabId) {
        PageBinding page = pagesByTabId.get(tabId);
        return page == null ? null : page.webView.getUrl();
    }

    String currentTitle(String tabId) {
        PageBinding page = pagesByTabId.get(tabId);
        return page == null ? null : page.webView.getTitle();
    }

    boolean isLoading(String tabId) {
        PageBinding page = pagesByTabId.get(tabId);
        return page != null && page.loading;
    }

    int loadProgress(String tabId) {
        PageBinding page = pagesByTabId.get(tabId);
        return page == null ? 0 : page.loadProgress;
    }

    String lastMainFrameError(String tabId) {
        PageBinding page = pagesByTabId.get(tabId);
        return page == null ? "" : page.lastMainFrameError;
    }

    List<PageState> pageStates() {
        List<PageState> states = new ArrayList<>();
        for (PageBinding page : pagesByTabId.values()) {
            states.add(new PageState(page.tab, page.webView.getUrl(), page.webView.getTitle()));
        }
        return states;
    }

    void removeTab(String tabId) {
        PageBinding page = pagesByTabId.remove(tabId);
        if (page == null) return;
        cancelPermissionRequestsForTab(tabId, "page-closed");
        cancelFileChooserForTab(tabId);
        pagesByView.remove(page.webView);
        destroyPage(page);
    }

    void destroyAll() {
        cancelAllPermissionRequests("pages-destroyed");
        cancelPendingFileChooser();
        if (container != null) container.removeAllViews();
        for (PageBinding page : new ArrayList<>(pagesByTabId.values())) destroyPage(page);
        pagesByTabId.clear();
        pagesByView.clear();
    }

    private static void destroyPage(PageBinding page) {
        page.webView.stopLoading();
        if (page.webView.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) page.webView.getParent()).removeView(page.webView);
        }
        page.webView.removeAllViews();
        page.webView.destroy();
    }

    void onResume() {
        foreground = true;
        applyPreferencesToAll();
        for (PageBinding page : pagesByTabId.values()) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
                WebViewCompat.setAudioMuted(
                        page.webView, page.webView.getParent() != container);
            }
        }
        PageBinding visible = visiblePage();
        if (visible != null) visible.webView.onResume();
    }

    void pauseForAndroidPermissionDialog() {
        foreground = false;
        for (PageBinding page : pagesByTabId.values()) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
                WebViewCompat.setAudioMuted(page.webView, true);
            }
            page.webView.onPause();
        }
    }

    void onPause() {
        foreground = false;
        cancelAllPermissionRequests("application-backgrounded");
        for (PageBinding page : new ArrayList<>(pagesByTabId.values())) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
                WebViewCompat.setAudioMuted(page.webView, true);
            }
            page.webView.onPause();
            if (page.sensitiveMediaActive) {
                pagesByTabId.remove(page.tab.id);
                pagesByView.remove(page.webView);
                destroyPage(page);
            }
        }
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != fileChooserRequestCode || pendingFileChooser == null) return;
        ValueCallback<Uri[]> callback = pendingFileChooser;
        PageBinding page = pagesByTabId.get(pendingFileChooserTabId);
        boolean valid = page != null && page.generation == pendingFileChooserGeneration;
        pendingFileChooser = null;
        pendingFileChooserTabId = null;
        pendingFileChooserGeneration = 0L;
        Uri[] result = valid
                ? WebChromeClient.FileChooserParams.parseResult(resultCode, data) : null;
        callback.onReceiveValue(result);
    }

    boolean hasPendingFileChooser() {
        return pendingFileChooser != null;
    }

    void cancelPendingFileChooser() {
        if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
        pendingFileChooser = null;
        pendingFileChooserTabId = null;
        pendingFileChooserGeneration = 0L;
    }

    private void cancelFileChooserForTab(String tabId) {
        if (tabId != null && tabId.equals(pendingFileChooserTabId)) cancelPendingFileChooser();
    }

    private void cancelPermissionRequestsForTab(String tabId, String reason) {
        for (ReBrowserPermissionRequest request
                : new ArrayList<>(pendingPermissionRequests)) {
            if (request.tabId.equals(tabId)) request.deny(reason);
        }
    }

    private void cancelAllPermissionRequests(String reason) {
        for (ReBrowserPermissionRequest request
                : new ArrayList<>(pendingPermissionRequests)) request.deny(reason);
    }

    @Override
    public String cookieForHttpDownload(
            String tabId,
            String profileName,
            String sourceOrigin,
            String requestUrl
    ) {
        PageBinding page = requireMatchingPage(tabId, profileName, sourceOrigin);
        Profile profile = ProfileStore.getInstance().getProfile(page.profileName);
        if (profile == null) throw new IllegalStateException("profile-unavailable");
        return profile.getCookieManager().getCookie(requestUrl);
    }

    @Override
    public BlobHandle beginBlobTransfer(
            String tabId,
            String profileName,
            String origin,
            String blobUrl,
            String transferId,
            ValueCallback<Boolean> callback
    ) {
        PageBinding page = requireMatchingPage(tabId, profileName, origin);
        if (!page.webView.getSettings().getJavaScriptEnabled()) {
            throw new IllegalStateException("javascript-disabled");
        }
        String key = "__rebrowser_download_" + transferId;
        BlobHandle handle = new BlobHandle(
                tabId, profileName, origin, key,
                page.generation, page.navigationGeneration);
        String quotedKey = JSONObject.quote(key);
        String script = "(()=>{const s={status:'loading',blob:null,error:''};window["
                + quotedKey + "]=s;fetch(" + JSONObject.quote(blobUrl)
                + ").then(r=>r.blob()).then(b=>{s.blob=b;s.size=b.size;s.type=b.type||'';"
                + "s.status='ready';}).catch(e=>{s.error=String(e).slice(0,160);"
                + "s.status='error';});return true;})()";
        evaluateFixed(page, handle, script, result -> callback.onReceiveValue(
                Boolean.TRUE.equals(decodeBoolean(result))));
        return handle;
    }

    @Override
    public void pollBlobMetadata(BlobHandle handle, ValueCallback<BlobMetadata> callback) {
        PageBinding page = requireBlobPage(handle);
        String key = JSONObject.quote(handle.key);
        String script = "(()=>{const s=window[" + key + "];if(!s)return JSON.stringify("
                + "{status:'error',error:'missing-blob-state'});return JSON.stringify("
                + "{status:s.status,error:s.error||'',size:s.size||0,type:s.type||''});})()";
        evaluateFixed(page, handle, script, result -> {
            try {
                JSONObject value = new JSONObject(decodeString(result));
                callback.onReceiveValue(new BlobMetadata(
                        value.optString("status"), value.optString("error"),
                        value.optLong("size"), value.optString("type")));
            } catch (Exception error) {
                callback.onReceiveValue(new BlobMetadata(
                        "error", "blob-metadata-invalid:" + error.getMessage(), 0L, ""));
            }
        });
    }

    @Override
    public void requestBlobChunk(
            BlobHandle handle,
            long offset,
            long end,
            ValueCallback<Boolean> callback
    ) {
        PageBinding page = requireBlobPage(handle);
        String key = JSONObject.quote(handle.key);
        String script = "(()=>{const s=window[" + key + "];if(!s||!s.blob)return false;"
                + "s.chunkStatus='loading';s.chunk='';s.chunkError='';s.blob.slice("
                + offset + "," + end + ").arrayBuffer().then(v=>{const a=new Uint8Array(v);"
                + "let x='';for(let i=0;i<a.length;i+=32768)x+=String.fromCharCode.apply("
                + "null,a.subarray(i,i+32768));s.chunk=btoa(x);s.chunkStatus='ready';})"
                + ".catch(e=>{s.chunkError=String(e).slice(0,160);s.chunkStatus='error';});"
                + "return true;})()";
        evaluateFixed(page, handle, script, result -> callback.onReceiveValue(
                Boolean.TRUE.equals(decodeBoolean(result))));
    }

    @Override
    public void pollBlobChunk(BlobHandle handle, ValueCallback<BlobChunk> callback) {
        PageBinding page = requireBlobPage(handle);
        String key = JSONObject.quote(handle.key);
        String script = "(()=>{const s=window[" + key + "];if(!s)return JSON.stringify("
                + "{status:'error',error:'missing-blob-state'});return JSON.stringify("
                + "{status:s.chunkStatus||'loading',error:s.chunkError||'',data:s.chunk||''});})()";
        evaluateFixed(page, handle, script, result -> {
            try {
                JSONObject value = new JSONObject(decodeString(result));
                callback.onReceiveValue(new BlobChunk(
                        value.optString("status"), value.optString("error"),
                        value.optString("data")));
            } catch (Exception error) {
                callback.onReceiveValue(new BlobChunk(
                        "error", "blob-chunk-invalid:" + error.getMessage(), ""));
            }
        });
    }

    @Override
    public void endBlobTransfer(BlobHandle handle) {
        PageBinding page = pagesByTabId.get(handle.tabId);
        if (page == null || page.generation != handle.pageGeneration
                || page.navigationGeneration != handle.navigationGeneration
                || !handle.origin.equals(safeOrigin(page.webView.getUrl()))) return;
        try {
            page.webView.evaluateJavascript(
                    "(()=>{try{delete window[" + JSONObject.quote(handle.key)
                            + "];return true;}catch(e){return false;}})()", null);
        } catch (RuntimeException ignored) {
            // A closing page invalidates the one-use Blob state automatically.
        }
    }

    private void evaluateFixed(
            PageBinding page,
            BlobHandle handle,
            String fixedScript,
            ValueCallback<String> callback
    ) {
        requireBlobPage(handle);
        page.webView.evaluateJavascript(fixedScript, callback);
    }

    private PageBinding requireBlobPage(BlobHandle handle) {
        PageBinding page = requireMatchingPage(
                handle.tabId, handle.profileName, handle.origin);
        if (page.generation != handle.pageGeneration) {
            throw new IllegalStateException("blob-source-page-replaced");
        }
        if (page.navigationGeneration != handle.navigationGeneration) {
            throw new IllegalStateException("blob-source-page-changed");
        }
        return page;
    }

    private PageBinding requireMatchingPage(
            String tabId,
            String profileName,
            String origin
    ) {
        PageBinding page = requirePage(tabId);
        if (!page.profileName.equals(profileName)) {
            throw new IllegalStateException("profile-mismatch");
        }
        if (!origin.equals(safeOrigin(page.webView.getUrl()))) {
            throw new IllegalStateException("origin-changed");
        }
        return page;
    }

    private PageBinding requirePage(String tabId) {
        PageBinding page = pagesByTabId.get(tabId);
        if (page == null) throw new IllegalStateException("page-unavailable");
        return page;
    }

    private PageBinding visiblePage() {
        for (PageBinding page : pagesByTabId.values()) {
            if (page.webView.getParent() == container) return page;
        }
        return null;
    }

    private static void rememberRequestMethod(
            PageBinding page,
            String url,
            String method
    ) {
        String normalized = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if (url == null || url.isBlank()) return;
        synchronized (page.requestMethods) {
            page.requestMethods.put(url,
                    new RequestMethodObservation(normalized, System.currentTimeMillis()));
            while (page.requestMethods.size() > 32) {
                String oldest = page.requestMethods.keySet().iterator().next();
                page.requestMethods.remove(oldest);
            }
        }
    }

    private static String observedRequestMethod(PageBinding page, String url) {
        synchronized (page.requestMethods) {
            RequestMethodObservation observation = page.requestMethods.remove(url);
            if (observation == null
                    || observation.observedAt < System.currentTimeMillis() - 120_000L) {
                return "";
            }
            return observation.method;
        }
    }

    private void dispatchDownload(
            PageBinding page,
            String url,
            String userAgent,
            String disposition,
            String mimeType,
            long contentLength
    ) {
        if (pagesByTabId.get(page.tab.id) != page) return;
        String sourceUrl = page.webView.getUrl() == null ? page.tab.url : page.webView.getUrl();
        listener.onDownloadRequested(new DownloadRequest(
                page.workspaceId, page.tab.id, page.profileName,
                sourceUrl, safeOrigin(sourceUrl), url, observedRequestMethod(page, url),
                userAgent, disposition, mimeType, contentLength));
    }

    private void handleMediaPermissionRequest(
            PageBinding page,
            PermissionRequest nativeRequest
    ) {
        String origin = ReBrowserSitePermissions.normalizeOrigin(
                nativeRequest.getOrigin().toString());
        if (!foreground || !isCurrentVisiblePage(page) || origin.isEmpty()
                || !origin.equals(safeOrigin(page.webView.getUrl()))) {
            nativeRequest.deny();
            return;
        }
        List<String> permissions = new ArrayList<>();
        List<String> androidPermissions = new ArrayList<>();
        List<String> grantedResources = new ArrayList<>();
        for (String resource : nativeRequest.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && sitePermissions.capabilityEnabled(ReBrowserSitePermissions.CAMERA)) {
                permissions.add(ReBrowserSitePermissions.CAMERA);
                androidPermissions.add(Manifest.permission.CAMERA);
                grantedResources.add(resource);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && sitePermissions.capabilityEnabled(ReBrowserSitePermissions.MICROPHONE)) {
                permissions.add(ReBrowserSitePermissions.MICROPHONE);
                androidPermissions.add(Manifest.permission.RECORD_AUDIO);
                grantedResources.add(resource);
            }
        }
        if (permissions.isEmpty()) {
            nativeRequest.deny();
            return;
        }
        for (String permission : permissions) {
            if (page.permissionPromptBlockedUntil.getOrDefault(permission, 0L)
                    > SystemClock.elapsedRealtime()) {
                nativeRequest.deny();
                return;
            }
        }
        long now = System.currentTimeMillis();
        boolean siteGranted = true;
        for (String permission : permissions) {
            siteGranted &= sitePermissions.hasGrant(
                    page.profileName, origin, permission, now);
        }
        if (siteGranted && androidPermissionsGranted(androidPermissions)) {
            page.sensitiveMediaActive = true;
            nativeRequest.grant(grantedResources.toArray(new String[0]));
            return;
        }
        Runnable grant = () -> {
            mediaRequests.remove(nativeRequest);
            if (!foreground || !isCurrentVisiblePage(page)) {
                nativeRequest.deny();
                return;
            }
            page.sensitiveMediaActive = true;
            nativeRequest.grant(grantedResources.toArray(new String[0]));
        };
        Runnable deny = () -> {
            mediaRequests.remove(nativeRequest);
            try {
                nativeRequest.deny();
            } catch (RuntimeException ignored) {
                // WebView can cancel a request while the browser prompt is visible.
            }
        };
        ReBrowserPermissionRequest request = createPermissionRequest(
                page, origin, permissions, 0L, ReBrowserSitePermissions.THREE_HOURS_MS,
                "仅本次", "允许 3 小时", androidPermissions, grant, deny);
        mediaRequests.put(nativeRequest, request);
        listener.onSitePermissionRequested(request);
    }

    private void readLocation(
            PageBinding page,
            String origin,
            String id,
            boolean precise,
            int timeoutMs,
            JavaScriptReplyProxy reply
    ) {
        if (!foreground || !isCurrentVisiblePage(page)
                || !origin.equals(safeOrigin(page.webView.getUrl()))) {
            replyError(reply, id, "page-not-eligible");
            return;
        }
        LocationManager manager = (LocationManager)
                activity.getSystemService(Activity.LOCATION_SERVICE);
        String provider = null;
        try {
            if (precise && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                provider = LocationManager.GPS_PROVIDER;
            } else if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                provider = LocationManager.NETWORK_PROVIDER;
            } else if (manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                provider = LocationManager.PASSIVE_PROVIDER;
            }
        } catch (RuntimeException ignored) {
            // Provider lookup failure is reported as a bounded website error below.
        }
        if (provider == null) {
            replyError(reply, id, "location-provider-unavailable");
            return;
        }
        AtomicBoolean completed = new AtomicBoolean();
        String selectedProvider = provider;
        LocationListener locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (!completed.compareAndSet(false, true)) return;
                manager.removeUpdates(this);
                if (!foreground || !isCurrentVisiblePage(page)
                        || !origin.equals(safeOrigin(page.webView.getUrl()))) {
                    replyError(reply, id, "page-not-eligible");
                    return;
                }
                replyValue(reply, id, locationValue(location, precise));
            }

            @Override
            public void onProviderDisabled(String providerName) {
                if (!selectedProvider.equals(providerName)
                        || !completed.compareAndSet(false, true)) return;
                manager.removeUpdates(this);
                replyError(reply, id, "location-provider-disabled");
            }
        };
        handler.postDelayed(() -> {
            if (!completed.compareAndSet(false, true)) return;
            manager.removeUpdates(locationListener);
            replyError(reply, id, "location-timeout");
        }, timeoutMs);
        try {
            manager.requestSingleUpdate(provider, locationListener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException error) {
            if (completed.compareAndSet(false, true)) {
                manager.removeUpdates(locationListener);
                replyError(reply, id, "location-unavailable");
            }
        }
    }

    private static JSONObject locationValue(Location location, boolean precise) {
        try {
            double latitude = location.getLatitude();
            double longitude = location.getLongitude();
            float accuracy = location.hasAccuracy() ? location.getAccuracy() : 5_000f;
            if (!precise) {
                double step = 0.02d;
                latitude = Math.round(latitude / step) * step;
                longitude = Math.round(longitude / step) * step;
                accuracy = Math.max(accuracy, 2_000f);
            }
            JSONObject value = new JSONObject()
                    .put("latitude", latitude)
                    .put("longitude", longitude)
                    .put("accuracy", accuracy)
                    .put("timestamp", location.getTime() > 0L
                            ? location.getTime() : System.currentTimeMillis());
            if (precise) {
                value.put("altitude", location.hasAltitude()
                        ? location.getAltitude() : JSONObject.NULL);
                value.put("altitudeAccuracy", location.hasVerticalAccuracy()
                        ? location.getVerticalAccuracyMeters() : JSONObject.NULL);
                value.put("heading", location.hasBearing()
                        ? location.getBearing() : JSONObject.NULL);
                value.put("speed", location.hasSpeed()
                        ? location.getSpeed() : JSONObject.NULL);
            } else {
                value.put("altitude", JSONObject.NULL)
                        .put("altitudeAccuracy", JSONObject.NULL)
                        .put("heading", JSONObject.NULL)
                        .put("speed", JSONObject.NULL);
            }
            return value;
        } catch (Exception impossible) {
            return new JSONObject();
        }
    }

    private static void replyValue(JavaScriptReplyProxy reply, String id, Object value) {
        try {
            reply.postMessage(new JSONObject()
                    .put("id", id).put("ok", true).put("value", value).toString());
        } catch (Exception ignored) {
            // A navigated or destroyed page invalidates its reply channel.
        }
    }

    private static void replyError(JavaScriptReplyProxy reply, String id, String message) {
        try {
            reply.postMessage(new JSONObject()
                    .put("id", id).put("ok", false).put("message", message).toString());
        } catch (Exception ignored) {
            // A navigated or destroyed page invalidates its reply channel.
        }
    }

    private final class BrowserClient extends WebViewClient {
        private final PageBinding binding;

        BrowserClient(PageBinding binding) {
            this.binding = binding;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            PageBinding page = pagesByView.get(view);
            if (page == null) return;
            cancelPermissionRequestsForTab(page.tab.id, "page-navigated");
            cancelFileChooserForTab(page.tab.id);
            page.permissionPromptBlockedUntil.clear();
            page.navigationGeneration++;
            page.loading = true;
            page.loadProgress = 5;
            page.lastMainFrameError = "";
            listener.onPageStarted(page.tab, url, view.getParent() == container);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            PageBinding page = pagesByView.get(view);
            if (page == null) return;
            page.loading = false;
            page.loadProgress = 100;
            listener.onPageFinished(
                    page.tab, url, view.getTitle(), view.getParent() == container);
        }

        @Override
        public android.webkit.WebResourceResponse shouldInterceptRequest(
                WebView view,
                WebResourceRequest request
        ) {
            PageBinding page = binding;
            if (request != null) {
                rememberRequestMethod(page, request.getUrl().toString(), request.getMethod());
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? ""
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme)) return false;
            if (!request.isForMainFrame() || !request.hasGesture()) {
                listener.onExternalNavigationBlocked("已阻止网页自动唤起外部应用");
                return true;
            }
            if (!("mailto".equals(scheme) || "tel".equals(scheme)
                    || "sms".equals(scheme) || "geo".equals(scheme))) {
                listener.onExternalNavigationBlocked("已阻止不受支持的外部链接协议");
                return true;
            }
            listener.onExternalNavigationRequested(uri, scheme);
            return true;
        }

        @Override
        public void onReceivedError(
                WebView view,
                WebResourceRequest request,
                WebResourceError error
        ) {
            if (!request.isForMainFrame()) return;
            PageBinding page = pagesByView.get(view);
            if (page == null) return;
            page.loading = false;
            String detail = error.getErrorCode() + ":" + error.getDescription();
            page.lastMainFrameError = detail.substring(0, Math.min(detail.length(), 200));
            listener.onMainFrameError(page.tab, error.getErrorCode(),
                    String.valueOf(error.getDescription()), view.getParent() == container);
        }
    }

    private final class ChromeClient extends WebChromeClient {
        private final PageBinding binding;

        ChromeClient(PageBinding binding) {
            this.binding = binding;
        }

        @Override
        public void onShowCustomView(android.view.View view, CustomViewCallback callback) {
            fullscreenController.show(view, callback);
        }

        @Override
        public void onHideCustomView() {
            fullscreenController.hide();
        }

        @Override
        public void onProgressChanged(WebView view, int progress) {
            PageBinding page = pagesByView.get(view);
            if (page != null) {
                page.loadProgress = progress;
                listener.onProgressChanged(
                        page.tab, progress, view.getParent() == container);
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            PageBinding page = pagesByView.get(view);
            if (page != null) listener.onTitleReceived(
                    page.tab, title, view.getParent() == container);
        }

        @Override
        public boolean onCreateWindow(
                WebView view,
                boolean isDialog,
                boolean isUserGesture,
                Message resultMsg
        ) {
            PageBinding source = pagesByView.get(view);
            if (!isUserGesture || source == null) return false;
            PopupTarget target = listener.onCreatePopup(source.tab);
            if (target == null || target.tab == null) return false;
            try {
                PageBinding popup = createPage(
                        target.workspaceId, target.profileName, target.tab);
                showPage(popup, false);
                WebView.WebViewTransport transport =
                        (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup.webView);
                resultMsg.sendToTarget();
                listener.onPopupCreated(target.tab);
                return true;
            } catch (Throwable error) {
                listener.onPopupCreationFailed(target.tab, error);
                return false;
            }
        }

        @Override
        public void onCloseWindow(WebView window) {
            PageBinding page = pagesByView.get(window);
            if (page != null) listener.onCloseRequested(page.tab);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            activity.runOnUiThread(() -> handleMediaPermissionRequest(binding, request));
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            activity.runOnUiThread(() -> {
                ReBrowserPermissionRequest pending = mediaRequests.remove(request);
                if (pending != null) pending.deny("webview-cancelled");
            });
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(
                String origin,
                android.webkit.GeolocationPermissions.Callback callback
        ) {
            callback.invoke(origin, false, false);
        }

        @Override
        public boolean onShowFileChooser(
                WebView webView,
                ValueCallback<Uri[]> callback,
                FileChooserParams params
        ) {
            if (!foreground || !isCurrentVisiblePage(binding)) {
                callback.onReceiveValue(null);
                return true;
            }
            if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
            pendingFileChooser = callback;
            pendingFileChooserTabId = binding.tab.id;
            pendingFileChooserGeneration = binding.generation;
            try {
                activity.startActivityForResult(params.createIntent(), fileChooserRequestCode);
                return true;
            } catch (ActivityNotFoundException error) {
                cancelPendingFileChooser();
                listener.onFileChooserUnavailable();
                return false;
            }
        }
    }

    private static Boolean decodeBoolean(String result) {
        return "true".equals(result) ? Boolean.TRUE : Boolean.FALSE;
    }

    private static String decodeString(String result) throws Exception {
        Object decoded = new JSONTokener(result == null ? "null" : result).nextValue();
        if (!(decoded instanceof String)) throw new IllegalStateException("javascript-no-result");
        return (String) decoded;
    }

    static String safeOrigin(String value) {
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return "";
            return scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT)
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
