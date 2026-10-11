package jp.kamei.worldstatesync.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class PlayerSession {
    final UUID playerId;
    final String playerName;
    final String initialScope;
    final byte[] initialPayload;
    final long acquireDeadlineNanos;
    CompletableFuture<Void> databaseTail = CompletableFuture.completedFuture(null);
    Mode mode = Mode.ACQUIRING;
    String activeScope;
    String desiredScope;
    long lastLeaseRenewalNanos;
    boolean initialImportAttempted;

    PlayerSession(
            UUID playerId,
            String playerName,
            String desiredScope,
            byte[] initialPayload,
            long acquireDeadlineNanos
    ) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.initialScope = desiredScope;
        this.initialPayload = initialPayload;
        this.desiredScope = desiredScope;
        this.acquireDeadlineNanos = acquireDeadlineNanos;
    }

    enum Mode {
        ACQUIRING,
        ACTIVE,
        SWITCHING,
        CLOSING
    }
}
