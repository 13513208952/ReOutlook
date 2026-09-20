package io.github.reoutlook;

import android.webkit.ValueCallback;

/** Fixed, download-only page capabilities; it cannot execute caller-supplied scripts. */
interface ReBrowserPageDownloadBridge {
    final class BlobHandle {
        final String tabId;
        final String profileName;
        final String origin;
        final String key;
        final long pageGeneration;
        final long navigationGeneration;

        BlobHandle(
                String tabId,
                String profileName,
                String origin,
                String key,
                long pageGeneration,
                long navigationGeneration
        ) {
            this.tabId = tabId;
            this.profileName = profileName;
            this.origin = origin;
            this.key = key;
            this.pageGeneration = pageGeneration;
            this.navigationGeneration = navigationGeneration;
        }
    }

    final class BlobMetadata {
        final String status;
        final String error;
        final long size;
        final String type;

        BlobMetadata(String status, String error, long size, String type) {
            this.status = status;
            this.error = error;
            this.size = size;
            this.type = type;
        }
    }

    final class BlobChunk {
        final String status;
        final String error;
        final String data;

        BlobChunk(String status, String error, String data) {
            this.status = status;
            this.error = error;
            this.data = data;
        }
    }

    String cookieForHttpDownload(
            String tabId,
            String profileName,
            String sourceOrigin,
            String requestUrl);

    BlobHandle beginBlobTransfer(
            String tabId,
            String profileName,
            String origin,
            String blobUrl,
            String transferId,
            ValueCallback<Boolean> callback);

    void pollBlobMetadata(BlobHandle handle, ValueCallback<BlobMetadata> callback);

    void requestBlobChunk(
            BlobHandle handle,
            long offset,
            long end,
            ValueCallback<Boolean> callback);

    void pollBlobChunk(BlobHandle handle, ValueCallback<BlobChunk> callback);

    void endBlobTransfer(BlobHandle handle);
}
