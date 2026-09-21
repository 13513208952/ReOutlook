package io.github.reoutlook;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.webkit.ValueCallback;

import org.json.JSONObject;

import java.io.File;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** On-device regression coverage using device-protected preferences isolated from user state. */
public final class ReBrowserRegressionInstrumentation extends Instrumentation {
    private Context isolatedContext;
    private int assertions;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            Context deviceContext = getTargetContext().createDeviceProtectedStorageContext();
            isolatedContext = new ContextWrapper(deviceContext) {
                @Override
                public Context getApplicationContext() {
                    return this;
                }
            };
            clearIsolatedState();
            testWorkspaceLifecycleAndPersistence();
            testAdministratorAuthorizationBoundaries();
            testDownloadRateRedactionAndCancellation();
            result.putString("status", "passed");
            result.putInt("assertions", assertions);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("status", "failed");
            result.putString("error", error.toString());
            result.putInt("assertions", assertions);
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (isolatedContext != null) clearIsolatedState();
        }
    }

    private void testWorkspaceLifecycleAndPersistence() {
        ReBrowserStore store = new ReBrowserStore(isolatedContext);
        ReBrowserWorkspaceController controller = new ReBrowserWorkspaceController(store);
        controller.initialize(ReBrowserStore.HOME_URL);
        ReBrowserStore.Workspace workspace = controller.activeWorkspace();
        check(workspace != null, "temporary-workspace-missing");
        check(workspace.level == ReBrowserStore.Level.TEMPORARY, "initial-level");
        check(controller.pendingProfileDeletions().contains(workspace.profileName),
                "temporary-profile-not-marked");
        check(controller.lockAsSecondary(workspace), "lock-failed");
        check(!controller.pendingProfileDeletions().contains(workspace.profileName),
                "persistent-profile-marked");
        ReBrowserStore.Tab second = controller.addTab(workspace, "https://www.baidu.com/");
        check(second != null, "second-tab-missing");
        controller.saveAll();

        ReBrowserWorkspaceController restored = new ReBrowserWorkspaceController(
                new ReBrowserStore(isolatedContext));
        restored.initialize(ReBrowserStore.HOME_URL);
        ReBrowserStore.Workspace loaded = restored.activeWorkspace();
        check(loaded != null && loaded.id.equals(workspace.id), "workspace-not-restored");
        check(loaded.tabs.size() == 2, "tabs-not-restored");
        check(loaded.level == ReBrowserStore.Level.SECONDARY, "level-not-restored");
        check(restored.unlockToTemporary(loaded), "unlock-failed");
        check(restored.pendingProfileDeletions().contains(loaded.profileName),
                "unlocked-profile-not-marked");
        check(restored.removeTemporary(loaded), "temporary-remove-failed");
    }

    private void testAdministratorAuthorizationBoundaries() throws Exception {
        JSONObject state = request(ReBrowserAdminProtocol.OP_GET_STATE, "device_state_1234");
        String challenge = ReBrowserAdminAuthorizer.createChallenge(
                isolatedContext, ReBrowserAdminProtocol.encode(state.toString()));
        JSONObject authorized = ReBrowserAdminAuthorizer.authorizeWithOwnerCredential(
                isolatedContext, challenge);
        check("device-credential".equals(authorized.optString("_authentication")),
                "owner-authentication-missing");
        expectSecurity(() -> ReBrowserAdminAuthorizer.inspectPending(
                isolatedContext, challenge), "challenge-replay-accepted");

        JSONObject close = request(ReBrowserAdminProtocol.OP_CLOSE_WORKSPACE,
                "device_close_1234").put("workspaceId", id('a')).put("confirmDelete", true);
        String closeChallenge = ReBrowserAdminAuthorizer.createChallenge(
                isolatedContext, ReBrowserAdminProtocol.encode(close.toString()));
        JSONObject closeAuthorized = ReBrowserAdminAuthorizer.authorizeWithOwnerCredential(
                isolatedContext, closeChallenge);
        check(closeAuthorized.optInt("authorizationLevel") == 2, "level-two-mismatch");

        JSONObject repair = request(ReBrowserAdminProtocol.OP_REPAIR_STATE,
                "device_repair_1234");
        String repairChallenge = ReBrowserAdminAuthorizer.createChallenge(
                isolatedContext, ReBrowserAdminProtocol.encode(repair.toString()));
        expectSecurity(() -> ReBrowserAdminAuthorizer.authorizeWithOwnerCredential(
                isolatedContext, repairChallenge), "owner-authorized-level-three");

        expectSecurity(() -> ReBrowserAdminProtocol.prepareExternalRequest(
                request(ReBrowserAdminProtocol.OP_OPEN_URL, "device_url_12345")
                        .put("url", "javascript:alert(1)")), "script-url-accepted");
        expectSecurity(() -> ReBrowserAdminProtocol.prepareExternalRequest(
                request(ReBrowserAdminProtocol.OP_GET_STATE, "device_reserved_1")
                        .put("_keyId", "forged")), "reserved-field-accepted");
    }

    private void testDownloadRateRedactionAndCancellation() throws Exception {
        ReBrowserDownloads downloads = new ReBrowserDownloads(isolatedContext);
        long now = 2_000_000L;
        String site = "rebrowser_workspace_" + id('a') + "|https://example.com";
        check(downloads.recordAttempt(site, now) == 1, "rate-one");
        check(downloads.recordAttempt(site, now + 1) == 2, "rate-two");
        check(downloads.recordAttempt(site, now + 2) == 3, "rate-three");
        check(downloads.recordAttempt(site, now + 300_003L) == 1, "rate-expiry");

        String exact = "https://example.com/file.bin?token=device-secret";
        ReBrowserDownloads.Record persisted = downloads.create(
                id('a'), id('b'), "rebrowser_workspace_" + id('a'),
                "https://example.com/private?cookie=value", "https://example.com",
                exact, "agent", "attachment; filename=file.bin",
                "application/octet-stream", 10L, 1, false);
        downloads.save(List.of(persisted));
        String raw = isolatedContext.getSharedPreferences(
                        "rebrowser_downloads_v1", Context.MODE_PRIVATE)
                .getString("records", "");
        check(!raw.contains("device-secret"), "exact-url-persisted");
        check(!raw.contains("cookie=value"), "source-url-persisted");
        ReBrowserDownloads.Record loaded = downloads.load().get(0);
        check("https://example.com".equals(loaded.url), "origin-not-retained");
        check(!loaded.exactRequestAvailable, "exact-request-restored");

        byte[] payload = new byte[4 * 1024 * 1024];
        java.util.Arrays.fill(payload, (byte) 'x');
        ReBrowserDownloads.Record cancelling = downloads.create(
                id('a'), id('b'), "rebrowser_workspace_" + id('a'),
                "https://example.com/page", "https://example.com",
                "data:application/octet-stream;base64,"
                        + Base64.getEncoder().encodeToString(payload),
                "agent", "attachment; filename=cancel.bin",
                "application/octet-stream", payload.length, 1, false);
        cancelling.status = ReBrowserDownloads.STATUS_DOWNLOADING;
        CountDownLatch finished = new CountDownLatch(1);
        downloads.importDataAsync(cancelling, finished::countDown);
        downloads.cancel(cancelling);
        check(finished.await(20, TimeUnit.SECONDS), "cancel-timeout");
        check(ReBrowserDownloads.STATUS_CANCELLED.equals(cancelling.status),
                "cancel-lost-race");
        check(cancelling.localUri == null || cancelling.localUri.isBlank(),
                "cancelled-local-uri");
        check(!new File(isolatedContext.getCacheDir(),
                "rebrowser-download-" + cancelling.id).exists(), "cancelled-temp-file");

        ReBrowserStore.Workspace workspace = new ReBrowserStore.Workspace(
                id('a'), "test", ReBrowserStore.Level.TEMPORARY);
        ReBrowserStore.Tab tab = new ReBrowserStore.Tab(
                id('b'), "test", "https://example.com/");
        workspace.tabs.add(tab);
        workspace.activeTabId = tab.id;
        ReBrowserDownloadController controller = new ReBrowserDownloadController(
                isolatedContext, new NoOpPageBridge(), new NoOpDownloadListener());
        controller.accept(new ReBrowserWebController.DownloadRequest(
                workspace.id, tab.id, workspace.profileName,
                "https://example.com/form", "https://example.com",
                "https://example.com/private-export?token=secret", "POST", "agent",
                "attachment; filename=export.bin", "application/octet-stream", -1L),
                workspace);
        ReBrowserDownloads.Record rejected = controller.records().get(0);
        check(ReBrowserDownloads.STATUS_REJECTED.equals(rejected.status),
                "post-download-not-rejected");
        check("unsupported-request-method:POST".equals(rejected.error),
                "post-download-error-not-explicit");
        controller.accept(new ReBrowserWebController.DownloadRequest(
                workspace.id, tab.id, workspace.profileName,
                "https://example.com/page", "https://example.com",
                "https://example.com/service-worker-download", "", "agent",
                "attachment; filename=worker.bin", "application/octet-stream", -1L),
                workspace);
        ReBrowserDownloads.Record unknown = controller.records().get(0);
        check(ReBrowserDownloads.STATUS_REJECTED.equals(unknown.status),
                "unknown-request-context-not-rejected");
        check("unsupported-request-context".equals(unknown.error),
                "unknown-request-context-error-not-explicit");
    }

    private void clearIsolatedState() {
        for (String name : new String[]{
                "rebrowser_workspace_store_v1",
                "rebrowser_admin_protocol_v2",
                "rebrowser_admin_state_v1",
                "rebrowser_downloads_v1"
        }) {
            isolatedContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit().clear().commit();
        }
    }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private void expectSecurity(ThrowingRunnable runnable, String message) throws Exception {
        assertions++;
        try {
            runnable.run();
        } catch (SecurityException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static JSONObject request(String operation, String requestId) throws Exception {
        return new JSONObject()
                .put("protocolVersion", ReBrowserAdminProtocol.VERSION)
                .put("requestId", requestId)
                .put("operation", operation);
    }

    private static String id(char value) {
        return String.valueOf(value).repeat(32);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class NoOpDownloadListener
            implements ReBrowserDownloadController.Listener {
        @Override public void onDownloadRecordsChanged() {}
        @Override public void onDownloadConfirmationRequired(ReBrowserDownloads.Record record) {}
        @Override public void onDownloadMessage(String message) {}
    }

    private static final class NoOpPageBridge implements ReBrowserPageDownloadBridge {
        @Override
        public String cookieForHttpDownload(
                String tabId,
                String profileName,
                String sourceOrigin,
                String requestUrl
        ) {
            return "";
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
            return null;
        }

        @Override public void pollBlobMetadata(
                BlobHandle handle, ValueCallback<BlobMetadata> callback) {}
        @Override public void requestBlobChunk(
                BlobHandle handle, long offset, long end, ValueCallback<Boolean> callback) {}
        @Override public void pollBlobChunk(
                BlobHandle handle, ValueCallback<BlobChunk> callback) {}
        @Override public void endBlobTransfer(BlobHandle handle) {}
    }
}
