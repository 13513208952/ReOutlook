package io.github.reoutlook;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Sole owner of ReBrowser workspace collections, active selection, and metadata persistence. */
final class ReBrowserWorkspaceController {
    static final class TabCloseResult {
        final boolean valid;
        final boolean resetOnly;
        final ReBrowserStore.Tab nextActiveTab;

        private TabCloseResult(
                boolean valid,
                boolean resetOnly,
                ReBrowserStore.Tab nextActiveTab
        ) {
            this.valid = valid;
            this.resetOnly = resetOnly;
            this.nextActiveTab = nextActiveTab;
        }

        static TabCloseResult invalid() {
            return new TabCloseResult(false, false, null);
        }
    }

    private final ReBrowserStore store;
    private final List<ReBrowserStore.Workspace> activeWorkspaces;
    private final List<ReBrowserStore.Workspace> shelvedWorkspaces;
    private final List<ReBrowserStore.Workspace> activeView;
    private final List<ReBrowserStore.Workspace> shelvedView;
    private ReBrowserStore.Workspace activeWorkspace;

    ReBrowserWorkspaceController(ReBrowserStore store) {
        this.store = store;
        activeWorkspaces = new java.util.ArrayList<>();
        shelvedWorkspaces = new java.util.ArrayList<>();
        activeView = Collections.unmodifiableList(activeWorkspaces);
        shelvedView = Collections.unmodifiableList(shelvedWorkspaces);
    }

    void initialize(String homeUrl) {
        if (!activeWorkspaces.isEmpty() || !shelvedWorkspaces.isEmpty()
                || activeWorkspace != null) return;
        activeWorkspaces.addAll(store.loadPersistentWorkspaces());
        shelvedWorkspaces.addAll(store.loadShelvedSecondaryWorkspaces(activeWorkspaces));
        if (activeWorkspaces.isEmpty()) activeWorkspaces.add(newTemporary(homeUrl));
        activeWorkspace = chooseInitialWorkspace();
    }

    List<ReBrowserStore.Workspace> activeWorkspaces() {
        return activeView;
    }

    List<ReBrowserStore.Workspace> shelvedWorkspaces() {
        return shelvedView;
    }

    ReBrowserStore.Workspace activeWorkspace() {
        return activeWorkspace;
    }

    int totalWorkspaceCount() {
        return activeWorkspaces.size() + shelvedWorkspaces.size();
    }

    boolean canCreateWorkspace() {
        return totalWorkspaceCount() < ReBrowserStore.MAX_WORKSPACES;
    }

    ReBrowserStore.Workspace createTemporary(String homeUrl) {
        if (!canCreateWorkspace()) return null;
        ReBrowserStore.Workspace workspace = newTemporary(homeUrl);
        activeWorkspaces.add(workspace);
        return workspace;
    }

    ReBrowserStore.Workspace ensureActiveWorkspace(String homeUrl) {
        if (activeWorkspaces.isEmpty()) activeWorkspaces.add(newTemporary(homeUrl));
        if (activeWorkspace == null || !activeWorkspaces.contains(activeWorkspace)) {
            activeWorkspace = chooseInitialWorkspace();
        }
        return activeWorkspace;
    }

    boolean activate(ReBrowserStore.Workspace workspace) {
        if (workspace == null || !activeWorkspaces.contains(workspace)) return false;
        activeWorkspace = workspace;
        return true;
    }

    void clearActiveWorkspace() {
        activeWorkspace = null;
    }

    ReBrowserStore.Tab addTab(ReBrowserStore.Workspace workspace, String url) {
        if (!activeWorkspaces.contains(workspace)) return null;
        return store.addTab(workspace, url);
    }

    void discardAddedTab(ReBrowserStore.Workspace workspace, ReBrowserStore.Tab tab) {
        if (workspace == null || tab == null || !activeWorkspaces.contains(workspace)) return;
        workspace.tabs.remove(tab);
        workspace.activeTab();
        save();
    }

    boolean selectTab(ReBrowserStore.Workspace workspace, ReBrowserStore.Tab tab) {
        if (workspace == null || tab == null || !activeWorkspaces.contains(workspace)
                || !workspace.tabs.contains(tab)) return false;
        workspace.activeTabId = tab.id;
        return true;
    }

    TabCloseResult closeTab(
            ReBrowserStore.Workspace workspace,
            ReBrowserStore.Tab tab,
            String homeUrl
    ) {
        if (workspace == null || tab == null || !activeWorkspaces.contains(workspace)
                || !workspace.tabs.contains(tab)) return TabCloseResult.invalid();
        if (workspace.tabs.size() == 1) {
            tab.title = "新子标签页";
            tab.url = ReBrowserStore.safeUrl(homeUrl);
            workspace.activeTabId = tab.id;
            save();
            return new TabCloseResult(true, true, tab);
        }
        int oldIndex = workspace.tabs.indexOf(tab);
        boolean wasActive = tab.id.equals(workspace.activeTabId);
        workspace.tabs.remove(tab);
        ReBrowserStore.Tab next = null;
        if (wasActive) {
            int nextIndex = Math.min(oldIndex, workspace.tabs.size() - 1);
            next = workspace.tabs.get(nextIndex);
            workspace.activeTabId = next.id;
        }
        save();
        return new TabCloseResult(true, false, next);
    }

    void updateTab(ReBrowserStore.Tab tab, String url, String title) {
        if (!ownsTab(tab)) return;
        if (url != null) tab.url = ReBrowserStore.safeUrl(url);
        if (title != null && !title.isBlank()) {
            tab.title = boundedTitle(title);
        }
    }

    void updatePageMetadata(ReBrowserStore.Tab tab, String url, String title) {
        updateTab(tab, url, title);
        ReBrowserStore.Workspace workspace = activeWorkspace;
        if (workspace != null && workspace.level == ReBrowserStore.Level.TEMPORARY
                && tab != null && tab.id.equals(workspace.activeTabId)
                && title != null && !title.isBlank()) {
            workspace.title = boundedTitle(title);
        }
    }

    boolean lockAsSecondary(ReBrowserStore.Workspace workspace) {
        return activeWorkspaces.contains(workspace)
                && store.lockAsSecondary(workspace, activeWorkspaces);
    }

    boolean unlockToTemporary(ReBrowserStore.Workspace workspace) {
        return activeWorkspaces.contains(workspace)
                && store.unlockToTemporary(workspace, activeWorkspaces);
    }

    boolean promoteToPrimary(ReBrowserStore.Workspace workspace, boolean allowTemporary) {
        return activeWorkspaces.contains(workspace)
                && store.promoteToPrimary(workspace, activeWorkspaces, allowTemporary);
    }

    boolean demotePrimaryToSecondary(ReBrowserStore.Workspace workspace) {
        return activeWorkspaces.contains(workspace)
                && store.demotePrimaryToSecondary(workspace, activeWorkspaces);
    }

    boolean canPromoteToPrimary() {
        return store.canPromoteToPrimary(activeWorkspaces);
    }

    boolean shelfSecondary(ReBrowserStore.Workspace workspace) {
        if (!activeWorkspaces.contains(workspace)
                || workspace.level != ReBrowserStore.Level.SECONDARY) return false;
        store.shelfSecondary(workspace, activeWorkspaces, shelvedWorkspaces);
        if (workspace == activeWorkspace) activeWorkspace = null;
        return shelvedWorkspaces.contains(workspace);
    }

    boolean removeTemporary(ReBrowserStore.Workspace workspace) {
        if (!activeWorkspaces.contains(workspace)
                || workspace.level != ReBrowserStore.Level.TEMPORARY) return false;
        store.removeWorkspace(workspace, activeWorkspaces);
        if (workspace == activeWorkspace) activeWorkspace = null;
        return !activeWorkspaces.contains(workspace);
    }

    boolean restoreSecondary(ReBrowserStore.Workspace workspace) {
        return shelvedWorkspaces.contains(workspace)
                && store.restoreSecondary(workspace, activeWorkspaces, shelvedWorkspaces);
    }

    boolean deleteShelvedSecondary(ReBrowserStore.Workspace workspace) {
        if (!shelvedWorkspaces.contains(workspace)) return false;
        store.deleteShelvedSecondary(workspace, shelvedWorkspaces);
        return !shelvedWorkspaces.contains(workspace);
    }

    void save() {
        store.save(activeWorkspaces);
    }

    void saveAll() {
        store.save(activeWorkspaces);
        store.saveShelvedSecondaryWorkspaces(shelvedWorkspaces);
    }

    Set<String> pendingProfileDeletions() {
        return store.pendingProfileDeletions();
    }

    void unmarkProfileForDeletion(String profileName) {
        store.unmarkProfileForDeletion(profileName);
    }

    private boolean ownsTab(ReBrowserStore.Tab tab) {
        if (tab == null) return false;
        for (ReBrowserStore.Workspace workspace : activeWorkspaces) {
            if (workspace.tabs.contains(tab)) return true;
        }
        for (ReBrowserStore.Workspace workspace : shelvedWorkspaces) {
            if (workspace.tabs.contains(tab)) return true;
        }
        return false;
    }

    private ReBrowserStore.Workspace newTemporary(String homeUrl) {
        ReBrowserStore.Workspace workspace = store.createTemporaryWorkspace();
        ReBrowserStore.Tab tab = workspace.activeTab();
        if (tab != null) tab.url = ReBrowserStore.safeUrl(homeUrl);
        return workspace;
    }

    private ReBrowserStore.Workspace chooseInitialWorkspace() {
        for (ReBrowserStore.Workspace workspace : activeWorkspaces) {
            if (workspace.level == ReBrowserStore.Level.PRIMARY) return workspace;
        }
        return activeWorkspaces.get(0);
    }

    private static String boundedTitle(String value) {
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.substring(0, Math.min(clean.length(), 160));
    }
}
