package io.github.reoutlook;

import java.util.HashSet;
import java.util.Set;

/** Owns only transient Overview page and multi-selection state. */
final class ReBrowserOverviewController {
    enum Page {
        NONE,
        WORKSPACES,
        CHILD_TABS,
        FAVORITES,
        WORKSPACE_BOOKMARKS,
        DOWNLOADS
    }

    private final Set<String> selectedWorkspaceIds = new HashSet<>();
    private final Set<String> selectedChildTabIds = new HashSet<>();
    private Page page = Page.NONE;

    Page page() {
        return page;
    }

    boolean is(Page expected) {
        return page == expected;
    }

    void show(Page next) {
        page = next;
        if (next != Page.WORKSPACES) clearWorkspaceSelection();
        if (next != Page.CHILD_TABS) clearChildSelection();
    }

    void hide() {
        page = Page.NONE;
        clearWorkspaceSelection();
        clearChildSelection();
    }

    boolean hasWorkspaceSelection() {
        return !selectedWorkspaceIds.isEmpty();
    }

    boolean hasChildSelection() {
        return !selectedChildTabIds.isEmpty();
    }

    int selectedWorkspaceCount() {
        return selectedWorkspaceIds.size();
    }

    int selectedChildTabCount() {
        return selectedChildTabIds.size();
    }

    boolean isWorkspaceSelected(String workspaceId) {
        return selectedWorkspaceIds.contains(workspaceId);
    }

    boolean isChildTabSelected(String tabId) {
        return selectedChildTabIds.contains(tabId);
    }

    void startWorkspaceSelection(String workspaceId) {
        selectedWorkspaceIds.add(workspaceId);
    }

    void toggleWorkspaceSelection(String workspaceId) {
        if (!selectedWorkspaceIds.add(workspaceId)) selectedWorkspaceIds.remove(workspaceId);
    }

    void clearWorkspaceSelection() {
        selectedWorkspaceIds.clear();
    }

    void startChildTabSelection(String tabId) {
        selectedChildTabIds.add(tabId);
    }

    void toggleChildTabSelection(String tabId) {
        if (!selectedChildTabIds.add(tabId)) selectedChildTabIds.remove(tabId);
    }

    void clearChildSelection() {
        selectedChildTabIds.clear();
    }
}
