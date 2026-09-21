package io.github.reoutlook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
public final class ReBrowserSitePermissionsTest {
    private ReBrowserSitePermissions permissions;
    private final String profile = "rebrowser_workspace_0123456789abcdef0123456789abcdef";

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("rebrowser_site_permissions_v1", Context.MODE_PRIVATE)
                .edit().clear().commit();
        permissions = new ReBrowserSitePermissions(context);
    }

    @Test
    public void defaultsArePrivacyBounded() {
        assertTrue(permissions.capabilityEnabled(ReBrowserSitePermissions.CLIPBOARD));
        assertTrue(permissions.capabilityEnabled(
                ReBrowserSitePermissions.APPROXIMATE_LOCATION));
        assertFalse(permissions.capabilityEnabled(ReBrowserSitePermissions.CAMERA));
        assertFalse(permissions.capabilityEnabled(ReBrowserSitePermissions.MICROPHONE));
        assertFalse(permissions.capabilityEnabled(ReBrowserSitePermissions.PRECISE_LOCATION));
        assertTrue(permissions.backgroundRuntimeEnabled());
        assertFalse(permissions.notificationsEnabled());
        assertFalse(permissions.backgroundPushEnabled());
        assertFalse(permissions.webSensorsEnabled());
    }

    @Test
    public void grantsAreBoundToProfileOriginAndDuration() {
        long now = 1_800_000_000_000L;
        assertTrue(permissions.grant(profile, "https://Example.COM:443/path",
                ReBrowserSitePermissions.CLIPBOARD,
                ReBrowserSitePermissions.FIVE_MINUTES_MS, now));
        assertTrue(permissions.hasGrant(profile, "https://example.com/other",
                ReBrowserSitePermissions.CLIPBOARD, now + 1));
        assertFalse(permissions.hasGrant(
                "rebrowser_workspace_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "https://example.com", ReBrowserSitePermissions.CLIPBOARD, now + 1));
        assertFalse(permissions.hasGrant(profile, "https://sub.example.com",
                ReBrowserSitePermissions.CLIPBOARD, now + 1));
        assertFalse(permissions.hasGrant(profile, "https://example.com",
                ReBrowserSitePermissions.CLIPBOARD,
                now + ReBrowserSitePermissions.FIVE_MINUTES_MS));
    }

    @Test
    public void disabledCapabilityImmediatelyDeletesItsGrants() {
        long now = System.currentTimeMillis();
        permissions.grant(profile, "https://example.com",
                ReBrowserSitePermissions.CLIPBOARD,
                ReBrowserSitePermissions.FIFTEEN_DAYS_MS, now);
        permissions.setCapabilityEnabled(ReBrowserSitePermissions.CLIPBOARD, false);
        assertFalse(permissions.hasGrant(profile, "https://example.com",
                ReBrowserSitePermissions.CLIPBOARD, now + 1));
        assertEquals(0, permissions.grants(now + 1).size());
        permissions.setCapabilityEnabled(ReBrowserSitePermissions.CLIPBOARD, true);
        assertFalse(permissions.hasGrant(profile, "https://example.com",
                ReBrowserSitePermissions.CLIPBOARD, now + 1));
    }

    @Test
    public void validationReportsAndRepairsInvalidAuthorizationMetadata() throws Exception {
        long now = 1_800_000_000_000L;
        JSONArray stored = new JSONArray()
                .put(grantJson(profile, "https://example.com",
                        ReBrowserSitePermissions.CLIPBOARD, now,
                        now + ReBrowserSitePermissions.FIVE_MINUTES_MS))
                .put(grantJson(profile, "https://example.com",
                        ReBrowserSitePermissions.CLIPBOARD, now,
                        now + ReBrowserSitePermissions.FIFTEEN_DAYS_MS))
                .put(grantJson(profile, "https://expired.example",
                        ReBrowserSitePermissions.CLIPBOARD,
                        now - ReBrowserSitePermissions.FIVE_MINUTES_MS, now))
                .put(grantJson(profile, "https://future.example",
                        ReBrowserSitePermissions.CLIPBOARD, now + 120_000L,
                        now + 120_000L + ReBrowserSitePermissions.FIVE_MINUTES_MS))
                .put(grantJson(profile, "https://camera.example",
                        ReBrowserSitePermissions.CAMERA, now,
                        now + ReBrowserSitePermissions.THREE_HOURS_MS))
                .put(new JSONObject().put("profileName", "Default"));
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("rebrowser_site_permissions_v1", Context.MODE_PRIVATE)
                .edit().putString("grants", stored.toString()).commit();

        ReBrowserSitePermissions.Validation validation = permissions.validate(now, false);
        assertFalse(validation.valid());
        assertEquals(6, validation.storedCount);
        assertEquals(1, validation.validCount);
        assertEquals(1, validation.malformedCount);
        assertEquals(1, validation.expiredCount);
        assertEquals(1, validation.futureCount);
        assertEquals(1, validation.disabledCount);
        assertEquals(1, validation.duplicateCount);

        ReBrowserSitePermissions.Validation repair = permissions.validate(now, true);
        assertTrue(repair.repaired);
        assertTrue(permissions.validate(now, false).valid());
        assertEquals(1, permissions.grants(now).size());
    }

    private static JSONObject grantJson(String profileName, String origin, String permission,
            long grantedAt, long expiresAt) throws Exception {
        return new JSONObject().put("profileName", profileName).put("origin", origin)
                .put("permission", permission).put("grantedAt", grantedAt)
                .put("expiresAt", expiresAt);
    }

    @Test
    public void invalidOriginsProfilesAndDurationsNeverGrant() {
        long now = System.currentTimeMillis();
        assertFalse(permissions.grant(profile, "http://example.com",
                ReBrowserSitePermissions.CLIPBOARD,
                ReBrowserSitePermissions.FIVE_MINUTES_MS, now));
        assertFalse(permissions.grant("Default", "https://example.com",
                ReBrowserSitePermissions.CLIPBOARD,
                ReBrowserSitePermissions.FIVE_MINUTES_MS, now));
        permissions.setCapabilityEnabled(ReBrowserSitePermissions.CAMERA, true);
        assertFalse(permissions.grant(profile, "https://example.com",
                ReBrowserSitePermissions.CAMERA,
                ReBrowserSitePermissions.FIVE_MINUTES_MS, now));
        assertTrue(permissions.grant(profile, "https://example.com",
                ReBrowserSitePermissions.CAMERA,
                ReBrowserSitePermissions.THREE_HOURS_MS, now));
    }
}
