package io.github.reoutlook;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Global permission gates and bounded Profile + top-level-origin grants for ReBrowser. */
final class ReBrowserSitePermissions {
    static final String CAMERA = "camera";
    static final String MICROPHONE = "microphone";
    static final String PRECISE_LOCATION = "precise-location";
    static final String APPROXIMATE_LOCATION = "approximate-location";
    static final String CLIPBOARD = "clipboard";
    static final String BACKGROUND_RUNTIME_PERMISSION = "background-runtime";

    static final long FIVE_MINUTES_MS = 5L * 60L * 1000L;
    static final long THREE_HOURS_MS = 3L * 60L * 60L * 1000L;
    static final long FIFTEEN_DAYS_MS = 15L * 24L * 60L * 60L * 1000L;
    static final int MAX_GRANTS = 512;

    private static final String PREFERENCES = "rebrowser_site_permissions_v1";
    private static final String GRANTS = "grants";
    private static final String REVISION = "revision";
    private static final String BACKGROUND_RUNTIME = "background_runtime";

    static final class Validation {
        final int storedCount;
        final int validCount;
        final int malformedCount;
        final int expiredCount;
        final int futureCount;
        final int disabledCount;
        final int duplicateCount;
        final int excessCount;
        final boolean malformedStorage;
        final boolean repaired;

        Validation(int storedCount, int validCount, int malformedCount, int expiredCount,
                int futureCount, int disabledCount, int duplicateCount, int excessCount,
                boolean malformedStorage, boolean repaired) {
            this.storedCount = storedCount;
            this.validCount = validCount;
            this.malformedCount = malformedCount;
            this.expiredCount = expiredCount;
            this.futureCount = futureCount;
            this.disabledCount = disabledCount;
            this.duplicateCount = duplicateCount;
            this.excessCount = excessCount;
            this.malformedStorage = malformedStorage;
            this.repaired = repaired;
        }

        boolean valid() {
            return !malformedStorage && malformedCount == 0 && expiredCount == 0
                    && futureCount == 0 && disabledCount == 0 && duplicateCount == 0
                    && excessCount == 0;
        }
    }

    static final class Grant {
        final String profileName;
        final String origin;
        final String permission;
        final long grantedAt;
        final long expiresAt;

        Grant(String profileName, String origin, String permission, long grantedAt, long expiresAt) {
            this.profileName = profileName;
            this.origin = origin;
            this.permission = permission;
            this.grantedAt = grantedAt;
            this.expiresAt = expiresAt;
        }
    }

    private final SharedPreferences preferences;

    ReBrowserSitePermissions(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    synchronized boolean capabilityEnabled(String permission) {
        if (!isManaged(permission)) return false;
        return preferences.getBoolean("enabled_" + permission, defaultEnabled(permission));
    }

    synchronized void setCapabilityEnabled(String permission, boolean enabled) {
        if (!isManaged(permission)) return;
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean("enabled_" + permission, enabled)
                .putLong(REVISION, revision() + 1L);
        if (!enabled) editor.putString(GRANTS, encodeWithout(permission));
        editor.apply();
    }

    boolean notificationsEnabled() {
        return false;
    }

    boolean backgroundPushEnabled() {
        return false;
    }

    boolean webSensorsEnabled() {
        return false;
    }

    boolean backgroundRuntimeEnabled() {
        return preferences.getBoolean(BACKGROUND_RUNTIME, true);
    }

    synchronized void setBackgroundRuntimeEnabled(boolean enabled) {
        preferences.edit().putBoolean(BACKGROUND_RUNTIME, enabled)
                .putLong(REVISION, revision() + 1L).apply();
    }

    long revision() {
        return preferences.getLong(REVISION, 0L);
    }

    synchronized boolean hasGrant(
            String profileName,
            String origin,
            String permission,
            long now
    ) {
        if (!capabilityEnabled(permission)) return false;
        String normalizedOrigin = normalizeOrigin(origin);
        if (!ReBrowserStore.isOwnedProfile(profileName) || normalizedOrigin.isEmpty()) return false;
        boolean changed = false;
        boolean found = false;
        List<Grant> valid = new ArrayList<>();
        for (Grant grant : decode()) {
            if (grant.expiresAt <= now || grant.grantedAt > now + 60_000L) {
                changed = true;
                continue;
            }
            valid.add(grant);
            if (grant.profileName.equals(profileName)
                    && grant.origin.equals(normalizedOrigin)
                    && grant.permission.equals(permission)) found = true;
        }
        if (changed) save(valid, false);
        return found;
    }

    synchronized boolean grant(
            String profileName,
            String origin,
            String permission,
            long durationMs,
            long now
    ) {
        if (!capabilityEnabled(permission)
                || !validDuration(permission, durationMs)
                || !ReBrowserStore.isOwnedProfile(profileName)) return false;
        String normalizedOrigin = normalizeOrigin(origin);
        if (normalizedOrigin.isEmpty()) return false;
        List<Grant> grants = validGrants(now);
        grants.removeIf(value -> value.profileName.equals(profileName)
                && value.origin.equals(normalizedOrigin)
                && value.permission.equals(permission));
        grants.add(new Grant(profileName, normalizedOrigin, permission, now, now + durationMs));
        grants.sort(Comparator.comparingLong(value -> -value.expiresAt));
        if (grants.size() > MAX_GRANTS) grants = new ArrayList<>(grants.subList(0, MAX_GRANTS));
        save(grants, false);
        return true;
    }

    synchronized void revoke(String profileName, String origin, String permission) {
        String normalizedOrigin = normalizeOrigin(origin);
        List<Grant> grants = validGrants(System.currentTimeMillis());
        boolean removed = grants.removeIf(value -> value.profileName.equals(profileName)
                && value.origin.equals(normalizedOrigin)
                && value.permission.equals(permission));
        if (removed) save(grants, true);
    }

    synchronized void clearForProfile(String profileName) {
        List<Grant> grants = validGrants(System.currentTimeMillis());
        boolean removed = grants.removeIf(value -> value.profileName.equals(profileName));
        if (removed) save(grants, true);
    }

    synchronized void clearAll() {
        preferences.edit().putString(GRANTS, "[]")
                .putLong(REVISION, revision() + 1L).apply();
    }

    synchronized int clearGrants(String permission) {
        if (permission != null && !permission.isBlank() && !isManaged(permission)) return 0;
        List<Grant> grants = validGrants(System.currentTimeMillis());
        int before = grants.size();
        if (permission == null || permission.isBlank()) grants.clear();
        else grants.removeIf(value -> value.permission.equals(permission));
        int removed = before - grants.size();
        if (removed > 0) save(grants, true);
        return removed;
    }

    synchronized List<Grant> grants(long now) {
        List<Grant> grants = validGrants(now);
        save(grants, false);
        return Collections.unmodifiableList(grants);
    }

    synchronized Validation validate(long now, boolean repair) {
        String raw;
        JSONArray values;
        boolean malformedStorage = false;
        try {
            raw = preferences.getString(GRANTS, "[]");
        } catch (ClassCastException ignored) {
            raw = "[]";
            malformedStorage = true;
        }
        try {
            values = new JSONArray(raw);
        } catch (Exception ignored) {
            values = new JSONArray();
            malformedStorage = true;
        }
        int storedCount = values.length();
        int scanCount = Math.min(storedCount, MAX_GRANTS * 2);
        int malformedCount = 0;
        int expiredCount = 0;
        int futureCount = 0;
        int disabledCount = 0;
        int duplicateCount = 0;
        int excessCount = Math.max(0, storedCount - MAX_GRANTS);
        List<Grant> candidates = new ArrayList<>();
        for (int index = 0; index < scanCount; index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) {
                malformedCount++;
                continue;
            }
            String storedOrigin = value.optString("origin");
            String origin = normalizeOrigin(storedOrigin);
            String profileName = value.optString("profileName");
            String permission = value.optString("permission");
            long grantedAt = value.optLong("grantedAt");
            long expiresAt = value.optLong("expiresAt");
            if (!ReBrowserStore.isOwnedProfile(profileName) || origin.isEmpty()
                    || !origin.equals(storedOrigin) || !isManaged(permission)
                    || grantedAt <= 0L || expiresAt <= grantedAt
                    || !validDuration(permission, expiresAt - grantedAt)) {
                malformedCount++;
            } else if (expiresAt <= now) {
                expiredCount++;
            } else if (grantedAt > now + 60_000L) {
                futureCount++;
            } else if (!capabilityEnabled(permission)) {
                disabledCount++;
            } else {
                candidates.add(new Grant(profileName, origin, permission, grantedAt, expiresAt));
            }
        }
        candidates.sort(Comparator.comparingLong(value -> -value.expiresAt));
        List<Grant> valid = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Grant grant : candidates) {
            String key = grant.profileName + "\n" + grant.origin + "\n" + grant.permission;
            if (!keys.add(key)) {
                duplicateCount++;
            } else if (valid.size() < MAX_GRANTS) {
                valid.add(grant);
            }
        }
        boolean hasIssues = malformedStorage || malformedCount > 0 || expiredCount > 0
                || futureCount > 0 || disabledCount > 0 || duplicateCount > 0
                || excessCount > 0;
        if (repair && hasIssues) save(valid, true);
        return new Validation(storedCount, valid.size(), malformedCount, expiredCount,
                futureCount, disabledCount, duplicateCount, excessCount,
                malformedStorage, repair && hasIssues);
    }

    private List<Grant> validGrants(long now) {
        List<Grant> result = new ArrayList<>();
        for (Grant grant : decode()) {
            if (grant.expiresAt > now && grant.grantedAt <= now + 60_000L
                    && capabilityEnabled(grant.permission)) result.add(grant);
        }
        return result;
    }

    private String encodeWithout(String permission) {
        JSONArray values = new JSONArray();
        for (Grant grant : decode()) {
            if (!grant.permission.equals(permission)) values.put(encode(grant));
        }
        return values.toString();
    }

    private void save(List<Grant> grants, boolean incrementRevision) {
        JSONArray values = new JSONArray();
        for (Grant grant : grants) values.put(encode(grant));
        SharedPreferences.Editor editor = preferences.edit().putString(GRANTS, values.toString());
        if (incrementRevision) editor.putLong(REVISION, revision() + 1L);
        editor.apply();
    }

    private List<Grant> decode() {
        List<Grant> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(preferences.getString(GRANTS, "[]"));
            for (int index = 0; index < values.length() && result.size() < MAX_GRANTS; index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                String profileName = value.optString("profileName");
                String origin = normalizeOrigin(value.optString("origin"));
                String permission = value.optString("permission");
                long grantedAt = value.optLong("grantedAt");
                long expiresAt = value.optLong("expiresAt");
                if (!ReBrowserStore.isOwnedProfile(profileName) || origin.isEmpty()
                        || !isManaged(permission) || grantedAt <= 0L || expiresAt <= grantedAt
                        || !validDuration(permission, expiresAt - grantedAt)) {
                    continue;
                }
                result.add(new Grant(profileName, origin, permission, grantedAt, expiresAt));
            }
        } catch (Exception ignored) {
            // Corrupt authorization metadata grants nothing.
        }
        return result;
    }

    private static JSONObject encode(Grant grant) {
        try {
            return new JSONObject()
                    .put("profileName", grant.profileName)
                    .put("origin", grant.origin)
                    .put("permission", grant.permission)
                    .put("grantedAt", grant.grantedAt)
                    .put("expiresAt", grant.expiresAt);
        } catch (Exception impossible) {
            return new JSONObject();
        }
    }

    static boolean validDuration(String permission, long durationMs) {
        if (CAMERA.equals(permission) || MICROPHONE.equals(permission)
                || PRECISE_LOCATION.equals(permission)) {
            return durationMs == THREE_HOURS_MS;
        }
        return (APPROXIMATE_LOCATION.equals(permission) || CLIPBOARD.equals(permission))
                && (durationMs == FIVE_MINUTES_MS || durationMs == FIFTEEN_DAYS_MS);
    }

    static boolean defaultEnabled(String permission) {
        return APPROXIMATE_LOCATION.equals(permission) || CLIPBOARD.equals(permission);
    }

    static boolean isManaged(String permission) {
        return CAMERA.equals(permission) || MICROPHONE.equals(permission)
                || PRECISE_LOCATION.equals(permission)
                || APPROXIMATE_LOCATION.equals(permission) || CLIPBOARD.equals(permission);
    }

    static String normalizeOrigin(String value) {
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(scheme) || host == null || host.isBlank()) return "";
            int port = uri.getPort();
            return "https://" + host.toLowerCase(Locale.ROOT)
                    + (port < 0 || port == 443 ? "" : ":" + port);
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
