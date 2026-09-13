package io.github.reoutlook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
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
        scope.setText("这些设置由同一应用内的所有 ReBrowser 工作区共享；网站登录状态仍按总标签页 Profile 隔离。");
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
                "按工作区 Profile 分别保存；不会与 ReOutlook 共用",
                preferences.thirdPartyCookiesEnabled(),
                preferences::setThirdPartyCookiesEnabled));
        content.addView(settingSwitch(
                "桌面版网站",
                "新建或重新载入的页面使用桌面布局",
                preferences.desktopModeEnabled(),
                preferences::setDesktopModeEnabled));

        content.addView(sectionTitle("隐私与数据"));
        TextView privacy = new TextView(this);
        privacy.setText("总标签页之间隔离 Cookie、Web Storage、IndexedDB 和 Service Worker。"
                + "关闭总标签页会安排删除整个命名 Profile。清除浏览数据和站点权限管理将在后续版本加入。");
        privacy.setTextSize(14);
        privacy.setTextColor(Color.rgb(62, 68, 78));
        privacy.setLineSpacing(0, 1.15f);
        content.addView(privacy, matchWrap());

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
