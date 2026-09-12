package io.github.reoutlook;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;

/** Exercises the encrypted exporter without weakening the production owner-consent path. */
public final class MaintenanceExportInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
               super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    @Override
    public void onStart() {
        if ("staleCheckpoint".equals(arguments.getString("mode"))) {
            markLatestCheckpointStale();
            return;
        }
        Bundle result = new Bundle();
        File output = new File(getTargetContext().getCacheDir(),
                "maintenance-export-instrumentation.reoutlook-admin");
        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            MaintenanceExporter.exportActiveAccount(getTargetContext(), stream);
            result.putString("exportPath", output.getAbsolutePath());
            result.putLong("exportBytes", output.length());
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void markLatestCheckpointStale() {
        Bundle result = new Bundle();
        File databaseFile = getTargetContext().getDatabasePath("mail_cache.db");
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(
                databaseFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
             Cursor cursor = database.query(
                     "synced_conversations",
                     new String[]{"account_key", "conversation_id", "remote_revision"},
                     "remote_revision <> ''",
                     null,
                     null,
                     null,
                     "synced_at DESC",
                     "1")) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("No revised checkpoint");
            String accountKey = cursor.getString(0);
            String conversationId = cursor.getString(1);
            String originalRevision = cursor.getString(2);
            ContentValues stale = new ContentValues();
            stale.put("remote_revision", "instrumentation-forced-stale");
            int changed = database.update(
                    "synced_conversations",
                    stale,
                    "account_key = ? AND conversation_id = ?",
                    new String[]{accountKey, conversationId});
            if (changed != 1) throw new IllegalStateException("Checkpoint update failed");
            result.putString("conversationId", conversationId);
            result.putString("originalRevision", originalRevision);
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
