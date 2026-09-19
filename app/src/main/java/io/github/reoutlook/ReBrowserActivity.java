package io.github.reoutlook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
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

import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private static final long DOUBLE_BACK_INTERVAL_MS = 2_000L;

    private final List<ReBrowserStore.Workspace> workspaces = new ArrayList<>();
    private final List<ReBrowserStore.Workspace> shelvedSecondaryWorkspaces = new ArrayList<>();
    private final List<ReBrowserFavorites.Favorite> bookmarks = new ArrayList<>();
    private final Map<String, WebView> tabWebViews = new HashMap<>();
    private final Map<WebView, ReBrowserStore.Tab> webViewTabs = new IdentityHashMap<>();
    private final Set<String> loadedTabIds = new java.util.HashSet<>();
    private final Set<String> selectedWorkspaceIds = new java.util.HashSet<>();
    private final Set<String> selectedChildTabIds = new java.util.HashSet<>();
    private final Map<String, Integer> tabLoadProgress = new HashMap<>();
    private final Map<String, String> tabLastErrors = new HashMap<>();
    private final Set<String> loadingTabIds = new java.util.HashSet<>();
    private final Map<String, JSONObject> pendingAdminLoads = new HashMap<>();

    private ReBrowserStore store;
    private ReBrowserPreferences browserPreferences;
    private ReBrowserFavorites bookmarkStore;
    private ReBrowserStore.Workspace activeWorkspace;
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
    private boolean workspaceSelectionMode;
    private boolean childSelectionMode;
    private long lastBackPressAt;
    private ValueCallback<Uri[]> pendingFileChooser;
    private FrameLayout fullscreenContainer;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int systemUiBeforeFullscreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBackDispatcher.register(this, this::handleSystemBack);
        store = new ReBrowserStore(this);
        browserPreferences = new ReBrowserPreferences(this);
        applyGlobalOrientationPreference();
        bookmarkStore = new ReBrowserFavorites(this);
        bookmarks.addAll(bookmarkStore.load());
        root = createRoot();
        setContentView(root);

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            showUnsupportedProvider();
            handleAdminCommand(getIntent());
            return;
        }

        deletePendingProfiles();
        workspaces.addAll(store.loadPersistentWorkspaces());
        shelvedSecondaryWorkspaces.addAll(store.loadShelvedSecondaryWorkspaces(workspaces));
        if (workspaces.isEmpty()) workspaces.add(newTemporaryWorkspace());
        activeWorkspace = chooseInitialWorkspace();
        activateWorkspace(activeWorkspace);
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
        if (shelved && !store.restoreSecondary(
                workspace, workspaces, shelvedSecondaryWorkspaces)) {
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
                    store.deleteShelvedSecondary(workspace, shelvedSecondaryWorkspaces);
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
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        return background;
    }

    private void updateChromeUi() {
        if (activeWorkspace == null) return;
        ReBrowserStore.Tab activeTab = activeWorkspace.activeTab();
        workspaceCountButton.setText(Integer.toString(workspaces.size()));
        workspaceCountButton.setContentDescription(
                workspaces.size() + " 个总标签页；管理总标签页");
        if (!omnibox.hasFocus() && activeTab != null) omnibox.setText(activeTab.url);
    }

    private void activateWorkspace(ReBrowserStore.Workspace workspace) {
        if (workspace == activeWorkspace && !tabWebViews.isEmpty()) return;
        if (activeWorkspace != null && workspace != activeWorkspace) {
            for (ReBrowserStore.Tab tab : activeWorkspace.tabs) {
                JSONObject pending = pendingAdminLoads.remove(tab.id);
                if (pending != null) {
                    recordAdminStatus(pending, "failed", null, "workspace-deactivated");
                }
            }
        }
        saveCurrentTabStates();
        destroyTabWebViews();
        activeWorkspace = workspace;
        ReBrowserStore.Tab tab = workspace.activeTab();
        if (tab == null) tab = store.addTab(workspace, browserPreferences.homeUrl());
        if (tab != null) showTab(tab);
        saveWorkspaceMetadata();
        updateChromeUi();
    }

    private void showTab(ReBrowserStore.Tab tab) {
        if (activeWorkspace == null || !activeWorkspace.tabs.contains(tab)) return;
        saveVisibleTabState();
        activeWorkspace.activeTabId = tab.id;
        WebView webView = tabWebViews.get(tab.id);
        if (webView == null) {
            try {
                webView = createBrowserWebView(activeWorkspace.profileName, tab);
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
        webView.setDownloadListener(new ExternalDownloadListener());
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
        ReBrowserStore.Tab tab = activeWorkspace == null ? null : activeWorkspace.activeTab();
        if (tab == null) return;
        String url = normalizeAddress(input);
        tab.url = url;
        WebView webView = tabWebViews.get(tab.id);
        if (webView == null) showTab(tab);
        else webView.loadUrl(url);
        loadedTabIds.add(tab.id);
        saveWorkspaceMetadata();
        omnibox.setText(url);
    }

    private void toggleCurrentBookmark() {
        ReBrowserStore.Tab tab = activeWorkspace == null ? null : activeWorkspace.activeTab();
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
        ReBrowserStore.Tab tab = activeWorkspace == null ? null : activeWorkspace.activeTab();
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
        if (activeWorkspace == null) return;
        ReBrowserStore.Tab tab = store.addTab(activeWorkspace, browserPreferences.homeUrl());
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
        if (workspaces.size() + shelvedSecondaryWorkspaces.size()
                >= ReBrowserStore.MAX_WORKSPACES) {
            toast("当前与书签栏中的总标签页数量已达到上限");
            return;
        }
        ReBrowserStore.Workspace workspace = newTemporaryWorkspace();
        workspaces.add(workspace);
        hideOverview();
        activateWorkspace(workspace);
    }

    private ReBrowserStore.Workspace newTemporaryWorkspace() {
        ReBrowserStore.Workspace workspace = store.createTemporaryWorkspace();
        ReBrowserStore.Tab tab = workspace.activeTab();
        if (tab != null) tab.url = browserPreferences.homeUrl();
        return workspace;
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
        if (activeWorkspace == null) throw new IllegalStateException("ReBrowser-disabled");
        if (ReBrowserAdminProtocol.OP_GET_STATE.equals(operation)) return createAdminState();
        if (ReBrowserAdminProtocol.OP_VALIDATE_STATE.equals(operation)) return validateAdminState();
        if (ReBrowserAdminProtocol.OP_REPAIR_STATE.equals(operation)) return repairAdminState();
        if (ReBrowserAdminProtocol.OP_NEW_WORKSPACE.equals(operation)) {
            if (workspaces.size() + shelvedSecondaryWorkspaces.size()
                    >= ReBrowserStore.MAX_WORKSPACES) {
                throw new IllegalStateException("workspace-limit-reached");
            }
            ReBrowserStore.Workspace workspace = newTemporaryWorkspace();
            workspaces.add(workspace);
            hideOverview();
            activateWorkspace(workspace);
            return adminTargetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_NEW_CHILD_TAB.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            activateWorkspace(workspace);
            ReBrowserStore.Tab tab = store.addTab(workspace, browserPreferences.homeUrl());
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
            if (!store.lockAsSecondary(workspace, workspaces)) {
                throw new IllegalStateException("workspace-cannot-lock");
            }
            refreshLifecycleUi();
            return adminTargetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_UNLOCK_TEMPORARY.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            if (!store.unlockToTemporary(workspace, workspaces)) {
                throw new IllegalStateException("workspace-cannot-unlock");
            }
            refreshLifecycleUi();
            return adminTargetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_PROMOTE_PRIMARY.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            if (!store.promoteToPrimary(workspace, workspaces,
                    browserPreferences.forcePrimaryPromotionEnabled())) {
                throw new IllegalStateException("workspace-cannot-promote");
            }
            refreshLifecycleUi();
            return adminTargetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_DEMOTE_SECONDARY.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, false);
            if (!store.demotePrimaryToSecondary(workspace, workspaces)) {
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
            if (!shelvedSecondaryWorkspaces.contains(workspace)
                    || !store.restoreSecondary(workspace, workspaces,
                            shelvedSecondaryWorkspaces)) {
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
        if (ReBrowserAdminProtocol.OP_DELETE_SHELVED.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireAdminWorkspace(request, true);
            if (!shelvedSecondaryWorkspaces.contains(workspace)) {
                throw new IllegalStateException("shelved-workspace-not-found");
            }
            store.deleteShelvedSecondary(workspace, shelvedSecondaryWorkspaces);
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
        if (workspaceId.isBlank() && !shelvedOnly) return activeWorkspace;
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
            if (fullscreenContainer == null) applyGlobalOrientationPreference();
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
        return value;
    }

    private JSONObject createAdminDiagnostics() throws Exception {
        JSONObject value = new JSONObject();
        android.content.pm.PackageInfo provider = WebView.getCurrentWebViewPackage();
        value.put("webViewPackage", provider == null ? "" : provider.packageName);
        value.put("webViewVersion", provider == null ? "" : provider.versionName);
        value.put("multiProfile",
                WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE));
        value.put("activeWorkspaceId", activeWorkspace == null ? "" : activeWorkspace.id);
        ReBrowserStore.Tab tab = activeWorkspace == null ? null : activeWorkspace.activeTab();
        value.put("activeTabId", tab == null ? "" : tab.id);
        value.put("activeOrigin", tab == null ? "" : safeOrigin(tab.url));
        value.put("loading", tab != null && loadingTabIds.contains(tab.id));
        value.put("loadProgress", tab == null ? 0 : tabLoadProgress.getOrDefault(tab.id, 0));
        value.put("lastMainFrameError", tab == null ? ""
                : tabLastErrors.getOrDefault(tab.id, ""));
        value.put("fullscreenVideo", fullscreenContainer != null);
        value.put("globalOrientation", browserPreferences.globalOrientation());
        value.put("videoOrientationOverride",
                browserPreferences.videoOrientationOverrideEnabled());
        value.put("videoOrientation", browserPreferences.videoOrientation());
        value.put("activeWorkspaceCount", workspaces.size());
        value.put("shelvedWorkspaceCount", shelvedSecondaryWorkspaces.size());
        value.put("pendingProfileDeletionCount", store == null
                ? 0 : store.pendingProfileDeletions().size());
        return value;
    }

    private JSONObject createAdminState() throws Exception {
        JSONObject state = new JSONObject();
        state.put("version", 2);
        state.put("activeWorkspaceId", activeWorkspace == null ? "" : activeWorkspace.id);
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
        for (ReBrowserStore.Workspace workspace : workspaces) {
            if (!workspaceIds.add(workspace.id)) issues.put("duplicate-workspace:" + workspace.id);
            if (workspace.level == ReBrowserStore.Level.PRIMARY) primaryCount++;
            if (workspace.activeTab() == null) issues.put("missing-active-tab:" + workspace.id);
            for (ReBrowserStore.Tab tab : workspace.tabs) {
                if (!tabIds.add(tab.id)) issues.put("duplicate-tab:" + tab.id);
            }
            if (workspace.level != ReBrowserStore.Level.TEMPORARY
                    && store.pendingProfileDeletions().contains(workspace.profileName)) {
                issues.put("persistent-profile-pending-deletion:" + workspace.id);
            }
        }
        for (ReBrowserStore.Workspace workspace : shelvedSecondaryWorkspaces) {
            if (!workspaceIds.add(workspace.id)) issues.put("duplicate-workspace:" + workspace.id);
            if (workspace.level != ReBrowserStore.Level.SECONDARY) {
                issues.put("invalid-shelved-level:" + workspace.id);
            }
            if (store.pendingProfileDeletions().contains(workspace.profileName)) {
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
        for (ReBrowserStore.Workspace workspace : workspaces) {
            workspace.activeTab();
            if (workspace.level != ReBrowserStore.Level.TEMPORARY
                    && store.pendingProfileDeletions().contains(workspace.profileName)) {
                store.unmarkProfileForDeletion(workspace.profileName);
                cancelledDeletionMarkers++;
            }
        }
        for (ReBrowserStore.Workspace workspace : shelvedSecondaryWorkspaces) {
            workspace.activeTab();
            if (store.pendingProfileDeletions().contains(workspace.profileName)) {
                store.unmarkProfileForDeletion(workspace.profileName);
                cancelledDeletionMarkers++;
            }
        }
        store.save(workspaces);
        store.saveShelvedSecondaryWorkspaces(shelvedSecondaryWorkspaces);
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
            JSONObject details = adminTargetDetails(activeWorkspace, tab);
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
        if (activeWorkspace == null || !activeWorkspace.tabs.contains(tab)) return;
        JSONObject pendingLoad = pendingAdminLoads.remove(tab.id);
        if (pendingLoad != null) {
            recordAdminStatus(pendingLoad, "failed", null, "tab-closed-during-load");
        }
        if (activeWorkspace.tabs.size() == 1) {
            tab.title = "新子标签页";
            tab.url = browserPreferences.homeUrl();
            WebView current = tabWebViews.get(tab.id);
            if (current != null) current.loadUrl(tab.url);
            loadedTabIds.add(tab.id);
        } else {
            int oldIndex = activeWorkspace.tabs.indexOf(tab);
            activeWorkspace.tabs.remove(tab);
            WebView removed = tabWebViews.remove(tab.id);
            webViewTabs.remove(removed);
            loadedTabIds.remove(tab.id);
            if (removed != null) {
                if (removed.getParent() instanceof ViewGroup) {
                    ((ViewGroup) removed.getParent()).removeView(removed);
                }
                removed.destroy();
            }
            if (tab.id.equals(activeWorkspace.activeTabId)) {
                int nextIndex = Math.min(oldIndex, activeWorkspace.tabs.size() - 1);
                showTab(activeWorkspace.tabs.get(nextIndex));
            }
        }
        saveWorkspaceMetadata();
        updateChromeUi();
        if (childOverviewVisible) showChildOverview();
    }

    private void showWorkspaceSettings() {
        if (activeWorkspace == null || activeWorkspace.level == ReBrowserStore.Level.TEMPORARY) return;
        List<String> actions = new ArrayList<>();
        actions.add("重命名总标签页");
        if (activeWorkspace.level == ReBrowserStore.Level.SECONDARY) {
            actions.add("取消上锁并降级为临时总标签页");
            actions.add("提升为主总标签页");
        }
        new AlertDialog.Builder(this)
                .setTitle("总标签页设置")
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) {
                        showRenameDialog();
                    } else if (which == 1) {
                        unlockWorkspace(activeWorkspace);
                    } else {
                        if (store.promoteToPrimary(activeWorkspace, workspaces, false)) {
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
        if (!store.unlockToTemporary(workspace, workspaces)) return;
        toast("已移出书签栏；关闭后将清除此临时总标签页");
        updateChromeUi();
        if (workspaceOverviewVisible) showWorkspaceOverview();
        if (workspaceBookmarkOverviewVisible) showWorkspaceBookmarkOverview();
    }

    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(activeWorkspace.title);
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
                    activeWorkspace.title = value.substring(0, Math.min(value.length(), 160));
                    store.save(workspaces);
                    updateChromeUi();
                    if (workspaceOverviewVisible) showWorkspaceOverview();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmCloseWorkspace() {
        if (activeWorkspace != null) confirmCloseWorkspace(activeWorkspace);
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
        boolean wasActive = closing == activeWorkspace;
        if (wasActive) {
            saveCurrentTabStates();
            destroyTabWebViews();
        }
        if (closing.level == ReBrowserStore.Level.SECONDARY) {
            store.shelfSecondary(closing, workspaces, shelvedSecondaryWorkspaces);
        } else {
            store.removeWorkspace(closing, workspaces);
            deleteProfileIfPossible(closing.profileName);
        }
        if (workspaces.isEmpty()) workspaces.add(newTemporaryWorkspace());
        if (wasActive) {
            activeWorkspace = chooseInitialWorkspace();
            activateWorkspace(activeWorkspace);
            hideOverview();
        } else if (workspaceOverviewVisible) {
            showWorkspaceOverview();
        }
        updateChromeUi();
    }

    private ReBrowserStore.Workspace chooseInitialWorkspace() {
        for (ReBrowserStore.Workspace workspace : workspaces) {
            if (workspace.level == ReBrowserStore.Level.PRIMARY) return workspace;
        }
        return workspaces.get(0);
    }

    private void saveVisibleTabState() {
        if (activeWorkspace == null) return;
        ReBrowserStore.Tab tab = activeWorkspace.activeTab();
        WebView webView = tab == null ? null : tabWebViews.get(tab.id);
        if (tab == null || webView == null) return;
        String url = webView.getUrl();
        if (url != null) tab.url = ReBrowserStore.safeUrl(url);
        String title = webView.getTitle();
        if (title != null && !title.isBlank()) {
            tab.title = title.substring(0, Math.min(title.length(), 160));
        }
    }

    private void saveCurrentTabStates() {
        for (Map.Entry<WebView, ReBrowserStore.Tab> entry : webViewTabs.entrySet()) {
            WebView webView = entry.getKey();
            ReBrowserStore.Tab tab = entry.getValue();
            String url = webView.getUrl();
            if (url != null) tab.url = ReBrowserStore.safeUrl(url);
            String title = webView.getTitle();
            if (title != null && !title.isBlank()) {
                tab.title = title.substring(0, Math.min(title.length(), 160));
            }
        }
        saveWorkspaceMetadata();
    }

    private void saveWorkspaceMetadata() {
        if (store != null) store.save(workspaces);
    }

    private void destroyTabWebViews() {
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
        for (String profileName : store.pendingProfileDeletions()) {
            deleteProfileIfPossible(profileName);
        }
    }

    private void deleteProfileIfPossible(String profileName) {
        if (!ReBrowserStore.isOwnedProfile(profileName)) return;
        try {
            ProfileStore.getInstance().deleteProfile(profileName);
            store.unmarkProfileForDeletion(profileName);
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
        activeWorkspace = null;
        browserToolbar.setVisibility(View.GONE);
    }

    private void showBrowserMenu(View anchor) {
        if (activeWorkspace == null) return;
        WebView webView = activeWebView();
        ReBrowserStore.Tab tab = activeWorkspace.activeTab();
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
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
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

        addMenuListItem(panel, R.drawable.ic_rb_star_filled, "收藏夹", () -> {
            popup.dismiss();
            showBookmarkOverview();
        });
        addMenuListItem(panel, R.drawable.ic_rb_workspaces, "书签栏", () -> {
            popup.dismiss();
            showWorkspaceBookmarkOverview();
        });
        boolean primary = activeWorkspace.level == ReBrowserStore.Level.PRIMARY;
        boolean temporaryPromotionAllowed = activeWorkspace.level != ReBrowserStore.Level.TEMPORARY
                || browserPreferences.forcePrimaryPromotionEnabled();
        boolean primaryActionEnabled = primary
                || temporaryPromotionAllowed && store.canPromoteToPrimary(workspaces);
        addMenuListItem(panel,
                primary ? R.drawable.ic_rb_primary_cancel : R.drawable.ic_rb_primary_promote,
                primary ? "取消主标签页" : "提升为主标签页",
                primaryActionEnabled,
                () -> {
                    popup.dismiss();
                    confirmPrimaryLevelChange(activeWorkspace);
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
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        browserPreferences.setGlobalOrientation(landscape
                ? ReBrowserPreferences.ORIENTATION_PORTRAIT
                : ReBrowserPreferences.ORIENTATION_LANDSCAPE);
        applyGlobalOrientationPreference();
        toast(landscape ? "已锁定竖屏" : "已锁定横屏；横屏朝向跟随传感器");
    }

    private void clearOrientationLock() {
        browserPreferences.setGlobalOrientation(ReBrowserPreferences.ORIENTATION_UNLOCKED);
        applyGlobalOrientationPreference();
        toast("已取消 ReBrowser 方向锁定");
    }

    private void applyGlobalOrientationPreference() {
        String mode = browserPreferences.globalOrientation();
        if (ReBrowserPreferences.ORIENTATION_LANDSCAPE.equals(mode)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else if (ReBrowserPreferences.ORIENTATION_UNLOCKED.equals(mode)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    private int effectiveVideoOrientation() {
        String mode;
        if (browserPreferences.videoOrientationOverrideEnabled()) {
            mode = browserPreferences.videoOrientation();
        } else {
            mode = ReBrowserPreferences.ORIENTATION_UNLOCKED.equals(
                    browserPreferences.globalOrientation())
                    ? ReBrowserPreferences.VIDEO_ORIENTATION_AUTO
                    : ReBrowserPreferences.ORIENTATION_LANDSCAPE;
        }
        if (ReBrowserPreferences.VIDEO_ORIENTATION_AUTO.equals(mode)) {
            return ActivityInfo.SCREEN_ORIENTATION_SENSOR;
        }
        if (ReBrowserPreferences.ORIENTATION_PORTRAIT.equals(mode)) {
            return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
        return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
    }

    private void showFullscreenContent(
            View customView,
            WebChromeClient.CustomViewCallback callback
    ) {
        if (fullscreenContainer != null) {
            callback.onCustomViewHidden();
            return;
        }
        systemUiBeforeFullscreen = getWindow().getDecorView().getSystemUiVisibility();
        fullscreenCallback = callback;
        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        if (customView.getParent() instanceof ViewGroup) {
            ((ViewGroup) customView.getParent()).removeView(customView);
        }
        fullscreenContainer.addView(customView, matchMatch());
        ViewGroup content = findViewById(android.R.id.content);
        content.addView(fullscreenContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setFullscreenSystemUi(true);
        setRequestedOrientation(effectiveVideoOrientation());
    }

    private void hideFullscreenContent() {
        if (fullscreenContainer == null) return;
        ViewGroup parent = (ViewGroup) fullscreenContainer.getParent();
        if (parent != null) parent.removeView(fullscreenContainer);
        fullscreenContainer.removeAllViews();
        fullscreenContainer = null;
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenCallback = null;
        applyGlobalOrientationPreference();
        setFullscreenSystemUi(false);
        if (callback != null) callback.onCustomViewHidden();
    }

    private void setFullscreenSystemUi(boolean fullscreen) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (fullscreen) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
        } else if (fullscreen) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(systemUiBeforeFullscreen);
        }
        if (!fullscreen && root != null) WindowStyling.apply(this, root);
    }

    private void confirmPrimaryLevelChange(ReBrowserStore.Workspace workspace) {
        if (workspace == null || workspace != activeWorkspace || !workspaces.contains(workspace)) {
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
                        if (store.demotePrimaryToSecondary(workspace, workspaces)) {
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
                    if (store.promoteToPrimary(workspace, workspaces, allowTemporary)) {
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
            if (store.lockAsSecondary(workspace, workspaces)) changed++;
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
            if (store.unlockToTemporary(workspace, workspaces)) changed++;
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
        boolean activeClosing = closing.contains(activeWorkspace);
        if (activeClosing) {
            saveCurrentTabStates();
            destroyTabWebViews();
        }
        for (ReBrowserStore.Workspace workspace : closing) {
            if (workspace.level == ReBrowserStore.Level.SECONDARY) {
                store.shelfSecondary(workspace, workspaces, shelvedSecondaryWorkspaces);
            } else {
                store.removeWorkspace(workspace, workspaces);
                deleteProfileIfPossible(workspace.profileName);
            }
        }
        if (workspaces.isEmpty()) workspaces.add(newTemporaryWorkspace());
        if (activeClosing) {
            activeWorkspace = chooseInitialWorkspace();
            activateWorkspace(activeWorkspace);
        }
        workspaceSelectionMode = false;
        selectedWorkspaceIds.clear();
        updateChromeUi();
        showWorkspaceOverview();
    }

    private void showWorkspaceOverview() {
        if (activeWorkspace == null) return;
        childSelectionMode = false;
        selectedChildTabIds.clear();
        saveCurrentTabStates();
        workspaceOverviewVisible = true;
        childOverviewVisible = false;
        bookmarkOverviewVisible = false;
        workspaceBookmarkOverviewVisible = false;
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
            card.setElevation(selected || workspace == activeWorkspace ? dp(6) : dp(2));
            GradientDrawable background = roundedBackground(
                    selected ? Color.rgb(224, 218, 249)
                            : workspace == activeWorkspace
                                    ? Color.rgb(235, 231, 250) : Color.WHITE,
                    dp(18));
            if (selected || workspace == activeWorkspace) {
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
                        if (store.lockAsSecondary(workspace, workspaces)) {
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
        if (activeWorkspace == null) return;
        List<ReBrowserStore.Tab> closing = new ArrayList<>();
        for (ReBrowserStore.Tab tab : activeWorkspace.tabs) {
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
        if (activeWorkspace == null) return;
        workspaceSelectionMode = false;
        selectedWorkspaceIds.clear();
        saveCurrentTabStates();
        childOverviewVisible = true;
        workspaceOverviewVisible = false;
        bookmarkOverviewVisible = false;
        workspaceBookmarkOverviewVisible = false;
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
                        : "总标签页 · " + activeWorkspace.title,
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
        for (ReBrowserStore.Tab tab : new ArrayList<>(activeWorkspace.tabs)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(10), dp(10));
            boolean selected = selectedChildTabIds.contains(tab.id);
            boolean active = tab.id.equals(activeWorkspace.activeTabId);
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
        if (activeWorkspace == null) return;
        saveCurrentTabStates();
        bookmarkOverviewVisible = true;
        workspaceOverviewVisible = false;
        childOverviewVisible = false;
        workspaceBookmarkOverviewVisible = false;
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

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(8), dp(8), dp(8), dp(28));
        int cardWidth = Math.max(dp(150),
                (getResources().getDisplayMetrics().widthPixels - dp(40)) / 2);
        for (ReBrowserFavorites.Favorite bookmark : new ArrayList<>(bookmarks)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(10), dp(12));
            card.setElevation(dp(2));
            card.setBackground(roundedBackground(Color.WHITE, dp(18)));
            card.setOnClickListener(view -> {
                hideOverview();
                navigateActiveTab(bookmark.url);
            });

            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView icon = new TextView(this);
            icon.setText("★");
            icon.setTextSize(17);
            icon.setTextColor(Color.WHITE);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(roundedBackground(Color.rgb(242, 167, 48), dp(16)));
            heading.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
            heading.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            TextView remove = toolbarButton("×", "删除收藏 " + bookmark.title);
            remove.setOnClickListener(view -> confirmRemoveBookmark(bookmark));
            heading.addView(remove, new LinearLayout.LayoutParams(dp(36), dp(36)));
            card.addView(heading, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

            TextView titleView = new TextView(this);
            titleView.setText(bookmark.title);
            titleView.setTextSize(16);
            titleView.setTextColor(Color.rgb(32, 37, 46));
            titleView.setTypeface(null, android.graphics.Typeface.BOLD);
            titleView.setMaxLines(3);
            card.addView(titleView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            TextView urlView = new TextView(this);
            urlView.setText(displayUrl(bookmark.url));
            urlView.setTextSize(11);
            urlView.setTextColor(Color.rgb(100, 106, 116));
            urlView.setSingleLine(true);
            card.addView(urlView, new LinearLayout.LayoutParams(
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

    private void showWorkspaceBookmarkOverview() {
        if (activeWorkspace == null) return;
        saveCurrentTabStates();
        workspaceBookmarkOverviewVisible = true;
        bookmarkOverviewVisible = false;
        workspaceOverviewVisible = false;
        childOverviewVisible = false;
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

    private WebView activeWebView() {
        ReBrowserStore.Tab tab = activeWorkspace == null ? null : activeWorkspace.activeTab();
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
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private FrameLayout.LayoutParams matchWrap() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        if (fullscreenContainer != null) {
            hideFullscreenContent();
            return;
        }
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
        if (bookmarkOverviewVisible || workspaceBookmarkOverviewVisible) {
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
        ReBrowserStore.Tab tab = activeWorkspace == null ? null : activeWorkspace.activeTab();
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
        if (root != null) root.requestApplyInsets();
        if (fullscreenContainer != null) fullscreenContainer.requestLayout();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreenContainer != null) setFullscreenSystemUi(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (WebView webView : tabWebViews.values()) applyBrowserPreferences(webView);
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
        hideFullscreenContent();
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

    private final class WorkspaceWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            ReBrowserStore.Tab tab = webViewTabs.get(view);
            if (tab != null) {
                tab.url = ReBrowserStore.safeUrl(url);
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
                tab.url = ReBrowserStore.safeUrl(url);
                String title = view.getTitle();
                if (title != null && !title.isBlank()) {
                    tab.title = title.substring(0, Math.min(title.length(), 160));
                    if (activeWorkspace != null
                            && activeWorkspace.level == ReBrowserStore.Level.TEMPORARY
                            && tab.id.equals(activeWorkspace.activeTabId)) {
                        activeWorkspace.title = tab.title;
                    }
                }
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
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException error) {
                toast("没有能够打开此链接的应用");
            }
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
            showFullscreenContent(view, callback);
        }

        @Override
        public void onHideCustomView() {
            hideFullscreenContent();
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
            tab.title = title.substring(0, Math.min(title.length(), 160));
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
            if (!isUserGesture || activeWorkspace == null) return false;
            ReBrowserStore.Tab tab = store.addTab(activeWorkspace, "about:blank");
            if (tab == null) {
                toast("每个总标签页最多 50 个子标签页");
                return false;
            }
            try {
                WebView popup = createBrowserWebView(activeWorkspace.profileName, tab);
                tabWebViews.put(tab.id, popup);
                loadedTabIds.add(tab.id);
                showTab(tab);
                WebView.WebViewTransport transport =
                        (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            } catch (Throwable error) {
                activeWorkspace.tabs.remove(tab);
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

    private final class ExternalDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(
                String url,
                String userAgent,
                String contentDisposition,
                String mimeType,
                long contentLength
        ) {
            toast("尚未实现内置下载管理器");
        }
    }

    private boolean isVisibleWebView(WebView webView) {
        return webView != null && webView.getParent() == webContainer;
    }
}
