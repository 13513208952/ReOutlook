package io.github.reoutlook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Browser-global settings. Website state remains inside each workspace's named Profile. */
@SuppressLint("SetTextI18n")
public final class ReBrowserSettingsActivity extends Activity {
    private ReBrowserPreferences preferences;
    private EditText homeUrl;
    private Spinner searchEngine;
    private LinearLayout videoOrientationSelector;
    private TextView videoLandscape;
    private TextView videoAuto;
    private TextView videoPortrait;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new ReBrowserPreferences(this);
        ScrollView root = new ScrollView(this);
        root.setFillViewport(true);
        WindowStyling.apply(this, root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(),
                    0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(28));
        content.setBackgroundColor(Color.rgb(247, 248, 252));
        root.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("ReBrowser 设置");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(31, 36, 45));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title, matchWrap());

        TextView scope = new TextView(this);
        scope.setText("这些设置由同一应用内的所有 ReBrowser 总标签页共享；网站登录状态仍按总标签页 Profile 隔离。");
        scope.setTextSize(13);
        scope.setTextColor(Color.rgb(93, 100, 111));
        LinearLayout.LayoutParams scopeParams = matchWrap();
        scopeParams.setMargins(0, dp(8), 0, dp(22));
        content.addView(scope, scopeParams);

        content.addView(sectionTitle("启动与搜索"));
        content.addView(fieldLabel("主页网址"));
        homeUrl = new EditText(this);
        homeUrl.setSingleLine(true);
        homeUrl.setText(preferences.homeUrl());
        homeUrl.setHint("https://cn.bing.com/");
        content.addView(homeUrl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        content.addView(fieldLabel("默认搜索引擎"));
        searchEngine = new Spinner(this);
        String[] labels = {"Bing", "百度", "DuckDuckGo", "Google"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, labels);
        searchEngine.setAdapter(adapter);
        String currentSearch = preferences.searchEngine();
        searchEngine.setSelection(ReBrowserPreferences.SEARCH_BAIDU.equals(currentSearch)
                ? 1 : ReBrowserPreferences.SEARCH_DUCKDUCKGO.equals(currentSearch)
                        ? 2 : ReBrowserPreferences.SEARCH_GOOGLE.equals(currentSearch) ? 3 : 0);
        content.addView(searchEngine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        content.addView(sectionTitle("网页行为"));
        content.addView(settingSwitch(
                "JavaScript",
                "关闭后部分网站将无法正常工作",
                preferences.javascriptEnabled(),
                preferences::setJavascriptEnabled));
        content.addView(settingSwitch(
                "允许第三方 Cookie",
                "按总标签页 Profile 分别保存；不会与 ReOutlook 共用",
                preferences.thirdPartyCookiesEnabled(),
                preferences::setThirdPartyCookiesEnabled));
        content.addView(settingSwitch(
                "桌面版网站",
                "新建或重新载入的页面使用桌面布局",
                preferences.desktopModeEnabled(),
                preferences::setDesktopModeEnabled));

        content.addView(sectionTitle("总标签页生命周期"));
        content.addView(settingSwitch(
                "允许强行提升为主标签页",
                "开启后，临时总标签页可跳过副级别直接提升为主；其他生命周期规则不变",
                preferences.forcePrimaryPromotionEnabled(),
                preferences::setForcePrimaryPromotionEnabled));

        content.addView(sectionTitle("视频播放器"));
        content.addView(settingSwitch(
                "覆写视频播放器界面方向",
                "关闭时根据当前全局方向状态自动选择视频方向策略",
                preferences.videoOrientationOverrideEnabled(),
                enabled -> {
                    preferences.setVideoOrientationOverrideEnabled(enabled);
                    updateVideoOrientationSelector();
                }));
        videoOrientationSelector = createVideoOrientationSelector();
        content.addView(videoOrientationSelector, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        updateVideoOrientationSelector();

        Button save = new Button(this);
        save.setText("保存并返回");
        save.setOnClickListener(view -> {
            saveSettings(true);
            finish();
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        saveParams.setMargins(0, dp(28), 0, 0);
        content.addView(save, saveParams);

        setContentView(root);
    }

    private TextView sectionTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(16);
        title.setTextColor(Color.rgb(91, 70, 180));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(18), 0, dp(8));
        return title;
    }

    private TextView fieldLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(13);
        label.setTextColor(Color.rgb(82, 88, 98));
        label.setPadding(0, dp(8), 0, 0);
        return label;
    }

    private LinearLayout createVideoOrientationSelector() {
        LinearLayout selector = new LinearLayout(this);
        selector.setOrientation(LinearLayout.HORIZONTAL);
        selector.setGravity(Gravity.CENTER);
        selector.setPadding(dp(3), dp(3), dp(3), dp(3));
        selector.setBackground(roundedBackground(Color.rgb(231, 233, 239), dp(14)));
        videoLandscape = videoOrientationChoice(
                "横屏锁定", ReBrowserPreferences.ORIENTATION_LANDSCAPE);
        videoAuto = videoOrientationChoice(
                "自动旋转", ReBrowserPreferences.VIDEO_ORIENTATION_AUTO);
        videoPortrait = videoOrientationChoice(
                "竖屏锁定", ReBrowserPreferences.ORIENTATION_PORTRAIT);
        selector.addView(videoLandscape);
        selector.addView(videoAuto);
        selector.addView(videoPortrait);
        return selector;
    }

    private TextView videoOrientationChoice(String label, String value) {
        TextView choice = new TextView(this);
        choice.setText(label);
        choice.setTextSize(14);
        choice.setGravity(Gravity.CENTER);
        choice.setOnClickListener(view -> {
            preferences.setVideoOrientation(value);
            updateVideoOrientationSelector();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
        params.setMargins(dp(2), 0, dp(2), 0);
        choice.setLayoutParams(params);
        return choice;
    }

    private void updateVideoOrientationSelector() {
        if (videoOrientationSelector == null) return;
        boolean enabled = preferences.videoOrientationOverrideEnabled();
        String selected = preferences.videoOrientation();
        videoOrientationSelector.setAlpha(enabled ? 1f : 0.45f);
        styleVideoOrientationChoice(videoLandscape, enabled,
                ReBrowserPreferences.ORIENTATION_LANDSCAPE.equals(selected));
        styleVideoOrientationChoice(videoAuto, enabled,
                ReBrowserPreferences.VIDEO_ORIENTATION_AUTO.equals(selected));
        styleVideoOrientationChoice(videoPortrait, enabled,
                ReBrowserPreferences.ORIENTATION_PORTRAIT.equals(selected));
    }

    private void styleVideoOrientationChoice(TextView choice, boolean enabled, boolean selected) {
        if (choice == null) return;
        choice.setEnabled(enabled);
        choice.setTextColor(selected && enabled ? Color.WHITE : Color.rgb(55, 59, 68));
        choice.setBackground(roundedBackground(
                selected && enabled ? Color.rgb(91, 70, 180) : Color.TRANSPARENT,
                dp(11)));
        choice.setContentDescription(choice.getText()
                + (selected ? "，已选择" : "")
                + (enabled ? "" : "，覆写未启用"));
    }

    private GradientDrawable roundedBackground(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        return background;
    }

    private LinearLayout settingSwitch(
            String title,
            String subtitle,
            boolean checked,
            java.util.function.Consumer<Boolean> listener
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), 0, dp(8));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTextColor(Color.rgb(35, 40, 49));
        labels.addView(titleView);
        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(12);
        subtitleView.setTextColor(Color.rgb(101, 107, 117));
        labels.addView(subtitleView);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, enabled) -> listener.accept(enabled));
        row.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(52)));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        return row;
    }

    @Override
    protected void onPause() {
        saveSettings(false);
        super.onPause();
    }

    private void saveSettings(boolean notify) {
        preferences.setHomeUrl(homeUrl.getText().toString());
        int selected = searchEngine.getSelectedItemPosition();
        preferences.setSearchEngine(selected == 1
                ? ReBrowserPreferences.SEARCH_BAIDU
                : selected == 2
                        ? ReBrowserPreferences.SEARCH_DUCKDUCKGO
                        : selected == 3
                                ? ReBrowserPreferences.SEARCH_GOOGLE
                                : ReBrowserPreferences.SEARCH_BING);
        if (notify) Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
