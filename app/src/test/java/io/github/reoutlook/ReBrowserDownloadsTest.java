package io.github.reoutlook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.webkit.ValueCallback;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ReBrowserDownloadsTest {
    private Context context;
    private ReBrowserDownloads downloads;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("rebrowser_downloads_v1", Context.MODE_PRIVATE)
                .edit().clear().commit();
        downloads = new ReBrowserDownloads(context);
    }

    @Test
    public void fiveMinuteRateWindowCountsFailuresAndDropsExpiredOrFutureEntries() {
        long now = 1_000_000L;
        String site = "rebrowser_workspace_" + id('a') + "|https://example.com";
        assertEquals(1, downloads.recordAttempt(site, now));
        assertEquals(2, downloads.recordAttempt(site, now + 1));
        assertEquals(3, downloads.recordAttempt(site, now + 2));
        assertEquals(1, downloads.recordAttempt(site, now + 5 * 60_000L + 2));

        // A clock rollback must not retain timestamps that are in the future.
        assertEquals(1, downloads.recordAttempt(site, now));
        assertEquals(Integer.MAX_VALUE, downloads.recordAttempt("", now));
        assertEquals(Integer.MAX_VALUE, downloads.recordAttempt("x".repeat(601), now));
    }

    @Test
    public void persistedRecordRetainsOnlyOriginAndNeverTheExactRequest() throws Exception {
        String secretUrl = "https://example.com:8443/file.bin?token=secret#fragment";
        ReBrowserDownloads.Record record = downloads.create(
                id('a'), id('b'), "rebrowser_workspace_" + id('a'),
                "https://example.com/page?session=private", "https://example.com",
                secretUrl, "agent", "attachment; filename=file.bin",
                "application/octet-stream", 123L, 1, false);
        record.cookieAttached = true;
        downloads.save(List.of(record));

        String raw = context.getSharedPreferences(
                        "rebrowser_downloads_v1", Context.MODE_PRIVATE)
                .getString("records", "");
        assertFalse(raw.contains("token=secret"));
        assertFalse(raw.contains("session=private"));
        assertFalse(raw.contains("Cookie"));

        List<ReBrowserDownloads.Record> restored = downloads.load();
        assertEquals(1, restored.size());
        ReBrowserDownloads.Record loaded = restored.get(0);
        assertEquals("https://example.com:8443", loaded.url);
        assertFalse(loaded.exactRequestAvailable);
        assertTrue(loaded.cookieAttached); // Boolean audit fact only; no cookie value exists.

        JSONObject serialized = record.toJson();
        assertEquals("https://example.com:8443", serialized.getString("requestLocation"));
        assertFalse(serialized.toString().contains("secret"));
    }

    @Test
    public void riskyKindsExtensionsAndMimeTypesAreClassifiedConservatively() {
        assertEquals(ReBrowserDownloads.KIND_HTTP,
                ReBrowserDownloads.kind("https://example.com/file"));
        assertEquals(ReBrowserDownloads.KIND_BLOB,
                ReBrowserDownloads.kind("blob:https://example.com/id"));
        assertEquals(ReBrowserDownloads.KIND_DATA,
                ReBrowserDownloads.kind("data:text/plain,hello"));
        assertTrue(ReBrowserDownloads.isDangerous("update.apk", "application/octet-stream"));
        assertTrue(ReBrowserDownloads.isDangerous("note.txt", "text/html"));
        assertTrue(ReBrowserDownloads.isDangerous("archive.zip", "application/zip"));
        assertFalse(ReBrowserDownloads.isDangerous("report.pdf", "application/pdf"));
        assertEquals("1.0 KB", ReBrowserDownloads.formatBytes(1024));
    }

    @Test
    public void cancellationWinsAgainstAsynchronousDataPublication() throws Exception {
        byte[] payload = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(payload, (byte) 'x');
        String dataUrl = "data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(payload);
        ReBrowserDownloads.Record record = downloads.create(
                id('a'), id('b'), "rebrowser_workspace_" + id('a'),
                "https://example.com/page", "https://example.com", dataUrl,
                "agent", "attachment; filename=cancel.bin",
                "application/octet-stream", payload.length, 1, false);
        record.status = ReBrowserDownloads.STATUS_DOWNLOADING;
        CountDownLatch completed = new CountDownLatch(1);

        downloads.importDataAsync(record, completed::countDown);
        downloads.cancel(record);

        assertTrue(completed.await(15, TimeUnit.SECONDS));
        assertEquals(ReBrowserDownloads.STATUS_CANCELLED, record.status);
        assertTrue(record.localUri == null || record.localUri.isBlank());
        File temporary = new File(context.getCacheDir(),
                "rebrowser-download-" + record.id);
        assertFalse(temporary.exists());
    }

    @Test
    public void pendingQueueCapsApplyToOrdinaryAndHighFrequencyRequests() {
        ReBrowserStore.Workspace workspace = workspace();
        ReBrowserDownloadController controller = new ReBrowserDownloadController(
                context, new NoOpPageBridge(), new NoOpDownloadListener());

        for (int site = 0; site < 17; site++) {
            String origin = "https://site" + site + ".example.com";
            controller.accept(request(workspace, origin, site * 2), workspace);
            controller.accept(request(workspace, origin, site * 2 + 1), workspace);
        }
        long pending = controller.records().stream()
                .filter(record -> ReBrowserDownloads.STATUS_PENDING.equals(record.status))
                .count();
        long rejected = controller.records().stream()
                .filter(record -> ReBrowserDownloads.STATUS_REJECTED.equals(record.status)
                        && "pending-queue-limit".equals(record.error))
                .count();
        assertEquals(ReBrowserDownloadController.MAX_PENDING_GLOBAL, pending);
        assertEquals(2L, rejected);

        context.getSharedPreferences("rebrowser_downloads_v1", Context.MODE_PRIVATE)
                .edit().clear().commit();
        ReBrowserDownloadController perSite = new ReBrowserDownloadController(
                context, new NoOpPageBridge(), new NoOpDownloadListener());
        for (int index = 0; index < 9; index++) {
            perSite.accept(request(workspace, "https://single.example.com", index), workspace);
        }
        assertEquals(ReBrowserDownloadController.MAX_PENDING_PER_SITE,
                perSite.pendingCount());
        assertEquals(ReBrowserDownloads.STATUS_REJECTED,
                perSite.records().get(0).status);
        assertEquals("pending-queue-limit", perSite.records().get(0).error);
    }

    @Test
    public void observedPostDownloadIsRejectedBeforeSystemDownloader() {
        ReBrowserStore.Workspace workspace = workspace();
        ReBrowserDownloadController controller = new ReBrowserDownloadController(
                context, new NoOpPageBridge(), new NoOpDownloadListener());
        String origin = "https://example.com";
        ReBrowserWebController.DownloadRequest request =
                new ReBrowserWebController.DownloadRequest(
                        workspace.id, workspace.activeTabId, workspace.profileName,
                        origin + "/form", origin, origin + "/private-export?token=secret",
                        "POST", "agent", "attachment; filename=export.bin",
                        "application/octet-stream", -1L);

        controller.accept(request, workspace);

        assertEquals(1, controller.records().size());
        ReBrowserDownloads.Record record = controller.records().get(0);
        assertEquals(ReBrowserDownloads.STATUS_REJECTED, record.status);
        assertEquals("unsupported-request-method:POST", record.error);
        assertEquals(-1L, record.systemId);

        ReBrowserDownloadController unknownContext = new ReBrowserDownloadController(
                context, new NoOpPageBridge(), new NoOpDownloadListener());
        unknownContext.accept(new ReBrowserWebController.DownloadRequest(
                workspace.id, workspace.activeTabId, workspace.profileName,
                origin + "/page", origin, origin + "/service-worker-download", "",
                "agent", "attachment; filename=worker.bin",
                "application/octet-stream", -1L), workspace);
        assertEquals(ReBrowserDownloads.STATUS_REJECTED,
                unknownContext.records().get(0).status);
        assertEquals("unsupported-request-context", unknownContext.records().get(0).error);
    }

    @Test
    public void interruptedTemporaryCleanupIsStrictlyNameBounded() throws Exception {
        File data = new File(context.getCacheDir(), "rebrowser-download-" + id('c'));
        File blobDirectory = new File(context.getCacheDir(), "rebrowser-downloads");
        assertTrue(blobDirectory.mkdirs() || blobDirectory.isDirectory());
        File blob = new File(blobDirectory, id('d') + ".part");
        File unrelatedData = new File(context.getCacheDir(), "rebrowser-download-not-an-id");
        File unrelatedBlob = new File(blobDirectory, "notes.part");
        for (File file : new File[]{data, blob, unrelatedData, unrelatedBlob}) {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(1);
            }
        }

        assertEquals(2, downloads.cleanupInterruptedTemporaryFiles());
        assertFalse(data.exists());
        assertFalse(blob.exists());
        assertTrue(unrelatedData.exists());
        assertTrue(unrelatedBlob.exists());
        unrelatedData.delete();
        unrelatedBlob.delete();
    }

    @Test
    public void malformedPersistedIdsAreDiscarded() throws Exception {
        JSONObject malformed = new JSONObject().put("id", "../private-file");
        context.getSharedPreferences("rebrowser_downloads_v1", Context.MODE_PRIVATE)
                .edit().putString("records", "[" + malformed + "]").commit();
        assertNotNull(downloads.load());
        assertTrue(downloads.load().isEmpty());
    }

    private static ReBrowserStore.Workspace workspace() {
        ReBrowserStore.Workspace workspace = new ReBrowserStore.Workspace(
                id('a'), "test", ReBrowserStore.Level.TEMPORARY);
        ReBrowserStore.Tab tab = new ReBrowserStore.Tab(
                id('b'), "test", "https://cn.bing.com/");
        workspace.tabs.add(tab);
        workspace.activeTabId = tab.id;
        return workspace;
    }

    private static ReBrowserWebController.DownloadRequest request(
            ReBrowserStore.Workspace workspace,
            String origin,
            int index
    ) {
        return new ReBrowserWebController.DownloadRequest(
                workspace.id, workspace.activeTabId, workspace.profileName,
                origin + "/page", origin, "data:text/plain,request-" + index,
                "GET", "agent", "attachment; filename=request.txt", "text/plain", -1L);
    }

    private static String id(char value) {
        return String.valueOf(value).repeat(32);
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
