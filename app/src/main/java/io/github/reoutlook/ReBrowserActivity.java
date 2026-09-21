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
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
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
import androidx.webkit.WebViewFeature;

import java.util.ArrayList;
import java.util.List;

/** Multi-Profile browser workspace MVP. ReOutlook's Default Profile is never used here. */
@SuppressLint({"ClickableViewAccessibility", "RequiresFeature", "SetJavaScriptEnabled", "SetTextI18n", "UnsafeOptInUsageError"})
public final class ReBrowserActivity extends Activity
        implements ReBrowserAdminController.Host {
    private static final int FILE_CHOOSER_REQUEST = 5102;
    private static final int WEBSITE_ANDROID_PERMISSION_REQUEST = 5103;
    private static final long DOUBLE_BACK_INTERVAL_MS = 2_000L;

    private List<ReBrowserStore.Workspace> workspaces = List.of();
    private List<ReBrowserStore.Workspace> shelvedSecondaryWorkspaces = List.of();
    private final List<ReBrowserFavorites.Favorite> bookmarks = new ArrayList<>();
    private List<ReBrowserDownloads.Record> downloads = List.of();
    private final ReBrowserOverviewController overviewController =
            new ReBrowserOverviewController();

    private ReBrowserWorkspaceController workspaceController;
    private ReBrowserPreferences browserPreferences;
    private ReBrowserSitePermissions sitePermissions;
    private ReBrowserFavorites bookmarkStore;
    private ReBrowserDownloadController downloadController;
    private ReBrowserWebController webController;
    private ReBrowserAdminController adminController;
    private FrameLayout root;
    private FrameLayout webContainer;
    private LinearLayout browserToolbar;
    private EditText omnibox;
    private TextView workspaceCountButton;
    private ProgressBar progressBar;
    private FrameLayout overviewContainer;
    private final Runnable downloadRefreshRunnable = () -> {
        if (!overviewController.is(ReBrowserOverviewController.Page.DOWNLOADS)
                || root == null) return;
        downloadController.refresh();
        showDownloadOverview();
    };
    private long lastBackPressAt;
    private ReBrowserFullscreenController fullscreenController;
    private ReBrowserPermissionRequest visiblePermissionRequest;
    private ReBrowserPermissionRequest pendingAndroidPermissionRequest;
    private long pendingAndroidPermissionDuration;
    private View permissionPromptView;
    private boolean androidPermissionDialogVisible;
    private long observedPermissionRevision;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBackDispatcher.register(this, this::handleSystemBack);
        workspaceController = new ReBrowserWorkspaceController(new ReBrowserStore(this));
        workspaces = workspaceController.activeWorkspaces();
        shelvedSecondaryWorkspaces = workspaceController.shelvedWorkspaces();
        browserPreferences = new ReBrowserPreferences(this);
        sitePermissions = new ReBrowserSitePermissions(this);
        observedPermissionRevision = sitePermissions.revision();
        fullscreenController = new ReBrowserFullscreenController(this, browserPreferences);
        fullscreenController.applyGlobalOrientationPreference();
        webController = new ReBrowserWebController(this, browserPreferences, sitePermissions,
                fullscreenController, new WebListener(), FILE_CHOOSER_REQUEST);
        bookmarkStore = new ReBrowserFavorites(this);
        bookmarks.addAll(bookmarkStore.load());
        root = createRoot();
        webController.attachContainer(webContainer);
        fullscreenController.attachStyledRoot(root);
        setContentView(root);
        downloadController = new ReBrowserDownloadController(
                this, webController, new DownloadListener());
        downloads = downloadController.records();
        adminController = new ReBrowserAdminController(
                this, workspaceController, browserPreferences, sitePermissions,
                fullscreenController, webController, downloadController, this);

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            showUnsupportedProvider();
            adminController.handle(getIntent());
            return;
        }

        deletePendingProfiles();
        workspaceController.initialize(browserPreferences.homeUrl());
        activateWorkspace(activeWorkspace());
        adminController.handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        adminController.handle(intent);
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
                    if (overviewController.is(
                            ReBrowserOverviewController.Page.WORKSPACE_BOOKMARKS)) {
                        showWorkspaceBookmarkOverview();
                    }
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

    @Override
    public void activateWorkspace(ReBrowserStore.Workspace workspace) {
        if (workspace == activeWorkspace() && webController.hasPages()) return;
        dismissPermissionPrompt("workspace-switched");
        if (activeWorkspace() != null && workspace != activeWorkspace()) {
            adminController.onWorkspaceDeactivated(activeWorkspace());
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

    @Override
    public void showTab(ReBrowserStore.Tab tab) {
        ReBrowserStore.Workspace workspace = activeWorkspace();
        if (workspace == null || !workspace.tabs.contains(tab)) return;
        if (workspace.activeTab() != tab) dismissPermissionPrompt("tab-switched");
        saveVisibleTabState();
        workspaceController.selectTab(workspace, tab);
        try {
            webController.showTab(workspace.id, workspace.profileName, tab);
        } catch (Throwable error) {
            showProfileFailure(error);
            return;
        }
        saveWorkspaceMetadata();
        updateChromeUi();
    }

    @Override
    public void onWebsitePermissionPolicyChanged() {
        observedPermissionRevision = sitePermissions.revision();
        dismissPermissionPrompt("permission-policy-changed");
        if (webController.hasPages()) {
            saveCurrentTabStates();
            downloadController.invalidatePageTransfers("permission-policy-changed");
            webController.destroyAll();
        }
        if (activeWorkspace() != null && activeWorkspace().activeTab() != null) {
            showTab(activeWorkspace().activeTab());
            webController.onResume();
        }
    }

    @Override
    public void navigateActiveTab(String input) {
        ReBrowserStore.Tab tab = activeWorkspace() == null ? null : activeWorkspace().activeTab();
        if (tab == null) return;
        String url = normalizeAddress(input);
        workspaceController.updateTab(tab, url, null);
        if (webController.currentUrl(tab.id) == null) showTab(tab);
        else webController.load(tab.id, url);
        saveWorkspaceMetadata();
        omnibox.setText(url);
    }

    private void toggleCurrentBookmark() {
        ReBrowserStore.Tab tab = activeWorkspace() == null ? null : activeWorkspace().activeTab();
        if (tab == null) return;
        String currentUrl = webController.currentUrl(tab.id);
        String url = currentUrl == null ? tab.url : currentUrl;
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
        String currentUrl = webController.currentUrl(tab.id);
        String currentTitle = webController.currentTitle(tab.id);
        String url = currentUrl == null ? tab.url : currentUrl;
        String title = currentTitle == null ? tab.title : currentTitle;
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
        if (overviewController.is(ReBrowserOverviewController.Page.FAVORITES)) showBookmarkOverview();
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
                    if (overviewController.is(ReBrowserOverviewController.Page.FAVORITES)) showBookmarkOverview();
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

    private String normalizeAddress(String input) {
        String clean = input == null ? "" : input.trim();
        if (clean.isEmpty()) return browserPreferences.homeUrl();
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://")) return clean;
        if (!clean.contains(" ") && clean.contains(".")) return "https://" + clean;
        return browserPreferences.searchUrl(clean);
    }

    @Override
    public void closeTab(ReBrowserStore.Tab tab) {
        ReBrowserStore.Workspace workspace = activeWorkspace();
        if (workspace == null || !workspace.tabs.contains(tab)) return;
        adminController.onTabClosing(tab);
        ReBrowserWorkspaceController.TabCloseResult result = workspaceController.closeTab(
                workspace, tab, browserPreferences.homeUrl());
        if (!result.valid) return;
        if (result.resetOnly) {
            if (webController.currentUrl(tab.id) != null) webController.load(tab.id, tab.url);
        } else {
            webController.removeTab(tab.id);
            if (result.nextActiveTab != null) showTab(result.nextActiveTab);
        }
        updateChromeUi();
        if (overviewController.is(ReBrowserOverviewController.Page.CHILD_TABS)) showChildOverview();
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
        if (overviewController.is(ReBrowserOverviewController.Page.WORKSPACES)) showWorkspaceOverview();
        if (overviewController.is(
                ReBrowserOverviewController.Page.WORKSPACE_BOOKMARKS)) {
            showWorkspaceBookmarkOverview();
        }
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

    @Override
    public void closeWorkspace(ReBrowserStore.Workspace closing) {
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
        } else if (overviewController.is(ReBrowserOverviewController.Page.WORKSPACES)) {
            showWorkspaceOverview();
        }
        updateChromeUi();
    }

    private void saveVisibleTabState() {
        if (activeWorkspace() == null) return;
        ReBrowserStore.Tab tab = activeWorkspace().activeTab();
        if (tab == null) return;
        workspaceController.updateTab(
                tab, webController.currentUrl(tab.id), webController.currentTitle(tab.id));
    }

    private void saveCurrentTabStates() {
        for (ReBrowserWebController.PageState state : webController.pageStates()) {
            workspaceController.updateTab(state.tab, state.url, state.title);
        }
        saveWorkspaceMetadata();
    }

    private void saveWorkspaceMetadata() {
        workspaceController.save();
    }

    private void destroyTabWebViews() {
        downloadController.invalidatePageTransfers("blob-source-webview-destroyed");
        webController.destroyAll();
    }

    private void deletePendingProfiles() {
        for (String profileName : workspaceController.pendingProfileDeletions()) {
            deleteProfileIfPossible(profileName);
        }
    }

    @Override
    public void deleteProfileIfPossible(String profileName) {
        if (!ReBrowserStore.isOwnedProfile(profileName)) return;
        sitePermissions.clearForProfile(profileName);
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
        message.setText("当前系统 WebView 不支持 ReBrowser 所需的 Multi-Profile 或网站权限策略。\n\n"
                + "为保护 ReOutlook 的 Default Profile，并防止网页绕过权限管理，ReBrowser 已禁用。\n\n"
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
        ReBrowserStore.Tab tab = activeWorkspace().activeTab();
        String currentUrl = tab == null ? null : webController.currentUrl(tab.id);
        String url = currentUrl == null ? tab == null ? "" : tab.url : currentUrl;
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
                tab != null && webController.canGoBack(tab.id), () -> {
                    popup.dismiss();
                    if (tab != null) webController.goBack(tab.id);
                }));
        shortcuts.addView(menuShortcut(R.drawable.ic_rb_forward, "前进",
                tab != null && webController.canGoForward(tab.id), () -> {
                    popup.dismiss();
                    if (tab != null) webController.goForward(tab.id);
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
                tab != null && webController.currentUrl(tab.id) != null, () -> {
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

    @Override
    public void openSettings() {
        startActivity(new Intent(this, ReBrowserSettingsActivity.class));
    }

    private void reloadActivePage() {
        ReBrowserStore.Tab tab = activeWorkspace() == null ? null : activeWorkspace().activeTab();
        if (tab == null || webController.currentUrl(tab.id) == null) return;
        progressBar.setProgress(5);
        progressBar.setVisibility(View.VISIBLE);
        root.post(() -> {
            if (activeWorkspace() == null || activeWorkspace().activeTab() != tab) return;
            webController.stop(tab.id);
            webController.reload(tab.id);
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

    @Override
    public void refreshLifecycleUi() {
        updateChromeUi();
        if (overviewController.is(ReBrowserOverviewController.Page.WORKSPACES)) showWorkspaceOverview();
        if (overviewController.is(
                ReBrowserOverviewController.Page.WORKSPACE_BOOKMARKS)) {
            showWorkspaceBookmarkOverview();
        }
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
        actions.addView(batchButton("关闭", !primarySelected && overviewController.hasWorkspaceSelection(),
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
            if (overviewController.isWorkspaceSelected(workspace.id)) selected.add(workspace);
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
        overviewController.toggleWorkspaceSelection(workspace.id);
        showWorkspaceOverview();
    }

    private void startWorkspaceSelection(ReBrowserStore.Workspace workspace) {
        overviewController.startWorkspaceSelection(workspace.id);
        showWorkspaceOverview();
    }

    private void exitWorkspaceSelection() {
        overviewController.clearWorkspaceSelection();
        showWorkspaceOverview();
    }

    private void batchLockSelectedWorkspaces() {
        if (selectedWorkspacesContainPrimary()) return;
        int changed = 0;
        for (ReBrowserStore.Workspace workspace : selectedWorkspaces()) {
            if (workspaceController.lockAsSecondary(workspace)) changed++;
        }
        overviewController.clearWorkspaceSelection();
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
        overviewController.clearWorkspaceSelection();
        toast("已解锁 " + changed + " 个总标签页");
        updateChromeUi();
        showWorkspaceOverview();
    }

    private void confirmBatchCloseWorkspaces() {
        if (selectedWorkspacesContainPrimary() || !overviewController.hasWorkspaceSelection()) return;
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
        overviewController.clearWorkspaceSelection();
        updateChromeUi();
        showWorkspaceOverview();
    }

    @Override
    public void showWorkspaceOverview() {
        if (activeWorkspace() == null) return;
        overviewController.show(ReBrowserOverviewController.Page.WORKSPACES);
        saveCurrentTabStates();
        setBrowserContentVisible(false);
        overviewContainer.removeAllViews();
        overviewContainer.addView(createWorkspaceOverviewPage(), matchMatch());
        overviewContainer.setAlpha(0f);
        overviewContainer.setVisibility(View.VISIBLE);
        overviewContainer.animate().alpha(1f).setDuration(160).start();
    }

    private View createWorkspaceOverviewPage() {
        LinearLayout page = overviewPage("总标签页",
                overviewController.hasWorkspaceSelection()
                        ? "已选择 " + overviewController.selectedWorkspaceCount() + " 个"
                        : workspaces.size() + " 个独立浏览空间",
                overviewController.hasWorkspaceSelection() ? null : this::createTemporaryWorkspace,
                overviewController.hasWorkspaceSelection() ? this::exitWorkspaceSelection : this::hideOverview,
                overviewController.hasWorkspaceSelection() ? "退出多选" : "返回网页");
        LinearLayout body = (LinearLayout) ((ScrollView) page.getChildAt(1)).getChildAt(0);
        if (overviewController.hasWorkspaceSelection()) body.addView(createWorkspaceBatchBar());
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(8), dp(4), dp(8), dp(28));
        int cardWidth = Math.max(dp(150),
                (getResources().getDisplayMetrics().widthPixels - dp(40)) / 2);
        for (ReBrowserStore.Workspace workspace : new ArrayList<>(workspaces)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(12), dp(10));
            boolean selected = overviewController.isWorkspaceSelected(workspace.id);
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
                if (overviewController.hasWorkspaceSelection()) {
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
            if (!overviewController.hasWorkspaceSelection() && !primary) {
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
            if (!overviewController.hasWorkspaceSelection()) {
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
        bar.addView(batchButton("关闭选中子标签页", overviewController.hasChildSelection(),
                this::confirmBatchCloseChildTabs));
        return bar;
    }

    private void toggleChildTabSelection(ReBrowserStore.Tab tab) {
        overviewController.toggleChildTabSelection(tab.id);
        showChildOverview();
    }

    private void startChildTabSelection(ReBrowserStore.Tab tab) {
        overviewController.startChildTabSelection(tab.id);
        showChildOverview();
    }

    private void exitChildSelection() {
        overviewController.clearChildSelection();
        showChildOverview();
    }

    private void confirmBatchCloseChildTabs() {
        if (!overviewController.hasChildSelection()) return;
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
            if (overviewController.isChildTabSelected(tab.id)) closing.add(tab);
        }
        overviewController.hide();
        for (ReBrowserStore.Tab tab : closing) closeTab(tab);
        showChildOverview();
    }

    @Override
    public void showChildOverview() {
        if (activeWorkspace() == null) return;
        overviewController.show(ReBrowserOverviewController.Page.CHILD_TABS);
        saveCurrentTabStates();
        setBrowserContentVisible(false);
        overviewContainer.removeAllViews();
        overviewContainer.addView(createChildOverviewPage(), matchMatch());
        overviewContainer.setAlpha(0f);
        overviewContainer.setVisibility(View.VISIBLE);
        overviewContainer.animate().alpha(1f).setDuration(160).start();
    }

    private View createChildOverviewPage() {
        LinearLayout page = overviewPage("子标签页",
                overviewController.hasChildSelection()
                        ? "已选择 " + overviewController.selectedChildTabCount() + " 个"
                        : "总标签页 · " + activeWorkspace().title,
                overviewController.hasChildSelection() ? null : () -> createChildTab(true),
                overviewController.hasChildSelection() ? this::exitChildSelection : this::showWorkspaceOverview,
                overviewController.hasChildSelection() ? "退出多选" : "返回总标签页");
        LinearLayout body = (LinearLayout) ((ScrollView) page.getChildAt(1)).getChildAt(0);
        if (overviewController.hasChildSelection()) body.addView(createChildBatchBar());
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(8), dp(4), dp(8), dp(28));
        int cardWidth = Math.max(dp(150),
                (getResources().getDisplayMetrics().widthPixels - dp(40)) / 2);
        for (ReBrowserStore.Tab tab : new ArrayList<>(activeWorkspace().tabs)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(10), dp(10));
            boolean selected = overviewController.isChildTabSelected(tab.id);
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
                if (overviewController.hasChildSelection()) {
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
            if (!overviewController.hasChildSelection()) {
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
        overviewController.show(ReBrowserOverviewController.Page.FAVORITES);
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

    @Override
    public void showDownloadOverview() {
        if (activeWorkspace() == null) return;
        downloadController.refresh();
        overviewController.show(ReBrowserOverviewController.Page.DOWNLOADS);
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
        int pending = downloadController.pendingCount();
        String subtitle = downloadController.downloadsEnabled()
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
                        downloadController.cancel(record);
                        showDownloadOverview();
                    }).setNegativeButton("继续", null).show();
        } else if (ReBrowserDownloads.STATUS_COMPLETED.equals(record.status)) {
            confirmOpenDownload(record);
        } else if (ReBrowserDownloads.STATUS_FAILED.equals(record.status)
                || ReBrowserDownloads.STATUS_CANCELLED.equals(record.status)) {
            downloadController.retry(record);
        } else {
            downloadController.delete(record);
        }
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
        Intent open = downloadController.externalOpenIntent(record);
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
                .setPositiveButton("删除", (dialog, which) -> downloadController.delete(record))
                .setNegativeButton("取消", null).show();
    }

    private void showWorkspaceBookmarkOverview() {
        if (activeWorkspace() == null) return;
        saveCurrentTabStates();
        overviewController.show(ReBrowserOverviewController.Page.WORKSPACE_BOOKMARKS);
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

    @Override
    public void hideOverview() {
        overviewController.hide();
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
        if (visiblePermissionRequest != null) {
            dismissPermissionPrompt("user-dismissed");
            return;
        }
        if (fullscreenController.hideIfVisible()) return;
        switch (overviewController.page()) {
            case CHILD_TABS:
                if (overviewController.hasChildSelection()) exitChildSelection();
                else showWorkspaceOverview();
                return;
            case WORKSPACES:
                if (overviewController.hasWorkspaceSelection()) exitWorkspaceSelection();
                else hideOverview();
                return;
            case FAVORITES:
            case WORKSPACE_BOOKMARKS:
            case DOWNLOADS:
                hideOverview();
                return;
            case NONE:
                break;
        }
        if (omnibox.hasFocus()) {
            omnibox.clearFocus();
            InputMethodManager keyboard = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            keyboard.hideSoftInputFromWindow(omnibox.getWindowToken(), 0);
            return;
        }
        ReBrowserStore.Tab tab = activeWorkspace() == null ? null : activeWorkspace().activeTab();
        String currentUrl = tab == null ? null : webController.currentUrl(tab.id);
        boolean atHomepage = currentUrl != null
                && samePage(currentUrl, browserPreferences.homeUrl());
        if (!atHomepage && tab != null && webController.canGoBack(tab.id)) {
            lastBackPressAt = 0;
            webController.goBack(tab.id);
            return;
        }
        if (!atHomepage && currentUrl != null) {
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
        long currentPermissionRevision = sitePermissions.revision();
        if (currentPermissionRevision != observedPermissionRevision) {
            observedPermissionRevision = currentPermissionRevision;
            dismissPermissionPrompt("permission-policy-changed");
            if (webController.hasPages()) {
                saveCurrentTabStates();
                destroyTabWebViews();
            }
        }
        if (activeWorkspace() != null && activeWorkspace().activeTab() != null
                && webController.currentUrl(activeWorkspace().activeTab().id) == null) {
            showTab(activeWorkspace().activeTab());
        }
        webController.onResume();
        downloadController.refresh();
        if (overviewController.is(ReBrowserOverviewController.Page.DOWNLOADS)) showDownloadOverview();
    }

    @Override
    protected void onPause() {
        saveCurrentTabStates();
        if (!androidPermissionDialogVisible) {
            dismissPermissionPrompt("application-backgrounded");
            webController.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!androidPermissionDialogVisible && !sitePermissions.backgroundRuntimeEnabled()
                && !webController.hasPendingFileChooser() && webController.hasPages()) {
            saveCurrentTabStates();
            downloadController.invalidatePageTransfers("background-runtime-disabled");
            webController.destroyAll();
        }
    }

    @Override
    protected void onDestroy() {
        dismissPermissionPrompt("activity-destroyed");
        adminController.onDestroy();
        fullscreenController.hide();
        saveCurrentTabStates();
        destroyTabWebViews();
        webController.cancelPendingFileChooser();
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
        webController.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != WEBSITE_ANDROID_PERMISSION_REQUEST) return;
        androidPermissionDialogVisible = false;
        ReBrowserPermissionRequest request = pendingAndroidPermissionRequest;
        long duration = pendingAndroidPermissionDuration;
        pendingAndroidPermissionRequest = null;
        pendingAndroidPermissionDuration = 0L;
        if (request == null || request.isCompleted()) {
            webController.onResume();
            return;
        }
        webController.onResume();
        boolean granted = true;
        for (String permission : request.androidPermissions) {
            granted &= checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }
        if (granted) request.approve(duration);
        else {
            request.deny("android-permission-denied");
            toast("系统未授予所需权限");
        }
    }

    private void showSitePermissionPrompt(ReBrowserPermissionRequest request) {
        if (request == null || request.isCompleted()) return;
        if (visiblePermissionRequest != null && !visiblePermissionRequest.isCompleted()) {
            request.deny("another-permission-prompt-visible");
            return;
        }
        visiblePermissionRequest = request;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.setBackground(roundedBackground(Color.WHITE, dp(18)));
        card.setElevation(dp(18));

        TextView title = new TextView(this);
        title.setText(request.origin + " 想使用" + permissionNames(request.permissions));
        title.setTextSize(16);
        title.setTextColor(Color.rgb(31, 36, 45));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title, matchWrap());
        TextView detail = new TextView(this);
        detail.setText("仅当前可见页面可以使用；进入后台、导航或关闭页面会立即停止。");
        detail.setTextSize(12);
        detail.setTextColor(Color.rgb(92, 99, 110));
        detail.setPadding(0, dp(5), 0, dp(10));
        card.addView(detail, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button deny = new Button(this);
        deny.setText("拒绝");
        deny.setOnClickListener(view -> {
            request.deny("user-denied");
            removePermissionPrompt(request);
        });
        Button shortGrant = new Button(this);
        shortGrant.setText(request.shortLabel);
        shortGrant.setOnClickListener(view -> beginPermissionApproval(
                request, request.shortDurationMs));
        Button longGrant = new Button(this);
        longGrant.setText(request.longLabel);
        longGrant.setOnClickListener(view -> beginPermissionApproval(
                request, request.longDurationMs));
        actions.addView(deny, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(shortGrant, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(longGrant, new LinearLayout.LayoutParams(0, dp(48), 1));
        card.addView(actions, matchWrap());

        permissionPromptView = card;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        params.setMargins(dp(12), dp(74), dp(12), 0);
        root.addView(card, params);
        root.postDelayed(() -> {
            if (visiblePermissionRequest == request) {
                request.deny("permission-timeout");
                removePermissionPrompt(request);
            }
        }, 30_000L);
    }

    private void beginPermissionApproval(ReBrowserPermissionRequest request, long duration) {
        if (request != visiblePermissionRequest || request.isCompleted()) return;
        for (String permission : request.permissions) {
            if (!sitePermissions.capabilityEnabled(permission)) {
                request.deny("permission-disabled");
                removePermissionPrompt(request);
                return;
            }
        }
        List<String> missing = new ArrayList<>();
        for (String permission : request.androidPermissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        // Android 12+ ignores a fine-location upgrade requested without coarse location in
        // the same array, even when coarse location was already granted earlier.
        if (missing.contains(Manifest.permission.ACCESS_FINE_LOCATION)
                && !missing.contains(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            missing.add(0, Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        removePermissionPrompt(request);
        if (missing.isEmpty()) {
            request.approve(duration);
            return;
        }
        pendingAndroidPermissionRequest = request;
        pendingAndroidPermissionDuration = duration;
        androidPermissionDialogVisible = true;
        webController.pauseForAndroidPermissionDialog();
        try {
            requestPermissions(missing.toArray(new String[0]),
                    WEBSITE_ANDROID_PERMISSION_REQUEST);
        } catch (RuntimeException error) {
            androidPermissionDialogVisible = false;
            pendingAndroidPermissionRequest = null;
            pendingAndroidPermissionDuration = 0L;
            webController.onResume();
            request.deny("android-permission-request-failed");
        }
    }

    private void dismissPermissionPrompt(String reason) {
        if (visiblePermissionRequest != null) visiblePermissionRequest.deny(reason);
        removePermissionPrompt(visiblePermissionRequest);
        if (!androidPermissionDialogVisible && pendingAndroidPermissionRequest != null) {
            pendingAndroidPermissionRequest.deny(reason);
            pendingAndroidPermissionRequest = null;
            pendingAndroidPermissionDuration = 0L;
        }
    }

    private void removePermissionPrompt(ReBrowserPermissionRequest request) {
        if (request != null && visiblePermissionRequest != request) return;
        if (permissionPromptView != null && permissionPromptView.getParent() == root) {
            root.removeView(permissionPromptView);
        }
        permissionPromptView = null;
        visiblePermissionRequest = null;
    }

    private static String permissionNames(List<String> permissions) {
        List<String> values = new ArrayList<>();
        for (String permission : permissions) {
            if (ReBrowserSitePermissions.CAMERA.equals(permission)) values.add("摄像头");
            else if (ReBrowserSitePermissions.MICROPHONE.equals(permission)) values.add("麦克风");
            else if (ReBrowserSitePermissions.PRECISE_LOCATION.equals(permission)) {
                values.add("高精度定位");
            } else if (ReBrowserSitePermissions.APPROXIMATE_LOCATION.equals(permission)) {
                values.add("模糊定位");
            } else if (ReBrowserSitePermissions.CLIPBOARD.equals(permission)) {
                values.add("剪贴板");
            }
        }
        return String.join("和", values);
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

    private final class WebListener implements ReBrowserWebController.Listener {
        @Override
        public void onPageStarted(ReBrowserStore.Tab tab, String url, boolean visible) {
            if (visiblePermissionRequest != null
                    && visiblePermissionRequest.tabId.equals(tab.id)) {
                dismissPermissionPrompt("page-navigated");
            }
            workspaceController.updateTab(tab, url, null);
            adminController.onPageStarted(tab);
            if (visible) {
                omnibox.setText(ReBrowserStore.safeUrl(url));
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(5);
            }
        }

        @Override
        public void onPageFinished(
                ReBrowserStore.Tab tab,
                String url,
                String title,
                boolean visible
        ) {
            workspaceController.updatePageMetadata(tab, url, title);
            adminController.onPageFinished(tab);
            saveWorkspaceMetadata();
            updateChromeUi();
            if (visible) {
                omnibox.setText(ReBrowserStore.safeUrl(url));
                progressBar.setVisibility(View.GONE);
            }
        }

        @Override
        public void onMainFrameError(
                ReBrowserStore.Tab tab,
                int errorCode,
                String description,
                boolean visible
        ) {
            adminController.onMainFrameError(tab, errorCode);
            if (visible) {
                progressBar.setVisibility(View.GONE);
                toast("页面连接失败");
            }
        }

        @Override
        public void onProgressChanged(
                ReBrowserStore.Tab tab,
                int progress,
                boolean visible
        ) {
            if (!visible) return;
            progressBar.setProgress(progress);
            progressBar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onTitleReceived(
                ReBrowserStore.Tab tab,
                String title,
                boolean visible
        ) {
            if (title == null || title.isBlank()) return;
            workspaceController.updateTab(tab, null, title);
            saveWorkspaceMetadata();
            if (visible) updateChromeUi();
        }

        @Override
        public void onExternalNavigationBlocked(String message) {
            toast(message);
        }

        @Override
        public void onExternalNavigationRequested(Uri uri, String scheme) {
            confirmExternalNavigation(uri, scheme);
        }

        @Override
        public ReBrowserWebController.PopupTarget onCreatePopup(
                ReBrowserStore.Tab sourceTab
        ) {
            ReBrowserStore.Workspace workspace = activeWorkspace();
            if (workspace == null || !workspace.tabs.contains(sourceTab)) return null;
            ReBrowserStore.Tab tab = workspaceController.addTab(workspace, "about:blank");
            if (tab == null) {
                toast("每个总标签页最多 50 个子标签页");
                return null;
            }
            return new ReBrowserWebController.PopupTarget(
                    workspace.id, workspace.profileName, tab);
        }

        @Override
        public void onPopupCreated(ReBrowserStore.Tab tab) {
            workspaceController.selectTab(activeWorkspace(), tab);
            saveWorkspaceMetadata();
            updateChromeUi();
        }

        @Override
        public void onPopupCreationFailed(ReBrowserStore.Tab tab, Throwable error) {
            workspaceController.discardAddedTab(activeWorkspace(), tab);
            showProfileFailure(error);
        }

        @Override
        public void onCloseRequested(ReBrowserStore.Tab tab) {
            closeTab(tab);
        }

        @Override
        public void onDownloadRequested(ReBrowserWebController.DownloadRequest request) {
            downloadController.accept(request, activeWorkspace());
        }

        @Override
        public void onFileChooserUnavailable() {
            toast("没有可用的文件选择器");
        }

        @Override
        public void onSitePermissionRequested(ReBrowserPermissionRequest request) {
            showSitePermissionPrompt(request);
        }
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
                        downloadController.approve(record);
                    }
                })
                .setNegativeButton("拒绝", (dialog, which) -> downloadController.reject(record))
                .show();
    }

    private void showDangerousDownloadConfirmation(ReBrowserDownloads.Record record) {
        new AlertDialog.Builder(this)
                .setTitle("危险文件二次确认")
                .setMessage("ReBrowser 只会保存文件，不会自动打开、安装、预览、解压或执行。"
                        + "请仅在信任来源时继续。\n\n" + record.fileName)
                .setPositiveButton("仍要下载", (dialog, which) -> downloadController.approve(record))
                .setNegativeButton("拒绝", (dialog, which) -> downloadController.reject(record))
                .show();
    }

    private final class DownloadListener implements ReBrowserDownloadController.Listener {
        @Override
        public void onDownloadRecordsChanged() {
            if (overviewController.is(ReBrowserOverviewController.Page.DOWNLOADS)
                    && root != null) {
                showDownloadOverview();
            }
        }

        @Override
        public void onDownloadConfirmationRequired(ReBrowserDownloads.Record record) {
            showDownloadConfirmation(record, false);
        }

        @Override
        public void onDownloadMessage(String message) {
            toast(message);
        }

    }
}
