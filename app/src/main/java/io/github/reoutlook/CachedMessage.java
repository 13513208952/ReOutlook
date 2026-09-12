package io.github.reoutlook;

public final class CachedMessage {
    public final long id;
    public final String sourceKey;
    public final String subject;
    public final String sender;
    public final String receivedAt;
    public final String bodyHtml;
    public final String bodyText;
    public final String sourceUrl;
    public final long cachedAt;

    public CachedMessage(
            long id,
            String sourceKey,
            String subject,
            String sender,
            String receivedAt,
            String bodyHtml,
            String bodyText,
            String sourceUrl,
            long cachedAt
    ) {
        this.id = id;
        this.sourceKey = sourceKey;
        this.subject = subject;
        this.sender = sender;
        this.receivedAt = receivedAt;
        this.bodyHtml = bodyHtml;
        this.bodyText = bodyText;
        this.sourceUrl = sourceUrl;
        this.cachedAt = cachedAt;
    }
}
