package io.github.reoutlook;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

/** One-use ADB authorization with either an administrator signature or owner credential. */
final class ReBrowserAdminAuthorizer {
    static final String OP_OPEN_URL = ReBrowserAdminProtocol.OP_OPEN_URL;
    static final String OP_NEW_WORKSPACE = ReBrowserAdminProtocol.OP_NEW_WORKSPACE;
    static final String OP_NEW_CHILD_TAB = ReBrowserAdminProtocol.OP_NEW_CHILD_TAB;
    static final String OP_SHOW_WORKSPACES = ReBrowserAdminProtocol.OP_SHOW_WORKSPACES;
    static final String OP_SHOW_CHILD_TABS = ReBrowserAdminProtocol.OP_SHOW_CHILD_TABS;
    static final String OP_OPEN_SETTINGS = ReBrowserAdminProtocol.OP_OPEN_SETTINGS;
    static final String OP_SWITCH_TO_OUTLOOK = ReBrowserAdminProtocol.OP_SWITCH_TO_OUTLOOK;
    static final String OP_GET_STATE = ReBrowserAdminProtocol.OP_GET_STATE;
    static final String OP_SET_HOME = ReBrowserAdminProtocol.OP_SET_HOME;
    private static final String PREFERENCES = "rebrowser_admin_state_v1";
    private static final String INSTALLATION_ID = "installation_id";
    private static final String PENDING_CHALLENGE = "pending_challenge";
    private static final long CHALLENGE_LIFETIME_MS = 3 * 60_000L;

    private ReBrowserAdminAuthorizer() {}

    static String createChallenge(Context context, String encodedRequest) throws Exception {
        JSONObject request = ReBrowserAdminProtocol.prepareExternalRequest(
                decodeRequest(encodedRequest));
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
        payload.put("version", ReBrowserAdminProtocol.VERSION);
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
        ReBrowserAdminProtocol.recordStatus(context, request, "challenge-created",
                "", "", null, null);
        return challenge;
    }

    static JSONObject inspectPending(Context context, String challenge) throws Exception {
        requirePending(context, challenge);
        return validatePayload(challenge).getJSONObject("request");
    }

    static JSONObject authorizeWithSignature(
            Context context,
            String challenge,
            String signatureBase64,
            String keyId
    ) throws Exception {
        JSONObject request = inspectPending(context, challenge);
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            throw new SecurityException("Missing administrator signature");
        }
        String verifiedKeyId = ReBrowserAdminKeys.verify(keyId, challenge, signatureBase64);
        if (!consume(context, challenge)) throw new SecurityException("Challenge already consumed");
        request.put("_authentication", "administrator-key");
        request.put("_keyId", verifiedKeyId);
        ReBrowserAdminProtocol.recordStatus(context, request, "authorized",
                "administrator-key", verifiedKeyId, null, null);
        return request;
    }

    static JSONObject authorizeWithOwnerCredential(Context context, String challenge) throws Exception {
        JSONObject request = inspectPending(context, challenge);
        if (!ReBrowserAdminProtocol.ownerCredentialAllowed(request)) {
            throw new SecurityException("This level requires an administrator root key");
        }
        if (!consume(context, challenge)) throw new SecurityException("Challenge already consumed");
        request.put("_authentication", "device-credential");
        ReBrowserAdminProtocol.recordStatus(context, request, "authorized",
                "device-credential", "", null, null);
        return request;
    }

    static void clearPendingChallenge(Context context) {
        preferences(context).edit().remove(PENDING_CHALLENGE).apply();
    }

    static String lastResult(Context context) {
        return ReBrowserAdminProtocol.lastResult(context);
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
        if (payload.optInt("version") != ReBrowserAdminProtocol.VERSION
                || payload.optLong("expiresAt") < System.currentTimeMillis()) {
            throw new SecurityException("Challenge expired or unsupported");
        }
        ReBrowserAdminProtocol.prepareRequest(payload.getJSONObject("request"));
        return payload;
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
