package io.github.reoutlook;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Versioned request validation, structured results, and bounded administrator audit metadata. */
final class ReBrowserAdminProtocol {
    static final int VERSION = 2;
    static final String OP_OPEN_URL = "OPEN_URL";
    static final String OP_NEW_WORKSPACE = "NEW_WORKSPACE";
    static final String OP_NEW_CHILD_TAB = "NEW_CHILD_TAB";
    static final String OP_SHOW_WORKSPACES = "SHOW_WORKSPACES";
    static final String OP_SHOW_CHILD_TABS = "SHOW_CHILD_TABS";
    static final String OP_OPEN_SETTINGS = "OPEN_SETTINGS";
    static final String OP_SWITCH_TO_OUTLOOK = "SWITCH_TO_OUTLOOK";
    static final String OP_GET_STATE = "GET_STATE";
    static final String OP_GET_CAPABILITIES = "GET_CAPABILITIES";
    static final String OP_GET_DIAGNOSTICS = "GET_DIAGNOSTICS";
    static final String OP_GET_AUDIT = "GET_AUDIT";
    static final String OP_VALIDATE_STATE = "VALIDATE_STATE";
    static final String OP_REPAIR_STATE = "REPAIR_STATE";
    static final String OP_SET_HOME = "SET_HOME";
    static final String OP_SET_PREFERENCE = "SET_PREFERENCE";
    static final String OP_ACTIVATE_WORKSPACE = "ACTIVATE_WORKSPACE";
    static final String OP_ACTIVATE_TAB = "ACTIVATE_TAB";
    static final String OP_RELOAD = "RELOAD";
    static final String OP_STOP = "STOP";
    static final String OP_GO_BACK = "GO_BACK";
    static final String OP_GO_FORWARD = "GO_FORWARD";
    static final String OP_GO_HOME = "GO_HOME";
    static final String OP_ASSERT_LOCATION = "ASSERT_LOCATION";
    static final String OP_LOCK_SECONDARY = "LOCK_SECONDARY";
    static final String OP_UNLOCK_TEMPORARY = "UNLOCK_TEMPORARY";
    static final String OP_PROMOTE_PRIMARY = "PROMOTE_PRIMARY";
    static final String OP_DEMOTE_SECONDARY = "DEMOTE_SECONDARY";
    static final String OP_SHELVE_WORKSPACE = "SHELVE_WORKSPACE";
    static final String OP_RESTORE_WORKSPACE = "RESTORE_WORKSPACE";
    static final String OP_CLOSE_WORKSPACE = "CLOSE_WORKSPACE";
    static final String OP_CLOSE_TAB = "CLOSE_TAB";
    static final String OP_DELETE_SHELVED = "DELETE_SHELVED";
    static final String OP_SHOW_DOWNLOADS = "SHOW_DOWNLOADS";
    static final String OP_GET_DOWNLOAD_POLICY = "GET_DOWNLOAD_POLICY";
    static final String OP_SET_DOWNLOAD_POLICY = "SET_DOWNLOAD_POLICY";
    static final String OP_GET_DOWNLOADS = "GET_DOWNLOADS";
    static final String OP_APPROVE_DOWNLOAD = "APPROVE_DOWNLOAD";
    static final String OP_REJECT_DOWNLOAD = "REJECT_DOWNLOAD";
    static final String OP_CANCEL_DOWNLOAD = "CANCEL_DOWNLOAD";
    static final String OP_RETRY_DOWNLOAD = "RETRY_DOWNLOAD";
    static final String OP_DELETE_DOWNLOAD = "DELETE_DOWNLOAD";
    static final String OP_CLEAR_DOWNLOADS = "CLEAR_DOWNLOADS";
    static final String OP_REPAIR_DOWNLOADS = "REPAIR_DOWNLOADS";
    static final String OP_GET_SITE_PERMISSIONS = "GET_SITE_PERMISSIONS";
    static final String OP_DISABLE_SITE_PERMISSION = "DISABLE_SITE_PERMISSION";
    static final String OP_CLEAR_SITE_PERMISSION_GRANTS = "CLEAR_SITE_PERMISSION_GRANTS";

    private static final Set<String> OPERATIONS = new HashSet<>(Arrays.asList(
            OP_OPEN_URL, OP_NEW_WORKSPACE, OP_NEW_CHILD_TAB, OP_SHOW_WORKSPACES,
            OP_SHOW_CHILD_TABS, OP_OPEN_SETTINGS, OP_SWITCH_TO_OUTLOOK, OP_GET_STATE,
            OP_GET_CAPABILITIES, OP_GET_DIAGNOSTICS, OP_GET_AUDIT, OP_VALIDATE_STATE,
            OP_REPAIR_STATE, OP_SET_HOME, OP_SET_PREFERENCE, OP_ACTIVATE_WORKSPACE,
            OP_ACTIVATE_TAB, OP_RELOAD, OP_STOP, OP_GO_BACK, OP_GO_FORWARD, OP_GO_HOME,
            OP_ASSERT_LOCATION,
            OP_LOCK_SECONDARY, OP_UNLOCK_TEMPORARY, OP_PROMOTE_PRIMARY,
            OP_DEMOTE_SECONDARY, OP_SHELVE_WORKSPACE, OP_RESTORE_WORKSPACE,
            OP_CLOSE_WORKSPACE, OP_CLOSE_TAB, OP_DELETE_SHELVED,
            OP_SHOW_DOWNLOADS, OP_GET_DOWNLOAD_POLICY, OP_SET_DOWNLOAD_POLICY,
            OP_GET_DOWNLOADS,
            OP_APPROVE_DOWNLOAD, OP_REJECT_DOWNLOAD, OP_CANCEL_DOWNLOAD,
            OP_RETRY_DOWNLOAD, OP_DELETE_DOWNLOAD, OP_CLEAR_DOWNLOADS,
            OP_REPAIR_DOWNLOADS, OP_GET_SITE_PERMISSIONS,
            OP_DISABLE_SITE_PERMISSION, OP_CLEAR_SITE_PERMISSION_GRANTS));
    private static final Set<String> LEVEL_TWO = new HashSet<>(Arrays.asList(
            OP_LOCK_SECONDARY, OP_UNLOCK_TEMPORARY, OP_PROMOTE_PRIMARY,
            OP_DEMOTE_SECONDARY, OP_SHELVE_WORKSPACE, OP_RESTORE_WORKSPACE,
            OP_CLOSE_WORKSPACE, OP_CLOSE_TAB, OP_DELETE_SHELVED,
            OP_APPROVE_DOWNLOAD, OP_REJECT_DOWNLOAD, OP_CANCEL_DOWNLOAD,
            OP_RETRY_DOWNLOAD, OP_DELETE_DOWNLOAD, OP_SET_DOWNLOAD_POLICY,
            OP_DISABLE_SITE_PERMISSION));
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,80}");
    private static final Pattern OBJECT_ID = Pattern.compile("[a-f0-9]{32}");
    private static final String PREFERENCES = "rebrowser_admin_protocol_v2";
    private static final String RESULT_PREFIX = "result_";
    private static final String LAST_REQUEST_ID = "last_request_id";
    private static final String RESULT_IDS = "result_ids";
    private static final String AUDIT = "audit";
    private static final int MAX_RESULTS = 8;
    private static final int MAX_AUDIT = 100;

    private ReBrowserAdminProtocol() {}

    static JSONObject prepareExternalRequest(JSONObject request) throws Exception {
        java.util.Iterator<String> names = request.keys();
        while (names.hasNext()) {
            if (names.next().startsWith("_")) {
                throw new SecurityException("Reserved request field");
            }
        }
        return prepareRequest(request);
    }

    static JSONObject prepareRequest(JSONObject request) throws Exception {
        int requestedVersion = request.optInt("protocolVersion", VERSION);
        if (requestedVersion != VERSION) throw new SecurityException("Unsupported protocol version");
        request.put("protocolVersion", VERSION);
        String requestId = request.optString("requestId", "");
        if (requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
            request.put("requestId", requestId);
        }
        if (!REQUEST_ID.matcher(requestId).matches()) {
            throw new SecurityException("Invalid request ID");
        }
        String operation = request.optString("operation", "");
        if (!OPERATIONS.contains(operation)) throw new SecurityException("Unsupported operation");
        validateOptionalId(request, "workspaceId");
        validateOptionalId(request, "tabId");
        validateOptionalId(request, "downloadId");
        if (requiresDownload(operation) && !request.has("downloadId")) {
            throw new SecurityException("downloadId is required");
        }
        if (requiresWorkspace(operation) && !request.has("workspaceId")) {
            throw new SecurityException("workspaceId is required");
        }
        if (requiresTab(operation) && !request.has("tabId")) {
            throw new SecurityException("tabId is required");
        }
        if (OP_OPEN_URL.equals(operation) || OP_SET_HOME.equals(operation)
                || OP_ASSERT_LOCATION.equals(operation)) {
            requireHttpUrl(request.optString("url", ""));
        }
        if ((OP_CLOSE_WORKSPACE.equals(operation) || OP_CLOSE_TAB.equals(operation)
                || OP_DELETE_SHELVED.equals(operation)
                || OP_DELETE_DOWNLOAD.equals(operation)
                || OP_CLEAR_DOWNLOADS.equals(operation)
                || OP_CLEAR_SITE_PERMISSION_GRANTS.equals(operation))
                && !request.optBoolean("confirmDelete", false)) {
            throw new SecurityException("Explicit confirmDelete=true is required");
        }
        if (request.has("waitForLoad") && !(request.opt("waitForLoad") instanceof Boolean)) {
            throw new SecurityException("waitForLoad must be boolean");
        }
        int timeoutSeconds = request.optInt("timeoutSeconds", 30);
        if (timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new SecurityException("timeoutSeconds must be between 1 and 60");
        }
        if (OP_SET_PREFERENCE.equals(operation)) validatePreference(request);
        if (OP_DISABLE_SITE_PERMISSION.equals(operation)
                || OP_CLEAR_SITE_PERMISSION_GRANTS.equals(operation)) {
            validateSitePermission(request, OP_DISABLE_SITE_PERMISSION.equals(operation));
        }
        if (OP_SET_DOWNLOAD_POLICY.equals(operation)
                && !(request.opt("downloadsEnabled") instanceof Boolean)) {
            throw new SecurityException("downloadsEnabled must be boolean");
        }
        request.put("authorizationLevel", authorizationLevel(operation));
        return request;
    }

    static int authorizationLevel(String operation) {
        if (OP_REPAIR_STATE.equals(operation) || OP_REPAIR_DOWNLOADS.equals(operation)
                || OP_CLEAR_DOWNLOADS.equals(operation)
                || OP_CLEAR_SITE_PERMISSION_GRANTS.equals(operation)) return 3;
        return LEVEL_TWO.contains(operation) ? 2 : 1;
    }

    static boolean ownerCredentialAllowed(JSONObject request) {
        return request.optInt("authorizationLevel", 1) < 3;
    }

    static JSONArray supportedOperations() {
        JSONArray result = new JSONArray();
        java.util.List<String> sorted = new java.util.ArrayList<>(OPERATIONS);
        java.util.Collections.sort(sorted);
        for (String operation : sorted) result.put(operation);
        return result;
    }

    static void recordStatus(
            Context context,
            JSONObject request,
            String status,
            String authentication,
            String keyId,
            JSONObject details,
            String error
    ) {
        try {
            JSONObject result = new JSONObject();
            result.put("protocolVersion", VERSION);
            result.put("requestId", request.optString("requestId"));
            result.put("operation", request.optString("operation"));
            result.put("authorizationLevel", request.optInt("authorizationLevel", 1));
            result.put("status", status);
            result.put("updatedAt", System.currentTimeMillis());
            if (authentication != null && !authentication.isBlank()) {
                result.put("authentication", authentication);
            }
            if (keyId != null && !keyId.isBlank()) result.put("keyId", keyId);
            if (details != null) result.put("details", details);
            if (error != null && !error.isBlank()) result.put("error", bounded(error, 300));
            saveResult(context, result);
            if ("completed".equals(status) || "failed".equals(status)
                    || "cancelled".equals(status)) appendAudit(context, result, request);
        } catch (Exception ignored) {
            // Result reporting must never replay or alter an already authorized operation.
        }
    }

    static String result(Context context, String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) return "";
        return preferences(context).getString(RESULT_PREFIX + requestId, "");
    }

    static String lastResult(Context context) {
        String requestId = preferences(context).getString(LAST_REQUEST_ID, "");
        return result(context, requestId);
    }

    static JSONArray audit(Context context) {
        try {
            return new JSONArray(preferences(context).getString(AUDIT, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    static String encode(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static void saveResult(Context context, JSONObject result) throws Exception {
        SharedPreferences preferences = preferences(context);
        String requestId = result.getString("requestId");
        JSONArray ids;
        try {
            ids = new JSONArray(preferences.getString(RESULT_IDS, "[]"));
        } catch (Exception ignored) {
            ids = new JSONArray();
        }
        JSONArray retained = new JSONArray();
        retained.put(requestId);
        for (int index = 0; index < ids.length() && retained.length() < MAX_RESULTS; index++) {
            String previous = ids.optString(index);
            if (!requestId.equals(previous) && REQUEST_ID.matcher(previous).matches()) {
                retained.put(previous);
            }
        }
        SharedPreferences.Editor editor = preferences.edit()
                .putString(RESULT_PREFIX + requestId, result.toString())
                .putString(LAST_REQUEST_ID, requestId)
                .putString(RESULT_IDS, retained.toString());
        for (int index = MAX_RESULTS - 1; index < ids.length(); index++) {
            String expired = ids.optString(index);
            if (!expired.isBlank()) editor.remove(RESULT_PREFIX + expired);
        }
        editor.apply();
    }

    private static void appendAudit(Context context, JSONObject result, JSONObject request)
            throws Exception {
        JSONArray previous = audit(context);
        JSONArray retained = new JSONArray();
        JSONObject entry = new JSONObject();
        entry.put("timestamp", result.optLong("updatedAt"));
        entry.put("requestId", result.optString("requestId"));
        entry.put("operation", result.optString("operation"));
        entry.put("authorizationLevel", result.optInt("authorizationLevel"));
        entry.put("status", result.optString("status"));
        entry.put("authentication", result.optString("authentication"));
        entry.put("keyId", result.optString("keyId"));
        if (request.has("workspaceId")) entry.put("workspaceId", request.optString("workspaceId"));
        if (request.has("tabId")) entry.put("tabId", request.optString("tabId"));
        if (request.has("downloadId")) {
            entry.put("downloadId", request.optString("downloadId"));
        }
        retained.put(entry);
        for (int index = 0; index < previous.length() && retained.length() < MAX_AUDIT; index++) {
            JSONObject value = previous.optJSONObject(index);
            if (value != null && !entry.optString("requestId").equals(value.optString("requestId"))) {
                retained.put(value);
            }
        }
        preferences(context).edit().putString(AUDIT, retained.toString()).apply();
    }

    private static void validateOptionalId(JSONObject request, String name) {
        if (!request.has(name)) return;
        if (!OBJECT_ID.matcher(request.optString(name)).matches()) {
            throw new SecurityException("Invalid " + name);
        }
    }

    private static boolean requiresWorkspace(String operation) {
        return OP_ACTIVATE_WORKSPACE.equals(operation) || OP_LOCK_SECONDARY.equals(operation)
                || OP_UNLOCK_TEMPORARY.equals(operation) || OP_PROMOTE_PRIMARY.equals(operation)
                || OP_DEMOTE_SECONDARY.equals(operation) || OP_SHELVE_WORKSPACE.equals(operation)
                || OP_RESTORE_WORKSPACE.equals(operation) || OP_CLOSE_WORKSPACE.equals(operation)
                || OP_DELETE_SHELVED.equals(operation);
    }

    private static boolean requiresTab(String operation) {
        return OP_ACTIVATE_TAB.equals(operation) || OP_CLOSE_TAB.equals(operation);
    }

    private static boolean requiresDownload(String operation) {
        return OP_APPROVE_DOWNLOAD.equals(operation) || OP_REJECT_DOWNLOAD.equals(operation)
                || OP_CANCEL_DOWNLOAD.equals(operation) || OP_RETRY_DOWNLOAD.equals(operation)
                || OP_DELETE_DOWNLOAD.equals(operation);
    }

    private static void requireHttpUrl(String url) {
        Uri parsed = Uri.parse(url);
        String scheme = parsed.getScheme();
        if (url.length() > 8192 || parsed.getHost() == null
                || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new SecurityException("Only a valid HTTP(S) URL is allowed");
        }
    }

    private static void validateSitePermission(JSONObject request, boolean allowBackground) {
        String permission = request.optString("permission", "");
        if (permission.isBlank()) {
            if (allowBackground) throw new SecurityException("permission is required");
            return;
        }
        if (ReBrowserSitePermissions.isManaged(permission)) return;
        if (allowBackground
                && ReBrowserSitePermissions.BACKGROUND_RUNTIME_PERMISSION.equals(permission)) {
            return;
        }
        throw new SecurityException("Unsupported website permission");
    }

    private static void validatePreference(JSONObject request) {
        String name = request.optString("name", "");
        Object value = request.opt("value");
        if ("searchEngine".equals(name)) {
            String string = value instanceof String ? (String) value : "";
            if (!ReBrowserPreferences.SEARCH_BING.equals(string)
                    && !ReBrowserPreferences.SEARCH_BAIDU.equals(string)
                    && !ReBrowserPreferences.SEARCH_DUCKDUCKGO.equals(string)
                    && !ReBrowserPreferences.SEARCH_GOOGLE.equals(string)) {
                throw new SecurityException("Invalid search engine");
            }
            return;
        }
        if ("globalOrientation".equals(name)) {
            String string = value instanceof String ? (String) value : "";
            if (!ReBrowserPreferences.ORIENTATION_PORTRAIT.equals(string)
                    && !ReBrowserPreferences.ORIENTATION_LANDSCAPE.equals(string)
                    && !ReBrowserPreferences.ORIENTATION_UNLOCKED.equals(string)) {
                throw new SecurityException("Invalid global orientation");
            }
            return;
        }
        if ("videoOrientation".equals(name)) {
            String string = value instanceof String ? (String) value : "";
            if (!ReBrowserPreferences.ORIENTATION_PORTRAIT.equals(string)
                    && !ReBrowserPreferences.ORIENTATION_LANDSCAPE.equals(string)
                    && !ReBrowserPreferences.VIDEO_ORIENTATION_AUTO.equals(string)) {
                throw new SecurityException("Invalid video orientation");
            }
            return;
        }
        if (!(value instanceof Boolean) || !("javascript".equals(name)
                || "thirdPartyCookies".equals(name) || "desktopMode".equals(name)
                || "forcePrimaryPromotion".equals(name)
                || "videoOrientationOverride".equals(name))) {
            throw new SecurityException("Unsupported preference or value");
        }
    }

    private static String bounded(String value, int maximum) {
        return value.substring(0, Math.min(value.length(), maximum));
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
