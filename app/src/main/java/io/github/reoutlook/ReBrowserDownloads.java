package io.github.reoutlook;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.URLUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** ReBrowser-only download metadata and Android DownloadManager integration. */
final class ReBrowserDownloads {
    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_DOWNLOADING = "DOWNLOADING";
    static final String STATUS_COMPLETED = "COMPLETED";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_CANCELLED = "CANCELLED";
    static final String STATUS_REJECTED = "REJECTED";
    static final String STATUS_EXPIRED = "EXPIRED";
    static final String STATUS_DELETED = "DELETED";
    static final String KIND_HTTP = "HTTP";
    static final String KIND_BLOB = "BLOB";
    static final String KIND_DATA = "DATA";
    static final int MAX_RECORDS = 500;
    static final long MAX_BLOB_BYTES = 1024L * 1024L * 1024L;
    static final long MAX_DATA_BYTES = 32L * 1024L * 1024L;

    private static final String PREFERENCES = "rebrowser_downloads_v1";
    private static final String RECORDS = "records";
    private static final String ATTEMPTS = "attempts";
    private static final String DOWNLOADS_ENABLED = "downloads_enabled";
    private static final long RATE_WINDOW_MS = 5 * 60 * 1_000L;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-f0-9]{32}");
    private static final String CUSTOM_TEMPORARY_PREFIX = "rebrowser-download-";
    private static final String BLOB_TEMPORARY_DIRECTORY = "rebrowser-downloads";
    private static final Pattern DANGEROUS_EXTENSION = Pattern.compile(
            "(?i).+\\.(apk|apks|xapk|aab|dex|jar|class|so|sh|bash|py|js|mjs|html?|xhtml|svg|wasm|exe|msi|bat|cmd|com|scr|ps1|lnk|url|desktop|reg|dmg|pkg|deb|rpm|docm|xlsm|pptm|p12|pfx|cer|crt|pem|der|key|jks|keystore|mobileconfig|zip|7z|rar|tar|gz)$");

    static final class Record {
        final String id;
        String kind;
        String workspaceId;
        String tabId;
        String profileName;
        String sourceUrl;
        String sourceOrigin;
        String url;
        String userAgent;
        String contentDisposition;
        String mimeType;
        String fileName;
        volatile String status;
        String localUri;
        String sha256;
        String error;
        long declaredSize;
        long downloadedBytes;
        long totalSize;
        long systemId;
        long createdAt;
        long updatedAt;
        int rateCount;
        boolean highFrequency;
        boolean dangerous;
        boolean cookieAttached;
        boolean exactRequestAvailable;
        boolean hashAttempted;
        String riskReasons;

        Record(String id) {
            this.id = id;
            systemId = -1L;
            kind = KIND_HTTP;
            workspaceId = "";
            tabId = "";
            profileName = "";
            sourceUrl = "";
            sourceOrigin = "";
            url = "";
            userAgent = "";
            contentDisposition = "";
            mimeType = "application/octet-stream";
            fileName = "download.bin";
            status = STATUS_FAILED;
            localUri = "";
            sha256 = "";
            error = "";
            riskReasons = "";
        }

        JSONObject toJson() throws Exception {
            JSONObject value = new JSONObject();
            value.put("id", id);
            value.put("kind", bounded(kind, 16));
            value.put("workspaceId", bounded(workspaceId, 32));
            value.put("tabId", bounded(tabId, 32));
            value.put("profileName", bounded(profileName, 80));
            value.put("sourceOrigin", bounded(sourceOrigin, 400));
            value.put("requestLocation", bounded(persistentRequestLocation(url), 8192));
            value.put("userAgent", bounded(userAgent, 1000));
            value.put("contentDisposition", bounded(contentDisposition, 1000));
            value.put("mimeType", bounded(mimeType, 200));
            value.put("fileName", bounded(fileName, 200));
            value.put("status", bounded(status, 24));
            value.put("localUri", bounded(localUri, 2000));
            value.put("sha256", bounded(sha256, 64));
            value.put("error", bounded(error, 300));
            value.put("declaredSize", declaredSize);
            value.put("downloadedBytes", downloadedBytes);
            value.put("totalSize", totalSize);
            value.put("systemId", systemId);
            value.put("createdAt", createdAt);
            value.put("updatedAt", updatedAt);
            value.put("rateCount", rateCount);
            value.put("highFrequency", highFrequency);
            value.put("dangerous", dangerous);
            value.put("cookieAttached", cookieAttached);
            value.put("hashAttempted", hashAttempted);
            value.put("riskReasons", bounded(riskReasons, 500));
            return value;
        }

        static Record fromJson(JSONObject value) {
            String id = value.optString("id");
            if (!ID_PATTERN.matcher(id).matches()) return null;
            Record record = new Record(id);
            record.kind = value.optString("kind", KIND_HTTP);
            record.workspaceId = value.optString("workspaceId");
            record.tabId = value.optString("tabId");
            record.profileName = value.optString("profileName");
            record.sourceOrigin = value.optString("sourceOrigin");
            record.sourceUrl = record.sourceOrigin.isBlank() ? "" : record.sourceOrigin + "/";
            record.url = value.optString("requestLocation");
            record.exactRequestAvailable = false;
            record.userAgent = value.optString("userAgent");
            record.contentDisposition = value.optString("contentDisposition");
            record.mimeType = value.optString("mimeType");
            record.fileName = value.optString("fileName", "download.bin");
            record.status = value.optString("status", STATUS_FAILED);
            record.localUri = value.optString("localUri");
            record.sha256 = value.optString("sha256");
            record.error = value.optString("error");
            record.declaredSize = value.optLong("declaredSize", -1L);
            record.downloadedBytes = value.optLong("downloadedBytes", 0L);
            record.totalSize = value.optLong("totalSize", record.declaredSize);
            record.systemId = value.optLong("systemId", -1L);
            record.createdAt = value.optLong("createdAt", System.currentTimeMillis());
            record.updatedAt = value.optLong("updatedAt", record.createdAt);
            record.rateCount = value.optInt("rateCount", 1);
            record.highFrequency = value.optBoolean("highFrequency", false);
            record.dangerous = value.optBoolean("dangerous", isDangerous(
                    record.fileName, record.mimeType));
            record.cookieAttached = value.optBoolean("cookieAttached", false);
            record.hashAttempted = value.optBoolean("hashAttempted",
                    record.sha256 != null && !record.sha256.isBlank());
            record.riskReasons = value.optString("riskReasons");
            return record;
        }
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final DownloadManager manager;
    private final ExecutorService hashExecutor = Executors.newSingleThreadExecutor();

    ReBrowserDownloads(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        manager = (DownloadManager) this.context.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    List<Record> load() {
        List<Record> records = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(preferences.getString(RECORDS, "[]"));
            for (int index = 0; index < values.length() && records.size() < MAX_RECORDS; index++) {
                JSONObject value = values.optJSONObject(index);
                Record record = value == null ? null : Record.fromJson(value);
                if (record != null) records.add(record);
            }
        } catch (Exception ignored) {
            // Corrupt metadata never grants access to a file or WebView Profile.
        }
        return records;
    }

    void save(List<Record> records) {
        JSONArray values = new JSONArray();
        for (int index = 0; index < records.size() && index < MAX_RECORDS; index++) {
            try {
                values.put(records.get(index).toJson());
            } catch (Exception ignored) {
                // Every field is a bounded primitive.
            }
        }
        preferences.edit().putString(RECORDS, values.toString()).apply();
    }

    boolean downloadsEnabled() {
        return preferences.getBoolean(DOWNLOADS_ENABLED, true);
    }

    void setDownloadsEnabled(boolean enabled) {
        preferences.edit().putBoolean(DOWNLOADS_ENABLED, enabled).apply();
    }

    int recordAttempt(String siteKey, long now) {
        if (siteKey == null || siteKey.isBlank() || siteKey.length() > 600) return Integer.MAX_VALUE;
        try {
            JSONObject root = new JSONObject(preferences.getString(ATTEMPTS, "{}"));
            JSONArray old = root.optJSONArray(siteKey);
            JSONArray recent = new JSONArray();
            if (old != null) {
                for (int index = 0; index < old.length(); index++) {
                    long timestamp = old.optLong(index, 0L);
                    if (timestamp > now - RATE_WINDOW_MS && timestamp <= now) recent.put(timestamp);
                }
            }
            recent.put(now);
            root.put(siteKey, recent);
            // Bound persistent keys as well as each five-minute timestamp list.
            if (root.length() > 256) {
                JSONArray names = root.names();
                if (names != null) {
                    for (int index = 0; index < names.length() && root.length() > 256; index++) {
                        String key = names.optString(index);
                        if (!siteKey.equals(key)) root.remove(key);
                    }
                }
            }
            preferences.edit().putString(ATTEMPTS, root.toString()).apply();
            return recent.length();
        } catch (Exception ignored) {
            return Integer.MAX_VALUE;
        }
    }

    int cleanupInterruptedTemporaryFiles() {
        int deleted = 0;
        File[] dataFiles = context.getCacheDir().listFiles();
        if (dataFiles != null) {
            for (File file : dataFiles) {
                String name = file.getName();
                if (!file.isFile() || !name.startsWith(CUSTOM_TEMPORARY_PREFIX)) continue;
                String id = name.substring(CUSTOM_TEMPORARY_PREFIX.length());
                if (ID_PATTERN.matcher(id).matches() && file.delete()) deleted++;
            }
        }
        File blobDirectory = new File(context.getCacheDir(), BLOB_TEMPORARY_DIRECTORY);
        File[] blobFiles = blobDirectory.listFiles();
        if (blobFiles != null) {
            for (File file : blobFiles) {
                String name = file.getName();
                if (!file.isFile() || !name.endsWith(".part")) continue;
                String id = name.substring(0, name.length() - ".part".length());
                if (ID_PATTERN.matcher(id).matches() && file.delete()) deleted++;
            }
        }
        return deleted;
    }

    File blobTemporaryFile(String id) {
        if (!ID_PATTERN.matcher(id == null ? "" : id).matches()) {
            throw new IllegalArgumentException("invalid-download-id");
        }
        File directory = new File(context.getCacheDir(), BLOB_TEMPORARY_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("temporary-directory-unavailable");
        }
        return new File(directory, id + ".part");
    }

    Record create(
            String workspaceId,
            String tabId,
            String profileName,
            String sourceUrl,
            String sourceOrigin,
            String url,
            String userAgent,
            String contentDisposition,
            String mimeType,
            long contentLength,
            int rateCount,
            boolean highFrequency
    ) {
        Record record = new Record(newId());
        record.kind = kind(url);
        record.workspaceId = workspaceId;
        record.tabId = tabId;
        record.profileName = profileName;
        record.sourceUrl = sourceUrl;
        record.sourceOrigin = sourceOrigin;
        record.url = url;
        record.exactRequestAvailable = true;
        record.userAgent = userAgent;
        record.contentDisposition = contentDisposition;
        record.mimeType = normalizeMime(mimeType);
        record.fileName = safeFileName(url, contentDisposition, record.mimeType);
        record.status = STATUS_PENDING;
        record.declaredSize = contentLength;
        record.totalSize = contentLength;
        record.rateCount = rateCount;
        record.highFrequency = highFrequency;
        record.dangerous = isDangerous(record.fileName, record.mimeType);
        record.riskReasons = record.dangerous ? "risky-extension-or-mime" : "";
        record.createdAt = System.currentTimeMillis();
        record.updatedAt = record.createdAt;
        return record;
    }

    void enqueueHttp(Record record, String cookie, String referer) {
        if (manager == null || !KIND_HTTP.equals(record.kind)) {
            throw new IllegalStateException("System download service is unavailable");
        }
        Uri uri = Uri.parse(record.url);
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new SecurityException("Only HTTP(S) can use the system downloader");
        }
        DownloadManager.Request request = new DownloadManager.Request(uri);
        request.setTitle(record.fileName);
        request.setDescription(record.sourceOrigin);
        if (!record.mimeType.isBlank()) request.setMimeType(record.mimeType);
        if (record.userAgent != null && !record.userAgent.isBlank()) {
            request.addRequestHeader("User-Agent", bounded(record.userAgent, 1000));
        }
        if (cookie != null && !cookie.isBlank()) {
            request.addRequestHeader("Cookie", cookie);
            record.cookieAttached = true;
        }
        if (referer != null && !referer.isBlank()) {
            request.addRequestHeader("Referer", bounded(referer, 8192));
        }
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, record.fileName);
        record.systemId = manager.enqueue(request);
        record.status = STATUS_DOWNLOADING;
        record.error = "";
        record.updatedAt = System.currentTimeMillis();
    }

    boolean refresh(Record record) {
        if (manager == null || record.systemId < 0) return false;
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(record.systemId);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            boolean changed = downloaded != record.downloadedBytes || total != record.totalSize;
            record.downloadedBytes = Math.max(0L, downloaded);
            if (total >= 0) record.totalSize = total;
            String nextStatus = record.status;
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                nextStatus = STATUS_COMPLETED;
                Uri local = manager.getUriForDownloadedFile(record.systemId);
                if (local != null) record.localUri = local.toString();
                String type = manager.getMimeTypeForDownloadedFile(record.systemId);
                if (type != null && !type.isBlank()) record.mimeType = type;
                record.dangerous = isDangerous(record.fileName, record.mimeType);
            } else if (status == DownloadManager.STATUS_FAILED) {
                nextStatus = STATUS_FAILED;
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_REASON));
                record.error = "DownloadManager reason " + reason;
            } else if (status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_PAUSED
                    || status == DownloadManager.STATUS_RUNNING) {
                nextStatus = STATUS_DOWNLOADING;
            }
            if (!nextStatus.equals(record.status)) {
                record.status = nextStatus;
                changed = true;
            }
            if (changed) record.updatedAt = System.currentTimeMillis();
            return changed;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    void cancel(Record record) {
        long systemId;
        String localUri;
        synchronized (record) {
            record.status = STATUS_CANCELLED;
            record.updatedAt = System.currentTimeMillis();
            systemId = record.systemId;
            localUri = record.localUri;
            if (systemId < 0 && localUri != null && !localUri.isBlank()) {
                record.localUri = "";
            }
        }
        if (manager != null && systemId >= 0) manager.remove(systemId);
        if (systemId < 0 && localUri != null && !localUri.isBlank()) {
            try {
                context.getContentResolver().delete(Uri.parse(localUri), null, null);
            } catch (RuntimeException ignored) {
                // A concurrent publisher also removes its pending MediaStore entry.
            }
        }
    }

    void delete(Record record) {
        long systemId;
        String localUri;
        synchronized (record) {
            record.status = STATUS_DELETED;
            record.updatedAt = System.currentTimeMillis();
            systemId = record.systemId;
            localUri = record.localUri;
            record.localUri = "";
        }
        if (manager != null && systemId >= 0) manager.remove(systemId);
        if (localUri != null && !localUri.isBlank()) {
            try {
                context.getContentResolver().delete(Uri.parse(localUri), null, null);
            } catch (RuntimeException ignored) {
                // DownloadManager.remove usually already removed the file.
            }
        }
    }

    Uri contentUri(Record record) {
        if (manager != null && record.systemId >= 0) {
            Uri uri = manager.getUriForDownloadedFile(record.systemId);
            if (uri != null) return uri;
        }
        if (record.localUri == null || record.localUri.isBlank()) return null;
        return Uri.parse(record.localUri);
    }

    Intent externalOpenIntent(Record record) {
        Uri uri = contentUri(record);
        if (uri == null) return null;
        String type = record.mimeType == null || record.mimeType.isBlank()
                ? "application/octet-stream" : record.mimeType;
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    void importDataAsync(Record record, Runnable completion) {
        hashExecutor.execute(() -> {
            File temporary = null;
            try {
                String value = record.url;
                int comma = value.indexOf(',');
                if (comma < 5) throw new IllegalArgumentException("invalid-data-url");
                String metadata = value.substring(5, comma);
                String payload = value.substring(comma + 1);
                byte[] decoded = metadata.toLowerCase(Locale.ROOT).endsWith(";base64")
                        ? Base64.decode(payload, Base64.DEFAULT)
                        : decodePercentPayload(payload);
                if (decoded.length > MAX_DATA_BYTES) {
                    throw new IllegalArgumentException("data-url-too-large");
                }
                temporary = new File(context.getCacheDir(),
                        CUSTOM_TEMPORARY_PREFIX + record.id);
                requireCustomDownloadActive(record);
                try (OutputStream output = new FileOutputStream(temporary)) {
                    output.write(decoded);
                }
                requireCustomDownloadActive(record);
                publishTemporaryFile(record, temporary, sha256(decoded));
            } catch (java.util.concurrent.CancellationException ignored) {
                if (temporary != null) temporary.delete();
            } catch (Exception error) {
                if (temporary != null) temporary.delete();
                if (STATUS_DOWNLOADING.equals(record.status)) {
                    record.status = STATUS_FAILED;
                    record.error = error.getClass().getSimpleName() + ":" + error.getMessage();
                    record.updatedAt = System.currentTimeMillis();
                }
            }
            completion.run();
        });
    }

    void publishTemporaryFileAsync(
            Record record,
            File temporary,
            String digest,
            Runnable completion
    ) {
        hashExecutor.execute(() -> {
            try {
                publishTemporaryFile(record, temporary, digest);
            } catch (java.util.concurrent.CancellationException ignored) {
                temporary.delete();
            } catch (Exception error) {
                temporary.delete();
                if (STATUS_DOWNLOADING.equals(record.status)) {
                    record.status = STATUS_FAILED;
                    record.error = error.getClass().getSimpleName() + ":" + error.getMessage();
                    record.updatedAt = System.currentTimeMillis();
                }
            }
            completion.run();
        });
    }

    private void publishTemporaryFile(Record record, File temporary, String digest)
            throws Exception {
        requireCustomDownloadActive(record);
        long size = temporary.length();
        if (size > MAX_BLOB_BYTES) throw new IllegalArgumentException("download-too-large");
        inspectFileRisk(record, temporary);
        requireCustomDownloadActive(record);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, record.fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, record.mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("media-store-insert-failed");
        try (InputStream input = new FileInputStream(temporary);
                OutputStream output = resolver.openOutputStream(uri, "w")) {
            if (output == null) throw new IllegalStateException("media-store-open-failed");
            copy(input, output, record);
            requireCustomDownloadActive(record);
        } catch (Exception error) {
            resolver.delete(uri, null, null);
            throw error;
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        try {
            requireCustomDownloadActive(record);
        } catch (java.util.concurrent.CancellationException error) {
            resolver.delete(uri, null, null);
            throw error;
        }
        record.systemId = -1L;
        synchronized (record) {
            if (!STATUS_DOWNLOADING.equals(record.status)) {
                if (record.systemId >= 0 && manager != null) manager.remove(record.systemId);
                else context.getContentResolver().delete(uri, null, null);
                temporary.delete();
                throw new java.util.concurrent.CancellationException("custom-download-cancelled");
            }
            temporary.delete();
            record.localUri = uri.toString();
            record.downloadedBytes = size;
            record.totalSize = size;
            record.sha256 = digest;
            record.hashAttempted = true;
            record.status = STATUS_COMPLETED;
            record.error = "";
            record.updatedAt = System.currentTimeMillis();
        }
    }

    void computeSha256Async(Record record, Runnable completion) {
        if (record == null || !STATUS_COMPLETED.equals(record.status)
                || record.sha256 != null && !record.sha256.isBlank()) return;
        record.hashAttempted = true;
        Uri uri = contentUri(record);
        if (uri == null) {
            hashExecutor.execute(completion);
            return;
        }
        hashExecutor.execute(() -> {
            String digest = "";
            try (ParcelFileDescriptor descriptor = context.getContentResolver()
                    .openFileDescriptor(uri, "r")) {
                if (descriptor != null) {
                    MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
                    byte[] first = new byte[512];
                    int firstCount = 0;
                    try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            if (count > 0) {
                                if (firstCount < first.length) {
                                    int copy = Math.min(count, first.length - firstCount);
                                    System.arraycopy(buffer, 0, first, firstCount, copy);
                                    firstCount += copy;
                                }
                                messageDigest.update(buffer, 0, count);
                            }
                        }
                    }
                    inspectHeaderRisk(record, first, firstCount);
                    digest = hex(messageDigest.digest());
                }
            } catch (Exception ignored) {
                // Hashing failure does not change the system download result.
            }
            if (!digest.isBlank()) {
                record.sha256 = digest;
                record.updatedAt = System.currentTimeMillis();
            }
            completion.run();
        });
    }

    private static String persistentRequestLocation(String value) {
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                String host = uri.getHost();
                if (host == null || host.isBlank()) return "";
                return scheme.toLowerCase(Locale.ROOT) + "://"
                        + host.toLowerCase(Locale.ROOT)
                        + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            }
            return scheme == null ? "" : scheme.toLowerCase(Locale.ROOT) + ":";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    static String kind(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("blob:")) return KIND_BLOB;
        if (lower.startsWith("data:")) return KIND_DATA;
        return KIND_HTTP;
    }

    private static void inspectFileRisk(Record record, File file) {
        byte[] header = new byte[512];
        int count = 0;
        try (InputStream input = new FileInputStream(file)) {
            count = input.read(header);
        } catch (Exception ignored) {
            // Extension and MIME classification still applies.
        }
        inspectHeaderRisk(record, header, Math.max(0, count));
    }

    private static void inspectHeaderRisk(Record record, byte[] bytes, int count) {
        List<String> reasons = new ArrayList<>();
        if (isDangerous(record.fileName, record.mimeType)) {
            reasons.add("risky-extension-or-mime");
        }
        if (starts(bytes, count, new byte[]{'M', 'Z'})) reasons.add("pe-executable-magic");
        if (starts(bytes, count, new byte[]{0x7f, 'E', 'L', 'F'})) reasons.add("elf-magic");
        if (starts(bytes, count, new byte[]{'d', 'e', 'x', '\n'})) reasons.add("dex-magic");
        if (starts(bytes, count, new byte[]{'P', 'K', 3, 4})) reasons.add("zip-container-magic");
        if (starts(bytes, count, new byte[]{'#', '!'})) reasons.add("script-shebang");
        String text = new String(bytes, 0, Math.min(count, 256),
                java.nio.charset.StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("<!doctype html") || text.startsWith("<html")
                || text.startsWith("<script") || text.startsWith("<svg")) {
            reasons.add("active-document-content");
        }
        String expected = expectedMimeForExtension(record.fileName);
        if (!expected.isBlank() && record.mimeType != null
                && !"application/octet-stream".equals(record.mimeType)
                && !record.mimeType.startsWith(expected)) {
            reasons.add("extension-mime-mismatch");
        }
        record.dangerous = !reasons.isEmpty();
        record.riskReasons = String.join(",", reasons);
    }

    private static boolean starts(byte[] source, int count, byte[] prefix) {
        if (count < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (source[index] != prefix[index]) return false;
        }
        return true;
    }

    private static String expectedMimeForExtension(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".xml")) return "application/xml";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".zip")) return "application/zip";
        if (name.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "";
    }

    static boolean isDangerous(String fileName, String mimeType) {
        String name = fileName == null ? "" : fileName;
        String type = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        return DANGEROUS_EXTENSION.matcher(name).matches()
                || type.contains("android.package-archive")
                || type.contains("javascript")
                || type.contains("html")
                || type.contains("x-sh")
                || type.contains("executable")
                || type.contains("java-archive")
                || type.contains("x-dex")
                || type.contains("x-msdownload");
    }

    static String statusLabel(Record record) {
        switch (record.status) {
            case STATUS_PENDING: return record.highFrequency ? "高频待确认" : "待确认";
            case STATUS_DOWNLOADING: return "下载中";
            case STATUS_COMPLETED: return "已完成";
            case STATUS_FAILED: return "失败";
            case STATUS_CANCELLED: return "已取消";
            case STATUS_REJECTED: return "已拒绝";
            case STATUS_EXPIRED: return "已过期";
            case STATUS_DELETED: return "已删除";
            default: return record.status;
        }
    }

    static String formatBytes(long bytes) {
        if (bytes < 0) return "大小未知";
        if (bytes < 1024) return bytes + " B";
        double value = bytes / 1024d;
        if (value < 1024) return String.format(Locale.ROOT, "%.1f KB", value);
        value /= 1024d;
        if (value < 1024) return String.format(Locale.ROOT, "%.1f MB", value);
        return String.format(Locale.ROOT, "%.2f GB", value / 1024d);
    }

    private static byte[] decodePercentPayload(String payload) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        for (int index = 0; index < payload.length();) {
            char value = payload.charAt(index);
            if (value == '%' && index + 2 < payload.length()) {
                int high = Character.digit(payload.charAt(index + 1), 16);
                int low = Character.digit(payload.charAt(index + 2), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException("invalid-data-escape");
                output.write((high << 4) | low);
                index += 3;
            } else {
                int codePoint = payload.codePointAt(index);
                byte[] bytes = new String(Character.toChars(codePoint)).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8);
                output.write(bytes);
                index += Character.charCount(codePoint);
            }
            if (output.size() > MAX_DATA_BYTES) {
                throw new IllegalArgumentException("data-url-too-large");
            }
        }
        return output.toByteArray();
    }

    private static void requireCustomDownloadActive(Record record) {
        if (!STATUS_DOWNLOADING.equals(record.status)) {
            throw new java.util.concurrent.CancellationException("custom-download-cancelled");
        }
    }

    private static void copy(InputStream input, OutputStream output, Record record)
            throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            requireCustomDownloadActive(record);
            if (count > 0) output.write(buffer, 0, count);
        }
        requireCustomDownloadActive(record);
    }

    private static String safeFileName(String url, String disposition, String mimeType) {
        String guessed;
        try {
            String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
            if ((lower.startsWith("data:") || lower.startsWith("blob:"))
                    && (disposition == null
                    || !disposition.toLowerCase(Locale.ROOT).contains("filename"))) {
                guessed = "download" + extensionForMime(mimeType);
            } else {
                guessed = URLUtil.guessFileName(url, disposition, mimeType);
            }
        } catch (RuntimeException ignored) {
            guessed = "download.bin";
        }
        guessed = guessed == null ? "download.bin" : guessed;
        guessed = guessed.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        while (guessed.startsWith(".")) guessed = guessed.substring(1);
        if (guessed.isBlank()) guessed = "download.bin";
        if (guessed.length() > 180) guessed = guessed.substring(0, 180);
        return guessed;
    }

    private static String extensionForMime(String mimeType) {
        String type = normalizeMime(mimeType);
        if ("text/plain".equals(type)) return ".txt";
        if ("application/pdf".equals(type)) return ".pdf";
        if ("application/json".equals(type)) return ".json";
        if ("image/png".equals(type)) return ".png";
        if ("image/jpeg".equals(type)) return ".jpg";
        if ("image/gif".equals(type)) return ".gif";
        if ("image/webp".equals(type)) return ".webp";
        if ("application/zip".equals(type)) return ".zip";
        return ".bin";
    }

    private static String normalizeMime(String value) {
        if (value == null) return "application/octet-stream";
        String clean = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return clean.isBlank() || clean.length() > 200 ? "application/octet-stream" : clean;
    }

    private static String sha256(byte[] value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format(Locale.ROOT, "%02x", item));
        return result.toString();
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        return value.substring(0, Math.min(value.length(), max));
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
