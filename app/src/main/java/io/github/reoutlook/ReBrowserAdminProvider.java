package io.github.reoutlook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

/** ADB-shell-only endpoint for issuing one-use ReBrowser control challenges. */
public final class ReBrowserAdminProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        Context context = attachedContext();
        if ("clearChallenge".equals(method)) {
            ReBrowserAdminAuthorizer.clearPendingChallenge(context);
            return Bundle.EMPTY;
        }
        if ("getLastResult".equals(method) || "getResult".equals(method)) {
            String value = "getResult".equals(method)
                    ? ReBrowserAdminProtocol.result(context, argument)
                    : ReBrowserAdminProtocol.lastResult(context);
            Bundle result = new Bundle();
            result.putString("payload", ReBrowserAdminProtocol.encode(value));
            return result;
        }
        if (!"createChallenge".equals(method)) {
            throw new IllegalArgumentException("Unsupported ReBrowser administrator method");
        }
        try {
            String challenge = ReBrowserAdminAuthorizer.createChallenge(context, argument);
            JSONObject last = new JSONObject(ReBrowserAdminProtocol.lastResult(context));
            Bundle result = new Bundle();
            result.putString("challenge", challenge);
            result.putString("requestId", last.optString("requestId"));
            result.putInt("authorizationLevel", last.optInt("authorizationLevel", 1));
            result.putString("administratorKeys",
                    "reoutlook-root-v1,rebrowser-root-v1");
            result.putString("authentication", last.optInt("authorizationLevel", 1) >= 3
                    ? "administrator-root-key"
                    : "administrator-root-key OR device-credential");
            result.putLong("expiresInSeconds", 180);
            return result;
        } catch (Exception error) {
            throw new SecurityException("Unable to create ReBrowser administrator challenge", error);
        }
    }

    private Context attachedContext() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider is not attached");
        return context;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
