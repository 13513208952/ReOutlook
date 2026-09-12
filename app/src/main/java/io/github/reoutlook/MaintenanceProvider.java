package io.github.reoutlook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Shell-only endpoint used to create a signed, owner-approved maintenance challenge. */
public final class MaintenanceProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        if ("clearChallenge".equals(method)) {
            MaintenanceAuthorizer.clearPendingChallenge(attachedContext());
            return Bundle.EMPTY;
        }
        if (!"createChallenge".equals(method)) {
            throw new IllegalArgumentException("Unsupported maintenance method");
        }
        try {
            String challenge = MaintenanceAuthorizer.createChallenge(attachedContext(), argument);
            Bundle result = new Bundle();
            result.putString("challenge", challenge);
            result.putString("administratorKey", "rsa3072:0c045556f779ddc4");
            result.putLong("expiresInSeconds", 180);
            return result;
        } catch (Exception error) {
            throw new SecurityException("Unable to create maintenance challenge", error);
        }
    }

    private android.content.Context attachedContext() {
        android.content.Context context = getContext();
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
