package io.github.reoutlook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** User-owned website permission policy; administrator commands cannot create grants here. */
@SuppressLint("SetTextI18n")
public final class ReBrowserSitePermissionsActivity extends Activity {
    private ReBrowserSitePermissions permissions;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissions = new ReBrowserSitePermissions(this);
        showContent();
    }

    private void showContent() {
        ScrollView root = new ScrollView(this);
        root.setFillViewport(true);
        WindowStyling.apply(this, root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(),
                    0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(32));
        content.setBackgroundColor(Color.rgb(247, 248, 252));
        root.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("网站权限管理", 26, Color.rgb(31, 36, 45));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title, matchWrap());
        TextView explanation = text(
                "启用表示网站可以提出申请，不代表自动授权。授权按总标签页 Profile 与 HTTPS 网站分别隔离。",
                13, Color.rgb(93, 100, 111));
        LinearLayout.LayoutParams explanationParams = matchWrap();
        explanationParams.setMargins(0, dp(8), 0, dp(16));
        content.addView(explanation, explanationParams);

        content.addView(section("需要网站确认"));
        content.addView(permissionSwitch("剪贴板", "默认开启；每站允许 5 分钟或 15 天",
                ReBrowserSitePermissions.CLIPBOARD));
        content.addView(permissionSwitch("模糊定位", "默认开启；每站允许 5 分钟或 15 天",
                ReBrowserSitePermissions.APPROXIMATE_LOCATION));
        content.addView(permissionSwitch("麦克风", "默认关闭；每站仅本次或允许 3 小时",
                ReBrowserSitePermissions.MICROPHONE));
        content.addView(permissionSwitch("摄像头", "默认关闭；每站仅本次或允许 3 小时",
                ReBrowserSitePermissions.CAMERA));
        content.addView(permissionSwitch("高精度定位", "默认关闭；每站仅本次或允许 3 小时",
                ReBrowserSitePermissions.PRECISE_LOCATION));

        content.addView(section("永久禁止"));
        content.addView(fixedPolicy("网站通知", "永久禁止，不提供申请入口"));
        content.addView(fixedPolicy("后台推送", "永久禁止，不允许网页后台保活"));
        content.addView(fixedPolicy("网页运动传感器", "永久禁止陀螺仪、加速度计和方向传感器"));

        content.addView(section("应用后台策略"));
        content.addView(backgroundSwitch());

        content.addView(section("有效的网站授权"));
        List<ReBrowserSitePermissions.Grant> grants = permissions.grants(
                System.currentTimeMillis());
        if (grants.isEmpty()) {
            TextView empty = text("目前没有有效的跨请求授权。单次授权不会保存。",
                    13, Color.rgb(101, 107, 117));
            empty.setPadding(dp(4), dp(8), dp(4), dp(12));
            content.addView(empty, matchWrap());
        } else {
            for (ReBrowserSitePermissions.Grant grant : grants) content.addView(grantRow(grant));
            Button clear = new Button(this);
            clear.setText("清除全部网站授权");
            clear.setOnClickListener(view -> {
                permissions.clearAll();
                showContent();
            });
            content.addView(clear, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        }
        setContentView(root);
    }

    private LinearLayout permissionSwitch(String title, String subtitle, String permission) {
        LinearLayout row = settingRow(title, subtitle);
        Switch toggle = new Switch(this);
        toggle.setChecked(permissions.capabilityEnabled(permission));
        toggle.setContentDescription(title);
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            permissions.setCapabilityEnabled(permission, enabled);
            if (!enabled) android.widget.Toast.makeText(this,
                    "已立即禁用并清除“" + title + "”的网站授权",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        row.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(52)));
        return row;
    }

    private LinearLayout backgroundSwitch() {
        LinearLayout row = settingRow("允许后台运行",
                "关闭后网页运行时被冻结；系统 DownloadManager 下载仍可继续");
        Switch toggle = new Switch(this);
        toggle.setChecked(permissions.backgroundRuntimeEnabled());
        toggle.setOnCheckedChangeListener((button, enabled) ->
                permissions.setBackgroundRuntimeEnabled(enabled));
        row.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(52)));
        return row;
    }

    private LinearLayout fixedPolicy(String title, String subtitle) {
        LinearLayout row = settingRow(title, subtitle);
        TextView denied = text("已禁止", 13, Color.rgb(150, 49, 55));
        denied.setGravity(Gravity.CENTER);
        denied.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(denied, new LinearLayout.LayoutParams(dp(64), dp(52)));
        return row;
    }

    private LinearLayout grantRow(ReBrowserSitePermissions.Grant grant) {
        String shortProfile = grant.profileName.substring(
                Math.max(0, grant.profileName.length() - 8));
        String expiry = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(grant.expiresAt));
        LinearLayout row = settingRow(permissionLabel(grant.permission) + " · " + grant.origin,
                "Profile …" + shortProfile + " · 到期 " + expiry);
        Button revoke = new Button(this);
        revoke.setText("撤销");
        revoke.setOnClickListener(view -> {
            permissions.revoke(grant.profileName, grant.origin, grant.permission);
            showContent();
        });
        row.addView(revoke, new LinearLayout.LayoutParams(dp(72), dp(48)));
        return row;
    }

    private LinearLayout settingRow(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(7), 0, dp(7));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, Color.rgb(35, 40, 49)), matchWrap());
        labels.addView(text(subtitle, 12, Color.rgb(101, 107, 117)), matchWrap());
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView section(String value) {
        TextView title = text(value, 16, Color.rgb(91, 70, 180));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(18), 0, dp(8));
        return title;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static String permissionLabel(String permission) {
        switch (permission) {
            case ReBrowserSitePermissions.CAMERA: return "摄像头";
            case ReBrowserSitePermissions.MICROPHONE: return "麦克风";
            case ReBrowserSitePermissions.PRECISE_LOCATION: return "高精度定位";
            case ReBrowserSitePermissions.APPROXIMATE_LOCATION: return "模糊定位";
            default: return "剪贴板";
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
