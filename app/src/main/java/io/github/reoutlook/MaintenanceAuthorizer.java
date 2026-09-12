package io.github.reoutlook;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

final class MaintenanceAuthorizer {
    static final String OP_EXPORT_ACCOUNT = "EXPORT_ACCOUNT";
    private static final String PREFERENCES = "maintenance_state";
    private static final String INSTALLATION_ID = "installation_id";
    private static final String PENDING_CHALLENGE = "pending_challenge";
    private static final long CHALLENGE_LIFETIME_MS = 3 * 60_000L;

    private MaintenanceAuthorizer() {}

    static String createChallenge(Context context, String operation) throws Exception {
        if (!OP_EXPORT_ACCOUNT.equals(operation)) throw new SecurityException("Unsupported operation");
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
        String installationId = preferences.getString(INSTALLATION_ID, "");
        if (installationId.isBlank()) {
            installationId = UUID.randomUUID().toString();
            preferences.edit().putString(INSTALLATION_ID, installationId).apply();
        }
        byte[] nonce = new byte[24];
        new SecureRandom().nextBytes(nonce);
        long now = System.currentTimeMillis();
        String accountKey;
        try (MailDatabase database = new MailDatabase(context)) {
            accountKey = database.activeAccountKey();
        }
        JSONObject payload = new JSONObject();
        payload.put("version", 1);
        payload.put("operation", operation);
        payload.put("nonce", Base64.encodeToString(
                nonce, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
        payload.put("issuedAt", now);
        payload.put("expiresAt", now + CHALLENGE_LIFETIME_MS);
        payload.put("installationId", installationId);
        payload.put("accountKey", accountKey);
        String challenge = Base64.encodeToString(
                payload.toString().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        preferences.edit().putString(PENDING_CHALLENGE, challenge).commit();
        return challenge;
    }

    static JSONObject verifySignedChallenge(
            Context context,
            String challenge,
            String signatureBase64
    ) throws Exception {
        if (challenge == null || signatureBase64 == null) throw new SecurityException("Missing proof");
        String pending = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(PENDING_CHALLENGE, "");
        if (!MessageDigest.isEqual(
                pending.getBytes(StandardCharsets.UTF_8),
                challenge.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Challenge is not pending");
        }
        JSONObject payload = decodeChallenge(challenge);
        if (payload.optInt("version") != 1
                || !OP_EXPORT_ACCOUNT.equals(payload.optString("operation"))
                || payload.optLong("expiresAt") < System.currentTimeMillis()) {
            throw new SecurityException("Challenge expired or unsupported");
        }
        try (MailDatabase database = new MailDatabase(context)) {
            if (!constantTimeEqual(payload.optString("accountKey"), database.activeAccountKey())) {
                throw new SecurityException("Active account changed");
            }
        }
        byte[] publicBytes = Base64.decode(
                MaintenanceKeys.SIGNING_RSA_X509_BASE64, Base64.DEFAULT);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(publicBytes)));
        verifier.update(challenge.getBytes(StandardCharsets.UTF_8));
        byte[] signature = Base64.decode(signatureBase64, Base64.DEFAULT);
        if (!verifier.verify(signature)) throw new SecurityException("Invalid administrator signature");
        return payload;
    }

    static boolean consumeAfterOwnerAuthentication(Context context, String challenge) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
        String pending = preferences.getString(PENDING_CHALLENGE, "");
        if (!constantTimeEqual(pending, challenge)) return false;
        try {
            if (decodeChallenge(challenge).optLong("expiresAt") < System.currentTimeMillis()) {
                return false;
            }
        } catch (Exception ignored) {
            return false;
        }
        return preferences.edit().remove(PENDING_CHALLENGE).commit();
    }

    static void ensureEntryPointsEnabled(Context context) {
        PackageManager manager = context.getPackageManager();
        manager.setComponentEnabledSetting(
                new ComponentName(context, MaintenanceActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        manager.setComponentEnabledSetting(
                new ComponentName(context, MaintenanceProvider.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    static void clearPendingChallenge(Context context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove(PENDING_CHALLENGE)
                .apply();
    }

    private static JSONObject decodeChallenge(String challenge) throws Exception {
        byte[] json = Base64.decode(challenge,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return new JSONObject(new String(json, StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEqual(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
