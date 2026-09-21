package io.github.reoutlook;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Credential-protected, app-private mail cache. Android file-based encryption protects the file at
 * rest; every query is additionally scoped to one Outlook account.
 */
public final class MailDatabase extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "mail_cache.db";
    private static final int DATABASE_VERSION = 4;
    private static final String PREFERENCES = "account_preferences";
    private static final String ACTIVE_ACCOUNT = "active_account_key";
    private static final String LEGACY_ACCOUNT = "legacy-unassigned";

    private final SharedPreferences preferences;
    private volatile String activeAccountKey;

    public MailDatabase(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        activeAccountKey = preferences.getString(ACTIVE_ACCOUNT, LEGACY_ACCOUNT);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createAccountTable(db);
        createMessageTable(db);
        createConversationTable(db);
        insertLegacyAccount(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN remote_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("CREATE UNIQUE INDEX messages_remote_id ON messages(remote_id) " +
                    "WHERE remote_id <> ''");
            createLegacyConversationTable(db);
        }
        if (oldVersion < 3) {
            migrateToAccountIsolation(db);
        } else if (oldVersion < 4) {
            db.execSQL("ALTER TABLE synced_conversations ADD COLUMN " +
                    "remote_revision TEXT NOT NULL DEFAULT ''");
        }
    }

    private static void createAccountTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS accounts (" +
                "account_key TEXT PRIMARY KEY," +
                "identity_kind TEXT NOT NULL," +
                "identity_value TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "last_seen_at INTEGER NOT NULL" +
                ")");
    }

    private static void createMessageTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "account_key TEXT NOT NULL," +
                "source_key TEXT NOT NULL," +
                "remote_id TEXT NOT NULL DEFAULT ''," +
                "subject TEXT NOT NULL," +
                "sender TEXT NOT NULL," +
                "received_at TEXT NOT NULL," +
                "body_html TEXT NOT NULL," +
                "body_text TEXT NOT NULL," +
                "source_url TEXT NOT NULL," +
                "cached_at INTEGER NOT NULL," +
                "FOREIGN KEY(account_key) REFERENCES accounts(account_key) ON DELETE CASCADE" +
                ")");
        db.execSQL("CREATE UNIQUE INDEX messages_account_source " +
                "ON messages(account_key, source_key)");
        db.execSQL("CREATE UNIQUE INDEX messages_account_remote " +
                "ON messages(account_key, remote_id) WHERE remote_id <> ''");
        db.execSQL("CREATE INDEX messages_account_received " +
                "ON messages(account_key, received_at DESC, cached_at DESC)");
    }

    private static void createLegacyConversationTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS synced_conversations (" +
                "conversation_id TEXT PRIMARY KEY," +
                "synced_at INTEGER NOT NULL" +
                ")");
    }

    private static void createConversationTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE synced_conversations (" +
                "account_key TEXT NOT NULL," +
                "conversation_id TEXT NOT NULL," +
                "remote_revision TEXT NOT NULL DEFAULT ''," +
                "synced_at INTEGER NOT NULL," +
                "PRIMARY KEY(account_key, conversation_id)," +
                "FOREIGN KEY(account_key) REFERENCES accounts(account_key) ON DELETE CASCADE" +
                ")");
    }

    private static void insertLegacyAccount(SQLiteDatabase db) {
        long now = System.currentTimeMillis();
        db.execSQL("INSERT OR IGNORE INTO accounts " +
                        "(account_key, identity_kind, identity_value, created_at, last_seen_at) " +
                        "VALUES (?, 'legacy', '', ?, ?)",
                new Object[]{LEGACY_ACCOUNT, now, now});
    }

    private static void migrateToAccountIsolation(SQLiteDatabase db) {
        createAccountTable(db);
        insertLegacyAccount(db);

        db.execSQL("ALTER TABLE messages RENAME TO messages_v2");
        db.execSQL("DROP INDEX IF EXISTS messages_cached_at");
        db.execSQL("DROP INDEX IF EXISTS messages_remote_id");
        createMessageTable(db);
        db.execSQL("INSERT INTO messages (id, account_key, source_key, remote_id, subject, sender, " +
                "received_at, body_html, body_text, source_url, cached_at) " +
                "SELECT id, ?, source_key, remote_id, subject, sender, received_at, body_html, " +
                "body_text, source_url, cached_at FROM messages_v2", new Object[]{LEGACY_ACCOUNT});
        db.execSQL("DROP TABLE messages_v2");

        db.execSQL("ALTER TABLE synced_conversations RENAME TO synced_conversations_v2");
        createConversationTable(db);
        db.execSQL("INSERT INTO synced_conversations " +
                        "(account_key, conversation_id, remote_revision, synced_at) " +
                        "SELECT ?, conversation_id, '', synced_at FROM synced_conversations_v2",
                new Object[]{LEGACY_ACCOUNT});
        db.execSQL("DROP TABLE synced_conversations_v2");
    }

    /**
     * Activates the mailbox represented by Outlook's non-secret anchor mailbox header. Existing
     * pre-v3 rows are adopted only for the first identified account and can never be reassigned.
     */
    public String activateAccount(String accountHint) {
        return activateAccount(accountHint, "");
    }

    public String activateAccount(String accountHint, String previousAccountHint) {
        String identity = normalizeAccountHint(accountHint);
        if (identity.isBlank()) return activeAccountKey;
        String accountKey = sha256("outlook-anchor\n" + identity);
        if (accountKey.equals(activeAccountKey)) return accountKey;
        String previouslyActiveKey = activeAccountKey;
        String previousIdentity = normalizeAccountHint(previousAccountHint);
        String previousAccountKey = previousIdentity.isBlank()
                ? ""
                : sha256("outlook-anchor\n" + previousIdentity);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            boolean hasIdentifiedAccount;
            try (Cursor cursor = db.rawQuery(
                    "SELECT 1 FROM accounts WHERE account_key <> ? LIMIT 1",
                    new String[]{LEGACY_ACCOUNT})) {
                hasIdentifiedAccount = cursor.moveToFirst();
            }
            ContentValues account = new ContentValues();
            account.put("account_key", accountKey);
            account.put("identity_kind", "owa-anchor-mailbox");
            account.put("identity_value", identity);
            account.put("created_at", now);
            account.put("last_seen_at", now);
            db.insertWithOnConflict("accounts", null, account, SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues seen = new ContentValues();
            seen.put("identity_value", identity);
            seen.put("last_seen_at", now);
            db.update("accounts", seen, "account_key = ?", new String[]{accountKey});

            if (!hasIdentifiedAccount) {
                ContentValues adopted = new ContentValues();
                adopted.put("account_key", accountKey);
                db.update("messages", adopted, "account_key = ?", new String[]{LEGACY_ACCOUNT});
                db.update("synced_conversations", adopted,
                        "account_key = ?", new String[]{LEGACY_ACCOUNT});
                db.delete("accounts", "account_key = ?", new String[]{LEGACY_ACCOUNT});
            }
            if (!previousAccountKey.isBlank() && !previousAccountKey.equals(accountKey)) {
                mergeAccountAlias(db, previousAccountKey, accountKey);
            }
            if (previouslyActiveKey != null && !previouslyActiveKey.equals(accountKey)
                    && !previouslyActiveKey.equals(LEGACY_ACCOUNT)) {
                deleteAccountIfEmpty(db, previouslyActiveKey);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        activeAccountKey = accountKey;
        preferences.edit().putString(ACTIVE_ACCOUNT, accountKey).apply();
        return accountKey;
    }

    private static void mergeAccountAlias(
            SQLiteDatabase db,
            String aliasAccountKey,
            String canonicalAccountKey
    ) {
        db.execSQL("DELETE FROM messages AS alias WHERE alias.account_key = ? " +
                        "AND EXISTS (SELECT 1 FROM messages AS canonical " +
                        "WHERE canonical.account_key = ? " +
                        "AND canonical.source_key = alias.source_key)",
                new Object[]{aliasAccountKey, canonicalAccountKey});
        ContentValues account = new ContentValues();
        account.put("account_key", canonicalAccountKey);
        db.update("messages", account, "account_key = ?", new String[]{aliasAccountKey});
        db.execSQL("INSERT OR REPLACE INTO synced_conversations " +
                        "(account_key, conversation_id, remote_revision, synced_at) " +
                        "SELECT ?, conversation_id, remote_revision, synced_at " +
                        "FROM synced_conversations WHERE account_key = ?",
                new Object[]{canonicalAccountKey, aliasAccountKey});
        db.delete("synced_conversations", "account_key = ?", new String[]{aliasAccountKey});
        deleteAccountIfEmpty(db, aliasAccountKey);
    }

    private static void deleteAccountIfEmpty(SQLiteDatabase db, String accountKey) {
        db.execSQL("DELETE FROM accounts WHERE account_key = ? " +
                        "AND NOT EXISTS (SELECT 1 FROM messages WHERE account_key = ?) " +
                        "AND NOT EXISTS (SELECT 1 FROM synced_conversations WHERE account_key = ?)",
                new Object[]{accountKey, accountKey, accountKey});
    }

    public String activeAccountKey() {
        return activeAccountKey;
    }

    public String activeAccountIdentity() {
        try (Cursor cursor = getReadableDatabase().query(
                "accounts", new String[]{"identity_value"}, "account_key = ?",
                new String[]{activeAccountKey}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        }
    }

    public boolean upsert(
            String identity,
            String subject,
            String sender,
            String receivedAt,
            String bodyHtml,
            String bodyText,
            String sourceUrl
    ) {
        String accountKey = ensureActiveAccount();
        String remoteId = nonNull(identity, "");
        String sourceKey = sha256(remoteId.isBlank()
                ? subject + "\n" + sender + "\n" + receivedAt
                : remoteId);
        ContentValues values = new ContentValues();
        values.put("account_key", accountKey);
        values.put("source_key", sourceKey);
        values.put("remote_id", remoteId);
        values.put("subject", nonNull(subject, "（无主题）"));
        values.put("sender", nonNull(sender, "未知发件人"));
        values.put("received_at", nonNull(receivedAt, ""));
        values.put("body_html", nonNull(bodyHtml, ""));
        values.put("body_text", nonNull(bodyText, ""));
        values.put("source_url", nonNull(sourceUrl, ""));
        values.put("cached_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        if (!remoteId.isBlank()) {
            db.delete(
                    "messages",
                    "account_key = ? AND remote_id = '' AND subject = ? AND received_at = ?",
                    new String[]{accountKey, nonNull(subject, "（无主题）"),
                            nonNull(receivedAt, "")}
            );
        }
        return db.insertWithOnConflict(
                "messages", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1;
    }

    public List<CachedMessage> allMessages() {
        List<CachedMessage> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "messages",
                new String[]{
                        "id", "source_key", "subject", "sender", "received_at", "source_url",
                        "cached_at", "'' AS body_html", "'' AS body_text"
                },
                "account_key = ?",
                new String[]{ensureActiveAccount()},
                null,
                null,
                "received_at DESC, cached_at DESC"
        )) {
            while (cursor.moveToNext()) result.add(fromCursor(cursor));
        }
        return result;
    }

    public CachedMessage message(long id) {
        try (Cursor cursor = getReadableDatabase().query(
                "messages",
                null,
                "id = ? AND account_key = ?",
                new String[]{Long.toString(id), ensureActiveAccount()},
                null,
                null,
                null,
                "1"
        )) {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        }
    }

    public int count() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM messages WHERE account_key = ?",
                new String[]{ensureActiveAccount()})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public void cleanupLegacyDuplicates() {
        String accountKey = ensureActiveAccount();
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(
                "DELETE FROM messages AS legacy WHERE legacy.account_key = ? " +
                        "AND legacy.remote_id = '' " +
                        "AND EXISTS (SELECT 1 FROM messages AS current " +
                        "WHERE current.account_key = legacy.account_key " +
                        "AND current.remote_id <> '' " +
                        "AND current.subject = legacy.subject " +
                        "AND current.received_at = legacy.received_at)",
                new Object[]{accountKey}
        );
        db.execSQL("DELETE FROM accounts WHERE account_key <> ? AND account_key <> ? " +
                        "AND NOT EXISTS (SELECT 1 FROM messages " +
                        "WHERE messages.account_key = accounts.account_key) " +
                        "AND NOT EXISTS (SELECT 1 FROM synced_conversations " +
                        "WHERE synced_conversations.account_key = accounts.account_key)",
                new Object[]{accountKey, LEGACY_ACCOUNT});
    }

    public List<String> conversationIdsNeedingSync(List<ConversationCandidate> candidates) {
        if (candidates.isEmpty()) return List.of();
        String accountKey = ensureActiveAccount();
        Set<String> missing = new HashSet<>();
        SQLiteDatabase db = getReadableDatabase();
        for (ConversationCandidate candidate : candidates) {
            if (candidate.id.isBlank()) continue;
            try (Cursor cursor = db.query(
                    "synced_conversations",
                    new String[]{"remote_revision"},
                    "account_key = ? AND conversation_id = ?",
                    new String[]{accountKey, candidate.id},
                    null,
                    null,
                    null,
                    "1"
            )) {
                if (!cursor.moveToFirst()) {
                    missing.add(candidate.id);
                } else {
                    String storedRevision = cursor.getString(0);
                    if (!candidate.revision.isBlank()
                            && !candidate.revision.equals(storedRevision)) {
                        missing.add(candidate.id);
                    }
                }
            }
        }
        List<String> ordered = new ArrayList<>();
        for (ConversationCandidate candidate : candidates) {
            if (missing.contains(candidate.id) && !ordered.contains(candidate.id)) {
                ordered.add(candidate.id);
            }
        }
        return ordered;
    }

    public void markConversationSynced(String conversationId, String remoteRevision) {
        if (conversationId == null || conversationId.isBlank()) return;
        ContentValues values = new ContentValues();
        values.put("account_key", ensureActiveAccount());
        values.put("conversation_id", conversationId);
        values.put("remote_revision", nonNull(remoteRevision, ""));
        values.put("synced_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "synced_conversations", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Writes a consistent, account-scoped logical archive; the caller must encrypt destination. */
    public void writeActiveAccountArchive(OutputStream destination) throws IOException {
        String accountKey = ensureActiveAccount();
        SQLiteDatabase db = getReadableDatabase();
        db.beginTransactionNonExclusive();
        try (ZipOutputStream zip = new ZipOutputStream(destination, StandardCharsets.UTF_8)) {
            try {
                JSONObject manifest = new JSONObject();
                manifest.put("format", "reoutlook-account-archive");
                manifest.put("formatVersion", 1);
                manifest.put("databaseVersion", DATABASE_VERSION);
                manifest.put("createdAt", System.currentTimeMillis());
                manifest.put("accountKey", accountKey);
                manifest.put("accountIdentity", activeAccountIdentity());
                manifest.put("messageCount", count());
                writeZipText(zip, "manifest.json", manifest.toString());
            } catch (Exception error) {
                throw new IOException("Unable to create archive manifest", error);
            }
            writeRowsAsJsonLines(zip, "messages.jsonl", db,
                    "SELECT source_key, remote_id, subject, sender, received_at, body_html, " +
                            "body_text, source_url, cached_at FROM messages " +
                            "WHERE account_key = ? ORDER BY id",
                    new String[]{accountKey});
            writeRowsAsJsonLines(zip, "conversations.jsonl", db,
                    "SELECT conversation_id, remote_revision, synced_at " +
                            "FROM synced_conversations WHERE account_key = ?",
                    new String[]{accountKey});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void writeZipText(ZipOutputStream zip, String name, String value)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void writeRowsAsJsonLines(
            ZipOutputStream zip,
            String name,
            SQLiteDatabase db,
            String sql,
            String[] arguments
    ) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        try (Cursor cursor = db.rawQuery(sql, arguments)) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                try {
                    for (int index = 0; index < cursor.getColumnCount(); index++) {
                        String column = cursor.getColumnName(index);
                        switch (cursor.getType(index)) {
                            case Cursor.FIELD_TYPE_NULL:
                                row.put(column, JSONObject.NULL);
                                break;
                            case Cursor.FIELD_TYPE_INTEGER:
                                row.put(column, cursor.getLong(index));
                                break;
                            case Cursor.FIELD_TYPE_FLOAT:
                                row.put(column, cursor.getDouble(index));
                                break;
                            default:
                                row.put(column, cursor.getString(index));
                                break;
                        }
                    }
                } catch (Exception error) {
                    throw new IOException("Unable to encode " + name, error);
                }
                writer.write(row.toString());
                writer.newLine();
            }
            writer.flush();
            zip.closeEntry();
        }
    }

    private String ensureActiveAccount() {
        String key = activeAccountKey;
        if (key == null || key.isBlank()) key = LEGACY_ACCOUNT;
        if (LEGACY_ACCOUNT.equals(key)) {
            SQLiteDatabase db = getWritableDatabase();
            insertLegacyAccount(db);
        }
        return key;
    }

    private static CachedMessage fromCursor(Cursor cursor) {
        return new CachedMessage(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("source_key")),
                cursor.getString(cursor.getColumnIndexOrThrow("subject")),
                cursor.getString(cursor.getColumnIndexOrThrow("sender")),
                cursor.getString(cursor.getColumnIndexOrThrow("received_at")),
                cursor.getString(cursor.getColumnIndexOrThrow("body_html")),
                cursor.getString(cursor.getColumnIndexOrThrow("body_text")),
                cursor.getString(cursor.getColumnIndexOrThrow("source_url")),
                cursor.getLong(cursor.getColumnIndexOrThrow("cached_at"))
        );
    }

    private static String normalizeAccountHint(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.regionMatches(true, 0, "SMTP:", 0, 5)) {
            normalized = normalized.substring(5).trim();
        }
        if (normalized.isBlank() || normalized.length() > 512 || normalized.contains("\n")
                || normalized.contains("\r") || normalized.contains(" ")) return "";
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String nonNull(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) output.append(String.format(Locale.ROOT, "%02x", item));
            return output.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
