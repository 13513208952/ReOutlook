package io.github.reoutlook;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/** Immutable public roots for ReBrowser administrator control. */
final class ReBrowserAdminKeys {
    static final String REOUTLOOK_ROOT_ID = "reoutlook-root-v1";
    static final String REBROWSER_ROOT_ID = "rebrowser-root-v1";
    private static final String REBROWSER_EC_X509_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7UvUBKa2X4Mlov8IdqnfwalvTudNbx2bHNFQDNoiZVTT0N5C89qqsueJkr1OtZMb8trItFvXtqWFn+m8xOW3QQ==";
    private static final String REOUTLOOK_FINGERPRINT =
            "0c045556f779ddc469cdf64c14b33f27904bf75fd48956111860a9f081be99fd";
    private static final String REBROWSER_FINGERPRINT =
            "38d674fb6eb66ed4efaf2360def9c023c81b18a594b3fcea127bc87338a0ef01";

    private ReBrowserAdminKeys() {}

    static String verify(String keyId, String challenge, String signatureBase64) throws Exception {
        String selected = keyId == null || keyId.isBlank() ? REOUTLOOK_ROOT_ID : keyId;
        byte[] encodedPublic;
        String keyAlgorithm;
        String signatureAlgorithm;
        if (REOUTLOOK_ROOT_ID.equals(selected)) {
            encodedPublic = Base64.decode(MaintenanceKeys.SIGNING_RSA_X509_BASE64, Base64.DEFAULT);
            keyAlgorithm = "RSA";
            signatureAlgorithm = "SHA256withRSA";
        } else if (REBROWSER_ROOT_ID.equals(selected)) {
            encodedPublic = Base64.decode(REBROWSER_EC_X509_BASE64, Base64.DEFAULT);
            keyAlgorithm = "EC";
            signatureAlgorithm = "SHA256withECDSA";
        } else {
            throw new SecurityException("Unknown or unavailable administrator key");
        }
        Signature verifier = Signature.getInstance(signatureAlgorithm);
        verifier.initVerify(KeyFactory.getInstance(keyAlgorithm)
                .generatePublic(new X509EncodedKeySpec(encodedPublic)));
        verifier.update(challenge.getBytes(StandardCharsets.UTF_8));
        byte[] signature;
        try {
            signature = Base64.decode(signatureBase64, Base64.DEFAULT);
        } catch (IllegalArgumentException error) {
            throw new SecurityException("Malformed administrator signature", error);
        }
        if (!verifier.verify(signature)) {
            throw new SecurityException("Invalid administrator signature");
        }
        return selected;
    }

    static JSONArray capabilities() throws Exception {
        JSONArray keys = new JSONArray();
        keys.put(keyValue(REOUTLOOK_ROOT_ID, "RSA-3072/SHA-256",
                REOUTLOOK_FINGERPRINT, "rebrowser:root,reoutlook:maintenance"));
        keys.put(keyValue(REBROWSER_ROOT_ID, "ECDSA-P256/SHA-256",
                REBROWSER_FINGERPRINT, "rebrowser:root"));
        return keys;
    }

    private static JSONObject keyValue(
            String id,
            String algorithm,
            String fingerprint,
            String scope
    ) throws Exception {
        JSONObject value = new JSONObject();
        value.put("keyId", id);
        value.put("algorithm", algorithm);
        value.put("fingerprintSha256", fingerprint);
        value.put("scope", scope);
        value.put("runtimeRevocable", false);
        return value;
    }
}
