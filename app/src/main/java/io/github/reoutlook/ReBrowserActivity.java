package io.github.reoutlook;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.os.SystemClock;
import android.os.StatFs;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Multi-Profile browser workspace MVP. ReOutlook's Default Profile is never used here. */
@SuppressLint({"ClickableViewAccessibility", "RequiresFeature", "SetJavaScriptEnabled", "SetTextI18n", "UnsafeOptInUsageError"})
public final class ReBrowserActivity extends Activity {
    static final String ACTION_ADMIN_COMMAND = "io.github.reoutlook.action.REBROWSER_ADMIN";
    static final String EXTRA_ADMIN_REQUEST = "adminRequest";
    private static final int FILE_CHOOSER_REQUEST = 5102;
    private static final int DOWNLOAD_STORAGE_REQUEST = 5103;
    private static final int MAX_PENDING_DOWNLOADS = 32;
    private static final int MAX_PENDING_DOWNLOADS_PER_SITE = 8;
    private static final long DOUBLE_BACK_INTERVAL_MS = 2_000L;

    private List<ReBrowserStore.Workspace> workspaces = List.of();
    private List<ReBrowserStore.Workspace> shelvedSecondaryWorkspaces = List.of();
    private final List<ReBrowserFavorites.Favorite> bookmarks = new ArrayList<>();
    private final List<ReBrowserDownloads.Record> downloads = new ArrayList<>();
    private final Map<String, WebView> tabWebViews = new HashMap<>();
    private final Map<WebView, ReBrowserStore.Tab> webViewTabs = new IdentityHashMap<>();
    private final Set<String> loadedTabIds = new java.util.HashSet<>();
    private final Set<String> selectedWorkspaceIds = new java.util.HashSet<>();
    private final Set<String> selectedChildTabIds = new java.util.HashSet<>();
    private final Map<String, Integer> tabLoadProgress = new HashMap<>();
    private final Map<String, String> tabLastErrors = new HashMap<>();
    private final Set<String> loadingTabIds = new java.util.HashSet<>();
    private final Set<String> hashingDownloadIds = new java.util.HashSet<>();
    private final Set<String> customDownloadIds = new java.util.HashSet<>();
    private final Map<String, JSONObject> pendingAdminLoads = new HashMap<>();

    private ReBrowserWorkspaceController workspaceController;
    private ReBrowserPreferences browserPreferences;
    private ReBrowserFavorites bookmarkStore;
    private ReBrowserDownloads downloadStore;
    private FrameLayout root;
    private FrameLayout webContainer;
    private LinearLayout browserToolbar;
    private EditText omnibox;
    private TextView workspaceCountButton;
    private ProgressBar progressBar;
    private FrameLayout overviewContainer;
    private boolean workspaceOverviewVisible;
    private boolean childOverviewVisible;
    private boolean bookmarkOverviewVisible;
    private boolean workspaceBookmarkOverviewVisible;
    private boolean downloadOverviewVisible;
    private String awaitingStorageDownloadId;
    private BlobTransfer activeBlobTransfer;
    private final Runnable downloadRefreshRunnable = () -> {
        if (!downloadOverviewVisible || root == null) return;
        refreshDownloadStates();
        showDownloadOverview();
    };
    private boolean workspaceSelectionMode;
    private boolean childSelectionMode;
    private long lastBackPressAt;
    private ValueCallback<Uri[]> pendingFileChooser;
    private ReBrowserFullscreenController fullscreenController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBackDispatcher.register(this, this::handleSystemBack);
        workspaceController = new ReBrowserWorkspaceController(new ReBrowserStore(this));
        workspaces = workspaceController.activeWorkspaces();
        shelvedSecondaryWorkspaces = workspaceController.shelvedWorkspaces();
        browserPreferences = new ReBrowserPreferences(this);
        fullscreenController = new ReBrowserFullscreenController(this, browserPreferences);
        fullscreenController.applyGlobalOrientationPreference();
        bookmarkStore = new ReBrowserFavorites(this);
        bookmarks.addAll(bookmarkStore.load());
        downloadStore = new ReBrowserDownloads(this);
        downloads.addAll(downloadStore.load());
        refreshDownloadStates();
        root = createRoot();
        fullscreenController.attachStyledRoot(root);
        setContentView(root);

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            showUnsupportedProvider();
            handleAdminCommand(getIntent());
            return;
        }

        deletePendingProfiles();
        workspaceController.initialize(browserPreferences.homeUrl());
        activateWorkspace(activeWorkspace());
        handleAdminCommand(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAdminCommand(intent);
    }

    private FrameLayout createRoot() {
        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Color.rgb(247, 248, 252));
        WindowStyling.apply(this, content);

        webContainer = new FrameLayout(this);
        FrameLayout.LayoutParams webParams = matchMatch();
        webParams.topMargin = dp(66);
        content.addView(webContainer, webParams);

        browserToolbar = createBrowserToolbar();
        content.addView(browserToolbar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66), Gravity.TOP));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.TOP);
        progressParams.topMargin = dp(64);
        content.addView(progressBar, progressParams);

        overviewContainer = new FrameLayout(this);
        overviewContainer.setVisibility(View.GONE);
        overviewContainer.setElevation(dp(24));
        overviewContainer.setBackgroundColor(Color.rgb(245, 247, 251));
        content.addView(overviewContainer, matchMatch());
        return content;
    }

    private LinearLayout createBrowserToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), dp(6), dp(6), dp(6));
        toolbar.setBackgroundColor(Color.WHITE);
        toolbar.setElevation(dp(3));

        TextView home = toolbarButton("⌂", "打开当前子标签页的主页");
        home.setOnClickListener(view -> navigateActiveTab(browserPreferences.homeUrl()));
        toolbar.addView(home, new LinearLayout.LayoutParams(dp(46), dp(50)));

        omnibox = new EditText(this);
        omnibox.setSingleLine(true);
        omnibox.setTextSize(14);
        omnibox.setTextColor(Color.rgb(34, 39, 48));
        omnibox.setHintTextColor(Color.rgb(100, 107, 118));
        omnibox.setHint("搜索或输入网址");
        omnibox.setPadding(dp(16), 0, dp(12), 0);
        omnibox.setSelectAllOnFocus(true);
        omnibox.setImeOptions(EditorInfo.IME_ACTION_GO);
        omnibox.setBackground(roundedBackground(Color.rgb(239, 241, 246), dp(24)));
        omnibox.setOnEditorActionListener((view, actionId, event) -> {
            navigateActiveTab(omnibox.getText().toString());
            omnibox.clearFocus();
            InputMethodManager keyboard = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            keyboard.hideSoftInputFromWindow(omnibox.getWindowToken(), 0);
            return true;
        });
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        addressParams.setMargins(dp(2), 0, dp(3), 0);
        toolbar.addView(omnibox, addressParams);

        TextView addChildTab = toolbarButton("＋", "新建并打开子标签页");
        addChildTab.setTextSize(27);
        addChildTab.setOnClickListener(view -> createChildTab(true));
        toolbar.addView(addChildTab, new LinearLayout.LayoutParams(dp(40), dp(48)));

        workspaceCountButton = toolbarButton("1", "管理总标签页");
        GradientDrawable counter = roundedBackground(Color.TRANSPARENT, dp(8));
        counter.setStroke(dp(2), Color.rgb(68, 73, 82));
        workspaceCountButton.setBackground(counter);
        workspaceCountButton.setOnClickListener(view -> showWorkspaceOverview());
        LinearLayout.LayoutParams counterParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        counterParams.setMargins(0, 0, dp(3), 0);
        toolbar.addView(workspaceCountButton, counterParams);

        TextView menu = toolbarButton("⋮", "ReBrowser 菜单");
        menu.setTextSize(25);
        menu.setOnClickListener(this::showBrowserMenu);
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(42), dp(50)));
        return toolbar;
    }

    private void openWorkspaceBookmark(ReBrowserStore.Workspace workspace, boolean shelved) {
        if (shelved && !workspaceController.restoreSecondary(workspace)) {
            toast("无法恢复副总标签页");
            return;
        }
        hideOverview();
        activateWorkspace(workspace);
    }

    private void confirmDeleteShelvedWorkspace(ReBrowserStore.Workspace workspace) {
        new AlertDialog.Builder(this)
                .setTitle("取消上锁并删除？")
                .setMessage("此副总标签页已经收起。取消上锁会将“" + workspace.title
                        + "”移出书签栏；由于它当前已关闭，其子标签页和网站 Profile 将被清除。")
                .setPositiveButton("移出并删除", (dialog, which) -> {
                    workspaceController.deleteShelvedSecondary(workspace);
                    deleteProfileIfPossible(workspace.profileName);
                    if (workspaceBookmarkOverviewVisible) showWorkspaceBookmarkOverview();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private TextView toolbarButton(String text, String description) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(19);
        button.setTextColor(Color.rgb(49, 53, 61));
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setBackgroundResource(android.R.drawable.list_selector_background);
        return button;
    }

    private GradientDrawable roundedBackground(int color, int radius) {
        return ReBrowserUi.roundedBackground(color, radius);
    }

    private void updateChromeUi() {
        if (activeWorkspace() == null) return;
        ReBrowserStore.Tab activeTab = activeWorkspace().activeTab();
        workspaceCountButton.setText(Integer.toString(workspaces.size()));
        workspaceCountButton.setContentDescription(
                workspaces.size() + " 个总标签页；管理总标签页");
        if (!omnibox.hasFocus() && activeTab != null) omnibox.setText(activeTab.url);
    }

    private void activateWorkspace(ReBrowserStore.Workspace workspace) {
        if (workspace == activeWorkspace() && !tabWebViews.isEmpty()) return;
        if (activeWorkspace() != null && workspace != activeWorkspace()) {
            for (ReBrowserStore.Tab tab : activeWorkspace().tabs) {
                JSONObject pending = pendingAdminLoads.remove(tab.id);
                if (pending != null) {
                    recordAdminStatus(pending, "failed", null, "workspace-deactivated");
                }
            }
        }
        saveCurrentTabStates();
        destroyTabWebViews();
        if (!workspaceController.activate(workspace)) return;
        ReBrowserStore.Tab tab = workspace.activeTab();
        if (tab == null) tab = workspaceController.addTab(
                workspace, browserPreferences.homeUrl());
        if (tab != null) showTab(tab);
        saveWorkspaceMetadata();
        updateChromeUi();
    }

    private void showTab(ReBrowserStore.Tab tab) {
        if (activeWorkspace() == null || !activeWorkspace().tabs.contains(tab)) return;
        saveVisibleTabState();
        workspaceController.selectTab(activeWorkspace(), tab);
        WebView webView = tabWebViews.get(tab.id);
        if (webView == null) {
            try {
                webView = createBrowserWebView(activeWorkspace().profileName, tab);
            } catch (Throwable error) {
                showProfileFailure(error);
                return;
            }
            tabWebViews.put(tab.id, webView);
        }
        View previous = webContainer.getChildCount() == 0 ? null : webContainer.getChildAt(0);
        if (previous instanceof WebView && previous != webView) ((WebView) previous).onPause();
        webContainer.removeAllViews();
        if (webView.getParent() instanceof ViewGroup) {
            ((ViewGroup) webView.getParent()).removeView(webView);
        }
        webContainer.addView(webView, matchMatch());
        if (loadedTabIds.add(tab.id)) webView.loadUrl(tab.url);
        webView.onResume();
        saveWorkspaceMetadata();
        updateChromeUi();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createBrowserWebView(String profileName, ReBrowserStore.Tab tab) {
        if (!ReBrowserStore.isOwnedProfile(profileName)) {
            throw new IllegalArgumentException("拒绝使用非 ReBrowser Profile");
        }
        WebView webView = new WebView(this);
        try {
            // Required invariant: no WebView operation may happen before the named Profile is set.
            WebViewCompat.setProfile(webView, profileName);
        } catch (Throwable error) {
            webView.destroy();
            throw error;
        }
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(browserPreferences.javascriptEnabled());
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
        webView.setWebViewClient(new WorkspaceWebViewClient());
        webView.setWebChromeClient(new WorkspaceChromeClient());
        webView.setDownloadListener(new WorkspaceDownloadListener(webView, profileName));
        applyBrowserPreferences(webView);
        webViewTabs.put(webView, tab);
        return webView;
    }

    private void applyBrowserPreferences(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(browserPreferences.javascriptEnabled());
        settings.setUseWideViewPort(browserPreferences.desktopModeEnabled());
        settings.setLoadWithOverviewMode(browserPreferences.desktopModeEnabled());
        settings.setUserAgentString(browserPreferences.desktopModeEnabled()
                ? desktopUserAgent() : null);
        WebViewCompat.getProfile(webView).getCookieManager().setAcceptThirdPartyCookies(
                webView, browserPreferences.thirdPartyCookiesEnabled());
    }

    private String desktopUserAgent() {
        String defaultAgent = WebSettings.getDefaultUserAgent(this);
        return defaultAgent.replaceFirst("\\(Linux; Android[^)]*\\)", "(X11; Linux x86_64)")
                .replace("; wv", "")
                .replace(" Mobile", "");
    }

    private void navigateActiveTab(String input) {
        ReBrowserStore.Tab tab = activeWorkspace() == null ? null : activeWorkspace().activeTab();
        if (tab == null) return;
        String url = normalizeAddress(input);
        workspaceController.updateTab(tab, url, null);
        WebView webView = tabWebViews.get(tab.id);
        if (webView == null) showTab(tab);
        else webView.loadUrl(url);
        loadedTabIds.add(tab.id);
        saveWorkspaceMetadata();
        omnibox.setText(url);
    }

    private void toggleCurrentBookmark() {
        ReBrowserStore.Tab tab = activeWorkspace() == null ? null : activeWorkspace().activeTab();
        if (tab == null) return;
        WebView webView = activeWebView();
        String url = webView == null || webView.getUrl() == null ? tab.url : webView.getUrl();
        ReBrowserFavorites.Favorite existing = bookmarkStore.findByUrl(bookmarks, url);
        if (existing == null) {
            addCurrentBookmark();
        } else {
            bookmarkStore.remove(bookmarks, existing);
            toast("已取消收藏");
        }
    }

    private void addCurrentBookmark() {
        ReBrowserStore.Tab tab = activeWorkspace() == null ? null : activeWorkspace().activeTab();
        if (tab == null) return;
        WebView webView = activeWebView();
        String url = webView == null || webView.getUrl() == null ? tab.url : webView.getUrl();
        String title = webView == null || webView.getTitle() == null
                ? tab.title : webView.getTitle();
        ReBrowserFavorites.Favorite existing = bookmarkStore.findByUrl(bookmarks, url);
        if (existing != null) {
            toast("当前网页已收藏");
            return;
        }
        ReBrowserFavorites.Favorite added = bookmarkStore.add(bookmarks, title, url);
        if (added == null) {
            toast(bookmarks.size() >= ReBrowserFavorites.MAX_FAVORITES
                    ? "收藏数量已达到上限" : "当前页面不能收藏");
            return;
        }
        if (bookmarkOverviewVisible) showBookmarkOverview();
        toast("已添加到收藏栏");
    }

    private void showEditBookmarkDialog(ReBrowserFavorites.Favorite bookmark) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), 0, dp(20), 0);
        EditText title = new EditText(this);
        title.setHint("标题");
        title.setSingleLine(true);
        title.setText(bookmark.title);
        EditText url = new EditText(this);
        url.setHint("https://…");
        url.setSingleLine(true);
        url.setText(bookmark.url);
        fields.addView(title, matchWrap());
        fields.addView(url, matchWrap());
        new AlertDialog.Builder(this)
                .setTitle("编辑收藏")
                .setView(fields)
                .setPositiveButton("保存", (dialog, which) -> {
                    if (!bookmarkStore.update(bookmarks, bookmark,
                            title.getText().toString(), url.getText().toString())) {
                        toast("网址无效或已经收藏");
                    }
                    showBookmarkOverview();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemoveBookmark(ReBrowserFavorites.Favorite bookmark) {
        new AlertDialog.Builder(this)
                .setTitle("删除收藏？")
                .setMessage(bookmark.title + "\n" + displayUrl(bookmark.url))
                .setPositiveButton("删除", (dialog, which) -> {
                    bookmarkStore.remove(bookmarks, bookmark);
                    if (bookmarkOverviewVisible) showBookmarkOverview();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createChildTab(boolean activate) {
        if (activeWorkspace() == null) return;
        ReBrowserStore.Tab tab = workspaceController.addTab(
                activeWorkspace(), browserPreferences.homeUrl());
        if (tab == null) {
            toast("每个总标签页最多 50 个子标签页");
            return;
        }
        saveWorkspaceMetadata();
        if (activate) {
            hideOverview();
            showTab(tab);
        } else {
            updateChromeUi();
        }
    }

    private void createTemporaryWorkspace() {
        ReBrowserStore.Workspace workspace = workspaceController.createTemporary(
                browserPreferences.homeUrl());
        if (workspace == null) {
            toast("当前与书签栏中的总标签页数量已达到上限");
            return;
        }
        hideOverview();
        activateWorkspace(workspace);
    }

    private void handleAdminCommand(Intent intent) {
        if (intent == null || !ACTION_ADMIN_COMMAND.equals(intent.getAction())) return;
        intent.setAction(null); // A configuration change must not replay an authorized command.
        JSONObject request = null;
        try {
            request = new JSONObject(intent.getStringExtra(EXTRA_ADMIN_REQUEST));
            ReBrowserAdminProtocol.prepareRequest(request);
            recordAdminStatus(request, "running", null, null);
            JSONObject details = executeAdminRequest(request);
            if (details != null) recordAdminStatus(request, "completed", details, null);
        } catch (Exception error) {
            if (request != null) {
                recordAdminStatus(request, "failed", null,
                        error.getClass().getSimpleName() + ":" + error.getMessage());
            }
        }
    }

    private JSONObject executeAdminRequest(JSONObject request) throws Exception {
        String operation = request.getString("operation");
        if (ReBrowserAdminProtocol.OP_GET_CAPABILITIES.equals(operation)) {
            return createAdminCapabilities();
        }
        if (ReBrowserAdminProtocol.OP_GET_DIAGNOSTICS.equals(operation)) {
            return createAdminDiagnostics();
        }
        if (ReBrowserAdminProtocol.OP_GET_DOWNLOAD_POLICY.equals(operation)) {
            return createAdminDownloadPolicy();
        }
        if (ReBrowserAdminProtocol.OP_SET_DOWNLOAD_POLICY.equals(operation)) {
            return setAdminDownloadPolicy(request);
        }
        if (ReBrowserAdminProtocol.OP_GET_DOWNLOADS.equals(operation)) {
            return createAdminDownloads();
        }
        if (activeWorkspace() == null) throw new IllegalStateException("ReBrowser-disabled");
        if (ReBrowserAdminProtocol.OP_GET_STATE.equals(operation)) return createAdminState();
        if (ReBrowserAdminProtocol.OP_VALIDATE_STATE.equals(operation)) return validateAdminState();
        if (ReBrowserAdminProtocol.OP_REPAIR_STATE.equals(operation)) return repairAdminState();
        if (ReBrowserAdminProtocol.OP_NEW_WORKSPACE.equals(operation)) {
            ReBrowserStore.Workspace workspace = workspaceController.createTemporary(
                    browserPreferences.homeUrl());
            if (workspace == null) throw new IllegalStateException("workspace-limit-reached");
            hideOverview();
            activateWorkspace(workspace);
            return adminTargetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_NEW_CHILD_TAB.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            activateWorkspace(workspace);
            ReBrowserStore.Tab tab = workspaceController.addTab(
                    workspace, browserPreferences.homeUrl());
            if (tab == null) throw new IllegalStateException("child-tab-limit-reached");
            hideOverview();
            showTab(tab);
            return adminTargetDetails(workspace, tab);
        }
        if (ReBrowserAdminProtocol.OP_SHOW_WORKSPACES.equals(operation)) {
            showWorkspaceOverview();
            return new JSONObject().put("view", "workspaces");
        }
        if (ReBrowserAdminProtocol.OP_SHOW_CHILD_TABS.equals(operation)) {
            showChildOverview();
            return new JSONObject().put("view", "tabs");
        }
        if (ReBrowserAdminProtocol.OP_OPEN_SETTINGS.equals(operation)) {
            startActivity(new Intent(this, ReBrowserSettingsActivity.class));
            return new JSONObject().put("view", "settings");
        }
        if (ReBrowserAdminProtocol.OP_SHOW_DOWNLOADS.equals(operation)) {
            showDownloadOverview();
            return new JSONObject().put("view", "downloads");
        }
        if (ReBrowserAdminProtocol.OP_ACTIVATE_WORKSPACE.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            hideOverview();
            activateWorkspace(workspace);
            return adminTargetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_ACTIVATE_TAB.equals(operation)) {
            return activateAdminTarget(request);
        }
        if (ReBrowserAdminProtocol.OP_SET_PREFERENCE.equals(operation)) {
            return setAdminPreference(request);
        }
        if (ReBrowserAdminProtocol.OP_ASSERT_LOCATION.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            ReBrowserStore.Tab tab = requireAdminTab(workspace, request);
            WebView webView = tabWebViews.get(tab.id);
            String actual = webView != null && webView.getUrl() != null
                    ? webView.getUrl() : tab.url;
            return adminTargetDetails(workspace, tab)
                    .put("matches", samePage(actual, request.getString("url")))
                    .put("origin", safeOrigin(actual));
        }
        if (isAdminNavigationOperation(operation)) return executeAdminNavigation(request);
        if (ReBrowserAdminProtocol.OP_LOCK_SECONDARY.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            if (!workspaceController.lockAsSecondary(workspace)) {
                throw new IllegalStateException("workspace-cannot-lock");
            }
            refreshLifecycleUi();
            return adminTargetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_UNLOCK_TEMPORARY.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            if (!workspaceController.unlockToTemporary(workspace)) {
                throw new IllegalStateException("workspace-cannot-unlock");
            }
            refreshLifecycleUi();
            return adminTargetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_PROMOTE_PRIMARY.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            if (!workspaceController.promoteToPrimary(
                    workspace, browserPreferences.forcePrimaryPromotionEnabled())) {
                throw new IllegalStateException("workspace-cannot-promote");
            }
            refreshLifecycleUi();
            return adminTargetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_DEMOTE_SECONDARY.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            if (!workspaceController.demotePrimaryToSecondary(workspace)) {
                throw new IllegalStateException("workspace-cannot-demote");
            }
            refreshLifecycleUi();
            return adminTargetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_SHELVE_WORKSPACE.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            if (workspace.level != ReBrowserStore.Level.SECONDARY) {
                throw new IllegalStateException("only-secondary-can-shelve");
            }
            String workspaceId = workspace.id;
            closeWorkspace(workspace);
            return new JSONObject().put("workspaceId", workspaceId).put("shelved", true);
        }
        if (ReBrowserAdminProtocol.OP_RESTORE_WORKSPACE.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, true);
            if (!workspaceController.restoreSecondary(workspace)) {
                throw new IllegalStateException("workspace-cannot-restore");
            }
            hideOverview();
            activateWorkspace(workspace);
            return adminTargetDetails(workspace, workspace.activeTab()).put("restored", true);
        }
        if (ReBrowserAdminProtocol.OP_CLOSE_WORKSPACE.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            if (workspace.level == ReBrowserStore.Level.PRIMARY) {
                throw new IllegalStateException("primary-workspace-cannot-close");
            }
            String workspaceId = workspace.id;
            boolean shelved = workspace.level == ReBrowserStore.Level.SECONDARY;
            closeWorkspace(workspace);
            return new JSONObject().put("workspaceId", workspaceId)
                    .put("closed", true).put("shelved", shelved);
        }
        if (ReBrowserAdminProtocol.OP_CLOSE_TAB.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            ReBrowserStore.Tab tab = requireAdminTab(workspace, request);
            activateWorkspace(workspace);
            showTab(tab);
            String tabId = tab.id;
            closeTab(tab);
            return new JSONObject().put("workspaceId", workspace.id)
                    .put("tabId", tabId).put("closed", true);
        }
        if (ReBrowserAdminProtocol.OP_APPROVE_DOWNLOAD.equals(operation)) {
            ReBrowserDownloads.Record record = requireAdminDownload(request);
            approveDownload(record);
            return adminDownloadDetails(record);
        }
        if (ReBrowserAdminProtocol.OP_REJECT_DOWNLOAD.equals(operation)) {
            ReBrowserDownloads.Record record = requireAdminDownload(request);
            rejectDownload(record);
            return adminDownloadDetails(record);
        }
        if (ReBrowserAdminProtocol.OP_CANCEL_DOWNLOAD.equals(operation)) {
            ReBrowserDownloads.Record record = requireAdminDownload(request);
            if (!ReBrowserDownloads.STATUS_DOWNLOADING.equals(record.status)) {
                throw new IllegalStateException("download-not-running");
            }
            cancelDownload(record);
            return adminDownloadDetails(record);
        }
        if (ReBrowserAdminProtocol.OP_RETRY_DOWNLOAD.equals(operation)) {
            ReBrowserDownloads.Record record = requireAdminDownload(request);
            retryDownload(record);
            return adminDownloadDetails(record);
        }
        if (ReBrowserAdminProtocol.OP_DELETE_DOWNLOAD.equals(operation)) {
            ReBrowserDownloads.Record record = requireAdminDownload(request);
            deleteDownloadRecord(record);
            return new JSONObject().put("downloadId", record.id).put("deleted", true);
        }
        if (ReBrowserAdminProtocol.OP_CLEAR_DOWNLOADS.equals(operation)) {
            int count = downloads.size();
            for (ReBrowserDownloads.Record record : new ArrayList<>(downloads)) {
                if (activeBlobTransfer != null && activeBlobTransfer.record == record) {
                    failBlobTransfer(activeBlobTransfer, "administrator-cleared-downloads");
                }
                downloadStore.delete(record);
            }
            downloads.clear();
            downloadStore.save(downloads);
            return new JSONObject().put("deletedCount", count);
        }
        if (ReBrowserAdminProtocol.OP_REPAIR_DOWNLOADS.equals(operation)) {
            return repairAdminDownloads();
        }
        if (ReBrowserAdminProtocol.OP_DELETE_SHELVED.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, true);
            if (!workspaceController.deleteShelvedSecondary(workspace)) {
                throw new IllegalStateException("shelved-workspace-not-found");
            }
            deleteProfileIfPossible(workspace.profileName);
            return new JSONObject().put("workspaceId", workspace.id).put("deleted", true);
        }
        throw new IllegalArgumentException("unsupported-operation");
    }

    private JSONObject executeAdminNavigation(JSONObject request) throws Exception {
        String operation = request.getString("operation");
        ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
        ReBrowserStore.Tab tab = requireAdminTab(workspace, request);
        hideOverview();
        activateWorkspace(workspace);
        showTab(tab);
        WebView webView = tabWebViews.get(tab.id);
        if (webView == null) throw new IllegalStateException("webview-unavailable");
        webView.stopLoading();
        if (ReBrowserAdminProtocol.OP_GO_BACK.equals(operation) && !webView.canGoBack()) {
            throw new IllegalStateException("no-back-history");
        }
        if (ReBrowserAdminProtocol.OP_GO_FORWARD.equals(operation) && !webView.canGoForward()) {
            throw new IllegalStateException("no-forward-history");
        }
        if (request.optBoolean("waitForLoad", false)
                && !ReBrowserAdminProtocol.OP_STOP.equals(operation)) {
            queueAdminLoadWait(request, tab);
        }
        if (ReBrowserAdminProtocol.OP_OPEN_URL.equals(operation)) {
            navigateActiveTab(request.getString("url"));
        } else if (ReBrowserAdminProtocol.OP_RELOAD.equals(operation)) {
            webView.stopLoading();
            webView.reload();
        } else if (ReBrowserAdminProtocol.OP_STOP.equals(operation)) {
            webView.stopLoading();
            loadingTabIds.remove(tab.id);
            JSONObject pending = pendingAdminLoads.remove(tab.id);
            if (pending != null) {
                recordAdminStatus(pending, "failed", null, "navigation-stopped");
            }
        } else if (ReBrowserAdminProtocol.OP_GO_BACK.equals(operation)) {
            webView.goBack();
        } else if (ReBrowserAdminProtocol.OP_GO_FORWARD.equals(operation)) {
            webView.goForward();
        } else if (ReBrowserAdminProtocol.OP_GO_HOME.equals(operation)) {
            navigateActiveTab(browserPreferences.homeUrl());
        }
        if (request.optBoolean("waitForLoad", false)
                && !ReBrowserAdminProtocol.OP_STOP.equals(operation)) return null;
        return adminTargetDetails(workspace, tab)
                .put("origin", safeOrigin(tab.url))
                .put("navigationDispatched", true);
    }

    private boolean isAdminNavigationOperation(String operation) {
        return ReBrowserAdminProtocol.OP_OPEN_URL.equals(operation)
                || ReBrowserAdminProtocol.OP_RELOAD.equals(operation)
                || ReBrowserAdminProtocol.OP_STOP.equals(operation)
                || ReBrowserAdminProtocol.OP_GO_BACK.equals(operation)
                || ReBrowserAdminProtocol.OP_GO_FORWARD.equals(operation)
                || ReBrowserAdminProtocol.OP_GO_HOME.equals(operation);
    }

    private JSONObject activateAdminTarget(JSONObject request) throws Exception {
        ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
        ReBrowserStore.Tab tab = requireAdminTab(workspace, request);
        hideOverview();
        activateWorkspace(workspace);
        showTab(tab);
        return adminTargetDetails(workspace, tab);
    }

    private ReBrowserStore.Workspace requireAdminWorkspace(
            JSONObject request,
            boolean shelvedOnly
    ) {
        String workspaceId = request.optString("workspaceId", "");
        if (workspaceId.isBlank() && request.has("tabId")) {
            String tabId = request.optString("tabId");
            for (ReBrowserStore.Workspace workspace : workspaces) {
                for (ReBrowserStore.Tab tab : workspace.tabs) {
                    if (tab.id.equals(tabId)) return workspace;
                }
            }
        }
        if (workspaceId.isBlank() && !shelvedOnly) return activeWorkspace();
        List<ReBrowserStore.Workspace> source = shelvedOnly
                ? shelvedSecondaryWorkspaces : workspaces;
        for (ReBrowserStore.Workspace workspace : source) {
            if (workspace.id.equals(workspaceId)) return workspace;
        }
        throw new IllegalStateException("workspace-not-found");
    }

    private ReBrowserStore.Tab requireAdminTab(
            ReBrowserStore.Workspace workspace,
            JSONObject request
    ) {
        String tabId = request.optString("tabId", "");
        if (tabId.isBlank()) {
            ReBrowserStore.Tab tab = workspace.activeTab();
            if (tab != null) return tab;
        } else {
            for (ReBrowserStore.Tab tab : workspace.tabs) {
                if (tab.id.equals(tabId)) return tab;
            }
        }
        throw new IllegalStateException("tab-not-found");
    }

    private JSONObject setAdminPreference(JSONObject request) throws Exception {
        String name = request.getString("name");
        Object value = request.get("value");
        if ("searchEngine".equals(name)) {
            browserPreferences.setSearchEngine((String) value);
        } else if ("javascript".equals(name)) {
            browserPreferences.setJavascriptEnabled((Boolean) value);
        } else if ("thirdPartyCookies".equals(name)) {
            browserPreferences.setThirdPartyCookiesEnabled((Boolean) value);
        } else if ("desktopMode".equals(name)) {
            browserPreferences.setDesktopModeEnabled((Boolean) value);
        } else if ("forcePrimaryPromotion".equals(name)) {
            browserPreferences.setForcePrimaryPromotionEnabled((Boolean) value);
        } else if ("globalOrientation".equals(name)) {
            browserPreferences.setGlobalOrientation((String) value);
            if (!fullscreenController.isFullscreen()) {
                fullscreenController.applyGlobalOrientationPreference();
            }
        } else if ("videoOrientationOverride".equals(name)) {
            browserPreferences.setVideoOrientationOverrideEnabled((Boolean) value);
        } else if ("videoOrientation".equals(name)) {
            browserPreferences.setVideoOrientation((String) value);
        } else {
            throw new IllegalArgumentException("unsupported-preference");
        }
        for (WebView webView : tabWebViews.values()) applyBrowserPreferences(webView);
        return new JSONObject().put("name", name).put("updated", true);
    }

    private JSONObject createAdminCapabilities() throws Exception {
        JSONObject value = new JSONObject();
        value.put("protocolVersion", ReBrowserAdminProtocol.VERSION);
        value.put("operations", ReBrowserAdminProtocol.supportedOperations());
        value.put("administratorRoots", ReBrowserAdminKeys.capabilities());
        value.put("ownerCredentialMaximumLevel", 2);
        value.put("rootKeyMaximumLevel", 3);
        value.put("maxWorkspaces", ReBrowserStore.MAX_WORKSPACES);
        value.put("maxPrimaryWorkspaces", ReBrowserStore.MAX_PRIMARY_WORKSPACES);
        value.put("maxTabsPerWorkspace", ReBrowserStore.MAX_TABS_PER_WORKSPACE);
        value.put("multiProfile",
                WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE));
        value.put("deleteBrowsingData",
                WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA));
        value.put("saveState", WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE));
        value.put("downloads", createAdminDownloadPolicy());
        return value;
    }

    private JSONObject createAdminDiagnostics() throws Exception {
        JSONObject value = new JSONObject();
        android.content.pm.PackageInfo provider = WebView.getCurrentWebViewPackage();
        value.put("webViewPackage", provider == null ? "" : provider.packageName);
        value.put("webViewVersion", provider == null ? "" : provider.versionName);
        value.put("multiProfile",
                WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE));
        value.put("activeWorkspaceId", activeWorkspace() == null ? "" : activeWorkspace().id);
        ReBrowserStore.Tab tab = activeWorkspace() == null ? null : activeWorkspace().activeTab();
        value.put("activeTabId", tab == null ? "" : tab.id);
        value.put("activeOrigin", tab == null ? "" : safeOrigin(tab.url));
        value.put("loading", tab != null && loadingTabIds.contains(tab.id));
        value.put("loadProgress", tab == null ? 0 : tabLoadProgress.getOrDefault(tab.id, 0));
        value.put("lastMainFrameError", tab == null ? ""
                : tabLastErrors.getOrDefault(tab.id, ""));
        value.put("fullscreenVideo", fullscreenController.isFullscreen());
        value.put("globalOrientation", browserPreferences.globalOrientation());
        value.put("videoOrientationOverride",
                browserPreferences.videoOrientationOverrideEnabled());
        value.put("videoOrientation", browserPreferences.videoOrientation());
        value.put("activeWorkspaceCount", workspaces.size());
        value.put("shelvedWorkspaceCount", shelvedSecondaryWorkspaces.size());
        value.put("pendingProfileDeletionCount",
                workspaceController.pendingProfileDeletions().size());
        value.put("downloadRecordCount", downloads.size());
        value.put("downloadsEnabled", downloadStore.downloadsEnabled());
        value.put("pendingDownloadCount", pendingDownloadCount(null));
        value.put("activeBlobTransfer", activeBlobTransfer != null);
        return value;
    }

    private JSONObject createAdminDownloadPolicy() throws Exception {
        return new JSONObject()
                .put("downloadsEnabled", downloadStore.downloadsEnabled())
                .put("scope", "ReBrowser-named-profiles-only")
                .put("rateWindowSeconds", 300)
                .put("ordinaryRequestsPerSite", 2)
                .put("siteDefinition", "profileName+topLevelOrigin")
                .put("maxPendingGlobal", MAX_PENDING_DOWNLOADS)
                .put("maxPendingPerSite", MAX_PENDING_DOWNLOADS_PER_SITE)
                .put("maxHistory", ReBrowserDownloads.MAX_RECORDS)
                .put("maxBlobBytes", ReBrowserDownloads.MAX_BLOB_BYTES)
                .put("maxDataBytes", ReBrowserDownloads.MAX_DATA_BYTES)
                .put("automaticOpen", false)
                .put("automaticInstall", false)
                .put("rawCookieDisclosure", false)
                .put("policyEffect", "new-and-pending-requests")
                .put("runningDownloadsContinueWhenDisabled", true);
    }

    private JSONObject setAdminDownloadPolicy(JSONObject request) throws Exception {
        boolean enabled = request.getBoolean("downloadsEnabled");
        downloadStore.setDownloadsEnabled(enabled);
        int rejectedPending = 0;
        if (!enabled) {
            for (ReBrowserDownloads.Record record : downloads) {
                if (!ReBrowserDownloads.STATUS_PENDING.equals(record.status)) continue;
                record.status = ReBrowserDownloads.STATUS_REJECTED;
                record.error = "administrator-policy-disabled";
                record.updatedAt = System.currentTimeMillis();
                rejectedPending++;
            }
            downloadStore.save(downloads);
        }
        if (downloadOverviewVisible) showDownloadOverview();
        return createAdminDownloadPolicy().put("rejectedPendingCount", rejectedPending);
    }

    private JSONObject createAdminDownloads() throws Exception {
        refreshDownloadStates();
        JSONArray values = new JSONArray();
        for (ReBrowserDownloads.Record record : downloads) {
            values.put(adminDownloadDetails(record));
        }
        return new JSONObject()
                .put("downloads", values)
                .put("pendingCount", pendingDownloadCount(null));
    }

    private JSONObject adminDownloadDetails(ReBrowserDownloads.Record record) throws Exception {
        JSONObject value = new JSONObject();
        value.put("downloadId", record.id);
        value.put("kind", record.kind);
        value.put("workspaceId", record.workspaceId);
        value.put("tabId", record.tabId);
        value.put("profileName", record.profileName);
        value.put("sourceOrigin", record.sourceOrigin);
        value.put("requestLocation", redactedDownloadLocation(record.url));
        value.put("fileName", record.fileName);
        value.put("mimeType", record.mimeType);
        value.put("declaredSize", record.declaredSize);
        value.put("downloadedBytes", record.downloadedBytes);
        value.put("totalSize", record.totalSize);
        value.put("status", record.status);
        value.put("systemDownloadId", record.systemId);
        value.put("sha256", record.sha256 == null ? "" : record.sha256);
        value.put("hashAttempted", record.hashAttempted);
        value.put("dangerous", record.dangerous);
        value.put("riskReasons", record.riskReasons == null ? "" : record.riskReasons);
        value.put("highFrequency", record.highFrequency);
        value.put("rateCount", record.rateCount);
        value.put("authenticatedRequest", record.cookieAttached);
        value.put("createdAt", record.createdAt);
        value.put("updatedAt", record.updatedAt);
        value.put("error", record.error == null ? "" : record.error);
        return value;
    }

    private static String redactedDownloadLocation(String value) {
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return scheme == null ? "" : scheme.toLowerCase(java.util.Locale.ROOT) + ":";
            }
            return safeOrigin(value);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private ReBrowserDownloads.Record requireAdminDownload(JSONObject request) {
        ReBrowserDownloads.Record record = findDownload(request.optString("downloadId"));
        if (record == null) throw new IllegalStateException("download-not-found");
        return record;
    }

    private JSONObject repairAdminDownloads() throws Exception {
        int refreshed = 0;
        int expired = 0;
        int removedDeleted = 0;
        for (ReBrowserDownloads.Record record : new ArrayList<>(downloads)) {
            if (ReBrowserDownloads.STATUS_DOWNLOADING.equals(record.status)
                    && downloadStore.refresh(record)) refreshed++;
            if (ReBrowserDownloads.STATUS_PENDING.equals(record.status)
                    && (!ReBrowserStore.isOwnedProfile(record.profileName)
                    || ProfileStore.getInstance().getProfile(record.profileName) == null)) {
                record.status = ReBrowserDownloads.STATUS_EXPIRED;
                record.error = "profile-unavailable";
                record.updatedAt = System.currentTimeMillis();
                expired++;
            }
            if (ReBrowserDownloads.STATUS_DELETED.equals(record.status)) {
                downloads.remove(record);
                removedDeleted++;
            }
        }
        trimDownloadHistory();
        downloadStore.save(downloads);
        return new JSONObject().put("refreshed", refreshed)
                .put("expired", expired)
                .put("removedDeleted", removedDeleted)
                .put("remaining", downloads.size());
    }

    private JSONObject createAdminState() throws Exception {
        JSONObject state = new JSONObject();
        state.put("version", 2);
        state.put("activeWorkspaceId", activeWorkspace() == null ? "" : activeWorkspace().id);
        state.put("workspaces", createAdminWorkspaceValues(workspaces));
        state.put("shelvedSecondaries",
                createAdminWorkspaceValues(shelvedSecondaryWorkspaces));
        return state;
    }

    private JSONArray createAdminWorkspaceValues(
            List<ReBrowserStore.Workspace> source
    ) throws Exception {
        JSONArray workspaceValues = new JSONArray();
        for (ReBrowserStore.Workspace workspace : source) {
            JSONObject workspaceValue = new JSONObject();
            workspaceValue.put("id", workspace.id);
            workspaceValue.put("profileName", workspace.profileName);
            workspaceValue.put("title", workspace.title);
            workspaceValue.put("level", workspace.level.name());
            workspaceValue.put("activeTabId", workspace.activeTabId);
            JSONArray tabValues = new JSONArray();
            for (ReBrowserStore.Tab tab : workspace.tabs) {
                JSONObject tabValue = new JSONObject();
                tabValue.put("id", tab.id);
                tabValue.put("title", tab.title);
                tabValue.put("origin", safeOrigin(tab.url));
                tabValue.put("loading", loadingTabIds.contains(tab.id));
                tabValue.put("loadProgress", tabLoadProgress.getOrDefault(tab.id, 0));
                tabValue.put("lastMainFrameError", tabLastErrors.getOrDefault(tab.id, ""));
                tabValues.put(tabValue);
            }
            workspaceValue.put("tabs", tabValues);
            workspaceValues.put(workspaceValue);
        }
        return workspaceValues;
    }

    private JSONObject validateAdminState() throws Exception {
        JSONArray issues = new JSONArray();
        Set<String> workspaceIds = new java.util.HashSet<>();
        Set<String> tabIds = new java.util.HashSet<>();
        int primaryCount = 0;
        Set<String> pendingProfileDeletions = workspaceController.pendingProfileDeletions();
        for (ReBrowserStore.Workspace workspace : workspaces) {
            if (!workspaceIds.add(workspace.id)) issues.put("duplicate-workspace:" + workspace.id);
            if (workspace.level == ReBrowserStore.Level.PRIMARY) primaryCount++;
            if (workspace.activeTab() == null) issues.put("missing-active-tab:" + workspace.id);
            for (ReBrowserStore.Tab tab : workspace.tabs) {
                if (!tabIds.add(tab.id)) issues.put("duplicate-tab:" + tab.id);
            }
            if (workspace.level != ReBrowserStore.Level.TEMPORARY
                    && pendingProfileDeletions.contains(workspace.profileName)) {
                issues.put("persistent-profile-pending-deletion:" + workspace.id);
            }
        }
        for (ReBrowserStore.Workspace workspace : shelvedSecondaryWorkspaces) {
            if (!workspaceIds.add(workspace.id)) issues.put("duplicate-workspace:" + workspace.id);
            if (workspace.level != ReBrowserStore.Level.SECONDARY) {
                issues.put("invalid-shelved-level:" + workspace.id);
            }
            if (pendingProfileDeletions.contains(workspace.profileName)) {
                issues.put("shelved-profile-pending-deletion:" + workspace.id);
            }
            for (ReBrowserStore.Tab tab : workspace.tabs) {
                if (!tabIds.add(tab.id)) issues.put("duplicate-tab:" + tab.id);
            }
        }
        if (primaryCount > ReBrowserStore.MAX_PRIMARY_WORKSPACES) {
            issues.put("primary-workspace-limit-exceeded");
        }
        if (workspaces.size() + shelvedSecondaryWorkspaces.size()
                > ReBrowserStore.MAX_WORKSPACES) issues.put("workspace-limit-exceeded");
        return new JSONObject().put("valid", issues.length() == 0).put("issues", issues);
    }

    private JSONObject repairAdminState() throws Exception {
        int cancelledDeletionMarkers = 0;
        Set<String> pendingProfileDeletions = workspaceController.pendingProfileDeletions();
        for (ReBrowserStore.Workspace workspace : workspaces) {
            workspace.activeTab();
            if (workspace.level != ReBrowserStore.Level.TEMPORARY
                    && pendingProfileDeletions.contains(workspace.profileName)) {
                workspaceController.unmarkProfileForDeletion(workspace.profileName);
                cancelledDeletionMarkers++;
            }
        }
        for (ReBrowserStore.Workspace workspace : shelvedSecondaryWorkspaces) {
            workspace.activeTab();
            if (pendingProfileDeletions.contains(workspace.profileName)) {
                workspaceController.unmarkProfileForDeletion(workspace.profileName);
                cancelledDeletionMarkers++;
            }
        }
        workspaceController.saveAll();
        return new JSONObject().put("cancelledDeletionMarkers", cancelledDeletionMarkers)
                .put("validation", validateAdminState());
    }

    private JSONObject adminTargetDetails(
            ReBrowserStore.Workspace workspace,
            ReBrowserStore.Tab tab
    ) throws Exception {
        JSONObject details = new JSONObject();
        details.put("workspaceId", workspace == null ? "" : workspace.id);
        details.put("tabId", tab == null ? "" : tab.id);
        if (workspace != null) details.put("level", workspace.level.name());
        return details;
    }

    private void queueAdminLoadWait(JSONObject request, ReBrowserStore.Tab tab) throws Exception {
        request.put("_loadStarted", false);
        JSONObject previous = pendingAdminLoads.put(tab.id, request);
        if (previous != null) {
            recordAdminStatus(previous, "failed", null, "superseded-by-new-navigation");
        }
        int timeoutSeconds = Math.max(1, Math.min(60, request.optInt("timeoutSeconds", 30)));
        String requestId = request.getString("requestId");
        root.postDelayed(() -> {
            JSONObject pending = pendingAdminLoads.get(tab.id);
            if (pending == null || !requestId.equals(pending.optString("requestId"))) return;
            pendingAdminLoads.remove(tab.id);
            recordAdminStatus(pending, "failed", null, "page-load-timeout");
        }, timeoutSeconds * 1_000L);
    }

    private void finishAdminLoad(ReBrowserStore.Tab tab, boolean success, String error) {
        if (tab == null) return;
        JSONObject request = pendingAdminLoads.get(tab.id);
        if (request == null || !request.optBoolean("_loadStarted", false)) return;
        pendingAdminLoads.remove(tab.id);
        try {
            JSONObject details = adminTargetDetails(activeWorkspace(), tab);
            details.put("origin", safeOrigin(tab.url));
            details.put("title", tab.title);
            details.put("loadProgress", tabLoadProgress.getOrDefault(tab.id, 0));
            recordAdminStatus(request, success ? "completed" : "failed",
                    success ? details : null, error);
        } catch (Exception resultError) {
            recordAdminStatus(request, "failed", null,
                    resultError.getClass().getSimpleName());
        }
    }

    private void recordAdminStatus(
            JSONObject request,
            String status,
            JSONObject details,
            String error
    ) {
        ReBrowserAdminProtocol.recordStatus(this, request, status,
                request.optString("_authentication"), request.optString("_keyId"),
                details, error);
    }

    private static String safeOrigin(String value) {
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return "";
            return scheme.toLowerCase(java.util.Locale.ROOT) + "://"
                    + host.toLowerCase(java.util.Locale.ROOT)
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private String normalizeAddress(String input) {
        String clean = input == null ? "" : input.trim();
        if (clean.isEmpty()) return browserPreferences.homeUrl();
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://")) return clean;
        if (!clean.contains(" ") && clean.contains(".")) return "https://" + clean;
        return browserPreferences.searchUrl(clean);
    }

    private void closeTab(ReBrowserStore.Tab tab) {
        ReBrowserStore.Workspace workspace = activeWorkspace();
        if (workspace == null || !workspace.tabs.contains(tab)) return;
        JSONObject pendingLoad = pendingAdminLoads.remove(tab.id);
        if (pendingLoad != null) {
            recordAdminStatus(pendingLoad, "failed", null, "tab-closed-during-load");
        }
        ReBrowserWorkspaceController.TabCloseResult result = workspaceController.closeTab(
                workspace, tab, browserPreferences.homeUrl());
        if (!result.valid) return;
        if (result.resetOnly) {
            WebView current = tabWebViews.get(tab.id);
            if (current != null) current.loadUrl(tab.url);
            loadedTabIds.add(tab.id);
        } else {
            WebView removed = tabWebViews.remove(tab.id);
            webViewTabs.remove(removed);
            loadedTabIds.remove(tab.id);
            if (removed != null) {
                if (removed.getParent() instanceof ViewGroup) {
                    ((ViewGroup) removed.getParent()).removeView(removed);
                }
                removed.destroy();
            }
            if (result.nextActiveTab != null) showTab(result.nextActiveTab);
        }
        updateChromeUi();
        if (childOverviewVisible) showChildOverview();
    }

    private void showWorkspaceSettings() {
        if (activeWorkspace() == null || activeWorkspace().level == ReBrowserStore.Level.TEMPORARY) return;
        List<String> actions = new ArrayList<>();
        actions.add("重命名总标签页");
        if (activeWorkspace().level == ReBrowserStore.Level.SECONDARY) {
            actions.add("取消上锁并降级为临时总标签页");
            actions.add("提升为主总标签页");
        }
        new AlertDialog.Builder(this)
                .setTitle("总标签页设置")
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) {
                        showRenameDialog();
                    } else if (which == 1) {
                        unlockWorkspace(activeWorkspace());
                    } else {
                        if (workspaceController.promoteToPrimary(
                                activeWorkspace(), false)) {
                            toast("已提升为主总标签页");
                            updateChromeUi();
                            if (workspaceOverviewVisible) showWorkspaceOverview();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmUnlockWorkspace(ReBrowserStore.Workspace workspace) {
        new AlertDialog.Builder(this)
                .setTitle("取消上锁？")
                .setMessage("此总标签页会从书签栏移除并降级为临时总标签页。"
                        + "当前可以继续使用，但关闭后将清除其子标签页和网站 Profile。")
                .setPositiveButton("取消上锁", (dialog, which) -> unlockWorkspace(workspace))
                .setNegativeButton("保持上锁", null)
                .show();
    }

    private void unlockWorkspace(ReBrowserStore.Workspace workspace) {
        if (!workspaceController.unlockToTemporary(workspace)) return;
        toast("已移出书签栏；关闭后将清除此临时总标签页");
        updateChromeUi();
        if (workspaceOverviewVisible) showWorkspaceOverview();
        if (workspaceBookmarkOverviewVisible) showWorkspaceBookmarkOverview();
    }

    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(activeWorkspace().title);
        input.setSelectAllOnFocus(true);
        int padding = dp(20);
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(padding, 0, padding, 0);
        holder.addView(input, matchWrap());
        new AlertDialog.Builder(this)
                .setTitle("重命名总标签页")
                .setView(holder)
                .setPositiveButton("保存", (dialog, which) -> {
                    String value = input.getText().toString().replaceAll("\\s+", " ").trim();
                    if (value.isEmpty()) return;
                    workspaceController.renameActiveWorkspace(value);
                    updateChromeUi();
                    if (workspaceOverviewVisible) showWorkspaceOverview();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmCloseWorkspace() {
        if (activeWorkspace() != null) confirmCloseWorkspace(activeWorkspace());
    }

    private void confirmCloseWorkspace(ReBrowserStore.Workspace workspace) {
        if (workspace.level == ReBrowserStore.Level.PRIMARY) {
            toast("主总标签页不能关闭");
            return;
        }
        String message = workspace.level == ReBrowserStore.Level.TEMPORARY
                ? "临时总标签页将关闭即焚，其全部子标签页和命名 Profile 会被删除。"
                : "副总标签页将从当前列表收起，但会保留在书签栏中，"
                        + "其子标签页和网站 Profile 不会删除。";
        new AlertDialog.Builder(this)
                .setTitle(workspace.level == ReBrowserStore.Level.SECONDARY
                        ? "收起副总标签页？" : "关闭临时总标签页？")
                .setMessage(message)
                .setPositiveButton("关闭", (dialog, which) -> closeWorkspace(workspace))
                .setNegativeButton("取消", null)
                .show();
    }

    private void closeWorkspace(ReBrowserStore.Workspace closing) {
        if (!workspaces.contains(closing)) return;
        if (closing.level == ReBrowserStore.Level.PRIMARY) {
            toast("主总标签页不能关闭");
            return;
        }
        boolean wasActive = closing == activeWorkspace();
        if (wasActive) {
            saveCurrentTabStates();
            destroyTabWebViews();
        }
        if (closing.level == ReBrowserStore.Level.SECONDARY) {
            workspaceController.shelfSecondary(closing);
        } else {
            workspaceController.removeTemporary(closing);
            deleteProfileIfPossible(closing.profileName);
        }
        workspaceController.ensureActiveWorkspace(browserPreferences.homeUrl());
        if (wasActive) {
            activateWorkspace(activeWorkspace());
            hideOverview();
        } else if (workspaceOverviewVisible) {
            showWorkspaceOverview();
        }
        updateChromeUi();
    }

    private void saveVisibleTabState() {
        if (activeWorkspace() == null) return;
        ReBrowserStore.Tab tab = activeWorkspace().activeTab();
        WebView webView = tab == null ? null : tabWebViews.get(tab.id);
        if (tab == null || webView == null) return;
        workspaceController.updateTab(tab, webView.getUrl(), webView.getTitle());
    }

    private void saveCurrentTabStates() {
        for (Map.Entry<WebView, ReBrowserStore.Tab> entry : webViewTabs.entrySet()) {
            WebView webView = entry.getKey();
            ReBrowserStore.Tab tab = entry.getValue();
            workspaceController.updateTab(tab, webView.getUrl(), webView.getTitle());
        }
        saveWorkspaceMetadata();
    }

    private void saveWorkspaceMetadata() {
        workspaceController.save();
    }

    private void destroyTabWebViews() {
        if (activeBlobTransfer != null) {
            failBlobTransfer(activeBlobTransfer, "blob-source-webview-destroyed");
        }
        webContainer.removeAllViews();
        for (WebView webView : tabWebViews.values()) {
            webView.stopLoading();
            webView.removeAllViews();
            webView.destroy();
        }
        tabWebViews.clear();
        webViewTabs.clear();
        loadedTabIds.clear();
    }

    private void deletePendingProfiles() {
        for (String profileName : workspaceController.pendingProfileDeletions()) {
            deleteProfileIfPossible(profileName);
        }
    }

    private void deleteProfileIfPossible(String profileName) {
        if (!ReBrowserStore.isOwnedProfile(profileName)) return;
        try {
            ProfileStore.getInstance().deleteProfile(profileName);
            workspaceController.unmarkProfileForDeletion(profileName);
        } catch (IllegalStateException ignored) {
            // The provider can retain a recently destroyed WebView until process shutdown.
        } catch (RuntimeException ignored) {
            // Keep the deletion marker and retry before WebView creation in the next process.
        }
    }

    private void showUnsupportedProvider() {
        browserToolbar.setVisibility(View.GONE);
        TextView message = new TextView(this);
        message.setText("当前系统 WebView 不支持 Multi-Profile。\n\n"
                + "为保护 ReOutlook 的 Default Profile，ReBrowser 已禁用。\n\n"
                + "可以更新 Android System WebView 后重试。");
        message.setTextColor(Color.rgb(35, 39, 43));
        message.setTextSize(18);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(32), dp(32), dp(32), dp(32));
        webContainer.addView(message, matchMatch());
    }

    private void showProfileFailure(Throwable error) {
        String detail = error.getClass().getSimpleName();
        toast("命名 Profile 创建失败，ReBrowser 已停止：" + detail);
        destroyTabWebViews();
        workspaceController.clearActiveWorkspace();
        browserToolbar.setVisibility(View.GONE);
    }

    private void showBrowserMenu(View anchor) {
        if (activeWorkspace() == null) return;
        WebView webView = activeWebView();
        ReBrowserStore.Tab tab = activeWorkspace().activeTab();
        String url = webView == null || webView.getUrl() == null
                ? tab == null ? "" : tab.url : webView.getUrl();
        boolean favorite = bookmarkStore.findByUrl(bookmarks, url) != null;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(9), dp(8), dp(10));
        panel.setBackground(roundedBackground(Color.rgb(250, 249, 253), dp(28)));

        int popupWidth = dp(264);
        PopupWindow popup = new PopupWindow(panel, popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(12));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);

        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setGravity(Gravity.CENTER);
        shortcuts.addView(menuShortcut(R.drawable.ic_rb_back, "后退",
                webView != null && webView.canGoBack(), () -> {
                    popup.dismiss();
                    if (webView != null) webView.goBack();
                }));
        shortcuts.addView(menuShortcut(R.drawable.ic_rb_forward, "前进",
                webView != null && webView.canGoForward(), () -> {
                    popup.dismiss();
                    if (webView != null) webView.goForward();
                }));
        shortcuts.addView(menuShortcut(
                favorite ? R.drawable.ic_rb_star_filled : R.drawable.ic_rb_star_outline,
                favorite ? "取消收藏" : "收藏当前网页", true, () -> {
                    popup.dismiss();
                    toggleCurrentBookmark();
                }));
        boolean landscape = fullscreenController.isLandscape();
        ImageButton orientation = menuShortcut(
                landscape ? R.drawable.ic_rb_orientation_portrait
                        : R.drawable.ic_rb_orientation_landscape,
                landscape ? "切换并锁定竖屏；长按取消方向锁定"
                        : "切换并锁定横屏；长按取消方向锁定",
                true,
                () -> {
                    popup.dismiss();
                    toggleOrientationLock();
                });
        orientation.setOnLongClickListener(view -> {
            popup.dismiss();
            clearOrientationLock();
            return true;
        });
        shortcuts.addView(orientation);
        shortcuts.addView(menuShortcut(R.drawable.ic_rb_refresh, "刷新",
                webView != null, () -> {
                    popup.dismiss();
                    reloadActivePage();
                }));
        panel.addView(shortcuts, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        panel.addView(menuDivider(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        addMenuListItem(panel, R.drawable.ic_rb_download, "下载内容", () -> {
            popup.dismiss();
            showDownloadOverview();
        });
        addMenuListItem(panel, R.drawable.ic_rb_star_filled, "收藏夹", () -> {
            popup.dismiss();
            showBookmarkOverview();
        });
        addMenuListItem(panel, R.drawable.ic_rb_workspaces, "书签栏", () -> {
            popup.dismiss();
            showWorkspaceBookmarkOverview();
        });
        boolean primary = activeWorkspace().level == ReBrowserStore.Level.PRIMARY;
        boolean temporaryPromotionAllowed = activeWorkspace().level != ReBrowserStore.Level.TEMPORARY
                || browserPreferences.forcePrimaryPromotionEnabled();
        boolean primaryActionEnabled = primary
                || temporaryPromotionAllowed && workspaceController.canPromoteToPrimary();
        addMenuListItem(panel,
                primary ? R.drawable.ic_rb_primary_cancel : R.drawable.ic_rb_primary_promote,
                primary ? "取消主标签页" : "提升为主标签页",
                primaryActionEnabled,
                () -> {
                    popup.dismiss();
                    confirmPrimaryLevelChange(activeWorkspace());
                });
        panel.addView(menuDivider(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        addMenuListItem(panel, R.drawable.ic_rb_settings, "浏览器设置", () -> {
            popup.dismiss();
            startActivity(new Intent(this, ReBrowserSettingsActivity.class));
        });
        addMenuListItem(panel, R.drawable.ic_rb_switch, "切换到 ReOutlook", () -> {
            popup.dismiss();
            ModeRouter.openOutlook(this);
            finish();
        });

        int xOffset = anchor.getWidth() - popupWidth;
        int yOffset = -anchor.getHeight() + dp(5);
        popup.showAsDropDown(anchor, xOffset, yOffset);
    }

    private ImageButton menuShortcut(
            int iconResource,
            String description,
            boolean enabled,
            Runnable action
    ) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setContentDescription(description);
        button.setAlpha(enabled ? 1f : 0.3f);
        button.setEnabled(enabled);
        GradientDrawable circle = roundedBackground(Color.rgb(239, 237, 243), dp(22));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(28, 63, 63, 70)), circle, null));
        if (enabled) button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(44));
        params.setMargins(dp(2), 0, dp(2), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void addMenuListItem(
            LinearLayout panel,
            int iconResource,
            String title,
            Runnable action
    ) {
        addMenuListItem(panel, iconResource, title, true, action);
    }

    private void addMenuListItem(
            LinearLayout panel,
            int iconResource,
            String title,
            boolean enabled,
            Runnable action
    ) {
        TextView row = new TextView(this);
        row.setText(title);
        row.setTextSize(17);
        row.setTextColor(Color.rgb(39, 38, 43));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(12), 0);
        row.setCompoundDrawablePadding(dp(18));
        row.setCompoundDrawablesWithIntrinsicBounds(iconResource, 0, 0, 0);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setAlpha(enabled ? 1f : 0.32f);
        row.setEnabled(enabled);
        if (enabled) row.setOnClickListener(view -> action.run());
        panel.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
    }

    private View menuDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(224, 222, 228));
        return divider;
    }

    private void reloadActivePage() {
        WebView webView = activeWebView();
        if (webView == null) return;
        progressBar.setProgress(5);
        progressBar.setVisibility(View.VISIBLE);
        webView.post(() -> {
            if (webView != activeWebView()) return;
            webView.stopLoading();
            webView.reload();
        });
    }

    private void toggleOrientationLock() {
        String mode = fullscreenController.toggleGlobalOrientationLock();
        toast(ReBrowserPreferences.ORIENTATION_PORTRAIT.equals(mode)
                ? "已锁定竖屏" : "已锁定横屏；横屏朝向跟随传感器");
    }

    private void clearOrientationLock() {
        fullscreenController.clearGlobalOrientationLock();
        toast("已取消 ReBrowser 方向锁定");
    }

    private void confirmPrimaryLevelChange(ReBrowserStore.Workspace workspace) {
        if (workspace == null || workspace != activeWorkspace() || !workspaces.contains(workspace)) {
            return;
        }
        if (workspace.level == ReBrowserStore.Level.TEMPORARY
                && !browserPreferences.forcePrimaryPromotionEnabled()) return;
        if (workspace.level == ReBrowserStore.Level.PRIMARY) {
            new AlertDialog.Builder(this)
                    .setTitle("取消主标签页？")
                    .setMessage("此总标签页将降级为副总标签页并登记到书签栏；"
                            + "它仍会保留全部子标签页和原命名 Profile。")
                    .setPositiveButton("降级为副标签页", (dialog, which) -> {
                        if (workspaceController.demotePrimaryToSecondary(workspace)) {
                            toast("已降级为副总标签页");
                            refreshLifecycleUi();
                        }
                    })
                    .setNegativeButton("保持为主标签页", null)
                    .show();
            return;
        }

        boolean skipsSecondary = workspace.level == ReBrowserStore.Level.TEMPORARY;
        new AlertDialog.Builder(this)
                .setTitle("提升为主标签页？")
                .setMessage(skipsSecondary
                        ? "已允许强行提升：此临时总标签页将跳过副级别直接成为主总标签页。"
                                + "其他主总标签页不受影响。"
                        : "此副总标签页将成为主总标签页；其他主总标签页不受影响。")
                .setPositiveButton("提升", (dialog, which) -> {
                    boolean allowTemporary = browserPreferences.forcePrimaryPromotionEnabled();
                    if (workspaceController.promoteToPrimary(workspace, allowTemporary)) {
                        toast("已提升为主总标签页");
                        refreshLifecycleUi();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshLifecycleUi() {
        updateChromeUi();
        if (workspaceOverviewVisible) showWorkspaceOverview();
        if (workspaceBookmarkOverviewVisible) showWorkspaceBookmarkOverview();
    }

    private LinearLayout createWorkspaceBatchBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(8));
        boolean primarySelected = selectedWorkspacesContainPrimary();

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        boolean hasTemporary = false;
        boolean hasSecondary = false;
        for (ReBrowserStore.Workspace workspace : selectedWorkspaces()) {
            if (workspace.level == ReBrowserStore.Level.TEMPORARY) hasTemporary = true;
            if (workspace.level == ReBrowserStore.Level.SECONDARY) hasSecondary = true;
        }
        actions.addView(batchButton("上锁", !primarySelected && hasTemporary,
                this::batchLockSelectedWorkspaces));
        actions.addView(batchButton("解锁", !primarySelected && hasSecondary,
                this::batchUnlockSelectedWorkspaces));
        actions.addView(batchButton("关闭", !primarySelected && !selectedWorkspaceIds.isEmpty(),
                this::confirmBatchCloseWorkspaces));
        bar.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        if (primarySelected) {
            TextView warning = new TextView(this);
            warning.setText("已选择主总标签页：上锁、解锁和关闭操作暂时禁用");
            warning.setTextSize(11);
            warning.setTextColor(Color.rgb(171, 72, 55));
            warning.setGravity(Gravity.CENTER);
            bar.addView(warning, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        }
        return bar;
    }

    private TextView batchButton(String text, boolean enabled, Runnable action) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(enabled ? Color.rgb(72, 56, 145) : Color.rgb(150, 152, 158));
        button.setGravity(Gravity.CENTER);
        button.setAlpha(enabled ? 1f : 0.45f);
        button.setBackground(roundedBackground(
                enabled ? Color.rgb(235, 231, 250) : Color.rgb(235, 236, 239), dp(18)));
        if (enabled) button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(params);
        return button;
    }

    private List<ReBrowserStore.Workspace> selectedWorkspaces() {
        List<ReBrowserStore.Workspace> selected = new ArrayList<>();
        for (ReBrowserStore.Workspace workspace : workspaces) {
            if (selectedWorkspaceIds.contains(workspace.id)) selected.add(workspace);
        }
        return selected;
    }

    private boolean selectedWorkspacesContainPrimary() {
        for (ReBrowserStore.Workspace workspace : selectedWorkspaces()) {
            if (workspace.level == ReBrowserStore.Level.PRIMARY) return true;
        }
        return false;
    }

    private void toggleWorkspaceSelection(ReBrowserStore.Workspace workspace) {
        workspaceSelectionMode = true;
        if (!selectedWorkspaceIds.add(workspace.id)) selectedWorkspaceIds.remove(workspace.id);
        if (selectedWorkspaceIds.isEmpty()) workspaceSelectionMode = false;
        showWorkspaceOverview();
    }

    private void startWorkspaceSelection(ReBrowserStore.Workspace workspace) {
        workspaceSelectionMode = true;
        selectedWorkspaceIds.add(workspace.id);
        showWorkspaceOverview();
    }

    private void exitWorkspaceSelection() {
        workspaceSelectionMode = false;
        selectedWorkspaceIds.clear();
        showWorkspaceOverview();
    }

    private void batchLockSelectedWorkspaces() {
        if (selectedWorkspacesContainPrimary()) return;
        int changed = 0;
        for (ReBrowserStore.Workspace workspace : selectedWorkspaces()) {
            if (workspaceController.lockAsSecondary(workspace)) changed++;
        }
        workspaceSelectionMode = false;
        selectedWorkspaceIds.clear();
        toast("已上锁 " + changed + " 个总标签页");
        updateChromeUi();
        showWorkspaceOverview();
    }

    private void batchUnlockSelectedWorkspaces() {
        if (selectedWorkspacesContainPrimary()) return;
        int changed = 0;
        for (ReBrowserStore.Workspace workspace : selectedWorkspaces()) {
            if (workspaceController.unlockToTemporary(workspace)) changed++;
        }
        workspaceSelectionMode = false;
        selectedWorkspaceIds.clear();
        toast("已解锁 " + changed + " 个总标签页");
        updateChromeUi();
        showWorkspaceOverview();
    }

    private void confirmBatchCloseWorkspaces() {
        if (selectedWorkspacesContainPrimary() || selectedWorkspaceIds.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("关闭选中的总标签页？")
                .setMessage("临时总标签页会被清除；副总标签页会收起并保留在书签栏。")
                .setPositiveButton("关闭", (dialog, which) -> batchCloseSelectedWorkspaces())
                .setNegativeButton("取消", null)
                .show();
    }

    private void batchCloseSelectedWorkspaces() {
        List<ReBrowserStore.Workspace> closing = selectedWorkspaces();
        if (closing.isEmpty() || selectedWorkspacesContainPrimary()) return;
        boolean activeClosing = closing.contains(activeWorkspace());
        if (activeClosing) {
            saveCurrentTabStates();
            destroyTabWebViews();
        }
        for (ReBrowserStore.Workspace workspace : closing) {
            if (workspace.level == ReBrowserStore.Level.SECONDARY) {
                workspaceController.shelfSecondary(workspace);
            } else {
                workspaceController.removeTemporary(workspace);
                deleteProfileIfPossible(workspace.profileName);
            }
        }
        workspaceController.ensureActiveWorkspace(browserPreferences.homeUrl());
        if (activeClosing) {
            activateWorkspace(activeWorkspace());
        }
        workspaceSelectionMode = false;
        selectedWorkspaceIds.clear();
        updateChromeUi();
        showWorkspaceOverview();
    }

    private void showWorkspaceOverview() {
        if (activeWorkspace() == null) return;
        childSelectionMode = false;
        selectedChildTabIds.clear();
        saveCurrentTabStates();
        workspaceOverviewVisible = true;
        childOverviewVisible = false;
        bookmarkOverviewVisible = false;
        workspaceBookmarkOverviewVisible = false;
        downloadOverviewVisible = false;
        setBrowserContentVisible(false);
        overviewContainer.removeAllViews();
        overviewContainer.addView(createWorkspaceOverviewPage(), matchMatch());
        overviewContainer.setAlpha(0f);
        overviewContainer.setVisibility(View.VISIBLE);
        overviewContainer.animate().alpha(1f).setDuration(160).start();
    }

    private View createWorkspaceOverviewPage() {
        LinearLayout page = overviewPage("总标签页",
                workspaceSelectionMode
                        ? "已选择 " + selectedWorkspaceIds.size() + " 个"
                        : workspaces.size() + " 个独立浏览空间",
                workspaceSelectionMode ? null : this::createTemporaryWorkspace,
                workspaceSelectionMode ? this::exitWorkspaceSelection : this::hideOverview,
                workspaceSelectionMode ? "退出多选" : "返回网页");
        LinearLayout body = (LinearLayout) ((ScrollView) page.getChildAt(1)).getChildAt(0);
        if (workspaceSelectionMode) body.addView(createWorkspaceBatchBar());
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(8), dp(4), dp(8), dp(28));
        int cardWidth = Math.max(dp(150),
                (getResources().getDisplayMetrics().widthPixels - dp(40)) / 2);
        for (ReBrowserStore.Workspace workspace : new ArrayList<>(workspaces)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(12), dp(10));
            boolean selected = selectedWorkspaceIds.contains(workspace.id);
            card.setElevation(selected || workspace == activeWorkspace() ? dp(6) : dp(2));
            GradientDrawable background = roundedBackground(
                    selected ? Color.rgb(224, 218, 249)
                            : workspace == activeWorkspace()
                                    ? Color.rgb(235, 231, 250) : Color.WHITE,
                    dp(18));
            if (selected || workspace == activeWorkspace()) {
                background.setStroke(dp(2), selected
                        ? Color.rgb(74, 52, 166) : Color.rgb(91, 70, 180));
            }
            card.setBackground(background);
            card.setOnClickListener(view -> {
                if (workspaceSelectionMode) {
                    toggleWorkspaceSelection(workspace);
                } else {
                    activateWorkspace(workspace);
                    showChildOverview();
                }
            });
            card.setOnLongClickListener(view -> {
                startWorkspaceSelection(workspace);
                return true;
            });

            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView badge = new TextView(this);
            badge.setText(selected ? "✓" : levelShortLabel(workspace.level));
            badge.setTextSize(11);
            badge.setTextColor(Color.WHITE);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(roundedBackground(
                    selected ? Color.rgb(74, 52, 166) : levelColor(workspace.level), dp(10)));
            heading.addView(badge, new LinearLayout.LayoutParams(dp(52), dp(24)));
            View headingSpace = new View(this);
            heading.addView(headingSpace, new LinearLayout.LayoutParams(0, 1, 1));
            boolean primary = workspace.level == ReBrowserStore.Level.PRIMARY;
            if (!workspaceSelectionMode && !primary) {
                boolean temporary = workspace.level == ReBrowserStore.Level.TEMPORARY;
                TextView lock = toolbarButton("",
                        temporary ? "上锁为副总标签页" : "取消上锁");
                lock.setCompoundDrawablesWithIntrinsicBounds(0,
                        temporary ? R.drawable.ic_rb_lock_open
                                : R.drawable.ic_rb_lock_closed,
                        0, 0);
                lock.setOnClickListener(view -> {
                    if (temporary) {
                        if (workspaceController.lockAsSecondary(workspace)) {
                            toast("已锁定为副总标签页并加入书签栏");
                            updateChromeUi();
                            showWorkspaceOverview();
                        }
                    } else {
                        confirmUnlockWorkspace(workspace);
                    }
                });
                heading.addView(lock, new LinearLayout.LayoutParams(dp(44), dp(34)));
            }
            if (!workspaceSelectionMode) {
                TextView close = toolbarButton(primary ? "◆" : "×",
                        primary ? "主总标签页不可关闭" : "关闭总标签页 " + workspace.title);
                if (primary) {
                    close.setTextColor(Color.rgb(43, 125, 86));
                } else {
                    close.setOnClickListener(view -> {
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                        confirmCloseWorkspace(workspace);
                    });
                }
                heading.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
            }
            card.addView(heading, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

            TextView title = new TextView(this);
            title.setText(workspace.title);
            title.setTextSize(16);
            title.setTextColor(Color.rgb(32, 37, 46));
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setMaxLines(2);
            card.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            ReBrowserStore.Tab tab = workspace.activeTab();
            TextView active = new TextView(this);
            active.setText(tab == null ? "没有子标签页" : tab.title);
            active.setTextSize(12);
            active.setTextColor(Color.rgb(92, 98, 108));
            active.setMaxLines(2);
            card.addView(active, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

            TextView count = new TextView(this);
            count.setText(workspace.tabs.size() + " 个子标签页  ›");
            count.setTextSize(12);
            count.setTextColor(Color.rgb(91, 70, 180));
            count.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(count, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

            GridLayout.LayoutParams cardParams = new GridLayout.LayoutParams();
            cardParams.width = cardWidth;
            cardParams.height = dp(212);
            cardParams.setMargins(dp(6), dp(7), dp(6), dp(7));
            grid.addView(card, cardParams);
        }
        body.addView(grid);
        return page;
    }

    private LinearLayout createChildBatchBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(8));
        bar.addView(batchButton("关闭选中子标签页", !selectedChildTabIds.isEmpty(),
                this::confirmBatchCloseChildTabs));
        return bar;
    }

    private void toggleChildTabSelection(ReBrowserStore.Tab tab) {
        childSelectionMode = true;
        if (!selectedChildTabIds.add(tab.id)) selectedChildTabIds.remove(tab.id);
        if (selectedChildTabIds.isEmpty()) childSelectionMode = false;
        showChildOverview();
    }

    private void startChildTabSelection(ReBrowserStore.Tab tab) {
        childSelectionMode = true;
        selectedChildTabIds.add(tab.id);
        showChildOverview();
    }

    private void exitChildSelection() {
        childSelectionMode = false;
        selectedChildTabIds.clear();
        showChildOverview();
    }

    private void confirmBatchCloseChildTabs() {
        if (selectedChildTabIds.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("关闭选中的子标签页？")
                .setMessage("关闭子标签页不会删除总标签页的网站 Profile。")
                .setPositiveButton("关闭", (dialog, which) -> batchCloseSelectedChildTabs())
                .setNegativeButton("取消", null)
                .show();
    }

    private void batchCloseSelectedChildTabs() {
        if (activeWorkspace() == null) return;
        List<ReBrowserStore.Tab> closing = new ArrayList<>();
        for (ReBrowserStore.Tab tab : activeWorkspace().tabs) {
            if (selectedChildTabIds.contains(tab.id)) closing.add(tab);
        }
        childOverviewVisible = false;
        for (ReBrowserStore.Tab tab : closing) closeTab(tab);
        childSelectionMode = false;
        selectedChildTabIds.clear();
        childOverviewVisible = true;
        showChildOverview();
    }

    private void showChildOverview() {
        if (activeWorkspace() == null) return;
        workspaceSelectionMode = false;
        selectedWorkspaceIds.clear();
        saveCurrentTabStates();
        childOverviewVisible = true;
        workspaceOverviewVisible = false;
        bookmarkOverviewVisible = false;
        workspaceBookmarkOverviewVisible = false;
        downloadOverviewVisible = false;
        setBrowserContentVisible(false);
        overviewContainer.removeAllViews();
        overviewContainer.addView(createChildOverviewPage(), matchMatch());
        overviewContainer.setAlpha(0f);
        overviewContainer.setVisibility(View.VISIBLE);
        overviewContainer.animate().alpha(1f).setDuration(160).start();
    }

    private View createChildOverviewPage() {
        LinearLayout page = overviewPage("子标签页",
                childSelectionMode
                        ? "已选择 " + selectedChildTabIds.size() + " 个"
                        : "总标签页 · " + activeWorkspace().title,
                childSelectionMode ? null : () -> createChildTab(true),
                childSelectionMode ? this::exitChildSelection : this::showWorkspaceOverview,
                childSelectionMode ? "退出多选" : "返回总标签页");
        LinearLayout body = (LinearLayout) ((ScrollView) page.getChildAt(1)).getChildAt(0);
        if (childSelectionMode) body.addView(createChildBatchBar());
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(8), dp(4), dp(8), dp(28));
        int cardWidth = Math.max(dp(150),
                (getResources().getDisplayMetrics().widthPixels - dp(40)) / 2);
        for (ReBrowserStore.Tab tab : new ArrayList<>(activeWorkspace().tabs)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(10), dp(10));
            boolean selected = selectedChildTabIds.contains(tab.id);
            boolean active = tab.id.equals(activeWorkspace().activeTabId);
            card.setElevation(selected || active ? dp(6) : dp(2));
            GradientDrawable background = roundedBackground(
                    selected ? Color.rgb(224, 218, 249)
                            : active ? Color.rgb(235, 231, 250) : Color.WHITE,
                    dp(18));
            if (selected || active) {
                background.setStroke(dp(2), selected
                        ? Color.rgb(74, 52, 166) : Color.rgb(91, 70, 180));
            }
            card.setBackground(background);
            card.setOnClickListener(view -> {
                if (childSelectionMode) {
                    toggleChildTabSelection(tab);
                } else {
                    hideOverview();
                    showTab(tab);
                }
            });
            card.setOnLongClickListener(view -> {
                startChildTabSelection(tab);
                return true;
            });

            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView icon = new TextView(this);
            String titleText = tab.title == null || tab.title.isBlank() ? "新" : tab.title.trim();
            icon.setText(selected ? "✓"
                    : titleText.substring(0, Math.min(1, titleText.length())));
            icon.setTextColor(Color.WHITE);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(roundedBackground(Color.rgb(91, 70, 180), dp(16)));
            heading.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
            heading.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            if (!childSelectionMode) {
                TextView close = toolbarButton("×", "关闭子标签页 " + tab.title);
                close.setOnClickListener(view -> closeTab(tab));
                heading.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
            }
            card.addView(heading, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

            TextView title = new TextView(this);
            title.setText(tab.title);
            title.setTextSize(16);
            title.setTextColor(Color.rgb(32, 37, 46));
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setMaxLines(3);
            card.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            TextView url = new TextView(this);
            url.setText(displayUrl(tab.url));
            url.setTextSize(11);
            url.setTextColor(Color.rgb(100, 106, 116));
            url.setSingleLine(true);
            card.addView(url, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

            GridLayout.LayoutParams cardParams = new GridLayout.LayoutParams();
            cardParams.width = cardWidth;
            cardParams.height = dp(212);
            cardParams.setMargins(dp(6), dp(7), dp(6), dp(7));
            grid.addView(card, cardParams);
        }
        body.addView(grid);
        return page;
    }

    private void showBookmarkOverview() {
        if (activeWorkspace() == null) return;
        saveCurrentTabStates();
        bookmarkOverviewVisible = true;
        workspaceOverviewVisible = false;
        childOverviewVisible = false;
        workspaceBookmarkOverviewVisible = false;
        downloadOverviewVisible = false;
        setBrowserContentVisible(false);
        overviewContainer.removeAllViews();
        overviewContainer.addView(createBookmarkOverviewPage(), matchMatch());
        overviewContainer.setAlpha(0f);
        overviewContainer.setVisibility(View.VISIBLE);
        overviewContainer.animate().alpha(1f).setDuration(160).start();
    }

    private View createBookmarkOverviewPage() {
        LinearLayout page = overviewPage("收藏", bookmarks.size() + " 个收藏网址",
                this::addCurrentBookmark, this::hideOverview, "返回网页");
        LinearLayout body = (LinearLayout) ((ScrollView) page.getChildAt(1)).getChildAt(0);
        if (bookmarks.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有收藏网址\n\n点击右上角＋收藏当前网页");
            empty.setTextSize(16);
            empty.setTextColor(Color.rgb(92, 98, 108));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(96), dp(24), dp(48));
            body.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return page;
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(28));
        for (ReBrowserFavorites.Favorite bookmark : new ArrayList<>(bookmarks)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(7), dp(6), dp(7));
            row.setBackground(roundedBackground(Color.WHITE, dp(10)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            labels.setOnClickListener(view -> {
                hideOverview();
                navigateActiveTab(bookmark.url);
            });
            TextView titleView = new TextView(this);
            titleView.setText(bookmark.title);
            titleView.setTextSize(15);
            titleView.setTextColor(Color.rgb(32, 37, 46));
            titleView.setTypeface(null, android.graphics.Typeface.BOLD);
            titleView.setSingleLine(true);
            labels.addView(titleView);
            TextView urlView = new TextView(this);
            urlView.setText(bookmark.url);
            urlView.setTextSize(11);
            urlView.setTextColor(Color.rgb(100, 106, 116));
            urlView.setSingleLine(true);
            labels.addView(urlView);
            row.addView(labels, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            TextView edit = toolbarButton("编辑", "编辑收藏 " + bookmark.title);
            edit.setTextSize(12);
            edit.setOnClickListener(view -> showEditBookmarkDialog(bookmark));
            row.addView(edit, new LinearLayout.LayoutParams(dp(50), dp(52)));
            TextView remove = toolbarButton("删除", "删除收藏 " + bookmark.title);
            remove.setTextSize(12);
            remove.setOnClickListener(view -> confirmRemoveBookmark(bookmark));
            row.addView(remove, new LinearLayout.LayoutParams(dp(50), dp(52)));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(78));
            rowParams.setMargins(0, dp(3), 0, dp(3));
            list.addView(row, rowParams);
        }
        body.addView(list);
        return page;
    }

    private void showDownloadOverview() {
        if (activeWorkspace() == null) return;
        refreshDownloadStates();
        downloadOverviewVisible = true;
        bookmarkOverviewVisible = false;
        workspaceBookmarkOverviewVisible = false;
        workspaceOverviewVisible = false;
        childOverviewVisible = false;
        setBrowserContentVisible(false);
        overviewContainer.removeAllViews();
        overviewContainer.addView(createDownloadOverviewPage(), matchMatch());
        overviewContainer.setVisibility(View.VISIBLE);
        root.removeCallbacks(downloadRefreshRunnable);
        boolean active = false;
        for (ReBrowserDownloads.Record record : downloads) {
            if (ReBrowserDownloads.STATUS_DOWNLOADING.equals(record.status)) {
                active = true;
                break;
            }
        }
        if (active) root.postDelayed(downloadRefreshRunnable, 1_000L);
    }

    private View createDownloadOverviewPage() {
        int pending = pendingDownloadCount(null);
        String subtitle = downloadStore.downloadsEnabled()
                ? pending == 0 ? downloads.size() + " 条记录"
                        : pending + " 个待确认 · " + downloads.size() + " 条记录"
                : "管理员已暂停新下载 · " + downloads.size() + " 条记录";
        LinearLayout page = overviewPage("下载内容", subtitle,
                null, this::hideOverview, "返回网页");
        LinearLayout body = (LinearLayout) ((ScrollView) page.getChildAt(1)).getChildAt(0);
        if (downloads.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有下载记录\n\n网站发起下载时会先在这里记录；文件永不自动打开。");
            empty.setTextSize(16);
            empty.setTextColor(Color.rgb(92, 98, 108));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(96), dp(24), dp(48));
            body.addView(empty);
            return page;
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(28));
        for (ReBrowserDownloads.Record record : new ArrayList<>(downloads)) {
            list.addView(createDownloadRow(record), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(82)));
        }
        body.addView(list);
        return page;
    }

    private View createDownloadRow(ReBrowserDownloads.Record record) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(7), dp(8), dp(7));
        GradientDrawable background = roundedBackground(
                ReBrowserDownloads.STATUS_PENDING.equals(record.status)
                        ? Color.rgb(255, 248, 230) : Color.WHITE, dp(10));
        row.setBackground(background);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(record.fileName);
        title.setTextSize(15);
        title.setTextColor(Color.rgb(32, 37, 46));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        labels.addView(title);
        TextView status = new TextView(this);
        long size = record.totalSize >= 0 ? record.totalSize : record.declaredSize;
        String progress = ReBrowserDownloads.STATUS_DOWNLOADING.equals(record.status)
                ? " · " + ReBrowserDownloads.formatBytes(record.downloadedBytes)
                        + " / " + ReBrowserDownloads.formatBytes(size)
                : " · " + ReBrowserDownloads.formatBytes(size);
        status.setText(ReBrowserDownloads.statusLabel(record) + progress
                + " · " + displayUrl(record.sourceOrigin));
        status.setTextSize(11);
        status.setTextColor(record.dangerous
                ? Color.rgb(174, 68, 45) : Color.rgb(92, 98, 108));
        status.setSingleLine(true);
        labels.addView(status);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView action = toolbarButton(downloadActionLabel(record),
                "管理下载 " + record.fileName);
        action.setTextSize(14);
        action.setTextColor(Color.rgb(72, 56, 145));
        action.setOnClickListener(view -> handleDownloadAction(record));
        row.addView(action, new LinearLayout.LayoutParams(dp(58), dp(52)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(82));
        params.setMargins(0, dp(3), 0, dp(3));
        row.setLayoutParams(params);
        return row;
    }

    private String downloadActionLabel(ReBrowserDownloads.Record record) {
        if (ReBrowserDownloads.STATUS_PENDING.equals(record.status)) return "确认";
        if (ReBrowserDownloads.STATUS_DOWNLOADING.equals(record.status)) return "取消";
        if (ReBrowserDownloads.STATUS_COMPLETED.equals(record.status)) return "打开";
        if (ReBrowserDownloads.STATUS_FAILED.equals(record.status)
                || ReBrowserDownloads.STATUS_CANCELLED.equals(record.status)) return "重试";
        return "删除";
    }

    private void handleDownloadAction(ReBrowserDownloads.Record record) {
        if (ReBrowserDownloads.STATUS_PENDING.equals(record.status)) {
            showDownloadConfirmation(record, false);
        } else if (ReBrowserDownloads.STATUS_DOWNLOADING.equals(record.status)) {
            new AlertDialog.Builder(this).setTitle("取消下载？")
                    .setMessage(record.fileName)
                    .setPositiveButton("取消下载", (dialog, which) -> {
                        cancelDownload(record);
                        showDownloadOverview();
                    }).setNegativeButton("继续", null).show();
        } else if (ReBrowserDownloads.STATUS_COMPLETED.equals(record.status)) {
            confirmOpenDownload(record);
        } else if (ReBrowserDownloads.STATUS_FAILED.equals(record.status)
                || ReBrowserDownloads.STATUS_CANCELLED.equals(record.status)) {
            retryDownload(record);
        } else {
            deleteDownloadRecord(record);
        }
    }

    private void retryDownload(ReBrowserDownloads.Record record) {
        if (!downloadStore.downloadsEnabled()) {
            record.status = ReBrowserDownloads.STATUS_REJECTED;
            record.error = "administrator-policy-disabled";
            record.updatedAt = System.currentTimeMillis();
            downloadStore.save(downloads);
            showDownloadOverview();
            toast("管理员已暂停 ReBrowser 新下载");
            return;
        }
        if (!record.exactRequestAvailable) {
            record.status = ReBrowserDownloads.STATUS_EXPIRED;
            record.error = "exact-request-not-retained-after-restart";
            record.updatedAt = System.currentTimeMillis();
            downloadStore.save(downloads);
            showDownloadOverview();
            toast("为避免持久化网址令牌，请在原网页重新发起下载");
            return;
        }
        int count = downloadStore.recordAttempt(
                record.profileName + "|" + record.sourceOrigin, System.currentTimeMillis());
        record.rateCount = count;
        record.highFrequency = count >= 3;
        record.systemId = -1L;
        record.status = ReBrowserDownloads.STATUS_PENDING;
        record.error = "";
        record.updatedAt = System.currentTimeMillis();
        downloadStore.save(downloads);
        showDownloadOverview();
        toast("重试请求已进入待确认列表");
    }

    private void cancelDownload(ReBrowserDownloads.Record record) {
        if (activeBlobTransfer != null && activeBlobTransfer.record == record) {
            failBlobTransfer(activeBlobTransfer, "download-cancelled");
        }
        downloadStore.cancel(record);
        downloadStore.save(downloads);
    }

    private void confirmOpenDownload(ReBrowserDownloads.Record record) {
        String message = "文件将以只读临时授权交给 Android 系统选择器。ReBrowser 不会解析或执行它。";
        if (record.dangerous) message += "\n\n⚠ 此文件被标记为高风险，请谨慎选择外部应用。";
        new AlertDialog.Builder(this)
                .setTitle("交给其他应用打开？")
                .setMessage(message + "\n\n" + record.fileName)
                .setPositiveButton("选择应用", (dialog, which) -> openDownloadExternally(record))
                .setNeutralButton("删除", (dialog, which) -> confirmDeleteDownload(record))
                .setNegativeButton("取消", null)
                .show();
    }

    private void openDownloadExternally(ReBrowserDownloads.Record record) {
        Intent open = downloadStore.externalOpenIntent(record);
        if (open == null) {
            toast("下载文件已不存在");
            return;
        }
        try {
            startActivity(Intent.createChooser(open, "选择打开文件的应用"));
        } catch (ActivityNotFoundException error) {
            toast("没有能够打开此文件的应用");
        }
    }

    private void confirmDeleteDownload(ReBrowserDownloads.Record record) {
        new AlertDialog.Builder(this).setTitle("删除下载文件和记录？")
                .setMessage(record.fileName)
                .setPositiveButton("删除", (dialog, which) -> deleteDownloadRecord(record))
                .setNegativeButton("取消", null).show();
    }

    private void deleteDownloadRecord(ReBrowserDownloads.Record record) {
        if (activeBlobTransfer != null && activeBlobTransfer.record == record) {
            failBlobTransfer(activeBlobTransfer, "download-deleted");
        }
        downloadStore.delete(record);
        downloads.remove(record);
        downloadStore.save(downloads);
        if (downloadOverviewVisible) showDownloadOverview();
    }

    private void showWorkspaceBookmarkOverview() {
        if (activeWorkspace() == null) return;
        saveCurrentTabStates();
        workspaceBookmarkOverviewVisible = true;
        bookmarkOverviewVisible = false;
        workspaceOverviewVisible = false;
        childOverviewVisible = false;
        downloadOverviewVisible = false;
        setBrowserContentVisible(false);
        overviewContainer.removeAllViews();
        overviewContainer.addView(createWorkspaceBookmarkOverviewPage(), matchMatch());
        overviewContainer.setAlpha(0f);
        overviewContainer.setVisibility(View.VISIBLE);
        overviewContainer.animate().alpha(1f).setDuration(160).start();
    }

    private View createWorkspaceBookmarkOverviewPage() {
        List<ReBrowserStore.Workspace> secondaryWorkspaces = new ArrayList<>();
        for (ReBrowserStore.Workspace workspace : workspaces) {
            if (workspace.level == ReBrowserStore.Level.SECONDARY) {
                secondaryWorkspaces.add(workspace);
            }
        }
        secondaryWorkspaces.addAll(shelvedSecondaryWorkspaces);
        LinearLayout page = overviewPage("书签栏",
                secondaryWorkspaces.size() + " 个副总标签页",
                null, this::hideOverview, "返回网页");
        LinearLayout body = (LinearLayout) ((ScrollView) page.getChildAt(1)).getChildAt(0);
        if (secondaryWorkspaces.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("书签栏中还没有副总标签页\n\n"
                    + "请在总标签页管理界面锁定一个临时总标签页");
            empty.setTextSize(16);
            empty.setTextColor(Color.rgb(92, 98, 108));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(96), dp(24), dp(48));
            body.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return page;
        }

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(8), dp(8), dp(8), dp(28));
        int cardWidth = Math.max(dp(150),
                (getResources().getDisplayMetrics().widthPixels - dp(40)) / 2);
        for (ReBrowserStore.Workspace workspace : secondaryWorkspaces) {
            boolean shelved = shelvedSecondaryWorkspaces.contains(workspace);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(10), dp(12));
            card.setElevation(dp(2));
            card.setBackground(roundedBackground(
                    shelved ? Color.WHITE : Color.rgb(235, 239, 251), dp(18)));
            card.setOnClickListener(view -> openWorkspaceBookmark(workspace, shelved));

            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView badge = new TextView(this);
            badge.setText(shelved ? "已收起" : "已打开");
            badge.setTextSize(11);
            badge.setTextColor(Color.WHITE);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(roundedBackground(
                    shelved ? Color.rgb(105, 105, 118) : Color.rgb(70, 101, 176), dp(11)));
            heading.addView(badge, new LinearLayout.LayoutParams(dp(58), dp(25)));
            heading.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            TextView action = toolbarButton(shelved ? "×" : "",
                    shelved ? "从书签栏移除" : "取消上锁");
            action.setTextSize(19);
            if (!shelved) {
                action.setCompoundDrawablesWithIntrinsicBounds(
                        0, R.drawable.ic_rb_lock_open, 0, 0);
            }
            action.setOnClickListener(view -> {
                if (shelved) confirmDeleteShelvedWorkspace(workspace);
                else confirmUnlockWorkspace(workspace);
            });
            heading.addView(action, new LinearLayout.LayoutParams(dp(38), dp(38)));
            card.addView(heading, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

            TextView titleView = new TextView(this);
            titleView.setText(workspace.title);
            titleView.setTextSize(16);
            titleView.setTextColor(Color.rgb(32, 37, 46));
            titleView.setTypeface(null, android.graphics.Typeface.BOLD);
            titleView.setMaxLines(3);
            card.addView(titleView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            TextView countView = new TextView(this);
            countView.setText(workspace.tabs.size() + " 个子标签页");
            countView.setTextSize(12);
            countView.setTextColor(Color.rgb(70, 101, 176));
            card.addView(countView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

            GridLayout.LayoutParams cardParams = new GridLayout.LayoutParams();
            cardParams.width = cardWidth;
            cardParams.height = dp(190);
            cardParams.setMargins(dp(6), dp(7), dp(6), dp(7));
            grid.addView(card, cardParams);
        }
        body.addView(grid);
        return page;
    }

    private LinearLayout overviewPage(
            String title,
            String subtitle,
            Runnable addAction,
            Runnable backAction,
            String backDescription
    ) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(245, 247, 251));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(6), dp(8), dp(6));
        header.setBackgroundColor(Color.WHITE);
        header.setElevation(dp(4));
        TextView back = toolbarButton("‹", backDescription);
        back.setTextSize(32);
        back.setOnClickListener(view -> backAction.run());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(52)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(20);
        titleView.setTextColor(Color.rgb(31, 36, 45));
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        labels.addView(titleView);
        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(11);
        subtitleView.setTextColor(Color.rgb(97, 103, 113));
        labels.addView(subtitleView);
        header.addView(labels, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView add = toolbarButton("＋", "新建");
        add.setTextSize(26);
        if (addAction == null) {
            add.setVisibility(View.INVISIBLE);
        } else {
            add.setOnClickListener(view -> addAction.run());
        }
        header.addView(add, new LinearLayout.LayoutParams(dp(52), dp(52)));
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    private void hideOverview() {
        workspaceOverviewVisible = false;
        childOverviewVisible = false;
        bookmarkOverviewVisible = false;
        workspaceBookmarkOverviewVisible = false;
        downloadOverviewVisible = false;
        workspaceSelectionMode = false;
        childSelectionMode = false;
        selectedWorkspaceIds.clear();
        selectedChildTabIds.clear();
        overviewContainer.animate().cancel();
        overviewContainer.setVisibility(View.GONE);
        overviewContainer.removeAllViews();
        setBrowserContentVisible(true);
    }

    private void setBrowserContentVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.INVISIBLE;
        browserToolbar.setVisibility(visibility);
        webContainer.setVisibility(visibility);
        if (!visible) progressBar.setVisibility(View.GONE);
        int importance = visible
                ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
        browserToolbar.setImportantForAccessibility(importance);
        webContainer.setImportantForAccessibility(importance);
    }

    private ReBrowserStore.Workspace activeWorkspace() {
        return workspaceController.activeWorkspace();
    }

    private WebView activeWebView() {
        ReBrowserStore.Tab tab = activeWorkspace() == null ? null : activeWorkspace().activeTab();
        return tab == null ? null : tabWebViews.get(tab.id);
    }

    private static String displayUrl(String value) {
        if (value == null || value.isBlank()) return "about:blank";
        Uri uri = Uri.parse(value);
        String host = uri.getHost();
        return host == null || host.isBlank() ? value : host;
    }

    private static int levelColor(ReBrowserStore.Level level) {
        if (level == ReBrowserStore.Level.PRIMARY) return Color.rgb(43, 125, 86);
        if (level == ReBrowserStore.Level.SECONDARY) return Color.rgb(70, 101, 176);
        return Color.rgb(112, 99, 125);
    }

    private static String levelShortLabel(ReBrowserStore.Level level) {
        if (level == ReBrowserStore.Level.PRIMARY) return "主";
        if (level == ReBrowserStore.Level.SECONDARY) return "副";
        return "临时";
    }

    private FrameLayout.LayoutParams matchMatch() {
        return ReBrowserUi.matchMatch();
    }

    private FrameLayout.LayoutParams matchWrap() {
        return ReBrowserUi.matchWrap();
    }

    private int dp(int value) {
        return ReBrowserUi.dp(this, value);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static boolean samePage(String first, String second) {
        if (first == null || second == null) return false;
        try {
            String normalizedFirst = Uri.parse(first).normalizeScheme().buildUpon()
                    .fragment(null).build().toString().replaceAll("/+$", "");
            String normalizedSecond = Uri.parse(second).normalizeScheme().buildUpon()
                    .fragment(null).build().toString().replaceAll("/+$", "");
            return normalizedFirst.equalsIgnoreCase(normalizedSecond);
        } catch (RuntimeException ignored) {
            return first.equalsIgnoreCase(second);
        }
    }

    private void handleSystemBack() {
        if (fullscreenController.hideIfVisible()) return;
        if (childOverviewVisible) {
            if (childSelectionMode) exitChildSelection();
            else showWorkspaceOverview();
            return;
        }
        if (workspaceOverviewVisible) {
            if (workspaceSelectionMode) exitWorkspaceSelection();
            else hideOverview();
            return;
        }
        if (bookmarkOverviewVisible || workspaceBookmarkOverviewVisible
                || downloadOverviewVisible) {
            hideOverview();
            return;
        }
        if (omnibox.hasFocus()) {
            omnibox.clearFocus();
            InputMethodManager keyboard = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            keyboard.hideSoftInputFromWindow(omnibox.getWindowToken(), 0);
            return;
        }
        ReBrowserStore.Tab tab = activeWorkspace() == null ? null : activeWorkspace().activeTab();
        WebView webView = tab == null ? null : tabWebViews.get(tab.id);
        boolean atHomepage = webView != null
                && samePage(webView.getUrl(), browserPreferences.homeUrl());
        if (!atHomepage && webView != null && webView.canGoBack()) {
            lastBackPressAt = 0;
            webView.goBack();
            return;
        }
        if (!atHomepage && webView != null) {
            lastBackPressAt = 0;
            navigateActiveTab(browserPreferences.homeUrl());
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPressAt <= DOUBLE_BACK_INTERVAL_MS) {
            finishAffinity();
            return;
        }
        lastBackPressAt = now;
        toast("再按一次返回键退出 ReBrowser");
    }

    @Override
    public void onBackPressed() {
        handleSystemBack();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        fullscreenController.onConfigurationChanged();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        fullscreenController.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (WebView webView : tabWebViews.values()) applyBrowserPreferences(webView);
        refreshDownloadStates();
        if (downloadOverviewVisible) showDownloadOverview();
        if (webContainer.getChildCount() > 0
                && webContainer.getChildAt(0) instanceof WebView) {
            ((WebView) webContainer.getChildAt(0)).onResume();
        }
    }

    @Override
    protected void onPause() {
        saveCurrentTabStates();
        for (WebView webView : tabWebViews.values()) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        for (JSONObject request : new ArrayList<>(pendingAdminLoads.values())) {
            recordAdminStatus(request, "failed", null, "activity-destroyed-during-load");
        }
        pendingAdminLoads.clear();
        fullscreenController.hide();
        saveCurrentTabStates();
        destroyTabWebViews();
        if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
        pendingFileChooser = null;
        for (ReBrowserStore.Workspace workspace : workspaces) {
            if (workspace.level == ReBrowserStore.Level.TEMPORARY) {
                deleteProfileIfPossible(workspace.profileName);
            }
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || pendingFileChooser == null) return;
        Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        pendingFileChooser.onReceiveValue(result);
        pendingFileChooser = null;
    }

    private void confirmExternalNavigation(Uri uri, String scheme) {
        String action = "tel".equals(scheme) ? "拨号"
                : "mailto".equals(scheme) ? "邮件"
                : "sms".equals(scheme) ? "短信" : "地图";
        String display = uri.toString();
        if (display.length() > 300) display = display.substring(0, 300) + "…";
        new AlertDialog.Builder(this)
                .setTitle("打开外部" + action + "应用？")
                .setMessage(display)
                .setPositiveButton("选择应用", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                        intent.setSelector(null);
                        startActivity(Intent.createChooser(intent, "选择外部应用"));
                    } catch (ActivityNotFoundException error) {
                        toast("没有能够处理此链接的应用");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private final class WorkspaceWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            ReBrowserStore.Tab tab = webViewTabs.get(view);
            if (tab != null) {
                workspaceController.updateTab(tab, url, null);
                JSONObject pending = pendingAdminLoads.get(tab.id);
                if (pending != null) {
                    try {
                        pending.put("_loadStarted", true);
                    } catch (Exception ignored) {
                        // Boolean insertion into a JSONObject cannot fail in normal operation.
                    }
                }
                loadingTabIds.add(tab.id);
                tabLoadProgress.put(tab.id, 5);
                tabLastErrors.remove(tab.id);
            }
            if (isVisibleWebView(view)) {
                omnibox.setText(ReBrowserStore.safeUrl(url));
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(5);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            ReBrowserStore.Tab tab = webViewTabs.get(view);
            if (tab != null) {
                workspaceController.updatePageMetadata(tab, url, view.getTitle());
                loadingTabIds.remove(tab.id);
                tabLoadProgress.put(tab.id, 100);
                saveWorkspaceMetadata();
                updateChromeUi();
            }
            if (isVisibleWebView(view)) {
                omnibox.setText(ReBrowserStore.safeUrl(url));
                progressBar.setVisibility(View.GONE);
            }
            finishAdminLoad(tab, true, null);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : request.getUrl().getScheme()
                    .toLowerCase(java.util.Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme)) return false;
            if (!request.isForMainFrame() || !request.hasGesture()) {
                toast("已阻止网页自动唤起外部应用");
                return true;
            }
            if (!("mailto".equals(scheme) || "tel".equals(scheme)
                    || "sms".equals(scheme) || "geo".equals(scheme))) {
                toast("已阻止不受支持的外部链接协议");
                return true;
            }
            confirmExternalNavigation(uri, scheme);
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            ReBrowserStore.Tab tab = webViewTabs.get(view);
            String description = error.getErrorCode() + ":" + error.getDescription();
            if (tab != null) {
                loadingTabIds.remove(tab.id);
                tabLastErrors.put(tab.id, description.substring(0,
                        Math.min(description.length(), 200)));
            }
            if (isVisibleWebView(view)) {
                progressBar.setVisibility(View.GONE);
                toast("页面连接失败");
            }
            finishAdminLoad(tab, false, "page-load-error:" + error.getErrorCode());
        }
    }

    private final class WorkspaceChromeClient extends WebChromeClient {
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            fullscreenController.show(view, callback);
        }

        @Override
        public void onHideCustomView() {
            fullscreenController.hide();
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            ReBrowserStore.Tab tab = webViewTabs.get(view);
            if (tab != null) tabLoadProgress.put(tab.id, newProgress);
            if (!isVisibleWebView(view)) return;
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            ReBrowserStore.Tab tab = webViewTabs.get(view);
            if (tab == null || title == null || title.isBlank()) return;
            workspaceController.updateTab(tab, null, title);
            saveWorkspaceMetadata();
            if (isVisibleWebView(view)) updateChromeUi();
        }

        @Override
        public boolean onCreateWindow(
                WebView view,
                boolean isDialog,
                boolean isUserGesture,
                Message resultMsg
        ) {
            if (!isUserGesture || activeWorkspace() == null) return false;
            ReBrowserStore.Tab tab = workspaceController.addTab(
                    activeWorkspace(), "about:blank");
            if (tab == null) {
                toast("每个总标签页最多 50 个子标签页");
                return false;
            }
            try {
                WebView popup = createBrowserWebView(activeWorkspace().profileName, tab);
                tabWebViews.put(tab.id, popup);
                loadedTabIds.add(tab.id);
                showTab(tab);
                WebView.WebViewTransport transport =
                        (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            } catch (Throwable error) {
                workspaceController.discardAddedTab(
                        activeWorkspace(), tab);
                showProfileFailure(error);
                return false;
            }
        }

        @Override
        public void onCloseWindow(WebView window) {
            ReBrowserStore.Tab tab = webViewTabs.get(window);
            if (tab != null) closeTab(tab);
        }

        @Override
        public boolean onShowFileChooser(
                WebView webView,
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams
        ) {
            if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
            pendingFileChooser = filePathCallback;
            try {
                startActivityForResult(fileChooserParams.createIntent(), FILE_CHOOSER_REQUEST);
                return true;
            } catch (ActivityNotFoundException error) {
                pendingFileChooser = null;
                toast("没有可用的文件选择器");
                return false;
            }
        }
    }

    private final class WorkspaceDownloadListener implements DownloadListener {
        private final WebView sourceView;
        private final String profileName;

        WorkspaceDownloadListener(WebView sourceView, String profileName) {
            this.sourceView = sourceView;
            this.profileName = profileName;
        }

        @Override
        public void onDownloadStart(
                String url,
                String userAgent,
                String contentDisposition,
                String mimeType,
                long contentLength
        ) {
            requestDownload(sourceView, profileName, url, userAgent,
                    contentDisposition, mimeType, contentLength);
        }
    }

    private void requestDownload(
            WebView sourceView,
            String profileName,
            String url,
            String userAgent,
            String contentDisposition,
            String mimeType,
            long contentLength
    ) {
        ReBrowserStore.Tab tab = webViewTabs.get(sourceView);
        if (tab == null || activeWorkspace() == null || !activeWorkspace().tabs.contains(tab)
                || !activeWorkspace().profileName.equals(profileName)
                || !ReBrowserStore.isOwnedProfile(profileName)) {
            toast("已拒绝来源不明确的下载");
            return;
        }
        String sourceUrl = sourceView.getUrl() == null ? tab.url : sourceView.getUrl();
        String origin = safeOrigin(sourceUrl);
        if (origin.isBlank()) {
            toast("已拒绝没有顶层网站来源的下载");
            return;
        }
        String kind = ReBrowserDownloads.kind(url);
        if (ReBrowserDownloads.KIND_HTTP.equals(kind)) {
            String scheme = Uri.parse(url).getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                toast("仅允许 HTTP(S)、Blob 或 Data 下载");
                return;
            }
        } else if (ReBrowserDownloads.KIND_BLOB.equals(kind)
                && !url.startsWith("blob:" + origin + "/")) {
            toast("Blob 下载来源与当前顶层网站不一致");
            return;
        }
        String siteKey = profileName + "|" + origin;
        int rateCount = downloadStore.recordAttempt(siteKey, System.currentTimeMillis());
        boolean highFrequency = rateCount >= 3;
        ReBrowserDownloads.Record record = downloadStore.create(
                activeWorkspace().id, tab.id, profileName, sourceUrl, origin, url,
                userAgent, contentDisposition, mimeType, contentLength,
                rateCount, highFrequency);
        if (!makeDownloadRecordRoom()) {
            toast("下载记录与活动任务已达到上限");
            return;
        }
        downloads.add(0, record);
        if (!downloadStore.downloadsEnabled()) {
            record.status = ReBrowserDownloads.STATUS_REJECTED;
            record.error = "administrator-policy-disabled";
            record.updatedAt = System.currentTimeMillis();
            downloadStore.save(downloads);
            toast("管理员已暂停 ReBrowser 新下载");
            return;
        }
        if (highFrequency) {
            int global = pendingDownloadCount(null);
            int perSite = pendingDownloadCount(siteKey);
            if (global > MAX_PENDING_DOWNLOADS || perSite > MAX_PENDING_DOWNLOADS_PER_SITE) {
                record.status = ReBrowserDownloads.STATUS_REJECTED;
                record.error = "pending-queue-limit";
                toast("该网站的高频下载请求过多，已拒绝");
            } else {
                toast("高频下载已拦截，请在“下载内容”中确认");
            }
            downloadStore.save(downloads);
            return;
        }
        downloadStore.save(downloads);
        showDownloadConfirmation(record, false);
    }

    private int pendingDownloadCount(String siteKey) {
        int count = 0;
        for (ReBrowserDownloads.Record record : downloads) {
            if (!ReBrowserDownloads.STATUS_PENDING.equals(record.status)) continue;
            if (siteKey == null || siteKey.equals(record.profileName + "|" + record.sourceOrigin)) {
                count++;
            }
        }
        return count;
    }

    private void showDownloadConfirmation(
            ReBrowserDownloads.Record record,
            boolean dangerAcknowledged
    ) {
        if (!ReBrowserDownloads.STATUS_PENDING.equals(record.status)) return;
        String warning = record.dangerous
                ? "\n\n⚠ 该文件类型可能包含可执行代码、脚本、宏或主动内容。"
                : "";
        new AlertDialog.Builder(this)
                .setTitle(record.highFrequency ? "确认高频下载？" : "下载文件？")
                .setMessage(record.fileName + "\n"
                        + ReBrowserDownloads.formatBytes(record.declaredSize)
                        + "\n来源：" + record.sourceOrigin + warning)
                .setPositiveButton("下载", (dialog, which) -> {
                    if (record.dangerous && !dangerAcknowledged) {
                        showDangerousDownloadConfirmation(record);
                    } else {
                        approveDownload(record);
                    }
                })
                .setNegativeButton("拒绝", (dialog, which) -> rejectDownload(record))
                .show();
    }

    private void showDangerousDownloadConfirmation(ReBrowserDownloads.Record record) {
        new AlertDialog.Builder(this)
                .setTitle("危险文件二次确认")
                .setMessage("ReBrowser 只会保存文件，不会自动打开、安装、预览、解压或执行。"
                        + "请仅在信任来源时继续。\n\n" + record.fileName)
                .setPositiveButton("仍要下载", (dialog, which) -> approveDownload(record))
                .setNegativeButton("拒绝", (dialog, which) -> rejectDownload(record))
                .show();
    }

    private void approveDownload(ReBrowserDownloads.Record record) {
        if (!ReBrowserDownloads.STATUS_PENDING.equals(record.status)) return;
        if (!downloadStore.downloadsEnabled()) {
            record.status = ReBrowserDownloads.STATUS_REJECTED;
            record.error = "administrator-policy-disabled";
            record.updatedAt = System.currentTimeMillis();
            downloadStore.save(downloads);
            toast("管理员已暂停 ReBrowser 新下载");
            if (downloadOverviewVisible) showDownloadOverview();
            return;
        }
        if (!record.exactRequestAvailable) {
            record.status = ReBrowserDownloads.STATUS_EXPIRED;
            record.error = "exact-request-not-retained-after-restart";
            record.updatedAt = System.currentTimeMillis();
            downloadStore.save(downloads);
            toast("为避免持久化网址令牌，请回到原网页重新发起下载");
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            awaitingStorageDownloadId = record.id;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    DOWNLOAD_STORAGE_REQUEST);
            return;
        }
        if (ReBrowserDownloads.KIND_DATA.equals(record.kind)) {
            record.status = ReBrowserDownloads.STATUS_DOWNLOADING;
            record.updatedAt = System.currentTimeMillis();
            customDownloadIds.add(record.id);
            downloadStore.save(downloads);
            downloadStore.importDataAsync(record, () -> runOnUiThread(() -> {
                customDownloadIds.remove(record.id);
                downloadStore.save(downloads);
                if (downloadOverviewVisible) showDownloadOverview();
                toast(ReBrowserDownloads.STATUS_COMPLETED.equals(record.status)
                        ? "Data 文件已保存；不会自动打开" : "Data 下载失败");
            }));
            return;
        }
        if (ReBrowserDownloads.KIND_BLOB.equals(record.kind)) {
            startBlobTransfer(record);
            return;
        }
        try {
            if (!ReBrowserStore.isOwnedProfile(record.profileName)) {
                throw new SecurityException("non-ReBrowser-profile");
            }
            Profile profile = ProfileStore.getInstance().getProfile(record.profileName);
            if (profile == null) throw new IllegalStateException("profile-unavailable");
            String cookie = profile.getCookieManager().getCookie(record.url);
            String referer = record.sourceOrigin.equals(safeOrigin(record.url))
                    ? record.sourceUrl : record.sourceOrigin + "/";
            downloadStore.enqueueHttp(record, cookie, referer);
            downloadStore.save(downloads);
            toast("已开始下载；不会自动打开文件");
            if (downloadOverviewVisible) showDownloadOverview();
        } catch (Exception error) {
            record.status = ReBrowserDownloads.STATUS_FAILED;
            record.error = error.getClass().getSimpleName() + ":" + error.getMessage();
            record.updatedAt = System.currentTimeMillis();
            downloadStore.save(downloads);
            toast("无法开始下载");
        }
    }

    private void startBlobTransfer(ReBrowserDownloads.Record record) {
        if (activeBlobTransfer != null) {
            record.status = ReBrowserDownloads.STATUS_FAILED;
            record.error = "another-blob-transfer-active";
            downloadStore.save(downloads);
            toast("一次只能提取一个 Blob 文件");
            return;
        }
        WebView webView = tabWebViews.get(record.tabId);
        ReBrowserStore.Tab tab = webView == null ? null : webViewTabs.get(webView);
        if (webView == null || tab == null
                || !record.profileName.equals(activeWorkspace().profileName)
                || !record.sourceOrigin.equals(safeOrigin(webView.getUrl()))) {
            record.status = ReBrowserDownloads.STATUS_EXPIRED;
            record.error = "blob-source-page-unavailable";
            downloadStore.save(downloads);
            toast("Blob 所属页面已离开，请在原网页重新发起下载");
            return;
        }
        if (!webView.getSettings().getJavaScriptEnabled()) {
            record.status = ReBrowserDownloads.STATUS_FAILED;
            record.error = "javascript-disabled";
            downloadStore.save(downloads);
            toast("Blob 提取需要当前网页启用 JavaScript");
            return;
        }
        try {
            File directory = new File(getCacheDir(), "rebrowser-downloads");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("temporary-directory-unavailable");
            }
            BlobTransfer transfer = new BlobTransfer(record, webView,
                    new File(directory, record.id + ".part"));
            activeBlobTransfer = transfer;
            customDownloadIds.add(record.id);
            record.status = ReBrowserDownloads.STATUS_DOWNLOADING;
            record.updatedAt = System.currentTimeMillis();
            downloadStore.save(downloads);
            String key = JSONObject.quote(transfer.key);
            String script = "(()=>{const s={status:'loading',blob:null,error:''};window["
                    + key + "]=s;fetch(" + JSONObject.quote(record.url)
                    + ").then(r=>r.blob()).then(b=>{s.blob=b;s.size=b.size;s.type=b.type||'';"
                    + "s.status='ready';}).catch(e=>{s.error=String(e).slice(0,160);"
                    + "s.status='error';});return true;})()";
            transfer.phaseDeadline = SystemClock.elapsedRealtime() + 30_000L;
            webView.evaluateJavascript(script, ignored -> pollBlobMetadata(transfer));
        } catch (Exception error) {
            failBlobTransfer(activeBlobTransfer, "blob-initialization-failed");
        }
    }

    private void pollBlobMetadata(BlobTransfer transfer) {
        if (transfer != activeBlobTransfer) return;
        String key = JSONObject.quote(transfer.key);
        String script = "(()=>{const s=window[" + key + "];if(!s)return JSON.stringify("
                + "{status:'error',error:'missing-blob-state'});return JSON.stringify("
                + "{status:s.status,error:s.error||'',size:s.size||0,type:s.type||''});})()";
        transfer.webView.evaluateJavascript(script,
                result -> handleBlobMetadataPoll(transfer, result));
    }

    private void handleBlobMetadataPoll(BlobTransfer transfer, String result) {
        if (transfer != activeBlobTransfer) return;
        try {
            JSONObject metadata = new JSONObject(decodeJavascriptResult(result));
            String status = metadata.optString("status");
            if ("loading".equals(status)) {
                if (SystemClock.elapsedRealtime() >= transfer.phaseDeadline) {
                    throw new IllegalStateException("blob-fetch-timeout");
                }
                root.postDelayed(() -> pollBlobMetadata(transfer), 50L);
                return;
            }
            if (!"ready".equals(status)) {
                throw new IllegalStateException(metadata.optString("error", "blob-fetch-failed"));
            }
            long size = metadata.getLong("size");
            if (size < 0 || size > ReBrowserDownloads.MAX_BLOB_BYTES) {
                throw new IllegalArgumentException("blob-size-limit");
            }
            if (size > new StatFs(transfer.temporary.getParent()).getAvailableBytes()) {
                throw new IllegalStateException("insufficient-storage");
            }
            transfer.expectedSize = size;
            transfer.record.totalSize = size;
            String type = metadata.optString("type", "");
            if (!type.isBlank()) transfer.record.mimeType = type;
            transfer.record.dangerous = ReBrowserDownloads.isDangerous(
                    transfer.record.fileName, transfer.record.mimeType);
            requestNextBlobChunk(transfer);
        } catch (Exception error) {
            failBlobTransfer(transfer, "blob-metadata-invalid:" + error.getMessage());
        }
    }

    private void requestNextBlobChunk(BlobTransfer transfer) {
        if (transfer != activeBlobTransfer) return;
        if (transfer.offset >= transfer.expectedSize) {
            finishBlobTransfer(transfer);
            return;
        }
        if (!transfer.record.sourceOrigin.equals(safeOrigin(transfer.webView.getUrl()))) {
            failBlobTransfer(transfer, "blob-source-page-changed");
            return;
        }
        long end = Math.min(transfer.expectedSize, transfer.offset + 128 * 1024L);
        String key = JSONObject.quote(transfer.key);
        String script = "(()=>{const s=window[" + key + "];if(!s||!s.blob)return false;"
                + "s.chunkStatus='loading';s.chunk='';s.chunkError='';s.blob.slice("
                + transfer.offset + "," + end + ").arrayBuffer().then(v=>{const a=new Uint8Array(v);"
                + "let x='';for(let i=0;i<a.length;i+=32768)x+=String.fromCharCode.apply("
                + "null,a.subarray(i,i+32768));s.chunk=btoa(x);s.chunkStatus='ready';})"
                + ".catch(e=>{s.chunkError=String(e).slice(0,160);s.chunkStatus='error';});"
                + "return true;})()";
        transfer.phaseDeadline = SystemClock.elapsedRealtime() + 30_000L;
        transfer.webView.evaluateJavascript(script, ignored -> pollBlobChunk(transfer));
    }

    private void pollBlobChunk(BlobTransfer transfer) {
        if (transfer != activeBlobTransfer) return;
        String key = JSONObject.quote(transfer.key);
        String script = "(()=>{const s=window[" + key + "];if(!s)return JSON.stringify("
                + "{status:'error',error:'missing-blob-state'});return JSON.stringify("
                + "{status:s.chunkStatus||'loading',error:s.chunkError||'',data:s.chunk||''});})()";
        transfer.webView.evaluateJavascript(script, result -> handleBlobChunkPoll(transfer, result));
    }

    private void handleBlobChunkPoll(BlobTransfer transfer, String result) {
        if (transfer != activeBlobTransfer) return;
        try {
            JSONObject chunkResult = new JSONObject(decodeJavascriptResult(result));
            String status = chunkResult.optString("status");
            if ("loading".equals(status)) {
                if (SystemClock.elapsedRealtime() >= transfer.phaseDeadline) {
                    throw new IllegalStateException("blob-chunk-timeout");
                }
                root.postDelayed(() -> pollBlobChunk(transfer), 50L);
                return;
            }
            if (!"ready".equals(status)) {
                throw new IllegalStateException(chunkResult.optString(
                        "error", "blob-chunk-failed"));
            }
            String encoded = chunkResult.optString("data");
            if (encoded.isBlank()) throw new IllegalStateException("empty-blob-chunk");
            byte[] chunk = Base64.decode(encoded, Base64.DEFAULT);
            long remaining = transfer.expectedSize - transfer.offset;
            if (chunk.length <= 0 || chunk.length > remaining || chunk.length > 128 * 1024) {
                throw new IllegalStateException("invalid-blob-chunk-size");
            }
            transfer.output.write(chunk);
            transfer.digest.update(chunk);
            transfer.offset += chunk.length;
            transfer.record.downloadedBytes = transfer.offset;
            transfer.record.updatedAt = System.currentTimeMillis();
            if (transfer.offset % (1024 * 1024L) < chunk.length) {
                downloadStore.save(downloads);
            }
            requestNextBlobChunk(transfer);
        } catch (Exception error) {
            failBlobTransfer(transfer, "blob-chunk-failed:" + error.getMessage());
        }
    }

    private void finishBlobTransfer(BlobTransfer transfer) {
        if (transfer != activeBlobTransfer) return;
        try {
            transfer.output.close();
            transfer.output = null;
            if (transfer.temporary.length() != transfer.expectedSize) {
                throw new IllegalStateException("blob-size-mismatch");
            }
            String digest = hexDigest(transfer.digest.digest());
            cleanupBlobJavascript(transfer);
            activeBlobTransfer = null;
            downloadStore.publishTemporaryFileAsync(
                    transfer.record, transfer.temporary, digest,
                    () -> runOnUiThread(() -> {
                        customDownloadIds.remove(transfer.record.id);
                        downloadStore.save(downloads);
                        if (downloadOverviewVisible) showDownloadOverview();
                        toast(ReBrowserDownloads.STATUS_COMPLETED.equals(transfer.record.status)
                                ? "Blob 文件已保存；不会自动打开" : "Blob 文件保存失败");
                    }));
        } catch (Exception error) {
            failBlobTransfer(transfer, "blob-finalization-failed:" + error.getMessage());
        }
    }

    private void failBlobTransfer(BlobTransfer transfer, String reason) {
        if (transfer == null) return;
        try {
            if (transfer.output != null) transfer.output.close();
        } catch (Exception ignored) {
            // Failure path continues with deletion.
        }
        transfer.temporary.delete();
        cleanupBlobJavascript(transfer);
        customDownloadIds.remove(transfer.record.id);
        transfer.record.status = ReBrowserDownloads.STATUS_FAILED;
        transfer.record.error = reason == null ? "blob-transfer-failed"
                : reason.substring(0, Math.min(300, reason.length()));
        transfer.record.updatedAt = System.currentTimeMillis();
        if (activeBlobTransfer == transfer) activeBlobTransfer = null;
        downloadStore.save(downloads);
        if (downloadOverviewVisible) showDownloadOverview();
    }

    private void cleanupBlobJavascript(BlobTransfer transfer) {
        try {
            transfer.webView.evaluateJavascript(
                    "(()=>{try{delete window[" + JSONObject.quote(transfer.key)
                            + "];return true;}catch(e){return false;}})()", null);
        } catch (RuntimeException ignored) {
            // The source WebView may already have been destroyed.
        }
    }

    private static String decodeJavascriptResult(String result) throws Exception {
        Object decoded = new JSONTokener(result == null ? "null" : result).nextValue();
        if (!(decoded instanceof String)) throw new IllegalStateException("javascript-no-result");
        return (String) decoded;
    }

    private static String hexDigest(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte item : value) output.append(String.format(java.util.Locale.ROOT, "%02x", item));
        return output.toString();
    }

    private void rejectDownload(ReBrowserDownloads.Record record) {
        if (!ReBrowserDownloads.STATUS_PENDING.equals(record.status)) return;
        record.status = ReBrowserDownloads.STATUS_REJECTED;
        record.updatedAt = System.currentTimeMillis();
        downloadStore.save(downloads);
        if (downloadOverviewVisible) showDownloadOverview();
    }

    private void refreshDownloadStates() {
        if (downloadStore == null) return;
        boolean changed = false;
        for (ReBrowserDownloads.Record record : downloads) {
            if (ReBrowserDownloads.STATUS_PENDING.equals(record.status)
                    && !record.exactRequestAvailable) {
                record.status = ReBrowserDownloads.STATUS_EXPIRED;
                record.error = "exact-request-not-retained-after-restart";
                record.updatedAt = System.currentTimeMillis();
                changed = true;
            }
            if (ReBrowserDownloads.STATUS_DOWNLOADING.equals(record.status)) {
                if (record.systemId < 0 && !customDownloadIds.contains(record.id)) {
                    record.status = ReBrowserDownloads.STATUS_FAILED;
                    record.error = "custom-download-interrupted";
                    record.updatedAt = System.currentTimeMillis();
                    changed = true;
                } else {
                    changed |= downloadStore.refresh(record);
                }
            }
            if (ReBrowserDownloads.STATUS_COMPLETED.equals(record.status)
                    && !record.hashAttempted
                    && (record.sha256 == null || record.sha256.isBlank())
                    && hashingDownloadIds.add(record.id)) {
                downloadStore.computeSha256Async(record, () -> runOnUiThread(() -> {
                    hashingDownloadIds.remove(record.id);
                    downloadStore.save(downloads);
                    if (downloadOverviewVisible) showDownloadOverview();
                }));
            }
        }
        if (changed) downloadStore.save(downloads);
    }

    private boolean makeDownloadRecordRoom() {
        while (downloads.size() >= ReBrowserDownloads.MAX_RECORDS) {
            int removable = findOldestRemovableDownload();
            if (removable < 0) return false;
            downloads.remove(removable);
        }
        return true;
    }

    private void trimDownloadHistory() {
        while (downloads.size() > ReBrowserDownloads.MAX_RECORDS) {
            int removable = findOldestRemovableDownload();
            if (removable < 0) return;
            downloads.remove(removable);
        }
    }

    private int findOldestRemovableDownload() {
        for (int index = downloads.size() - 1; index >= 0; index--) {
            ReBrowserDownloads.Record record = downloads.get(index);
            if (!ReBrowserDownloads.STATUS_DOWNLOADING.equals(record.status)
                    && !ReBrowserDownloads.STATUS_PENDING.equals(record.status)) {
                return index;
            }
        }
        return -1;
    }

    private static final class BlobTransfer {
        final ReBrowserDownloads.Record record;
        final WebView webView;
        final String key;
        final File temporary;
        FileOutputStream output;
        MessageDigest digest;
        long expectedSize;
        long offset;
        long phaseDeadline;

        BlobTransfer(ReBrowserDownloads.Record record, WebView webView, File temporary)
                throws Exception {
            this.record = record;
            this.webView = webView;
            this.key = "__rebrowser_download_" + record.id;
            this.temporary = temporary;
            output = new FileOutputStream(temporary);
            digest = MessageDigest.getInstance("SHA-256");
        }
    }

    private ReBrowserDownloads.Record findDownload(String id) {
        for (ReBrowserDownloads.Record record : downloads) {
            if (record.id.equals(id)) return record;
        }
        return null;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != DOWNLOAD_STORAGE_REQUEST) return;
        ReBrowserDownloads.Record record = findDownload(awaitingStorageDownloadId);
        awaitingStorageDownloadId = null;
        if (record == null) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            approveDownload(record);
        } else {
            record.status = ReBrowserDownloads.STATUS_FAILED;
            record.error = "storage-permission-denied";
            record.updatedAt = System.currentTimeMillis();
            downloadStore.save(downloads);
            toast("没有存储权限，无法下载");
        }
    }

    private boolean isVisibleWebView(WebView webView) {
        return webView != null && webView.getParent() == webContainer;
    }
}
