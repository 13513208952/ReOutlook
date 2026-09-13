package io.github.reoutlook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Debug-only probe for ReBrowser's one-named-Profile-per-workspace model. */
@SuppressLint({"RequiresFeature", "SetTextI18n"})
@WebViewCompat.ExperimentalSaveState
public final class ReBrowserProbeActivity extends Activity {
    private static final String PROFILE_A = "rebrowser_probe_workspace_a";
    private static final String PROFILE_B = "rebrowser_probe_workspace_b";
    private static final String COOKIE_URL = "https://example.com/";

    private final List<WebView> probeWebViews = new ArrayList<>();
    private TextView resultsView;
    private int probeGeneration;
    private CookieManager cleanupManagerA;
    private CookieManager cleanupManagerB;
    private String cleanupCookieName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        runProbe();
    }

    private ScrollView createContentView() {
        int padding = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        content.setBackgroundColor(Color.rgb(248, 250, 252));

        TextView title = new TextView(this);
        title.setText("ReBrowser Multi-Profile 探针");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(20, 32, 51));
        title.setGravity(Gravity.START);
        content.addView(title, matchWrap());

        TextView explanation = new TextView(this);
        explanation.setText("仅存在于 debug APK；不会访问或迁移 ReOutlook 的 Default Profile 数据。");
        explanation.setTextSize(14);
        explanation.setTextColor(Color.rgb(70, 83, 105));
        LinearLayout.LayoutParams explanationParams = matchWrap();
        explanationParams.topMargin = dp(8);
        content.addView(explanation, explanationParams);

        Button rerun = new Button(this);
        rerun.setText("重新运行隔离测试");
        rerun.setOnClickListener(view -> runProbe());
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(16);
        content.addView(rerun, buttonParams);

        resultsView = new TextView(this);
        resultsView.setTextSize(14);
        resultsView.setTextColor(Color.rgb(25, 39, 55));
        resultsView.setTextIsSelectable(true);
        resultsView.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams resultsParams = matchWrap();
        resultsParams.topMargin = dp(16);
        content.addView(resultsView, resultsParams);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scrollView;
    }

    private void runProbe() {
        int generation = ++probeGeneration;
        cleanupProbeCookie();
        destroyProbeWebViews();

        StringBuilder report = new StringBuilder();
        PackageInfo provider = WebView.getCurrentWebViewPackage();
        report.append("WebView Provider\n");
        if (provider == null) {
            report.append("⚠ 无法读取当前 Provider\n");
        } else {
            report.append("• ").append(provider.packageName).append('\n');
            report.append("• version ").append(provider.versionName).append("\n");
        }

        boolean multiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
        boolean deleteData = WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA);
        boolean saveState = WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE);
        report.append("\nFeature 支持\n");
        appendCheck(report, multiProfile, "MULTI_PROFILE");
        appendCheck(report, deleteData, "DELETE_BROWSING_DATA");
        appendCheck(report, saveState, "SAVE_STATE");

        if (!multiProfile) {
            report.append("\n结论：当前 Provider 不支持 Multi-Profile，ReBrowser 必须保持禁用。\n");
            resultsView.setText(report.toString());
            return;
        }

        try {
            // setProfile() must be the first operation performed on each new WebView.
            WebView childA1 = createProfileWebView(PROFILE_A);
            WebView childA2 = createProfileWebView(PROFILE_A);
            WebView childB = createProfileWebView(PROFILE_B);

            Profile profileA1 = WebViewCompat.getProfile(childA1);
            Profile profileA2 = WebViewCompat.getProfile(childA2);
            Profile profileB = WebViewCompat.getProfile(childB);
            boolean profileNamesCorrect = PROFILE_A.equals(profileA1.getName())
                    && PROFILE_A.equals(profileA2.getName())
                    && PROFILE_B.equals(profileB.getName());
            boolean sameWorkspaceProfile = profileA1.getName().equals(profileA2.getName());
            boolean differentWorkspaceProfiles = !profileA1.getName().equals(profileB.getName());

            report.append("\nProfile 绑定\n");
            appendCheck(report, profileNamesCorrect, "命名 Profile 与预期一致");
            appendCheck(report, sameWorkspaceProfile, "工作区 A 的两个子 Tab 共用 Profile");
            appendCheck(report, differentWorkspaceProfiles, "工作区 A 与 B 使用不同 Profile");

            List<String> profileNames = ProfileStore.getInstance().getAllProfileNames();
            report.append("\nProfileStore\n");
            appendCheck(report, profileNames.contains(Profile.DEFAULT_PROFILE_NAME),
                    "Default Profile 保持存在");
            appendCheck(report, profileNames.contains(PROFILE_A), "工作区 A 可持久发现");
            appendCheck(report, profileNames.contains(PROFILE_B), "工作区 B 可持久发现");
            report.append("• 当前 Profile 总数：").append(profileNames.size()).append('\n');

            resultsView.setText(report.append("\nCookie 隔离测试运行中…\n").toString());
            runCookieIsolationProbe(generation, report, profileA1, profileA2, profileB);
        } catch (Throwable error) {
            report.append("\n❌ Profile 探针异常：")
                    .append(error.getClass().getSimpleName());
            String message = error.getMessage();
            if (message != null && !message.isBlank()) {
                report.append(" — ").append(message.substring(0, Math.min(message.length(), 240)));
            }
            report.append("\n结论：ReBrowser 保持禁用，ReOutlook 不受影响。\n");
            resultsView.setText(report.toString());
            destroyProbeWebViews();
        }
    }

    private WebView createProfileWebView(String profileName) {
        WebView webView = new WebView(this);
        WebViewCompat.setProfile(webView, profileName);
        probeWebViews.add(webView);
        return webView;
    }

    private void runCookieIsolationProbe(
            int generation,
            StringBuilder report,
            Profile profileA1,
            Profile profileA2,
            Profile profileB
    ) {
        String cookieName = "rebrowser_probe_" + Long.toString(System.nanoTime(), 36)
                .toLowerCase(Locale.ROOT);
        CookieManager managerA1 = profileA1.getCookieManager();
        CookieManager managerA2 = profileA2.getCookieManager();
        CookieManager managerB = profileB.getCookieManager();
        cleanupManagerA = managerA1;
        cleanupManagerB = managerB;
        cleanupCookieName = cookieName;

        int[] callbacks = {0};
        boolean[] writesSucceeded = {true};
        boolean[] finished = {false};
        Runnable finish = () -> {
            if (finished[0] || generation != probeGeneration) return;
            finished[0] = true;
            String cookiesA1 = managerA1.getCookie(COOKIE_URL);
            String cookiesA2 = managerA2.getCookie(COOKIE_URL);
            String cookiesB = managerB.getCookie(COOKIE_URL);
            boolean a1HasA = hasCookie(cookiesA1, cookieName, "workspace-a");
            boolean a2HasA = hasCookie(cookiesA2, cookieName, "workspace-a");
            boolean bHasB = hasCookie(cookiesB, cookieName, "workspace-b");
            boolean aDoesNotSeeB = !hasCookie(cookiesA1, cookieName, "workspace-b");
            boolean bDoesNotSeeA = !hasCookie(cookiesB, cookieName, "workspace-a");

            report.append("\nCookie 隔离\n");
            appendCheck(report, writesSucceeded[0], "两个临时 Cookie 写入成功");
            appendCheck(report, a1HasA && a2HasA, "同一工作区的子 Tab 共享 Cookie");
            appendCheck(report, bHasB && aDoesNotSeeB && bDoesNotSeeA,
                    "不同工作区 Cookie 相互隔离");
            boolean passed = writesSucceeded[0] && a1HasA && a2HasA && bHasB
                    && aDoesNotSeeB && bDoesNotSeeA;
            report.append("\n结论：")
                    .append(passed
                            ? "基础工作区 Profile 模型可用，可以进入 ReBrowser MVP。"
                            : "隔离验证未通过，ReBrowser 必须保持禁用。")
                    .append('\n');
            resultsView.setText(report.toString());
            cleanupProbeCookie();
        };

        android.webkit.ValueCallback<Boolean> callback = success -> {
            writesSucceeded[0] &= Boolean.TRUE.equals(success);
            callbacks[0]++;
            if (callbacks[0] == 2) finish.run();
        };
        String attributes = "; Path=/; Secure; SameSite=Lax";
        managerA1.setCookie(COOKIE_URL, cookieName + "=workspace-a" + attributes, callback);
        managerB.setCookie(COOKIE_URL, cookieName + "=workspace-b" + attributes, callback);
        resultsView.postDelayed(finish, 2_500L);
    }

    private static boolean hasCookie(String cookieHeader, String name, String value) {
        if (cookieHeader == null) return false;
        String expected = name + "=" + value;
        for (String entry : cookieHeader.split(";")) {
            if (expected.equals(entry.trim())) return true;
        }
        return false;
    }

    private void cleanupProbeCookie() {
        if (cleanupCookieName == null) return;
        String expired = cleanupCookieName
                + "=; Max-Age=0; Path=/; Secure; SameSite=Lax";
        if (cleanupManagerA != null) {
            cleanupManagerA.setCookie(COOKIE_URL, expired, null);
            cleanupManagerA.flush();
        }
        if (cleanupManagerB != null) {
            cleanupManagerB.setCookie(COOKIE_URL, expired, null);
            cleanupManagerB.flush();
        }
        cleanupManagerA = null;
        cleanupManagerB = null;
        cleanupCookieName = null;
    }

    private void destroyProbeWebViews() {
        for (WebView webView : probeWebViews) webView.destroy();
        probeWebViews.clear();
    }

    private static void appendCheck(StringBuilder report, boolean passed, String label) {
        report.append(passed ? "✅ " : "❌ ").append(label).append('\n');
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        probeGeneration++;
        cleanupProbeCookie();
        destroyProbeWebViews();
        super.onDestroy();
    }
}
