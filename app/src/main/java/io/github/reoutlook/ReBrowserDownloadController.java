package io.github.reoutlook;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Base64;

import androidx.webkit.ProfileStore;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Owns ReBrowser download policy, records, queues, and transfer state. */
@SuppressLint("RequiresFeature") // Profile-dependent commands remain behind ReBrowser's feature gate.
final class ReBrowserDownloadController {
    static final int MAX_PENDING_GLOBAL = 32;
    static final int MAX_PENDING_PER_SITE = 8;

    interface Listener {
        void onDownloadRecordsChanged();
        void onDownloadConfirmationRequired(ReBrowserDownloads.Record record);
        void onDownloadMessage(String message);
    }

    static final class RepairResult {
        final int refreshed;
        final int expired;
        final int removedDeleted;
        final int remaining;

        RepairResult(int refreshed, int expired, int removedDeleted, int remaining) {
            this.refreshed = refreshed;
            this.expired = expired;
            this.removedDeleted = removedDeleted;
            this.remaining = remaining;
        }
    }

    private final Context context;
    private final ReBrowserPageDownloadBridge pageBridge;
    private final Listener listener;
    private final ReBrowserDownloads store;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ReBrowserDownloads.Record> records = new ArrayList<>();
    private final List<ReBrowserDownloads.Record> recordsView =
            Collections.unmodifiableList(records);
    private final Set<String> hashingIds = new HashSet<>();
    private final Set<String> customIds = new HashSet<>();
    private BlobTransfer activeBlobTransfer;

    ReBrowserDownloadController(
            Context context,
            ReBrowserPageDownloadBridge pageBridge,
            Listener listener
    ) {
        this.context = context.getApplicationContext();
        this.pageBridge = pageBridge;
        this.listener = listener;
        store = new ReBrowserDownloads(this.context);
        store.cleanupInterruptedTemporaryFiles();
        records.addAll(store.load());
        refresh();
    }

    List<ReBrowserDownloads.Record> records() {
        return recordsView;
    }

    boolean downloadsEnabled() {
        return store.downloadsEnabled();
    }

    boolean hasActiveBlobTransfer() {
        return activeBlobTransfer != null;
    }

    int pendingCount() {
        return pendingCount(null);
    }

    ReBrowserDownloads.Record find(String id) {
        if (id == null) return null;
        for (ReBrowserDownloads.Record record : records) {
            if (record.id.equals(id)) return record;
        }
        return null;
    }

    void accept(
            ReBrowserWebController.DownloadRequest request,
            ReBrowserStore.Workspace activeWorkspace
    ) {
        ReBrowserStore.Tab sourceTab = null;
        if (activeWorkspace != null) {
            for (ReBrowserStore.Tab candidate : activeWorkspace.tabs) {
                if (candidate.id.equals(request.tabId)) {
                    sourceTab = candidate;
                    break;
                }
            }
        }
        if (activeWorkspace == null || sourceTab == null
                || !activeWorkspace.id.equals(request.workspaceId)
                || !activeWorkspace.profileName.equals(request.profileName)
                || !ReBrowserStore.isOwnedProfile(request.profileName)) {
            message("已拒绝来源不明确的下载");
            return;
        }
        if (request.sourceOrigin.isBlank()) {
            message("已拒绝没有顶层网站来源的下载");
            return;
        }
        String kind = ReBrowserDownloads.kind(request.url);
        if (ReBrowserDownloads.KIND_HTTP.equals(kind)) {
            String scheme = Uri.parse(request.url).getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                message("仅允许 HTTP(S)、Blob 或 Data 下载");
                return;
            }
        } else if (ReBrowserDownloads.KIND_BLOB.equals(kind)
                && !request.url.startsWith("blob:" + request.sourceOrigin + "/")) {
            message("Blob 下载来源与当前顶层网站不一致");
            return;
        }

        String siteKey = siteKey(request.profileName, request.sourceOrigin);
        int rateCount = store.recordAttempt(siteKey, System.currentTimeMillis());
        boolean highFrequency = rateCount >= 3;
        ReBrowserDownloads.Record record = store.create(
                request.workspaceId, request.tabId, request.profileName,
                request.sourceUrl, request.sourceOrigin, request.url,
                request.userAgent, request.contentDisposition,
                request.mimeType, request.contentLength, rateCount, highFrequency);
        if (!makeRecordRoom()) {
            message("下载记录与活动任务已达到上限");
            return;
        }
        records.add(0, record);
        if (ReBrowserDownloads.KIND_HTTP.equals(kind)
                && !"GET".equalsIgnoreCase(request.requestMethod)) {
            String reason = request.requestMethod == null || request.requestMethod.isBlank()
                    ? "unsupported-request-context"
                    : "unsupported-request-method:" + request.requestMethod;
            rejectWith(record, reason);
            saveAndNotify();
            message("无法确认该请求为普通 GET 下载，已拒绝");
            return;
        }
        if (!store.downloadsEnabled()) {
            rejectWith(record, "administrator-policy-disabled");
            saveAndNotify();
            message("管理员已暂停 ReBrowser 新下载");
            return;
        }
        int globalPending = pendingCount(null);
        int sitePending = pendingCount(siteKey);
        if (globalPending > MAX_PENDING_GLOBAL || sitePending > MAX_PENDING_PER_SITE) {
            rejectWith(record, "pending-queue-limit");
            saveAndNotify();
            message("待确认下载请求过多，已拒绝");
            return;
        }
        if (highFrequency) {
            message("高频下载已拦截，请在“下载内容”中确认");
            saveAndNotify();
            return;
        }
        store.save(records);
        listener.onDownloadConfirmationRequired(record);
    }

    void approve(ReBrowserDownloads.Record record) {
        if (!owns(record) || !ReBrowserDownloads.STATUS_PENDING.equals(record.status)) return;
        if (!store.downloadsEnabled()) {
            rejectWith(record, "administrator-policy-disabled");
            saveAndNotify();
            message("管理员已暂停 ReBrowser 新下载");
            return;
        }
        if (!record.exactRequestAvailable) {
            expireExactRequest(record);
            saveAndNotify();
            message("为避免持久化网址令牌，请回到原网页重新发起下载");
            return;
        }
        if (ReBrowserDownloads.KIND_DATA.equals(record.kind)) {
            startData(record);
        } else if (ReBrowserDownloads.KIND_BLOB.equals(record.kind)) {
            startBlob(record);
        } else {
            startHttp(record);
        }
    }

    void retry(ReBrowserDownloads.Record record) {
        if (!owns(record)) return;
        if (!store.downloadsEnabled()) {
            rejectWith(record, "administrator-policy-disabled");
            saveAndNotify();
            message("管理员已暂停 ReBrowser 新下载");
            return;
        }
        if (!record.exactRequestAvailable) {
            expireExactRequest(record);
            saveAndNotify();
            message("为避免持久化网址令牌，请在原网页重新发起下载");
            return;
        }
        int count = store.recordAttempt(siteKey(record.profileName, record.sourceOrigin),
                System.currentTimeMillis());
        record.rateCount = count;
        record.highFrequency = count >= 3;
        record.systemId = -1L;
        record.status = ReBrowserDownloads.STATUS_PENDING;
        record.error = "";
        record.updatedAt = System.currentTimeMillis();
        saveAndNotify();
        message("重试请求已进入待确认列表");
    }

    void reject(ReBrowserDownloads.Record record) {
        if (!owns(record) || !ReBrowserDownloads.STATUS_PENDING.equals(record.status)) return;
        rejectWith(record, "");
        saveAndNotify();
    }

    void cancel(ReBrowserDownloads.Record record) {
        if (!owns(record)) return;
        if (activeBlobTransfer != null && activeBlobTransfer.record == record) {
            failBlob(activeBlobTransfer, "download-cancelled", false);
        }
        store.cancel(record);
        saveAndNotify();
    }

    void delete(ReBrowserDownloads.Record record) {
        if (!owns(record)) return;
        if (activeBlobTransfer != null && activeBlobTransfer.record == record) {
            failBlob(activeBlobTransfer, "download-deleted", false);
        }
        store.delete(record);
        records.remove(record);
        saveAndNotify();
    }

    int clear() {
        int count = records.size();
        for (ReBrowserDownloads.Record record : new ArrayList<>(records)) {
            if (activeBlobTransfer != null && activeBlobTransfer.record == record) {
                failBlob(activeBlobTransfer, "administrator-cleared-downloads", false);
            }
            store.delete(record);
        }
        records.clear();
        saveAndNotify();
        return count;
    }

    Intent externalOpenIntent(ReBrowserDownloads.Record record) {
        return owns(record) ? store.externalOpenIntent(record) : null;
    }

    int setDownloadsEnabled(boolean enabled) {
        store.setDownloadsEnabled(enabled);
        int rejected = 0;
        if (!enabled) {
            for (ReBrowserDownloads.Record record : records) {
                if (!ReBrowserDownloads.STATUS_PENDING.equals(record.status)) continue;
                rejectWith(record, "administrator-policy-disabled");
                rejected++;
            }
        }
        saveAndNotify();
        return rejected;
    }

    RepairResult repair() {
        int refreshed = 0;
        int expired = 0;
        int removedDeleted = 0;
        for (ReBrowserDownloads.Record record : new ArrayList<>(records)) {
            if (ReBrowserDownloads.STATUS_DOWNLOADING.equals(record.status)
                    && store.refresh(record)) refreshed++;
            if (ReBrowserDownloads.STATUS_PENDING.equals(record.status)
                    && (!ReBrowserStore.isOwnedProfile(record.profileName)
                    || ProfileStore.getInstance().getProfile(record.profileName) == null)) {
                failRecord(record, "profile-unavailable");
                record.status = ReBrowserDownloads.STATUS_EXPIRED;
                expired++;
            }
            if (ReBrowserDownloads.STATUS_DELETED.equals(record.status)) {
                records.remove(record);
                removedDeleted++;
            }
        }
        trimHistory();
        saveAndNotify();
        return new RepairResult(refreshed, expired, removedDeleted, records.size());
    }

    void refresh() {
        boolean changed = false;
        for (ReBrowserDownloads.Record record : records) {
            if (ReBrowserDownloads.STATUS_PENDING.equals(record.status)
                    && !record.exactRequestAvailable) {
                expireExactRequest(record);
                changed = true;
            }
            if (ReBrowserDownloads.STATUS_DOWNLOADING.equals(record.status)) {
                if (record.systemId < 0 && !customIds.contains(record.id)) {
                    failRecord(record, "custom-download-interrupted");
                    changed = true;
                } else {
                    changed |= store.refresh(record);
                }
            }
            if (ReBrowserDownloads.STATUS_COMPLETED.equals(record.status)
                    && !record.hashAttempted
                    && (record.sha256 == null || record.sha256.isBlank())
                    && hashingIds.add(record.id)) {
                store.computeSha256Async(record, () -> mainHandler.post(() -> {
                    hashingIds.remove(record.id);
                    store.save(records);
                    listener.onDownloadRecordsChanged();
                }));
            }
        }
        if (changed) store.save(records);
    }

    void invalidatePageTransfers(String reason) {
        if (activeBlobTransfer != null) failBlob(activeBlobTransfer, reason, true);
    }

    private void startData(ReBrowserDownloads.Record record) {
        record.status = ReBrowserDownloads.STATUS_DOWNLOADING;
        record.updatedAt = System.currentTimeMillis();
        customIds.add(record.id);
        store.save(records);
        store.importDataAsync(record, () -> mainHandler.post(() -> {
            customIds.remove(record.id);
            store.save(records);
            listener.onDownloadRecordsChanged();
            message(ReBrowserDownloads.STATUS_COMPLETED.equals(record.status)
                    ? "Data 文件已保存；不会自动打开" : "Data 下载失败");
        }));
    }

    private void startHttp(ReBrowserDownloads.Record record) {
        try {
            if (!ReBrowserStore.isOwnedProfile(record.profileName)) {
                throw new SecurityException("non-ReBrowser-profile");
            }
            String cookie = pageBridge.cookieForHttpDownload(
                    record.tabId, record.profileName, record.sourceOrigin, record.url);
            String referer = record.sourceOrigin.equals(safeOrigin(record.url))
                    ? record.sourceUrl : record.sourceOrigin + "/";
            store.enqueueHttp(record, cookie, referer);
            saveAndNotify();
            message("已开始下载；不会自动打开文件");
        } catch (Exception error) {
            failRecord(record, error.getClass().getSimpleName() + ":" + error.getMessage());
            saveAndNotify();
            message("无法开始下载");
        }
    }

    private void startBlob(ReBrowserDownloads.Record record) {
        if (activeBlobTransfer != null) {
            failRecord(record, "another-blob-transfer-active");
            saveAndNotify();
            message("一次只能提取一个 Blob 文件");
            return;
        }
        BlobTransfer transfer = null;
        try {
            transfer = new BlobTransfer(record, store.blobTemporaryFile(record.id));
            activeBlobTransfer = transfer;
            customIds.add(record.id);
            record.status = ReBrowserDownloads.STATUS_DOWNLOADING;
            record.updatedAt = System.currentTimeMillis();
            store.save(records);
            transfer.phaseDeadline = SystemClock.elapsedRealtime() + 30_000L;
            BlobTransfer started = transfer;
            transfer.handle = pageBridge.beginBlobTransfer(
                    record.tabId, record.profileName, record.sourceOrigin,
                    record.url, record.id, result -> {
                        if (started != activeBlobTransfer) return;
                        if (!Boolean.TRUE.equals(result)) {
                            failBlob(started, "blob-initialization-failed", true);
                        } else {
                            pollBlobMetadata(started);
                        }
                    });
        } catch (Exception error) {
            if (transfer != null || activeBlobTransfer != null) {
                failBlob(transfer == null ? activeBlobTransfer : transfer,
                        "blob-initialization-failed:" + error.getMessage(), true);
            } else {
                failRecord(record, "blob-initialization-failed:" + error.getMessage());
                saveAndNotify();
            }
        }
    }

    private void pollBlobMetadata(BlobTransfer transfer) {
        if (transfer != activeBlobTransfer) return;
        try {
            pageBridge.pollBlobMetadata(transfer.handle,
                    metadata -> handleBlobMetadata(transfer, metadata));
        } catch (Exception error) {
            failBlob(transfer, "blob-metadata-invalid:" + error.getMessage(), true);
        }
    }

    private void handleBlobMetadata(
            BlobTransfer transfer,
            ReBrowserPageDownloadBridge.BlobMetadata metadata
    ) {
        if (transfer != activeBlobTransfer) return;
        try {
            if ("loading".equals(metadata.status)) {
                if (SystemClock.elapsedRealtime() >= transfer.phaseDeadline) {
                    throw new IllegalStateException("blob-fetch-timeout");
                }
                mainHandler.postDelayed(() -> pollBlobMetadata(transfer), 50L);
                return;
            }
            if (!"ready".equals(metadata.status)) {
                throw new IllegalStateException(metadata.error.isBlank()
                        ? "blob-fetch-failed" : metadata.error);
            }
            if (metadata.size < 0 || metadata.size > ReBrowserDownloads.MAX_BLOB_BYTES) {
                throw new IllegalArgumentException("blob-size-limit");
            }
            if (metadata.size > new StatFs(transfer.temporary.getParent()).getAvailableBytes()) {
                throw new IllegalStateException("insufficient-storage");
            }
            transfer.expectedSize = metadata.size;
            transfer.record.totalSize = metadata.size;
            if (!metadata.type.isBlank()) transfer.record.mimeType = metadata.type;
            transfer.record.dangerous = ReBrowserDownloads.isDangerous(
                    transfer.record.fileName, transfer.record.mimeType);
            requestNextBlobChunk(transfer);
        } catch (Exception error) {
            failBlob(transfer, "blob-metadata-invalid:" + error.getMessage(), true);
        }
    }

    private void requestNextBlobChunk(BlobTransfer transfer) {
        if (transfer != activeBlobTransfer) return;
        if (transfer.offset >= transfer.expectedSize) {
            finishBlob(transfer);
            return;
        }
        long end = Math.min(transfer.expectedSize, transfer.offset + 128 * 1024L);
        transfer.phaseDeadline = SystemClock.elapsedRealtime() + 30_000L;
        try {
            pageBridge.requestBlobChunk(transfer.handle, transfer.offset, end, result -> {
                if (transfer != activeBlobTransfer) return;
                if (!Boolean.TRUE.equals(result)) {
                    failBlob(transfer, "blob-chunk-start-failed", true);
                } else {
                    pollBlobChunk(transfer);
                }
            });
        } catch (Exception error) {
            failBlob(transfer, "blob-source-page-changed:" + error.getMessage(), true);
        }
    }

    private void pollBlobChunk(BlobTransfer transfer) {
        if (transfer != activeBlobTransfer) return;
        try {
            pageBridge.pollBlobChunk(transfer.handle,
                    chunk -> handleBlobChunk(transfer, chunk));
        } catch (Exception error) {
            failBlob(transfer, "blob-chunk-failed:" + error.getMessage(), true);
        }
    }

    private void handleBlobChunk(
            BlobTransfer transfer,
            ReBrowserPageDownloadBridge.BlobChunk result
    ) {
        if (transfer != activeBlobTransfer) return;
        try {
            if ("loading".equals(result.status)) {
                if (SystemClock.elapsedRealtime() >= transfer.phaseDeadline) {
                    throw new IllegalStateException("blob-chunk-timeout");
                }
                mainHandler.postDelayed(() -> pollBlobChunk(transfer), 50L);
                return;
            }
            if (!"ready".equals(result.status)) {
                throw new IllegalStateException(result.error.isBlank()
                        ? "blob-chunk-failed" : result.error);
            }
            if (result.data.isBlank()) throw new IllegalStateException("empty-blob-chunk");
            byte[] chunk = Base64.decode(result.data, Base64.DEFAULT);
            long remaining = transfer.expectedSize - transfer.offset;
            if (chunk.length <= 0 || chunk.length > remaining || chunk.length > 128 * 1024) {
                throw new IllegalStateException("invalid-blob-chunk-size");
            }
            transfer.output.write(chunk);
            transfer.digest.update(chunk);
            transfer.offset += chunk.length;
            transfer.record.downloadedBytes = transfer.offset;
            transfer.record.updatedAt = System.currentTimeMillis();
            if (transfer.offset % (1024 * 1024L) < chunk.length) store.save(records);
            requestNextBlobChunk(transfer);
        } catch (Exception error) {
            failBlob(transfer, "blob-chunk-failed:" + error.getMessage(), true);
        }
    }

    private void finishBlob(BlobTransfer transfer) {
        if (transfer != activeBlobTransfer) return;
        try {
            transfer.output.close();
            transfer.output = null;
            if (transfer.temporary.length() != transfer.expectedSize) {
                throw new IllegalStateException("blob-size-mismatch");
            }
            String digest = hexDigest(transfer.digest.digest());
            cleanupBlobPageState(transfer);
            activeBlobTransfer = null;
            store.publishTemporaryFileAsync(transfer.record, transfer.temporary, digest,
                    () -> mainHandler.post(() -> {
                        customIds.remove(transfer.record.id);
                        store.save(records);
                        listener.onDownloadRecordsChanged();
                        message(ReBrowserDownloads.STATUS_COMPLETED.equals(transfer.record.status)
                                ? "Blob 文件已保存；不会自动打开" : "Blob 文件保存失败");
                    }));
        } catch (Exception error) {
            failBlob(transfer, "blob-finalization-failed:" + error.getMessage(), true);
        }
    }

    private void failBlob(BlobTransfer transfer, String reason, boolean notify) {
        if (transfer == null) return;
        try {
            if (transfer.output != null) transfer.output.close();
        } catch (Exception ignored) {
            // Failure path continues with deletion.
        }
        transfer.temporary.delete();
        cleanupBlobPageState(transfer);
        customIds.remove(transfer.record.id);
        failRecord(transfer.record, reason == null ? "blob-transfer-failed" : reason);
        if (activeBlobTransfer == transfer) activeBlobTransfer = null;
        store.save(records);
        if (notify) listener.onDownloadRecordsChanged();
    }

    private void cleanupBlobPageState(BlobTransfer transfer) {
        if (transfer.handle != null) pageBridge.endBlobTransfer(transfer.handle);
    }

    private int pendingCount(String key) {
        int count = 0;
        for (ReBrowserDownloads.Record record : records) {
            if (!ReBrowserDownloads.STATUS_PENDING.equals(record.status)) continue;
            if (key == null || key.equals(siteKey(record.profileName, record.sourceOrigin))) count++;
        }
        return count;
    }

    private boolean makeRecordRoom() {
        while (records.size() >= ReBrowserDownloads.MAX_RECORDS) {
            int removable = findOldestRemovable();
            if (removable < 0) return false;
            records.remove(removable);
        }
        return true;
    }

    private void trimHistory() {
        while (records.size() > ReBrowserDownloads.MAX_RECORDS) {
            int removable = findOldestRemovable();
            if (removable < 0) return;
            records.remove(removable);
        }
    }

    private int findOldestRemovable() {
        for (int index = records.size() - 1; index >= 0; index--) {
            ReBrowserDownloads.Record record = records.get(index);
            if (!ReBrowserDownloads.STATUS_DOWNLOADING.equals(record.status)
                    && !ReBrowserDownloads.STATUS_PENDING.equals(record.status)) return index;
        }
        return -1;
    }

    private boolean owns(ReBrowserDownloads.Record record) {
        return record != null && records.contains(record);
    }

    private void rejectWith(ReBrowserDownloads.Record record, String reason) {
        record.status = ReBrowserDownloads.STATUS_REJECTED;
        record.error = reason == null ? "" : reason;
        record.updatedAt = System.currentTimeMillis();
    }

    private void expireExactRequest(ReBrowserDownloads.Record record) {
        record.status = ReBrowserDownloads.STATUS_EXPIRED;
        record.error = "exact-request-not-retained-after-restart";
        record.updatedAt = System.currentTimeMillis();
    }

    private void failRecord(ReBrowserDownloads.Record record, String reason) {
        record.status = ReBrowserDownloads.STATUS_FAILED;
        String value = reason == null ? "download-failed" : reason;
        record.error = value.substring(0, Math.min(300, value.length()));
        record.updatedAt = System.currentTimeMillis();
    }

    private void saveAndNotify() {
        store.save(records);
        listener.onDownloadRecordsChanged();
    }

    private void message(String value) {
        listener.onDownloadMessage(value);
    }

    private static String siteKey(String profileName, String origin) {
        return profileName + "|" + origin;
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

    private static String hexDigest(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte item : value) output.append(String.format(Locale.ROOT, "%02x", item));
        return output.toString();
    }

    private static final class BlobTransfer {
        final ReBrowserDownloads.Record record;
        final File temporary;
        ReBrowserPageDownloadBridge.BlobHandle handle;
        FileOutputStream output;
        MessageDigest digest;
        long expectedSize;
        long offset;
        long phaseDeadline;

        BlobTransfer(ReBrowserDownloads.Record record, File temporary) throws Exception {
            this.record = record;
            this.temporary = temporary;
            output = new FileOutputStream(temporary);
            digest = MessageDigest.getInstance("SHA-256");
        }
    }
}
