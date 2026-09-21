package io.github.reoutlook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ReBrowserAdminProtocolTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("rebrowser_admin_protocol_v2", Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences("rebrowser_admin_state_v1", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void authorizationLevelsAndOwnerBoundaryAreStable() throws Exception {
        assertEquals(1, ReBrowserAdminProtocol.authorizationLevel(
                ReBrowserAdminProtocol.OP_GET_STATE));
        assertEquals(2, ReBrowserAdminProtocol.authorizationLevel(
                ReBrowserAdminProtocol.OP_CLOSE_TAB));
        assertEquals(3, ReBrowserAdminProtocol.authorizationLevel(
                ReBrowserAdminProtocol.OP_REPAIR_STATE));
        assertEquals(3, ReBrowserAdminProtocol.authorizationLevel(
                ReBrowserAdminProtocol.OP_REPAIR_DOWNLOADS));
        assertEquals(3, ReBrowserAdminProtocol.authorizationLevel(
                ReBrowserAdminProtocol.OP_CLEAR_DOWNLOADS));
        assertEquals(1, ReBrowserAdminProtocol.authorizationLevel(
                ReBrowserAdminProtocol.OP_GET_SITE_PERMISSIONS));
        assertEquals(2, ReBrowserAdminProtocol.authorizationLevel(
                ReBrowserAdminProtocol.OP_DISABLE_SITE_PERMISSION));
        assertEquals(3, ReBrowserAdminProtocol.authorizationLevel(
                ReBrowserAdminProtocol.OP_CLEAR_SITE_PERMISSION_GRANTS));

        JSONObject levelTwo = prepared(ReBrowserAdminProtocol.OP_SET_DOWNLOAD_POLICY)
                .put("downloadsEnabled", true);
        ReBrowserAdminProtocol.prepareExternalRequest(levelTwo);
        assertTrue(ReBrowserAdminProtocol.ownerCredentialAllowed(levelTwo));

        JSONObject levelThree = prepared(ReBrowserAdminProtocol.OP_REPAIR_STATE);
        ReBrowserAdminProtocol.prepareExternalRequest(levelThree);
        assertFalse(ReBrowserAdminProtocol.ownerCredentialAllowed(levelThree));
    }

    @Test
    public void requestValidationRejectsUnboundedOrAmbiguousControl() throws Exception {
        assertThrows(SecurityException.class, () -> ReBrowserAdminProtocol.prepareExternalRequest(
                prepared(ReBrowserAdminProtocol.OP_GET_STATE).put("_authentication", "forged")));
        assertThrows(SecurityException.class, () -> ReBrowserAdminProtocol.prepareExternalRequest(
                prepared(ReBrowserAdminProtocol.OP_OPEN_URL).put("url", "javascript:alert(1)")));
        assertThrows(SecurityException.class, () -> ReBrowserAdminProtocol.prepareExternalRequest(
                prepared(ReBrowserAdminProtocol.OP_ACTIVATE_WORKSPACE)
                        .put("workspaceId", "not-an-object-id")));
        assertThrows(SecurityException.class, () -> ReBrowserAdminProtocol.prepareExternalRequest(
                prepared(ReBrowserAdminProtocol.OP_CLOSE_WORKSPACE)
                        .put("workspaceId", id('a'))));
        assertThrows(SecurityException.class, () -> ReBrowserAdminProtocol.prepareExternalRequest(
                prepared(ReBrowserAdminProtocol.OP_RELOAD).put("waitForLoad", "yes")));
        assertThrows(SecurityException.class, () -> ReBrowserAdminProtocol.prepareExternalRequest(
                prepared(ReBrowserAdminProtocol.OP_RELOAD).put("timeoutSeconds", 61)));
        assertThrows(SecurityException.class, () -> ReBrowserAdminProtocol.prepareExternalRequest(
                prepared(ReBrowserAdminProtocol.OP_SET_PREFERENCE)
                        .put("name", "arbitraryScript").put("value", "alert(1)")));
        assertThrows(SecurityException.class, () -> ReBrowserAdminProtocol.prepareExternalRequest(
                prepared(ReBrowserAdminProtocol.OP_DISABLE_SITE_PERMISSION)
                        .put("permission", "enable-camera")));
        assertThrows(SecurityException.class, () -> ReBrowserAdminProtocol.prepareExternalRequest(
                prepared(ReBrowserAdminProtocol.OP_DISABLE_SITE_PERMISSION)));
        assertThrows(SecurityException.class, () -> ReBrowserAdminProtocol.prepareExternalRequest(
                prepared(ReBrowserAdminProtocol.OP_CLEAR_SITE_PERMISSION_GRANTS)));

        JSONObject disablePermission = prepared(
                ReBrowserAdminProtocol.OP_DISABLE_SITE_PERMISSION)
                .put("permission", ReBrowserSitePermissions.CAMERA);
        ReBrowserAdminProtocol.prepareExternalRequest(disablePermission);
        assertEquals(2, disablePermission.getInt("authorizationLevel"));
        JSONObject clearPermissions = prepared(
                ReBrowserAdminProtocol.OP_CLEAR_SITE_PERMISSION_GRANTS)
                .put("permission", ReBrowserSitePermissions.CLIPBOARD)
                .put("confirmDelete", true);
        ReBrowserAdminProtocol.prepareExternalRequest(clearPermissions);
        assertEquals(3, clearPermissions.getInt("authorizationLevel"));

        JSONObject valid = prepared(ReBrowserAdminProtocol.OP_CLOSE_WORKSPACE)
                .put("workspaceId", id('b')).put("confirmDelete", true);
        ReBrowserAdminProtocol.prepareExternalRequest(valid);
        assertEquals(2, valid.getInt("authorizationLevel"));
    }

    @Test
    public void ownerChallengeIsSingleUseAndCannotAuthorizeLevelThree() throws Exception {
        JSONObject request = prepared(ReBrowserAdminProtocol.OP_GET_DIAGNOSTICS);
        String challenge = ReBrowserAdminAuthorizer.createChallenge(
                context, ReBrowserAdminProtocol.encode(request.toString()));
        JSONObject authorized = ReBrowserAdminAuthorizer.authorizeWithOwnerCredential(
                context, challenge);
        assertEquals("device-credential", authorized.getString("_authentication"));
        assertThrows(SecurityException.class,
                () -> ReBrowserAdminAuthorizer.inspectPending(context, challenge));

        JSONObject repair = prepared(ReBrowserAdminProtocol.OP_REPAIR_STATE);
        String repairChallenge = ReBrowserAdminAuthorizer.createChallenge(
                context, ReBrowserAdminProtocol.encode(repair.toString()));
        assertThrows(SecurityException.class,
                () -> ReBrowserAdminAuthorizer.authorizeWithOwnerCredential(
                        context, repairChallenge));
        assertEquals(ReBrowserAdminProtocol.OP_REPAIR_STATE,
                ReBrowserAdminAuthorizer.inspectPending(context, repairChallenge)
                        .getString("operation"));

        JSONObject clearPermissions = prepared(
                ReBrowserAdminProtocol.OP_CLEAR_SITE_PERMISSION_GRANTS)
                .put("confirmDelete", true);
        String clearChallenge = ReBrowserAdminAuthorizer.createChallenge(
                context, ReBrowserAdminProtocol.encode(clearPermissions.toString()));
        assertThrows(SecurityException.class,
                () -> ReBrowserAdminAuthorizer.authorizeWithOwnerCredential(
                        context, clearChallenge));
    }

    @Test
    public void auditIsBoundedAndNeverCopiesUrlOrResultDetails() throws Exception {
        String secret = "token=must-not-enter-audit";
        for (int index = 0; index < 105; index++) {
            JSONObject request = prepared(ReBrowserAdminProtocol.OP_OPEN_URL)
                    .put("requestId", String.format("request_%03d", index))
                    .put("url", "https://example.com/download?" + secret);
            ReBrowserAdminProtocol.prepareExternalRequest(request);
            ReBrowserAdminProtocol.recordStatus(context, request, "completed",
                    "administrator-key", "rebrowser-root-v1",
                    new JSONObject().put("origin", "https://example.com"), null);
        }
        JSONArray audit = ReBrowserAdminProtocol.audit(context);
        assertEquals(100, audit.length());
        String serialized = audit.toString();
        assertFalse(serialized.contains(secret));
        assertFalse(serialized.contains("https://example.com/download"));
        assertFalse(serialized.contains("details"));
        assertEquals("request_104", audit.getJSONObject(0).getString("requestId"));
    }

    private static JSONObject prepared(String operation) throws Exception {
        return new JSONObject()
                .put("protocolVersion", ReBrowserAdminProtocol.VERSION)
                .put("requestId", "request_12345678")
                .put("operation", operation);
    }

    private static String id(char value) {
        return String.valueOf(value).repeat(32);
    }
}
