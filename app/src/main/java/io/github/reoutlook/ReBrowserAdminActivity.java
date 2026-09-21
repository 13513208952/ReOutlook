package io.github.reoutlook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Foreground ADB authorization screen; it has no launcher or ordinary in-app entry point. */
@SuppressLint("SetTextI18n")
public final class ReBrowserAdminActivity extends Activity {
    static final String EXTRA_CHALLENGE = "challenge";
    static final String EXTRA_SIGNATURE = "signature";
    static final String EXTRA_KEY_ID = "keyId";
    private static final int CONFIRM_DEVICE_CREDENTIAL = 7401;

    private String challenge;
    private JSONObject pendingRequest;
    private TextView status;
    private boolean awaitingCredential;
    private boolean commandExecuted;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SystemBackDispatcher.register(this, () -> {
            if (!commandExecuted) finish();
        });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getWindow().setHideOverlayWindows(true);
        }
        WindowStyling.apply(this, createAndSetContent());
        challenge = getIntent().getStringExtra(EXTRA_CHALLENGE);
        String signature = getIntent().getStringExtra(EXTRA_SIGNATURE);
        String keyId = getIntent().getStringExtra(EXTRA_KEY_ID);
        try {
            if (signature != null && !signature.isBlank()) {
                pendingRequest = ReBrowserAdminAuthorizer.authorizeWithSignature(
                        this, challenge, signature, keyId);
                executeAuthorizedRequest();
            } else {
                pendingRequest = ReBrowserAdminAuthorizer.inspectPending(this, challenge);
                showOwnerAuthentication();
            }
        } catch (Exception error) {
            status.setText("ReBrowser 管理员授权无效或已经过期。\n\n" + error.getMessage());
            if (pendingRequest == null) {
                try {
                    pendingRequest = ReBrowserAdminAuthorizer.inspectPending(this, challenge);
                } catch (Exception ignored) {
                    // A malformed or non-pending challenge has no trusted request ID to report.
                }
            }
            recordFailure("authorization-failed:" + error.getClass().getSimpleName());
            ReBrowserAdminAuthorizer.clearPendingChallenge(this);
            commandExecuted = true;
        }
    }

    private LinearLayout createAndSetContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(42), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(248, 250, 252));

        TextView title = new TextView(this);
        title.setText("ReBrowser 管理员控制");
        title.setTextSize(25);
        title.setTextColor(Color.rgb(117, 70, 173));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText("正在验证一次性控制请求…");
        status.setTextSize(16);
        status.setTextColor(Color.rgb(35, 39, 43));
        status.setPadding(0, dp(28), 0, dp(24));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        return root;
    }

    private void showOwnerAuthentication() throws Exception {
        String operation = pendingRequest.getString("operation");
        int level = pendingRequest.optInt("authorizationLevel", 1);
        String target = pendingRequest.has("workspaceId")
                ? "\n总标签页：" + pendingRequest.optString("workspaceId") : "";
        if (pendingRequest.has("tabId")) {
            target += "\n子标签页：" + pendingRequest.optString("tabId");
        }
        if (pendingRequest.has("downloadId")) {
            target += "\n下载任务：" + pendingRequest.optString("downloadId");
        }
        status.setText("ADB 请求尚未获得管理员密钥签名。\n\n请求操作：" + operation
                + "\n授权级别：" + level + target
                + (level >= 2 ? "\n\n这是会改变或删除浏览器状态的操作。" : "")
                + (level >= 3
                        ? "\n\n第三级操作至少需要永久管理员根密钥，不能仅由机主确认。"
                        : "\n\n可以改用本机系统级锁屏凭据批准这一次操作。"
                                + "未通过验证时不会控制浏览器。"));
        LinearLayout root = (LinearLayout) status.getParent();
        if (level < 3) {
            Button approve = new Button(this);
            approve.setText("使用系统锁屏凭据确认");
            approve.setFilterTouchesWhenObscured(true);
            approve.setOnClickListener(view -> requestOwnerCredential());
            root.addView(approve, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        }
        Button cancel = new Button(this);
        cancel.setText("拒绝并退出");
        cancel.setOnClickListener(view -> {
            ReBrowserAdminProtocol.recordStatus(this, pendingRequest, "cancelled",
                    "device-credential", "", null, "owner-declined");
            ReBrowserAdminAuthorizer.clearPendingChallenge(this);
            commandExecuted = true;
            finish();
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        cancelParams.setMargins(0, dp(10), 0, 0);
        root.addView(cancel, cancelParams);
    }

    private void requestOwnerCredential() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            status.setText("设备没有系统级安全锁屏，控制操作已拒绝。");
            return;
        }
        Intent confirmation = keyguard.createConfirmDeviceCredentialIntent(
                "确认 ReBrowser 管理员控制", "验证机主身份以批准这一次浏览器操作");
        if (confirmation == null) {
            status.setText("无法调用系统锁屏验证，控制操作已拒绝。");
            return;
        }
        awaitingCredential = true;
        startActivityForResult(confirmation, CONFIRM_DEVICE_CREDENTIAL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CONFIRM_DEVICE_CREDENTIAL) return;
        awaitingCredential = false;
        if (resultCode != RESULT_OK) {
            status.setText("机主验证已取消。一次性请求未执行。");
            ReBrowserAdminProtocol.recordStatus(this, pendingRequest, "cancelled",
                    "device-credential", "", null, "device-credential-cancelled");
            ReBrowserAdminAuthorizer.clearPendingChallenge(this);
            commandExecuted = true;
            return;
        }
        try {
            pendingRequest = ReBrowserAdminAuthorizer.authorizeWithOwnerCredential(
                    this, challenge);
            executeAuthorizedRequest();
        } catch (Exception error) {
            status.setText("一次性请求已经失效：" + error.getMessage());
            recordFailure("authorization-failed:" + error.getClass().getSimpleName());
            ReBrowserAdminAuthorizer.clearPendingChallenge(this);
            commandExecuted = true;
        }
    }

    private void executeAuthorizedRequest() throws Exception {
        String operation = pendingRequest.getString("operation");
        commandExecuted = true;
        String authentication = pendingRequest.optString("_authentication");
        String keyId = pendingRequest.optString("_keyId");
        if (ReBrowserAdminProtocol.OP_SET_HOME.equals(operation)) {
            new ReBrowserPreferences(this).setHomeUrl(pendingRequest.getString("url"));
            ReBrowserAdminProtocol.recordStatus(this, pendingRequest, "completed",
                    authentication, keyId, new JSONObject().put("homeUpdated", true), null);
            status.setText("主页设置已更新。此次授权已经失效。");
            status.postDelayed(this::finish, 900);
            return;
        }
        if (ReBrowserAdminProtocol.OP_GET_AUDIT.equals(operation)) {
            JSONObject details = new JSONObject();
            details.put("entries", ReBrowserAdminProtocol.audit(this));
            ReBrowserAdminProtocol.recordStatus(this, pendingRequest, "completed",
                    authentication, keyId, details, null);
            status.setText("管理员审计快照已更新。此次授权已经失效。");
            status.postDelayed(this::finish, 500);
            return;
        }
        if (ReBrowserAdminProtocol.OP_SWITCH_TO_OUTLOOK.equals(operation)) {
            ReBrowserAdminProtocol.recordStatus(this, pendingRequest, "completed",
                    authentication, keyId, new JSONObject().put("mode", "ReOutlook"), null);
            ModeRouter.openOutlook(this);
            finish();
            return;
        }
        ReBrowserAdminProtocol.recordStatus(this, pendingRequest, "queued",
                authentication, keyId, null, null);
        Intent browser = new Intent(this, ReBrowserActivity.class)
                .setAction(ReBrowserAdminController.ACTION_ADMIN_COMMAND)
                .putExtra(ReBrowserAdminController.EXTRA_ADMIN_REQUEST,
                        pendingRequest.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(browser);
        finish();
    }

    private void recordFailure(String message) {
        if (pendingRequest == null) return;
        ReBrowserAdminProtocol.recordStatus(this, pendingRequest, "failed",
                pendingRequest.optString("_authentication"), pendingRequest.optString("_keyId"),
                null, message);
    }

    @Override
    protected void onDestroy() {
        if (!awaitingCredential && !commandExecuted) {
            if (pendingRequest != null) {
                ReBrowserAdminProtocol.recordStatus(this, pendingRequest, "cancelled",
                        "device-credential", "", null, "authorization-screen-closed");
            }
            ReBrowserAdminAuthorizer.clearPendingChallenge(this);
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
