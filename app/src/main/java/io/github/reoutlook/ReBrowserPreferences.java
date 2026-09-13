package io.github.reoutlook;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** Browser-global user preferences shared by every ReBrowser workspace. */
final class ReBrowserPreferences {
    static final String SEARCH_BING = "bing";
    static final String SEARCH_BAIDU = "baidu";
    static final String SEARCH_DUCKDUCKGO = "duckduckgo";
    static final String SEARCH_GOOGLE = "google";

    private static final String PREFERENCES = "rebrowser_preferences_v1";
    private static final String HOME_URL = "home_url";
    private static final String SEARCH_ENGINE = "search_engine";
    private static final String JAVASCRIPT = "javascript";
    private static final String THIRD_PARTY_COOKIES = "third_party_cookies";
    private static final String DESKTOP_MODE = "desktop_mode";

    private final SharedPreferences preferences;

    ReBrowserPreferences(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    String homeUrl() {
        return validHttpUrl(preferences.getString(HOME_URL, ReBrowserStore.HOME_URL),
                ReBrowserStore.HOME_URL);
    }

    void setHomeUrl(String value) {
        preferences.edit().putString(HOME_URL,
                validHttpUrl(normalizeAddress(value), ReBrowserStore.HOME_URL)).apply();
    }

    String searchEngine() {
        String value = preferences.getString(SEARCH_ENGINE, SEARCH_BING);
        if (SEARCH_GOOGLE.equals(value) || SEARCH_BAIDU.equals(value)
                || SEARCH_DUCKDUCKGO.equals(value)) return value;
        return SEARCH_BING;
    }

    void setSearchEngine(String value) {
        if (!SEARCH_GOOGLE.equals(value) && !SEARCH_BAIDU.equals(value)
                && !SEARCH_BING.equals(value) && !SEARCH_DUCKDUCKGO.equals(value)) return;
        preferences.edit().putString(SEARCH_ENGINE, value).apply();
    }

    boolean javascriptEnabled() {
        return preferences.getBoolean(JAVASCRIPT, true);
    }

    void setJavascriptEnabled(boolean enabled) {
        preferences.edit().putBoolean(JAVASCRIPT, enabled).apply();
    }

    boolean thirdPartyCookiesEnabled() {
        return preferences.getBoolean(THIRD_PARTY_COOKIES, true);
    }

    void setThirdPartyCookiesEnabled(boolean enabled) {
        preferences.edit().putBoolean(THIRD_PARTY_COOKIES, enabled).apply();
    }

    boolean desktopModeEnabled() {
        return preferences.getBoolean(DESKTOP_MODE, false);
    }

    void setDesktopModeEnabled(boolean enabled) {
        preferences.edit().putBoolean(DESKTOP_MODE, enabled).apply();
    }

    String searchUrl(String query) {
        String encoded = Uri.encode(query == null ? "" : query.trim());
        switch (searchEngine()) {
            case SEARCH_BAIDU:
                return "https://www.baidu.com/s?wd=" + encoded;
            case SEARCH_DUCKDUCKGO:
                return "https://duckduckgo.com/?q=" + encoded;
            case SEARCH_GOOGLE:
                return "https://www.google.com/search?q=" + encoded;
            default:
                return "https://cn.bing.com/search?q=" + encoded;
        }
    }

    private static String normalizeAddress(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return ReBrowserStore.HOME_URL;
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://")) return clean;
        return "https://" + clean;
    }

    private static String validHttpUrl(String value, String fallback) {
        if (value == null || value.isBlank() || value.length() > 8192) return fallback;
        Uri uri = Uri.parse(value.trim());
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            return fallback;
        }
        return uri.getHost() == null || uri.getHost().isBlank() ? fallback : value.trim();
    }
}
