package io.github.reoutlook;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground owner-consent screen. It has no launcher or in-app entry point. */
public final class MaintenanceActivity extends Activity {
    public static final String EXTRA_CHALLENGE = "challenge";
    public static final String EXTRA_SIGNATURE = "signature";
    private static final int CONFIRM_DEVICE_CREDENTIAL = 7301;
    private static final int CREATE_EXPORT = 7302;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String challenge;
    private String operation;
    private TextView status;
    private boolean awaitingResult;
    private boolean exporting;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getWindow().setHideOverlayWindows(true);
        }
        WindowStyling.apply(this, createAndSetContent());
        try {
            challenge = getIntent().getStringExtra(EXTRA_CHALLENGE);
            JSONObject request = MaintenanceAuthorizer.verifySignedChallenge(
                    this, challenge, getIntent().getStringExtra(EXTRA_SIGNATURE));
            operation = request.getString("operation");
            showVerifiedRequest();
        } catch (Exception error) {
            status.setText("管理员授权无效或已经过期。\n\n" + error.getMessage());
        }
    }

    private LinearLayout createAndSetContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(42), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(248, 250, 252));

        TextView title = new TextView(this);
        title.setText("ReOutlook 管理员维护");
        title.setTextSize(25);
        title.setTextColor(Color.rgb(145, 45, 35));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText("正在验证管理员签名…");
        status.setTextSize(16);
        status.setTextColor(Color.rgb(35, 39, 43));
        status.setPadding(0, dp(28), 0, dp(24));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        return root;
    }

    private void showVerifiedRequest() {
        String identity;
        try (MailDatabase database = new MailDatabase(this)) {
            identity = database.activeAccountIdentity();
        }
        status.setText("管理员签名已经验证。\n\n请求操作：导出当前账号的全部本地邮件" +
                "\n目标账号：" + maskIdentity(identity) +
                "\n\n导出文件由管理员公钥加密，持有管理员导出私钥的人可以读取其中的邮件。" +
                "\n\n如果你不允许本次操作，请立即取消。除非通过下方系统锁屏验证，否则不会导出任何数据。");

        LinearLayout root = (LinearLayout) status.getParent();
        Button approve = new Button(this);
        approve.setText("使用系统锁屏凭据确认");
        approve.setFilterTouchesWhenObscured(true);
        approve.setOnClickListener(view -> requestOwnerCredential());
        root.addView(approve, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        Button cancel = new Button(this);
        cancel.setText("拒绝并退出");
        cancel.setOnClickListener(view -> finish());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        cancelParams.setMargins(0, dp(10), 0, 0);
        root.addView(cancel, cancelParams);
    }

    private void requestOwnerCredential() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            status.setText("设备没有设置系统级锁屏密码，维护操作已拒绝。");
            return;
        }
        Intent confirmation = keyguard.createConfirmDeviceCredentialIntent(
                "确认管理员维护操作", "验证机主身份后才能导出本地邮件");
        if (confirmation == null) {
            status.setText("无法调用系统锁屏验证，维护操作已拒绝。");
            return;
        }
        awaitingResult = true;
        startActivityForResult(confirmation, CONFIRM_DEVICE_CREDENTIAL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        awaitingResult = false;
        if (requestCode == CONFIRM_DEVICE_CREDENTIAL) {
            if (resultCode != RESULT_OK
                    || !MaintenanceAuthorizer.consumeAfterOwnerAuthentication(this, challenge)) {
                status.setText("机主验证被取消，或者一次性授权已经过期。");
                return;
            }
            chooseExportDestination();
        } else if (requestCode == CREATE_EXPORT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                status.setText("已取消导出。此次一次性授权不能再次使用。");
                return;
            }
            exportTo(data.getData());
        }
    }

    private void chooseExportDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        intent.putExtra(Intent.EXTRA_TITLE, "reoutlook-admin-" + stamp + ".reoutlook-admin");
        awaitingResult = true;
        startActivityForResult(intent, CREATE_EXPORT);
    }

    private void exportTo(Uri destination) {
        exporting = true;
        status.setText("正在创建加密导出…\n请保持此界面在前台。");
        worker.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IllegalStateException("Cannot open destination");
                MaintenanceExporter.exportActiveAccount(this, output);
                recordAudit("success");
                runOnUiThread(() -> {
                    exporting = false;
                    status.setText("加密导出已经完成。\n此次管理员授权已失效。");
                    Toast.makeText(this, "维护导出完成", Toast.LENGTH_LONG).show();
                    status.postDelayed(this::finish, 1800);
                });
            } catch (Exception error) {
                recordAudit("failed:" + error.getClass().getSimpleName());
                runOnUiThread(() -> {
                    exporting = false;
                    status.setText("导出失败：" + error.getMessage());
                });
            }
        });
    }

    private void recordAudit(String result) {
        getSharedPreferences("maintenance_audit", MODE_PRIVATE).edit()
                .putString("last_operation", operation)
                .putLong("last_time", System.currentTimeMillis())
                .putString("last_result", result)
                .apply();
    }

    private static String maskIdentity(String value) {
        if (value == null || value.isBlank()) return "尚未识别的旧账号";
        int at = value.indexOf('@');
        if (at <= 1) return "***";
        return value.substring(0, 1) + "***" + value.substring(at);
    }

    @Override
    public void onBackPressed() {
        if (!exporting) finish();
    }

    @Override
    protected void onDestroy() {
        if (!awaitingResult && !exporting) MaintenanceAuthorizer.clearPendingChallenge(this);
        worker.shutdown();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
