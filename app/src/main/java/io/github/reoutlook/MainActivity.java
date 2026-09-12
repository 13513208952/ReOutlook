package io.github.reoutlook;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String OUTLOOK_URL = "https://outlook.office.com/mail/";
    private static final int FILE_CHOOSER_REQUEST = 4102;
    private static final long AUTO_CAPTURE_INTERVAL_MS = 12_000L;
    private static final long DOUBLE_BACK_INTERVAL_MS = 2_000L;
    private static final long AUTO_SCROLL_INTERVAL_MS = 1_600L;
    private static final long MANUAL_SCROLL_PAUSE_MS = 3_500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoCapture = new Runnable() {
        @Override
        public void run() {
            if (!structuredCaptureEnabled) captureCurrentMessage(false);
            handler.postDelayed(this, AUTO_CAPTURE_INTERVAL_MS);
        }
    };
    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            if (autoScrollEnabled) {
                scrollOutlookMailList();
                handler.postDelayed(this, AUTO_SCROLL_INTERVAL_MS);
            }
        }
    };

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusText;
    private View drawerScrim;
    private LinearLayout appDrawer;
    private TextView floatingButton;
    private Switch autoScrollSwitch;
    private boolean drawerOpen;
    private boolean autoScrollEnabled;
    private long autoScrollPausedUntil;
    private long lastBackPressAt;
    private MailDatabase database;
    private ValueCallback<Uri[]> pendingFileChooser;
    private final ExecutorService cacheWriter = Executors.newSingleThreadExecutor();
    private boolean activityVisible;
    private boolean structuredCaptureEnabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Repair a vendor package-manager override left by an interrupted maintenance test.
        MaintenanceAuthorizer.ensureEntryPointsEnabled(this);
        // A normal launch invalidates any interrupted, short-lived administrator challenge.
        MaintenanceAuthorizer.clearPendingChallenge(this);
        database = new MailDatabase(this);
        database.cleanupLegacyDuplicates();
        setContentView(createContentView());
        configureWebView();

        boolean restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null;
        if (!restored) webView.loadUrl(OUTLOOK_URL);
    }

    private View createContentView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        WindowStyling.apply(this, root);

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.TOP);
        root.addView(progressBar, progressParams);

        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(Color.BLACK);
        drawerScrim.setAlpha(0f);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(view -> closeDrawer());
        root.addView(drawerScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        appDrawer = createDrawer();
        int drawerWidth = Math.min(dp(320),
                Math.round(getResources().getDisplayMetrics().widthPixels * 0.84f));
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(
                drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        appDrawer.setTranslationX(-drawerWidth);
        root.addView(appDrawer, drawerParams);

        floatingButton = createFloatingButton(root);
        FrameLayout.LayoutParams floatingParams = new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
        floatingParams.setMarginEnd(dp(12));
        root.addView(floatingButton, floatingParams);
        restoreFloatingPosition(root);
        return root;
    }

    private TextView createFloatingButton(FrameLayout parent) {
        TextView button = new TextView(this);
        button.setText("R");
        button.setTextColor(Color.WHITE);
        button.setTextSize(17);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription("打开 ReOutlook 菜单");
        button.setElevation(dp(8));
        button.setAlpha(0.68f);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.rgb(15, 108, 189));
        button.setBackground(shape);
        button.setOnClickListener(view -> openDrawer());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            button.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) ->
                    view.setSystemGestureExclusionRects(Collections.singletonList(
                            new Rect(0, 0, view.getWidth(), view.getHeight()))));
        }

        float[] down = new float[4];
        boolean[] dragging = new boolean[1];
        int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    down[2] = view.getX();
                    down[3] = view.getY();
                    dragging[0] = false;
                    view.animate().alpha(0.94f).setDuration(100).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    if (!dragging[0] && Math.hypot(dx, dy) > touchSlop) dragging[0] = true;
                    if (dragging[0]) {
                        float minX = parent.getPaddingLeft();
                        float minY = parent.getPaddingTop();
                        float maxX = Math.max(minX,
                                parent.getWidth() - parent.getPaddingRight() - view.getWidth());
                        float maxY = Math.max(minY,
                                parent.getHeight() - parent.getPaddingBottom() - view.getHeight());
                        view.setX(clamp(down[2] + dx, minX, maxX));
                        view.setY(clamp(down[3] + dy, minY, maxY));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging[0]) settleFloatingButton(parent, view);
                    else {
                        view.animate().alpha(0.68f).setDuration(140).start();
                        view.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging[0]) settleFloatingButton(parent, view);
                    else view.animate().alpha(0.68f).setDuration(140).start();
                    return true;
                default:
                    return false;
            }
        });
        return button;
    }

    private void restoreFloatingPosition(FrameLayout parent) {
        parent.post(() -> {
            android.content.SharedPreferences preferences =
                    getSharedPreferences("ui_preferences", MODE_PRIVATE);
            float minX = parent.getPaddingLeft();
            float minY = parent.getPaddingTop();
            float width = Math.max(0, parent.getWidth() - parent.getPaddingLeft()
                    - parent.getPaddingRight() - floatingButton.getWidth());
            float height = Math.max(0, parent.getHeight() - parent.getPaddingTop()
                    - parent.getPaddingBottom() - floatingButton.getHeight());
            boolean attachRight = preferences.getFloat("floating_x", 1f) >= 0.5f;
            float yRatio = preferences.getFloat("floating_y", 0.5f);
            floatingButton.setX(minX + (attachRight ? width : 0));
            floatingButton.setY(minY + height * clamp(yRatio, 0f, 1f));
            saveFloatingPosition(parent, floatingButton);
        });
    }

    private void settleFloatingButton(FrameLayout parent, View view) {
        float minX = parent.getPaddingLeft();
        float maxX = Math.max(minX,
                parent.getWidth() - parent.getPaddingRight() - view.getWidth());
        float targetX = view.getX() + view.getWidth() / 2f < parent.getWidth() / 2f
                ? minX
                : maxX;
        view.animate()
                .x(targetX)
                .alpha(0.68f)
                .setDuration(180)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        saveFloatingPosition(parent, view);
                        view.animate().setListener(null);
                    }
                })
                .start();
    }

    private void saveFloatingPosition(FrameLayout parent, View view) {
        float minX = parent.getPaddingLeft();
        float minY = parent.getPaddingTop();
        float width = Math.max(1, parent.getWidth() - parent.getPaddingLeft()
                - parent.getPaddingRight() - view.getWidth());
        float height = Math.max(1, parent.getHeight() - parent.getPaddingTop()
                - parent.getPaddingBottom() - view.getHeight());
        float x = clamp((view.getX() - minX) / width, 0f, 1f);
        float y = clamp((view.getY() - minY) / height, 0f, 1f);
        getSharedPreferences("ui_preferences", MODE_PRIVATE).edit()
                .putFloat("floating_x", x)
                .putFloat("floating_y", y)
                .apply();
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private LinearLayout createDrawer() {
        LinearLayout drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setElevation(dp(18));
        drawer.setPadding(dp(20), dp(24), dp(16), dp(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(250, 251, 253));
        background.setCornerRadii(new float[]{0, 0, dp(22), dp(22), dp(22), dp(22), 0, 0});
        drawer.setBackground(background);

        TextView brand = new TextView(this);
        brand.setText(R.string.app_name);
        brand.setTextColor(Color.rgb(15, 84, 140));
        brand.setTextSize(27);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        drawer.addView(brand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        TextView description = new TextView(this);
        description.setText("Outlook 网页与离线邮件");
        description.setTextColor(Color.rgb(96, 104, 112));
        description.setTextSize(13);
        drawer.addView(description, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        statusText = new TextView(this);
        statusText.setText("正在启动…");
        statusText.setTextColor(Color.rgb(15, 108, 189));
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable statusBackground = new GradientDrawable();
        statusBackground.setColor(Color.rgb(229, 242, 252));
        statusBackground.setCornerRadius(dp(12));
        statusText.setBackground(statusBackground);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        statusParams.setMargins(0, dp(8), 0, dp(12));
        drawer.addView(statusText, statusParams);

        drawer.addView(createAutoScrollControl());
        drawer.addView(drawerItem("本地邮件", "阅读已经保存的邮件", view -> {
            closeDrawer();
            startActivity(new Intent(this, ArchiveActivity.class));
        }));
        drawer.addView(drawerItem("刷新 Outlook", "重新连接并继续同步", view -> {
            closeDrawer();
            webView.reload();
        }));
        drawer.addView(drawerItem("缓存当前邮件", "页面适配器手动回退", view -> {
            closeDrawer();
            captureCurrentMessage(true);
        }));
        drawer.addView(drawerItem("Outlook 首页", "返回收件箱入口", view -> {
            closeDrawer();
            webView.loadUrl(OUTLOOK_URL);
        }));
        drawer.addView(drawerItem("网页后退", "返回上一个网页页面", view -> {
            closeDrawer();
            if (webView.canGoBack()) webView.goBack();
        }));

        View spacer = new View(this);
        drawer.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        TextView hint = new TextView(this);
        hint.setText("点击遮罩或按返回键收起");
        hint.setTextColor(Color.rgb(130, 136, 142));
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        drawer.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        return drawer;
    }

    private View createAutoScrollControl() {
        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setPadding(dp(14), dp(7), dp(8), dp(7));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(241, 245, 248));
        background.setCornerRadius(dp(12));
        control.setBackground(background);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("自动加载历史邮件");
        title.setTextColor(Color.rgb(35, 39, 43));
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView subtitle = new TextView(this);
        subtitle.setText("仅滚动 Outlook 邮件列表");
        subtitle.setTextColor(Color.rgb(103, 110, 118));
        subtitle.setTextSize(11);
        labels.addView(title);
        labels.addView(subtitle);
        control.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        autoScrollEnabled = getSharedPreferences("ui_preferences", MODE_PRIVATE)
                .getBoolean("auto_scroll_enabled", false);
        autoScrollSwitch = new Switch(this);
        autoScrollSwitch.setContentDescription("自动向下滚动 Outlook 邮件列表");
        autoScrollSwitch.setChecked(autoScrollEnabled);
        autoScrollSwitch.setOnCheckedChangeListener((button, checked) ->
                setAutoScrollEnabled(checked, true));
        control.addView(autoScrollSwitch, new LinearLayout.LayoutParams(dp(54), dp(48)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66));
        params.setMargins(0, 0, 0, dp(10));
        control.setLayoutParams(params);
        return control;
    }

    private View drawerItem(String title, String subtitle, View.OnClickListener listener) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(14), dp(8), dp(10), dp(8));
        item.setBackgroundResource(android.R.drawable.list_selector_background);
        item.setOnClickListener(listener);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.rgb(35, 39, 43));
        titleView.setTextSize(16);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        item.addView(titleView);
        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(Color.rgb(110, 116, 122));
        subtitleView.setTextSize(12);
        item.addView(subtitleView);
        item.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));
        return item;
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        lastBackPressAt = 0;
        floatingButton.setVisibility(View.GONE);
        drawerScrim.setVisibility(View.VISIBLE);
        drawerScrim.animate().alpha(0.32f).setDuration(180).start();
        appDrawer.animate().setListener(null).translationX(0).setDuration(220).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawerScrim.animate().alpha(0f).setDuration(180).start();
        appDrawer.animate().translationX(-appDrawer.getWidth()).setDuration(220)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!drawerOpen) {
                            drawerScrim.setVisibility(View.GONE);
                            floatingButton.setVisibility(View.VISIBLE);
                        }
                        appDrawer.animate().setListener(null);
                    }
                }).start();
    }

    private void setAutoScrollEnabled(boolean enabled, boolean notifyUser) {
        autoScrollEnabled = enabled;
        getSharedPreferences("ui_preferences", MODE_PRIVATE).edit()
                .putBoolean("auto_scroll_enabled", enabled)
                .apply();
        handler.removeCallbacks(autoScroll);
        if (enabled && activityVisible) handler.post(autoScroll);
        if (notifyUser) {
            toast(enabled
                    ? "已开启，关闭菜单后自动加载历史邮件"
                    : "已关闭自动滚动");
        }
    }

    private boolean isOutlookMailListPage() {
        String currentUrl = webView == null ? null : webView.getUrl();
        if (!isOutlookMailOrigin(currentUrl)) return false;
        String path = Uri.parse(currentUrl).getPath();
        if (path == null) return false;
        path = path.toLowerCase(Locale.ROOT).replaceAll("/+$", "");
        if (path.equals("/mail") || path.isEmpty()) return true;
        return path.startsWith("/mail/") && path.split("/").length == 3;
    }

    private void scrollOutlookMailList() {
        if (!activityVisible || drawerOpen || !isOutlookMailListPage()
                || SystemClock.elapsedRealtime() < autoScrollPausedUntil) return;
        webView.evaluateJavascript(CaptureScript.SCROLL_MAIL_LIST, ignored -> {});
    }

    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        installStructuredCapture();
        webView.setWebViewClient(new BrowserClient());
        webView.setWebChromeClient(new BrowserChromeClient());
        webView.setOnTouchListener((view, event) -> {
            if (autoScrollEnabled && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                autoScrollPausedUntil = SystemClock.elapsedRealtime() + MANUAL_SCROLL_PAUSE_MS;
            }
            return false;
        });
    }

    private void installStructuredCapture() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            structuredCaptureEnabled = false;
            return;
        }
        Set<String> origins = Set.of(
                "https://outlook.office.com",
                "https://outlook.office365.com",
                "https://outlook.live.com"
        );
        WebViewCompat.addWebMessageListener(
                webView,
                "reOutlookBridge",
                origins,
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (!isMainFrame || !isOutlookMailOrigin(sourceOrigin.toString())) return;
                    handleBridgeMessage(message, replyProxy);
                }
        );
        WebViewCompat.addDocumentStartJavaScript(webView, CaptureScript.OWA_SERVICE_RESPONSES, origins);
        structuredCaptureEnabled = true;
    }

    private void handleBridgeMessage(WebMessageCompat message, JavaScriptReplyProxy replyProxy) {
        String payload = message.getData();
        if (payload == null || payload.length() > 1_800_000) return;
        cacheWriter.execute(() -> {
            try {
                JSONObject envelope = new JSONObject(payload);
                String type = envelope.optString("type");
                String accountHint = envelope.optString("accountHint");
                if (!accountHint.isBlank() && accountHint.length() <= 512) {
                    if ("account".equals(type)) {
                        database.activateAccount(
                                accountHint, envelope.optString("previousAccountHint"));
                    } else {
                        database.activateAccount(accountHint);
                    }
                }
                if ("account".equals(type)) {
                    runOnUiThread(() -> statusText.setText(
                            getString(R.string.local_mail_count, database.count())));
                } else if ("mail".equals(type)) {
                    boolean saved = database.upsert(
                            envelope.optString("identity"),
                            envelope.optString("subject"),
                            envelope.optString("sender"),
                            envelope.optString("receivedAt"),
                            envelope.optString("bodyHtml"),
                            envelope.optString("bodyText"),
                            envelope.optString("sourceUrl")
                    );
                    if (saved) runOnUiThread(() -> statusText.setText(
                            getString(R.string.local_mail_count, database.count())));
                } else if ("conversationComplete".equals(type)) {
                    database.markConversationSynced(
                            envelope.optString("conversationId"),
                            envelope.optString("revision"));
                } else if ("candidates".equals(type)) {
                    JSONArray values = envelope.optJSONArray("candidates");
                    if (values == null) values = envelope.optJSONArray("ids");
                    if (values == null) return;
                    List<ConversationCandidate> candidates = new ArrayList<>();
                    for (int index = 0; index < values.length() && index < 200; index++) {
                        Object value = values.opt(index);
                        String id;
                        String revision;
                        if (value instanceof JSONObject) {
                            JSONObject candidate = (JSONObject) value;
                            id = candidate.optString("id");
                            revision = candidate.optString("revision");
                        } else {
                            id = values.optString(index);
                            revision = "";
                        }
                        if (!id.isBlank() && id.length() <= 4096 && revision.length() <= 8192) {
                            candidates.add(new ConversationCandidate(id, revision));
                        }
                    }
                    List<String> missing = database.conversationIdsNeedingSync(candidates);
                    JSONObject decision = new JSONObject();
                    decision.put("type", "backfillDecision");
                    decision.put("token", envelope.optString("token"));
                    decision.put("missing", new JSONArray(missing));
                    runOnUiThread(() -> {
                        try {
                            replyProxy.postMessage(decision.toString());
                        } catch (Exception ignored) {
                            // The page may have navigated before the database query completed.
                        }
                    });
                }
            } catch (Exception ignored) {
                // Outlook response schemas are expected to change; the DOM fallback remains available.
            }
        });
    }

    private void captureCurrentMessage(boolean userRequested) {
        if (!activityVisible || webView == null) return;
        String currentUrl = webView.getUrl();
        if (!isOutlookMailOrigin(currentUrl)) {
            if (userRequested) toast("请先打开 Outlook 邮件阅读页");
            return;
        }
        webView.evaluateJavascript(CaptureScript.CURRENT_MESSAGE, encodedResult -> {
            try {
                if (encodedResult == null || "null".equals(encodedResult)) {
                    if (userRequested) toast("当前没有识别到已打开的邮件");
                    return;
                }
                Object decoded = new JSONTokener(encodedResult).nextValue();
                if (!(decoded instanceof String) || ((String) decoded).isBlank()) {
                    if (userRequested) toast("当前没有识别到已打开的邮件");
                    return;
                }
                JSONObject mail = new JSONObject((String) decoded);
                boolean saved = database.upsert(
                        mail.optString("identity", mail.optString("sourceUrl")),
                        mail.optString("subject"),
                        mail.optString("sender"),
                        mail.optString("receivedAt"),
                        mail.optString("bodyHtml"),
                        mail.optString("bodyText"),
                        mail.optString("sourceUrl")
                );
                if (userRequested) {
                    toast(saved ? "邮件已缓存" : "缓存失败");
                }
                if (saved) statusText.setText(getString(R.string.local_mail_count, database.count()));
            } catch (Exception error) {
                if (userRequested) toast("页面适配器解析失败");
            }
        });
    }

    private static boolean isOutlookMailOrigin(String value) {
        if (value == null) return false;
        String host = Uri.parse(value).getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("outlook.office.com") || host.endsWith(".outlook.office.com")
                || host.equals("outlook.office365.com") || host.endsWith(".outlook.office365.com")
                || host.equals("outlook.live.com") || host.endsWith(".outlook.live.com");
    }

    private boolean isViewingOutlookMessage() {
        String currentUrl = webView == null ? null : webView.getUrl();
        if (!isOutlookMailOrigin(currentUrl)) return false;
        String path = Uri.parse(currentUrl).getPath();
        if (path == null) return false;
        path = path.toLowerCase(Locale.ROOT);
        return path.startsWith("/mail/")
                && (path.contains("/id/") || path.contains("/deeplink/read"));
    }

    private boolean shouldNavigateWebHistory() {
        if (webView == null || !webView.canGoBack()) return false;
        String currentUrl = webView.getUrl();
        if (!isOutlookMailOrigin(currentUrl)) return true;
        String path = Uri.parse(currentUrl).getPath();
        if (path == null) return true;
        path = path.toLowerCase(Locale.ROOT).replaceAll("/+$", "");
        if (path.isEmpty() || path.equals("/mail")) return false;
        String[] segments = path.split("/");
        // /mail/inbox、/mail/sentitems 等单一文件夹路由属于邮箱列表顶层。
        return segments.length > 3;
    }

    private void handleSystemBack() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        if (shouldNavigateWebHistory()) {
            lastBackPressAt = 0;
            webView.goBack();
            return;
        }
        if (isViewingOutlookMessage()) {
            lastBackPressAt = 0;
            webView.loadUrl(OUTLOOK_URL);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPressAt <= DOUBLE_BACK_INTERVAL_MS) {
            finish();
            return;
        }
        lastBackPressAt = now;
        toast("再按一次返回键退出");
    }

    @Override
    public void onBackPressed() {
        handleSystemBack();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityVisible = true;
        webView.onResume();
        handler.removeCallbacks(autoCapture);
        handler.postDelayed(autoCapture, AUTO_CAPTURE_INTERVAL_MS);
        handler.removeCallbacks(autoScroll);
        if (autoScrollEnabled) handler.postDelayed(autoScroll, AUTO_SCROLL_INTERVAL_MS);
        statusText.setText(getString(R.string.local_mail_count, database.count()));
    }

    @Override
    protected void onPause() {
        activityVisible = false;
        handler.removeCallbacks(autoCapture);
        handler.removeCallbacks(autoScroll);
        CookieManager.getInstance().flush();
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
        pendingFileChooser = null;
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        }
        cacheWriter.shutdown();
        database.close();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || pendingFileChooser == null) return;
        Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        pendingFileChooser.onReceiveValue(result);
        pendingFileChooser = null;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class BrowserClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            progressBar.setVisibility(View.VISIBLE);
            statusText.setText("正在连接…");
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progressBar.setVisibility(View.GONE);
            statusText.setText(getString(R.string.local_mail_count, database.count()));
            if (!structuredCaptureEnabled) {
                handler.postDelayed(() -> captureCurrentMessage(false), 1_500L);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException error) {
                toast("没有能够打开此链接的应用");
            }
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                statusText.setText("页面连接失败，可查看本地邮件");
                progressBar.setVisibility(View.GONE);
            }
        }
    }

    private final class BrowserChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public boolean onShowFileChooser(
                WebView view,
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams
        ) {
            if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
            pendingFileChooser = filePathCallback;
            try {
                startActivityForResult(fileChooserParams.createIntent(), FILE_CHOOSER_REQUEST);
                return true;
            } catch (ActivityNotFoundException error) {
                pendingFileChooser = null;
                toast("没有可用的文件选择器");
                return false;
            }
        }
    }
}
