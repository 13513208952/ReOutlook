package io.github.reoutlook;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded administrator control plane over the actual ReBrowser state owners. */
@SuppressLint({"RequiresFeature", "UnsafeOptInUsageError"})
final class ReBrowserAdminController {
    static final String ACTION_ADMIN_COMMAND = "io.github.reoutlook.action.REBROWSER_ADMIN";
    static final String EXTRA_ADMIN_REQUEST = "adminRequest";

    interface Host {
        void hideOverview();
        void activateWorkspace(ReBrowserStore.Workspace workspace);
        void showTab(ReBrowserStore.Tab tab);
        void showWorkspaceOverview();
        void showChildOverview();
        void showDownloadOverview();
        void openSettings();
        void refreshLifecycleUi();
        void closeWorkspace(ReBrowserStore.Workspace workspace);
        void closeTab(ReBrowserStore.Tab tab);
        void deleteProfileIfPossible(String profileName);
        void navigateActiveTab(String url);
    }

    private final Context context;
    private final ReBrowserWorkspaceController workspaceController;
    private final ReBrowserPreferences preferences;
    private final ReBrowserFullscreenController fullscreenController;
    private final ReBrowserWebController webController;
    private final ReBrowserDownloadController downloadController;
    private final Host host;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, JSONObject> pendingLoads = new HashMap<>();

    ReBrowserAdminController(
            Context context,
            ReBrowserWorkspaceController workspaceController,
            ReBrowserPreferences preferences,
            ReBrowserFullscreenController fullscreenController,
            ReBrowserWebController webController,
            ReBrowserDownloadController downloadController,
            Host host
    ) {
        this.context = context.getApplicationContext();
        this.workspaceController = workspaceController;
        this.preferences = preferences;
        this.fullscreenController = fullscreenController;
        this.webController = webController;
        this.downloadController = downloadController;
        this.host = host;
    }

    void handle(Intent intent) {
        if (intent == null || !ACTION_ADMIN_COMMAND.equals(intent.getAction())) return;
        intent.setAction(null); // A configuration change must not replay an authorized command.
        JSONObject request = null;
        try {
            request = new JSONObject(intent.getStringExtra(EXTRA_ADMIN_REQUEST));
            ReBrowserAdminProtocol.prepareRequest(request);
            recordStatus(request, "running", null, null);
            JSONObject details = execute(request);
            if (details != null) recordStatus(request, "completed", details, null);
        } catch (Exception error) {
            if (request != null) {
                recordStatus(request, "failed", null,
                        error.getClass().getSimpleName() + ":" + error.getMessage());
            }
        }
    }

    private JSONObject execute(JSONObject request) throws Exception {
        String operation = request.getString("operation");
        if (ReBrowserAdminProtocol.OP_GET_CAPABILITIES.equals(operation)) {
            return capabilities();
        }
        if (ReBrowserAdminProtocol.OP_GET_DIAGNOSTICS.equals(operation)) {
            return diagnostics();
        }
        if (ReBrowserAdminProtocol.OP_GET_DOWNLOAD_POLICY.equals(operation)) {
            return downloadPolicy();
        }
        if (ReBrowserAdminProtocol.OP_SET_DOWNLOAD_POLICY.equals(operation)) {
            int rejected = downloadController.setDownloadsEnabled(
                    request.getBoolean("downloadsEnabled"));
            return downloadPolicy().put("rejectedPendingCount", rejected);
        }
        if (ReBrowserAdminProtocol.OP_GET_DOWNLOADS.equals(operation)) return downloads();
        if (activeWorkspace() == null) throw new IllegalStateException("ReBrowser-disabled");
        if (ReBrowserAdminProtocol.OP_GET_STATE.equals(operation)) return state();
        if (ReBrowserAdminProtocol.OP_VALIDATE_STATE.equals(operation)) return validateState();
        if (ReBrowserAdminProtocol.OP_REPAIR_STATE.equals(operation)) return repairState();
        if (ReBrowserAdminProtocol.OP_NEW_WORKSPACE.equals(operation)) {
            ReBrowserStore.Workspace workspace = workspaceController.createTemporary(
                    preferences.homeUrl());
            if (workspace == null) throw new IllegalStateException("workspace-limit-reached");
            host.hideOverview();
            host.activateWorkspace(workspace);
            return targetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_NEW_CHILD_TAB.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireWorkspace(request, false);
            host.activateWorkspace(workspace);
            ReBrowserStore.Tab tab = workspaceController.addTab(workspace, preferences.homeUrl());
            if (tab == null) throw new IllegalStateException("child-tab-limit-reached");
            host.hideOverview();
            host.showTab(tab);
            return targetDetails(workspace, tab);
        }
        if (ReBrowserAdminProtocol.OP_SHOW_WORKSPACES.equals(operation)) {
            host.showWorkspaceOverview();
            return new JSONObject().put("view", "workspaces");
        }
        if (ReBrowserAdminProtocol.OP_SHOW_CHILD_TABS.equals(operation)) {
            host.showChildOverview();
            return new JSONObject().put("view", "tabs");
        }
        if (ReBrowserAdminProtocol.OP_OPEN_SETTINGS.equals(operation)) {
            host.openSettings();
            return new JSONObject().put("view", "settings");
        }
        if (ReBrowserAdminProtocol.OP_SHOW_DOWNLOADS.equals(operation)) {
            host.showDownloadOverview();
            return new JSONObject().put("view", "downloads");
        }
        if (ReBrowserAdminProtocol.OP_ACTIVATE_WORKSPACE.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireWorkspace(request, false);
            host.hideOverview();
            host.activateWorkspace(workspace);
            return targetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_ACTIVATE_TAB.equals(operation)) {
            return activateTarget(request);
        }
        if (ReBrowserAdminProtocol.OP_SET_PREFERENCE.equals(operation)) {
            return setPreference(request);
        }
        if (ReBrowserAdminProtocol.OP_ASSERT_LOCATION.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireWorkspace(request, false);
            ReBrowserStore.Tab tab = requireTab(workspace, request);
            String currentUrl = webController.currentUrl(tab.id);
            String actual = currentUrl == null ? tab.url : currentUrl;
            return targetDetails(workspace, tab)
                    .put("matches", samePage(actual, request.getString("url")))
                    .put("origin", safeOrigin(actual));
        }
        if (isNavigationOperation(operation)) return executeNavigation(request);
        if (ReBrowserAdminProtocol.OP_LOCK_SECONDARY.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireWorkspace(request, false);
            if (!workspaceController.lockAsSecondary(workspace)) {
                throw new IllegalStateException("workspace-cannot-lock");
            }
            host.refreshLifecycleUi();
            return targetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_UNLOCK_TEMPORARY.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireWorkspace(request, false);
            if (!workspaceController.unlockToTemporary(workspace)) {
                throw new IllegalStateException("workspace-cannot-unlock");
            }
            host.refreshLifecycleUi();
            return targetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_PROMOTE_PRIMARY.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireWorkspace(request, false);
            if (!workspaceController.promoteToPrimary(
                    workspace, preferences.forcePrimaryPromotionEnabled())) {
                throw new IllegalStateException("workspace-cannot-promote");
            }
            host.refreshLifecycleUi();
            return targetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_DEMOTE_SECONDARY.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireWorkspace(request, false);
            if (!workspaceController.demotePrimaryToSecondary(workspace)) {
                throw new IllegalStateException("workspace-cannot-demote");
            }
            host.refreshLifecycleUi();
            return targetDetails(workspace, workspace.activeTab());
        }
        if (ReBrowserAdminProtocol.OP_SHELVE_WORKSPACE.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireWorkspace(request, false);
            if (workspace.level != ReBrowserStore.Level.SECONDARY) {
                throw new IllegalStateException("only-secondary-can-shelve");
            }
            String workspaceId = workspace.id;
            host.closeWorkspace(workspace);
            return new JSONObject().put("workspaceId", workspaceId).put("shelved", true);
        }
        if (ReBrowserAdminProtocol.OP_RESTORE_WORKSPACE.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireWorkspace(request, true);
            if (!workspaceController.restoreSecondary(workspace)) {
                throw new IllegalStateException("workspace-cannot-restore");
            }
            host.hideOverview();
            host.activateWorkspace(workspace);
            return targetDetails(workspace, workspace.activeTab()).put("restored", true);
        }
        if (ReBrowserAdminProtocol.OP_CLOSE_WORKSPACE.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireWorkspace(request, false);
            if (workspace.level == ReBrowserStore.Level.PRIMARY) {
                throw new IllegalStateException("primary-workspace-cannot-close");
            }
            String workspaceId = workspace.id;
            boolean shelved = workspace.level == ReBrowserStore.Level.SECONDARY;
            host.closeWorkspace(workspace);
            return new JSONObject().put("workspaceId", workspaceId)
                    .put("closed", true).put("shelved", shelved);
        }
        if (ReBrowserAdminProtocol.OP_CLOSE_TAB.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireWorkspace(request, false);
            ReBrowserStore.Tab tab = requireTab(workspace, request);
            host.activateWorkspace(workspace);
            host.showTab(tab);
            String tabId = tab.id;
            host.closeTab(tab);
            return new JSONObject().put("workspaceId", workspace.id)
                    .put("tabId", tabId).put("closed", true);
        }
        if (ReBrowserAdminProtocol.OP_APPROVE_DOWNLOAD.equals(operation)) {
            ReBrowserDownloads.Record record = requireDownload(request);
            downloadController.approve(record);
            return downloadDetails(record);
        }
        if (ReBrowserAdminProtocol.OP_REJECT_DOWNLOAD.equals(operation)) {
            ReBrowserDownloads.Record record = requireDownload(request);
            downloadController.reject(record);
            return downloadDetails(record);
        }
        if (ReBrowserAdminProtocol.OP_CANCEL_DOWNLOAD.equals(operation)) {
            ReBrowserDownloads.Record record = requireDownload(request);
            if (!ReBrowserDownloads.STATUS_DOWNLOADING.equals(record.status)) {
                throw new IllegalStateException("download-not-running");
            }
            downloadController.cancel(record);
            return downloadDetails(record);
        }
        if (ReBrowserAdminProtocol.OP_RETRY_DOWNLOAD.equals(operation)) {
            ReBrowserDownloads.Record record = requireDownload(request);
            downloadController.retry(record);
            return downloadDetails(record);
        }
        if (ReBrowserAdminProtocol.OP_DELETE_DOWNLOAD.equals(operation)) {
            ReBrowserDownloads.Record record = requireDownload(request);
            downloadController.delete(record);
            return new JSONObject().put("downloadId", record.id).put("deleted", true);
        }
        if (ReBrowserAdminProtocol.OP_CLEAR_DOWNLOADS.equals(operation)) {
            return new JSONObject().put("deletedCount", downloadController.clear());
        }
        if (ReBrowserAdminProtocol.OP_REPAIR_DOWNLOADS.equals(operation)) {
            ReBrowserDownloadController.RepairResult result = downloadController.repair();
            return new JSONObject().put("refreshed", result.refreshed)
                    .put("expired", result.expired)
                    .put("removedDeleted", result.removedDeleted)
                    .put("remaining", result.remaining);
        }
        if (ReBrowserAdminProtocol.OP_DELETE_SHELVED.equals(operation)) {
            ReBrowserStore.Workspace workspace = requireWorkspace(request, true);
            if (!workspaceController.deleteShelvedSecondary(workspace)) {
                throw new IllegalStateException("shelved-workspace-not-found");
            }
            host.deleteProfileIfPossible(workspace.profileName);
            return new JSONObject().put("workspaceId", workspace.id).put("deleted", true);
        }
        throw new IllegalArgumentException("unsupported-operation");
    }

    private JSONObject executeNavigation(JSONObject request) throws Exception {
        String operation = request.getString("operation");
        ReBrowserStore.Workspace workspace = requireWorkspace(request, false);
        ReBrowserStore.Tab tab = requireTab(workspace, request);
        host.hideOverview();
        host.activateWorkspace(workspace);
        host.showTab(tab);
        if (webController.currentUrl(tab.id) == null) {
            throw new IllegalStateException("webview-unavailable");
        }
        webController.stop(tab.id);
        if (ReBrowserAdminProtocol.OP_GO_BACK.equals(operation)
                && !webController.canGoBack(tab.id)) {
            throw new IllegalStateException("no-back-history");
        }
        if (ReBrowserAdminProtocol.OP_GO_FORWARD.equals(operation)
                && !webController.canGoForward(tab.id)) {
            throw new IllegalStateException("no-forward-history");
        }
        if (request.optBoolean("waitForLoad", false)
                && !ReBrowserAdminProtocol.OP_STOP.equals(operation)) {
            queueLoadWait(request, tab);
        }
        if (ReBrowserAdminProtocol.OP_OPEN_URL.equals(operation)) {
            host.navigateActiveTab(request.getString("url"));
        } else if (ReBrowserAdminProtocol.OP_RELOAD.equals(operation)) {
            webController.stop(tab.id);
            webController.reload(tab.id);
        } else if (ReBrowserAdminProtocol.OP_STOP.equals(operation)) {
            webController.stop(tab.id);
            JSONObject pending = pendingLoads.remove(tab.id);
            if (pending != null) recordStatus(pending, "failed", null, "navigation-stopped");
        } else if (ReBrowserAdminProtocol.OP_GO_BACK.equals(operation)) {
            webController.goBack(tab.id);
        } else if (ReBrowserAdminProtocol.OP_GO_FORWARD.equals(operation)) {
            webController.goForward(tab.id);
        } else if (ReBrowserAdminProtocol.OP_GO_HOME.equals(operation)) {
            host.navigateActiveTab(preferences.homeUrl());
        }
        if (request.optBoolean("waitForLoad", false)
                && !ReBrowserAdminProtocol.OP_STOP.equals(operation)) return null;
        return targetDetails(workspace, tab)
                .put("origin", safeOrigin(tab.url))
                .put("navigationDispatched", true);
    }

    private static boolean isNavigationOperation(String operation) {
        return ReBrowserAdminProtocol.OP_OPEN_URL.equals(operation)
                || ReBrowserAdminProtocol.OP_RELOAD.equals(operation)
                || ReBrowserAdminProtocol.OP_STOP.equals(operation)
                || ReBrowserAdminProtocol.OP_GO_BACK.equals(operation)
                || ReBrowserAdminProtocol.OP_GO_FORWARD.equals(operation)
                || ReBrowserAdminProtocol.OP_GO_HOME.equals(operation);
    }

    private JSONObject activateTarget(JSONObject request) throws Exception {
        ReBrowserStore.Workspace workspace = requireWorkspace(request, false);
        ReBrowserStore.Tab tab = requireTab(workspace, request);
        host.hideOverview();
        host.activateWorkspace(workspace);
        host.showTab(tab);
        return targetDetails(workspace, tab);
    }

    private ReBrowserStore.Workspace requireWorkspace(JSONObject request, boolean shelvedOnly) {
        String workspaceId = request.optString("workspaceId", "");
        if (workspaceId.isBlank() && request.has("tabId")) {
            String tabId = request.optString("tabId");
            for (ReBrowserStore.Workspace workspace : activeWorkspaces()) {
                for (ReBrowserStore.Tab tab : workspace.tabs) {
                    if (tab.id.equals(tabId)) return workspace;
                }
            }
        }
        if (workspaceId.isBlank() && !shelvedOnly) return activeWorkspace();
        List<ReBrowserStore.Workspace> source = shelvedOnly
                ? shelvedWorkspaces() : activeWorkspaces();
        for (ReBrowserStore.Workspace workspace : source) {
            if (workspace.id.equals(workspaceId)) return workspace;
        }
        throw new IllegalStateException("workspace-not-found");
    }

    private static ReBrowserStore.Tab requireTab(
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

    private JSONObject setPreference(JSONObject request) throws Exception {
        String name = request.getString("name");
        Object value = request.get("value");
        if ("searchEngine".equals(name)) {
            preferences.setSearchEngine((String) value);
        } else if ("javascript".equals(name)) {
            preferences.setJavascriptEnabled((Boolean) value);
        } else if ("thirdPartyCookies".equals(name)) {
            preferences.setThirdPartyCookiesEnabled((Boolean) value);
        } else if ("desktopMode".equals(name)) {
            preferences.setDesktopModeEnabled((Boolean) value);
        } else if ("forcePrimaryPromotion".equals(name)) {
            preferences.setForcePrimaryPromotionEnabled((Boolean) value);
        } else if ("globalOrientation".equals(name)) {
            preferences.setGlobalOrientation((String) value);
            if (!fullscreenController.isFullscreen()) {
                fullscreenController.applyGlobalOrientationPreference();
            }
        } else if ("videoOrientationOverride".equals(name)) {
            preferences.setVideoOrientationOverrideEnabled((Boolean) value);
        } else if ("videoOrientation".equals(name)) {
            preferences.setVideoOrientation((String) value);
        } else {
            throw new IllegalArgumentException("unsupported-preference");
        }
        webController.applyPreferencesToAll();
        return new JSONObject().put("name", name).put("updated", true);
    }

    private JSONObject capabilities() throws Exception {
        JSONObject value = new JSONObject();
        value.put("protocolVersion", ReBrowserAdminProtocol.VERSION);
        value.put("operations", ReBrowserAdminProtocol.supportedOperations());
        value.put("administratorRoots", ReBrowserAdminKeys.capabilities());
        value.put("ownerCredentialMaximumLevel", 2);
        value.put("rootKeyMaximumLevel", 3);
        value.put("maxWorkspaces", ReBrowserStore.MAX_WORKSPACES);
        value.put("maxPrimaryWorkspaces", ReBrowserStore.MAX_PRIMARY_WORKSPACES);
        value.put("maxTabsPerWorkspace", ReBrowserStore.MAX_TABS_PER_WORKSPACE);
        value.put("multiProfile", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE));
        value.put("deleteBrowsingData",
                WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA));
        value.put("saveState", WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE));
        value.put("downloads", downloadPolicy());
        return value;
    }

    private JSONObject diagnostics() throws Exception {
        JSONObject value = new JSONObject();
        android.content.pm.PackageInfo provider = ReBrowserWebController.currentProvider();
        value.put("webViewPackage", provider == null ? "" : provider.packageName);
        value.put("webViewVersion", provider == null ? "" : provider.versionName);
        value.put("multiProfile", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE));
        ReBrowserStore.Workspace workspace = activeWorkspace();
        value.put("activeWorkspaceId", workspace == null ? "" : workspace.id);
        ReBrowserStore.Tab tab = workspace == null ? null : workspace.activeTab();
        value.put("activeTabId", tab == null ? "" : tab.id);
        value.put("activeOrigin", tab == null ? "" : safeOrigin(tab.url));
        value.put("loading", tab != null && webController.isLoading(tab.id));
        value.put("loadProgress", tab == null ? 0 : webController.loadProgress(tab.id));
        value.put("lastMainFrameError", tab == null ? ""
                : webController.lastMainFrameError(tab.id));
        value.put("fullscreenVideo", fullscreenController.isFullscreen());
        value.put("globalOrientation", preferences.globalOrientation());
        value.put("videoOrientationOverride", preferences.videoOrientationOverrideEnabled());
        value.put("videoOrientation", preferences.videoOrientation());
        value.put("activeWorkspaceCount", activeWorkspaces().size());
        value.put("shelvedWorkspaceCount", shelvedWorkspaces().size());
        value.put("pendingProfileDeletionCount",
                workspaceController.pendingProfileDeletions().size());
        value.put("downloadRecordCount", downloadController.records().size());
        value.put("downloadsEnabled", downloadController.downloadsEnabled());
        value.put("pendingDownloadCount", downloadController.pendingCount());
        value.put("activeBlobTransfer", downloadController.hasActiveBlobTransfer());
        return value;
    }

    private JSONObject downloadPolicy() throws Exception {
        return new JSONObject()
                .put("downloadsEnabled", downloadController.downloadsEnabled())
                .put("scope", "ReBrowser-named-profiles-only")
                .put("rateWindowSeconds", 300)
                .put("ordinaryRequestsPerSite", 2)
                .put("siteDefinition", "profileName+topLevelOrigin")
                .put("maxPendingGlobal", ReBrowserDownloadController.MAX_PENDING_GLOBAL)
                .put("maxPendingPerSite", ReBrowserDownloadController.MAX_PENDING_PER_SITE)
                .put("maxHistory", ReBrowserDownloads.MAX_RECORDS)
                .put("maxBlobBytes", ReBrowserDownloads.MAX_BLOB_BYTES)
                .put("maxDataBytes", ReBrowserDownloads.MAX_DATA_BYTES)
                .put("automaticOpen", false)
                .put("automaticInstall", false)
                .put("rawCookieDisclosure", false)
                .put("policyEffect", "new-and-pending-requests")
                .put("runningDownloadsContinueWhenDisabled", true);
    }

    private JSONObject downloads() throws Exception {
        downloadController.refresh();
        JSONArray values = new JSONArray();
        for (ReBrowserDownloads.Record record : downloadController.records()) {
            values.put(downloadDetails(record));
        }
        return new JSONObject().put("downloads", values)
                .put("pendingCount", downloadController.pendingCount());
    }

    private static JSONObject downloadDetails(ReBrowserDownloads.Record record) throws Exception {
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

    private ReBrowserDownloads.Record requireDownload(JSONObject request) {
        ReBrowserDownloads.Record record = downloadController.find(request.optString("downloadId"));
        if (record == null) throw new IllegalStateException("download-not-found");
        return record;
    }

    private JSONObject state() throws Exception {
        JSONObject value = new JSONObject();
        value.put("version", 2);
        value.put("activeWorkspaceId", activeWorkspace() == null ? "" : activeWorkspace().id);
        value.put("workspaces", workspaceValues(activeWorkspaces()));
        value.put("shelvedSecondaries", workspaceValues(shelvedWorkspaces()));
        return value;
    }

    private JSONArray workspaceValues(List<ReBrowserStore.Workspace> source) throws Exception {
        JSONArray values = new JSONArray();
        for (ReBrowserStore.Workspace workspace : source) {
            JSONObject workspaceValue = new JSONObject();
            workspaceValue.put("id", workspace.id);
            workspaceValue.put("profileName", workspace.profileName);
            workspaceValue.put("title", workspace.title);
            workspaceValue.put("level", workspace.level.name());
            workspaceValue.put("activeTabId", workspace.activeTabId);
            JSONArray tabs = new JSONArray();
            for (ReBrowserStore.Tab tab : workspace.tabs) {
                JSONObject tabValue = new JSONObject();
                tabValue.put("id", tab.id);
                tabValue.put("title", tab.title);
                tabValue.put("origin", safeOrigin(tab.url));
                tabValue.put("loading", webController.isLoading(tab.id));
                tabValue.put("loadProgress", webController.loadProgress(tab.id));
                tabValue.put("lastMainFrameError", webController.lastMainFrameError(tab.id));
                tabs.put(tabValue);
            }
            workspaceValue.put("tabs", tabs);
            values.put(workspaceValue);
        }
        return values;
    }

    private JSONObject validateState() throws Exception {
        JSONArray issues = new JSONArray();
        Set<String> workspaceIds = new HashSet<>();
        Set<String> tabIds = new HashSet<>();
        int primaryCount = 0;
        Set<String> pendingProfileDeletions = workspaceController.pendingProfileDeletions();
        for (ReBrowserStore.Workspace workspace : activeWorkspaces()) {
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
        for (ReBrowserStore.Workspace workspace : shelvedWorkspaces()) {
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
        if (activeWorkspaces().size() + shelvedWorkspaces().size()
                > ReBrowserStore.MAX_WORKSPACES) issues.put("workspace-limit-exceeded");
        return new JSONObject().put("valid", issues.length() == 0).put("issues", issues);
    }

    private JSONObject repairState() throws Exception {
        int cancelledDeletionMarkers = 0;
        Set<String> pendingProfileDeletions = workspaceController.pendingProfileDeletions();
        for (ReBrowserStore.Workspace workspace : activeWorkspaces()) {
            workspace.activeTab();
            if (workspace.level != ReBrowserStore.Level.TEMPORARY
                    && pendingProfileDeletions.contains(workspace.profileName)) {
                workspaceController.unmarkProfileForDeletion(workspace.profileName);
                cancelledDeletionMarkers++;
            }
        }
        for (ReBrowserStore.Workspace workspace : shelvedWorkspaces()) {
            workspace.activeTab();
            if (pendingProfileDeletions.contains(workspace.profileName)) {
                workspaceController.unmarkProfileForDeletion(workspace.profileName);
                cancelledDeletionMarkers++;
            }
        }
        workspaceController.saveAll();
        return new JSONObject().put("cancelledDeletionMarkers", cancelledDeletionMarkers)
                .put("validation", validateState());
    }

    void onPageStarted(ReBrowserStore.Tab tab) {
        if (tab == null) return;
        JSONObject pending = pendingLoads.get(tab.id);
        if (pending != null) {
            try {
                pending.put("_loadStarted", true);
            } catch (Exception ignored) {
                // Boolean insertion cannot fail for a normal JSONObject.
            }
        }
    }

    void onPageFinished(ReBrowserStore.Tab tab) {
        if (tab == null) return;
        finishLoad(tab, true, null);
    }

    void onMainFrameError(ReBrowserStore.Tab tab, int errorCode) {
        if (tab == null) return;
        finishLoad(tab, false, "page-load-error:" + errorCode);
    }

    void onWorkspaceDeactivated(ReBrowserStore.Workspace workspace) {
        if (workspace == null) return;
        for (ReBrowserStore.Tab tab : workspace.tabs) {
            JSONObject pending = pendingLoads.remove(tab.id);
            if (pending != null) {
                recordStatus(pending, "failed", null, "workspace-deactivated");
            }
        }
    }

    void onTabClosing(ReBrowserStore.Tab tab) {
        if (tab == null) return;
        JSONObject pending = pendingLoads.remove(tab.id);
        if (pending != null) recordStatus(pending, "failed", null, "tab-closed-during-load");
    }

    void onDestroy() {
        for (JSONObject request : pendingLoads.values()) {
            recordStatus(request, "failed", null, "activity-destroyed-during-load");
        }
        pendingLoads.clear();
    }

    private void queueLoadWait(JSONObject request, ReBrowserStore.Tab tab) throws Exception {
        request.put("_loadStarted", false);
        JSONObject previous = pendingLoads.put(tab.id, request);
        if (previous != null) {
            recordStatus(previous, "failed", null, "superseded-by-new-navigation");
        }
        int timeoutSeconds = Math.max(1, Math.min(60, request.optInt("timeoutSeconds", 30)));
        String requestId = request.getString("requestId");
        mainHandler.postDelayed(() -> {
            JSONObject pending = pendingLoads.get(tab.id);
            if (pending == null || !requestId.equals(pending.optString("requestId"))) return;
            pendingLoads.remove(tab.id);
            recordStatus(pending, "failed", null, "page-load-timeout");
        }, timeoutSeconds * 1_000L);
    }

    private void finishLoad(ReBrowserStore.Tab tab, boolean success, String error) {
        JSONObject request = pendingLoads.get(tab.id);
        if (request == null || !request.optBoolean("_loadStarted", false)) return;
        pendingLoads.remove(tab.id);
        try {
            JSONObject details = targetDetails(activeWorkspace(), tab);
            details.put("origin", safeOrigin(tab.url));
            details.put("title", tab.title);
            details.put("loadProgress", webController.loadProgress(tab.id));
            recordStatus(request, success ? "completed" : "failed",
                    success ? details : null, error);
        } catch (Exception resultError) {
            recordStatus(request, "failed", null, resultError.getClass().getSimpleName());
        }
    }

    private void recordStatus(
            JSONObject request,
            String status,
            JSONObject details,
            String error
    ) {
        ReBrowserAdminProtocol.recordStatus(context, request, status,
                request.optString("_authentication"), request.optString("_keyId"),
                details, error);
    }

    private ReBrowserStore.Workspace activeWorkspace() {
        return workspaceController.activeWorkspace();
    }

    private List<ReBrowserStore.Workspace> activeWorkspaces() {
        return workspaceController.activeWorkspaces();
    }

    private List<ReBrowserStore.Workspace> shelvedWorkspaces() {
        return workspaceController.shelvedWorkspaces();
    }

    private static JSONObject targetDetails(
            ReBrowserStore.Workspace workspace,
            ReBrowserStore.Tab tab
    ) throws Exception {
        JSONObject details = new JSONObject();
        details.put("workspaceId", workspace == null ? "" : workspace.id);
        details.put("tabId", tab == null ? "" : tab.id);
        if (workspace != null) details.put("level", workspace.level.name());
        return details;
    }

    private static String redactedDownloadLocation(String value) {
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return scheme == null ? "" : scheme.toLowerCase(Locale.ROOT) + ":";
            }
            return safeOrigin(value);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String safeOrigin(String value) {
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
}
