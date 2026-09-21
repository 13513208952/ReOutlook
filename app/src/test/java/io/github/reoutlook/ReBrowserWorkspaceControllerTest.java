package io.github.reoutlook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ReBrowserWorkspaceControllerTest {
    private ReBrowserStore store;
    private ReBrowserWorkspaceController controller;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("rebrowser_workspace_store_v1", Context.MODE_PRIVATE)
                .edit().clear().commit();
        store = new ReBrowserStore(context);
        controller = new ReBrowserWorkspaceController(store);
        controller.initialize(ReBrowserStore.HOME_URL);
    }

    @Test
    public void lifecycleTransitionsKeepProfileDeletionMarkerConsistent() {
        ReBrowserStore.Workspace workspace = controller.activeWorkspace();
        assertNotNull(workspace);
        assertEquals(ReBrowserStore.Level.TEMPORARY, workspace.level);
        assertTrue(controller.pendingProfileDeletions().contains(workspace.profileName));

        assertTrue(controller.lockAsSecondary(workspace));
        assertEquals(ReBrowserStore.Level.SECONDARY, workspace.level);
        assertFalse(controller.pendingProfileDeletions().contains(workspace.profileName));

        assertTrue(controller.unlockToTemporary(workspace));
        assertEquals(ReBrowserStore.Level.TEMPORARY, workspace.level);
        assertTrue(controller.pendingProfileDeletions().contains(workspace.profileName));

        assertFalse(controller.promoteToPrimary(workspace, false));
        assertTrue(controller.promoteToPrimary(workspace, true));
        assertEquals(ReBrowserStore.Level.PRIMARY, workspace.level);
        assertFalse(controller.pendingProfileDeletions().contains(workspace.profileName));

        assertTrue(controller.demotePrimaryToSecondary(workspace));
        assertEquals(ReBrowserStore.Level.SECONDARY, workspace.level);
        assertFalse(controller.pendingProfileDeletions().contains(workspace.profileName));
    }

    @Test
    public void primaryLimitAndWorkspaceLimitAreEnforced() {
        ReBrowserStore.Workspace first = controller.activeWorkspace();
        assertTrue(controller.promoteToPrimary(first, true));
        for (int index = 1; index < ReBrowserStore.MAX_PRIMARY_WORKSPACES; index++) {
            ReBrowserStore.Workspace workspace = controller.createTemporary(ReBrowserStore.HOME_URL);
            assertNotNull(workspace);
            assertTrue(controller.promoteToPrimary(workspace, true));
        }
        ReBrowserStore.Workspace sixth = controller.createTemporary(ReBrowserStore.HOME_URL);
        assertNotNull(sixth);
        assertFalse(controller.promoteToPrimary(sixth, true));

        while (controller.totalWorkspaceCount() < ReBrowserStore.MAX_WORKSPACES) {
            assertNotNull(controller.createTemporary(ReBrowserStore.HOME_URL));
        }
        assertFalse(controller.canCreateWorkspace());
        assertNull(controller.createTemporary(ReBrowserStore.HOME_URL));
    }

    @Test
    public void closingTabsSelectsNeighborAndResetsLastTab() {
        ReBrowserStore.Workspace workspace = controller.activeWorkspace();
        ReBrowserStore.Tab first = workspace.activeTab();
        ReBrowserStore.Tab second = controller.addTab(workspace, "https://www.baidu.com/");
        ReBrowserStore.Tab third = controller.addTab(workspace, "https://cn.bing.com/search?q=test");
        assertNotNull(second);
        assertNotNull(third);
        assertTrue(controller.selectTab(workspace, second));

        ReBrowserWorkspaceController.TabCloseResult middle =
                controller.closeTab(workspace, second, ReBrowserStore.HOME_URL);
        assertTrue(middle.valid);
        assertFalse(middle.resetOnly);
        assertEquals(third.id, middle.nextActiveTab.id);

        controller.closeTab(workspace, third, ReBrowserStore.HOME_URL);
        ReBrowserWorkspaceController.TabCloseResult last =
                controller.closeTab(workspace, first, "https://www.baidu.com/");
        assertTrue(last.valid);
        assertTrue(last.resetOnly);
        assertEquals(first.id, workspace.activeTabId);
        assertEquals("https://www.baidu.com/", first.url);
        assertEquals("新子标签页", first.title);
    }

    @Test
    public void shelvingRestoringAndDeletingSecondaryPreservesIntent() {
        ReBrowserStore.Workspace workspace = controller.activeWorkspace();
        assertTrue(controller.lockAsSecondary(workspace));
        assertTrue(controller.shelfSecondary(workspace));
        assertFalse(controller.containsActive(workspace));
        assertTrue(controller.containsShelved(workspace));
        assertFalse(controller.pendingProfileDeletions().contains(workspace.profileName));

        assertTrue(controller.restoreSecondary(workspace));
        assertTrue(controller.containsActive(workspace));
        assertFalse(controller.containsShelved(workspace));

        assertTrue(controller.shelfSecondary(workspace));
        assertTrue(controller.deleteShelvedSecondary(workspace));
        assertFalse(controller.containsShelved(workspace));
        assertTrue(controller.pendingProfileDeletions().contains(workspace.profileName));
    }
}
