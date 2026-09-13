package io.github.reoutlook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
@SuppressLint({"ClickableViewAccessibility", "RequiresFeature", "SetJavaScriptEnabled", "SetTextI18n"})
public final class ReBrowserActivity extends Activity {
    static final String ACTION_ADMIN_COMMAND = "io.github.reoutlook.action.REBROWSER_ADMIN";
    static final String EXTRA_ADMIN_OPERATION = "adminOperation";
    static final String EXTRA_ADMIN_URL = "adminUrl";
    private static final int FILE_CHOOSER_REQUEST = 5102;
    private static final long DOUBLE_BACK_INTERVAL_MS = 2_000L;

    private final List<ReBrowserStore.Workspace> workspaces = new ArrayList<>();
    private final Map<String, WebView> tabWebViews = new HashMap<>();
    private final Map<WebView, ReBrowserStore.Tab> webViewTabs = new IdentityHashMap<>();
    private final Set<String> loadedTabIds = new java.util.HashSet<>();

    private ReBrowserStore store;
    private ReBrowserPreferences browserPreferences;
    private ReBrowserStore.Workspace activeWorkspace;
    private FrameLayout root;
    private FrameLayout webContainer;
    private LinearLayout browserToolbar;
    private EditText omnibox;
    private TextView workspaceCountButton;
    private LinearLayout childTabStrip;
    private HorizontalScrollView childStripScroller;
    private LinearLayout childStripContent;
    private TextView childCountButton;
    private ProgressBar progressBar;
    private FrameLayout overviewContainer;
    private boolean workspaceOverviewVisible;
    private boolean childOverviewVisible;
    private long lastBackPressAt;
    private ValueCallback<Uri[]> pendingFileChooser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBackDispatcher.register(this, this::handleSystemBack);
        store = new ReBrowserStore(this);
        browserPreferences = new ReBrowserPreferences(this);
        root = createRoot();
        setContentView(root);

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            showUnsupportedProvider();
            return;
        }

        deletePendingProfiles();
        workspaces.addAll(store.loadPersistentWorkspaces());
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
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(),
                    0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        webContainer = new FrameLayout(this);
        FrameLayout.LayoutParams webParams = matchMatch();
        webParams.topMargin = dp(64);
        webParams.bottomMargin = dp(58);
        content.addView(webContainer, webParams);

        browserToolbar = createBrowserToolbar();
        content.addView(browserToolbar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.TOP));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.TOP);
        progressParams.topMargin = dp(62);
        content.addView(progressBar, progressParams);

        childTabStrip = createChildTabStrip();
        content.addView(childTabStrip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.BOTTOM));

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

        TextView home = toolbarButton("⌂", "打开当前子 Tab 的主页");
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
        addressParams.setMargins(dp(2), 0, dp(7), 0);
        toolbar.addView(omnibox, addressParams);

        workspaceCountButton = toolbarButton("1", "打开总标签页总览");
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

    private LinearLayout createChildTabStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(6), dp(4), dp(6), dp(4));
        strip.setBackgroundColor(Color.WHITE);
        strip.setElevation(dp(5));

        childStripScroller = new HorizontalScrollView(this);
        childStripScroller.setHorizontalScrollBarEnabled(false);
        childStripContent = new LinearLayout(this);
        childStripContent.setOrientation(LinearLayout.HORIZONTAL);
        childStripContent.setGravity(Gravity.CENTER_VERTICAL);
        childStripScroller.addView(childStripContent, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        strip.addView(childStripScroller, new LinearLayout.LayoutParams(0, dp(50), 1));

        childCountButton = toolbarButton("1", "当前工作区的子 Tab 总览");
        childCountButton.setOnClickListener(view -> showChildOverview());
        childCountButton.setBackground(roundedBackground(Color.rgb(233, 229, 249), dp(18)));
        strip.addView(childCountButton, new LinearLayout.LayoutParams(dp(44), dp(42)));
        return strip;
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
                workspaces.size() + " 个总标签页；打开总标签页总览");
        childCountButton.setText(Integer.toString(activeWorkspace.tabs.size()));
        childCountButton.setContentDescription(activeWorkspace.tabs.size()
                + " 个子 Tab；打开当前工作区子 Tab 总览");
        if (!omnibox.hasFocus() && activeTab != null) omnibox.setText(activeTab.url);
        rebuildChildTabStrip();
    }

    private void rebuildChildTabStrip() {
        childStripContent.removeAllViews();
        if (activeWorkspace == null) return;
        for (ReBrowserStore.Tab tab : activeWorkspace.tabs) {
            TextView chip = new TextView(this);
            boolean selected = tab.id.equals(activeWorkspace.activeTabId);
            String title = tab.title == null || tab.title.isBlank() ? "新" : tab.title.trim();
            chip.setText(title.substring(0, Math.min(1, title.length())));
            chip.setTextSize(16);
            chip.setTextColor(selected ? Color.WHITE : Color.rgb(61, 64, 73));
            chip.setGravity(Gravity.CENTER);
            chip.setContentDescription((selected ? "当前子 Tab：" : "切换到子 Tab：") + tab.title);
            chip.setBackground(roundedBackground(
                    selected ? Color.rgb(91, 70, 180) : Color.rgb(235, 237, 242), dp(20)));
            chip.setOnClickListener(view -> showTab(tab));
            chip.setOnLongClickListener(view -> {
                showChildOverview();
                return true;
            });
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(dp(42), dp(42));
            chipParams.setMargins(dp(3), 0, dp(3), 0);
            childStripContent.addView(chip, chipParams);
        }
        TextView add = toolbarButton("＋", "在当前工作区中新建子 Tab");
        add.setOnClickListener(view -> createChildTab(true));
        childStripContent.addView(add, new LinearLayout.LayoutParams(dp(46), dp(44)));
        childStripContent.post(() -> {
            int activeIndex = Math.max(0, activeWorkspace.tabs.indexOf(activeWorkspace.activeTab()));
            childStripScroller.smoothScrollTo(Math.max(0, activeIndex * dp(48) - dp(48)), 0);
        });
    }

    private void activateWorkspace(ReBrowserStore.Workspace workspace) {
        if (workspace == activeWorkspace && !tabWebViews.isEmpty()) return;
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

    private void createChildTab(boolean activate) {
        if (activeWorkspace == null) return;
        ReBrowserStore.Tab tab = store.addTab(activeWorkspace, browserPreferences.homeUrl());
        if (tab == null) {
            toast("每个工作区最多 50 个子 Tab");
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
        if (workspaces.size() >= ReBrowserStore.MAX_WORKSPACES) {
            toast("总标签页数量已达到上限");
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
        String operation = intent.getStringExtra(EXTRA_ADMIN_OPERATION);
        if (activeWorkspace == null) {
            ReBrowserAdminProvider.recordResult(this, "failed:ReBrowser-disabled");
            return;
        }
        if (ReBrowserAdminAuthorizer.OP_OPEN_URL.equals(operation)) {
            String url = intent.getStringExtra(EXTRA_ADMIN_URL);
            navigateActiveTab(url);
        } else if (ReBrowserAdminAuthorizer.OP_NEW_WORKSPACE.equals(operation)) {
            createTemporaryWorkspace();
        } else if (ReBrowserAdminAuthorizer.OP_NEW_CHILD_TAB.equals(operation)) {
            createChildTab(true);
        } else if (ReBrowserAdminAuthorizer.OP_SHOW_WORKSPACES.equals(operation)) {
            showWorkspaceOverview();
        } else if (ReBrowserAdminAuthorizer.OP_SHOW_CHILD_TABS.equals(operation)) {
            showChildOverview();
        } else if (ReBrowserAdminAuthorizer.OP_OPEN_SETTINGS.equals(operation)) {
            startActivity(new Intent(this, ReBrowserSettingsActivity.class));
        } else if (ReBrowserAdminAuthorizer.OP_GET_STATE.equals(operation)) {
            ReBrowserAdminProvider.recordResult(this, createAdminState());
            toast("管理员状态快照已更新");
            return;
        } else {
            ReBrowserAdminProvider.recordResult(this, "failed:unsupported-operation");
            return;
        }
        ReBrowserAdminProvider.recordResult(this, "completed:" + operation);
    }

    private String createAdminState() {
        try {
            JSONObject state = new JSONObject();
            state.put("version", 1);
            state.put("activeWorkspaceId", activeWorkspace == null ? "" : activeWorkspace.id);
            JSONArray workspaceValues = new JSONArray();
            for (ReBrowserStore.Workspace workspace : workspaces) {
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
                    tabValues.put(tabValue);
                }
                workspaceValue.put("tabs", tabValues);
                workspaceValues.put(workspaceValue);
            }
            state.put("workspaces", workspaceValues);
            return state.toString();
        } catch (Exception error) {
            return "failed:state:" + error.getClass().getSimpleName();
        }
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
        if (activeWorkspace.tabs.size() == 1) {
            tab.title = "新标签页";
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
        actions.add("重命名工作区");
        if (activeWorkspace.level == ReBrowserStore.Level.SECONDARY) {
            actions.add("提升为主标签页");
        }
        new AlertDialog.Builder(this)
                .setTitle("工作区设置")
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) showRenameDialog();
                    else {
                        store.promoteToPrimary(activeWorkspace, workspaces);
                        toast("已提升为主标签页");
                        updateChromeUi();
                        if (workspaceOverviewVisible) showWorkspaceOverview();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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
                .setTitle("重命名工作区")
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
        String message = workspace.level == ReBrowserStore.Level.TEMPORARY
                ? "临时总标签页将关闭，其全部子 Tab 和命名 Profile 会被删除。"
                : "整个总标签页、全部子 Tab 元数据和网站 Profile 数据都将删除。";
        new AlertDialog.Builder(this)
                .setTitle("关闭总标签页？")
                .setMessage(message)
                .setPositiveButton("关闭", (dialog, which) -> closeWorkspace(workspace))
                .setNegativeButton("取消", null)
                .show();
    }

    private void closeWorkspace(ReBrowserStore.Workspace closing) {
        if (!workspaces.contains(closing)) return;
        boolean wasActive = closing == activeWorkspace;
        if (wasActive) {
            saveCurrentTabStates();
            destroyTabWebViews();
        }
        store.removeWorkspace(closing, workspaces);
        deleteProfileIfPossible(closing.profileName);
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
        childTabStrip.setVisibility(View.GONE);
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
        childTabStrip.setVisibility(View.GONE);
    }

    private void showBrowserMenu(View anchor) {
        if (activeWorkspace == null) return;
        WebView webView = activeWebView();
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "新建总标签页");
        popup.getMenu().add(0, 2, 1, "新建子 Tab");
        popup.getMenu().add(0, 3, 2, "子 Tab 总览");
        popup.getMenu().add(0, 4, 3, "刷新");
        popup.getMenu().add(0, 5, 4, "后退").setEnabled(webView != null && webView.canGoBack());
        popup.getMenu().add(0, 6, 5, "前进").setEnabled(webView != null && webView.canGoForward());
        popup.getMenu().add(0, 7, 6,
                activeWorkspace.level == ReBrowserStore.Level.TEMPORARY
                        ? "锁定为副标签页" : "工作区设置");
        popup.getMenu().add(0, 8, 7, "浏览器设置");
        popup.getMenu().add(0, 9, 8, "切换到 ReOutlook");
        popup.getMenu().add(0, 10, 9, "关闭当前子 Tab");
        popup.getMenu().add(0, 11, 10, "关闭当前总标签页");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: createTemporaryWorkspace(); return true;
                case 2: createChildTab(true); return true;
                case 3: showChildOverview(); return true;
                case 4:
                    if (webView != null) webView.reload();
                    return true;
                case 5:
                    if (webView != null && webView.canGoBack()) webView.goBack();
                    return true;
                case 6:
                    if (webView != null && webView.canGoForward()) webView.goForward();
                    return true;
                case 7:
                    if (activeWorkspace.level == ReBrowserStore.Level.TEMPORARY) {
                        if (store.lockAsSecondary(activeWorkspace, workspaces)) {
                            toast("已锁定为副标签页");
                            updateChromeUi();
                        } else {
                            toast("常用标签页数量已达到上限");
                        }
                    } else {
                        showWorkspaceSettings();
                    }
                    return true;
                case 8:
                    startActivity(new Intent(this, ReBrowserSettingsActivity.class));
                    return true;
                case 9:
                    finish();
                    return true;
                case 10:
                    closeTab(activeWorkspace.activeTab());
                    return true;
                case 11:
                    confirmCloseWorkspace();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private void showWorkspaceOverview() {
        if (activeWorkspace == null) return;
        saveCurrentTabStates();
        workspaceOverviewVisible = true;
        childOverviewVisible = false;
        setBrowserContentVisible(false);
        overviewContainer.removeAllViews();
        overviewContainer.addView(createWorkspaceOverviewPage(), matchMatch());
        overviewContainer.setAlpha(0f);
        overviewContainer.setVisibility(View.VISIBLE);
        overviewContainer.animate().alpha(1f).setDuration(160).start();
    }

    private View createWorkspaceOverviewPage() {
        LinearLayout page = overviewPage("总标签页", workspaces.size() + " 个独立工作区",
                this::createTemporaryWorkspace);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(8), dp(4), dp(8), dp(28));
        int cardWidth = Math.max(dp(150),
                (getResources().getDisplayMetrics().widthPixels - dp(40)) / 2);
        for (ReBrowserStore.Workspace workspace : new ArrayList<>(workspaces)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(12), dp(10));
            card.setElevation(workspace == activeWorkspace ? dp(6) : dp(2));
            GradientDrawable background = roundedBackground(
                    workspace == activeWorkspace ? Color.rgb(235, 231, 250) : Color.WHITE,
                    dp(18));
            if (workspace == activeWorkspace) {
                background.setStroke(dp(2), Color.rgb(91, 70, 180));
            }
            card.setBackground(background);
            card.setOnClickListener(view -> {
                hideOverview();
                activateWorkspace(workspace);
            });
            card.setOnLongClickListener(view -> {
                hideOverview();
                activateWorkspace(workspace);
                if (workspace.level == ReBrowserStore.Level.TEMPORARY) {
                    if (store.lockAsSecondary(workspace, workspaces)) {
                        toast("已锁定为副标签页");
                    }
                } else {
                    showWorkspaceSettings();
                }
                return true;
            });

            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView badge = new TextView(this);
            badge.setText(levelShortLabel(workspace.level));
            badge.setTextSize(11);
            badge.setTextColor(Color.WHITE);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(roundedBackground(levelColor(workspace.level), dp(10)));
            heading.addView(badge, new LinearLayout.LayoutParams(dp(52), dp(24)));
            View headingSpace = new View(this);
            heading.addView(headingSpace, new LinearLayout.LayoutParams(0, 1, 1));
            TextView close = toolbarButton("×", "关闭总标签页 " + workspace.title);
            close.setOnClickListener(view -> {
                view.getParent().requestDisallowInterceptTouchEvent(true);
                confirmCloseWorkspace(workspace);
            });
            heading.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
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
            active.setText(tab == null ? "空工作区" : tab.title);
            active.setTextSize(12);
            active.setTextColor(Color.rgb(92, 98, 108));
            active.setMaxLines(2);
            card.addView(active, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

            TextView count = new TextView(this);
            count.setText(workspace.tabs.size() + " 个子 Tab");
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
        ((LinearLayout) ((ScrollView) page.getChildAt(1)).getChildAt(0)).addView(grid);
        return page;
    }

    private void showChildOverview() {
        if (activeWorkspace == null) return;
        saveCurrentTabStates();
        childOverviewVisible = true;
        workspaceOverviewVisible = false;
        setBrowserContentVisible(false);
        overviewContainer.removeAllViews();
        overviewContainer.addView(createChildOverviewPage(), matchMatch());
        overviewContainer.setAlpha(0f);
        overviewContainer.setVisibility(View.VISIBLE);
        overviewContainer.animate().alpha(1f).setDuration(160).start();
    }

    private View createChildOverviewPage() {
        LinearLayout page = overviewPage("子 Tab", activeWorkspace.title,
                () -> createChildTab(true));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(8), dp(4), dp(8), dp(28));
        int cardWidth = Math.max(dp(150),
                (getResources().getDisplayMetrics().widthPixels - dp(40)) / 2);
        for (ReBrowserStore.Tab tab : new ArrayList<>(activeWorkspace.tabs)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(10), dp(10));
            card.setElevation(tab.id.equals(activeWorkspace.activeTabId) ? dp(6) : dp(2));
            GradientDrawable background = roundedBackground(
                    tab.id.equals(activeWorkspace.activeTabId)
                            ? Color.rgb(235, 231, 250) : Color.WHITE,
                    dp(18));
            if (tab.id.equals(activeWorkspace.activeTabId)) {
                background.setStroke(dp(2), Color.rgb(91, 70, 180));
            }
            card.setBackground(background);
            card.setOnClickListener(view -> {
                hideOverview();
                showTab(tab);
            });

            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView icon = new TextView(this);
            String titleText = tab.title == null || tab.title.isBlank() ? "新" : tab.title.trim();
            icon.setText(titleText.substring(0, Math.min(1, titleText.length())));
            icon.setTextColor(Color.WHITE);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(roundedBackground(Color.rgb(91, 70, 180), dp(16)));
            heading.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
            heading.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            TextView close = toolbarButton("×", "关闭子 Tab " + tab.title);
            close.setOnClickListener(view -> closeTab(tab));
            heading.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
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
        ((LinearLayout) ((ScrollView) page.getChildAt(1)).getChildAt(0)).addView(grid);
        return page;
    }

    private LinearLayout overviewPage(String title, String subtitle, Runnable addAction) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(245, 247, 251));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(6), dp(8), dp(6));
        header.setBackgroundColor(Color.WHITE);
        header.setElevation(dp(4));
        TextView back = toolbarButton("‹", "返回网页");
        back.setTextSize(32);
        back.setOnClickListener(view -> hideOverview());
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
        add.setOnClickListener(view -> addAction.run());
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
        overviewContainer.animate().cancel();
        overviewContainer.setVisibility(View.GONE);
        overviewContainer.removeAllViews();
        setBrowserContentVisible(true);
    }

    private void setBrowserContentVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.INVISIBLE;
        browserToolbar.setVisibility(visibility);
        webContainer.setVisibility(visibility);
        childTabStrip.setVisibility(visibility);
        if (!visible) progressBar.setVisibility(View.GONE);
        int importance = visible
                ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
        browserToolbar.setImportantForAccessibility(importance);
        webContainer.setImportantForAccessibility(importance);
        childTabStrip.setImportantForAccessibility(importance);
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

    private static String levelLabel(ReBrowserStore.Level level) {
        if (level == ReBrowserStore.Level.PRIMARY) return "主标签页";
        if (level == ReBrowserStore.Level.SECONDARY) return "副标签页";
        return "临时标签页";
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
        if (workspaceOverviewVisible || childOverviewVisible) {
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
            if (tab != null) tab.url = ReBrowserStore.safeUrl(url);
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
                saveWorkspaceMetadata();
                updateChromeUi();
            }
            if (isVisibleWebView(view)) {
                omnibox.setText(ReBrowserStore.safeUrl(url));
                progressBar.setVisibility(View.GONE);
            }
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
            if (request.isForMainFrame() && isVisibleWebView(view)) {
                progressBar.setVisibility(View.GONE);
                toast("页面连接失败");
            }
        }
    }

    private final class WorkspaceChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
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
                toast("每个工作区最多 50 个子 Tab");
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
