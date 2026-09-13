package io.github.reoutlook;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** One-use ADB authorization with either an administrator signature or owner credential. */
final class ReBrowserAdminAuthorizer {
    static final String OP_OPEN_URL = "OPEN_URL";
    static final String OP_NEW_WORKSPACE = "NEW_WORKSPACE";
    static final String OP_NEW_CHILD_TAB = "NEW_CHILD_TAB";
    static final String OP_SHOW_WORKSPACES = "SHOW_WORKSPACES";
    static final String OP_SHOW_CHILD_TABS = "SHOW_CHILD_TABS";
    static final String OP_OPEN_SETTINGS = "OPEN_SETTINGS";
    static final String OP_SWITCH_TO_OUTLOOK = "SWITCH_TO_OUTLOOK";
    static final String OP_GET_STATE = "GET_STATE";
    static final String OP_SET_HOME = "SET_HOME";

    private static final Set<String> OPERATIONS = new HashSet<>(Arrays.asList(
            OP_OPEN_URL,
            OP_NEW_WORKSPACE,
            OP_NEW_CHILD_TAB,
            OP_SHOW_WORKSPACES,
            OP_SHOW_CHILD_TABS,
            OP_OPEN_SETTINGS,
            OP_SWITCH_TO_OUTLOOK,
            OP_GET_STATE,
            OP_SET_HOME));
    private static final String PREFERENCES = "rebrowser_admin_state_v1";
    private static final String INSTALLATION_ID = "installation_id";
    private static final String PENDING_CHALLENGE = "pending_challenge";
    private static final String LAST_RESULT = "last_result";
    private static final long CHALLENGE_LIFETIME_MS = 3 * 60_000L;

    private ReBrowserAdminAuthorizer() {}

    static String createChallenge(Context context, String encodedRequest) throws Exception {
        JSONObject request = validateRequest(decodeRequest(encodedRequest));
        SharedPreferences preferences = preferences(context);
        String installationId = preferences.getString(INSTALLATION_ID, "");
        if (installationId.isBlank()) {
            installationId = UUID.randomUUID().toString();
            preferences.edit().putString(INSTALLATION_ID, installationId).apply();
        }
        byte[] nonce = new byte[24];
        new SecureRandom().nextBytes(nonce);
        long now = System.currentTimeMillis();
        JSONObject payload = new JSONObject();
        payload.put("version", 1);
        payload.put("request", request);
        payload.put("nonce", Base64.encodeToString(
                nonce, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
        payload.put("issuedAt", now);
        payload.put("expiresAt", now + CHALLENGE_LIFETIME_MS);
        payload.put("installationId", installationId);
        String challenge = Base64.encodeToString(
                payload.toString().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        if (!preferences.edit().putString(PENDING_CHALLENGE, challenge).commit()) {
            throw new IllegalStateException("Cannot persist challenge");
        }
        return challenge;
    }

    static JSONObject inspectPending(Context context, String challenge) throws Exception {
        requirePending(context, challenge);
        return validatePayload(challenge).getJSONObject("request");
    }

    static JSONObject authorizeWithSignature(
            Context context,
            String challenge,
            String signatureBase64
    ) throws Exception {
        JSONObject request = inspectPending(context, challenge);
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            throw new SecurityException("Missing administrator signature");
        }
        byte[] publicBytes = Base64.decode(
                MaintenanceKeys.SIGNING_RSA_X509_BASE64, Base64.DEFAULT);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(publicBytes)));
        verifier.update(challenge.getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(Base64.decode(signatureBase64, Base64.DEFAULT))) {
            throw new SecurityException("Invalid administrator signature");
        }
        if (!consume(context, challenge)) throw new SecurityException("Challenge already consumed");
        return request;
    }

    static JSONObject authorizeWithOwnerCredential(Context context, String challenge) throws Exception {
        JSONObject request = inspectPending(context, challenge);
        if (!consume(context, challenge)) throw new SecurityException("Challenge already consumed");
        return request;
    }

    static void clearPendingChallenge(Context context) {
        preferences(context).edit().remove(PENDING_CHALLENGE).apply();
    }

    static void recordResult(Context context, String result) {
        preferences(context).edit().putString(LAST_RESULT, result == null ? "" : result).apply();
    }

    static String lastResult(Context context) {
        return preferences(context).getString(LAST_RESULT, "");
    }

    private static JSONObject decodeRequest(String encodedRequest) throws Exception {
        if (encodedRequest == null || encodedRequest.isBlank()) {
            throw new SecurityException("Missing request");
        }
        byte[] json = Base64.decode(encodedRequest,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        if (json.length > 12_000) throw new SecurityException("Request is too large");
        return new JSONObject(new String(json, StandardCharsets.UTF_8));
    }

    private static JSONObject validatePayload(String challenge) throws Exception {
        byte[] json = Base64.decode(challenge,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        JSONObject payload = new JSONObject(new String(json, StandardCharsets.UTF_8));
        if (payload.optInt("version") != 1
                || payload.optLong("expiresAt") < System.currentTimeMillis()) {
            throw new SecurityException("Challenge expired or unsupported");
        }
        validateRequest(payload.getJSONObject("request"));
        return payload;
    }

    private static JSONObject validateRequest(JSONObject request) throws Exception {
        String operation = request.optString("operation", "");
        if (!OPERATIONS.contains(operation)) throw new SecurityException("Unsupported operation");
        if (OP_OPEN_URL.equals(operation) || OP_SET_HOME.equals(operation)) {
            String url = request.optString("url", "");
            Uri parsed = Uri.parse(url);
            String scheme = parsed.getScheme();
            if (url.length() > 8192 || parsed.getHost() == null
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                throw new SecurityException("Only a valid HTTP(S) URL is allowed");
            }
        }
        return request;
    }

    private static void requirePending(Context context, String challenge) {
        if (challenge == null) throw new SecurityException("Missing challenge");
        String pending = preferences(context).getString(PENDING_CHALLENGE, "");
        if (!constantTimeEqual(pending, challenge)) {
            throw new SecurityException("Challenge is not pending");
        }
    }

    private static boolean consume(Context context, String challenge) {
        SharedPreferences preferences = preferences(context);
        String pending = preferences.getString(PENDING_CHALLENGE, "");
        return constantTimeEqual(pending, challenge)
                && preferences.edit().remove(PENDING_CHALLENGE).commit();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static boolean constantTimeEqual(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
