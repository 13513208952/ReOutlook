package io.github.reoutlook;

/** A conversation identity paired with Outlook's current change fingerprint. */
public final class ConversationCandidate {
    public final String id;
    public final String revision;

    public ConversationCandidate(String id, String revision) {
        this.id = id == null ? "" : id;
        this.revision = revision == null ? "" : revision;
    }
}
