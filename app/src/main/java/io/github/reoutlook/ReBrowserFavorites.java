package io.github.reoutlook;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Browser-global favorite URLs. Website cookies and Profile storage never enter this store. */
final class ReBrowserFavorites {
    private static final String PREFERENCES = "rebrowser_favorites_v1";
    private static final String FAVORITES = "favorites";
    private static final Pattern ID_PATTERN = Pattern.compile("[a-f0-9]{32}");
    static final int MAX_FAVORITES = 200;

    static final class Favorite {
        final String id;
        String title;
        final String url;

        Favorite(String id, String title, String url) {
            this.id = id;
            this.title = cleanTitle(title, url);
            this.url = validUrl(url);
        }
    }

    private final SharedPreferences preferences;

    ReBrowserFavorites(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    List<Favorite> load() {
        List<Favorite> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            JSONArray values = new JSONArray(preferences.getString(FAVORITES, "[]"));
            for (int index = 0;
                    index < values.length() && result.size() < MAX_FAVORITES;
                    index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                String id = value.optString("id");
                String url = validUrl(value.optString("url"));
                if (!ID_PATTERN.matcher(id).matches() || !ids.add(id) || url.isEmpty()) continue;
                result.add(new Favorite(id, value.optString("title"), url));
            }
        } catch (Exception ignored) {
            // Invalid favorite metadata is discarded without touching any WebView Profile data.
        }
        return result;
    }

    Favorite findByUrl(List<Favorite> favorites, String url) {
        String normalized = normalizedUrl(url);
        if (normalized.isEmpty()) return null;
        for (Favorite favorite : favorites) {
            if (normalized.equals(normalizedUrl(favorite.url))) return favorite;
        }
        return null;
    }

    Favorite add(List<Favorite> favorites, String title, String url) {
        String safeUrl = validUrl(url);
        if (safeUrl.isEmpty() || favorites.size() >= MAX_FAVORITES) return null;
        Favorite existing = findByUrl(favorites, safeUrl);
        if (existing != null) return existing;
        Favorite favorite = new Favorite(newId(), title, safeUrl);
        favorites.add(favorite);
        save(favorites);
        return favorite;
    }

    void remove(List<Favorite> favorites, Favorite favorite) {
        if (favorite == null || !favorites.remove(favorite)) return;
        save(favorites);
    }

    void save(List<Favorite> favorites) {
        JSONArray values = new JSONArray();
        for (int index = 0;
                index < favorites.size() && index < MAX_FAVORITES;
                index++) {
            Favorite favorite = favorites.get(index);
            String url = validUrl(favorite.url);
            if (url.isEmpty()) continue;
            try {
                JSONObject value = new JSONObject();
                value.put("id", favorite.id);
                value.put("title", cleanTitle(favorite.title, url));
                value.put("url", url);
                values.put(value);
            } catch (Exception ignored) {
                // All values are bounded strings, so org.json should not reject them.
            }
        }
        preferences.edit().putString(FAVORITES, values.toString()).apply();
    }

    private static String validUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 8192) return "";
        String clean = value.trim();
        Uri uri = Uri.parse(clean);
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) return "";
        return uri.getHost() == null || uri.getHost().isBlank() ? "" : clean;
    }

    private static String normalizedUrl(String value) {
        String valid = validUrl(value);
        if (valid.isEmpty()) return "";
        try {
            return Uri.parse(valid).normalizeScheme().buildUpon().fragment(null).build()
                    .toString().replaceAll("/+$", "");
        } catch (RuntimeException ignored) {
            return valid;
        }
    }

    private static String cleanTitle(String value, String fallback) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) clean = fallback;
        return clean.substring(0, Math.min(clean.length(), 160));
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
