package io.github.reoutlook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded website permission request; its responder never exposes WebView objects to the UI. */
final class ReBrowserPermissionRequest {
    interface Responder {
        void approve(long durationMs);
        void deny(String reason);
    }

    final String profileName;
    final String tabId;
    final String origin;
    final List<String> permissions;
    final long shortDurationMs;
    final long longDurationMs;
    final String shortLabel;
    final String longLabel;
    final List<String> androidPermissions;
    private final AtomicBoolean completed = new AtomicBoolean();
    private final Responder responder;

    ReBrowserPermissionRequest(
            String profileName,
            String tabId,
            String origin,
            List<String> permissions,
            long shortDurationMs,
            long longDurationMs,
            String shortLabel,
            String longLabel,
            List<String> androidPermissions,
            Responder responder
    ) {
        this.profileName = profileName;
        this.tabId = tabId;
        this.origin = origin;
        this.permissions = Collections.unmodifiableList(new ArrayList<>(permissions));
        this.shortDurationMs = shortDurationMs;
        this.longDurationMs = longDurationMs;
        this.shortLabel = shortLabel;
        this.longLabel = longLabel;
        this.androidPermissions = Collections.unmodifiableList(
                new ArrayList<>(androidPermissions));
        this.responder = responder;
    }

    boolean approve(long durationMs) {
        if (durationMs != shortDurationMs && durationMs != longDurationMs) return false;
        if (!completed.compareAndSet(false, true)) return false;
        responder.approve(durationMs);
        return true;
    }

    boolean deny(String reason) {
        if (!completed.compareAndSet(false, true)) return false;
        responder.deny(reason == null ? "denied" : reason);
        return true;
    }

    boolean isCompleted() {
        return completed.get();
    }
}
