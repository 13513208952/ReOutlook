package io.github.reoutlook;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Persists browser-global workspace metadata, never website credentials or Profile data. */
final class ReBrowserStore {
    static final String HOME_URL = "https://cn.bing.com/";
    private static final String PREFERENCES = "rebrowser_workspace_store_v1";
    private static final String WORKSPACES = "workspaces";
    private static final String PENDING_PROFILE_DELETIONS = "pending_profile_deletions";
    private static final String PROFILE_PREFIX = "rebrowser_workspace_";
    private static final Pattern ID_PATTERN = Pattern.compile("[a-f0-9]{32}");
    static final int MAX_WORKSPACES = 24;
    private static final int MAX_TABS_PER_WORKSPACE = 50;

    enum Level {
        TEMPORARY,
        SECONDARY,
        PRIMARY
    }

    static final class Tab {
        final String id;
        String title;
        String url;

        Tab(String id, String title, String url) {
            this.id = id;
            this.title = cleanTitle(title, "新标签页");
            this.url = safeUrl(url);
        }
    }

    static final class Workspace {
        final String id;
        final String profileName;
        final List<Tab> tabs = new ArrayList<>();
        String title;
        String activeTabId;
        Level level;

        Workspace(String id, String title, Level level) {
            this.id = id;
            this.profileName = PROFILE_PREFIX + id;
            this.title = cleanTitle(title, "临时标签页");
            this.level = level;
        }

        Tab activeTab() {
            for (Tab tab : tabs) {
                if (tab.id.equals(activeTabId)) return tab;
            }
            if (tabs.isEmpty()) return null;
            activeTabId = tabs.get(0).id;
            return tabs.get(0);
        }
    }

    private final SharedPreferences preferences;

    ReBrowserStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    List<Workspace> loadPersistentWorkspaces() {
        List<Workspace> result = new ArrayList<>();
        Set<String> workspaceIds = new HashSet<>();
        String serialized = preferences.getString(WORKSPACES, "[]");
        try {
            JSONArray values = new JSONArray(serialized);
            for (int index = 0; index < values.length() && result.size() < MAX_WORKSPACES; index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                String id = value.optString("id");
                if (!ID_PATTERN.matcher(id).matches() || !workspaceIds.add(id)) continue;
                Level level;
                try {
                    level = Level.valueOf(value.optString("level"));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (level == Level.TEMPORARY) continue;
                Workspace workspace = new Workspace(id, value.optString("title"), level);
                JSONArray tabs = value.optJSONArray("tabs");
                Set<String> tabIds = new HashSet<>();
                if (tabs != null) {
                    for (int tabIndex = 0;
                            tabIndex < tabs.length() && workspace.tabs.size() < MAX_TABS_PER_WORKSPACE;
                            tabIndex++) {
                        JSONObject tab = tabs.optJSONObject(tabIndex);
                        if (tab == null) continue;
                        String tabId = tab.optString("id");
                        if (!ID_PATTERN.matcher(tabId).matches() || !tabIds.add(tabId)) continue;
                        workspace.tabs.add(new Tab(
                                tabId, tab.optString("title"), tab.optString("url")));
                    }
                }
                if (workspace.tabs.isEmpty()) workspace.tabs.add(newTab());
                workspace.activeTabId = value.optString("activeTabId");
                workspace.activeTab();
                result.add(workspace);
            }
        } catch (Exception ignored) {
            // Corrupt metadata is discarded; named Profile data is not touched automatically.
        }
        normalizePrimary(result);
        return result;
    }

    Workspace createTemporaryWorkspace() {
        Workspace workspace = new Workspace(newId(), "临时标签页", Level.TEMPORARY);
        Tab tab = newTab();
        workspace.tabs.add(tab);
        workspace.activeTabId = tab.id;
        markProfileForDeletion(workspace.profileName);
        return workspace;
    }

    Tab addTab(Workspace workspace, String url) {
        if (workspace.tabs.size() >= MAX_TABS_PER_WORKSPACE) return null;
        Tab tab = new Tab(newId(), "新标签页", url);
        workspace.tabs.add(tab);
        workspace.activeTabId = tab.id;
        return tab;
    }

    boolean lockAsSecondary(Workspace workspace, List<Workspace> allWorkspaces) {
        if (workspace.level != Level.TEMPORARY) return false;
        int persistentCount = 0;
        for (Workspace candidate : allWorkspaces) {
            if (candidate.level != Level.TEMPORARY) persistentCount++;
        }
        if (persistentCount >= MAX_WORKSPACES) return false;
        workspace.level = Level.SECONDARY;
        workspace.title = cleanTitle(workspace.title, "副标签页");
        unmarkProfileForDeletion(workspace.profileName);
        save(allWorkspaces);
        return true;
    }

    void promoteToPrimary(Workspace workspace, List<Workspace> allWorkspaces) {
        if (workspace.level != Level.SECONDARY) return;
        for (Workspace candidate : allWorkspaces) {
            if (candidate.level == Level.PRIMARY) candidate.level = Level.SECONDARY;
        }
        workspace.level = Level.PRIMARY;
        save(allWorkspaces);
    }

    void removeWorkspace(Workspace workspace, List<Workspace> allWorkspaces) {
        allWorkspaces.remove(workspace);
        markProfileForDeletion(workspace.profileName);
        normalizePrimary(allWorkspaces);
        save(allWorkspaces);
    }

    void save(List<Workspace> workspaces) {
        JSONArray values = new JSONArray();
        int saved = 0;
        for (Workspace workspace : workspaces) {
            if (workspace.level == Level.TEMPORARY || saved >= MAX_WORKSPACES) continue;
            JSONObject value = new JSONObject();
            JSONArray tabs = new JSONArray();
            try {
                value.put("id", workspace.id);
                value.put("title", cleanTitle(workspace.title, "常用标签页"));
                value.put("level", workspace.level.name());
                value.put("activeTabId", workspace.activeTabId);
                for (int index = 0;
                        index < workspace.tabs.size() && index < MAX_TABS_PER_WORKSPACE;
                        index++) {
                    Tab tab = workspace.tabs.get(index);
                    JSONObject serializedTab = new JSONObject();
                    serializedTab.put("id", tab.id);
                    serializedTab.put("title", cleanTitle(tab.title, "新标签页"));
                    serializedTab.put("url", safeUrl(tab.url));
                    tabs.put(serializedTab);
                }
                value.put("tabs", tabs);
                values.put(value);
                saved++;
            } catch (Exception ignored) {
                // org.json only rejects unsupported values; every value here is a bounded string.
            }
        }
        preferences.edit().putString(WORKSPACES, values.toString()).apply();
    }

    Set<String> pendingProfileDeletions() {
        return new HashSet<>(preferences.getStringSet(
                PENDING_PROFILE_DELETIONS, Set.of()));
    }

    @SuppressLint("ApplySharedPref")
    void markProfileForDeletion(String profileName) {
        if (!isOwnedProfile(profileName)) return;
        Set<String> pending = pendingProfileDeletions();
        pending.add(profileName);
        preferences.edit().putStringSet(PENDING_PROFILE_DELETIONS, pending).commit();
    }

    @SuppressLint("ApplySharedPref")
    void unmarkProfileForDeletion(String profileName) {
        Set<String> pending = pendingProfileDeletions();
        if (!pending.remove(profileName)) return;
        preferences.edit().putStringSet(PENDING_PROFILE_DELETIONS, pending).commit();
    }

    static boolean isOwnedProfile(String profileName) {
        return profileName != null && profileName.startsWith(PROFILE_PREFIX)
                && ID_PATTERN.matcher(profileName.substring(PROFILE_PREFIX.length())).matches();
    }

    private static Tab newTab() {
        return new Tab(newId(), "新标签页", HOME_URL);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void normalizePrimary(List<Workspace> workspaces) {
        boolean foundPrimary = false;
        for (Workspace workspace : workspaces) {
            if (workspace.level != Level.PRIMARY) continue;
            if (foundPrimary) workspace.level = Level.SECONDARY;
            foundPrimary = true;
        }
    }

    private static String cleanTitle(String value, String fallback) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) clean = fallback;
        return clean.substring(0, Math.min(clean.length(), 160));
    }

    static String safeUrl(String value) {
        if (value == null || value.isBlank()) return HOME_URL;
        String clean = value.trim();
        if (clean.length() > 8192) return HOME_URL;
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://")
                || "about:blank".equals(lower)) return clean;
        return HOME_URL;
    }
}
