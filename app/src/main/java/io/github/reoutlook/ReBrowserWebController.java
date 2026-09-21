package io.github.reoutlook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Message;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        boolean loading;
        int loadProgress;
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

    private final Activity activity;
    private final ReBrowserPreferences preferences;
    private final ReBrowserFullscreenController fullscreenController;
    private final Listener listener;
    private final int fileChooserRequestCode;
    private final Map<String, PageBinding> pagesByTabId = new HashMap<>();
    private final Map<WebView, PageBinding> pagesByView = new IdentityHashMap<>();
    private FrameLayout container;
    private ValueCallback<Uri[]> pendingFileChooser;
    private long nextGeneration = 1L;

    ReBrowserWebController(
            Activity activity,
            ReBrowserPreferences preferences,
            ReBrowserFullscreenController fullscreenController,
            Listener listener,
            int fileChooserRequestCode
    ) {
        this.activity = activity;
        this.preferences = preferences;
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
            ((WebView) previous).onPause();
        }
        container.removeAllViews();
        if (page.webView.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) page.webView.getParent()).removeView(page.webView);
        }
        container.addView(page.webView, ReBrowserUi.matchMatch());
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
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        webView.setWebViewClient(new BrowserClient());
        webView.setWebChromeClient(new ChromeClient());
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

    boolean isVisible(String tabId) {
        PageBinding page = pagesByTabId.get(tabId);
        return page != null && page.webView.getParent() == container;
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
        pagesByView.remove(page.webView);
        destroyPage(page);
    }

    void destroyAll() {
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
        applyPreferencesToAll();
        PageBinding visible = visiblePage();
        if (visible != null) visible.webView.onResume();
    }

    void onPause() {
        for (PageBinding page : pagesByTabId.values()) page.webView.onPause();
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != fileChooserRequestCode || pendingFileChooser == null) return;
        Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        pendingFileChooser.onReceiveValue(result);
        pendingFileChooser = null;
    }

    void cancelPendingFileChooser() {
        if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
        pendingFileChooser = null;
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
                sourceUrl, safeOrigin(sourceUrl), url, userAgent,
                disposition, mimeType, contentLength));
    }

    private final class BrowserClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            PageBinding page = pagesByView.get(view);
            if (page == null) return;
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
        public boolean onShowFileChooser(
                WebView webView,
                ValueCallback<Uri[]> callback,
                FileChooserParams params
        ) {
            if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
            pendingFileChooser = callback;
            try {
                activity.startActivityForResult(params.createIntent(), fileChooserRequestCode);
                return true;
            } catch (ActivityNotFoundException error) {
                pendingFileChooser = null;
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
