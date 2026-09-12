package io.github.reoutlook;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/** Creates a streaming archive decryptable only by the offline administrator export key. */
public final class MaintenanceExporter {
    private static final byte[] MAGIC = "REOUTLOOK-ADMIN-1\n".getBytes(StandardCharsets.US_ASCII);

    private MaintenanceExporter() {}

    public static void exportActiveAccount(Context context, OutputStream destination) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey contentKey = generator.generateKey();
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);

        byte[] recipientKey = Base64.decode(
                MaintenanceKeys.EXPORT_RSA_X509_BASE64, Base64.DEFAULT);
        Cipher wrapping = Cipher.getInstance("RSA/ECB/OAEPPadding");
        wrapping.init(Cipher.ENCRYPT_MODE,
                KeyFactory.getInstance("RSA").generatePublic(
                        new X509EncodedKeySpec(recipientKey)),
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                        PSource.PSpecified.DEFAULT));
        byte[] wrappedKey = wrapping.doFinal(contentKey.getEncoded());

        JSONObject metadata = new JSONObject();
        metadata.put("format", "reoutlook-admin-export");
        metadata.put("version", 1);
        metadata.put("cipher", "AES-256-GCM");
        metadata.put("keyWrap", "RSA-3072-OAEP-SHA256");
        metadata.put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP));
        metadata.put("wrappedKey", Base64.encodeToString(wrappedKey, Base64.NO_WRAP));
        metadata.put("createdAt", System.currentTimeMillis());
        byte[] header = metadata.toString().getBytes(StandardCharsets.UTF_8);

        DataOutputStream framing = new DataOutputStream(destination);
        framing.write(MAGIC);
        framing.writeInt(header.length);
        framing.write(header);
        framing.flush();

        Cipher encryption = Cipher.getInstance("AES/GCM/NoPadding");
        encryption.init(Cipher.ENCRYPT_MODE, contentKey, new GCMParameterSpec(128, nonce));
        encryption.updateAAD(header);
        try (CipherOutputStream encrypted = new CipherOutputStream(destination, encryption);
             MailDatabase database = new MailDatabase(context)) {
            database.writeActiveAccountArchive(encrypted);
        }
    }
}
